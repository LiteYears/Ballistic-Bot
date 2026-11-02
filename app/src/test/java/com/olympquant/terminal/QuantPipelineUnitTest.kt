package com.olympquant.terminal

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.olympquant.terminal.domain.EnergyState
import com.olympquant.terminal.domain.HardLockedMandate
import com.olympquant.terminal.domain.MarketPhase
import com.olympquant.terminal.domain.MarketTelemetryState
import com.olympquant.terminal.domain.MicroUrgency
import com.olympquant.terminal.domain.MicrostructureSnapshot
import com.olympquant.terminal.domain.RegimeOutput
import com.olympquant.terminal.domain.SignalDirection
import com.olympquant.terminal.domain.Tick
import com.olympquant.terminal.domain.TriggerType
import com.olympquant.terminal.engine.CandleEngine
import com.olympquant.terminal.engine.IndicatorEngine
import com.olympquant.terminal.engine.ModularStrategyEngine
import com.olympquant.terminal.engine.RegimeDetectionEngine
import com.olympquant.terminal.ui.screens.HmmAndAuditScreen
import com.olympquant.terminal.ui.theme.OlympQuantTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuantPipelineUnitTest {

    @get:Rule
    val composeTestRule = createComposeRule()

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
    fun `test HmmAndAuditScreen renders without crashing in warmup, active, and NaN edge states`() {
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

        val edgeState = MarketTelemetryState(
            pair = "LATAM_X",
            latestPrice = 4537.0,
            tickCount = 60,
            candles = candles,
            recentTicks = ticks,
            fibLevels = ind.fibLevels,
            microstructure = ind.microstructure.copy(breakoutProb = Double.NaN),
            hmmOutput = nanInjectedHmm,
            orderFlowImbalance = Double.NaN
        )

        composeTestRule.setContent {
            OlympQuantTheme {
                HmmAndAuditScreen(telemetry = edgeState)
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("hmm_audit_screen_list").assertExists()
    }

    @Test
    fun `test Hawkes-Laminar BallisticApex5s strategy trigger and hard stop gates`() {
        val strategyEngine = ModularStrategyEngine()
        val lockedStrategy = HardLockedMandate.lockedStrategyConfig()
        assertEquals(TriggerType.HAWKES_BALLISTIC_IMPULSE, lockedStrategy.triggerType)

        val activeTelemetry = MarketTelemetryState(
            pair = "LATAM_X",
            latestPrice = 4520.0,
            tickCount = 65,
            assetPayoutPercent = 85,
            assetLocked = false,
            orderFlowImbalance = 42.0,
            tickIntensityRatio = 1.32,
            trend = SignalDirection.UP,
            htfBias = SignalDirection.UP,
            consistency = 0.85,
            volatility = 0.30,
            velocity = 3.2,
            slopeRatio = 1.85,
            netDelta5 = 0.65,
            relPos = 76.0,
            baseConfidenceScore = 82.0,
            microstructure = MicrostructureSnapshot(
                tapeSpeed = 8,
                directionalEfficiency = 82.0,
                microAcceleration = 0.92,
                microPersistence = 50.0,
                microImpulseScore = 86.0,
                impulseDirection = SignalDirection.UP,
                streakCount = 5,
                urgency = MicroUrgency.SURGING,
                energyState = EnergyState.EXPLODING,
                phase = MarketPhase.EXPANSION
            )
        )

        val eval = strategyEngine.evaluateStrategy(
            strategy = lockedStrategy,
            telemetry = activeTelemetry,
            wsLatencyMs = 42L
        )

        assertEquals(SignalDirection.UP, eval.rawSignal)
        assertEquals(SignalDirection.UP, eval.finalSignal)
        assertTrue(eval.readyToExecute)

        // Verify Hard Stop blocks execution when 10 trades or 3 consecutive losses are reached
        val hardStoppedEval = strategyEngine.evaluateStrategy(
            strategy = lockedStrategy,
            telemetry = activeTelemetry.copy(hardStopReason = "10/10 Daily Trade Cap Reached (Hard Stop)"),
            wsLatencyMs = 42L
        )
        assertFalse(hardStoppedEval.readyToExecute)
    }
}
