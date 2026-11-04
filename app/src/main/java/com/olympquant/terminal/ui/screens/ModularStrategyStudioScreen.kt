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
let median_ofi    = roll_median(ofi_imbalance, 5)
let fast_ema_vel  = ema(velocity, 4)
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
        if (editorText.isBlank() || editorText != targetCode) {
            editorText = targetCode
        }
    }

    val compileResult by remember(editorText) {
        derivedStateOf { StrategyScriptCompiler.compile(editorText) }
    }
    val errorLines by remember(compileResult) {
        derivedStateOf { compileResult.errors.map { it.line }.toSet() }
    }

    val activeEvaluation = telemetry.activeEvaluations.firstOrNull()
    val effectiveStakeUsd = remember(activeBalance, loadedStrategy) {
        loadedStrategy.computeEffectiveStake(activeBalance)
    }

    fun copyToClipboardWithFeedback(content: String, confirmationLabel: String) {
        clipboardManager.setText(AnnotatedString(content))
        copyConfirmationMessage = confirmationLabel
        statusIsError = false
        statusBanner = confirmationLabel
    }

    if (showNewStrategyDialog) {
        AlertDialog(
            onDismissRequest = { showNewStrategyDialog = false },
            containerColor = SlateSurface,
            title = {
                Text(
                    "CREATE NEW .OQS STRATEGY",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "New strategies are initialized with the standard 6-block OlympQuant Script (.oqs) structure ([META], [EXECUTION], [VARIABLES], [SIGNAL_UP], [SIGNAL_DOWN], [GATES]).",
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
                        val clean = newStrategyNameInput.trim().ifEmpty { "Custom_HighVoltage_${activeTf.label}" }
                        showNewStrategyDialog = false
                        onCreateStrategyFromTemplate(clean) { created ->
                            selectedStrategyId = created.id
                            editorText = created.scriptCode
                            statusIsError = false
                            statusBanner = "Created & loaded `${created.name}.oqs` (${activeTf.label}) from standard 6-block template."
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BullishGreen, contentColor = DeepObsidian),
                    modifier = Modifier.testTag("confirm_create_strategy_button")
                ) {
                    Text("CREATE & LOAD", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewStrategyDialog = false }) {
                    Text("CANCEL", color = TextSecondary)
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepObsidian)
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Currently Loaded Strategy Execution Banner
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, ElectricCyan.copy(alpha = 0.45f), RoundedCornerShape(14.dp)),
                shape = RoundedCornerShape(14.dp),
                color = SlateSurface
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
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
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = "OlympQuant Strategy Script IDE",
                                tint = ElectricCyan,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "OLYMPQUANT SCRIPT (.OQS) ENGINE",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "LOADED FOR EXECUTION: ${loadedStrategy.name}.oqs",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BullishGreen,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(BullishGreen.copy(alpha = 0.14f))
                                .border(1.dp, BullishGreen.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "ACTIVE • ${activeTf.label.uppercase()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = BullishGreen,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = loadedStrategy.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = SlateBorder)
                    Spacer(Modifier.height(10.dp))

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            LoadedSpecTile(
                                label = "STAKE (${loadedStrategy.stakeMode})",
                                value = "$${"%.2f".format(effectiveStakeUsd)}",
                                sub = "${loadedStrategy.stakeValue}% ($${loadedStrategy.minStakeUsd.toInt()}–$${loadedStrategy.maxStakeUsd.toInt()})",
                                accent = BullishGreen,
                                modifier = Modifier.weight(1f)
                            )
                            LoadedSpecTile(
                                label = "CONTRACT DURATION",
                                value = "${activeTf.label} (${activeTf.seconds}s)",
                                sub = "Cooldown ${loadedStrategy.cooldownMs / 1000}s",
                                accent = ElectricCyan,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            LoadedSpecTile(
                                label = "DAILY QUOTA CAP",
                                value = "${telemetry.dailyTradesCount}/${loadedStrategy.maxDailyTrades}",
                                sub = "Max ${loadedStrategy.maxDailyTrades} trades/day",
                                accent = if (telemetry.dailyTradesCount >= loadedStrategy.maxDailyTrades) BearishCrimson else TextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            LoadedSpecTile(
                                label = "LOSS STREAK GUARD",
                                value = "${telemetry.consecutiveLosses}/${loadedStrategy.maxConsecutiveLosses}",
                                sub = "Hard Stop @ ${loadedStrategy.maxConsecutiveLosses}L",
                                accent = if (telemetry.consecutiveLosses >= loadedStrategy.maxConsecutiveLosses) BearishCrimson else TextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // Trade Timeframe Expansion Bar in Studio (5s • 15s • 30s • 45s • 1m)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TradeTimeframe.entries.forEach { tf ->
                                val isSelected = tf == activeTf
                                Surface(
                                    onClick = {
                                        onSelectTimeframe(tf.seconds)
                                        editorText = DefaultStrategyScripts.withDurationSec(editorText, tf.seconds)
                                        statusIsError = false
                                        statusBanner = "Expanded trade timeframe to ${tf.label} (${tf.seconds}s) in active strategy & editor."
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) ElectricCyan.copy(alpha = 0.20f) else SlateSurfaceVariant,
                                    modifier = Modifier
                                        .weight(1f)
                                        .border(
                                            1.dp,
                                            if (isSelected) ElectricCyan else SlateBorder,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .testTag("studio_timeframe_chip_${tf.label}")
                                ) {
                                    Box(
                                        modifier = Modifier.padding(vertical = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = tf.label.uppercase(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isSelected) ElectricCyan else TextSecondary,
                                            fontFamily = JetBrainsMonoFamily,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. Saved Strategy Selector & Management Bar
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SlateBorder, RoundedCornerShape(12.dp)),
                shape = RoundedCornerShape(12.dp),
                color = SlateSurface
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = { showNewStrategyDialog = true },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .heightIn(min = 36.dp)
                                    .testTag("create_new_oqs_strategy_button")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "NEW .OQS",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ElectricCyan,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    onResetDefaultStrategy { def ->
                                        selectedStrategyId = def.id
                                        editorText = def.scriptCode
                                        statusIsError = false
                                        statusBanner = "Restored default strategy `${def.name}.oqs`."
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .heightIn(min = 36.dp)
                                    .testTag("reset_default_oqs_button")
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "DEFAULT",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        strategies.forEach { strat ->
                            val isLoaded = strat.isEnabled
                            val isSelected = strat.id == selectedStrategy.id
                            val borderColor = when {
                                isLoaded -> BullishGreen
                                isSelected -> ElectricCyan
                                else -> SlateBorder
                            }
                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                                    .clickable {
                                        selectedStrategyId = strat.id
                                        editorText = strat.scriptCode
                                        onLoadStrategy(strat.id)
                                        statusIsError = false
                                        statusBanner = "Loaded `${strat.name}.oqs` into active bot execution."
                                    }
                                    .testTag("strategy_chip_${strat.id}"),
                                color = if (isSelected) SlateSurfaceVariant else DeepObsidian
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (isLoaded) BullishGreen else TextMuted)
                                    )
                                    Column {
                                        Text(
                                            text = "${strat.name}.oqs",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (isLoaded) BullishGreen else TextPrimary,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = if (isLoaded) "LOADED • ${strat.durationSeconds}s" else "TAP TO LOAD • ${strat.durationSeconds}s",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary
                                        )
                                    }
                                    if (strategies.size > 1 && !strat.isPreset) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = "Delete strategy",
                                            tint = BearishCrimson,
                                            modifier = Modifier
                                                .size(16.dp)
                                                .clickable {
                                                    onDeleteStrategy(strat.id)
                                                }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Compiler Validation, Copy Diagnostics & Action Bar
        item {
            val isValid = compileResult.isValid
            val statusColor = if (isValid) BullishGreen else BearishCrimson
            val diagnostics = activeEvaluation?.filterDiagnostics ?: emptyList()

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, statusColor.copy(alpha = 0.45f), RoundedCornerShape(12.dp)),
                shape = RoundedCornerShape(12.dp),
                color = SlateSurface
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                            Icon(
                                imageVector = if (isValid) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = statusColor,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (isValid) {
                                    "OQS COMPILER: VALID 6-BLOCK AST (${compileResult.parsedScript?.gates?.size ?: 0} GATES)"
                                } else {
                                    "OQS COMPILER: ${compileResult.errors.size} SYNTAX ERROR(S)"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = statusColor,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Prominent COPY ALL button for validation errors and compilation diagnostics
                        OutlinedButton(
                            onClick = {
                                val report = buildCompleteDiagnosticsText(
                                    strategyName = selectedStrategy.name,
                                    isValid = isValid,
                                    errors = compileResult.errors,
                                    triggerReason = activeEvaluation?.triggerReason,
                                    diagnostics = diagnostics
                                )
                                copyToClipboardWithFeedback(
                                    content = report,
                                    confirmationLabel = "Copied all compiler & gate diagnostics (${compileResult.errors.size} errors, ${diagnostics.size} gates)."
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier
                                .heightIn(min = 34.dp)
                                .testTag("copy_all_errors_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy All Diagnostics",
                                tint = if (isValid) ElectricCyan else BearishCrimson,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (isValid) "COPY ALL DIAGNOSTICS" else "COPY ALL ERRORS",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isValid) ElectricCyan else BearishCrimson,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }

                    Button(
                        onClick = {
                            onSaveAndLoadStrategy(selectedStrategy.id, editorText) { result ->
                                result.onSuccess { saved ->
                                    selectedStrategyId = saved.id
                                    statusIsError = false
                                    statusBanner =
                                        "COMPILED & LOADED: `${saved.name}.oqs` (${saved.durationSeconds}s • ${saved.stakeMode} ${saved.stakeValue}%)"
                                }.onFailure { err ->
                                    statusIsError = true
                                    statusBanner = err.message ?: "Failed to compile .oqs script"
                                }
                            }
                        },
                        enabled = isValid,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BullishGreen,
                            contentColor = DeepObsidian,
                            disabledContainerColor = SlateSurfaceVariant,
                            disabledContentColor = TextMuted
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .testTag("compile_and_load_oqs_button")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "COMPILE & LOAD STRATEGY",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Individual error rows with per-error copy button and full readable text
                    if (!isValid) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            compileResult.errors.forEach { err ->
                                val formattedErr = "Line ${err.line} [${err.section}]: ${err.message}"
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(DeepObsidian)
                                        .border(1.dp, BearishCrimson.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "• $formattedErr",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = BearishCrimson,
                                        fontFamily = JetBrainsMonoFamily,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(end = 8.dp)
                                    )
                                    IconButton(
                                        onClick = {
                                            copyToClipboardWithFeedback(
                                                content = formattedErr,
                                                confirmationLabel = "Copied Line ${err.line} error to clipboard."
                                            )
                                        },
                                        modifier = Modifier
                                            .size(28.dp)
                                            .testTag("copy_error_line_${err.line}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = "Copy Error",
                                            tint = BearishCrimson,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Copy / Action confirmation banner
                    if (statusBanner != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = DeepObsidian,
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    1.dp,
                                    if (statusIsError) BearishCrimson.copy(alpha = 0.5f) else BullishGreen.copy(alpha = 0.45f),
                                    RoundedCornerShape(8.dp)
                                )
                                .testTag("copy_confirmation_banner")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (statusIsError) Icons.Default.ErrorOutline else Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = if (statusIsError) BearishCrimson else BullishGreen,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = statusBanner!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (statusIsError) BearishCrimson else ElectricCyan,
                                    fontFamily = JetBrainsMonoFamily
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                copyToClipboardWithFeedback(
                                    content = editorText,
                                    confirmationLabel = "Copied `${selectedStrategy.name}.oqs` strategy code to clipboard."
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 38.dp)
                                .testTag("copy_oqs_script_button")
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "COPY .OQS",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                val clip = clipboardManager.getText()?.text
                                if (!clip.isNullOrBlank()) {
                                    editorText = clip
                                    statusIsError = false
                                    statusBanner = "Pasted `.oqs` script from clipboard. Tap COMPILE & LOAD to activate."
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 38.dp)
                                .testTag("paste_oqs_script_button")
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "PASTE .OQS",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        OutlinedButton(
                            onClick = { showVariableReference = !showVariableReference },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 38.dp)
                                .testTag("toggle_oqs_reference_button")
                        ) {
                            Icon(Icons.Default.Code, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (showVariableReference) "HIDE DOCS" else "DOCS",
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricCyan,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // 4. Strategy Documentation & Built-In Variable Dictionary (with Prominent COPY ALL + Code Block Copy Controls)
        if (showVariableReference) {
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, ElectricCyan.copy(alpha = 0.35f), RoundedCornerShape(12.dp)),
                    shape = RoundedCornerShape(12.dp),
                    color = SlateSurface
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SectionHeader(
                                title = "OQS LANGUAGE & X13 DOCUMENTATION",
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp)
                            )
                            Button(
                                onClick = {
                                    val fullDocs = buildCompleteDocumentationText()
                                    copyToClipboardWithFeedback(
                                        content = fullDocs,
                                        confirmationLabel = "Copied complete .oqs documentation (${DefaultStrategyScripts.BUILTIN_VARIABLE_DOCS.size} variables & code blocks)."
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ElectricCyan,
                                    contentColor = DeepObsidian
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .heightIn(min = 34.dp)
                                    .testTag("copy_all_docs_button")
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy All Documentation", modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "COPY ALL DOCS",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            }
                        }

                        if (copyConfirmationMessage != null) {
                            Text(
                                text = "✓ $copyConfirmationMessage",
                                style = MaterialTheme.typography.labelSmall,
                                color = BullishGreen,
                                fontFamily = JetBrainsMonoFamily
                            )
                        }

                        Text(
                            text = "Every strategy file must include [META], [EXECUTION], [VARIABLES], [SIGNAL_UP], [SIGNAL_DOWN], and [GATES]. Supported operators & X13 temporal functions: roll_mean(v,w), roll_median(v,w), ema(v,w), hysteresis(v,enter,exit), n_of_m(v,n,m), abs(x), sqrt(x), min(a,b), max(a,b).",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )

                        // Code Block 1: 6-Block Structure Template with individual copy control
                        DocumentationCodeBlockCard(
                            title = "STANDARD 6-BLOCK .OQS TEMPLATE",
                            code = OQS_SIX_BLOCK_TEMPLATE_SNIPPET,
                            copyTag = "copy_doc_template_block",
                            onCopy = {
                                copyToClipboardWithFeedback(
                                    content = OQS_SIX_BLOCK_TEMPLATE_SNIPPET,
                                    confirmationLabel = "Copied 6-block .oqs template snippet."
                                )
                            }
                        )

                        // Code Block 2: X13 Temporal Functions Snippet with individual copy control
                        DocumentationCodeBlockCard(
                            title = "X13 TEMPORAL INTELLIGENCE OPERATORS",
                            code = OQS_X13_FUNCTIONS_SNIPPET,
                            copyTag = "copy_doc_temporal_block",
                            onCopy = {
                                copyToClipboardWithFeedback(
                                    content = OQS_X13_FUNCTIONS_SNIPPET,
                                    confirmationLabel = "Copied X13 temporal functions snippet."
                                )
                            }
                        )

                        HorizontalDivider(color = SlateBorder)
                        SectionHeader(title = "BUILT-IN TELEMETRY VARIABLES (${DefaultStrategyScripts.BUILTIN_VARIABLE_DOCS.size})")

                        DefaultStrategyScripts.BUILTIN_VARIABLE_DOCS.forEach { doc ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(DeepObsidian)
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(
                                        text = "${doc.name} (${doc.unit})",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ElectricCyan,
                                        fontFamily = JetBrainsMonoFamily,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = doc.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        copyToClipboardWithFeedback(
                                            content = doc.name,
                                            confirmationLabel = "Copied variable `${doc.name}`."
                                        )
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy ${doc.name}",
                                        tint = TextSecondary,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 5. Quick-Insert Token Bar + Monospace .oqs Code Editor
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
                        "eligibility_passed",
                        "signal_layer_score",
                        "validation_layer_score",
                        "calibrated_win_prob",
                        "roll_mean(rush_snr, 3)",
                        "roll_median(ofi_imbalance, 5)",
                        "hysteresis(tick_purity, 70, 56)",
                        "n_of_m(tip_aligned, 3, 5)",
                        "hyst_momentum",
                        "persist_count_5",
                        "noise_clean",
                        "regime_stable",
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
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
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
                Column(modifier = Modifier.padding(14.dp)) {
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
                        Text(
                            text = when (activeEvaluation?.finalSignal) {
                                SignalDirection.UP -> "SIGNAL: CALL (UP)"
                                SignalDirection.DOWN -> "SIGNAL: PUT (DOWN)"
                                else -> "SCANNING (${"%.0f".format((activeEvaluation?.confidenceScore ?: 0.0).finiteOr(0.0))}%)"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = signalColor,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }

                    if (activeEvaluation != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = activeEvaluation.triggerReason,
                            style = MaterialTheme.typography.bodySmall,
                            color = ElectricCyan,
                            fontFamily = JetBrainsMonoFamily
                        )
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
                fontWeight = FontWeight.Bold
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
                Text("COPY BLOCK", style = MaterialTheme.typography.labelSmall, color = ElectricCyan)
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
            .clip(RoundedCornerShape(8.dp))
            .background(DeepObsidian)
            .border(1.dp, SlateBorder, RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = accent,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = sub,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            maxLines = 1,
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
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
        shape = RoundedCornerShape(10.dp),
        color = SlateSurface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = diag.gateTitle,
                        style = MaterialTheme.typography.labelLarge,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = diag.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = diag.actualValueFormatted,
                    style = MaterialTheme.typography.labelLarge,
                    color = accent,
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = diag.thresholdFormatted,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    fontFamily = JetBrainsMonoFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
}
