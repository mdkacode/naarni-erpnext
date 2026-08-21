package com.naarni.service.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.location.Location
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.naarni.service.appContainer
import com.naarni.service.core.camera.PhotoStamper
import com.naarni.service.core.media.ImageScaler
import com.naarni.service.core.location.LocationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Full-screen CameraX capture that burns a geo/time/user stamp into every photo
 * (PhotoStamper) before handing the file back. Camera permission is required;
 * location is a soft gate — denied/unavailable stamps "Location unavailable".
 */
@Composable
fun StampingCamera(
    /**
     * The stamped file, and the fix it was stamped with (null when location was
     * denied or timed out).
     *
     * The fix is handed back rather than kept private because burning
     * coordinates into the pixels is not the same as recording them: the burn-in
     * survives a screenshot, but only a stored latitude/longitude can be queried,
     * geofenced or plotted. A photo whose location exists solely as painted text
     * is evidence a human can read and a report cannot.
     */
    onCaptured: (File, Location?) -> Unit,
    onClose: () -> Unit,
    /** Optional photo type/angle burned into the stamp (e.g. "Front", "Damage"). */
    label: String? = null,
    /**
     * The scanned serial / pack number this photo belongs to.
     *
     * Burned in so the image identifies itself once it has left the record it was
     * captured against — exported, emailed or printed, which is exactly when the
     * surrounding context is gone.
     */
    subject: String? = null,
    /**
     * A word painted across the top of the picture — INWARD or OUTWARD at the
     * material gate. Unlike [label] it is not part of the small corner block:
     * it has to be readable at thumbnail size and after printing.
     */
    banner: String? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val session = remember { context.appContainer.session }
    val locationProvider = remember { LocationProvider(context) }
    val imageCapture = remember {
        ImageCapture.Builder()
            // Latency over resolution, deliberately. The stamp has to be legible
            // and the defect has to be recognisable; neither needs twelve
            // megapixels, and the full-sensor path costs twice over — the camera
            // encodes a 5 MB JPEG, then we decode all of it back to stamp it.
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(CAPTURE_LONG_EDGE, CAPTURE_SHORT_EDGE),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build(),
            )
            .build()
    }
    val executor = remember { Executors.newSingleThreadExecutor() }

    // The fix, fetched while the operator is still aiming.
    //
    // This used to be requested *on the shutter*, at high accuracy, with a six
    // second timeout — so inside a shed, where a GPS fix is exactly what is hard
    // to get, every single photograph made somebody stand and watch a spinner for
    // six seconds before the stamping had even begun. On a fifty-nine check sheet
    // that is minutes of a shift spent waiting for a satellite.
    //
    // Aiming a camera takes a second or two anyway. Starting the request when the
    // viewfinder opens spends that time instead of the operator's.
    var fix by remember { mutableStateOf<Location?>(null) }

    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var busy by remember { mutableStateOf(false) }

    fun hasLocationPerm() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> hasCamera = result[Manifest.permission.CAMERA] == true }

    // Google Play "prominent disclosure": explain WHY we access camera + location
    // BEFORE the system permission prompt appears. Shown once per screen entry
    // while either permission is still missing.
    var showDisclosure by remember { mutableStateOf(!(hasCamera && hasLocationPerm())) }

    if (showDisclosure) {
        AlertDialog(
            onDismissRequest = { showDisclosure = false },
            title = { Text("Camera & location") },
            text = {
                Text(
                    "NaArNi Care uses your camera to capture job photos and your " +
                        "location to stamp each photo with the place and time it was taken, " +
                        "so office staff can verify field work. Location is read only at the " +
                        "moment you take a photo — never in the background — and is not shared " +
                        "with third parties."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDisclosure = false
                    permLauncher.launch(
                        arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
                    )
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDisclosure = false
                    onClose()
                }) { Text("Not now") }
            },
        )
    }

    LaunchedEffect(hasCamera, showDisclosure) {
        if (!hasCamera || showDisclosure || !hasLocationPerm()) return@LaunchedEffect
        // Cheap and instant first, so there is always *something* to stamp with,
        // then upgrade to a real fix if one arrives while the operator is aiming.
        fix = locationProvider.lastKnown()
        locationProvider.current()?.let { fix = it }
    }

    if (!hasCamera) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Button(onClick = {
                permLauncher.launch(
                    arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
                )
            }) { Text("Grant camera access") }
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture,
                    )
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
        )

        FloatingActionButton(
            onClick = {
                if (busy) return@FloatingActionButton
                busy = true
                val temp = File.createTempFile("capture_", ".jpg", context.cacheDir)
                val output = ImageCapture.OutputFileOptions.Builder(temp).build()
                imageCapture.takePicture(
                    output,
                    executor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onError(exc: ImageCaptureException) {
                            busy = false
                        }

                        override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                            scope.launch {
                                // Whatever the prefetch has by now. No fix is a
                                // stamp that says so — never a reason to hold up
                                // somebody who has already taken the photograph.
                                val location = fix
                                withContext(Dispatchers.IO) {
                                    // Downscaled *before* stamping, never after.
                                    // Decoding a 12-megapixel capture whole costs
                                    // ~48 MB of heap with the camera still bound,
                                    // and shrinking a stamped photo afterwards
                                    // would soften the very text the stamp exists
                                    // to make readable.
                                    val src = decodeForStamping(temp)
                                        ?: return@withContext
                                    val stamp = PhotoStamper.build(
                                        location = location,
                                        userFullName = session.fullName ?: (session.user ?: "User"),
                                        userRole = session.primaryRole,
                                        label = label,
                                        subject = subject,
                                        banner = banner,
                                    )
                                    val stamped = PhotoStamper.stamp(src, stamp)
                                    FileOutputStream(temp).use { out ->
                                        stamped.compress(
                                            android.graphics.Bitmap.CompressFormat.JPEG, 85, out
                                        )
                                    }
                                }
                                busy = false
                                onCaptured(temp, location)
                            }
                        }
                    },
                )
            },
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp)
                .size(76.dp),
        ) {
            if (busy) CircularProgressIndicator() else Text("📷", style = MaterialTheme.typography.titleLarge)
        }

        Button(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
        ) { Text("Close") }
    }
}

