package com.olympquant.terminal.domain

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

fun Double.finiteOr(default: Double = 0.0): Double = if (this.isFinite()) this else default
fun Float.finiteOr(default: Float = 0f): Float = if (this.isFinite()) this else default

/**
 * Immutable, hard-locked execution & risk mandate enforced across the entire engine.
 * - Stake: 5% of entire balance, minimum $1.00, maximum $4,000.00 per trade
 * - Daily Cap: Maximum 10 trades per day (Hard Stop)
 * - Loss Streak Guard: Hard Stop on 3 consecutive losses
 * - Strategy: Single proprietary Hawkes-Laminar Ballistic Impulse Continuation (5s)
 */
object HardLockedMandate {
    const val STRATEGY_NAME = "BallisticApex5s (Hawkes-Laminar Impulse)"
    const val STRATEGY_DESCRIPTION =
        "Novel HFT impulse continuation engine: captures self-exciting Hawkes tick-arrival compression, laminar Kaufman fractal efficiency (>=64%), convex 2nd-derivative acceleration, and unexhausted ballistic corridor headroom to ride 5s burst continuation."
    const val MIN_STAKE_USD = 1.0
    const val BALANCE_STAKE_PERCENT = 5.0
    const val MAX_STAKE_USD = 4000.0
    const val MAX_TRADES_PER_DAY = 10
    const val MAX_CONSECUTIVE_LOSSES = 3
    const val LOCKED_DURATION_SEC = 5
    const val LOCKED_COOLDOWN_MS = 4000L
    const val MIN_PAYOUT_PERCENT = 75
    const val MIN_CONFIDENCE_REQUIRED = 76.0

    fun computeLockedStake(balance: Double): Double {
        val safeBalance = balance.finiteOr(0.0).coerceAtLeast(0.0)
        val fivePercent = safeBalance * (BALANCE_STAKE_PERCENT / 100.0)
        val clamped = fivePercent.coerceIn(MIN_STAKE_USD, MAX_STAKE_USD)
        return ((clamped * 100.0).roundToLong() / 100.0)
    }

    fun lockedStrategyConfig(): StrategyConfig = StrategyConfig(
        id = 1L,
        name = STRATEGY_NAME,
        description = STRATEGY_DESCRIPTION,
        isPreset = true,
        isEnabled = true,
        durationSeconds = LOCKED_DURATION_SEC,
        stakeAmount = MIN_STAKE_USD,
        cooldownMs = LOCKED_COOLDOWN_MS,
        minConfidence = MIN_CONFIDENCE_REQUIRED,
        triggerType = TriggerType.HAWKES_BALLISTIC_IMPULSE,
        triggerThreshold = 68.0,
        regimeAdaptiveMode = RegimeAdaptiveMode.SKIP_BAD,
        filters = listOf(
            StrategyFilterRule(FilterType.HAWKES_ARRIVAL_INTENSITY, true, 18.0),
            StrategyFilterRule(FilterType.LAMINAR_EFFICIENCY_GATE, true, 64.0),
            StrategyFilterRule(FilterType.CONVEX_ACCELERATION_GATE, true, 0.30),
            StrategyFilterRule(FilterType.BALLISTIC_HEADROOM_CORRIDOR, true, 93.5),
            StrategyFilterRule(FilterType.ZLEMA_HMM_COHERENCE, true, 140.0)
        )
    )
}

@Immutable
@Serializable
data class Tick(
    val pair: String,
    val price: Double,
    val timeMs: Long
)

@Immutable
@Serializable
data class Candle(
    val timeSec: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val tickCount: Int = 1
) {
    val bodySize: Double get() = kotlin.math.abs(close - open)
    val totalRange: Double get() = (high - low).coerceAtLeast(1e-9)
    val isBullish: Boolean get() = close >= open
    val upperWick: Double get() = high - maxOf(open, close)
    val lowerWick: Double get() = minOf(open, close) - low
}

@Serializable
enum class SwingType { HIGH, LOW }

@Immutable
@Serializable
data class SwingPoint(
    val type: SwingType,
    val price: Double,
    val candleTimeSec: Long,
    val strength: Int = 1
)

@Immutable
@Serializable
data class SRZone(
    val centerPrice: Double,
    val lowerBound: Double,
    val upperBound: Double,
    val touchCount: Int,
    val lastTouchTimeSec: Long,
    val isSupport: Boolean
)

@Immutable
@Serializable
data class FibonacciLevels(
    val high50: Double = 0.0,
    val low50: Double = 0.0,
    val fib236: Double = 0.0,
    val fib382: Double = 0.0,
    val fib500: Double = 0.0,
    val fib618: Double = 0.0,
    val fib786: Double = 0.0
)

@Serializable
enum class SignalDirection { UP, DOWN, NONE }

