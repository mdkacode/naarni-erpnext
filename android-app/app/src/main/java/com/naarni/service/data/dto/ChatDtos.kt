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
    val geotagged: Boolean = false,
    val lat: Double? = null,
    val lon: Double? = null,
    val deleted: Boolean = false,
    val created_at: String = "",
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
    /** True when the server capped the page and more remains above `last_seq`. */
    val more: Boolean = false,
)

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
