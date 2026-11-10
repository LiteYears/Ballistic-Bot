package com.olympquant.terminal.engine

import androidx.compose.runtime.Immutable
import com.olympquant.terminal.domain.FilterDiagnostic
import com.olympquant.terminal.domain.FilterType
import com.olympquant.terminal.domain.HardLockedMandate
import com.olympquant.terminal.domain.MarketTelemetryState
import com.olympquant.terminal.domain.SignalDirection
import com.olympquant.terminal.domain.finiteOr
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * OlympQuant Strategy Script (.oqs) — Deterministic Domain-Specific Language (DSL)
 *
 * Every strategy in OlympQuant is defined as a structured `.oqs` source file with a
 * strict, uniform 6-block structure:
 *   1. [META]        — name, version, author, description
 *   2. [EXECUTION]   — duration_sec, stake_mode, stake_value, min_stake_usd, max_stake_usd,
 *                      cooldown_ms, min_payout_pct, min_confidence, max_daily_trades, max_consecutive_losses
 *   3. [VARIABLES]   — user-defined derived variables: `let <identifier> = <math_expr>`
 *   4. [SIGNAL_UP]   — boolean rules that trigger a CALL (UP) entry when all lines evaluate to true
 *   5. [SIGNAL_DOWN] — boolean rules that trigger a PUT (DOWN) entry when all lines evaluate to true
 *   6. [GATES]       — named safety/confirmation gates: `gate "<Name>" : <boolean_expr>`
 */
object DefaultStrategyScripts {

    const val SNIPER_M_STRATEGY_NAME = "Sniper M"

    val SNIPER_M_OQS_SCRIPT: String = """
# ============================================================================
# OLYMPQUANT — SNIPER M (.oqs)
# Single Streamlined Momentum Surge Strategy:
#   1. Analyze & Smooth the raw tick stream (Zero-Lag Fast Smoother vs Slow Baseline)
#   2. Follow the smoothed momentum direction (UP or DOWN)
#   3. Detect an accelerating surge in the smoothed direction & ride the surge
# ============================================================================

[META]
name = "Sniper M"
version = "8.0"
author = "OlympQuant Core"
description = "Analyzes raw tick stream data, smooths it with a zero-lag filter, follows directional momentum, and places a trade when an active tick stream surge aligns with the smoothed direction."

[EXECUTION]
duration_sec = 5
stake_mode = "PERCENT_BALANCE"
stake_value = 5.0
min_stake_usd = 1.0
max_stake_usd = 4000.0
cooldown_ms = 4000
min_payout_pct = 75
min_confidence = 58.0
max_daily_trades = 10
max_consecutive_losses = 3

[SIGNAL_UP]
rush_dir == UP
zlema_trend_dir == UP
rush_snr >= 1.25
tick_purity >= 65.0
tip_aligned == 1
cleared_noise_mean == 1
tick_convexity >= 0.55
tick_speed >= 1.15
single_tick_dominance <= 65.0

[SIGNAL_DOWN]
rush_dir == DOWN
zlema_trend_dir == DOWN
rush_snr >= 1.25
tick_purity >= 65.0
tip_aligned == 1
cleared_noise_mean == 1
tick_convexity >= 0.55
tick_speed >= 1.15
single_tick_dominance <= 65.0

[GATES]
gate "1. Smoothed Tick Direction & Momentum" : (rush_dir == UP and zlema_trend_dir == UP) or (rush_dir == DOWN and zlema_trend_dir == DOWN)
gate "2. Tick Stream Surge (SNR >= 1.25x & Speed >= 1.15 T/s)" : rush_snr >= 1.25 and tick_speed >= 1.15 and cleared_noise_mean == 1 and tick_convexity >= 0.55
gate "3. Clean Surge Purity (>= 65% & No 1-Tick Glitch)" : tick_purity >= 65.0 and tip_aligned == 1 and single_tick_dominance <= 65.0 and ws_latency_ms <= 250
""".trimIndent()

    @Immutable
    data class VariableDoc(
        val name: String,
        val unit: String,
        val description: String
    )

