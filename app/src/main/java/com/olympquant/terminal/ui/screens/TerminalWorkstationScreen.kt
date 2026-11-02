package com.olympquant.terminal.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.olympquant.terminal.data.SessionPreferences
import com.olympquant.terminal.domain.ActiveOpenTrade
import com.olympquant.terminal.domain.HardLockedMandate
import com.olympquant.terminal.domain.MarketTelemetryState
import com.olympquant.terminal.domain.MicrostructureSnapshot
import com.olympquant.terminal.domain.SessionQuality
import com.olympquant.terminal.domain.SignalDirection
import com.olympquant.terminal.domain.StrategyEvaluation
import com.olympquant.terminal.domain.TerminalDensityMode
import com.olympquant.terminal.domain.Tick
import com.olympquant.terminal.domain.finiteOr
import com.olympquant.terminal.network.WsConnectionStatus
import com.olympquant.terminal.ui.theme.BearishCrimson
import com.olympquant.terminal.ui.theme.BearishCrimsonDim
import com.olympquant.terminal.ui.theme.BullishGreen
import com.olympquant.terminal.ui.theme.BullishGreenDim
import com.olympquant.terminal.ui.theme.ElectricCyan
import com.olympquant.terminal.ui.theme.ObsidianBackground
import com.olympquant.terminal.ui.theme.ObsidianElevated
import com.olympquant.terminal.ui.theme.SlateBorder
import com.olympquant.terminal.ui.theme.SlateBorderBright
import com.olympquant.terminal.ui.theme.SlateSurface
import com.olympquant.terminal.ui.theme.SlateSurfaceVariant
import com.olympquant.terminal.ui.theme.TextMuted
import com.olympquant.terminal.ui.theme.TextPrimary
import com.olympquant.terminal.ui.theme.TextSecondary
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private enum class ChartRenderMode(val label: String) {
    CANDLES_5S("5S OHLC"),
    TICK_WAVE("TICK WAVE"),
    DUAL_PRO("PRO MATRIX")
}