@Immutable
@Serializable
data class PsychGridBounceSignal(
    val levelPrice: Double,
    val direction: SignalDirection,
    val timestampMs: Long
)

@Serializable
enum class SessionQuality { OPTIMAL, NEUTRAL, AVOID }

@Serializable
enum class RegimeAdaptiveMode { OFF, SKIP_BAD, REVERSE_BAD, ADAPTIVE_ALL }

@Serializable
enum class TerminalDensityMode { PRO_TERMINAL, MINIMAL_EXECUTION, SPLIT_ALL }

@Serializable
enum class MicroUrgency { SURGING, STEADY, HESITANT }

@Serializable
enum class MicroParticipation { ACTIVE, THIN, DORMANT }

@Serializable
enum class EnergyState { EXPLODING, INCREASING, STABLE, LEAKING, COLLAPSING }

@Serializable
enum class MarketPhase { MOMENTUM, EXPANSION, PRESSURE_BUILD, LEVEL_TEST, EXHAUSTION }

@Serializable
enum class LandmarkType { SWING_HIGH, SWING_LOW, ROUND_LEVEL, FIB_618, REVERSAL_POINT, NONE }

@Serializable
enum class MoneyManagementMode(val displayName: String) {
    DYNAMIC_COMPOUND("Locked 5% Balance ($1–$4000)");

    val label: String get() = displayName
}

@Immutable
@Serializable
data class TradeSettingsConfig(
    val defaultStake: Double = HardLockedMandate.MIN_STAKE_USD,
    val defaultDurationSeconds: Int = HardLockedMandate.LOCKED_DURATION_SEC,
    val globalCooldownSeconds: Int = 4,
    val minPayoutPercent: Int = HardLockedMandate.MIN_PAYOUT_PERCENT,
    val moneyManagementMode: MoneyManagementMode = MoneyManagementMode.DYNAMIC_COMPOUND,
    val martingaleMultiplier: Double = 1.0,
    val maxMartingaleSteps: Int = 0,
    val overrideStrategyStakeAndDuration: Boolean = true
)

@Immutable
@Serializable
data class RiskConfig(
    val maxDailyLossLimit: Double = 0.0,
    val maxDailyLossPercent: Double = 15.0,
    val dailyProfitTarget: Double = 0.0,
    val maxConsecutiveLosses: Int = HardLockedMandate.MAX_CONSECUTIVE_LOSSES,
    val dynamicCompoundPercent: Double = HardLockedMandate.BALANCE_STAKE_PERCENT,
    val autoStopOnDailyLoss: Boolean = true
)

@Immutable
@Serializable
data class MicrostructureSnapshot(
    val tapeSpeed: Int = 0,
    val displacement10: Double = 0.0,
    val directionalEfficiency: Double = 0.0, // Kaufman Efficiency Ratio (0..100%)
    val microAcceleration: Double = 0.0,
    val microPersistence: Double = 0.0,      // -100..+100 signed streak score
    val microHesitation: Double = 0.0,       // 0..100%
    val microExhaustion: Double = 0.0,       // 0..100%
    val microImpulseScore: Double = 0.0,     // 0..100% composite ballistic impulse strength
    val impulseDirection: SignalDirection = SignalDirection.NONE,
    val streakCount: Int = 0,
    val urgency: MicroUrgency = MicroUrgency.STEADY,
    val participation: MicroParticipation = MicroParticipation.THIN,
    val energyState: EnergyState = EnergyState.STABLE,
    val phase: MarketPhase = MarketPhase.MOMENTUM,
    val activeLandmark: LandmarkType = LandmarkType.NONE,
    val breakoutProb: Double = 50.0,
    val meanReversionProb: Double = 50.0,
    val mci: Double = 50.0
)

@Immutable
@Serializable
data class ActiveOpenTrade(
    val brokerTradeId: Long,
    val strategyName: String,
    val pair: String,
    val direction: SignalDirection,
    val stake: Double,
    val entryPrice: Double,
    val openedAtMs: Long,
    val expiresAtMs: Long,
    val payoutPercent: Int = 82
)

@Immutable
@Serializable
data class HistoricalEdge(
    val horizon: Int,
    val count: Int,
    val pUp: Double,
    val statSig: Boolean,
    val edgeStrength: Double
)

@Immutable
@Serializable
data class RegimeOutput(
    val state: Int,
    val regimeName: String,
    val confidence: Double,
    val duration: Int,
    val persistence: Double,
    val stateProbabilities: List<Double>,
    val scaledFeatures: Map<String, Double>,
    val historicalEdges: List<HistoricalEdge>
)

@Serializable
enum class TriggerType(val displayName: String, val description: String) {
    HAWKES_BALLISTIC_IMPULSE(
        "Hawkes-Laminar Ballistic Impulse",
        "Captures self-exciting tick-arrival compression, convex 2nd-derivative acceleration, and laminar efficiency before impulse exhaustion."
    )
}