    val BUILTIN_VARIABLE_DOCS: List<VariableDoc> = listOf(
        VariableDoc("zlema_trend_dir", "UP/DOWN", "HighVoltage Filter 1: ZLEMA Fast(10) vs Slow(30) trend (UP=1, DOWN=-1, NONE=0)"),
        VariableDoc("tick_speed", "T/s", "HighVoltage Filter 2: 3-second tick speed in ticks/sec (minVelocity >= 1.5)"),
        VariableDoc("rel_pos", "%", "HighVoltage Filter 3: 50-tick range breakout position (UP >= 80%, DOWN <= 20%)"),
        VariableDoc("net_delta_5", "px", "HighVoltage Filter 4: Net price displacement over last 6 ticks (>0 UP, <0 DOWN)"),
        VariableDoc("sr_wall_clear", "0/1", "HighVoltage Filter 5: 1 if no S/R wall within scale-adjusted clearance ahead of trade"),
        VariableDoc("sr_ahead_dist", "px", "HighVoltage Filter 5: Distance in points to nearest S/R wall ahead of trade"),
        VariableDoc("sr_min_clearance", "px", "HighVoltage Filter 5: Scale-adjusted minimum S/R clearance (0.25 idx / 0.00025 FX)"),
        VariableDoc("candle_range_ratio", "x", "HighVoltage Filter 6: Current candle range divided by 10-candle avg range"),
        VariableDoc("vol_compressed", "0/1", "HighVoltage Filter 6: 1 if current candle range < 40% of 10-candle average"),
        VariableDoc("vol_exhausted", "0/1", "HighVoltage Filter 6: 1 if candle range is abnormally exhausted (>265%)"),
        VariableDoc("vol_regime_ok", "0/1", "HighVoltage Filter 6: 1 if candle volatility is neither compressed nor exhausted"),
        VariableDoc("opportunity_dir", "UP/DOWN", "Omni-Playbook Direction (UP=1, DOWN=-1, NONE=0) across Continuation, S/R Bounce, and Mean Reversion"),
        VariableDoc("continuation_ready", "0/1", "1 if all 6 strict Trend/Momentum Continuation filters are satisfied"),
        VariableDoc("sr_bounce_ready", "0/1", "1 if price is actively bouncing off Support (UP) or rejecting off Resistance (DOWN)"),
        VariableDoc("mean_rev_ready", "0/1", "1 if price is snapping back from Overbought (>=82% -> DOWN) or Oversold (<=18% -> UP) extreme"),
        VariableDoc("any_setup_ready", "0/1", "1 if Continuation, Support/Resistance Bounce, or Mean Reversion is ready to execute"),
        VariableDoc("playbook_type", "0..3", "Active Playbook ID (1 = CONTINUATION, 2 = SR_BOUNCE, 3 = MEAN_REVERSION, 0 = SCANNING)"),
        VariableDoc("opportunity_score", "%", "Quality score (0..100%) of the active Continuation, S/R Bounce, or Mean-Reversion setup"),
        VariableDoc("support_dist", "px", "Distance in points above nearest Support / Swing Low / Grid floor"),
        VariableDoc("resistance_dist", "px", "Distance in points below nearest Resistance / Swing High / Grid ceiling"),
        VariableDoc("hv_score", "%", "HV Window Co-Pilot: Zero-lag fast-decay EMA (α=0.08) score (≥75 Excellent, 50-74 Mod, <50 Avoid)"),
        VariableDoc("hv_raw_score", "%", "HV Window Co-Pilot: Instantaneous unsmoothed structural score (0..100%)"),
        VariableDoc("hv_state", "0..2", "HV Window State (2 = EXCELLENT ≥75%, 1 = MODERATE 50-74%, 0 = AVOID <50%)"),
        VariableDoc("hv_tight_chop", "0/1", "HV Window Trap: 1 if 15s/30s/45s/60s anchor range < 0.20 pts (TIGHT_CHOP)"),
        VariableDoc("hv_crazy_whipsaw", "0/1", "HV Window Trap: 1 if active + 4 completed candles show collapse wicks > 55%"),
        VariableDoc("hv_wick_ratio", "%", "HV Window: Dominant wick collapse ratio (%) across active + 4 completed candles"),
        VariableDoc("hv_body_ratio", "%", "HV Window: Average solid body ratio (%) across active + 4 completed candles"),
        VariableDoc("hv_anchor_range_60s", "px", "HV Window: Total price range across 15s, 30s, 45s, and 60s anchors"),
        VariableDoc("hv_mtf_aligned", "0..4", "HV Window: Count of 15s/30s/45s/60s anchors aligned with ZLEMA trend"),
        VariableDoc("hv_stability_sec", "s", "HV Window: Seconds continuously held ≥ 72% (target 15–20s before bot launch)"),
        VariableDoc("trade_duration_sec", "s", "Expanded Trade Timeframe duration in seconds (5, 15, 30, 45, or 60)"),
        VariableDoc("rush_dir", "UP/DOWN", "Primary 4-tick rush direction (UP = 1, DOWN = -1, NONE = 0)"),
        VariableDoc("rush_snr", "x", "4-tick net displacement divided by baseline noise step * sqrt(4)"),
        VariableDoc("tick_purity", "%", "One-way directional purity (0..100%) across the 4-tick window"),
        VariableDoc("tick_streak", "ticks", "Consecutive ticks moving in the active rush direction"),
        VariableDoc("tip_aligned", "0/1", "1 if the latest tick accelerated in the rush direction without hesitation"),
        VariableDoc("cleared_noise_mean", "0/1", "1 if latest price broke outside the 8-tick oscillation center"),
        VariableDoc("single_tick_dominance", "%", "Largest single tick step as % of 4-tick move (high = freak spike trap)"),
        VariableDoc("tick_convexity", "x", "Recent 2-tick step size vs prior 2-tick step size (>1.0 = accelerating)"),
        VariableDoc("arrival_compression", "x", "Recent inter-tick time interval vs prior interval (<1.0 = speeding up)"),
        VariableDoc("pullback_recovery", "0..1", "6-tick forward thrust ratio vs counter-ticks"),
        VariableDoc("local_zscore_15", "σ", "15-tick local price Z-score (overextension gauge)"),
        VariableDoc("ftrl_win_prob", "%", "Online FTRL-Proximal ML predicted 5-second continuation probability"),
        VariableDoc("calibrated_win_prob", "%", "X13 Calibrated 5s win probability with cold-start & regime calibration"),
        VariableDoc("eligibility_passed", "0/1", "X13 Layer 1 Market Eligibility hard-rejection pass flag"),
        VariableDoc("signal_layer_score", "%", "X13 Layer 2 Signal Intelligence weighted score (0..100%)"),
        VariableDoc("validation_layer_score", "%", "X13 Layer 3 Prediction Validation score (0..100%)"),
        VariableDoc("snapshot_sync", "0/1", "1 if atomic market snapshot is synchronized and non-stale"),
        VariableDoc("tick_age_ms", "ms", "Age of latest tick observation in milliseconds"),
        VariableDoc("roll_snr_3", "x", "3-observation rolling mean of rush_snr"),
        VariableDoc("roll_purity_4", "%", "4-observation rolling mean of tick_purity"),
        VariableDoc("roll_ofi_median_5", "%", "5-observation outlier-resistant rolling median of OFI"),
        VariableDoc("roll_impulse_4", "%", "4-observation rolling mean of micro_impulse_score"),
        VariableDoc("hyst_momentum", "0/1", "Schmitt-trigger hysteresis state on momentum (enter>=65, exit<50)"),
        VariableDoc("hyst_purity", "0/1", "Schmitt-trigger hysteresis state on tick purity (enter>=72, exit<62)"),
        VariableDoc("hyst_snr", "0/1", "Schmitt-trigger hysteresis state on rush SNR (enter>=1.32, exit<1.12)"),
        VariableDoc("persist_count_5", "0..5", "N-of-5 directional persistence count across last 5 evaluations"),
        VariableDoc("noise_clean", "0/1", "1 if X13 Noise Classifier confirms no spike trap or OFI divergence"),
        VariableDoc("regime_type", "0..3", "X13 Adaptive Regime (0=Consolidation, 1=Trend, 2=Expansion, 3=Transition)"),
        VariableDoc("regime_stable", "0/1", "1 if regime is stable and not in an uncertain transition"),
        VariableDoc("regime_snr_thresh", "x", "Regime-adaptive SNR threshold (1.24 Trend, 1.28 Expansion, 1.42 Chop)"),
        VariableDoc("regime_purity_thresh", "%", "Regime-adaptive Purity threshold (66% Trend, 68% Expansion, 74% Chop)"),
        VariableDoc("up_win_rate", "%", "Empirical walk-forward 5s accuracy for UP signals"),
        VariableDoc("down_win_rate", "%", "Empirical walk-forward 5s accuracy for DOWN signals"),
        VariableDoc("regime_win_rate", "%", "Empirical walk-forward 5s accuracy in the active market regime"),
        VariableDoc("gate_stability_pct", "%", "X13 Gate Stability index (100% minus gate flicker rate)"),
        VariableDoc("oos_expectancy_pct", "%", "Walk-forward Out-of-Sample Expected Value (%) at current payout"),
        VariableDoc("shadow_win_rate", "%", "Rolling 5s virtual shadow-trade continuation win rate"),
        VariableDoc("shadow_samples", "count", "Total 5s virtual shadow rushes learned online in session"),
        VariableDoc("avg_noise_step", "px", "Baseline single-tick noise oscillation step size"),
        VariableDoc("net_delta_4", "px", "Signed 4-tick price displacement"),
        VariableDoc("last_step_ratio", "x", "Latest tick step divided by average noise step"),
        VariableDoc("price", "px", "Latest raw tick quote price"),
        VariableDoc("ofi", "%", "Order Flow Imbalance (-100..+100%)"),
        VariableDoc("tick_intensity", "x", "Tick arrival intensity ratio vs baseline"),
        VariableDoc("micro_impulse_score", "%", "Composite microstructure ballistic score (0..100%)"),
        VariableDoc("micro_efficiency", "%", "10-tick Kaufman directional efficiency (0..100%)"),
        VariableDoc("micro_acceleration", "px", "Second-order tick acceleration"),
        VariableDoc("z_fast", "px", "Zero-Lag EMA Fast (period 10)"),
        VariableDoc("z_slow", "px", "Zero-Lag EMA Slow (period 30)"),
        VariableDoc("zlema_spread", "px", "Signed spread (z_fast - z_slow)"),
        VariableDoc("volatility", "px", "Rolling tick return standard deviation"),
        VariableDoc("consistency", "%", "Directional tick consistency (0..100%)"),
        VariableDoc("persistence", "ticks", "Signed macro tick persistence"),
        VariableDoc("slope_ratio", "x", "Linear regression slope / volatility"),
        VariableDoc("hvoi_score", "%", "HV Window / HVOI smoothed session suitability score"),
        VariableDoc("sr_distance", "px", "Distance to nearest Support/Resistance zone"),
        VariableDoc("hmm_state", "0..7", "8-state Gaussian HMM regime"),
        VariableDoc("hmm_confidence", "%", "Gaussian HMM state posterior confidence (0..100%)"),
        VariableDoc("ws_latency_ms", "ms", "Live WebSocket round-trip latency in milliseconds"),
        VariableDoc("payout_pct", "%", "Current broker payout percentage for the active asset"),
        VariableDoc("sentiment_pct", "%", "Live crowd bullish sentiment percentage (0..100%)"),
        VariableDoc("ofi_imbalance", "%", "Alias for `ofi`: Order Flow Imbalance (-100..+100%)"),
        VariableDoc("velocity", "T/s", "Alias for `tick_speed`: 3-second tick velocity in ticks/sec")
    )
}

@Immutable
data class DslSyntaxError(
    val line: Int,
    val section: String,
    val message: String
)

@Immutable
data class DslExecutionConfig(
    val durationSec: Int = HardLockedMandate.LOCKED_DURATION_SEC,
    val stakeMode: String = "PERCENT_BALANCE", // PERCENT_BALANCE or FIXED_USD
    val stakeValue: Double = HardLockedMandate.BALANCE_STAKE_PERCENT,
    val minStakeUsd: Double = HardLockedMandate.MIN_STAKE_USD,
    val maxStakeUsd: Double = HardLockedMandate.MAX_STAKE_USD,
    val cooldownMs: Long = HardLockedMandate.LOCKED_COOLDOWN_MS,
    val minPayoutPct: Int = HardLockedMandate.MIN_PAYOUT_PERCENT,
    val minConfidence: Double = HardLockedMandate.MIN_CONFIDENCE_REQUIRED,
    val maxDailyTrades: Int = HardLockedMandate.MAX_TRADES_PER_DAY,
    val maxConsecutiveLosses: Int = HardLockedMandate.MAX_CONSECUTIVE_LOSSES
) {
    fun computeStake(balance: Double): Double {
        val safeBalance = balance.finiteOr(0.0).coerceAtLeast(0.0)
        val rawStake = if (stakeMode.equals("FIXED_USD", ignoreCase = true)) {
            stakeValue.finiteOr(minStakeUsd)
        } else {
            safeBalance * (stakeValue.finiteOr(5.0).coerceIn(0.1, 100.0) / 100.0)
        }
        val clamped = rawStake.coerceIn(
            minStakeUsd.coerceAtLeast(1.0),
            maxStakeUsd.coerceIn(minStakeUsd.coerceAtLeast(1.0), 4000.0)
        )
        return ((clamped * 100.0).roundToLong() / 100.0)
    }
}

