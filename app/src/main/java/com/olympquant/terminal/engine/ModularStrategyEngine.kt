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
import com.olympquant.terminal.domain.Time5EvaluationRecord
import com.olympquant.terminal.domain.Time5Metrics
import com.olympquant.terminal.domain.Time5StrategyParams
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

    // Centralized TIME-5 Strategy Parameters
    var params: Time5StrategyParams = Time5StrategyParams()

    // TIME-5 Time-Aware Exponential Smoothing State (tau = 800ms)
    private var lastSmoothedVelocity: Double = 0.0
    private var lastComputedAcceleration: Double = 0.0
    private var lastVelocityTimestampMs: Long = 0L
    private var hasVelocityHistory: Boolean = false

    // TIME-5 Evaluation Record and Signal Lifecycle History
    private val evaluationRecords = ArrayDeque<Time5EvaluationRecord>(256)

    fun getEvaluationRecords(): List<Time5EvaluationRecord> = synchronized(this) {
        evaluationRecords.toList()
    }

    fun getLatestEvaluationRecord(): Time5EvaluationRecord? = synchronized(this) {
        evaluationRecords.lastOrNull()
    }

    @Synchronized
    fun recordExecutionAttempt(timestampMs: Long, pair: String, price: Double, direction: SignalDirection) {
        val last = evaluationRecords.lastOrNull()
        if (last != null && (last.assetIdentifier == pair || last.assetIdentifier.isEmpty())) {
            evaluationRecords.removeLast()
            evaluationRecords.addLast(
                last.copy(
                    executionAttempted = true,
                    lifecycleEvent = "EXECUTION_ATTEMPT"
                )
            )
        }
    }

    @Synchronized
    fun recordExecutionConfirmed(brokerTradeId: Long, openPrice: Double) {
        val last = evaluationRecords.lastOrNull()
        if (last != null) {
            evaluationRecords.removeLast()
            evaluationRecords.addLast(
                last.copy(
                    platformConfirmed = true,
                    actualExecutionPrice = if (openPrice > 0.0) openPrice else last.currentPrice,
                    lifecycleEvent = "EXECUTION_CONFIRMED"
                )
            )
        }
    }

    @Synchronized
    fun recordExecutionFailed(reason: String) {
        val last = evaluationRecords.lastOrNull()
        if (last != null) {
            evaluationRecords.removeLast()
            evaluationRecords.addLast(
                last.copy(
                    rejectionReason = reason,
                    lifecycleEvent = "EXECUTION_FAILED"
                )
            )
        }
    }

    @Synchronized
    fun recordTradeSettled(brokerTradeId: Long, expiryPrice: Double, outcome: String) {
        val last = evaluationRecords.lastOrNull()
        if (last != null) {
            evaluationRecords.removeLast()
            evaluationRecords.addLast(
                last.copy(
                    expiryPrice = expiryPrice,
                    realizedOutcome = outcome,
                    lifecycleEvent = if (outcome.isNotBlank()) "TRADE_RESULT" else "TRADE_EXPIRED"
                )
            )
        }
    }

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
        lastSmoothedVelocity = 0.0
        lastComputedAcceleration = 0.0
        lastVelocityTimestampMs = 0L
        hasVelocityHistory = false
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
        lastSmoothedVelocity = 0.0
        lastComputedAcceleration = 0.0
        lastVelocityTimestampMs = 0L
        hasVelocityHistory = false
        evaluationRecords.clear()
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
        val ticks = telemetry.recentTicks
        val nowMs = System.currentTimeMillis()

        // 1. Tick Count & Structural Observation Integrity
        if (ticks.size < 4) {
            val record = Time5EvaluationRecord(
                timestampMs = telemetry.latestTimeMs.takeIf { it > 0L } ?: nowMs,
                assetIdentifier = telemetry.pair,
                currentPrice = telemetry.latestPrice,
                decision = "NO_TRADE",
                rejectionReason = "Insufficient observations (${ticks.size}/4 ticks required)",
                lifecycleEvent = "SIGNAL_REJECTED"
            )
            evaluationRecords.addLast(record)
            if (evaluationRecords.size > 256) evaluationRecords.removeFirst()

            return StrategyEvaluation(
                strategyId = strategy.id,
                strategyName = HardLockedMandate.STRATEGY_NAME,
                rawSignal = SignalDirection.NONE,
                finalSignal = SignalDirection.NONE,
                confidenceScore = 0.0,
                minConfidenceRequired = HardLockedMandate.MIN_CONFIDENCE_REQUIRED,
                triggerReason = "TIME-5 [NO TRADE]: Insufficient observations (${ticks.size}/4 ticks)",
                filterDiagnostics = listOf(
                    FilterDiagnostic(
                        filterType = FilterType.TICK_SPEED_AND_FRESHNESS,
                        passed = false,
                        actualValueFormatted = "${ticks.size}/4 Ticks",
                        thresholdFormatted = "≥ 4 Ticks",
                        reason = "TIME-5 requires minimum 4 recent price observations for 3-interval displacement & velocity",
                        gateTitle = "1. Price Observations"
                    ),
                    FilterDiagnostic(
                        filterType = FilterType.FTRL_ML_META_LABEL_GATE,
                        passed = telemetry.contextBlockReasons.isEmpty(),
                        actualValueFormatted = if (telemetry.contextBlockReasons.isEmpty()) "CLEAN" else telemetry.contextBlockReasons.joinToString(", "),
                        thresholdFormatted = "Zero Context Blocks",
                        reason = if (telemetry.contextBlockReasons.isEmpty()) "Context block check passed" else "Blocked by context: ${telemetry.contextBlockReasons.joinToString(", ")}",
                        gateTitle = "Context & Structural Guard"
                    )
                ),
                contextBlockReasons = listOf("Insufficient ticks"),
                regimeAdjustmentNote = "TIME-5 Balanced Momentum • Waiting for tick stream",
                readyToExecute = false,
                temporalTelemetry = temporalSnapshot,
                time5Metrics = Time5Metrics(
                    decision = SignalDirection.NONE,
                    rejectionReason = "Insufficient observations"
                )
            )
        }

        val lastIdx = ticks.lastIndex
        val t0 = ticks[lastIdx - 3]
        val t1 = ticks[lastIdx - 2]
        val t2 = ticks[lastIdx - 1]
        val t3 = ticks[lastIdx]

        val currentPrice = t3.price
        val currentTickTimeMs = if (t3.timeMs > 0L) t3.timeMs else nowMs

        // 2. Monotonic Timestamp & Stale Data Check
        val isMonotonic = (t3.timeMs >= t2.timeMs) && (t2.timeMs >= t1.timeMs) && (t1.timeMs >= t0.timeMs)
        val streamRef = if (telemetry.latestTimeMs > 0L) telemetry.latestTimeMs else currentTickTimeMs
        val tickAgeMs = if (abs(nowMs - currentTickTimeMs) < 300_000L) {
            (nowMs - currentTickTimeMs).coerceAtLeast(0L)
        } else {
            abs(streamRef - currentTickTimeMs).coerceAtLeast(0L)
        }
        val isFresh = isMonotonic && (tickAgeMs <= params.maxTickAgeMs) && (wsLatencyMs <= params.maxLatencyMs)
        val isPriceValid = currentPrice > 0.0 && currentPrice.isFinite()

        // 3. Mathematical Component A: Short-Term Price Displacement
        // M = current_price - price_3_ticks_ago
        val M = currentPrice - t0.price

        // 4. Mathematical Component B: Short-Term Velocity
        // V = (current_price - price_2_ticks_ago) / elapsed_seconds
        val velocityDtMs = (currentTickTimeMs - t1.timeMs).coerceAtLeast(1L)
        val velocityDtSec = max(0.001, velocityDtMs / 1000.0)
        val V = (currentPrice - t1.price) / velocityDtSec

        // 5. Mathematical Component C: Acceleration via Time-Aware Exponential Smoothing
        // Tau = 800 ms; idempotent when evaluated repeatedly on the same tick timestamp
        val smoothedVelocity: Double
        val A: Double
        if (hasVelocityHistory && currentTickTimeMs == lastVelocityTimestampMs) {
            smoothedVelocity = lastSmoothedVelocity
            A = lastComputedAcceleration
        } else {
            val stepDtMs = if (lastVelocityTimestampMs > 0L) (currentTickTimeMs - lastVelocityTimestampMs).coerceAtLeast(1L) else 250L
            val stepDtSec = max(0.001, stepDtMs / 1000.0)
            val tauSec = params.smoothingTauMs / 1000.0
            val alpha = (1.0 - exp(-stepDtSec / tauSec)).coerceIn(0.01, 1.0)
            smoothedVelocity = if (hasVelocityHistory) {
                alpha * V + (1.0 - alpha) * lastSmoothedVelocity
            } else {
                V
            }
            A = if (hasVelocityHistory) (smoothedVelocity - lastSmoothedVelocity) else 0.0
            lastSmoothedVelocity = smoothedVelocity
            lastComputedAcceleration = A
            lastVelocityTimestampMs = currentTickTimeMs
            hasVelocityHistory = true
        }

        // 6. Mathematical Component D: Directional Efficiency
        // DE = abs(net_displacement) / (sum(abs(individual_price_changes)) + epsilon)
        val dp1 = t1.price - t0.price
        val dp2 = t2.price - t1.price
        val dp3 = t3.price - t2.price
        val absDisplacement = abs(M)
        val sumAbsSteps = abs(dp1) + abs(dp2) + abs(dp3)
        val epsilon = 1e-9
        val DE = (absDisplacement / (sumAbsSteps + epsilon)).coerceIn(0.0, 1.0)

        // 7. Rolling Volatility Estimate & Normalized Signal Strength
        // Compute average absolute tick change over available recent ticks (up to 25 ticks)
        val recentSteps = ticks.takeLast(25).zipWithNext { a, b -> abs(b.price - a.price) }
        val rollingVol = if (recentSteps.isNotEmpty()) {
            recentSteps.average().coerceAtLeast(1e-7)
        } else {
            1e-7
        }
        val normalizedMomentum = absDisplacement / rollingVol

        // 8. Opposing Impulse Detection on Latest Observation
        // For UP (M > 0): latest step dp3 must not show a clear opposing impulse
        val upOpposingImpulse = dp3 < 0 && (abs(dp3) > 0.40 * absDisplacement || abs(dp3) > 1.8 * rollingVol || (dp3 < 0 && dp2 < 0))
        // For DOWN (M < 0): latest step dp3 must not show a clear opposing impulse
        val downOpposingImpulse = dp3 > 0 && (abs(dp3) > 0.40 * absDisplacement || abs(dp3) > 1.8 * rollingVol || (dp3 > 0 && dp2 > 0))

        // 9. Balanced Execution Evaluation
        val maxStep = maxOf(abs(dp1), abs(dp2), abs(dp3))
        val isSingleTickSpike = (sumAbsSteps > 0.0) && ((maxStep / (sumAbsSteps + 1e-9)) > 0.65) && (maxStep > 2.0 * rollingVol)
        val tipDisplacement = abs(dp2) + abs(dp3)
        val isExhaustedDyingTail = (abs(dp1) > 2.0 * rollingVol) && (tipDisplacement < 0.35 * abs(dp1))
        val isAnomaly = isSingleTickSpike || isExhaustedDyingTail

        val dePassed = DE >= params.minDirectionalEfficiency
        val momentumPassed = normalizedMomentum >= params.minNormalizedMomentum
        val contextPassed = telemetry.contextBlockReasons.isEmpty()
        val feedPassed = isFresh && isPriceValid && !telemetry.assetLocked && telemetry.wsSynchronized && (telemetry.assetPayoutPercent >= minPayoutPercent) && (telemetry.hardStopReason == null) && contextPassed

        val isUpValid = (M > 0.0) && (V > 0.0) && dePassed && momentumPassed && !upOpposingImpulse && feedPassed && !isAnomaly
        val isDownValid = (M < 0.0) && (V < 0.0) && dePassed && momentumPassed && !downOpposingImpulse && feedPassed && !isAnomaly

        val decision = when {
            isUpValid -> SignalDirection.UP
            isDownValid -> SignalDirection.DOWN
            else -> SignalDirection.NONE
        }

        val opposingImpulse = if (M > 0.0) upOpposingImpulse else if (M < 0.0) downOpposingImpulse else false

        val trendContradicts = (decision == SignalDirection.UP && telemetry.zFast > 0.0 && telemetry.zSlow > 0.0 && telemetry.zFast < telemetry.zSlow - 1e-4) ||
            (decision == SignalDirection.DOWN && telemetry.zFast > 0.0 && telemetry.zSlow > 0.0 && telemetry.zFast > telemetry.zSlow + 1e-4)

        val rejectionReason: String? = when {
            telemetry.hardStopReason != null -> "Hard stop engaged: ${telemetry.hardStopReason}"
            !contextPassed -> "Blocked by context: ${telemetry.contextBlockReasons.first()}"
            !telemetry.wsSynchronized -> "WebSocket stream not synchronized / reconnecting"
            isSingleTickSpike -> "Single-tick freak spike anomaly (single step > 65% of movement)"
            isExhaustedDyingTail -> "Late impulse exhaustion (dying tail)"
            !isPriceValid -> "Invalid price quote: $currentPrice"
            !isMonotonic -> "Market data out of order (timestamps non-monotonic)"
            !isFresh -> "Feed stale (Tick age: ${tickAgeMs}ms, RTT: ${wsLatencyMs}ms)"
            telemetry.assetLocked -> "Asset locked / suspended"
            telemetry.assetPayoutPercent < minPayoutPercent -> "Payout too low: ${telemetry.assetPayoutPercent}% < $minPayoutPercent%"
            !momentumPassed -> "Momentum too weak relative to volatility (${"%.2f".format(normalizedMomentum)}σ < ${"%.2f".format(params.minNormalizedMomentum)}σ)"
            !dePassed -> "Directional efficiency below threshold (${"%.2f".format(DE)} < ${"%.2f".format(params.minDirectionalEfficiency)})"
            opposingImpulse -> "Clear opposing counter-impulse on latest observation"
            M > 0.0 && V <= 0.0 -> "Velocity contradicts displacement (M>0, V<=0)"
            M < 0.0 && V >= 0.0 -> "Velocity opposes displacement (M<0, V>=0)"
            M == 0.0 -> "Zero price displacement (flat market)"
            trendContradicts -> "Recent smoothed movement contradicts proposed direction"
            else -> null
        }

        // 10. Confidence Score (Scaled from normalized momentum, directional efficiency, and acceleration bonus)
        val baseConfidence = 50.0 +
            min(35.0, (normalizedMomentum - params.minNormalizedMomentum).coerceAtLeast(0.0) * 15.0) +
            min(15.0, (DE - params.minDirectionalEfficiency).coerceAtLeast(0.0) * 30.0)
        val accelAligned = (decision == SignalDirection.UP && A > 0.0) || (decision == SignalDirection.DOWN && A < 0.0)
        val accelBonus = if (accelAligned) 5.0 else 0.0
        val effectiveConfidence = if (decision != SignalDirection.NONE) {
            (baseConfidence + accelBonus).coerceIn(0.0, 100.0)
        } else {
            0.0
        }

        val readyToExecute = (decision != SignalDirection.NONE) && !trendContradicts && (effectiveConfidence >= HardLockedMandate.MIN_CONFIDENCE_REQUIRED)

        // 11. TIME-5 Filter Diagnostics for Telemetry & Dashboard
        val diag1 = FilterDiagnostic(
            filterType = FilterType.TICK_RUSH_SNR_GATE,
            passed = (decision == SignalDirection.UP && M > 0.0) || (decision == SignalDirection.DOWN && M < 0.0) || (decision == SignalDirection.NONE && absDisplacement > 0.0),
            actualValueFormatted = "M = ${"%+.5f".format(M)}",
            thresholdFormatted = if (M > 0.0) "> 0.0 (UP)" else if (M < 0.0) "< 0.0 (DOWN)" else "Non-zero",
            reason = "A. Price Displacement over 3 ticks: M = current_price - price_3_ticks_ago",
            gateTitle = "A. Displacement (M)"
        )
        val diag2 = FilterDiagnostic(
            filterType = FilterType.TICK_SPEED_AND_FRESHNESS,
            passed = (decision == SignalDirection.UP && V > 0.0) || (decision == SignalDirection.DOWN && V < 0.0),
            actualValueFormatted = "V = ${"%+.4f".format(V)}/s",
            thresholdFormatted = if (M > 0.0) "> 0.0" else if (M < 0.0) "< 0.0" else "Aligned",
            reason = "B. Velocity over 2 ticks with actual timestamp Δt: V = (current_price - price_2_ticks_ago) / elapsed_seconds",
            gateTitle = "B. Velocity (V)"
        )
        val diag3 = FilterDiagnostic(
            filterType = FilterType.TICK_DIRECTIONAL_PURITY,
            passed = dePassed,
            actualValueFormatted = "DE = ${"%.2f".format(DE)}",
            thresholdFormatted = "≥ ${"%.2f".format(params.minDirectionalEfficiency)}",
            reason = "D. Directional Efficiency: DE = abs(net_displacement) / (sum(abs(individual_changes)) + ε)",
            gateTitle = "D. Directional Efficiency (DE)"
        )
        val diag4 = FilterDiagnostic(
            filterType = FilterType.ANTI_SPIKE_DOMINANCE_GATE,
            passed = momentumPassed,
            actualValueFormatted = "${"%.2f".format(normalizedMomentum)}σ (σ=${"%.5f".format(rollingVol)})",
            thresholdFormatted = "≥ ${"%.2f".format(params.minNormalizedMomentum)}σ",
            reason = "Volatility-Normalized Momentum: |M| / rolling_volatility across synthetic-asset scales",
            gateTitle = "Strength (Normalized M)"
        )
        val diag5 = FilterDiagnostic(
            filterType = FilterType.FTRL_ML_META_LABEL_GATE,
            passed = !opposingImpulse,
            actualValueFormatted = if (opposingImpulse) "OPPOSING IMPULSE" else "CLEAN PATH",
            thresholdFormatted = "No Counter Impulse",
            reason = "Verifies latest tick does not show a clear opposing counter-impulse",
            gateTitle = "Opposing Impulse Guard"
        )
        val diag6 = FilterDiagnostic(
            filterType = FilterType.TICK_SPEED_AND_FRESHNESS,
            passed = feedPassed,
            actualValueFormatted = "${wsLatencyMs}ms • ${tickAgeMs}ms • +${telemetry.assetPayoutPercent}%",
            thresholdFormatted = "Fresh ≤450ms • ≥${minPayoutPercent}%",
            reason = "Feed freshness, latency validation, asset lock guard, and minimum payout",
            gateTitle = "Feed Freshness & Yield"
        )
        val contextDiag = FilterDiagnostic(
            filterType = FilterType.FTRL_ML_META_LABEL_GATE,
            passed = contextPassed && !trendContradicts,
            actualValueFormatted = when {
                !contextPassed -> telemetry.contextBlockReasons.joinToString(", ")
                trendContradicts -> "Trend Conflict"
                else -> "CLEAN"
            },
            thresholdFormatted = "Zero Context Blocks & Aligned",
            reason = when {
                !contextPassed -> "Blocked by context: ${telemetry.contextBlockReasons.joinToString(", ")}"
                trendContradicts -> "Momentum opposes recent smoothed trend direction"
                else -> "Structural context and smoothed trend aligned"
            },
            gateTitle = "Context & Structural Guard"
        )

        val baseDiagnostics = listOf(diag1, diag2, diag3, diag4, diag5, diag6)
        val diagnostics = if (!contextPassed || trendContradicts) {
            baseDiagnostics + contextDiag
        } else {
            baseDiagnostics
        }

        val triggerReason = if (readyToExecute) {
            "TIME-5 [${decision.name} 5s]: M=${"%+.4f".format(M)} • V=${"%+.3f".format(V)}/s • A=${"%+.3f".format(A)} • DE=${"%.2f".format(DE)} • Str=${"%.1f".format(normalizedMomentum)}σ"
        } else {
            "TIME-5 [NO TRADE]: ${rejectionReason ?: "Scanning for momentum impulse"}"
        }

        val metrics = Time5Metrics(
            momentum = M,
            velocity = V,
            acceleration = A,
            directionalEfficiency = DE,
            rollingVolatility = rollingVol,
            normalizedMomentum = normalizedMomentum,
            smoothedVelocity = smoothedVelocity,
            decision = decision,
            rejectionReason = rejectionReason,
            isFresh = isFresh,
            isOpposingImpulse = opposingImpulse
        )

        val lifecycleEvent = when (decision) {
            SignalDirection.UP -> "SIGNAL_UP"
            SignalDirection.DOWN -> "SIGNAL_DOWN"
            else -> "SIGNAL_REJECTED"
        }

        val record = Time5EvaluationRecord(
            timestampMs = currentTickTimeMs,
            assetIdentifier = telemetry.pair,
            currentPrice = currentPrice,
            momentum = M,
            velocity = V,
            acceleration = A,
            directionalEfficiency = DE,
            normalizedSignalStrength = normalizedMomentum,
            decision = if (decision != SignalDirection.NONE) decision.name else "NO_TRADE",
            rejectionReason = rejectionReason,
            executionAttempted = false,
            platformConfirmed = false,
            actualExecutionPrice = null,
            expiryPrice = null,
            realizedOutcome = null,
            lifecycleEvent = lifecycleEvent
        )
        evaluationRecords.addLast(record)
        while (evaluationRecords.size > 256) {
            evaluationRecords.removeFirst()
        }

        if (readyToExecute && rush.candidatePopulation == CandidatePopulation.POPULATION_B_STRUCTURAL_SNIPER_CANDIDATE) {
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

        return StrategyEvaluation(
            strategyId = strategy.id,
            strategyName = HardLockedMandate.STRATEGY_NAME,
            rawSignal = decision,
            finalSignal = if (readyToExecute) decision else SignalDirection.NONE,
            confidenceScore = effectiveConfidence,
            minConfidenceRequired = HardLockedMandate.MIN_CONFIDENCE_REQUIRED,
            triggerReason = triggerReason,
            filterDiagnostics = diagnostics,
            contextBlockReasons = if (rejectionReason != null && decision == SignalDirection.NONE) listOf(rejectionReason) else emptyList(),
            regimeAdjustmentNote = "TIME-5 Balanced Execution • 4 Core Math Components (M, V, A, DE) • 600ms Cooldown • 5s Expiry",
            readyToExecute = readyToExecute,
            temporalTelemetry = temporalSnapshot,
            time5Metrics = metrics
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
            val actualNoiseRange = if (noiseCount == 0) {
                for (i in 1..lastIdx) {
                    val step = abs(ticks[i].price - ticks[i - 1].price)
                    if (step.isFinite() && step > 0.0) {
                        rawNoiseSum += step
                        noiseCount++
                    }
                }
                1..lastIdx
            } else {
                noiseStartIdx..noiseEndIdx
            }
            val prelimNoiseMean = if (noiseCount > 0) rawNoiseSum / noiseCount.toDouble() else 0.0
            val winsorCap = (prelimNoiseMean * 2.2).coerceAtLeast(1e-7)
            var winsorSum = 0.0
            var winsorCount = 0
            if (prelimNoiseMean > 0.0) {
                for (i in actualNoiseRange) {
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
            val k2 = min(2, lastIdx)
            val netDelta2 = (ticks[lastIdx].price - ticks[lastIdx - k2].price).finiteOr(0.0)

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

            // 3. Feature 1: Multi-Scale RushSNR (harmonized 4-tick, 3-tick, and early 2-tick impulse SNR)
            val snr4 = abs(netDelta4) / (avgNoiseStep * sqrt(k.toDouble()))
            val snr3 = abs(netDelta3) / (avgNoiseStep * sqrt(k3.toDouble()))
            val snr2 = abs(netDelta2) / (avgNoiseStep * sqrt(k2.toDouble()))
            val earlyStepAligned = (candidateDir == SignalDirection.UP && netDelta2 > 0.0 && netDelta3 > 0.0) ||
                (candidateDir == SignalDirection.DOWN && netDelta2 < 0.0 && netDelta3 < 0.0)
            val rushSnr = maxOf(snr4, snr3, if (earlyStepAligned) snr2 else 0.0).finiteOr(0.0)

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
                (tailRetentionRatio >= 0.60 || lastStepRatio >= 1.05) &&
                (prevStepAligned || sameDirStepsIn4 >= 3)

            // Tick arrival speed over the 4-tick rush window (guard against 0ms burst timestamps)
            val dtMs = (ticks[lastIdx].timeMs - ticks[lastIdx - k].timeMs).coerceAtLeast(k * 40L)
            val windowTps = (k * 1000.0) / dtMs.toDouble()
            val tickSpeedTps = maxOf(windowTps.coerceAtMost(25.0), telemetry.velocity.finiteOr(0.0))

            // Micro-noise breakout beyond the preceding pre-surge oscillation center (zero-allocation index loop)
            val priorStart = max(0, ticks.size - 14)
            val priorEndExclusive = max(priorStart + 1, lastIdx - 3)
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
                SignalDirection.UP -> latestTick.price >= localMean + avgNoiseStep * 0.50
                SignalDirection.DOWN -> latestTick.price <= localMean - avgNoiseStep * 0.50
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
                (tailRetentionRatio >= 0.60 || lastStepRatio >= 1.05) &&
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
            telemetry.highVoltageStatus.zlemaTrend == SignalDirection.UP -> SignalDirection.UP
            telemetry.highVoltageStatus.zlemaTrend == SignalDirection.DOWN -> SignalDirection.DOWN
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
            ((localZScore15 - 0.85) / 1.0).finiteOr(0.0).coerceIn(-1.5, 1.8)
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

