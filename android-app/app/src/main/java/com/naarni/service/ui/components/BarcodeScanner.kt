package com.naarni.service.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
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
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

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

    BackHandler { onClose() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCamera) {
            val executor = remember { Executors.newSingleThreadExecutor() }
            val scanner = remember { BarcodeScanning.getClient() }
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
                            .build()

                        analysis.setAnalyzer(executor) { proxy: ImageProxy ->
                            @Suppress("UnsafeOptInUsageError")
                            val media = proxy.image
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
                                    val value = codes.firstNotNullOfOrNull { it.rawValue }
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
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
                update = { view ->
                    // Torch is read back off the bound camera rather than kept as
                    // separate state, so the button can never disagree with the
                    // hardware.
                    (view.tag as? androidx.camera.core.Camera)
                        ?.cameraControl
                        ?.enableTorch(torch)
                },
            )

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
                if (hasCamera) {
                    IconButton(onClick = { torch = !torch }, modifier = Modifier.size(48.dp)) {
                        Icon(
                            if (torch) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff,
                            contentDescription = if (torch) "Torch off" else "Torch on",
                            tint = if (torch) Color(0xFFFFD166) else Color.White,
                            modifier = Modifier.size(28.dp),
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
                Text(
                    when {
                        !hasCamera -> "Camera permission is needed to scan. You can still type the code."
                        else -> hint ?: "Point the camera at the QR code or barcode"
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
                    shape = RoundedCornerShape(16.dp),
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

/** Barcode formats worth naming in help text. Kept for callers that explain themselves. */
val SCANNABLE_FORMATS = listOf(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_CODE_128, Barcode.FORMAT_DATA_MATRIX)
