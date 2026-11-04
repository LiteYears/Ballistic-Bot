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
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * OlympQuant X13 — Two-Stage Sub-Millisecond HFT Continuation + Temporal Intelligence Engine:
 *  - Stage 1: `TickRush5s` Raw Tick-Stream Rush vs. Noise Oscillation Primary Detector
 *  - Stage 2: Online FTRL-Proximal Logistic Regression Meta-Labeling Classifier
 *  - Stage 3: `TemporalIntelligenceEngine` (Snapshot Synchronization, Rolling Windows,
 *    Schmitt-Trigger Hysteresis, N-of-M Persistence, Adaptive Regimes, and 3-Layer Scoring)
 */
class ModularStrategyEngine {

    internal data class PendingShadowRush(
        val entryTimeMs: Long,
        val entryPrice: Double,
        val direction: SignalDirection,
        val features: DoubleArray,
        val regime: TemporalRegimeType = TemporalRegimeType.EXPANSION_IMPULSE,
        val predictedProb: Double = 0.65,
        val wasSpikeNoise: Boolean = false
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
        val tickConvexity: Double,
        val arrivalCompressionRatio: Double,
        val singleTickDominancePercent: Double,
        val microPullbackRecovery: Double,
        val localZScore15: Double,
        val rollingShadowWinRatePercent: Double,
        val shadowSamplesLearned: Int,
        val ftrlWinProbPercent: Double
    )

    companion object {
        private const val FEATURE_DIM = 11
        private const val FTRL_ALPHA = 0.14
        private const val FTRL_BETA = 1.0
        private const val FTRL_L1 = 0.015
        private const val FTRL_L2 = 0.12
        private const val FORGETTING_GAMMA = 0.985

        // Microstructure-informed warm-start prior weights:
        // [0]=Bias, [1]=RushSNR_4, [2]=TickConvexity, [3]=ArrivalCompression,
        // [4]=SmoothDominance(Anti-Spike), [5]=MicroPullbackRecovery, [6]=LocalZScore_15,
        // [7]=RollingShadowWinRate_5s, [8]=SNR*Convexity, [9]=SNR*SmoothDominance, [10]=Convexity*Compression
        private val PRIOR_WEIGHTS = doubleArrayOf(
            0.34, // w0: Intercept
            0.56, // w1: RushSNR_4
            0.42, // w2: TickConvexity
            0.28, // w3: ArrivalCompressionRatio
            0.54, // w4: SmoothStepDistribution (penalizes single-tick spike traps)
            0.30, // w5: MicroPullbackRecovery
            0.26, // w6: LocalZScore_15 headroom
            0.44, // w7: RollingShadowWinRate_5s
            0.34, // w8: Non-linear Cross (RushSNR * TickConvexity)
            0.48, // w9: Non-linear Cross (RushSNR * SmoothStepDistribution)
            0.25  // w10: Non-linear Cross (TickConvexity * ArrivalCompression)
        )
    }

    private val ftrlZ = DoubleArray(FEATURE_DIM)
    private val ftrlN = DoubleArray(FEATURE_DIM)
    private val ftrlW = DoubleArray(FEATURE_DIM)
    private val pendingShadowRushes = ArrayDeque<PendingShadowRush>(64)
    private var lastShadowEnqueueTimeMs: Long = 0L
    private var rollingShadowWinRate: Double = 0.66
    private var shadowSamplesCount: Int = 0
    val temporalEngine = TemporalIntelligenceEngine()

    init {
        reset()
    }

    private var cachedScriptHash: Int = 0
    private var cachedCompileResult: DslCompileResult? = null

    @Synchronized
    fun reset() {
        pendingShadowRushes.clear()
        lastShadowEnqueueTimeMs = 0L
        rollingShadowWinRate = 0.66
        shadowSamplesCount = 0
        cachedScriptHash = 0
        cachedCompileResult = null
        temporalEngine.reset()
        for (i in 0 until FEATURE_DIM) {
            ftrlN[i] = 1.0
            val denom = ((FTRL_BETA + sqrt(ftrlN[i])) / FTRL_ALPHA) + FTRL_L2
            ftrlZ[i] = -PRIOR_WEIGHTS[i] * denom
            ftrlW[i] = PRIOR_WEIGHTS[i]
        }
    }

