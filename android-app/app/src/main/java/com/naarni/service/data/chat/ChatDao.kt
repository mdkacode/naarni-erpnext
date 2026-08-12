package com.naarni.service.data.chat

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Every read the chat UI performs. Note that no method here takes or returns a
 * network type — the UI cannot accidentally render an un-persisted response.
 */
@Dao
interface ChatDao {

    // ------------------------------------------------------------------ rooms

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRooms(rooms: List<ChatRoomEntity>)

    @Query("SELECT * FROM chat_room ORDER BY lastMessageAt DESC, title ASC")
    fun observeRooms(): Flow<List<ChatRoomEntity>>

    @Query("SELECT * FROM chat_room WHERE name = :room")
    fun observeRoom(room: String): Flow<ChatRoomEntity?>

    @Query("SELECT * FROM chat_room WHERE name = :room")
    suspend fun room(room: String): ChatRoomEntity?

    /**
     * Drives the bottom-nav badge. One query, recomputed by Room on any write,
     * so it is correct offline and after a cold start with no network.
     */
    @Query("SELECT COALESCE(SUM(MAX(lastSeq - lastReadSeq, 0)), 0) FROM chat_room WHERE muted = 0")
    fun observeUnreadTotal(): Flow<Int>

    @Query("UPDATE chat_room SET lastReadSeq = MAX(lastReadSeq, :seq) WHERE name = :room")
    suspend fun advanceReadCursor(room: String, seq: Long)

    @Query("UPDATE chat_room SET muted = :muted WHERE name = :room")
    suspend fun setMuted(room: String, muted: Boolean)

    @Query("UPDATE chat_room SET lastSeq = MAX(lastSeq, :seq), lastMessagePreview = :preview WHERE name = :room")
    suspend fun touchRoom(room: String, seq: Long, preview: String)

    /**
     * Cursors for the delta sync: the highest seq we actually **hold**.
     *
     * Deliberately derived from chat_message, not from `chat_room.lastSeq`.
     * `lastSeq` is the server's high-water mark, copied in by refreshRooms() —
     * using it as the cursor tells the server "I already have everything up to
     * N" when the message table is empty, so sync correctly returns nothing and
     * every thread renders blank while the room list looks fully populated.
     */
    @Query(
        """
        SELECT r.name AS name, COALESCE(MAX(m.seq), 0) AS lastSeq
          FROM chat_room r
          LEFT JOIN chat_message m ON m.room = r.name
         GROUP BY r.name
        """
    )
    suspend fun syncCursors(): List<RoomCursor>

    // --------------------------------------------------------------- messages

    @Query("SELECT * FROM chat_message WHERE room = :room ORDER BY sortSeq DESC")
    fun pagedMessages(room: String): PagingSource<Int, ChatMessageEntity>

    @Query("SELECT * FROM chat_message WHERE clientId = :clientId")
    suspend fun message(clientId: String): ChatMessageEntity?

    /**
     * Everything a conversation's gallery can show, in one observer.
     *
     * Attachments and link-bearing text come back together and are bucketed on
     * the client rather than in four separate queries: the tabs need counts for
     * *all* buckets to render their labels, so four queries would mean four
     * observers running permanently to populate headers for tabs nobody opened.
     *
     * `LIKE '%http%'` is a coarse pre-filter — it is a plain scan, so the real
     * URL match happens in Kotlin against rows this has already narrowed.
     *
     * Bounded deliberately. A depot vehicle thread accumulates thousands of
     * photos over a year, and a gallery is a "recent things" surface, not an
     * archive; the cap is what stops opening it from allocating the entire
     * message history of the room.
     */
    @Query(
        """
        SELECT * FROM chat_message
         WHERE room = :room
           AND (
                (kind IN ('image', 'video', 'audio', 'file')
                 AND (fileUrl IS NOT NULL OR localPath IS NOT NULL))
             OR (kind = 'text' AND body LIKE '%http%')
           )
         ORDER BY sortSeq DESC
         LIMIT :limit
        """
    )
    fun observeGallery(room: String, limit: Int = 500): Flow<List<ChatMessageEntity>>

