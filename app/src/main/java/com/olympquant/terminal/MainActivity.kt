package com.olympquant.terminal

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.olympquant.terminal.data.SessionPreferences
import com.olympquant.terminal.data.TradeJournalEntity
import com.olympquant.terminal.domain.HardLockedMandate
import com.olympquant.terminal.domain.MarketTelemetryState
import com.olympquant.terminal.domain.SignalDirection
import com.olympquant.terminal.domain.StrategyConfig
import com.olympquant.terminal.domain.finiteOr
import com.olympquant.terminal.network.WsConnectionStatus
import com.olympquant.terminal.ui.screens.BrokerSessionScreen
import com.olympquant.terminal.ui.screens.HmmAndAuditScreen
import com.olympquant.terminal.ui.screens.OlympQuantSplashScreen
import com.olympquant.terminal.ui.screens.StatusBadge
import com.olympquant.terminal.ui.screens.TerminalWorkstationScreen
import com.olympquant.terminal.ui.screens.TradeJournalScreen
import com.olympquant.terminal.ui.theme.BearishCrimson
import com.olympquant.terminal.ui.theme.BullishGreen
import com.olympquant.terminal.ui.theme.ElectricCyan
import com.olympquant.terminal.ui.theme.ObsidianBackground
import com.olympquant.terminal.ui.theme.OlympQuantTheme
import com.olympquant.terminal.ui.theme.SlateBorder
import com.olympquant.terminal.ui.theme.SlateSurface
import com.olympquant.terminal.ui.theme.SlateSurfaceVariant
import com.olympquant.terminal.ui.theme.TextPrimary
import com.olympquant.terminal.ui.theme.TextSecondary
import com.olympquant.terminal.viewmodel.TerminalViewModel

/**
 * Consolidated 2-Screen Workstation Architecture:
 * 1. TERMINAL: Live Sniper M 5s Execution Control Deck, HFT Matrix Chart, HV Co-Pilot & 7-Gate Pipeline
 * 2. OPERATIONS: Unified Broker & Mandate Setup, Trade Journal & Equity Curve, and 8-State HMM Audit
 */
enum class WorkstationTab(val label: String, val icon: ImageVector, val testTag: String) {
    TERMINAL("Sniper Terminal", Icons.AutoMirrored.Filled.ShowChart, "nav_tab_terminal"),
    OPERATIONS("Ops & Analytics", Icons.Default.Analytics, "nav_tab_operations")
}

enum class OperationsSection(val label: String, val icon: ImageVector, val testTag: String) {
    BROKER_MANDATE("BROKER & MANDATE", Icons.Default.Sensors, "ops_section_broker"),
    TRADE_JOURNAL("TRADE JOURNAL", Icons.Default.History, "ops_section_journal"),
    HMM_AUDIT("HMM & QUANT AUDIT", Icons.Default.Analytics, "ops_section_hmm")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as OlympQuantApp
        setContent {
            OlympQuantTheme {
                val vm: TerminalViewModel = viewModel(
                    factory = TerminalViewModel.Factory(app.repository, app.wsClient, app)
                )
                OlympQuantRoot(vm = vm)
            }
        }
    }
}

