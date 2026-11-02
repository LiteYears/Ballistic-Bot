package com.olympquant.terminal.engine

import com.olympquant.terminal.domain.FilterDiagnostic
import com.olympquant.terminal.domain.FilterType
import com.olympquant.terminal.domain.HardLockedMandate
import com.olympquant.terminal.domain.MarketTelemetryState
import com.olympquant.terminal.domain.MicroUrgency
import com.olympquant.terminal.domain.SignalDirection
import com.olympquant.terminal.domain.StrategyConfig
import com.olympquant.terminal.domain.StrategyEvaluation
import com.olympquant.terminal.domain.finiteOr
import kotlin.math.abs
import kotlin.math.min

/**
 * Proprietary Hawkes-Laminar Ballistic Impulse Continuation Engine (BallisticApex5s).
 *
 * Designed specifically for HFT & synthetic tick streams:
 *Instead of lagging indicator crossovers or exhausted range extremes, it detects the exact
 * physical signature of a self-exciting burst wave while it is still accelerating and has
 * unexhausted continuation headroom for the next 5 seconds:
 *  1. Hawkes Arrival Intensity & Signed Order-Flow Surge
 *  2. Laminar Kaufman Fractal Efficiency (ER10 >= 64%, near-zero internal counter-ticks)
 *  3. Convex 2nd-Derivative Tick Acceleration (displacement is steepening, not decelerating)
 *  4. Ballistic Continuation Headroom (relPos in launch corridor 54..93.5% UP / 6.5..46% DOWN, slopeRatio < 3.15x)
 *  5. Zero-Lag EMA Phase-Lead & HMM Regime Coherence with sub-140ms wire latency
 */
class ModularStrategyEngine {

    fun evaluateStrategy(
        strategy: StrategyConfig = HardLockedMandate.lockedStrategyConfig(),
        telemetry: MarketTelemetryState,
        wsLatencyMs: Long,
        minPayoutPercent: Int = HardLockedMandate.MIN_PAYOUT_PERCENT
    ): StrategyEvaluation {
        val lockedStrategy = HardLockedMandate.lockedStrategyConfig()

        // 1. Evaluate Hawkes-Laminar Ballistic Impulse Trigger
        val (rawSignal, triggerReason) = evaluateBallisticImpulseTrigger(telemetry)

        // 2. Evaluate all 5 locked quantitative gatekeepers
        val diagnostics = lockedStrategy.filters.map { rule ->
            evaluateLockedGate(rule.filterType, rule.threshold, rawSignal, telemetry, wsLatencyMs)
        }

        val payoutAndLockPassed = !telemetry.assetLocked && telemetry.assetPayoutPercent >= minPayoutPercent
        val allFiltersPassed = diagnostics.all { it.passed } &&
            telemetry.volatility > 0.0 &&
            payoutAndLockPassed &&
            telemetry.hardStopReason == null

        // 3. HMM Regime Coherence Check (blocks entries if HMM is in high-confidence Reversal state)
        val (regimeAdjustedSignal, regimeNote) = evaluateHmmCoherence(rawSignal, telemetry)

        // 4. Composite Ballistic Continuation Confidence Score
        val ms = telemetry.microstructure
        val hawkesIntensityBonus = min(6.0, (telemetry.tickIntensityRatio - 1.0).coerceAtLeast(0.0) * 12.0)
        val flowSynergyBonus = when {
            regimeAdjustedSignal == SignalDirection.UP && telemetry.orderFlowImbalance >= 20.0 ->
                min(6.0, (telemetry.orderFlowImbalance - 15.0) * 0.12)
            regimeAdjustedSignal == SignalDirection.DOWN && telemetry.orderFlowImbalance <= -20.0 ->
                min(6.0, (abs(telemetry.orderFlowImbalance) - 15.0) * 0.12)
            else -> 0.0
        }
        val convexityBonus = if (ms.microAcceleration >= 0.45) 4.0 else 0.0

        val effectiveConfidence = (
            maxOf(telemetry.baseConfidenceScore, ms.microImpulseScore) +
                hawkesIntensityBonus +
                flowSynergyBonus +
                convexityBonus
            ).finiteOr(0.0).coerceIn(0.0, 100.0)

        val confidencePassed = effectiveConfidence >= HardLockedMandate.MIN_CONFIDENCE_REQUIRED
        val readyToExecute = telemetry.tickCount >= 25 &&
            regimeAdjustedSignal != SignalDirection.NONE &&
            allFiltersPassed &&
            confidencePassed

        return StrategyEvaluation(
            strategyId = lockedStrategy.id,
            strategyName = lockedStrategy.name,
            rawSignal = rawSignal,
            finalSignal = if (allFiltersPassed && confidencePassed) regimeAdjustedSignal else SignalDirection.NONE,
            confidenceScore = effectiveConfidence,
            minConfidenceRequired = HardLockedMandate.MIN_CONFIDENCE_REQUIRED,
            triggerReason = triggerReason,
            filterDiagnostics = diagnostics,
            contextBlockReasons = telemetry.contextBlockReasons,
            regimeAdjustmentNote = regimeNote,
            readyToExecute = readyToExecute
        )
    }

