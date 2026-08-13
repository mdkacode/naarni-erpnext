package com.naarni.service.ui.chat

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.naarni.service.App
import com.naarni.service.core.chat.ChatWork
import com.naarni.service.core.chat.FrappeSocket
import com.naarni.service.data.chat.ChatMessageEntity
import com.naarni.service.data.chat.ChatRoomEntity
import com.naarni.service.data.chat.SendStatus
import com.naarni.service.data.dto.ChatTicketDto
import com.naarni.service.data.dto.ChatUserDto
import com.naarni.service.data.repo.ChatRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** What the connection banner shows. */
enum class ConnectionState { Connecting, Live, Offline, Rejected }

/**
 * Chat screen state.
 *
 * Reads are Room Flows straight through — the ViewModel adds no cache of its
 * own, because a second copy of the truth is exactly how a chat UI starts
 * disagreeing with itself. Writes go to Room and are handed to WorkManager.
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as App).container
    private val repo: ChatRepository = container.chatRepo
    private val socket: FrappeSocket = container.chatSocket
    private val session = container.session

    val rooms = repo.observeRooms()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val unreadTotal = repo.observeUnreadTotal()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    var connection by mutableStateOf(ConnectionState.Connecting)
        private set

    /** Why the socket is unhappy, surfaced in the banner instead of guessed at. */
    var connectionDetail by mutableStateOf<String?>(null)
        private set

    /** Emits when a message lands in the open thread, so the UI can chime. */
    val incoming = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    val me: String get() = session.user.orEmpty()
    private val myName: String get() = session.fullName ?: session.user.orEmpty()

    /** How the rest of the room sees your name — what an `@` of you looks like. */
    val myDisplayName: String get() = myName

    /** The thread currently on screen, if any — drives doc-room subscription. */
    private var openRoom: String? = null

    init {
        observeSocket()
        refresh()
    }

    // ------------------------------------------------------------------ socket

    private fun observeSocket() {
        viewModelScope.launch {
            socket.events.collect { event ->
                when (event) {
                    is FrappeSocket.Event.Connected -> {
                        connection = ConnectionState.Live
                        connectionDetail = null
                        // Always resync on reconnect. The socket is a latency
                        // optimisation; this is the correctness path.
                        runCatching { repo.sync() }
                        openRoom?.let { socket.subscribeThread(it) }
                    }

                    is FrappeSocket.Event.Disconnected -> {
                        connection = ConnectionState.Offline
                        connectionDetail = socket.lastError
                    }

                    // Auth/namespace rejection. Retrying without a new session is
                    // pointless, so we surface it rather than spin.
                    is FrappeSocket.Event.Fatal -> {
                        connection = ConnectionState.Rejected
                        connectionDetail = event.reason
                        // The socket is a poor judge of whether the *session* is
                        // dead — a namespace or origin mistake looks identical to
                        // an expiry from here, and signing someone out over that
                        // would be unforgivable. So ask REST, which answers with
                        // Frappe's explicit `session_expired` flag; if the session
                        // really is gone, the interceptor signs the user out.
                        runCatching { repo.refreshRooms() }
                    }

                    is FrappeSocket.Event.Message -> {
                        repo.onRealtimeMessage(event.name, event.payload)
                        val room = event.payload["room"]?.toString()?.trim('"')
                        val author = event.payload["author"]?.toString()?.trim('"')
                        if (room != null && room == openRoom && author != me) {
                            incoming.tryEmit(Unit)
                        }
                    }
                }
            }
        }
    }

    /** Pull the room list and any missed messages. Safe to call often. */
    fun refresh() {
        viewModelScope.launch {
            runCatching { repo.refreshRooms() }
            runCatching { repo.sync() }
        }
    }

    // ------------------------------------------------------------------ thread

    fun messages(room: String): Flow<PagingData<ChatMessageEntity>> =
        repo.pagedMessages(room).cachedIn(viewModelScope)

    fun observeRoom(room: String) = repo.observeRoom(room)

    /** Attachments and links for the conversation gallery. */
    fun gallery(room: String) = repo.observeGallery(room)

    /**
     * The room's members, held for the `@` autocomplete.
     *
     * Fetched once per thread and filtered on the device rather than queried per
     * keystroke. A room has tens of members, not thousands, and an autocomplete
     * that waits on a depot's link before offering a name is one people stop
     * using — they type the name by hand and the mention never happens.
     */
    var members by mutableStateOf<List<ChatUserDto>>(emptyList())
        private set

    fun openThread(room: String) {
        openRoom = room
        socket.subscribeThread(room)
        viewModelScope.launch { runCatching { repo.sync() } }
        viewModelScope.launch {
            members = runCatching { repo.roomMembers(room, "") }.getOrDefault(emptyList())
        }
    }

    fun closeThread(room: String) {
        if (openRoom == room) openRoom = null
        socket.unsubscribeThread(room)
    }

    /** Mark everything up to [seq] read. Forward-only, server-side and locally. */
    fun markRead(room: String, seq: Long) {
        if (seq <= 0) return
        viewModelScope.launch { runCatching { repo.markRead(room, seq) } }
    }

    // ------------------------------------------------------------------- send

    fun sendText(
        room: String,
        body: String,
        replyTo: String? = null,
        mentions: List<String> = emptyList(),
    ) {
        val text = body.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            val clientId = repo.queueText(room, text, me, myName, replyTo, mentions)
            ChatWork.enqueueText(getApplication(), clientId)
        }
    }

    fun sendAttachment(
        room: String,
        file: File,
        contentType: String,
        kind: String,
        fileName: String? = null,
        caption: String = "",
        durationMs: Long? = null,
        lat: Double? = null,
        lon: Double? = null,
        replyTo: String? = null,
    ) {
        viewModelScope.launch {
            val clientId = repo.queueAttachment(
                room = room,
                source = file,
                contentType = contentType,
                kind = kind,
                author = me,
                authorName = myName,
                fileName = fileName,
                caption = caption,
                durationMs = durationMs,
                lat = lat,
                lon = lon,
                replyTo = replyTo,
            )
            ChatWork.enqueueUpload(getApplication(), clientId, file.length())
        }
    }

    /** Re-arm a failed send. The server is idempotent on client_id, so this is safe. */
    fun retry(message: ChatMessageEntity) {
        viewModelScope.launch {
            repo.markStatusPending(message.clientId)
            if (message.localPath != null) {
                ChatWork.enqueueUpload(getApplication(), message.clientId, message.fileSize ?: 0)
            } else {
                ChatWork.enqueueText(getApplication(), message.clientId)
            }
        }
    }

    // -------------------------------------------------------- new conversation

    /**
     * A debounced staff-directory search.
     *
     * Cancelling the in-flight job on each keystroke matters more than usual
     * here: field handsets are on slow links, and without it a fast typist
     * queues six requests whose responses can land out of order and leave the
     * list showing results for a prefix they already deleted.
     *
     * Two independent instances exist — one behind the chat list's search box,
     * one behind the new-chat directory — because with a single shared result
     * list, opening the directory wipes whatever the list screen was showing.
     */
    inner class PeopleSearch {
        var results by mutableStateOf<List<ChatUserDto>>(emptyList())
            private set

        var busy by mutableStateOf(false)
            private set

        private var job: Job? = null

        fun query(term: String) {
            job?.cancel()
            job = viewModelScope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                busy = true
                results = runCatching { repo.searchUsers(term) }.getOrDefault(emptyList())
                busy = false
            }
        }

        fun clear() {
            job?.cancel()
            results = emptyList()
            busy = false
        }
    }

    /** Backs the full-screen "New chat" directory. */
    val directory = PeopleSearch()

    /** Backs the people results inlined under the chat list's search box. */
    val contacts = PeopleSearch()

    /**
     * Create a group. [onResult] carries the room name, or null with a reason.
     *
     * The server restricts this to the roles that can run a depot; a Technician
     * gets a PermissionError, which is surfaced verbatim rather than as a dead
     * button, so it is clear the app is not broken.
     */
    fun createGroup(
        title: String,
        members: List<String>,
        onResult: (String?, String?) -> Unit,
    ) {
        viewModelScope.launch {
            runCatching { repo.createGroup(title.trim(), members) }
                .onSuccess { onResult(it, null) }
                .onFailure { onResult(null, it.message ?: "Couldn't create that group.") }
        }
    }

    /** Open (or create) a DM and hand the room name back for navigation. */
    fun openDirect(user: String, onReady: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { repo.openDirect(user) }
                .onSuccess { onReady(it) }
        }
    }

    fun setMuted(room: String, muted: Boolean) {
        viewModelScope.launch { runCatching { repo.setMuted(room, muted) } }
    }

    // ---------------------------------------------------------------- tickets

    var tickets by mutableStateOf<List<ChatTicketDto>>(emptyList())
        private set

    var ticketsLoading by mutableStateOf(false)
        private set

    private var ticketJob: Job? = null

    fun searchTickets(term: String) {
        ticketJob?.cancel()
        ticketJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            ticketsLoading = true
            tickets = runCatching { repo.searchTickets(term) }.getOrDefault(emptyList())
            ticketsLoading = false
        }
    }

    /** [onResult] carries null on success, or a message to show on failure. */
    fun shareTicket(room: String, ticket: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val error = runCatching { repo.shareTicket(room, ticket) }
                .exceptionOrNull()
                ?.let { it.message ?: "Couldn't share that ticket." }
            onResult(error)
        }
    }

    fun assignTicket(ticket: String, user: String, room: String?, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val error = runCatching { repo.assignTicket(ticket, user, room) }
                .exceptionOrNull()
                ?.let { it.message ?: "Couldn't assign that ticket." }
            onResult(error)
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 250L
    }

    fun isMine(m: ChatMessageEntity) = m.author == me

    fun isPending(m: ChatMessageEntity) =
        m.status == SendStatus.PENDING || m.status == SendStatus.UPLOADING

    /** Rooms filtered by a search box, matching title or last message. */
    fun filterRooms(all: List<ChatRoomEntity>, query: String): List<ChatRoomEntity> {
        if (query.isBlank()) return all
        val q = query.trim().lowercase()
        return all.filter {
            it.title.lowercase().contains(q) ||
                it.lastMessagePreview.orEmpty().lowercase().contains(q) ||
                it.vehicle.orEmpty().lowercase().contains(q)
        }
    }
}
