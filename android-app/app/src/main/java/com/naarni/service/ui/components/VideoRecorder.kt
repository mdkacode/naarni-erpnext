package com.naarni.service.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.naarni.service.core.media.VideoCompressor
import kotlinx.coroutines.delay
import java.io.File

/**
 * Full-screen video capture for chat.
 *
 * **Recorded small rather than shrunk afterwards.** The recorder is pinned to
 * 720p and a ~90-second ceiling, so what lands on disk is already close to what
 * goes over the wire. Transcoding a 4K clip down costs a minute of CPU and a
 * generation of quality; never producing one costs nothing. The compressor
 * still exists for clips picked out of the gallery, which arrive at whatever
 * size the phone's own camera app chose.
 *
 * Unlike [StampingCamera] the frames are not stamped. Burning a legible overlay
 * into every frame needs a video effect pipeline, and a stamp that is only on
 * the first frame is worse than none — it implies the whole clip is covered.
 * The message itself still carries who sent it and when.
 */
@Composable
fun VideoRecorderScreen(
    onRecorded: (File, Long) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    /**
     * Audio is part of the point — a technician narrating what the camera is
     * looking at is most of a clip's value — so it is requested up front rather
     * than at the moment of recording.
     */
    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    var hasCamera by remember { mutableStateOf(granted(Manifest.permission.CAMERA)) }
    var hasMic by remember { mutableStateOf(granted(Manifest.permission.RECORD_AUDIO)) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        hasCamera = result[Manifest.permission.CAMERA] ?: hasCamera
        hasMic = result[Manifest.permission.RECORD_AUDIO] ?: hasMic
    }

    LaunchedEffect(Unit) {
        if (!hasCamera || !hasMic) {
            permLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
            )
        }
    }

    if (!hasCamera) {
        // Back has to be caught here. This screen is drawn inside the thread, so
        // an uncaught Back pops the whole conversation and dumps the user at the
        // list — and for somebody who has permanently denied the camera, the
        // grant button does nothing visible, because the system shows no dialog
        // the second time. Without a way out this state is a trap.
        BackHandler { onClose() }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Camera access is needed to record a video.")
                Text(
                    "If nothing happens, turn it on in Settings › Apps › NaArNi Care › Permissions.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp, start = 24.dp, end = 24.dp),
                )
                Button(
                    onClick = {
                        permLauncher.launch(
                            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                        )
                    },
                    modifier = Modifier.padding(top = 12.dp),
                ) { Text("Grant access") }
                TextButton(onClick = onClose, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Close")
                }
            }
        }
        return
    }

    val recorder = remember {
        Recorder.Builder()
            // HD is 720p. FallbackStrategy matters on the cheap handsets this
            // ships to: several do not offer HD on the back camera at all, and
            // without a fallback binding simply fails and the screen is black.
            .setQualitySelector(
                QualitySelector.from(
                    Quality.HD,
                    androidx.camera.video.FallbackStrategy.lowerQualityOrHigherThan(Quality.HD),
                ),
            )
            // Without this the encoder takes the device's CamcorderProfile
            // bitrate for 720p, which is 8–12 Mbit/s — a ninety-second clip
            // lands at 100 MB and the whole point of recording at 720p is lost
            // on the upload. Matched to what the gallery transcoder targets so
            // a recorded clip and a picked one cost the same to send.
            .setTargetVideoEncodingBitRate(TARGET_BITRATE)
            // Deliberately NOT setExecutor(): the Recorder keeps encoding and
            // muxing on whatever executor it is given, and this composable used
            // to shut that executor down on dispose. CameraX then rejected the
            // teardown work — including the muxer's final write — which left
            // unplayable files behind and threw on the main thread the next
            // time anything unbound the still-attached use case. The default
            // executor outlives the screen, which is what this needs.
            .build()
    }
    val videoCapture = remember { VideoCapture.withOutput(recorder) }

    var recording by remember { mutableStateOf<Recording?>(null) }
    var seconds by remember { mutableIntStateOf(0) }
    var target by remember { mutableStateOf<File?>(null) }

    // The clock is driven here rather than off recording events, because
    // RecordEvent.Status arrives on the encoder's own schedule and stutters
    // visibly when the encoder is busy — which is exactly while recording.
    LaunchedEffect(recording) {
        if (recording == null) return@LaunchedEffect
        seconds = 0
        while (seconds < MAX_SECONDS) {
            delay(1000)
            seconds += 1
        }
        // The ceiling is enforced by stopping, not by refusing to start. A clip
        // cut off at ninety seconds is still a usable clip; one that never
        // started because someone held the button too long is not.
        recording?.stop()
    }

    // Held so teardown can release the camera. bindToLifecycle attaches to the
    // *thread screen's* lifecycle owner, not to this composable, so without an
    // explicit unbind the camera stays open — green privacy dot lit, battery
    // burning, unavailable to other apps — for as long as the conversation
    // remains on the back stack after this screen closes.
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            recording?.stop()
            provider?.unbindAll()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    val cameraProvider = providerFuture.get()
                    provider = cameraProvider
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        videoCapture,
                    )
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
        )

        if (recording != null) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 56.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xCCE53935))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
                Text(
                    "%d:%02d".format(seconds / 60, seconds % 60),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 44.dp, start = 24.dp, end = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Button(onClick = { recording?.stop(); onClose() }) { Text("Close") }

            // One disc, always the same size, so the hit target does not shrink
            // out from under a thumb the moment recording starts. Only the inner
            // shape changes: a circle to start, a square to stop.
            Box(
                Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable {
                        val active = recording
                        if (active != null) {
                            active.stop()
                        } else {
                            // cacheDir, not filesDir/chat_outbox, matching the
                            // photo and voice-note capture paths. The repository
                            // copies whatever it is handed into the outbox under
                            // its own client id and deletes only that copy, so a
                            // recording written straight into the outbox is a
                            // second file nothing ever cleans up — around 25 MB
                            // per clip, in storage the OS cannot reclaim.
                            val dir = File(context.cacheDir, "video_capture").apply { mkdirs() }
                            val file = File(dir, "video_${System.currentTimeMillis()}.mp4")
                            target = file
                            val pending = recorder
                                .prepareRecording(context, FileOutputOptions.Builder(file).build())
                                .apply { if (hasMic) withAudioEnabled() }
                            recording = pending.start(
                                ContextCompat.getMainExecutor(context),
                            ) { event ->
                                if (event is VideoRecordEvent.Finalize) {
                                    val done = target
                                    recording = null
                                    // A stop we asked for finalises cleanly;
                                    // anything else leaves a file not worth
                                    // sending. Hitting the ceiling counts as
                                    // clean — it stops the same way.
                                    if (done != null && !event.hasError() && done.length() > 0) {
                                        // Read out of the file rather than off
                                        // the on-screen counter: that counter
                                        // only ticks once a second, so a clip
                                        // stopped at 800 ms was being sent as
                                        // "0:00" and every other one was
                                        // rounded down by up to a second.
                                        val length = VideoCompressor.durationMs(done)
                                            ?: (seconds * 1000L)
                                        onRecorded(done, length)
                                        onClose()
                                    } else if (done != null) {
                                        // Nothing downstream will ever see this
                                        // file, so it is ours to remove.
                                        done.delete()
                                    }
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(if (recording == null) 62.dp else 28.dp)
                        .clip(if (recording == null) CircleShape else RoundedCornerShape(6.dp))
                        .background(Color(0xFFE53935)),
                )
            }

            // Balances the row so the shutter sits centred.
            Box(Modifier.size(64.dp))
        }
    }
}

/** Encoder ceiling, matched to VideoCompressor so both paths cost the same to send. */
private const val TARGET_BITRATE = VideoCompressor.TARGET_BITRATE

/** ~90 seconds. Long enough to walk around a bus; short enough to send. */
private const val MAX_SECONDS = 90