    private fun evaluateBallisticImpulseTrigger(
        telemetry: MarketTelemetryState
    ): Pair<SignalDirection, String> {
        val ms = telemetry.microstructure
        val dir = when {
            ms.impulseDirection != SignalDirection.NONE -> ms.impulseDirection
            telemetry.netDelta5 > telemetry.volatility * 0.75 && telemetry.trend == SignalDirection.UP -> SignalDirection.UP
            telemetry.netDelta5 < -telemetry.volatility * 0.75 && telemetry.trend == SignalDirection.DOWN -> SignalDirection.DOWN
            else -> SignalDirection.NONE
        }

        val alignedWithZlema = dir != SignalDirection.NONE &&
            (telemetry.trend == SignalDirection.NONE || dir == telemetry.trend)

        val orderFlowAligned = when (dir) {
            SignalDirection.UP -> telemetry.orderFlowImbalance >= 12.0 || ms.streakCount >= 3
            SignalDirection.DOWN -> telemetry.orderFlowImbalance <= -12.0 || ms.streakCount >= 3
            SignalDirection.NONE -> false
        }

        return if (
            alignedWithZlema &&
            orderFlowAligned &&
            ms.microImpulseScore >= 66.0 &&
            ms.streakCount >= 3 &&
            ms.urgency != MicroUrgency.HESITANT
        ) {
            dir to "Ballistic Ignition ${dir.name}: Score ${"%.1f".format(ms.microImpulseScore)}% • Streak ${ms.streakCount}t • OFI ${"%+.0f".format(telemetry.orderFlowImbalance)}%"
        } else {
            SignalDirection.NONE to "Hunting Ballistic Ignition (Impulse ${"%.0f".format(ms.microImpulseScore)}%, Streak ${ms.streakCount}t, OFI ${"%+.0f".format(telemetry.orderFlowImbalance)}%)"
        }
    }

