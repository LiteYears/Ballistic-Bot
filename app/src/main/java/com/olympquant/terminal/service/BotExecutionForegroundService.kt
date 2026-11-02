package com.olympquant.terminal.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.olympquant.terminal.MainActivity
import com.olympquant.terminal.R
import com.olympquant.terminal.domain.HardLockedMandate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ForegroundExecutionSnapshot(
    val botRunning: Boolean = false,
    val pair: String = "LATAM_X",
    val latestPrice: Double = 0.0,
    val lockedStakeUsd: Double = 1.0,
    val dailyTradesCount: Int = 0,
    val consecutiveLosses: Int = 0,
    val dailyNetPnl: Double = 0.0,
    val hardStopReason: String? = null,
    val lastSignalNote: String = "Standby"
)

class BotExecutionForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE_BOT -> {
                onPauseBotRequested?.invoke()
            }
        }

        val notification = buildNotification(latestSnapshot.value)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "OlympQuant::BotExecutionWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire(6 * 60 * 60 * 1000L) // Up to 6 hours continuous background execution
                }
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OlympQuant Background Execution",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Tracks live HFT Ballistic Impulse execution, daily 10-trade quota, and 3-loss hard stop."
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(snap: ForegroundExecutionSnapshot): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            100,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusTag = when {
            snap.hardStopReason != null -> "HARD STOP • ${snap.hardStopReason}"
            snap.botRunning -> "ARMED • ${snap.pair} @ ${"%.4f".format(snap.latestPrice)}"
            else -> "MONITORING • ${snap.pair} @ ${"%.4f".format(snap.latestPrice)}"
        }

        val bodyLine = "Stake $${"%.2f".format(snap.lockedStakeUsd)} (5% Bal) • Trades ${snap.dailyTradesCount}/${HardLockedMandate.MAX_TRADES_PER_DAY} • Losses ${snap.consecutiveLosses}/${HardLockedMandate.MAX_CONSECUTIVE_LOSSES} • P&L ${"%+.2f".format(snap.dailyNetPnl)}"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("OlympQuant • $statusTag")
            .setContentText(bodyLine)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "$bodyLine\n${snap.lastSignalNote}"
                )
            )
            .setOngoing(snap.botRunning)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingOpen)

        if (snap.botRunning) {
            val pauseIntent = Intent(this, BotExecutionForegroundService::class.java).apply {
                action = ACTION_PAUSE_BOT
            }
            val pendingPause = PendingIntent.getService(
                this,
                101,
                pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, "PAUSE BOT", pendingPause)
        }

        return builder.build()
    }

    companion object {
        const val CHANNEL_ID = "olympquant_execution_channel"
        const val NOTIFICATION_ID = 4401
        const val ACTION_START_OR_UPDATE = "com.olympquant.terminal.START_OR_UPDATE_FG"
        const val ACTION_PAUSE_BOT = "com.olympquant.terminal.PAUSE_BOT_FG"
        const val ACTION_STOP_SERVICE = "com.olympquant.terminal.STOP_FG"

        private val _latestSnapshot = MutableStateFlow(ForegroundExecutionSnapshot())
        val latestSnapshot: StateFlow<ForegroundExecutionSnapshot> = _latestSnapshot.asStateFlow()

        var onPauseBotRequested: (() -> Unit)? = null

        fun syncForegroundExecution(context: Context, snapshot: ForegroundExecutionSnapshot) {
            _latestSnapshot.value = snapshot
            runCatching {
                val appCtx = context.applicationContext
                val intent = Intent(appCtx, BotExecutionForegroundService::class.java).apply {
                    action = ACTION_START_OR_UPDATE
                }
                if (snapshot.botRunning) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        appCtx.startForegroundService(intent)
                    } else {
                        appCtx.startService(intent)
                    }
                } else {
                    val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    if (nm != null && snapshot.latestPrice > 0.0) {
                        appCtx.startService(intent)
                    }
                }
            }
        }

        fun stopService(context: Context) {
            runCatching {
                val appCtx = context.applicationContext
                val intent = Intent(appCtx, BotExecutionForegroundService::class.java).apply {
                    action = ACTION_STOP_SERVICE
                }
                appCtx.startService(intent)
            }
        }
    }
}