    @Synchronized
    private fun getOrCompileScript(scriptCode: String): DslCompileResult {
        val codeToCompile = scriptCode.ifBlank { DefaultStrategyScripts.DEFAULT_OQS_SCRIPT }
        val hash = codeToCompile.hashCode()
        val existing = cachedCompileResult
        if (existing != null && cachedScriptHash == hash) {
            return existing
        }
        val compiled = StrategyScriptCompiler.compile(codeToCompile)
        cachedScriptHash = hash
        cachedCompileResult = compiled
        return compiled
    }

    @Synchronized
    fun getLiveSymbolSnapshot(
        telemetry: MarketTelemetryState,
        wsLatencyMs: Long,
        scriptCode: String = DefaultStrategyScripts.DEFAULT_OQS_SCRIPT
    ): Map<String, Double> {
        val rush = extractRawTickRushMetrics(telemetry)
        val temporalSnapshot = temporalEngine.pushObservationFrame(telemetry, rush, wsLatencyMs)
        val enrichedTelemetry = telemetry.copy(temporalTelemetry = temporalSnapshot)
        val symbols = StrategyScriptCompiler.buildLiveSymbolTable(enrichedTelemetry, rush, wsLatencyMs, temporalEngine)
        val compiled = getOrCompileScript(scriptCode)
        val parsed = compiled.parsedScript
        if (parsed != null) {
            for (i in parsed.variables.indices) {
                val v = parsed.variables[i]
                val evaluated = if (v.compiledExpr != null) {
                    v.compiledExpr.eval(symbols).finiteOr(0.0)
                } else {
                    runCatching {
                        StrategyScriptCompiler.evaluateMathExpression(v.rawExpression, symbols)
                    }.getOrDefault(0.0).finiteOr(0.0)
                }
                symbols[v.name] = evaluated
            }
        }
        return symbols
    }