@Immutable
data class DslVariableAssignment(
    val line: Int,
    val name: String,
    val rawExpression: String,
    val compiledExpr: CompiledMathExpr? = null
)

@Immutable
data class DslConditionRule(
    val line: Int,
    val rawCondition: String,
    val compiledBool: CompiledBoolExpr? = null
)

@Immutable
data class DslGateDefinition(
    val line: Int,
    val name: String,
    val rawCondition: String,
    val compiledBool: CompiledBoolExpr? = null,
    val primaryIdentifiers: List<String> = emptyList()
)

@Immutable
sealed interface CompiledMathExpr {
    fun eval(
        symbols: Map<String, Double>,
        temporalEngine: TemporalIntelligenceEngine? = null,
        mutateState: Boolean = true
    ): Double

    data class Literal(val value: Double) : CompiledMathExpr {
        override fun eval(
            symbols: Map<String, Double>,
            temporalEngine: TemporalIntelligenceEngine?,
            mutateState: Boolean
        ): Double = value
    }

    data class Variable(val name: String) : CompiledMathExpr {
        override fun eval(
            symbols: Map<String, Double>,
            temporalEngine: TemporalIntelligenceEngine?,
            mutateState: Boolean
        ): Double = (symbols[name] ?: 0.0).finiteOr(0.0)
    }

    data class UnaryNeg(val inner: CompiledMathExpr) : CompiledMathExpr {
        override fun eval(
            symbols: Map<String, Double>,
            temporalEngine: TemporalIntelligenceEngine?,
            mutateState: Boolean
        ): Double = -inner.eval(symbols, temporalEngine, mutateState)
    }

    data class BinaryOp(
        val op: Char,
        val left: CompiledMathExpr,
        val right: CompiledMathExpr
    ) : CompiledMathExpr {
        override fun eval(
            symbols: Map<String, Double>,
            temporalEngine: TemporalIntelligenceEngine?,
            mutateState: Boolean
        ): Double {
            val l = left.eval(symbols, temporalEngine, mutateState)
            val r = right.eval(symbols, temporalEngine, mutateState)
            val res = when (op) {
                '+' -> l + r
                '-' -> l - r
                '*' -> l * r
                '/' -> if (abs(r) > 1e-12) l / r else 0.0
                else -> 0.0
            }
            return res.finiteOr(0.0)
        }
    }

    data class Func1(
        val fnName: String,
        val arg: CompiledMathExpr
    ) : CompiledMathExpr {
        override fun eval(
            symbols: Map<String, Double>,
            temporalEngine: TemporalIntelligenceEngine?,
            mutateState: Boolean
        ): Double {
            val v = arg.eval(symbols, temporalEngine, mutateState)
            return when (fnName) {
                "abs" -> abs(v)
                "sqrt" -> sqrt(v.coerceAtLeast(0.0)).finiteOr(0.0)
                else -> 0.0
            }
        }
    }

    data class Func2(
        val fnName: String,
        val arg1: CompiledMathExpr,
        val arg2: CompiledMathExpr
    ) : CompiledMathExpr {
        override fun eval(
            symbols: Map<String, Double>,
            temporalEngine: TemporalIntelligenceEngine?,
            mutateState: Boolean
        ): Double {
            val a = arg1.eval(symbols, temporalEngine, mutateState)
            val b = arg2.eval(symbols, temporalEngine, mutateState)
            val w = b.toInt().coerceIn(1, 16)
            return when (fnName) {
                "min" -> min(a, b)
                "max" -> max(a, b)
                "roll_mean" -> {
                    val varKey = (arg1 as? Variable)?.name
                    if (varKey != null && temporalEngine != null && temporalEngine.hasSeries(varKey)) {
                        temporalEngine.rollingMean(varKey, w)
                    } else if (varKey != null && symbols.containsKey("__roll_mean_${varKey}")) {
                        symbols["__roll_mean_${varKey}"]?.finiteOr(a) ?: a
                    } else {
                        a
                    }
                }
                "roll_median" -> {
                    val varKey = (arg1 as? Variable)?.name
                    if (varKey != null && temporalEngine != null && temporalEngine.hasSeries(varKey)) {
                        temporalEngine.rollingMedian(varKey, w)
                    } else if (varKey != null && symbols.containsKey("__roll_median_${varKey}")) {
                        symbols["__roll_median_${varKey}"]?.finiteOr(a) ?: a
                    } else {
                        a
                    }
                }
                "ema" -> {
                    val varKey = (arg1 as? Variable)?.name
                    if (varKey != null && temporalEngine != null && temporalEngine.hasSeries(varKey)) {
                        temporalEngine.rollingEma(varKey, w)
                    } else if (varKey != null && symbols.containsKey("__ema_${varKey}")) {
                        symbols["__ema_${varKey}"]?.finiteOr(a) ?: a
                    } else {
                        a
                    }
                }
                else -> 0.0
            }
        }
    }

    data class Func3(
        val fnName: String,
        val arg1: CompiledMathExpr,
        val arg2: CompiledMathExpr,
        val arg3: CompiledMathExpr
    ) : CompiledMathExpr {
        override fun eval(
            symbols: Map<String, Double>,
            temporalEngine: TemporalIntelligenceEngine?,
            mutateState: Boolean
        ): Double {
            val v = arg1.eval(symbols, temporalEngine, mutateState)
            val p1 = arg2.eval(symbols, temporalEngine, mutateState)
            val p2 = arg3.eval(symbols, temporalEngine, mutateState)
            return when (fnName) {
                "hysteresis" -> {
                    val varKey = (arg1 as? Variable)?.name
                    if (temporalEngine != null) {
                        val hystKey = "dsl_hyst_${varKey ?: "expr"}_${"%.2f".format(p1)}_${"%.2f".format(p2)}"
                        if (temporalEngine.evaluateHysteresis(hystKey, v, p1, p2, mutateState = mutateState)) 1.0 else 0.0
                    } else if (varKey != null && symbols.containsKey("__hyst_${varKey}")) {
                        symbols["__hyst_${varKey}"] ?: if (v >= p1) 1.0 else 0.0
                    } else {
                        if (v >= p1 || (v >= p2 && (symbols["hyst_momentum"] ?: 0.0) >= 0.5)) 1.0 else 0.0
                    }
                }
                "n_of_m" -> {
                    val requiredN = p1.toInt().coerceAtLeast(1)
                    val windowM = p2.toInt().coerceIn(1, 16)
                    val varKey = (arg1 as? Variable)?.name
                    val hits = if (varKey != null && temporalEngine != null) {
                        max(
                            temporalEngine.countTruthyInWindow(varKey, windowM),
                            (symbols["persist_count_5"] ?: 0.0).toInt()
                        )
                    } else {
                        (symbols["persist_count_5"] ?: 0.0).toInt()
                    }
                    if (v >= 0.5 || hits >= requiredN) 1.0 else 0.0
                }
                else -> 0.0
            }
        }
    }
}

@Immutable
sealed interface CompiledBoolExpr {
    fun eval(
        symbols: Map<String, Double>,
        temporalEngine: TemporalIntelligenceEngine? = null,
        mutateState: Boolean = true
    ): Boolean

    data class Or(val parts: List<CompiledBoolExpr>) : CompiledBoolExpr {
        override fun eval(
            symbols: Map<String, Double>,
            temporalEngine: TemporalIntelligenceEngine?,
            mutateState: Boolean
        ): Boolean {
            for (i in parts.indices) {
                if (parts[i].eval(symbols, temporalEngine, mutateState)) return true
            }
            return false
        }
    }

    data class And(val parts: List<CompiledBoolExpr>) : CompiledBoolExpr {
        override fun eval(
            symbols: Map<String, Double>,
            temporalEngine: TemporalIntelligenceEngine?,
            mutateState: Boolean
        ): Boolean {
            for (i in parts.indices) {
                if (!parts[i].eval(symbols, temporalEngine, mutateState)) return false
            }
            return true
        }
    }

    data class Not(val inner: CompiledBoolExpr) : CompiledBoolExpr {
        override fun eval(
            symbols: Map<String, Double>,
            temporalEngine: TemporalIntelligenceEngine?,
            mutateState: Boolean
        ): Boolean = !inner.eval(symbols, temporalEngine, mutateState)
    }

