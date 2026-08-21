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
 *    than retrying a thing that will never work. Only a server that *answered*
 *    counts as refusing: an IOException with no response is the network being
 *    absent, and downgrading the process to long-polling for the rest of the day
 *    over one dead zone is a self-inflicted outage.
 *
 * 5. **A state machine that stops transitioning.** Every failure above schedules
 *    its own retry, so the ways this ends up permanently disconnected are the
 *    ways it stops moving at all: a handshake that stalls after the upgrade (no
 *    callback ever fires — a stalled socket is indistinguishable from an idle
 *    healthy one), and a dying transport whose late callback tears down the
 *    replacement that has already taken its place. Hence [connectDeadlineJob],
 *    the [generation] tag on every transport, and a [startSupervisor] tick that
 *    re-dials whenever we are neither live nor legitimately waiting.
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
    private var connectDeadlineJob: Job? = null
    private var supervisorJob: Job? = null
    private var attempt = 0

    /**
     * Bumped for every dial. A transport carries the generation it was born in
     * and its callbacks are dropped once that number moves on — closing a socket
     * fires `onFailure` *after* the replacement has started, and without this
     * the corpse tears down its own successor. Volatile because it is written
     * under the monitor and read from OkHttp's callback threads.
     */
    @Volatile
    private var generation = 0

    /** The session this attempt was dialled with, so a refreshed one re-dials. */
    @Volatile
    private var dialledSid: String? = null

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
        // The supervisor is started even when this call goes no further: a
        // process that came up logged-out, or one whose dial is already in
        // flight, still needs somebody watching that it ends up connected.
        startSupervisor()
        if (state == State.Connecting || state == State.Live) return

        val sid = session.sid
        if (sid.isNullOrBlank()) {
            // No session yet. Go Idle rather than Backoff: ChatLifecycle calls
            // reconnectNow() after login, and the supervisor dials the moment a
            // session appears, so there is nothing to poll for.
            Log.i(TAG, "no session; staying idle")
            state = State.Idle
            lastError = "Not signed in"
            return
        }

        val gen = ++generation
        dialledSid = sid
        state = State.Connecting
        reconnectJob?.cancel()
        reconnectJob = null

        transport = if (preferPolling) PollingTransport(sid, gen) else WsTransport(sid, gen)
        transport?.start()
        armConnectDeadline(gen)
    }

    @Synchronized
    fun disconnect() {
        generation++            // orphan anything still in flight
        supervisorJob?.cancel(); supervisorJob = null
        reconnectJob?.cancel(); reconnectJob = null
        watchdogJob?.cancel(); watchdogJob = null
        connectDeadlineJob?.cancel(); connectDeadlineJob = null
        transport?.close()
        transport = null
        state = State.Idle
    }

    /**
     * Short-circuit a long backoff — used on network-available, on resume, and
     * whenever the session changes.
     *
     * Re-dials even from [State.Connecting], which the previous version skipped.
     * That skip was the bug: an attempt started before the phone had a network
     * is exactly the one worth abandoning, and a handshake that has stalled
     * looks identical to one still in progress.
     */
    @Synchronized
    fun reconnectNow() {
        attempt = 0
        if (state == State.Live && session.sid == dialledSid) return
        forceReconnect()
    }

    /** Tear down whatever is there — live, stalled or backing off — and re-dial. */
    @Synchronized
    private fun forceReconnect() {
        generation++
        reconnectJob?.cancel(); reconnectJob = null
        watchdogJob?.cancel(); watchdogJob = null
        connectDeadlineJob?.cancel(); connectDeadlineJob = null
        transport?.close(); transport = null
        val wasLive = state == State.Live
        state = State.Idle
        if (wasLive) scope.launch { _events.emit(Event.Disconnected) }
        connect()
    }

    /**
     * A dial that never resolves either way.
     *
     * The upgrade can succeed and the Engine.IO open packet never arrive — a
     * loaded socketio worker, a proxy holding the stream, a namespace ack lost
     * on the way back. No OkHttp callback fires for that, the liveness watchdog
     * has not started (it only runs once live), and the old `reconnectNow()`
     * refused to touch a socket in Connecting. So it sat there, for as long as
     * the process lived, believing it was about to connect.
     */
    private fun armConnectDeadline(gen: Int) {
        connectDeadlineJob?.cancel()
        connectDeadlineJob = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (generation == gen && state == State.Connecting) {
                fail(gen, "handshake did not complete in ${CONNECT_TIMEOUT_MS}ms", upgradeFailed = false)
            }
        }
    }

    /**
     * Last-resort liveness for the connection *machine*, not the connection.
     *
     * Everything above schedules its own retry, so what is left is the machine
     * stopping: a job cancelled with a scope, a Fatal raised against a session
     * that has since been replaced, a callback that never comes. A ten-second
     * tick that re-dials whenever we are neither live nor legitimately waiting
     * costs nothing measurable and removes the entire class of "it just never
     * connected until I killed the app".
     */
    private fun startSupervisor() {
        if (supervisorJob?.isActive == true) return
        supervisorJob = scope.launch {
            while (isActive) {
                delay(SUPERVISOR_TICK_MS)
                when (
                    supervisorAction(
                        state = state,
                        sid = session.sid,
                        dialledSid = dialledSid,
                        reconnectPending = reconnectJob?.isActive == true,
                    )
                ) {
                    SocketAction.Redial -> reconnectNow()
                    SocketAction.Dial -> connect()
                    SocketAction.Wait, SocketAction.Idle -> Unit
                }
            }
        }
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
    private fun onFrame(gen: Int, text: String) {
        // A frame from a transport we have already replaced is history: acting
        // on it would mark a dead connection live, or pong down a closed socket.
        if (gen != generation) return
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
        connectDeadlineJob?.cancel(); connectDeadlineJob = null
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
        connectDeadlineJob?.cancel(); connectDeadlineJob = null
        transport?.close(); transport = null
        scope.launch { _events.emit(Event.Fatal(reason)) }
        // Fatal used to be terminal, and a phone left running past its session
        // expiry stayed dead until somebody backgrounded and reopened the app.
        // Retrying an auth rejection every second is pointless, so it gets its
        // own floor rather than the normal backoff — and the supervisor re-dials
        // the instant the sid actually changes.
        scheduleReconnect(floor = FATAL_RETRY_MS)
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
    @Synchronized
    private fun fail(gen: Int, reason: String, upgradeFailed: Boolean) {
        // The corpse of a connection we have already replaced. Letting it run
        // would close the live transport and schedule a second reconnect on top
        // of the one already in flight — the socket flaps instead of settling.
        if (gen != generation) {
            Log.d(TAG, "ignoring failure from stale gen $gen: $reason")
            return
        }
        if (state == State.Fatal) return
        Log.w(TAG, "transport failed (${transport?.label}): $reason")
        lastError = reason
        // A refused upgrade is not transient — stop retrying it and use polling.
        if (upgradeFailed && !preferPolling) {
            preferPolling = true
            Log.i(TAG, "websocket upgrade refused; falling back to long-polling")
        }
        watchdogJob?.cancel()
        connectDeadlineJob?.cancel(); connectDeadlineJob = null
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
                    fail(generation, "no frames for ${silent}ms — assuming dead", upgradeFailed = false)
                    return@launch
                }
            }
        }
    }

    private fun scheduleReconnect(floor: Long = 0L) {
        if (reconnectJob?.isActive == true) return
        val backoff = maxOf(floor, minOf(BASE_BACKOFF_MS shl attempt.coerceAtMost(6), MAX_BACKOFF_MS))
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
    private inner class WsTransport(
        private val sid: String,
        private val gen: Int,
    ) : WebSocketListener(), Transport {
        override val label = "websocket"
        private var socket: WebSocket? = null
        private var everOpened = false

        /** Set before we close, so our own teardown does not read as a failure. */
        @Volatile
        private var stopped = false

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
            stopped = true
            runCatching { socket?.close(NORMAL_CLOSURE, "client stopped") }
            socket = null
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            // Not "connected" — the upgrade succeeding says nothing about whether
            // Frappe accepted us. Only the namespace ack does.
            everOpened = true
            Log.d(TAG, "ws upgraded (${response.code})")
        }

        override fun onMessage(webSocket: WebSocket, text: String) = onFrame(gen, text)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (stopped) return
            val refused = isUpgradeRefusal(everOpened, response?.code)
            fail(gen, t.message ?: "websocket failure", upgradeFailed = refused)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // The server going away on its own — a socketio restart, an nginx
            // reload, a session revoked. Without this we stay nominally live
            // until the watchdog notices, 45 seconds of silently missing
            // messages for something the server just told us about.
            if (stopped) return
            fail(gen, "server closing $code $reason", upgradeFailed = false)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (stopped) return
            fail(gen, "closed $code $reason", upgradeFailed = false)
        }
    }

    /**
     * Fallback transport: Engine.IO HTTP long-polling.
     *
     * Plain HTTPS request/response, so it survives proxies and carriers that
     * refuse `Upgrade: websocket`. Slower and chattier than a socket, which is
     * exactly why it is the fallback and not the default.
     */
    private inner class PollingTransport(
        private val sid: String,
        private val gen: Int,
    ) : Transport {
        override val label = "polling"
        private var engineSid: String? = null
        private var loop: Job? = null

        @Volatile
        private var stopped = false

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
                    open.split(SEPARATOR).forEach { if (it.isNotBlank()) onFrame(gen, it) }

                    var empties = 0
                    while (isActive) {
                        val body = get(engineSid)
                        if (body.isNullOrBlank()) {
                            // A `continue` here span the CPU flat out against a
                            // server answering 200 with nothing. Give it a beat,
                            // and give up rather than poll a dead endpoint forever.
                            if (++empties >= MAX_EMPTY_POLLS) error("polling returned nothing")
                            delay(EMPTY_POLL_PAUSE_MS)
                            continue
                        }
                        empties = 0
                        // Handled inline, not dispatched: launching a coroutine
                        // per packet loses ordering, and message order is the one
                        // thing a chat may not get wrong.
                        body.split(SEPARATOR).forEach { if (it.isNotBlank()) onFrame(gen, it) }
                    }
                }.onFailure {
                    if (isActive && !stopped) {
                        fail(gen, it.message ?: "polling failed", upgradeFailed = false)
                    }
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
            stopped = true
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

        /** How long a dial may sit between "opened" and "accepted" before we retry. */
        const val CONNECT_TIMEOUT_MS = 15_000L

        /** How often the supervisor checks that we are connected or on our way. */
        const val SUPERVISOR_TICK_MS = 10_000L

        /** Auth rejections retry, but slowly — a wrong session stays wrong. */
        const val FATAL_RETRY_MS = 60_000L

        const val EMPTY_POLL_PAUSE_MS = 1_000L
        const val MAX_EMPTY_POLLS = 5

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

/** What a supervisor tick should do. Separated out so the rule can be tested. */
internal enum class SocketAction { Idle, Wait, Redial, Dial }

/**
 * Whether a tick should dial, wait, or leave it alone.
 *
 * Order is the whole content of this function. A changed session comes first
 * because it outranks every other state, including [FrappeSocket.State.Fatal]:
 * an auth rejection raised against a session that has since been replaced is
 * stale, and leaving it standing is how a phone stays disconnected for hours
 * after a perfectly good re-login.
 */
internal fun supervisorAction(
    state: FrappeSocket.State,
    sid: String?,
    dialledSid: String?,
    reconnectPending: Boolean,
): SocketAction = when {
    sid.isNullOrBlank() -> SocketAction.Idle              // logged out: nothing to dial
    sid != dialledSid -> SocketAction.Redial
    state == FrappeSocket.State.Live -> SocketAction.Idle
    state == FrappeSocket.State.Connecting -> SocketAction.Wait   // the deadline owns this
    reconnectPending -> SocketAction.Wait                          // the backoff owns this
    else -> SocketAction.Dial
}

/**
 * Whether a websocket failure means the upgrade itself was refused.
 *
 * Only a server that *answered* can refuse. A bare IOException with no response
 * is the network being absent — a lift, a tunnel, a handover — and reading that
 * as a refusal pinned the whole process to long-polling for its remaining
 * lifetime over one dead zone.
 */
internal fun isUpgradeRefusal(everOpened: Boolean, responseCode: Int?): Boolean =
    !everOpened && responseCode != null && responseCode != 101
