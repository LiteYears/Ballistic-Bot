package com.olympquant.terminal.network

import com.olympquant.terminal.domain.Candle
import com.olympquant.terminal.domain.SignalDirection
import com.olympquant.terminal.domain.Tick
import com.olympquant.terminal.domain.finiteOr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

enum class WsConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    AUTHENTICATED,
    SYNTHETIC_FEED,
    RECONNECTING,
    ERROR
}

data class OrderAcceptedEvent(
    val brokerTradeId: Long,
    val pair: String,
    val direction: String,
    val amount: Double,
    val openPrice: Double,
    val timestampMs: Long
)

data class OrderSettledEvent(
    val brokerTradeId: Long,
    val pair: String,
    val closePrice: Double,
    val outcome: String, // WIN, LOSS, TIE
    val profitLoss: Double
)

data class AccountBalanceUpdate(
    val demoAccountId: Long?,
    val demoBalance: Double?,
    val realAccountId: Long?,
    val realBalance: Double?
)

/**
 * Custom SocketFactory enforcing TCP_NODELAY (disabling Nagle's algorithm) and SO_KEEPALIVE
 * for sub-millisecond dispatch of Olymp Trade Event 23 order frames.
 */
private class TcpNoDelaySocketFactory : SocketFactory() {
    private val delegate = getDefault()

    private fun configure(socket: Socket): Socket {
        runCatching {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.trafficClass = 0x10 // IPTOS_LOWDELAY
        }
        return socket
    }

    override fun createSocket(): Socket = configure(delegate.createSocket())
    override fun createSocket(host: String?, port: Int): Socket = configure(delegate.createSocket(host, port))
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        configure(delegate.createSocket(host, port, localHost, localPort))
    override fun createSocket(host: InetAddress?, port: Int): Socket = configure(delegate.createSocket(host, port))
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        configure(delegate.createSocket(address, port, localAddress, localPort))
}

