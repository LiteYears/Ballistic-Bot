package com.olympquant.terminal.engine

import com.olympquant.terminal.domain.Candle
import com.olympquant.terminal.domain.PsychGridBounceSignal
import com.olympquant.terminal.domain.SRZone
import com.olympquant.terminal.domain.SignalDirection
import com.olympquant.terminal.domain.SwingPoint
import com.olympquant.terminal.domain.SwingType
import com.olympquant.terminal.domain.Tick
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Aggregates live ticks into 5-second OHLC candles with a 300-candle sliding window
 * and out-of-order timestamp routing.
 */
class CandleEngine(
    private val candleSizeSec: Long = 5L,
    private val maxCandles: Int = 300
) {
    private val completedCandles = ArrayDeque<Candle>(maxCandles + 4)
    private var currentCandle: Candle? = null

    fun reset() {
        completedCandles.clear()
        currentCandle = null
    }

    fun seedHistoricalCandles(candles: List<Candle>) {
        completedCandles.clear()
        val sorted = candles.sortedBy { it.timeSec }.takeLast(maxCandles)
        completedCandles.addAll(sorted)
        currentCandle = null
    }

    fun upsertServerCandle(serverCandle: Candle): List<Candle> {
        val bucketSec = (serverCandle.timeSec / candleSizeSec) * candleSizeSec
        val normalized = serverCandle.copy(timeSec = bucketSec)
        val active = currentCandle
        if (active == null || bucketSec == active.timeSec) {
            currentCandle = if (active == null) {
                normalized
            } else {
                active.copy(
                    open = normalized.open,
                    high = max(active.high, normalized.high),
                    low = min(active.low, normalized.low),
                    close = normalized.close,
                    tickCount = max(1, max(active.tickCount, normalized.tickCount))
                )
            }
        } else if (bucketSec > active.timeSec) {
            completedCandles.addLast(active)
            while (completedCandles.size > maxCandles) {
                completedCandles.removeFirst()
            }
            currentCandle = normalized
        } else {
            val idx = completedCandles.indexOfLast { it.timeSec == bucketSec }
            if (idx >= 0) {
                completedCandles[idx] = normalized
            }
        }
        return getAllCandles()
    }

    fun processTick(tick: Tick): List<Candle> {
        val bucketSec = (tick.timeMs / 1000L / candleSizeSec) * candleSizeSec
        val active = currentCandle

        if (active == null) {
            currentCandle = Candle(
                timeSec = bucketSec,
                open = tick.price,
                high = tick.price,
                low = tick.price,
                close = tick.price,
                tickCount = 1
            )
        } else if (bucketSec == active.timeSec) {
            currentCandle = active.copy(
                high = max(active.high, tick.price),
                low = min(active.low, tick.price),
                close = tick.price,
                tickCount = active.tickCount + 1
            )
        } else if (bucketSec > active.timeSec) {
            completedCandles.addLast(active)
            while (completedCandles.size > maxCandles) {
                completedCandles.removeFirst()
            }
            currentCandle = Candle(
                timeSec = bucketSec,
                open = tick.price,
                high = tick.price,
                low = tick.price,
                close = tick.price,
                tickCount = 1
            )
        } else {
            // Out-of-order packet routing: update matching historical bucket without corrupting active candle
            val idx = completedCandles.indexOfLast { it.timeSec == bucketSec }
            if (idx >= 0) {
                val old = completedCandles[idx]
                completedCandles[idx] = old.copy(
                    high = max(old.high, tick.price),
                    low = min(old.low, tick.price),
                    close = old.close,
                    tickCount = old.tickCount + 1
                )
            }
        }
        return getAllCandles()
    }

    fun getAllCandles(): List<Candle> {
        val list = ArrayList<Candle>(completedCandles.size + 1)
        list.addAll(completedCandles)
        currentCandle?.let { list.add(it) }
        return list
    }

    fun getAverageCandleRange(lookback: Int = 40): Double {
        val slice = completedCandles.takeLast(lookback)
        if (slice.isEmpty()) return 0.05
        return slice.sumOf { (it.high - it.low).coerceAtLeast(1e-6) } / slice.size
    }
}

/**
 * Detects fractal Swing Highs and Swing Lows and prunes stale swings outside the 300-candle window.
 */
class StructureEngine {
    private val swings = mutableListOf<SwingPoint>()

    fun reset() {
        swings.clear()
    }

    fun updateSwings(candles: List<Candle>): List<SwingPoint> {
        if (candles.size < 5) return emptyList()
        swings.clear()
        val oldestAllowedSec = candles.first().timeSec
        val n = candles.size
        for (i in 2 until n - 2) {
            val c = candles[i]
            if (c.timeSec < oldestAllowedSec) continue
            val isSwingHigh = c.high > candles[i - 1].high &&
                c.high > candles[i - 2].high &&
                c.high >= candles[i + 1].high &&
                c.high >= candles[i + 2].high
            val isSwingLow = c.low < candles[i - 1].low &&
                c.low < candles[i - 2].low &&
                c.low <= candles[i + 1].low &&
                c.low <= candles[i + 2].low

            if (isSwingHigh) {
                swings.add(SwingPoint(SwingType.HIGH, c.high, c.timeSec, strength = 2))
            }
            if (isSwingLow) {
                swings.add(SwingPoint(SwingType.LOW, c.low, c.timeSec, strength = 2))
            }
        }
        return swings.takeLast(30)
    }
}

/**
 * Psychological Round-Number Grid Tracker & Bounce Detector.
 * Detects when price enters a psychological level's tolerance zone and bounces (exits the same side it entered).
 */
