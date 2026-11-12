package com.olympquant.terminal

import com.olympquant.terminal.domain.Candle
import com.olympquant.terminal.domain.HardLockedMandate
import com.olympquant.terminal.domain.MarketTelemetryState
import com.olympquant.terminal.domain.RegimeOutput
import com.olympquant.terminal.domain.SRZone
import com.olympquant.terminal.domain.SignalDirection
import com.olympquant.terminal.domain.Tick
import com.olympquant.terminal.domain.TriggerType
import com.olympquant.terminal.domain.finiteOr
import com.olympquant.terminal.engine.CandleEngine
import com.olympquant.terminal.engine.IndicatorEngine
import com.olympquant.terminal.engine.ModularStrategyEngine
import com.olympquant.terminal.engine.RegimeDetectionEngine
import com.olympquant.terminal.network.OlympTradeWsClient
import com.olympquant.terminal.network.WsConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuantPipelineUnitTest {

    @Test
    fun `test FTRL starts neutral and restores only validated persisted state`() {
        val original = ModularStrategyEngine()
        val features = DoubleArray(11).also {
            it[0] = 1.0
            it[1] = 1.0
            it[2] = 0.5
        }
        assertEquals(0.5, original.predictFtrlWinProbability(features), 1e-9)

        original.updateFtrlWeightsOnline(features, 1.0)
        val expectedProbability = original.predictFtrlWinProbability(features)
        val snapshot = original.exportLearningState()

        val restored = ModularStrategyEngine()
        assertTrue(restored.restoreLearningState(snapshot))
        assertEquals(expectedProbability, restored.predictFtrlWinProbability(features), 1e-9)
        assertFalse(restored.restoreLearningState("corrupt|state"))
    }

    @Test
    fun `test recent regime-direction performance uses bounded walk-forward outcomes`() {
        val temporal = com.olympquant.terminal.engine.TemporalIntelligenceEngine()
        repeat(16) {
            temporal.recordShadowOutcome(
                direction = SignalDirection.UP,
                regime = com.olympquant.terminal.domain.TemporalRegimeType.TREND,
                predictedProb = 0.5,
                won = false,
                wasSpikeNoise = false
            )
        }
        assertEquals(16, temporal.recentRegimeDirectionSampleCount(
            com.olympquant.terminal.domain.TemporalRegimeType.TREND,
            SignalDirection.UP
        ))
        assertEquals(0.0, temporal.recentRegimeDirectionWinRate(
            com.olympquant.terminal.domain.TemporalRegimeType.TREND,
            SignalDirection.UP
        )!!, 1e-9)

        // The ring is capped at 32; new outcomes replace the oldest observations.
        repeat(20) {
            temporal.recordShadowOutcome(
                direction = SignalDirection.UP,
                regime = com.olympquant.terminal.domain.TemporalRegimeType.TREND,
                predictedProb = 0.5,
                won = true,
                wasSpikeNoise = false
            )
        }
        assertEquals(32, temporal.recentRegimeDirectionSampleCount(
            com.olympquant.terminal.domain.TemporalRegimeType.TREND,
            SignalDirection.UP
        ))
        assertEquals(0.625, temporal.recentRegimeDirectionWinRate(
            com.olympquant.terminal.domain.TemporalRegimeType.TREND,
            SignalDirection.UP
        )!!, 1e-9)
        assertEquals(0, temporal.recentRegimeDirectionSampleCount(
            com.olympquant.terminal.domain.TemporalRegimeType.CONSOLIDATION,
            SignalDirection.UP
        ))

        // Fingerprint statistics use their own bounded outcome window.
        repeat(16) {
            temporal.recordShadowOutcome(
                direction = SignalDirection.UP,
                regime = com.olympquant.terminal.domain.TemporalRegimeType.TREND,
                predictedProb = 0.5,
                won = false,
                wasSpikeNoise = true,
                fingerprint = ModularStrategyEngine.ShadowFingerprint.SPIKE_DOMINANCE
            )
        }
        assertEquals(16, temporal.recentFingerprintSampleCount(
            ModularStrategyEngine.ShadowFingerprint.SPIKE_DOMINANCE
        ))
        assertEquals(0.0, temporal.recentFingerprintWinRate(
            ModularStrategyEngine.ShadowFingerprint.SPIKE_DOMINANCE
        )!!, 1e-9)
        repeat(20) {
            temporal.recordShadowOutcome(
                direction = SignalDirection.UP,
                regime = com.olympquant.terminal.domain.TemporalRegimeType.TREND,
                predictedProb = 0.5,
                won = true,
                wasSpikeNoise = false,
                fingerprint = ModularStrategyEngine.ShadowFingerprint.SPIKE_DOMINANCE
            )
        }
        assertEquals(32, temporal.recentFingerprintSampleCount(
            ModularStrategyEngine.ShadowFingerprint.SPIKE_DOMINANCE
        ))
        assertEquals(0.625, temporal.recentFingerprintWinRate(
            ModularStrategyEngine.ShadowFingerprint.SPIKE_DOMINANCE
        )!!, 1e-9)
    }

    @Test
    fun `test HardLockedMandate stake sizing enforces 1 min, 5 percent balance, and 4000 max`() {
        assertEquals(1.0, HardLockedMandate.computeLockedStake(0.0), 1e-6)
        assertEquals(1.0, HardLockedMandate.computeLockedStake(10.0), 1e-6)
        assertEquals(1.0, HardLockedMandate.computeLockedStake(19.99), 1e-6)
        assertEquals(5.0, HardLockedMandate.computeLockedStake(100.0), 1e-6)
        assertEquals(500.0, HardLockedMandate.computeLockedStake(10000.0), 1e-6)
        assertEquals(4000.0, HardLockedMandate.computeLockedStake(80000.0), 1e-6)
        assertEquals(4000.0, HardLockedMandate.computeLockedStake(250000.0), 1e-6)
        assertEquals(10, HardLockedMandate.MAX_TRADES_PER_DAY)
        assertEquals(3, HardLockedMandate.MAX_CONSECUTIVE_LOSSES)
    }

    @Test
    fun `test quant indicators, HMM regime inference, and NaN guards`() {
        val candleEngine = CandleEngine()
        val indicatorEngine = IndicatorEngine()
        val regimeEngine = RegimeDetectionEngine()

        val ticks = (0 until 60).map { i ->
            Tick(
                pair = "LATAM_X",
                price = if (i < 40) 4500.0 else 4500.0 + (i - 40) * 0.45,
                timeMs = 1700000000000L + i * 220L
            )
        }
        ticks.forEach { candleEngine.processTick(it) }
        val candles = candleEngine.getAllCandles()
        val ind = indicatorEngine.computeIndicators(ticks, candles)
        val hmm = regimeEngine.processTicks(ticks)
        assertNotNull(hmm)
        assertTrue(hmm!!.stateProbabilities.all { it.isFinite() })

        val nanInjectedHmm = RegimeOutput(
            state = 5,
            regimeName = "Trending",
            confidence = Double.NaN,
            duration = 4,
            persistence = Double.NaN,
            stateProbabilities = List(8) { Double.NaN },
            scaledFeatures = mapOf("return_1" to Double.NaN, "velocity" to Double.POSITIVE_INFINITY),
            historicalEdges = emptyList()
        )
        assertEquals(0.0, nanInjectedHmm.confidence.finiteOr(0.0), 1e-6)
        assertTrue(ind.volatility.isFinite())
    }

    @Test
    fun `test TickRush5s and Online FTRL-Proximal ML Meta-Labeler with 5s virtual shadow learning`() {
        val strategyEngine = ModularStrategyEngine()
        val lockedStrategy = HardLockedMandate.lockedStrategyConfig()
        assertEquals(TriggerType.RAW_TICK_RUSH_5S, lockedStrategy.triggerType)

        // 1. Build 14 ticks of average back-and-forth noise oscillation (+/- 0.15 around 4500.0)
        val baseTs = 1700000000000L
        val noiseTicks = (0 until 14).map { i ->
            val offset = if (i % 2 == 0) 0.15 else -0.15
            Tick(pair = "LATAM_X", price = 4500.0 + offset, timeMs = baseTs + i * 240L)
        }

        val noiseTelemetry = MarketTelemetryState(
            pair = "LATAM_X",
            latestPrice = noiseTicks.last().price,
            tickCount = noiseTicks.size,
            assetPayoutPercent = 85,
            assetLocked = false,
            recentTicks = noiseTicks
        )

        val noiseEval = strategyEngine.evaluateStrategy(
            strategy = lockedStrategy,
            telemetry = noiseTelemetry,
            wsLatencyMs = 38L
        )
        assertEquals(SignalDirection.NONE, noiseEval.rawSignal)
        assertFalse(noiseEval.readyToExecute)

        // 2. Append a smooth, accelerating 4-tick upward rush (+0.42, +0.46, +0.50, +0.54 every 140ms)
        val lastNoiseTime = noiseTicks.last().timeMs
        val lastNoisePrice = noiseTicks.last().price
        val smoothDeltas = listOf(0.42, 0.88, 1.38, 1.92)
        val rushTicks = noiseTicks + smoothDeltas.mapIndexed { idx, cumDelta ->
            Tick(
                pair = "LATAM_X",
                price = lastNoisePrice + cumDelta,
                timeMs = lastNoiseTime + (idx + 1) * 140L
            )
        }

        val rushTelemetry = MarketTelemetryState(
            pair = "LATAM_X",
            latestPrice = rushTicks.last().price,
            tickCount = rushTicks.size,
            assetPayoutPercent = 85,
            assetLocked = false,
            recentTicks = rushTicks
        )

        val rushEval = strategyEngine.evaluateStrategy(
            strategy = lockedStrategy,
            telemetry = rushTelemetry,
            wsLatencyMs = 38L
        )
        assertEquals(SignalDirection.UP, rushEval.rawSignal)
        assertEquals(SignalDirection.UP, rushEval.finalSignal)
        assertTrue(rushEval.confidenceScore >= HardLockedMandate.MIN_CONFIDENCE_REQUIRED)
        assertTrue(rushEval.readyToExecute)

        // 3. Verify Single-Tick Freak Spike Trap (e.g. +0.04, +0.04, +0.04, +1.80 -> dominance > 90%) is BLOCKED
        val spikeDeltas = listOf(0.04, 0.08, 0.12, 1.92)
        val spikeTicks = noiseTicks + spikeDeltas.mapIndexed { idx, cumDelta ->
            Tick(
                pair = "LATAM_X",
                price = lastNoisePrice + cumDelta,
                timeMs = lastNoiseTime + (idx + 1) * 140L
            )
        }
        val spikeEval = strategyEngine.evaluateStrategy(
            strategy = lockedStrategy,
            telemetry = rushTelemetry.copy(
                latestPrice = spikeTicks.last().price,
                tickCount = spikeTicks.size,
                recentTicks = spikeTicks
            ),
            wsLatencyMs = 38L
        )
        assertFalse(spikeEval.readyToExecute)

        // 4. Verify 5-second virtual shadow-learning resolves after 5000ms and increments learned sample count
        val futureTickBeforeHorizon = Tick(
            pair = "LATAM_X",
            price = rushTicks.last().price + 0.95,
            timeMs = rushTicks.last().timeMs + 4800L
        )
        val futureTick = Tick(
            pair = "LATAM_X",
            price = rushTicks.last().price + 1.25,
            timeMs = rushTicks.last().timeMs + 5200L
        )
        val postShadowTicks = rushTicks + listOf(futureTickBeforeHorizon, futureTick)
        val postShadowMetrics = strategyEngine.extractRawTickRushMetrics(
            rushTelemetry.copy(
                latestPrice = futureTick.price,
                latestTimeMs = futureTick.timeMs,
                tickCount = postShadowTicks.size,
                recentTicks = postShadowTicks
            )
        )
        assertTrue(postShadowMetrics.shadowSamplesLearned >= 1)

        // A five-second outcome bracketed only by a six-second reconnect gap is unverifiable.
        val gapEngine = ModularStrategyEngine()
        gapEngine.extractRawTickRushMetrics(rushTelemetry)
        val gapOnlyTick = Tick(
            pair = "LATAM_X",
            price = rushTicks.last().price + 1.25,
            timeMs = rushTicks.last().timeMs + 5200L
        )
        gapEngine.extractRawTickRushMetrics(
            rushTelemetry.copy(
                latestPrice = gapOnlyTick.price,
                latestTimeMs = gapOnlyTick.timeMs,
                tickCount = rushTicks.size + 1,
                recentTicks = rushTicks + gapOnlyTick
            )
        )
        assertEquals(0, gapEngine.learnedSampleCount())

        // 5. Verify Hard Stop blocks execution when 10 trades or 3 consecutive losses are reached
        val hardStoppedEval = strategyEngine.evaluateStrategy(
            strategy = lockedStrategy,
            telemetry = rushTelemetry.copy(hardStopReason = "10/10 Daily Trade Cap Reached (Hard Stop)"),
            wsLatencyMs = 38L
        )
        assertFalse(hardStoppedEval.readyToExecute)
    }

    @Test
    fun `test Scientific 7-Gate Sniper M false-positive rejection pipeline and sole strategy lock`() {
        val lockedStrategy = HardLockedMandate.lockedStrategyConfig()
        assertEquals(HardLockedMandate.STRATEGY_NAME, lockedStrategy.name)
        assertEquals(5, lockedStrategy.durationSeconds)
        assertEquals("TIME_5_BALANCED", lockedStrategy.scriptCode)

        val baseTs = 1700000000000L
        val noiseTicks = (0 until 14).map { i ->
            val offset = if (i % 2 == 0) 0.12 else -0.12
            Tick(pair = "LATAM_X", price = 4500.0 + offset, timeMs = baseTs + i * 240L)
        }
        val lastNoisePrice = noiseTicks.last().price
        val lastNoiseTime = noiseTicks.last().timeMs

        // 1. Clean, accelerating 4-tick surge passes TIME-5 balanced execution gates
        val cleanRushTicks = noiseTicks + listOf(0.38, 0.80, 1.25, 1.74).mapIndexed { idx, cum ->
            Tick(pair = "LATAM_X", price = lastNoisePrice + cum, timeMs = lastNoiseTime + (idx + 1) * 130L)
        }
        val cleanTelemetry = MarketTelemetryState(
            pair = "LATAM_X",
            latestPrice = cleanRushTicks.last().price,
            latestTimeMs = cleanRushTicks.last().timeMs,
            tickCount = cleanRushTicks.size,
            assetPayoutPercent = 85,
            assetLocked = false,
            recentTicks = cleanRushTicks,
            zFast = 4501.5,
            zSlow = 4500.2,
            orderFlowImbalance = 32.0
        )

        val engine = ModularStrategyEngine()
        val cleanEval = engine.evaluateStrategy(
            strategy = lockedStrategy,
            telemetry = cleanTelemetry,
            wsLatencyMs = 45L
        )
        assertEquals(HardLockedMandate.STRATEGY_NAME, cleanEval.strategyName)
        assertEquals(6, cleanEval.filterDiagnostics.size)
        assertTrue("Clean accelerating surge must pass TIME-5 balanced execution: ${cleanEval.triggerReason}", cleanEval.readyToExecute)

        // 2. Opposing Counter-Impulse Trap (impulse reversed on latest tick -> rejected by Opposing Impulse Guard)
        val opposingCounterTicks = noiseTicks + listOf(0.40, 0.90, 1.50, 0.70).mapIndexed { idx, cum ->
            Tick(pair = "LATAM_X", price = lastNoisePrice + cum, timeMs = lastNoiseTime + (idx + 1) * 130L)
        }
        val opposingEval = ModularStrategyEngine().evaluateStrategy(
            strategy = lockedStrategy,
            telemetry = cleanTelemetry.copy(
                latestPrice = opposingCounterTicks.last().price,
                latestTimeMs = opposingCounterTicks.last().timeMs,
                tickCount = opposingCounterTicks.size,
                recentTicks = opposingCounterTicks
            ),
            wsLatencyMs = 45L
        )
        assertFalse("Opposing counter-impulse on latest observation must be rejected", opposingEval.readyToExecute)

        // 3. Low Directional Efficiency / Choppy Oscillation Trap (whipsawing steps with DE < 0.45)
        val choppyTicks = noiseTicks + listOf(0.40, -0.30, 0.35, -0.25).mapIndexed { idx, cum ->
            Tick(pair = "LATAM_X", price = lastNoisePrice + cum, timeMs = lastNoiseTime + (idx + 1) * 130L)
        }
        val choppyEval = ModularStrategyEngine().evaluateStrategy(
            strategy = lockedStrategy,
            telemetry = cleanTelemetry.copy(
                latestPrice = choppyTicks.last().price,
                latestTimeMs = choppyTicks.last().timeMs,
                tickCount = choppyTicks.size,
                recentTicks = choppyTicks
            ),
            wsLatencyMs = 45L
        )
        assertFalse("Choppy oscillation with DE < 0.45 must be rejected by TIME-5", choppyEval.readyToExecute)
    }

    @Test
    fun `test X13 Temporal Intelligence Engine rolling windows hysteresis N-of-M persistence and 3-layer architecture`() {
        val x13Strategy = HardLockedMandate.lockedStrategyConfig()
        assertEquals(HardLockedMandate.STRATEGY_NAME, x13Strategy.name)
        assertEquals(5, x13Strategy.durationSeconds)


        val engine = ModularStrategyEngine()
        val baseTs = System.currentTimeMillis() - 4000L

        // 1. Feed 14 baseline ticks of normal oscillation
        val noiseTicks = (0 until 14).map { i ->
            val offset = if (i % 2 == 0) 0.14 else -0.14
            Tick(pair = "LATAM_X", price = 4500.0 + offset, timeMs = baseTs + i * 200L)
        }

        // 2. Feed 3 consecutive accelerating upward rush frames so N-of-M persistence (2-of-5) and Hysteresis latch
        val lastNoiseTs = noiseTicks.last().timeMs
        val lastNoisePrice = noiseTicks.last().price
        val rushStepDeltas = listOf(0.40, 0.85, 1.35, 1.90, 2.50, 3.15)

        var lastEval: com.olympquant.terminal.domain.StrategyEvaluation? = null
        for (frame in 4..6) {
            val frameTicks = noiseTicks + rushStepDeltas.take(frame).mapIndexed { idx, cum ->
                Tick(
                    pair = "LATAM_X",
                    price = lastNoisePrice + cum,
                    timeMs = lastNoiseTs + (idx + 1) * 130L
                )
            }
            val telemetry = MarketTelemetryState(
                pair = "LATAM_X",
                latestPrice = frameTicks.last().price,
                latestTimeMs = frameTicks.last().timeMs,
                tickCount = frameTicks.size,
                assetPayoutPercent = 85,
                assetLocked = false,
                orderFlowImbalance = 0.42,
                consistency = 0.82,
                recentTicks = frameTicks
            )
            lastEval = engine.evaluateStrategy(
                strategy = x13Strategy,
                telemetry = telemetry,
                wsLatencyMs = 35L
            )
        }

        assertNotNull(lastEval)
        assertEquals(SignalDirection.UP, lastEval!!.finalSignal)
        assertTrue(lastEval.temporalTelemetry.eligibilityLayerPassed)
        assertTrue(lastEval.temporalTelemetry.signalLayerScore >= 60.0)
        assertEquals(0.0, lastEval.temporalTelemetry.validationLayerScore, 1e-6)
        assertTrue(lastEval.temporalTelemetry.momentumHysteresisActive)
        assertTrue(lastEval.temporalTelemetry.persistenceCount5 >= 2)
        assertTrue(lastEval.readyToExecute)

        // 3. Test Schmitt-Trigger Hysteresis directly: enter >= 65, hold at 55 (>= 50), release at 45 (< 50)
        val temporal = engine.temporalEngine
        assertTrue(temporal.evaluateHysteresis("unit_test_hyst", 68.0, 65.0, 50.0))
        assertTrue(temporal.evaluateHysteresis("unit_test_hyst", 54.0, 65.0, 50.0)) // Held by hysteresis!
        assertFalse(temporal.evaluateHysteresis("unit_test_hyst", 46.0, 65.0, 50.0)) // Released below exit threshold
    }

    @Test
    fun `test WebSocket exponential backoff, disconnect-reconnect deduplication, snapshot sync gating, and high-frequency stress`() {
        // 1. Exponential Backoff with Jitter verification
        val b1 = OlympTradeWsClient.computeReconnectBackoffMs(attempt = 1, jitterMs = 200L)
        val b2 = OlympTradeWsClient.computeReconnectBackoffMs(attempt = 2, jitterMs = 200L)
        val b3 = OlympTradeWsClient.computeReconnectBackoffMs(attempt = 3, jitterMs = 200L)
        val b4 = OlympTradeWsClient.computeReconnectBackoffMs(attempt = 4, jitterMs = 200L)
        val b8 = OlympTradeWsClient.computeReconnectBackoffMs(attempt = 8, jitterMs = 300L)
        assertEquals(1200L, b1) // 1000 * 2^0 + 200
        assertEquals(2200L, b2) // 1000 * 2^1 + 200
        assertEquals(4200L, b3) // 1000 * 2^2 + 200
        assertEquals(8200L, b4) // 1000 * 2^3 + 200
        assertEquals(30300L, b8) // Capped at 30_000 + 300 jitter

        // 2. WebSocket tick deduplication & snapshot synchronization lifecycle across disconnect/reconnect
        val wsClient = OlympTradeWsClient()
        assertEquals(WsConnectionStatus.DISCONNECTED, wsClient.connectionStatus.value)
        assertFalse(wsClient.isStreamSynchronized.value)

        val t0 = 1700000000000L
        assertTrue(wsClient.acceptAndRecordTick("LATAM_X", 4510.0, t0))
        // Exact duplicate tick (same timestamp and price) must be rejected
        assertFalse(wsClient.acceptAndRecordTick("LATAM_X", 4510.0, t0))
        // Out-of-order stale tick (older timestamp) must be rejected
        assertFalse(wsClient.acceptAndRecordTick("LATAM_X", 4510.5, t0 - 50L))
        // Wrong asset pair tick must be rejected
        assertFalse(wsClient.acceptAndRecordTick("EURUSD", 1.0850, t0 + 100L))

        // Feed ticks 2..5 -> still warming up (< 6 sequential ticks without historical seed)
        for (i in 1..4) {
            assertTrue(wsClient.acceptAndRecordTick("LATAM_X", 4510.0 + i * 0.2, t0 + i * 180L))
        }
        assertFalse(wsClient.isStreamSynchronized.value)

        // 6th valid sequential tick completes snapshot synchronization and computes positive tickRateTps
        assertTrue(wsClient.acceptAndRecordTick("LATAM_X", 4511.2, t0 + 5 * 180L))
        assertTrue(wsClient.isStreamSynchronized.value)
        assertTrue(wsClient.tickRateTps.value > 0.0)

        // Disconnect resets synchronization and tick rate cleanly
        wsClient.disconnect()
        assertEquals(WsConnectionStatus.DISCONNECTED, wsClient.connectionStatus.value)
        assertFalse(wsClient.isStreamSynchronized.value)
        assertEquals(0.0, wsClient.tickRateTps.value, 1e-6)

        // 3. Verify strategy evaluation blocks execution when snapshot is not yet synchronized after reconnect
        val engine = ModularStrategyEngine()
        val strategy = HardLockedMandate.lockedStrategyConfig()
        val noiseTicks = (0 until 14).map { i ->
            val offset = if (i % 2 == 0) 0.15 else -0.15
            Tick(pair = "LATAM_X", price = 4500.0 + offset, timeMs = t0 + i * 240L)
        }
        val rushTicks = noiseTicks + listOf(0.42, 0.88, 1.38, 1.92).mapIndexed { idx, cum ->
            Tick(pair = "LATAM_X", price = noiseTicks.last().price + cum, timeMs = noiseTicks.last().timeMs + (idx + 1) * 140L)
        }

        val unsyncedEval = engine.evaluateStrategy(
            strategy = strategy,
            telemetry = MarketTelemetryState(
                pair = "LATAM_X",
                latestPrice = rushTicks.last().price,
                tickCount = rushTicks.size,
                assetPayoutPercent = 85,
                assetLocked = false,
                wsSynchronized = false, // Reconnecting / unsynchronized snapshot
                recentTicks = rushTicks
            ),
            wsLatencyMs = 38L
        )
        assertFalse(unsyncedEval.temporalTelemetry.snapshotSynchronized)
        assertFalse(unsyncedEval.temporalTelemetry.eligibilityLayerPassed)
        assertFalse(unsyncedEval.readyToExecute)

        // Once wsSynchronized becomes true, the identical snapshot is eligible and readyToExecute
        val syncedEval = engine.evaluateStrategy(
            strategy = strategy,
            telemetry = MarketTelemetryState(
                pair = "LATAM_X",
                latestPrice = rushTicks.last().price,
                tickCount = rushTicks.size,
                assetPayoutPercent = 85,
                assetLocked = false,
                wsSynchronized = true,
                recentTicks = rushTicks
            ),
            wsLatencyMs = 38L
        )
        assertTrue(syncedEval.temporalTelemetry.snapshotSynchronized)
        assertTrue(syncedEval.readyToExecute)

        // 4. High-frequency 250-tick stress test
        val rollingBuffer = ArrayDeque<Tick>(300)
        for (step in 0 until 250) {
            val tick = Tick(
                pair = "LATAM_X",
                price = 4500.0 + kotlin.math.sin(step * 0.25) * 0.4 + (step * 0.02),
                timeMs = t0 + step * 120L
            )
            rollingBuffer.addLast(tick)
            if (rollingBuffer.size > 120) rollingBuffer.removeFirst()
            if (step >= 15 && step % 15 == 0) {
                val snapEval = engine.evaluateStrategy(
                    strategy = strategy,
                    telemetry = MarketTelemetryState(
                        pair = "LATAM_X",
                        latestPrice = tick.price,
                        latestTimeMs = tick.timeMs,
                        tickCount = rollingBuffer.size,
                        recentTicks = rollingBuffer.toList()
                    ),
                    wsLatencyMs = 32L
                )
                assertTrue(snapEval.confidenceScore.isFinite())
                assertTrue(snapEval.temporalTelemetry.signalLayerScore.isFinite())
            }
        }
    }

    @Test
    fun `test Sniper M five-second lock, strict mathematical filters, HV Window states, and chop and whipsaw guards`() {
        // 1. Sniper M has one immutable five-second execution horizon.
        val lockedConfig = HardLockedMandate.lockedStrategyConfig()
        assertEquals(HardLockedMandate.STRATEGY_NAME, lockedConfig.name)
        assertEquals(5, lockedConfig.durationSeconds)
        assertEquals("TIME_5_BALANCED", lockedConfig.scriptCode)

        // 2. Verify Clean Breakout Momentum -> HV Window EXCELLENT (>=75%) and all 6 HighVoltage filters PASS
        val candleEngine = CandleEngine()
        val indicatorEngine = IndicatorEngine()
        val baseTimeMs = 1700000000000L

        // Generate 65 seconds of clean, steady, solid-body upward breakout ticks (~4 ticks/sec)
        val cleanBreakoutTicks = mutableListOf<Tick>()
        var candles = emptyList<Candle>()
        var price = 4500.00
        for (step in 0..260) {
            val stepMs = baseTimeMs + step * 250L
            price += 0.045 // Steady directional climb -> 60s range ~ 10.8 points, solid bodies, zero collapse wicks
            val tick = Tick(pair = "LATAM_X", price = price, timeMs = stepMs)
            cleanBreakoutTicks.add(tick)
            candles = candleEngine.processTick(tick)
        }

        val indClean = indicatorEngine.computeIndicators(
            ticks = cleanBreakoutTicks.takeLast(120),
            candles = candles,
            swings = emptyList(),
            psychLevels = emptyList(),
            nearestSupport = null,
            nearestResistance = null
        )

        // Check all 6 strict HighVoltage mathematical filters:
        assertEquals(SignalDirection.UP, indClean.highVoltageStatus.trendDirection)
        assertTrue("Filter 1 (ZLEMA 10 > 30) must pass", indClean.highVoltageStatus.trendFilterPassed)
        assertTrue("Filter 2 (Velocity >= 1.5 T/s) must pass", indClean.highVoltageStatus.velocityFilterPassed)
        assertTrue("Filter 3 (relPos >= 80%) must pass", indClean.highVoltageStatus.breakoutFilterPassed)
        assertTrue("Filter 4 (netDelta5 > 0) must pass", indClean.highVoltageStatus.momentumFilterPassed)
        assertTrue("Filter 5 (S/R Wall >= 0.25pt) must pass", indClean.highVoltageStatus.srObstaclePassed)
        assertTrue("Filter 6 (Candle volatility regime) must pass", indClean.highVoltageStatus.volatilityFilterPassed)
        assertTrue("All 6 strict filters must pass", indClean.highVoltageStatus.allSixFiltersPassed)
        assertEquals(6, indClean.highVoltageStatus.passedFilterCount)

        // Check HV Window state on clean breakout:
        assertTrue("HV Window must be EXCELLENT (>= 75%), got ${indClean.hvWindow.score}", indClean.hvWindow.score >= 75.0)
        assertEquals(com.olympquant.terminal.domain.HvWindowState.EXCELLENT, indClean.hvWindow.state)
        assertFalse(indClean.hvWindow.tightChopDetected)
        assertFalse(indClean.hvWindow.crazyWhipsawDetected)

        // Evaluate HighVoltage5s_HVWindow strategy across expanded 30s timeframe:
        val strategyEngine = ModularStrategyEngine()
        val hv30sStrategy = HardLockedMandate.lockedStrategyConfig()
        val cleanTelemetry = MarketTelemetryState(
            pair = "LATAM_X",
            latestPrice = cleanBreakoutTicks.last().price,
            latestTimeMs = cleanBreakoutTicks.last().timeMs,
            tickCount = cleanBreakoutTicks.size,
            candles = candles.takeLast(80),
            recentTicks = cleanBreakoutTicks.takeLast(120),
            zFast = indClean.zFast,
            zSlow = indClean.zSlow,
            trend = indClean.trend,
            persistence = indClean.persistence,
            consistency = indClean.consistency,
            volatility = indClean.volatility,
            velocity = indClean.velocity,
            slope = indClean.slope,
            slopeRatio = indClean.slopeRatio,
            netDelta5 = indClean.netDelta5,
            relPos = indClean.relPos,
            hvoiScore = indClean.hvoiScore,
            microstructure = indClean.microstructure,
            assetPayoutPercent = 85,
            assetLocked = false,
            wsSynchronized = true,
            hvWindow = indClean.hvWindow,
            highVoltageStatus = indClean.highVoltageStatus,
            selectedTimeframeSec = 30
        )
        val hvEval = strategyEngine.evaluateStrategy(
            strategy = hv30sStrategy,
            telemetry = cleanTelemetry,
            wsLatencyMs = 35L
        )
        assertEquals(SignalDirection.UP, hvEval.finalSignal)
        assertTrue("HighVoltage 30s must be readyToExecute in EXCELLENT HV Window", hvEval.readyToExecute)

        // 3. Verify S/R Filters are removed: Resistance 0.12 points ahead does NOT block Sniper M
        val nearbyResistance = SRZone(
            centerPrice = cleanBreakoutTicks.last().price + 0.12,
            lowerBound = cleanBreakoutTicks.last().price + 0.07,
            upperBound = cleanBreakoutTicks.last().price + 0.17,
            touchCount = 4,
            lastTouchTimeSec = baseTimeMs / 1000L,
            isSupport = false
        )
        val indSrUnblocked = indicatorEngine.computeIndicators(
            ticks = cleanBreakoutTicks.takeLast(120),
            candles = candles,
            swings = emptyList(),
            psychLevels = emptyList(),
            nearestSupport = null,
            nearestResistance = nearbyResistance
        )
        assertTrue("Sniper M has no S/R obstacle filter blocking surges", indSrUnblocked.highVoltageStatus.srObstaclePassed)
        assertTrue(indSrUnblocked.highVoltageStatus.allSixFiltersPassed)

        // 4. Verify HV Window TIGHT_CHOP Trap (< 0.20 points across 15s, 30s, 45s, 60s anchors)
        val chopCandleEngine = CandleEngine()
        val chopIndicatorEngine = IndicatorEngine()
        val chopTicks = mutableListOf<Tick>()
        var chopCandles = emptyList<Candle>()
        for (step in 0..250) {
            val stepMs = baseTimeMs + step * 250L
            // Oscillate tightly within 0.04 points total (< 0.20 pt threshold)
            val chopPrice = 4500.0 + if (step % 2 == 0) 0.02 else -0.02
            val tick = Tick(pair = "LATAM_X", price = chopPrice, timeMs = stepMs)
            chopTicks.add(tick)
            chopCandles = chopCandleEngine.processTick(tick)
        }
        val indChop = chopIndicatorEngine.computeIndicators(
            ticks = chopTicks.takeLast(120),
            candles = chopCandles,
            swings = emptyList(),
            psychLevels = emptyList()
        )
        assertTrue("TIGHT_CHOP must be detected when 60s anchor range < 0.20 pts", indChop.hvWindow.tightChopDetected)
        assertEquals(com.olympquant.terminal.domain.HvWindowState.AVOID, indChop.hvWindow.state)
        assertTrue("HV Window score must drop < 50% in TIGHT_CHOP, got ${indChop.hvWindow.score}", indChop.hvWindow.score < 50.0)
        assertTrue(indChop.hvWindow.pauseImmediately)

        // 5. Verify HV Window CRAZY_WHIPSAW Trap (Active + last 4 completed candles collapsing with > 55% wicks)
        val whipsawIndicatorEngine = IndicatorEngine()
        val whipsawCandles = (0 until 5).map { idx ->
            val openP = 4500.0 + idx * 0.1
            // Shoot up +1.5 points and collapse right back near open (body = 0.08, upper wick = 1.42 -> 94.6% wick > 55%)
            Candle(
                timeSec = (baseTimeMs / 1000L) + idx * 5L,
                open = openP,
                high = openP + 1.50,
                low = openP - 0.02,
                close = openP + 0.08,
                tickCount = 18
            )
        }
        val whipsawTicks = (0..40).map { step ->
            Tick(pair = "LATAM_X", price = 4500.0 + (step % 3) * 0.4, timeMs = baseTimeMs + 20_000L + step * 200L)
        }
        val indWhipsaw = whipsawIndicatorEngine.computeIndicators(
            ticks = whipsawTicks,
            candles = whipsawCandles,
            swings = emptyList(),
            psychLevels = emptyList()
        )
        assertTrue("CRAZY_WHIPSAW must be detected when candles leave >55% collapse wicks", indWhipsaw.hvWindow.crazyWhipsawDetected)
        assertEquals(com.olympquant.terminal.domain.HvWindowState.AVOID, indWhipsaw.hvWindow.state)
        assertTrue("HV Window score must drop < 50% in CRAZY_WHIPSAW, got ${indWhipsaw.hvWindow.score}", indWhipsaw.hvWindow.score < 50.0)
        assertTrue(indWhipsaw.hvWindow.pauseImmediately)
    }

    @Test
    fun `test FX S_R scale clearance, bearish HMM symmetry, dynamic DSL window functions, and non-mutating symbol snapshot`() {
        val baseTimeMs = 1700000000000L
        val candleEngine = CandleEngine()
        val indicatorEngine = IndicatorEngine()
        val strategyEngine = ModularStrategyEngine()

        // 1. FX Pair (EURUSD ~ 1.0850) with Resistance 0.0012 ahead (> 0.00025 minClearance, < 0.25 index scale)
        val fxTicks = mutableListOf<Tick>()
        var fxCandles = emptyList<Candle>()
        var fxPrice = 1.08500
        for (step in 0..260) {
            val stepMs = baseTimeMs + step * 250L
            fxPrice += 0.00004 // Steady 0.4-pip per tick climb
            val tick = Tick(pair = "EURUSD", price = fxPrice, timeMs = stepMs)
            fxTicks.add(tick)
            fxCandles = candleEngine.processTick(tick)
        }
        val fxResistance = SRZone(
            centerPrice = fxTicks.last().price + 0.0012, // 12 pips ahead (4.8x above 2.5-pip FX requirement)
            lowerBound = fxTicks.last().price + 0.0010,
            upperBound = fxTicks.last().price + 0.0014,
            touchCount = 3,
            lastTouchTimeSec = baseTimeMs / 1000L,
            isSupport = false
        )
        val indFx = indicatorEngine.computeIndicators(
            ticks = fxTicks.takeLast(120),
            candles = fxCandles,
            swings = emptyList(),
            psychLevels = emptyList(),
            nearestSupport = null,
            nearestResistance = fxResistance
        )
        assertTrue("FX S/R obstacle filter must pass when clearance is 0.0012 >= 0.00025", indFx.highVoltageStatus.srObstaclePassed)
        val fxTelemetry = MarketTelemetryState(
            pair = "EURUSD",
            latestPrice = fxTicks.last().price,
            latestTimeMs = fxTicks.last().timeMs,
            tickCount = fxTicks.size,
            candles = fxCandles.takeLast(80),
            recentTicks = fxTicks.takeLast(120),
            zFast = indFx.zFast,
            zSlow = indFx.zSlow,
            trend = indFx.trend,
            persistence = indFx.persistence,
            consistency = indFx.consistency,
            volatility = indFx.volatility,
            velocity = indFx.velocity,
            slope = indFx.slope,
            slopeRatio = indFx.slopeRatio,
            netDelta5 = indFx.netDelta5,
            relPos = indFx.relPos,
            hvoiScore = indFx.hvoiScore,
            microstructure = indFx.microstructure,
            assetPayoutPercent = 85,
            assetLocked = false,
            wsSynchronized = true,
            hvWindow = indFx.hvWindow,
            highVoltageStatus = indFx.highVoltageStatus,
            selectedTimeframeSec = 5
        )
        val fxEval = strategyEngine.evaluateStrategy(
            strategy = HardLockedMandate.lockedStrategyConfig(),
            telemetry = fxTelemetry,
            wsLatencyMs = 28L
        )
        assertTrue("HighVoltage Gate 5 must pass for FX pair with 0.0012 clearance", fxEval.filterDiagnostics.all { it.passed })
        assertTrue("FX breakout must be readyToExecute", fxEval.readyToExecute)

        // 2. Bearish HMM Regime Symmetry: steady bearish downtrend should classify as Trending or Expansion
        val regimeEngine = RegimeDetectionEngine()
        val bearishTicks = (0 until 80).map { i ->
            Tick(pair = "LATAM_X", price = 4500.0 - i * 0.35, timeMs = baseTimeMs + i * 200L)
        }
        val bearishRegime = regimeEngine.processTicks(bearishTicks)
        assertNotNull(bearishRegime)
        assertTrue(
            "Bearish trend must classify as Trending or Expansion, got ${bearishRegime!!.regimeName}",
            bearishRegime.regimeName == "Trending" || bearishRegime.regimeName == "Expansion"
        )

        // 3. Verify non-mutating getLiveSymbolSnapshot and Studio aliases (ofi_imbalance, velocity, sr_min_clearance)
        val countBefore = strategyEngine.temporalEngine.rollingMean("rush_snr", 4)
        val snapshotSymbols = strategyEngine.getLiveSymbolSnapshot(
            telemetry = fxTelemetry.copy(orderFlowImbalance = 44.5),
            wsLatencyMs = 28L
        )
        val countAfter = strategyEngine.temporalEngine.rollingMean("rush_snr", 4)
        assertEquals("getLiveSymbolSnapshot must not mutate rolling window state", countBefore, countAfter, 1e-9)
        assertEquals(44.5, snapshotSymbols["ofi_imbalance"] ?: 0.0, 1e-6)
        assertEquals(0.00025, snapshotSymbols["sr_min_clearance"] ?: 0.0, 1e-7)
        assertTrue((snapshotSymbols["velocity"] ?: 0.0) > 0.0)
    }

    @Test
    fun `test Bidirectional Autopilot Guard, Single Trade Lock, Chrono Sniper 1_Hr Mode, and Foreground KeepAlive`() {
        // 1. Foreground service keep-alive during Autopilot Standby (AVOID) and Chrono Sniper 1/Hr
        val idleSnap = com.olympquant.terminal.service.ForegroundExecutionSnapshot(
            botRunning = false,
            autopilotArmed = false,
            autopilotStandby = false,
            hourlySniperMode = false,
            singleTradeLocked = false
        )
        assertFalse(idleSnap.shouldKeepServiceAlive)

        val avoidStandbySnap = idleSnap.copy(autopilotArmed = true, autopilotStandby = true)
        assertTrue("Foreground service must stay alive during AVOID standby to auto-start on MODERATE/GOOD", avoidStandbySnap.shouldKeepServiceAlive)

        val sniperSnap = idleSnap.copy(hourlySniperMode = true, hourlyTradesThisHour = 1, nextHourlyResetMinutes = 42)
        assertTrue("Foreground service must stay alive in 24/7 Chrono Sniper 1/Hr mode", sniperSnap.shouldKeepServiceAlive)

        val singleTradeSnap = idleSnap.copy(singleTradeLocked = true)
        assertTrue("Foreground service must stay alive while 1 active trade is in-flight", singleTradeSnap.shouldKeepServiceAlive)

        // 2. Verify HV Window transitions from AVOID (<50%) to MODERATE (50-74%) and EXCELLENT (>=75%)
        val baseTimeMs = 1700000000000L
        val candleEngine = CandleEngine()
        val indicatorEngine = IndicatorEngine()

        // Step A: Feed tight chop -> AVOID (<50%)
        val chopTicks = mutableListOf<Tick>()
        var candles = emptyList<Candle>()
        for (step in 0..250) {
            val tick = Tick(
                pair = "LATAM_X",
                price = 4500.0 + if (step % 2 == 0) 0.015 else -0.015,
                timeMs = baseTimeMs + step * 250L
            )
            chopTicks.add(tick)
            candles = candleEngine.processTick(tick)
        }
        val avoidInd = indicatorEngine.computeIndicators(chopTicks.takeLast(120), candles)
        assertEquals(com.olympquant.terminal.domain.HvWindowState.AVOID, avoidInd.hvWindow.state)
        assertTrue(avoidInd.hvWindow.pauseImmediately)

        // Step B: Feed clean directional breakout -> recovers to MODERATE and then EXCELLENT
        var recoveredModerateOrGood = false
        var price = 4500.0
        val lastChopMs = chopTicks.last().timeMs
        for (step in 1..260) {
            price += 0.05
            val tick = Tick(pair = "LATAM_X", price = price, timeMs = lastChopMs + step * 250L)
            chopTicks.add(tick)
            candles = candleEngine.processTick(tick)
            if (step % 20 == 0) {
                val ind = indicatorEngine.computeIndicators(chopTicks.takeLast(120), candles)
                if (!ind.hvWindow.pauseImmediately &&
                    (ind.hvWindow.state == com.olympquant.terminal.domain.HvWindowState.MODERATE ||
                        ind.hvWindow.state == com.olympquant.terminal.domain.HvWindowState.EXCELLENT) &&
                    ind.hvWindow.score >= 50.0
                ) {
                    recoveredModerateOrGood = true
                }
            }
        }
        assertTrue("HV Window must recover from AVOID to MODERATE/EXCELLENT for Autopilot Auto-Start", recoveredModerateOrGood)
    }

    @Test
    fun `test Audited Mathematical Guards block context traps and settle Sniper M shadow learning at five seconds`() {
        val baseTimeMs = 1700000000000L
        val candleEngine = CandleEngine()
        val indicatorEngine = IndicatorEngine()
        val strategyEngine = ModularStrategyEngine()
        val hvStrategy = HardLockedMandate.lockedStrategyConfig()

        // 1. Build clean breakout baseline
        val cleanTicks = mutableListOf<Tick>()
        var candles = emptyList<Candle>()
        var price = 4500.0
        for (step in 0..260) {
            price += 0.045
            val tick = Tick(pair = "LATAM_X", price = price, timeMs = baseTimeMs + step * 250L)
            cleanTicks.add(tick)
            candles = candleEngine.processTick(tick)
        }
        val indClean = indicatorEngine.computeIndicators(cleanTicks.takeLast(120), candles)
        val baseTelemetry = MarketTelemetryState(
            pair = "LATAM_X",
            latestPrice = cleanTicks.last().price,
            latestTimeMs = cleanTicks.last().timeMs,
            tickCount = cleanTicks.size,
            candles = candles.takeLast(80),
            recentTicks = cleanTicks.takeLast(120),
            zFast = indClean.zFast,
            zSlow = indClean.zSlow,
            trend = indClean.trend,
            persistence = indClean.persistence,
            consistency = indClean.consistency,
            volatility = indClean.volatility,
            velocity = indClean.velocity,
            slope = indClean.slope,
            slopeRatio = indClean.slopeRatio,
            netDelta5 = indClean.netDelta5,
            relPos = indClean.relPos,
            hvoiScore = indClean.hvoiScore,
            microstructure = indClean.microstructure,
            assetPayoutPercent = 85,
            assetLocked = false,
            wsSynchronized = true,
            hvWindow = indClean.hvWindow,
            highVoltageStatus = indClean.highVoltageStatus,
            selectedTimeframeSec = 15
        )

        // Verify clean setup is readyToExecute when contextBlockReasons is empty
        val cleanEval = strategyEngine.evaluateStrategy(hvStrategy, baseTelemetry, wsLatencyMs = 30L)
        assertTrue("Clean breakout with zero context blocks must be readyToExecute", cleanEval.readyToExecute)

        // 2. Verify ContextEnhancer structural block (e.g. Bearish Rejection Wick or Counter Order-Flow) strictly blocks execution!
        val contextBlockedEval = strategyEngine.evaluateStrategy(
            strategy = hvStrategy,
            telemetry = baseTelemetry.copy(
                contextBlockReasons = listOf("Bearish Rejection Wick (68%)")
            ),
            wsLatencyMs = 30L
        )
        assertFalse("Non-empty contextBlockReasons must strictly block readyToExecute", contextBlockedEval.readyToExecute)
        assertEquals(SignalDirection.NONE, contextBlockedEval.finalSignal)
        assertTrue(
            "filterDiagnostics must include Context & Structural Guard when blocked",
            contextBlockedEval.filterDiagnostics.any { it.gateTitle == "Context & Structural Guard" && !it.passed }
        )

        // 3. Verify reversed smoothed tick direction (zFast < zSlow while ticks move UP) is rejected by Sniper M
        val unalignedSmoothEval = strategyEngine.evaluateStrategy(
            strategy = hvStrategy,
            telemetry = baseTelemetry.copy(
                zFast = 4498.0,
                zSlow = 4505.0
            ),
            wsLatencyMs = 30L
        )
        assertFalse("Momentum opposing smoothed tick direction must not execute", unalignedSmoothEval.readyToExecute)

        // 4. Sniper M labels only a well-bracketed exact five-second horizon.
        // Ticks at +4.8s and +5.2s provide a valid interpolation bracket around expiry.
        val recentCleanTicks = cleanTicks.takeLast(120)
        val tickBefore5s = Tick(
            pair = "LATAM_X",
            price = recentCleanTicks.last().price + 0.6,
            timeMs = recentCleanTicks.last().timeMs + 4_800L
        )
        val tickAt6s = Tick(
            pair = "LATAM_X",
            price = recentCleanTicks.last().price + 0.8,
            timeMs = recentCleanTicks.last().timeMs + 5_200L
        )
        val metricsAt6s = strategyEngine.extractRawTickRushMetrics(
            MarketTelemetryState(
                pair = "LATAM_X",
                latestPrice = tickAt6s.price,
                latestTimeMs = tickAt6s.timeMs,
                tickCount = recentCleanTicks.size + 2,
                recentTicks = recentCleanTicks + listOf(tickBefore5s, tickAt6s),
                selectedTimeframeSec = 15
            )
        )
        assertTrue("Sniper M must settle a bracketed five-second shadow at the first post-horizon tick", metricsAt6s.shadowSamplesLearned >= 1)
    }

    @Test
    fun `test Sniper M analyzes tick stream smooths trajectory follows momentum and rides UP and DOWN surges with zero filter blocks`() {
        val strategyEngine = ModularStrategyEngine()
        val sniperStrategy = HardLockedMandate.lockedStrategyConfig()
        assertEquals(HardLockedMandate.STRATEGY_NAME, sniperStrategy.name)

        val contextEnhancer = com.olympquant.terminal.engine.ContextEnhancer()
        val candleEngine = CandleEngine()
        val indicatorEngine = IndicatorEngine()
        val baseTimeMs = 1700000000000L

        // 1. UPWARD TICK STREAM SURGE:
        //    - 20 baseline ticks oscillating mildly around 4500.0
        //    - Followed by a smooth, accelerating 6-tick upward surge (+0.35, +0.75, +1.20, +1.70, +2.25, +2.85)
        val baseUpTicks = (0 until 20).map { i ->
            val noise = if (i % 2 == 0) 0.08 else -0.08
            Tick(pair = "LATAM_X", price = 4500.0 + noise, timeMs = baseTimeMs + i * 220L)
        }
        val upLastTs = baseUpTicks.last().timeMs
        val upLastPrice = baseUpTicks.last().price
        val upSurgeDeltas = listOf(0.35, 0.75, 1.20, 1.70, 2.25, 2.85)
        val upTicks = baseUpTicks + upSurgeDeltas.mapIndexed { idx, cum ->
            Tick(pair = "LATAM_X", price = upLastPrice + cum, timeMs = upLastTs + (idx + 1) * 140L)
        }
        var upCandles = emptyList<Candle>()
        upTicks.forEach { upCandles = candleEngine.processTick(it) }
        val indUp = indicatorEngine.computeIndicators(upTicks, upCandles)

        // Even with a tight resistance zone right above price, ContextEnhancer must produce 0 blocks
        val tightResistance = SRZone(
            centerPrice = upTicks.last().price + 0.05,
            lowerBound = upTicks.last().price + 0.01,
            upperBound = upTicks.last().price + 0.10,
            touchCount = 5,
            lastTouchTimeSec = baseTimeMs / 1000L,
            isSupport = false
        )
        val (upBlocks, upScore) = contextEnhancer.evaluateContext(
            price = upTicks.last().price,
            indicators = indUp,
            candles = upCandles,
            swings = emptyList(),
            nearestSupport = null,
            nearestResistance = tightResistance,
            corridorWidth = 1.0
        )
        assertTrue("ContextEnhancer must have zero strategy filter blocks: $upBlocks", upBlocks.isEmpty())

        val upEval = strategyEngine.evaluateStrategy(
            strategy = sniperStrategy,
            telemetry = MarketTelemetryState(
                pair = "LATAM_X",
                latestPrice = upTicks.last().price,
                latestTimeMs = upTicks.last().timeMs,
                tickCount = upTicks.size,
                candles = upCandles,
                recentTicks = upTicks,
                zFast = indUp.zFast,
                zSlow = indUp.zSlow,
                trend = indUp.trend,
                persistence = indUp.persistence,
                consistency = indUp.consistency,
                volatility = indUp.volatility,
                velocity = indUp.velocity,
                netDelta5 = indUp.netDelta5,
                relPos = indUp.relPos,
                microstructure = indUp.microstructure,
                hvWindow = indUp.hvWindow,
                highVoltageStatus = indUp.highVoltageStatus,
                baseConfidenceScore = upScore,
                contextBlockReasons = upBlocks,
                wsSynchronized = true
            ),
            wsLatencyMs = 30L
        )
        assertEquals(SignalDirection.UP, upEval.finalSignal)
        assertTrue("Sniper M must ride upward smoothed tick surge: ${upEval.triggerReason}", upEval.readyToExecute)

        // 2. DOWNWARD TICK STREAM SURGE:
        //    - 20 baseline ticks oscillating mildly around 4500.0
        //    - Followed by a smooth, accelerating 6-tick downward surge (-0.35, -0.75, -1.20, -1.70, -2.25, -2.85)
        val downCandleEngine = CandleEngine()
        val downIndEngine = IndicatorEngine()
        val downTicks = baseUpTicks + upSurgeDeltas.mapIndexed { idx, cum ->
            Tick(pair = "LATAM_X", price = upLastPrice - cum, timeMs = upLastTs + (idx + 1) * 140L)
        }
        var downCandles = emptyList<Candle>()
        downTicks.forEach { downCandles = downCandleEngine.processTick(it) }
        val indDown = downIndEngine.computeIndicators(downTicks, downCandles)

        val downEval = strategyEngine.evaluateStrategy(
            strategy = sniperStrategy,
            telemetry = MarketTelemetryState(
                pair = "LATAM_X",
                latestPrice = downTicks.last().price,
                latestTimeMs = downTicks.last().timeMs,
                tickCount = downTicks.size,
                candles = downCandles,
                recentTicks = downTicks,
                zFast = indDown.zFast,
                zSlow = indDown.zSlow,
                trend = indDown.trend,
                persistence = indDown.persistence,
                consistency = indDown.consistency,
                volatility = indDown.volatility,
                velocity = indDown.velocity,
                netDelta5 = indDown.netDelta5,
                relPos = indDown.relPos,
                microstructure = indDown.microstructure,
                hvWindow = indDown.hvWindow,
                highVoltageStatus = indDown.highVoltageStatus,
                wsSynchronized = true
            ),
            wsLatencyMs = 30L
        )
        assertEquals(SignalDirection.DOWN, downEval.finalSignal)
        assertTrue("Sniper M must ride downward smoothed tick surge: ${downEval.triggerReason}", downEval.readyToExecute)
    }

    @Test
    fun `test Logic and System Integrity Audit fixes for hysteresis snapshot isolation zero-mean rolling windows 8-tick warm-up burst guard and multi-deal WS events`() {
        val baseTimeMs = 1700000000000L
        val strategyEngine = ModularStrategyEngine()

        // 1. Verify getLiveSymbolSnapshot does NOT mutate hysteresis state
        val highSnrTicks = (0 until 10).map { i ->
            Tick(pair = "LATAM_X", price = 4500.0 + i * 0.45, timeMs = baseTimeMs + i * 180L)
        }
        val highSnrTelemetry = MarketTelemetryState(
            pair = "LATAM_X",
            latestPrice = highSnrTicks.last().price,
            latestTimeMs = highSnrTicks.last().timeMs,
            tickCount = highSnrTicks.size,
            recentTicks = highSnrTicks,
            wsSynchronized = true
        )
        // Call getLiveSymbolSnapshot (read-only UI preview) when rush_snr >= 1.50
        val snapHigh = strategyEngine.getLiveSymbolSnapshot(highSnrTelemetry, wsLatencyMs = 25L)
        assertTrue((snapHigh["rush_snr"] ?: 0.0) >= 1.50)

        // Now evaluate at rush_snr ~ 1.25 (between exit=1.10 and enter=1.50):
        // Because getLiveSymbolSnapshot did NOT mutate hysteresis state, the latch must still be false!
        val midActivated = strategyEngine.temporalEngine.evaluateHysteresis(
            key = "momentum_snr_hyst",
            currentValue = 1.25,
            enterThreshold = 1.50,
            exitThreshold = 1.10,
            mutateState = false
        )
        assertFalse("getLiveSymbolSnapshot must not mutate live hysteresis latch state", midActivated)

        // 2. Verify roll_mean / roll_median / ema accurately return 0.0 for populated zero-mean series (e.g. ofi)
        val zeroMeanEngine = ModularStrategyEngine()
        val symTicksA = (0 until 10).map { i ->
            Tick(pair = "LATAM_X", price = 4500.0 + (if (i % 2 == 0) 0.10 else -0.10), timeMs = baseTimeMs + i * 200L)
        }
        // Push two frames with +25.0 and -25.0 OFI so the 2-tick rolling mean is exactly 0.0
        val rushA = zeroMeanEngine.extractRawTickRushMetrics(highSnrTelemetry, mutateState = true)
        zeroMeanEngine.temporalEngine.pushObservationFrame(
            highSnrTelemetry.copy(orderFlowImbalance = 25.0),
            rushA,
            wsLatencyMs = 20L,
            mutateState = true
        )
        zeroMeanEngine.temporalEngine.pushObservationFrame(
            highSnrTelemetry.copy(orderFlowImbalance = -25.0),
            rushA,
            wsLatencyMs = 20L,
            mutateState = true
        )
        assertTrue(zeroMeanEngine.temporalEngine.hasSeries("ofi"))
        assertEquals(0.0, zeroMeanEngine.temporalEngine.rollingMean("ofi", 2), 1e-9)
        assertEquals(0.0, zeroMeanEngine.temporalEngine.rollingMedian("ofi", 2), 1e-9)

        // 3. Verify IndicatorEngine & Sniper M work seamlessly on 10-tick streams (6 <= len < 15)
        val indEngine = IndicatorEngine()
        val candleEngine = CandleEngine()
        val tenTicks = listOf(
            4500.00, 4500.08, 4499.95, 4500.05, 4499.98,
            4500.32, 4500.68, 4501.08, 4501.52, 4502.00
        ).mapIndexed { idx, p ->
            Tick(pair = "LATAM_X", price = p, timeMs = baseTimeMs + idx * 140L)
        }
        var tenCandles = emptyList<Candle>()
        tenTicks.forEach { tenCandles = candleEngine.processTick(it) }
        val ind10 = indEngine.computeIndicators(tenTicks, tenCandles)
        assertEquals(SignalDirection.UP, ind10.trend)
        assertTrue("10-tick stream must produce zFast > zSlow on upward surge", ind10.zFast > ind10.zSlow)

        val eval10 = ModularStrategyEngine().evaluateStrategy(
            strategy = HardLockedMandate.lockedStrategyConfig(),
            telemetry = MarketTelemetryState(
                pair = "LATAM_X",
                latestPrice = tenTicks.last().price,
                latestTimeMs = tenTicks.last().timeMs,
                tickCount = tenTicks.size,
                candles = tenCandles,
                recentTicks = tenTicks,
                zFast = ind10.zFast,
                zSlow = ind10.zSlow,
                trend = ind10.trend,
                consistency = ind10.consistency,
                volatility = ind10.volatility,
                velocity = ind10.velocity,
                netDelta5 = ind10.netDelta5,
                microstructure = ind10.microstructure,
                hvWindow = ind10.hvWindow,
                highVoltageStatus = ind10.highVoltageStatus,
                wsSynchronized = true
            ),
            wsLatencyMs = 25L
        )
        assertEquals(SignalDirection.UP, eval10.finalSignal)
        assertTrue("10-tick upward surge must be readyToExecute in Sniper M", eval10.readyToExecute)

        // 4. Verify multi-deal Event 21/22 array in OlympTradeWsClient emits all deals without dropping index > 0
        kotlinx.coroutines.runBlocking {
            val ws = OlympTradeWsClient()
            val multiDealJson = kotlinx.serialization.json.Json.parseToJsonElement(
                """
                [
                  {"id": 9001, "pair": "LATAM_X", "dir": "up", "amount": 15.0, "open_rate": 4501.25, "status": "open", "winperc": 85},
                  {"id": 9002, "pair": "EURUSD", "dir": "down", "amount": 20.0, "open_rate": 1.0845, "close_rate": 1.0830, "status": "win", "winperc": 82}
                ]
                """.trimIndent()
            )
            ws.handleOrderAcceptedOrUpdatedEvent(21, multiDealJson)
            val wireLogs = ws.rawWireLog.value.joinToString("\n")
            assertTrue("First deal #9001 must be logged as accepted", wireLogs.contains("ORDER ACCEPTED #9001"))
            assertTrue("Second deal #9002 in same Event 21 array must be settled", wireLogs.contains("ORDER SETTLED #9002: WIN"))
        }

        // 5. Verify TIME-5 rejects opposing counter-impulses on latest observation
        val counterSurgePrices = listOf(
            4500.00, 4500.06, 4499.96, 4500.04, 4499.98, 4500.05,
            4500.85, 4501.65, // Big early steps (+0.80, +0.80)
            4501.90, 4501.20  // Counter impulse drop on latest tick (-0.70)
        )
        val counterTicks = counterSurgePrices.mapIndexed { idx, p ->
            Tick(pair = "LATAM_X", price = p, timeMs = baseTimeMs + idx * 140L)
        }
        val counterEval = ModularStrategyEngine().evaluateStrategy(
            strategy = HardLockedMandate.lockedStrategyConfig(),
            telemetry = MarketTelemetryState(
                pair = "LATAM_X",
                latestPrice = counterTicks.last().price,
                latestTimeMs = counterTicks.last().timeMs,
                tickCount = counterTicks.size,
                recentTicks = counterTicks,
                wsSynchronized = true
            ),
            wsLatencyMs = 25L
        )
        assertFalse("Opposing counter-impulse on latest observation must not execute", counterEval.readyToExecute)
    }

    @Test
    fun `test Iteration 1 Task 1 shadow settlement prevents future price interpolation and handles ties delays and wide brackets`() {
        val baseTs = 1700000000000L
        val noiseTicks = (0 until 14).map { i ->
            val offset = if (i % 2 == 0) 0.14 else -0.14
            Tick(pair = "LATAM_X", price = 4500.0 + offset, timeMs = baseTs + i * 220L)
        }
        val lastNoiseTs = noiseTicks.last().timeMs
        val lastNoisePrice = noiseTicks.last().price
        val rushTicks = noiseTicks + listOf(0.42, 0.88, 1.38, 1.92).mapIndexed { idx, cum ->
            Tick(pair = "LATAM_X", price = lastNoisePrice + cum, timeMs = lastNoiseTs + (idx + 1) * 140L)
        }
        val triggerTick = rushTicks.last() // price = 4501.78, timeMs = triggerTick.timeMs

        // 1. Future-price anti-interpolation test:
        //    At +4800ms (200ms before 5s expiry), price has reversed BELOW entryPrice (triggerTick.price - 0.30).
        //    At +5200ms (200ms AFTER 5s expiry), a huge post-expiry jump occurs (triggerTick.price + 6.00).
        //    Linear interpolation would compute +2.85 (> entryPrice -> fabricated WIN).
        //    True càdlàg settlement must use the last valid quote at or before +5000ms (-0.30 -> LOSS).
        val engine = ModularStrategyEngine()
        engine.extractRawTickRushMetrics(
            MarketTelemetryState(
                pair = "LATAM_X",
                latestPrice = triggerTick.price,
                latestTimeMs = triggerTick.timeMs,
                tickCount = rushTicks.size,
                recentTicks = rushTicks,
                wsSynchronized = true
            )
        )
        val firstPostTriggerTick = Tick(
            pair = "LATAM_X",
            price = triggerTick.price + 0.12,
            timeMs = triggerTick.timeMs + 160L
        )
        val preExpiryLossTick = Tick(
            pair = "LATAM_X",
            price = triggerTick.price - 0.30,
            timeMs = triggerTick.timeMs + 4800L
        )
        val postExpiryHugeSpikeTick = Tick(
            pair = "LATAM_X",
            price = triggerTick.price + 6.00,
            timeMs = triggerTick.timeMs + 5200L
        )

        // Feed at +4800ms: even if telemetry.latestTimeMs is artificially set to +5100ms (delayed WS frame),
        // premature settlement must NOT occur because ticks.last().timeMs is only +4800ms (< +5000ms).
        val preExpiryStream = rushTicks + listOf(firstPostTriggerTick, preExpiryLossTick)
        engine.extractRawTickRushMetrics(
            MarketTelemetryState(
                pair = "LATAM_X",
                latestPrice = preExpiryLossTick.price,
                latestTimeMs = triggerTick.timeMs + 5100L,
                tickCount = preExpiryStream.size,
                recentTicks = preExpiryStream,
                wsSynchronized = true
            )
        )
        assertEquals("Delayed tick stream before +5000ms must not settle prematurely", 0, engine.learnedSampleCount())

        // Now feed the post-expiry tick at +5200ms (bracket gap = 400ms <= 1500ms):
        val settledStream = preExpiryStream + postExpiryHugeSpikeTick
        engine.extractRawTickRushMetrics(
            MarketTelemetryState(
                pair = "LATAM_X",
                latestPrice = postExpiryHugeSpikeTick.price,
                latestTimeMs = postExpiryHugeSpikeTick.timeMs,
                tickCount = settledStream.size,
                recentTicks = settledStream,
                wsSynchronized = true
            )
        )
        assertEquals(1, engine.learnedSampleCount())
        val record = engine.getSettledShadowRecords().single()
        assertEquals(triggerTick.price, record.entryPrice, 1e-9)
        assertEquals("Expiry price must be last quote at or before +5000ms, never interpolated with post-expiry spike", preExpiryLossTick.price, record.expiryPrice, 1e-9)
        assertEquals(preExpiryLossTick.timeMs, record.expiryQuoteTimeMs)
        assertEquals(postExpiryHugeSpikeTick.timeMs, record.confirmingTickTimeMs)
        assertEquals(firstPostTriggerTick.price, record.firstPostTriggerPrice, 1e-9)
        assertFalse("Outcome at +5000ms was below entryPrice and must be recorded as LOSS", record.won)

        // 2. Exact timestamp settlement (+5000ms exact tick):
        val exactEngine = ModularStrategyEngine()
        exactEngine.extractRawTickRushMetrics(
            MarketTelemetryState(
                pair = "LATAM_X",
                latestPrice = triggerTick.price,
                latestTimeMs = triggerTick.timeMs,
                tickCount = rushTicks.size,
                recentTicks = rushTicks,
                wsSynchronized = true
            )
        )
        val tickAt4600 = Tick("LATAM_X", triggerTick.price + 0.25, triggerTick.timeMs + 4600L)
        val tickExact5000 = Tick("LATAM_X", triggerTick.price + 0.55, triggerTick.timeMs + 5000L)
        val exactStream = rushTicks + listOf(tickAt4600, tickExact5000)
        exactEngine.extractRawTickRushMetrics(
            MarketTelemetryState(
                pair = "LATAM_X",
                latestPrice = tickExact5000.price,
                latestTimeMs = tickExact5000.timeMs,
                tickCount = exactStream.size,
                recentTicks = exactStream,
                wsSynchronized = true
            )
        )
        assertEquals(1, exactEngine.learnedSampleCount())
        val exactRecord = exactEngine.getSettledShadowRecords().single()
        assertEquals(ModularStrategyEngine.ExpiryResolutionStatus.RESOLVED_EXACT_TIMESTAMP, exactRecord.resolutionStatus)
        assertEquals(tickExact5000.price, exactRecord.expiryPrice, 1e-9)
        assertTrue(exactRecord.won)

        // 3. Tie settlement (expiry quote == entryPrice):
        val tieEngine = ModularStrategyEngine()
        tieEngine.extractRawTickRushMetrics(
            MarketTelemetryState(
                pair = "LATAM_X",
                latestPrice = triggerTick.price,
                latestTimeMs = triggerTick.timeMs,
                tickCount = rushTicks.size,
                recentTicks = rushTicks,
                wsSynchronized = true
            )
        )
        val tieBefore = Tick("LATAM_X", triggerTick.price, triggerTick.timeMs + 4850L)
        val tieAfter = Tick("LATAM_X", triggerTick.price + 0.90, triggerTick.timeMs + 5150L)
        val tieStream = rushTicks + listOf(tieBefore, tieAfter)
        tieEngine.extractRawTickRushMetrics(
            MarketTelemetryState(
                pair = "LATAM_X",
                latestPrice = tieAfter.price,
                latestTimeMs = tieAfter.timeMs,
                tickCount = tieStream.size,
                recentTicks = tieStream,
                wsSynchronized = true
            )
        )
        assertEquals("Tie outcome must not increment directional learnedSampleCount", 0, tieEngine.learnedSampleCount())
        assertEquals("Tie outcome must be counted in tieShadowSampleCount", 1, tieEngine.tieShadowSampleCount())
        assertTrue(tieEngine.getSettledShadowRecords().single().isTie)
    }

    @Test
    fun `test Iteration 1 Task 2 shadow candidate eligibility separates Populations A B C and D`() {
        val engine = ModularStrategyEngine()
        val strategy = HardLockedMandate.lockedStrategyConfig()
        val baseTs = 1700000000000L

        val noiseTicks = (0 until 14).map { i ->
            val offset = if (i % 2 == 0) 0.14 else -0.14
            Tick(pair = "LATAM_X", price = 4500.0 + offset, timeMs = baseTs + i * 220L)
        }
        val lastNoiseTs = noiseTicks.last().timeMs
        val lastNoisePrice = noiseTicks.last().price

        // Population A only: 1-tick freak spike (rushSnr >= 1.22, purity >= 62%, but singleTickDominance > 65%)
        val spikeTicks = noiseTicks + listOf(0.04, 0.08, 0.12, 1.92).mapIndexed { idx, cum ->
            Tick(pair = "LATAM_X", price = lastNoisePrice + cum, timeMs = lastNoiseTs + (idx + 1) * 140L)
        }
        engine.evaluateStrategy(
            strategy = strategy,
            telemetry = MarketTelemetryState(
                pair = "LATAM_X",
                latestPrice = spikeTicks.last().price,
                latestTimeMs = spikeTicks.last().timeMs,
                tickCount = spikeTicks.size,
                recentTicks = spikeTicks,
                wsSynchronized = true
            ),
            wsLatencyMs = 30L
        )
        val diagAfterSpike = engine.getPopulationDiagnostics()
        assertEquals("Spike must be recorded in Population A (observed impulses)", 1, diagAfterSpike.observedImpulseCount)
        assertEquals("Spike must NOT enter Population B (structural Sniper M candidates)", 0, diagAfterSpike.structuralCandidateCount)
        assertEquals("Spike must NOT enter Population C (deterministic gate pass)", 0, diagAfterSpike.deterministicGatePassCount)

        // Counter-trend impulse (Population A only: upward burst while zFast < zSlow)
        val counterTrendStartTs = spikeTicks.last().timeMs + 6000L
        val counterBaseTicks = (0 until 14).map { i ->
            val offset = if (i % 2 == 0) 0.14 else -0.14
            Tick(pair = "LATAM_X", price = 4500.0 + offset, timeMs = counterTrendStartTs + i * 220L)
        }
        val counterRushTicks = counterBaseTicks + listOf(0.42, 0.88, 1.38, 1.92).mapIndexed { idx, cum ->
            Tick(
                pair = "LATAM_X",
                price = counterBaseTicks.last().price + cum,
                timeMs = counterBaseTicks.last().timeMs + (idx + 1) * 140L
            )
        }
        engine.evaluateStrategy(
            strategy = strategy,
            telemetry = MarketTelemetryState(
                pair = "LATAM_X",
                latestPrice = counterRushTicks.last().price,
                latestTimeMs = counterRushTicks.last().timeMs,
                tickCount = counterRushTicks.size,
                recentTicks = counterRushTicks,
                zFast = 4495.0,
                zSlow = 4505.0, // Explicitly bearish ZLEMA opposing the upward burst
                wsSynchronized = true
            ),
            wsLatencyMs = 30L
        )
        val diagAfterCounterTrend = engine.getPopulationDiagnostics()
        assertEquals(2, diagAfterCounterTrend.observedImpulseCount)
        assertEquals("Counter-trend burst must NOT enter Population B", 0, diagAfterCounterTrend.structuralCandidateCount)
        assertEquals(0, diagAfterCounterTrend.deterministicGatePassCount)

        // Population B & C: Structurally valid Sniper M surge passing all deterministic gates
        val validStartTs = counterRushTicks.last().timeMs + 6000L
        val validBaseTicks = (0 until 14).map { i ->
            val offset = if (i % 2 == 0) 0.14 else -0.14
            Tick(pair = "LATAM_X", price = 4500.0 + offset, timeMs = validStartTs + i * 220L)
        }
        val validRushTicks = validBaseTicks + listOf(0.42, 0.88, 1.38, 1.92).mapIndexed { idx, cum ->
            Tick(
                pair = "LATAM_X",
                price = validBaseTicks.last().price + cum,
                timeMs = validBaseTicks.last().timeMs + (idx + 1) * 140L
            )
        }
        val validEval = engine.evaluateStrategy(
            strategy = strategy,
            telemetry = MarketTelemetryState(
                pair = "LATAM_X",
                latestPrice = validRushTicks.last().price,
                latestTimeMs = validRushTicks.last().timeMs,
                tickCount = validRushTicks.size,
                recentTicks = validRushTicks,
                wsSynchronized = true
            ),
            wsLatencyMs = 30L
        )
        assertTrue(validEval.readyToExecute)
        val diagAfterValid = engine.getPopulationDiagnostics()
        assertEquals(3, diagAfterValid.observedImpulseCount)
        assertEquals(1, diagAfterValid.structuralCandidateCount)
        assertEquals(1, diagAfterValid.deterministicGatePassCount)

        // Population D: Broker-accepted trade with trigger timestamp, estimated entry, and broker-confirmed open price
        engine.recordBrokerAcceptedTrade(
            brokerTradeId = 77001L,
            pair = "LATAM_X",
            triggerTimeMs = validRushTicks.last().timeMs,
            estimatedEntryPrice = validRushTicks.last().price,
            brokerConfirmedOpenPrice = validRushTicks.last().price + 0.04,
            acceptedAtMs = validRushTicks.last().timeMs + 42L
        )
        val diagFinal = engine.getPopulationDiagnostics()
        assertEquals(1, diagFinal.brokerAcceptedTradeCount)
        val acceptedRecord = engine.getBrokerAcceptedTrades().single()
        assertEquals(77001L, acceptedRecord.brokerTradeId)
        assertEquals(validRushTicks.last().price, acceptedRecord.estimatedEntryPrice, 1e-9)
        assertEquals(validRushTicks.last().price + 0.04, acceptedRecord.brokerConfirmedOpenPrice, 1e-9)
    }

    @Test
    fun `test Iteration 1 Task 3 FTRL exponential forgetting mathematics decay and numerical stability`() {
        val engine = ModularStrategyEngine()
        // Feature 1 active vector: x[0]=1 (bias), x[1]=1.5 (signal), others 0
        val xFeature1 = DoubleArray(ModularStrategyEngine.FEATURE_DIM).apply {
            this[0] = 1.0
            this[1] = 1.5
        }
        // Orthogonal Feature 2 active vector: x[0]=0.0, x[2]=1.0, x[1]=0.0
        val xFeature2Only = DoubleArray(ModularStrategyEngine.FEATURE_DIM).apply {
            this[2] = 1.0
        }

        // 1. Train 8 consecutive positive outcomes on xFeature1
        repeat(8) {
            engine.updateFtrlWeightsOnline(xFeature1, 1.0)
        }
        val probAfterPositiveRegime = engine.predictFtrlWinProbability(xFeature1)
        assertTrue("After positive regime, P(Win) must exceed 0.55, got $probAfterPositiveRegime", probAfterPositiveRegime > 0.55)
        val z1Peak = kotlin.math.abs(engine.getFtrlZ(1))
        val n1Peak = engine.getFtrlN(1)
        assertTrue(z1Peak > ModularStrategyEngine.FTRL_L1)
        assertTrue(n1Peak > 0.0)

        // 2. Advance 60 steps where Feature 1 has zero gradient (x[1] = 0.0)
        //    Verify exact analytical exponential decay: z_1(t+60) = gamma^60 * z_1(t) and n_1(t+60) = gamma^60 * n_1(t)
        val expectedDecay60 = Math.pow(ModularStrategyEngine.FORGETTING_GAMMA, 60.0)
        repeat(60) {
            engine.updateFtrlWeightsOnline(xFeature2Only, 1.0)
        }
        assertEquals(z1Peak * expectedDecay60, kotlin.math.abs(engine.getFtrlZ(1)), 1e-9)
        assertEquals(n1Peak * expectedDecay60, engine.getFtrlN(1), 1e-9)

        // 3. Advance another 340 steps (400 total, 0.985^400 = 0.00237) with x[1] = 0.0 so |z_1| decays below FTRL_L1 (0.015):
        //    L1 proximal soft-thresholding must zero out w_1 completely!
        repeat(340) {
            engine.updateFtrlWeightsOnline(xFeature2Only, 1.0)
        }
        engine.predictFtrlWinProbability(xFeature1)
        assertTrue("Stale |z_1| must decay below FTRL_L1", kotlin.math.abs(engine.getFtrlZ(1)) <= ModularStrategyEngine.FTRL_L1)
        assertEquals("L1 soft-thresholding must drive forgotten feature weight w_1 to exact 0.0", 0.0, engine.getFtrlWeight(1), 1e-12)

        // 4. Regime reversal: train 10 negative outcomes (labelY = 0.0) on xFeature1 and verify rapid adaptation < 0.45
        repeat(10) {
            engine.updateFtrlWeightsOnline(xFeature1, 0.0)
        }
        val probAfterNegativeRegime = engine.predictFtrlWinProbability(xFeature1)
        assertTrue("Recent negative observations must dominate forgotten positive observations, got $probAfterNegativeRegime", probAfterNegativeRegime < 0.45)

        // 5. Prolonged inactivity stability: 4000 zero-gradient updates must flush subnormals to 0.0 without NaN
        val zeroVec = DoubleArray(ModularStrategyEngine.FEATURE_DIM)
        repeat(4000) {
            engine.updateFtrlWeightsOnline(zeroVec, 0.5)
        }
        for (i in 0 until ModularStrategyEngine.FEATURE_DIM) {
            assertEquals(0.0, engine.getFtrlZ(i), 1e-18)
            assertEquals(0.0, engine.getFtrlN(i), 1e-18)
        }
        assertEquals(0.5, engine.predictFtrlWinProbability(xFeature1), 1e-9)
    }

    @Test
    fun `test Iteration 1 Task 4 removes target contamination from f7 and migrates SNIPER_FTRL_V1 safely`() {
        val engine = ModularStrategyEngine()
        val vecLowPurity = engine.buildFtrlFeatureVector(
            rushSnr = 1.60,
            tickConvexity = 1.20,
            arrivalCompressionRatio = 0.80,
            singleTickDominance = 0.35,
            microPullbackRecovery = 0.80,
            localZScore15 = 1.70,
            purityPercent = 65.0
        )
        val vecHighPurity = engine.buildFtrlFeatureVector(
            rushSnr = 1.60,
            tickConvexity = 1.20,
            arrivalCompressionRatio = 0.80,
            singleTickDominance = 0.35,
            microPullbackRecovery = 0.80,
            localZScore15 = 1.70,
            purityPercent = 95.0
        )
        assertEquals(ModularStrategyEngine.FEATURE_DIM, vecLowPurity.size)
        assertEquals(-0.5, vecLowPurity[7], 1e-9)  // (65 - 75) / 20 = -0.5
        assertEquals(1.0, vecHighPurity[7], 1e-9)  // (95 - 75) / 20 = +1.0

        // Verify SNIPER_FTRL_V1 -> SNIPER_FTRL_V2 explicit migration zeroes retired coordinate 7
        // while preserving valid exogenous coordinates 0..6 and 8..10:
        val legacyZ = DoubleArray(11) { idx -> if (idx == 7) -2.50 else -0.40 }
        val legacyN = DoubleArray(11) { idx -> if (idx == 7) 9.00 else 1.20 }
        val legacyPayload = buildString {
            append("SNIPER_FTRL_V1|42|0.68")
            legacyZ.forEach { append('|').append(it) }
            legacyN.forEach { append('|').append(it) }
        }

        val migratedEngine = ModularStrategyEngine()
        assertTrue(migratedEngine.restoreLearningState(legacyPayload))
        assertTrue("Restoring SNIPER_FTRL_V1 must flag explicit schema migration", migratedEngine.migratedFromLegacySchema)
        assertEquals("SNIPER_FTRL_V1->SNIPER_FTRL_V2", migratedEngine.lastRestoredSchemaVersion)
        assertEquals("Retired target-leak coordinate 7 z-accumulator must be zeroed on V1 migration", 0.0, migratedEngine.getFtrlZ(7), 1e-12)
        assertEquals("Retired target-leak coordinate 7 n-accumulator must be zeroed on V1 migration", 0.0, migratedEngine.getFtrlN(7), 1e-12)
        assertEquals("Retired target-leak coordinate 7 weight must be zeroed on V1 migration", 0.0, migratedEngine.getFtrlWeight(7), 1e-12)
        assertEquals("Exogenous coordinate 1 z-accumulator must be preserved", -0.40, migratedEngine.getFtrlZ(1), 1e-9)
        assertEquals("Exogenous coordinate 1 n-accumulator must be preserved", 1.20, migratedEngine.getFtrlN(1), 1e-9)

        // Re-exporting after migration must emit SNIPER_FTRL_V2
        val exportedV2 = migratedEngine.exportLearningState()
        assertTrue(exportedV2.startsWith("SNIPER_FTRL_V2|"))
    }

    @Test
    fun `test Iteration 1 Task 5 ring buffer validation across initial partial full wrap-around reset and isolation`() {
        val temporal = com.olympquant.terminal.engine.TemporalIntelligenceEngine()
        val regimes = com.olympquant.terminal.domain.TemporalRegimeType.entries

        // 1. Initial empty state across all regimes and directions
        for (r in regimes) {
            assertEquals(0, temporal.recentRegimeDirectionSampleCount(r, SignalDirection.UP))
            assertEquals(0, temporal.recentRegimeDirectionSampleCount(r, SignalDirection.DOWN))
            assertEquals(null, temporal.recentRegimeDirectionWinRate(r, SignalDirection.UP))
            assertEquals(null, temporal.recentRegimeDirectionWinRate(r, SignalDirection.DOWN))
            assertEquals(null, temporal.recentRegimeDirectionWinRate(r, SignalDirection.NONE))
        }

        // 2. Partially filled buffer (8 samples: 5 wins, 3 losses -> 0.625)
        repeat(5) {
            temporal.recordShadowOutcome(SignalDirection.UP, com.olympquant.terminal.domain.TemporalRegimeType.EXPANSION_IMPULSE, 0.6, true, false)
        }
        repeat(3) {
            temporal.recordShadowOutcome(SignalDirection.UP, com.olympquant.terminal.domain.TemporalRegimeType.EXPANSION_IMPULSE, 0.6, false, false)
        }
        assertEquals(8, temporal.recentRegimeDirectionSampleCount(com.olympquant.terminal.domain.TemporalRegimeType.EXPANSION_IMPULSE, SignalDirection.UP))
        assertEquals(0.625, temporal.recentRegimeDirectionWinRate(com.olympquant.terminal.domain.TemporalRegimeType.EXPANSION_IMPULSE, SignalDirection.UP)!!, 1e-9)

        // 3. Regime and direction isolation check
        assertEquals(0, temporal.recentRegimeDirectionSampleCount(com.olympquant.terminal.domain.TemporalRegimeType.EXPANSION_IMPULSE, SignalDirection.DOWN))
        assertEquals(null, temporal.recentRegimeDirectionWinRate(com.olympquant.terminal.domain.TemporalRegimeType.EXPANSION_IMPULSE, SignalDirection.DOWN))
        assertEquals(0, temporal.recentRegimeDirectionSampleCount(com.olympquant.terminal.domain.TemporalRegimeType.TREND, SignalDirection.UP))

        // 4. Fill to exact capacity (32 samples: add 24 losses -> 5 wins / 32 = 0.15625)
        repeat(24) {
            temporal.recordShadowOutcome(SignalDirection.UP, com.olympquant.terminal.domain.TemporalRegimeType.EXPANSION_IMPULSE, 0.6, false, false)
        }
        assertEquals(32, temporal.recentRegimeDirectionSampleCount(com.olympquant.terminal.domain.TemporalRegimeType.EXPANSION_IMPULSE, SignalDirection.UP))
        assertEquals(5.0 / 32.0, temporal.recentRegimeDirectionWinRate(com.olympquant.terminal.domain.TemporalRegimeType.EXPANSION_IMPULSE, SignalDirection.UP)!!, 1e-9)

        // 5. Circular wrap-around: overwrite the first 5 wins + 3 losses (8 oldest slots) with 8 wins -> 8 wins / 32 = 0.25
        repeat(8) {
            temporal.recordShadowOutcome(SignalDirection.UP, com.olympquant.terminal.domain.TemporalRegimeType.EXPANSION_IMPULSE, 0.6, true, false)
        }
        assertEquals(32, temporal.recentRegimeDirectionSampleCount(com.olympquant.terminal.domain.TemporalRegimeType.EXPANSION_IMPULSE, SignalDirection.UP))
        assertEquals(8.0 / 32.0, temporal.recentRegimeDirectionWinRate(com.olympquant.terminal.domain.TemporalRegimeType.EXPANSION_IMPULSE, SignalDirection.UP)!!, 1e-9)

        // 6. Buffer reset
        temporal.reset()
        assertEquals(0, temporal.recentRegimeDirectionSampleCount(com.olympquant.terminal.domain.TemporalRegimeType.EXPANSION_IMPULSE, SignalDirection.UP))
        assertEquals(null, temporal.recentRegimeDirectionWinRate(com.olympquant.terminal.domain.TemporalRegimeType.EXPANSION_IMPULSE, SignalDirection.UP))
    }

    @Test
    fun `test Iteration 1 Task 6 deterministic chronological walk-forward evaluation and UP DOWN symmetry`() {
        val baseTs = 1700000000000L

        // Helper to build a deterministic 18-tick segment + 5-second outcome resolution ticks
        fun buildScenarioStream(
            startTs: Long,
            basePrice: Double,
            directionSign: Int, // +1 for UP, -1 for DOWN
            surgeSteps: List<Double>,
            stepDtMs: Long = 135L,
            preExpiryDeltaAt4800Ms: Double?,
            postExpiryDeltaAt5200Ms: Double,
            postExpiryTimeMs: Long = 5200L
        ): List<Tick> {
            val osc = (0 until 14).map { i ->
                val offset = (if (i % 2 == 0) 0.12 else -0.12) * directionSign
                Tick(pair = "LATAM_X", price = basePrice + offset, timeMs = startTs + i * 220L)
            }
            var curPrice = osc.last().price
            var curTs = osc.last().timeMs
            val burst = surgeSteps.map { step ->
                curPrice += step * directionSign
                curTs += stepDtMs
                Tick(pair = "LATAM_X", price = curPrice, timeMs = curTs)
            }
            val triggerTs = burst.last().timeMs
            val triggerPrice = burst.last().price
            val postTriggerTick = Tick(
                pair = "LATAM_X",
                price = triggerPrice + (0.08 * directionSign),
                timeMs = triggerTs + 150L
            )
            val tail = mutableListOf(postTriggerTick)
            if (preExpiryDeltaAt4800Ms != null) {
                tail.add(
                    Tick(
                        pair = "LATAM_X",
                        price = triggerPrice + (preExpiryDeltaAt4800Ms * directionSign),
                        timeMs = triggerTs + 4800L
                    )
                )
            }
            val postPrice = triggerPrice + (postExpiryDeltaAt5200Ms * directionSign)
            tail.add(Tick(pair = "LATAM_X", price = postPrice, timeMs = triggerTs + postExpiryTimeMs))
            // Append 4 balanced oscillation ticks after +5200ms so the 4-tick window at settlement has netDelta4 == 0.0
            tail.add(Tick(pair = "LATAM_X", price = postPrice + 0.04 * directionSign, timeMs = triggerTs + postExpiryTimeMs + 150L))
            tail.add(Tick(pair = "LATAM_X", price = postPrice, timeMs = triggerTs + postExpiryTimeMs + 300L))
            tail.add(Tick(pair = "LATAM_X", price = postPrice + 0.04 * directionSign, timeMs = triggerTs + postExpiryTimeMs + 450L))
            tail.add(Tick(pair = "LATAM_X", price = postPrice, timeMs = triggerTs + postExpiryTimeMs + 600L))
            return osc + burst + tail
        }

        // 1. Verify exact UP/DOWN directional symmetry on mirror-image tick streams
        val upEngine = ModularStrategyEngine()
        val downEngine = ModularStrategyEngine()
        val upStream = buildScenarioStream(
            startTs = baseTs,
            basePrice = 4500.0,
            directionSign = 1,
            surgeSteps = listOf(0.42, 0.46, 0.50, 0.54),
            preExpiryDeltaAt4800Ms = 0.65,
            postExpiryDeltaAt5200Ms = 0.80
        )
        val downStream = buildScenarioStream(
            startTs = baseTs,
            basePrice = 4500.0,
            directionSign = -1,
            surgeSteps = listOf(0.42, 0.46, 0.50, 0.54),
            preExpiryDeltaAt4800Ms = 0.65,
            postExpiryDeltaAt5200Ms = 0.80
        )
        val upRushTicks = upStream.take(18)
        val downRushTicks = downStream.take(18)
        val upMetrics = upEngine.extractRawTickRushMetrics(
            MarketTelemetryState(pair = "LATAM_X", latestPrice = upRushTicks.last().price, latestTimeMs = upRushTicks.last().timeMs, tickCount = 18, recentTicks = upRushTicks, wsSynchronized = true)
        )
        val downMetrics = downEngine.extractRawTickRushMetrics(
            MarketTelemetryState(pair = "LATAM_X", latestPrice = downRushTicks.last().price, latestTimeMs = downRushTicks.last().timeMs, tickCount = 18, recentTicks = downRushTicks, wsSynchronized = true)
        )
        assertEquals(SignalDirection.UP, upMetrics.rushDirection)
        assertEquals(SignalDirection.DOWN, downMetrics.rushDirection)
        assertEquals(upMetrics.rushSnr, downMetrics.rushSnr, 1e-9)
        assertEquals(upMetrics.purityPercent, downMetrics.purityPercent, 1e-9)
        assertEquals(upMetrics.tickConvexity, downMetrics.tickConvexity, 1e-9)
        assertEquals(upMetrics.ftrlWinProbPercent, downMetrics.ftrlWinProbPercent, 1e-9)

        // 2. Chronological Walk-Forward Evaluation Foundation:
        //    Phase A (Warm-up / Training Period): 6 chronological setups (onlineTrainingEnabled = true)
        //    Phase B (Out-of-Sample Evaluation Period): 10 chronological scenarios covering:
        //      - Early impulse continuation (UP & DOWN)
        //      - Late impulse exhaustion (dying tail rejected by Sniper M)
        //      - Immediate reversal (snapback before +5000ms)
        //      - Flat/noisy market
        //      - Single-tick spike trap
        //      - Missing expiry observation (reconnect gap > 1500ms -> invalid sample)
        //      - High-latency shadow entry comparison
        val wfEngine = ModularStrategyEngine()
        val strategy = HardLockedMandate.lockedStrategyConfig()
        var clockMs = baseTs

        // Phase A: 6 Training setups (4 continuations, 2 reversals)
        val trainingOutcomes = listOf(true, true, false, true, true, false)
        for ((idx, isWin) in trainingOutcomes.withIndex()) {
            val dir = if (idx % 2 == 0) 1 else -1
            val delta4800 = if (isWin) 0.65 else -0.45
            val stream = buildScenarioStream(
                startTs = clockMs,
                basePrice = 4500.0,
                directionSign = dir,
                surgeSteps = listOf(0.42, 0.46, 0.50, 0.54),
                preExpiryDeltaAt4800Ms = delta4800,
                postExpiryDeltaAt5200Ms = delta4800
            )
            val triggerSlice = stream.take(18)
            wfEngine.evaluateStrategy(
                strategy = strategy,
                telemetry = MarketTelemetryState(
                    pair = "LATAM_X",
                    latestPrice = triggerSlice.last().price,
                    latestTimeMs = triggerSlice.last().timeMs,
                    tickCount = triggerSlice.size,
                    recentTicks = triggerSlice,
                    wsSynchronized = true
                ),
                wsLatencyMs = 30L
            )
            // Settle at post-expiry tail
            wfEngine.extractRawTickRushMetrics(
                MarketTelemetryState(
                    pair = "LATAM_X",
                    latestPrice = stream.last().price,
                    latestTimeMs = stream.last().timeMs,
                    tickCount = stream.size,
                    recentTicks = stream,
                    wsSynchronized = true
                )
            )
            clockMs = stream.last().timeMs + 6000L
        }
        assertEquals(6, wfEngine.learnedSampleCount())
        val weightsBeforeOos = (0 until ModularStrategyEngine.FEATURE_DIM).map { wfEngine.getFtrlWeight(it) }

        // Freeze online weight updates during the Out-Of-Sample (OOS) evaluation period!
        wfEngine.onlineTrainingEnabled = false
        val recordsBeforeOos = wfEngine.getSettledShadowRecords().size
        val diagBeforeOos = wfEngine.getPopulationDiagnostics()

        data class OosScenario(
            val name: String,
            val directionSign: Int,
            val surgeSteps: List<Double>,
            val preExpiryDelta4800: Double?,
            val postExpiryDelta5200: Double,
            val expectStructuralCandidate: Boolean
        )

        val oosScenarios = listOf(
            // 1. Early impulse continuation UP (WIN)
            OosScenario("EarlyImpulse_UP_Win1", +1, listOf(0.40, 0.46, 0.52, 0.58), +0.72, +0.85, true),
            // 2. Early impulse continuation DOWN (WIN)
            OosScenario("EarlyImpulse_DOWN_Win1", -1, listOf(0.40, 0.46, 0.52, 0.58), +0.70, +0.82, true),
            // 3. Immediate reversal UP (LOSS at +4800ms despite +5200ms spike)
            OosScenario("ImmediateReversal_UP_Loss", +1, listOf(0.42, 0.45, 0.48, 0.50), -0.38, +2.50, true),
            // 4. Late impulse exhaustion (dying tail: +0.85, +0.85, +0.12, +0.10 -> convexity < 0.55, rejected!)
            OosScenario("LateExhaustion_Rejected", +1, listOf(0.85, 0.85, 0.12, 0.10), -0.50, -0.60, false),
            // 5. Single-tick freak spike (+0.03, +0.03, +0.04, +1.85 -> dominance > 65%, rejected!)
            OosScenario("SingleTickSpike_Rejected", -1, listOf(0.03, 0.03, 0.04, 1.85), -0.90, -1.10, false),
            // 6. Flat/noisy market (+0.05, -0.04, +0.06, -0.03 -> SNR < 1.25, rejected!)
            OosScenario("FlatNoisy_Rejected", +1, listOf(0.05, -0.04, 0.06, -0.03), 0.0, 0.0, false),
            // 7. Missing expiry observation (>1500ms reconnect gap -> invalid sample, not fabricated!)
            OosScenario("MissingExpiry_InvalidBracket", +1, listOf(0.42, 0.46, 0.50, 0.54), null, +0.90, true),
            // 8. Early impulse continuation UP (WIN)
            OosScenario("EarlyImpulse_UP_Win2", +1, listOf(0.44, 0.48, 0.54, 0.60), +0.64, +0.75, true),
            // 9. Early impulse continuation DOWN (WIN)
            OosScenario("EarlyImpulse_DOWN_Win2", -1, listOf(0.44, 0.48, 0.54, 0.60), +0.68, +0.79, true),
            // 10. Immediate reversal DOWN (LOSS)
            OosScenario("ImmediateReversal_DOWN_Loss", -1, listOf(0.41, 0.44, 0.47, 0.49), -0.32, -0.40, true)
        )

        for (sc in oosScenarios) {
            val stream = buildScenarioStream(
                startTs = clockMs,
                basePrice = 4500.0,
                directionSign = sc.directionSign,
                surgeSteps = sc.surgeSteps,
                preExpiryDeltaAt4800Ms = sc.preExpiryDelta4800,
                postExpiryDeltaAt5200Ms = sc.postExpiryDelta5200
            )
            val triggerSlice = stream.take(18)
            val eval = wfEngine.evaluateStrategy(
                strategy = strategy,
                telemetry = MarketTelemetryState(
                    pair = "LATAM_X",
                    latestPrice = triggerSlice.last().price,
                    latestTimeMs = triggerSlice.last().timeMs,
                    tickCount = triggerSlice.size,
                    recentTicks = triggerSlice,
                    wsSynchronized = true
                ),
                wsLatencyMs = 30L
            )
            assertEquals("Scenario ${sc.name} candidate eligibility mismatch", sc.expectStructuralCandidate, eval.readyToExecute)

            // Settle horizon at post-expiry tail
            wfEngine.extractRawTickRushMetrics(
                MarketTelemetryState(
                    pair = "LATAM_X",
                    latestPrice = stream.last().price,
                    latestTimeMs = stream.last().timeMs,
                    tickCount = stream.size,
                    recentTicks = stream,
                    wsSynchronized = true
                )
            )
            clockMs = stream.last().timeMs + 6000L
        }

        // Verify FTRL weights were strictly frozen during OOS evaluation (zero look-ahead training)
        val weightsAfterOos = (0 until ModularStrategyEngine.FEATURE_DIM).map { wfEngine.getFtrlWeight(it) }
        for (i in weightsBeforeOos.indices) {
            assertEquals("OOS evaluation must not mutate FTRL weights", weightsBeforeOos[i], weightsAfterOos[i], 1e-12)
        }

        val diagAfterOos = wfEngine.getPopulationDiagnostics()
        val oosCandidatesB = diagAfterOos.structuralCandidateCount - diagBeforeOos.structuralCandidateCount
        val oosInvalidCount = diagAfterOos.invalidShadowSampleCount - diagBeforeOos.invalidShadowSampleCount
        val oosRecords = wfEngine.getSettledShadowRecords().drop(recordsBeforeOos)

        assertEquals("7 structural Sniper M candidates expected in OOS (4 wins, 2 losses, 1 invalid)", 7, oosCandidatesB)
        assertEquals("1 invalid missing-bracket sample expected in OOS", 1, oosInvalidCount)
        assertEquals("6 resolved non-tie OOS samples expected (4 wins, 2 losses)", 6, oosRecords.size)

        val oosWins = oosRecords.count { it.won }
        val oosAccuracy = oosWins.toDouble() / oosRecords.size.toDouble()
        assertEquals(4, oosWins)
        assertEquals(4.0 / 6.0, oosAccuracy, 1e-9) // 4 wins (2 UP, 2 DOWN) out of 6 resolved (1 MissingExpiry excluded)

        // Compute OOS Brier score and Payout-Adjusted Expected Value (at 85% payout)
        val oosBrier = oosRecords.map { rec ->
            val y = if (rec.won) 1.0 else 0.0
            (rec.predictedProb - y) * (rec.predictedProb - y)
        }.average()
        val payoutRatio = 0.85
        val oosEvPerUnitStake = oosAccuracy * payoutRatio - (1.0 - oosAccuracy)
        assertTrue(oosBrier in 0.0..0.35)
        assertEquals((4.0 / 6.0) * 0.85 - (2.0 / 6.0), oosEvPerUnitStake, 1e-9) // +0.2333 (+23.33% EV per unit stake)
    }

    @Test
    fun `test Iteration 3 execution quality summary pair switch preservation and latency deceleration penalty`() {
        val engine = ModularStrategyEngine()
        val strategy = HardLockedMandate.lockedStrategyConfig()
        val baseTs = 1700000500000L

        // Build a valid 18-tick Sniper M burst + 5s continuation settlement
        val osc = (0 until 14).map { i ->
            val offset = if (i % 2 == 0) 0.12 else -0.12
            Tick(pair = "LATAM_X", price = 4500.0 + offset, timeMs = baseTs + i * 220L)
        }
        var curPrice = osc.last().price
        var curTs = osc.last().timeMs
        val burst = listOf(0.42, 0.46, 0.50, 0.54).map { step ->
            curPrice += step
            curTs += 135L
            Tick(pair = "LATAM_X", price = curPrice, timeMs = curTs)
        }
        val triggerSlice = osc + burst
        val triggerTs = burst.last().timeMs
        val triggerPrice = burst.last().price

        engine.evaluateStrategy(
            strategy = strategy,
            telemetry = MarketTelemetryState(
                pair = "LATAM_X",
                latestPrice = triggerPrice,
                latestTimeMs = triggerTs,
                tickCount = triggerSlice.size,
                recentTicks = triggerSlice,
                wsSynchronized = true
            ),
            wsLatencyMs = 25L
        )
        // Record a broker-accepted trade with 0.04pt slippage
        engine.recordBrokerAcceptedTrade(
            brokerTradeId = 9001L,
            pair = "LATAM_X",
            triggerTimeMs = triggerTs,
            estimatedEntryPrice = triggerPrice,
            brokerConfirmedOpenPrice = triggerPrice + 0.04,
            acceptedAtMs = triggerTs + 35L
        )

        val fullStream = triggerSlice + listOf(
            Tick(pair = "LATAM_X", price = triggerPrice + 0.10, timeMs = triggerTs + 150L),
            Tick(pair = "LATAM_X", price = triggerPrice + 0.65, timeMs = triggerTs + 4800L),
            Tick(pair = "LATAM_X", price = triggerPrice + 0.80, timeMs = triggerTs + 5200L)
        )
        engine.extractRawTickRushMetrics(
            MarketTelemetryState(
                pair = "LATAM_X",
                latestPrice = fullStream.last().price,
                latestTimeMs = fullStream.last().timeMs,
                tickCount = fullStream.size,
                recentTicks = fullStream,
                wsSynchronized = true
            )
        )

        val summary = engine.getExecutionQualitySummary()
        assertEquals(1, summary.learnedSamples)
        assertEquals(100.0, summary.postTriggerWinRatePercent, 1e-9)
        assertEquals(0.04, summary.meanBrokerSlippagePts, 1e-9)
        val weightBeforeSwitch = engine.getFtrlWeight(0)

        // Switching pairs via resetTransientPairQueues() must preserve learned samples & FTRL weights!
        engine.resetTransientPairQueues()
        assertEquals(1, engine.learnedSampleCount())
        assertEquals(weightBeforeSwitch, engine.getFtrlWeight(0), 1e-12)
    }

    @Test
    fun `test Sniper M early surge entry, improved multi-scale SNR, FRTL responsiveness, and robust ride execution`() {
        val engine = ModularStrategyEngine()
        val indicatorEngine = IndicatorEngine()
        val strategy = HardLockedMandate.lockedStrategyConfig()

        // 1. Build a realistic 16-tick stream: 12 consolidation ticks + 4-tick explosive upward surge
        val ticks = mutableListOf<Tick>()
        val startTs = 1_700_000_000_000L
        var p = 100.00
        for (i in 0 until 12) {
            val step = if (i % 2 == 0) +0.015 else -0.015
            p += step
            ticks.add(Tick("LATAM_X", p, startTs + i * 200L))
        }
        val preSurgePrice = p

        // Burst begins: 4 explosive ticks with high speed (100ms dt), clear displacement above noise
        val surgeSteps = listOf(+0.08, +0.09, +0.11, +0.12)
        for (idx in surgeSteps.indices) {
            p += surgeSteps[idx]
            ticks.add(Tick("LATAM_X", p, startTs + (12 + idx) * 200L + idx * 100L))
        }

        // 2. Compute indicators and verify Fast Response Trend Line (FRTL) tracks the surge
        val ind = indicatorEngine.computeIndicators(
            ticks = ticks,
            candles = listOf(Candle(startTs / 1000L, preSurgePrice, p, preSurgePrice - 0.02, p, 10)),
            swings = emptyList(),
            psychLevels = listOf(100.0, 100.5),
            nearestSupport = null,
            nearestResistance = null
        )

        val telemetry = MarketTelemetryState(
            pair = "LATAM_X",
            latestPrice = ticks.last().price,
            latestTimeMs = ticks.last().timeMs,
            tickCount = ticks.size,
            recentTicks = ticks,
            zFast = ind.zFast,
            zSlow = ind.zSlow,
            trend = ind.trend,
            netDelta5 = ind.netDelta5,
            volatility = ind.volatility,
            velocity = ind.velocity,
            slopeRatio = ind.slopeRatio,
            microstructure = ind.microstructure,
            hvWindow = ind.hvWindow,
            highVoltageStatus = ind.highVoltageStatus,
            wsSynchronized = true,
            assetPayoutPercent = 88,
            contextBlockReasons = emptyList()
        )

        // 3. Extract metrics and verify multi-scale SNR detects early impulse without artificial delay
        val metrics = engine.extractRawTickRushMetrics(telemetry, mutateState = true)
        assertEquals(SignalDirection.UP, metrics.rushDirection)
        assertTrue("Multi-scale SNR must exceed 1.40x on surge onset: ${metrics.rushSnr}", metrics.rushSnr >= 1.40)
        assertTrue("Surge must clear pre-surge noise mean", metrics.clearedNoiseMean)
        assertTrue("Convexity must remain non-decelerating: ${metrics.tickConvexity}", metrics.tickConvexity in 0.65..3.20)
        assertTrue("Tip must be aligned with surge momentum", metrics.tipAligned)
        assertEquals(
            "Early surge must qualify for Population B (structural Sniper M candidates)",
            ModularStrategyEngine.CandidatePopulation.POPULATION_B_STRUCTURAL_SNIPER_CANDIDATE,
            metrics.candidatePopulation
        )

        // 4. Verify evaluation passes all gates and enters early to ride the surge
        val eval = engine.evaluateStrategy(
            strategy = strategy,
            telemetry = telemetry,
            wsLatencyMs = 25L,
            minPayoutPercent = 80
        )
        assertTrue(
            "TIME-5 must enter early in the surge to ride continuation: ${eval.triggerReason}",
            eval.readyToExecute
        )
        assertEquals(SignalDirection.UP, eval.finalSignal)
        assertTrue("Confidence score must reflect strong surge execution: ${eval.confidenceScore}", eval.confidenceScore >= 60.0)
    }

    @Test
    fun `test TIME-5 Balanced Momentum four mathematical components, execution rules, and lifecycle logging`() {
        val engine = ModularStrategyEngine()
        val strategy = HardLockedMandate.lockedStrategyConfig()
        assertEquals("TIME-5", strategy.name)
        assertEquals(5, strategy.durationSeconds)
        assertEquals(600L, strategy.cooldownMs)

        val baseTs = 1700000000000L

        // A. Insufficient Observations (< 4 ticks) -> NO TRADE
        val threeTicks = listOf(
            Tick("LATAM_X", 100.0, baseTs),
            Tick("LATAM_X", 100.1, baseTs + 200L),
            Tick("LATAM_X", 100.2, baseTs + 400L)
        )
        val shortEval = engine.evaluateStrategy(
            strategy = strategy,
            telemetry = MarketTelemetryState(
                pair = "LATAM_X",
                latestPrice = 100.2,
                latestTimeMs = baseTs + 400L,
                tickCount = 3,
                recentTicks = threeTicks,
                assetPayoutPercent = 85
            ),
            wsLatencyMs = 35L
        )
        assertEquals(SignalDirection.NONE, shortEval.finalSignal)
        assertFalse(shortEval.readyToExecute)
        assertEquals("SIGNAL_REJECTED", engine.getLatestEvaluationRecord()?.lifecycleEvent)

        // B. Component A & B: Positive Displacement (M > 0) & Velocity (V > 0) with DE >= 0.45 and S >= 1.0 -> UP Signal
        val baselineTicks = (0 until 12).map { i ->
            val p = 100.0 + if (i % 2 == 0) 0.05 else -0.05
            Tick("LATAM_X", p, baseTs + i * 200L)
        }
        val lastTs = baselineTicks.last().timeMs
        val lastP = baselineTicks.last().price
        val upImpulseTicks = baselineTicks + listOf(
            Tick("LATAM_X", lastP + 0.15, lastTs + 150L),
            Tick("LATAM_X", lastP + 0.35, lastTs + 300L),
            Tick("LATAM_X", lastP + 0.60, lastTs + 450L),
            Tick("LATAM_X", lastP + 0.90, lastTs + 600L)
        )
        val upEval = engine.evaluateStrategy(
            strategy = strategy,
            telemetry = MarketTelemetryState(
                pair = "LATAM_X",
                latestPrice = upImpulseTicks.last().price,
                latestTimeMs = upImpulseTicks.last().timeMs,
                tickCount = upImpulseTicks.size,
                recentTicks = upImpulseTicks,
                assetPayoutPercent = 85
            ),
            wsLatencyMs = 35L
        )
        assertEquals(SignalDirection.UP, upEval.finalSignal)
        assertTrue(upEval.readyToExecute)
        val mUp = upEval.time5Metrics
        assertTrue("Displacement M must be positive: ${mUp.momentum}", mUp.momentum > 0.0)
        assertTrue("Velocity V must be positive: ${mUp.velocity}", mUp.velocity > 0.0)
        assertTrue("Directional efficiency DE must be >= 0.45: ${mUp.directionalEfficiency}", mUp.directionalEfficiency >= 0.45)
        assertTrue("Normalized momentum must be >= 1.0σ: ${mUp.normalizedMomentum}", mUp.normalizedMomentum >= 1.0)
        assertFalse("No opposing impulse on up surge", mUp.isOpposingImpulse)
        assertEquals("SIGNAL_UP", engine.getLatestEvaluationRecord()?.lifecycleEvent)

        // C. Lifecycle Transitions: EXECUTION_ATTEMPT -> EXECUTION_CONFIRMED -> TRADE_RESULT
        engine.recordExecutionAttempt(
            upImpulseTicks.last().timeMs,
            "LATAM_X",
            upImpulseTicks.last().price,
            SignalDirection.UP,
            brokerTradeId = 9999L
        )
        assertEquals("EXECUTION_ATTEMPT", engine.getLatestEvaluationRecord()?.lifecycleEvent)
        assertTrue(engine.getLatestEvaluationRecord()?.executionAttempted == true)

        // A later market evaluation must not steal the lifecycle update from this order.
        engine.evaluateStrategy(
            strategy = strategy,
            telemetry = MarketTelemetryState(
                pair = "LATAM_X",
                latestPrice = 100.2,
                latestTimeMs = baseTs + 400L,
                tickCount = 3,
                recentTicks = threeTicks,
                assetPayoutPercent = 85
            ),
            wsLatencyMs = 35L
        )

        // Broker acceptance can return a broker-assigned ID different from the client order ID.
        engine.recordExecutionConfirmed(456789L, upImpulseTicks.last().price)
        val confirmedRecord = engine.getEvaluationRecords().last { it.brokerTradeId == 456789L }
        assertEquals("EXECUTION_CONFIRMED", confirmedRecord.lifecycleEvent)
        assertTrue(confirmedRecord.platformConfirmed)

        engine.recordTradeSettled(456789L, upImpulseTicks.last().price + 0.50, "WIN")
        val settledRecord = engine.getEvaluationRecords().last { it.brokerTradeId == 456789L }
        assertEquals("TRADE_RESULT", settledRecord.lifecycleEvent)
        assertEquals("WIN", settledRecord.realizedOutcome)

        // D. Component A & B: Negative Displacement (M < 0) & Velocity (V < 0) -> DOWN Signal
        val downImpulseTicks = baselineTicks + listOf(
            Tick("LATAM_X", lastP - 0.15, lastTs + 150L),
            Tick("LATAM_X", lastP - 0.35, lastTs + 300L),
            Tick("LATAM_X", lastP - 0.60, lastTs + 450L),
            Tick("LATAM_X", lastP - 0.90, lastTs + 600L)
        )
        val downEval = engine.evaluateStrategy(
            strategy = strategy,
            telemetry = MarketTelemetryState(
                pair = "LATAM_X",
                latestPrice = downImpulseTicks.last().price,
                latestTimeMs = downImpulseTicks.last().timeMs,
                tickCount = downImpulseTicks.size,
                recentTicks = downImpulseTicks,
                assetPayoutPercent = 85
            ),
            wsLatencyMs = 35L
        )
        assertEquals(SignalDirection.DOWN, downEval.finalSignal)
        assertTrue(downEval.readyToExecute)
        val mDown = downEval.time5Metrics
        assertTrue("Displacement M must be negative: ${mDown.momentum}", mDown.momentum < 0.0)
        assertTrue("Velocity V must be negative: ${mDown.velocity}", mDown.velocity < 0.0)
        assertEquals("SIGNAL_DOWN", engine.getLatestEvaluationRecord()?.lifecycleEvent)

        // E. Rejection: Opposing Impulse on latest observation
        val counterTipTicks = baselineTicks + listOf(
            Tick("LATAM_X", lastP + 0.25, lastTs + 150L),
            Tick("LATAM_X", lastP + 0.60, lastTs + 300L),
            Tick("LATAM_X", lastP + 0.95, lastTs + 450L),
            Tick("LATAM_X", lastP + 0.30, lastTs + 600L) // sharp drop on latest tick
        )
        val counterEval = engine.evaluateStrategy(
            strategy = strategy,
            telemetry = MarketTelemetryState(
                pair = "LATAM_X",
                latestPrice = counterTipTicks.last().price,
                latestTimeMs = counterTipTicks.last().timeMs,
                tickCount = counterTipTicks.size,
                recentTicks = counterTipTicks,
                assetPayoutPercent = 85
            ),
            wsLatencyMs = 35L
        )
        assertEquals(SignalDirection.NONE, counterEval.finalSignal)
        assertFalse(counterEval.readyToExecute)

        // F. Rejection: Weak Momentum relative to Volatility (S < 1.0σ)
        val weakTicks = baselineTicks + listOf(
            Tick("LATAM_X", lastP + 0.01, lastTs + 150L),
            Tick("LATAM_X", lastP + 0.02, lastTs + 300L),
            Tick("LATAM_X", lastP + 0.03, lastTs + 450L),
            Tick("LATAM_X", lastP + 0.04, lastTs + 600L)
        )
        val weakEval = engine.evaluateStrategy(
            strategy = strategy,
            telemetry = MarketTelemetryState(
                pair = "LATAM_X",
                latestPrice = weakTicks.last().price,
                latestTimeMs = weakTicks.last().timeMs,
                tickCount = weakTicks.size,
                recentTicks = weakTicks,
                assetPayoutPercent = 85
            ),
            wsLatencyMs = 35L
        )
        assertEquals(SignalDirection.NONE, weakEval.finalSignal)
        assertFalse(weakEval.readyToExecute)

        // G. Rejection: Stale feed / High Latency
        val staleEval = engine.evaluateStrategy(
            strategy = strategy,
            telemetry = MarketTelemetryState(
                pair = "LATAM_X",
                latestPrice = upImpulseTicks.last().price,
                latestTimeMs = upImpulseTicks.last().timeMs,
                tickCount = upImpulseTicks.size,
                recentTicks = upImpulseTicks,
                assetPayoutPercent = 85
            ),
            wsLatencyMs = 500L // exceeds 450ms
        )
        assertEquals(SignalDirection.NONE, staleEval.finalSignal)
        assertFalse(staleEval.readyToExecute)
    }

    @Test
    fun `test mathematical correctness across HMM stochasticity, complementary microstructure probabilities, out-of-order candle close, and idempotent TIME-5 acceleration`() {
        // 1. Out-of-order historical tick preserves completed candle close while updating high/low/tickCount
        val candleEngine = CandleEngine(candleSizeSec = 5L)
        candleEngine.processTick(Tick("LATAM_X", 100.0, 10_000L)) // bucket 10s open=100, close=100
        candleEngine.processTick(Tick("LATAM_X", 101.5, 14_000L)) // bucket 10s close=101.5
        candleEngine.processTick(Tick("LATAM_X", 102.0, 16_000L)) // bucket 15s (completes bucket 10s)
        // Late out-of-order tick from 12_000L with extreme low 99.2
        val candlesAfterLateTick = candleEngine.processTick(Tick("LATAM_X", 99.2, 12_000L))
        val bucket10s = candlesAfterLateTick.first { it.timeSec == 10L }
        assertEquals("Completed candle close must not be overwritten by earlier out-of-order tick", 101.5, bucket10s.close, 1e-9)
        assertEquals("Out-of-order tick must still update low", 99.2, bucket10s.low, 1e-9)
        assertEquals("Out-of-order tick must increment tickCount", 3, bucket10s.tickCount)

        // 2. Microstructure complementary probabilities (breakoutProb + meanReversionProb == 100.0) & bounded displacement10
        val indicatorEngine = IndicatorEngine()
        val baseTs = 1700000000000L
        val ticks = (0 until 45).map { i ->
            val p = when {
                i < 30 -> 4500.0 + (if (i % 2 == 0) 0.08 else -0.08)
                i < 42 -> 4500.0 + (i - 29) * 0.65
                else -> 4500.0 + 12 * 0.65 + (i - 41) * 0.05 // deceleration after steep slope triggers exhaustion
            }
            Tick("LATAM_X", p, baseTs + i * 200L)
        }
        ticks.forEach { candleEngine.processTick(it) }
        val ind = indicatorEngine.computeIndicators(ticks, candleEngine.getAllCandles())
        assertEquals(
            "Breakout and mean-reversion probabilities must be complementary (sum to 100%)",
            100.0,
            ind.microstructure.breakoutProb + ind.microstructure.meanReversionProb,
            1e-6
        )
        assertTrue(
            "displacement10 must be bounded within [0, 100]: ${ind.microstructure.displacement10}",
            ind.microstructure.displacement10 in 0.0..100.0
        )

        // 3. HMM posterior state probabilities sum to 1.0
        val regimeEngine = RegimeDetectionEngine()
        val hmm = regimeEngine.processTicks(ticks)
        assertNotNull(hmm)
        assertEquals(
            "HMM posterior state probabilities must sum to 1.0",
            1.0,
            hmm!!.stateProbabilities.sum(),
            1e-6
        )

        // 4. TIME-5 acceleration is idempotent when evaluated multiple times on the exact same tick timestamp
        val strategyEngine = ModularStrategyEngine()
        val strategy = HardLockedMandate.lockedStrategyConfig()
        val frame1Ticks = ticks.take(35)
        val frame2Ticks = ticks.take(36)
        strategyEngine.evaluateStrategy(
            strategy = strategy,
            telemetry = MarketTelemetryState(
                pair = "LATAM_X",
                latestPrice = frame1Ticks.last().price,
                latestTimeMs = frame1Ticks.last().timeMs,
                tickCount = frame1Ticks.size,
                recentTicks = frame1Ticks,
                assetPayoutPercent = 85
            ),
            wsLatencyMs = 30L
        )
        val eval2A = strategyEngine.evaluateStrategy(
            strategy = strategy,
            telemetry = MarketTelemetryState(
                pair = "LATAM_X",
                latestPrice = frame2Ticks.last().price,
                latestTimeMs = frame2Ticks.last().timeMs,
                tickCount = frame2Ticks.size,
                recentTicks = frame2Ticks,
                assetPayoutPercent = 85
            ),
            wsLatencyMs = 30L
        )
        val eval2B = strategyEngine.evaluateStrategy(
            strategy = strategy,
            telemetry = MarketTelemetryState(
                pair = "LATAM_X",
                latestPrice = frame2Ticks.last().price,
                latestTimeMs = frame2Ticks.last().timeMs,
                tickCount = frame2Ticks.size,
                recentTicks = frame2Ticks,
                assetPayoutPercent = 85
            ),
            wsLatencyMs = 30L
        )
        assertEquals(
            "Repeated evaluation on identical tick timestamp must preserve acceleration without decaying to 0",
            eval2A.time5Metrics.acceleration,
            eval2B.time5Metrics.acceleration,
            1e-9
        )
    }
}
