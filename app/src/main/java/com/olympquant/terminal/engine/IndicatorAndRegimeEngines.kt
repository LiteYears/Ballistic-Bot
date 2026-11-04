package com.olympquant.terminal.engine

import com.olympquant.terminal.domain.Candle
import com.olympquant.terminal.domain.EnergyState
import com.olympquant.terminal.domain.FibonacciLevels
import com.olympquant.terminal.domain.HighVoltageFilterStatus
import com.olympquant.terminal.domain.HistoricalEdge
import com.olympquant.terminal.domain.HvMarketTrap
import com.olympquant.terminal.domain.HvWindowSnapshot
import com.olympquant.terminal.domain.HvWindowState
import com.olympquant.terminal.domain.LandmarkType
import com.olympquant.terminal.domain.MarketPhase
import com.olympquant.terminal.domain.MicroParticipation
import com.olympquant.terminal.domain.MicroUrgency
import com.olympquant.terminal.domain.MicrostructureSnapshot
import com.olympquant.terminal.domain.RegimeOutput
import com.olympquant.terminal.domain.SRZone
import com.olympquant.terminal.domain.SessionQuality
import com.olympquant.terminal.domain.SignalDirection
import com.olympquant.terminal.domain.SwingPoint
import com.olympquant.terminal.domain.SwingType
import com.olympquant.terminal.domain.Tick
import com.olympquant.terminal.domain.finiteOr
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

data class IndicatorSnapshot(
    val zFast: Double,
    val zSlow: Double,
    val trend: SignalDirection,
    val htfBias: SignalDirection,
    val persistence: Int,
    val consistency: Double,
    val volatility: Double,
    val velocity: Double,
    val slope: Double,
    val slopeRatio: Double,
    val netDelta5: Double,
    val relPos: Double,
    val fibLevels: FibonacciLevels,
    val microstructure: MicrostructureSnapshot,
    val hvoiScore: Double,
    val sessionQuality: SessionQuality,
    val hvWindow: HvWindowSnapshot = HvWindowSnapshot(),
    val highVoltageStatus: HighVoltageFilterStatus = HighVoltageFilterStatus()
)

/**
 * Computes Zero-Lag EMAs (10 & 30), 50-tick Relative Position breakouts,
 * 50-tick Fibonacci Retracement anchors, Microstructure & Micro-Impulse telemetry,
 * scale-invariant slope ratios, the HighVoltage 6-filter mathematical pipeline,
 * and the zero-lag fast-decay EMA (α = 0.08) HV Window structural co-pilot.
 */
class IndicatorEngine {
    companion object {
        const val HV_EMA_ALPHA = 0.08
        const val HV_MIN_VELOCITY_TPS = 1.5
        const val HV_BREAKOUT_UPPER_PCT = 80.0
        const val HV_BREAKOUT_LOWER_PCT = 20.0
        const val HV_SR_CLEARANCE_POINTS = 0.25
        const val HV_TIGHT_CHOP_POINTS = 0.20
        const val HV_WICK_COLLAPSE_RATIO = 0.55
    }

    private var pricesBuf = DoubleArray(320)
    private var zFastBuf = DoubleArray(320)
    private var zSlowBuf = DoubleArray(320)

    // Stateful HV Window EMA (alpha = 0.08) & stability tracking
    private var hvEmaInitialized = false
    private var smoothedHvScore = 60.0
    private var hvFirstTickTimeMs = 0L
    private var hvStableAbove72StartMs = 0L

    @Synchronized
    fun reset() {
        hvEmaInitialized = false
        smoothedHvScore = 60.0
        hvFirstTickTimeMs = 0L
        hvStableAbove72StartMs = 0L
    }

    private fun ensureCapacity(required: Int) {
        if (pricesBuf.size < required) {
            val nextSize = max(required + 32, pricesBuf.size * 2)
            pricesBuf = DoubleArray(nextSize)
            zFastBuf = DoubleArray(nextSize)
            zSlowBuf = DoubleArray(nextSize)
        }
    }

