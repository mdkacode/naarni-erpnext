package com.naarni.service.core.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.content.edit
import com.naarni.service.R

/**
 * What the phone sounds like, and the one Android rule that shapes all of it.
 *
 * **A channel's sound is fixed when the channel is created.** `setSound` on an
 * existing channel is silently ignored — the platform treats the sound as the
 * user's to change, not the app's. Recreating the same channel id does not help
 * either: Android remembers a deleted channel's settings and restores them.
 *
 * So the tone is part of the channel *id*. Picking a new one creates a new
 * channel and deletes the old, which is why [applyTones] takes the whole
 * selection rather than mutating anything. The side effect is honest: choosing
 * a tone resets any per-channel tweaks the user made in system settings, and
 * there is no way to have both.
 *
 * Chat and alerts are separate channels for a reason worth keeping: a breakdown
 * at 2am must not sound like depot banter, and someone silencing the chatter
 * must not silence the thing that gets them out of bed.
 */
object NotificationTones {

    /** A tone we ship. `id` is what the server stores. */
    data class Bundled(val id: String, val label: String, val res: Int?)

    val BUNDLED = listOf(
        Bundled("default", "Naarni (default)", R.raw.msg_notify),
        Bundled("chime", "Chime", R.raw.tone_chime),
        Bundled("ping", "Ping", R.raw.tone_ping),
        Bundled("knock", "Knock", R.raw.tone_knock),
        Bundled("bell", "Bell", R.raw.tone_bell),
        Bundled("none", "Silent", null),
    )

    /** The alert default differs: an SLA breach gets the bell, not the message tone. */
    private val ALERT_DEFAULT_RES = R.raw.notify

    const val KIND_CHAT = "chat"
    const val KIND_ALERT = "alert"

    private const val PREFS = "naarni_tones"
    private const val KEY_CHAT = "chat_tone"
    private const val KEY_ALERT = "alert_tone"
    private const val KEY_VIBRATE = "vibrate"

    // ------------------------------------------------------------------ choice

    fun chatTone(context: Context): String = prefs(context).getString(KEY_CHAT, "default") ?: "default"

    fun alertTone(context: Context): String = prefs(context).getString(KEY_ALERT, "default") ?: "default"

    fun vibrate(context: Context): Boolean = prefs(context).getBoolean(KEY_VIBRATE, true)

    /**
     * Adopt a selection and rebuild the channels it names.
     *
     * Called after the profile loads (the server is the source of truth, so a
     * reinstall or a second handset inherits the choice) and again whenever the
     * person picks something in the app.
     */
    fun applyTones(context: Context, chat: String?, alert: String?, vibrate: Boolean?) {
        prefs(context).edit {
            chat?.let { putString(KEY_CHAT, it) }
            alert?.let { putString(KEY_ALERT, it) }
            vibrate?.let { putBoolean(KEY_VIBRATE, it) }
        }
        ensureChannels(context)
    }

    // ---------------------------------------------------------------- channels

    /** The live channel id for a kind. Notification builders must ask, never hardcode. */
    fun channelId(context: Context, kind: String): String =
        channelIdFor(kind, if (kind == KIND_CHAT) chatTone(context) else alertTone(context))

    private fun channelIdFor(kind: String, tone: String): String {
        // A system uri is long and contains characters that read badly in a
        // channel id, so it is folded to a stable hash. The id only has to be
        // unique and repeatable, never legible.
        val slug = if (tone.startsWith("system:")) "sys${tone.hashCode().toUInt()}" else tone
        return "${kind}_v3_$slug"
    }

    /**
     * Create the channels for the current selection and remove superseded ones.
     *
     * Sweeping the old ones matters: without it every tone a person has ever
     * tried stays in their system settings for ever as a dead row they can
     * toggle and get nothing from.
     */
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return

        val chatId = channelId(context, KIND_CHAT)
        val alertId = channelId(context, KIND_ALERT)

        mgr.createNotificationChannel(
            NotificationChannel(chatId, "Chat Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New messages in your depot and vehicle threads"
                enableVibration(vibrate(context))
                // Two short pulses rather than one long buzz: a message is a
                // different event from an SLA breach, and the pocket should be
                // able to tell them apart without the phone coming out.
                vibrationPattern = longArrayOf(0, 40, 90, 40)
                applySound(context, chatTone(context), R.raw.msg_notify)
            },
        )
        mgr.createNotificationChannel(
            NotificationChannel(alertId, "Alerts & Job Cards", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Assignments, approvals, SLA alerts and breakdowns"
                enableVibration(vibrate(context))
                vibrationPattern = longArrayOf(0, 200, 100, 200)
                applySound(context, alertTone(context), ALERT_DEFAULT_RES)
            },
        )

        val keep = setOf(chatId, alertId)
        mgr.notificationChannels
            .map { it.id }
            .filter { (it.startsWith("${KIND_CHAT}_v3_") || it.startsWith("${KIND_ALERT}_v3_")) && it !in keep }
            .forEach { runCatching { mgr.deleteNotificationChannel(it) } }
    }

    private fun NotificationChannel.applySound(context: Context, tone: String, fallback: Int) {
        if (tone == "none") {
            setSound(null, null)
            return
        }
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val uri = when {
            tone.startsWith("system:") -> runCatching { Uri.parse(tone.removePrefix("system:")) }.getOrNull()
            else -> BUNDLED.firstOrNull { it.id == tone }?.res?.let { resUri(context, it) }
        } ?: resUri(context, fallback)
        setSound(uri, attrs)
    }

    /** Preview uri for the picker — the same sound the channel will use. */
    fun previewUri(context: Context, tone: String): Uri? = when {
        tone == "none" -> null
        tone.startsWith("system:") -> runCatching { Uri.parse(tone.removePrefix("system:")) }.getOrNull()
        else -> BUNDLED.firstOrNull { it.id == tone }?.res?.let { resUri(context, it) }
    }

    fun labelFor(context: Context, tone: String): String = when {
        tone.startsWith("system:") -> "From this phone"
        else -> BUNDLED.firstOrNull { it.id == tone }?.label ?: "Naarni (default)"
    }

    private fun resUri(context: Context, res: Int): Uri =
        Uri.parse("android.resource://${context.packageName}/$res")

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
