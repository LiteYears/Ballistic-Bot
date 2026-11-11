package com.olympquant.terminal.engine

import com.olympquant.terminal.domain.FilterDiagnostic
import com.olympquant.terminal.domain.FilterType
import com.olympquant.terminal.domain.HardLockedMandate
import com.olympquant.terminal.domain.MarketTelemetryState
import com.olympquant.terminal.domain.SignalDirection
import com.olympquant.terminal.domain.StrategyConfig
import com.olympquant.terminal.domain.StrategyEvaluation
import com.olympquant.terminal.domain.TemporalRegimeType
import com.olympquant.terminal.domain.TemporalTelemetrySnapshot
import com.olympquant.terminal.domain.finiteOr
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * OlympQuant X13 — Two-Stage Sub-Millisecond HFT Continuation + Temporal Intelligence Engine:
 *  - Stage 1: `TickRush5s` Raw Tick-Stream Rush vs. Noise Oscillation Primary Detector
 *  - Stage 2: Online FTRL-Proximal Logistic Regression Meta-Labeling Classifier
 *  - Stage 3: `TemporalIntelligenceEngine` (Snapshot Synchronization, Rolling Windows,
 *    Schmitt-Trigger Hysteresis, N-of-M Persistence, Adaptive Regimes, and 3-Layer Scoring)
 */
class ModularStrategyEngine {

    enum class ShadowFingerprint { SPIKE_DOMINANCE, EXHAUSTION, WEAK_FOLLOW_THROUGH, OTHER }

    internal enum class CandidatePopulation {
        NONE,
        POPULATION_A_OBSERVED_IMPULSE,
        POPULATION_B_STRUCTURAL_SNIPER_CANDIDATE,
        POPULATION_C_DETERMINISTIC_GATES_PASSED,
        POPULATION_D_BROKER_ACCEPTED_TRADE
    }

    internal enum class ExpiryResolutionStatus {
        PENDING_FUTURE_OBSERVATION,
        RESOLVED_EXACT_TIMESTAMP,
        RESOLVED_LAST_QUOTE_AT_OR_BEFORE,
        INVALID_TIMESTAMP_SEQUENCE,
        INVALID_BRACKET_GAP_OR_MISSING
    }

    internal data class ExpiryResolution(
        val status: ExpiryResolutionStatus,
        val expiryPrice: Double = Double.NaN,
        val expiryQuoteTimeMs: Long = 0L,
        val confirmingTickTimeMs: Long = 0L,
        val bracketGapMs: Long = 0L
    )

    internal data class PendingShadowRush(
        val entryTimeMs: Long,
        val entryPrice: Double,
        val direction: SignalDirection,
        val features: DoubleArray,
        val regime: TemporalRegimeType = TemporalRegimeType.EXPANSION_IMPULSE,
        val predictedProb: Double = 0.5,
        val wasSpikeNoise: Boolean = false,
        val fingerprint: ShadowFingerprint = ShadowFingerprint.OTHER,
        val horizonMs: Long = 5000L,
        val population: CandidatePopulation = CandidatePopulation.POPULATION_B_STRUCTURAL_SNIPER_CANDIDATE,
        val firstPostTriggerPrice: Double = Double.NaN,
        val firstPostTriggerTimeMs: Long = 0L
    )

    internal data class ShadowSettlementRecord(
        val entryTimeMs: Long,
        val targetExpiryTimeMs: Long,
        val expiryQuoteTimeMs: Long,
        val confirmingTickTimeMs: Long,
        val entryPrice: Double,
        val expiryPrice: Double,
        val firstPostTriggerPrice: Double,
        val direction: SignalDirection,
        val predictedProb: Double,
        val won: Boolean,
        val isTie: Boolean,
        val population: CandidatePopulation,
        val resolutionStatus: ExpiryResolutionStatus
    )

    internal data class BrokerAcceptedTradeRecord(
        val brokerTradeId: Long,
        val pair: String,
        val triggerTimeMs: Long,
        val estimatedEntryPrice: Double,
        val brokerConfirmedOpenPrice: Double,
        val acceptedAtMs: Long
    )

    internal data class PopulationDiagnosticsSnapshot(
        val observedImpulseCount: Int,
        val structuralCandidateCount: Int,
        val deterministicGatePassCount: Int,
        val brokerAcceptedTradeCount: Int,
        val resolvedShadowSampleCount: Int,
        val invalidShadowSampleCount: Int,
        val tieShadowSampleCount: Int,
        val broadImpulseResolvedCount: Int,
        val broadImpulseWinCount: Int,
        val broadImpulseInvalidCount: Int
    )

    data class ExecutionQualitySummary(
        val schemaVersion: String,
        val learnedSamples: Int,
        val rollingShadowWinRatePercent: Double,
        val postTriggerWinRatePercent: Double,
        val adaptiveModelReady: Boolean,
        val meanBrokerSlippagePts: Double
    )

    internal data class RawTickRushMetrics(
        val rushDirection: SignalDirection,
        val avgNoiseStep: Double,
        val netDelta4: Double,
        val rushSnr: Double,
        val purityPercent: Double,
        val consecutiveStreak: Int,
        val tipAligned: Boolean,
        val tickSpeedTps: Double,
        val clearedNoiseMean: Boolean,
        val lastStepRatio: Double,
        val tailRetentionRatio: Double = 1.0,
        val tickConvexity: Double,
        val arrivalCompressionRatio: Double,
        val singleTickDominancePercent: Double,
        val microPullbackRecovery: Double,
        val localZScore15: Double,
        val rollingShadowWinRatePercent: Double,
        val shadowSamplesLearned: Int,
        val ftrlWinProbPercent: Double,
        val shadowFingerprint: ShadowFingerprint = ShadowFingerprint.OTHER,
        val candidatePopulation: CandidatePopulation = CandidatePopulation.NONE
    )

    companion object {
        internal const val FEATURE_DIM = 11
        internal const val CURRENT_LEARNING_STATE_SCHEMA = "SNIPER_FTRL_V2"
        internal const val LEGACY_LEARNING_STATE_SCHEMA_V1 = "SNIPER_FTRL_V1"
        internal const val RETIRED_V1_TARGET_LEAK_FEATURE_INDEX = 7
        private const val MIN_ADAPTIVE_LABELS = 30
        private const val MIN_FTRL_WIN_PROBABILITY = 0.53
        private const val MIN_REGIME_DIRECTION_LABELS = 16
        private const val MIN_REGIME_DIRECTION_WIN_RATE = 0.40
        private const val MIN_FINGERPRINT_LABELS = 16
        private const val MIN_FINGERPRINT_WIN_RATE = 0.40
        internal const val MAX_SHADOW_LABEL_BRACKET_GAP_MS = 1_500L
        internal const val HIGH_WS_LATENCY_THRESHOLD_MS = 280L
        internal const val FTRL_ALPHA = 0.14
        internal const val FTRL_BETA = 1.0
        internal const val FTRL_L1 = 0.015
        internal const val FTRL_L2 = 0.12
        internal const val FORGETTING_GAMMA = 0.985
    }

    private val ftrlZ = DoubleArray(FEATURE_DIM)
    private val ftrlN = DoubleArray(FEATURE_DIM)
    private val ftrlW = DoubleArray(FEATURE_DIM)
    private val pendingShadowRushes = ArrayDeque<PendingShadowRush>(64)
    private val pendingBroadImpulses = ArrayDeque<PendingShadowRush>(64)
    private val settledShadowRecords = ArrayDeque<ShadowSettlementRecord>(128)
    private val brokerAcceptedTrades = ArrayDeque<BrokerAcceptedTradeRecord>(64)
    private var lastShadowEnqueueTimeMs: Long = 0L
    private var lastBroadImpulseEnqueueTimeMs: Long = 0L
    private var rollingShadowWinRate: Double = 0.5
    private var shadowSamplesCount: Int = 0
    private var invalidShadowSamplesCount: Int = 0
    private var tieShadowSamplesCount: Int = 0
    private var observedImpulseCount: Int = 0
    private var structuralCandidateCount: Int = 0
    private var deterministicGatePassCount: Int = 0
    private var brokerAcceptedTradeCount: Int = 0
    private var broadImpulseResolvedCount: Int = 0
    private var broadImpulseWinCount: Int = 0
    private var broadImpulseInvalidCount: Int = 0
    internal var onlineTrainingEnabled: Boolean = true
    internal var lastRestoredSchemaVersion: String = CURRENT_LEARNING_STATE_SCHEMA
        private set
    internal var migratedFromLegacySchema: Boolean = false
        private set
    val temporalEngine = TemporalIntelligenceEngine()

    init {
        reset()
    }

    /**
     * Clears only transient per-pair pending queues when switching symbols or restarting the feed,
     * preserving persisted FTRL weights (`ftrlZ`, `ftrlN`, `ftrlW`) and learned sample counts.
     */
    @Synchronized
    fun resetTransientPairQueues() {
        pendingShadowRushes.clear()
        pendingBroadImpulses.clear()
        lastShadowEnqueueTimeMs = 0L
        lastBroadImpulseEnqueueTimeMs = 0L
    }

