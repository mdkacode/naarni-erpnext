package com.naarni.service.data.chat

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room schema for chat. This database is the single source of truth: the UI
 * renders only from here, and the socket, the REST delta sync and FCM are three
 * writers into it. Nothing is ever drawn straight from a network response.
 */

/** Local send state for a message. */
object SendStatus {
    /** Written locally, not yet accepted by the server. */
    const val PENDING = "PENDING"

    /** An attachment is being chunked up to the server. */
    const val UPLOADING = "UPLOADING"

    /** Server allocated a seq for it. */
    const val SENT = "SENT"

    /** Gave up — surfaced with a retry affordance. */
    const val FAILED = "FAILED"
}

/**
 * Sort key floor for messages that have no server sequence yet.
 *
 * Pending rows get `PENDING_BASE + localTick`, which parks them above every real
 * seq so they render newest-last without a `COALESCE` in the paging query — a
 * COALESCE would make the (room, sortSeq) index unusable and turn every page
 * load into a table scan.
 */
const val PENDING_BASE = 1_000_000_000_000L

@Entity(tableName = "chat_room")
data class ChatRoomEntity(
    @PrimaryKey val name: String,
    val title: String,
    val kind: String,
    val depot: String? = null,
    val vehicle: String? = null,
    val ticket: String? = null,
    val jobCard: String? = null,
    /** Highest seq the server has allocated in this room. */
    val lastSeq: Long = 0,
    /** Highest seq this user has read. Unread is the difference. */
    val lastReadSeq: Long = 0,
    val muted: Boolean = false,
    val lastMessagePreview: String? = null,
    val lastMessageAt: String? = null,
    val memberCount: Int = 0,
)

@Entity(
    tableName = "chat_message",
    indices = [
        // The paging query's index. Order matters: room narrows, sortSeq orders.
        Index(value = ["room", "sortSeq"]),
        // Gap detection and dedup of a message arriving by socket and by sync at once.
        Index(value = ["room", "seq"]),
    ],
)
data class ChatMessageEntity(
    /**
     * Device-minted UUID. Primary key locally and idempotency key on the server,
     * which is what makes a retry after a lost response safe.
     */
    @PrimaryKey val clientId: String,
    /** Frappe docname. Null until the server has acked. */
    val serverName: String? = null,
    val room: String,
    /** Server-allocated, per-room monotonic. Null while pending. */
    val seq: Long? = null,
    /** `seq` once acked, `PENDING_BASE + tick` before. Never null — it is the sort key. */
    val sortSeq: Long,
    val author: String,
    val authorName: String,
    val kind: String = "text",
    val body: String = "",
    val replyTo: String? = null,
    /** Local file path while an attachment is queued or uploading. */
    val localPath: String? = null,
    /** Server path once committed, e.g. /private/files/chat_abc.jpg */
    val fileUrl: String? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val durationMs: Long? = null,
    val transcript: String? = null,
    val vehicle: String? = null,
    val ticket: String? = null,
    val geotagged: Boolean = false,
    val lat: Double? = null,
    val lon: Double? = null,
    val deleted: Boolean = false,
    val status: String = SendStatus.SENT,
    /** Mirrored from the upload worker so progress survives process death. */
    val uploadPct: Int = 0,
    val failureReason: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Durable state for one resumable upload.
 *
 * Kept in Room rather than in WorkManager's `Data` because Data is capped at
 * 10 KB and, more importantly, because the worker must be able to recover this
 * after process death — the worker itself holds nothing.
 */
@Entity(tableName = "chat_upload")
data class ChatUploadEntity(
    @PrimaryKey val clientId: String,
    val room: String,
    /** Server handle from begin_upload. Null until that call succeeds. */
    val uploadId: String? = null,
    /** Absolute path under filesDir/chat_outbox — app-private, no scoped storage. */
    val path: String,
    val fileName: String,
    val contentType: String,
    val totalSize: Long,
    val bytesSent: Long = 0,
    val sha256: String? = null,
    val pct: Int = 0,
    val caption: String = "",
    val durationMs: Long? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val replyTo: String? = null,
)
