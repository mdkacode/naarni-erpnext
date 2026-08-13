package com.naarni.service.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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

    /** 0f..1f through the current note. */
    var progress by mutableStateOf(0f)
        private set

    private var player: MediaPlayer? = null

    /** Toggle: playing the note that is already playing stops it. */
    fun toggle(context: Context, id: String, source: String) {
        if (playingId == id) {
            stop()
            return
        }
        stop()

        val mp = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
        }

        val started = runCatching {
            if (source.startsWith("http")) mp.setDataSource(source)
            else mp.setDataSource(context, android.net.Uri.parse("file://$source"))
            mp.setOnCompletionListener { stop() }
            mp.setOnErrorListener { _, _, _ -> stop(); true }
            // Prepared asynchronously: a note streamed from the server over a
            // depot link would otherwise block the main thread until it buffers.
            mp.setOnPreparedListener { it.start() }
            mp.prepareAsync()
            true
        }.getOrDefault(false)

        if (!started) {
            runCatching { mp.release() }
            return
        }
        player = mp
        playingId = id
        progress = 0f
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
        val mp = player
        player = null
        playingId = null
        progress = 0f
        runCatching { mp?.stop() }
        runCatching { mp?.release() }
    }
}