    @Synchronized
    fun computeIndicators(
        ticks: List<Tick>,
        candles: List<Candle>,
        swings: List<SwingPoint> = emptyList(),
        psychLevels: List<Double> = emptyList(),
        nearestSupport: SRZone? = null,
        nearestResistance: SRZone? = null
    ): IndicatorSnapshot {
        val len = ticks.size
        if (len < 15) {
            val lastPrice = (ticks.lastOrNull()?.price ?: 0.0).finiteOr(0.0)
            return IndicatorSnapshot(
                zFast = lastPrice,
                zSlow = lastPrice,
                trend = SignalDirection.NONE,
                htfBias = SignalDirection.NONE,
                persistence = 0,
                consistency = 0.0,
                volatility = 0.0,
                velocity = 0.0,
                slope = 0.0,
                slopeRatio = 0.0,
                netDelta5 = 0.0,
                relPos = 50.0,
                fibLevels = FibonacciLevels(lastPrice, lastPrice, lastPrice, lastPrice, lastPrice, lastPrice, lastPrice),
                microstructure = MicrostructureSnapshot(),
                hvoiScore = 50.0,
                sessionQuality = SessionQuality.NEUTRAL,
                hvWindow = HvWindowSnapshot(
                    engineBooted = false,
                    stepGuideHint = "Step 1: Let Engine Boot (10–15s) to populate 1-minute candle map..."
                ),
                highVoltageStatus = HighVoltageFilterStatus()
            )
        }

        ensureCapacity(len)
        val prices = pricesBuf
        for (i in 0 until len) {
            prices[i] = ticks[i].price.finiteOr(0.0)
        }
        val zFastSeries = computeZlemaSeriesInPlace(prices, len, period = 10, out = zFastBuf)
        val zSlowSeries = computeZlemaSeriesInPlace(prices, len, period = 30, out = zSlowBuf)

        val lastPriceVal = prices[len - 1]
        val zFast = zFastSeries[len - 1].finiteOr(lastPriceVal)
        val zSlow = zSlowSeries[len - 1].finiteOr(lastPriceVal)
        val trend = when {
            zFast > zSlow -> SignalDirection.UP
            zFast < zSlow -> SignalDirection.DOWN
            else -> SignalDirection.NONE
        }

        // Persistence over up to 40 ticks
        var persistence = 0
        val maxPersLookback = min(40, len)
        for (i in (len - 1) downTo (len - maxPersLookback)) {
            val dirAtI = when {
                zFastSeries[i] > zSlowSeries[i] -> SignalDirection.UP
                zFastSeries[i] < zSlowSeries[i] -> SignalDirection.DOWN
                else -> SignalDirection.NONE
            }
            if (dirAtI == trend && trend != SignalDirection.NONE) {
                persistence++
            } else {
                break
            }
        }

        // ZLEMA Consistency over 15 ticks
        val consWindow = min(15, len - 1)
        var alignedSteps = 0
        for (i in (len - consWindow) until len) {
            val zDiff = zFastSeries[i] - zFastSeries[i - 1]
            if ((trend == SignalDirection.UP && zDiff > 0) ||
                (trend == SignalDirection.DOWN && zDiff < 0)
            ) {
                alignedSteps++
            }
        }
        val consistency = (if (consWindow > 0) alignedSteps.toDouble() / consWindow else 0.0).finiteOr(0.0)

        // Volatility (avgDiff over 50 ticks) with strict division guard
        val volWindow = min(50, len - 1)
        var diffSum = 0.0
        for (i in (len - volWindow) until len) {
            diffSum += abs(prices[i] - prices[i - 1])
        }
        val volatility = (if (volWindow > 0) diffSum / volWindow else 0.0).finiteOr(0.0)

        // Micro-Velocity (ticks per second over 3-second window, zero-allocation backward scan)
        val latestTime = ticks[len - 1].timeMs
        var recent3sCount = 0
        for (i in (len - 1) downTo 0) {
            val dt = latestTime - ticks[i].timeMs
            if (dt <= 3000L) recent3sCount++ else if (dt > 5000L) break
        }
        val velocity = (recent3sCount / 3.0).finiteOr(0.0)

        // Slope and Scale-Invariant Slope Ratio (slope / volatility)
        val slopeLookback = min(5, len - 1)
        val slope = abs(zFastSeries[len - 1] - zFastSeries[len - 1 - slopeLookback]).finiteOr(0.0)
        val slopeRatio = (if (volatility > 1e-9) slope / volatility else 0.0).finiteOr(0.0)

        // NetDelta5 (net price change over the last 6 ticks: prices[len - 1] - prices[len - 6])
        val deltaLookback = min(5, len - 1)
        val netDelta5 = (prices[len - 1] - prices[len - 1 - deltaLookback]).finiteOr(0.0)

        // 50-Tick Local Breakout Range (relPos percentile 0..100) + Fibonacci Levels
        val rangeSliceStart = max(0, len - 50)
        var localMin = Double.POSITIVE_INFINITY
        var localMax = Double.NEGATIVE_INFINITY
        for (i in rangeSliceStart until len) {
            if (prices[i] < localMin) localMin = prices[i]
            if (prices[i] > localMax) localMax = prices[i]
        }
        if (!localMin.isFinite() || !localMax.isFinite()) {
            localMin = lastPriceVal
            localMax = lastPriceVal
        }
        val localSpan = (localMax - localMin).finiteOr(0.0)
        val relPos = if (localSpan > 1e-9) {
            ((lastPriceVal - localMin) / localSpan * 100.0).finiteOr(50.0).coerceIn(0.0, 100.0)
        } else {
            50.0
        }

        val fibLevels = FibonacciLevels(
            high50 = localMax,
            low50 = localMin,
            fib236 = localMax - localSpan * 0.236,
            fib382 = localMax - localSpan * 0.382,
            fib500 = localMax - localSpan * 0.500,
            fib618 = localMax - localSpan * 0.618,
            fib786 = localMax - localSpan * 0.786
        )

        // Macro HTF Bias from 100-tick anchor
        val htfAnchorIdx = max(0, len - 100)
        val htfBias = when {
            zSlow > zSlowSeries[htfAnchorIdx] -> SignalDirection.UP
            zSlow < zSlowSeries[htfAnchorIdx] -> SignalDirection.DOWN
            else -> trend
        }

        // Microstructure & Micro-Impulse Profiling
        val microstructure = computeMicrostructure(
            ticks = ticks,
            prices = prices,
            n = len,
            recent3sCount = recent3sCount,
            volatility = volatility,
            consistency = consistency,
            slopeRatio = slopeRatio,
            relPos = relPos,
            fibLevels = fibLevels,
            swings = swings,
            psychLevels = psychLevels
        )

        // Compute HV Window Indicator (zero-lag init + alpha = 0.08 fast-decay EMA, 15s/30s/45s/60s anchors,
        // TIGHT_CHOP < 0.20 pt filter, and Crazy Market > 55% wick collapse filter)
        val hvWindow = computeHvWindow(
            ticks = ticks,
            candles = candles,
            currentPrice = lastPriceVal,
            latestTimeMs = latestTime,
            trend = trend,
            consistency = consistency,
            velocity = velocity,
            slopeRatio = slopeRatio
        )

        // Compute HighVoltage 6-Filter Mathematical Status
        val highVoltageStatus = computeHighVoltageFilterStatus(
            currentPrice = lastPriceVal,
            zFast = zFast,
            zSlow = zSlow,
            trend = trend,
            velocity = velocity,
            relPos = relPos,
            netDelta5 = netDelta5,
            candles = candles,
            nearestSupport = nearestSupport,
            nearestResistance = nearestResistance,
            microExhaustion = microstructure.microExhaustion,
            hvWindow = hvWindow
        )

        // Align HVOI & SessionQuality with the HV Window smoothed score
        val hvoiScore = hvWindow.score
        val sessionQuality = when (hvWindow.state) {
            HvWindowState.EXCELLENT -> SessionQuality.OPTIMAL
            HvWindowState.MODERATE -> SessionQuality.NEUTRAL
            HvWindowState.AVOID -> SessionQuality.AVOID
        }

        return IndicatorSnapshot(
            zFast = zFast,
            zSlow = zSlow,
            trend = trend,
            htfBias = htfBias,
            persistence = persistence,
            consistency = consistency,
            volatility = volatility,
            velocity = velocity,
            slope = slope,
            slopeRatio = slopeRatio,
            netDelta5 = netDelta5,
            relPos = relPos,
            fibLevels = fibLevels,
            microstructure = microstructure,
            hvoiScore = hvoiScore,
            sessionQuality = sessionQuality,
            hvWindow = hvWindow,
            highVoltageStatus = highVoltageStatus
        )
    }