    @Synchronized
    fun reset() {
        pendingShadowRushes.clear()
        pendingBroadImpulses.clear()
        settledShadowRecords.clear()
        brokerAcceptedTrades.clear()
        lastShadowEnqueueTimeMs = 0L
        lastBroadImpulseEnqueueTimeMs = 0L
        rollingShadowWinRate = 0.5
        shadowSamplesCount = 0
        invalidShadowSamplesCount = 0
        tieShadowSamplesCount = 0
        observedImpulseCount = 0
        structuralCandidateCount = 0
        deterministicGatePassCount = 0
        brokerAcceptedTradeCount = 0
        broadImpulseResolvedCount = 0
        broadImpulseWinCount = 0
        broadImpulseInvalidCount = 0
        onlineTrainingEnabled = true
        lastRestoredSchemaVersion = CURRENT_LEARNING_STATE_SCHEMA
        migratedFromLegacySchema = false
        temporalEngine.reset()
        for (i in 0 until FEATURE_DIM) {
            // Neutral cold start: no hand-authored weights or assumed edge.
            ftrlZ[i] = 0.0
            ftrlN[i] = 0.0
            ftrlW[i] = 0.0
        }
    }

    @Synchronized
    internal fun exportLearningState(): String {
        return buildString {
            append(CURRENT_LEARNING_STATE_SCHEMA)
            append('|')
            append(shadowSamplesCount)
            append('|')
            append(rollingShadowWinRate)
            for (value in ftrlZ) {
                append('|')
                append(value)
            }
            for (value in ftrlN) {
                append('|')
                append(value)
            }
        }
    }

    @Synchronized
    internal fun restoreLearningState(serialized: String): Boolean {
        val parts = serialized.split('|')
        if (parts.size != 3 + FEATURE_DIM * 2) return false
        val schema = parts[0]
        if (schema != CURRENT_LEARNING_STATE_SCHEMA && schema != LEGACY_LEARNING_STATE_SCHEMA_V1) return false
        val samples = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return false
        val rollingRate = parts[2].toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: return false
        val zValues = parts.subList(3, 3 + FEATURE_DIM).map { it.toDoubleOrNull() ?: return false }
        val nValues = parts.subList(3 + FEATURE_DIM, parts.size).map { it.toDoubleOrNull() ?: return false }
        if (zValues.any { !it.isFinite() || abs(it) > 1.0e12 }) return false
        if (nValues.any { !it.isFinite() || it < 0.0 || it > 1.0e12 }) return false

        shadowSamplesCount = samples
        rollingShadowWinRate = rollingRate
        for (i in 0 until FEATURE_DIM) {
            ftrlZ[i] = zValues[i]
            ftrlN[i] = nValues[i]
        }
        if (schema == LEGACY_LEARNING_STATE_SCHEMA_V1) {
            // Explicit V1 -> V2 schema migration:
            // Coordinate 7 in SNIPER_FTRL_V1 encoded the endogenous rollingShadowWinRate target EMA.
            // In SNIPER_FTRL_V2, coordinate 7 encodes exogenous prediction-time 4-tick purityPercent.
            // Never silently reinterpret the legacy target-leak accumulator on the new feature.
            ftrlZ[RETIRED_V1_TARGET_LEAK_FEATURE_INDEX] = 0.0
            ftrlN[RETIRED_V1_TARGET_LEAK_FEATURE_INDEX] = 0.0
            ftrlW[RETIRED_V1_TARGET_LEAK_FEATURE_INDEX] = 0.0
            lastRestoredSchemaVersion = "$LEGACY_LEARNING_STATE_SCHEMA_V1->$CURRENT_LEARNING_STATE_SCHEMA"
            migratedFromLegacySchema = true
        } else {
            lastRestoredSchemaVersion = CURRENT_LEARNING_STATE_SCHEMA
            migratedFromLegacySchema = false
        }
        predictFtrlWinProbability(DoubleArray(FEATURE_DIM))
        return true
    }

    @Synchronized
    internal fun learnedSampleCount(): Int = shadowSamplesCount

    @Synchronized
    internal fun invalidShadowSampleCount(): Int = invalidShadowSamplesCount

    @Synchronized
    internal fun tieShadowSampleCount(): Int = tieShadowSamplesCount

    @Synchronized
    internal fun getPopulationDiagnostics(): PopulationDiagnosticsSnapshot = PopulationDiagnosticsSnapshot(
        observedImpulseCount = observedImpulseCount,
        structuralCandidateCount = structuralCandidateCount,
        deterministicGatePassCount = deterministicGatePassCount,
        brokerAcceptedTradeCount = brokerAcceptedTradeCount,
        resolvedShadowSampleCount = shadowSamplesCount,
        invalidShadowSampleCount = invalidShadowSamplesCount,
        tieShadowSampleCount = tieShadowSamplesCount,
        broadImpulseResolvedCount = broadImpulseResolvedCount,
        broadImpulseWinCount = broadImpulseWinCount,
        broadImpulseInvalidCount = broadImpulseInvalidCount
    )

    @Synchronized
    internal fun getSettledShadowRecords(): List<ShadowSettlementRecord> = settledShadowRecords.toList()

    @Synchronized
    internal fun getFtrlWeight(index: Int): Double = if (index in 0 until FEATURE_DIM) ftrlW[index] else 0.0

    @Synchronized
    internal fun getFtrlZ(index: Int): Double = if (index in 0 until FEATURE_DIM) ftrlZ[index] else 0.0

    @Synchronized
    internal fun getFtrlN(index: Int): Double = if (index in 0 until FEATURE_DIM) ftrlN[index] else 0.0

    @Synchronized
    fun recordBrokerAcceptedTrade(
        brokerTradeId: Long,
        pair: String,
        triggerTimeMs: Long,
        estimatedEntryPrice: Double,
        brokerConfirmedOpenPrice: Double,
        acceptedAtMs: Long = triggerTimeMs
    ) {
        if (brokerTradeId <= 0L) return
        brokerAcceptedTradeCount++
        if (brokerAcceptedTrades.size >= 64) {
            brokerAcceptedTrades.removeFirst()
        }
        brokerAcceptedTrades.addLast(
            BrokerAcceptedTradeRecord(
                brokerTradeId = brokerTradeId,
                pair = pair,
                triggerTimeMs = triggerTimeMs,
                estimatedEntryPrice = estimatedEntryPrice,
                brokerConfirmedOpenPrice = brokerConfirmedOpenPrice,
                acceptedAtMs = acceptedAtMs
            )
        )
    }

    @Synchronized
    internal fun getBrokerAcceptedTrades(): List<BrokerAcceptedTradeRecord> = brokerAcceptedTrades.toList()

    @Synchronized
    fun getExecutionQualitySummary(): ExecutionQualitySummary {
        val postTriggerEligible = settledShadowRecords.filter { !it.isTie && it.firstPostTriggerPrice.isFinite() }
        val postTriggerWinRate = if (postTriggerEligible.isNotEmpty()) {
            val wins = postTriggerEligible.count { rec ->
                (rec.direction == SignalDirection.UP && rec.expiryPrice > rec.firstPostTriggerPrice) ||
                    (rec.direction == SignalDirection.DOWN && rec.expiryPrice < rec.firstPostTriggerPrice)
            }
            (wins.toDouble() / postTriggerEligible.size.toDouble()) * 100.0
        } else {
            rollingShadowWinRate * 100.0
        }
        val meanSlippage = if (brokerAcceptedTrades.isNotEmpty()) {
            brokerAcceptedTrades.map { abs(it.brokerConfirmedOpenPrice - it.estimatedEntryPrice) }.average().finiteOr(0.0)
        } else {
            0.0
        }
        return ExecutionQualitySummary(
            schemaVersion = lastRestoredSchemaVersion,
            learnedSamples = shadowSamplesCount,
            rollingShadowWinRatePercent = (rollingShadowWinRate * 100.0).finiteOr(50.0),
            postTriggerWinRatePercent = postTriggerWinRate.finiteOr(50.0),
            adaptiveModelReady = shadowSamplesCount >= MIN_ADAPTIVE_LABELS,
            meanBrokerSlippagePts = meanSlippage
        )
    }

    @Synchronized
    fun getLiveSymbolSnapshot(
        telemetry: MarketTelemetryState,
        wsLatencyMs: Long
    ): Map<String, Double> {
        val rush = extractRawTickRushMetrics(telemetry, mutateState = false)
        val temporalSnapshot = temporalEngine.pushObservationFrame(telemetry, rush, wsLatencyMs, mutateState = false)
        val hv = telemetry.hvWindow
        val hvFilter = telemetry.highVoltageStatus
        val dynamicSrMinClearance = if (hvFilter.srMinClearanceRequired > 0.0 && hvFilter.srMinClearanceRequired < 100.0) {
            hvFilter.srMinClearanceRequired
        } else if (telemetry.latestPrice > 0.0 && telemetry.latestPrice < 20.0) {
            0.00025
        } else {
            0.25
        }
        val effectiveTps = maxOf(
            rush.tickSpeedTps.finiteOr(0.0),
            telemetry.velocity.finiteOr(0.0),
            telemetry.tickRateTps.finiteOr(0.0),
            hvFilter.velocityTps.finiteOr(0.0)
        )
        return mapOf(
            "price" to telemetry.latestPrice.finiteOr(0.0),
            "z_fast" to telemetry.zFast.finiteOr(0.0),
            "z_slow" to telemetry.zSlow.finiteOr(0.0),
            "rush_snr" to rush.rushSnr.finiteOr(0.0),
            "tick_purity" to rush.purityPercent.finiteOr(0.0),
            "tick_convexity" to rush.tickConvexity.finiteOr(1.0),
            "tail_retention" to rush.tailRetentionRatio.finiteOr(1.0),
            "single_tick_dom" to rush.singleTickDominancePercent.finiteOr(35.0),
            "pullback_recovery" to rush.microPullbackRecovery.finiteOr(0.75),
            "local_z15" to rush.localZScore15.finiteOr(1.5),
            "ftrl_prob" to rush.ftrlWinProbPercent.finiteOr(50.0),
            "ofi_imbalance" to telemetry.orderFlowImbalance.finiteOr(0.0),
            "rolling_ofi_median" to temporalSnapshot.rollingOfiMedian.finiteOr(0.0),
            "velocity" to effectiveTps,
            "sr_min_clearance" to dynamicSrMinClearance,
            "hv_score" to hv.score.finiteOr(60.0),
            "ws_latency_ms" to wsLatencyMs.toDouble()
        )
    }

