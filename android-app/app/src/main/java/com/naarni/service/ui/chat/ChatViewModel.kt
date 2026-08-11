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
import com.naarni.service.data.repo.ChatRepository
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

    /** Emits when a message lands in the open thread, so the UI can chime. */
    val incoming = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    val me: String get() = session.user.orEmpty()
    private val myName: String get() = session.fullName ?: session.user.orEmpty()

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
                        // Always resync on reconnect. The socket is a latency
                        // optimisation; this is the correctness path.
                        runCatching { repo.sync() }
                        openRoom?.let { socket.subscribeThread(it) }
                    }

                    is FrappeSocket.Event.Disconnected -> connection = ConnectionState.Offline

                    // Auth/namespace rejection. Retrying without a new session is
                    // pointless, so we surface it rather than spin.
                    is FrappeSocket.Event.Fatal -> connection = ConnectionState.Rejected

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

    fun openThread(room: String) {
        openRoom = room
        socket.subscribeThread(room)
        viewModelScope.launch { runCatching { repo.sync() } }
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

    fun sendText(room: String, body: String, replyTo: String? = null) {
        val text = body.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            val clientId = repo.queueText(room, text, me, myName, replyTo)
            ChatWork.enqueueText(getApplication(), clientId)
        }
    }

    fun sendAttachment(
        room: String,
        file: File,
        contentType: String,
        kind: String,
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

    fun setMuted(room: String, muted: Boolean) {
        viewModelScope.launch { runCatching { repo.setMuted(room, muted) } }
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
