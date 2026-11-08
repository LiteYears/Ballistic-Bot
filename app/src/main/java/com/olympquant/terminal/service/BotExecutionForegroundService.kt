package com.olympquant.terminal.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.olympquant.terminal.MainActivity
import com.olympquant.terminal.R
import com.olympquant.terminal.domain.HardLockedMandate

data class ForegroundExecutionSnapshot(
    val botRunning: Boolean = false,
    val autopilotArmed: Boolean = false,
    val autopilotStandby: Boolean = false,
    val hourlySniperMode: Boolean = false,
    val hourlyTradesThisHour: Int = 0,
    val nextHourlyResetMinutes: Int = 0,
    val singleTradeLocked: Boolean = false,
    val hvStateLabel: String = "MODERATE",
    val hvScore: Double = 60.0,
    val pair: String = "LATAM_X",
    val latestPrice: Double = 0.0,
    val lockedStakeUsd: Double = 1.0,
    val dailyTradesCount: Int = 0,
    val maxDailyTrades: Int = HardLockedMandate.MAX_TRADES_PER_DAY,
    val consecutiveLosses: Int = 0,
    val dailyNetPnl: Double = 0.0,
    val hardStopReason: String? = null,
    val lastSignalNote: String = "Scanning 5s tick rush"
) {
    val shouldKeepServiceAlive: Boolean
        get() = botRunning || autopilotArmed || autopilotStandby || hourlySniperMode || singleTradeLocked
}

class BotExecutionForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_BOT -> {
                isServiceRunning = false
                onPauseBotRequested?.invoke()
                stopWakeAndWifiLocks()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_RESUME_BOT -> {
                isServiceRunning = true
                onResumeBotRequested?.invoke()
                acquireWakeAndWifiLocksIfNeeded()
                val notification = buildNotification(latestSnapshot.copy(botRunning = true, autopilotArmed = true))
                promoteToForeground(notification)
                return START_STICKY
            }

            ACTION_TOGGLE_SNIPER -> {
                isServiceRunning = true
                onToggleHourlySniperRequested?.invoke()
                acquireWakeAndWifiLocksIfNeeded()
                val notification = buildNotification(latestSnapshot)
                promoteToForeground(notification)
                return START_STICKY
            }

