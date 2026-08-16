package com.naarni.service.data.repo

import android.content.Context
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.naarni.service.core.auth.SessionManager
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
import com.naarni.service.data.dto.ChatTicketDto
import com.naarni.service.data.dto.ChatUserDto
import com.naarni.service.data.dto.PresencePayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
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
    private val session: SessionManager,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Where queued attachments live. App-private, so scoped storage never applies. */
    private val outboxDir: File
        get() = File(context.filesDir, "chat_outbox").apply { mkdirs() }

    // -------------------------------------------------------------- observing

    fun observeRooms(): Flow<List<ChatRoomEntity>> = dao.observeRooms()

    fun observeRoom(room: String): Flow<ChatRoomEntity?> = dao.observeRoom(room)

    /** Attachments and links for one conversation's gallery. */
    fun observeGallery(room: String): Flow<List<ChatMessageEntity>> = dao.observeGallery(room)

    /**
     * Parents of every reply in the room, keyed by server name.
     *
     * Emitted as a map rather than a list because the only access pattern is
     * "given this message's replyTo, what was quoted" — building the map once
     * per emission is far cheaper than a linear scan per visible bubble.
     */
    fun observeReplyParents(room: String): Flow<Map<String, ChatMessageEntity>> =
        dao.observeReplyParents(room).map { rows ->
            rows.associateBy { it.serverName.orEmpty() }
        }

    /** Title for a room already in Room, for the notification tray. */
    suspend fun roomTitle(room: String): String = dao.room(room)?.title.orEmpty()

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
        // Clamped to what is already held, because the upsert REPLACEs the row
        // and these three only ever move forward. A response describing the
        // room as it stood before a receipt or a local read landed must not be
        // allowed to undo either.
        val held = dao.roomWatermarks().associateBy { it.name }
        dao.upsertRooms(
            rooms.map { dto ->
                val entity = dto.toEntity()
                val mark = held[entity.name] ?: return@map entity
                entity.copy(
                    lastReadSeq = maxOf(entity.lastReadSeq, mark.lastReadSeq),
                    deliveredUpto = maxOf(entity.deliveredUpto, mark.deliveredUpto),
                    readUpto = maxOf(entity.readUpto, mark.readUpto),
                )
            },
        )
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
                // The message is now on this device, which is exactly what the
                // sender's second tick claims. Nothing else tells the server
                // that: a socket push leaves no trace, so without this the mark
                // waits for the next sync and the sender sits on one tick while
                // the recipient is already looking at the message.
                //
                // Not for our own messages — a sender delivering to themselves
                // would tick their own message on send.
                if (dto.author != session.user) {
                    markDelivered(room, dto.seq)
                }
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

    /**
     * Apply a receipt frame — somebody in the room received or read something.
     *
     * Handled apart from [onRealtimeMessage] because a receipt carries no `seq`
     * of its own and would be discarded by the gap check there. There is also
     * nothing to fetch: the payload is the whole fact, so this never triggers a
     * sync however far behind the watermarks are.
     */
    suspend fun onRealtimeReceipt(body: JsonObject) {
        val room = body["room"]?.jsonPrimitive?.content ?: return
        val read = body["read_upto"]?.jsonPrimitive?.longOrNull ?: 0L
        val delivered = body["delivered_upto"]?.jsonPrimitive?.longOrNull ?: 0L
        if (read <= 0 && delivered <= 0) return
        dao.advanceReceipts(room, delivered = maxOf(delivered, read), read = read)
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
        mentions: List<String> = emptyList(),
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
                mentions = mentions.takeIf { it.isNotEmpty() }?.joinToString(","),
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
            // Sent as the client's own parse. Re-deriving it server-side from the
            // text would guess wrong whenever two people share a display name.
            mentions = row.mentions
                ?.split(",")
                ?.filter { it.isNotBlank() }
                ?.let { json.encodeToString(it) },
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
        fileName: String? = null,
        caption: String = "",
        durationMs: Long? = null,
        lat: Double? = null,
        lon: Double? = null,
        replyTo: String? = null,
    ): String {
        val clientId = UUID.randomUUID().toString()
        // What the user will see on the card and what the server publishes under.
        val shownName = fileName?.takeIf { it.isNotBlank() } ?: source.name
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
                fileName = shownName,
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
                fileName = shownName,
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

    /**
     * Tell the server this device holds [seq]. Best-effort and never throws:
     * a tick is not worth failing a message store over, and the next `sync`
     * carries the same information anyway.
     */
    suspend fun markDelivered(room: String, seq: Long) {
        if (seq <= 0) return
        runCatching { api.chatMarkDelivered(room, seq) }
    }

    suspend fun markRead(room: String, seq: Long) {
        dao.advanceReadCursor(room, seq)
        runCatching { api.chatMarkRead(room, seq) }  // best-effort; local cursor already moved
    }

    suspend fun setMuted(room: String, muted: Boolean) {
        dao.setMuted(room, muted)
        runCatching { api.chatSetMuted(room, if (muted) 1 else 0) }
    }

    // ------------------------------------------------------ presence / typing

    /**
     * Announce composing state. Never throws.
     *
     * A failure here is genuinely inconsequential — the worst case is that
     * somebody's typing dot does not appear — and letting it propagate would
     * mean a dropped packet could surface an error over a thread the user is
     * successfully messaging in.
     */
    suspend fun setTyping(room: String, typing: Boolean) {
        runCatching { api.chatSetTyping(room, if (typing) 1 else 0) }
    }

    /**
     * Report this device online and collect who else is, plus when the rest
     * were last around. Empty on any failure — presence is never worth an error.
     */
    suspend fun heartbeat(): PresencePayload =
        runCatching { api.chatHeartbeat().payload() }.getOrDefault(PresencePayload())

    /** Directory search. Not cached — it is a live lookup, not app state. */
    suspend fun searchUsers(query: String): List<ChatUserDto> =
        api.chatSearchUsers(query).payload().users

    /** Candidates for an `@` — the room's own members, never the whole directory. */
    suspend fun roomMembers(room: String, query: String): List<ChatUserDto> =
        api.chatRoomMembers(room, query).payload().users

    // ---------------------------------------------------------------- tickets

    suspend fun searchTickets(query: String): List<ChatTicketDto> =
        api.chatSearchTickets(query).payload().tickets

    /**
     * Share a ticket into a thread.
     *
     * Unlike a text message this goes straight out rather than through the
     * outbox: it carries no attachment and no user-typed content that would be
     * lost, and the server response is what tells us the ticket's current status
     * to render on the card. A failure surfaces to the caller to retry.
     */
    suspend fun shareTicket(room: String, ticket: String, note: String = ""): String {
        val clientId = UUID.randomUUID().toString()
        val sent = api.chatShareTicket(room, ticket, clientId, note).payload().message
        dao.upsertMessage(sent.toEntity())
        dao.touchRoom(room, sent.seq, previewOf(sent.toEntity()))
        dao.advanceReadCursor(room, sent.seq)
        return clientId
    }

    /** Assign a ticket. The server posts the handover notice back into the room. */
    suspend fun assignTicket(ticket: String, user: String, room: String?) {
        api.chatAssignTicket(ticket, user, room)
        // The notice arrives as a normal message; pull it now rather than waiting
        // for the socket, so the thread reflects the action immediately.
        runCatching { sync() }
    }

    /**
     * Open (or create) the one-to-one thread with [user] and make sure it is in
     * Room before the caller navigates, so the thread screen never opens onto a
     * room the local database has not heard of.
     */
    suspend fun openDirect(user: String): String {
        val room = api.chatGetOrCreateDirect(user).payload().room
        runCatching { refreshRooms() }
        return room
    }

    /**
     * Create a group and return its room name.
     *
     * The room list is refreshed before returning so the caller can navigate
     * straight into the thread — without it the screen opens against a room
     * Room has never heard of and renders empty until the next sync.
     */
    suspend fun createGroup(title: String, members: List<String>): String {
        val room = api.chatCreateRoom(
            title = title,
            kind = "Group",
            members = JSONArray(members).toString(),
        ).payload().room
        runCatching { refreshRooms() }
        runCatching { sync() }
        return room
    }

    suspend fun outbox(): List<ChatMessageEntity> = dao.outbox()

    /** Re-arm a failed send. Safe because the server is idempotent on client_id. */
    suspend fun markStatusPending(clientId: String) {
        dao.markStatus(clientId, SendStatus.PENDING, null)
    }

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
        deliveredUpto = delivered_upto,
        readUpto = read_upto,
        muted = muted,
        lastMessagePreview = last_message_preview,
        lastMessageAt = last_message_at,
        memberCount = member_count,
        peer = peer,
        peerImage = peer_image,
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
        alertEvent = alert_event,
        mentions = mentions.takeIf { it.isNotEmpty() }?.joinToString(","),
        // Resolved here, against whoever is signed in as this row is written.
        mentionsMe = session.user?.let { it in mentions } == true,
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
