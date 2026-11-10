package com.olympquant.terminal.domain

import androidx.compose.runtime.Immutable
import com.olympquant.terminal.engine.DefaultStrategyScripts
import com.olympquant.terminal.engine.StrategyScriptCompiler
import kotlinx.serialization.Serializable
import kotlin.math.roundToLong

fun Double.finiteOr(default: Double = 0.0): Double = if (this.isFinite()) this else default
fun Float.finiteOr(default: Float = 0f): Float = if (this.isFinite()) this else default

@Serializable
enum class TradeTimeframe(
    val durationSec: Int,
    val label: String,
    val displayTitle: String
) {
    TF_5S(5, "5s", "5 Seconds"),
    TF_15S(15, "15s", "15 Seconds"),
    TF_30S(30, "30s", "30 Seconds"),
    TF_45S(45, "45s", "45 Seconds"),
    TF_1M(60, "1m", "1 Minute");

    val seconds: Int get() = durationSec
    val title: String get() = displayTitle

    companion object {
        fun fromDurationSec(sec: Int): TradeTimeframe {
            return entries.firstOrNull { it.durationSec == sec }
                ?: entries.minByOrNull { kotlin.math.abs(it.durationSec - sec) }
                ?: TF_5S
        }

        fun fromSeconds(sec: Int): TradeTimeframe = fromDurationSec(sec)
    }
}

@Serializable
enum class HvWindowState(
    val badgeText: String,
    val scoreRangeLabel: String,
    val marketCondition: String,
    val actionText: String
) {
    EXCELLENT(
        badgeText = "EXCELLENT",
        scoreRangeLabel = "≥75%",
        marketCondition = "Clean, high-velocity directional trend with solid candle bodies, low rejection wicks, and aligned 15s–60s anchors.",
        actionText = "Optimal Execution Zone — Auto-Bot cleared to engage."
    ),
    MODERATE(
        badgeText = "MODERATE",
        scoreRangeLabel = "50% – 74%",
        marketCondition = "Developing trend with moderate pullbacks or transitional momentum across micro-anchors.",
        actionText = "Selective Standby — Require all 6 breakout gates to align."
    ),
    AVOID(
        badgeText = "AVOID",
        scoreRangeLabel = "<50%",
        marketCondition = "Flat range compression (TIGHT_CHOP) or erratic whipsaw rejection wicks (>55%).",
        actionText = "Stand Down — Execution paused until structure stabilizes."
    );

    val label: String get() = badgeText
}

@Serializable
enum class HvMarketTrap(val label: String, val summary: String) {
    NONE("CLEAN MOMENTUM", "Directional candle structure • No chop or whipsaw detected"),
    TIGHT_CHOP("TIGHT CHOP", "60s multi-timeframe anchor range compressed below scale floor"),
    CRAZY_WHIPSAW("ERRATIC WHIPSAW", "Erratic candle reversals with dominant rejection wicks > 55%")
}

@Immutable
@Serializable
data class HvWindowSnapshot(
    val score: Double = 60.0,
    val rawScore: Double = 60.0,
    val state: HvWindowState = HvWindowState.MODERATE,
    val activeTrap: HvMarketTrap = HvMarketTrap.NONE,
    val tightChopDetected: Boolean = false,
    val crazyWhipsawDetected: Boolean = false,
    val wickCollapseRatioPercent: Double = 20.0,
    val solidBodyRatioPercent: Double = 65.0,
    val anchorRange60s: Double = 0.45,
    val tightChopThreshold: Double = 0.20,
    val delta5s: Double = 0.0,
    val delta15s: Double = 0.0,
    val delta30s: Double = 0.0,
    val delta45s: Double = 0.0,
    val delta60s: Double = 0.0,
    val mtfAlignedCount: Int = 0,
    val stabilitySeconds: Double = 0.0,
    val engineBooted: Boolean = true,
    val botLaunchReady: Boolean = false,
    val pauseImmediately: Boolean = false,
    val stepGuideHint: String = "Wait for HV Window score ≥ 72% for 15–20s before arming Auto-Bot."
) {
    val stabilityReady: Boolean get() = botLaunchReady
    val recommendation: String get() = stepGuideHint
}

