package com.naarni.service.ui.chat

import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.naarni.service.appContainer

/**
 * Full-screen video playback inside the media viewer.
 *
 * **Streamed through the app's own OkHttp client, never the framework's.**
 * Chat attachments are private files behind a Frappe session cookie, and the
 * platform `MediaPlayer` carries its own HTTP stack with no access to the
 * cookie jar — the exact failure that stopped voice notes playing, where the
 * server answered the media request with an HTML login page and the decoder
 * reported an unhelpful `error (-38, 0)`. Handing ExoPlayer an
 * [OkHttpDataSource] built on the shared client means playback is authenticated
 * the same way every other request in the app is.
 *
 * Streamed rather than downloaded-then-played, unlike voice notes: a voice note
 * is tens of kilobytes and arrives instantly, while a video is tens of
 * megabytes and waiting for all of it before the first frame would make a clip
 * feel broken on the connections these phones have.
 */
@UnstableApi
@Composable
fun VideoPage(
    model: Any?,
    active: Boolean,
    onChromeChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val uri = remember(model) { model?.toString() }

    val player = remember(uri) {
        val http = OkHttpDataSource.Factory(context.appContainer.httpClient)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                // DefaultDataSource wraps the HTTP factory so that a local
                // outbox file — a clip still uploading — plays from disk while
                // anything remote goes over the authenticated client.
                DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)),
            )
            .build()
            .apply {
                if (uri != null) {
                    setMediaItem(MediaItem.fromUri(uri))
                    prepare()
                }
                // Looping is wrong here: these are evidence clips, and a
                // ten-second loop of a wheel arch running unattended is
                // distracting rather than useful.
                repeatMode = ExoPlayer.REPEAT_MODE_OFF
            }
    }

    // Only the page actually on screen plays. Without this the pager's
    // neighbours — which stay composed — would play their audio underneath.
    //
    // Keyed on the player as well as on `active`. `player` is rebuilt whenever
    // the model changes, and it does change mid-playback: watch a clip you have
    // just sent and the delta sync replaces the row's local outbox path with
    // the server URL. Keyed on `active` alone the effect would not re-run, the
    // fresh player would sit at position zero with playWhenReady false, and
    // playback would simply stop dead.
    LaunchedEffect(player, active) {
        if (active) player.play() else player.pause()
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setBackgroundColor(android.graphics.Color.BLACK)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    // The viewer's header follows the transport controls
                    // rather than fighting them: one tap reveals both, the next
                    // hides both. Two independently-toggling overlays over the
                    // same video is the kind of thing that reads as a bug.
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            onChromeChange(visibility == android.view.View.VISIBLE)
                        },
                    )
                }
            },
            update = { view -> view.player = player },
        )
    }
}
