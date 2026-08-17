package com.naarni.service.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.naarni.service.BuildConfig
import com.naarni.service.data.dto.BusImage
import com.naarni.service.ui.AppViewModel
import kotlinx.coroutines.launch

private fun fullUrl(fileUrl: String?): String? =
    fileUrl?.takeIf { it.isNotBlank() }?.let {
        if (it.startsWith("http")) it else BuildConfig.BASE_URL.trimEnd('/') + it
    }

/**
 * Multi-angle bus photo gallery for a Vehicle or a Job Card. Renders a slot per
 * angle (admin-editable via Customize Form); an empty slot opens the stamped camera, a filled slot opens a
 * viewer (with replace / delete). Photos are geo/time/user-stamped at capture.
 *
 * @param parentDoctype "Vehicle" or "Job Card"
 * @param parentName the parent document name
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BusPhotoGrid(
    vm: AppViewModel,
    parentDoctype: String,
    parentName: String,
    modifier: Modifier = Modifier,
) {
    var images by remember { mutableStateOf<List<BusImage>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var activeAngle by remember { mutableStateOf<String?>(null) }
    var viewer by remember { mutableStateOf<BusImage?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        runCatching { vm.jobCards.busImages(parentDoctype, parentName) }.onSuccess { images = it }
    }
    androidx.compose.runtime.LaunchedEffect(parentDoctype, parentName) { loading = true; load(); loading = false }

    val byAngle = images.associateBy { it.angle }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth()) {
            Text("Bus Photos", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            if (busy || loading) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterEnd).size(18.dp), strokeWidth = 2.dp)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            vm.opt("angle").forEach { angle ->
                AngleSlot(
                    angle = angle,
                    image = byAngle[angle],
                    enabled = !busy,
                    onTap = {
                        val existing = byAngle[angle]
                        if (existing?.image != null) viewer = existing else activeAngle = angle
                    },
                )
            }
        }
        msg?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium) }
    }

    // Capture flow — stamped camera (full-screen overlay), then upload + map to the angle.
    activeAngle?.let { angle ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { activeAngle = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(Modifier.fillMaxSize()) {
                StampingCamera(
                    label = angle,
                    onClose = { activeAngle = null },
                    onCaptured = { file, _ ->
                        activeAngle = null
                        scope.launch {
                            busy = true
                            runCatching { images = vm.jobCards.uploadBusImage(parentDoctype, parentName, angle, file) }
                                .onSuccess { msg = "$angle photo saved" }
                                .onFailure { msg = it.message ?: "Upload failed" }
                            busy = false
                        }
                    },
                )
            }
        }
    }

    // Viewer — full image + replace / delete.
    viewer?.let { img ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { viewer = null }) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.fillMaxWidth()) {
                    Text(img.angle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { viewer = null }, modifier = Modifier.align(Alignment.CenterEnd).size(24.dp)) {
                        Icon(Icons.Rounded.Close, "Close")
                    }
                }
                AsyncImage(
                    model = fullUrl(img.image),
                    contentDescription = img.angle,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(12.dp)),
                )
                img.captured_at?.let {
                    Text("Captured ${it.take(16).replace("T", " ")}${img.captured_by?.let { u -> " · $u" } ?: ""}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { val a = img.angle; viewer = null; activeAngle = a }, modifier = Modifier.align(Alignment.CenterStart)) {
                        Icon(Icons.Rounded.AddAPhoto, null); Text("  Replace")
                    }
                    TextButton(
                        onClick = {
                            val a = img.angle; viewer = null
                            scope.launch {
                                busy = true
                                runCatching { images = vm.jobCards.deleteBusImage(parentDoctype, parentName, a) }
                                    .onSuccess { msg = "$a photo removed" }.onFailure { msg = it.message ?: "Delete failed" }
                                busy = false
                            }
                        },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    ) {
                        Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error)
                        Text("  Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun AngleSlot(angle: String, image: BusImage?, enabled: Boolean, onTap: () -> Unit) {
    val hasImage = image?.image != null
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier.width(96.dp).aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(
                    if (image?.is_primary == 1)
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                    else Modifier,
                )
                .clickable(enabled = enabled, onClick = onTap),
            contentAlignment = Alignment.Center,
        ) {
            if (hasImage) {
                AsyncImage(
                    model = fullUrl(image!!.image),
                    contentDescription = angle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // Photo type/angle label burned onto the thumbnail (bottom strip).
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    Text(
                        angle,
                        color = androidx.compose.ui.graphics.Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
                if (image.is_primary == 1) {
                    Icon(
                        Icons.Rounded.Star, "Primary",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp),
                    )
                }
            } else {
                Icon(Icons.Rounded.AddAPhoto, "Add $angle photo", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            angle,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (hasImage) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(96.dp),
        )
    }
}