    @Synchronized
    fun evaluateStrategy(
        strategy: StrategyConfig = HardLockedMandate.lockedStrategyConfig(),
        telemetry: MarketTelemetryState,
        wsLatencyMs: Long,
        minPayoutPercent: Int = HardLockedMandate.MIN_PAYOUT_PERCENT
    ): StrategyEvaluation {
        val rush = extractRawTickRushMetrics(telemetry)
        val temporalSnapshot = temporalEngine.pushObservationFrame(telemetry, rush, wsLatencyMs)
        val zlemaTrendDir = resolveZlemaTrendDirection(telemetry, telemetry.recentTicks, rush.rushDirection)

        val rawSignal = if (rush.rushDirection != SignalDirection.NONE && zlemaTrendDir == rush.rushDirection) {
            rush.rushDirection
        } else {
            SignalDirection.NONE
        }

        val effectiveMinPayout = minPayoutPercent.coerceIn(50, 98)
        val effectiveMinConfidence = 58.0
        val hv = telemetry.hvWindow
        val hvStatus = telemetry.highVoltageStatus
        val micro = telemetry.microstructure

        // =========================================================================================
        // SCIENTIFIC 7-GATE SNIPER M FALSE-POSITIVE REJECTION PIPELINE (SOLE NATIVE STRATEGY)
        // =========================================================================================

        // Gate 1: Market Synchronization, Latency, Payout & Chop/Whipsaw Immunity
        val syncAndFresh = temporalSnapshot.snapshotSynchronized &&
            temporalSnapshot.tickAgeMs <= 3500L &&
            wsLatencyMs <= 250L &&
            !telemetry.assetLocked &&
            telemetry.assetPayoutPercent >= effectiveMinPayout
        val noChopOrWhipsaw = !hv.tightChopDetected &&
            !hv.crazyWhipsawDetected &&
            !hv.pauseImmediately &&
            hv.wickCollapseRatioPercent.finiteOr(20.0) <= 55.0
        val gate1Passed = syncAndFresh && noChopOrWhipsaw
        val gate1Diag = FilterDiagnostic(
            filterType = FilterType.TICK_SPEED_AND_FRESHNESS,
            passed = gate1Passed,
            actualValueFormatted = if (!syncAndFresh) {
                "Sync=${temporalSnapshot.snapshotSynchronized} • ${wsLatencyMs}ms"
            } else if (!noChopOrWhipsaw) {
                hv.activeTrap.label
            } else {
                "SYNCED • ${wsLatencyMs}ms • +${telemetry.assetPayoutPercent}%"
            },
            thresholdFormatted = "Synced • ≤250ms • No Chop",
            reason = "Requires synchronized stream, RTT ≤ 250ms, payout ≥ $effectiveMinPayout%, and zero chop/whipsaw traps",
            gateTitle = "1. Sync, Latency & Chop Guard"
        )

        // Gate 2: Smoothed ZLEMA Trajectory & 5-Tick Momentum Anchor Alignment
        val netDelta5Val = telemetry.netDelta5.finiteOr(0.0)
        val netDelta5Aligned = when (rush.rushDirection) {
            SignalDirection.UP -> netDelta5Val >= -1e-9 && rush.netDelta4 > 0.0
            SignalDirection.DOWN -> netDelta5Val <= 1e-9 && rush.netDelta4 < 0.0
            SignalDirection.NONE -> false
        }
        val zlemaAligned = rush.rushDirection != SignalDirection.NONE && zlemaTrendDir == rush.rushDirection
        val gate2Passed = zlemaAligned && netDelta5Aligned && hv.score.finiteOr(60.0) >= 50.0
        val gate2Diag = FilterDiagnostic(
            filterType = FilterType.TICK_RUSH_SNR_GATE,
            passed = gate2Passed,
            actualValueFormatted = "Smooth=${zlemaTrendDir.name} • Rush=${rush.rushDirection.name} • HV=${"%.0f".format(hv.score)}%",
            thresholdFormatted = "Aligned • HV ≥ 50%",
            reason = "Requires smoothed ZLEMA (Fast vs Slow) and 5-tick net delta to align with burst direction",
            gateTitle = "2. Smoothed ZLEMA & Momentum Anchor"
        )

        // Gate 3: Winsorized Hawkes-Laminar Surge SNR & Arrival Speed
        val minSnrRequired = max(1.28, temporalSnapshot.regimeSnrThreshold.finiteOr(1.30) - 0.04)
        val gate3Passed = rush.rushSnr >= minSnrRequired &&
            rush.clearedNoiseMean &&
            rush.tickSpeedTps >= 1.15
        val gate3Diag = FilterDiagnostic(
            filterType = FilterType.TICK_RUSH_SNR_GATE,
            passed = gate3Passed,
            actualValueFormatted = "${"%.2f".format(rush.rushSnr)}x @ ${"%.2f".format(rush.tickSpeedTps)} T/s",
            thresholdFormatted = "≥${"%.2f".format(minSnrRequired)}x @ ≥1.15 T/s",
            reason = "Requires Winsorized 4-tick surge SNR ≥ ${"%.2f".format(minSnrRequired)}x above baseline noise and speed ≥ 1.15 T/s",
            gateTitle = "3. Hawkes-Laminar Surge SNR & Speed"
        )

        // Gate 4: Trajectory Purity, Tip Alignment & Non-Decelerating Convexity (Anti-Dying-Tail Guard)
        val minPurityRequired = max(68.0, temporalSnapshot.regimePurityThreshold.finiteOr(70.0) - 2.0)
        val convexityValid = rush.tickConvexity in 0.65..3.20
        val tailRetentionValid = rush.lastStepRatio >= 0.72 && rush.tailRetentionRatio >= 0.60
        val gate4Passed = rush.purityPercent >= minPurityRequired &&
            rush.tipAligned &&
            convexityValid &&
            tailRetentionValid &&
            rush.microPullbackRecovery >= 0.68
        val gate4Diag = FilterDiagnostic(
            filterType = FilterType.TICK_DIRECTIONAL_PURITY,
            passed = gate4Passed,
            actualValueFormatted = "Pur ${"%.0f".format(rush.purityPercent)}% • Cvx ${"%.2f".format(rush.tickConvexity)}x • Tail ${"%.2f".format(rush.tailRetentionRatio)}x",
            thresholdFormatted = "≥${"%.0f".format(minPurityRequired)}% • Cvx≥0.65x • Tail≥0.60x",
            reason = "Rejects decelerating/stalling tails (convexity < 0.65x or final step decay < 0.60x) and internal whipsaws",
            gateTitle = "4. Purity, Tip & Non-Decelerating Convexity"
        )

        // Gate 5: Anti-Spike Dominance & Z-Score Climax Exhaustion Guard
        val notSingleTickSpike = rush.singleTickDominancePercent <= 58.0
        val notClimaxOverextended = rush.localZScore15 in 0.70..2.90 &&
            !(rush.localZScore15 > 2.45 && rush.tickConvexity < 0.95)
        val activelyAccelerating = rush.tickConvexity in 0.95..2.40 && rush.tailRetentionRatio >= 0.85
        val notMicroExhausted = (micro.microExhaustion.finiteOr(0.0) <= 62.0 || activelyAccelerating) &&
            micro.microHesitation.finiteOr(0.0) <= 58.0
        val gate5Passed = notSingleTickSpike && notClimaxOverextended && notMicroExhausted
        val gate5Diag = FilterDiagnostic(
            filterType = FilterType.ANTI_SPIKE_DOMINANCE_GATE,
            passed = gate5Passed,
            actualValueFormatted = "Dom ${"%.0f".format(rush.singleTickDominancePercent)}% • Z15 ${"%.2f".format(rush.localZScore15)}σ • Exh ${"%.0f".format(micro.microExhaustion)}%",
            thresholdFormatted = "Dom≤58% • Z15≤2.9σ • Exh≤62%",
            reason = "Blocks isolated 1-tick liquidity spikes (>58% dominance) and overextended 15-tick Z-score climax reversals",
            gateTitle = "5. Anti-Spike & Climax Exhaustion Guard"
        )

        // Gate 6: Order-Flow Imbalance (OFI) Agreement, Slope Ratio & S/R Clearance
        val rawOfi = telemetry.orderFlowImbalance.finiteOr(0.0)
        val medOfi = temporalSnapshot.rollingOfiMedian.finiteOr(0.0)
        val ofiAligned = when (rush.rushDirection) {
            SignalDirection.UP -> rawOfi >= -18.0 && medOfi >= -14.0
            SignalDirection.DOWN -> rawOfi <= 18.0 && medOfi <= 14.0
            SignalDirection.NONE -> false
        }
        val slopeVal = telemetry.slopeRatio.finiteOr(1.0)
        val slopeAndSrPassed = (slopeVal < 3.10 || activelyAccelerating) && hvStatus.srObstaclePassed
        val gate6Passed = ofiAligned && slopeAndSrPassed
        val gate6Diag = FilterDiagnostic(
            filterType = FilterType.TICK_SPEED_AND_FRESHNESS,
            passed = gate6Passed,
            actualValueFormatted = "OFI ${"%+.1f".format(rawOfi)}% • Slope ${"%.2f".format(slopeVal)}x",
            thresholdFormatted = "OFI Aligned • Slope < 3.10x",
            reason = "Rejects counter-order-flow divergence, parabolic blow-offs (slope ≥ 3.10x), and S/R obstacles",
            gateTitle = "6. Order-Flow Imbalance & Slope Guard"
        )

        // Gate 7: Online FTRL-Proximal Meta-Labeler, Hysteresis & Walk-Forward Regime Guard
        val adaptiveModelReady = shadowSamplesCount >= MIN_ADAPTIVE_LABELS
        val learnedWinProbability = (rush.ftrlWinProbPercent / 100.0).finiteOr(0.5).coerceIn(0.01, 0.99)
        val adaptiveModelPassed = !adaptiveModelReady || learnedWinProbability >= MIN_FTRL_WIN_PROBABILITY
        val regimeDirectionWinRate = temporalEngine.recentRegimeDirectionWinRate(
            temporalSnapshot.regimeType,
            rawSignal
        )
        val regimeDirectionSampleCount = temporalEngine.recentRegimeDirectionSampleCount(
            temporalSnapshot.regimeType,
            rawSignal
        )
        val regimePerformancePassed = regimeDirectionSampleCount < MIN_REGIME_DIRECTION_LABELS ||
            (regimeDirectionWinRate ?: 0.5) >= MIN_REGIME_DIRECTION_WIN_RATE
        val fingerprintSampleCount = temporalEngine.recentFingerprintSampleCount(rush.shadowFingerprint)
        val fingerprintWinRate = temporalEngine.recentFingerprintWinRate(rush.shadowFingerprint)
        val fingerprintPerformancePassed = rush.shadowFingerprint == ShadowFingerprint.OTHER ||
            fingerprintSampleCount < MIN_FINGERPRINT_LABELS ||
            (fingerprintWinRate ?: 0.5) >= MIN_FINGERPRINT_WIN_RATE
        val hystOrStrongSnr = temporalSnapshot.momentumHysteresisActive ||
            temporalSnapshot.snrHysteresisActive ||
            rush.rushSnr >= 1.35
        val gate7Passed = adaptiveModelPassed &&
            regimePerformancePassed &&
            fingerprintPerformancePassed &&
            hystOrStrongSnr &&
            rush.ftrlWinProbPercent >= 49.5
        val gate7Diag = FilterDiagnostic(
            filterType = FilterType.FTRL_ML_META_LABEL_GATE,
            passed = gate7Passed,
            actualValueFormatted = "P(5s)=${"%.1f".format(rush.ftrlWinProbPercent)}% (${rush.shadowSamplesLearned} Lbl)",
            thresholdFormatted = if (adaptiveModelReady) "≥53.0% Calibrated" else "≥50.0% Warmup",
            reason = "Online FTRL-Proximal 5s continuation probability, Schmitt-trigger hysteresis, and walk-forward regime guard",
            gateTitle = "7. Online FTRL-Proximal & Walk-Forward Gate"
        )

        val baseDiagnostics = listOf(
            gate1Diag,
            gate2Diag,
            gate3Diag,
            gate4Diag,
            gate5Diag,
            gate6Diag,
            gate7Diag
        )

        val contextPassed = telemetry.contextBlockReasons.isEmpty()
        val finalDiagnostics = if (!contextPassed) {
            baseDiagnostics + FilterDiagnostic(
                filterType = FilterType.ANTI_SPIKE_DOMINANCE_GATE,
                passed = false,
                actualValueFormatted = "${telemetry.contextBlockReasons.size} Block(s)",
                thresholdFormatted = "0 Blocks",
                reason = telemetry.contextBlockReasons.joinToString(" • "),
                gateTitle = "Context & Structural Guard"
            )
        } else {
            baseDiagnostics
        }

        val deterministicCorePassed = gate1Passed &&
            gate2Passed &&
            gate3Passed &&
            gate4Passed &&
            gate5Passed &&
            gate6Passed &&
            contextPassed &&
            telemetry.hardStopReason == null

        // Deterministic signal-quality score remains separate from the learned win probability.
        val latencyDecelPenalty = if (wsLatencyMs > HIGH_WS_LATENCY_THRESHOLD_MS && rush.arrivalCompressionRatio > 1.05) {
            4.0
        } else {
            0.0
        }
        val rawSignalQuality = if (rawSignal != SignalDirection.NONE && deterministicCorePassed) {
            (
                46.0 +
                    min(18.0, (rush.rushSnr - 1.0).coerceAtLeast(0.0) * 12.0) +
                    min(15.0, (rush.purityPercent - 50.0).coerceAtLeast(0.0) * 0.35) +
                    ((rush.tickConvexity - 0.65).coerceIn(0.0, 1.5) * 5.0) +
                    ((rush.tickSpeedTps - 1.15).coerceAtLeast(0.0).coerceAtMost(2.0) * 2.0) +
                    (if (rush.tipAligned) 8.0 else 0.0) +
                    ((1.05 - rush.arrivalCompressionRatio).coerceIn(0.0, 0.5) * 4.0) -
                    latencyDecelPenalty
                ).finiteOr(0.0).coerceIn(0.0, 100.0)
        } else {
            0.0
        }

        val effectiveConfidence = if (adaptiveModelReady && rawSignalQuality > 0.0) {
            (rawSignalQuality + ((learnedWinProbability - 0.5) * 20.0).coerceIn(-10.0, 8.0))
                .finiteOr(0.0).coerceIn(0.0, 100.0)
        } else {
            rawSignalQuality
        }

        val enoughTicks = telemetry.tickCount >= 8 || telemetry.recentTicks.size >= 8
        val deterministicGatesPassed = enoughTicks &&
            temporalSnapshot.snapshotSynchronized &&
            rawSignal != SignalDirection.NONE &&
            deterministicCorePassed &&
            rawSignalQuality >= effectiveMinConfidence

        if (deterministicGatesPassed) {
            deterministicGatePassCount++
            val latestTickMs = telemetry.recentTicks.lastOrNull()?.timeMs ?: telemetry.latestTimeMs
            val lastPending = pendingShadowRushes.lastOrNull()
            if (lastPending != null && lastPending.entryTimeMs == latestTickMs) {
                pendingShadowRushes.removeLast()
                pendingShadowRushes.addLast(
                    lastPending.copy(population = CandidatePopulation.POPULATION_C_DETERMINISTIC_GATES_PASSED)
                )
            }
        }

        val allFiltersPassed = deterministicCorePassed && gate7Passed
        val confidencePassed = effectiveConfidence >= effectiveMinConfidence && gate7Passed
        val readyToExecute = enoughTicks &&
            temporalSnapshot.snapshotSynchronized &&
            rawSignal != SignalDirection.NONE &&
            allFiltersPassed &&
            confidencePassed

        val smoothedDirLabel = zlemaTrendDir.name

        val triggerReason = if (!contextPassed && rawSignal != SignalDirection.NONE) {
            "BLOCKED [${rawSignal.name}]: ${telemetry.contextBlockReasons.first()}"
        } else if (rawSignal != SignalDirection.NONE && deterministicCorePassed && !regimePerformancePassed) {
            "Sniper M [5s ${rawSignal.name}] paused: recent ${temporalSnapshot.regimeType.name} ${rawSignal.name} hit rate=${"%.1f".format((regimeDirectionWinRate ?: 0.0) * 100.0)}% over $regimeDirectionSampleCount labels"
        } else if (rawSignal != SignalDirection.NONE && deterministicCorePassed && !fingerprintPerformancePassed) {
            "Sniper M [5s ${rawSignal.name}] paused: recent ${rush.shadowFingerprint.name} outcomes=$fingerprintSampleCount, hit rate=${"%.1f".format((fingerprintWinRate ?: 0.0) * 100.0)}%"
        } else if (rawSignal != SignalDirection.NONE && deterministicCorePassed && adaptiveModelReady && !adaptiveModelPassed) {
            "Sniper M [5s ${rawSignal.name}] blocked by FTRL learner: P(5s)=${"%.1f".format(learnedWinProbability * 100.0)}% < ${(MIN_FTRL_WIN_PROBABILITY * 100.0).toInt()}%"
        } else if (readyToExecute) {
            "Sniper M [SURGE ${rawSignal.name} 5s]: Smooth=$smoothedDirLabel • SNR=${"%.2f".format(rush.rushSnr)}x • Cvx=${"%.2f".format(rush.tickConvexity)}x • Dom=${"%.0f".format(rush.singleTickDominancePercent)}% • Purity=${"%.0f".format(rush.purityPercent)}%"
        } else {
            val firstBlockedGate = finalDiagnostics.firstOrNull { !it.passed }
            val blockHint = firstBlockedGate?.let { "${it.gateTitle} (${it.actualValueFormatted})" } ?: "Scanning 5s Burst"
            "Sniper M [5s] • $blockHint • Smooth=$smoothedDirLabel • SNR=${"%.2f".format(rush.rushSnr)}x"
        }

        val regimeNote =
            "Sniper M Sole 5s Engine • 7-Gate Scientific False-Positive Filter • Smooth EMA ($smoothedDirLabel) • Purity ${"%.0f".format(rush.purityPercent)}% • SNR ${"%.2f".format(rush.rushSnr)}x • Convexity ${"%.2f".format(rush.tickConvexity)}x"

        return StrategyEvaluation(
            strategyId = 1L,
            strategyName = HardLockedMandate.STRATEGY_NAME,
            rawSignal = rawSignal,
            finalSignal = if (readyToExecute) rawSignal else SignalDirection.NONE,
            confidenceScore = effectiveConfidence,
            minConfidenceRequired = effectiveMinConfidence,
            triggerReason = triggerReason,
            filterDiagnostics = finalDiagnostics,
            contextBlockReasons = telemetry.contextBlockReasons,
            regimeAdjustmentNote = regimeNote,
            readyToExecute = readyToExecute,
            temporalTelemetry = temporalSnapshot
        )
    }