    data class Comparison(
        val op: String,
        val left: CompiledMathExpr,
        val right: CompiledMathExpr
    ) : CompiledBoolExpr {
        override fun eval(
            symbols: Map<String, Double>,
            temporalEngine: TemporalIntelligenceEngine?,
            mutateState: Boolean
        ): Boolean {
            val l = left.eval(symbols, temporalEngine, mutateState)
            val r = right.eval(symbols, temporalEngine, mutateState)
            return when (op) {
                ">=" -> l >= r - 1e-9
                "<=" -> l <= r + 1e-9
                ">" -> l > r
                "<" -> l < r
                "==" -> abs(l - r) <= 1e-6
                "!=" -> abs(l - r) > 1e-6
                else -> false
            }
        }
    }

    data class MathTruthy(val expr: CompiledMathExpr) : CompiledBoolExpr {
        override fun eval(
            symbols: Map<String, Double>,
            temporalEngine: TemporalIntelligenceEngine?,
            mutateState: Boolean
        ): Boolean = abs(expr.eval(symbols, temporalEngine, mutateState)) > 1e-9
    }
}

@Immutable
data class ParsedStrategyScript(
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val execution: DslExecutionConfig,
    val variables: List<DslVariableAssignment>,
    val signalUpRules: List<DslConditionRule>,
    val signalDownRules: List<DslConditionRule>,
    val gates: List<DslGateDefinition>,
    val rawSource: String
)

@Immutable
data class DslCompileResult(
    val isValid: Boolean,
    val errors: List<DslSyntaxError>,
    val parsedScript: ParsedStrategyScript?
)

/**
 * Compiler & Runtime Evaluator for `.oqs` strategy scripts.
 * Pre-compiles all math and boolean expressions into zero-allocation AST nodes.
 */
object StrategyScriptCompiler {

    private val VALID_VAR_NAME_REGEX = Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")
    private val IDENT_SCAN_REGEX = Regex("[a-zA-Z_][a-zA-Z0-9_]*")
    private val IGNORED_GATE_KEYWORDS = setOf(
        "and", "or", "not", "UP", "DOWN", "NONE", "TRUE", "FALSE", "true", "false",
        "abs", "min", "max", "sqrt", "roll_mean", "roll_median", "ema", "hysteresis", "n_of_m"
    )
    private val COMPARISON_OPERATORS = listOf(">=", "<=", "==", "!=", ">", "<")
    private val OR_DELIMITERS = listOf(" or ", " || ")
    private val AND_DELIMITERS = listOf(" and ", " && ")

    private val REQUIRED_SECTIONS = listOf(
        "META",
        "EXECUTION",
        "VARIABLES",
        "SIGNAL_UP",
        "SIGNAL_DOWN",
        "GATES"
    )

    private val BUILTIN_IDENTIFIERS: Set<String> =
        DefaultStrategyScripts.BUILTIN_VARIABLE_DOCS.map { it.name }.toSet() +
            setOf("UP", "DOWN", "NONE", "TRUE", "FALSE", "true", "false")