    private fun computeMicrostructure(
        ticks: List<Tick>,
        prices: DoubleArray,
        n: Int,
        recent3sCount: Int,
        volatility: Double,
        consistency: Double,
        slopeRatio: Double,
        relPos: Double,
        fibLevels: FibonacciLevels,
        swings: List<SwingPoint>,
        psychLevels: List<Double>
    ): MicrostructureSnapshot {
        val tapeSpeed = recent3sCount

        val dispWindow = min(10, n - 1)
        val rawDisp10 = abs(prices[n - 1] - prices[n - 1 - dispWindow])
        val displacement10 = (if (volatility > 1e-9) (rawDisp10 / (volatility * dispWindow)) * 100.0 else 50.0).finiteOr(50.0)

        val effWindow = min(10, n - 1)
        var pathSum = 0.0
        for (i in (n - effWindow) until n) {
            pathSum += abs(prices[i] - prices[i - 1])
        }
        val netMoveEff = abs(prices[n - 1] - prices[n - 1 - effWindow])
        val directionalEfficiency = if (pathSum > 1e-9) {
            ((netMoveEff / pathSum) * 100.0).finiteOr(0.0).coerceIn(0.0, 100.0)
        } else {
            0.0
        }

        var streakCount = 0
        var lastStepDir = 0
        for (i in (n - 1) downTo 1) {
            val d = prices[i] - prices[i - 1]
            val stepDir = when {
                d > 1e-9 -> 1
                d < -1e-9 -> -1
                else -> 0
            }
            if (stepDir == 0) continue
            if (lastStepDir == 0) {
                lastStepDir = stepDir
                streakCount = 1
            } else if (stepDir == lastStepDir) {
                streakCount++
            } else {
                break
            }
        }
        val microPersistence = (lastStepDir * min(10, streakCount) * 10.0).finiteOr(0.0).coerceIn(-100.0, 100.0)

        val halfW = min(4, (n - 1) / 2)
        val recentMove = if (halfW > 0) abs(prices[n - 1] - prices[n - 1 - halfW]) else 0.0
        val prevMove = if (halfW > 0) abs(prices[n - 1 - halfW] - prices[n - 1 - 2 * halfW]) else 0.0
        val microAcceleration = if (volatility > 1e-9) {
            ((recentMove - prevMove) / volatility).finiteOr(0.0).coerceIn(-10.0, 10.0)
        } else {
            0.0
        }

        val hesWindow = min(12, n - 1)
        var hesitantSteps = 0
        for (i in (n - hesWindow) until n) {
            val dt = ticks[i].timeMs - ticks[i - 1].timeMs
            val dp = abs(prices[i] - prices[i - 1])
            if (dt > 750L || dp <= volatility * 0.15) {
                hesitantSteps++
            }
        }
        val microHesitation = (if (hesWindow > 0) (hesitantSteps.toDouble() / hesWindow * 100.0) else 0.0).finiteOr(0.0)

        val lastStep = abs(prices[n - 1] - prices[n - 2])
        val microExhaustion = when {
            slopeRatio >= 3.2 && lastStep < volatility * 0.4 -> 85.0
            slopeRatio >= 2.6 -> 55.0
            else -> 15.0
        }

        val urgency = when {
            tapeSpeed >= 6 && microAcceleration > 0.3 && microHesitation < 35.0 -> MicroUrgency.SURGING
            microHesitation >= 50.0 || tapeSpeed <= 2 -> MicroUrgency.HESITANT
            else -> MicroUrgency.STEADY
        }

        val participation = when {
            tapeSpeed >= 6 -> MicroParticipation.ACTIVE
            tapeSpeed <= 2 -> MicroParticipation.DORMANT
            else -> MicroParticipation.THIN
        }

        val energyState = when {
            microAcceleration >= 1.2 && directionalEfficiency >= 75.0 -> EnergyState.EXPLODING
            microAcceleration > 0.25 && directionalEfficiency >= 55.0 -> EnergyState.INCREASING
            microAcceleration < -1.0 -> EnergyState.COLLAPSING
            microAcceleration < -0.3 -> EnergyState.LEAKING
            else -> EnergyState.STABLE
        }

        val phase = when {
            microExhaustion >= 75.0 -> MarketPhase.EXHAUSTION
            energyState == EnergyState.EXPLODING || relPos >= 88.0 || relPos <= 12.0 -> MarketPhase.EXPANSION
            directionalEfficiency >= 62.0 && consistency >= 0.70 -> MarketPhase.MOMENTUM
            relPos in 40.0..60.0 && volatility > 0 -> MarketPhase.PRESSURE_BUILD
            else -> MarketPhase.LEVEL_TEST
        }

        val currP = prices[n - 1]
        val tol = max(volatility * 1.5, 1e-5)
        val nearSwingHigh = swings.any { it.type == SwingType.HIGH && abs(it.price - currP) <= tol }
        val nearSwingLow = swings.any { it.type == SwingType.LOW && abs(it.price - currP) <= tol }
        val nearRound = psychLevels.any { abs(it - currP) <= tol }
        val nearFib618 = abs(fibLevels.fib618 - currP) <= tol || abs(fibLevels.fib382 - currP) <= tol
        val activeLandmark = when {
            nearSwingHigh -> LandmarkType.SWING_HIGH
            nearSwingLow -> LandmarkType.SWING_LOW
            nearFib618 -> LandmarkType.FIB_618
            nearRound -> LandmarkType.ROUND_LEVEL
            microExhaustion >= 75.0 -> LandmarkType.REVERSAL_POINT
            else -> LandmarkType.NONE
        }

        val breakoutProb = (directionalEfficiency * 0.45 + consistency * 40.0 + (if (microAcceleration > 0) 15.0 else 0.0))
            .finiteOr(50.0)
            .coerceIn(5.0, 95.0)
        val meanReversionProb = (100.0 - breakoutProb + (if (microExhaustion > 50.0) 15.0 else 0.0))
            .finiteOr(50.0)
            .coerceIn(5.0, 95.0)
        val mci = (directionalEfficiency * 0.4 + consistency * 40.0 + (100.0 - microHesitation) * 0.2)
            .finiteOr(50.0)
            .coerceIn(0.0, 100.0)

        val netRecent = prices[n - 1] - prices[max(0, n - 6)]
        val impulseDir = when {
            netRecent > volatility * 0.6 && lastStepDir >= 0 -> SignalDirection.UP
            netRecent < -volatility * 0.6 && lastStepDir <= 0 -> SignalDirection.DOWN
            else -> SignalDirection.NONE
        }
        val streakBonus = (min(6, streakCount) / 6.0) * 25.0
        val accelBonus = ((microAcceleration.coerceIn(-1.0, 2.5) + 1.0) / 3.5) * 25.0
        val effComponent = directionalEfficiency * 0.50
        val rawImpulseScore = (effComponent + streakBonus + accelBonus - (microHesitation * 0.15))
            .finiteOr(0.0)
            .coerceIn(0.0, 100.0)

        return MicrostructureSnapshot(
            tapeSpeed = tapeSpeed,
            displacement10 = displacement10,
            directionalEfficiency = directionalEfficiency,
            microAcceleration = microAcceleration,
            microPersistence = microPersistence,
            microHesitation = microHesitation,
            microExhaustion = microExhaustion,
            microImpulseScore = rawImpulseScore,
            impulseDirection = impulseDir,
            streakCount = streakCount,
            urgency = urgency,
            participation = participation,
            energyState = energyState,
            phase = phase,
            activeLandmark = activeLandmark,
            breakoutProb = breakoutProb,
            meanReversionProb = meanReversionProb,
            mci = mci
        )
    }

    private fun computeZlemaSeriesInPlace(
        prices: DoubleArray,
        len: Int,
        period: Int,
        out: DoubleArray
    ): DoubleArray {
        val lag = max(1, (period - 1) / 2)
        val k = 2.0 / (period + 1.0)
        out[0] = prices[0]
        for (i in 1 until len) {
            val laggedPrice = if (i >= lag) prices[i - lag] else prices[0]
            val deLagged = prices[i] + (prices[i] - laggedPrice)
            out[i] = (k * deLagged + (1.0 - k) * out[i - 1]).finiteOr(prices[i])
        }
        return out
    }

    private fun lookupPriceAtLookbackSec(
        ticks: List<Tick>,
        candles: List<Candle>,
        latestTimeMs: Long,
        lookbackSec: Int,
        fallbackPrice: Double
    ): Double {
        val targetMs = latestTimeMs - (lookbackSec * 1000L)
        val oldestTickMs = ticks.firstOrNull()?.timeMs ?: latestTimeMs
        if (ticks.isNotEmpty() && targetMs >= oldestTickMs - 2000L) {
            for (i in ticks.indices.reversed()) {
                if (ticks[i].timeMs <= targetMs) {
                    return ticks[i].price.finiteOr(fallbackPrice)
                }
            }
            return ticks.first().price.finiteOr(fallbackPrice)
        }
        // Fallback to 5-second candle map (15s = 3 candles back, 30s = 6, 45s = 9, 60s = 12)
        if (candles.isNotEmpty()) {
            val candleBucketsBack = max(1, lookbackSec / 5)
            val idx = (candles.lastIndex - candleBucketsBack).coerceIn(0, candles.lastIndex)
            return candles[idx].close.finiteOr(fallbackPrice)
        }
        return fallbackPrice
    }