    private fun evaluateLockedGate(
        type: FilterType,
        threshold: Double,
        signal: SignalDirection,
        t: MarketTelemetryState,
        wsLatencyMs: Long
    ): FilterDiagnostic {
        val activeDir = if (signal != SignalDirection.NONE) signal else t.trend
        return when (type) {
            FilterType.HAWKES_ARRIVAL_INTENSITY -> {
                val ofi = t.orderFlowImbalance
                val intensity = t.tickIntensityRatio
                val ofiAligned = when (activeDir) {
                    SignalDirection.UP -> ofi >= threshold || (t.microstructure.streakCount >= 4 && ofi >= 5.0)
                    SignalDirection.DOWN -> ofi <= -threshold || (t.microstructure.streakCount >= 4 && ofi <= -5.0)
                    SignalDirection.NONE -> abs(ofi) >= threshold
                }
                val passed = ofiAligned && t.velocity >= 1.4
                FilterDiagnostic(
                    filterType = type,
                    passed = passed,
                    actualValueFormatted = "OFI ${"%+.0f".format(ofi)}% (${"%.2f".format(intensity)}x)",
                    thresholdFormatted = "|OFI| >= ${"%.0f".format(threshold)}%",
                    reason = if (passed) {
                        "Self-exciting Hawkes cluster active (${"%.1f".format(t.velocity)} T/s)"
                    } else {
                        "Awaiting directional order-flow cluster & arrival surge"
                    }
                )
            }

            FilterType.LAMINAR_EFFICIENCY_GATE -> {
                val eff = t.microstructure.directionalEfficiency
                val passed = eff >= threshold && t.microstructure.streakCount >= 3
                FilterDiagnostic(
                    filterType = type,
                    passed = passed,
                    actualValueFormatted = "${"%.0f".format(eff)}% (${t.microstructure.streakCount}t)",
                    thresholdFormatted = ">= ${"%.0f".format(threshold)}% & 3t",
                    reason = if (passed) {
                        "Laminar ballistic path — near-zero counter-tick noise"
                    } else {
                        "Rejected: Internal tick overlap / whipsaw noise"
                    }
                )
            }

            FilterType.CONVEX_ACCELERATION_GATE -> {
                val acc = t.microstructure.microAcceleration
                val deltaAligned = when (activeDir) {
                    SignalDirection.UP -> t.netDelta5 > t.volatility * 0.65
                    SignalDirection.DOWN -> t.netDelta5 < -t.volatility * 0.65
                    SignalDirection.NONE -> abs(t.netDelta5) > t.volatility * 0.65
                }
                val passed = acc >= threshold && deltaAligned
                FilterDiagnostic(
                    filterType = type,
                    passed = passed,
                    actualValueFormatted = "${"%+.2f".format(acc)} (Δ5 ${"%+.3f".format(t.netDelta5)})",
                    thresholdFormatted = ">= +${"%.2f".format(threshold)} T/s²",
                    reason = if (passed) {
                        "Convex 2nd-derivative acceleration confirmed"
                    } else {
                        "Rejected: Impulse wave not accelerating"
                    }
                )
            }

            FilterType.BALLISTIC_HEADROOM_CORRIDOR -> {
                val rel = t.relPos
                val slopeOk = t.volatility > 0.0 && t.slopeRatio < 3.15
                val inLaunchCorridor = when (activeDir) {
                    SignalDirection.UP -> rel in 54.0..threshold
                    SignalDirection.DOWN -> rel in (100.0 - threshold)..46.0
                    SignalDirection.NONE -> rel in 54.0..threshold || rel in (100.0 - threshold)..46.0
                }
                val noWallBlocks = t.contextBlockReasons.isEmpty()
                val passed = slopeOk && inLaunchCorridor && noWallBlocks
                FilterDiagnostic(
                    filterType = type,
                    passed = passed,
                    actualValueFormatted = "Pos ${"%.0f".format(rel)}% • Slope ${"%.2f".format(t.slopeRatio)}x",
                    thresholdFormatted = "54..93.5% | <3.15x",
                    reason = when {
                        !noWallBlocks -> t.contextBlockReasons.first()
                        !slopeOk -> "Rejected: Climax overextension spike (${"%.2f".format(t.slopeRatio)}x)"
                        !inLaunchCorridor -> "Rejected: Outside continuation corridor (relPos=${"%.0f".format(rel)}%)"
                        else -> "Unexhausted continuation headroom verified"
                    }
                )
            }

            FilterType.ZLEMA_HMM_COHERENCE -> {
                val latencyOk = wsLatencyMs <= threshold.toLong()
                val consistencyOk = t.consistency >= 0.70
                val hmm = t.hmmOutput
                val hmmOk = hmm == null || !(hmm.regimeName == "Reversal" && hmm.confidence >= 0.62)
                val passed = latencyOk && consistencyOk && hmmOk
                FilterDiagnostic(
                    filterType = type,
                    passed = passed,
                    actualValueFormatted = "Cons ${"%.2f".format(t.consistency)} • ${wsLatencyMs}ms",
                    thresholdFormatted = ">=0.70 • <=${threshold.toInt()}ms",
                    reason = when {
                        !latencyOk -> "Rejected: Wire latency ${wsLatencyMs}ms > ${threshold.toInt()}ms"
                        !consistencyOk -> "Rejected: ZLEMA phase consistency < 0.70"
                        !hmmOk -> "Rejected: HMM State S${hmm?.state} Reversal veto"
                        else -> "ZLEMA phase-lead & HMM regime aligned"
                    }
                )
            }
        }
    }

    private fun evaluateHmmCoherence(
        rawSignal: SignalDirection,
        telemetry: MarketTelemetryState
    ): Pair<SignalDirection, String> {
        if (rawSignal == SignalDirection.NONE) {
            return SignalDirection.NONE to "HMM Coherence: Standby"
        }
        val hmm = telemetry.hmmOutput ?: return rawSignal to "HMM Coherence: Warmup (<25 ticks)"
        if (hmm.confidence < 0.60) {
            return rawSignal to "HMM Coherence: Low-conf bypass (${(hmm.confidence * 100).toInt()}%)"
        }
        return if (hmm.regimeName == "Reversal") {
            SignalDirection.NONE to "HMM Veto: Suppressed in Reversal State S${hmm.state} (${(hmm.confidence * 100).toInt()}%)"
        } else {
            rawSignal to "HMM Confirmed: ${hmm.regimeName} State S${hmm.state} (${(hmm.confidence * 100).toInt()}%)"
        }
    }
}
