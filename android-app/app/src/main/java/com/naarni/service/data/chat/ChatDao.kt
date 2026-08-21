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

    /**
     * The watermarks currently held, so a room refresh can be clamped to them.
     *
     * `upsertRooms` REPLACEs, and the room list is fetched on a schedule that
     * has nothing to do with when receipts arrive. Without this, a response
     * describing the room as it stood a second before a realtime receipt landed
     * would overwrite it — and a tick that had already turned blue would go
     * back to grey in front of the person who watched it turn.
     */
    @Query("SELECT name, lastReadSeq, deliveredUpto, readUpto FROM chat_room")
    suspend fun roomWatermarks(): List<RoomWatermarks>

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

    /**
     * Replace a message's reaction chips.
     *
     * Keyed on the server name because a reaction can only exist on a message
     * the server has acked — there is nothing to react to before that.
     * Wholesale replacement rather than a merge: the server sends the complete
     * set every time, and it is the only authority on who has reacted.
     */
    @Query("UPDATE chat_message SET reactions = :json WHERE serverName = :serverName")
    suspend fun setReactions(serverName: String, json: String?)

    /**
     * Move the room's delivered/read watermarks — the second and third tick.
     *
     * MAX, never assignment. Two sources feed these: `list_rooms`, which
     * computes the minimum across every member *except* the viewer, and the
     * realtime receipt, which cannot know who is receiving it and so takes the
     * minimum across everyone. The realtime figure is therefore sometimes the
     * lower of the two, and letting it overwrite would make a tick that had
     * already turned blue go grey again.
     */
    @Query(
        """
        UPDATE chat_room
           SET deliveredUpto = MAX(deliveredUpto, :delivered),
               readUpto = MAX(readUpto, :read)
         WHERE name = :room
        """
    )
    suspend fun advanceReceipts(room: String, delivered: Long, read: Long)

    @Query("UPDATE chat_room SET lastSeq = MAX(lastSeq, :seq), lastMessagePreview = :preview WHERE name = :room")
    suspend fun touchRoom(room: String, seq: Long, preview: String)

    /**
     * Cursors for the delta sync: the highest seq and the highest tombstone
     * number we actually **hold**.
     *
     * Deliberately derived from chat_message, not from `chat_room.lastSeq`.
     * `lastSeq` is the server's high-water mark, copied in by refreshRooms() —
     * using it as the cursor tells the server "I already have everything up to
     * N" when the message table is empty, so sync correctly returns nothing and
     * every thread renders blank while the room list looks fully populated.
     *
     * `lastDeleteSeq` is the same argument applied to deletions: without it the
     * server has no way to tell a device that already dropped a message from one
     * that has never been told, so every sync would re-send every tombstone in
     * the room forever.
     */
    @Query(
        """
        SELECT r.name AS name,
               COALESCE(MAX(m.seq), 0) AS lastSeq,
               COALESCE(MAX(m.deleteSeq), 0) AS lastDeleteSeq
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
           AND deleted = 0
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

    @Query("SELECT MAX(deleteSeq) FROM chat_message WHERE room = :room")
    suspend fun highestDeleteSeq(room: String): Long?

    /** The newest message held for a room, for recomputing the list-row preview. */
    @Query("SELECT * FROM chat_message WHERE room = :room ORDER BY sortSeq DESC LIMIT 1")
    suspend fun newestMessage(room: String): ChatMessageEntity?

    /**
     * Turn a message into a tombstone.
     *
     * Everything it carried goes in the same statement — body, attachment,
     * caption, reactions, mentions, coordinates — rather than being left for the
     * renderer to hide. A row that still holds the text is a row that shows it
     * the first time somebody writes a new bubble variant and forgets the flag,
     * and the local copy of a photo would otherwise sit in app storage after the
     * person who sent it asked for it to be gone.
     *
     * `localPath` is nulled but the file itself is removed by the repository,
     * which is the only layer that may touch the filesystem.
     */
    @Query(
        """
        UPDATE chat_message
           SET deleted = 1,
               deleteSeq = MAX(deleteSeq, :deleteSeq),
               deletedBy = COALESCE(:deletedBy, deletedBy),
               body = '',
               fileUrl = NULL,
               localPath = NULL,
               fileName = NULL,
               fileSize = NULL,
               durationMs = NULL,
               transcript = NULL,
               reactions = NULL,
               mentions = NULL,
               mentionsMe = 0,
               geotagged = 0,
               lat = NULL,
               lon = NULL
         WHERE clientId = :clientId
        """
    )
    suspend fun markDeleted(clientId: String, deleteSeq: Long, deletedBy: String?)

    @Query("SELECT * FROM chat_message WHERE serverName = :serverName LIMIT 1")
    suspend fun messageByServerName(serverName: String): ChatMessageEntity?

    /**
     * Hide a message the moment its sender asks, before the server has agreed.
     *
     * The flag only — nothing is wiped. That is the whole point of it being a
     * separate statement from [markDeleted]: a phone in a basement gets the
     * instant feedback it needs, and if the request ultimately turns out to be
     * refused the row is still intact and the bubble can come back. Content is
     * destroyed only once the deletion is real.
     */
    @Query("UPDATE chat_message SET deleted = 1 WHERE clientId = :clientId")
    suspend fun hideLocally(clientId: String)

    /** Put a message back after a delete the server permanently refused. */
    @Query("UPDATE chat_message SET deleted = 0 WHERE clientId = :clientId AND deleteSeq = 0")
    suspend fun unhideLocally(clientId: String)

    /**
     * Every message in this room that some other message is a reply to.
     *
     * One observed query for the whole room rather than a lookup per bubble.
     * The obvious alternative — a Room `@Relation` or a self-join on the paging
     * query — would make the (room, sortSeq) index unusable and turn every page
     * load into a scan, which is a heavy price for a decoration on a minority of
     * rows. This runs once, is recomputed only when the room is written to, and
     * the result is a map the bubble reads with a single lookup.
     *
     * Bounded because a year-old vehicle thread can accumulate thousands of
     * replies, and a quote is only ever drawn for a message currently on screen.
     */
    @Query(
        """
        SELECT * FROM chat_message
         WHERE room = :room
           AND serverName IS NOT NULL
           AND serverName IN (
                SELECT replyTo FROM chat_message
                 WHERE room = :room AND replyTo IS NOT NULL
           )
         ORDER BY sortSeq DESC
         LIMIT :limit
        """
    )
    fun observeReplyParents(room: String, limit: Int = 400): Flow<List<ChatMessageEntity>>

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
        // Deliberately not `messages.last()`. A delta now carries two streams —
        // new messages ordered by seq, then tombstones ordered by their own
        // counter — so the final element is routinely an old message somebody
        // just withdrew, and taking its preview would blank the room's list row
        // and claim the conversation ended there.
        val preview = newestMessage(room)?.let { previewOf(it) }.orEmpty()
        touchRoom(room, lastSeq, preview)
    }

    /**
     * Redraw the room's list row from whatever is now newest.
     *
     * Called after a deletion, because the line may be quoting the words that
     * were just withdrawn.
     */
    @Transaction
    suspend fun refreshPreview(room: String) {
        val newest = newestMessage(room) ?: return
        touchRoom(room, newest.seq ?: 0, previewOf(newest))
    }
}

/** Projection for [ChatDao.syncCursors]. */
data class RoomCursor(val name: String, val lastSeq: Long, val lastDeleteSeq: Long = 0)

/** Projection for [ChatDao.roomWatermarks] — everything that may only go up. */
data class RoomWatermarks(
    val name: String,
    val lastReadSeq: Long,
    val deliveredUpto: Long,
    val readUpto: Long,
)

/** What a withdrawn message reads as, wherever one has to be named. */
const val DELETED_LABEL = "This message was deleted"

/** One-line summary used for room list rows. Mirrors the server's preview_for(). */
fun previewOf(m: ChatMessageEntity): String = when {
    m.deleted -> DELETED_LABEL
    m.kind == "image" -> if (m.body.isBlank()) "📷 Photo" else "📷 Photo · ${m.body}"
    m.kind == "video" -> if (m.body.isBlank()) "🎥 Video" else "🎥 Video · ${m.body}"
    m.kind == "audio" -> "🎤 Voice note"
    else -> m.body
}.take(140)