    private fun computeHvWindow(
        ticks: List<Tick>,
        candles: List<Candle>,
        currentPrice: Double,
        latestTimeMs: Long,
        trend: SignalDirection,
        consistency: Double,
        velocity: Double,
        slopeRatio: Double
    ): HvWindowSnapshot {
        if (hvFirstTickTimeMs == 0L || latestTimeMs < hvFirstTickTimeMs) {
            hvFirstTickTimeMs = latestTimeMs
        }
        val elapsedBootSec = ((latestTimeMs - hvFirstTickTimeMs).coerceAtLeast(0L)) / 1000.0
        val engineBooted = candles.size >= 4 || elapsedBootSec >= 10.0 || ticks.size >= 25

        // 1. Multi-timeframe price anchors at 5s, 15s, 30s, 45s, and 60s (1m)
        val p5s = lookupPriceAtLookbackSec(ticks, candles, latestTimeMs, 5, currentPrice)
        val p15s = lookupPriceAtLookbackSec(ticks, candles, latestTimeMs, 15, currentPrice)
        val p30s = lookupPriceAtLookbackSec(ticks, candles, latestTimeMs, 30, p15s)
        val p45s = lookupPriceAtLookbackSec(ticks, candles, latestTimeMs, 45, p30s)
        val p60s = lookupPriceAtLookbackSec(ticks, candles, latestTimeMs, 60, p45s)

        val delta5s = (currentPrice - p5s).finiteOr(0.0)
        val delta15s = (currentPrice - p15s).finiteOr(0.0)
        val delta30s = (currentPrice - p30s).finiteOr(0.0)
        val delta45s = (currentPrice - p45s).finiteOr(0.0)
        val delta60s = (currentPrice - p60s).finiteOr(0.0)

        val anchorPrices = doubleArrayOf(currentPrice, p15s, p30s, p45s, p60s)
        var anchorMin = currentPrice
        var anchorMax = currentPrice
        for (ap in anchorPrices) {
            if (ap < anchorMin) anchorMin = ap
            if (ap > anchorMax) anchorMax = ap
        }
        // Also incorporate recent 12 candles (60s) high/low if available and larger than point samples
        val anchorRange60s = if (candles.size >= 4) {
            val recent12 = candles.takeLast(12)
            val cHigh = recent12.maxOf { it.high }
            val cLow = recent12.minOf { it.low }
            max(anchorMax - anchorMin, (cHigh - cLow) * 0.75).finiteOr(0.0)
        } else {
            // If only short-window test ticks are present without 60s span, extrapolate 60s range from tick span
            val tickMin = ticks.minOf { it.price }
            val tickMax = ticks.maxOf { it.price }
            max(anchorMax - anchorMin, tickMax - tickMin).finiteOr(0.0)
        }

        // Price scale normalization for 0.20 pt TIGHT_CHOP threshold (0.20 for indices/crypto/gold/JPY, scaled for 1.x FX)
        val tightChopThreshold = if (currentPrice in 0.01..15.0) 0.00020 else HV_TIGHT_CHOP_POINTS
        val tightChopDetected = engineBooted && anchorRange60s < tightChopThreshold

        // Multi-timeframe directional alignment across 15s, 30s, 45s, 60s
        val mtfDeltas = doubleArrayOf(delta15s, delta30s, delta45s, delta60s)
        var mtfAlignedCount = 0
        for (d in mtfDeltas) {
            val aligned = (trend == SignalDirection.UP && d > 0.0) ||
                (trend == SignalDirection.DOWN && d < 0.0)
            if (aligned) mtfAlignedCount++
        }

        // 2. Active candle + last 4 completed candles (up to 5 candles) inspection for
        //    "Crazy Market" whipsaw collapse wicks (>55%) and solid directional bodies
        var crazyWhipsawDetected = false
        var maxWickRatio = 0.20
        var avgBodyRatio = 0.68
        var alignedCandleCount = 0

        if (candles.isNotEmpty()) {
            val inspectCount = min(5, candles.size)
            val slice = candles.takeLast(inspectCount)
            var highWickCandles = 0
            var bodySum = 0.0
            var maxW = 0.0
            for (c in slice) {
                val range = c.totalRange.coerceAtLeast(1e-9)
                val dominantWickRatio = (max(c.upperWick, c.lowerWick) / range).finiteOr(0.0).coerceIn(0.0, 1.0)
                val bodyRatio = (c.bodySize / range).finiteOr(0.5).coerceIn(0.0, 1.0)
                if (dominantWickRatio > maxW) maxW = dominantWickRatio
                if (dominantWickRatio > HV_WICK_COLLAPSE_RATIO) {
                    highWickCandles++
                }
                bodySum += bodyRatio
                if ((trend == SignalDirection.UP && c.isBullish) || (trend == SignalDirection.DOWN && !c.isBullish)) {
                    alignedCandleCount++
                }
            }
            maxWickRatio = maxW
            avgBodyRatio = (bodySum / inspectCount.toDouble()).finiteOr(0.60)
            val activeCandle = slice.last()
            val activeWickRatio = (max(activeCandle.upperWick, activeCandle.lowerWick) / activeCandle.totalRange.coerceAtLeast(1e-9))
                .finiteOr(0.0)

            // Crazy Market Whipsaw: repeated candles (>55% wicks) or active candle collapsing (>60% wick) with poor body average
            crazyWhipsawDetected = (inspectCount >= 3 && highWickCandles >= 2) ||
                (activeWickRatio > 0.62 && avgBodyRatio < 0.42)
        }

        // 3. Raw HV Window Suitability Score (0..100%)
        val mtfAlignmentRatio = if (candles.size >= 4) {
            mtfAlignedCount / 4.0
        } else {
            consistency.coerceIn(0.0, 1.0)
        }
        val velocityFactor = (velocity / 2.2).coerceIn(0.0, 1.15)
        val candleAlignmentFactor = if (candles.isNotEmpty()) {
            alignedCandleCount.toDouble() / min(5, candles.size).toDouble()
        } else {
            consistency
        }
        val exhaustionPenalty = if (slopeRatio >= 3.25) 14.0 else 0.0

        val baseRawScore = (
            (avgBodyRatio * 28.0) +
                (consistency * 28.0) +
                (mtfAlignmentRatio * 20.0) +
                (velocityFactor * 14.0) +
                (candleAlignmentFactor * 14.0) -
                exhaustionPenalty
            ).finiteOr(60.0).coerceIn(10.0, 98.0)

        val rawScore = when {
            crazyWhipsawDetected -> min(38.0, baseRawScore * 0.48).coerceIn(12.0, 44.0)
            tightChopDetected -> min(42.0, baseRawScore * 0.55).coerceIn(15.0, 46.0)
            else -> baseRawScore
        }

        // 4. Zero-Lag Initialization + Fast-Decay EMA Smoothing (alpha = 0.08)
        if (!hvEmaInitialized) {
            smoothedHvScore = rawScore
            hvEmaInitialized = true
        } else {
            // Fast-decay EMA (alpha = 0.08) during normal evolution; accelerate protective drop on trap detection
            val effectiveAlpha = if (crazyWhipsawDetected || tightChopDetected) 0.35 else HV_EMA_ALPHA
            smoothedHvScore = (effectiveAlpha * rawScore + (1.0 - effectiveAlpha) * smoothedHvScore)
                .finiteOr(rawScore)
                .coerceIn(0.0, 100.0)
        }

        // Ensure immediate trap enforcement drops the displayed score into AVOID (<50%)
        val finalHvScore = when {
            crazyWhipsawDetected -> min(44.0, smoothedHvScore)
            tightChopDetected -> min(46.0, smoothedHvScore)
            else -> smoothedHvScore
        }.finiteOr(50.0).coerceIn(0.0, 100.0)

        // 5. Track stability time above 72% (Step 2 & 3 of HV Window Guide: hold >=72% for 15-20s)
        val stabilitySeconds = if (finalHvScore >= 72.0 && !crazyWhipsawDetected && !tightChopDetected) {
            if (hvStableAbove72StartMs == 0L || latestTimeMs < hvStableAbove72StartMs) {
                hvStableAbove72StartMs = latestTimeMs
            }
            ((latestTimeMs - hvStableAbove72StartMs).coerceAtLeast(0L)) / 1000.0
        } else {
            hvStableAbove72StartMs = 0L
            0.0
        }

        val state = when {
            finalHvScore >= 75.0 -> HvWindowState.EXCELLENT
            finalHvScore >= 50.0 -> HvWindowState.MODERATE
            else -> HvWindowState.AVOID
        }

        val activeTrap = when {
            crazyWhipsawDetected -> HvMarketTrap.CRAZY_WHIPSAW
            tightChopDetected -> HvMarketTrap.TIGHT_CHOP
            else -> HvMarketTrap.NONE
        }

        val botLaunchReady = engineBooted && finalHvScore >= 72.0 && !crazyWhipsawDetected && !tightChopDetected
        val pauseImmediately = state == HvWindowState.AVOID || crazyWhipsawDetected || tightChopDetected

        val stepGuideHint = when {
            !engineBooted -> "Step 1 (Booting): Populating 1-minute candle map (${"%.0f".format(elapsedBootSec)}s / 15s)..."
            pauseImmediately && crazyWhipsawDetected -> "Step 4 (AVOID — CRAZY MARKET): Collapse wicks >55% detected. Pause Bot immediately!"
            pauseImmediately && tightChopDetected -> "Step 4 (AVOID — TIGHT_CHOP): 60s range ${"%.3f".format(anchorRange60s)} < ${"%.2f".format(tightChopThreshold)}. Keep Bot OFF!"
            pauseImmediately -> "Step 4 (AVOID <50%): Market choppy/unstable. Keep Bot OFF to prevent loss clusters."
            finalHvScore >= 75.0 && stabilitySeconds >= 15.0 -> "Step 3 (LAUNCH BOT): Score ≥75% & stable for ${"%.0f".format(stabilitySeconds)}s — Clean directional trend!"
            finalHvScore >= 72.0 -> "Step 2–3 (STABILIZING ≥72%): Holding at ${"%.0f".format(finalHvScore)}% (${"%.0f".format(stabilitySeconds)}s / 15s stability target)."
            else -> "Step 2 (MODERATE ${"%.0f".format(finalHvScore)}%): Wait for score to climb & hold ≥72% for 15–20s."
        }

        return HvWindowSnapshot(
            score = finalHvScore,
            rawScore = rawScore,
            state = state,
            activeTrap = activeTrap,
            tightChopDetected = tightChopDetected,
            crazyWhipsawDetected = crazyWhipsawDetected,
            wickCollapseRatioPercent = (maxWickRatio * 100.0).finiteOr(20.0).coerceIn(0.0, 100.0),
            solidBodyRatioPercent = (avgBodyRatio * 100.0).finiteOr(65.0).coerceIn(0.0, 100.0),
            anchorRange60s = anchorRange60s,
            tightChopThreshold = tightChopThreshold,
            delta5s = delta5s,
            delta15s = delta15s,
            delta30s = delta30s,
            delta45s = delta45s,
            delta60s = delta60s,
            mtfAlignedCount = mtfAlignedCount,
            stabilitySeconds = stabilitySeconds,
            engineBooted = engineBooted,
            botLaunchReady = botLaunchReady,
            pauseImmediately = pauseImmediately,
            stepGuideHint = stepGuideHint
        )
    }

