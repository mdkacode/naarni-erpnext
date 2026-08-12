package com.naarni.service.core.chat

import android.util.Log
import com.naarni.service.BuildConfig
import com.naarni.service.core.auth.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Frappe realtime client — Socket.IO v4 over OkHttp, with a polling fallback.
 *
 * ## Why this is hand-rolled
 *
 * Frappe's realtime layer is Socket.IO 4.7, not a plain WebSocket. The protocol
 * is thin (Engine.IO packet types are single characters; Socket.IO adds a
 * namespace prefix), and doing it here keeps one OkHttp client — so the session
 * cookie, timeouts and TLS config are shared with the REST stack — instead of
 * dragging in a second, callback-based transport.
 *
 * ## The four things that actually break this in the field
 *
 * 1. **Origin.** `realtime/middlewares/authenticate.js` compares
 *    `hostname(host)` to `hostname(origin)` and derives the namespace from it —
 *    the namespace is the Frappe *site name*, never `/`. OkHttp sends no Origin
 *    on an upgrade. Without it the HTTP upgrade **succeeds** and the server
 *    replies `44 {"message":"Invalid origin"}`, so a naive client sits there
 *    believing it is connected and receives nothing.
 *
 * 2. **Auth.** The same middleware needs a cookie or an Authorization header. We
 *    set `Cookie: sid=…` explicitly rather than trusting the CookieJar to be
 *    consulted for an upgrade request, and refuse to even dial without a
 *    session — spinning a reconnect loop while logged out is pure battery burn.
 *
 * 3. **Half-open sockets.** Carrier NAT silently drops idle mappings; TCP still
 *    believes the connection is alive and no callback ever fires. The server
 *    pings every `pingInterval`, so [watchdog] treats "no frame for
 *    pingInterval + pingTimeout" as death and forces a reconnect. Without this
 *    the app looks connected and quietly misses everything.
 *
 * 4. **Blocked upgrades.** Some proxies and mobile carriers refuse `Upgrade:
 *    websocket`. Engine.IO's HTTP long-polling transport is plain HTTPS and gets
 *    through those, so a failed upgrade falls back to [PollingTransport] rather
 *    than retrying a thing that will never work.
 *
 * Verified against production on 2026-08-12: the upgrade returns HTTP 101 and a
 * valid Engine.IO open packet, so failures there are auth or liveness, not the
 * proxy.
 */
