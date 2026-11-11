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
import com.olympquant.terminal.engine.DefaultStrategyScripts
import com.olympquant.terminal.engine.IndicatorEngine
import com.olympquant.terminal.engine.ModularStrategyEngine
import com.olympquant.terminal.engine.RegimeDetectionEngine
import com.olympquant.terminal.engine.StrategyScriptCompiler
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
        val futureTick = Tick(
            pair = "LATAM_X",
            price = rushTicks.last().price + 1.25,
            timeMs = rushTicks.last().timeMs + 5200L
        )
        val postShadowTicks = rushTicks + futureTick
        val postShadowMetrics = strategyEngine.extractRawTickRushMetrics(
            rushTelemetry.copy(
                latestPrice = futureTick.price,
                latestTimeMs = futureTick.timeMs,
                tickCount = postShadowTicks.size,
                recentTicks = postShadowTicks
            )
        )
        assertTrue(postShadowMetrics.shadowSamplesLearned >= 1)

        // 5. Verify Hard Stop blocks execution when 10 trades or 3 consecutive losses are reached
        val hardStoppedEval = strategyEngine.evaluateStrategy(
            strategy = lockedStrategy,
            telemetry = rushTelemetry.copy(hardStopReason = "10/10 Daily Trade Cap Reached (Hard Stop)"),
            wsLatencyMs = 38L
        )
        assertFalse(hardStoppedEval.readyToExecute)
    }

    @Test
    fun `test OQS parser diagnostics while live execution remains hard-locked to Sniper M`() {
        val defaultCompiled = com.olympquant.terminal.engine.StrategyScriptCompiler.compile(
            DefaultStrategyScripts.SNIPER_M_OQS_SCRIPT
        )
        assertTrue(defaultCompiled.isValid)
        assertNotNull(defaultCompiled.parsedScript)
        assertEquals("Sniper M", defaultCompiled.parsedScript!!.name)
        assertEquals(5, defaultCompiled.parsedScript!!.execution.durationSec)

        // Verify custom user-programmed strategy with custom [EXECUTION], [VARIABLES], [SIGNAL_UP], [SIGNAL_DOWN], and [GATES]
        val customOqs = """
            [META]
            name = "User_Custom_Reversal_15s"
            version = "1.2"
            author = "QuantTrader"
            description = "Custom programmable strategy testing user variables and gates."

            [EXECUTION]
            duration_sec = 15
            stake_mode = "FIXED_USD"
            stake_value = 25.0
            min_stake_usd = 1.0
            max_stake_usd = 4000.0
            cooldown_ms = 5000
            min_payout_pct = 80
            min_confidence = 50.0
            max_daily_trades = 12
            max_consecutive_losses = 4

            [VARIABLES]
            let custom_ratio = rush_snr * 2.0
            let spread_abs = abs(z_fast - z_slow)

            [SIGNAL_UP]
            rush_dir == UP
            custom_ratio >= 2.5

            [SIGNAL_DOWN]
            rush_dir == DOWN
            custom_ratio >= 2.5

            [GATES]
            gate "Custom Ratio Gate" : custom_ratio >= 2.5 and tick_purity >= 65.0
            gate "Latency Check" : ws_latency_ms <= 150
        """.trimIndent()

        val customConfig = HardLockedMandate.fromScript(
            id = 42L,
            scriptCode = customOqs,
            isPreset = false,
            isEnabled = true
        )
        assertEquals("User_Custom_Reversal_15s", customConfig.name)
        assertEquals(15, customConfig.durationSeconds)
        assertEquals(25.0, customConfig.computeEffectiveStake(1000.0), 1e-6)
        assertEquals(12, customConfig.maxDailyTrades)
        assertEquals(4, customConfig.maxConsecutiveLosses)

        val baseTs = 1700000000000L
        val noiseTicks = (0 until 14).map { i ->
            val offset = if (i % 2 == 0) 0.12 else -0.12
            Tick(pair = "LATAM_X", price = 4500.0 + offset, timeMs = baseTs + i * 240L)
        }
        val rushTicks = noiseTicks + listOf(0.38, 0.80, 1.25, 1.74).mapIndexed { idx, cum ->
            Tick(pair = "LATAM_X", price = noiseTicks.last().price + cum, timeMs = noiseTicks.last().timeMs + (idx + 1) * 130L)
        }
        val telemetry = MarketTelemetryState(
            pair = "LATAM_X",
            latestPrice = rushTicks.last().price,
            tickCount = rushTicks.size,
            assetPayoutPercent = 85,
            assetLocked = false,
            recentTicks = rushTicks,
            zFast = 4501.5,
            zSlow = 4500.2
        )

        // A custom script can still be parsed for diagnostics, but the live engine must ignore it.
        val engine = ModularStrategyEngine()
        val eval = engine.evaluateStrategy(
            strategy = customConfig,
            telemetry = telemetry,
            wsLatencyMs = 45L
        )
        assertEquals("Sniper M", eval.strategyName)
        assertEquals(58.0, eval.minConfidenceRequired, 1e-6)
        assertTrue("Live execution must retain Sniper M's five-second script", eval.triggerReason.contains("5s"))

        // Verify syntax error reporting when a mandatory block or unknown variable is used
        val brokenOqs = """
            [META]
            name = "Broken"
            [SIGNAL_UP]
            non_existent_var >= 10.0
        """.trimIndent()
        val brokenCompiled = com.olympquant.terminal.engine.StrategyScriptCompiler.compile(brokenOqs)
        assertFalse(brokenCompiled.isValid)
        assertTrue(brokenCompiled.errors.isNotEmpty())
    }

    @Test
    fun `test X13 Temporal Intelligence Engine rolling windows hysteresis N-of-M persistence and 3-layer architecture`() {
        val x13Compiled = com.olympquant.terminal.engine.StrategyScriptCompiler.compile(
            DefaultStrategyScripts.SNIPER_M_OQS_SCRIPT
        )
        assertTrue(x13Compiled.isValid)
        assertNotNull(x13Compiled.parsedScript)
        assertEquals("Sniper M", x13Compiled.parsedScript!!.name)

        val x13Strategy = HardLockedMandate.fromScript(
            id = 1L,
            scriptCode = DefaultStrategyScripts.SNIPER_M_OQS_SCRIPT,
            isPreset = true,
            isEnabled = true
        )

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
        assertEquals("Sniper M", lockedConfig.name)
        assertEquals(5, lockedConfig.durationSeconds)
        val compiledLocked = StrategyScriptCompiler.compile(lockedConfig.scriptCode)
        assertTrue("Canonical Sniper M script must compile cleanly: ${compiledLocked.errors}", compiledLocked.isValid)
        assertEquals(5, compiledLocked.parsedScript?.execution?.durationSec)

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
            wsLatencyMs = 28L,
            scriptCode = DefaultStrategyScripts.SNIPER_M_OQS_SCRIPT
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

        // 4. Sniper M always labels the exact five-second horizon, independent of UI timeframe.
        // A tick arriving at +6s brackets the +5s target; interpolation must settle at +6s arrival.
        val recentCleanTicks = cleanTicks.takeLast(120)
        val tickAt6s = Tick(
            pair = "LATAM_X",
            price = recentCleanTicks.last().price + 0.8,
            timeMs = recentCleanTicks.last().timeMs + 6_000L
        )
        val metricsAt6s = strategyEngine.extractRawTickRushMetrics(
            MarketTelemetryState(
                pair = "LATAM_X",
                latestPrice = tickAt6s.price,
                latestTimeMs = tickAt6s.timeMs,
                tickCount = recentCleanTicks.size + 1,
                recentTicks = recentCleanTicks + tickAt6s,
                selectedTimeframeSec = 15
            )
        )
        assertTrue("Sniper M must settle a bracketed five-second shadow at the first post-horizon tick", metricsAt6s.shadowSamplesLearned >= 1)
    }

    @Test
    fun `test Sniper M analyzes tick stream smooths trajectory follows momentum and rides UP and DOWN surges with zero filter blocks`() {
        val strategyEngine = ModularStrategyEngine()
        val sniperStrategy = HardLockedMandate.lockedStrategyConfig()
        assertEquals("Sniper M", sniperStrategy.name)

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

        // 1. Verify getLiveSymbolSnapshot with hysteresis(...) in [VARIABLES] does NOT mutate hysteresis state
        val hystScript = """
            [META]
            name = "HystAuditTest"
            version = "1.0"
            author = "Audit"
            description = "Tests non-mutating hysteresis evaluation in getLiveSymbolSnapshot"
            [EXECUTION]
            duration_sec = 5
            [VARIABLES]
            let custom_hyst = hysteresis(rush_snr, 1.50, 1.10)
            [SIGNAL_UP]
            rush_dir == UP
            [SIGNAL_DOWN]
            rush_dir == DOWN
            [GATES]
            gate "HystGate" : custom_hyst == 1
        """.trimIndent()

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
        // Call getLiveSymbolSnapshot (read-only UI/Studio preview) when rush_snr >= 1.50
        val snapHigh = strategyEngine.getLiveSymbolSnapshot(highSnrTelemetry, wsLatencyMs = 25L, scriptCode = hystScript)
        assertEquals(1.0, snapHigh["custom_hyst"] ?: 0.0, 1e-6)

        // Now evaluate at rush_snr ~ 1.20 (between exit=1.10 and enter=1.50):
        // Because getLiveSymbolSnapshot did NOT mutate hysteresis state, the latch must still be false (0.0)!
        val midActivated = strategyEngine.temporalEngine.evaluateHysteresis(
            key = "dsl_hyst_rush_snr_1.50_1.10",
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

        val compiledRollMean = StrategyScriptCompiler.compile(
            """
            [META]
            name = "ZeroMeanRollTest"
            version = "1.0"
            author = "Audit"
            description = "Tests zero-mean rolling series"
            [EXECUTION]
            duration_sec = 5
            [VARIABLES]
            let ofi_mean_2 = roll_mean(ofi, 2)
            [SIGNAL_UP]
            rush_dir == UP
            [SIGNAL_DOWN]
            rush_dir == DOWN
            [GATES]
            gate "G1" : rush_snr >= 0.0
            """.trimIndent()
        ).parsedScript!!
        val symbolsWithNonZeroCurrentOfi = mutableMapOf("ofi" to -25.0, "rush_dir" to 1.0, "rush_snr" to 1.4, "UP" to 1.0, "DOWN" to -1.0)
        val evalZeroMean = StrategyScriptCompiler.evaluateVariablesAndSignals(
            compiledRollMean,
            symbolsWithNonZeroCurrentOfi,
            zeroMeanEngine.temporalEngine
        )
        // Must be 0.0 (the true 2-observation rolling mean of +25 and -25), NOT fallback to raw ofi (-25.0)!
        assertEquals(0.0, evalZeroMean.evaluatedSymbols["ofi_mean_2"] ?: -999.0, 1e-6)

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

        // 5. Verify Sniper M v8.0 rejects collapsing/dying surge tails (tick_convexity < 0.55)
        val dyingSurgePrices = listOf(
            4500.00, 4500.06, 4499.96, 4500.04, 4499.98, 4500.05,
            4500.85, 4501.65, // Big early steps (+0.80, +0.80)
            4501.79, 4501.91  // Dying tail steps (+0.14, +0.12 -> convexity = 0.26 / 1.60 = 0.16 < 0.55)
        )
        val dyingTicks = dyingSurgePrices.mapIndexed { idx, p ->
            Tick(pair = "LATAM_X", price = p, timeMs = baseTimeMs + idx * 140L)
        }
        val dyingEval = ModularStrategyEngine().evaluateStrategy(
            strategy = HardLockedMandate.lockedStrategyConfig(),
            telemetry = MarketTelemetryState(
                pair = "LATAM_X",
                latestPrice = dyingTicks.last().price,
                latestTimeMs = dyingTicks.last().timeMs,
                tickCount = dyingTicks.size,
                recentTicks = dyingTicks,
                wsSynchronized = true
            ),
            wsLatencyMs = 25L
        )
        assertFalse("Collapsing/dying surge tail (tick_convexity < 0.55) must not execute", dyingEval.readyToExecute)
    }
}





