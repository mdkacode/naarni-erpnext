package com.naarni.service.core.chat

import android.util.Log
import com.naarni.service.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.random.Random

/**
 * Minimal Socket.IO v4 client over a raw OkHttp WebSocket.
 *
 * Frappe's realtime layer is Socket.IO 4.7, not a plain WebSocket, but the
 * protocol is thin enough to speak directly — Engine.IO frames are
 * single-character packet types and Socket.IO adds a namespace prefix. Doing it
 * here rather than pulling in `io.socket:socket.io-client` keeps one OkHttp
 * client (so the existing SessionCookieJar supplies `sid`), keeps the API
 * coroutine-native, and avoids ~400 KB of callback-based transport.
 *
 * ## The Origin header is load-bearing
 *
 * `realtime/middlewares/authenticate.js` compares `hostname(host)` to
 * `hostname(origin)` and derives the Socket.IO namespace from it — the namespace
 * is the Frappe **site name**, e.g. `/service.naarni.com`, never the default `/`.
 * OkHttp does not send `Origin` on a WebSocket upgrade.
 *
 * Verified against a live bench on 2026-08-11: with no Origin header the HTTP
 * upgrade **succeeds** (so `onOpen` fires and the socket looks healthy) and the
 * server then replies `44/<site>,{"message":"Invalid origin"}` — a Socket.IO
 * CONNECT_ERROR. A client that ignores packet type 4 sits there believing it is
 * connected and silently receives nothing. Hence [PACKET_CONNECT_ERROR] is
 * treated as fatal rather than retried.
 */
class FrappeSocket(
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val siteHost: String = BuildConfig.SITE_HOST,
    private val socketUrl: String = BuildConfig.SOCKET_URL,
    private val originUrl: String = BuildConfig.ORIGIN_URL,
) : WebSocketListener() {

    sealed interface Event {
        data object Connected : Event
        data object Disconnected : Event
        /** Auth/namespace rejection. Reconnecting without a new session is pointless. */
        data class Fatal(val reason: String) : Event
        data class Message(val name: String, val payload: JsonObject) : Event
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val namespace = "/$siteHost"

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 256)
    val events = _events.asSharedFlow()

    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var attempt = 0

    @Volatile
    var isConnected: Boolean = false
        private set

    /** Threads we should re-join after a reconnect. */
    private val subscribedRooms = mutableSetOf<String>()

    // ------------------------------------------------------------- lifecycle

    fun connect() {
        if (socket != null) return
        reconnectJob?.cancel()
        val request = Request.Builder()
            .url(socketUrl)
            // Without this the upgrade succeeds and the server rejects us at the
            // Socket.IO layer. See the class docs.
            .header("Origin", originUrl)
            .build()
        socket = client.newWebSocket(request, this)
    }

    /** Graceful close. 1000 signals "we meant this", so onClosed will not retry. */
    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        isConnected = false
        socket?.close(NORMAL_CLOSURE, "client stopped")
        socket = null
    }

    /**
     * Join a thread's doc room for full-body messages, typing and receipts.
     * The server permission-checks this against room membership.
     */
    fun subscribeThread(room: String) {
        subscribedRooms += room
        emit("""["doc_subscribe","VM Chat Room","$room"]""")
    }

    fun unsubscribeThread(room: String) {
        subscribedRooms -= room
        emit("""["doc_unsubscribe","VM Chat Room","$room"]""")
    }

    private fun emit(argsJson: String) {
        socket?.send("$PACKET_EVENT$namespace,$argsJson")
    }

    // --------------------------------------------------------- socket events

    override fun onOpen(webSocket: WebSocket, response: Response) {
        // Deliberately NOT reporting Connected here. The HTTP upgrade succeeding
        // says nothing about whether Frappe accepted us — that only arrives with
        // the namespace CONNECT ack below.
        Log.d(TAG, "ws upgraded, awaiting engine.io open")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        when {
            // Engine.IO OPEN → immediately CONNECT to the site namespace.
            text.startsWith(PACKET_OPEN) -> webSocket.send("$PACKET_CONNECT$namespace,")

            // Engine.IO server-initiated PING. Miss the PONG and it drops us at ~45s.
            text == PACKET_PING -> webSocket.send(PACKET_PONG)

            text.startsWith("$PACKET_CONNECT$namespace,") -> onNamespaceJoined()

            text.startsWith("$PACKET_CONNECT_ERROR$namespace,") ->
                onFatal(text.removePrefix("$PACKET_CONNECT_ERROR$namespace,"))

            text.startsWith("$PACKET_EVENT$namespace,") ->
                dispatch(text.removePrefix("$PACKET_EVENT$namespace,"))
        }
    }

    private fun onNamespaceJoined() {
        isConnected = true
        // Reset backoff only here — not on TCP connect, which also succeeds
        // against a captive portal.
        attempt = 0
        Log.i(TAG, "connected to $namespace")
        subscribedRooms.forEach { subscribeThread(it) }
        scope.launch { _events.emit(Event.Connected) }
    }

    private fun onFatal(payload: String) {
        val reason = runCatching {
            json.parseToJsonElement(payload).let { (it as? JsonObject) }
                ?.get("message")?.jsonPrimitive?.content
        }.getOrNull() ?: payload
        Log.e(TAG, "socket rejected: $reason")
        isConnected = false
        socket?.close(NORMAL_CLOSURE, "rejected")
        socket = null
        scope.launch { _events.emit(Event.Fatal(reason)) }
    }

    private fun dispatch(payload: String) {
        runCatching {
            val arr = json.parseToJsonElement(payload) as? JsonArray ?: return
            val name = (arr.getOrNull(0))?.jsonPrimitive?.content ?: return
            val body = arr.getOrNull(1) as? JsonObject ?: JsonObject(emptyMap())
            scope.launch { _events.emit(Event.Message(name, body)) }
        }.onFailure { Log.w(TAG, "undecodable frame: ${payload.take(120)}") }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        Log.w(TAG, "socket failure: ${t.message}")
        socket = null
        isConnected = false
        scope.launch { _events.emit(Event.Disconnected) }
        scheduleReconnect()
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        socket = null
        isConnected = false
        scope.launch { _events.emit(Event.Disconnected) }
        if (code != NORMAL_CLOSURE) scheduleReconnect()
    }

    // ----------------------------------------------------------- reconnection

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        val backoff = minOf(1000L shl attempt.coerceAtMost(6), MAX_BACKOFF_MS)
        attempt++
        // Jitter is not cosmetic: a depot's worth of phones coming back after one
        // tower flap would otherwise reconnect in lockstep.
        val wait = backoff + Random.nextLong(JITTER_MS)
        Log.d(TAG, "reconnect in ${wait}ms (attempt $attempt)")
        reconnectJob = scope.launch {
            delay(wait)
            connect()
        }
    }

    /** Called by the connectivity callback to short-circuit a long backoff. */
    fun reconnectNow() {
        reconnectJob?.cancel()
        reconnectJob = null
        attempt = 0
        if (socket == null) connect()
    }

    private companion object {
        const val TAG = "FrappeSocket"
        const val NORMAL_CLOSURE = 1000
        const val MAX_BACKOFF_MS = 60_000L
        const val JITTER_MS = 800L

        // Engine.IO packet types.
        const val PACKET_OPEN = "0"
        const val PACKET_PING = "2"
        const val PACKET_PONG = "3"

        // Socket.IO packet types, carried inside an Engine.IO MESSAGE ("4").
        const val PACKET_CONNECT = "40"
        const val PACKET_EVENT = "42"
        const val PACKET_CONNECT_ERROR = "44"
    }
}
