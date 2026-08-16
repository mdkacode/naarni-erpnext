package com.naarni.service.core.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Getting a video down to something a depot can actually send.
 *
 * A minute of 4K off a modern phone is 300–400 MB. On the connections these
 * handsets have that is not a slow upload, it is one that never finishes: the
 * worker retries, the battery drains, and the clip is still sitting in the
 * outbox the next morning. Re-encoded to 720p it is 10–15 MB — the same
 * evidence, over in under a minute.
 *
 * Everything here is arranged around one rule: **the original is never lost.**
 * Transcoding is an optimisation, and a failed optimisation must degrade to
 * sending the file as it was, not to losing the recording. Every failure path
 * returns the source untouched.
 */
@UnstableApi
object VideoCompressor {

    /**
     * Target height. 720p is the point where a face is recognisable and a
     * number plate is legible in the middle distance; 1080p roughly doubles the
     * bytes for detail nobody is reading off a phone screen.
     */
    const val TARGET_HEIGHT = 720

    /**
     * ~2.5 Mbit/s. Enough for the camera pans and vibration that dominate
     * hand-held depot footage, which is exactly the content that falls apart at
     * lower bitrates.
     */
    const val TARGET_BITRATE = 2_500_000

    /**
     * Below this a clip is sent as it is.
     *
     * Transcoding costs a minute of CPU and a generation of quality. A short
     * clip already under the threshold is not the problem the compressor exists
     * to solve, and running it anyway would make a 6 MB file worse to save
     * perhaps two.
     */
    private const val SKIP_UNDER_BYTES = 8L * 1024 * 1024

    /**
     * Compress [source] if it is worth doing. Returns the file to actually send.
     *
     * Suspends for as long as the export takes — on the order of real time on a
     * mid-range handset — so callers must show something while it runs. The work
     * happens off the caller's thread, but a two-minute clip is a two-minute
     * wait, and pretending otherwise produces a UI that looks hung.
     */
    suspend fun compress(context: Context, source: File): File {
        if (source.length() < SKIP_UNDER_BYTES) return source
        // Already at or below target: re-encoding could only take quality away.
        if (shortEdge(source) in 1..TARGET_HEIGHT) return source

        val output = File(source.parentFile, "${source.nameWithoutExtension}_720.mp4")
        output.delete()

        return runCatching { transcode(context, source, output) }
            .getOrElse {
                output.delete()
                source
            }
    }

    private suspend fun transcode(context: Context, source: File, output: File): File {
        // Transformer must be built and driven from a thread with a Looper; it
        // does the actual encoding on its own internal threads.
        val ok = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source)))
                    // Presentation scales to the target height and leaves the
                    // aspect ratio alone, so a portrait clip stays portrait
                    // rather than being letterboxed into a landscape frame.
                    .setEffects(
                        Effects(emptyList(), listOf(Presentation.createForHeight(TARGET_HEIGHT))),
                    )
                    .build()

                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(
                        DefaultEncoderFactory.Builder(context)
                            .setRequestedVideoEncoderSettings(
                                VideoEncoderSettings.Builder().setBitrate(TARGET_BITRATE).build(),
                            )
                            // Fall back to whatever the device will actually
                            // encode rather than failing the export. Handsets
                            // vary wildly in what their encoders accept, and a
                            // clip sent at the wrong bitrate beats no clip.
                            .setEnableFallback(true)
                            .build(),
                    )
                    .addListener(
                        object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, result: ExportResult) {
                                if (cont.isActive) cont.resume(true)
                            }

                            override fun onError(
                                composition: Composition,
                                result: ExportResult,
                                exception: ExportException,
                            ) {
                                if (cont.isActive) cont.resume(false)
                            }
                        },
                    )
                    .build()

                cont.invokeOnCancellation { runCatching { transformer.cancel() } }
                transformer.start(item, output.absolutePath)
            }
        }

        // A transcode that produced something *larger* is one worth throwing
        // away; it happens with sources that were already efficiently encoded.
        return if (ok && output.length() in 1 until source.length()) {
            source.delete()
            output
        } else {
            output.delete()
            source
        }
    }

    /**
     * Shortest edge of the video, or 0 if it cannot be read.
     *
     * Shortest rather than height, because a portrait clip reports its height as
     * the long side — measuring that would wave through every portrait 4K video
     * on the grounds that it is "taller than 720".
     */
    private fun shortEdge(file: File): Int = readMetadata(file) { mmr ->
        val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        minOf(w, h)
    } ?: 0

    /** Duration in milliseconds, or null. Used for the bubble's length label. */
    fun durationMs(file: File): Long? = readMetadata(file) { mmr ->
        mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    }

    /**
     * `MediaMetadataRetriever` is only `AutoCloseable` from API 29, and this app
     * ships to API 24 — hence the hand-written release rather than `use`.
     */
    private fun <T> readMetadata(file: File, read: (MediaMetadataRetriever) -> T?): T? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            read(mmr)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }
}
