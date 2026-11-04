package com.olympquant.terminal.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.olympquant.terminal.data.SessionPreferences
import com.olympquant.terminal.domain.HardLockedMandate
import com.olympquant.terminal.domain.StrategyConfig
import com.olympquant.terminal.domain.finiteOr
import com.olympquant.terminal.network.WsConnectionStatus
import com.olympquant.terminal.ui.theme.BearishCrimson
import com.olympquant.terminal.ui.theme.BullishGreen
import com.olympquant.terminal.ui.theme.ElectricCyan
import com.olympquant.terminal.ui.theme.ObsidianBackground
import com.olympquant.terminal.ui.theme.SlateBorder
import com.olympquant.terminal.ui.theme.SlateSurface
import com.olympquant.terminal.ui.theme.SlateSurfaceVariant
import com.olympquant.terminal.ui.theme.TextMuted
import com.olympquant.terminal.ui.theme.TextPrimary
import com.olympquant.terminal.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AppAuthScreen(
    authError: String?,
    onLogin: (email: String, password: String) -> Unit,
    onRegister: (username: String, email: String, password: String) -> Unit,
    onClearError: () -> Unit
) {
    var isRegisterMode by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("QuantTrader") }
    var email by remember { mutableStateOf("trader@olympquant.io") }
    var password by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBackground)
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 460.dp)
                .border(1.dp, SlateBorder, RoundedCornerShape(18.dp)),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = SlateSurface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(ElectricCyan.copy(alpha = 0.14f))
                        .border(1.dp, ElectricCyan.copy(alpha = 0.45f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = "OlympQuant Security Vault",
                        tint = ElectricCyan,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "OLYMPQUANT TERMINAL",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary
                )
                Text(
                    text = "Hawkes-Laminar Ballistic HFT Execution Console",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(SlateSurfaceVariant)
                        .padding(4.dp)
                ) {
                    TabPillButton(
                        text = "Sign In",
                        selected = !isRegisterMode,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("auth_mode_signin_tab"),
                        onClick = {
                            isRegisterMode = false
                            onClearError()
                        }
                    )
                    TabPillButton(
                        text = "Create Vault",
                        selected = isRegisterMode,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("auth_mode_register_tab"),
                        onClick = {
                            isRegisterMode = true
                            onClearError()
                        }
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                AnimatedVisibility(visible = isRegisterMode) {
                    Column {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it; onClearError() },
                            label = { Text("Quant Handle") },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = "Username") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("username_input")
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it; onClearError() },
                    label = { Text("Operator Email") },
                    leadingIcon = { Icon(Icons.Default.Key, contentDescription = "Email") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("email_input")
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; onClearError() },
                    label = { Text("Vault Password") },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = "Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("password_input")
                )

                if (authError != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = authError,
                        color = BearishCrimson,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("auth_error_text")
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = {
                        if (isRegisterMode) {
                            onRegister(username, email, password)
                        } else {
                            onLogin(email, password)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("submit_auth_button"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan)
                ) {
                    Text(
                        text = if (isRegisterMode) "INITIALIZE QUANT VAULT" else "UNLOCK WORKSTATION",
                        style = MaterialTheme.typography.labelLarge,
                        color = TextPrimary
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                TextButton(
                    onClick = {
                        onRegister("ProQuant", "quant@olymptrade.bot", "quant2026")
                    },
                    modifier = Modifier.testTag("quick_vault_access_button")
                ) {
                    Text(
                        text = "Instant One-Tap Operator Access ->",
                        style = MaterialTheme.typography.labelMedium,
                        color = ElectricCyan
                    )
                }
            }
        }
    }
}

@Composable
private fun TabPillButton(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) ElectricCyan else SlateSurfaceVariant
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) TextPrimary else TextSecondary
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
    onDisconnect: () -> Unit,
    onLogoutApp: () -> Unit
) {
    var tokenInput by remember(sessionPrefs.accessToken) { mutableStateOf(sessionPrefs.accessToken) }
    var uriInput by remember(sessionPrefs.wsUri) { mutableStateOf(sessionPrefs.wsUri) }
    var showWebViewAuth by remember { mutableStateOf(false) }

    val activeBalance = if (sessionPrefs.accountGroup == "real") sessionPrefs.realBalance else sessionPrefs.demoBalance
    val lockedStake = loadedStrategy.computeEffectiveStake(activeBalance)
    val maxDailyTrades = loadedStrategy.maxDailyTrades.coerceAtLeast(1)
    val maxLossStreak = loadedStrategy.maxConsecutiveLosses.coerceAtLeast(1)
    val dateFmt = remember { SimpleDateFormat("MMM dd, HH:mm:ss", Locale.US) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBackground)
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Operator Header
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, SlateBorder, RoundedCornerShape(14.dp)),
            colors = CardDefaults.cardColors(containerColor = SlateSurface),
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                    Text(
                        text = "OPERATOR: ${sessionPrefs.loggedInUsername.ifBlank { "Quant" }.uppercase()}",
                        style = MaterialTheme.typography.titleMedium,
                        color = ElectricCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = sessionPrefs.loggedInEmail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedButton(
                    onClick = onLogoutApp,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.heightIn(min = 40.dp).testTag("logout_workstation_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Lock Vault", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Lock Vault", maxLines = 1)
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
                    RoundedCornerShape(14.dp)
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
                                    .heightIn(min = 40.dp)
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
                .border(1.dp, SlateBorder, RoundedCornerShape(14.dp)),
            colors = CardDefaults.cardColors(containerColor = SlateSurface),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
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
                            text = "Auto-Reconnect Saved Session & Background Service",
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
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
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
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

        // 4. Live Protocol Wire Logbook
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .border(1.dp, SlateBorder, RoundedCornerShape(14.dp)),
            colors = CardDefaults.cardColors(containerColor = SlateSurface),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Terminal, contentDescription = "Wire Log", tint = ElectricCyan, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "PROTOCOL EVENT WIRE LOG (TCP_NODELAY)",
                        style = MaterialTheme.typography.labelLarge,
                        color = ElectricCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (wireLog.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No WebSocket frames logged yet. Connect above or launch the Ballistic Impulse Stream.",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextMuted
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
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