/**
 * Low-latency Olymp Trade WebSocket Client implementing:
 * - TCP_NODELAY low-latency socket tuning
 * - Single-threaded ordered frame parsing & deduplicated tick dispatch
 * - Stale socket callback guards to prevent duplicate listeners & race conditions
 * - Exponential-backoff reconnection with jitter & full subscription/snapshot restoration
 * - Heartbeat ping-pong watchdog & stale-data detection
 * - Event 98 startup subscriptions & Event 90 keep-alive RTT tracking
 * - Event 1068 / 1043 / 55 Demo & Real account discovery and live balance updates
 * - Event 182 / 183 / 80 / 700 / 75 live asset profitability %, trading lock status & schedule
 * - Event 73 live trader sentiment (bullish vs bearish order ratio)
 * - Event 10 / 1003 historical 5s candles & Event 2 / 280 / 282 / 11 live server-side 5s OHLC frames
 * - Event 1 sub-second floating-point tick timestamps + live Order-Flow Imbalance & Tick Arrival Rate
 * - Event 23 pre-formatted order execution template + Event 21/22/26 trade settlement
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OlympTradeWsClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Dedicated single-threaded dispatcher guarantees strict arrival order for WebSocket frames & ticks
    private val frameDispatchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val json = Json { ignoreUnknownKeys = true }

    private val okHttpClient = OkHttpClient.Builder()
        .socketFactory(TcpNoDelaySocketFactory())
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var webSocket: WebSocket? = null
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    private var syntheticFeedJob: Job? = null
    private var reconnectAttempts = 0
    @Volatile
    private var manualDisconnect = false

    private var currentUri: String = ""
    private var currentToken: String = ""
    @Volatile
    private var currentPair: String = "LATAM_X"
    @Volatile
    private var latestStreamPrice: Double = 4515.00
    private var lastTickPrice: Double = 0.0
    private var lastTickTimeMs: Long = 0L
    private var syncedTickCountOnSession: Int = 0
    private var hasSeededCandlesOnSession: Boolean = false

    @Volatile
    private var lastFrameReceivedAtMs: Long = 0L
    @Volatile
    private var lastTickReceivedAtMs: Long = 0L

    // Rolling micro-order-flow ring buffers (last 24 ticks)
    private val stepDeltasRing = DoubleArray(24)
    private val stepDtMsRing = LongArray(24) { 250L }
    private var ringWriteIdx = 0
    private var ringCount = 0

    @Volatile
    var pendingOrderCheck: Boolean = false
        private set
    private var pendingTimeoutJob: Job? = null

    private val pingTimestamps = ConcurrentHashMap<String, Long>()

    private val _connectionStatus = MutableStateFlow(WsConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<WsConnectionStatus> = _connectionStatus.asStateFlow()

    private val _statusMessage = MutableStateFlow("Ready to connect to Olymp Trade WebSocket (TCP_NODELAY enabled)")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _wsLatencyMs = MutableStateFlow(38L)
    val wsLatencyMs: StateFlow<Long> = _wsLatencyMs.asStateFlow()

    private val _tickRateTps = MutableStateFlow(0.0)
    val tickRateTps: StateFlow<Double> = _tickRateTps.asStateFlow()

    private val _isStreamSynchronized = MutableStateFlow(false)
    val isStreamSynchronized: StateFlow<Boolean> = _isStreamSynchronized.asStateFlow()

    private val _assetPayoutPercent = MutableStateFlow(85)
    val assetPayoutPercent: StateFlow<Int> = _assetPayoutPercent.asStateFlow()

    private val _assetLocked = MutableStateFlow(false)
    val assetLocked: StateFlow<Boolean> = _assetLocked.asStateFlow()

    private val _bullishSentimentPercent = MutableStateFlow(54)
    val bullishSentimentPercent: StateFlow<Int> = _bullishSentimentPercent.asStateFlow()

    private val _orderFlowImbalance = MutableStateFlow(0.0)
    val orderFlowImbalance: StateFlow<Double> = _orderFlowImbalance.asStateFlow()

    private val _tickIntensityRatio = MutableStateFlow(1.0)
    val tickIntensityRatio: StateFlow<Double> = _tickIntensityRatio.asStateFlow()

    private val _serverClockOffsetMs = MutableStateFlow(0L)
    val serverClockOffsetMs: StateFlow<Long> = _serverClockOffsetMs.asStateFlow()

    private val _tickFlow = MutableSharedFlow<Tick>(extraBufferCapacity = 256)
    val tickFlow: SharedFlow<Tick> = _tickFlow.asSharedFlow()

    private val _candlesSeedFlow = MutableSharedFlow<List<Candle>>(extraBufferCapacity = 8)
    val candlesSeedFlow: SharedFlow<List<Candle>> = _candlesSeedFlow.asSharedFlow()

    private val _liveCandleFlow = MutableSharedFlow<Candle>(extraBufferCapacity = 64)
    val liveCandleFlow: SharedFlow<Candle> = _liveCandleFlow.asSharedFlow()

    private val _balanceFlow = MutableSharedFlow<AccountBalanceUpdate>(extraBufferCapacity = 16)
    val balanceFlow: SharedFlow<AccountBalanceUpdate> = _balanceFlow.asSharedFlow()

    private val _orderAcceptedFlow = MutableSharedFlow<OrderAcceptedEvent>(extraBufferCapacity = 16)
    val orderAcceptedFlow: SharedFlow<OrderAcceptedEvent> = _orderAcceptedFlow.asSharedFlow()

    private val _orderSettledFlow = MutableSharedFlow<OrderSettledEvent>(extraBufferCapacity = 16)
    val orderSettledFlow: SharedFlow<OrderSettledEvent> = _orderSettledFlow.asSharedFlow()

    private val _rawWireLog = MutableStateFlow<List<String>>(emptyList())
    val rawWireLog: StateFlow<List<String>> = _rawWireLog.asStateFlow()

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 12_000L
        private const val WATCHDOG_CHECK_MS = 6_000L
        private const val STALE_SOCKET_TIMEOUT_MS = 28_000L
        private const val STALE_TICK_STREAM_MS = 10_000L
        private const val MAX_BACKOFF_MS = 30_000L

        internal fun computeReconnectBackoffMs(
            attempt: Int,
            jitterMs: Long = Random.nextLong(150L, 850L)
        ): Long {
            val safeAttempt = attempt.coerceAtLeast(1)
            val expShift = (safeAttempt - 1).coerceIn(0, 5)
            val baseDelay = min(MAX_BACKOFF_MS, 1000L * (1L shl expShift))
            return baseDelay + jitterMs.coerceIn(0L, 1500L)
        }
    }

    fun connect(uri: String, accessToken: String, initialPair: String) {
        stopSyntheticFeedInternal()
        if (accessToken.isBlank()) {
            _connectionStatus.value = WsConnectionStatus.ERROR
            _isStreamSynchronized.value = false
            _statusMessage.value = "Missing Olymp Trade access_token. Authenticate via WebView, paste token, or launch Live Impulse Feed."
            return
        }
        manualDisconnect = false
        reconnectJob?.cancel()
        reconnectJob = null
        pingJob?.cancel()
        pingJob = null

        currentUri = uri
        currentToken = accessToken.trim()
        currentPair = initialPair
        resetOrderFlowRing()

        // Detach and cancel any previous socket before opening a new one
        val previousSocket = webSocket
        webSocket = null
        previousSocket?.cancel()

        _connectionStatus.value = WsConnectionStatus.CONNECTING
        _statusMessage.value = "Opening TCP_NODELAY WebSocket to ${uri.substringBefore("?")}..."

        val request = Request.Builder()
            .url(uri)
            .header("Origin", "https://olymptrade.com")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
            )
            .header("Cookie", "access_token=$currentToken")
            .build()

        webSocket = okHttpClient.newWebSocket(request, SocketListener())
    }

    @Synchronized
    internal fun resetOrderFlowRing() {
        lastTickPrice = 0.0
        lastTickTimeMs = 0L
        ringWriteIdx = 0
        ringCount = 0
        syncedTickCountOnSession = 0
        hasSeededCandlesOnSession = false
        pingTimestamps.clear()
        _orderFlowImbalance.value = 0.0
        _tickIntensityRatio.value = 1.0
        _tickRateTps.value = 0.0
        _isStreamSynchronized.value = false
        _assetLocked.value = false
    }

    /**
     * Deduplicates incoming ticks, prevents out-of-order regression, updates micro-order-flow
     * and live tick-rate telemetry, and marks snapshot synchronization once warm.
     */
    @Synchronized
    internal fun acceptAndRecordTick(pair: String, price: Double, timeMs: Long): Boolean {
        if (pair != currentPair || !price.isFinite() || price <= 0.0 || timeMs <= 0L) {
            return false
        }
        // Reject exact duplicate ticks or regressive out-of-order timestamps after reconnect
        if (lastTickTimeMs > 0L) {
            if (timeMs < lastTickTimeMs) return false
            if (timeMs == lastTickTimeMs && abs(price - lastTickPrice) < 1e-12) return false
        }

        val localNow = System.currentTimeMillis()
        lastFrameReceivedAtMs = localNow
        lastTickReceivedAtMs = localNow
        _serverClockOffsetMs.value = (timeMs - localNow).coerceIn(-60_000L, 60_000L)

        if (lastTickPrice > 0.0 && lastTickTimeMs > 0L) {
            val delta = price - lastTickPrice
            val dtMs = (timeMs - lastTickTimeMs).coerceIn(15L, 5000L)
            stepDeltasRing[ringWriteIdx] = delta
            stepDtMsRing[ringWriteIdx] = dtMs
            ringWriteIdx = (ringWriteIdx + 1) % stepDeltasRing.size
            if (ringCount < stepDeltasRing.size) ringCount++

            if (ringCount >= 4) {
                var buyFlow = 0.0
                var sellFlow = 0.0
                var totalDt = 0L
                var recentDt = 0L
                val recentCount = min(6, ringCount)

                for (k in 0 until ringCount) {
                    val idx = (ringWriteIdx - 1 - k + stepDeltasRing.size) % stepDeltasRing.size
                    val d = stepDeltasRing[idx]
                    val dt = stepDtMsRing[idx]
                    val speedWeight = 250.0 / max(40L, dt).toDouble()
                    val weightedMag = abs(d) * speedWeight
                    if (d > 0.0) buyFlow += weightedMag
                    else if (d < 0.0) sellFlow += weightedMag

                    totalDt += dt
                    if (k < recentCount) recentDt += dt
                }

                val flowSum = buyFlow + sellFlow
                val imbalance = if (flowSum > 1e-12) {
                    (((buyFlow - sellFlow) / flowSum) * 100.0).finiteOr(0.0).coerceIn(-100.0, 100.0)
                } else {
                    0.0
                }
                _orderFlowImbalance.value = imbalance

                val avgBaselineDt = totalDt.toDouble() / ringCount
                val avgRecentDt = recentDt.toDouble() / recentCount
                val intensity = if (avgRecentDt > 1.0) {
                    (avgBaselineDt / avgRecentDt).finiteOr(1.0).coerceIn(0.25, 3.5)
                } else {
                    1.0
                }
                _tickIntensityRatio.value = intensity

                val liveTps = if (avgRecentDt > 1.0) {
                    (1000.0 / avgRecentDt).finiteOr(0.0).coerceIn(0.1, 30.0)
                } else {
                    0.0
                }
                _tickRateTps.value = liveTps
            }
        }

        lastTickPrice = price
        lastTickTimeMs = timeMs
        latestStreamPrice = price
        syncedTickCountOnSession++
        if (hasSeededCandlesOnSession || syncedTickCountOnSession >= 6) {
            _isStreamSynchronized.value = true
        }
        return true
    }

    /**
     * Starts a high-frequency realistic Olymp Trade tick & micro-impulse stream so operators can
     * stress-test strategies and the chart pipeline immediately on-device.
     */
    fun startLiveImpulseTestFeed(pair: String) {
        disconnect()
        manualDisconnect = false
        currentPair = pair
        resetOrderFlowRing()
        _connectionStatus.value = WsConnectionStatus.SYNTHETIC_FEED
        _statusMessage.value = "High-Frequency Micro-Impulse Stream Active ($pair @ 4–6 ticks/s)"
        appendWireLog("⚡ STARTED High-Frequency Micro-Impulse Stream for $pair")

        syntheticFeedJob = scope.launch {
            var basePrice = when {
                pair.contains("EUR") || pair.contains("GBP") || pair.contains("AUD") -> 1.0850
                pair.contains("JPY") -> 154.20
                else -> 4515.00
            }
            val tickStep = if (basePrice < 10.0) 0.00025 else 0.18
            val nowSec = System.currentTimeMillis() / 1000L

            // Seed 60 historical 5s candles so indicators & S/R zones are immediately warm
            val seedCandles = mutableListOf<Candle>()
            for (i in 60 downTo 1) {
                val cTime = ((nowSec - i * 5L) / 5L) * 5L
                val open = basePrice
                val delta = (Random.nextDouble(-1.0, 1.05)) * tickStep * 3.0
                val close = open + delta
                val high = maxOf(open, close) + Random.nextDouble(0.1, 0.8) * tickStep
                val low = minOf(open, close) - Random.nextDouble(0.1, 0.8) * tickStep
                seedCandles.add(Candle(cTime, open, high, low, close, tickCount = 15))
                basePrice = close
            }
            latestStreamPrice = basePrice
            hasSeededCandlesOnSession = true
            _candlesSeedFlow.emit(seedCandles)

            var stepCounter = 0
            var impulsePhaseDir = 1
            var impulseTicksRemaining = 0
            var noisePhaseCounter = 0

            while (isActive && _connectionStatus.value == WsConnectionStatus.SYNTHETIC_FEED) {
                stepCounter++
                if (impulseTicksRemaining <= 0) {
                    noisePhaseCounter++
                    if (noisePhaseCounter >= 24) {
                        noisePhaseCounter = 0
                        impulsePhaseDir = if (Random.nextBoolean()) 1 else -1
                        impulseTicksRemaining = Random.nextInt(34, 43)
                    }
                }

                val move = if (impulseTicksRemaining > 0) {
                    impulseTicksRemaining--
                    val rushMult = if (impulseTicksRemaining % 6 == 0) {
                        Random.nextDouble(0.35, 0.85)
                    } else {
                        Random.nextDouble(1.15, 1.95)
                    }
                    impulsePhaseDir * tickStep * rushMult
                } else {
                    val alternatingSign = if (stepCounter % 2 == 0) 1.0 else -1.0
                    (alternatingSign * 0.55 + Random.nextDouble(-0.35, 0.35)) * tickStep
                }

                basePrice = (basePrice + move).coerceAtLeast(0.0001)
                val ts = System.currentTimeMillis()
                _wsLatencyMs.value = Random.nextLong(22L, 46L)
                _bullishSentimentPercent.value = (50 + (if (move >= 0) 12 else -12) + Random.nextInt(-5, 6)).coerceIn(15, 85)
                if (acceptAndRecordTick(currentPair, basePrice, ts)) {
                    _tickFlow.emit(Tick(pair = currentPair, price = basePrice, timeMs = ts))
                }

                val sleepMs = if (impulseTicksRemaining > 0) 155L else 240L
                delay(sleepMs)
            }
        }
    }

    private fun stopSyntheticFeedInternal() {
        syntheticFeedJob?.cancel()
        syntheticFeedJob = null
    }

    fun disconnect() {
        manualDisconnect = true
        stopSyntheticFeedInternal()
        reconnectJob?.cancel()
        reconnectJob = null
        pingJob?.cancel()
        pingJob = null
        clearPendingOrderLock()
        val activeSocket = webSocket
        webSocket = null
        activeSocket?.close(1000, "Client requested disconnect")
        resetOrderFlowRing()
        _connectionStatus.value = WsConnectionStatus.DISCONNECTED
        _statusMessage.value = "Disconnected from Olymp Trade WebSocket"
    }

    fun switchAssetPair(newPair: String) {
        val oldPair = currentPair
        currentPair = newPair
        resetOrderFlowRing()
        if (_connectionStatus.value == WsConnectionStatus.SYNTHETIC_FEED) {
            startLiveImpulseTestFeed(newPair)
            return
        }
        if (newPair == oldPair && _connectionStatus.value == WsConnectionStatus.AUTHENTICATED) return
        if (_connectionStatus.value == WsConnectionStatus.AUTHENTICATED) {
            scope.launch {
                sendFormattedRequest(13, """[{"pair":"$oldPair"}]""", requiresUuid = true)
                sendFormattedRequest(281, """[{"pair":"$oldPair"}]""", requiresUuid = true)
                subscribeAssetStreams(newPair)
            }
        }
    }

    /**
     * Sub-millisecond pre-formatted string-concatenation order payload for Event 23
     * (bypasses generic JSON reflection/serialization overhead).
     * Also supports full end-to-end settlement when running in SYNTHETIC_FEED mode.
     */
    fun placeOrder(
        pair: String,
        amount: Double,
        direction: SignalDirection,
        durationSec: Int,
        accountId: Long,
        accountGroup: String,
        clientOrderId: Long = System.currentTimeMillis() % 1_000_000L
    ): Boolean {
        val status = _connectionStatus.value
        if (status != WsConnectionStatus.AUTHENTICATED && status != WsConnectionStatus.SYNTHETIC_FEED) return false
        if (pendingOrderCheck || _assetLocked.value) return false

        val dirStr = if (direction == SignalDirection.UP) "up" else "down"
        val ts = System.currentTimeMillis()
        val uuid = generateProtocolUuid()

        pendingOrderCheck = true
        pendingTimeoutJob?.cancel()
        pendingTimeoutJob = scope.launch {
            delay(8000L)
            pendingOrderCheck = false
        }

        if (status == WsConnectionStatus.SYNTHETIC_FEED) {
            val syntheticId = clientOrderId
            val entrySnapPrice = latestStreamPrice
            clearPendingOrderLock()
            appendWireLog("⚡ [IMPULSE FEED] ORDER ACCEPTED #$syntheticId ($pair ${dirStr.uppercase()} $$amount @ ${"%.4f".format(entrySnapPrice)})")
            scope.launch {
                _orderAcceptedFlow.emit(
                    OrderAcceptedEvent(
                        brokerTradeId = syntheticId,
                        pair = pair,
                        direction = dirStr.uppercase(),
                        amount = amount,
                        openPrice = entrySnapPrice,
                        timestampMs = ts
                    )
                )
                delay(durationSec * 1000L)
                val closeSnapPrice = latestStreamPrice
                val isWin = (direction == SignalDirection.UP && closeSnapPrice > entrySnapPrice) ||
                    (direction == SignalDirection.DOWN && closeSnapPrice < entrySnapPrice)
                val isTie = abs(closeSnapPrice - entrySnapPrice) < 1e-8
                val payoutRatio = _assetPayoutPercent.value / 100.0
                val (outcome, pnl) = when {
                    isTie -> "TIE" to 0.0
                    isWin -> "WIN" to (amount * payoutRatio)
                    else -> "LOSS" to -amount
                }
                appendWireLog("🏁 [IMPULSE FEED] SETTLED #$syntheticId: $outcome (${"%+.2f".format(pnl)})")
                _orderSettledFlow.emit(
                    OrderSettledEvent(
                        brokerTradeId = syntheticId,
                        pair = pair,
                        closePrice = closeSnapPrice,
                        outcome = outcome,
                        profitLoss = pnl
                    )
                )
            }
            return true
        }

        val payload =
            """[{"t":2,"e":23,"uuid":"$uuid","d":[{"amount":$amount,"dir":"$dirStr","pair":"$pair","cat":"digital","pos":0,"source":"platform","account_id":$accountId,"group":"$accountGroup","timestamp":$ts,"risk_free_id":null,"is_flex":false,"duration":$durationSec}]}]"""

        appendWireLog("📤 SENT e:23 ($dirStr $$amount ${durationSec}s on $pair)")
        val sent = webSocket?.send(payload) ?: false
        if (!sent) {
            clearPendingOrderLock()
        }
        return sent
    }

    private fun clearPendingOrderLock() {
        pendingOrderCheck = false
        pendingTimeoutJob?.cancel()
        pendingTimeoutJob = null
    }

    private suspend fun runStartupHandshake() {
        val startupSubscriptions = listOf(
            "[220]",
            "[110,700,112,140,1038,1037,1039,141,22,26,111]",
            "[1054,1076,1301,1097]",
            "[141,241]",
            "[230,231]",
            "[73,75,183]",
            "[1055]",
            "[2223,2301,55,150,152,151,126,602,601]",
            "[2076]",
            "[126]"
        )
        for (sub in startupSubscriptions) {
            sendFormattedRequest(98, sub, requiresUuid = false)
        }

        sendPing()
        delay(100L)
        sendPing()

        sendFormattedRequest(98, "[[55]]", requiresUuid = false)
        sendFormattedRequest(1068, """[{"group":"demo"}]""", requiresUuid = true)
        sendFormattedRequest(1068, """[{"group":"real"}]""", requiresUuid = true)

        subscribeAssetStreams(currentPair)
    }

    private fun subscribeAssetStreams(pair: String) {
        val toTs = System.currentTimeMillis() / 1000L
        sendFormattedRequest(95, """[{"cat":"digital","pair":"$pair"}]""", requiresUuid = true)
        sendFormattedRequest(12, """[{"pair":"$pair"}]""", requiresUuid = true)
        sendFormattedRequest(280, """[{"pair":"$pair"}]""", requiresUuid = true)
        sendFormattedRequest(10, """[{"pair":"$pair","size":5,"to":$toTs,"solid":true}]""", requiresUuid = true)
        sendFormattedRequest(282, """[{"pair":"$pair","size":5,"to":$toTs,"solid":true}]""", requiresUuid = false)
        _statusMessage.value = "Live WebSocket stream active: $pair (TCP_NODELAY 5s OHLC + Tick Order-Flow)"
    }

    private fun startPingLoop(activeSocket: WebSocket) {
        pingJob?.cancel()
        pingJob = scope.launch {
            var timeSinceLastPingMs = 0L
            while (isActive && webSocket === activeSocket && _connectionStatus.value == WsConnectionStatus.AUTHENTICATED) {
                delay(WATCHDOG_CHECK_MS)
                if (webSocket !== activeSocket || _connectionStatus.value != WsConnectionStatus.AUTHENTICATED) break

                val now = System.currentTimeMillis()
                timeSinceLastPingMs += WATCHDOG_CHECK_MS

                // Stale tick-stream detection: mark unsynchronized if no ticks arrive for > 10s
                if (lastTickReceivedAtMs > 0L && now - lastTickReceivedAtMs > STALE_TICK_STREAM_MS) {
                    _isStreamSynchronized.value = false
                    _tickRateTps.value = 0.0
                }

                // Heartbeat timeout watchdog: reconnect if socket is silent for > 28s
                if (lastFrameReceivedAtMs > 0L && now - lastFrameReceivedAtMs > STALE_SOCKET_TIMEOUT_MS) {
                    appendWireLog("⚠️ WS Heartbeat Timeout (${(now - lastFrameReceivedAtMs) / 1000}s silent) — Reconnecting...")
                    _isStreamSynchronized.value = false
                    webSocket = null
                    activeSocket.cancel()
                    scheduleSingleFlightReconnect()
                    break
                }

                if (timeSinceLastPingMs >= HEARTBEAT_INTERVAL_MS) {
                    timeSinceLastPingMs = 0L
                    sendPing()
                }
            }
        }
    }

    private fun sendPing() {
        if (pingTimestamps.size > 16) {
            pingTimestamps.clear()
        }
        val uuid = generateProtocolUuid()
        pingTimestamps[uuid] = System.currentTimeMillis()
        val msg = """[{"t":2,"e":90,"uuid":"$uuid","d":{}}]"""
        webSocket?.send(msg)
    }

    private fun sendFormattedRequest(eventCode: Int, dataJson: String, requiresUuid: Boolean): String? {
        val uuid = if (requiresUuid) generateProtocolUuid() else null
        val payload = if (uuid != null) {
            """[{"t":2,"e":$eventCode,"uuid":"$uuid","d":$dataJson}]"""
        } else {
            """[{"t":2,"e":$eventCode,"d":$dataJson}]"""
        }
        webSocket?.send(payload)
        return uuid
    }

    private fun generateProtocolUuid(): String {
        val upper = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val lower = "abcdefghijklmnopqrstuvwxyz0123456789"
        val prefix = (1..4).map { upper[Random.nextInt(upper.length)] }.joinToString("")
        val suffix = (1..4).map { lower[Random.nextInt(lower.length)] }.joinToString("")
        return "$prefix-$suffix"
    }

    private fun scheduleSingleFlightReconnect() {
        if (manualDisconnect || currentToken.isBlank()) return
        if (reconnectJob?.isActive == true) return
        pingJob?.cancel()
        pingJob = null
        _isStreamSynchronized.value = false
        _tickRateTps.value = 0.0
        reconnectAttempts++
        val backoffMs = computeReconnectBackoffMs(reconnectAttempts)
        _connectionStatus.value = WsConnectionStatus.RECONNECTING
        _statusMessage.value =
            "Connection interrupted. Exp-backoff reconnect in ${"%.1f".format(backoffMs / 1000.0)}s (attempt #$reconnectAttempts)..."
        reconnectJob = scope.launch {
            delay(backoffMs)
            if (!manualDisconnect && currentToken.isNotBlank()) {
                connect(currentUri, currentToken, currentPair)
            }
        }
    }

    private fun appendWireLog(entry: String) {
        val current = _rawWireLog.value
        val nextSize = min(80, current.size + 1)
        val next = ArrayList<String>(nextSize)
        next.add(entry)
        for (i in 0 until nextSize - 1) {
            next.add(current[i])
        }
        _rawWireLog.value = next
    }

    private inner class SocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== this@OlympTradeWsClient.webSocket) return
            val now = System.currentTimeMillis()
            lastFrameReceivedAtMs = now
            lastTickReceivedAtMs = now
            reconnectAttempts = 0
            _isStreamSynchronized.value = false
            _connectionStatus.value = WsConnectionStatus.AUTHENTICATED
            _statusMessage.value = "Connected to Olymp Trade WebSocket (TCP_NODELAY). Synchronizing snapshot..."
            appendWireLog("✅ WS OPEN (${response.code}) TCP_NODELAY=true • Restoring subscriptions")
            scope.launch {
                if (webSocket === this@OlympTradeWsClient.webSocket) {
                    runStartupHandshake()
                    startPingLoop(webSocket)
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== this@OlympTradeWsClient.webSocket) return
            lastFrameReceivedAtMs = System.currentTimeMillis()
            frameDispatchScope.launch {
                if (webSocket === this@OlympTradeWsClient.webSocket) {
                    parseAndDispatchFrame(text)
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== this@OlympTradeWsClient.webSocket) return
            clearPendingOrderLock()
            _isStreamSynchronized.value = false
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== this@OlympTradeWsClient.webSocket) return
            this@OlympTradeWsClient.webSocket = null
            clearPendingOrderLock()
            _isStreamSynchronized.value = false
            _tickRateTps.value = 0.0
            appendWireLog("🔌 WS CLOSED ($code: $reason)")
            if (!manualDisconnect) {
                scheduleSingleFlightReconnect()
            } else {
                _connectionStatus.value = WsConnectionStatus.DISCONNECTED
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== this@OlympTradeWsClient.webSocket) return
            this@OlympTradeWsClient.webSocket = null
            clearPendingOrderLock()
            _isStreamSynchronized.value = false
            _tickRateTps.value = 0.0
            val codeInfo = response?.code?.let { " (HTTP $it)" } ?: ""
            appendWireLog("❌ WS ERROR$codeInfo: ${t.localizedMessage ?: "Socket failure"}")
            _statusMessage.value = "WebSocket error$codeInfo: ${t.localizedMessage ?: "Check token or use Live Impulse Feed"}"
            if (response?.code == 401 || response?.code == 403) {
                _connectionStatus.value = WsConnectionStatus.ERROR
            } else if (!manualDisconnect) {
                scheduleSingleFlightReconnect()
            }
        }
    }

    private suspend fun parseAndDispatchFrame(rawText: String) {
        val rootElement = runCatching { json.parseToJsonElement(rawText) }.getOrNull() ?: return
        val frames = rootElement as? JsonArray ?: return

        for (item in frames) {
            val obj = item as? JsonObject ?: continue
            val eventCode = obj["e"]?.jsonPrimitive?.intOrNull
            val uuid = obj["uuid"]?.jsonPrimitive?.content

            if (uuid != null) {
                val sentAt = pingTimestamps.remove(uuid)
                if (sentAt != null) {
                    val rtt = (System.currentTimeMillis() - sentAt).coerceAtLeast(1L)
                    _wsLatencyMs.value = rtt
                }
            }

            when (eventCode) {
                1 -> handleTickEvent(obj["d"])
                2, 280, 282, 11 -> handleLiveCandleStreamEvent(obj["d"])
                90 -> {
                    if (uuid == null) {
                        webSocket?.send("""[{"t":3,"e":90,"d":{"ts":${System.currentTimeMillis()}}}]""")
                    }
                }
                1003, 10 -> handleHistoricalCandlesEvent(obj["d"])
                55, 1068, 1043 -> handleBalanceAndAccountEvent(eventCode, obj["d"])
                182, 183, 80, 700, 75 -> handleAssetProfitabilityAndLockEvent(obj["d"])
                73 -> handleSentimentEvent(obj["d"])
                23, 22, 21 -> handleOrderAcceptedOrUpdatedEvent(eventCode, obj["d"])
                26 -> handleOrderClosedEvent(obj["d"])
            }
        }
    }

    private suspend fun handleTickEvent(dataElement: JsonElement?) {
        val arr = dataElement as? JsonArray ?: return
        for (el in arr) {
            val tickObj = el as? JsonObject ?: continue
            val pair = tickObj["p"]?.jsonPrimitive?.content
                ?: tickObj["pair"]?.jsonPrimitive?.content
                ?: currentPair
            val price = tickObj["q"]?.jsonPrimitive?.doubleOrNull
                ?: tickObj["close"]?.jsonPrimitive?.doubleOrNull
                ?: tickObj["c"]?.jsonPrimitive?.doubleOrNull
                ?: tickObj["price"]?.jsonPrimitive?.doubleOrNull
                ?: continue

            // Parse sub-second floating-point Unix timestamps (e.g., 1730500123.456) accurately
            val rawTimeDouble = tickObj["t"]?.jsonPrimitive?.doubleOrNull
                ?: tickObj["time"]?.jsonPrimitive?.doubleOrNull
            val timeMs = when {
                rawTimeDouble == null -> System.currentTimeMillis()
                rawTimeDouble < 10_000_000_000.0 -> (rawTimeDouble * 1000.0).toLong()
                else -> rawTimeDouble.toLong()
            }

            if (acceptAndRecordTick(pair, price, timeMs)) {
                _tickFlow.emit(Tick(pair = pair, price = price, timeMs = timeMs))
            }
        }
    }

    private suspend fun handleLiveCandleStreamEvent(dataElement: JsonElement?) {
        val arr = dataElement as? JsonArray ?: return
        for (el in arr) {
            val cObj = el as? JsonObject ?: continue
            val pair = cObj["p"]?.jsonPrimitive?.content
                ?: cObj["pair"]?.jsonPrimitive?.content
                ?: currentPair
            if (pair != currentPair) continue

            val candle = parseSingleCandle(cObj) ?: continue
            latestStreamPrice = candle.close
            _liveCandleFlow.emit(candle)
        }
    }

    private suspend fun handleHistoricalCandlesEvent(dataElement: JsonElement?) {
        val arr = dataElement as? JsonArray ?: return
        val parsed = mutableListOf<Candle>()
        for (el in arr) {
            val cObj = el as? JsonObject ?: continue
            val nested = cObj["candles"] as? JsonArray ?: cObj["data"] as? JsonArray
            if (nested != null) {
                for (nEl in nested) {
                    parseSingleCandle(nEl as? JsonObject)?.let { parsed.add(it) }
                }
            } else {
                parseSingleCandle(cObj)?.let { parsed.add(it) }
            }
        }
        if (parsed.isNotEmpty()) {
            hasSeededCandlesOnSession = true
            _isStreamSynchronized.value = true
            appendWireLog("📥 Synchronized ${parsed.size} historical 5s candles for $currentPair")
            _candlesSeedFlow.emit(parsed)
        }
    }

    private fun parseSingleCandle(cObj: JsonObject?): Candle? {
        if (cObj == null) return null
        val rawT = cObj["t"]?.jsonPrimitive?.doubleOrNull
            ?: cObj["time"]?.jsonPrimitive?.doubleOrNull
            ?: return null
        val open = cObj["open"]?.jsonPrimitive?.doubleOrNull ?: cObj["o"]?.jsonPrimitive?.doubleOrNull ?: return null
        val high = cObj["high"]?.jsonPrimitive?.doubleOrNull ?: cObj["h"]?.jsonPrimitive?.doubleOrNull ?: open
        val low = cObj["low"]?.jsonPrimitive?.doubleOrNull ?: cObj["l"]?.jsonPrimitive?.doubleOrNull ?: open
        val close = cObj["close"]?.jsonPrimitive?.doubleOrNull ?: cObj["c"]?.jsonPrimitive?.doubleOrNull ?: open
        if (!open.isFinite() || !close.isFinite()) return null
        val rawLong = rawT.toLong()
        val timeSec = if (rawLong > 10_000_000_000L) rawLong / 1000L else rawLong
        val alignedSec = (timeSec / 5L) * 5L
        return Candle(
            timeSec = alignedSec,
            open = open,
            high = maxOf(high, open, close),
            low = minOf(low, open, close),
            close = close
        )
    }

    private suspend fun handleBalanceAndAccountEvent(eventCode: Int, dataElement: JsonElement?) {
        val arr = dataElement as? JsonArray ?: return
        var demoId: Long? = null
        var demoBal: Double? = null
        var realId: Long? = null
        var realBal: Double? = null

        for (el in arr) {
            val accObj = el as? JsonObject ?: continue
            val group = accObj["group"]?.jsonPrimitive?.content
            val accId = accObj["account_id"]?.jsonPrimitive?.longOrNull ?: accObj["id"]?.jsonPrimitive?.longOrNull
            val amount = accObj["amount"]?.jsonPrimitive?.doubleOrNull
                ?: accObj["balance"]?.jsonPrimitive?.doubleOrNull
                ?: accObj["value"]?.jsonPrimitive?.doubleOrNull
            if (group == "demo") {
                if (accId != null) demoId = accId
                if (amount != null && amount.isFinite()) demoBal = amount
            } else if (group == "real") {
                if (accId != null) realId = accId
                if (amount != null && amount.isFinite()) realBal = amount
            }
        }
        if (demoId != null || demoBal != null || realId != null || realBal != null) {
            _balanceFlow.emit(AccountBalanceUpdate(demoId, demoBal, realId, realBal))
            if (eventCode == 1068) {
                if (demoId != null) {
                    sendFormattedRequest(1043, """[{"account_id":$demoId,"group":"demo"}]""", requiresUuid = true)
                    sendFormattedRequest(182, """[{"account_id":$demoId}]""", requiresUuid = true)
                }
                if (realId != null) {
                    sendFormattedRequest(1043, """[{"account_id":$realId,"group":"real"}]""", requiresUuid = true)
                    sendFormattedRequest(182, """[{"account_id":$realId}]""", requiresUuid = true)
                }
            }
        }
    }

    private fun handleAssetProfitabilityAndLockEvent(dataElement: JsonElement?) {
        val arr = dataElement as? JsonArray ?: return
        for (el in arr) {
            val obj = el as? JsonObject ?: continue
            val pair = obj["pair"]?.jsonPrimitive?.content
                ?: obj["p"]?.jsonPrimitive?.content
                ?: obj["name"]?.jsonPrimitive?.content
            if (pair != null && pair != currentPair) continue

            val payout = obj["winperc"]?.jsonPrimitive?.intOrNull
                ?: obj["profitability"]?.jsonPrimitive?.intOrNull
                ?: obj["payout"]?.jsonPrimitive?.intOrNull
            if (payout != null && payout in 10..99) {
                _assetPayoutPercent.value = payout
            }

            val lockedField = obj["locked"]?.jsonPrimitive?.booleanOrNull
            val activeField = obj["active"]?.jsonPrimitive?.booleanOrNull
            val isSuspended = when {
                lockedField != null -> lockedField
                activeField != null -> !activeField
                else -> null
            }
            if (isSuspended != null) {
                _assetLocked.value = isSuspended
                if (isSuspended) {
                    appendWireLog("⚠️ ASSET LOCKED by broker: $currentPair")
                }
            }
        }
    }

    private fun handleSentimentEvent(dataElement: JsonElement?) {
        val arr = dataElement as? JsonArray ?: return
        for (el in arr) {
            val obj = el as? JsonObject ?: continue
            val pair = obj["pair"]?.jsonPrimitive?.content ?: obj["p"]?.jsonPrimitive?.content
            val upPct = obj["up"]?.jsonPrimitive?.intOrNull
                ?: obj["buy"]?.jsonPrimitive?.intOrNull
                ?: obj["call"]?.jsonPrimitive?.intOrNull
            if ((pair == null || pair == currentPair) && upPct != null && upPct in 1..99) {
                _bullishSentimentPercent.value = upPct
            }
        }
    }

    internal suspend fun handleOrderAcceptedOrUpdatedEvent(eventCode: Int, dataElement: JsonElement?) {
        clearPendingOrderLock()
        val arr = dataElement as? JsonArray ?: return
        for (el in arr) {
            val dealObj = el as? JsonObject ?: continue
            val status = dealObj["status"]?.jsonPrimitive?.content?.lowercase() ?: ""
            if (eventCode == 21 && (
                    status.contains("win") ||
                        status.contains("loss") ||
                        status.contains("loose") ||
                        status.contains("tie") ||
                        status.contains("standoff") ||
                        status.contains("refund")
                    )
            ) {
                handleOrderClosedEvent(JsonArray(listOf(dealObj)))
                continue
            }
            if (eventCode == 21 && status.isNotBlank() && !status.contains("open") && !status.contains("new")) {
                continue
            }

            val id = dealObj["id"]?.jsonPrimitive?.longOrNull
                ?: dealObj["deal_id"]?.jsonPrimitive?.longOrNull
                ?: 0L
            val pair = dealObj["pair"]?.jsonPrimitive?.content ?: currentPair
            val dir = dealObj["dir"]?.jsonPrimitive?.content ?: "up"
            val amount = dealObj["amount"]?.jsonPrimitive?.doubleOrNull ?: 10.0
            val openPrice = dealObj["open_rate"]?.jsonPrimitive?.doubleOrNull
                ?: dealObj["curs"]?.jsonPrimitive?.doubleOrNull
                ?: latestStreamPrice
            val winperc = dealObj["winperc"]?.jsonPrimitive?.intOrNull
            if (winperc != null && winperc in 10..99) {
                _assetPayoutPercent.value = winperc
            }
            appendWireLog("✅ ORDER ACCEPTED #$id ($pair ${dir.uppercase()} $$amount)")
            _orderAcceptedFlow.emit(
                OrderAcceptedEvent(
                    brokerTradeId = id,
                    pair = pair,
                    direction = dir.uppercase(),
                    amount = amount,
                    openPrice = openPrice,
                    timestampMs = System.currentTimeMillis()
                )
            )
        }
    }

    internal suspend fun handleOrderClosedEvent(dataElement: JsonElement?) {
        clearPendingOrderLock()
        val arr = dataElement as? JsonArray ?: return
        for (el in arr) {
            val tObj = el as? JsonObject ?: continue
            val id = tObj["id"]?.jsonPrimitive?.longOrNull
                ?: tObj["deal_id"]?.jsonPrimitive?.longOrNull
                ?: 0L
            val pair = tObj["pair"]?.jsonPrimitive?.content ?: currentPair
            val status = tObj["status"]?.jsonPrimitive?.content?.lowercase() ?: ""
            val closeRate = tObj["close_rate"]?.jsonPrimitive?.doubleOrNull
                ?: tObj["curs"]?.jsonPrimitive?.doubleOrNull
                ?: latestStreamPrice
            val amount = tObj["amount"]?.jsonPrimitive?.doubleOrNull ?: 10.0
            val profit = tObj["profit"]?.jsonPrimitive?.doubleOrNull
                ?: tObj["win_amount"]?.jsonPrimitive?.doubleOrNull
            val interimChange = tObj["interim_balance_change"]?.jsonPrimitive?.doubleOrNull

            val winperc = tObj["winperc"]?.jsonPrimitive?.intOrNull
            val payoutRatio = ((winperc ?: _assetPayoutPercent.value).coerceIn(10, 99)) / 100.0
            val (outcome, netPnl) = when {
                status.contains("win") || (profit != null && profit > amount) || (interimChange != null && interimChange > 0.0) -> {
                    val net = when {
                        interimChange != null && interimChange > 0.0 -> interimChange
                        profit != null && profit > amount -> profit - amount
                        else -> amount * payoutRatio
                    }
                    "WIN" to net
                }
                status.contains("loose") || status.contains("loss") || (interimChange != null && interimChange < 0.0) -> {
                    "LOSS" to -amount
                }
                else -> "TIE" to 0.0
            }
            appendWireLog("🏁 ORDER SETTLED #$id: $outcome (${"%+.2f".format(netPnl)})")
            _orderSettledFlow.emit(
                OrderSettledEvent(
                    brokerTradeId = id,
                    pair = pair,
                    closePrice = closeRate,
                    outcome = outcome,
                    profitLoss = netPnl
                )
            )
        }
    }
}