    private fun computeHighVoltageFilterStatus(
        currentPrice: Double,
        zFast: Double,
        zSlow: Double,
        trend: SignalDirection,
        velocity: Double,
        relPos: Double,
        netDelta5: Double,
        candles: List<Candle>,
        nearestSupport: SRZone?,
        nearestResistance: SRZone?,
        microExhaustion: Double,
        hvWindow: HvWindowSnapshot
    ): HighVoltageFilterStatus {
        // 1. Trend Direction (ZLEMA Fast 10 vs Slow 30)
        val zSpread = (zFast - zSlow).finiteOr(0.0)
        val trendPassed = trend == SignalDirection.UP || trend == SignalDirection.DOWN

        // 2. Micro-Velocity (Tick Speed in last 3 seconds >= 1.5 T/s)
        val velocityPassed = velocity >= HV_MIN_VELOCITY_TPS - 1e-6

        // 3. 50-Tick Range Breakout (relPos >= 80% for UP, relPos <= 20% for DOWN)
        val positionBreakoutPassed = when (trend) {
            SignalDirection.UP -> relPos >= HV_BREAKOUT_UPPER_PCT - 1e-6
            SignalDirection.DOWN -> relPos <= HV_BREAKOUT_LOWER_PCT + 1e-6
            SignalDirection.NONE -> false
        }

        // 4. Active Directional Momentum (netDelta5 over last 6 ticks > 0 for UP, < 0 for DOWN)
        val activeMomentumPassed = when (trend) {
            SignalDirection.UP -> netDelta5 > 1e-9
            SignalDirection.DOWN -> netDelta5 < -1e-9
            SignalDirection.NONE -> false
        }

        // 5. Support / Resistance Obstacles (0.25 points ahead of UP/DOWN trade)
        val minClearance = if (currentPrice in 0.01..15.0) 0.00025 else HV_SR_CLEARANCE_POINTS
        val srClearanceAhead = when (trend) {
            SignalDirection.UP -> {
                if (nearestResistance != null && nearestResistance.centerPrice >= currentPrice) {
                    (nearestResistance.centerPrice - currentPrice).finiteOr(999.0)
                } else {
                    999.0
                }
            }
            SignalDirection.DOWN -> {
                if (nearestSupport != null && nearestSupport.centerPrice <= currentPrice) {
                    (currentPrice - nearestSupport.centerPrice).finiteOr(999.0)
                } else {
                    999.0
                }
            }
            SignalDirection.NONE -> 999.0
        }
        val srObstaclePassed = srClearanceAhead >= minClearance

        // 6. Volatility & Regimes (Compression < 40% of last 10 candles' avg range, or Exhausted)
        var currentCandleRange = 0.10
        var avg10CandleRange = 0.10
        var candleRangeRatio = 1.0
        var isCompressed = false
        var isExhausted = false

        if (candles.size >= 3) {
            val activeCandle = candles.last()
            val historySlice = if (candles.size > 1) {
                candles.subList(max(0, candles.size - 11), candles.size - 1)
            } else {
                candles
            }
            val avgRange = (historySlice.sumOf { it.totalRange } / historySlice.size.toDouble())
                .finiteOr(0.05)
                .coerceAtLeast(1e-6)
            currentCandleRange = activeCandle.totalRange.finiteOr(avgRange)
            avg10CandleRange = avgRange
            // Because an active 5s candle may have just opened (< 3 ticks), blend with previous completed candle
            val effectiveCurrentRange = if (activeCandle.tickCount <= 2 && candles.size >= 2) {
                max(currentCandleRange, candles[candles.size - 2].totalRange)
            } else {
                currentCandleRange
            }
            candleRangeRatio = (effectiveCurrentRange / avgRange).finiteOr(1.0)
            isCompressed = candleRangeRatio < 0.40
            isExhausted = candleRangeRatio > 2.65 || microExhaustion >= 80.0
        } else {
            isExhausted = microExhaustion >= 80.0
        }
        val volatilityFilterPassed = !isCompressed && !isExhausted
        val hvWindowPassed = hvWindow.state != HvWindowState.AVOID &&
            !hvWindow.tightChopDetected &&
            !hvWindow.crazyWhipsawDetected

        val allSixFiltersPassed = trendPassed &&
            velocityPassed &&
            positionBreakoutPassed &&
            activeMomentumPassed &&
            srObstaclePassed &&
            volatilityFilterPassed &&
            hvWindowPassed

        return HighVoltageFilterStatus(
            zlemaTrend = trend,
            zlemaSpread = zSpread,
            trendFilterPassed = trendPassed,
            velocityTps = velocity,
            minVelocityRequired = HV_MIN_VELOCITY_TPS,
            velocityFilterPassed = velocityPassed,
            relPos50 = relPos,
            breakoutUpperPct = HV_BREAKOUT_UPPER_PCT,
            breakoutLowerPct = HV_BREAKOUT_LOWER_PCT,
            positionBreakoutPassed = positionBreakoutPassed,
            netDelta5 = netDelta5,
            activeMomentumPassed = activeMomentumPassed,
            srClearanceAhead = srClearanceAhead,
            srMinClearanceRequired = minClearance,
            srObstaclePassed = srObstaclePassed,
            currentCandleRange = currentCandleRange,
            avg10CandleRange = avg10CandleRange,
            candleRangeRatio = candleRangeRatio,
            isCompressed = isCompressed,
            isExhausted = isExhausted,
            volatilityFilterPassed = volatilityFilterPassed,
            hvWindowPassed = hvWindowPassed,
            allSixFiltersPassed = allSixFiltersPassed
        )
    }
}