class FrappeSocket(
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val session: SessionManager,
    private val siteHost: String = BuildConfig.SITE_HOST,
    private val wsUrl: String = BuildConfig.SOCKET_URL,
    private val httpUrl: String = BuildConfig.SOCKET_HTTP_URL,
    private val originUrl: String = BuildConfig.ORIGIN_URL,
) {

    sealed interface Event {
        data object Connected : Event
        data object Disconnected : Event
        /** Auth/namespace rejection — reconnecting without a new session is futile. */
        data class Fatal(val reason: String) : Event
        data class Message(val name: String, val payload: JsonObject) : Event
    }

    enum class State { Idle, Connecting, Live, Backoff, Fatal }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val namespace = "/$siteHost"

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 256)
    val events = _events.asSharedFlow()

    @Volatile
    var state: State = State.Idle
        private set

    /** Last failure, for the diagnostics line in the UI. */
    @Volatile
    var lastError: String? = null
        private set

    val isConnected: Boolean get() = state == State.Live

    private var transport: Transport? = null
    private var reconnectJob: Job? = null
    private var watchdogJob: Job? = null
    private var attempt = 0

    /** Whether the previous attempt's upgrade failed, so we go straight to polling. */
    private var preferPolling = false

    private val lastFrameAt = AtomicLong(0)
    private var pingIntervalMs = 25_000L
    private var pingTimeoutMs = 20_000L

    /** Threads to re-join after any reconnect. */
    private val subscribedRooms = linkedSetOf<String>()

    // ----------------------------------------------------------------- public

    @Synchronized
    fun connect() {
        if (state == State.Connecting || state == State.Live) return

        val sid = session.sid
        if (sid.isNullOrBlank()) {
            // No session yet. Go Idle rather than Backoff: ChatLifecycle calls
            // reconnectNow() after login, so there is nothing to poll for.
            Log.i(TAG, "no session; staying idle")
            state = State.Idle
            lastError = "Not signed in"
            return
        }

        state = State.Connecting
        reconnectJob?.cancel()
        reconnectJob = null

        transport = if (preferPolling) PollingTransport(sid) else WsTransport(sid)
        transport?.start()
    }

    fun disconnect() {
        reconnectJob?.cancel(); reconnectJob = null
        watchdogJob?.cancel(); watchdogJob = null
        transport?.close()
        transport = null
        state = State.Idle
    }

    /** Short-circuit a long backoff — used on network-available and on resume. */
    fun reconnectNow() {
        reconnectJob?.cancel(); reconnectJob = null
        attempt = 0
        if (state == State.Fatal) state = State.Idle  // a new session deserves a new try
        if (state != State.Live && state != State.Connecting) connect()
    }

    fun subscribeThread(room: String) {
        subscribedRooms += room
        emit("""["doc_subscribe","VM Chat Room","$room"]""")
    }

    fun unsubscribeThread(room: String) {
        subscribedRooms -= room
        emit("""["doc_unsubscribe","VM Chat Room","$room"]""")
    }

    private fun emit(argsJson: String) {
        transport?.send("$PACKET_EVENT$namespace,$argsJson")
    }

    // ------------------------------------------------------- protocol handling

    /** One Engine.IO frame, from either transport. */
    private fun onFrame(text: String) {
        lastFrameAt.set(System.currentTimeMillis())
        when {
            text.startsWith(PACKET_OPEN) -> {
                parseHandshake(text.drop(1))
                transport?.send("$PACKET_CONNECT$namespace,")
            }

            // Server-initiated ping. Miss the pong and it drops us at pingTimeout.
            text == PACKET_PING -> transport?.send(PACKET_PONG)

            text.startsWith("$PACKET_CONNECT$namespace,") -> onLive()

            text.startsWith("$PACKET_CONNECT_ERROR$namespace,") ->
                onFatal(text.removePrefix("$PACKET_CONNECT_ERROR$namespace,"))

            text.startsWith("$PACKET_EVENT$namespace,") ->
                dispatch(text.removePrefix("$PACKET_EVENT$namespace,"))
        }
    }

    /** Adopt the server's own heartbeat numbers rather than hardcoding them. */
    private fun parseHandshake(body: String) {
        runCatching {
            val obj = json.parseToJsonElement(body) as? JsonObject ?: return
            obj["pingInterval"]?.jsonPrimitive?.content?.toLongOrNull()?.let { pingIntervalMs = it }
            obj["pingTimeout"]?.jsonPrimitive?.content?.toLongOrNull()?.let { pingTimeoutMs = it }
        }
    }

    private fun onLive() {
        state = State.Live
        lastError = null
        // Reset backoff only here. TCP connect and even the HTTP upgrade both
        // succeed against a captive portal, so neither proves we are usable.
        attempt = 0
        preferPolling = transport is PollingTransport
        Log.i(TAG, "live on $namespace via ${transport?.label}")
        subscribedRooms.forEach { subscribeThread(it) }
        startWatchdog()
        scope.launch { _events.emit(Event.Connected) }
    }

    private fun onFatal(payload: String) {
        val reason = runCatching {
            (json.parseToJsonElement(payload) as? JsonObject)
                ?.get("message")?.jsonPrimitive?.content
        }.getOrNull() ?: payload
        Log.e(TAG, "rejected: $reason")
        lastError = reason
        state = State.Fatal
        watchdogJob?.cancel()
        transport?.close(); transport = null
        scope.launch { _events.emit(Event.Fatal(reason)) }
    }

    private fun dispatch(payload: String) {
        runCatching {
            val arr = json.parseToJsonElement(payload) as? JsonArray ?: return
            val name = arr.getOrNull(0)?.jsonPrimitive?.content ?: return
            val body = arr.getOrNull(1) as? JsonObject ?: JsonObject(emptyMap())
            scope.launch { _events.emit(Event.Message(name, body)) }
        }.onFailure { Log.w(TAG, "undecodable frame: ${payload.take(120)}") }
    }

    /** Transport died for a non-auth reason. */
    private fun onTransportFailure(reason: String, upgradeFailed: Boolean) {
        if (state == State.Fatal) return
        Log.w(TAG, "transport failed (${transport?.label}): $reason")
        lastError = reason
        // A refused upgrade is not transient — stop retrying it and use polling.
        if (upgradeFailed && !preferPolling) {
            preferPolling = true
            Log.i(TAG, "websocket upgrade refused; falling back to long-polling")
        }
        watchdogJob?.cancel()
        transport?.close(); transport = null
        if (state == State.Live) scope.launch { _events.emit(Event.Disconnected) }
        state = State.Backoff
        scheduleReconnect()
    }

    // --------------------------------------------------------------- liveness

    /**
     * Half-open detection.
     *
     * The server sends a ping every `pingInterval`, so silence for longer than
     * `pingInterval + pingTimeout` means the connection is gone even though no
     * callback fired and TCP still thinks it is up. This is the common failure
     * on mobile: the app looks connected and receives nothing.
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        lastFrameAt.set(System.currentTimeMillis())
        watchdogJob = scope.launch {
            val limit = pingIntervalMs + pingTimeoutMs
            while (isActive && state == State.Live) {
                delay(WATCHDOG_TICK_MS)
                val silent = System.currentTimeMillis() - lastFrameAt.get()
                if (silent > limit) {
                    onTransportFailure("no frames for ${silent}ms — assuming dead", upgradeFailed = false)
                    return@launch
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        val backoff = minOf(BASE_BACKOFF_MS shl attempt.coerceAtMost(6), MAX_BACKOFF_MS)
        attempt++
        // Jitter: a depot's phones all reconnecting after one tower flap would
        // otherwise arrive in lockstep.
        val wait = backoff + Random.nextLong(JITTER_MS)
        Log.d(TAG, "reconnect in ${wait}ms (attempt $attempt, polling=$preferPolling)")
        reconnectJob = scope.launch {
            delay(wait)
            connect()
        }
    }

    // -------------------------------------------------------------- transports

    private interface Transport {
        val label: String
        fun start()
        fun send(packet: String)
        fun close()
    }

    /** Preferred transport: a real WebSocket. */
    private inner class WsTransport(private val sid: String) : WebSocketListener(), Transport {
        override val label = "websocket"
        private var socket: WebSocket? = null
        private var everOpened = false

        override fun start() {
            val request = Request.Builder()
                .url(wsUrl)
                .header("Origin", originUrl)
                // Explicit, rather than trusting the CookieJar to be applied to
                // an upgrade request.
                .header("Cookie", "sid=$sid")
                .build()
            socket = client.newWebSocket(request, this)
        }

        override fun send(packet: String) { socket?.send(packet) }

        override fun close() {
            runCatching { socket?.close(NORMAL_CLOSURE, "client stopped") }
            socket = null
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            // Not "connected" — the upgrade succeeding says nothing about whether
            // Frappe accepted us. Only the namespace ack does.
            everOpened = true
            Log.d(TAG, "ws upgraded (${response.code})")
        }

        override fun onMessage(webSocket: WebSocket, text: String) = onFrame(text)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // No successful upgrade => the proxy or carrier refused it.
            val refused = !everOpened && (response == null || response.code != 101)
            onTransportFailure(t.message ?: "websocket failure", upgradeFailed = refused)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (code != NORMAL_CLOSURE) {
                onTransportFailure("closed $code $reason", upgradeFailed = false)
            }
        }
    }

    /**
     * Fallback transport: Engine.IO HTTP long-polling.
     *
     * Plain HTTPS request/response, so it survives proxies and carriers that
     * refuse `Upgrade: websocket`. Slower and chattier than a socket, which is
     * exactly why it is the fallback and not the default.
     */
    private inner class PollingTransport(private val sid: String) : Transport {
        override val label = "polling"
        private var engineSid: String? = null
        private var loop: Job? = null

        /** Long-poll needs its own read timeout; built once, not per request. */
        private val poller: OkHttpClient by lazy {
            client.newBuilder()
                .readTimeout(pingIntervalMs + pingTimeoutMs + 10_000, TimeUnit.MILLISECONDS)
                .build()
        }

        override fun start() {
            loop = scope.launch(Dispatchers.IO) {
                runCatching {
                    val open = get(null) ?: error("handshake returned nothing")
                    // Order matters: the open packet triggers the namespace
                    // CONNECT, and send() needs engineSid. Extract it *first* or
                    // the CONNECT silently no-ops and we never come up.
                    engineSid = extractSid(open) ?: error("no engine sid")
                    open.split(SEPARATOR).forEach { if (it.isNotBlank()) onFrame(it) }

                    while (isActive) {
                        val body = get(engineSid) ?: continue
                        // Handled inline, not dispatched: launching a coroutine
                        // per packet loses ordering, and message order is the one
                        // thing a chat may not get wrong.
                        body.split(SEPARATOR).forEach { if (it.isNotBlank()) onFrame(it) }
                    }
                }.onFailure {
                    if (isActive) onTransportFailure(it.message ?: "polling failed", upgradeFailed = false)
                }
            }
        }

        private fun extractSid(open: String): String? =
            runCatching {
                val body = open.dropWhile { it != '{' }
                (json.parseToJsonElement(body) as? JsonObject)?.get("sid")?.jsonPrimitive?.content
            }.getOrNull()

        private fun url(engine: String?) = buildString {
            append(httpUrl.trimEnd('/'))
            append("/?EIO=4&transport=polling")
            if (engine != null) append("&sid=").append(engine)
        }

        private fun get(engine: String?): String? {
            val req = Request.Builder()
                .url(url(engine))
                .header("Origin", originUrl)
                .header("Cookie", "sid=$sid")
                .build()
            poller.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 200) error("poll HTTP ${resp.code}")
                return resp.body?.string()
            }
        }

        override fun send(packet: String) {
            val engine = engineSid ?: return
            scope.launch(Dispatchers.IO) {
                runCatching {
                    val req = Request.Builder()
                        .url(url(engine))
                        .header("Origin", originUrl)
                        .header("Cookie", "sid=$sid")
                        .post(packet.toRequestBody(TEXT))
                        .build()
                    withContext(Dispatchers.IO) { client.newCall(req).execute().close() }
                }.onFailure { Log.w(TAG, "polling send failed: ${it.message}") }
            }
        }

        override fun close() {
            loop?.cancel()
            loop = null
        }
    }

    private companion object {
        const val TAG = "FrappeSocket"
        const val NORMAL_CLOSURE = 1000
        const val BASE_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val JITTER_MS = 800L
        const val WATCHDOG_TICK_MS = 5_000L

        /** Engine.IO v4 payload separator (record separator, U+001E). */
        const val SEPARATOR = ''

        val TEXT = "text/plain; charset=UTF-8".toMediaType()

        // Engine.IO packet types.
        const val PACKET_OPEN = "0"
        const val PACKET_PING = "2"
        const val PACKET_PONG = "3"

        // Socket.IO packet types, inside an Engine.IO MESSAGE ("4").
        const val PACKET_CONNECT = "40"
        const val PACKET_EVENT = "42"
        const val PACKET_CONNECT_ERROR = "44"
    }
}
