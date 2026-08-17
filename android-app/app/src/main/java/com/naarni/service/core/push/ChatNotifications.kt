package com.naarni.service.core.push

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.LocusIdCompat
import androidx.core.graphics.drawable.IconCompat
import com.naarni.service.App
import com.naarni.service.MainActivity
import com.naarni.service.R

/**
 * Chat notifications: one tray per conversation, styled as a conversation.
 *
 * Chat used the same code path as job-card alerts, which meant every message
 * produced a separate flat tray with no idea which conversation it belonged
 * to. `MessagingStyle` is what makes a run of messages read as a thread, gives
 * Android the sender names it needs for the conversation shade, and lets the
 * system show the reply field inline.
 *
 * The notification id is derived from the room, so a second message from the
 * same conversation *updates* its tray instead of stacking another one.
 */
object ChatNotifications {

    const val EXTRA_ROOM = "chat_room"
    const val KEY_REPLY = "chat_reply_text"

    /** The thread on screen right now, if any — set by the chat screen. */
    @Volatile
    var visibleRoom: String? = null

    /**
     * Messages already shown per room, so MessagingStyle can redraw the run.
     *
     * Bounded: a tray showing more than a handful of lines is unreadable, and
     * this is a cache for display only — Room remains the record.
     */
    private val history = mutableMapOf<String, MutableList<Line>>()

    /**
     * Everything touching [history] holds this.
     *
     * Three threads reach it: `show` on FCM's callback thread, `showSentReply`
     * on an IO thread from the reply receiver, and `clear` on the main thread
     * when a thread is opened. Unsynchronised, a reply typed while a push
     * landed for the same room could throw ConcurrentModificationException out
     * of the middle of building the tray — and that propagates out of
     * onMessageReceived, which takes the process down rather than losing a
     * notification.
     */
    private val lock = Any()

    @androidx.annotation.VisibleForTesting
    internal data class Line(
        val sender: String,
        val text: String,
        val at: Long,
        /** A staged `content://` photo shown inline under the line, if any. */
        val image: Uri? = null,
    )

    fun idFor(room: String): Int = ("chat:$room").hashCode()

    /**
     * Show (or extend) the tray for one conversation.
     *
     * Returns false when nothing was shown, which is the case that matters
     * most: a notification for the conversation the user is already looking at
     * is noise, and buzzing someone's pocket for a message they are watching
     * arrive is the fastest way to get notifications turned off entirely.
     */
    fun show(
        context: Context,
        room: String,
        roomTitle: String,
        authorName: String,
        body: String,
        mention: Boolean,
        whenMillis: Long = 0L,
        /** Server path of a photo to show inline, for an image message. */
        imagePath: String? = null,
    ): Boolean {
        if (room.isBlank()) return false
        if (room == visibleRoom) return false

        // Fetched before the tray is built, because a MessagingStyle image has
        // to be attached to its own line — there is no way to add it later
        // without redrawing the whole run. Null whenever anything goes wrong,
        // which downgrades this to the text notification it was before.
        val image = NotificationImages.stage(context, imagePath)

        val lines = record(room, Line(authorName.ifBlank { "Someone" }, body, whenMillis, image))

        val notification = build(
            context = context,
            room = room,
            roomTitle = roomTitle,
            lines = lines,
            priority = NotificationCompat.PRIORITY_HIGH,
            alertOnce = !mention,
            sender = Person.Builder().setName(authorName.ifBlank { "Someone" }).build(),
        )

        return runCatching {
            NotificationManagerCompat.from(context).notify(idFor(room), notification)
            true
        }.getOrElse {
            // POST_NOTIFICATIONS may be denied; fall back rather than crash the
            // FCM service, which would cost us the message entirely.
            runCatching {
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                    ?.notify(idFor(room), notification)
                true
            }.getOrDefault(false)
        }
    }

