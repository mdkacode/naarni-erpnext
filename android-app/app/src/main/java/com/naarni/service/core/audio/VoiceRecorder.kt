package com.naarni.service.core.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File

/**
 * Records a voice note.
 *
 * AAC in an MP4 container, mono at 16 kHz — speech, not music. A depot voice
 * note is someone talking next to a running bus, and the difference between
 * this and 44.1 kHz stereo is inaudible while being roughly a tenth of the
 * bytes, which is what decides whether it sends at all on a bad uplink.
 *
 * The recorder is deliberately not a singleton: it holds a native resource that
 * must be released, and one instance per recording makes the lifetime obvious.
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var output: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recorder != null

    /** Begins recording; returns false if the device refused (mic busy, no permission). */
    fun start(): Boolean {
        if (recorder != null) return true
        val dir = File(context.cacheDir, "voice_notes").apply { mkdirs() }
        val file = File(dir, "vn_${System.currentTimeMillis()}.m4a")

        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        return runCatching {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioChannels(1)
            rec.setAudioSamplingRate(16_000)
            rec.setAudioEncodingBitRate(24_000)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            output = file
            startedAt = SystemClock.elapsedRealtime()
            true
        }.getOrElse {
            runCatching { rec.release() }
            file.delete()
            false
        }
    }

    /** Live length, for the timer next to the record button. */
    fun elapsedMs(): Long =
        if (startedAt == 0L) 0L else SystemClock.elapsedRealtime() - startedAt

    /**
     * Stops and returns the finished note, or null if it was too short to be
     * anything but an accidental tap on the mic.
     *
     * `stop()` throws when the recorder is stopped before it has written a
     * usable frame — a real case on a fast tap — and that must not surface as a
     * crash, so the file is discarded instead.
     */
    fun stop(minMs: Long = MIN_MS): Recording? {
        val rec = recorder ?: return null
        val file = output
        val ms = elapsedMs()
        recorder = null
        output = null
        startedAt = 0L

        val ok = runCatching { rec.stop() }.isSuccess
        runCatching { rec.release() }

        if (!ok || file == null || !file.exists() || file.length() == 0L || ms < minMs) {
            file?.delete()
            return null
        }
        return Recording(file, ms)
    }

    /** Abandons the recording and deletes the file. */
    fun cancel() {
        val rec = recorder ?: return
        val file = output
        recorder = null
        output = null
        startedAt = 0L
        runCatching { rec.stop() }
        runCatching { rec.release() }
        file?.delete()
    }

    data class Recording(val file: File, val durationMs: Long)

    companion object {
        /** Below this it is a mis-tap, not a message. */
        const val MIN_MS = 600L
    }
}
