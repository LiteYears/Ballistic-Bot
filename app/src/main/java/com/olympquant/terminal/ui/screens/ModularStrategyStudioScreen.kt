package com.olympquant.terminal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olympquant.terminal.domain.FilterDiagnostic
import com.olympquant.terminal.domain.HardLockedMandate
import com.olympquant.terminal.domain.MarketTelemetryState
import com.olympquant.terminal.domain.SignalDirection
import com.olympquant.terminal.domain.StrategyConfig
import com.olympquant.terminal.domain.TradeTimeframe
import com.olympquant.terminal.domain.finiteOr
import com.olympquant.terminal.engine.DefaultStrategyScripts
import com.olympquant.terminal.engine.DslSyntaxError
import com.olympquant.terminal.engine.StrategyScriptCompiler
import com.olympquant.terminal.ui.theme.BearishCrimson
import com.olympquant.terminal.ui.theme.BullishGreen
import com.olympquant.terminal.ui.theme.ElectricCyan
import com.olympquant.terminal.ui.theme.JetBrainsMonoFamily
import com.olympquant.terminal.ui.theme.ObsidianBackground
import com.olympquant.terminal.ui.theme.SlateBorder
import com.olympquant.terminal.ui.theme.SlateSurface
import com.olympquant.terminal.ui.theme.SlateSurfaceVariant
import com.olympquant.terminal.ui.theme.TextMuted
import com.olympquant.terminal.ui.theme.TextPrimary
import com.olympquant.terminal.ui.theme.TextSecondary
import com.olympquant.terminal.ui.theme.WarningAmber

private val DeepObsidian = ObsidianBackground

private const val OQS_SIX_BLOCK_TEMPLATE_SNIPPET = """[META]
name = "Custom_Strategy_5s"
version = "2.0"
author = "OlympQuant_X13"
description = "Standard 6-block OlympQuant strategy script."

[EXECUTION]
duration_sec = 5
stake_mode = "PERCENT_BALANCE"
stake_value = 5.0
min_stake_usd = 1.0
max_stake_usd = 4000.0
cooldown_ms = 4000
min_payout_pct = 75
min_confidence = 62.0
max_daily_trades = 10
max_consecutive_losses = 3

[VARIABLES]
let snr_smooth = roll_mean(rush_snr, 3)
let purity_latch = hysteresis(tick_purity, 70.0, 56.0)

[SIGNAL_UP]
rush_dir == UP
eligibility_passed == 1

[SIGNAL_DOWN]
rush_dir == DOWN
eligibility_passed == 1

[GATES]
gate "L1 Market Eligibility" : eligibility_passed == 1
gate "L2 Temporal Signal" : snr_smooth >= 1.25 and purity_latch == 1"""

private const val OQS_X13_FUNCTIONS_SNIPPET = """# X13 Temporal Functions & Operators
let smooth_snr    = roll_mean(rush_snr, 3)
let median_ofi    = roll_median(ofi, 5)
let fast_ema_vel  = ema(tick_speed, 4)
let mom_latched   = hysteresis(signal_layer_score, 65.0, 50.0)
let tip_confirmed = n_of_m(tip_aligned, 3, 5)"""

private fun buildCompleteDocumentationText(): String = buildString {
    appendLine("==================================================================")
    appendLine("OLYMPQUANT SCRIPT (.OQS) — COMPLETE LANGUAGE & VARIABLE REFERENCE")
    appendLine("==================================================================")
    appendLine()
    appendLine("1. MANDATORY 6-BLOCK STRUCTURE")
    appendLine("Every .oqs strategy file must declare these 6 sections in order:")
    appendLine("  [META]        -> name, version, author, description")
    appendLine("  [EXECUTION]   -> duration_sec, stake_mode, stake_value, min_stake_usd,")
    appendLine("                   max_stake_usd, cooldown_ms, min_payout_pct, min_confidence,")
    appendLine("                   max_daily_trades, max_consecutive_losses")
    appendLine("  [VARIABLES]   -> `let <identifier> = <expression>`")
    appendLine("  [SIGNAL_UP]   -> Boolean conditions (all must pass for CALL / UP)")
    appendLine("  [SIGNAL_DOWN] -> Boolean conditions (all must pass for PUT / DOWN)")
    appendLine("  [GATES]       -> `gate \"<Title>\" : <boolean_expression>`")
    appendLine()
    appendLine("2. SUPPORTED OPERATORS & X13 TEMPORAL FUNCTIONS")
    appendLine("  - Comparisons : >=, <=, >, <, ==, !=, and, or")
    appendLine("  - Math        : +, -, *, /, abs(x), sqrt(x), min(a, b), max(a, b)")
    appendLine("  - Temporal    :")
    appendLine("      roll_mean(var, window)          : Rolling mean over `window` observations (2..16)")
    appendLine("      roll_median(var, window)        : Outlier-resistant median over `window` observations")
    appendLine("      ema(var, window)                : Exponential moving average over `window`")
    appendLine("      hysteresis(var, enter, exit)    : Schmitt-trigger latch (1.0 when active, 0.0 when idle)")
    appendLine("      n_of_m(var, n, m)               : 1.0 if `var` was non-zero in >= `n` of last `m` frames")
    appendLine()
    appendLine("3. STANDARD 6-BLOCK TEMPLATE")
    appendLine(OQS_SIX_BLOCK_TEMPLATE_SNIPPET)
    appendLine()
    appendLine("4. BUILT-IN LIVE TELEMETRY VARIABLES (${DefaultStrategyScripts.BUILTIN_VARIABLE_DOCS.size} SYMBOLS)")
    DefaultStrategyScripts.BUILTIN_VARIABLE_DOCS.forEach { doc ->
        appendLine("  - ${doc.name.padEnd(24)} [${doc.unit.padEnd(8)}] : ${doc.description}")
    }
}