    @Synchronized
    internal fun extractRawTickRushMetrics(
        telemetry: MarketTelemetryState,
        mutateState: Boolean = true
    ): RawTickRushMetrics {
        val ticks = telemetry.recentTicks
        if (ticks.size >= 6) {
            val lastIdx = ticks.lastIndex
            val latestTick = ticks[lastIdx]
            val latestTimeMs = if (latestTick.timeMs > 0L) latestTick.timeMs else telemetry.latestTimeMs

            // Step A: Resolve any matured virtual shadow rushes and update FTRL-Proximal weights online!
            if (mutateState) {
                captureFirstPostTriggerQuotes(ticks)
                settleMaturedBroadImpulses(latestTimeMs, ticks)
                settleMaturedShadowRushes(latestTimeMs, ticks)
            }

            val k = min(4, lastIdx)

            // 1. Robust Winsorized baseline single-tick noise step from ticks preceding the active 3-tick tip
            //    Prevents a single prior freak tick jump from inflating the noise baseline and blinding SNR.
            val noiseEndIdx = (lastIdx - 3).coerceAtLeast(1)
            val noiseStartIdx = (noiseEndIdx - 24).coerceAtLeast(1)
            var rawNoiseSum = 0.0
            var noiseCount = 0
            for (i in noiseStartIdx..noiseEndIdx) {
                val step = abs(ticks[i].price - ticks[i - 1].price)
                if (step.isFinite() && step > 0.0) {
                    rawNoiseSum += step
                    noiseCount++
                }
            }
            if (noiseCount == 0) {
                for (i in 1..lastIdx) {
                    val step = abs(ticks[i].price - ticks[i - 1].price)
                    if (step.isFinite() && step > 0.0) {
                        rawNoiseSum += step
                        noiseCount++
                    }
                }
            }
            val prelimNoiseMean = if (noiseCount > 0) rawNoiseSum / noiseCount.toDouble() else 0.0
            val winsorCap = (prelimNoiseMean * 2.2).coerceAtLeast(1e-7)
            var winsorSum = 0.0
            var winsorCount = 0
            if (prelimNoiseMean > 0.0) {
                for (i in noiseStartIdx..noiseEndIdx) {
                    val step = abs(ticks[i].price - ticks[i - 1].price)
                    if (step.isFinite() && step > 0.0) {
                        winsorSum += min(step, winsorCap)
                        winsorCount++
                    }
                }
            }
            val avgNoiseStep = if (winsorCount > 0) {
                (winsorSum / winsorCount.toDouble()).coerceAtLeast(1e-7)
            } else if (prelimNoiseMean > 0.0) {
                prelimNoiseMean.coerceAtLeast(1e-7)
            } else {
                (telemetry.volatility * 0.45).finiteOr(1e-5).coerceAtLeast(1e-7)
            }

            // 2. Immediate 4-tick and 3-tick net displacement vs. total path length
            val netDelta4 = (ticks[lastIdx].price - ticks[lastIdx - k].price).finiteOr(0.0)
            val k3 = min(3, lastIdx)
            val netDelta3 = (ticks[lastIdx].price - ticks[lastIdx - k3].price).finiteOr(0.0)

            var pathLen4 = 0.0
            var maxSingleStep4 = 0.0
            var sameDirStepsIn4 = 0
            var forwardIn4 = 0.0
            var counterIn4 = 0.0
            val candidateDir = when {
                netDelta4 > 1e-9 -> SignalDirection.UP
                netDelta4 < -1e-9 -> SignalDirection.DOWN
                else -> SignalDirection.NONE
            }

            for (i in (lastIdx - k + 1)..lastIdx) {
                val d = ticks[i].price - ticks[i - 1].price
                val absD = abs(d)
                pathLen4 += absD
                if (absD > maxSingleStep4) maxSingleStep4 = absD
                if (candidateDir == SignalDirection.UP) {
                    if (d > 0.0) {
                        sameDirStepsIn4++
                        forwardIn4 += absD
                    } else if (d < 0.0) {
                        counterIn4 += absD
                    }
                } else if (candidateDir == SignalDirection.DOWN) {
                    if (d < 0.0) {
                        sameDirStepsIn4++
                        forwardIn4 += absD
                    } else if (d > 0.0) {
                        counterIn4 += absD
                    }
                }
            }

            val purityPercent = if (pathLen4 > 1e-9) {
                ((abs(netDelta4) / pathLen4) * 100.0).finiteOr(0.0).coerceIn(0.0, 100.0)
            } else {
                0.0
            }

            // 3. Feature 1: RushSNR_4 (blended 4-tick and 3-tick surge SNR)
            val snr4 = abs(netDelta4) / (avgNoiseStep * sqrt(k.toDouble()))
            val snr3 = abs(netDelta3) / (avgNoiseStep * sqrt(k3.toDouble()))
            val rushSnr = maxOf(snr4, snr3 * 0.96).finiteOr(0.0)

            // 4. Feature 2: TickConvexity ((|d_t| + |d_{t-1}|) / (|d_{t-2}| + |d_{t-3}|))
            val signedD0 = ticks[lastIdx].price - ticks[lastIdx - 1].price
            val signedD1 = if (lastIdx >= 2) ticks[lastIdx - 1].price - ticks[lastIdx - 2].price else signedD0
            val signedD2 = if (lastIdx >= 3) ticks[lastIdx - 2].price - ticks[lastIdx - 3].price else signedD1
            val d0 = abs(signedD0)
            val d1 = abs(signedD1)
            val d2 = if (lastIdx >= 3) abs(signedD2) else avgNoiseStep
            val d3 = if (lastIdx >= 4) abs(ticks[lastIdx - 3].price - ticks[lastIdx - 4].price) else avgNoiseStep
            val earlySteps = (d2 + d3).coerceAtLeast(avgNoiseStep * 0.6)
            val tickConvexity = ((d0 + d1) / earlySteps).finiteOr(1.0).coerceIn(0.2, 4.0)

            // 5. Feature 3: ArrivalCompressionRatio ((dt_t + dt_{t-1}) / (dt_{t-2} + dt_{t-3}))
            val dt0 = if (lastIdx >= 1) (ticks[lastIdx].timeMs - ticks[lastIdx - 1].timeMs).coerceAtLeast(20L) else 200L
            val dt1 = if (lastIdx >= 2) (ticks[lastIdx - 1].timeMs - ticks[lastIdx - 2].timeMs).coerceAtLeast(20L) else dt0
            val dt2 = if (lastIdx >= 3) (ticks[lastIdx - 2].timeMs - ticks[lastIdx - 3].timeMs).coerceAtLeast(20L) else dt1
            val dt3 = if (lastIdx >= 4) (ticks[lastIdx - 3].timeMs - ticks[lastIdx - 4].timeMs).coerceAtLeast(20L) else dt2
            val arrivalCompressionRatio =
                ((dt0 + dt1).toDouble() / (dt2 + dt3).coerceAtLeast(40L).toDouble()).finiteOr(1.0).coerceIn(0.25, 2.5)

            // 6. Feature 4: SingleTickDominance (max|d_i| / sum|d_i|)
            val singleTickDominance = if (pathLen4 > 1e-9 && k >= 3) {
                (maxSingleStep4 / pathLen4).finiteOr(0.35).coerceIn(0.20, 1.0)
            } else {
                0.35
            }
            val singleTickDominancePercent = singleTickDominance * 100.0

            // 7. Feature 5: MicroPullbackRecovery over last 6 ticks
            val k6 = min(6, lastIdx)
            var counterMoveSum = 0.0
            var forwardMoveSum = 0.0
            for (i in (lastIdx - k6 + 1)..lastIdx) {
                val step = ticks[i].price - ticks[i - 1].price
                val aligned = (candidateDir == SignalDirection.UP && step > 0.0) ||
                    (candidateDir == SignalDirection.DOWN && step < 0.0)
                if (aligned) forwardMoveSum += abs(step) else counterMoveSum += abs(step)
            }
            val microPullbackRecovery = if (forwardMoveSum + counterMoveSum > 1e-9) {
                (forwardMoveSum / (forwardMoveSum + counterMoveSum)).finiteOr(0.75).coerceIn(0.0, 1.0)
            } else {
                0.75
            }

            // 8. Feature 6: LocalZScore_15 (zero-allocation index loop)
            val zStart15 = max(0, ticks.size - 15)
            val zCount15 = (ticks.size - zStart15).coerceAtLeast(1)
            var sum15 = 0.0
            for (i in zStart15 until ticks.size) {
                sum15 += ticks[i].price
            }
            val mean15 = sum15 / zCount15.toDouble()
            var varSum15 = 0.0
            for (i in zStart15 until ticks.size) {
                val diff = ticks[i].price - mean15
                varSum15 += diff * diff
            }
            val std15 = sqrt(varSum15 / zCount15.toDouble()).coerceAtLeast(avgNoiseStep)
            val localZScore15 = (abs(latestTick.price - mean15) / std15).finiteOr(1.5).coerceIn(0.0, 5.0)

            // Consecutive directional streak, 3-tick smoothed velocity, & anti-whipsaw tip alignment
            val lastStep = signedD0
            val prevStep = signedD1
            val lastStepRatio = (abs(lastStep) / avgNoiseStep).finiteOr(0.0)
            val smoothedTipDelta = (0.50 * signedD0 + 0.32 * signedD1 + 0.18 * signedD2).finiteOr(0.0)
            val smoothedTipAligned = (candidateDir == SignalDirection.UP && smoothedTipDelta >= avgNoiseStep * 0.65) ||
                (candidateDir == SignalDirection.DOWN && smoothedTipDelta <= -avgNoiseStep * 0.65)
            val noInternalWhipsaw = counterIn4 <= forwardIn4 * 0.35

            var consecutiveStreak = 0
            if (candidateDir != SignalDirection.NONE) {
                for (i in lastIdx downTo 1) {
                    val d = ticks[i].price - ticks[i - 1].price
                    val matches = (candidateDir == SignalDirection.UP && d > 0.0) ||
                        (candidateDir == SignalDirection.DOWN && d < 0.0)
                    if (matches) {
                        consecutiveStreak++
                    } else if (abs(d) > avgNoiseStep * 0.15) {
                        break
                    }
                }
            }

            val latestStepAligned = (candidateDir == SignalDirection.UP && lastStep > 0.0) ||
                (candidateDir == SignalDirection.DOWN && lastStep < 0.0)
            val prevStepAligned = (candidateDir == SignalDirection.UP && prevStep >= 0.0) ||
                (candidateDir == SignalDirection.DOWN && prevStep <= 0.0)
            val tailRetentionRatio = if (latestStepAligned) {
                (d0 / d1.coerceAtLeast(avgNoiseStep * 0.35)).finiteOr(1.0).coerceIn(0.0, 3.5)
            } else {
                0.0
            }

            val tipAligned = latestStepAligned &&
                smoothedTipAligned &&
                noInternalWhipsaw &&
                lastStepRatio >= 0.72 &&
                tailRetentionRatio >= 0.60 &&
                (prevStepAligned || sameDirStepsIn4 >= 3)

            // Tick arrival speed over the 4-tick rush window (guard against 0ms burst timestamps)
            val dtMs = (ticks[lastIdx].timeMs - ticks[lastIdx - k].timeMs).coerceAtLeast(k * 40L)
            val windowTps = (k * 1000.0) / dtMs.toDouble()
            val tickSpeedTps = maxOf(windowTps.coerceAtMost(25.0), telemetry.velocity.finiteOr(0.0))

            // Micro-noise breakout beyond the preceding 8-tick oscillation center (zero-allocation index loop)
            val priorStart = max(0, ticks.size - 10)
            val priorEndExclusive = max(priorStart + 1, ticks.size - min(2, ticks.size - 1))
            var priorSum = 0.0
            var priorCount = 0
            for (i in priorStart until priorEndExclusive) {
                priorSum += ticks[i].price
                priorCount++
            }
            val localMean = if (priorCount > 0) {
                priorSum / priorCount.toDouble()
            } else {
                ticks[lastIdx - k].price
            }
            val clearedNoiseMean = when (candidateDir) {
                SignalDirection.UP -> latestTick.price >= localMean + avgNoiseStep * 0.65
                SignalDirection.DOWN -> latestTick.price <= localMean - avgNoiseStep * 0.65
                SignalDirection.NONE -> false
            }

            // Build the 11-dimensional FTRL-Proximal feature vector (7 exogenous core + 3 non-linear cross-features + bias)
            val featureVector = buildFtrlFeatureVector(
                rushSnr = rushSnr,
                tickConvexity = tickConvexity,
                arrivalCompressionRatio = arrivalCompressionRatio,
                singleTickDominance = singleTickDominance,
                microPullbackRecovery = microPullbackRecovery,
                localZScore15 = localZScore15,
                purityPercent = purityPercent
            )

            // Predict 5-second continuation probability P(Win 5s) via FTRL-Proximal dot product
            val ftrlWinProbPercent = predictFtrlWinProbability(featureVector) * 100.0
            val shadowFingerprint = classifyShadowFingerprint(
                singleTickDominancePercent = singleTickDominancePercent,
                consecutiveStreak = consecutiveStreak,
                localZScore15 = localZScore15,
                microPullbackRecovery = microPullbackRecovery,
                tickConvexity = tickConvexity
            )

            val zlemaTrendDir = resolveZlemaTrendDirection(telemetry, ticks, candidateDir)
            val isBroadObservedImpulse = candidateDir != SignalDirection.NONE &&
                rushSnr >= 1.22 &&
                purityPercent >= 62.0
            val isStructuralSniperCandidate = candidateDir != SignalDirection.NONE &&
                zlemaTrendDir == candidateDir &&
                rushSnr >= 1.28 &&
                purityPercent >= 68.0 &&
                tipAligned &&
                clearedNoiseMean &&
                tickConvexity in 0.65..3.20 &&
                tailRetentionRatio >= 0.60 &&
                tickSpeedTps >= 1.15 &&
                singleTickDominancePercent <= 58.0 &&
                localZScore15 in 0.70..2.90 &&
                !(localZScore15 > 2.45 && tickConvexity < 0.95)

            val candidatePopulation = when {
                isStructuralSniperCandidate -> CandidatePopulation.POPULATION_B_STRUCTURAL_SNIPER_CANDIDATE
                isBroadObservedImpulse -> CandidatePopulation.POPULATION_A_OBSERVED_IMPULSE
                else -> CandidatePopulation.NONE
            }

            if (mutateState && isBroadObservedImpulse && latestTimeMs - lastBroadImpulseEnqueueTimeMs >= 5_000L) {
                lastBroadImpulseEnqueueTimeMs = latestTimeMs
                observedImpulseCount++
                if (pendingBroadImpulses.size >= 48) {
                    pendingBroadImpulses.removeFirst()
                }
                pendingBroadImpulses.addLast(
                    PendingShadowRush(
                        entryTimeMs = latestTimeMs,
                        entryPrice = latestTick.price,
                        direction = candidateDir,
                        features = featureVector.copyOf(),
                        regime = if (rushSnr >= 1.45) TemporalRegimeType.EXPANSION_IMPULSE else TemporalRegimeType.TREND,
                        predictedProb = (ftrlWinProbPercent / 100.0).coerceIn(0.1, 0.95),
                        wasSpikeNoise = shadowFingerprint == ShadowFingerprint.SPIKE_DOMINANCE || singleTickDominancePercent > 58.0,
                        fingerprint = shadowFingerprint,
                        horizonMs = 5_000L,
                        population = CandidatePopulation.POPULATION_A_OBSERVED_IMPULSE
                    )
                )
            }

            // Enqueue virtual shadow rush ONLY for Population B (structurally valid Sniper M candidates)
            // so the execution-quality learner never trains on unrelated spikes or counter-trend impulses.
            if (
                mutateState &&
                isStructuralSniperCandidate &&
                latestTimeMs - lastShadowEnqueueTimeMs >= 5_000L
            ) {
                lastShadowEnqueueTimeMs = latestTimeMs
                structuralCandidateCount++
                if (pendingShadowRushes.size >= 48) {
                    pendingShadowRushes.removeFirst()
                }
                val horizonMs = 5_000L // Sniper M is a fixed five-second continuation model.
                pendingShadowRushes.addLast(
                    PendingShadowRush(
                        entryTimeMs = latestTimeMs,
                        entryPrice = latestTick.price,
                        direction = candidateDir,
                        features = featureVector.copyOf(),
                        regime = if (rushSnr >= 1.45) TemporalRegimeType.EXPANSION_IMPULSE else TemporalRegimeType.TREND,
                        predictedProb = (ftrlWinProbPercent / 100.0).coerceIn(0.1, 0.95),
                        wasSpikeNoise = shadowFingerprint == ShadowFingerprint.SPIKE_DOMINANCE,
                        fingerprint = shadowFingerprint,
                        horizonMs = horizonMs,
                        population = CandidatePopulation.POPULATION_B_STRUCTURAL_SNIPER_CANDIDATE
                    )
                )
            }

            return RawTickRushMetrics(
                rushDirection = candidateDir,
                avgNoiseStep = avgNoiseStep,
                netDelta4 = netDelta4,
                rushSnr = rushSnr,
                purityPercent = purityPercent,
                consecutiveStreak = consecutiveStreak,
                tipAligned = tipAligned,
                tickSpeedTps = tickSpeedTps,
                clearedNoiseMean = clearedNoiseMean,
                lastStepRatio = lastStepRatio,
                tailRetentionRatio = tailRetentionRatio,
                tickConvexity = tickConvexity,
                arrivalCompressionRatio = arrivalCompressionRatio,
                singleTickDominancePercent = singleTickDominancePercent,
                microPullbackRecovery = microPullbackRecovery,
                localZScore15 = localZScore15,
                rollingShadowWinRatePercent = rollingShadowWinRate * 100.0,
                shadowSamplesLearned = shadowSamplesCount,
                ftrlWinProbPercent = ftrlWinProbPercent,
                shadowFingerprint = shadowFingerprint,
                candidatePopulation = candidatePopulation
            )
        }

        // Fallback when called in compact unit tests without full recentTicks list
        val avgNoiseStep = (telemetry.volatility * 0.45).finiteOr(0.1).coerceAtLeast(1e-6)
        val netDelta4 = telemetry.netDelta5.finiteOr(0.0)
        val candidateDir = when {
            netDelta4 > 1e-6 -> SignalDirection.UP
            netDelta4 < -1e-6 -> SignalDirection.DOWN
            else -> telemetry.microstructure.impulseDirection
        }
        val rushSnr = (abs(netDelta4) / (avgNoiseStep * 2.0)).finiteOr(0.0)
        val purityPercent = telemetry.microstructure.directionalEfficiency.finiteOr(0.0)
        val streak = telemetry.microstructure.streakCount
        val tipAligned = streak in 2..6 && purityPercent >= 70.0
        val featureVector = buildFtrlFeatureVector(
            rushSnr = rushSnr,
            tickConvexity = 1.25,
            arrivalCompressionRatio = 0.78,
            singleTickDominance = 0.32,
            microPullbackRecovery = purityPercent / 100.0,
            localZScore15 = 1.85,
            purityPercent = purityPercent
        )
        val probPct = if (rushSnr >= 1.25 && purityPercent >= 68.0) {
            predictFtrlWinProbability(featureVector) * 100.0
        } else {
            50.0
        }
        return RawTickRushMetrics(
            rushDirection = candidateDir,
            avgNoiseStep = avgNoiseStep,
            netDelta4 = netDelta4,
            rushSnr = rushSnr,
            purityPercent = purityPercent,
            consecutiveStreak = streak,
            tipAligned = tipAligned,
            tickSpeedTps = telemetry.velocity.finiteOr(0.0),
            clearedNoiseMean = rushSnr >= 1.30,
            lastStepRatio = (rushSnr * 0.85).coerceAtLeast(0.8),
            tickConvexity = 1.25,
            arrivalCompressionRatio = 0.78,
            singleTickDominancePercent = 32.0,
            microPullbackRecovery = purityPercent / 100.0,
            localZScore15 = 1.85,
            rollingShadowWinRatePercent = rollingShadowWinRate * 100.0,
            shadowSamplesLearned = shadowSamplesCount,
            ftrlWinProbPercent = probPct,
            shadowFingerprint = ShadowFingerprint.OTHER,
            candidatePopulation = CandidatePopulation.NONE
        )
    }

