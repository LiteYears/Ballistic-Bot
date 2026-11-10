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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.olympquant.terminal.data.SessionPreferences
import com.olympquant.terminal.domain.ActiveOpenTrade
import com.olympquant.terminal.domain.HardLockedMandate
import com.olympquant.terminal.domain.HighVoltageFilterStatus
import com.olympquant.terminal.domain.HvMarketTrap
import com.olympquant.terminal.domain.HvWindowSnapshot
import com.olympquant.terminal.domain.HvWindowState
import com.olympquant.terminal.domain.MarketTelemetryState
import com.olympquant.terminal.domain.MicrostructureSnapshot
import com.olympquant.terminal.domain.SessionQuality
import com.olympquant.terminal.domain.SignalDirection
import com.olympquant.terminal.domain.StrategyConfig
import com.olympquant.terminal.domain.StrategyEvaluation
import com.olympquant.terminal.domain.TerminalDensityMode
import com.olympquant.terminal.domain.Tick
import com.olympquant.terminal.domain.TradeTimeframe
import com.olympquant.terminal.domain.finiteOr
import com.olympquant.terminal.network.WsConnectionStatus
import com.olympquant.terminal.ui.theme.BearishCrimson
import com.olympquant.terminal.ui.theme.BearishCrimsonDim
import com.olympquant.terminal.ui.theme.BullishGreen
import com.olympquant.terminal.ui.theme.BullishGreenDim
import com.olympquant.terminal.ui.theme.ElectricCyan
import com.olympquant.terminal.ui.theme.JetBrainsMonoFamily
import com.olympquant.terminal.ui.theme.ObsidianBackground
import com.olympquant.terminal.ui.theme.ObsidianElevated
import com.olympquant.terminal.ui.theme.SlateBorder
import com.olympquant.terminal.ui.theme.SlateBorderBright
import com.olympquant.terminal.ui.theme.SlateSurface
import com.olympquant.terminal.ui.theme.SlateSurfaceVariant
import com.olympquant.terminal.ui.theme.TextMuted
import com.olympquant.terminal.ui.theme.TextPrimary
import com.olympquant.terminal.ui.theme.TextSecondary
import com.olympquant.terminal.ui.theme.WarningAmber
import com.olympquant.terminal.ui.theme.WarningAmberDim
import kotlinx.coroutines.delay
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
    loadedStrategy: StrategyConfig = HardLockedMandate.lockedStrategyConfig(),
    connectionStatus: WsConnectionStatus,
    wsLatencyMs: Long,
    botRunning: Boolean,
    availablePairs: List<String>,
    lastExecutionBanner: String?,
    onSelectPair: (String) -> Unit,
    onSelectTimeframe: (Int) -> Unit = {},
    onToggleAutoPauseOnAvoid: (Boolean) -> Unit = {},
    onToggleHourlySniper: () -> Unit = {},
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
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // =========================================================================
        // 1. CONNECTION & MARKET STATUS HEADER
        // =========================================================================
        item(key = "top_bar", contentType = "header") {
            ConnectionAndMarketStatusBar(
                connectionStatus = connectionStatus,
                wsLatencyMs = wsLatencyMs,
                telemetry = telemetry,
                densityMode = sessionPrefs.densityMode,
                onNavigateToBrokerSetup = onNavigateToBrokerSetup,
                onStartImpulseStream = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onStartImpulseStream()
                },
                onSetDensityMode = { mode ->
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSetDensityMode(mode)
                }
            )
        }

        // Execution Toast Banner
        if (lastExecutionBanner != null) {
            item(key = "toast_banner", contentType = "banner") {
                AnimatedVisibility(visible = true) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = ObsidianElevated,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, SlateBorderBright, RoundedCornerShape(10.dp))
                            .clickable { onDismissBanner() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
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
                                    modifier = Modifier.size(15.dp)
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
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            }
        }

        // Asset Pair Selector Ribbon
        item(key = "pair_ribbon", contentType = "ribbon") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
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

        // =========================================================================
        // 2. ACTIVE STRATEGY & SIGNAL STATUS + EXECUTION DECK (WITH TIMEFRAME EXPANSION)
        // =========================================================================
        item(key = "execution_deck", contentType = "deck") {
            ExecutionControlDeckCard(
                sessionPrefs = sessionPrefs,
                telemetry = telemetry,
                loadedStrategy = loadedStrategy,
                evaluation = primaryEvaluation,
                botRunning = botRunning,
                onSelectTimeframe = { sec ->
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSelectTimeframe(sec)
                },
                onToggleHourlySniper = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggleHourlySniper()
                },
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

        // =========================================================================
        // 2B. HV WINDOW CO-PILOT & HIGHVOLTAGE 6-FILTER STRUCTURAL RADAR
        // =========================================================================
        item(key = "hv_window_copilot", contentType = "hv_window") {
            HvWindowCoPilotCard(
                telemetry = telemetry,
                autoPauseOnAvoid = sessionPrefs.autoPauseOnHvAvoid,
                onToggleAutoPauseOnAvoid = { enabled ->
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onToggleAutoPauseOnAvoid(enabled)
                }
            )
        }

        // In-Flight Open Contracts Tracker (Throttled 10Hz timer — zero layout thrashing)
        if (telemetry.activeOpenTrades.isNotEmpty()) {
            item(key = "active_open_trades", contentType = "open_trades") {
                ActiveOpenTradesCard(
                    openTrades = telemetry.activeOpenTrades,
                    currentPrice = telemetry.latestPrice
                )
            }
        }

        // =========================================================================
        // 3. LIVE MARKET INTELLIGENCE & KEY INDICATORS
        // =========================================================================
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

        item(key = "market_intelligence_cluster", contentType = "intelligence") {
            ConsolidatedMarketIntelligenceCard(
                telemetry = telemetry,
                micro = telemetry.microstructure,
                recentTicks = telemetry.recentTicks
            )
        }

        // =========================================================================
        // 5. VALIDATION RESULTS & EXECUTION DIAGNOSTICS (COLLAPSIBLE)
        // =========================================================================
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
                Text("Automated and manual orders will execute using real funds (${loadedStrategy.stakeMode}: ${loadedStrategy.stakeValue}, min $${loadedStrategy.minStakeUsd.toInt()}, max $${loadedStrategy.maxStakeUsd.toInt()}). Confirm switch?")
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
private fun ConnectionAndMarketStatusBar(
    connectionStatus: WsConnectionStatus,
    wsLatencyMs: Long,
    telemetry: MarketTelemetryState,
    densityMode: TerminalDensityMode,
    onNavigateToBrokerSetup: () -> Unit,
    onStartImpulseStream: () -> Unit,
    onSetDensityMode: (TerminalDensityMode) -> Unit
) {
    val isStreamActive = connectionStatus == WsConnectionStatus.AUTHENTICATED ||
        connectionStatus == WsConnectionStatus.SYNTHETIC_FEED
    val isSynced = telemetry.wsSynchronized && telemetry.temporalTelemetry.snapshotSynchronized
    val effectiveTps = if (telemetry.tickRateTps > 0.0) {
        telemetry.tickRateTps
    } else {
        telemetry.microstructure.tapeSpeed.toDouble()
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = SlateSurface,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, SlateBorder, RoundedCornerShape(12.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
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
                        .padding(end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
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
                            onClick = onStartImpulseStream,
                            shape = RoundedCornerShape(50),
                            color = ElectricCyan.copy(alpha = 0.14f),
                            modifier = Modifier
                                .border(1.dp, ElectricCyan.copy(alpha = 0.5f), RoundedCornerShape(50))
                                .testTag("quick_impulse_stream_chip")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bolt,
                                    contentDescription = "Start Impulse Stream",
                                    tint = ElectricCyan,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    text = "STREAM",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ElectricCyan,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }

                // Density Mode Switcher (Pro Terminal / Minimal Deck / Split All)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(SlateSurfaceVariant)
                        .border(1.dp, SlateBorder, RoundedCornerShape(8.dp))
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    TerminalDensityMode.entries.forEach { mode ->
                        val label = when (mode) {
                            TerminalDensityMode.PRO_TERMINAL -> "PRO"
                            TerminalDensityMode.MINIMAL_EXECUTION -> "DECK"
                            TerminalDensityMode.SPLIT_ALL -> "ALL"
                        }
                        val active = densityMode == mode
                        Surface(
                            onClick = { onSetDensityMode(mode) },
                            shape = RoundedCornerShape(6.dp),
                            color = if (active) ElectricCyan else Color.Transparent,
                            modifier = Modifier.testTag("density_mode_${mode.name.lowercase()}")
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (active) TextPrimary else TextSecondary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            // Live Telemetry Status Bar: Tick Rate, Snapshot Sync, Asset Lock & Yield
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusMetricPill(
                        label = "SYNC",
                        value = when {
                            !isStreamActive -> "OFFLINE"
                            isSynced -> "SYNCED"
                            else -> "WARMING"
                        },
                        color = when {
                            !isStreamActive -> TextMuted
                            isSynced -> BullishGreen
                            else -> ElectricCyan
                        }
                    )
                    StatusMetricPill(
                        label = "RATE",
                        value = "${"%.1f".format(effectiveTps.finiteOr(0.0))} T/s",
                        color = if (effectiveTps >= 1.0) ElectricCyan else TextSecondary
                    )
                    StatusMetricPill(
                        label = "RTT",
                        value = "${wsLatencyMs}ms",
                        color = when {
                            wsLatencyMs <= 120L -> BullishGreen
                            wsLatencyMs <= 250L -> ElectricCyan
                            else -> BearishCrimson
                        }
                    )
                }

                Text(
                    text = if (telemetry.assetLocked) {
                        "MARKET SUSPENDED"
                    } else {
                        "${telemetry.pair} • +${telemetry.assetPayoutPercent}% YIELD"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (telemetry.assetLocked) BearishCrimson else BullishGreen,
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun StatusMetricPill(
    label: String,
    value: String,
    color: Color
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ExecutionControlDeckCard(
    sessionPrefs: SessionPreferences,
    telemetry: MarketTelemetryState,
    loadedStrategy: StrategyConfig,
    evaluation: StrategyEvaluation?,
    botRunning: Boolean,
    onSelectTimeframe: (Int) -> Unit = {},
    onToggleHourlySniper: () -> Unit = {},
    onToggleBot: () -> Unit,
    onRequestAccountSwitch: (String) -> Unit,
    onManualOrder: (SignalDirection) -> Unit
) {
    val isReal = sessionPrefs.accountGroup == "real"
    val activeBalance = if (isReal) sessionPrefs.realBalance else sessionPrefs.demoBalance
    val lockedStake = loadedStrategy.computeEffectiveStake(activeBalance)
    val selectedTf = TradeTimeframe.fromSeconds(5)
    val durationSec = selectedTf.seconds
    val sniperActive = sessionPrefs.hourlySniperMode || telemetry.hourlySniperMode
    val maxDailyTrades = if (sniperActive) max(24, loadedStrategy.maxDailyTrades) else loadedStrategy.maxDailyTrades
    val maxLossStreak = loadedStrategy.maxConsecutiveLosses
    val zSpread = telemetry.zFast - telemetry.zSlow
    val t = evaluation?.temporalTelemetry ?: telemetry.temporalTelemetry
    val hvWin = telemetry.hvWindow

    val signalDir = evaluation?.finalSignal ?: SignalDirection.NONE
    val signalColor = when (signalDir) {
        SignalDirection.UP -> BullishGreen
        SignalDirection.DOWN -> BearishCrimson
        SignalDirection.NONE -> ElectricCyan
    }

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
                shape = RoundedCornerShape(14.dp)
            ),
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. Asset Price & Account Balance Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = telemetry.pair,
                            style = MaterialTheme.typography.titleMedium,
                            color = ElectricCyan,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = signalColor.copy(alpha = 0.14f),
                            modifier = Modifier.border(
                                1.dp,
                                signalColor.copy(alpha = 0.4f),
                                RoundedCornerShape(6.dp)
                            )
                        ) {
                            Text(
                                text = when (signalDir) {
                                    SignalDirection.UP -> "SIGNAL: CALL UP (${selectedTf.label})"
                                    SignalDirection.DOWN -> "SIGNAL: PUT DOWN (${selectedTf.label})"
                                    SignalDirection.NONE -> "HV ${"%.0f".format(hvWin.score)}% • ${hvWin.state.label}"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = signalColor,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
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
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "ZLEMA Δ ${"%+.4f".format(zSpread)} • Vel ${"%.2f".format(telemetry.velocity.finiteOr(0.0))}T/s • RelPos ${"%.0f".format(telemetry.relPos.finiteOr(50.0))}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
                            text = if (isReal) "REAL VAULT" else "DEMO VAULT",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isReal) BearishCrimson else ElectricCyan,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "$${"%,.2f".format(activeBalance)}",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Today P&L: $${"%+.2f".format(telemetry.dailyNetPnl.finiteOr(0.0))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (telemetry.dailyNetPnl >= 0.0) BullishGreen else BearishCrimson,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // 1B. Trade Timeframe Expansion Bar (5s • 15s • 30s • 45s • 1m)
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = ObsidianBackground,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SlateBorder, RoundedCornerShape(10.dp))
                    .testTag("trade_timeframe_expansion_bar")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Timer,
                                contentDescription = "Trade Timeframe Expansion",
                                tint = ElectricCyan,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = "SNIPER M FIXED EXPIRY",
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricCyan,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = selectedTf.title,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            fontFamily = JetBrainsMonoFamily
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TradeTimeframe.entries.filter { it.seconds == 5 }.forEach { tf ->
                            val isSelected = tf == selectedTf
                            Surface(
                                onClick = { onSelectTimeframe(tf.seconds) },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) ElectricCyan.copy(alpha = 0.20f) else SlateSurfaceVariant,
                                modifier = Modifier
                                    .weight(1f)
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) ElectricCyan else SlateBorder,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .testTag("timeframe_chip_${tf.label}")
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = tf.label.uppercase(),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (isSelected) ElectricCyan else TextPrimary,
                                        fontFamily = JetBrainsMonoFamily,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${tf.seconds}s",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isSelected) TextPrimary else TextMuted,
                                        fontFamily = JetBrainsMonoFamily
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2. Active Strategy & Signal Status Strip
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = ObsidianBackground,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SlateBorder, RoundedCornerShape(10.dp))
                    .testTag("locked_mandate_strip")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
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
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Active Strategy Mandate",
                                tint = ElectricCyan,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "${loadedStrategy.name} • $${"%,.2f".format(lockedStake)} • ${selectedTf.label.uppercase()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = "CONF ${"%.0f".format((evaluation?.confidenceScore ?: hvWin.score).finiteOr(0.0))}/${"%.0f".format((evaluation?.minConfidenceRequired ?: loadedStrategy.minConfidence).finiteOr(50.0))}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = if ((evaluation?.confidenceScore ?: hvWin.score) >= (evaluation?.minConfidenceRequired ?: loadedStrategy.minConfidence)) BullishGreen else TextSecondary,
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }

                    // Compact Sniper M Status Pills
                    val hvStatus = telemetry.highVoltageStatus
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        X13LayerChip(
                            title = "1. SMOOTH TICK",
                            value = "${hvStatus.zlemaTrend.name} (${"%+.4f".format(hvStatus.zlemaSpread)})",
                            passed = hvStatus.trendFilterPassed,
                            modifier = Modifier.weight(1f)
                        )
                        X13LayerChip(
                            title = "2. MOMENTUM",
                            value = if (hvStatus.activeMomentumPassed) "ALIGNED" else "WAIT",
                            passed = hvStatus.activeMomentumPassed,
                            modifier = Modifier.weight(1f)
                        )
                        X13LayerChip(
                            title = "3. TICK SURGE",
                            value = "${"%.2f".format(hvStatus.velocityTps)} T/s",
                            passed = hvStatus.velocityFilterPassed && hvStatus.positionBreakoutPassed,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Hard-Stop Status Banner if Daily Trade Cap or Loss Streak Reached
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

            // 3. Bot Arm/Standby Control, Autopilot Guard Status & Single-Trade Lock
            val isArmedOrStandby = botRunning || telemetry.autopilotStandby
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onToggleBot,
                    enabled = telemetry.hardStopReason == null || isArmedOrStandby,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier
                        .weight(1.15f)
                        .heightIn(min = 48.dp)
                        .testTag("toggle_bot_button"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when {
                            telemetry.autopilotStandby -> WarningAmber
                            botRunning -> BearishCrimson
                            else -> BullishGreen
                        },
                        disabledContainerColor = SlateSurfaceVariant
                    )
                ) {
                    Icon(
                        imageVector = if (isArmedOrStandby) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (isArmedOrStandby) "Stop Bot" else "Start Bot",
                        tint = ObsidianBackground,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = when {
                            telemetry.autopilotStandby -> "AUTO-HOLD (STOP)"
                            botRunning -> "STOP AUTO-BOT"
                            else -> "ARM AUTO-BOT"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = ObsidianBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                val quotaLocked = telemetry.hardStopReason != null
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = ObsidianBackground,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .border(
                            1.dp,
                            when {
                                quotaLocked -> BearishCrimson
                                telemetry.singleTradeLocked -> WarningAmber
                                else -> SlateBorder
                            },
                            RoundedCornerShape(10.dp)
                        )
                        .testTag("daily_quota_loss_pill")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (telemetry.singleTradeLocked) {
                                "1-TRADE LOCK: ACTIVE (1/1)"
                            } else {
                                "1-TRADE LOCK: READY • ${telemetry.dailyTradesCount}/$maxDailyTrades"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (telemetry.singleTradeLocked) WarningAmber else TextPrimary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "LOSS STREAK: ${telemetry.consecutiveLosses}/$maxLossStreak",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (telemetry.consecutiveLosses >= max(1, maxLossStreak - 1)) BearishCrimson else BullishGreen,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // 3B. Dedicated CHRONO SNIPER (1 TRADE / HOUR EVERY DAY) Mode Button
            val sniperSlotLocked = sniperActive && telemetry.hourlyTradesThisHour >= 1
            val sniperAccent = when {
                !sniperActive -> ElectricCyan
                sniperSlotLocked -> WarningAmber
                else -> BullishGreen
            }
            Surface(
                onClick = onToggleHourlySniper,
                shape = RoundedCornerShape(10.dp),
                color = if (sniperActive) sniperAccent.copy(alpha = 0.14f) else ObsidianBackground,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp)
                    .border(
                        width = 1.dp,
                        color = if (sniperActive) sniperAccent.copy(alpha = 0.8f) else SlateBorder,
                        shape = RoundedCornerShape(10.dp)
                    )
                    .testTag("toggle_hourly_sniper_button")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
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
                            imageVector = Icons.Default.Timer,
                            contentDescription = "Chrono Sniper 1 Trade Per Hour Mode",
                            tint = sniperAccent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = if (sniperActive) {
                                    "CHRONO SNIPER (1/HR): ACTIVE"
                                } else {
                                    "ACTIVATE CHRONO SNIPER (1 TRADE / HR)"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = sniperAccent,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (sniperActive) {
                                    if (sniperSlotLocked) {
                                        "1/1 Trade Taken This Hour • Next Hourly Slot in ${telemetry.nextHourlyResetMinutes}m (24/7)"
                                    } else {
                                        "0/1 Trade This Hour • Hunting 1 Optimal Entry Each Hour Every Day"
                                    }
                                } else {
                                    "Tap to enter 24/7 Hourly Sniper Mode (Strictly 1 trade each hour every day)"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = sniperAccent.copy(alpha = 0.18f),
                        modifier = Modifier.border(1.dp, sniperAccent.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    ) {
                        Text(
                            text = if (sniperActive) "${telemetry.hourlyTradesThisHour}/1 HR" else "OFF",
                            style = MaterialTheme.typography.labelSmall,
                            color = sniperAccent,
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // 4. Tactile Manual Execution Paddles (CALL UP / PUT DOWN)
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
                        .heightIn(min = 50.dp)
                        .border(1.dp, BullishGreen.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                        .testTag("manual_call_up_button")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Filled.TrendingUp, contentDescription = "Call Up", tint = BullishGreen)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                "CALL UP (${selectedTf.label})",
                                style = MaterialTheme.typography.labelLarge,
                                color = BullishGreen,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${durationSec}s • $${"%.2f".format(lockedStake)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Surface(
                    onClick = { onManualOrder(SignalDirection.DOWN) },
                    shape = RoundedCornerShape(10.dp),
                    color = BearishCrimsonDim,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 50.dp)
                        .border(1.dp, BearishCrimson.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                        .testTag("manual_put_down_button")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.AutoMirrored.Filled.TrendingDown, contentDescription = "Put Down", tint = BearishCrimson)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                "PUT DOWN (${selectedTf.label})",
                                style = MaterialTheme.typography.labelLarge,
                                color = BearishCrimson,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${durationSec}s • $${"%.2f".format(lockedStake)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HvWindowCoPilotCard(
    telemetry: MarketTelemetryState,
    autoPauseOnAvoid: Boolean,
    onToggleAutoPauseOnAvoid: (Boolean) -> Unit
) {
    val hv = telemetry.hvWindow
    val filters = telemetry.highVoltageStatus
    var showGuide by rememberSaveable { mutableStateOf(false) }

    val stateColor = when (hv.state) {
        HvWindowState.EXCELLENT -> BullishGreen
        HvWindowState.MODERATE -> WarningAmber
        HvWindowState.AVOID -> BearishCrimson
    }
    val stateDimColor = when (hv.state) {
        HvWindowState.EXCELLENT -> BullishGreenDim
        HvWindowState.MODERATE -> WarningAmberDim
        HvWindowState.AVOID -> BearishCrimsonDim
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, stateColor.copy(alpha = 0.65f), RoundedCornerShape(14.dp))
            .testTag("hv_window_copilot_card"),
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. Header: HV Window State Badge, Fast-Decay EMA Score (alpha=0.08), Stability Hold Timer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = stateDimColor,
                        modifier = Modifier.border(1.dp, stateColor, RoundedCornerShape(8.dp))
                    ) {
                        Text(
                            text = "HV WINDOW: ${hv.state.label}",
                            style = MaterialTheme.typography.labelMedium,
                            color = stateColor,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "${"%.1f".format(hv.score)}% EMA (Raw ${"%.0f".format(hv.rawScore)}%)",
                            style = MaterialTheme.typography.labelLarge,
                            color = stateColor,
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Hold ≥72%: ${"%.0f".format(hv.stabilitySeconds)}s / 15s ${if (hv.stabilityReady) "★ SWEET-SPOT READY" else "• Warming"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (hv.stabilityReady) BullishGreen else TextSecondary,
                            fontFamily = JetBrainsMonoFamily
                        )
                    }
                }

                // Bidirectional Autopilot Guard Toggle (Stop on AVOID & Auto-Start on MODERATE/GOOD)
                Surface(
                    onClick = { onToggleAutoPauseOnAvoid(!autoPauseOnAvoid) },
                    shape = RoundedCornerShape(8.dp),
                    color = if (autoPauseOnAvoid) BullishGreen.copy(alpha = 0.14f) else SlateSurfaceVariant,
                    modifier = Modifier
                        .border(
                            1.dp,
                            if (autoPauseOnAvoid) BullishGreen.copy(alpha = 0.6f) else SlateBorder,
                            RoundedCornerShape(8.dp)
                        )
                        .testTag("hv_auto_pause_toggle")
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(
                            text = if (autoPauseOnAvoid) "AUTOPILOT GUARD: ON" else "AUTOPILOT GUARD: OFF",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (autoPauseOnAvoid) BullishGreen else TextSecondary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (autoPauseOnAvoid) {
                                if (telemetry.autopilotStandby) "PAUSED (AVOID) • AUTO-START READY" else "STOP AVOID • START MOD/GOOD"
                            } else {
                                "MANUAL BOT CONTROL"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (telemetry.autopilotStandby) WarningAmber else TextMuted
                        )
                    }
                }
            }

            // 2. Co-Pilot Action Directive Banner
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = stateDimColor.copy(alpha = 0.65f),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, stateColor.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = hv.recommendation,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = hv.activeTrap.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (hv.activeTrap == HvMarketTrap.NONE) BullishGreen else BearishCrimson,
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // 3. Structural Anti-Trap Diagnostics (TIGHT_CHOP 60s Range & CRAZY_WHIPSAW Wick Ratio)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                X13LayerChip(
                    title = "60S ANCHOR RANGE",
                    value = "%.4f".format(hv.anchorRange60s),
                    passed = !hv.tightChopDetected,
                    modifier = Modifier.weight(1f)
                )
                X13LayerChip(
                    title = "WICK COLLAPSE",
                    value = "${"%.0f".format(hv.wickCollapseRatioPercent)}% (≤55%)",
                    passed = !hv.crazyWhipsawDetected && hv.wickCollapseRatioPercent <= 55.0,
                    modifier = Modifier.weight(1f)
                )
                X13LayerChip(
                    title = "SOLID BODY",
                    value = "${"%.0f".format(hv.solidBodyRatioPercent)}%",
                    passed = hv.solidBodyRatioPercent >= 45.0,
                    modifier = Modifier.weight(1f)
                )
            }

            // 4. Multi-Timeframe Anchor Map (5s • 15s • 30s • 45s • 1m)
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = ObsidianBackground,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SlateBorder, RoundedCornerShape(10.dp))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "MULTI-TIMEFRAME ANCHOR MAP (5S – 1M)",
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricCyan,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "MTF ALIGNED: ${hv.mtfAlignedCount}/4",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (hv.mtfAlignedCount >= 3) BullishGreen else WarningAmber,
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val anchors = listOf(
                            "5s" to hv.delta5s,
                            "15s" to hv.delta15s,
                            "30s" to hv.delta30s,
                            "45s" to hv.delta45s,
                            "1m" to hv.delta60s
                        )
                        anchors.forEach { (tfLabel, delta) ->
                            val dColor = when {
                                delta > 0.0 -> BullishGreen
                                delta < 0.0 -> BearishCrimson
                                else -> TextSecondary
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = SlateSurfaceVariant,
                                modifier = Modifier
                                    .weight(1f)
                                    .border(1.dp, dColor.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = tfLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "%+.4f".format(delta),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = dColor,
                                        fontFamily = JetBrainsMonoFamily,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 5. HighVoltage 6 Strict Mathematical Filters Matrix
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = ObsidianBackground,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SlateBorder, RoundedCornerShape(10.dp))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "SNIPER M — SMOOTHED TICK SURGE",
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricCyan,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (filters.allSixFiltersPassed) "SURGE READY (${filters.zlemaTrend.name})" else "SCANNING STREAM",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (filters.allSixFiltersPassed) BullishGreen else TextSecondary,
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        X13LayerChip(
                            title = "1. SMOOTHED TICK DIR",
                            value = "${filters.zlemaTrend.name} (${"%+.4f".format(filters.zFast - filters.zSlow)})",
                            passed = filters.trendFilterPassed,
                            modifier = Modifier.weight(1f)
                        )
                        X13LayerChip(
                            title = "2. FOLLOW MOMENTUM",
                            value = "%+.4f".format(filters.netDelta5),
                            passed = filters.momentumFilterPassed,
                            modifier = Modifier.weight(1f)
                        )
                        X13LayerChip(
                            title = "3. TICK STREAM SURGE",
                            value = "${"%.2f".format(filters.tickVelocity)} T/s",
                            passed = filters.velocityFilterPassed && filters.breakoutFilterPassed,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // 6. Collapsible HV Window Practical Guide & Best Times Reference
            Surface(
                onClick = { showGuide = !showGuide },
                shape = RoundedCornerShape(8.dp),
                color = SlateSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SlateBorder, RoundedCornerShape(8.dp))
                    .testTag("hv_window_guide_toggle")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "HV WINDOW PRACTICAL GUIDE & SWEET-SPOT PLAYBOOK",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan,
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        imageVector = if (showGuide) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Toggle HV Window Guide",
                        tint = ElectricCyan,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            AnimatedVisibility(visible = showGuide) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ObsidianBackground, RoundedCornerShape(8.dp))
                        .border(1.dp, SlateBorder, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "1. HV WINDOW STATES",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "• EXCELLENT (≥75% Green): Clean, fast directional trend. Solid bodies, small wicks, aligned 15s–1m. Action: Active Trading Zone — Launch the bot.\n" +
                            "• MODERATE (50%–74% Yellow): Trending with minor pullbacks/deceleration. Action: Standby / Caution — Selective entries.\n" +
                            "• AVOID (<50% Red): Stuck in TIGHT_CHOP (<0.20pt over 60s) or CRAZY_WHIPSAW (>55% collapse wicks). Action: Off-limits — Turn the bot OFF immediately.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    HorizontalDivider(color = SlateBorder)
                    Text(
                        text = "2. BEST TIMES vs HIGH-RISK CONDITIONS",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "• Peak Sweet-Spot: London/New York Overlap (12:00–17:00 UTC), Session Open Volatility, and Trend Expansion breakouts.\n" +
                            "• Stay Away: Asian Session off-hours (low-volume TIGHT_CHOP) and Major News Events (CPI/NFP/Rates causing 1.5pt spike-and-collapse whipsaws).",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    HorizontalDivider(color = SlateBorder)
                    Text(
                        text = "3. 4-STEP OPERATING PROTOCOL",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "1) Boot: Zero-lag raw score initializes immediately; wait 10–15s for 1m anchor map to populate.\n" +
                            "2) Stability: Wait until HV Window holds ≥72% for 15–20 continuous seconds.\n" +
                            "3) Execute: Run HighVoltage across 5s, 15s, 30s, 45s, or 1m while score stays 75%–95%.\n" +
                            "4) Stop Immediately: Pause bot the moment score drops <50% (AVOID).",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
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
    // Update countdown at 10Hz (every 100ms) instead of 60fps to avoid unnecessary layout redraws
    LaunchedEffect(openTrades.isNotEmpty()) {
        while (isActive && openTrades.isNotEmpty()) {
            frameNowMs = System.currentTimeMillis()
            delay(100L)
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
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
                        color = ElectricCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "LIVE EXECUTION",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    maxLines = 1
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
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                        ) {
                            Text(
                                text = "${pos.pair} • ${pos.direction.name} $${"%.2f".format(pos.stake)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Strike: ${"%.4f".format(pos.entryPrice)} -> Spot: ${"%.4f".format(currentPrice)} (${"%+.4f".format(currentPrice - pos.entryPrice)})",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
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
                                maxLines = 1,
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
private fun ConsolidatedMarketIntelligenceCard(
    telemetry: MarketTelemetryState,
    micro: MicrostructureSnapshot,
    recentTicks: List<Tick>
) {
    val dirColor = when (micro.impulseDirection) {
        SignalDirection.UP -> BullishGreen
        SignalDirection.DOWN -> BearishCrimson
        SignalDirection.NONE -> ElectricCyan
    }
    val qualityColor = when (telemetry.sessionQuality) {
        SessionQuality.OPTIMAL -> BullishGreen
        SessionQuality.NEUTRAL -> TextSecondary
        SessionQuality.AVOID -> BearishCrimson
    }
    val hmm = telemetry.hmmOutput
    val t = telemetry.temporalTelemetry

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, SlateBorder, RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        shape = RoundedCornerShape(14.dp)
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
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = "Market Intelligence",
                        tint = ElectricCyan,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "LIVE MARKET INTELLIGENCE & INDICATORS",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
            }

            // Group 1: Impulse, Laminar Efficiency & Tick Velocity Sparkline
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RadialTelemetryGauge(
                    title = "IMPULSE",
                    valueText = "${"%.0f".format(micro.microImpulseScore)}%",
                    subText = "SNR ${"%.2f".format(t.rollingSnrMean)}x",
                    progress = (micro.microImpulseScore / 100.0).toFloat().coerceIn(0f, 1f),
                    arcColor = dirColor,
                    modifier = Modifier.weight(1f)
                )

                RadialTelemetryGauge(
                    title = "LAMINAR ER",
                    valueText = "${"%.0f".format(micro.directionalEfficiency)}%",
                    subText = "Purity ${"%.0f".format(t.rollingPurityMean)}%",
                    progress = (micro.directionalEfficiency / 100.0).toFloat().coerceIn(0f, 1f),
                    arcColor = ElectricCyan,
                    modifier = Modifier.weight(1f)
                )
            }

            // Compact Tick Displacement Bar Strip
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(ObsidianBackground)
                    .border(1.dp, SlateBorder, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "OFI: ${"%+.0f".format(t.rollingOfiMedian)}% • ${micro.energyState.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    Text(
                        text = "SENTIMENT: ${telemetry.bullishSentimentPercent}% BULL • 50T POS: ${"%.0f".format(telemetry.relPos.finiteOr(50.0))}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .padding(vertical = 2.dp)
                ) {
                    val totalTicks = recentTicks.size
                    val startIdx = max(0, totalTicks - 25)
                    val count = totalTicks - startIdx - 1
                    if (count >= 1) {
                        var maxAbs = 1e-6
                        for (i in 0 until count) {
                            val d = recentTicks[startIdx + i + 1].price - recentTicks[startIdx + i].price
                            val ad = abs(d)
                            if (ad > maxAbs) maxAbs = ad
                        }
                        val slotW = size.width / count
                        val barW = (slotW * 0.65f).coerceAtLeast(2f)
                        val midY = size.height / 2f

                        drawLine(
                            color = SlateBorder,
                            start = Offset(0f, midY),
                            end = Offset(size.width, midY),
                            strokeWidth = 1f
                        )

                        for (i in 0 until count) {
                            val d = recentTicks[startIdx + i + 1].price - recentTicks[startIdx + i].price
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

            // Group 2: Consolidated Key Indicators (Session Quality, HMM Regime, Consistency, Slope & S/R)
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
                    label = "SLOPE & S/R DIST",
                    value = "${"%.2f".format(telemetry.slopeRatio)}x | S/R ${"%.2f".format(telemetry.srDistance)}",
                    progress = (1f - (telemetry.slopeRatio / 5.0).toFloat()).coerceIn(0.1f, 1f),
                    valueColor = if (telemetry.slopeRatio < 3.15) BullishGreen else BearishCrimson,
                    modifier = Modifier.weight(1f)
                )
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
    val trackStroke = remember { Stroke(width = 12f, cap = StrokeCap.Round) }
    val valueStroke = remember { Stroke(width = 12f, cap = StrokeCap.Round) }
    val clampedProgress = progress.finiteOr(0f).coerceIn(0f, 1f)

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(ObsidianBackground)
            .border(1.dp, SlateBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize().padding(3.dp)) {
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
                    sweepAngle = 270f * clampedProgress,
                    useCenter = false,
                    style = valueStroke
                )
            }
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium,
                color = arcColor,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subText,
                style = MaterialTheme.typography.labelMedium,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
    val areaFillBrush = remember {
        Brush.verticalGradient(
            colors = listOf(
                ElectricCyan.copy(alpha = 0.12f),
                Color.Transparent
            )
        )
    }
    val gridDashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f) }
    val fibDashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(4f, 6f), 0f) }
    val crosshairDashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f) }

    val candles = remember(telemetry.candles) { telemetry.candles.takeLast(48) }
    val recentTicks = remember(telemetry.recentTicks) { telemetry.recentTicks.takeLast(80) }

    val inspectedCandle = if (scrubbedIndex in candles.indices) candles[scrubbedIndex] else candles.lastOrNull()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, SlateBorder, RoundedCornerShape(14.dp)),
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
                Text(
                    text = "LIVE HFT PRICE MATRIX",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                )

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
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            if (inspectedCandle != null) {
                Text(
                    text = "O:${"%.4f".format(inspectedCandle.open)}  H:${"%.4f".format(inspectedCandle.high)}  L:${"%.4f".format(inspectedCandle.low)}  C:${"%.4f".format(inspectedCandle.close)} (${inspectedCandle.tickCount}t)",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (inspectedCandle.isBullish) BullishGreen else BearishCrimson,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Text(
                    text = "Touch & drag chart to inspect 5s candles & S/R corridor",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (candles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ObsidianBackground)
                        .border(1.dp, SlateBorder, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Stream offline — Connect Olymp Trade WS or start Impulse Stream",
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
                        .height(210.dp)
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

                    // 2. 50-Tick Fibonacci Band
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
                            brush = areaFillBrush
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
private fun MetricTile(
    label: String,
    value: String,
    progress: Float,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.border(1.dp, SlateBorder, RoundedCornerShape(10.dp)),
        shape = RoundedCornerShape(10.dp),
        color = ObsidianBackground
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(5.dp))
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
    val t = evaluation.temporalTelemetry
    var expandedDiagnostics by rememberSaveable { mutableStateOf(true) }
    val passedGates = evaluation.filterDiagnostics.count { it.passed }
    val totalGates = evaluation.filterDiagnostics.size

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
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expandedDiagnostics = !expandedDiagnostics }
                    .testTag("toggle_diagnostics_section"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                ) {
                    Text(
                        text = "VALIDATION & EXECUTION DIAGNOSTICS ($passedGates/$totalGates GATES)",
                        style = MaterialTheme.typography.labelLarge,
                        color = ElectricCyan,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = evaluation.triggerReason,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (evaluation.readyToExecute) BullishGreen.copy(alpha = 0.14f) else SlateSurfaceVariant
                    ) {
                        Text(
                            text = if (evaluation.readyToExecute) "READY" else "HOLD",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (evaluation.readyToExecute) BullishGreen else TextSecondary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = if (expandedDiagnostics) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expandedDiagnostics) "Collapse Diagnostics" else "Expand Diagnostics",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (expandedDiagnostics) {
                HorizontalDivider(color = SlateBorder)

                // X13 Walk-Forward Validation Metrics
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, SlateBorder, RoundedCornerShape(10.dp)),
                    shape = RoundedCornerShape(10.dp),
                    color = ObsidianBackground
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "X13 WALK-FORWARD TELEMETRY (${t.regimeType.displayName.uppercase()})",
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricCyan,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 6.dp)
                            )
                            Text(
                                text = if (t.momentumHysteresisActive) "HYST: LATCHED" else "HYST: IDLE",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (t.momentumHysteresisActive) BullishGreen else TextSecondary,
                                maxLines = 1
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            X13MetricMiniCell(
                                label = "GATE STABILITY",
                                value = "${"%.1f".format(t.gateStabilityPercent)}%",
                                good = t.gateStabilityPercent >= 75.0,
                                modifier = Modifier.weight(1f)
                            )
                            X13MetricMiniCell(
                                label = "FALSE-POS RATE",
                                value = "${"%.1f".format(t.falsePositiveRatePercent)}%",
                                good = t.falsePositiveRatePercent <= 25.0,
                                modifier = Modifier.weight(1f)
                            )
                            X13MetricMiniCell(
                                label = "IMPULSE DETECT",
                                value = "${"%.1f".format(t.impulseDetectionRatePercent)}%",
                                good = t.impulseDetectionRatePercent >= 50.0,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            X13MetricMiniCell(
                                label = "SIGNAL LATENCY",
                                value = "${t.signalLatencyMs}ms",
                                good = t.signalLatencyMs <= 600L,
                                modifier = Modifier.weight(1f)
                            )
                            X13MetricMiniCell(
                                label = "PROB CALIB",
                                value = "${"%.1f".format(t.calibrationAccuracyPercent)}%",
                                good = t.calibrationAccuracyPercent >= 65.0,
                                modifier = Modifier.weight(1f)
                            )
                            X13MetricMiniCell(
                                label = "OOS EXPECTANCY",
                                value = "${"%+.1f".format(t.oosExpectancyPercent)}%",
                                good = t.oosExpectancyPercent >= 0.0,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Individual Strategy Gate Diagnostics
                evaluation.filterDiagnostics.forEach { diag ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(ObsidianBackground)
                            .border(1.dp, SlateBorder, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                        ) {
                            Icon(
                                imageVector = if (diag.passed) Icons.Default.CheckCircle else Icons.Default.Close,
                                contentDescription = null,
                                tint = if (diag.passed) BullishGreen else BearishCrimson,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = diag.gateTitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = diag.reason,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Text(
                            text = diag.actualValueFormatted,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (diag.passed) BullishGreen else BearishCrimson,
                            maxLines = 1
                        )
                    }
                }

                if (evaluation.contextBlockReasons.isNotEmpty()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, BearishCrimson.copy(alpha = 0.45f), RoundedCornerShape(8.dp)),
                        shape = RoundedCornerShape(8.dp),
                        color = BearishCrimsonDim
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "STRUCTURAL & ORDER-FLOW CONTEXT ALERTS",
                                style = MaterialTheme.typography.labelSmall,
                                color = BearishCrimson,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = evaluation.contextBlockReasons.joinToString(" • "),
                                style = MaterialTheme.typography.labelSmall,
                                color = TextPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun X13LayerChip(
    title: String,
    value: String,
    passed: Boolean,
    modifier: Modifier = Modifier
) {
    val accent = if (passed) BullishGreen else BearishCrimson
    Surface(
        modifier = modifier.border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(8.dp)),
        shape = RoundedCornerShape(8.dp),
        color = SlateSurface
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun X13MetricMiniCell(
    label: String,
    value: String,
    good: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(SlateSurface)
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = if (good) BullishGreen else TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
