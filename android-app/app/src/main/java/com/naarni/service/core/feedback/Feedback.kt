package com.naarni.service.core.feedback

import android.content.Context
import android.media.AudioAttributes
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
 * Lightweight tactile + audio feedback used across the UI: a soft tap on primary
 * actions, a pleasant chime on success, and a buzz on error. Sound is subtle and
 * short; haptics use the device vibrator. Both fail silently if unavailable.
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

    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(3)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val tapId = soundPool.load(appContext, R.raw.tap, 1)
    private val successId = soundPool.load(appContext, R.raw.success_chime, 1)

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

    /** A light tap — primary buttons, chips, selections. */
    fun tap() {
        soundPool.play(tapId, 0.35f, 0.35f, 1, 0, 1f)
        vibrate(12, 80)
    }

    /** A success chime + double pulse — job created, saved, transition complete. */
    fun success() {
        soundPool.play(successId, 0.6f, 0.6f, 1, 0, 1f)
        vibratePattern(longArrayOf(0, 18, 60, 28))
    }

    /** A short buzz — errors / blocked actions. */
    fun error() {
        vibratePattern(longArrayOf(0, 35, 80, 35))
    }

    // ── Chat ──────────────────────────────────────────────────────────────
    // Deliberately built from the two existing samples at different playback
    // rates rather than shipping new audio: chat feedback fires far more often
    // than anything else in the app, so it has to stay light on the APK and
    // instantly familiar. Rate shifts read as "related but distinct".

    /** Outgoing message committed to the outbox — a crisp upward tick. */
    fun messageSent() {
        soundPool.play(tapId, 0.30f, 0.30f, 1, 0, 1.45f)
        vibrate(10, 60)
    }

    /** A message arrived while the thread is open — softer, lower, unobtrusive. */
    fun messageReceived() {
        soundPool.play(tapId, 0.22f, 0.22f, 0, 0, 0.75f)
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
        soundPool.play(successId, 0.4f, 0.4f, 0, 0, 1.15f)
        vibrate(12, 70)
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