@Composable
fun TerminalWorkstationScreen(
    sessionPrefs: SessionPreferences,
    telemetry: MarketTelemetryState,
    connectionStatus: WsConnectionStatus,
    wsLatencyMs: Long,
    botRunning: Boolean,
    availablePairs: List<String>,
    lastExecutionBanner: String?,
    onSelectPair: (String) -> Unit,
    onToggleBot: () -> Unit,
    onSwitchAccountGroup: (String) -> Unit,
    onSetDensityMode: (TerminalDensityMode) -> Unit,
    onManualOrder: (SignalDirection) -> Unit,
    onStartImpulseStream: () -> Unit,
    onDismissBanner: () -> Unit,
    onNavigateToBrokerSetup: () -> Unit
) {
    var showRealConfirmDialog by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    val primaryEvaluation by remember(telemetry.activeEvaluations) {
        derivedStateOf {
            telemetry.activeEvaluations.firstOrNull { it.readyToExecute }
                ?: telemetry.activeEvaluations.firstOrNull()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBackground),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Top Status, Quick Stream Chip & Density Mode Bar
        item(key = "top_bar", contentType = "header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { onNavigateToBrokerSetup() }
                            .testTag("connection_status_pill")
                    ) {
                        StatusBadge(connectionStatus = connectionStatus, latencyMs = wsLatencyMs)
                    }

                    if (connectionStatus == WsConnectionStatus.DISCONNECTED || connectionStatus == WsConnectionStatus.ERROR) {
                        Surface(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onStartImpulseStream()
                            },
                            shape = RoundedCornerShape(50),
                            color = ElectricCyan.copy(alpha = 0.14f),
                            modifier = Modifier
                                .border(1.dp, ElectricCyan.copy(alpha = 0.5f), RoundedCornerShape(50))
                                .testTag("quick_impulse_stream_chip")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bolt,
                                    contentDescription = "Start Impulse Stream",
                                    tint = ElectricCyan,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "IMPULSE STREAM",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ElectricCyan,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // Density Mode Switcher (Pro Terminal / Minimal Deck / Split All)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(SlateSurfaceVariant)
                        .border(1.dp, SlateBorder, RoundedCornerShape(10.dp))
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    TerminalDensityMode.entries.forEach { mode ->
                        val label = when (mode) {
                            TerminalDensityMode.PRO_TERMINAL -> "PRO"
                            TerminalDensityMode.MINIMAL_EXECUTION -> "DECK"
                            TerminalDensityMode.SPLIT_ALL -> "ALL"
                        }
                        val active = sessionPrefs.densityMode == mode
                        Surface(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSetDensityMode(mode)
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = if (active) ElectricCyan else Color.Transparent,
                            modifier = Modifier.testTag("density_mode_${mode.name.lowercase()}")
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (active) TextPrimary else TextSecondary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }
        }

        // Execution Toast Banner
        if (lastExecutionBanner != null) {
            item(key = "toast_banner", contentType = "banner") {
                AnimatedVisibility(visible = true) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = ObsidianElevated,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, SlateBorderBright, RoundedCornerShape(12.dp))
                            .clickable { onDismissBanner() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bolt,
                                    contentDescription = null,
                                    tint = ElectricCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = lastExecutionBanner,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextPrimary
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss",
                                tint = TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // 2. Asset Pair Ribbon
        item(key = "pair_ribbon", contentType = "ribbon") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                availablePairs.forEach { pair ->
                    val selected = pair == sessionPrefs.selectedPair
                    FilterChip(
                        selected = selected,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelectPair(pair)
                        },
                        label = {
                            Text(
                                text = pair,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ElectricCyan.copy(alpha = 0.16f),
                            selectedLabelColor = ElectricCyan,
                            containerColor = SlateSurface
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected,
                            borderColor = SlateBorder,
                            selectedBorderColor = ElectricCyan
                        ),
                        modifier = Modifier.testTag("pair_chip_$pair")
                    )
                }
            }
        }

        // 3. Account Balance & Tactile Bot Control Deck (Hard-Locked Mandate)
        item(key = "execution_deck", contentType = "deck") {
            ExecutionControlDeckCard(
                sessionPrefs = sessionPrefs,
                telemetry = telemetry,
                botRunning = botRunning,
                onToggleBot = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggleBot()
                },
                onRequestAccountSwitch = { targetGroup ->
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (targetGroup == "real" && sessionPrefs.accountGroup != "real") {
                        showRealConfirmDialog = true
                    } else {
                        onSwitchAccountGroup(targetGroup)
                    }
                },
                onManualOrder = { dir ->
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onManualOrder(dir)
                }
            )
        }

        // 4. 60fps Smooth Active Open Trades Countdown & Live ITM/OTM Tracker
        if (telemetry.activeOpenTrades.isNotEmpty()) {
            item(key = "active_open_trades", contentType = "open_trades") {
                ActiveOpenTradesCard(
                    openTrades = telemetry.activeOpenTrades,
                    currentPrice = telemetry.latestPrice
                )
            }
        }

        // 5. Dual-Gauge Ballistic Impulse Momentum Radar HUD
        item(key = "micro_impulse_radar", contentType = "radar") {
            MicroImpulseRadarCard(
                micro = telemetry.microstructure,
                recentTicks = telemetry.recentTicks
            )
        }

        // 6. Interactive Crosshair Quant Chart (Candles + Tick Wave + 50-Tick Fibonacci + S/R)
        if (sessionPrefs.densityMode != TerminalDensityMode.MINIMAL_EXECUTION) {
            item(key = "quant_chart", contentType = "chart") {
                QuantChartCard(
                    telemetry = telemetry,
                    onStartImpulseStream = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onStartImpulseStream()
                    }
                )
            }
        }

        // 7. Key Telemetry Strip (HVOI, HMM State, ZLEMA Consistency, Slope Ratio)
        item(key = "telemetry_grid", contentType = "metrics") {
            TelemetryMetricsGrid(telemetry = telemetry)
        }

        // 8. Active Hawkes-Laminar Ballistic Impulse Gatekeeper Pipeline Inspector
        val eval = primaryEvaluation
        if (eval != null) {
            item(key = "gatekeeper_pipeline", contentType = "pipeline") {
                GatekeeperPipelineCard(evaluation = eval)
            }
        }
    }

    if (showRealConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showRealConfirmDialog = false },
            title = { Text("Switch to REAL Account?") },
            text = {
                Text("Automated and manual orders will execute using real funds (5% balance per trade, min $1, max $4,000). Confirm switch?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRealConfirmDialog = false
                        onSwitchAccountGroup("real")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BearishCrimson)
                ) {
                    Text("CONFIRM REAL ACCOUNT")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRealConfirmDialog = false }) {
                    Text("Stay on Demo")
                }
            }
        )
    }
}