@Composable
fun OlympQuantRoot(vm: TerminalViewModel) {
    val sessionPrefs by vm.sessionPrefs.collectAsStateWithLifecycle()
    val strategies by vm.strategies.collectAsStateWithLifecycle()
    val tradeJournal by vm.tradeJournal.collectAsStateWithLifecycle()
    val telemetry by vm.telemetry.collectAsStateWithLifecycle()
    val connectionStatus by vm.connectionStatus.collectAsStateWithLifecycle()
    val wsStatusMessage by vm.wsStatusMessage.collectAsStateWithLifecycle()
    val wsLatencyMs by vm.wsLatencyMs.collectAsStateWithLifecycle()
    val rawWireLog by vm.rawWireLog.collectAsStateWithLifecycle()
    val botRunning by vm.botRunning.collectAsStateWithLifecycle()
    val lastExecutionBanner by vm.lastExecutionBanner.collectAsStateWithLifecycle()

    var showSplashScreen by rememberSaveable { mutableStateOf(true) }
    var currentTab by rememberSaveable { mutableStateOf(WorkstationTab.TERMINAL) }
    var currentOpsSection by rememberSaveable { mutableStateOf(OperationsSection.BROKER_MANDATE) }

    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(showSplashScreen, botRunning) {
        if (!showSplashScreen && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                runCatching {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    if (showSplashScreen) {
        OlympQuantSplashScreen(
            onSplashFinished = { showSplashScreen = false }
        )
        return
    }

    val haptics = LocalHapticFeedback.current
    val loadedStrategy = remember(strategies) {
        strategies.firstOrNull { it.isEnabled }
            ?: strategies.firstOrNull()
            ?: HardLockedMandate.lockedStrategyConfig()
    }

    if (currentTab != WorkstationTab.TERMINAL) {
        BackHandler {
            currentTab = WorkstationTab.TERMINAL
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWideScreen = maxWidth >= 600.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing,
            containerColor = ObsidianBackground,
            bottomBar = {
                if (!isWideScreen) {
                    NavigationBar(
                        containerColor = SlateSurface,
                        windowInsets = WindowInsets.navigationBars,
                        modifier = Modifier.border(width = 1.dp, color = SlateBorder)
                    ) {
                        WorkstationTab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = currentTab == tab,
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    currentTab = tab
                                },
                                icon = { Icon(tab.icon, contentDescription = tab.label, modifier = Modifier.size(20.dp)) },
                                label = {
                                    Text(
                                        text = tab.label,
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                        fontWeight = if (currentTab == tab) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                alwaysShowLabel = true,
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = TextPrimary,
                                    selectedTextColor = ElectricCyan,
                                    indicatorColor = ElectricCyan.copy(alpha = 0.25f),
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                ),
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isWideScreen) {
                    NavigationRail(
                        containerColor = SlateSurface,
                        modifier = Modifier.border(width = 1.dp, color = SlateBorder)
                    ) {
                        WorkstationTab.entries.forEach { tab ->
                            NavigationRailItem(
                                selected = currentTab == tab,
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    currentTab = tab
                                },
                                icon = { Icon(tab.icon, contentDescription = tab.label, modifier = Modifier.size(20.dp)) },
                                label = {
                                    Text(
                                        text = tab.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (currentTab == tab) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = TextPrimary,
                                    selectedTextColor = ElectricCyan,
                                    indicatorColor = ElectricCyan.copy(alpha = 0.25f),
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                ),
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f).fillMaxSize()) {
                    if (currentTab != WorkstationTab.TERMINAL) {
                        GlobalLiveMiniTickerBar(
                            telemetry = telemetry,
                            maxDailyTrades = loadedStrategy.maxDailyTrades,
                            connectionStatus = connectionStatus,
                            wsLatencyMs = wsLatencyMs,
                            botRunning = botRunning,
                            onJumpToTerminal = { currentTab = WorkstationTab.TERMINAL },
                            onStartImpulseStream = vm::startLiveImpulseTestFeed
                        )
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                        when (currentTab) {
                            WorkstationTab.TERMINAL -> TerminalWorkstationScreen(
                                sessionPrefs = sessionPrefs,
                                telemetry = telemetry,
                                loadedStrategy = loadedStrategy,
                                connectionStatus = connectionStatus,
                                wsLatencyMs = wsLatencyMs,
                                botRunning = botRunning,
                                availablePairs = vm.availablePairs,
                                lastExecutionBanner = lastExecutionBanner,
                                onSelectPair = vm::selectAssetPair,
                                onSelectTimeframe = vm::selectTradeTimeframe,
                                onToggleAutoPauseOnAvoid = vm::setAutoPauseOnHvAvoid,
                                onToggleHourlySniper = vm::toggleHourlySniperMode,
                                onToggleBot = vm::toggleBotRunning,
                                onSwitchAccountGroup = vm::switchAccountGroup,
                                onSetDensityMode = vm::setDensityMode,
                                onManualOrder = vm::executeManualOrder,
                                onStartImpulseStream = vm::startLiveImpulseTestFeed,
                                onDismissBanner = vm::dismissBanner,
                                onNavigateToBrokerSetup = {
                                    currentOpsSection = OperationsSection.BROKER_MANDATE
                                    currentTab = WorkstationTab.OPERATIONS
                                }
                            )

                            WorkstationTab.OPERATIONS -> OperationsAndAnalyticsHubScreen(
                                currentSection = currentOpsSection,
                                onSelectSection = { section ->
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    currentOpsSection = section
                                },
                                sessionPrefs = sessionPrefs,
                                loadedStrategy = loadedStrategy,
                                telemetry = telemetry,
                                tradeJournal = tradeJournal,
                                connectionStatus = connectionStatus,
                                wsStatusMessage = wsStatusMessage,
                                wsLatencyMs = wsLatencyMs,
                                rawWireLog = rawWireLog,
                                onSaveAndConnect = vm::saveAndConnectBroker,
                                onStartImpulseStream = vm::startLiveImpulseTestFeed,
                                onSetAutoReconnectSession = vm::setAutoReconnectSession,
                                onRestoreSessionNow = vm::restoreSavedSessionNow,
                                onResetDailyCounters = vm::resetDailyCounters,
                                onDisconnect = vm::disconnectBroker,
                                onClearJournal = vm::clearTradeJournal,
                                onExportJson = vm::exportJournalJson
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OperationsAndAnalyticsHubScreen(
    currentSection: OperationsSection,
    onSelectSection: (OperationsSection) -> Unit,
    sessionPrefs: SessionPreferences,
    loadedStrategy: StrategyConfig,
    telemetry: MarketTelemetryState,
    tradeJournal: List<TradeJournalEntity>,
    connectionStatus: WsConnectionStatus,
    wsStatusMessage: String,
    wsLatencyMs: Long,
    rawWireLog: List<String>,
    onSaveAndConnect: (token: String, uri: String) -> Unit,
    onStartImpulseStream: () -> Unit,
    onSetAutoReconnectSession: (Boolean) -> Unit,
    onRestoreSessionNow: () -> Unit,
    onResetDailyCounters: () -> Unit,
    onDisconnect: () -> Unit,
    onClearJournal: () -> Unit,
    onExportJson: () -> String
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBackground)
    ) {
        // Segmented Sub-Navigation Bar for Screen 2
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .background(SlateSurface)
                .border(1.dp, SlateBorder),
            contentAlignment = Alignment.Center
        ) {
            val horizontalPad = when {
                maxWidth < 380.dp -> 12.dp
                maxWidth < 600.dp -> 16.dp
                else -> 28.dp
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 880.dp)
                    .padding(horizontal = horizontalPad, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OperationsSection.entries.forEach { section ->
                    val selected = currentSection == section
                    Surface(
                        onClick = { onSelectSection(section) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) ElectricCyan.copy(alpha = 0.18f) else SlateSurfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .border(
                                width = 1.dp,
                                color = if (selected) ElectricCyan else SlateBorder,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .testTag(section.testTag)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = section.icon,
                                contentDescription = section.label,
                                tint = if (selected) ElectricCyan else TextSecondary,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = section.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) ElectricCyan else TextSecondary,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
            when (currentSection) {
                OperationsSection.BROKER_MANDATE -> BrokerSessionScreen(
                    sessionPrefs = sessionPrefs,
                    loadedStrategy = loadedStrategy,
                    connectionStatus = connectionStatus,
                    statusMessage = wsStatusMessage,
                    wsLatencyMs = wsLatencyMs,
                    dailyNetPnl = telemetry.dailyNetPnl,
                    dailyTradesCount = telemetry.dailyTradesCount,
                    consecutiveLosses = telemetry.consecutiveLosses,
                    hardStopReason = telemetry.hardStopReason,
                    wireLog = rawWireLog,
                    onSaveAndConnect = onSaveAndConnect,
                    onStartImpulseStream = onStartImpulseStream,
                    onSetAutoReconnectSession = onSetAutoReconnectSession,
                    onRestoreSessionNow = onRestoreSessionNow,
                    onResetDailyCounters = onResetDailyCounters,
                    onDisconnect = onDisconnect
                )

                OperationsSection.TRADE_JOURNAL -> TradeJournalScreen(
                    trades = tradeJournal,
                    onClearJournal = onClearJournal,
                    onExportJson = onExportJson
                )

                OperationsSection.HMM_AUDIT -> HmmAndAuditScreen(
                    telemetry = telemetry
                )
            }
        }
    }
}

@Composable
private fun GlobalLiveMiniTickerBar(
    telemetry: MarketTelemetryState,
    maxDailyTrades: Int = HardLockedMandate.MAX_TRADES_PER_DAY,
    connectionStatus: WsConnectionStatus,
    wsLatencyMs: Long,
    botRunning: Boolean,
    onJumpToTerminal: () -> Unit,
    onStartImpulseStream: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlateSurface)
            .border(1.dp, SlateBorder)
            .clickable { onJumpToTerminal() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusBadge(connectionStatus = connectionStatus, latencyMs = wsLatencyMs)
            Text(
                text = telemetry.pair,
                style = MaterialTheme.typography.labelLarge,
                color = ElectricCyan,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (telemetry.latestPrice > 0) "%.4f".format(telemetry.latestPrice.finiteOr(0.0)) else "STANDBY",
                style = MaterialTheme.typography.labelLarge,
                color = when (telemetry.trend) {
                    SignalDirection.UP -> BullishGreen
                    SignalDirection.DOWN -> BearishCrimson
                    SignalDirection.NONE -> TextPrimary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (connectionStatus == WsConnectionStatus.DISCONNECTED) {
                Surface(
                    onClick = onStartImpulseStream,
                    shape = RoundedCornerShape(50),
                    color = ElectricCyan.copy(alpha = 0.16f),
                    modifier = Modifier.border(1.dp, ElectricCyan.copy(alpha = 0.45f), RoundedCornerShape(50))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "STREAM",
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricCyan,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            } else {
                Text(
                    text = "${telemetry.dailyTradesCount}/${maxDailyTrades}T",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    maxLines = 1
                )
            }

            val badgeLabel = when {
                telemetry.autopilotStandby -> "AUTO-HOLD"
                telemetry.hourlySniperMode && botRunning -> "1/HR (${telemetry.hourlyTradesThisHour}/1)"
                botRunning -> "ARMED"
                else -> "STANDBY"
            }
            val badgeActive = botRunning || telemetry.autopilotStandby
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (badgeActive) BullishGreen.copy(alpha = 0.16f) else SlateSurfaceVariant)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (badgeActive) BullishGreen else TextSecondary)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = badgeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (badgeActive) BullishGreen else TextSecondary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