    @Synchronized
    fun evaluateStrategy(
        strategy: StrategyConfig = HardLockedMandate.lockedStrategyConfig(),
        telemetry: MarketTelemetryState,
        wsLatencyMs: Long,
        minPayoutPercent: Int = strategy.minPayoutPercent
    ): StrategyEvaluation {
        val rush = extractRawTickRushMetrics(telemetry)
        val temporalSnapshot = temporalEngine.pushObservationFrame(telemetry, rush, wsLatencyMs)
        val enrichedTelemetry = telemetry.copy(temporalTelemetry = temporalSnapshot)
        val compiled = getOrCompileScript(strategy.scriptCode)
        val parsedScript = compiled.parsedScript

        if (!compiled.isValid || parsedScript == null) {
            val firstErr = compiled.errors.firstOrNull()
            val errMsg = if (firstErr != null) {
                "Line ${firstErr.line} [${firstErr.section}]: ${firstErr.message}"
            } else {
                "Invalid .oqs strategy script"
            }
            return StrategyEvaluation(
                strategyId = strategy.id,
                strategyName = strategy.name,
                rawSignal = SignalDirection.NONE,
                finalSignal = SignalDirection.NONE,
                confidenceScore = 0.0,
                minConfidenceRequired = strategy.minConfidence,
                triggerReason = "SCRIPT ERROR: $errMsg",
                filterDiagnostics = listOf(
                    FilterDiagnostic(
                        filterType = FilterType.TICK_RUSH_SNR_GATE,
                        passed = false,
                        actualValueFormatted = "SYNTAX ERROR",
                        thresholdFormatted = "Valid .oqs",
                        reason = errMsg,
                        gateTitle = "OQS Compiler Validation"
                    )
                ),
                contextBlockReasons = listOf(errMsg),
                regimeAdjustmentNote = "Fix .oqs syntax error in Strategy IDE before execution.",
                readyToExecute = false,
                temporalTelemetry = temporalSnapshot
            )
        }

        val symbols = StrategyScriptCompiler.buildLiveSymbolTable(enrichedTelemetry, rush, wsLatencyMs, temporalEngine)
        val runtimeEval = StrategyScriptCompiler.evaluateVariablesAndSignals(parsedScript, symbols, temporalEngine)
        val rawSignal = runtimeEval.rawSignal
        val diagnostics = runtimeEval.gateDiagnostics
        val effectiveMinPayout = parsedScript.execution.minPayoutPct.coerceIn(50, 98)
        val effectiveMinConfidence = parsedScript.execution.minConfidence.coerceIn(1.0, 99.0)

        val payoutAndLockPassed = !telemetry.assetLocked && telemetry.assetPayoutPercent >= minOf(minPayoutPercent, effectiveMinPayout)
        val allFiltersPassed = diagnostics.all { it.passed } &&
            payoutAndLockPassed &&
            telemetry.hardStopReason == null

        val isHighVoltage = parsedScript.name.contains("HighVoltage", ignoreCase = true) ||
            strategy.name.contains("HighVoltage", ignoreCase = true)

        // Use HV Window score when running HighVoltage strategy, or the higher of raw FTRL and X13 Calibrated Win Prob
        val baseConfidence = max(
            rush.ftrlWinProbPercent.finiteOr(0.0),
            if (rawSignal != SignalDirection.NONE) temporalSnapshot.calibratedWinProbPercent.finiteOr(0.0) else 0.0
        ).coerceIn(0.0, 100.0)
        val effectiveConfidence = if (isHighVoltage && telemetry.hvWindow.engineBooted) {
            max(telemetry.hvWindow.score.finiteOr(0.0), if (allFiltersPassed && rawSignal != SignalDirection.NONE) baseConfidence else 0.0)
                .coerceIn(0.0, 100.0)
        } else {
            baseConfidence
        }
        val confidencePassed = effectiveConfidence >= effectiveMinConfidence
        val enoughTicks = telemetry.tickCount >= 10 || telemetry.recentTicks.size >= 8
        val readyToExecute = enoughTicks &&
            temporalSnapshot.snapshotSynchronized &&
            rawSignal != SignalDirection.NONE &&
            allFiltersPassed &&
            confidencePassed

        val hvWin = telemetry.hvWindow
        val hvStat = telemetry.highVoltageStatus
        val triggerReason = if (isHighVoltage) {
            if (rawSignal != SignalDirection.NONE && allFiltersPassed) {
                "${parsedScript.name} [${rawSignal.name} ${parsedScript.execution.durationSec}s]: HV=${"%.0f".format(hvWin.score)}% (${hvWin.state.label}) • Vel=${"%.2f".format(hvStat.tickVelocity)}T/s • RelPos=${"%.0f".format(hvStat.relPos50)}% • Δ5=${"%+.5f".format(hvStat.netDelta5)}"
            } else {
                "${parsedScript.name} [${parsedScript.execution.durationSec}s] • HV Window ${"%.0f".format(hvWin.score)}% (${hvWin.state.label}) • Filters ${hvStat.passedFilterCount}/6 • Trap: ${hvWin.activeTrap.label}"
            }
        } else if (rawSignal != SignalDirection.NONE) {
            "${parsedScript.name} [${rawSignal.name}]: Calib P(Win)=${"%.1f".format(effectiveConfidence)}% • SNR ${"%.2f".format(rush.rushSnr)}x • Purity ${"%.0f".format(rush.purityPercent)}% • ${temporalSnapshot.regimeType.displayName}"
        } else {
            "${parsedScript.name} X13 Scanner (L2=${"%.0f".format(temporalSnapshot.signalLayerScore)}% • Calib=${"%.0f".format(temporalSnapshot.calibratedWinProbPercent)}% • Stability=${"%.0f".format(temporalSnapshot.gateStabilityPercent)}% • EV=${"%+.1f".format(temporalSnapshot.oosExpectancyPercent)}%)"
        }

        val regimeNote = if (isHighVoltage) {
            "HV Window [${hvWin.state.label} ${"%.1f".format(hvWin.score)}% | Hold ${"%.0f".format(hvWin.stabilitySeconds)}s] • 60s Range ${"%.4f".format(hvWin.anchorRange60s)} • Wicks ${"%.0f".format(hvWin.wickCollapseRatioPercent)}% • MTF ${hvWin.mtfAlignedCount}/4"
        } else {
            "X13 Temporal [${temporalSnapshot.regimeType.displayName}] • L1:${if (temporalSnapshot.eligibilityLayerPassed) "OK" else "HOLD"} • L2:${"%.0f".format(temporalSnapshot.signalLayerScore)}% • L3:${"%.0f".format(temporalSnapshot.validationLayerScore)}% • Stability ${"%.1f".format(temporalSnapshot.gateStabilityPercent)}% • OOS EV ${"%+.1f".format(temporalSnapshot.oosExpectancyPercent)}%"
        }

        return StrategyEvaluation(
            strategyId = strategy.id,
            strategyName = parsedScript.name.ifBlank { strategy.name },
            rawSignal = rawSignal,
            finalSignal = if (allFiltersPassed && confidencePassed) rawSignal else SignalDirection.NONE,
            confidenceScore = effectiveConfidence,
            minConfidenceRequired = effectiveMinConfidence,
            triggerReason = triggerReason,
            filterDiagnostics = diagnostics,
            contextBlockReasons = emptyList(),
            regimeAdjustmentNote = regimeNote,
            readyToExecute = readyToExecute,
            temporalTelemetry = temporalSnapshot
        )
    }