@Composable
private fun ExecutionControlDeckCard(
    sessionPrefs: SessionPreferences,
    telemetry: MarketTelemetryState,
    botRunning: Boolean,
    onToggleBot: () -> Unit,
    onRequestAccountSwitch: (String) -> Unit,
    onManualOrder: (SignalDirection) -> Unit
) {
    val isReal = sessionPrefs.accountGroup == "real"
    val activeBalance = if (isReal) sessionPrefs.realBalance else sessionPrefs.demoBalance
    val lockedStake = HardLockedMandate.computeLockedStake(activeBalance)
    val zSpread = telemetry.zFast - telemetry.zSlow

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = when {
                    telemetry.hardStopReason != null -> BearishCrimson.copy(alpha = 0.7f)
                    botRunning -> BullishGreen.copy(alpha = 0.6f)
                    else -> SlateBorder
                },
                shape = RoundedCornerShape(16.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            // Price, Payout % & Account Switcher Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = telemetry.pair,
                            style = MaterialTheme.typography.titleMedium,
                            color = ElectricCyan
                        )
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = BullishGreen.copy(alpha = 0.14f),
                            modifier = Modifier.border(
                                1.dp,
                                BullishGreen.copy(alpha = 0.35f),
                                RoundedCornerShape(6.dp)
                            )
                        ) {
                            Text(
                                text = "+${telemetry.assetPayoutPercent}% YIELD",
                                style = MaterialTheme.typography.labelSmall,
                                color = BullishGreen,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (telemetry.latestPrice > 0) "%.4f".format(telemetry.latestPrice) else "STANDBY",
                        style = MaterialTheme.typography.headlineMedium,
                        color = when (telemetry.trend) {
                            SignalDirection.UP -> BullishGreen
                            SignalDirection.DOWN -> BearishCrimson
                            SignalDirection.NONE -> TextPrimary
                        }
                    )
                    Text(
                        text = "ZLEMA Δ ${"%+.4f".format(zSpread)} • Age ${"%.0f".format(telemetry.trendAgeSeconds)}s",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(SlateSurfaceVariant)
                            .border(
                                1.dp,
                                if (isReal) BearishCrimson.copy(alpha = 0.55f) else ElectricCyan.copy(alpha = 0.45f),
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { onRequestAccountSwitch(if (isReal) "demo" else "real") }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .testTag("toggle_account_group_button"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isReal) BearishCrimson else ElectricCyan)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (isReal) "REAL VAULT [D]" else "DEMO VAULT [D]",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isReal) BearishCrimson else ElectricCyan,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "$${"%,.2f".format(activeBalance)}",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = "Today P&L: $${"%+.2f".format(telemetry.dailyNetPnl.finiteOr(0.0))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (telemetry.dailyNetPnl >= 0.0) BullishGreen else BearishCrimson
                    )
                }
            }

            // Hard-Locked Mandate Readout Strip (Immutable 5% Stake, $1-$4000, 10 Trades/Day, 3 Losses Stop)
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = ObsidianBackground,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SlateBorder, RoundedCornerShape(10.dp))
                    .testTag("locked_mandate_strip")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Hard Locked Mandate",
                            tint = ElectricCyan,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "5% BAL STAKE: $${"%,.2f".format(lockedStake)} ($1–$4000) • 5S",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "OFI ${"%+.0f".format(telemetry.orderFlowImbalance.finiteOr(0.0))}% • LOCKED",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Hard-Stop Status Banner if 10 Trades/Day or 3 Consecutive Losses Reached
            if (telemetry.hardStopReason != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = BearishCrimsonDim,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, BearishCrimson, RoundedCornerShape(10.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = null,
                            tint = BearishCrimson,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "HARD STOP ACTIVE: ${telemetry.hardStopReason}",
                            style = MaterialTheme.typography.labelSmall,
                            color = BearishCrimson,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Live Bull/Bear Sentiment & 50-Tick Range Position Dual Bar
            val bullPct = telemetry.bullishSentimentPercent.coerceIn(1, 99)
            val relPosNorm = (telemetry.relPos.finiteOr(50.0) / 100.0).toFloat().finiteOr(0.5f).coerceIn(0f, 1f)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("BULL $bullPct%", style = MaterialTheme.typography.labelSmall, color = BullishGreen)
                        Text("BEAR ${100 - bullPct}%", style = MaterialTheme.typography.labelSmall, color = BearishCrimson)
                    }
                    SafeQuantProgressBar(
                        progress = bullPct / 100f,
                        color = BullishGreen,
                        trackColor = BearishCrimson,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                }

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("50T RANGE POS", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        Text("${"%.0f".format(telemetry.relPos.finiteOr(50.0))}%", style = MaterialTheme.typography.labelSmall, color = ElectricCyan)
                    }
                    SafeQuantProgressBar(
                        progress = relPosNorm,
                        color = ElectricCyan,
                        trackColor = SlateSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                }
            }

            // Bot Running [S] & Locked Quota / Loss Streak Guard Readout
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onToggleBot,
                    enabled = telemetry.hardStopReason == null || botRunning,
                    modifier = Modifier
                        .weight(1.2f)
                        .height(48.dp)
                        .testTag("toggle_bot_button"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (botRunning) BearishCrimson else BullishGreen,
                        disabledContainerColor = SlateSurfaceVariant
                    )
                ) {
                    Icon(
                        imageVector = if (botRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (botRunning) "Stop Bot" else "Start Bot",
                        tint = ObsidianBackground
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (botRunning) "STOP AUTO-BOT [S]" else "ARM AUTO-BOT [S]",
                        style = MaterialTheme.typography.labelLarge,
                        color = ObsidianBackground
                    )
                }

                // Locked Daily Quota & Loss Streak Guard Pill (Replaces removed Boost Mode)
                val quotaLocked = telemetry.hardStopReason != null
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = ObsidianBackground,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .border(
                            1.dp,
                            if (quotaLocked) BearishCrimson else SlateBorder,
                            RoundedCornerShape(10.dp)
                        )
                        .testTag("daily_quota_loss_pill")
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "TRADES: ${telemetry.dailyTradesCount}/${HardLockedMandate.MAX_TRADES_PER_DAY}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (telemetry.dailyTradesCount >= HardLockedMandate.MAX_TRADES_PER_DAY) BearishCrimson else TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "LOSS STREAK: ${telemetry.consecutiveLosses}/${HardLockedMandate.MAX_CONSECUTIVE_LOSSES}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (telemetry.consecutiveLosses >= 2) BearishCrimson else BullishGreen
                        )
                    }
                }
            }

            // Tactile Manual Execution Paddles (CALL UP / PUT DOWN using Locked 5% Stake)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    onClick = { onManualOrder(SignalDirection.UP) },
                    shape = RoundedCornerShape(10.dp),
                    color = BullishGreenDim,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .border(1.dp, BullishGreen.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                        .testTag("manual_call_up_button")
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Filled.TrendingUp, contentDescription = "Call Up", tint = BullishGreen)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("CALL UP", style = MaterialTheme.typography.labelLarge, color = BullishGreen)
                            Text("5s • $${"%.2f".format(lockedStake)}", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        }
                    }
                }

                Surface(
                    onClick = { onManualOrder(SignalDirection.DOWN) },
                    shape = RoundedCornerShape(10.dp),
                    color = BearishCrimsonDim,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .border(1.dp, BearishCrimson.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                        .testTag("manual_put_down_button")
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Filled.TrendingDown, contentDescription = "Put Down", tint = BearishCrimson)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("PUT DOWN", style = MaterialTheme.typography.labelLarge, color = BearishCrimson)
                            Text("5s • $${"%.2f".format(lockedStake)}", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveOpenTradesCard(
    openTrades: List<ActiveOpenTrade>,
    currentPrice: Double
) {
    var frameNowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(openTrades.isNotEmpty()) {
        while (isActive && openTrades.isNotEmpty()) {
            withFrameMillis {
                frameNowMs = System.currentTimeMillis()
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ElectricCyan.copy(alpha = 0.55f), RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Timer,
                        contentDescription = "Open Positions",
                        tint = ElectricCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "IN-FLIGHT CONTRACTS (${openTrades.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = ElectricCyan
                    )
                }
                Text(
                    text = "LIVE EXECUTION",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }

            openTrades.forEach { pos ->
                val totalDurationMs = (pos.expiresAtMs - pos.openedAtMs).coerceAtLeast(1000L)
                val remainingMs = (pos.expiresAtMs - frameNowMs).coerceAtLeast(0L)
                val remainingSec = remainingMs / 1000.0
                val progress = (remainingMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)

                val isItm = if (pos.direction == SignalDirection.UP) {
                    currentPrice > pos.entryPrice
                } else {
                    currentPrice < pos.entryPrice
                }
                val statusColor = if (isItm) BullishGreen else BearishCrimson
                val estPnl = if (isItm) pos.stake * (pos.payoutPercent / 100.0) else -pos.stake

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(ObsidianBackground)
                        .border(1.dp, statusColor.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${pos.pair} • ${pos.direction.name} $${"%.2f".format(pos.stake)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = "Strike: ${"%.4f".format(pos.entryPrice)} -> Spot: ${"%.4f".format(currentPrice)} (${"%+.4f".format(currentPrice - pos.entryPrice)})",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = statusColor.copy(alpha = 0.14f)
                        ) {
                            Text(
                                text = "${if (isItm) "ITM" else "OTM"} ${"%+.2f".format(estPnl)} • ${"%.1f".format(remainingSec)}s",
                                style = MaterialTheme.typography.labelMedium,
                                color = statusColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    SafeQuantProgressBar(
                        progress = progress,
                        color = statusColor,
                        trackColor = SlateSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                    )
                }
            }
        }
    }
}

@Composable
private fun MicroImpulseRadarCard(
    micro: MicrostructureSnapshot,
    recentTicks: List<Tick>
) {
    val dirColor = when (micro.impulseDirection) {
        SignalDirection.UP -> BullishGreen
        SignalDirection.DOWN -> BearishCrimson
        SignalDirection.NONE -> ElectricCyan
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, SlateBorder, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = "Ballistic Impulse Radar",
                        tint = ElectricCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "HAWKES-LAMINAR IMPULSE RADAR",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextPrimary
                    )
                }

                Surface(
                    shape = RoundedCornerShape(50),
                    color = dirColor.copy(alpha = 0.14f),
                    modifier = Modifier.border(1.dp, dirColor.copy(alpha = 0.45f), RoundedCornerShape(50))
                ) {
                    Text(
                        text = "${micro.impulseDirection.name} • ${micro.streakCount}T STREAK",
                        style = MaterialTheme.typography.labelSmall,
                        color = dirColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                RadialTelemetryGauge(
                    title = "IMPULSE",
                    valueText = "${"%.0f".format(micro.microImpulseScore)}%",
                    subText = micro.urgency.name,
                    progress = (micro.microImpulseScore / 100.0).toFloat().coerceIn(0f, 1f),
                    arcColor = dirColor,
                    modifier = Modifier.size(90.dp)
                )

                RadialTelemetryGauge(
                    title = "LAMINAR ER",
                    valueText = "${"%.0f".format(micro.directionalEfficiency)}%",
                    subText = "Acc ${"%+.1f".format(micro.microAcceleration)}",
                    progress = (micro.directionalEfficiency / 100.0).toFloat().coerceIn(0f, 1f),
                    arcColor = ElectricCyan,
                    modifier = Modifier.size(90.dp)
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "TAPE: ${micro.tapeSpeed} t/s",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextPrimary
                        )
                        Text(
                            text = micro.energyState.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    Text(
                        text = "PHASE: ${micro.phase.name} • ${micro.activeLandmark.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan
                    )

                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(ObsidianBackground)
                            .border(1.dp, SlateBorder, RoundedCornerShape(8.dp))
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        val tail = if (recentTicks.size > 19) recentTicks.takeLast(19) else recentTicks
                        if (tail.size >= 2) {
                            val deltas = ArrayList<Double>(tail.size - 1)
                            var maxAbs = 1e-6
                            for (i in 1 until tail.size) {
                                val d = tail[i].price - tail[i - 1].price
                                deltas.add(d)
                                val ad = abs(d)
                                if (ad > maxAbs) maxAbs = ad
                            }
                            val count = deltas.size
                            val slotW = size.width / max(1, count)
                            val barW = (slotW * 0.65f).coerceAtLeast(2f)
                            val midY = size.height / 2f

                            drawLine(
                                color = SlateBorder,
                                start = Offset(0f, midY),
                                end = Offset(size.width, midY),
                                strokeWidth = 1f
                            )

                            for (i in 0 until count) {
                                val d = deltas[i]
                                val norm = (abs(d) / maxAbs).toFloat().coerceIn(0.12f, 1f)
                                val barH = norm * (size.height * 0.45f)
                                val x = i * slotW + (slotW - barW) / 2f
                                val c = if (d >= 0) BullishGreen else BearishCrimson
                                val topY = if (d >= 0) midY - barH else midY
                                drawRect(
                                    color = c,
                                    topLeft = Offset(x, topY),
                                    size = Size(barW, max(2f, barH))
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RadialTelemetryGauge(
    title: String,
    valueText: String,
    subText: String,
    progress: Float,
    arcColor: Color,
    modifier: Modifier = Modifier
) {
    val trackStroke = remember { Stroke(width = 15f, cap = StrokeCap.Round) }
    val valueStroke = remember { Stroke(width = 15f, cap = StrokeCap.Round) }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(6.dp)) {
            drawArc(
                color = SlateSurfaceVariant,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                style = trackStroke
            )
            drawArc(
                color = arcColor,
                startAngle = 135f,
                sweepAngle = 270f * progress,
                useCenter = false,
                style = valueStroke
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = title, style = MaterialTheme.typography.labelSmall, color = TextMuted)
            Text(text = valueText, style = MaterialTheme.typography.labelLarge, color = arcColor)
            Text(text = subText, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
    }
}

@Composable
private fun QuantChartCard(
    telemetry: MarketTelemetryState,
    onStartImpulseStream: () -> Unit
) {
    var chartMode by remember { mutableStateOf(ChartRenderMode.DUAL_PRO) }
    var scrubbedIndex by remember { mutableIntStateOf(-1) }

    val fastZlemaPath = remember { Path() }
    val areaFillPath = remember { Path() }
    val tickRibbonPath = remember { Path() }
    val gridDashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f) }
    val fibDashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(4f, 6f), 0f) }
    val crosshairDashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f) }

    val candles = remember(telemetry.candles) { telemetry.candles.takeLast(48) }
    val recentTicks = remember(telemetry.recentTicks) { telemetry.recentTicks.takeLast(80) }

    val inspectedCandle = if (scrubbedIndex in candles.indices) candles[scrubbedIndex] else candles.lastOrNull()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, SlateBorder, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "AMOLED HFT PRICE MATRIX & 50T FIBONACCI",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextPrimary
                    )
                    if (inspectedCandle != null) {
                        Text(
                            text = "O:${"%.4f".format(inspectedCandle.open)}  H:${"%.4f".format(inspectedCandle.high)}  L:${"%.4f".format(inspectedCandle.low)}  C:${"%.4f".format(inspectedCandle.close)} (${inspectedCandle.tickCount}t)",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (inspectedCandle.isBullish) BullishGreen else BearishCrimson
                        )
                    } else {
                        Text(
                            text = "Touch & drag chart to inspect 5s candles & ballistic corridor",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(SlateSurfaceVariant)
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    ChartRenderMode.entries.forEach { mode ->
                        val active = chartMode == mode
                        Surface(
                            onClick = { chartMode = mode },
                            shape = RoundedCornerShape(6.dp),
                            color = if (active) ElectricCyan else Color.Transparent
                        ) {
                            Text(
                                text = mode.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (active) TextPrimary else TextSecondary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            if (candles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(225.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ObsidianBackground)
                        .border(1.dp, SlateBorder, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Stream offline — Connect Olymp Trade WS or start Ballistic Impulse Stream",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextMuted
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = onStartImpulseStream,
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = TextPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "START BALLISTIC IMPULSE STREAM",
                                color = TextPrimary,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            } else {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(225.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ObsidianBackground)
                        .border(1.dp, SlateBorder, RoundedCornerShape(12.dp))
                        .pointerInput(candles.size) {
                            detectTapGestures(
                                onPress = { offset ->
                                    val idx = ((offset.x / size.width) * candles.size).toInt()
                                        .coerceIn(0, max(0, candles.size - 1))
                                    scrubbedIndex = idx
                                    tryAwaitRelease()
                                    scrubbedIndex = -1
                                }
                            )
                        }
                        .pointerInput(candles.size) {
                            detectHorizontalDragGestures(
                                onDragEnd = { scrubbedIndex = -1 },
                                onDragCancel = { scrubbedIndex = -1 },
                                onHorizontalDrag = { change, _ ->
                                    val idx = ((change.position.x / size.width) * candles.size).toInt()
                                        .coerceIn(0, max(0, candles.size - 1))
                                    scrubbedIndex = idx
                                }
                            )
                        }
                        .padding(10.dp)
                ) {
                    val w = size.width
                    val h = size.height
                    var minP = candles.minOf { it.low }
                    var maxP = candles.maxOf { it.high }
                    if (chartMode == ChartRenderMode.TICK_WAVE && recentTicks.isNotEmpty()) {
                        minP = min(minP, recentTicks.minOf { it.price })
                        maxP = max(maxP, recentTicks.maxOf { it.price })
                    }
                    val pad = ((maxP - minP) * 0.12).coerceAtLeast(1e-5)
                    minP -= pad
                    maxP += pad
                    val span = (maxP - minP).coerceAtLeast(1e-6)

                    fun priceToY(p: Double): Float {
                        return (h - ((p - minP) / span * h)).toFloat().coerceIn(0f, h)
                    }

                    // 1. Psychological Round-Number Grid
                    telemetry.psychGridLevels.forEach { lvl ->
                        if (lvl in minP..maxP) {
                            val y = priceToY(lvl)
                            drawLine(
                                color = SlateBorder.copy(alpha = 0.5f),
                                start = Offset(0f, y),
                                end = Offset(w, y),
                                strokeWidth = 1f,
                                pathEffect = gridDashEffect
                            )
                        }
                    }

                    // 2. 50-Tick Fibonacci Band (Subtle Blue)
                    val fib = telemetry.fibLevels
                    if (fib.high50 > fib.low50) {
                        val y382 = priceToY(fib.fib382)
                        val y618 = priceToY(fib.fib618)
                        drawRect(
                            color = ElectricCyan.copy(alpha = 0.05f),
                            topLeft = Offset(0f, min(y382, y618)),
                            size = Size(w, max(2f, abs(y618 - y382)))
                        )
                        listOf(fib.fib382, fib.fib500, fib.fib618).forEach { fLvl ->
                            if (fLvl in minP..maxP) {
                                val y = priceToY(fLvl)
                                drawLine(
                                    color = ElectricCyan.copy(alpha = 0.32f),
                                    start = Offset(0f, y),
                                    end = Offset(w, y),
                                    strokeWidth = 1f,
                                    pathEffect = fibDashEffect
                                )
                            }
                        }
                    }

                    // 3. Nearest Support & Resistance Corridors
                    telemetry.nearestSupport?.let { sup ->
                        if (sup.centerPrice in minP..maxP) {
                            val topY = priceToY(sup.upperBound)
                            val botY = priceToY(sup.lowerBound)
                            drawRect(
                                color = BullishGreen.copy(alpha = 0.09f),
                                topLeft = Offset(0f, min(topY, botY)),
                                size = Size(w, max(2f, abs(botY - topY)))
                            )
                        }
                    }
                    telemetry.nearestResistance?.let { res ->
                        if (res.centerPrice in minP..maxP) {
                            val topY = priceToY(res.upperBound)
                            val botY = priceToY(res.lowerBound)
                            drawRect(
                                color = BearishCrimson.copy(alpha = 0.09f),
                                topLeft = Offset(0f, min(topY, botY)),
                                size = Size(w, max(2f, abs(botY - topY)))
                            )
                        }
                    }

                    // 4. Active Open Trade Entry Strike Lines
                    telemetry.activeOpenTrades.forEach { pos ->
                        if (pos.entryPrice in minP..maxP) {
                            val y = priceToY(pos.entryPrice)
                            drawLine(
                                color = if (pos.direction == SignalDirection.UP) BullishGreen else BearishCrimson,
                                start = Offset(0f, y),
                                end = Offset(w, y),
                                strokeWidth = 2.2f
                            )
                        }
                    }

                    // 5. Render Candles and/or High-Frequency Tick Ribbon
                    if (chartMode == ChartRenderMode.CANDLES_5S || chartMode == ChartRenderMode.DUAL_PRO) {
                        val count = candles.size
                        val stepX = w / max(1, count)
                        val candleW = (stepX * 0.66f).coerceAtLeast(3f)

                        fastZlemaPath.reset()
                        areaFillPath.reset()

                        candles.forEachIndexed { idx, c ->
                            val cx = idx * stepX + stepX / 2f
                            val highY = priceToY(c.high)
                            val lowY = priceToY(c.low)
                            val openY = priceToY(c.open)
                            val closeY = priceToY(c.close)
                            val color = if (c.isBullish) BullishGreen else BearishCrimson

                            drawLine(
                                color = color,
                                start = Offset(cx, highY),
                                end = Offset(cx, lowY),
                                strokeWidth = 1.4f
                            )
                            val top = min(openY, closeY)
                            val bodyH = max(2.5f, abs(closeY - openY))
                            drawRect(
                                color = color,
                                topLeft = Offset(cx - candleW / 2f, top),
                                size = Size(candleW, bodyH)
                            )

                            if (idx == 0) {
                                fastZlemaPath.moveTo(cx, closeY)
                                areaFillPath.moveTo(cx, h)
                                areaFillPath.lineTo(cx, closeY)
                            } else {
                                fastZlemaPath.lineTo(cx, closeY)
                                areaFillPath.lineTo(cx, closeY)
                            }
                            if (idx == count - 1) {
                                areaFillPath.lineTo(cx, h)
                                areaFillPath.close()
                            }
                        }

                        drawPath(
                            path = areaFillPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    ElectricCyan.copy(alpha = 0.12f),
                                    Color.Transparent
                                )
                            )
                        )
                        drawPath(
                            path = fastZlemaPath,
                            color = ElectricCyan.copy(alpha = 0.8f),
                            style = Stroke(width = 2f)
                        )
                    }

                    if ((chartMode == ChartRenderMode.TICK_WAVE || chartMode == ChartRenderMode.DUAL_PRO) && recentTicks.size >= 2) {
                        tickRibbonPath.reset()
                        val tCount = recentTicks.size
                        val tStepX = w / max(1, tCount - 1)
                        recentTicks.forEachIndexed { idx, t ->
                            val tx = idx * tStepX
                            val ty = priceToY(t.price)
                            if (idx == 0) tickRibbonPath.moveTo(tx, ty) else tickRibbonPath.lineTo(tx, ty)
                        }
                        drawPath(
                            path = tickRibbonPath,
                            color = ElectricCyan.copy(alpha = 0.7f),
                            style = Stroke(width = if (chartMode == ChartRenderMode.TICK_WAVE) 2.2f else 1.3f)
                        )
                    }

                    // 6. Static Live Price Marker on Right Edge
                    if (telemetry.latestPrice in minP..maxP) {
                        val liveY = priceToY(telemetry.latestPrice)
                        val liveColor = when (telemetry.trend) {
                            SignalDirection.UP -> BullishGreen
                            SignalDirection.DOWN -> BearishCrimson
                            SignalDirection.NONE -> ElectricCyan
                        }
                        drawCircle(
                            color = liveColor,
                            radius = 4.5f,
                            center = Offset(w - 4f, liveY)
                        )
                    }

                    // 7. Interactive Crosshair Scrubber Overlay
                    if (scrubbedIndex in candles.indices) {
                        val stepX = w / max(1, candles.size)
                        val cx = scrubbedIndex * stepX + stepX / 2f
                        val cy = priceToY(candles[scrubbedIndex].close)
                        drawLine(
                            color = ElectricCyan,
                            start = Offset(cx, 0f),
                            end = Offset(cx, h),
                            strokeWidth = 1.4f,
                            pathEffect = crosshairDashEffect
                        )
                        drawLine(
                            color = ElectricCyan.copy(alpha = 0.6f),
                            start = Offset(0f, cy),
                            end = Offset(w, cy),
                            strokeWidth = 1f,
                            pathEffect = crosshairDashEffect
                        )
                        drawCircle(
                            color = ElectricCyan,
                            radius = 4.5f,
                            center = Offset(cx, cy)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryMetricsGrid(telemetry: MarketTelemetryState) {
    val qualityColor = when (telemetry.sessionQuality) {
        SessionQuality.OPTIMAL -> BullishGreen
        SessionQuality.NEUTRAL -> TextSecondary
        SessionQuality.AVOID -> BearishCrimson
    }
    val hmm = telemetry.hmmOutput

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricTile(
                label = "HVOI SESSION",
                value = "${"%.1f".format(telemetry.hvoiScore)}% (${telemetry.sessionQuality.name})",
                progress = (telemetry.hvoiScore / 100.0).toFloat().coerceIn(0f, 1f),
                valueColor = qualityColor,
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                label = "HMM REGIME",
                value = if (hmm != null) "S${hmm.state} ${hmm.regimeName} (${(hmm.confidence * 100).toInt()}%)" else "Warming Up",
                progress = (hmm?.confidence ?: 0.25).toFloat().coerceIn(0f, 1f),
                valueColor = ElectricCyan,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricTile(
                label = "CONSISTENCY",
                value = "${"%.2f".format(telemetry.consistency)} (Pers: ${telemetry.persistence}t)",
                progress = telemetry.consistency.toFloat().coerceIn(0f, 1f),
                valueColor = if (telemetry.consistency >= 0.70) BullishGreen else TextSecondary,
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                label = "SLOPE RATIO",
                value = "${"%.2f".format(telemetry.slopeRatio)}x | S/R ${"%.2f".format(telemetry.srDistance)}",
                progress = (1f - (telemetry.slopeRatio / 5.0).toFloat()).coerceIn(0.1f, 1f),
                valueColor = if (telemetry.slopeRatio < 3.15) BullishGreen else BearishCrimson,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    progress: Float,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.border(1.dp, SlateBorder, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        color = SlateSurface
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
            Spacer(Modifier.height(4.dp))
            Text(text = value, style = MaterialTheme.typography.labelLarge, color = valueColor)
            Spacer(Modifier.height(6.dp))
            SafeQuantProgressBar(
                progress = progress,
                color = valueColor,
                trackColor = SlateSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
            )
        }
    }
}

@Composable
private fun GatekeeperPipelineCard(evaluation: StrategyEvaluation) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (evaluation.readyToExecute) BullishGreen.copy(alpha = 0.6f) else SlateBorder,
                RoundedCornerShape(14.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "LOCKED ENGINE: ${evaluation.strategyName.uppercase()}",
                        style = MaterialTheme.typography.labelLarge,
                        color = ElectricCyan
                    )
                    Text(
                        text = evaluation.triggerReason,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (evaluation.confidenceScore >= evaluation.minConfidenceRequired) {
                        BullishGreen.copy(alpha = 0.14f)
                    } else {
                        SlateSurfaceVariant
                    }
                ) {
                    Text(
                        text = "CONF ${"%.0f".format(evaluation.confidenceScore)}/${"%.0f".format(evaluation.minConfidenceRequired)}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (evaluation.confidenceScore >= evaluation.minConfidenceRequired) BullishGreen else TextSecondary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            evaluation.filterDiagnostics.forEach { diag ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(ObsidianBackground)
                        .border(1.dp, SlateBorder, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = if (diag.passed) Icons.Default.CheckCircle else Icons.Default.Close,
                            contentDescription = null,
                            tint = if (diag.passed) BullishGreen else BearishCrimson,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = diag.filterType.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = diag.reason,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }
                    Text(
                        text = "${diag.actualValueFormatted} (${diag.thresholdFormatted})",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (diag.passed) BullishGreen else BearishCrimson
                    )
                }
            }
        }
    }
}