    fun compile(sourceCode: String): DslCompileResult {
        val errors = mutableListOf<DslSyntaxError>()
        val seenSections = mutableSetOf<String>()
        var currentSection = ""

        val metaMap = mutableMapOf<String, String>()
        val execMap = mutableMapOf<String, String>()
        val variables = mutableListOf<DslVariableAssignment>()
        val declaredVars = mutableSetOf<String>()
        val signalUpRules = mutableListOf<DslConditionRule>()
        val signalDownRules = mutableListOf<DslConditionRule>()
        val gates = mutableListOf<DslGateDefinition>()

        val lines = sourceCode.lines()
        for ((idx, rawLine) in lines.withIndex()) {
            val lineNum = idx + 1
            val stripped = stripInlineComment(rawLine).trim()
            if (stripped.isEmpty()) continue

            if (stripped.startsWith("[") && stripped.endsWith("]")) {
                val secName = stripped.substring(1, stripped.length - 1).trim().uppercase()
                if (secName !in REQUIRED_SECTIONS) {
                    errors.add(
                        DslSyntaxError(
                            line = lineNum,
                            section = secName,
                            message = "Unknown section [$secName]. Allowed: ${REQUIRED_SECTIONS.joinToString { "[$it]" }}"
                        )
                    )
                } else {
                    seenSections.add(secName)
                }
                currentSection = secName
                continue
            }

            if (currentSection.isEmpty()) {
                errors.add(
                    DslSyntaxError(
                        line = lineNum,
                        section = "GLOBAL",
                        message = "Statement found outside of a section block. Start with [META]."
                    )
                )
                continue
            }

            when (currentSection) {
                "META", "EXECUTION" -> {
                    val eqIdx = stripped.indexOf('=')
                    if (eqIdx <= 0) {
                        errors.add(
                            DslSyntaxError(
                                line = lineNum,
                                section = currentSection,
                                message = "Expected `key = value` assignment in [$currentSection]."
                            )
                        )
                    } else {
                        val key = stripped.substring(0, eqIdx).trim().lowercase()
                        val rawVal = stripped.substring(eqIdx + 1).trim().removeSurrounding("\"")
                        if (currentSection == "META") {
                            metaMap[key] = rawVal
                        } else {
                            execMap[key] = rawVal
                        }
                    }
                }

                "VARIABLES" -> {
                    if (!stripped.startsWith("let ")) {
                        errors.add(
                            DslSyntaxError(
                                line = lineNum,
                                section = "VARIABLES",
                                message = "Variable declarations must start with `let <name> = <expression>`."
                            )
                        )
                    } else {
                        val rest = stripped.removePrefix("let ").trim()
                        val eqIdx = rest.indexOf('=')
                        if (eqIdx <= 0) {
                            errors.add(
                                DslSyntaxError(
                                    line = lineNum,
                                    section = "VARIABLES",
                                    message = "Missing `=` in `let` variable declaration."
                                )
                            )
                        } else {
                            val varName = rest.substring(0, eqIdx).trim()
                            val exprStr = rest.substring(eqIdx + 1).trim()
                            if (!VALID_VAR_NAME_REGEX.matches(varName)) {
                                errors.add(
                                    DslSyntaxError(
                                        line = lineNum,
                                        section = "VARIABLES",
                                        message = "Invalid variable identifier `$varName`."
                                    )
                                )
                            } else if (exprStr.isEmpty()) {
                                errors.add(
                                    DslSyntaxError(
                                        line = lineNum,
                                        section = "VARIABLES",
                                        message = "Empty expression for variable `$varName`."
                                    )
                                )
                            } else {
                                val compiledRes = compileMathExpressionResult(exprStr, declaredVars)
                                val err = compiledRes.exceptionOrNull()
                                if (err != null) {
                                    errors.add(DslSyntaxError(lineNum, "VARIABLES", err.message ?: "Invalid math expression"))
                                } else {
                                    declaredVars.add(varName)
                                    variables.add(
                                        DslVariableAssignment(
                                            line = lineNum,
                                            name = varName,
                                            rawExpression = exprStr,
                                            compiledExpr = compiledRes.getOrNull()
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                "SIGNAL_UP" -> {
                    val compiledRes = compileBooleanExpressionResult(stripped, declaredVars)
                    val err = compiledRes.exceptionOrNull()
                    if (err != null) {
                        errors.add(DslSyntaxError(lineNum, "SIGNAL_UP", err.message ?: "Invalid boolean expression"))
                    } else {
                        signalUpRules.add(DslConditionRule(lineNum, stripped, compiledRes.getOrNull()))
                    }
                }

                "SIGNAL_DOWN" -> {
                    val compiledRes = compileBooleanExpressionResult(stripped, declaredVars)
                    val err = compiledRes.exceptionOrNull()
                    if (err != null) {
                        errors.add(DslSyntaxError(lineNum, "SIGNAL_DOWN", err.message ?: "Invalid boolean expression"))
                    } else {
                        signalDownRules.add(DslConditionRule(lineNum, stripped, compiledRes.getOrNull()))
                    }
                }

                "GATES" -> {
                    if (!stripped.startsWith("gate ")) {
                        errors.add(
                            DslSyntaxError(
                                line = lineNum,
                                section = "GATES",
                                message = "Gate definitions must follow: `gate \"<Gate Name>\" : <boolean_condition>`"
                            )
                        )
                    } else {
                        val colonIdx = findColonOutsideQuotes(stripped)
                        if (colonIdx <= 5) {
                            errors.add(
                                DslSyntaxError(
                                    line = lineNum,
                                    section = "GATES",
                                    message = "Missing `:` separator in gate definition."
                                )
                            )
                        } else {
                            val gateTitle = stripped.substring(5, colonIdx).trim().removeSurrounding("\"")
                            val condStr = stripped.substring(colonIdx + 1).trim()
                            if (gateTitle.isEmpty()) {
                                errors.add(
                                    DslSyntaxError(
                                        line = lineNum,
                                        section = "GATES",
                                        message = "Gate name cannot be empty."
                                    )
                                )
                            } else {
                                val compiledRes = compileBooleanExpressionResult(condStr, declaredVars)
                                val err = compiledRes.exceptionOrNull()
                                if (err != null) {
                                    errors.add(DslSyntaxError(lineNum, "GATES", err.message ?: "Invalid gate condition"))
                                } else {
                                    val preExtractedIds = IDENT_SCAN_REGEX.findAll(condStr)
                                        .map { it.value }
                                        .filter { it !in IGNORED_GATE_KEYWORDS }
                                        .distinct()
                                        .take(2)
                                        .toList()
                                    gates.add(
                                        DslGateDefinition(
                                            line = lineNum,
                                            name = gateTitle,
                                            rawCondition = condStr,
                                            compiledBool = compiledRes.getOrNull(),
                                            primaryIdentifiers = preExtractedIds
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Enforce that all 6 sections exist so every strategy follows the exact same structure
        for (req in REQUIRED_SECTIONS) {
            if (req !in seenSections) {
                errors.add(
                    DslSyntaxError(
                        line = 1,
                        section = req,
                        message = "Missing required section [$req]. All strategies must contain ${REQUIRED_SECTIONS.joinToString { "[$it]" }}."
                    )
                )
            }
        }

        if (signalUpRules.isEmpty()) {
            errors.add(
                DslSyntaxError(
                    line = 1,
                    section = "SIGNAL_UP",
                    message = "[SIGNAL_UP] must define at least one boolean entry rule."
                )
            )
        }
        if (signalDownRules.isEmpty()) {
            errors.add(
                DslSyntaxError(
                    line = 1,
                    section = "SIGNAL_DOWN",
                    message = "[SIGNAL_DOWN] must define at least one boolean entry rule."
                )
            )
        }
        if (gates.isEmpty()) {
            errors.add(
                DslSyntaxError(
                    line = 1,
                    section = "GATES",
                    message = "[GATES] must define at least one `gate \"Name\" : <condition>` rule."
                )
            )
        }

        val name = metaMap["name"]?.takeIf { it.isNotBlank() } ?: "Unnamed_Strategy"
        val version = metaMap["version"]?.takeIf { it.isNotBlank() } ?: "1.0"
        val author = metaMap["author"]?.takeIf { it.isNotBlank() } ?: "User"
        val description = metaMap["description"]?.takeIf { it.isNotBlank() }
            ?: "Custom programmable .oqs strategy."

        val execution = DslExecutionConfig(
            durationSec = execMap["duration_sec"]?.toIntOrNull()?.coerceIn(5, 900)
                ?: HardLockedMandate.LOCKED_DURATION_SEC,
            stakeMode = execMap["stake_mode"]?.uppercase()?.takeIf {
                it == "PERCENT_BALANCE" || it == "FIXED_USD"
            } ?: "PERCENT_BALANCE",
            stakeValue = execMap["stake_value"]?.toDoubleOrNull()?.finiteOr(5.0)?.coerceIn(0.1, 4000.0)
                ?: HardLockedMandate.BALANCE_STAKE_PERCENT,
            minStakeUsd = execMap["min_stake_usd"]?.toDoubleOrNull()?.finiteOr(1.0)?.coerceIn(1.0, 4000.0)
                ?: HardLockedMandate.MIN_STAKE_USD,
            maxStakeUsd = execMap["max_stake_usd"]?.toDoubleOrNull()?.finiteOr(4000.0)?.coerceIn(1.0, 4000.0)
                ?: HardLockedMandate.MAX_STAKE_USD,
            cooldownMs = execMap["cooldown_ms"]?.toLongOrNull()?.coerceIn(500L, 120_000L)
                ?: HardLockedMandate.LOCKED_COOLDOWN_MS,
            minPayoutPct = execMap["min_payout_pct"]?.toIntOrNull()?.coerceIn(50, 98)
                ?: HardLockedMandate.MIN_PAYOUT_PERCENT,
            minConfidence = execMap["min_confidence"]?.toDoubleOrNull()?.finiteOr(62.0)?.coerceIn(1.0, 99.0)
                ?: HardLockedMandate.MIN_CONFIDENCE_REQUIRED,
            maxDailyTrades = execMap["max_daily_trades"]?.toIntOrNull()?.coerceIn(1, 200)
                ?: HardLockedMandate.MAX_TRADES_PER_DAY,
            maxConsecutiveLosses = execMap["max_consecutive_losses"]?.toIntOrNull()?.coerceIn(1, 25)
                ?: HardLockedMandate.MAX_CONSECUTIVE_LOSSES
        )

        val parsed = ParsedStrategyScript(
            name = name,
            version = version,
            author = author,
            description = description,
            execution = execution,
            variables = variables,
            signalUpRules = signalUpRules,
            signalDownRules = signalDownRules,
            gates = gates,
            rawSource = sourceCode
        )

        return DslCompileResult(
            isValid = errors.isEmpty(),
            errors = errors,
            parsedScript = parsed
        )
    }

    internal fun buildLiveSymbolTable(
        telemetry: MarketTelemetryState,
        rush: ModularStrategyEngine.RawTickRushMetrics,
        wsLatencyMs: Long,
        temporalEngine: TemporalIntelligenceEngine? = null
    ): MutableMap<String, Double> {
        val dirNumeric = when (rush.rushDirection) {
            SignalDirection.UP -> 1.0
            SignalDirection.DOWN -> -1.0
            SignalDirection.NONE -> 0.0
        }
        val temporal = telemetry.temporalTelemetry
        val map = HashMap<String, Double>(96)
        // Symbolic constants
        map["UP"] = 1.0
        map["DOWN"] = -1.0
        map["NONE"] = 0.0
        map["TRUE"] = 1.0
        map["FALSE"] = 0.0
        map["true"] = 1.0
        map["false"] = 0.0

        // Raw Tick & Rush variables
        map["rush_dir"] = dirNumeric
        map["rush_snr"] = rush.rushSnr.finiteOr(0.0)
        map["tick_purity"] = rush.purityPercent.finiteOr(0.0)
        map["tick_streak"] = rush.consecutiveStreak.toDouble()
        map["tip_aligned"] = if (rush.tipAligned) 1.0 else 0.0
        map["tick_speed"] = rush.tickSpeedTps.finiteOr(0.0)
        map["cleared_noise_mean"] = if (rush.clearedNoiseMean) 1.0 else 0.0
        map["single_tick_dominance"] = rush.singleTickDominancePercent.finiteOr(0.0)
        map["tick_convexity"] = rush.tickConvexity.finiteOr(1.0)
        map["arrival_compression"] = rush.arrivalCompressionRatio.finiteOr(1.0)
        map["pullback_recovery"] = rush.microPullbackRecovery.finiteOr(0.0)
        map["local_zscore_15"] = rush.localZScore15.finiteOr(0.0)
        map["ftrl_win_prob"] = if (rush.shadowSamplesLearned >= 30) rush.ftrlWinProbPercent.finiteOr(50.0) else 50.0
        map["shadow_win_rate"] = rush.rollingShadowWinRatePercent.finiteOr(0.0)
        map["shadow_samples"] = rush.shadowSamplesLearned.toDouble()
        map["avg_noise_step"] = rush.avgNoiseStep.finiteOr(1e-5)
        map["net_delta_4"] = rush.netDelta4.finiteOr(0.0)
        map["last_step_ratio"] = rush.lastStepRatio.finiteOr(0.0)

        // X13 Temporal Intelligence Built-In Variables
        map["calibrated_win_prob"] = temporal.calibratedWinProbPercent.finiteOr(50.0)
        map["eligibility_passed"] = if (temporal.eligibilityLayerPassed) 1.0 else 0.0
        map["signal_layer_score"] = temporal.signalLayerScore.finiteOr(0.0)
        map["validation_layer_score"] = temporal.validationLayerScore.finiteOr(0.0)
        map["snapshot_sync"] = if (temporal.snapshotSynchronized) 1.0 else 0.0
        map["tick_age_ms"] = temporal.tickAgeMs.toDouble()
        map["roll_snr_3"] = if (temporal.rollingSnrMean > 0.0) temporal.rollingSnrMean else rush.rushSnr.finiteOr(0.0)
        map["roll_purity_4"] = if (temporal.rollingPurityMean > 0.0) temporal.rollingPurityMean else rush.purityPercent.finiteOr(0.0)
        map["roll_ofi_median_5"] = temporal.rollingOfiMedian.finiteOr(telemetry.orderFlowImbalance.finiteOr(0.0))
        map["roll_impulse_4"] = if (temporal.rollingImpulseMean > 0.0) temporal.rollingImpulseMean else telemetry.microstructure.microImpulseScore.finiteOr(0.0)
        map["hyst_momentum"] = if (temporal.momentumHysteresisActive) 1.0 else 0.0
        map["hyst_purity"] = if (temporal.purityHysteresisActive) 1.0 else 0.0
        map["hyst_snr"] = if (temporal.snrHysteresisActive) 1.0 else 0.0
        map["persist_count_5"] = temporal.persistenceCount5.toDouble()
        map["noise_clean"] = if (temporal.noiseClean) 1.0 else 0.0
        map["regime_type"] = temporal.regimeType.ordinal.toDouble()
        map["regime_stable"] = if (temporal.regimeTransitionStable) 1.0 else 0.0
        map["regime_snr_thresh"] = temporal.regimeSnrThreshold.finiteOr(1.32)
        map["regime_purity_thresh"] = temporal.regimePurityThreshold.finiteOr(70.0)
        map["up_win_rate"] = temporal.upWinRatePercent.finiteOr(50.0)
        map["down_win_rate"] = temporal.downWinRatePercent.finiteOr(50.0)
        map["regime_win_rate"] = temporal.regimeWinRatePercent.finiteOr(50.0)
        map["gate_stability_pct"] = temporal.gateStabilityPercent.finiteOr(0.0)
        map["oos_expectancy_pct"] = temporal.oosExpectancyPercent.finiteOr(0.0)

        // Pre-populate rolling & hysteresis lookup keys for DSL functions `roll_mean(...)`, `roll_median(...)`, `ema(...)`, `hysteresis(...)`
        if (temporalEngine != null) {
            val tracked = listOf(
                "rush_snr", "tick_purity", "ofi", "ofi_imbalance",
                "micro_impulse_score", "tick_speed", "velocity",
                "single_tick_dominance", "ftrl_win_prob", "hv_score", "rel_pos", "net_delta_5"
            )
            for (series in tracked) {
                map["__roll_mean_$series"] = temporalEngine.rollingMean(series, 4)
                map["__roll_median_$series"] = temporalEngine.rollingMedian(series, 5)
                map["__ema_$series"] = temporalEngine.rollingEma(series, 4)
            }
        } else {
            map["__roll_mean_rush_snr"] = map["roll_snr_3"] ?: rush.rushSnr
            map["__roll_mean_tick_purity"] = map["roll_purity_4"] ?: rush.purityPercent
            map["__roll_median_ofi"] = map["roll_ofi_median_5"] ?: telemetry.orderFlowImbalance
            map["__roll_median_ofi_imbalance"] = map["roll_ofi_median_5"] ?: telemetry.orderFlowImbalance
            map["__roll_mean_micro_impulse_score"] = map["roll_impulse_4"] ?: telemetry.microstructure.microImpulseScore
        }
        map["__hyst_micro_impulse_score"] = map["hyst_momentum"] ?: 0.0
        map["__hyst_tick_purity"] = map["hyst_purity"] ?: 0.0
        map["__hyst_rush_snr"] = map["hyst_snr"] ?: 0.0

        // Market & Microstructure Telemetry variables
        map["price"] = telemetry.latestPrice.finiteOr(0.0)
        map["ofi"] = telemetry.orderFlowImbalance.finiteOr(0.0)
        map["ofi_imbalance"] = telemetry.orderFlowImbalance.finiteOr(0.0)
        map["velocity"] = rush.tickSpeedTps.finiteOr(0.0)
        map["tick_intensity"] = telemetry.tickIntensityRatio.finiteOr(1.0)
        map["micro_impulse_score"] = telemetry.microstructure.microImpulseScore.finiteOr(0.0)
        map["micro_efficiency"] = telemetry.microstructure.directionalEfficiency.finiteOr(0.0)
        map["micro_acceleration"] = telemetry.microstructure.microAcceleration.finiteOr(0.0)
        var resolvedZFast = telemetry.zFast.finiteOr(0.0)
        var resolvedZSlow = telemetry.zSlow.finiteOr(0.0)
        if (((resolvedZFast == 0.0 && resolvedZSlow == 0.0) || abs(resolvedZFast - resolvedZSlow) < 1e-12) &&
            telemetry.recentTicks.size >= 4
        ) {
            val ticks = telemetry.recentTicks
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
        map["z_fast"] = resolvedZFast
        map["z_slow"] = resolvedZSlow
        map["zlema_spread"] = (resolvedZFast - resolvedZSlow).finiteOr(0.0)
        map["volatility"] = telemetry.volatility.finiteOr(0.0)
        map["consistency"] = telemetry.consistency.finiteOr(0.0)
        map["persistence"] = telemetry.persistence.toDouble()
        map["slope_ratio"] = telemetry.slopeRatio.finiteOr(0.0)
        map["rel_pos"] = telemetry.relPos.finiteOr(50.0)
        map["hvoi_score"] = telemetry.hvoiScore.finiteOr(0.0)
        map["sr_distance"] = telemetry.srDistance.finiteOr(999.0)
        map["hmm_state"] = (telemetry.hmmOutput?.state ?: 0).toDouble()
        map["hmm_confidence"] = ((telemetry.hmmOutput?.confidence ?: 0.5) * 100.0).finiteOr(50.0)
        map["ws_latency_ms"] = wsLatencyMs.toDouble()
        map["payout_pct"] = telemetry.assetPayoutPercent.toDouble()
        map["sentiment_pct"] = telemetry.bullishSentimentPercent.toDouble()
        map["trade_duration_sec"] = telemetry.selectedTimeframeSec.toDouble()

        val hvFilter = telemetry.highVoltageStatus
        val hvWin = telemetry.hvWindow
        val zlemaTrendNumeric = when {
            resolvedZFast > resolvedZSlow + 1e-10 -> 1.0
            resolvedZFast < resolvedZSlow - 1e-10 -> -1.0
            telemetry.trend == SignalDirection.UP -> 1.0
            telemetry.trend == SignalDirection.DOWN -> -1.0
            else -> dirNumeric
        }
        map["zlema_trend_dir"] = zlemaTrendNumeric
        map["net_delta_5"] = telemetry.netDelta5.finiteOr(hvFilter.netDelta5)
        map["sr_wall_clear"] = if (hvFilter.srObstaclePassed) 1.0 else 0.0
        map["sr_ahead_dist"] = hvFilter.srClearanceAhead.finiteOr(telemetry.srDistance.finiteOr(999.0))
        map["sr_min_clearance"] = hvFilter.srMinClearanceRequired.finiteOr(
            if (telemetry.latestPrice in 0.01..15.0) 0.00025 else 0.25
        )
        map["candle_range_ratio"] = hvFilter.candleRangeRatio.finiteOr(1.0)
        map["vol_compressed"] = if (hvFilter.isCompressed) 1.0 else 0.0
        map["vol_exhausted"] = if (hvFilter.isExhausted) 1.0 else 0.0
        map["vol_regime_ok"] = if (hvFilter.volatilityFilterPassed) 1.0 else 0.0
        map["opportunity_dir"] = when (hvFilter.opportunityDirection) {
            SignalDirection.UP -> 1.0
            SignalDirection.DOWN -> -1.0
            SignalDirection.NONE -> zlemaTrendNumeric
        }
        map["continuation_ready"] = if (hvFilter.continuationReady) 1.0 else 0.0
        map["sr_bounce_ready"] = if (hvFilter.srBounceReady) 1.0 else 0.0
        map["mean_rev_ready"] = if (hvFilter.meanReversionReady) 1.0 else 0.0
        map["any_setup_ready"] = if (hvFilter.allSixFiltersPassed) 1.0 else 0.0
        map["playbook_type"] = when (hvFilter.activePlaybook) {
            "CONTINUATION" -> 1.0
            "SR_BOUNCE" -> 2.0
            "MEAN_REVERSION" -> 3.0
            else -> 0.0
        }
        map["opportunity_score"] = hvFilter.opportunityScore.finiteOr(0.0)
        map["support_dist"] = hvFilter.supportDistance.finiteOr(999.0)
        map["resistance_dist"] = hvFilter.resistanceDistance.finiteOr(999.0)

        // HV Window Structural Co-Pilot Built-In Variables
        map["hv_score"] = hvWin.score.finiteOr(0.0)
        map["hv_raw_score"] = hvWin.rawScore.finiteOr(0.0)
        map["hv_state"] = when (hvWin.state) {
            com.olympquant.terminal.domain.HvWindowState.EXCELLENT -> 2.0
            com.olympquant.terminal.domain.HvWindowState.MODERATE -> 1.0
            com.olympquant.terminal.domain.HvWindowState.AVOID -> 0.0
        }
        map["hv_tight_chop"] = if (hvWin.tightChopDetected) 1.0 else 0.0
        map["hv_crazy_whipsaw"] = if (hvWin.crazyWhipsawDetected) 1.0 else 0.0
        map["hv_wick_ratio"] = hvWin.wickCollapseRatioPercent.finiteOr(0.0)
        map["hv_body_ratio"] = hvWin.solidBodyRatioPercent.finiteOr(0.0)
        map["hv_anchor_range_60s"] = hvWin.anchorRange60s.finiteOr(0.0)
        map["hv_mtf_aligned"] = hvWin.mtfAlignedCount.toDouble()
        map["hv_stability_sec"] = hvWin.stabilitySeconds.finiteOr(0.0)

        return map
    }

    fun evaluateVariablesAndSignals(
        script: ParsedStrategyScript,
        symbols: MutableMap<String, Double>,
        temporalEngine: TemporalIntelligenceEngine? = null
    ): ScriptRuntimeEvaluation {
        // 1. Evaluate [VARIABLES] sequentially via pre-compiled AST nodes (fallback to parser only if uncompiled)
        for (i in script.variables.indices) {
            val v = script.variables[i]
            val value = if (v.compiledExpr != null) {
                v.compiledExpr.eval(symbols, temporalEngine).finiteOr(0.0)
            } else {
                runCatching { evaluateMathExpression(v.rawExpression, symbols) }
                    .getOrDefault(0.0)
                    .finiteOr(0.0)
            }
            symbols[v.name] = value
        }

        // 2. Evaluate [SIGNAL_UP] and [SIGNAL_DOWN] via pre-compiled AST nodes
        val upPassed = script.signalUpRules.isNotEmpty() && script.signalUpRules.all { rule ->
            rule.compiledBool?.eval(symbols, temporalEngine)
                ?: runCatching { evaluateBooleanCondition(rule.rawCondition, symbols) }.getOrDefault(false)
        }
        val downPassed = script.signalDownRules.isNotEmpty() && script.signalDownRules.all { rule ->
            rule.compiledBool?.eval(symbols, temporalEngine)
                ?: runCatching { evaluateBooleanCondition(rule.rawCondition, symbols) }.getOrDefault(false)
        }

        val rawSignal = when {
            upPassed && !downPassed -> SignalDirection.UP
            downPassed && !upPassed -> SignalDirection.DOWN
            upPassed && downPassed -> {
                if ((symbols["rush_dir"] ?: 0.0) >= 0.0) SignalDirection.UP else SignalDirection.DOWN
            }
            else -> SignalDirection.NONE
        }

        // 3. Evaluate [GATES] and build FilterDiagnostic rows using pre-extracted identifiers & X13 Temporal Stability tracking
        val filterTypes = FilterType.entries
        val gateDiagnostics = ArrayList<FilterDiagnostic>(script.gates.size)
        for (idx in script.gates.indices) {
            val gate = script.gates[idx]
            val rawPassed = gate.compiledBool?.eval(symbols, temporalEngine)
                ?: runCatching { evaluateBooleanCondition(gate.rawCondition, symbols) }.getOrDefault(false)
            // Apply X13 Temporal Hold Latch only to L2 signal gates (never to L1 hard-rejection, HighVoltage strict filters, or HV Window gates)
            val isHardRejectionGate = gate.name.contains("L1:", ignoreCase = true) ||
                gate.name.contains("Spike", ignoreCase = true) ||
                gate.name.contains("Latency", ignoreCase = true) ||
                gate.name.contains("Eligibility", ignoreCase = true) ||
                gate.name.contains("Filter ", ignoreCase = true) ||
                gate.name.contains("HV Window", ignoreCase = true)
            val passed = if (temporalEngine != null) {
                temporalEngine.recordGatePassAndCheckHold(
                    gateName = "${script.name}_${gate.name}",
                    rawPassed = rawPassed,
                    holdTicksOnDip = if (isHardRejectionGate) 0 else 1
                )
            } else {
                rawPassed
            }
            val latchedSuffix = if (passed && !rawPassed) " [X13 HOLD LATCH]" else ""
            val mappedType = filterTypes[idx % filterTypes.size]
            val primaryVarValue = formatPreExtractedIdentifiers(gate.primaryIdentifiers, symbols)
            gateDiagnostics.add(
                FilterDiagnostic(
                    filterType = mappedType,
                    passed = passed,
                    actualValueFormatted = primaryVarValue,
                    thresholdFormatted = gate.rawCondition,
                    reason = if (passed) {
                        "PASS$latchedSuffix • ${gate.name} ($primaryVarValue)"
                    } else {
                        "BLOCKED • ${gate.name}: condition `${gate.rawCondition}` not met ($primaryVarValue)"
                    },
                    gateTitle = gate.name
                )
            )
        }

        return ScriptRuntimeEvaluation(
            rawSignal = rawSignal,
            upSignalMatched = upPassed,
            downSignalMatched = downPassed,
            gateDiagnostics = gateDiagnostics,
            evaluatedSymbols = symbols
        )
    }

    data class ScriptRuntimeEvaluation(
        val rawSignal: SignalDirection,
        val upSignalMatched: Boolean,
        val downSignalMatched: Boolean,
        val gateDiagnostics: List<FilterDiagnostic>,
        val evaluatedSymbols: Map<String, Double>
    )

    private fun formatPreExtractedIdentifiers(identifiers: List<String>, symbols: Map<String, Double>): String {
        if (identifiers.isEmpty()) return "eval"
        val sb = StringBuilder(28)
        for (i in identifiers.indices) {
            val name = identifiers[i]
            if (!symbols.containsKey(name)) continue
            if (sb.isNotEmpty()) sb.append(" • ")
            val v = symbols[name] ?: 0.0
            if (name == "rush_dir" || name == "zlema_trend_dir") {
                val dirLabel = when {
                    v > 0.5 -> "$name=UP"
                    v < -0.5 -> "$name=DOWN"
                    else -> "$name=NONE"
                }
                sb.append(dirLabel)
            } else {
                sb.append(name).append('=').append(formatCompactNumber(v))
            }
        }
        return if (sb.isEmpty()) "eval" else sb.toString()
    }

    private fun formatCompactNumber(v: Double): String {
        val safe = v.finiteOr(0.0)
        return when {
            abs(safe - safe.roundToLong().toDouble()) < 1e-6 -> "${safe.roundToLong()}"
            abs(safe) >= 10.0 -> "%.1f".format(safe)
            abs(safe) >= 0.1 -> "%.2f".format(safe)
            else -> "%.4f".format(safe)
        }
    }

    private fun stripInlineComment(line: String): String {
        var inQuotes = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuotes = !inQuotes
            if (!inQuotes && c == '#') {
                return line.substring(0, i)
            }
            if (!inQuotes && c == '/' && i + 1 < line.length && line[i + 1] == '/') {
                return line.substring(0, i)
            }
        }
        return line
    }

    private fun compileMathExpressionResult(expr: String, declaredVars: Set<String>): Result<CompiledMathExpr> {
        return runCatching {
            val knownSet = BUILTIN_IDENTIFIERS + declaredVars
            val compiler = MathAstCompiler(expr, knownSet, strictUnknownCheck = true)
            val ast = compiler.parseExpression()
            compiler.skipWhitespace()
            if (compiler.pos < compiler.input.length) {
                throw IllegalArgumentException("Unexpected token `${compiler.input.substring(compiler.pos)}` in `$expr`")
            }
            ast
        }
    }

    private fun compileBooleanExpressionResult(expr: String, declaredVars: Set<String>): Result<CompiledBoolExpr> {
        if (expr.isBlank()) return Result.failure(IllegalArgumentException("Empty boolean condition."))
        return runCatching {
            val knownSet = BUILTIN_IDENTIFIERS + declaredVars
            compileBooleanAst(expr, knownSet, strictUnknownCheck = true)
        }
    }

    private fun findColonOutsideQuotes(line: String): Int {
        var inQuotes = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuotes = !inQuotes
            else if (!inQuotes && c == ':') return i
        }
        return -1
    }

    private fun compileBooleanAst(
        condition: String,
        knownVars: Set<String>,
        strictUnknownCheck: Boolean
    ): CompiledBoolExpr {
        val trimmed = removeOuterParentheses(condition.trim())

        val orParts = splitTopLevelLogical(trimmed, OR_DELIMITERS)
        if (orParts.size > 1) {
            return CompiledBoolExpr.Or(orParts.map { compileBooleanAst(it, knownVars, strictUnknownCheck) })
        }

        val andParts = splitTopLevelLogical(trimmed, AND_DELIMITERS)
        if (andParts.size > 1) {
            return CompiledBoolExpr.And(andParts.map { compileBooleanAst(it, knownVars, strictUnknownCheck) })
        }

        if (trimmed.startsWith("not ", ignoreCase = true) || trimmed.startsWith("!")) {
            val inner = if (trimmed.startsWith("not ", ignoreCase = true)) trimmed.substring(4).trim() else trimmed.removePrefix("!").trim()
            return CompiledBoolExpr.Not(compileBooleanAst(inner, knownVars, strictUnknownCheck))
        }

        var opFound: String? = null
        var opIndex = -1
        var depth = 0
        var i = 0
        while (i < trimmed.length) {
            val ch = trimmed[i]
            if (ch == '(') depth++
            else if (ch == ')') depth = (depth - 1).coerceAtLeast(0)
            else if (depth == 0) {
                val matched = COMPARISON_OPERATORS.firstOrNull { op ->
                    trimmed.regionMatches(i, op, 0, op.length)
                }
                if (matched != null) {
                    opFound = matched
                    opIndex = i
                    break
                }
            }
            i++
        }

        if (opFound == null || opIndex <= 0) {
            if (strictUnknownCheck) {
                throw IllegalArgumentException("Expected comparison operator (>=, <=, ==, !=, >, <) in `$trimmed`")
            }
            val mathCompiler = MathAstCompiler(trimmed, knownVars, strictUnknownCheck)
            return CompiledBoolExpr.MathTruthy(mathCompiler.parseExpression())
        }

        val leftStr = trimmed.substring(0, opIndex).trim()
        val rightStr = trimmed.substring(opIndex + opFound.length).trim()
        if (leftStr.isEmpty() || rightStr.isEmpty()) {
            throw IllegalArgumentException("Incomplete comparison around `$opFound` in `$trimmed`")
        }

        val leftCompiler = MathAstCompiler(leftStr, knownVars, strictUnknownCheck)
        val leftAst = leftCompiler.parseExpression()
        leftCompiler.skipWhitespace()
        if (leftCompiler.pos < leftCompiler.input.length) {
            throw IllegalArgumentException("Unexpected token `${leftCompiler.input.substring(leftCompiler.pos)}` in `$leftStr`")
        }

        val rightCompiler = MathAstCompiler(rightStr, knownVars, strictUnknownCheck)
        val rightAst = rightCompiler.parseExpression()
        rightCompiler.skipWhitespace()
        if (rightCompiler.pos < rightCompiler.input.length) {
            throw IllegalArgumentException("Unexpected token `${rightCompiler.input.substring(rightCompiler.pos)}` in `$rightStr`")
        }

        return CompiledBoolExpr.Comparison(opFound, leftAst, rightAst)
    }

    internal fun evaluateBooleanCondition(
        condition: String,
        symbols: Map<String, Double>,
        strictUnknownCheck: Boolean = false
    ): Boolean {
        val ast = compileBooleanAst(condition, symbols.keys, strictUnknownCheck)
        return ast.eval(symbols)
    }

    private fun splitTopLevelLogical(input: String, delimiters: List<String>): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var lastSplit = 0
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '(') depth++
            else if (c == ')') depth = (depth - 1).coerceAtLeast(0)
            else if (depth == 0) {
                val matched = delimiters.firstOrNull { delim ->
                    input.regionMatches(i, delim, 0, delim.length, ignoreCase = true)
                }
                if (matched != null) {
                    result.add(input.substring(lastSplit, i))
                    i += matched.length
                    lastSplit = i
                    continue
                }
            }
            i++
        }
        if (lastSplit == 0) return listOf(input)
        result.add(input.substring(lastSplit))
        return result
    }

    private fun removeOuterParentheses(s: String): String {
        var cur = s
        while (cur.startsWith("(") && cur.endsWith(")")) {
            var depth = 0
            var wrapsAll = true
            for (i in 0 until cur.length - 1) {
                if (cur[i] == '(') depth++
                else if (cur[i] == ')') depth--
                if (depth == 0) {
                    wrapsAll = false
                    break
                }
            }
            if (wrapsAll) {
                cur = cur.substring(1, cur.length - 1).trim()
            } else {
                break
            }
        }
        return cur
    }

    internal fun evaluateMathExpression(
        expr: String,
        symbols: Map<String, Double>,
        strictUnknownCheck: Boolean = false
    ): Double {
        val compiler = MathAstCompiler(expr, symbols.keys, strictUnknownCheck)
        val ast = compiler.parseExpression()
        compiler.skipWhitespace()
        if (compiler.pos < compiler.input.length) {
            throw IllegalArgumentException("Unexpected token `${compiler.input.substring(compiler.pos)}` in `$expr`")
        }
        return ast.eval(symbols).finiteOr(0.0)
    }

    private class MathAstCompiler(
        val input: String,
        val knownSymbols: Set<String>,
        val strictUnknownCheck: Boolean
    ) {
        var pos = 0

        fun skipWhitespace() {
            while (pos < input.length && input[pos].isWhitespace()) pos++
        }

        fun parseExpression(): CompiledMathExpr {
            var x = parseTerm()
            while (true) {
                skipWhitespace()
                if (pos < input.length && (input[pos] == '+' || input[pos] == '-')) {
                    val op = input[pos]
                    pos++
                    x = CompiledMathExpr.BinaryOp(op, x, parseTerm())
                } else {
                    return x
                }
            }
        }

        private fun parseTerm(): CompiledMathExpr {
            var x = parseFactor()
            while (true) {
                skipWhitespace()
                if (pos < input.length && (input[pos] == '*' || input[pos] == '/')) {
                    val op = input[pos]
                    pos++
                    x = CompiledMathExpr.BinaryOp(op, x, parseFactor())
                } else {
                    return x
                }
            }
        }

        private fun parseFactor(): CompiledMathExpr {
            skipWhitespace()
            if (pos < input.length && input[pos] == '+') {
                pos++
                return parseFactor()
            }
            if (pos < input.length && input[pos] == '-') {
                pos++
                return CompiledMathExpr.UnaryNeg(parseFactor())
            }
            if (pos < input.length && input[pos] == '(') {
                pos++
                val x = parseExpression()
                skipWhitespace()
                if (pos >= input.length || input[pos] != ')') {
                    throw IllegalArgumentException("Missing closing `)` in expression `$input`")
                }
                pos++
                return x
            }

            val start = pos
            if (pos < input.length && (input[pos].isDigit() || input[pos] == '.')) {
                while (pos < input.length && (input[pos].isDigit() || input[pos] == '.' || input[pos] == 'e' || input[pos] == 'E')) {
                    pos++
                }
                val numStr = input.substring(start, pos)
                val num = numStr.toDoubleOrNull()
                    ?: throw IllegalArgumentException("Invalid number `$numStr`")
                return CompiledMathExpr.Literal(num)
            }

            if (pos < input.length && (input[pos].isLetter() || input[pos] == '_')) {
                while (pos < input.length && (input[pos].isLetterOrDigit() || input[pos] == '_')) {
                    pos++
                }
                val ident = input.substring(start, pos)
                skipWhitespace()
                if (pos < input.length && input[pos] == '(') {
                    pos++ // consume '('
                    val arg1 = parseExpression()
                    skipWhitespace()
                    return when (val fnLower = ident.lowercase()) {
                        "abs", "sqrt" -> {
                            expectClosingParen(ident)
                            CompiledMathExpr.Func1(fnLower, arg1)
                        }
                        "min", "max", "roll_mean", "roll_median", "ema" -> {
                            expectComma(ident)
                            val arg2 = parseExpression()
                            expectClosingParen(ident)
                            CompiledMathExpr.Func2(fnLower, arg1, arg2)
                        }
                        "hysteresis", "n_of_m" -> {
                            expectComma(ident)
                            val arg2 = parseExpression()
                            expectComma(ident)
                            val arg3 = parseExpression()
                            expectClosingParen(ident)
                            CompiledMathExpr.Func3(fnLower, arg1, arg2, arg3)
                        }
                        else -> throw IllegalArgumentException(
                            "Unknown function `$ident()`. Supported: abs(x), sqrt(x), min(a,b), max(a,b), roll_mean(v,w), roll_median(v,w), ema(v,w), hysteresis(v,enter,exit), n_of_m(v,n,m)"
                        )
                    }
                }

                if (ident in knownSymbols) {
                    return CompiledMathExpr.Variable(ident)
                }
                if (strictUnknownCheck) {
                    throw IllegalArgumentException(
                        "Unknown variable `$ident`. Define it with `let $ident = ...` in [VARIABLES] or use a built-in variable."
                    )
                }
                return CompiledMathExpr.Variable(ident)
            }

            throw IllegalArgumentException("Unexpected syntax at position $pos in `$input`")
        }

        private fun expectComma(fnName: String) {
            skipWhitespace()
            if (pos >= input.length || input[pos] != ',') {
                throw IllegalArgumentException("Function `$fnName(a, b)` requires two comma-separated arguments.")
            }
            pos++
        }

        private fun expectClosingParen(fnName: String) {
            skipWhitespace()
            if (pos >= input.length || input[pos] != ')') {
                throw IllegalArgumentException("Missing closing `)` for function `$fnName(...)`.")
            }
            pos++
        }
    }
}

