package com.naarni.service.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.naarni.service.appContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * Plays voice notes, one at a time.
 *
 * Process-wide on purpose. Each bubble owning its own MediaPlayer is how you
 * end up with three voice notes talking over each other in a depot thread, and
 * with players left holding an audio focus nobody released. One player means
 * starting a note necessarily stops the previous one.
 *
 * State is Compose-observable so the playing bubble can show a pause icon and a
 * progress line without polling from the UI layer.
 */
object VoicePlayer {

    /** Client id of the note currently playing, or null. */
    var playingId by mutableStateOf<String?>(null)
        private set

    /** Client id of the note being fetched, so its bubble can show a spinner. */
    var loadingId by mutableStateOf<String?>(null)
        private set

    /** 0f..1f through the current note. */
    var progress by mutableStateOf(0f)
        private set

    /**
     * Playback rate, remembered across notes.
     *
     * Deliberately sticky and process-wide. Someone working through a backlog of
     * voice notes wants to hear all of them at their chosen speed, and a control
     * that resets to 1× for every bubble is one they will press once and never
     * find useful. The cycle stops at 2× because AAC speech beyond that stops
     * being comprehensible.
     */
    var speed by mutableStateOf(1f)
        private set

    /** Cycles 1× → 1.5× → 2× → 1×, applying immediately if something is playing. */
    fun cycleSpeed() {
        speed = when {
            speed < 1.25f -> 1.5f
            speed < 1.75f -> 2f
            else -> 1f
        }
        val mp = player ?: return
        // Only legal while started; setting it on a paused or prepared player
        // throws, and on some OEM builds silently restarts playback.
        runCatching {
            if (mp.isPlaying) mp.playbackParams = mp.playbackParams.setSpeed(speed)
        }
    }

    private var player: MediaPlayer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    /**
     * Called when a note reaches its end, with the id that finished.
     *
     * The hook exists so the thread screen can play the next voice note in the
     * run without this object having to know what a conversation is — someone
     * catching up on six notes from a breakdown should not have to tap six
     * times, and the alternative was teaching the player to read Room.
     *
     * Set by the screen while it is composed and cleared when it leaves, so a
     * note finishing in the background falls back to simply stopping.
     */
    var onFinished: ((String) -> Unit)? = null

    /** Toggle: playing the note that is already playing stops it. */
    fun toggle(context: Context, id: String, source: String) {
        if (playingId == id || loadingId == id) {
            stop()
            return
        }
        stop()

        loadingId = id
        job = scope.launch {
            // A note on the server lives under /private/files/ and needs the
            // session cookie. MediaPlayer has its own HTTP stack that knows
            // nothing about our cookie jar, so pointing it at the URL fetched
            // Frappe's error page instead — it arrived as application/octet-
            // stream and died with "error (-38, 0)". Fetching it through the
            // app's authenticated client first is the only reliable route, and
            // it leaves the note cached for the next play.
            val file = if (source.startsWith("http")) {
                withContext(Dispatchers.IO) { cache(context, source) }
            } else {
                File(source).takeIf { it.exists() }
            }

            if (file == null) {
                loadingId = null
                return@launch
            }
            start(context, id, file)
        }
    }

    private fun start(context: Context, id: String, file: File) {
        val mp = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
        }
        val ok = runCatching {
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener { onFinished?.invoke(id) ?: stop() }
            mp.setOnErrorListener { _, _, _ -> stop(); true }
            mp.setOnPreparedListener {
                it.start()
                // After start(), never before: setting playbackParams on a
                // prepared-but-not-started player begins playback on most
                // devices, which produced a note that played twice.
                if (speed != 1f) {
                    runCatching { it.playbackParams = it.playbackParams.setSpeed(speed) }
                }
            }
            mp.prepareAsync()
            true
        }.getOrDefault(false)

        loadingId = null
        if (!ok) {
            runCatching { mp.release() }
            return
        }
        player = mp
        playingId = id
        progress = 0f
    }

    /**
     * Downloads a note into the cache, once.
     *
     * Streamed to a `.part` file and renamed only when complete, so an
     * interrupted download can never be mistaken for a playable cache entry —
     * a truncated m4a fails in exactly the confusing way this method exists to
     * prevent.
     */
    private fun cache(context: Context, url: String): File? {
        val dir = File(context.cacheDir, "voice_cache").apply { mkdirs() }
        val dest = File(dir, url.substringAfterLast('/').take(64).ifBlank { "note.m4a" })
        if (dest.exists() && dest.length() > 0) return dest

        return runCatching {
            val response = context.appContainer.httpClient
                .newCall(Request.Builder().url(url).build())
                .execute()
            response.use {
                if (!it.isSuccessful) return null
                val body = it.body ?: return null
                val partial = File(dest.absolutePath + ".part")
                body.byteStream().use { input ->
                    partial.outputStream().use { out -> input.copyTo(out) }
                }
                partial.renameTo(dest)
            }
            dest.takeIf { it.exists() && it.length() > 0 }
        }.getOrNull()
    }

    /** Called on a ticker while something is playing. */
    fun tick() {
        val mp = player ?: return
        val total = runCatching { mp.duration }.getOrDefault(0)
        if (total <= 0) return
        val at = runCatching { mp.currentPosition }.getOrDefault(0)
        progress = (at.toFloat() / total).coerceIn(0f, 1f)
    }

    fun stop() {
        job?.cancel()
        job = null
        val mp = player
        player = null
        playingId = null
        loadingId = null
        progress = 0f
        runCatching { mp?.stop() }
        runCatching { mp?.release() }
    }
}