    @Synchronized
    internal fun extractRawTickRushMetrics(telemetry: MarketTelemetryState): RawTickRushMetrics {
        val ticks = telemetry.recentTicks
        if (ticks.size >= 6) {
            val lastIdx = ticks.lastIndex
            val latestTick = ticks[lastIdx]
            val latestTimeMs = if (latestTick.timeMs > 0L) latestTick.timeMs else telemetry.latestTimeMs

            // Step A: Resolve any matured 5-second virtual shadow rushes and update FTRL-Proximal weights online!
            settleMaturedShadowRushes(latestTimeMs, latestTick.price)

            val k = min(4, lastIdx)

            // 1. Baseline single-tick noise step from ticks preceding the active 3-tick tip
            val noiseEndIdx = (lastIdx - 3).coerceAtLeast(1)
            val noiseStartIdx = (noiseEndIdx - 24).coerceAtLeast(1)
            var noiseSum = 0.0
            var noiseCount = 0
            for (i in noiseStartIdx..noiseEndIdx) {
                val step = abs(ticks[i].price - ticks[i - 1].price)
                if (step.isFinite() && step > 0.0) {
                    noiseSum += step
                    noiseCount++
                }
            }
            if (noiseCount == 0) {
                for (i in 1..lastIdx) {
                    val step = abs(ticks[i].price - ticks[i - 1].price)
                    if (step.isFinite() && step > 0.0) {
                        noiseSum += step
                        noiseCount++
                    }
                }
            }
            val avgNoiseStep = if (noiseCount > 0) {
                (noiseSum / noiseCount.toDouble()).coerceAtLeast(1e-7)
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
                if (candidateDir == SignalDirection.UP && d > 0.0) sameDirStepsIn4++
                if (candidateDir == SignalDirection.DOWN && d < 0.0) sameDirStepsIn4++
            }

            val purityPercent = if (pathLen4 > 1e-9) {
                ((abs(netDelta4) / pathLen4) * 100.0).finiteOr(0.0).coerceIn(0.0, 100.0)
            } else {
                0.0
            }

            // 3. Feature 1: RushSNR_4
            val snr4 = abs(netDelta4) / (avgNoiseStep * sqrt(k.toDouble()))
            val snr3 = abs(netDelta3) / (avgNoiseStep * sqrt(k3.toDouble()))
            val rushSnr = maxOf(snr4, snr3 * 0.96).finiteOr(0.0)

            // 4. Feature 2: TickConvexity ((|d_t| + |d_{t-1}|) / (|d_{t-2}| + |d_{t-3}|))
            val d0 = abs(ticks[lastIdx].price - ticks[lastIdx - 1].price)
            val d1 = if (lastIdx >= 2) abs(ticks[lastIdx - 1].price - ticks[lastIdx - 2].price) else d0
            val d2 = if (lastIdx >= 3) abs(ticks[lastIdx - 2].price - ticks[lastIdx - 3].price) else avgNoiseStep
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

            // Consecutive directional streak & zero-hesitation tip alignment
            val lastStep = ticks[lastIdx].price - ticks[lastIdx - 1].price
            val prevStep = if (lastIdx >= 2) ticks[lastIdx - 1].price - ticks[lastIdx - 2].price else lastStep
            val lastStepRatio = (abs(lastStep) / avgNoiseStep).finiteOr(0.0)

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

            val tipAligned = latestStepAligned &&
                lastStepRatio >= 0.70 &&
                (prevStepAligned || sameDirStepsIn4 >= 3)

            // Tick arrival speed over the 4-tick rush window
            val dtMs = (ticks[lastIdx].timeMs - ticks[lastIdx - k].timeMs).coerceAtLeast(1L)
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

            // Build the 11-dimensional FTRL-Proximal feature vector (7 core + 3 non-linear cross-features + bias)
            val featureVector = buildFtrlFeatureVector(
                rushSnr = rushSnr,
                tickConvexity = tickConvexity,
                arrivalCompressionRatio = arrivalCompressionRatio,
                singleTickDominance = singleTickDominance,
                microPullbackRecovery = microPullbackRecovery,
                localZScore15 = localZScore15,
                shadowWinRate = rollingShadowWinRate
            )

            // Predict 5-second continuation probability P(Win 5s) via FTRL-Proximal dot product
            val ftrlWinProbPercent = if (candidateDir != SignalDirection.NONE && rushSnr >= 1.05 && purityPercent >= 55.0) {
                predictFtrlWinProbability(featureVector) * 100.0
            } else {
                // During flat noise oscillation, discount probability so UI reflects low continuation odds
                (predictFtrlWinProbability(featureVector) * 55.0).coerceAtMost(48.0)
            }

            // Enqueue virtual shadow rush if a candidate burst is underway so the model learns online every 5s
            if (
                candidateDir != SignalDirection.NONE &&
                rushSnr >= 1.22 &&
                purityPercent >= 62.0 &&
                latestTimeMs - lastShadowEnqueueTimeMs >= 900L
            ) {
                lastShadowEnqueueTimeMs = latestTimeMs
                if (pendingShadowRushes.size >= 48) {
                    pendingShadowRushes.removeFirst()
                }
                pendingShadowRushes.addLast(
                    PendingShadowRush(
                        entryTimeMs = latestTimeMs,
                        entryPrice = latestTick.price,
                        direction = candidateDir,
                        features = featureVector,
                        regime = if (rushSnr >= 1.45) TemporalRegimeType.EXPANSION_IMPULSE else TemporalRegimeType.TREND,
                        predictedProb = (ftrlWinProbPercent / 100.0).coerceIn(0.1, 0.95),
                        wasSpikeNoise = singleTickDominancePercent > 64.0
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
                tickConvexity = tickConvexity,
                arrivalCompressionRatio = arrivalCompressionRatio,
                singleTickDominancePercent = singleTickDominancePercent,
                microPullbackRecovery = microPullbackRecovery,
                localZScore15 = localZScore15,
                rollingShadowWinRatePercent = rollingShadowWinRate * 100.0,
                shadowSamplesLearned = shadowSamplesCount,
                ftrlWinProbPercent = ftrlWinProbPercent
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
            shadowWinRate = rollingShadowWinRate
        )
        val probPct = if (rushSnr >= 1.25 && purityPercent >= 68.0) {
            predictFtrlWinProbability(featureVector) * 100.0
        } else {
            42.0
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
            ftrlWinProbPercent = probPct
        )
    }

    private fun buildFtrlFeatureVector(
        rushSnr: Double,
        tickConvexity: Double,
        arrivalCompressionRatio: Double,
        singleTickDominance: Double,
        microPullbackRecovery: Double,
        localZScore15: Double,
        shadowWinRate: Double
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
        val f7 = ((shadowWinRate - 0.52) / 0.20).finiteOr(0.0).coerceIn(-2.0, 2.0)

        // Non-linear cross-feature interactions
        val f8 = (f1 * f2).coerceIn(-2.5, 3.0)
        val f9 = (f1 * f4).coerceIn(-3.0, 2.5)
        val f10 = (f2 * f3).coerceIn(-2.0, 2.5)

        return doubleArrayOf(1.0, f1, f2, f3, f4, f5, f6, f7, f8, f9, f10)
    }

    private fun settleMaturedShadowRushes(currentTimeMs: Long, currentPrice: Double) {
        while (pendingShadowRushes.isNotEmpty()) {
            val oldest = pendingShadowRushes.first()
            if (currentTimeMs - oldest.entryTimeMs < 5000L) break
            pendingShadowRushes.removeFirst()

            val isContinuationWin = (oldest.direction == SignalDirection.UP && currentPrice > oldest.entryPrice) ||
                (oldest.direction == SignalDirection.DOWN && currentPrice < oldest.entryPrice)
            val isTie = abs(currentPrice - oldest.entryPrice) < 1e-9
            if (!isTie) {
                val labelY = if (isContinuationWin) 1.0 else 0.0
                updateFtrlWeightsOnline(oldest.features, labelY)
                rollingShadowWinRate = (0.85 * rollingShadowWinRate + 0.15 * labelY).coerceIn(0.15, 0.92)
                shadowSamplesCount++
                temporalEngine.recordShadowOutcome(
                    direction = oldest.direction,
                    regime = oldest.regime,
                    predictedProb = oldest.predictedProb,
                    won = isContinuationWin,
                    wasSpikeNoise = oldest.wasSpikeNoise
                )
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
            val nOld = max(1e-6, ftrlN[i] * FORGETTING_GAMMA)
            val nNew = nOld + grad * grad
            val sigma = (sqrt(nNew) - sqrt(nOld)) / FTRL_ALPHA
            ftrlZ[i] = (ftrlZ[i] * FORGETTING_GAMMA) + grad - sigma * ftrlW[i]
            ftrlN[i] = nNew
        }
    }
}