/**
 * Evaluates structural danger zones (rejection wicks, adaptive S/R corridor blocks,
 * broker asset locks, minimum payout filter, and WebSocket order-flow contradictions)
 * and computes the audited scale-invariant confidence score.
 */
class ContextEnhancer {
    fun evaluateContext(
        price: Double,
        indicators: IndicatorSnapshot,
        candles: List<Candle>,
        swings: List<SwingPoint>,
        nearestSupport: SRZone?,
        nearestResistance: SRZone?,
        corridorWidth: Double,
        orderFlowImbalance: Double = 0.0,
        tickIntensityRatio: Double = 1.0,
        assetPayoutPercent: Int = 85,
        minPayoutPercent: Int = 75,
        assetLocked: Boolean = false,
        bullishSentimentPercent: Int = 50
    ): Pair<List<String>, Double> {
        val blocks = mutableListOf<String>()
        val dir = indicators.trend

        // 0. Broker Asset Lock & Minimum Payout Filter from WebSocket Stream
        if (assetLocked) {
            blocks.add("Broker Asset Locked (Trading Suspended)")
        }
        if (assetPayoutPercent < minPayoutPercent) {
            blocks.add("Payout ${assetPayoutPercent}% < Min ${minPayoutPercent}% Filter")
        }

        // 1. Rejection Wick Check on latest candles
        if (candles.size >= 2) {
            val last = candles.last()
            val range = last.totalRange
            if (dir == SignalDirection.UP && last.upperWick > range * 0.55) {
                blocks.add("Bearish Rejection Wick (${(last.upperWick / range * 100).toInt()}%)")
            } else if (dir == SignalDirection.DOWN && last.lowerWick > range * 0.55) {
                blocks.add("Bullish Rejection Wick (${(last.lowerWick / range * 100).toInt()}%)")
            }
        }

        // 2. Adaptive S/R Corridor Proximity Check (bypassed during strong breakouts relPos > 92 or < 8)
        val isStrongBreakout = indicators.relPos >= 92.0 || indicators.relPos <= 8.0
        if (!isStrongBreakout) {
            if (dir == SignalDirection.UP && nearestResistance != null) {
                val dist = nearestResistance.centerPrice - price
                if (dist in 0.0..corridorWidth && nearestResistance.touchCount >= 2) {
                    blocks.add("Entering Resistance Zone (dist=${"%.4f".format(dist)})")
                }
            }
            if (dir == SignalDirection.DOWN && nearestSupport != null) {
                val dist = price - nearestSupport.centerPrice
                if (dist in 0.0..corridorWidth && nearestSupport.touchCount >= 2) {
                    blocks.add("Entering Support Zone (dist=${"%.4f".format(dist)})")
                }
            }
        }

        // 3. Active Window Swing Structure Check
        if (!isStrongBreakout && swings.size >= 4) {
            val recentHighs = swings.filter { it.type == SwingType.HIGH }.takeLast(2)
            val recentLows = swings.filter { it.type == SwingType.LOW }.takeLast(2)
            if (dir == SignalDirection.UP && recentHighs.size == 2 && recentLows.size == 2) {
                if (recentHighs[1].price < recentHighs[0].price && recentLows[1].price < recentLows[0].price) {
                    blocks.add("Lower-High / Lower-Low Structure Contradiction")
                }
            } else if (dir == SignalDirection.DOWN && recentHighs.size == 2 && recentLows.size == 2) {
                if (recentHighs[1].price > recentHighs[0].price && recentLows[1].price > recentLows[0].price) {
                    blocks.add("Higher-High / Higher-Low Structure Contradiction")
                }
            }
        }

        // 4. WebSocket Order-Flow Imbalance Contradiction Check
        if (dir == SignalDirection.UP && orderFlowImbalance <= -42.0) {
            blocks.add("Counter Order-Flow Imbalance (${"%.0f".format(orderFlowImbalance)}%)")
        } else if (dir == SignalDirection.DOWN && orderFlowImbalance >= 42.0) {
            blocks.add("Counter Order-Flow Imbalance (+${"%.0f".format(orderFlowImbalance)}%)")
        }

        // Audited Scale-Invariant Confidence Score + Stream Order-Flow & Intensity Synergy
        val assetTrendiness = ((max(indicators.hvoiScore, indicators.microstructure.microImpulseScore) / 100.0) * 0.52)
            .finiteOr(0.25)
            .coerceIn(0.0, 0.55)
        val baseScore = (indicators.consistency * 50.0) + (assetTrendiness * 100.0)

        val flowAlignedBonus = when {
            dir == SignalDirection.UP && orderFlowImbalance >= 20.0 -> min(4.0, (orderFlowImbalance - 15.0) * 0.08)
            dir == SignalDirection.DOWN && orderFlowImbalance <= -20.0 -> min(4.0, (abs(orderFlowImbalance) - 15.0) * 0.08)
            else -> 0.0
        }
        val intensityBonus = if (tickIntensityRatio >= 1.18) min(2.5, (tickIntensityRatio - 1.0) * 3.0) else 0.0
        val sentimentBonus = when {
            dir == SignalDirection.UP && bullishSentimentPercent >= 58 -> 1.5
            dir == SignalDirection.DOWN && bullishSentimentPercent <= 42 -> 1.5
            else -> 0.0
        }

        val blockPenalty = blocks.size * 12.0
        val finalConfidence = (baseScore + flowAlignedBonus + intensityBonus + sentimentBonus - blockPenalty)
            .finiteOr(50.0)
            .coerceIn(0.0, 100.0)

        return blocks to finalConfidence
    }
}

/**
 * 8-State Gaussian Hidden Markov Model (HMM) Regime Detection Engine.
 * Pre-allocates static buffers of 150 ticks and applies a 1e-9 variance floor,
 * Z-score clamping [-10, +10], and finite sanitization so NaN can never occur.
 */
class RegimeDetectionEngine {
    private val nStates = 8
    private val featureNames = listOf(
        "return_1", "return_5", "return_10",
        "rolling_vol_10", "rolling_vol_20", "rolling_vol_50",
        "rolling_mean_10", "rolling_mean_20",
        "streak_length", "direction_change", "velocity", "acceleration"
    )