@Immutable
@Serializable
data class HighVoltageFilterStatus(
    val zlemaTrend: SignalDirection = SignalDirection.NONE,
    val zlemaSpread: Double = 0.0,
    val zFastValue: Double = 0.0,
    val zSlowValue: Double = 0.0,
    val trendFilterPassed: Boolean = false,
    val velocityTps: Double = 0.0,
    val minVelocityRequired: Double = 1.5,
    val velocityFilterPassed: Boolean = false,
    val relPos50: Double = 50.0,
    val breakoutUpperPct: Double = 80.0,
    val breakoutLowerPct: Double = 20.0,
    val positionBreakoutPassed: Boolean = false,
    val netDelta5: Double = 0.0,
    val activeMomentumPassed: Boolean = false,
    val srClearanceAhead: Double = 999.0,
    val srMinClearanceRequired: Double = 0.25,
    val srObstaclePassed: Boolean = true,
    val currentCandleRange: Double = 0.0,
    val avg10CandleRange: Double = 0.0,
    val candleRangeRatio: Double = 1.0,
    val isCompressed: Boolean = false,
    val isExhausted: Boolean = false,
    val volatilityFilterPassed: Boolean = true,
    val hvWindowPassed: Boolean = true,
    val allSixFiltersPassed: Boolean = false,
    val activePlaybook: String = "SCANNING",
    val opportunityDirection: SignalDirection = SignalDirection.NONE,
    val continuationReady: Boolean = false,
    val srBounceReady: Boolean = false,
    val meanReversionReady: Boolean = false,
    val opportunityScore: Double = 0.0,
    val supportDistance: Double = 999.0,
    val resistanceDistance: Double = 999.0
) {
    val trendDirection: SignalDirection
        get() = if (opportunityDirection != SignalDirection.NONE) opportunityDirection else zlemaTrend
    val zFast: Double get() = if (zFastValue != 0.0 || zSlowValue != 0.0) zFastValue else zlemaSpread
    val zSlow: Double get() = zSlowValue
    val tickVelocity: Double get() = velocityTps
    val breakoutFilterPassed: Boolean get() = positionBreakoutPassed
    val momentumFilterPassed: Boolean get() = activeMomentumPassed
    val passedFilterCount: Int
        get() = if (srBounceReady || meanReversionReady) {
            6
        } else {
            listOf(
                trendFilterPassed,
                velocityFilterPassed,
                positionBreakoutPassed,
                activeMomentumPassed,
                srObstaclePassed,
                volatilityFilterPassed
            ).count { it }
        }
}

/**
 * Default Strategy & Safety Bounds for the OlympQuant Strategy Script (.oqs) Engine.
 * - Supports `HighVoltage5s_HVWindow.oqs`, `X13_Temporal_Intelligence_5s.oqs`, and `TickRush5s_FTRL_Default.oqs`
 * - Supports expanded trade timeframes: 5s, 15s, 30s, 45s, 1m (60s).
 */
object HardLockedMandate {
    const val STRATEGY_NAME = DefaultStrategyScripts.SNIPER_M_STRATEGY_NAME
    const val STRATEGY_DESCRIPTION =
        "Sniper M: Analyzes raw tick stream data, smooths the trajectory (Zero-Lag Fast vs Slow EMA), follows aligned momentum, and executes when a tick stream surge fires in the smoothed direction."
    const val MIN_STAKE_USD = 1.0
    const val BALANCE_STAKE_PERCENT = 5.0
    const val MAX_STAKE_USD = 4000.0
    const val MAX_TRADES_PER_DAY = 10
    const val MAX_CONSECUTIVE_LOSSES = 3
    const val LOCKED_DURATION_SEC = 5
    const val LOCKED_COOLDOWN_MS = 4000L
    const val MIN_PAYOUT_PERCENT = 75
    const val MIN_CONFIDENCE_REQUIRED = 60.0

    fun computeLockedStake(balance: Double, activeStrategy: StrategyConfig? = null): Double {
        if (activeStrategy != null) {
            return activeStrategy.computeEffectiveStake(balance)
        }
        val safeBalance = balance.finiteOr(0.0).coerceAtLeast(0.0)
        val fivePercent = safeBalance * (BALANCE_STAKE_PERCENT / 100.0)
        val clamped = fivePercent.coerceIn(MIN_STAKE_USD, MAX_STAKE_USD)
        return ((clamped * 100.0).roundToLong() / 100.0)
    }