            ACTION_STOP_SERVICE -> {
                isServiceRunning = false
                stopWakeAndWifiLocks()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                isServiceRunning = true
                acquireWakeAndWifiLocksIfNeeded()
                val notification = buildNotification(latestSnapshot)
                promoteToForeground(notification)
                return START_STICKY
            }
        }
    }

    private fun promoteToForeground(notification: Notification) {
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
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep foreground execution alive if the bot, Autopilot Guard standby, or Chrono Sniper 1/Hr is armed
        if (latestSnapshot.shouldKeepServiceAlive) {
            acquireWakeAndWifiLocksIfNeeded()
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            runCatching { nm.notify(NOTIFICATION_ID, buildNotification(latestSnapshot)) }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isServiceRunning = false
        stopWakeAndWifiLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeAndWifiLocksIfNeeded() {
        runCatching {
            if (wakeLock?.isHeld != true) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "OlympQuant::BotForegroundExecutionLock"
                ).apply {
                    setReferenceCounted(false)
                    // 24-hour wake lock for full-day Chrono Sniper (1/Hr) & Autopilot Guard monitoring
                    acquire(24 * 60 * 60 * 1000L)
                }
            }
        }
        runCatching {
            if (wifiLock?.isHeld != true) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm?.createWifiLock(lockMode, "OlympQuant::LowLatencyWsWifiLock")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        }
    }

    private fun stopWakeAndWifiLocks() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock = null
        }
        runCatching {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
            wifiLock = null
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OlympQuant Live Execution",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent foreground execution for 24/7 Chrono Sniper (1/Hr), Autopilot Guard, and 1-Trade-At-A-Time engine"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(snap: ForegroundExecutionSnapshot): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            100,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = Intent(this, BotExecutionForegroundService::class.java).apply {
            action = ACTION_PAUSE_BOT
        }
        val pausePending = PendingIntent.getService(
            this,
            101,
            pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val resumeIntent = Intent(this, BotExecutionForegroundService::class.java).apply {
            action = ACTION_RESUME_BOT
        }
        val resumePending = PendingIntent.getService(
            this,
            102,
            resumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sniperIntent = Intent(this, BotExecutionForegroundService::class.java).apply {
            action = ACTION_TOGGLE_SNIPER
        }
        val sniperPending = PendingIntent.getService(
            this,
            103,
            sniperIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val priceStr = if (snap.latestPrice > 0.0) "%.4f".format(snap.latestPrice) else "SYNC"
        val pnlStr = "%+.2f".format(snap.dailyNetPnl)
        val modePrefix = when {
            snap.hardStopReason != null -> "HARD STOP • ${snap.pair}"
            snap.autopilotStandby -> "AUTOPILOT STANDBY (AVOID) • ${snap.pair} @ $priceStr"
            snap.hourlySniperMode && snap.hourlyTradesThisHour >= 1 ->
                "SNIPER 1/HR LOCKED (Next ${snap.nextHourlyResetMinutes}m) • ${snap.pair}"
            snap.hourlySniperMode && snap.botRunning ->
                "CHRONO SNIPER 1/HR ARMED • ${snap.pair} @ $priceStr"
            snap.botRunning ->
                "BOT ARMED • ${snap.pair} @ $priceStr"
            else ->
                "STANDBY • ${snap.pair} @ $priceStr"
        }

        val lockNote = if (snap.singleTradeLocked) "1/1 Trade Active" else "1-Trade Slot Ready"
        val sniperNote = if (snap.hourlySniperMode) {
            " • Sniper: ${snap.hourlyTradesThisHour}/1 Hr"
        } else {
            ""
        }

        val subtitle = snap.hardStopReason
            ?: "HV ${snap.hvStateLabel} (${"%.0f".format(snap.hvScore)}%) • $lockNote$sniperNote • ${snap.dailyTradesCount}/${snap.maxDailyTrades}T • P&L $$pnlStr"

        val expandedBody = buildString {
            appendLine(subtitle)
            append("Stake $${"%.2f".format(snap.lockedStakeUsd)} • Loss Streak ${snap.consecutiveLosses}/${HardLockedMandate.MAX_CONSECUTIVE_LOSSES} • ${snap.lastSignalNote}")
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(modePrefix)
            .setContentText(subtitle)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expandedBody))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openPending)

        if (snap.botRunning || snap.autopilotArmed || snap.autopilotStandby) {
            builder.addAction(0, "PAUSE BOT", pausePending)
        } else {
            builder.addAction(0, "RESUME BOT", resumePending)
        }
        builder.addAction(
            0,
            if (snap.hourlySniperMode) "SNIPER 1/HR: ON" else "SNIPER 1/HR: OFF",
            sniperPending
        )

        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "olympquant_bot_execution_channel"
        private const val NOTIFICATION_ID = 4105
        const val ACTION_PAUSE_BOT = "com.olympquant.terminal.ACTION_PAUSE_BOT"
        const val ACTION_RESUME_BOT = "com.olympquant.terminal.ACTION_RESUME_BOT"
        const val ACTION_TOGGLE_SNIPER = "com.olympquant.terminal.ACTION_TOGGLE_SNIPER"
        const val ACTION_STOP_SERVICE = "com.olympquant.terminal.ACTION_STOP_SERVICE"

        @Volatile
        private var latestSnapshot: ForegroundExecutionSnapshot = ForegroundExecutionSnapshot()

        @Volatile
        private var isServiceRunning: Boolean = false

        var onPauseBotRequested: (() -> Unit)? = null
        var onResumeBotRequested: (() -> Unit)? = null
        var onToggleHourlySniperRequested: (() -> Unit)? = null

        fun syncForegroundExecution(context: Context, snapshot: ForegroundExecutionSnapshot) {
            latestSnapshot = snapshot
            val appCtx = context.applicationContext
            if (snapshot.shouldKeepServiceAlive) {
                val intent = Intent(appCtx, BotExecutionForegroundService::class.java)
                runCatching {
                    if (!isServiceRunning) {
                        isServiceRunning = true
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            appCtx.startForegroundService(intent)
                        } else {
                            appCtx.startService(intent)
                        }
                    } else {
                        val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        appCtx.startService(intent)
                        nm.notify(NOTIFICATION_ID, ByteNotificationHelper.buildStatic(appCtx, snapshot))
                    }
                }
            } else if (isServiceRunning) {
                stopService(appCtx)
            }
        }

        fun stopService(context: Context) {
            isServiceRunning = false
            val appCtx = context.applicationContext
            val stopIntent = Intent(appCtx, BotExecutionForegroundService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            runCatching { appCtx.startService(stopIntent) }
        }
    }
}