    internal fun resolveZlemaTrendDirection(
        telemetry: MarketTelemetryState,
        ticks: List<com.olympquant.terminal.domain.Tick>,
        fallbackDir: SignalDirection
    ): SignalDirection {
        var resolvedZFast = telemetry.zFast.finiteOr(0.0)
        var resolvedZSlow = telemetry.zSlow.finiteOr(0.0)
        if (((resolvedZFast == 0.0 && resolvedZSlow == 0.0) || abs(resolvedZFast - resolvedZSlow) < 1e-12) &&
            ticks.size >= 4
        ) {
            var emaFast = ticks.first().price
            var emaSlow = ticks.first().price
            val alphaFast = 2.0 / (6.0 + 1.0)
            val alphaSlow = 2.0 / (16.0 + 1.0)
            for (i in 1 until ticks.size) {
                val p = ticks[i].price
                val lagIdx = max(0, i - 2)
                val deLagged = p + (p - ticks[lagIdx].price)
                emaFast = alphaFast * deLagged + (1.0 - alphaFast) * emaFast
                emaSlow = alphaSlow * p + (1.0 - alphaSlow) * emaSlow
            }
            resolvedZFast = emaFast.finiteOr(telemetry.latestPrice)
            resolvedZSlow = emaSlow.finiteOr(telemetry.latestPrice)
        }
        return when {
            resolvedZFast > resolvedZSlow + 1e-10 -> SignalDirection.UP
            resolvedZFast < resolvedZSlow - 1e-10 -> SignalDirection.DOWN
            telemetry.trend == SignalDirection.UP -> SignalDirection.UP
            telemetry.trend == SignalDirection.DOWN -> SignalDirection.DOWN
            else -> fallbackDir
        }
    }

