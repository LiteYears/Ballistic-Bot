package com.olympquant.terminal.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.olympquant.terminal.R
import com.olympquant.terminal.data.SessionPreferences
import com.olympquant.terminal.domain.HardLockedMandate
import com.olympquant.terminal.domain.StrategyConfig
import com.olympquant.terminal.domain.finiteOr
import com.olympquant.terminal.network.WsConnectionStatus
import com.olympquant.terminal.ui.theme.BearishCrimson
import com.olympquant.terminal.ui.theme.BullishGreen
import com.olympquant.terminal.ui.theme.ElectricCyan
import com.olympquant.terminal.ui.theme.JetBrainsMonoFamily
import com.olympquant.terminal.ui.theme.ObsidianBackground
import com.olympquant.terminal.ui.theme.SlateBorder
import com.olympquant.terminal.ui.theme.SlateBorderBright
import com.olympquant.terminal.ui.theme.SlateSurface
import com.olympquant.terminal.ui.theme.SlateSurfaceVariant
import com.olympquant.terminal.ui.theme.TextMuted
import com.olympquant.terminal.ui.theme.TextPrimary
import com.olympquant.terminal.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun OlympQuantSplashScreen(
    onSplashFinished: () -> Unit
) {
    var targetProgress by remember { mutableFloatStateOf(0.15f) }
    var bootStageLabel by remember {
        mutableStateOf("Initializing Sniper M 7-Gate False-Positive Rejection Pipeline...")
    }
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "splash_boot_progress"
    )

    LaunchedEffect(Unit) {
        delay(220L)
        targetProgress = 0.58f
        bootStageLabel = "Calibrating Online FTRL-Proximal & 8-State Gaussian HMM..."
        delay(360L)
        targetProgress = 1.0f
        bootStageLabel = "Sniper M 5s Ballistic Engine Ready."
        delay(280L)
        onSplashFinished()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBackground)
            .testTag("olympquant_splash_screen"),
        contentAlignment = Alignment.Center
    ) {
        val horizontalPad = when {
            maxWidth < 380.dp -> 18.dp
            maxWidth < 600.dp -> 24.dp
            else -> 36.dp
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp)
                .padding(horizontal = horizontalPad, vertical = 20.dp)
                .border(1.dp, SlateBorderBright, RoundedCornerShape(22.dp)),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = SlateSurface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(ElectricCyan.copy(alpha = 0.12f))
                        .border(1.dp, ElectricCyan.copy(alpha = 0.5f), RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_olympquant_logo),
                        contentDescription = "OlympQuant Logo",
                        modifier = Modifier.size(56.dp)
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "OLYMPQUANT TERMINAL",
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "SNIPER M • SOLE 5-SECOND BALLISTIC ENGINE",
                        style = MaterialTheme.typography.labelLarge,
                        color = ElectricCyan,
                        fontFamily = JetBrainsMonoFamily,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = ObsidianBackground,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, SlateBorder, RoundedCornerShape(12.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "ENGINE STATUS",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                            Text(
                                text = "${(animatedProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = BullishGreen,
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        SafeQuantProgressBar(
                            progress = animatedProgress,
                            color = ElectricCyan,
                            trackColor = SlateSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                        Text(
                            text = bootStageLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SplashFeatureBadge("7-GATE FILTER", Modifier.weight(1f))
                    SplashFeatureBadge("FTRL-PROXIMAL", Modifier.weight(1f))
                    SplashFeatureBadge("5S HORIZON", Modifier.weight(1f))
                }

                Button(
                    onClick = onSplashFinished,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("enter_terminal_splash_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan)
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = ObsidianBackground,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "LAUNCH SNIPER M WORKSTATION",
                        style = MaterialTheme.typography.labelLarge,
                        color = ObsidianBackground,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun SplashFeatureBadge(
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.border(1.dp, SlateBorder, RoundedCornerShape(8.dp)),
        shape = RoundedCornerShape(8.dp),
        color = SlateSurfaceVariant
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = ElectricCyan,
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun BrokerSessionScreen(
    sessionPrefs: SessionPreferences,
    loadedStrategy: StrategyConfig = HardLockedMandate.lockedStrategyConfig(),
    connectionStatus: WsConnectionStatus,
    statusMessage: String,
    wsLatencyMs: Long,
    dailyNetPnl: Double,
    dailyTradesCount: Int,
    consecutiveLosses: Int,
    hardStopReason: String?,
    wireLog: List<String>,
    onSaveAndConnect: (token: String, uri: String) -> Unit,
    onStartImpulseStream: () -> Unit,
    onSetAutoReconnectSession: (Boolean) -> Unit,
    onRestoreSessionNow: () -> Unit,
    onResetDailyCounters: () -> Unit = {},
    onDisconnect: () -> Unit
) {
    var tokenInput by remember(sessionPrefs.accessToken) { mutableStateOf(sessionPrefs.accessToken) }
    var uriInput by remember(sessionPrefs.wsUri) { mutableStateOf(sessionPrefs.wsUri) }
    var showWebViewAuth by remember { mutableStateOf(false) }
    var showWireLog by remember { mutableStateOf(true) }

    val activeBalance = if (sessionPrefs.accountGroup == "real") sessionPrefs.realBalance else sessionPrefs.demoBalance
    val lockedStake = loadedStrategy.computeEffectiveStake(activeBalance)
    val maxDailyTrades = loadedStrategy.maxDailyTrades.coerceAtLeast(1)
    val maxLossStreak = loadedStrategy.maxConsecutiveLosses.coerceAtLeast(1)
    val dateFmt = remember { SimpleDateFormat("MMM dd, HH:mm:ss", Locale.US) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBackground)
    ) {
        val horizontalPad = when {
            maxWidth < 380.dp -> 14.dp
            maxWidth < 600.dp -> 18.dp
            else -> 28.dp
        }
        val verticalPad = if (maxWidth >= 600.dp) 20.dp else 16.dp
        val cardSpacing = if (maxWidth >= 600.dp) 18.dp else 16.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = horizontalPad, vertical = verticalPad),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 860.dp),
                verticalArrangement = Arrangement.spacedBy(cardSpacing)
            ) {
                // 1. Sole Strategy & Mandate Identity Banner
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, SlateBorderBright, RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(containerColor = SlateSurface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = "SOLE STRATEGY: SNIPER M (5S NATIVE)",
                                style = MaterialTheme.typography.titleMedium,
                                color = ElectricCyan,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "7-Gate Scientific False-Positive Filter • Non-Decelerating Convexity • Online FTRL-Proximal",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = BullishGreen.copy(alpha = 0.14f),
                            modifier = Modifier.border(1.dp, BullishGreen.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                        ) {
                            Text(
                                text = "5S LOCKED",
                                style = MaterialTheme.typography.labelSmall,
                                color = BullishGreen,
                                fontFamily = JetBrainsMonoFamily,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

        // 2. Hard-Locked Execution & Risk Mandate Vault (Read-Only, Non-Editable)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    1.dp,
                    if (hardStopReason != null) BearishCrimson.copy(alpha = 0.7f) else SlateBorder,
                    RoundedCornerShape(16.dp)
                ),
            colors = CardDefaults.cardColors(containerColor = SlateSurface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = "Hard Locked Mandate",
                            tint = ElectricCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "EXECUTION & RISK MANDATE",
                            style = MaterialTheme.typography.titleMedium,
                            color = ElectricCyan,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = SlateSurfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("LOCKED", style = MaterialTheme.typography.labelSmall, color = TextSecondary, maxLines = 1)
                        }
                    }
                }

                // Live Daily Quota & Consecutive Loss Progress
                val quotaRatio = (dailyTradesCount.toFloat() / maxDailyTrades.toFloat())
                    .finiteOr(0f).coerceIn(0f, 1f)
                val lossRatio = (consecutiveLosses.toFloat() / maxLossStreak.toFloat())
                    .finiteOr(0f).coerceIn(0f, 1f)

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = ObsidianBackground,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, SlateBorder, RoundedCornerShape(10.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${loadedStrategy.name}: $${"%,.2f".format(lockedStake)}",
                                style = MaterialTheme.typography.labelLarge,
                                color = BullishGreen,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(end = 8.dp)
                            )
                            Text(
                                text = "Min $${"%.0f".format(loadedStrategy.minStakeUsd)} • Max $${"%,.0f".format(loadedStrategy.maxStakeUsd)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                maxLines = 1
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Daily Trades Quota: $dailyTradesCount / $maxDailyTrades",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (dailyTradesCount >= maxDailyTrades) BearishCrimson else TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(end = 8.dp)
                            )
                            Text(
                                text = "Today P&L: $${"%+.2f".format(dailyNetPnl.finiteOr(0.0))}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (dailyNetPnl >= 0.0) BullishGreen else BearishCrimson,
                                maxLines = 1
                            )
                        }
                        SafeQuantProgressBar(
                            progress = quotaRatio,
                            color = if (dailyTradesCount >= maxDailyTrades) BearishCrimson else ElectricCyan,
                            trackColor = SlateSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Loss Streak Guard: $consecutiveLosses / $maxLossStreak",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (consecutiveLosses >= maxLossStreak) BearishCrimson else TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(end = 8.dp)
                            )
                            Text(
                                text = hardStopReason ?: "Circuit Breaker Armed",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (hardStopReason != null) BearishCrimson else BullishGreen,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        SafeQuantProgressBar(
                            progress = lossRatio,
                            color = if (consecutiveLosses >= maxLossStreak - 1) BearishCrimson else BullishGreen,
                            trackColor = SlateSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )

                        if (dailyTradesCount > 0 || consecutiveLosses > 0 || hardStopReason != null) {
                            OutlinedButton(
                                onClick = onResetDailyCounters,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 44.dp)
                                    .testTag("reset_daily_counters_button"),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, tint = ElectricCyan, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "RESET DAILY QUOTA & HARD-STOP COUNTERS",
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
        }

        // 3. Persistent Session Capture & Auto-Restore Card
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
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = "PERSISTENT SESSION VAULT",
                            style = MaterialTheme.typography.titleMedium,
                            color = ElectricCyan,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val captureStatus = if (sessionPrefs.accessToken.isNotBlank()) {
                            val timeStr = if (sessionPrefs.tokenCapturedAtMs > 0L) {
                                dateFmt.format(Date(sessionPrefs.tokenCapturedAtMs))
                            } else {
                                "Stored in DataStore"
                            }
                            "Token Persisted (${sessionPrefs.accessToken.take(6)}... • $timeStr)"
                        } else {
                            "No broker token captured yet — Mode: ${sessionPrefs.lastSessionMode}"
                        }
                        Text(
                            text = captureStatus,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (sessionPrefs.accessToken.isNotBlank()) BullishGreen else TextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    StatusBadge(connectionStatus = connectionStatus, latencyMs = wsLatencyMs)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(ObsidianBackground)
                        .border(1.dp, SlateBorder, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                        Text(
                            text = "Auto-Reconnect Saved Session & Service",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Restores WebView cookie, WS stream, and background execution notification",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Switch(
                        checked = sessionPrefs.autoReconnectSession,
                        onCheckedChange = onSetAutoReconnectSession,
                        colors = SwitchDefaults.colors(checkedThumbColor = TextPrimary, checkedTrackColor = ElectricCyan),
                        modifier = Modifier.testTag("auto_reconnect_session_switch")
                    )
                }

                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { showWebViewAuth = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("open_webview_auth_button"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Language, contentDescription = "Olymp Trade WebView", tint = ElectricCyan, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "CAPTURE WEBVIEW",
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricCyan,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    OutlinedButton(
                        onClick = onRestoreSessionNow,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("restore_saved_session_button"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Restore Session", tint = BullishGreen, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("RESTORE", style = MaterialTheme.typography.labelSmall, color = BullishGreen, maxLines = 1)
                    }
                }

                Button(
                    onClick = onStartImpulseStream,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("start_impulse_stream_button"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SlateSurfaceVariant)
                ) {
                    Icon(Icons.Default.Bolt, contentDescription = "Impulse Stream", tint = ElectricCyan, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "LAUNCH BALLISTIC IMPULSE STREAM",
                        style = MaterialTheme.typography.labelMedium,
                        color = ElectricCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    label = { Text("Persistent Olymp Trade access_token Cookie") },
                    placeholder = { Text("Paste access_token or capture via WebView...") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("access_token_input")
                )

                OutlinedTextField(
                    value = uriInput,
                    onValueChange = { uriInput = it },
                    label = { Text("WebSocket OTP Endpoint URI") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ws_uri_input")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { onSaveAndConnect(tokenInput, uriInput) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("connect_ws_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = BullishGreen),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = "Connect", tint = ObsidianBackground, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "PERSIST & CONNECT",
                            style = MaterialTheme.typography.labelLarge,
                            color = ObsidianBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    OutlinedButton(
                        onClick = onDisconnect,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("disconnect_ws_button"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.PowerSettingsNew, contentDescription = "Disconnect", tint = BearishCrimson, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("DISCONNECT", style = MaterialTheme.typography.labelSmall, color = BearishCrimson, maxLines = 1)
                    }
                }
            }
        }

        // 4. Collapsible Live Protocol Wire Logbook
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
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    ) {
                        Icon(Icons.Default.Terminal, contentDescription = "Wire Log", tint = ElectricCyan, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "PROTOCOL EVENT WIRE LOG (${wireLog.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = ElectricCyan,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    OutlinedButton(
                        onClick = { showWireLog = !showWireLog },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.heightIn(min = 36.dp)
                    ) {
                        Text(
                            text = if (showWireLog) "HIDE" else "SHOW",
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricCyan
                        )
                    }
                }

                if (showWireLog) {
                    if (wireLog.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No WebSocket frames logged yet. Connect above or launch the Ballistic Impulse Stream.",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextMuted
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(ObsidianBackground)
                                .border(1.dp, SlateBorder, RoundedCornerShape(10.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(wireLog) { line ->
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }
            }
        }
            }
        }
    }

    if (showWebViewAuth) {
        OlympTradeWebViewAuthDialog(
            onTokenExtracted = { extracted ->
                tokenInput = extracted
                showWebViewAuth = false
                onSaveAndConnect(extracted, uriInput)
            },
            onDismiss = { showWebViewAuth = false }
        )
    }
}

@Composable
fun StatusBadge(connectionStatus: WsConnectionStatus, latencyMs: Long) {
    val (color, label) = when (connectionStatus) {
        WsConnectionStatus.AUTHENTICATED -> BullishGreen to "LIVE ${latencyMs}ms"
        WsConnectionStatus.SYNTHETIC_FEED -> ElectricCyan to "HFT STREAM ${latencyMs}ms"
        WsConnectionStatus.CONNECTING -> ElectricCyan to "CONNECTING"
        WsConnectionStatus.RECONNECTING -> ElectricCyan to "RECONNECTING"
        WsConnectionStatus.ERROR -> BearishCrimson to "AUTH ERROR"
        WsConnectionStatus.DISCONNECTED -> TextMuted to "OFFLINE"
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OlympTradeWebViewAuthDialog(
    onTokenExtracted: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var detectedCookieStatus by remember {
        mutableStateOf("Sign in to Olymp Trade below — access_token cookie will be captured & persisted to Vault automatically.")
    }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            webViewInstance?.apply {
                stopLoading()
                webViewClient = WebViewClient()
                destroy()
            }
            webViewInstance = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            shape = RoundedCornerShape(16.dp),
            color = SlateSurface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SlateSurfaceVariant)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Persistent Olymp Trade Session Interceptor",
                            style = MaterialTheme.typography.titleMedium,
                            color = ElectricCyan
                        )
                        Text(
                            text = detectedCookieStatus,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Close", color = BearishCrimson)
                    }
                }

                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { context ->
                        WebView(context).apply {
                            webViewInstance = this
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    checkForAccessTokenCookie(url, onTokenExtracted) { msg ->
                                        detectedCookieStatus = msg
                                    }
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    checkForAccessTokenCookie(url, onTokenExtracted) { msg ->
                                        detectedCookieStatus = msg
                                    }
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    checkForAccessTokenCookie(request?.url?.toString(), onTokenExtracted) { msg ->
                                        detectedCookieStatus = msg
                                    }
                                    return false
                                }
                            }
                            loadUrl("https://olymptrade.com/platform")
                        }
                    }
                )
            }
        }
    }
}

private fun checkForAccessTokenCookie(
    url: String?,
    onFound: (String) -> Unit,
    onStatus: (String) -> Unit
) {
    val targetUrl = url ?: "https://olymptrade.com"
    val cookieManager = CookieManager.getInstance()
    val rawCookies = cookieManager.getCookie(targetUrl) ?: return
    val tokenPair = rawCookies.split(";")
        .map { it.trim() }
        .firstOrNull { it.startsWith("access_token=") }
    if (tokenPair != null) {
        val tokenValue = tokenPair.substringAfter("access_token=").trim()
        if (tokenValue.isNotBlank()) {
            runCatching { cookieManager.flush() }
            onStatus("Captured & persisted access_token! Connecting WebSocket...")
            onFound(tokenValue)
        }
    }
}
