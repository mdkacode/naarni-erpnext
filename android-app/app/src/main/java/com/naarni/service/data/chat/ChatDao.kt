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

    /** Cursors for the delta sync: the highest seq we hold per room. */
    @Query("SELECT name, lastSeq FROM chat_room")
    suspend fun syncCursors(): List<RoomCursor>

    // --------------------------------------------------------------- messages

    @Query("SELECT * FROM chat_message WHERE room = :room ORDER BY sortSeq DESC")
    fun pagedMessages(room: String): PagingSource<Int, ChatMessageEntity>

    @Query("SELECT * FROM chat_message WHERE clientId = :clientId")
    suspend fun message(clientId: String): ChatMessageEntity?

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
