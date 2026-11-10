package com.olympquant.terminal.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.olympquant.terminal.data.OlympQuantRepository
import com.olympquant.terminal.data.SessionPreferences
import com.olympquant.terminal.data.TradeJournalEntity
import com.olympquant.terminal.domain.ActiveOpenTrade
import com.olympquant.terminal.domain.HardLockedMandate
import com.olympquant.terminal.domain.HvWindowState
import com.olympquant.terminal.domain.MarketTelemetryState
import com.olympquant.terminal.domain.SignalDirection
import com.olympquant.terminal.domain.StrategyConfig
import com.olympquant.terminal.domain.TerminalDensityMode
import com.olympquant.terminal.domain.Tick
import com.olympquant.terminal.domain.TradeTimeframe
import com.olympquant.terminal.domain.finiteOr
import com.olympquant.terminal.engine.CandleEngine
import com.olympquant.terminal.engine.ContextEnhancer
import com.olympquant.terminal.engine.IndicatorEngine
import com.olympquant.terminal.engine.LevelEngine
import com.olympquant.terminal.engine.ModularStrategyEngine
import com.olympquant.terminal.engine.RegimeDetectionEngine
import com.olympquant.terminal.engine.SRMemoryEngine
import com.olympquant.terminal.engine.StructureEngine
import com.olympquant.terminal.engine.TrendAgeEngine
import com.olympquant.terminal.network.OlympTradeWsClient
import com.olympquant.terminal.network.WsConnectionStatus
import com.olympquant.terminal.service.BotExecutionForegroundService
import com.olympquant.terminal.service.ForegroundExecutionSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Calendar
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalViewModel(
    private val repository: OlympQuantRepository,
    val wsClient: OlympTradeWsClient = OlympTradeWsClient(),
    private val appContext: Context? = null
) : ViewModel() {

    private val quantEngineDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val backgroundEngineScope = CoroutineScope(SupervisorJob() + quantEngineDispatcher)

    private val candleEngine = CandleEngine(candleSizeSec = 5L, maxCandles = 300)
    private val structureEngine = StructureEngine()
    private val levelEngine = LevelEngine()
    private val srMemoryEngine = SRMemoryEngine()
    private val trendAgeEngine = TrendAgeEngine()
    private val indicatorEngine = IndicatorEngine()
    private val contextEnhancer = ContextEnhancer()
    private val regimeEngine = RegimeDetectionEngine()
    private val modularStrategyEngine = ModularStrategyEngine()
    @Volatile private var lastPersistedShadowSampleCount: Int = 0

    private val tickHistory = ArrayDeque<Tick>(305)
    private val activeOpenTrades = CopyOnWriteArrayList<ActiveOpenTrade>()
    private var lastTradeExecutionTimeMs: Long = 0L
    private var lastForegroundNotificationUpdateMs: Long = 0L

    @Volatile
    private var activeOrderExpiresAtMs: Long = 0L

    @Volatile
    private var lastHourlyTradeTimestampMs: Long = 0L

    @Volatile
    private var cachedDayStartMs: Long = 0L

    @Volatile
    private var nextDayRolloverMs: Long = 0L

    val sessionPrefs: StateFlow<SessionPreferences> = repository.sessionFlow
        .stateIn(backgroundEngineScope, SharingStarted.Eagerly, SessionPreferences())

    val strategies: StateFlow<List<StrategyConfig>> = repository.strategiesFlow
        .stateIn(backgroundEngineScope, SharingStarted.Eagerly, listOf(HardLockedMandate.lockedStrategyConfig()))

    val tradeJournal: StateFlow<List<TradeJournalEntity>> = repository.tradeJournalFlow
        .stateIn(backgroundEngineScope, SharingStarted.Eagerly, emptyList())

    val connectionStatus: StateFlow<WsConnectionStatus> = wsClient.connectionStatus
    val wsStatusMessage: StateFlow<String> = wsClient.statusMessage
    val wsLatencyMs: StateFlow<Long> = wsClient.wsLatencyMs
    val rawWireLog: StateFlow<List<String>> = wsClient.rawWireLog

    private val _botRunning = MutableStateFlow(false)
    val botRunning: StateFlow<Boolean> = _botRunning.asStateFlow()

    private val _autopilotArmed = MutableStateFlow(false)
    val autopilotArmed: StateFlow<Boolean> = _autopilotArmed.asStateFlow()

    private val _autopilotStandby = MutableStateFlow(false)
    val autopilotStandby: StateFlow<Boolean> = _autopilotStandby.asStateFlow()

    private val _telemetry = MutableStateFlow(MarketTelemetryState())
    val telemetry: StateFlow<MarketTelemetryState> = _telemetry.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private val _lastExecutionBanner = MutableStateFlow<String?>(null)
    val lastExecutionBanner: StateFlow<String?> = _lastExecutionBanner.asStateFlow()

    val availablePairs = listOf(
        "LATAM_X", "ASIA_X", "EURUSD", "GBPUSD", "USDJPY", "AUDUSD", "CRYPTO_X", "GOLD_X", "OTC_EURUSD"
    )

    init {
        BotExecutionForegroundService.onPauseBotRequested = {
            _botRunning.value = false
            _autopilotArmed.value = false
            _autopilotStandby.value = false
            _lastExecutionBanner.value = "Auto-Bot disarmed from background notification."
            pushForegroundNotification(force = true)
        }

        BotExecutionForegroundService.onResumeBotRequested = {
            armBotWithAutopilotCheck(sourceLabel = "Notification Resume")
        }

        BotExecutionForegroundService.onToggleHourlySniperRequested = {
            toggleHourlySniperMode()
        }

        backgroundEngineScope.launch(Dispatchers.IO) {
            repository.ensureSeeded()
            val savedLearningState = runCatching { repository.readSniperLearningState() }.getOrDefault("")
            if (savedLearningState.isNotBlank() && modularStrategyEngine.restoreLearningState(savedLearningState)) {
                lastPersistedShadowSampleCount = modularStrategyEngine.learnedSampleCount()
            }
            val initialPrefs = runCatching { repository.sessionFlow.first() }.getOrNull()
            if (initialPrefs != null) {
                if (initialPrefs.hourlySniperMode) {
                    _autopilotArmed.value = true
                }
                if (initialPrefs.autoReconnectSession && wsClient.connectionStatus.value == WsConnectionStatus.DISCONNECTED) {
                    if (initialPrefs.accessToken.isNotBlank() && initialPrefs.lastSessionMode == "AUTHENTICATED") {
                        wsClient.connect(initialPrefs.wsUri, initialPrefs.accessToken, initialPrefs.selectedPair)
                        _lastExecutionBanner.value = "Persistent Session Restored (${initialPrefs.selectedPair})"
                    } else if (initialPrefs.lastSessionMode == "SYNTHETIC_FEED" || initialPrefs.hourlySniperMode) {
                        wsClient.startLiveImpulseTestFeed(initialPrefs.selectedPair)
                    }
                }
            }
        }
        backgroundEngineScope.launch {
            wsClient.candlesSeedFlow.collectLatest { historical ->
                candleEngine.seedHistoricalCandles(historical)
                val current = _telemetry.value
                _telemetry.value = current.copy(candles = candleEngine.getAllCandles())
            }
        }
        backgroundEngineScope.launch {
            wsClient.liveCandleFlow.collect { serverCandle ->
                val updatedCandles = candleEngine.upsertServerCandle(serverCandle)
                val current = _telemetry.value
                _telemetry.value = current.copy(candles = updatedCandles.takeLast(80))
            }
        }
        backgroundEngineScope.launch(Dispatchers.IO) {
            wsClient.balanceFlow.collectLatest { bal ->
                repository.updateAccountBalances(
                    demoAccountId = bal.demoAccountId,
                    demoBalance = bal.demoBalance,
                    realAccountId = bal.realAccountId,
                    realBalance = bal.realBalance
                )
            }
        }
        backgroundEngineScope.launch(Dispatchers.IO) {
            wsClient.orderAcceptedFlow.collect { accepted ->
                if (accepted.brokerTradeId > 0L) {
                    val idx = activeOpenTrades.indexOfLast {
                        it.pair == accepted.pair && abs(it.openedAtMs - accepted.timestampMs) < 5_000L
                    }
                    if (idx >= 0) {
                        val existing = activeOpenTrades[idx]
                        if (existing.brokerTradeId != accepted.brokerTradeId) {
                            activeOpenTrades[idx] = existing.copy(
                                brokerTradeId = accepted.brokerTradeId,
                                entryPrice = if (accepted.openPrice > 0.0) accepted.openPrice else existing.entryPrice
                            )
                        }
                    }
                    repository.updateAcceptedTrade(
                        pair = accepted.pair,
                        brokerTradeId = accepted.brokerTradeId,
                        openPrice = accepted.openPrice
                    )
                }
            }
        }
        backgroundEngineScope.launch(Dispatchers.IO) {
            wsClient.orderSettledFlow.collect { settled ->
                val nowMs = System.currentTimeMillis()
                activeOpenTrades.removeAll {
                    it.brokerTradeId == settled.brokerTradeId ||
                        (it.pair == settled.pair && nowMs >= it.expiresAtMs - 1200L)
                }
                if (activeOpenTrades.isEmpty()) {
                    activeOrderExpiresAtMs = 0L
                }
                val freshJournal = repository.settleTradeAndGetJournal(
                    brokerTradeId = settled.brokerTradeId,
                    pair = settled.pair,
                    exitPrice = settled.closePrice,
                    winStatus = settled.outcome,
                    profitLoss = settled.profitLoss
                )
                if (wsClient.connectionStatus.value == WsConnectionStatus.SYNTHETIC_FEED && settled.profitLoss != 0.0) {
                    val curPrefs = sessionPrefs.value
                    if (curPrefs.accountGroup == "real") {
                        repository.updateAccountBalances(
                            demoAccountId = null,
                            demoBalance = null,
                            realAccountId = null,
                            realBalance = (curPrefs.realBalance + settled.profitLoss).coerceAtLeast(0.0)
                        )
                    } else {
                        repository.updateAccountBalances(
                            demoAccountId = null,
                            demoBalance = (curPrefs.demoBalance + settled.profitLoss).coerceAtLeast(0.0),
                            realAccountId = null,
                            realBalance = null
                        )
                    }
                }
                val updatedRisk = computeHardLockedDailyStats(sessionPrefs.value, nowMs, freshJournal)
                if (updatedRisk.hardStopReason != null && (_botRunning.value || _autopilotArmed.value)) {
                    _botRunning.value = false
                    _autopilotArmed.value = false
                    _autopilotStandby.value = false
                    _lastExecutionBanner.value = "HARD STOP TRIGGERED: ${updatedRisk.hardStopReason}"
                } else {
                    _lastExecutionBanner.value =
                        "Trade #${settled.brokerTradeId} settled: ${settled.outcome} (${"%+.2f".format(settled.profitLoss)}) • 1-Trade Lock Released"
                }
                _telemetry.value = _telemetry.value.copy(
                    activeOpenTrades = activeOpenTrades.toList(),
                    singleTradeLocked = hasActiveTradeInFlight(nowMs, freshJournal),
                    dailyNetPnl = updatedRisk.dailyNetPnl,
                    dailyTradesCount = updatedRisk.dailyTradesCount,
                    consecutiveLosses = updatedRisk.consecutiveLosses,
                    hourlyTradesThisHour = updatedRisk.hourlyTradesThisHour,
                    nextHourlyResetMinutes = updatedRisk.nextHourlyResetMinutes,
                    hardStopReason = updatedRisk.hardStopReason
                )
                pushForegroundNotification(force = true)
            }
        }
        backgroundEngineScope.launch {
            wsClient.tickFlow.collect { tick ->
                processIncomingTick(tick)
            }
        }
        // Background watchdog & 1-second timer to release expired 1-trade locks and keep 24/7 Chrono Sniper / Autopilot alive
        backgroundEngineScope.launch {
            while (isActive) {
                delay(1000L)
                val nowMs = System.currentTimeMillis()
                val hadTrades = activeOpenTrades.isNotEmpty() || activeOrderExpiresAtMs > 0L
                val stillLocked = hasActiveTradeInFlight(nowMs)
                val prefs = sessionPrefs.value
                val stats = computeHardLockedDailyStats(prefs, nowMs)
                val cur = _telemetry.value
                if (hadTrades != stillLocked ||
                    cur.singleTradeLocked != stillLocked ||
                    cur.hourlyTradesThisHour != stats.hourlyTradesThisHour ||
                    cur.nextHourlyResetMinutes != stats.nextHourlyResetMinutes ||
                    cur.hourlySniperMode != prefs.hourlySniperMode
                ) {
                    _telemetry.value = cur.copy(
                        activeOpenTrades = activeOpenTrades.toList(),
                        singleTradeLocked = stillLocked,
                        hourlySniperMode = prefs.hourlySniperMode,
                        hourlyTradesThisHour = stats.hourlyTradesThisHour,
                        nextHourlyResetMinutes = stats.nextHourlyResetMinutes,
                        autopilotArmed = _autopilotArmed.value,
                        autopilotStandby = _autopilotStandby.value
                    )
                }
                if ((_botRunning.value || _autopilotArmed.value || prefs.hourlySniperMode) &&
                    wsClient.connectionStatus.value == WsConnectionStatus.DISCONNECTED &&
                    prefs.autoReconnectSession
                ) {
                    if (prefs.accessToken.isNotBlank() && prefs.lastSessionMode == "AUTHENTICATED") {
                        wsClient.connect(prefs.wsUri, prefs.accessToken, prefs.selectedPair)
                    } else if (prefs.lastSessionMode == "SYNTHETIC_FEED" || prefs.hourlySniperMode) {
                        wsClient.startLiveImpulseTestFeed(prefs.selectedPair)
                    }
                }
            }
        }
    }

    private fun hasActiveTradeInFlight(
        nowMs: Long = System.currentTimeMillis(),
        journalSnapshot: List<TradeJournalEntity> = tradeJournal.value
    ): Boolean {
        activeOpenTrades.removeAll { nowMs >= it.expiresAtMs }
        if (nowMs >= activeOrderExpiresAtMs && activeOpenTrades.isEmpty()) {
            activeOrderExpiresAtMs = 0L
        }
        val pendingInJournal = journalSnapshot.any {
            it.outcome == "PENDING" && nowMs < (it.timestampMs + it.durationSeconds * 1000L)
        }
        return activeOpenTrades.isNotEmpty() ||
            nowMs < activeOrderExpiresAtMs ||
            wsClient.pendingOrderCheck ||
            pendingInJournal
    }

    private fun isHvWindowAvoid(hv: com.olympquant.terminal.domain.HvWindowSnapshot): Boolean {
        return hv.state == HvWindowState.AVOID ||
            hv.pauseImmediately ||
            hv.tightChopDetected ||
            hv.crazyWhipsawDetected ||
            hv.score < 50.0
    }

    private fun isHvWindowModerateOrGood(hv: com.olympquant.terminal.domain.HvWindowSnapshot): Boolean {
        return hv.engineBooted &&
            !isHvWindowAvoid(hv) &&
            (hv.state == HvWindowState.MODERATE || hv.state == HvWindowState.EXCELLENT) &&
            hv.score >= 50.0
    }

    private fun armBotWithAutopilotCheck(sourceLabel: String = "Manual") {
        val prefs = sessionPrefs.value
        val activeStrategy = currentLoadedStrategy()
        val stats = computeHardLockedDailyStats(prefs)
        if (stats.hardStopReason != null) {
            _botRunning.value = false
            _autopilotArmed.value = false
            _autopilotStandby.value = false
            _lastExecutionBanner.value = "LOCKED HARD STOP: ${stats.hardStopReason}"
            return
        }
        _autopilotArmed.value = true
        val hv = _telemetry.value.hvWindow
        if (prefs.autoPauseOnHvAvoid && isHvWindowAvoid(hv)) {
            _botRunning.value = false
            _autopilotStandby.value = true
            _lastExecutionBanner.value =
                "AUTOPILOT GUARD STANDBY ($sourceLabel): Market in AVOID (${"%.0f".format(hv.score)}%) — Auto-Trading Paused & Will Auto-Start on MODERATE/GOOD"
        } else {
            _botRunning.value = true
            _autopilotStandby.value = false
            val modeTag = if (prefs.hourlySniperMode) "CHRONO SNIPER 1/HR" else activeStrategy.name
            _lastExecutionBanner.value =
                "ARMED ($modeTag): Stake $${"%.2f".format(stats.lockedStakeUsd)} • 1-Trade Lock Active • HV ${hv.state.label} (${"%.0f".format(hv.score)}%)"
        }
        _telemetry.value = _telemetry.value.copy(
            autopilotArmed = _autopilotArmed.value,
            autopilotStandby = _autopilotStandby.value
        )
        pushForegroundNotification(force = true)
    }

    fun clearAuthError() {
        _authError.value = null
    }

    fun dismissBanner() {
        _lastExecutionBanner.value = null
    }

    fun loginWorkstation(email: String, passwordPlain: String) {
        if (email.isBlank() || passwordPlain.isBlank()) {
            _authError.value = "Please enter both email and password."
            return
        }
        viewModelScope.launch {
            val result = repository.loginUser(email, passwordPlain)
            result.onFailure { _authError.value = it.message }
            result.onSuccess { _authError.value = null }
        }
    }

    fun registerWorkstation(username: String, email: String, passwordPlain: String) {
        if (username.isBlank() || email.isBlank() || passwordPlain.length < 4) {
            _authError.value = "Enter a valid username, email, and password (min 4 chars)."
            return
        }
        viewModelScope.launch {
            val result = repository.registerUser(username, email, passwordPlain)
            result.onFailure { _authError.value = it.message }
            result.onSuccess { _authError.value = null }
        }
    }

    fun logoutWorkstation() {
        _botRunning.value = false
        _autopilotArmed.value = false
        _autopilotStandby.value = false
        appContext?.let { BotExecutionForegroundService.stopService(it) }
        wsClient.disconnect()
        viewModelScope.launch {
            repository.logoutUser()
        }
    }

    fun saveAndConnectBroker(accessToken: String, wsUri: String) {
        viewModelScope.launch {
            val cleanUri = wsUri.ifBlank { SessionPreferences.DEFAULT_WS_URI }
            repository.updateBrokerCredentials(accessToken, cleanUri)
            if (accessToken.isNotBlank()) {
                repository.updatePersistentSessionMeta(
                    lastMode = "AUTHENTICATED",
                    tokenCapturedAtMs = System.currentTimeMillis()
                )
                wsClient.connect(cleanUri, accessToken.trim(), sessionPrefs.value.selectedPair)
                _lastExecutionBanner.value = "Session token persisted in Vault & connecting WS..."
            }
        }
    }

    fun setAutoReconnectSession(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updatePersistentSessionMeta(autoReconnect = enabled)
            _lastExecutionBanner.value =
                if (enabled) "Persistent Session Auto-Reconnect ENABLED" else "Persistent Session Auto-Reconnect DISABLED"
        }
    }

    fun restoreSavedSessionNow() {
        val prefs = sessionPrefs.value
        if (prefs.accessToken.isNotBlank()) {
            wsClient.connect(prefs.wsUri, prefs.accessToken, prefs.selectedPair)
            _lastExecutionBanner.value = "Restoring saved Olymp Trade WebSocket session..."
        } else {
            startLiveImpulseTestFeed()
        }
    }

    fun startLiveImpulseTestFeed() {
        val pair = sessionPrefs.value.selectedPair
        backgroundEngineScope.launch {
            repository.updatePersistentSessionMeta(lastMode = "SYNTHETIC_FEED")
            resetEnginesForNewPair(pair)
            wsClient.startLiveImpulseTestFeed(pair)
            _lastExecutionBanner.value = "High-Frequency Ballistic Impulse Stream active on $pair"
            pushForegroundNotification(force = true)
        }
    }

    fun disconnectBroker() {
        _botRunning.value = false
        _autopilotArmed.value = false
        _autopilotStandby.value = false
        appContext?.let { BotExecutionForegroundService.stopService(it) }
        viewModelScope.launch(Dispatchers.IO) {
            repository.updatePersistentSessionMeta(lastMode = "DISCONNECTED")
        }
        wsClient.disconnect()
    }

    fun selectAssetPair(pair: String) {
        if (pair == sessionPrefs.value.selectedPair) return
        backgroundEngineScope.launch {
            repository.updateSelectedPair(pair)
            resetEnginesForNewPair(pair)
            wsClient.switchAssetPair(pair)
            pushForegroundNotification(force = true)
        }
    }

    fun toggleBotRunning() {
        if (!_botRunning.value && !_autopilotStandby.value) {
            armBotWithAutopilotCheck(sourceLabel = "Manual")
        } else {
            _botRunning.value = false
            _autopilotArmed.value = false
            _autopilotStandby.value = false
            _telemetry.value = _telemetry.value.copy(
                autopilotArmed = false,
                autopilotStandby = false
            )
            _lastExecutionBanner.value = "Auto-Bot Disarmed & Placed on Standby."
            pushForegroundNotification(force = true)
        }
    }

    fun toggleHourlySniperMode() {
        setHourlySniperMode(!sessionPrefs.value.hourlySniperMode)
    }

    fun setHourlySniperMode(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateHourlySniperMode(enabled)
            val stats = computeHardLockedDailyStats(sessionPrefs.value.copy(hourlySniperMode = enabled))
            if (enabled) {
                _autopilotArmed.value = true
                val hv = _telemetry.value.hvWindow
                if (sessionPrefs.value.autoPauseOnHvAvoid && isHvWindowAvoid(hv)) {
                    _botRunning.value = false
                    _autopilotStandby.value = true
                    _lastExecutionBanner.value =
                        "CHRONO SNIPER (1/HR) ARMED: Waiting in Autopilot Standby (AVOID ${"%.0f".format(hv.score)}%) • Will Auto-Start on MODERATE/GOOD"
                } else if (stats.hardStopReason == null) {
                    _botRunning.value = true
                    _autopilotStandby.value = false
                    _lastExecutionBanner.value =
                        "CHRONO SNIPER (1/HR) ACTIVE: Bot takes max 1 trade per hour, every day (${stats.hourlyTradesThisHour}/1 this hr)"
                }
                if (wsClient.connectionStatus.value == WsConnectionStatus.DISCONNECTED) {
                    val prefs = sessionPrefs.value
                    if (prefs.accessToken.isNotBlank() && prefs.lastSessionMode == "AUTHENTICATED") {
                        wsClient.connect(prefs.wsUri, prefs.accessToken, prefs.selectedPair)
                    } else {
                        wsClient.startLiveImpulseTestFeed(prefs.selectedPair)
                    }
                }
            } else {
                _lastExecutionBanner.value =
                    "Chrono Sniper (1/Hr) Mode OFF — Standard strategy cadence restored"
            }
            _telemetry.value = _telemetry.value.copy(
                hourlySniperMode = enabled,
                hourlyTradesThisHour = stats.hourlyTradesThisHour,
                nextHourlyResetMinutes = stats.nextHourlyResetMinutes,
                autopilotArmed = _autopilotArmed.value,
                autopilotStandby = _autopilotStandby.value
            )
            pushForegroundNotification(force = true)
        }
    }

    fun switchAccountGroup(group: String) {
        viewModelScope.launch {
            repository.updateAccountGroup(group)
            pushForegroundNotification(force = true)
        }
    }

    fun setDensityMode(mode: TerminalDensityMode) {
        viewModelScope.launch {
            repository.updateDensityMode(mode)
        }
    }

    fun selectTradeTimeframe(durationSec: Int) {
        // The strategy's horizon is locked to five seconds; ignore legacy timeframe requests.
        _telemetry.value = _telemetry.value.copy(selectedTimeframeSec = 5)
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateSelectedTimeframe(5)
            _lastExecutionBanner.value = "Sniper M fixed expiry: 5 seconds"
            pushForegroundNotification(force = true)
        }
    }

    fun setAutoPauseOnHvAvoid(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateAutoPauseOnHvAvoid(enabled)
            val hv = _telemetry.value.hvWindow
            val stats = computeHardLockedDailyStats(sessionPrefs.value.copy(autoPauseOnHvAvoid = enabled))
            if (enabled) {
                _autopilotArmed.value = true
                if (isHvWindowAvoid(hv)) {
                    _botRunning.value = false
                    _autopilotStandby.value = true
                    _lastExecutionBanner.value =
                        "AUTOPILOT GUARD ON: Auto-Trading Stopped in AVOID (${"%.0f".format(hv.score)}%) • Will Auto-Start on MODERATE to GOOD"
                } else if (stats.hardStopReason == null) {
                    _botRunning.value = true
                    _autopilotStandby.value = false
                    _lastExecutionBanner.value =
                        "AUTOPILOT GUARD ON: Auto-Started Bot in ${hv.state.label} (${"%.0f".format(hv.score)}%) • Auto-Stops on AVOID"
                }
            } else {
                _autopilotStandby.value = false
                _lastExecutionBanner.value =
                    "Autopilot Guard OFF: Manual bot control during AVOID states"
            }
            _telemetry.value = _telemetry.value.copy(
                autopilotArmed = _autopilotArmed.value,
                autopilotStandby = _autopilotStandby.value
            )
            pushForegroundNotification(force = true)
        }
    }

    fun clearTradeJournal() {
        viewModelScope.launch(Dispatchers.IO) {
            lastHourlyTradeTimestampMs = 0L
            activeOrderExpiresAtMs = 0L
            activeOpenTrades.clear()
            repository.clearJournal()
            _lastExecutionBanner.value = "Trade journal, hourly sniper lock & daily hard-stop counters cleared."
        }
    }

    fun resetDailyCounters() {
        viewModelScope.launch(Dispatchers.IO) {
            lastHourlyTradeTimestampMs = 0L
            repository.resetDailyPnlTimestamp()
            _lastExecutionBanner.value = "Daily trade quota, hourly sniper slot & loss streak counters reset."
            pushForegroundNotification(force = true)
        }
    }

    fun exportJournalJson(): String {
        val json = Json { prettyPrint = true }
        val rows = tradeJournal.value.map {
            mapOf(
                "id" to it.id.toString(),
                "brokerTradeId" to it.brokerTradeId.toString(),
                "timestampMs" to it.timestampMs.toString(),
                "strategy" to it.strategyName,
                "pair" to it.pair,
                "direction" to it.direction,
                "stake" to it.stake.toString(),
                "durationSeconds" to it.durationSeconds.toString(),
                "accountGroup" to it.accountGroup,
                "wsLatencyMs" to it.wsLatencyMs.toString(),
                "hmmState" to it.hmmState.toString(),
                "hmmRegime" to it.hmmRegime,
                "hmmConfidence" to "%.4f".format(it.hmmConfidence),
                "hvoiScore" to "%.2f".format(it.hvoiScore),
                "consistency" to "%.4f".format(it.consistency),
                "slopeRatio" to "%.4f".format(it.slopeRatio),
                "outcome" to it.outcome,
                "profitLoss" to "%.2f".format(it.profitLoss)
            )
        }
        return json.encodeToString(rows)
    }

    fun currentLoadedStrategy(): StrategyConfig {
        val list = strategies.value
        return list.firstOrNull { it.isEnabled }
            ?: list.firstOrNull()
            ?: HardLockedMandate.lockedStrategyConfig()
    }

    fun getLiveSymbolSnapshot(scriptCode: String): Map<String, Double> {
        return modularStrategyEngine.getLiveSymbolSnapshot(
            telemetry = _telemetry.value,
            wsLatencyMs = wsLatencyMs.value,
            scriptCode = scriptCode
        )
    }

    fun createNewStrategyFromTemplate(customName: String, onCreated: (StrategyConfig) -> Unit = {}) {
        viewModelScope.launch {
            val tfSec = TradeTimeframe.fromSeconds(sessionPrefs.value.selectedTimeframeSec).seconds
            val created = withContext(Dispatchers.IO) {
                repository.createStrategyFromTemplate(customName, durationSec = tfSec)
            }
            _lastExecutionBanner.value = "Created & Loaded Strategy: ${created.name} (${TradeTimeframe.fromSeconds(created.durationSeconds).label})"
            onCreated(created)
        }
    }

    fun saveAndLoadStrategyScript(
        strategyId: Long,
        scriptCode: String,
        onResult: (Result<StrategyConfig>) -> Unit = {}
    ) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.saveAndLoadStrategyScript(strategyId, scriptCode)
            }
            result.onSuccess { saved ->
                _lastExecutionBanner.value =
                    "Loaded Strategy: ${saved.name} • ${saved.durationSeconds}s • ${saved.stakeMode} (${saved.stakeValue})"
            }.onFailure { err ->
                _lastExecutionBanner.value = "Script Compile Error: ${err.message}"
            }
            onResult(result)
        }
    }

    fun loadStrategy(strategyId: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.loadStrategy(strategyId)
            }
            val chosen = strategies.value.firstOrNull { it.id == strategyId }
            if (chosen != null) {
                _lastExecutionBanner.value =
                    "Active Loaded Strategy: ${chosen.name} (${chosen.durationSeconds}s)"
            }
        }
    }

    fun deleteStrategy(strategyId: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.deleteStrategy(strategyId)
            }
            _lastExecutionBanner.value = "Strategy removed • Active strategy updated"
        }
    }

    fun resetToDefaultStrategy(onReset: (StrategyConfig) -> Unit = {}) {
        viewModelScope.launch {
            val defaultCfg = withContext(Dispatchers.IO) {
                repository.resetToDefaultStrategy()
            }
            _lastExecutionBanner.value = "Reset to Default Strategy: ${defaultCfg.name}"
            onReset(defaultCfg)
        }
    }

    private data class HardLockedDailyStats(
        val dailyNetPnl: Double,
        val dailyTradesCount: Int,
        val consecutiveLosses: Int,
        val lockedStakeUsd: Double,
        val maxDailyTrades: Int,
        val maxConsecutiveLosses: Int,
        val hourlyTradesThisHour: Int,
        val nextHourlyResetMinutes: Int,
        val hardStopReason: String?
    )

    private fun startOfCurrentDayMs(now: Long = System.currentTimeMillis()): Long {
        if (now < nextDayRolloverMs && cachedDayStartMs > 0L) {
            return cachedDayStartMs
        }
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startMs = cal.timeInMillis
        cachedDayStartMs = startMs
        nextDayRolloverMs = startMs + 86_400_000L
        return startMs
    }

    private fun computeHardLockedDailyStats(
        prefs: SessionPreferences,
        nowMs: Long = System.currentTimeMillis(),
        journalSnapshot: List<TradeJournalEntity> = tradeJournal.value
    ): HardLockedDailyStats {
        val activeStrategy = currentLoadedStrategy()
        val dayStartMs = maxOf(startOfCurrentDayMs(nowMs), prefs.dailyPnlResetAtMs)

        val hourCal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val rawHourStartMs = hourCal.timeInMillis
        val currentHourStartMs = maxOf(rawHourStartMs, prefs.dailyPnlResetAtMs)
        val nextHourStartMs = rawHourStartMs + 3_600_000L
        val nextHourlyResetMinutes = (((nextHourStartMs - nowMs).coerceAtLeast(0L) + 59_999L) / 60_000L)
            .toInt()
            .coerceIn(1, 60)

        val journal = journalSnapshot
        var dailyTradesCount = 0
        var dbHourlyTradesCount = 0
        var dailyNetPnl = 0.0
        var consecutiveLosses = 0
        var streakBroken = false

        for (i in journal.indices) {
            val t = journal[i]
            if (t.timestampMs < dayStartMs) break
            if (t.outcome == "WS_OFFLINE") continue
            dailyTradesCount++
            if (t.timestampMs >= currentHourStartMs) {
                dbHourlyTradesCount++
            }
            if (t.outcome == "WIN" || t.outcome == "LOSS") {
                dailyNetPnl += t.profitLoss.finiteOr(0.0)
                if (!streakBroken) {
                    if (t.outcome == "LOSS") {
                        consecutiveLosses++
                    } else {
                        streakBroken = true
                    }
                }
            }
        }
        dailyNetPnl = dailyNetPnl.finiteOr(0.0)

        val inMemHourly = if (lastHourlyTradeTimestampMs >= currentHourStartMs) 1 else 0
        val hourlyTradesThisHour = maxOf(dbHourlyTradesCount, inMemHourly)

        val activeBalance = if (prefs.accountGroup == "real") prefs.realBalance else prefs.demoBalance
        val lockedStakeUsd = activeStrategy.computeEffectiveStake(activeBalance)
        val baseMaxDaily = activeStrategy.maxDailyTrades.coerceIn(1, 200)
        // In Chrono Sniper (1/Hr) mode, allow 24 hourly trades per day (1 trade each hour every day)
        val maxDaily = if (prefs.hourlySniperMode) maxOf(24, baseMaxDaily) else baseMaxDaily
        val maxLossStreak = activeStrategy.maxConsecutiveLosses.coerceIn(1, 25)

        val hardStopReason = when {
            consecutiveLosses >= maxLossStreak ->
                "$consecutiveLosses/$maxLossStreak Consecutive Losses Reached (Hard Stop)"
            dailyTradesCount >= maxDaily ->
                "$dailyTradesCount/$maxDaily Daily Trade Cap Reached (Hard Stop)"
            else -> null
        }

        return HardLockedDailyStats(
            dailyNetPnl = dailyNetPnl,
            dailyTradesCount = dailyTradesCount,
            consecutiveLosses = consecutiveLosses,
            lockedStakeUsd = lockedStakeUsd,
            maxDailyTrades = maxDaily,
            maxConsecutiveLosses = maxLossStreak,
            hourlyTradesThisHour = hourlyTradesThisHour,
            nextHourlyResetMinutes = nextHourlyResetMinutes,
            hardStopReason = hardStopReason
        )
    }

    fun executeManualOrder(direction: SignalDirection) {
        val now = System.currentTimeMillis()
        val prefs = sessionPrefs.value
        val activeStrategy = currentLoadedStrategy()
        val stats = computeHardLockedDailyStats(prefs, now)
        if (stats.hardStopReason != null) {
            _lastExecutionBanner.value = "BLOCKED BY HARD STOP: ${stats.hardStopReason}"
            return
        }

        if (hasActiveTradeInFlight(now)) {
            val remSec = ((activeOrderExpiresAtMs - now).coerceAtLeast(1000L) / 1000.0)
            _lastExecutionBanner.value =
                "1-TRADE-AT-A-TIME LOCK: 1 trade is already active (${"%.1f".format(remSec)}s left). Wait for settlement."
            return
        }

        if (prefs.hourlySniperMode && stats.hourlyTradesThisHour >= 1) {
            _lastExecutionBanner.value =
                "CHRONO SNIPER 1/HR LOCK: 1/1 hourly trade already taken • Next slot opens in ${stats.nextHourlyResetMinutes}m"
            return
        }

        val stake = stats.lockedStakeUsd
        val duration = 5
        val accountId = if (prefs.accountGroup == "real") prefs.realAccountId else prefs.demoAccountId
        val snap = _telemetry.value
        val tradeId = now % 1_000_000L

        val sent = wsClient.placeOrder(
            pair = prefs.selectedPair,
            amount = stake,
            direction = direction,
            durationSec = duration,
            accountId = accountId,
            accountGroup = prefs.accountGroup,
            clientOrderId = tradeId
        )
        if (sent) {
            activeOrderExpiresAtMs = now + duration * 1000L
            lastHourlyTradeTimestampMs = now
            if (snap.latestPrice > 0.0) {
                activeOpenTrades.add(
                    ActiveOpenTrade(
                        brokerTradeId = tradeId,
                        strategyName = activeStrategy.name,
                        pair = prefs.selectedPair,
                        direction = direction,
                        stake = stake,
                        entryPrice = snap.latestPrice,
                        openedAtMs = now,
                        expiresAtMs = now + duration * 1000L,
                        payoutPercent = wsClient.assetPayoutPercent.value
                    )
                )
            }
            _telemetry.value = snap.copy(
                activeOpenTrades = activeOpenTrades.toList(),
                singleTradeLocked = true,
                hourlyTradesThisHour = stats.hourlyTradesThisHour + 1
            )
        }
        backgroundEngineScope.launch(Dispatchers.IO) {
            repository.recordTradeEntry(
                TradeJournalEntity(
                    brokerTradeId = tradeId,
                    timestampMs = now,
                    strategyName = activeStrategy.name,
                    pair = prefs.selectedPair,
                    direction = direction.name,
                    stake = stake,
                    durationSeconds = duration,
                    accountGroup = prefs.accountGroup,
                    accountId = accountId,
                    wsLatencyMs = wsLatencyMs.value,
                    hmmState = snap.hmmOutput?.state ?: -1,
                    hmmRegime = snap.hmmOutput?.regimeName ?: "Manual",
                    hmmConfidence = snap.hmmOutput?.confidence ?: 0.0,
                    hvoiScore = snap.hvoiScore,
                    volatility = snap.volatility,
                    velocity = snap.velocity,
                    consistency = snap.consistency,
                    slopeRatio = snap.slopeRatio,
                    srDistance = snap.srDistance,
                    trendAgeSeconds = snap.trendAgeSeconds,
                    entryPrice = snap.latestPrice,
                    exitPrice = 0.0,
                    outcome = if (sent) "PENDING" else "WS_OFFLINE",
                    profitLoss = 0.0
                )
            )
            if (sent) {
                val newCount = stats.dailyTradesCount + 1
                if (newCount >= stats.maxDailyTrades) {
                    _botRunning.value = false
                    _autopilotArmed.value = false
                    _autopilotStandby.value = false
                }
                _lastExecutionBanner.value =
                    "Executed ${direction.name} $${"%.2f".format(stake)} (${activeStrategy.name} • ${duration}s • 1-Trade Lock Active • Trade $newCount/${stats.maxDailyTrades})"
            } else {
                _lastExecutionBanner.value =
                    "Feed Offline — Start Ballistic Impulse Stream or connect Broker WebSocket before placing ${direction.name} orders"
            }
            pushForegroundNotification(force = true)
        }
    }

    private fun resetEnginesForNewPair(pair: String) {
        tickHistory.clear()
        activeOpenTrades.clear()
        activeOrderExpiresAtMs = 0L
        candleEngine.reset()
        structureEngine.reset()
        levelEngine.reset()
        trendAgeEngine.reset()
        regimeEngine.reset()
        indicatorEngine.reset()
        modularStrategyEngine.reset()
        val prefs = sessionPrefs.value
        _telemetry.value = MarketTelemetryState(
            pair = pair,
            selectedTimeframeSec = 5,
            autopilotArmed = _autopilotArmed.value,
            autopilotStandby = _autopilotStandby.value,
            hourlySniperMode = prefs.hourlySniperMode
        )
    }

    private suspend fun processIncomingTick(tick: Tick) {
        val nowMs = System.currentTimeMillis()
        activeOpenTrades.removeAll { nowMs >= it.expiresAtMs }
        if (nowMs >= activeOrderExpiresAtMs && activeOpenTrades.isEmpty()) {
            activeOrderExpiresAtMs = 0L
        }

        val prevPrice = tickHistory.lastOrNull()?.price ?: 0.0
        tickHistory.addLast(tick)
        while (tickHistory.size > 300) {
            tickHistory.removeFirst()
        }
        val tickList = tickHistory.toList()

        // 1. Update 5s OHLC Candle Engine & Structure Swings
        val candles = candleEngine.processTick(tick)
        val avgRange = candleEngine.getAverageCandleRange()
        val swings = structureEngine.updateSwings(candles)

        // 2. Update Psychological Round-Number Grid & Bounce Engine
        val gridSpacing = levelEngine.computeGridSpacing(tick.price, avgRange)
        val gridLevels = levelEngine.getNearbyLevels(tick.price, gridSpacing)
        val lastBounce = levelEngine.processTick(prevPrice, tick, avgRange)

        // 3. Update Consolidated S/R Memory Zones with Adaptive Corridor
        val corridorWidth = srMemoryEngine.computeAdaptiveCorridor(avgRange, tick.price)
        val (nearestSupport, nearestResistance) = srMemoryEngine.buildConsolidatedZones(
            swings = swings,
            currentPrice = tick.price,
            corridorWidth = corridorWidth
        )
        val srDistance = minOf(
            nearestSupport?.let { abs(tick.price - it.centerPrice) } ?: 999.0,
            nearestResistance?.let { abs(it.centerPrice - tick.price) } ?: 999.0
        )

        // 4. Update Mathematical Indicators (ZLEMA 10/30, 50-Tick Fibonacci, Microstructure, HV Window & HighVoltage 6 Filters)
        val ind = indicatorEngine.computeIndicators(
            ticks = tickList,
            candles = candles,
            swings = swings,
            psychLevels = gridLevels,
            nearestSupport = nearestSupport,
            nearestResistance = nearestResistance
        )
        val trendAgeSec = trendAgeEngine.update(ind.trend, tick.timeMs)

        val prefs = sessionPrefs.value
        val activeStrategy = currentLoadedStrategy()
        val selectedTfSec = 5
        val orderFlowImbalance = wsClient.orderFlowImbalance.value
        val tickIntensityRatio = wsClient.tickIntensityRatio.value
        val assetPayoutPct = wsClient.assetPayoutPercent.value
        val assetLocked = wsClient.assetLocked.value
        val bullishSentimentPct = wsClient.bullishSentimentPercent.value

        // 5. Run Gaussian HMM Regime Inference
        val hmmOutput = regimeEngine.processTicks(tickList)

        // 6. Evaluate Context Enhancer & Base Confidence (including HMM regime, consistency, and exhaustion blocks)
        val (contextBlocks, baseConfidence) = contextEnhancer.evaluateContext(
            price = tick.price,
            indicators = ind,
            candles = candles,
            swings = swings,
            nearestSupport = nearestSupport,
            nearestResistance = nearestResistance,
            corridorWidth = corridorWidth,
            orderFlowImbalance = orderFlowImbalance,
            tickIntensityRatio = tickIntensityRatio,
            assetPayoutPercent = assetPayoutPct,
            minPayoutPercent = activeStrategy.minPayoutPercent,
            assetLocked = assetLocked,
            bullishSentimentPercent = bullishSentimentPct,
            trendAgeSeconds = trendAgeSec,
            hmmOutput = hmmOutput
        )

        val stats = computeHardLockedDailyStats(prefs, nowMs)

        // =========================================================================
        // 6B. BIDIRECTIONAL AUTOPILOT GUARD:
        //     - Immediately STOPS auto-trading during AVOID (<50%, Tight Chop, Whipsaw)
        //     - Automatically STARTS/RESUMES auto-trading during MODERATE (50-74%) to GOOD/EXCELLENT (≥75%)
        // =========================================================================
        if (stats.hardStopReason != null) {
            if (_botRunning.value || _autopilotStandby.value) {
                _botRunning.value = false
                _autopilotArmed.value = false
                _autopilotStandby.value = false
                _lastExecutionBanner.value = "HARD STOP ENGAGED: ${stats.hardStopReason}"
                pushForegroundNotification(force = true)
            }
        } else if (prefs.autoPauseOnHvAvoid && (_autopilotArmed.value || _botRunning.value || _autopilotStandby.value || prefs.hourlySniperMode)) {
            _autopilotArmed.value = true
            val isAvoid = isHvWindowAvoid(ind.hvWindow)
            val isModerateOrGood = isHvWindowModerateOrGood(ind.hvWindow)

            if (isAvoid) {
                val wasRunning = _botRunning.value
                _botRunning.value = false
                _autopilotStandby.value = true
                if (wasRunning) {
                    _lastExecutionBanner.value =
                        "AUTOPILOT GUARD: Auto-Trading Stopped in AVOID (${"%.0f".format(ind.hvWindow.score)}% — ${ind.hvWindow.activeTrap.label}) • Will Auto-Start on MODERATE/GOOD"
                    pushForegroundNotification(force = true)
                }
            } else if (isModerateOrGood) {
                val wasPaused = !_botRunning.value || _autopilotStandby.value
                _botRunning.value = true
                _autopilotStandby.value = false
                if (wasPaused) {
                    _lastExecutionBanner.value =
                        "AUTOPILOT GUARD: Auto-Started Bot in ${ind.hvWindow.state.label} (${"%.0f".format(ind.hvWindow.score)}%)"
                    pushForegroundNotification(force = true)
                }
            }
        }

        val singleTradeLocked = hasActiveTradeInFlight(nowMs)

        val preliminaryState = MarketTelemetryState(
            pair = tick.pair,
            latestPrice = tick.price,
            latestTimeMs = tick.timeMs,
            tickCount = tickList.size,
            candles = candles.takeLast(80),
            recentTicks = tickList.takeLast(120),
            zFast = ind.zFast,
            zSlow = ind.zSlow,
            trend = ind.trend,
            htfBias = ind.htfBias,
            trendAgeSeconds = trendAgeSec,
            persistence = ind.persistence,
            consistency = ind.consistency,
            volatility = ind.volatility,
            velocity = ind.velocity,
            slope = ind.slope,
            slopeRatio = ind.slopeRatio,
            netDelta5 = ind.netDelta5,
            relPos = ind.relPos,
            hvoiScore = ind.hvoiScore,
            sessionQuality = ind.sessionQuality,
            adaptiveCorridorWidth = corridorWidth,
            nearestSupport = nearestSupport,
            nearestResistance = nearestResistance,
            srDistance = srDistance,
            swingPoints = swings,
            psychGridLevels = gridLevels,
            lastPsychBounce = lastBounce,
            fibLevels = ind.fibLevels,
            microstructure = ind.microstructure,
            hmmOutput = hmmOutput,
            contextBlockReasons = contextBlocks,
            baseConfidenceScore = baseConfidence,
            assetPayoutPercent = assetPayoutPct,
            assetLocked = assetLocked,
            bullishSentimentPercent = bullishSentimentPct,
            orderFlowImbalance = orderFlowImbalance,
            tickIntensityRatio = tickIntensityRatio,
            serverClockOffsetMs = wsClient.serverClockOffsetMs.value,
            dailyNetPnl = stats.dailyNetPnl,
            dailyTradesCount = stats.dailyTradesCount,
            consecutiveLosses = stats.consecutiveLosses,
            currentMartingaleStep = 0,
            nextEffectiveStake = stats.lockedStakeUsd,
            hardStopReason = stats.hardStopReason,
            tickRateTps = wsClient.tickRateTps.value,
            wsSynchronized = wsClient.isStreamSynchronized.value,
            activeOpenTrades = activeOpenTrades.toList(),
            hvWindow = ind.hvWindow,
            highVoltageStatus = ind.highVoltageStatus,
            selectedTimeframeSec = selectedTfSec,
            autopilotArmed = _autopilotArmed.value,
            autopilotStandby = _autopilotStandby.value,
            hourlySniperMode = prefs.hourlySniperMode,
            hourlyTradesThisHour = stats.hourlyTradesThisHour,
            nextHourlyResetMinutes = stats.nextHourlyResetMinutes,
            singleTradeLocked = singleTradeLocked
        )

        val latency = wsLatencyMs.value

        // 7. Evaluate the currently loaded .oqs strategy script
        val evaluation = modularStrategyEngine.evaluateStrategy(
            strategy = activeStrategy,
            telemetry = preliminaryState,
            wsLatencyMs = latency,
            minPayoutPercent = activeStrategy.minPayoutPercent
        )

        val updatedState = preliminaryState.copy(
            activeEvaluations = listOf(evaluation),
            temporalTelemetry = evaluation.temporalTelemetry
        )
        _telemetry.value = updatedState

        // Persist only when a new, fully settled shadow label updates the learner.
        val learnedSamples = modularStrategyEngine.learnedSampleCount()
        if (learnedSamples > lastPersistedShadowSampleCount) {
            val learningSnapshot = modularStrategyEngine.exportLearningState()
            lastPersistedShadowSampleCount = learnedSamples
            backgroundEngineScope.launch(Dispatchers.IO) {
                runCatching { repository.saveSniperLearningState(learningSnapshot) }
            }
        }

        // 8. Automated Execution Gate:
        //    - Requires _botRunning.value == true (Autopilot Guard not in AVOID standby)
        //    - Enforces Single Active Trade Lock (only 1 trade at a time!)
        //    - Enforces Chrono Sniper (1/Hr) mode when active (max 1 trade per hour, every day)
        if (_botRunning.value && !hasActiveTradeInFlight(nowMs)) {
            if (stats.hardStopReason != null) {
                _botRunning.value = false
                _autopilotArmed.value = false
                _autopilotStandby.value = false
                _lastExecutionBanner.value = "HARD STOP ENGAGED: ${stats.hardStopReason}"
                pushForegroundNotification(force = true)
                return
            }

            if (prefs.autoPauseOnHvAvoid && isHvWindowAvoid(ind.hvWindow)) {
                _botRunning.value = false
                _autopilotStandby.value = true
                _lastExecutionBanner.value =
                    "AUTOPILOT GUARD: Auto-Trading Paused in AVOID (${"%.0f".format(ind.hvWindow.score)}%) • Waiting for MODERATE/GOOD"
                pushForegroundNotification(force = true)
                return
            }

            // Chrono Sniper Mode: allow strictly 1 trade each hour every day
            if (prefs.hourlySniperMode && stats.hourlyTradesThisHour >= 1) {
                if (nowMs - lastForegroundNotificationUpdateMs >= 1000L) {
                    pushForegroundNotification(force = false)
                }
                return
            }

            val now = System.currentTimeMillis()
            if (evaluation.readyToExecute && now - lastTradeExecutionTimeMs >= activeStrategy.cooldownMs) {
                lastTradeExecutionTimeMs = now
                val stake = stats.lockedStakeUsd
                val durationSec = selectedTfSec
                val accountId = if (prefs.accountGroup == "real") prefs.realAccountId else prefs.demoAccountId
                val tradeId = now % 1_000_000L

                val dispatched = wsClient.placeOrder(
                    pair = tick.pair,
                    amount = stake,
                    direction = evaluation.finalSignal,
                    durationSec = durationSec,
                    accountId = accountId,
                    accountGroup = prefs.accountGroup,
                    clientOrderId = tradeId
                )
                if (dispatched) {
                    activeOrderExpiresAtMs = now + durationSec * 1000L
                    lastHourlyTradeTimestampMs = now
                    activeOpenTrades.add(
                        ActiveOpenTrade(
                            brokerTradeId = tradeId,
                            strategyName = activeStrategy.name,
                            pair = tick.pair,
                            direction = evaluation.finalSignal,
                            stake = stake,
                            entryPrice = tick.price,
                            openedAtMs = now,
                            expiresAtMs = now + durationSec * 1000L,
                            payoutPercent = assetPayoutPct
                        )
                    )
                    val newHourlyCount = stats.hourlyTradesThisHour + 1
                    val newTradeTotal = stats.dailyTradesCount + 1
                    _telemetry.value = _telemetry.value.copy(
                        activeOpenTrades = activeOpenTrades.toList(),
                        singleTradeLocked = true,
                        hourlyTradesThisHour = newHourlyCount,
                        dailyTradesCount = newTradeTotal
                    )
                    repository.recordTradeEntry(
                        TradeJournalEntity(
                            brokerTradeId = tradeId,
                            timestampMs = now,
                            strategyName = activeStrategy.name,
                            pair = tick.pair,
                            direction = evaluation.finalSignal.name,
                            stake = stake,
                            durationSeconds = durationSec,
                            accountGroup = prefs.accountGroup,
                            accountId = accountId,
                            wsLatencyMs = latency,
                            hmmState = hmmOutput?.state ?: -1,
                            hmmRegime = hmmOutput?.regimeName ?: "Unknown",
                            hmmConfidence = hmmOutput?.confidence ?: 0.0,
                            hvoiScore = ind.hvoiScore,
                            volatility = ind.volatility,
                            velocity = ind.velocity,
                            consistency = ind.consistency,
                            slopeRatio = ind.slopeRatio,
                            srDistance = srDistance,
                            trendAgeSeconds = trendAgeSec,
                            entryPrice = tick.price,
                            exitPrice = 0.0,
                            outcome = "PENDING",
                            profitLoss = 0.0
                        )
                    )
                    if (newTradeTotal >= stats.maxDailyTrades && !prefs.hourlySniperMode) {
                        _botRunning.value = false
                        _autopilotArmed.value = false
                        _autopilotStandby.value = false
                        _lastExecutionBanner.value =
                            "AUTO #${newTradeTotal}/${stats.maxDailyTrades} [${activeStrategy.name}] -> ${evaluation.finalSignal} $${"%.2f".format(stake)} @ ${"%.4f".format(tick.price)} • DAILY CAP REACHED"
                    } else if (prefs.hourlySniperMode) {
                        _lastExecutionBanner.value =
                            "CHRONO SNIPER (1/1 HR): ${evaluation.finalSignal} $${"%.2f".format(stake)} (${durationSec}s) @ ${"%.4f".format(tick.price)} • Next slot in ${stats.nextHourlyResetMinutes}m"
                    } else {
                        _lastExecutionBanner.value =
                            "AUTO #${newTradeTotal}/${stats.maxDailyTrades} [${activeStrategy.name}] -> ${evaluation.finalSignal} $${"%.2f".format(stake)} (${durationSec}s) @ ${"%.4f".format(tick.price)} • 1-Trade Lock Active"
                    }
                    pushForegroundNotification(force = true)
                }
            }
        }

        if (nowMs - lastForegroundNotificationUpdateMs >= 1000L) {
            pushForegroundNotification(force = false)
        }
    }

    private fun pushForegroundNotification(force: Boolean) {
        val ctx = appContext ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastForegroundNotificationUpdateMs < 1000L) return
        lastForegroundNotificationUpdateMs = now
        val snap = _telemetry.value
        val prefs = sessionPrefs.value
        val eval = snap.activeEvaluations.firstOrNull()
        val activeStrategy = currentLoadedStrategy()
        val maxDaily = if (prefs.hourlySniperMode) maxOf(24, activeStrategy.maxDailyTrades) else activeStrategy.maxDailyTrades
        BotExecutionForegroundService.syncForegroundExecution(
            context = ctx,
            snapshot = ForegroundExecutionSnapshot(
                botRunning = _botRunning.value,
                autopilotArmed = _autopilotArmed.value,
                autopilotStandby = _autopilotStandby.value,
                hourlySniperMode = prefs.hourlySniperMode,
                hourlyTradesThisHour = snap.hourlyTradesThisHour,
                nextHourlyResetMinutes = snap.nextHourlyResetMinutes,
                singleTradeLocked = snap.singleTradeLocked,
                hvStateLabel = snap.hvWindow.state.label,
                hvScore = snap.hvWindow.score,
                pair = snap.pair,
                latestPrice = snap.latestPrice,
                lockedStakeUsd = snap.nextEffectiveStake,
                dailyTradesCount = snap.dailyTradesCount,
                maxDailyTrades = maxDaily,
                consecutiveLosses = snap.consecutiveLosses,
                dailyNetPnl = snap.dailyNetPnl,
                hardStopReason = snap.hardStopReason,
                lastSignalNote = eval?.triggerReason ?: "${activeStrategy.name} Loaded"
            )
        )
    }

    override fun onCleared() {
        BotExecutionForegroundService.onPauseBotRequested = null
        BotExecutionForegroundService.onResumeBotRequested = null
        BotExecutionForegroundService.onToggleHourlySniperRequested = null
        backgroundEngineScope.cancel()
        super.onCleared()
    }

    class Factory(
        private val repository: OlympQuantRepository,
        private val wsClient: OlympTradeWsClient,
        private val appContext: Context
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return TerminalViewModel(repository, wsClient, appContext.applicationContext) as T
        }
    }
}