private fun buildCompleteDiagnosticsText(
    strategyName: String,
    isValid: Boolean,
    errors: List<DslSyntaxError>,
    triggerReason: String?,
    diagnostics: List<FilterDiagnostic>
): String = buildString {
    appendLine("==================================================================")
    appendLine("OLYMPQUANT X13 — COMPILER & GATE DIAGNOSTICS REPORT")
    appendLine("Strategy : $strategyName.oqs")
    appendLine("Status   : ${if (isValid) "VALID AST" else "${errors.size} COMPILER ERROR(S)"}")
    appendLine("==================================================================")
    if (errors.isNotEmpty()) {
        appendLine()
        appendLine("[COMPILATION ERRORS]")
        errors.forEachIndexed { idx, err ->
            appendLine("  ${idx + 1}. Line ${err.line} [${err.section}]: ${err.message}")
        }
    }
    if (!triggerReason.isNullOrBlank()) {
        appendLine()
        appendLine("[SIGNAL SUMMARY]")
        appendLine("  $triggerReason")
    }
    if (diagnostics.isNotEmpty()) {
        appendLine()
        appendLine("[LIVE GATE DIAGNOSTICS (${diagnostics.count { it.passed }}/${diagnostics.size} PASSED)]")
        diagnostics.forEachIndexed { idx, d ->
            val state = if (d.passed) "PASS" else "FAIL"
            appendLine("  ${idx + 1}. [$state] ${d.gateTitle}")
            appendLine("     Actual    : ${d.actualValueFormatted}")
            appendLine("     Threshold : ${d.thresholdFormatted}")
            appendLine("     Reason    : ${d.reason}")
        }
    }
}

@Composable
private fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = TextPrimary,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

