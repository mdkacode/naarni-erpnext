package com.naarni.service.data.dto

import kotlinx.serialization.Serializable

/**
 * Wire shapes for `vehicle_maintenance.api.chat` and `.chat_upload`.
 *
 * Property names stay snake_case to match Frappe exactly, as the rest of this
 * app's DTOs do. These types never reach the UI — the repository maps them into
 * Room entities and the UI reads only from there.
 */

@Serializable
data class ChatRoomDto(
    val name: String,
    val title: String = "",
    val kind: String = "Group",
    val depot: String? = null,
    val vehicle: String? = null,
    val ticket: String? = null,
    val job_card: String? = null,
    val last_seq: Long = 0,
    val last_read_seq: Long = 0,
    val delivered_upto: Long = 0,
    val read_upto: Long = 0,
    val unread: Int = 0,
    val muted: Boolean = false,
    val last_message_preview: String? = null,
    val last_message_at: String? = null,
    val member_count: Int = 0,
    /** Direct rooms only: the other participant, resolved per-viewer. */
    val peer: String? = null,
    val peer_image: String? = null,
)

@Serializable
data class ChatUserDto(
    val name: String,
    val full_name: String? = null,
    val mobile_no: String? = null,
    val user_image: String? = null,
)

@Serializable
data class UsersPayload(val users: List<ChatUserDto> = emptyList())

@Serializable
data class DirectRoomPayload(val room: String, val created: Boolean = false)

@Serializable
data class ChatMessageDto(
    val name: String,
    val room: String,
    val seq: Long,
    val client_id: String,
    val author: String,
    val author_name: String = "",
    val kind: String = "text",
    val body: String = "",
    val file_url: String? = null,
    val file_name: String? = null,
    val file_size: Long? = null,
    val duration_ms: Long? = null,
    val transcript: String? = null,
    val reply_to: String? = null,
    val vehicle: String? = null,
    val ticket: String? = null,
    val alert_event: String? = null,
    /** User ids named with `@`. Already filtered to room members by the server. */
    val mentions: List<String> = emptyList(),
    /** Grouped by reaction code, in the server's fixed display order. */
    val reactions: List<ChatReactionDto> = emptyList(),
    val geotagged: Boolean = false,
    val lat: Double? = null,
    val lon: Double? = null,
    val deleted: Boolean = false,
    /**
     * Position in the room's *tombstone* stream — a second counter, unrelated to
     * [seq]. Non-zero only once the message has been withdrawn. This is what the
     * delta sync pages on, so a device that was offline when somebody deleted a
     * message still hears about it.
     */
    val delete_seq: Long = 0,
    val deleted_by: String? = null,
    val deleted_by_name: String? = null,
    val created_at: String = "",
)

/**
 * One reaction chip: who put which emoji on a message.
 *
 * `code` is the identity — `emoji` is only what to draw. The server keys on the
 * code because MariaDB's collation treats every emoji as equal to every other,
 * so a glyph cannot be a key.
 */
@Serializable
data class ChatReactionDto(
    val code: String,
    val emoji: String = "",
    val users: List<String> = emptyList(),
    val count: Int = 0,
)

/** A Service Ticket as it appears in the in-chat picker and on a shared card. */
@Serializable
data class ChatTicketDto(
    val name: String,
    val title: String? = null,
    val status: String = "",
    val severity: String? = null,
    val registration_number: String? = null,
    val vehicle: String? = null,
    val depot: String? = null,
    val assigned_to: String? = null,
    val assigned_to_name: String? = null,
)

@Serializable
data class TicketsPayload(val tickets: List<ChatTicketDto> = emptyList())

@Serializable
data class RoomsPayload(val rooms: List<ChatRoomDto> = emptyList())

@Serializable
data class MessagesPayload(
    val room: String = "",
    val messages: List<ChatMessageDto> = emptyList(),
    val has_more: Boolean = false,
)

@Serializable
data class RoomDelta(
    val messages: List<ChatMessageDto> = emptyList(),
    val last_seq: Long = 0,
    /** High-water mark of the room's tombstone stream — see [SyncCursor]. */
    val last_delete_seq: Long = 0,
    /** True when the server capped the page and more remains above either mark. */
    val more: Boolean = false,
)

/**
 * What this device already holds for one room, as two independent marks.
 *
 * Two, because a deletion mutates a message the client was handed long ago:
 * `sync` returns rows *above* the seq cursor, and a message withdrawn an hour
 * after it was sent sits below it, so on one cursor the deletion would simply
 * never be mentioned again. `del` is the second stream, and it is deliberately
 * not part of `seq` — unread is `last_seq` minus the read cursor, and a
 * tombstone drawn from that counter would light a badge nothing could clear.
 */
@Serializable
data class SyncCursor(val seq: Long, val del: Long)

@Serializable
data class SyncPayload(
    val rooms: Map<String, RoomDelta> = emptyMap(),
    val server_time: String = "",
)

@Serializable
data class SendPayload(
    val message: ChatMessageDto,
    /** True when the server matched an existing `client_id` instead of inserting. */
    val duplicate: Boolean = false,
)

@Serializable
data class CreateRoomPayload(val room: String)

@Serializable
data class MarkReadPayload(val room: String = "", val last_read_seq: Long = 0)

@Serializable
data class ReactionsPayload(
    val message: String = "",
    val reactions: List<ChatReactionDto> = emptyList(),
)

@Serializable
data class TypingPayload(val room: String = "", val typing: Boolean = false)

/**
 * Who, of the people you share a room with, is reachable right now.
 *
 * `ttl` is the server's own staleness window rather than a client constant, so
 * tuning presence never means shipping an app update to match.
 *
 * `last_seen` covers only those *not* in `online`, and only those who have not
 * opted out — a user who is online is here now, and saying both at once reads
 * as a bug. Values are server-local `yyyy-MM-dd HH:mm:ss`.
 */
@Serializable
data class PresencePayload(
    val online: List<String> = emptyList(),
    val last_seen: Map<String, String> = emptyMap(),
    val ttl: Int = 75,
)

// ------------------------------------------------------------------- uploads

@Serializable
data class BeginUploadPayload(
    val upload_id: String,
    /** Server-dictated chunk ceiling. The worker must not exceed it. */
    val chunk_size: Int = 4 * 1024 * 1024,
    val bytes_received: Long = 0,
)

@Serializable
data class ChunkPayload(
    val bytes_received: Long = 0,
    val total_size: Long = 0,
    val complete: Boolean = false,
)

@Serializable
data class ChunkStatusPayload(
    val upload_id: String = "",
    /** The authoritative resume point after process death. */
    val bytes_received: Long = 0,
    val total_size: Long = 0,
    val status: String = "Staging",
)

@Serializable
data class CommitPayload(
    val message: ChatMessageDto,
    val file_url: String? = null,
    val duplicate: Boolean = false,
)