/**
 * What the camera is asked to produce, in pixels.
 *
 * Sized for what these photographs are for: a legible stamp and a recognisable
 * defect, viewed on a phone or in a PDF. A full-sensor capture costs the encode,
 * the decode and the heap, and every one of those is paid while an operator
 * stands waiting at a bench.
 */
private const val CAPTURE_LONG_EDGE = 1920
private const val CAPTURE_SHORT_EDGE = 1080

/**
 * Decode a capture at upload resolution, rotated upright.
 *
 * The camera writes a full-sensor JPEG with an orientation tag. Both facts are
 * dealt with here so that everything downstream — the stamp, the outbox, the
 * bubble — is working on pixels that are already the right size and the right
 * way up. Nothing re-reads the EXIF afterwards, because after this there is
 * none: the re-encode drops it.
 */
private fun decodeForStamping(file: File): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    if (longest <= 0) return BitmapFactory.decodeFile(file.absolutePath)

    var sample = 1
    while (longest / (sample * 2) >= ImageScaler.MAX_EDGE) sample *= 2
    val decoded = BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sample },
    ) ?: return null

    val upright = when (
        ExifInterface(file.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    ) {
        ExifInterface.ORIENTATION_ROTATE_90 -> rotated(decoded, 90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> rotated(decoded, 180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> rotated(decoded, 270f)
        else -> decoded
    }
    return ImageScaler.fit(upright, ImageScaler.MAX_EDGE)
}

private fun rotated(source: android.graphics.Bitmap, degrees: Float): android.graphics.Bitmap {
    val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
    val out = android.graphics.Bitmap
        .createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    if (out !== source) source.recycle()
    return out
}