    @Query("SELECT MAX(seq) FROM chat_message WHERE room = :room")
    suspend fun highestSeq(room: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(rows: List<ChatMessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(row: ChatMessageEntity)

    /** Next free pending sort key, so optimistic rows keep their send order. */
    @Query("SELECT COALESCE(MAX(sortSeq), :base) + 1 FROM chat_message WHERE room = :room AND sortSeq >= :base")
    suspend fun nextPendingSortSeq(room: String, base: Long = PENDING_BASE): Long

    @Query(
        """
        UPDATE chat_message
           SET serverName = :serverName, seq = :seq, sortSeq = :seq,
               fileUrl = :fileUrl, status = '${SendStatus.SENT}',
               uploadPct = 100, failureReason = NULL
         WHERE clientId = :clientId
        """
    )
    suspend fun markSent(clientId: String, serverName: String, seq: Long, fileUrl: String?)

    @Query("UPDATE chat_message SET status = :status, failureReason = :reason WHERE clientId = :clientId")
    suspend fun markStatus(clientId: String, status: String, reason: String? = null)

    @Query("UPDATE chat_message SET uploadPct = :pct, status = '${SendStatus.UPLOADING}' WHERE clientId = :clientId")
    suspend fun markProgress(clientId: String, pct: Int)

    /** Queued sends to replay when connectivity returns. Oldest first. */
    @Query(
        """
        SELECT * FROM chat_message
         WHERE status IN ('${SendStatus.PENDING}', '${SendStatus.FAILED}')
         ORDER BY sortSeq ASC
        """
    )
    suspend fun outbox(): List<ChatMessageEntity>

    @Query("DELETE FROM chat_message WHERE clientId = :clientId")
    suspend fun deleteMessage(clientId: String)

    // ---------------------------------------------------------------- uploads

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUpload(row: ChatUploadEntity)

    @Query("SELECT * FROM chat_upload WHERE clientId = :clientId")
    suspend fun upload(clientId: String): ChatUploadEntity?

    @Query("UPDATE chat_upload SET uploadId = :uploadId WHERE clientId = :clientId")
    suspend fun setUploadId(clientId: String, uploadId: String)

    @Query("UPDATE chat_upload SET bytesSent = :bytes, pct = :pct WHERE clientId = :clientId")
    suspend fun setUploadProgress(clientId: String, bytes: Long, pct: Int)

    @Query("DELETE FROM chat_upload WHERE clientId = :clientId")
    suspend fun deleteUpload(clientId: String)

    // --------------------------------------------------------------- combined

    /**
     * Apply one room's delta atomically.
     *
     * Doing this in a transaction is what stops the UI showing a message whose
     * room cursor has not moved yet — Paging would emit the row, the badge would
     * disagree, and a sync interrupted midway would leave a permanent gap.
     */
    /**
     * Wipe every trace of the signed-out user's chat.
     *
     * Rows rather than the database file: the Room instance is a process-wide
     * lazy that cannot be rebuilt, and deleting the file out from under an open
     * connection leaves the next user's session talking to a handle that no
     * longer has a file behind it. This leaves the schema intact and usable.
     *
     * The outbox goes too. A queued message belongs to whoever wrote it, and on
     * a shared depot handset the next person to sign in must not send it.
     */
    @Transaction
    suspend fun wipeEverything() {
        deleteAllMessages()
        deleteAllUploads()
        deleteAllRooms()
    }

    @Query("DELETE FROM chat_message")
    suspend fun deleteAllMessages()

    @Query("DELETE FROM chat_upload")
    suspend fun deleteAllUploads()

    @Query("DELETE FROM chat_room")
    suspend fun deleteAllRooms()

    @Transaction
    suspend fun applyDelta(room: String, messages: List<ChatMessageEntity>, lastSeq: Long) {
        if (messages.isNotEmpty()) upsertMessages(messages)
        val preview = messages.lastOrNull()?.let { previewOf(it) }.orEmpty()
        touchRoom(room, lastSeq, preview)
    }
}

/** Projection for [ChatDao.syncCursors]. */
data class RoomCursor(val name: String, val lastSeq: Long)

/** One-line summary used for room list rows. Mirrors the server's preview_for(). */
fun previewOf(m: ChatMessageEntity): String = when (m.kind) {
    "image" -> if (m.body.isBlank()) "📷 Photo" else "📷 Photo · ${m.body}"
    "video" -> if (m.body.isBlank()) "🎥 Video" else "🎥 Video · ${m.body}"
    "audio" -> "🎤 Voice note"
    else -> m.body
}.take(140)
