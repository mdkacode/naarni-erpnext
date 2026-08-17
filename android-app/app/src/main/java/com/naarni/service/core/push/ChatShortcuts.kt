package com.naarni.service.core.push

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.naarni.service.MainActivity
import com.naarni.service.R

/**
 * The long-lived shortcut behind each conversation.
 *
 * Android 11 split the shade in two: notifications that name a long-lived
 * conversation shortcut go in the **conversation section** at the top, with the
 * sender's avatar, and can be marked Priority or opened as a bubble by the
 * user. Everything else — however carefully it is styled as a message — falls
 * into the generic alerting section below.
 *
 * `MessagingStyle` alone was never enough for that. The shortcut is the missing
 * half, and it is why chat messages have been sitting in the same part of the
 * shade as an SLA breach.
 *
 * Shortcuts are also what let a user pin a depot thread to their launcher.
 */
object ChatShortcuts {

    /**
     * The platform caps dynamic shortcuts, and silently drops pushes past the
     * limit. Kept well under any device's ceiling so a manager in forty rooms
     * still gets conversation treatment for the ones they are actually in.
     */
    private const val MAX_SHORTCUTS = 6

    /**
     * Publish (or refresh) the shortcut for a room. Returns its id, or null if
     * the platform refused it — in which case the notification simply goes out
     * without conversation treatment rather than not at all.
     */
    fun ensure(context: Context, room: String, title: String, sender: Person?): String? {
        if (room.isBlank()) return null
        return runCatching {
            val label = title.ifBlank { "Conversation" }
            // An explicit ACTION_VIEW intent is mandatory: pushDynamicShortcut
            // throws without one, and the same deep link the notification uses
            // keeps a tapped shortcut and a tapped notification landing in the
            // same place.
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("naarni://chat/" + Uri.encode(room))
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val builder = ShortcutInfoCompat.Builder(context, room)
                .setShortLabel(label.take(24))
                .setLongLabel(label.take(64))
                .setIntent(intent)
                // Long-lived is the part that matters: the system keeps caching
                // it after it is removed from the dynamic list, which is what
                // lets a conversation stay ranked and stay Priority.
                .setLongLived(true)
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setCategories(setOf(CATEGORY_CONVERSATION))

            if (sender != null) builder.setPerson(sender)

            trim(context)
            ShortcutManagerCompat.pushDynamicShortcut(context, builder.build())
            room
        }.getOrNull()
    }

    /** Drop the oldest dynamic shortcuts so a push is never refused for space. */
    private fun trim(context: Context) {
        runCatching {
            val existing = ShortcutManagerCompat.getDynamicShortcuts(context)
            if (existing.size < MAX_SHORTCUTS) return
            val doomed = existing
                .sortedBy { it.lastChangedTimestamp }
                .take(existing.size - MAX_SHORTCUTS + 1)
                .map { it.id }
            ShortcutManagerCompat.removeLongLivedShortcuts(context, doomed)
        }
    }

    /**
     * Required for the shade to treat the shortcut as a conversation. Declared
     * by string rather than the constant, which is only on the framework class
     * and not mirrored on the compat one.
     */
    private const val CATEGORY_CONVERSATION = "android.shortcut.conversation"
}