private object ByteNotificationHelper {
    private const val CHANNEL_ID = "olympquant_bot_execution_channel"

    fun buildStatic(context: Context, snap: ForegroundExecutionSnapshot): Notification {
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            context,
            100,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = Intent(context, BotExecutionForegroundService::class.java).apply {
            action = BotExecutionForegroundService.ACTION_PAUSE_BOT
        }
        val pausePending = PendingIntent.getService(
            context,
            101,
            pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val resumeIntent = Intent(context, BotExecutionForegroundService::class.java).apply {
            action = BotExecutionForegroundService.ACTION_RESUME_BOT
        }
        val resumePending = PendingIntent.getService(
            context,
            102,
            resumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sniperIntent = Intent(context, BotExecutionForegroundService::class.java).apply {
            action = BotExecutionForegroundService.ACTION_TOGGLE_SNIPER
        }
        val sniperPending = PendingIntent.getService(
            context,
            103,
            sniperIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val priceStr = if (snap.latestPrice > 0.0) "%.4f".format(snap.latestPrice) else "SYNC"
        val pnlStr = "%+.2f".format(snap.dailyNetPnl)
        val modePrefix = when {
            snap.hardStopReason != null -> "HARD STOP • ${snap.pair}"
            snap.autopilotStandby -> "AUTOPILOT STANDBY (AVOID) • ${snap.pair} @ $priceStr"
            snap.hourlySniperMode && snap.hourlyTradesThisHour >= 1 ->
                "SNIPER 1/HR LOCKED (Next ${snap.nextHourlyResetMinutes}m) • ${snap.pair}"
            snap.hourlySniperMode && snap.botRunning ->
                "CHRONO SNIPER 1/HR ARMED • ${snap.pair} @ $priceStr"
            snap.botRunning ->
                "BOT ARMED • ${snap.pair} @ $priceStr"
            else ->
                "STANDBY • ${snap.pair} @ $priceStr"
        }

        val lockNote = if (snap.singleTradeLocked) "1/1 Trade Active" else "1-Trade Slot Ready"
        val sniperNote = if (snap.hourlySniperMode) {
            " • Sniper: ${snap.hourlyTradesThisHour}/1 Hr"
        } else {
            ""
        }

        val subtitle = snap.hardStopReason
            ?: "HV ${snap.hvStateLabel} (${"%.0f".format(snap.hvScore)}%) • $lockNote$sniperNote • ${snap.dailyTradesCount}/${snap.maxDailyTrades}T • P&L $$pnlStr"

        val expandedBody = buildString {
            appendLine(subtitle)
            append("Stake $${"%.2f".format(snap.lockedStakeUsd)} • Loss Streak ${snap.consecutiveLosses}/${HardLockedMandate.MAX_CONSECUTIVE_LOSSES} • ${snap.lastSignalNote}")
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(modePrefix)
            .setContentText(subtitle)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expandedBody))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openPending)

        if (snap.botRunning || snap.autopilotArmed || snap.autopilotStandby) {
            builder.addAction(0, "PAUSE BOT", pausePending)
        } else {
            builder.addAction(0, "RESUME BOT", resumePending)
        }
        builder.addAction(
            0,
            if (snap.hourlySniperMode) "SNIPER 1/HR: ON" else "SNIPER 1/HR: OFF",
            sniperPending
        )

        return builder.build()
    }
}