class LevelEngine {
    private var activeZoneLevel: Double? = null
    private var entryApproachSide: Int = 0 // -1 entered from below, +1 entered from above
    private var lastBounceSignal: PsychGridBounceSignal? = null

    fun reset() {
        activeZoneLevel = null
        entryApproachSide = 0
        lastBounceSignal = null
    }

    fun computeGridSpacing(price: Double, avgRange: Double): Double {
        return when {
            price > 1000.0 -> max(1.0, round(avgRange * 5.0))
            price > 50.0 -> 0.50
            price > 5.0 -> 0.05
            else -> 0.0010
        }
    }

    fun getNearbyLevels(price: Double, spacing: Double): List<Double> {
        if (price <= 0.0 || spacing <= 0.0) return emptyList()
        val base = floor(price / spacing) * spacing
        return (-3..3).map { i -> base + i * spacing }
    }

    fun processTick(prevPrice: Double, tick: Tick, avgRange: Double): PsychGridBounceSignal? {
        // Expire stale psychological grid bounce signals after 30 seconds
        lastBounceSignal?.let { existing ->
            if (tick.timeMs > existing.timestampMs && (tick.timeMs - existing.timestampMs) > 30_000L) {
                lastBounceSignal = null
            }
        }
        if (prevPrice <= 0.0) return lastBounceSignal
        val spacing = computeGridSpacing(tick.price, avgRange)
        val tolerance = spacing * 0.16
        val nearestLevel = round(tick.price / spacing) * spacing
        val distance = tick.price - nearestLevel

        val currentZone = activeZoneLevel
        if (currentZone == null) {
            if (abs(distance) <= tolerance) {
                activeZoneLevel = nearestLevel
                entryApproachSide = if (prevPrice < nearestLevel) -1 else 1
            }
        } else {
            val distFromTracked = tick.price - currentZone
            if (abs(distFromTracked) > tolerance) {
                val exitSide = if (tick.price < currentZone) -1 else 1
                if (exitSide == entryApproachSide && entryApproachSide != 0) {
                    // Exited the same side it entered -> Confirmed Round-Number Bounce
                    val bounceDir = if (exitSide == 1) SignalDirection.UP else SignalDirection.DOWN
                    lastBounceSignal = PsychGridBounceSignal(
                        levelPrice = currentZone,
                        direction = bounceDir,
                        timestampMs = tick.timeMs
                    )
                }
                activeZoneLevel = null
                entryApproachSide = 0
            }
        }
        return lastBounceSignal
    }
}

/**
 * Consolidated Support & Resistance Memory Zone Engine with Adaptive Corridor Width:
 * max(scaleFloor, 4.0 * AverageCandleRange) scaled to asset price magnitude.
 */
class SRMemoryEngine {
    fun computeAdaptiveCorridor(avgCandleRange: Double, price: Double): Double {
        val rawCorridor = 4.0 * avgCandleRange
        val minFloor = when {
            price > 100.0 -> 0.20
            price in 0.01..15.0 -> max(0.00020, avgCandleRange * 1.5)
            else -> max(0.02, avgCandleRange * 1.5)
        }
        return max(minFloor, rawCorridor)
    }

    fun buildConsolidatedZones(
        swings: List<SwingPoint>,
        currentPrice: Double,
        corridorWidth: Double
    ): Pair<SRZone?, SRZone?> {
        if (swings.isEmpty()) return null to null
        val clusters = mutableListOf<MutableList<SwingPoint>>()
        val halfBand = (corridorWidth * 0.5).coerceAtLeast(1e-6)

        for (swing in swings) {
            val match = clusters.firstOrNull { cluster ->
                val avg = cluster.sumOf { it.price } / cluster.size
                abs(swing.price - avg) <= halfBand
            }
            if (match != null) {
                match.add(swing)
            } else {
                clusters.add(mutableListOf(swing))
            }
        }

        val zones = clusters.map { cluster ->
            val center = cluster.sumOf { it.price } / cluster.size
            val lastTouch = cluster.maxOf { it.candleTimeSec }
            SRZone(
                centerPrice = center,
                lowerBound = center - halfBand,
                upperBound = center + halfBand,
                touchCount = cluster.size,
                lastTouchTimeSec = lastTouch,
                isSupport = center <= currentPrice
            )
        }

        val nearestSupport = zones
            .filter { it.centerPrice <= currentPrice }
            .minByOrNull { abs(currentPrice - it.centerPrice) }

        val nearestResistance = zones
            .filter { it.centerPrice > currentPrice }
            .minByOrNull { abs(it.centerPrice - currentPrice) }

        return nearestSupport to nearestResistance
    }
}

/**
 * Monotonic Trend Age Engine with out-of-order timestamp guard.
 */
class TrendAgeEngine {
    private var currentTrend: SignalDirection = SignalDirection.NONE
    private var trendStartMs: Long = 0L

    fun reset() {
        currentTrend = SignalDirection.NONE
        trendStartMs = 0L
    }

    fun update(trend: SignalDirection, tickTimeMs: Long): Double {
        if (trend == SignalDirection.NONE) {
            currentTrend = SignalDirection.NONE
            trendStartMs = tickTimeMs
            return 0.0
        }
        if (trend != currentTrend || trendStartMs == 0L) {
            currentTrend = trend
            trendStartMs = tickTimeMs
            return 0.0
        }
        return max(0.0, (tickTimeMs - trendStartMs) / 1000.0)
    }
}