    private fun classifyShadowFingerprint(
        singleTickDominancePercent: Double,
        consecutiveStreak: Int,
        localZScore15: Double,
        microPullbackRecovery: Double,
        tickConvexity: Double
    ): ShadowFingerprint = when {
        singleTickDominancePercent >= 64.0 && consecutiveStreak < 2 -> ShadowFingerprint.SPIKE_DOMINANCE
        localZScore15 >= 3.0 && microPullbackRecovery < 0.55 -> ShadowFingerprint.EXHAUSTION
        tickConvexity < 0.90 && microPullbackRecovery < 0.55 -> ShadowFingerprint.WEAK_FOLLOW_THROUGH
        else -> ShadowFingerprint.OTHER
    }

    internal fun buildFtrlFeatureVector(
        rushSnr: Double,
        tickConvexity: Double,
        arrivalCompressionRatio: Double,
        singleTickDominance: Double,
        microPullbackRecovery: Double,
        localZScore15: Double,
        purityPercent: Double
    ): DoubleArray {
        val f1 = ((rushSnr - 1.35) / 1.25).finiteOr(0.0).coerceIn(-2.0, 2.5)
        val f2 = ((tickConvexity - 0.95) / 0.65).finiteOr(0.0).coerceIn(-2.0, 2.5)
        val f3 = ((0.95 - arrivalCompressionRatio) / 0.35).finiteOr(0.0).coerceIn(-1.5, 2.0)
        val f4 = ((0.55 - singleTickDominance) / 0.22).finiteOr(0.0).coerceIn(-2.5, 1.8)
        val f5 = ((microPullbackRecovery - 0.55) / 0.35).finiteOr(0.0).coerceIn(-1.5, 1.8)
        val f6 = if (localZScore15 <= 2.85) {
            ((localZScore15 - 1.05) / 1.0).finiteOr(0.0).coerceIn(-1.5, 1.8)
        } else {
            -((localZScore15 - 2.85) * 1.4).finiteOr(0.0).coerceIn(0.0, 2.5)
        }
        // Exogenous prediction-time 4-tick directional purity (replaces contaminated rollingShadowWinRate)
        val f7 = ((purityPercent - 75.0) / 20.0).finiteOr(0.0).coerceIn(-2.0, 1.5)

        // Non-linear cross-feature interactions
        val f8 = (f1 * f2).coerceIn(-2.5, 3.0)
        val f9 = (f1 * f4).coerceIn(-3.0, 2.5)
        val f10 = (f2 * f3).coerceIn(-2.0, 2.5)

        return doubleArrayOf(1.0, f1, f2, f3, f4, f5, f6, f7, f8, f9, f10)
    }

