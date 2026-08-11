package com.naarni.service.data.repo

import android.content.Context
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.naarni.service.core.network.FrappeApi
import com.naarni.service.core.network.payload
import com.naarni.service.data.chat.ChatDao
import com.naarni.service.data.chat.ChatMessageEntity
import com.naarni.service.data.chat.ChatRoomEntity
import com.naarni.service.data.chat.ChatUploadEntity
import com.naarni.service.data.chat.PENDING_BASE
import com.naarni.service.data.chat.SendStatus
import com.naarni.service.data.chat.previewOf
import com.naarni.service.data.dto.ChatMessageDto
import com.naarni.service.data.dto.ChatRoomDto
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * The chat client's brain.
 *
 * Nothing here returns a network type to the UI. Reads are Room Flows; writes go
 * to Room first and reach the server through a WorkManager job, so the screen is
 * identical whether the phone is on wifi or in a tunnel.
 *
 * The load-bearing method is [sync]. Every reconnect, foreground and push calls
 * it, which is why a dropped socket event, a half-open socket behind carrier NAT
 * and an FCM message lost to a restricted standby bucket are not three problems —
 * they are all "the cursor is behind", with one code path.
 */
class ChatRepository(
    private val api: FrappeApi,
    private val dao: ChatDao,
    private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Where queued attachments live. App-private, so scoped storage never applies. */
    private val outboxDir: File
        get() = File(context.filesDir, "chat_outbox").apply { mkdirs() }

    // -------------------------------------------------------------- observing

    fun observeRooms(): Flow<List<ChatRoomEntity>> = dao.observeRooms()

    fun observeRoom(room: String): Flow<ChatRoomEntity?> = dao.observeRoom(room)

    /** Drives the bottom-nav badge. Correct offline and on a cold start. */
    fun observeUnreadTotal(): Flow<Int> = dao.observeUnreadTotal()

    /**
     * Paged thread history straight out of Room.
     *
     * `maxSize` is the real OOM guard for TC03: it evicts pages from memory as
     * the user scrolls past them, so a 200-image thread has a bounded ceiling
     * rather than one that grows with scroll distance.
     */
    fun pagedMessages(room: String): Flow<PagingData<ChatMessageEntity>> =
        Pager(
            config = PagingConfig(
                pageSize = 50,
                prefetchDistance = 20,
                maxSize = 200,
                enablePlaceholders = false,
            ),
            pagingSourceFactory = { dao.pagedMessages(room) },
        ).flow

    // ------------------------------------------------------------------- sync

    /** Refresh the room list (titles, membership, mute state). */
    suspend fun refreshRooms() {
        val rooms = api.chatRooms().payload().rooms
        dao.upsertRooms(rooms.map { it.toEntity() })
    }

    /**
     * Delta sync. Sends the highest seq held per room, applies whatever is newer.
     *
     * Returns true if anything changed, so callers can decide whether to bother
     * re-rendering or notifying.
     */
    suspend fun sync(): Boolean {
        val cursors = dao.syncCursors().associate { it.name to it.lastSeq }
        val payload = api.chatSync(json.encodeToString(cursors)).payload()
        var changed = false
        for ((room, delta) in payload.rooms) {
            if (delta.messages.isEmpty()) continue
            dao.applyDelta(room, delta.messages.map { it.toEntity() }, delta.last_seq)
            changed = true
            // The server caps a delta page; if more remains, keep pulling rather
            // than leaving a hole a week-offline device would never fill.
            if (delta.more) syncRoomForward(room)
        }
        return changed
    }

    private suspend fun syncRoomForward(room: String) {
        var guard = 0
        while (guard++ < MAX_SYNC_PAGES) {
            val since = dao.highestSeq(room) ?: 0
            val delta = api.chatSync(json.encodeToString(mapOf(room to since)))
                .payload().rooms[room] ?: return
            if (delta.messages.isEmpty()) return
            dao.applyDelta(room, delta.messages.map { it.toEntity() }, delta.last_seq)
            if (!delta.more) return
        }
    }

    /** Older history for infinite scroll. */
    suspend fun loadOlder(room: String, beforeSeq: Long): Boolean {
        val page = api.chatMessages(room, beforeSeq = beforeSeq, limit = 50).payload()
        dao.upsertMessages(page.messages.map { it.toEntity() })
        return page.has_more
    }

    // --------------------------------------------------------- socket intake

    /**
     * Apply a realtime frame. Optimistic, but gap-checked.
     *
     * A message whose seq is more than one above what we hold means we missed
     * something, so we fall back to the delta sync instead of writing a row that
     * would leave a permanent hole in the thread.
     */
    suspend fun onRealtimeMessage(event: String, body: JsonObject) {
        val room = body["room"]?.jsonPrimitive?.content ?: return
        val seq = body["seq"]?.jsonPrimitive?.long ?: return
        val held = dao.highestSeq(room) ?: 0

        if (seq > held + 1) {
            sync()
            return
        }
        when (event) {
            EVENT_MESSAGE -> {
                val dto = runCatching { json.decodeFromString<ChatMessageDto>(body.toString()) }
                    .getOrNull() ?: return sync().let {}
                dao.upsertMessage(dto.toEntity())
                dao.touchRoom(room, dto.seq, previewOf(dto.toEntity()))
            }
            // The envelope is intentionally lightweight — enough to move the badge
            // without a round-trip. The body arrives via the doc room if the
            // thread is open, or on the next sync if it is not.
            EVENT_ENVELOPE -> {
                val preview = body["preview"]?.jsonPrimitive?.content.orEmpty()
                dao.touchRoom(room, seq, preview)
                if (seq > held) sync()
            }
        }
    }

    // ---------------------------------------------------------------- sending

    /**
     * Queue a text message.
     *
     * The row lands in Room as PENDING with a sort key above every real seq, so
     * it renders instantly at the bottom of the thread. Delivery is a WorkManager
     * job with a CONNECTED constraint — offline, it simply waits.
     */
    suspend fun queueText(
        room: String,
        body: String,
        author: String,
        authorName: String,
        replyTo: String? = null,
    ): String {
        val clientId = UUID.randomUUID().toString()
        dao.upsertMessage(
            ChatMessageEntity(
                clientId = clientId,
                room = room,
                seq = null,
                sortSeq = dao.nextPendingSortSeq(room),
                author = author,
                authorName = authorName,
                kind = "text",
                body = body,
                replyTo = replyTo,
                status = SendStatus.PENDING,
            )
        )
        return clientId
    }

    /** Perform the actual send. Called from the worker, never from the UI. */
    suspend fun deliverText(clientId: String) {
        val row = dao.message(clientId) ?: return
        val result = api.chatSend(
            room = row.room,
            clientId = row.clientId,
            body = row.body,
            kind = "text",
            replyTo = row.replyTo,
        ).payload()
        val msg = result.message
        dao.markSent(clientId, msg.name, msg.seq, msg.file_url)
        dao.touchRoom(row.room, msg.seq, previewOf(row.copy(seq = msg.seq)))
        dao.advanceReadCursor(row.room, msg.seq)
    }

    /**
     * Queue an attachment.
     *
     * The file is copied into app-private storage first. A 400 MB video may sit
     * queued for hours; a cache path could be purged under storage pressure and a
     * content URI's permission can be revoked, either of which would orphan the
     * upload halfway through.
     */
    suspend fun queueAttachment(
        room: String,
        source: File,
        contentType: String,
        kind: String,
        author: String,
        authorName: String,
        caption: String = "",
        durationMs: Long? = null,
        lat: Double? = null,
        lon: Double? = null,
        replyTo: String? = null,
    ): String {
        val clientId = UUID.randomUUID().toString()
        val dest = File(outboxDir, "${clientId}_${source.name}")
        if (source.absolutePath != dest.absolutePath) source.copyTo(dest, overwrite = true)

        dao.upsertMessage(
            ChatMessageEntity(
                clientId = clientId,
                room = room,
                seq = null,
                sortSeq = dao.nextPendingSortSeq(room),
                author = author,
                authorName = authorName,
                kind = kind,
                body = caption,
                localPath = dest.absolutePath,
                fileName = source.name,
                fileSize = dest.length(),
                durationMs = durationMs,
                geotagged = lat != null && lon != null,
                lat = lat,
                lon = lon,
                replyTo = replyTo,
                status = SendStatus.PENDING,
            )
        )
        dao.upsertUpload(
            ChatUploadEntity(
                clientId = clientId,
                room = room,
                path = dest.absolutePath,
                fileName = source.name,
                contentType = contentType,
                totalSize = dest.length(),
                sha256 = sha256Of(dest),
                caption = caption,
                durationMs = durationMs,
                lat = lat,
                lon = lon,
                replyTo = replyTo,
            )
        )
        return clientId
    }

    // ------------------------------------------------------------------ reads

    suspend fun markRead(room: String, seq: Long) {
        dao.advanceReadCursor(room, seq)
        runCatching { api.chatMarkRead(room, seq) }  // best-effort; local cursor already moved
    }

    suspend fun setMuted(room: String, muted: Boolean) {
        dao.setMuted(room, muted)
        runCatching { api.chatSetMuted(room, if (muted) 1 else 0) }
    }

    suspend fun outbox(): List<ChatMessageEntity> = dao.outbox()

    suspend fun highestSeq(room: String): Long = dao.highestSeq(room) ?: 0

    // ----------------------------------------------------------------- mapping

    private fun ChatRoomDto.toEntity() = ChatRoomEntity(
        name = name,
        title = title,
        kind = kind,
        depot = depot,
        vehicle = vehicle,
        ticket = ticket,
        jobCard = job_card,
        lastSeq = last_seq,
        lastReadSeq = last_read_seq,
        muted = muted,
        lastMessagePreview = last_message_preview,
        lastMessageAt = last_message_at,
        memberCount = member_count,
    )

    private fun ChatMessageDto.toEntity() = ChatMessageEntity(
        clientId = client_id,
        serverName = name,
        room = room,
        seq = seq,
        sortSeq = seq,
        author = author,
        authorName = author_name.ifBlank { author },
        kind = kind,
        body = body,
        replyTo = reply_to,
        fileUrl = file_url,
        fileName = file_name,
        fileSize = file_size,
        durationMs = duration_ms,
        transcript = transcript,
        vehicle = vehicle,
        ticket = ticket,
        geotagged = geotagged,
        lat = lat,
        lon = lon,
        deleted = deleted,
        status = SendStatus.SENT,
        uploadPct = 100,
    )

    companion object {
        const val EVENT_MESSAGE = "vm_chat_message"
        const val EVENT_ENVELOPE = "vm_chat_envelope"

        /** Bound on catch-up paging so a corrupt cursor cannot spin forever. */
        private const val MAX_SYNC_PAGES = 50

        /** Streamed so a 500 MB file is never held in memory to be hashed. */
        fun sha256Of(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(1024 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
