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
    fun `test OQS Strategy Script Compiler, uniform 6-block structure, and custom loaded strategy execution`() {
        val defaultCompiled = com.olympquant.terminal.engine.StrategyScriptCompiler.compile(
            DefaultStrategyScripts.DEFAULT_OQS_SCRIPT
        )
        assertTrue(defaultCompiled.isValid)
        assertNotNull(defaultCompiled.parsedScript)
        assertEquals("TickRush5s_FTRL_Default", defaultCompiled.parsedScript!!.name)
        assertEquals(5, defaultCompiled.parsedScript!!.execution.durationSec)

        // Verify template generator follows the exact same 6-block structure
        val templateCode = DefaultStrategyScripts.buildNewStrategyTemplate("Alpha_Custom_10s")
        val templateCompiled = com.olympquant.terminal.engine.StrategyScriptCompiler.compile(templateCode)
        assertTrue(templateCompiled.isValid)
        assertEquals("Alpha_Custom_10s", templateCompiled.parsedScript!!.name)

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

        val engine = ModularStrategyEngine()
        val eval = engine.evaluateStrategy(
            strategy = customConfig,
            telemetry = telemetry,
            wsLatencyMs = 45L
        )
        assertEquals("User_Custom_Reversal_15s", eval.strategyName)
        assertEquals(SignalDirection.UP, eval.finalSignal)
        assertEquals(2, eval.filterDiagnostics.size)
        assertEquals("Custom Ratio Gate", eval.filterDiagnostics[0].gateTitle)
        assertTrue(eval.readyToExecute)

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
            DefaultStrategyScripts.X13_TEMPORAL_OQS_SCRIPT
        )
        assertTrue(x13Compiled.isValid)
        assertNotNull(x13Compiled.parsedScript)
        assertEquals("X13_Temporal_Intelligence_5s", x13Compiled.parsedScript!!.name)

        val x13Strategy = HardLockedMandate.fromScript(
            id = 1L,
            scriptCode = DefaultStrategyScripts.X13_TEMPORAL_OQS_SCRIPT,
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
        assertTrue(lastEval.temporalTelemetry.validationLayerScore >= 52.0)
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
    fun `test HighVoltage5s 6 strict mathematical filters, HV Window states, TIGHT_CHOP and CRAZY_WHIPSAW traps, and Trade Timeframe Expansion`() {
        // 1. Verify Trade Timeframe Expansion (5s, 15s, 30s, 45s, 1m / 60s)
        val timeframes = com.olympquant.terminal.domain.TradeTimeframe.entries
        assertEquals(listOf(5, 15, 30, 45, 60), timeframes.map { it.seconds })
        assertEquals(listOf("5s", "15s", "30s", "45s", "1m"), timeframes.map { it.label })

        for (tf in timeframes) {
            val hvConfig = HardLockedMandate.highVoltageStrategyConfig(durationSec = tf.seconds)
            assertEquals("HighVoltage5s_HVWindow", hvConfig.name)
            assertEquals(tf.seconds, hvConfig.durationSeconds)
            val compiled = StrategyScriptCompiler.compile(hvConfig.scriptCode)
            assertTrue("HighVoltage script for ${tf.label} must compile cleanly: ${compiled.errors}", compiled.isValid)
            assertEquals(tf.seconds, compiled.parsedScript?.execution?.durationSec)
        }

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
        val hv30sStrategy = HardLockedMandate.highVoltageStrategyConfig(durationSec = 30)
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

        // 3. Verify Filter 5 (S/R Wall Check): Resistance within 0.25 points ahead blocks UP entry
        val blockedResistance = SRZone(
            centerPrice = cleanBreakoutTicks.last().price + 0.12, // Only 0.12 pts ahead (< 0.25 pt required)
            lowerBound = cleanBreakoutTicks.last().price + 0.07,
            upperBound = cleanBreakoutTicks.last().price + 0.17,
            touchCount = 4,
            lastTouchTimeSec = baseTimeMs / 1000L,
            isSupport = false
        )
        val indSrBlocked = indicatorEngine.computeIndicators(
            ticks = cleanBreakoutTicks.takeLast(120),
            candles = candles,
            swings = emptyList(),
            psychLevels = emptyList(),
            nearestSupport = null,
            nearestResistance = blockedResistance
        )
        assertFalse("Filter 5 must block UP trade when resistance is 0.12pt ahead (< 0.25pt)", indSrBlocked.highVoltageStatus.srObstaclePassed)
        assertFalse(indSrBlocked.highVoltageStatus.allSixFiltersPassed)

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
}