    /**
     * The one place a chat tray is assembled.
     *
     * There were two near-identical copies of this — the incoming-message one
     * and the sent-reply one — and they had already drifted: only the first
     * carried the conversation styling, so replying to a message demoted its
     * own notification out of the conversation section.
     */
    private fun build(
        context: Context,
        room: String,
        roomTitle: String,
        lines: List<Line>,
        priority: Int,
        alertOnce: Boolean,
        sender: Person?,
    ): android.app.Notification {
        val style = NotificationCompat.MessagingStyle(
            Person.Builder().setName("You").setKey(SELF).build(),
        ).setConversationTitle(roomTitle.takeIf { it.isNotBlank() })
            // Only a group needs sender names on every line; on a direct
            // message the title already says who it is.
            .setGroupConversation(true)

        lines.forEach { line ->
            val at = line.at.takeIf { it > 0 } ?: System.currentTimeMillis()
            val person = if (line.sender == SELF) null
            else Person.Builder().setName(line.sender).build()
            val message = NotificationCompat.MessagingStyle.Message(line.text, at, person)
            // The photo itself, rendered under the line by the shade. The
            // system takes its own read grant on URIs it finds in a posted
            // notification, which is why a non-exported FileProvider works here.
            line.image?.let { message.setData("image/jpeg", it) }
            style.addMessage(message)
        }

        // Ranked as a conversation when the platform allows it. Failure is
        // survivable: without a shortcut id the notification is an ordinary
        // alert, which is exactly what it was before.
        val shortcutId = ChatShortcuts.ensure(context, room, roomTitle, sender)

        val builder = NotificationCompat.Builder(context, App.CHANNEL_CHAT)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setStyle(style)
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, room))
            .setDeleteIntent(dismissIntent(context, room))
            .addAction(replyAction(context, room))
            .addAction(markReadAction(context, room))
            .setOnlyAlertOnce(alertOnce)

        if (shortcutId != null) {
            builder.setShortcutId(shortcutId)
            builder.setLocusId(LocusIdCompat(shortcutId))
        }

        // The newest photo doubles as the tray's collapsed thumbnail, so the
        // shade shows what arrived without being expanded.
        lines.lastOrNull { it.image != null }?.image?.let { uri ->
            runCatching { builder.setLargeIcon(IconCompat.createWithContentUri(uri).toIcon(context)) }
        }

        return builder.build()
    }

    /** Drop every cached run. Test seam only — the app clears per room. */
    @androidx.annotation.VisibleForTesting
    internal fun resetHistoryForTest() = synchronized(lock) { history.clear() }

    /** How many conversations are currently cached. Test seam only. */
    @androidx.annotation.VisibleForTesting
    internal fun cachedRoomCount(): Int = synchronized(lock) { history.size }

    /** Called once a reply is sent, or the thread is opened. */
    fun clear(context: Context, room: String) {
        synchronized(lock) { history.remove(room) }
        runCatching { NotificationManagerCompat.from(context).cancel(idFor(room)) }
    }

    /**
     * Append a line and hand back an immutable copy of the run to draw.
     *
     * The copy is the point: [build] iterates the result, and iterating the
     * live list would race every other thread that can append to it.
     */
    @androidx.annotation.VisibleForTesting
    internal fun record(room: String, line: Line): List<Line> = synchronized(lock) {
        val lines = history.getOrPut(room) { mutableListOf() }
        lines += line
        while (lines.size > MAX_LINES) lines.removeAt(0)
        // Rooms accumulate for the life of the process — one entry per room a
        // manager is pushed from and never opens — so the map is bounded too,
        // not just each list within it.
        if (history.size > MAX_ROOMS) {
            history.keys.take(history.size - MAX_ROOMS).forEach { history.remove(it) }
        }
        lines.toList()
    }

    /**
     * Appends the just-sent reply to the tray and leaves it up.
     *
     * Android keeps the notification on screen with a spinner until it is
     * either updated or cancelled; leaving it spinning is what makes inline
     * reply feel broken even when the message went through.
     */
    fun showSentReply(context: Context, room: String, roomTitle: String, text: String) {
        val lines = record(room, Line(SELF, text, System.currentTimeMillis()))

        val notification = build(
            context = context,
            room = room,
            roomTitle = roomTitle,
            lines = lines,
            // Low, and alert-once: the user is standing there having just typed
            // it. Buzzing them to confirm their own reply is noise.
            priority = NotificationCompat.PRIORITY_LOW,
            alertOnce = true,
            sender = null,
        )

        runCatching { NotificationManagerCompat.from(context).notify(idFor(room), notification) }
    }

    /**
     * Clear the conversation without opening it.
     *
     * The counterpart to replying: most notifications are read on the lock
     * screen and need nothing back. Without this, the only way to stop a room
     * badgering you is to open it, which then marks every message in it read —
     * so a glance at one message silently clears twenty others.
     */
    private fun markReadAction(context: Context, room: String): NotificationCompat.Action {
        val intent = Intent(context, ChatReplyReceiver::class.java).apply {
            action = ChatReplyReceiver.ACTION_MARK_READ
            putExtra(EXTRA_ROOM, room)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            idFor(room) + 3,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(R.drawable.ic_stat_notify, "Mark read", pending)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
    }

    private fun openIntent(context: Context, room: String): PendingIntent {
        // Encoded, because a Frappe docname is not guaranteed to be URL-safe and
        // a room whose name contains a slash would otherwise route to the wrong
        // place — or nowhere.
        val route = "naarni://chat/" + Uri.encode(room)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data = Uri.parse(route)
            putExtra("route", route)
        }
        return PendingIntent.getActivity(
            context,
            idFor(room),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun dismissIntent(context: Context, room: String): PendingIntent {
        val intent = Intent(context, ChatReplyReceiver::class.java).apply {
            action = ChatReplyReceiver.ACTION_DISMISS
            putExtra(EXTRA_ROOM, room)
        }
        return PendingIntent.getBroadcast(
            context,
            idFor(room) + 1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * The inline reply field.
     *
     * MUTABLE by necessity — RemoteInput writes the typed text into the intent,
     * which an immutable PendingIntent forbids. It is a broadcast to our own
     * non-exported receiver, so nothing outside the app can reach it.
     */
    private fun replyAction(context: Context, room: String): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(KEY_REPLY)
            .setLabel("Reply")
            .build()

        val intent = Intent(context, ChatReplyReceiver::class.java).apply {
            action = ChatReplyReceiver.ACTION_REPLY
            putExtra(EXTRA_ROOM, room)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            idFor(room) + 2,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        return NotificationCompat.Action.Builder(R.drawable.ic_stat_notify, "Reply", pending)
            .addRemoteInput(remoteInput)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setAllowGeneratedReplies(true)
            .setShowsUserInterface(false)
            .build()
    }

    private const val MAX_LINES = 6

    /** Conversations kept in the redraw cache. Beyond this the oldest are dropped. */
    private const val MAX_ROOMS = 12
    private const val SELF = " self"
}