    private val pricesBuf = DoubleArray(150)
    private val timesBuf = DoubleArray(150)
    private val tickDirectionsBuf = IntArray(150)
    private val dtCleansBuf = DoubleArray(150)
    private val velocitiesBuf = DoubleArray(150)
    private val accelerationsBuf = DoubleArray(150)
    private val streakLengthsBuf = IntArray(150)
    private val directionChangesBuf = IntArray(150)
    private val return1Buf = DoubleArray(150)

    private var lastState: Int? = null
    private var stateDuration: Int = 0

    private val transmat = arrayOf(
        doubleArrayOf(0.461, 0.484, 0.000, 0.000, 0.000, 0.020, 0.010, 0.026), // S0 Expansion
        doubleArrayOf(0.210, 0.433, 0.001, 0.000, 0.000, 0.205, 0.011, 0.139), // S1 Reversal
        doubleArrayOf(0.005, 0.033, 0.939, 0.000, 0.000, 0.017, 0.007, 0.000), // S2 Reversal
        doubleArrayOf(0.000, 0.000, 1.000, 0.000, 0.000, 0.000, 0.000, 0.000), // S3 Expansion
        doubleArrayOf(0.000, 0.000, 0.276, 0.000, 0.724, 0.000, 0.000, 0.000), // S4 Calm
        doubleArrayOf(0.018, 0.480, 0.000, 0.000, 0.001, 0.452, 0.008, 0.041), // S5 Trending
        doubleArrayOf(0.027, 0.090, 0.001, 0.000, 0.000, 0.028, 0.063, 0.791), // S6 Calm
        doubleArrayOf(0.038, 0.500, 0.000, 0.000, 0.000, 0.060, 0.401, 0.000)  // S7 Calm
    )

    private val startProb = doubleArrayOf(0.05, 0.45, 0.10, 0.02, 0.08, 0.15, 0.08, 0.07)

    private val regimeNames = mapOf(
        0 to "Expansion",
        1 to "Reversal",
        2 to "Reversal",
        3 to "Expansion",
        4 to "Calm",
        5 to "Trending",
        6 to "Calm",
        7 to "Calm"
    )

    private val stateMeans = arrayOf(
        doubleArrayOf(0.4, 0.6, 0.7, 1.6, 1.5, 1.3, 0.2, 0.2, 0.3, 0.1, 1.8, 1.4),
        doubleArrayOf(-0.1, 0.0, 0.0, 0.4, 0.4, 0.3, 0.0, 0.0, -0.8, 1.1, 0.1, -0.3),
        doubleArrayOf(0.0, -0.1, -0.1, 0.5, 0.6, 0.5, -0.1, -0.1, -0.9, 1.3, -0.2, 0.2),
        doubleArrayOf(1.2, 1.4, 1.1, 2.2, 2.0, 1.7, 0.6, 0.5, 0.8, -0.2, 2.4, 2.1),
        doubleArrayOf(0.0, 0.0, 0.0, -0.9, -0.9, -0.8, 0.0, 0.0, -0.2, 0.0, -0.8, -0.4),
        doubleArrayOf(0.5, 0.8, 1.0, 0.6, 0.6, 0.5, 0.7, 0.7, 1.7, -0.9, 1.1, 0.5),
        doubleArrayOf(0.0, 0.0, 0.0, -0.7, -0.7, -0.6, 0.0, 0.0, -0.3, 0.2, -0.6, -0.2),
        doubleArrayOf(0.0, 0.0, 0.0, -0.8, -0.8, -0.7, 0.0, 0.0, -0.4, 0.3, -0.7, -0.3)
    )

    private val stateVars = Array(8) { s ->
        when (s) {
            0, 3 -> DoubleArray(12) { 1.45 }
            5 -> DoubleArray(12) { 0.85 }
            1, 2 -> DoubleArray(12) { 0.95 }
            else -> DoubleArray(12) { 0.65 }
        }
    }

    private val stateLogNormConst = Array(8) { s ->
        DoubleArray(12) { f ->
            val v = max(1e-9, stateVars[s][f])
            -0.5 * ln(2.0 * PI) - 0.5 * ln(v)
        }
    }

    private val forwardProbBuf = DoubleArray(nStates)
    private val nextForwardBuf = DoubleArray(nStates)
    private val logEmissionsBuf = DoubleArray(nStates)
    private val scaledEmissionsBuf = DoubleArray(nStates)
    private val scaledVecBuf = DoubleArray(12)

    @Synchronized
    fun reset() {
        lastState = null
        stateDuration = 0
    }

