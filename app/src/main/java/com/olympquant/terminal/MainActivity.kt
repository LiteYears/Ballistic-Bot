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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
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
import com.olympquant.terminal.domain.HardLockedMandate
import com.olympquant.terminal.domain.MarketTelemetryState
import com.olympquant.terminal.domain.SignalDirection
import com.olympquant.terminal.domain.finiteOr
import com.olympquant.terminal.network.WsConnectionStatus
import com.olympquant.terminal.ui.screens.AppAuthScreen
import com.olympquant.terminal.ui.screens.BrokerSessionScreen
import com.olympquant.terminal.ui.screens.HmmAndAuditScreen
import com.olympquant.terminal.ui.screens.ModularStrategyStudioScreen
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

enum class WorkstationTab(val label: String, val icon: ImageVector, val testTag: String) {
    TERMINAL("Terminal", Icons.AutoMirrored.Filled.ShowChart, "nav_tab_terminal"),
    STRATEGIES("Strategy", Icons.Default.Lock, "nav_tab_strategies"),
    HMM_AUDIT("HMM Audit", Icons.Default.Analytics, "nav_tab_hmm"),
    JOURNAL("Journal", Icons.Default.History, "nav_tab_journal"),
    SESSION("Session", Icons.Default.Sensors, "nav_tab_session")
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
    val authError by vm.authError.collectAsStateWithLifecycle()
    val lastExecutionBanner by vm.lastExecutionBanner.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(sessionPrefs.loggedInEmail, botRunning) {
        if (sessionPrefs.loggedInEmail.isNotBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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

    if (sessionPrefs.loggedInEmail.isBlank()) {
        AppAuthScreen(
            authError = authError,
            onLogin = vm::loginWorkstation,
            onRegister = vm::registerWorkstation,
            onClearError = vm::clearAuthError
        )
        return
    }

    var currentTab by rememberSaveable { mutableStateOf(WorkstationTab.TERMINAL) }
    val haptics = LocalHapticFeedback.current
    val activeBalance = if (sessionPrefs.accountGroup == "real") sessionPrefs.realBalance else sessionPrefs.demoBalance
    val loadedStrategy = remember(strategies, sessionPrefs.selectedTimeframeSec) {
        val base = strategies.firstOrNull { it.isEnabled }
            ?: strategies.firstOrNull()
            ?: HardLockedMandate.highVoltageStrategyConfig(durationSec = sessionPrefs.selectedTimeframeSec)
        val tfSec = com.olympquant.terminal.domain.TradeTimeframe.fromSeconds(
            sessionPrefs.selectedTimeframeSec.takeIf { it > 0 } ?: base.durationSeconds
        ).seconds
        if (base.durationSeconds != tfSec) {
            base.copy(
                durationSeconds = tfSec,
                scriptCode = com.olympquant.terminal.engine.DefaultStrategyScripts.withDurationSec(base.scriptCode, tfSec)
            )
        } else {
            base
        }
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
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
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
                                onToggleBot = vm::toggleBotRunning,
                                onSwitchAccountGroup = vm::switchAccountGroup,
                                onSetDensityMode = vm::setDensityMode,
                                onManualOrder = vm::executeManualOrder,
                                onStartImpulseStream = vm::startLiveImpulseTestFeed,
                                onDismissBanner = vm::dismissBanner,
                                onNavigateToBrokerSetup = { currentTab = WorkstationTab.SESSION }
                            )

                            WorkstationTab.STRATEGIES -> ModularStrategyStudioScreen(
                                telemetry = telemetry,
                                activeBalance = activeBalance,
                                strategies = strategies,
                                wsLatencyMs = wsLatencyMs,
                                selectedTimeframeSec = sessionPrefs.selectedTimeframeSec,
                                onSelectTimeframe = vm::selectTradeTimeframe,
                                onCreateStrategyFromTemplate = vm::createNewStrategyFromTemplate,
                                onSaveAndLoadStrategy = vm::saveAndLoadStrategyScript,
                                onLoadStrategy = vm::loadStrategy,
                                onDeleteStrategy = vm::deleteStrategy,
                                onResetDefaultStrategy = vm::resetToDefaultStrategy
                            )

                            WorkstationTab.HMM_AUDIT -> HmmAndAuditScreen(
                                telemetry = telemetry
                            )

                            WorkstationTab.JOURNAL -> TradeJournalScreen(
                                trades = tradeJournal,
                                onClearJournal = vm::clearTradeJournal,
                                onExportJson = vm::exportJournalJson
                            )

                            WorkstationTab.SESSION -> BrokerSessionScreen(
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
                                onSaveAndConnect = vm::saveAndConnectBroker,
                                onStartImpulseStream = vm::startLiveImpulseTestFeed,
                                onSetAutoReconnectSession = vm::setAutoReconnectSession,
                                onRestoreSessionNow = vm::restoreSavedSessionNow,
                                onResetDailyCounters = vm::resetDailyCounters,
                                onDisconnect = vm::disconnectBroker,
                                onLogoutApp = vm::logoutWorkstation
                            )
                        }
                    }
                }
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
            StatusBadge(connectionStatus = connectionStatus, latencyMs = wsLatencyMs)
            Spacer(Modifier.width(8.dp))
            Text(
                text = telemetry.pair,
                style = MaterialTheme.typography.labelLarge,
                color = ElectricCyan,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(6.dp))
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

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (connectionStatus == WsConnectionStatus.DISCONNECTED) {
                Surface(
                    onClick = onStartImpulseStream,
                    shape = RoundedCornerShape(50),
                    color = ElectricCyan.copy(alpha = 0.16f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(3.dp))
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
                    text = "${telemetry.dailyTradesCount}/${maxDailyTrades}T • OFI ${"%+.0f".format(telemetry.orderFlowImbalance.finiteOr(0.0))}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (botRunning) BullishGreen.copy(alpha = 0.16f) else SlateSurfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (botRunning) BullishGreen else TextSecondary)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (botRunning) "ARMED" else "STANDBY",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (botRunning) BullishGreen else TextSecondary,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