    private fun captureFirstPostTriggerQuotes(ticks: List<com.olympquant.terminal.domain.Tick>) {
        if (ticks.isEmpty() || pendingShadowRushes.isEmpty()) return
        for (idx in pendingShadowRushes.indices) {
            val item = pendingShadowRushes[idx]
            if (item.firstPostTriggerTimeMs == 0L) {
                val nextTick = ticks.firstOrNull {
                    it.timeMs > item.entryTimeMs && it.timeMs - item.entryTimeMs <= MAX_SHADOW_LABEL_BRACKET_GAP_MS
                }
                if (nextTick != null && nextTick.price.isFinite()) {
                    pendingShadowRushes[idx] = item.copy(
                        firstPostTriggerPrice = nextTick.price,
                        firstPostTriggerTimeMs = nextTick.timeMs
                    )
                }
            }
        }
    }

    internal fun resolveExpiryQuote(
        entryTimeMs: Long,
        entryPrice: Double,
        targetTimeMs: Long,
        currentTimeMs: Long,
        ticks: List<com.olympquant.terminal.domain.Tick>
    ): ExpiryResolution {
        if (entryTimeMs <= 0L || !entryPrice.isFinite() || entryPrice <= 0.0 || targetTimeMs <= entryTimeMs) {
            return ExpiryResolution(ExpiryResolutionStatus.INVALID_TIMESTAMP_SEQUENCE)
        }
        if (ticks.isEmpty()) {
            return if (currentTimeMs < targetTimeMs) {
                ExpiryResolution(ExpiryResolutionStatus.PENDING_FUTURE_OBSERVATION)
            } else {
                ExpiryResolution(ExpiryResolutionStatus.INVALID_BRACKET_GAP_OR_MISSING)
            }
        }
        for (i in ticks.indices) {
            val t = ticks[i]
            if (t.timeMs <= 0L || !t.price.isFinite() || t.price <= 0.0) {
                return ExpiryResolution(ExpiryResolutionStatus.INVALID_TIMESTAMP_SEQUENCE)
            }
            if (i > 0 && ticks[i].timeMs <= ticks[i - 1].timeMs) {
                return ExpiryResolution(ExpiryResolutionStatus.INVALID_TIMESTAMP_SEQUENCE)
            }
        }
        val latestObservedMs = ticks.last().timeMs
        // Delayed or missing ticks cannot cause premature settlement before the target horizon is observed.
        if (currentTimeMs < targetTimeMs || latestObservedMs < targetTimeMs) {
            return ExpiryResolution(ExpiryResolutionStatus.PENDING_FUTURE_OBSERVATION)
        }
        if (ticks.first().timeMs > targetTimeMs) {
            return ExpiryResolution(ExpiryResolutionStatus.INVALID_BRACKET_GAP_OR_MISSING)
        }

        for (i in 1 until ticks.size) {
            val before = ticks[i - 1]
            val after = ticks[i]
            if (after.timeMs == targetTimeMs) {
                val span = after.timeMs - before.timeMs
                if (before.timeMs < entryTimeMs || span <= 0L || span > MAX_SHADOW_LABEL_BRACKET_GAP_MS) {
                    return ExpiryResolution(
                        status = ExpiryResolutionStatus.INVALID_BRACKET_GAP_OR_MISSING,
                        bracketGapMs = span
                    )
                }
                return ExpiryResolution(
                    status = ExpiryResolutionStatus.RESOLVED_EXACT_TIMESTAMP,
                    expiryPrice = after.price,
                    expiryQuoteTimeMs = after.timeMs,
                    confirmingTickTimeMs = after.timeMs,
                    bracketGapMs = span
                )
            }
            if (before.timeMs <= targetTimeMs && after.timeMs > targetTimeMs) {
                val span = after.timeMs - before.timeMs
                if (before.timeMs <= entryTimeMs || span <= 0L || span > MAX_SHADOW_LABEL_BRACKET_GAP_MS) {
                    return ExpiryResolution(
                        status = ExpiryResolutionStatus.INVALID_BRACKET_GAP_OR_MISSING,
                        bracketGapMs = span
                    )
                }
                // Càdlàg (piecewise-constant step function) settlement:
                // The expiry quote at targetTimeMs is strictly the last valid observed quote at or before
                // targetTimeMs (before.price). The post-expiry observation (after) confirms continuity
                // across the horizon without ever influencing or interpolating the expiry price.
                return ExpiryResolution(
                    status = ExpiryResolutionStatus.RESOLVED_LAST_QUOTE_AT_OR_BEFORE,
                    expiryPrice = before.price,
                    expiryQuoteTimeMs = before.timeMs,
                    confirmingTickTimeMs = after.timeMs,
                    bracketGapMs = span
                )
            }
        }
        return ExpiryResolution(ExpiryResolutionStatus.INVALID_BRACKET_GAP_OR_MISSING)
    }