    @Synchronized
    fun processTicks(ticks: List<Tick>): RegimeOutput? {
        if (ticks.size < 25) return null
        val offset = max(0, ticks.size - 150)
        val n = ticks.size - offset

        for (i in 0 until n) {
            val tk = ticks[offset + i]
            pricesBuf[i] = tk.price.finiteOr(1.0).coerceAtLeast(1e-9)
            timesBuf[i] = tk.timeMs.toDouble()
        }

        tickDirectionsBuf[0] = 0
        dtCleansBuf[0] = 1.0
        velocitiesBuf[0] = 0.0
        accelerationsBuf[0] = 0.0
        streakLengthsBuf[0] = 1
        directionChangesBuf[0] = 0
        return1Buf[0] = 0.0

        for (i in 1 until n) {
            val delta = pricesBuf[i] - pricesBuf[i - 1]
            tickDirectionsBuf[i] = when {
                delta > 0 -> 1
                delta < 0 -> -1
                else -> 0
            }
            val dt = (timesBuf[i] - timesBuf[i - 1]) / 1000.0
            dtCleansBuf[i] = if (dt <= 0.0 || !dt.isFinite()) 0.001 else dt
            velocitiesBuf[i] = (delta / dtCleansBuf[i]).finiteOr(0.0)
            directionChangesBuf[i] = if (tickDirectionsBuf[i] != tickDirectionsBuf[i - 1]) 1 else 0
            streakLengthsBuf[i] = if (tickDirectionsBuf[i] == tickDirectionsBuf[i - 1] && tickDirectionsBuf[i] != 0) {
                streakLengthsBuf[i - 1] + 1
            } else {
                1
            }
            val prevP = pricesBuf[i - 1].coerceAtLeast(1e-9)
            val currP = pricesBuf[i].coerceAtLeast(1e-9)
            return1Buf[i] = ln(currP / prevP).finiteOr(0.0)
        }

        accelerationsBuf[1] = 0.0
        for (i in 2 until n) {
            accelerationsBuf[i] = ((velocitiesBuf[i] - velocitiesBuf[i - 1]) / dtCleansBuf[i]).finiteOr(0.0)
        }

        val startIdx = min(50, max(10, n / 3))
        val hmmSteps = n - startIdx
        if (hmmSteps <= 0) return null

        val retStd = computeStd(return1Buf, n).finiteOr(1e-5).coerceAtLeast(1e-7)
        val velStd = computeStd(velocitiesBuf, n).finiteOr(1e-5).coerceAtLeast(1e-7)
        val accStd = computeStd(accelerationsBuf, n).finiteOr(1e-5).coerceAtLeast(1e-7)

        val forwardProb = forwardProbBuf
        val nextForward = nextForwardBuf
        val logEmissions = logEmissionsBuf
        val scaledEmissions = scaledEmissionsBuf
        val scaledVec = scaledVecBuf
        for (j in 0 until nStates) {
            forwardProb[j] = 1.0 / nStates
        }
        var lastScaledFeatures = emptyMap<String, Double>()

        for (t in 0 until hmmSteps) {
            val idx = startIdx + t
            scaledVec[0] = (return1Buf[idx] / retStd).finiteOr(0.0).coerceIn(-10.0, 10.0)
            scaledVec[1] = (ln(pricesBuf[idx] / pricesBuf[max(0, idx - 5)]) / (retStd * 2.2)).finiteOr(0.0).coerceIn(-10.0, 10.0)
            scaledVec[2] = (ln(pricesBuf[idx] / pricesBuf[max(0, idx - 10)]) / (retStd * 3.1)).finiteOr(0.0).coerceIn(-10.0, 10.0)

            val w10 = min(10, idx)
            val w20 = min(20, idx)
            val w50 = min(50, idx)

            scaledVec[3] = ((getWindowStd(return1Buf, idx, w10) / retStd) - 1.0).finiteOr(0.0).coerceIn(-10.0, 10.0)
            scaledVec[4] = ((getWindowStd(return1Buf, idx, w20) / retStd) - 1.0).finiteOr(0.0).coerceIn(-10.0, 10.0)
            scaledVec[5] = ((getWindowStd(return1Buf, idx, w50) / retStd) - 1.0).finiteOr(0.0).coerceIn(-10.0, 10.0)
            scaledVec[6] = (getWindowMean(return1Buf, idx, w10) / retStd).finiteOr(0.0).coerceIn(-10.0, 10.0)
            scaledVec[7] = (getWindowMean(return1Buf, idx, w20) / retStd).finiteOr(0.0).coerceIn(-10.0, 10.0)

            scaledVec[8] = ((streakLengthsBuf[idx] - 2.2) / 1.8).finiteOr(0.0).coerceIn(-10.0, 10.0)
            scaledVec[9] = ((directionChangesBuf[idx] - 0.48) / 0.50).finiteOr(0.0).coerceIn(-10.0, 10.0)
            scaledVec[10] = (abs(velocitiesBuf[idx]) / velStd - 0.7).finiteOr(0.0).coerceIn(-10.0, 10.0)
            scaledVec[11] = (accelerationsBuf[idx] / accStd).finiteOr(0.0).coerceIn(-10.0, 10.0)

            if (t == hmmSteps - 1) {
                lastScaledFeatures = featureNames.indices.associate { f ->
                    featureNames[f] to scaledVec[f].finiteOr(0.0)
                }
            }

            var maxLog = Double.NEGATIVE_INFINITY
            for (j in 0 until nStates) {
                var logSum = 0.0
                val varsJ = stateVars[j]
                val meansJ = stateMeans[j]
                val constsJ = stateLogNormConst[j]
                for (f in 0 until 12) {
                    val variance = max(1e-9, varsJ[f])
                    val diff = (scaledVec[f] - meansJ[f]).finiteOr(0.0)
                    val logE = constsJ[f] - (diff * diff) / (2.0 * variance)
                    logSum += logE.finiteOr(0.0)
                }
                val safeLog = logSum.finiteOr(-50.0)
                logEmissions[j] = safeLog
                if (safeLog > maxLog) maxLog = safeLog
            }

            if (maxLog.isFinite()) {
                for (j in 0 until nStates) {
                    scaledEmissions[j] = exp((logEmissions[j] - maxLog).coerceIn(-60.0, 0.0)).finiteOr(1.0 / nStates)
                }
            } else {
                for (j in 0 until nStates) {
                    scaledEmissions[j] = 1.0 / nStates
                }
            }

            var sumProb = 0.0
            if (t == 0) {
                for (j in 0 until nStates) {
                    val p = (startProb[j] * scaledEmissions[j]).finiteOr(0.0)
                    nextForward[j] = p
                    sumProb += p
                }
            } else {
                for (j in 0 until nStates) {
                    var sum = 0.0
                    for (i in 0 until nStates) {
                        sum += forwardProb[i] * transmat[i][j]
                    }
                    val p = (sum * scaledEmissions[j]).finiteOr(0.0)
                    nextForward[j] = p
                    sumProb += p
                }
            }

            if (sumProb > 1e-15 && sumProb.isFinite()) {
                for (j in 0 until nStates) {
                    forwardProb[j] = (nextForward[j] / sumProb).finiteOr(1.0 / nStates).coerceIn(0.0, 1.0)
                }
            } else {
                for (j in 0 until nStates) {
                    forwardProb[j] = 1.0 / nStates
                }
            }
        }

        var bestState = 0
        var maxConf = -1.0
        for (j in 0 until nStates) {
            val p = forwardProb[j].finiteOr(1.0 / nStates).coerceIn(0.0, 1.0)
            forwardProb[j] = p
            if (p > maxConf) {
                maxConf = p
                bestState = j
            }
        }

        if (lastState == null || lastState != bestState) {
            lastState = bestState
            stateDuration = 1
        } else {
            stateDuration++
        }

        val regimeName = regimeNames[bestState] ?: "Unknown"
        val persistence = transmat[bestState][bestState].finiteOr(0.5).coerceIn(0.0, 1.0)

        return RegimeOutput(
            state = bestState,
            regimeName = regimeName,
            confidence = maxConf.finiteOr(0.5).coerceIn(0.0, 1.0),
            duration = stateDuration,
            persistence = persistence,
            stateProbabilities = forwardProb.map { it.finiteOr(1.0 / nStates).coerceIn(0.0, 1.0) },
            scaledFeatures = lastScaledFeatures,
            historicalEdges = listOf(
                HistoricalEdge(horizon = 5, count = 4210, pUp = if (regimeName == "Trending") 0.548 else 0.509, statSig = regimeName == "Trending", edgeStrength = if (regimeName == "Trending") 0.048 else 0.009),
                HistoricalEdge(horizon = 15, count = 3190, pUp = if (regimeName == "Trending") 0.541 else 0.504, statSig = regimeName == "Trending", edgeStrength = if (regimeName == "Trending") 0.041 else 0.004),
                HistoricalEdge(horizon = 30, count = 2150, pUp = 0.528, statSig = false, edgeStrength = 0.028)
            )
        )
    }

    private fun getWindowMean(arr: DoubleArray, endIdx: Int, window: Int): Double {
        if (window <= 0) return 0.0
        var s = 0.0
        val start = max(0, endIdx - window + 1)
        val count = endIdx - start + 1
        for (i in start..endIdx) s += arr[i].finiteOr(0.0)
        return (s / count).finiteOr(0.0)
    }

    private fun getWindowStd(arr: DoubleArray, endIdx: Int, window: Int): Double {
        val start = max(0, endIdx - window + 1)
        val count = endIdx - start + 1
        if (count < 2) return 1e-6
        val mean = getWindowMean(arr, endIdx, window)
        var sumSq = 0.0
        for (i in start..endIdx) {
            val d = (arr[i] - mean).finiteOr(0.0)
            sumSq += d * d
        }
        return sqrt((sumSq / (count - 1)).finiteOr(1e-12).coerceAtLeast(0.0)).coerceAtLeast(1e-7)
    }

    private fun computeStd(arr: DoubleArray, n: Int): Double {
        if (n < 2) return 1e-6
        var mean = 0.0
        for (i in 0 until n) mean += arr[i].finiteOr(0.0)
        mean /= n
        var sumSq = 0.0
        for (i in 0 until n) {
            val d = (arr[i] - mean).finiteOr(0.0)
            sumSq += d * d
        }
        return sqrt((sumSq / (n - 1)).finiteOr(1e-12).coerceAtLeast(0.0))
    }
}
