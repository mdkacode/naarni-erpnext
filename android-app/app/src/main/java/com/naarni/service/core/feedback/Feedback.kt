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
