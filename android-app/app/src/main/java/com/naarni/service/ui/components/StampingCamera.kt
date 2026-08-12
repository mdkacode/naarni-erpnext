package com.naarni.service.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
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
import com.naarni.service.appContainer
import com.naarni.service.core.camera.PhotoStamper
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
    onCaptured: (File) -> Unit,
    onClose: () -> Unit,
    /** Optional photo type/angle burned into the stamp (e.g. "Front", "Damage"). */
    label: String? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val session = remember { context.appContainer.session }
    val locationProvider = remember { LocationProvider(context) }
    val imageCapture = remember { ImageCapture.Builder().build() }
    val executor = remember { Executors.newSingleThreadExecutor() }

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
                                val location =
                                    if (hasLocationPerm()) locationProvider.current() else null
                                withContext(Dispatchers.IO) {
                                    val src = BitmapFactory.decodeFile(temp.absolutePath)
                                        ?: return@withContext
                                    val stamp = PhotoStamper.build(
                                        location = location,
                                        userFullName = session.fullName ?: (session.user ?: "User"),
                                        userRole = session.primaryRole,
                                        label = label,
                                    )
                                    val stamped = PhotoStamper.stamp(src, stamp)
                                    FileOutputStream(temp).use { out ->
                                        stamped.compress(
                                            android.graphics.Bitmap.CompressFormat.JPEG, 85, out
                                        )
                                    }
                                }
                                busy = false
                                onCaptured(temp)
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