@Composable
fun ModularStrategyStudioScreen(
    telemetry: MarketTelemetryState,
    activeBalance: Double,
    strategies: List<StrategyConfig> = listOf(HardLockedMandate.highVoltageStrategyConfig()),
    wsLatencyMs: Long = 0L,
    selectedTimeframeSec: Int = 5,
    onSelectTimeframe: (Int) -> Unit = {},
    onCreateStrategyFromTemplate: (String, (StrategyConfig) -> Unit) -> Unit = { _, _ -> },
    onSaveAndLoadStrategy: (Long, String, (Result<StrategyConfig>) -> Unit) -> Unit = { _, _, _ -> },
    onLoadStrategy: (Long) -> Unit = {},
    onDeleteStrategy: (Long) -> Unit = {},
    onResetDefaultStrategy: ((StrategyConfig) -> Unit) -> Unit = {}
) {
    val clipboardManager = LocalClipboardManager.current
    val loadedStrategy = remember(strategies) {
        strategies.firstOrNull { it.isEnabled }
            ?: strategies.firstOrNull()
            ?: HardLockedMandate.highVoltageStrategyConfig()
    }
    val activeTf = TradeTimeframe.fromSeconds(
        selectedTimeframeSec.takeIf { it > 0 } ?: loadedStrategy.durationSeconds
    )

    var selectedStrategyId by rememberSaveable { mutableLongStateOf(loadedStrategy.id) }
    val selectedStrategy = remember(strategies, selectedStrategyId, loadedStrategy) {
        strategies.firstOrNull { it.id == selectedStrategyId } ?: loadedStrategy
    }

    var editorText by rememberSaveable(selectedStrategy.id) {
        mutableStateOf(selectedStrategy.scriptCode.ifBlank { DefaultStrategyScripts.HIGH_VOLTAGE_OQS_SCRIPT })
    }
    var statusBanner by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var copyConfirmationMessage by remember { mutableStateOf<String?>(null) }
    var showNewStrategyDialog by remember { mutableStateOf(false) }
    var newStrategyNameInput by remember { mutableStateOf("My_HighVoltage_${activeTf.label}") }
    var showVariableReference by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(selectedStrategy.id, selectedStrategy.scriptCode) {
        val targetCode = selectedStrategy.scriptCode.ifBlank { DefaultStrategyScripts.HIGH_VOLTAGE_OQS_SCRIPT }
        if (editorText != targetCode) {
            editorText = targetCode
        }
    }

    fun copyToClipboardWithFeedback(content: String, confirmationLabel: String) {
        clipboardManager.setText(AnnotatedString(content))
        copyConfirmationMessage = confirmationLabel
        statusBanner = confirmationLabel
        statusIsError = false
    }

    val compiledPreview by remember(editorText) {
        derivedStateOf { StrategyScriptCompiler.compile(editorText) }
    }

    val errorLines by remember(compiledPreview) {
        derivedStateOf { compiledPreview.errors.map { it.line }.toSet() }
    }

    val liveSymbolValues by remember(telemetry, wsLatencyMs, editorText) {
        derivedStateOf {
            val base = mutableMapOf<String, Double>()
            val hv = telemetry.hvWindow
            val hvs = telemetry.highVoltageStatus
            val t = telemetry.temporalTelemetry
            val m = telemetry.microstructure
            base["hv_score"] = hv.score
            base["hv_raw_score"] = hv.rawScore
            base["hv_chop"] = if (hv.tightChopDetected) 1.0 else 0.0
            base["hv_whipsaw"] = if (hv.crazyWhipsawDetected) 1.0 else 0.0
            base["hv_wick_ratio"] = hv.wickCollapseRatioPercent
            base["hv_body_ratio"] = hv.solidBodyRatioPercent
            base["hv_anchor_range_60s"] = hv.anchorRange60s
            base["hv_mtf_aligned"] = hv.mtfAlignedCount.toDouble()
            base["hv_stability_sec"] = hv.stabilitySeconds
            base["hv_ready"] = if (hv.stabilityReady) 1.0 else 0.0
            base["zlema_fast"] = telemetry.zFast
            base["zlema_slow"] = telemetry.zSlow
            base["zlema_spread"] = telemetry.zFast - telemetry.zSlow
            base["zlema_trend_dir"] = when (hvs.trendDirection) {
                SignalDirection.UP -> 1.0
                SignalDirection.DOWN -> -1.0
                SignalDirection.NONE -> 0.0
            }
            base["rel_pos"] = telemetry.relPos
            base["net_delta_5"] = telemetry.netDelta5
            base["sr_clearance"] = hvs.srClearanceAhead
            base["sr_min_clearance"] = hvs.srMinClearanceRequired
            base["sr_wall_clear"] = if (hvs.srObstaclePassed) 1.0 else 0.0
            base["candle_range_ratio"] = hvs.candleRangeRatio
            base["vol_regime_ok"] = if (hvs.volatilityFilterPassed) 1.0 else 0.0
            base["hv_all_6_passed"] = if (hvs.allSixFiltersPassed) 1.0 else 0.0
            base["price"] = telemetry.latestPrice
            base["payout_pct"] = telemetry.assetPayoutPercent.toDouble()
            base["asset_locked"] = if (telemetry.assetLocked) 1.0 else 0.0
            base["ws_latency_ms"] = wsLatencyMs.toDouble()
            base["ofi"] = telemetry.orderFlowImbalance
            base["tick_intensity"] = telemetry.tickIntensityRatio
            base["sentiment_bull_pct"] = telemetry.bullishSentimentPercent.toDouble()
            base["velocity"] = telemetry.velocity
            base["volatility"] = telemetry.volatility
            base["consistency"] = telemetry.consistency
            base["hvoi_score"] = telemetry.hvoiScore
            base["sr_distance"] = telemetry.srDistance
            base["corridor_width"] = telemetry.adaptiveCorridorWidth
            base["tape_speed"] = m.tapeSpeed.toDouble()
            base["kaufman_er"] = m.directionalEfficiency
            base["impulse_score"] = m.microImpulseScore
            base["streak_count"] = m.streakCount.toDouble()
            base["hesitation"] = m.microHesitation
            base["exhaustion"] = m.microExhaustion
            base["breakout_prob"] = m.breakoutProb
            base["hmm_confidence"] = telemetry.hmmOutput?.confidence ?: 50.0
            base["hmm_state"] = (telemetry.hmmOutput?.state ?: 0).toDouble()
            base["eligibility_passed"] = if (t.eligibilityLayerPassed) 1.0 else 0.0
            base["signal_layer_score"] = t.signalLayerScore
            base["validation_layer_score"] = t.validationLayerScore
            base["calibrated_win_prob"] = t.calibratedWinProbPercent
            base["rolling_snr"] = t.rollingSnrMean
            base["rolling_purity"] = t.rollingPurityMean
            base["rolling_ofi_median"] = t.rollingOfiMedian
            base["rolling_impulse"] = t.rollingImpulseMean
            base["momentum_hysteresis"] = if (t.momentumHysteresisActive) 1.0 else 0.0
            base["purity_hysteresis"] = if (t.purityHysteresisActive) 1.0 else 0.0
            base["snr_hysteresis"] = if (t.snrHysteresisActive) 1.0 else 0.0
            base["persistence_5"] = t.persistenceCount5.toDouble()
            base["noise_clean"] = if (t.noiseClean) 1.0 else 0.0
            base["regime_snr_req"] = t.regimeSnrThreshold
            base["regime_purity_req"] = t.regimePurityThreshold
            base["gate_stability_pct"] = t.gateStabilityPercent
            base["false_positive_pct"] = t.falsePositiveRatePercent
            base
        }
    }

    val effectiveStake = loadedStrategy.computeEffectiveStake(activeBalance)
    val activeEvaluation = telemetry.activeEvaluations.firstOrNull()

    if (showNewStrategyDialog) {
        AlertDialog(
            onDismissRequest = { showNewStrategyDialog = false },
            title = { Text("Create New .OQS Strategy", color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Generates a complete HighVoltage + HV Window .oqs strategy script pre-configured with all 6 strict filters.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    OutlinedTextField(
                        value = newStrategyNameInput,
                        onValueChange = { newStrategyNameInput = it },
                        label = { Text("Strategy Identifier") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("new_strategy_name_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cleaned = newStrategyNameInput.trim().ifBlank { "Custom_HighVoltage_5s" }
                        showNewStrategyDialog = false
                        onCreateStrategyFromTemplate(cleaned) { created ->
                            selectedStrategyId = created.id
                            editorText = created.scriptCode
                            statusBanner = "Created & Loaded Strategy: ${created.name}"
                            statusIsError = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BullishGreen),
                    modifier = Modifier.testTag("confirm_create_strategy_button")
                ) {
                    Text("CREATE & LOAD", color = DeepObsidian, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewStrategyDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = SlateSurface
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepObsidian)
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        // 1. Loaded Active Strategy Banner (Redesigned for Zero Text Overflow)
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BullishGreen.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                shape = RoundedCornerShape(12.dp),
                color = SlateSurface
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Header Row: Section Title + Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(BullishGreen)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "LOADED ACTIVE STRATEGY",
                                style = MaterialTheme.typography.labelLarge,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = {
                                    newStrategyNameInput = "Custom_HV_${activeTf.label}_${strategies.size + 1}"
                                    showNewStrategyDialog = true
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .heightIn(min = 34.dp)
                                    .testTag("new_strategy_template_button")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("NEW .OQS", style = MaterialTheme.typography.labelSmall, color = ElectricCyan)
                            }
                            OutlinedButton(
                                onClick = {
                                    onResetDefaultStrategy { defaultCfg ->
                                        selectedStrategyId = defaultCfg.id
                                        editorText = defaultCfg.scriptCode
                                        statusBanner = "Reset & Loaded Default: ${defaultCfg.name}"
                                        statusIsError = false
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .heightIn(min = 34.dp)
                                    .testTag("reset_default_strategy_button")
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("RESET", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            }
                        }
                    }

                    // Full-Width Active Strategy Name & Guard Status Box
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = DeepObsidian,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, SlateBorder, RoundedCornerShape(8.dp))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${loadedStrategy.name}.oqs",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = ElectricCyan,
                                    fontFamily = JetBrainsMonoFamily,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp)
                                )
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = BullishGreen.copy(alpha = 0.16f),
                                    modifier = Modifier.border(1.dp, BullishGreen.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                ) {
                                    Text(
                                        text = "${activeTf.label.uppercase()} • ACTIVE",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = BullishGreen,
                                        fontFamily = JetBrainsMonoFamily,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                            Text(
                                text = "1-Trade-At-A-Time Lock • Autopilot Guard (Stop on AVOID / Auto-Start on MODERATE–GOOD) • Chrono Sniper 1/Hr Compatible",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                lineHeight = 14.sp
                            )
                        }
                    }

                    // 2x2 Responsive Spec Grid (No Text Overflow)
                    val stakeModeLabel = if (loadedStrategy.stakeMode.contains("FIXED", ignoreCase = true)) {
                        "STAKE • FIXED USD"
                    } else {
                        "STAKE • % BALANCE"
                    }
                    val maxDailyEffective = if (telemetry.hourlySniperMode) {
                        maxOf(24, loadedStrategy.maxDailyTrades)
                    } else {
                        loadedStrategy.maxDailyTrades
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LoadedSpecTile(
                            label = stakeModeLabel,
                            value = "$${"%,.2f".format(effectiveStake)}",
                            sub = "${loadedStrategy.stakeValue}% • Min $${loadedStrategy.minStakeUsd.toInt()} / Max $${loadedStrategy.maxStakeUsd.toInt()}",
                            accent = BullishGreen,
                            modifier = Modifier.weight(1f)
                        )
                        LoadedSpecTile(
                            label = "EXPIRY & LOCK",
                            value = "${loadedStrategy.durationSeconds}s (${activeTf.label.uppercase()})",
                            sub = "1-Trade Lock • ${loadedStrategy.cooldownMs / 1000.0}s Cooldown",
                            accent = ElectricCyan,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LoadedSpecTile(
                            label = "CONFIDENCE & YIELD",
                            value = "≥${loadedStrategy.minConfidence.toInt()}% • ${loadedStrategy.minPayoutPercent}%+",
                            sub = "Stop AVOID • Auto-Start GOOD",
                            accent = ElectricCyan,
                            modifier = Modifier.weight(1f)
                        )
                        LoadedSpecTile(
                            label = "QUOTA & SNIPER",
                            value = "${telemetry.dailyTradesCount}/$maxDailyEffective Trades",
                            sub = if (telemetry.hourlySniperMode) {
                                "Sniper: ${telemetry.hourlyTradesThisHour}/1 Hr • Loss ${telemetry.consecutiveLosses}/${loadedStrategy.maxConsecutiveLosses}"
                            } else {
                                "Loss Cap: ${telemetry.consecutiveLosses}/${loadedStrategy.maxConsecutiveLosses} Streak"
                            },
                            accent = if (telemetry.dailyTradesCount >= maxDailyEffective) BearishCrimson else BullishGreen,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // 1B. Strategy Studio Timeframe Expansion Selector (5s • 15s • 30s • 45s • 1m)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(DeepObsidian)
                            .border(1.dp, SlateBorder, RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "TIMEFRAME EXPANSION (.OQS SYNC)",
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricCyan,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp)
                            )
                            Text(
                                text = activeTf.title,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                fontFamily = JetBrainsMonoFamily,
                                maxLines = 1
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            TradeTimeframe.entries.forEach { tf ->
                                val isSelected = tf == activeTf
                                Surface(
                                    onClick = {
                                        onSelectTimeframe(tf.seconds)
                                        editorText = DefaultStrategyScripts.withDurationSec(editorText, tf.seconds)
                                    },
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isSelected) ElectricCyan.copy(alpha = 0.2f) else SlateSurface,
                                    modifier = Modifier
                                        .weight(1f)
                                        .border(
                                            1.dp,
                                            if (isSelected) ElectricCyan else SlateBorder,
                                            RoundedCornerShape(6.dp)
                                        )
                                        .testTag("studio_tf_chip_${tf.label}")
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 6.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = tf.label.uppercase(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isSelected) ElectricCyan else TextPrimary,
                                            fontFamily = JetBrainsMonoFamily,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = "${tf.seconds}s",
                                            style = TextStyle(
                                                fontFamily = JetBrainsMonoFamily,
                                                fontSize = 10.sp,
                                                color = if (isSelected) TextPrimary else TextMuted
                                            ),
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. Strategy Files Selector Ribbon (Load / Switch / Delete)
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SlateBorder, RoundedCornerShape(12.dp)),
                shape = RoundedCornerShape(12.dp),
                color = SlateSurface
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SectionHeader(
                            title = "STRATEGY FILES (${strategies.size})",
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                        )
                        Text(
                            text = "Tap to Edit • Play to Load",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            maxLines = 1
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        strategies.forEach { strat ->
                            val isSelected = strat.id == selectedStrategy.id
                            val isLoaded = strat.isEnabled
                            val borderColor = when {
                                isLoaded -> BullishGreen
                                isSelected -> ElectricCyan
                                else -> SlateBorder
                            }
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) DeepObsidian else SlateSurfaceVariant)
                                    .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                                    .clickable {
                                        selectedStrategyId = strat.id
                                        editorText = strat.scriptCode
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                                    .testTag("strategy_chip_${strat.id}"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(if (isLoaded) BullishGreen else TextMuted)
                                )
                                Column {
                                    Text(
                                        text = "${strat.name}.oqs",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (isLoaded) BullishGreen else TextPrimary,
                                        fontFamily = JetBrainsMonoFamily,
                                        fontWeight = if (isLoaded || isSelected) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = if (isLoaded) "LOADED IN ENGINE • ${strat.durationSeconds}s" else "TAP TO EDIT • ${strat.durationSeconds}s",
                                        style = TextStyle(
                                            fontFamily = JetBrainsMonoFamily,
                                            fontSize = 10.sp,
                                            color = if (isLoaded) BullishGreen else TextMuted
                                        ),
                                        maxLines = 1
                                    )
                                }
                                if (!isLoaded) {
                                    IconButton(
                                        onClick = {
                                            selectedStrategyId = strat.id
                                            onLoadStrategy(strat.id)
                                            statusBanner = "Loaded ${strat.name}.oqs into live engine."
                                            statusIsError = false
                                        },
                                        modifier = Modifier
                                            .size(26.dp)
                                            .testTag("quick_load_strategy_${strat.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = "Load Strategy",
                                            tint = ElectricCyan,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                if (strategies.size > 1) {
                                    IconButton(
                                        onClick = { onDeleteStrategy(strat.id) },
                                        modifier = Modifier
                                            .size(26.dp)
                                            .testTag("delete_strategy_${strat.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = "Delete Strategy",
                                            tint = BearishCrimson,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Selected Strategy Description Box (Full width, clean wrapping)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = DeepObsidian,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, SlateBorder, RoundedCornerShape(8.dp))
                    ) {
                        Text(
                            text = selectedStrategy.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        }

        // 3. Real-Time Compiler Status & AST Diagnostics (Stacked Layout — Zero Overflow)
        item {
            val isValid = compiledPreview.isValid
            val borderCol = if (isValid) BullishGreen.copy(alpha = 0.45f) else BearishCrimson.copy(alpha = 0.7f)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, borderCol, RoundedCornerShape(12.dp)),
                shape = RoundedCornerShape(12.dp),
                color = SlateSurface
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isValid) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = if (isValid) BullishGreen else BearishCrimson,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (isValid) {
                                "OQS COMPILER: VALID 6-BLOCK AST (${compiledPreview.parsedScript?.gates?.size ?: 0} GATES)"
                            } else {
                                "OQS COMPILER: ${compiledPreview.errors.size} SYNTAX ERROR(S)"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isValid) BullishGreen else BearishCrimson,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    val parsed = compiledPreview.parsedScript
                    if (isValid && parsed != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = DeepObsidian,
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, SlateBorder, RoundedCornerShape(8.dp))
                        ) {
                            Text(
                                text = "Execution AST: duration=${parsed.execution.durationSec}s • stake=${parsed.execution.stakeMode}(${parsed.execution.stakeValue}) • min_conf=${parsed.execution.minConfidence}% • vars=${parsed.variables.size} • up=${parsed.signalUpRules.size} • down=${parsed.signalDownRules.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                fontFamily = JetBrainsMonoFamily,
                                lineHeight = 16.sp,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    } else {
                        compiledPreview.errors.take(5).forEach { err ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = DeepObsidian,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, BearishCrimson.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                            ) {
                                Text(
                                    text = "Line ${err.line} [${err.section}]: ${err.message}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = BearishCrimson,
                                    fontFamily = JetBrainsMonoFamily,
                                    lineHeight = 16.sp,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = {
                                val report = buildCompleteDiagnosticsText(
                                    strategyName = selectedStrategy.name,
                                    isValid = isValid,
                                    errors = compiledPreview.errors,
                                    triggerReason = activeEvaluation?.triggerReason,
                                    diagnostics = activeEvaluation?.filterDiagnostics ?: emptyList()
                                )
                                copyToClipboardWithFeedback(
                                    content = report,
                                    confirmationLabel = "Copied compiler & gate diagnostics (${(activeEvaluation?.filterDiagnostics?.size ?: 0)} gates) to clipboard."
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier
                                .heightIn(min = 34.dp)
                                .testTag("copy_all_diagnostics_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy All Diagnostics",
                                tint = ElectricCyan,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "COPY ALL DIAGNOSTICS",
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricCyan,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (statusBanner != null) {
                        Text(
                            text = statusBanner!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (statusIsError) BearishCrimson else ElectricCyan,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // 4. Expandable Language Guide & Live Variable Symbol Table (Full-Width Symbol Cards — Zero Overflow)
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SlateBorder, RoundedCornerShape(12.dp)),
                shape = RoundedCornerShape(12.dp),
                color = SlateSurface
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showVariableReference = !showVariableReference }
                            .testTag("toggle_variable_reference_button"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                        ) {
                            Icon(Icons.Default.Code, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "OQS LANGUAGE GUIDE & LIVE SYMBOLS",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${DefaultStrategyScripts.BUILTIN_VARIABLE_DOCS.size} Built-in Telemetry Variables • Tap to ${if (showVariableReference) "Collapse" else "Expand"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = ElectricCyan.copy(alpha = 0.14f),
                            modifier = Modifier.border(1.dp, ElectricCyan.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                        ) {
                            Text(
                                text = if (showVariableReference) "HIDE" else "SHOW",
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricCyan,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    if (showVariableReference) {
                        HorizontalDivider(color = SlateBorder)

                        OutlinedButton(
                            onClick = {
                                val fullDocs = buildCompleteDocumentationText()
                                copyToClipboardWithFeedback(
                                    content = fullDocs,
                                    confirmationLabel = "Copied complete .OQS documentation & ${DefaultStrategyScripts.BUILTIN_VARIABLE_DOCS.size} variable specs."
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 36.dp)
                                .testTag("copy_all_documentation_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy Complete Documentation",
                                tint = ElectricCyan,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "COPY FULL .OQS DOCUMENTATION & SYMBOL TABLE",
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricCyan,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (copyConfirmationMessage != null) {
                            Text(
                                text = copyConfirmationMessage!!,
                                style = MaterialTheme.typography.labelSmall,
                                color = BullishGreen,
                                fontFamily = JetBrainsMonoFamily,
                                modifier = Modifier.testTag("copy_confirmation_banner")
                            )
                        }

                        Text(
                            text = "Every .oqs strategy file has 6 mandatory blocks: [META], [EXECUTION], [VARIABLES], [SIGNAL_UP], [SIGNAL_DOWN], and [GATES]. Tap any symbol card below to insert it into the editor.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            lineHeight = 16.sp
                        )

                        DocumentationCodeBlockCard(
                            title = "6-BLOCK .OQS TEMPLATE SNIPPET",
                            code = OQS_SIX_BLOCK_TEMPLATE_SNIPPET,
                            copyTag = "copy_template_snippet_button",
                            onCopy = {
                                copyToClipboardWithFeedback(
                                    content = OQS_SIX_BLOCK_TEMPLATE_SNIPPET,
                                    confirmationLabel = "Copied 6-block .oqs template snippet to clipboard."
                                )
                            }
                        )

                        DocumentationCodeBlockCard(
                            title = "X13 TEMPORAL SMOOTHING & HYSTERESIS FUNCTIONS",
                            code = OQS_X13_FUNCTIONS_SNIPPET,
                            copyTag = "copy_functions_snippet_button",
                            onCopy = {
                                copyToClipboardWithFeedback(
                                    content = OQS_X13_FUNCTIONS_SNIPPET,
                                    confirmationLabel = "Copied X13 temporal functions snippet to clipboard."
                                )
                            }
                        )

                        // Spacious Live Telemetry Symbol Cards (Zero Clipping)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            DefaultStrategyScripts.BUILTIN_VARIABLE_DOCS.forEach { doc ->
                                val liveVal = liveSymbolValues[doc.name] ?: 0.0
                                val liveFormatted = when (doc.unit) {
                                    "%" -> "${"%.2f".format(liveVal.finiteOr(0.0))}%"
                                    "dir" -> when {
                                        liveVal > 0.0 -> "UP (+1)"
                                        liveVal < 0.0 -> "DOWN (-1)"
                                        else -> "NONE (0)"
                                    }
                                    "bool" -> if (liveVal >= 0.5) "TRUE (1)" else "FALSE (0)"
                                    else -> "${"%.4f".format(liveVal.finiteOr(0.0))} ${doc.unit}".trim()
                                }
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(DeepObsidian)
                                        .border(1.dp, SlateBorder, RoundedCornerShape(8.dp))
                                        .clickable {
                                            editorText = "$editorText\n${doc.name}"
                                        }
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "+ ${doc.name}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = ElectricCyan,
                                            fontFamily = JetBrainsMonoFamily,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(end = 8.dp)
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = BullishGreen.copy(alpha = 0.14f),
                                            modifier = Modifier.border(
                                                1.dp,
                                                BullishGreen.copy(alpha = 0.4f),
                                                RoundedCornerShape(6.dp)
                                            )
                                        ) {
                                            Text(
                                                text = liveFormatted,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = BullishGreen,
                                                fontFamily = JetBrainsMonoFamily,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = doc.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary,
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 5. Action Toolbar (Stacked Primary Compile & Load + Copy/Paste Row — Zero Overflow)
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        onSaveAndLoadStrategy(selectedStrategy.id, editorText) { result ->
                            result.onSuccess { saved ->
                                selectedStrategyId = saved.id
                                statusBanner =
                                    "Compiled & Loaded `${saved.name}.oqs` (${saved.durationSeconds}s, ${saved.stakeMode}=${saved.stakeValue})"
                                statusIsError = false
                            }.onFailure { err ->
                                statusBanner = err.message ?: "Failed to compile .oqs script"
                                statusIsError = true
                            }
                        }
                    },
                    enabled = compiledPreview.isValid,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BullishGreen,
                        disabledContainerColor = SlateBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("compile_and_load_oqs_button")
                ) {
                    Icon(Icons.Default.Terminal, contentDescription = null, tint = DeepObsidian, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "COMPILE & LOAD STRATEGY (.OQS)",
                        style = MaterialTheme.typography.labelLarge,
                        color = DeepObsidian,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            copyToClipboardWithFeedback(
                                content = editorText,
                                confirmationLabel = "Copied `${selectedStrategy.name}.oqs` (${editorText.lines().size} lines) to clipboard."
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                            .testTag("copy_oqs_script_button")
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy Script", tint = ElectricCyan, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "COPY .OQS",
                            style = MaterialTheme.typography.labelMedium,
                            color = ElectricCyan,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            val clip = clipboardManager.getText()?.text
                            if (!clip.isNullOrBlank()) {
                                editorText = clip
                                statusBanner = "Pasted .oqs script from clipboard — Review & tap COMPILE & LOAD."
                                statusIsError = false
                            } else {
                                statusBanner = "Clipboard is empty."
                                statusIsError = true
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                            .testTag("paste_oqs_script_button")
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Paste Script", tint = TextPrimary, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "PASTE SCRIPT",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // 5B. Multi-Line .oqs Code Editor with Quick-Insert Token Chips
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SlateBorder, RoundedCornerShape(12.dp)),
                shape = RoundedCornerShape(12.dp),
                color = DeepObsidian
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SectionHeader(
                            title = "SOURCE EDITOR (${selectedStrategy.name}.oqs)",
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                        )
                        Text(
                            text = "${editorText.lines().size} lines",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            fontFamily = JetBrainsMonoFamily,
                            maxLines = 1
                        )
                    }

                    // Quick-Insert Token Chips
                    val quickTokens = listOf(
                        "hv_score",
                        "zlema_trend_dir",
                        "tick_speed",
                        "rel_pos",
                        "net_delta_5",
                        "sr_wall_clear",
                        "sr_min_clearance",
                        "vol_regime_ok",
                        "eligibility_passed",
                        "signal_layer_score",
                        "calibrated_win_prob",
                        "roll_mean(rush_snr, 3)",
                        "roll_median(ofi, 5)",
                        "hysteresis(tick_purity, 70, 56)",
                        "n_of_m(tip_aligned, 3, 5)",
                        "rush_snr",
                        "tick_purity",
                        "ftrl_win_prob",
                        "and",
                        "or",
                        ">=",
                        "<="
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        quickTokens.forEach { token ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(SlateSurface)
                                    .border(1.dp, SlateBorder, RoundedCornerShape(6.dp))
                                    .clickable {
                                        editorText = "$editorText $token"
                                    }
                                    .padding(horizontal = 8.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = "+ $token",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ElectricCyan,
                                    fontFamily = JetBrainsMonoFamily
                                )
                            }
                        }
                    }

                    val lineCount = remember(editorText) { maxOf(1, editorText.count { it == '\n' } + 1) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 320.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(DeepObsidian)
                            .border(1.dp, SlateBorder, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(end = 10.dp),
                            horizontalAlignment = Alignment.End
                        ) {
                            for (lineNum in 1..lineCount) {
                                val hasErr = errorLines.contains(lineNum)
                                Text(
                                    text = lineNum.toString().padStart(2, ' '),
                                    style = TextStyle(
                                        fontFamily = JetBrainsMonoFamily,
                                        fontSize = 12.sp,
                                        lineHeight = 18.sp,
                                        fontWeight = if (hasErr) FontWeight.Bold else FontWeight.Normal,
                                        color = if (hasErr) BearishCrimson else TextMuted
                                    )
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height((lineCount * 18).dp)
                                .background(SlateBorder)
                        )

                        Spacer(Modifier.width(10.dp))

                        BasicTextField(
                            value = editorText,
                            onValueChange = { editorText = it },
                            textStyle = TextStyle(
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                color = TextPrimary
                            ),
                            cursorBrush = SolidColor(BullishGreen),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .testTag("oqs_code_editor_input")
                        )
                    }
                }
            }
        }

        // 6. Live Gate & Signal Debugger for the Currently Loaded Strategy
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SlateBorder, RoundedCornerShape(12.dp)),
                shape = RoundedCornerShape(12.dp),
                color = SlateSurface
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SectionHeader(
                            title = "LIVE GATE TELEMETRY",
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                        )
                        val signalColor = when (activeEvaluation?.finalSignal) {
                            SignalDirection.UP -> BullishGreen
                            SignalDirection.DOWN -> BearishCrimson
                            else -> TextSecondary
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = signalColor.copy(alpha = 0.14f),
                            modifier = Modifier.border(1.dp, signalColor.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                        ) {
                            Text(
                                text = when (activeEvaluation?.finalSignal) {
                                    SignalDirection.UP -> "SIGNAL: CALL (UP)"
                                    SignalDirection.DOWN -> "SIGNAL: PUT (DOWN)"
                                    else -> "SCANNING (${"%.0f".format((activeEvaluation?.confidenceScore ?: 0.0).finiteOr(0.0))}%)"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = signalColor,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    if (activeEvaluation != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = DeepObsidian,
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, SlateBorder, RoundedCornerShape(8.dp))
                        ) {
                            Text(
                                text = activeEvaluation.triggerReason,
                                style = MaterialTheme.typography.bodySmall,
                                color = ElectricCyan,
                                fontFamily = JetBrainsMonoFamily,
                                lineHeight = 16.sp,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }
        }

        val diagnostics = activeEvaluation?.filterDiagnostics ?: emptyList()
        items(
            items = diagnostics,
            key = { "${it.gateTitle}_${it.thresholdFormatted}" }
        ) { diag ->
            GateDiagnosticCard(
                diag = diag,
                onCopy = {
                    val text = "[${if (diag.passed) "PASS" else "FAIL"}] ${diag.gateTitle}: ${diag.actualValueFormatted} (Req: ${diag.thresholdFormatted}) — ${diag.reason}"
                    copyToClipboardWithFeedback(
                        content = text,
                        confirmationLabel = "Copied gate diagnostic `${diag.gateTitle}`."
                    )
                }
            )
        }
    }
}

@Composable
private fun DocumentationCodeBlockCard(
    title: String,
    code: String,
    copyTag: String,
    onCopy: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(DeepObsidian)
            .border(1.dp, SlateBorder, RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = ElectricCyan,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            )
            OutlinedButton(
                onClick = onCopy,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier
                    .heightIn(min = 28.dp)
                    .testTag(copyTag)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy Code Block", tint = ElectricCyan, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text("COPY", style = MaterialTheme.typography.labelSmall, color = ElectricCyan)
            }
        }
        Text(
            text = code,
            style = MaterialTheme.typography.bodySmall,
            color = TextPrimary,
            fontFamily = JetBrainsMonoFamily,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        )
    }
}

@Composable
private fun LoadedSpecTile(
    label: String,
    value: String,
    sub: String,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .heightIn(min = 76.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(DeepObsidian)
            .border(1.dp, SlateBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = TextStyle(
                fontSize = 10.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextMuted
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = accent,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = sub,
            style = TextStyle(
                fontSize = 10.sp,
                lineHeight = 13.sp,
                color = TextSecondary
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun GateDiagnosticCard(
    diag: FilterDiagnostic,
    onCopy: () -> Unit = {}
) {
    val accent = if (diag.passed) BullishGreen else BearishCrimson
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
        shape = RoundedCornerShape(10.dp),
        color = SlateSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(accent)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = diag.gateTitle,
                        style = MaterialTheme.typography.labelLarge,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = accent.copy(alpha = 0.14f),
                        modifier = Modifier.border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                    ) {
                        Text(
                            text = "${if (diag.passed) "PASS" else "FAIL"} • ${diag.actualValueFormatted}",
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = onCopy,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Gate Diagnostic",
                            tint = TextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            Text(
                text = diag.reason,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                lineHeight = 16.sp
            )

            Surface(
                shape = RoundedCornerShape(6.dp),
                color = DeepObsidian,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SlateBorder, RoundedCornerShape(6.dp))
            ) {
                Text(
                    text = "Rule: ${diag.thresholdFormatted}",
                    style = TextStyle(
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = WarningAmber
                    ),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                )
            }
        }
    }
}