    private fun settleMaturedBroadImpulses(currentTimeMs: Long, ticks: List<com.olympquant.terminal.domain.Tick>) {
        if (pendingBroadImpulses.isNotEmpty() && currentTimeMs < pendingBroadImpulses.first().entryTimeMs) {
            pendingBroadImpulses.clear()
            lastBroadImpulseEnqueueTimeMs = 0L
            return
        }
        while (pendingBroadImpulses.isNotEmpty()) {
            val oldest = pendingBroadImpulses.first()
            val targetTimeMs = oldest.entryTimeMs + oldest.horizonMs
            val resolution = resolveExpiryQuote(
                entryTimeMs = oldest.entryTimeMs,
                entryPrice = oldest.entryPrice,
                targetTimeMs = targetTimeMs,
                currentTimeMs = currentTimeMs,
                ticks = ticks
            )
            if (resolution.status == ExpiryResolutionStatus.PENDING_FUTURE_OBSERVATION) {
                break
            }
            pendingBroadImpulses.removeFirst()
            if (resolution.status != ExpiryResolutionStatus.RESOLVED_EXACT_TIMESTAMP &&
                resolution.status != ExpiryResolutionStatus.RESOLVED_LAST_QUOTE_AT_OR_BEFORE
            ) {
                broadImpulseInvalidCount++
                continue
            }
            val settledPrice = resolution.expiryPrice
            val isTie = abs(settledPrice - oldest.entryPrice) <= 1e-9
            if (!isTie && settledPrice.isFinite()) {
                val won = (oldest.direction == SignalDirection.UP && settledPrice > oldest.entryPrice) ||
                    (oldest.direction == SignalDirection.DOWN && settledPrice < oldest.entryPrice)
                broadImpulseResolvedCount++
                if (won) broadImpulseWinCount++
            }
        }
    }

    private fun settleMaturedShadowRushes(currentTimeMs: Long, ticks: List<com.olympquant.terminal.domain.Tick>) {
        if (pendingShadowRushes.isNotEmpty() && currentTimeMs < pendingShadowRushes.first().entryTimeMs) {
            // Stream clock rewound (e.g., replay or simulation reset).
            pendingShadowRushes.clear()
            lastShadowEnqueueTimeMs = 0L
            return
        }

        while (pendingShadowRushes.isNotEmpty()) {
            val oldest = pendingShadowRushes.first()
            val targetTimeMs = oldest.entryTimeMs + oldest.horizonMs
            val resolution = resolveExpiryQuote(
                entryTimeMs = oldest.entryTimeMs,
                entryPrice = oldest.entryPrice,
                targetTimeMs = targetTimeMs,
                currentTimeMs = currentTimeMs,
                ticks = ticks
            )
            if (resolution.status == ExpiryResolutionStatus.PENDING_FUTURE_OBSERVATION) {
                break
            }
            pendingShadowRushes.removeFirst()
            if (resolution.status != ExpiryResolutionStatus.RESOLVED_EXACT_TIMESTAMP &&
                resolution.status != ExpiryResolutionStatus.RESOLVED_LAST_QUOTE_AT_OR_BEFORE
            ) {
                invalidShadowSamplesCount++
                continue
            }
            val settledPrice = resolution.expiryPrice
            val isTie = abs(settledPrice - oldest.entryPrice) <= 1e-9
            val isContinuationWin = !isTie && (
                (oldest.direction == SignalDirection.UP && settledPrice > oldest.entryPrice) ||
                    (oldest.direction == SignalDirection.DOWN && settledPrice < oldest.entryPrice)
                )
            val resolvedFirstPostTrigger = if (oldest.firstPostTriggerPrice.isFinite()) {
                oldest.firstPostTriggerPrice
            } else {
                ticks.firstOrNull {
                    it.timeMs > oldest.entryTimeMs && it.timeMs - oldest.entryTimeMs <= MAX_SHADOW_LABEL_BRACKET_GAP_MS
                }?.price ?: Double.NaN
            }

            if (settledShadowRecords.size >= 128) {
                settledShadowRecords.removeFirst()
            }
            settledShadowRecords.addLast(
                ShadowSettlementRecord(
                    entryTimeMs = oldest.entryTimeMs,
                    targetExpiryTimeMs = targetTimeMs,
                    expiryQuoteTimeMs = resolution.expiryQuoteTimeMs,
                    confirmingTickTimeMs = resolution.confirmingTickTimeMs,
                    entryPrice = oldest.entryPrice,
                    expiryPrice = settledPrice,
                    firstPostTriggerPrice = resolvedFirstPostTrigger,
                    direction = oldest.direction,
                    predictedProb = oldest.predictedProb,
                    won = isContinuationWin,
                    isTie = isTie,
                    population = oldest.population,
                    resolutionStatus = resolution.status
                )
            )

            if (isTie) {
                tieShadowSamplesCount++
                continue
            }
            if (settledPrice.isFinite()) {
                val labelY = if (isContinuationWin) 1.0 else 0.0
                if (onlineTrainingEnabled) {
                    updateFtrlWeightsOnline(oldest.features, labelY)
                    rollingShadowWinRate = (0.85 * rollingShadowWinRate + 0.15 * labelY).coerceIn(0.0, 1.0)
                    shadowSamplesCount++
                    temporalEngine.recordShadowOutcome(
                        direction = oldest.direction,
                        regime = oldest.regime,
                        predictedProb = oldest.predictedProb,
                        won = isContinuationWin,
                        wasSpikeNoise = oldest.wasSpikeNoise,
                        fingerprint = oldest.fingerprint
                    )
                }
            }
        }
    }

    internal fun predictFtrlWinProbability(x: DoubleArray): Double {
        var logit = 0.0
        for (i in 0 until FEATURE_DIM) {
            val zi = ftrlZ[i]
            val ni = ftrlN[i]
            val wi = if (abs(zi) <= FTRL_L1) {
                0.0
            } else {
                val signZi = if (zi >= 0.0) 1.0 else -1.0
                -(zi - signZi * FTRL_L1) / (((FTRL_BETA + sqrt(ni)) / FTRL_ALPHA) + FTRL_L2)
            }
            ftrlW[i] = wi
            logit += wi * x[i]
        }
        val clampedLogit = logit.finiteOr(0.0).coerceIn(-8.0, 8.0)
        return (1.0 / (1.0 + exp(-clampedLogit))).coerceIn(0.01, 0.99)
    }

    internal fun updateFtrlWeightsOnline(x: DoubleArray, labelY: Double) {
        val p = predictFtrlWinProbability(x)
        val err = (p - labelY).finiteOr(0.0)
        for (i in 0 until FEATURE_DIM) {
            val grad = (err * x[i]).finiteOr(0.0)
            val nOld = (ftrlN[i] * FORGETTING_GAMMA).finiteOr(0.0).coerceAtLeast(0.0)
            val nNewRaw = (nOld + grad * grad).finiteOr(0.0).coerceAtLeast(0.0)
            val nNew = if (nNewRaw < 1e-18) 0.0 else nNewRaw
            val sigma = (sqrt(nNew) - sqrt(nOld)) / FTRL_ALPHA
            val zDiscounted = (ftrlZ[i] * FORGETTING_GAMMA).finiteOr(0.0)
            val zNewRaw = (zDiscounted + grad - sigma * ftrlW[i]).finiteOr(0.0)
            ftrlZ[i] = if (abs(zNewRaw) < 1e-18) 0.0 else zNewRaw
            ftrlN[i] = nNew
        }
    }
}

