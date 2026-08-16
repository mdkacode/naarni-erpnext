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

    private data class Line(val sender: String, val text: String, val at: Long)

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
    ): Boolean {
        if (room.isBlank()) return false
        if (room == visibleRoom) return false

        val lines = history.getOrPut(room) { mutableListOf() }
        lines += Line(authorName.ifBlank { "Someone" }, body, whenMillis)
        while (lines.size > MAX_LINES) lines.removeAt(0)

        val style = NotificationCompat.MessagingStyle(
            Person.Builder().setName("You").build(),
        ).setConversationTitle(roomTitle.takeIf { it.isNotBlank() })
            // Only a group needs sender names on every line; on a direct
            // message the title already says who it is.
            .setGroupConversation(true)

        lines.forEach { line ->
            style.addMessage(
                line.text,
                line.at.takeIf { it > 0 } ?: System.currentTimeMillis(),
                Person.Builder().setName(line.sender).build(),
            )
        }

        val notification = NotificationCompat.Builder(context, App.CHANNEL_CHAT)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setStyle(style)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, room))
            .setDeleteIntent(dismissIntent(context, room))
            .addAction(replyAction(context, room))
            .setOnlyAlertOnce(!mention)
            .build()

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

    /** Called once a reply is sent, or the thread is opened. */
    fun clear(context: Context, room: String) {
        history.remove(room)
        runCatching { NotificationManagerCompat.from(context).cancel(idFor(room)) }
    }

    /**
     * Appends the just-sent reply to the tray and leaves it up.
     *
     * Android keeps the notification on screen with a spinner until it is
     * either updated or cancelled; leaving it spinning is what makes inline
     * reply feel broken even when the message went through.
     */
    fun showSentReply(context: Context, room: String, roomTitle: String, text: String) {
        val lines = history.getOrPut(room) { mutableListOf() }
        lines += Line(SELF, text, System.currentTimeMillis())

        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
            .setConversationTitle(roomTitle.takeIf { it.isNotBlank() })
            .setGroupConversation(true)
        lines.forEach { line ->
            if (line.sender == SELF) {
                style.addMessage(line.text, line.at, null as Person?)
            } else {
                style.addMessage(line.text, line.at, Person.Builder().setName(line.sender).build())
            }
        }

        val notification = NotificationCompat.Builder(context, App.CHANNEL_CHAT)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setStyle(style)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, room))
            .setDeleteIntent(dismissIntent(context, room))
            .addAction(replyAction(context, room))
            .setOnlyAlertOnce(true)
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(idFor(room), notification) }
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
    private const val SELF = " self"
}
