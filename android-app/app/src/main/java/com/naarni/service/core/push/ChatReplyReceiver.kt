package com.naarni.service.core.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.naarni.service.appContainer
import com.naarni.service.core.chat.ChatWork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Sends a reply typed straight into the notification.
 *
 * The reply takes the *same* path as one typed in the app: queued into Room and
 * handed to WorkManager. That matters more than it looks — it means a reply
 * written in a lift with no signal is not lost, it is retried like any other
 * message, and it is idempotent on the client id if the retry duplicates.
 * Posting it directly over HTTP from here would be the one send in the app that
 * silently fails when the network does.
 */
class ChatReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val room = intent.getStringExtra(ChatNotifications.EXTRA_ROOM).orEmpty()
        if (room.isBlank()) return

        when (intent.action) {
            ACTION_DISMISS -> {
                ChatNotifications.clear(context, room)
                return
            }

            ACTION_MARK_READ -> {
                ChatNotifications.clear(context, room)
                val result = goAsync()
                val app = context.applicationContext
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        val container = app.appContainer
                        if (!container.session.isLoggedIn) return@launch
                        // Read up to what we actually hold. Marking the room's
                        // server high-water mark instead would clear messages
                        // this device has never seen, and they would never show
                        // as unread again.
                        val held = container.chatRepo.highestSeq(room)
                        if (held > 0) container.chatRepo.markRead(room, held)
                    } catch (_: Exception) {
                        // The tray is already gone; a failed cursor update just
                        // means the badge reappears on the next sync.
                    } finally {
                        result.finish()
                    }
                }
                return
            }

            ACTION_REPLY -> Unit
            else -> return
        }

        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(ChatNotifications.KEY_REPLY)
            ?.toString()
            ?.trim()
            .orEmpty()

        if (text.isEmpty()) {
            ChatNotifications.clear(context, room)
            return
        }

        // goAsync keeps the receiver alive past onReceive; a broadcast receiver
        // is otherwise killable the instant this method returns, which would
        // cut the database write off halfway.
        val result = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val container = app.appContainer
                val session = container.session
                if (!session.isLoggedIn) return@launch

                val clientId = container.chatRepo.queueText(
                    room = room,
                    body = text,
                    author = session.user.orEmpty(),
                    authorName = session.fullName ?: session.user.orEmpty(),
                    replyTo = null,
                    mentions = emptyList(),
                )
                ChatWork.enqueueText(app, clientId)

                val title = runCatching { container.chatRepo.roomTitle(room) }.getOrNull().orEmpty()
                ChatNotifications.showSentReply(app, room, title, text)
            } catch (_: Exception) {
                // The tray is left alone on failure: the message is already in
                // the outbox and WorkManager will retry it, so telling the user
                // it failed would be wrong.
            } finally {
                result.finish()
            }
        }
    }

    companion object {
        const val ACTION_REPLY = "com.naarni.service.CHAT_REPLY"
        const val ACTION_DISMISS = "com.naarni.service.CHAT_DISMISS"
        const val ACTION_MARK_READ = "com.naarni.service.CHAT_MARK_READ"
    }
}
