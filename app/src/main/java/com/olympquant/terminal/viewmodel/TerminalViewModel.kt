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
import com.olympquant.terminal.domain.MarketTelemetryState
import com.olympquant.terminal.domain.SignalDirection
import com.olympquant.terminal.domain.StrategyConfig
import com.olympquant.terminal.domain.TerminalDensityMode
import com.olympquant.terminal.domain.Tick
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    private val tickHistory = ArrayDeque<Tick>(305)
    private val activeOpenTrades = CopyOnWriteArrayList<ActiveOpenTrade>()
    private var lastTradeExecutionTimeMs: Long = 0L
    private var lastForegroundNotificationUpdateMs: Long = 0L

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
            _lastExecutionBanner.value = "Auto-Bot paused from background notification."
            pushForegroundNotification(force = true)
        }

        backgroundEngineScope.launch(Dispatchers.IO) {
            repository.ensureSeeded()
            val initialPrefs = runCatching { repository.sessionFlow.first() }.getOrNull()
            if (initialPrefs != null && initialPrefs.autoReconnectSession && wsClient.connectionStatus.value == WsConnectionStatus.DISCONNECTED) {
                if (initialPrefs.accessToken.isNotBlank() && initialPrefs.lastSessionMode == "AUTHENTICATED") {
                    wsClient.connect(initialPrefs.wsUri, initialPrefs.accessToken, initialPrefs.selectedPair)
                    _lastExecutionBanner.value = "Persistent Session Restored (${initialPrefs.selectedPair})"
                } else if (initialPrefs.lastSessionMode == "SYNTHETIC_FEED") {
                    wsClient.startLiveImpulseTestFeed(initialPrefs.selectedPair)
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
            wsClient.orderSettledFlow.collectLatest { settled ->
                activeOpenTrades.removeAll {
                    it.brokerTradeId == settled.brokerTradeId ||
                        (it.pair == settled.pair && System.currentTimeMillis() >= it.expiresAtMs - 1000L)
                }
                repository.settleTrade(
                    brokerTradeId = settled.brokerTradeId,
                    pair = settled.pair,
                    exitPrice = settled.closePrice,
                    winStatus = settled.outcome,
                    profitLoss = settled.profitLoss
                )
                val updatedRisk = computeHardLockedDailyStats(sessionPrefs.value)
                if (updatedRisk.hardStopReason != null && _botRunning.value) {
                    _botRunning.value = false
                    _lastExecutionBanner.value = "HARD STOP TRIGGERED: ${updatedRisk.hardStopReason}"
                } else {
                    _lastExecutionBanner.value =
                        "Trade #${settled.brokerTradeId} settled: ${settled.outcome} (${"%+.2f".format(settled.profitLoss)})"
                }
                pushForegroundNotification(force = true)
            }
        }
        backgroundEngineScope.launch {
            wsClient.tickFlow.collect { tick ->
                processIncomingTick(tick)
            }
        }
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
        if (!_botRunning.value) {
            val stats = computeHardLockedDailyStats(sessionPrefs.value)
            if (stats.hardStopReason != null) {
                _lastExecutionBanner.value = "LOCKED HARD STOP: ${stats.hardStopReason}"
                return
            }
            _botRunning.value = true
            _lastExecutionBanner.value =
                "ARMED: BallisticApex5s • Stake $${"%.2f".format(stats.lockedStakeUsd)} (5% Bal) • Trades ${stats.dailyTradesCount}/10"
        } else {
            _botRunning.value = false
            _lastExecutionBanner.value = "Auto-Bot placed on Standby."
        }
        pushForegroundNotification(force = true)
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

    fun clearTradeJournal() {
        viewModelScope.launch {
            repository.clearJournal()
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

    private data class HardLockedDailyStats(
        val dailyNetPnl: Double,
        val dailyTradesCount: Int,
        val consecutiveLosses: Int,
        val lockedStakeUsd: Double,
        val hardStopReason: String?
    )

    private fun startOfCurrentDayMs(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun computeHardLockedDailyStats(prefs: SessionPreferences): HardLockedDailyStats {
        val dayStartMs = maxOf(startOfCurrentDayMs(), prefs.dailyPnlResetAtMs)
        val tradesToday = tradeJournal.value.filter { it.timestampMs >= dayStartMs }
        val dailyTradesCount = tradesToday.size
        val settledToday = tradesToday.filter { it.outcome == "WIN" || it.outcome == "LOSS" }
        val dailyNetPnl = settledToday.sumOf { it.profitLoss }.finiteOr(0.0)

        var consecutiveLosses = 0
        for (trade in settledToday) {
            if (trade.outcome == "LOSS") consecutiveLosses++ else break
        }

        val activeBalance = if (prefs.accountGroup == "real") prefs.realBalance else prefs.demoBalance
        val lockedStakeUsd = HardLockedMandate.computeLockedStake(activeBalance)

        val hardStopReason = when {
            consecutiveLosses >= HardLockedMandate.MAX_CONSECUTIVE_LOSSES ->
                "3/3 Consecutive Losses Reached (Hard Stop)"
            dailyTradesCount >= HardLockedMandate.MAX_TRADES_PER_DAY ->
                "10/10 Daily Trade Cap Reached (Hard Stop)"
            else -> null
        }

        return HardLockedDailyStats(
            dailyNetPnl = dailyNetPnl,
            dailyTradesCount = dailyTradesCount,
            consecutiveLosses = consecutiveLosses,
            lockedStakeUsd = lockedStakeUsd,
            hardStopReason = hardStopReason
        )
    }

    fun executeManualOrder(direction: SignalDirection) {
        val prefs = sessionPrefs.value
        val stats = computeHardLockedDailyStats(prefs)
        if (stats.hardStopReason != null) {
            _lastExecutionBanner.value = "BLOCKED BY HARD STOP: ${stats.hardStopReason}"
            return
        }

        val stake = stats.lockedStakeUsd
        val duration = HardLockedMandate.LOCKED_DURATION_SEC
        val accountId = if (prefs.accountGroup == "real") prefs.realAccountId else prefs.demoAccountId
        val snap = _telemetry.value
        val now = System.currentTimeMillis()
        val tradeId = now % 1_000_000L

        val sent = wsClient.placeOrder(
            pair = prefs.selectedPair,
            amount = stake,
            direction = direction,
            durationSec = duration,
            accountId = accountId,
            accountGroup = prefs.accountGroup
        )
        if (sent && snap.latestPrice > 0.0) {
            activeOpenTrades.add(
                ActiveOpenTrade(
                    brokerTradeId = tradeId,
                    strategyName = HardLockedMandate.STRATEGY_NAME,
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
        backgroundEngineScope.launch(Dispatchers.IO) {
            repository.recordTradeEntry(
                TradeJournalEntity(
                    brokerTradeId = tradeId,
                    timestampMs = now,
                    strategyName = HardLockedMandate.STRATEGY_NAME,
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
            val newCount = stats.dailyTradesCount + 1
            if (newCount >= HardLockedMandate.MAX_TRADES_PER_DAY) {
                _botRunning.value = false
            }
            _lastExecutionBanner.value = if (sent) {
                "Executed ${direction.name} $${"%.2f".format(stake)} (5% Bal • Trade $newCount/10) on ${prefs.selectedPair}"
            } else {
                "Logged ${direction.name} $${"%.2f".format(stake)} (Trade $newCount/10 — Connect WS or start stream)"
            }
            pushForegroundNotification(force = true)
        }
    }

    private fun resetEnginesForNewPair(pair: String) {
        tickHistory.clear()
        activeOpenTrades.clear()
        candleEngine.reset()
        structureEngine.reset()
        levelEngine.reset()
        trendAgeEngine.reset()
        regimeEngine.reset()
        _telemetry.value = MarketTelemetryState(pair = pair)
    }

    private suspend fun processIncomingTick(tick: Tick) {
        val nowMs = System.currentTimeMillis()
        activeOpenTrades.removeAll { nowMs > it.expiresAtMs + 1500L }

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

        // 4. Update Mathematical Indicators (ZLEMA 10/30, 50-Tick Fibonacci, Microstructure & Impulse Radar)
        val ind = indicatorEngine.computeIndicators(
            ticks = tickList,
            candles = candles,
            swings = swings,
            psychLevels = gridLevels
        )
        val trendAgeSec = trendAgeEngine.update(ind.trend, tick.timeMs)

        val prefs = sessionPrefs.value
        val orderFlowImbalance = wsClient.orderFlowImbalance.value
        val tickIntensityRatio = wsClient.tickIntensityRatio.value
        val assetPayoutPct = wsClient.assetPayoutPercent.value
        val assetLocked = wsClient.assetLocked.value
        val bullishSentimentPct = wsClient.bullishSentimentPercent.value

        // 5. Evaluate Context Enhancer & Base Confidence
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
            minPayoutPercent = HardLockedMandate.MIN_PAYOUT_PERCENT,
            assetLocked = assetLocked,
            bullishSentimentPercent = bullishSentimentPct
        )

        // 6. Run 8-State Gaussian HMM Regime Inference
        val hmmOutput = regimeEngine.processTicks(tickList)

        val stats = computeHardLockedDailyStats(prefs)

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
            activeOpenTrades = activeOpenTrades.toList()
        )

        val latency = wsLatencyMs.value
        val lockedStrategy = HardLockedMandate.lockedStrategyConfig()

        // 7. Evaluate the single hard-locked Hawkes-Laminar Ballistic Impulse strategy
        val evaluation = modularStrategyEngine.evaluateStrategy(
            strategy = lockedStrategy,
            telemetry = preliminaryState,
            wsLatencyMs = latency,
            minPayoutPercent = HardLockedMandate.MIN_PAYOUT_PERCENT
        )

        val updatedState = preliminaryState.copy(activeEvaluations = listOf(evaluation))
        _telemetry.value = updatedState

        // 8. Automated Execution Gate with Hard-Locked 10 Trades/Day & 3 Consecutive Losses Stop
        if (_botRunning.value && !wsClient.pendingOrderCheck) {
            if (stats.hardStopReason != null) {
                _botRunning.value = false
                _lastExecutionBanner.value = "HARD STOP ENGAGED: ${stats.hardStopReason}"
                pushForegroundNotification(force = true)
                return
            }

            val now = System.currentTimeMillis()
            if (evaluation.readyToExecute && now - lastTradeExecutionTimeMs >= HardLockedMandate.LOCKED_COOLDOWN_MS) {
                lastTradeExecutionTimeMs = now
                val stake = stats.lockedStakeUsd
                val durationSec = HardLockedMandate.LOCKED_DURATION_SEC
                val accountId = if (prefs.accountGroup == "real") prefs.realAccountId else prefs.demoAccountId
                val tradeId = now % 1_000_000L

                val dispatched = wsClient.placeOrder(
                    pair = tick.pair,
                    amount = stake,
                    direction = evaluation.finalSignal,
                    durationSec = durationSec,
                    accountId = accountId,
                    accountGroup = prefs.accountGroup
                )
                if (dispatched) {
                    activeOpenTrades.add(
                        ActiveOpenTrade(
                            brokerTradeId = tradeId,
                            strategyName = lockedStrategy.name,
                            pair = tick.pair,
                            direction = evaluation.finalSignal,
                            stake = stake,
                            entryPrice = tick.price,
                            openedAtMs = now,
                            expiresAtMs = now + durationSec * 1000L,
                            payoutPercent = assetPayoutPct
                        )
                    )
                    repository.recordTradeEntry(
                        TradeJournalEntity(
                            brokerTradeId = tradeId,
                            timestampMs = now,
                            strategyName = lockedStrategy.name,
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
                    val newTradeTotal = stats.dailyTradesCount + 1
                    if (newTradeTotal >= HardLockedMandate.MAX_TRADES_PER_DAY) {
                        _botRunning.value = false
                        _lastExecutionBanner.value =
                            "AUTO #${newTradeTotal}/10 -> ${evaluation.finalSignal} $${"%.2f".format(stake)} @ ${"%.4f".format(tick.price)} • 10/10 DAILY CAP REACHED"
                    } else {
                        _lastExecutionBanner.value =
                            "AUTO #${newTradeTotal}/10 -> ${evaluation.finalSignal} $${"%.2f".format(stake)} (5% Bal) @ ${"%.4f".format(tick.price)}"
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
        val eval = snap.activeEvaluations.firstOrNull()
        BotExecutionForegroundService.syncForegroundExecution(
            context = ctx,
            snapshot = ForegroundExecutionSnapshot(
                botRunning = _botRunning.value,
                pair = snap.pair,
                latestPrice = snap.latestPrice,
                lockedStakeUsd = snap.nextEffectiveStake,
                dailyTradesCount = snap.dailyTradesCount,
                consecutiveLosses = snap.consecutiveLosses,
                dailyNetPnl = snap.dailyNetPnl,
                hardStopReason = snap.hardStopReason,
                lastSignalNote = eval?.triggerReason ?: "BallisticApex5s Active"
            )
        )
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
