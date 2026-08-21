package com.naarni.service.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.util.Size as CameraSize
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.naarni.service.core.feedback.LocalFeedback
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.util.concurrent.Executors
import com.naarni.service.ui.theme.Radii
import com.naarni.service.ui.theme.Semantic

/**
 * Full-screen QR and barcode scanner.
 *
 * The camera fills the whole screen rather than sitting in a card. A viewfinder
 * inset into a preview is a smaller target for the operator to aim, and on a
 * plant floor the thing being scanned is often on the underside of a pack at
 * arm's length — every pixel of preview is a pixel of aiming margin.
 *
 * **The decoder is the bundled ML Kit model, not the Play-Services variant.** A
 * plant floor is exactly where you cannot assume Google Play Services are
 * installed or that the network is up to fetch a model on first use, and a
 * scanner that works everywhere except the factory is not a scanner.
 *
 * Typing is always offered. Labels get scratched, greasy and heat-damaged, and a
 * scan-only step would stop an inspection over a damaged sticker — which is the
 * opposite of what these checks exist to do.
 */
@Composable
fun BarcodeScannerScreen(
    title: String,
    hint: String? = null,
    onScanned: (String) -> Unit,
    onManualEntry: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val feedback = LocalFeedback.current

    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { hasCamera = it }
    LaunchedEffect(Unit) { if (!hasCamera) permission.launch(Manifest.permission.CAMERA) }

    var torch by remember { mutableStateOf(false) }
    // Latched so a code sitting in frame fires exactly once. Without it the
    // analyser reports the same barcode on every frame and the caller advances
    // through several steps from one label.
    var handled by remember { mutableStateOf(false) }
    // Held so the decoder's auto-zoom and the pinch gesture drive the same
    // camera, and so the zoom readout can never disagree with the lens.
    var boundCamera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    var zoom by remember { mutableStateOf(1f) }
    // One log line per scanner opening — see the analyser below.
    var reported by remember { mutableStateOf(false) }

    BackHandler { onClose() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCamera) {
            val executor = remember { Executors.newSingleThreadExecutor() }
            // Auto-zoom, and the formats we actually print.
            //
            // A small QR code — the ones on a module rather than on the pack —
            // occupies a few dozen pixels of a 640×480 analysis frame, which is
            // below what any decoder can resolve. Two things fix that together:
            // the higher analysis resolution set below, and this, which lets ML
            // Kit ask the camera to zoom in when it can see a code but cannot yet
            // read it. Between them the operator stops having to physically move
            // the phone to within an inch of the label.
            val scanner = remember {
                BarcodeScanning.getClient(
                    BarcodeScannerOptions.Builder()
                        // Every format, deliberately.
                        //
                        // Naming a shortlist is the standard advice because it is
                        // faster, and it is wrong here: a format left off the list
                        // is not slow to scan, it is *impossible* to scan, and
                        // nobody can tell that apart from a broken camera. This
                        // fleet takes parts from many suppliers and the labels are
                        // whatever each of them prints — ITF and Codabar on cartons,
                        // PDF417 and Aztec on documents, UPC on bought-in items.
                        // The cost is a few milliseconds a frame; the cost of the
                        // shortlist is an operator who cannot record a part at all.
                        .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                        .setZoomSuggestionOptions(
                            ZoomSuggestionOptions.Builder { ratio ->
                                val camera = boundCamera ?: return@Builder false
                                camera.cameraControl.setZoomRatio(ratio)
                                zoom = ratio
                                android.util.Log.i("BarcodeScanner", "auto-zoom to ${ratio}x")
                                true
                            }
                                .setMaxSupportedZoomRatio(MAX_AUTO_ZOOM)
                                .build(),
                        )
                        .build(),
                )
            }
            // The camera and the analyser both hold native resources; a scanner
            // left open keeps the torch on and the camera busy for the whole app.
            DisposableEffect(Unit) {
                onDispose {
                    executor.shutdown()
                    runCatching { scanner.close() }
                }
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                    val providerFuture = ProcessCameraProvider.getInstance(ctx)
                    providerFuture.addListener({
                        val provider = providerFuture.get()
                        val preview = Preview.Builder().build()
                            .also { it.surfaceProvider = previewView.surfaceProvider }

                        val analysis = ImageAnalysis.Builder()
                            // Dropping stale frames rather than queueing them is
                            // what keeps the viewfinder live while decoding — a
                            // backed-up queue shows the operator a preview that
                            // lags their own hand.
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            // The single biggest thing for small codes — see the
                            // arithmetic on ANALYSIS_WIDTH. At CameraX's 640×480
                            // default a small module label carries about one pixel
                            // per QR module, which no decoder can read: the
                            // information simply is not in the frame.
                            .setResolutionSelector(
                                ResolutionSelector.Builder()
                                    .setResolutionStrategy(
                                        ResolutionStrategy(
                                            CameraSize(ANALYSIS_WIDTH, ANALYSIS_HEIGHT),
                                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                        ),
                                    )
                                    .build(),
                            )
                            .build()

                        analysis.setAnalyzer(executor) { proxy: ImageProxy ->
                            @Suppress("UnsafeOptInUsageError")
                            val media = proxy.image
                            // Reported once, because *asking* for a resolution and
                            // *getting* it are different things: a camera that does
                            // not offer 1080p analysis falls back silently, and a
                            // silent fallback here is the difference between a
                            // scanner that reads small labels and one that does not.
                            if (!reported) {
                                reported = true
                                android.util.Log.i(
                                    "BarcodeScanner",
                                    "analysis frame ${proxy.width}x${proxy.height} " +
                                        "(asked ${ANALYSIS_WIDTH}x$ANALYSIS_HEIGHT)",
                                )
                            }
                            if (media == null || handled) {
                                proxy.close()
                                return@setAnalyzer
                            }
                            val image = InputImage.fromMediaImage(
                                media,
                                proxy.imageInfo.rotationDegrees,
                            )
                            scanner.process(image)
                                .addOnSuccessListener { codes ->
                                    val value = codes.firstNotNullOfOrNull { it.bestValue() }
                                    if (!value.isNullOrBlank() && !handled) {
                                        handled = true
                                        // The beep is the whole confirmation. An
                                        // operator scanning a pack is looking at
                                        // the label, not at the screen, and the
                                        // viewfinder closing is not something you
                                        // notice from that angle — so without a
                                        // sound the only way to know it worked is
                                        // to stop and look, once per scan, all day.
                                        feedback.success()
                                        onScanned(value)
                                    }
                                }
                                .addOnCompleteListener { proxy.close() }
                        }

                        runCatching {
                            provider.unbindAll()
                            val camera = provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                analysis,
                            )
                            previewView.tag = camera
                            boundCamera = camera

                            // Tap to focus, pinch to zoom — the two things anyone
                            // holding a camera at an awkward angle tries first. A
                            // label on the underside of a pack is often outside
                            // the centre-weighted autofocus window, and without a
                            // way to say "focus there" the preview stays sharp on
                            // the bench behind it.
                            previewView.setOnTouchListener { view, event ->
                                if (event.action != android.view.MotionEvent.ACTION_UP) {
                                    return@setOnTouchListener true
                                }
                                val factory = previewView.meteringPointFactory
                                val point = factory.createPoint(event.x, event.y)
                                camera.cameraControl.startFocusAndMetering(
                                    FocusMeteringAction.Builder(point).build(),
                                )
                                view.performClick()
                                true
                            }
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
            )

            // The torch, driven from state rather than from the AndroidView's
            // update block.
            //
            // It used to reach the camera through `view.tag` inside `update {}`,
            // which is a race it could only lose: the camera is bound in an async
            // listener, and if the tag was not set by the time the block last ran,
            // the tap did nothing — silently, with the button still showing "on".
            // Keying the effect on the camera *and* the flag means the torch comes
            // on whichever arrives second.
            LaunchedEffect(torch, boundCamera) {
                boundCamera?.cameraControl?.enableTorch(torch)
            }

            ScannerReticle(Modifier.fillMaxSize())
        }

        // ── Chrome over the preview ───────────────────────────────────────
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Close scanner",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                // Only when this camera actually has a lamp, and only once it is
                // bound. A torch button that cannot light anything is worse than no
                // button: on a dark underside of a pack it is the first thing an
                // operator reaches for, and one that does nothing costs them the
                // time it takes to decide the scanner is broken.
                if (boundCamera?.cameraInfo?.hasFlashUnit() == true) {
                    IconButton(onClick = { torch = !torch }, modifier = Modifier.size(52.dp)) {
                        Icon(
                            if (torch) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff,
                            contentDescription = if (torch) "Torch off" else "Torch on",
                            tint = if (torch) Semantic.caution else Color.White,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (hasCamera) {
                    // Zoom as buttons rather than as a pinch. A gloved pinch on a
                    // greasy screen is unreliable, and the operator's other hand
                    // is holding the pack — these are the sizes that make a small
                    // module label fill the frame from a comfortable distance.
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ZOOM_STEPS.forEach { step ->
                            val active = kotlin.math.abs(zoom - step) < 0.05f
                            Surface(
                                color = if (active) Color.White else Color.White.copy(alpha = 0.18f),
                                shape = CircleShape,
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        boundCamera?.cameraControl?.setZoomRatio(step)
                                        zoom = step
                                    },
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        "${step.toInt()}×",
                                        color = if (active) Color.Black else Color.White,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    when {
                        !hasCamera -> "Camera permission is needed to scan. You can still type the code."
                        else -> hint ?: "Small code? Tap the screen to focus, or zoom in above"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                // Typing is a first-class route out of here, not a hidden
                // fallback: a damaged label must never end an inspection.
                Button(
                    onClick = onManualEntry,
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    shape = Radii.xl,
                ) {
                    Icon(Icons.Rounded.Keyboard, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Type it instead", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/**
 * The aiming frame.
 *
 * Four corner brackets and a dimmed surround rather than a solid rectangle: the
 * brackets say where to aim without covering the thing being aimed at, and the
 * scrim makes the live area obvious in the bright, cluttered background a plant
 * floor provides.
 */
@Composable
private fun ScannerReticle(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val side = minOf(size.width, size.height) * 0.68f
            val left = (size.width - side) / 2f
            val top = (size.height - side) / 2f

            // Dim everything outside the window, in four bands rather than with
            // a clip path — cheaper, and exact.
            val scrim = Color.Black.copy(alpha = 0.45f)
            drawRect(scrim, size = Size(size.width, top))
            drawRect(scrim, topLeft = Offset(0f, top + side), size = Size(size.width, size.height - top - side))
            drawRect(scrim, topLeft = Offset(0f, top), size = Size(left, side))
            drawRect(scrim, topLeft = Offset(left + side, top), size = Size(size.width - left - side, side))

            val arm = side * 0.13f
            val stroke = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
            val white = Color.White
            // Top-left, top-right, bottom-left, bottom-right.
            listOf(
                Offset(left, top) to listOf(Offset(left + arm, top), Offset(left, top + arm)),
                Offset(left + side, top) to listOf(Offset(left + side - arm, top), Offset(left + side, top + arm)),
                Offset(left, top + side) to listOf(Offset(left + arm, top + side), Offset(left, top + side - arm)),
                Offset(left + side, top + side) to
                    listOf(Offset(left + side - arm, top + side), Offset(left + side, top + side - arm)),
            ).forEach { (corner, arms) ->
                arms.forEach { end -> drawLine(white, corner, end, strokeWidth = stroke.width, cap = stroke.cap) }
            }
        }
    }
}

/**
 * Analysis resolution, and the single thing that decides whether a small label
 * can be read at all.
 *
 * A QR code needs roughly 2–3 pixels per module to decode. Work it through for a
 * 10 mm label held at arm's length (~200 mm) through a typical 60° field:
 *
 * | analysis size | px/mm | 10 mm code | px per module (v1, 21 modules) |
 * |---|---|---|---|
 * | 640×480 (CameraX default) | 2.8 | 28 px | **1.3 — undecodable** |
 * | 1280×720 | 5.5 | 55 px | 2.6 — marginal |
 * | 1920×1080 | 8.3 | 83 px | **4.0 — comfortable** |
 *
 * At the default, no amount of steadiness, light or patience could ever have read
 * a small module label: the information was not in the frame. That is why the
 * scanner "worked" on the big pack sticker and not on the little ones.
 *
 * 1080p costs about 2¼ times the decode work of 720p per frame, which on these
 * handsets is tens of milliseconds and is dropped under
 * `STRATEGY_KEEP_ONLY_LATEST` anyway. A frame that decodes slightly slower beats
 * a frame that cannot decode.
 */
private const val ANALYSIS_WIDTH = 1920
private const val ANALYSIS_HEIGHT = 1080

/** How far the decoder may zoom the lens by itself when it spots a small code. */
private const val MAX_AUTO_ZOOM = 4f

/** The manual zoom steps offered under the viewfinder. */
private val ZOOM_STEPS = listOf(1f, 2f, 3f)

/**
 * Everything ML Kit saw in this barcode, in the order worth trusting.
 *
 * **`rawValue` alone was silently losing scans.** It is null whenever the decoder
 * cannot render the payload as a Java string, and the commonest cause on this
 * shop floor is a QR whose Byte-mode content is Chinese encoded as GB2312/GBK
 * with no UTF-8 marker — which describes a great many supplier labels. The code
 * was detected perfectly: position, format and `rawBytes` all present, and then
 * `firstNotNullOfOrNull { it.rawValue }` threw the whole thing away. To the
 * operator that is indistinguishable from a scanner that cannot see the label.
 *
 * So: the decoded string if there is one, then the display form, and failing
 * both, the raw bytes run through the encodings these labels are actually
 * printed in. A code we hold the bytes of is a code we can read.
 */
private fun Barcode.bestValue(): String? {
    rawValue?.takeIf { it.isNotBlank() }?.let { return it }
    displayValue?.takeIf { it.isNotBlank() }?.let { return it }
    val bytes = rawBytes ?: return null

    for (name in BYTE_CHARSETS) {
        val charset = runCatching { Charset.forName(name) }.getOrNull() ?: continue
        // Strict: a charset that "succeeds" by substituting replacement
        // characters would hand back mojibake and call it a serial number.
        val decoded = runCatching {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
        if (!decoded.isNullOrBlank() && '�' !in decoded) return decoded.trim()
    }
    // Last resort: every byte maps to a character in Latin-1, so this cannot
    // fail. A label we can only half-read still beats an inspection that stops.
    return String(bytes, Charsets.ISO_8859_1).trim().takeIf { it.isNotBlank() }
}

/**
 * The encodings supplier labels are printed in, most specific first.
 *
 * GB18030 covers GB2312 and GBK, which is what Chinese industrial labelling
 * uses. Big5 is traditional Chinese, Shift_JIS Japanese — both cheap to try and
 * both real on a floor that takes parts from wherever they come from.
 */
private val BYTE_CHARSETS = listOf("UTF-8", "GB18030", "Big5", "Shift_JIS")

/** Barcode formats worth naming in help text. Kept for callers that explain themselves. */
val SCANNABLE_FORMATS = listOf(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_CODE_128, Barcode.FORMAT_DATA_MATRIX)