@Serializable
enum class FilterType(
    val displayName: String,
    val unit: String,
    val defaultThreshold: Double,
    val minRange: Double,
    val maxRange: Double
) {
    HAWKES_ARRIVAL_INTENSITY("Hawkes Arrival & Order-Flow Gate", "OFI%", 18.0, 10.0, 60.0),
    LAMINAR_EFFICIENCY_GATE("Laminar Fractal Efficiency (ER10)", "%", 64.0, 50.0, 95.0),
    CONVEX_ACCELERATION_GATE("Convex 2nd-Derivative Acceleration", "T/s²", 0.30, 0.10, 3.0),
    BALLISTIC_HEADROOM_CORRIDOR("Ballistic Headroom (Anti-Climax)", "relPos%", 93.5, 80.0, 96.0),
    ZLEMA_HMM_COHERENCE("ZLEMA Phase-Lead & Latency Guard", "ms", 140.0, 50.0, 250.0)
}

@Immutable
@Serializable
data class StrategyFilterRule(
    val filterType: FilterType,
    val enabled: Boolean = true,
    val threshold: Double = filterType.defaultThreshold
)

@Immutable
@Serializable
data class StrategyConfig(
    val id: Long = 1L,
    val name: String,
    val description: String,
    val isPreset: Boolean,
    val isEnabled: Boolean,
    val durationSeconds: Int,
    val stakeAmount: Double,
    val cooldownMs: Long,
    val minConfidence: Double,
    val triggerType: TriggerType,
    val triggerThreshold: Double,
    val regimeAdaptiveMode: RegimeAdaptiveMode,
    val filters: List<StrategyFilterRule>
)

@Immutable
@Serializable
data class FilterDiagnostic(
    val filterType: FilterType,
    val passed: Boolean,
    val actualValueFormatted: String,
    val thresholdFormatted: String,
    val reason: String
)

@Immutable
@Serializable
data class StrategyEvaluation(
    val strategyId: Long,
    val strategyName: String,
    val rawSignal: SignalDirection,
    val finalSignal: SignalDirection,
    val confidenceScore: Double,
    val minConfidenceRequired: Double,
    val triggerReason: String,
    val filterDiagnostics: List<FilterDiagnostic>,
    val contextBlockReasons: List<String>,
    val regimeAdjustmentNote: String,
    val readyToExecute: Boolean
)

@Immutable
@Serializable
data class MarketTelemetryState(
    val pair: String = "LATAM_X",
    val latestPrice: Double = 0.0,
    val latestTimeMs: Long = 0L,
    val tickCount: Int = 0,
    val assetPayoutPercent: Int = 85,
    val assetLocked: Boolean = false,
    val bullishSentimentPercent: Int = 54,
    val orderFlowImbalance: Double = 0.0,
    val tickIntensityRatio: Double = 1.0,
    val serverClockOffsetMs: Long = 0L,
    val candles: List<Candle> = emptyList(),
    val recentTicks: List<Tick> = emptyList(),
    val zFast: Double = 0.0,
    val zSlow: Double = 0.0,
    val trend: SignalDirection = SignalDirection.NONE,
    val htfBias: SignalDirection = SignalDirection.NONE,
    val trendAgeSeconds: Double = 0.0,
    val persistence: Int = 0,
    val consistency: Double = 0.0,
    val volatility: Double = 0.0,
    val velocity: Double = 0.0,
    val slope: Double = 0.0,
    val slopeRatio: Double = 0.0,
    val netDelta5: Double = 0.0,
    val relPos: Double = 50.0,
    val fibLevels: FibonacciLevels = FibonacciLevels(),
    val microstructure: MicrostructureSnapshot = MicrostructureSnapshot(),
    val hvoiScore: Double = 50.0,
    val sessionQuality: SessionQuality = SessionQuality.NEUTRAL,
    val adaptiveCorridorWidth: Double = 0.20,
    val nearestSupport: SRZone? = null,
    val nearestResistance: SRZone? = null,
    val srDistance: Double = 999.0,
    val swingPoints: List<SwingPoint> = emptyList(),
    val psychGridLevels: List<Double> = emptyList(),
    val lastPsychBounce: PsychGridBounceSignal? = null,
    val hmmOutput: RegimeOutput? = null,
    val contextBlockReasons: List<String> = emptyList(),
    val baseConfidenceScore: Double = 0.0,
    val activeEvaluations: List<StrategyEvaluation> = emptyList(),
    val activeOpenTrades: List<ActiveOpenTrade> = emptyList(),
    val dailyNetPnl: Double = 0.0,
    val dailyTradesCount: Int = 0,
    val consecutiveLosses: Int = 0,
    val currentMartingaleStep: Int = 0,
    val nextEffectiveStake: Double = 1.0,
    val hardStopReason: String? = null
)
