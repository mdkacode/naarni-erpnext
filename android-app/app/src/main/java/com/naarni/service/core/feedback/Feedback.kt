package com.naarni.service.core.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.naarni.service.R

/**
 * Tactile and audio feedback.
 *
 * Two principles, both learned the hard way from the previous version:
 *
 * **Every sound is its own sample.** The old implementation built the chat tones
 * by replaying one `tap.wav` at different playback rates, which shifts formants
 * along with pitch — the "sent" tick was an audibly chipmunked tap and "received"
 * was a muddy one. They are now eight purpose-built samples from one synthesised
 * family (see `tools/gen_ui_sounds.py`), which costs about 130 KB of APK and is
 * the difference between an app that sounds designed and one that sounds cheap.
 *
 * **Silence is respected.** Sound is skipped when the phone is on vibrate or
 * silent. `USAGE_ASSISTANCE_SONIFICATION` streams do *not* honour ringer mode on
 * their own, so a phone in a meeting was chiming for every message that arrived
 * — the single fastest way to get an app's audio switched off for good. The
 * haptic still fires, because that is what the user asked for by choosing
 * vibrate.
 *
 * Both halves fail silently if the hardware is missing.
 */
class Feedback(context: Context) {
    private val appContext = context.applicationContext

    private val vibrator: Vibrator? = run {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val soundPool: SoundPool = SoundPool.Builder()
        // Four, not three: a send, an arriving message, an upload completing and
        // a tap can genuinely overlap in a live thread, and the stream that gets
        // dropped when the pool is full is the one that was about to start.
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val tapId = soundPool.load(appContext, R.raw.ui_tap, 1)
    private val successId = soundPool.load(appContext, R.raw.ui_success, 1)
    private val errorId = soundPool.load(appContext, R.raw.ui_error, 1)
    private val sentId = soundPool.load(appContext, R.raw.msg_sent, 1)
    private val receivedId = soundPool.load(appContext, R.raw.msg_received, 1)
    private val recStartId = soundPool.load(appContext, R.raw.rec_start, 1)
    private val recCancelId = soundPool.load(appContext, R.raw.rec_cancel, 1)

    /** True when the user has asked, via the ringer switch, not to hear things. */
    private val audible: Boolean
        get() = audioManager?.ringerMode == AudioManager.RINGER_MODE_NORMAL

    /**
     * Volumes are relative to each other, and every one of them is low.
     *
     * These sounds play over whatever the user is already listening to, on a
     * phone that may be in a pocket next to a running engine. Loud does not make
     * them more useful, it makes them the reason someone turns the app's audio
     * off.
     */
    private fun play(id: Int, volume: Float) {
        if (!audible) return
        runCatching { soundPool.play(id, volume, volume, 1, 0, 1f) }
    }

    private fun vibrate(ms: Long, amplitude: Int = VibrationEffect.DEFAULT_AMPLITUDE) {
        val v = vibrator ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms, amplitude))
            } else {
                @Suppress("DEPRECATION") v.vibrate(ms)
            }
        }
    }

    private fun vibratePattern(timings: LongArray) {
        val v = vibrator ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(timings, -1))
            } else {
                @Suppress("DEPRECATION") v.vibrate(timings, -1)
            }
        }
    }

    // ── General ───────────────────────────────────────────────────────────

    /** A light tap — primary buttons, chips, selections. */
    fun tap() {
        play(tapId, 0.5f)
        vibrate(12, 80)
    }

    /** A success chime + double pulse — job created, saved, transition complete. */
    fun success() {
        play(successId, 0.85f)
        vibratePattern(longArrayOf(0, 18, 60, 28))
    }

    /**
     * A short buzz — errors, blocked actions.
     *
     * Now has a tone of its own. It was silent before, which meant a blocked
     * action and a successful one were indistinguishable to anyone who had the
     * phone in a pocket rather than in their hand.
     */
    fun error() {
        play(errorId, 0.7f)
        vibratePattern(longArrayOf(0, 35, 80, 35))
    }

    // ── Chat ──────────────────────────────────────────────────────────────

    /** Outgoing message committed to the outbox — a crisp rising tick. */
    fun messageSent() {
        play(sentId, 0.55f)
        vibrate(10, 60)
    }

    /** A message arrived while the thread is open — falling, softer, unobtrusive. */
    fun messageReceived() {
        play(receivedId, 0.45f)
        vibrate(14, 55)
    }

    /**
     * The swipe-to-reply gesture passing its trigger threshold.
     *
     * Silent on purpose. This fires mid-gesture, and a sound here would be
     * intolerable in a busy thread — the haptic alone is what confirms the
     * threshold, exactly as it does elsewhere in the app.
     */
    fun replyTriggered() {
        vibrate(16, 110)
    }

    /** Long-press entering selection mode — a deliberate, heavier press. */
    fun selectionEntered() {
        vibrate(22, 140)
    }

    /** Attachment finished uploading. */
    fun uploadComplete() {
        play(successId, 0.4f)
        vibrate(12, 70)
    }

    // ── Voice notes ───────────────────────────────────────────────────────

    /** The microphone is live. Rising, and firm enough to feel through a glove. */
    fun recordStart() {
        play(recStartId, 0.5f)
        vibrate(18, 130)
    }

    /**
     * The recording was thrown away — slid to cancel, or too short to be real.
     *
     * Falling, and distinct from [messageSent] on purpose: the one thing a user
     * must never be unsure about is whether the thing they just recorded went.
     */
    fun recordCancel() {
        play(recCancelId, 0.5f)
        vibratePattern(longArrayOf(0, 20, 55, 20))
    }

    /**
     * The slide-to-cancel gesture crossing its point of no return.
     *
     * Haptic only, and sharp. It fires while the finger is still down and the
     * decision is still reversible, so its whole job is to say "let go now and
     * this is gone" without adding a noise to a gesture already in progress.
     */
    fun cancelArmed() {
        vibrate(20, 160)
    }
}

val LocalFeedback = staticCompositionLocalOf<Feedback> {
    error("Feedback not provided")
}

/** Remembers a single [Feedback] for the current context. */
@Composable
fun rememberFeedback(): Feedback {
    val context = LocalContext.current
    return remember(context) { Feedback(context) }
}