    fun lockedStrategyConfig(): StrategyConfig = fromScript(
        id = 1L,
        scriptCode = DefaultStrategyScripts.SNIPER_M_OQS_SCRIPT,
        isPreset = true,
        isEnabled = true
    )

    fun highVoltageStrategyConfig(durationSec: Int = LOCKED_DURATION_SEC): StrategyConfig = fromScript(
        id = 1L,
        scriptCode = DefaultStrategyScripts.withDurationSec(
            DefaultStrategyScripts.SNIPER_M_OQS_SCRIPT,
            durationSec
        ),
        isPreset = true,
        isEnabled = true
    )

    fun fromScript(
        id: Long,
        scriptCode: String,
        isPreset: Boolean = false,
        isEnabled: Boolean = true
    ): StrategyConfig {
        val compiled = StrategyScriptCompiler.compile(scriptCode)
        val parsed = compiled.parsedScript
        val exec = parsed?.execution
        return StrategyConfig(
            id = id,
            name = parsed?.name ?: STRATEGY_NAME,
            description = parsed?.description ?: STRATEGY_DESCRIPTION,
            isPreset = isPreset,
            isEnabled = isEnabled,
            durationSeconds = exec?.durationSec ?: LOCKED_DURATION_SEC,
            stakeAmount = exec?.minStakeUsd ?: MIN_STAKE_USD,
            cooldownMs = exec?.cooldownMs ?: LOCKED_COOLDOWN_MS,
            minConfidence = exec?.minConfidence ?: MIN_CONFIDENCE_REQUIRED,
            triggerType = TriggerType.RAW_TICK_RUSH_5S,
            triggerThreshold = 1.35,
            regimeAdaptiveMode = RegimeAdaptiveMode.OFF,
            filters = listOf(
                StrategyFilterRule(FilterType.TICK_RUSH_SNR_GATE, true, 1.35),
                StrategyFilterRule(FilterType.TICK_DIRECTIONAL_PURITY, true, 72.0),
                StrategyFilterRule(FilterType.TICK_SPEED_AND_FRESHNESS, true, 1.15),
                StrategyFilterRule(FilterType.ANTI_SPIKE_DOMINANCE_GATE, true, 62.0),
                StrategyFilterRule(FilterType.FTRL_ML_META_LABEL_GATE, true, 62.0)
            ),
            scriptCode = scriptCode,
            stakeMode = exec?.stakeMode ?: "PERCENT_BALANCE",
            stakeValue = exec?.stakeValue ?: BALANCE_STAKE_PERCENT,
            minStakeUsd = exec?.minStakeUsd ?: MIN_STAKE_USD,
            maxStakeUsd = exec?.maxStakeUsd ?: MAX_STAKE_USD,
            minPayoutPercent = exec?.minPayoutPct ?: MIN_PAYOUT_PERCENT,
            maxDailyTrades = exec?.maxDailyTrades ?: MAX_TRADES_PER_DAY,
            maxConsecutiveLosses = exec?.maxConsecutiveLosses ?: MAX_CONSECUTIVE_LOSSES
        )
    }
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
    RAW_TICK_RUSH_5S(
        "Raw Tick-Stream Rush (5s)",
        "Measures tick-by-tick displacement directly from the raw tick stream and enters for 5s when directional speed & purity break out above baseline noise oscillation."
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
    TICK_RUSH_SNR_GATE("Tick Rush Signal-to-Noise Ratio", "x", 1.35, 1.1, 3.0),
    TICK_DIRECTIONAL_PURITY("4-Tick One-Way Purity & Tip", "%", 72.0, 55.0, 95.0),
    TICK_SPEED_AND_FRESHNESS("Tick Speed & Fresh Window (2-6t)", "T/s", 1.15, 0.8, 4.0),
    ANTI_SPIKE_DOMINANCE_GATE("Anti-Spike Dominance & Convexity", "%", 62.0, 40.0, 80.0),
    FTRL_ML_META_LABEL_GATE("Online FTRL-Proximal P(Win 5s)", "%", 62.0, 52.0, 85.0)
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
    val filters: List<StrategyFilterRule>,
    val scriptCode: String = DefaultStrategyScripts.SNIPER_M_OQS_SCRIPT,
    val stakeMode: String = "PERCENT_BALANCE",
    val stakeValue: Double = HardLockedMandate.BALANCE_STAKE_PERCENT,
    val minStakeUsd: Double = HardLockedMandate.MIN_STAKE_USD,
    val maxStakeUsd: Double = HardLockedMandate.MAX_STAKE_USD,
    val minPayoutPercent: Int = HardLockedMandate.MIN_PAYOUT_PERCENT,
    val maxDailyTrades: Int = HardLockedMandate.MAX_TRADES_PER_DAY,
    val maxConsecutiveLosses: Int = HardLockedMandate.MAX_CONSECUTIVE_LOSSES
) {
    fun computeEffectiveStake(balance: Double): Double {
        val safeBalance = balance.finiteOr(0.0).coerceAtLeast(0.0)
        val raw = if (stakeMode.equals("FIXED_USD", ignoreCase = true)) {
            stakeValue.finiteOr(minStakeUsd)
        } else {
            safeBalance * (stakeValue.finiteOr(5.0).coerceIn(0.1, 100.0) / 100.0)
        }
        val lo = minStakeUsd.finiteOr(1.0).coerceAtLeast(1.0)
        val hi = maxStakeUsd.finiteOr(4000.0).coerceIn(lo, 4000.0)
        val clamped = raw.coerceIn(lo, hi)
        return ((clamped * 100.0).roundToLong() / 100.0)
    }
}

@Serializable
enum class TemporalRegimeType(val displayName: String) {
    CONSOLIDATION("Consolidation"),
    TREND("Trend"),
    EXPANSION_IMPULSE("Expansion Impulse"),
    TRANSITION_UNCERTAIN("Transition (Hold)")
}

@Immutable
@Serializable
data class TemporalTelemetrySnapshot(
    val snapshotSynchronized: Boolean = true,
    val tickAgeMs: Long = 0L,
    val regimeType: TemporalRegimeType = TemporalRegimeType.CONSOLIDATION,
    val regimeTransitionStable: Boolean = true,
    val eligibilityLayerPassed: Boolean = true,
    val signalLayerScore: Double = 0.0,
    val validationLayerScore: Double = 0.0,
    val calibratedWinProbPercent: Double = 50.0,
    val rollingSnrMean: Double = 0.0,
    val rollingPurityMean: Double = 0.0,
    val rollingOfiMedian: Double = 0.0,
    val rollingImpulseMean: Double = 0.0,
    val momentumHysteresisActive: Boolean = false,
    val purityHysteresisActive: Boolean = false,
    val snrHysteresisActive: Boolean = false,
    val persistenceCount5: Int = 0,
    val noiseClean: Boolean = true,
    val upWinRatePercent: Double = 66.0,
    val downWinRatePercent: Double = 66.0,
    val regimeWinRatePercent: Double = 66.0,
    val regimeSnrThreshold: Double = 1.32,
    val regimePurityThreshold: Double = 70.0,
    val gateStabilityPercent: Double = 92.5,
    val falsePositiveRatePercent: Double = 6.8,
    val impulseDetectionRatePercent: Double = 84.0,
    val signalLatencyMs: Long = 165L,
    val calibrationAccuracyPercent: Double = 83.2,
    val oosExpectancyPercent: Double = 18.4
)

@Immutable
@Serializable
data class FilterDiagnostic(
    val filterType: FilterType,
    val passed: Boolean,
    val actualValueFormatted: String,
    val thresholdFormatted: String,
    val reason: String,
    val gateTitle: String = filterType.displayName
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
    val readyToExecute: Boolean,
    val temporalTelemetry: TemporalTelemetrySnapshot = TemporalTelemetrySnapshot()
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
    val hardStopReason: String? = null,
    val tickRateTps: Double = 0.0,
    val wsSynchronized: Boolean = true,
    val temporalTelemetry: TemporalTelemetrySnapshot = TemporalTelemetrySnapshot(),
    val hvWindow: HvWindowSnapshot = HvWindowSnapshot(),
    val highVoltageStatus: HighVoltageFilterStatus = HighVoltageFilterStatus(),
    val selectedTimeframeSec: Int = 5,
    val autopilotArmed: Boolean = false,
    val autopilotStandby: Boolean = false,
    val hourlySniperMode: Boolean = false,
    val hourlyTradesThisHour: Int = 0,
    val nextHourlyResetMinutes: Int = 0,
    val singleTradeLocked: Boolean = false
)


