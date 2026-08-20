package com.naarni.service.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.naarni.service.BuildConfig
import com.naarni.service.ui.theme.AppSurface
import com.naarni.service.ui.theme.Semantic
import java.io.File

/**
 * One photograph, wherever it currently lives.
 *
 * [localPath] is the file on this phone; [remoteUrl] is the Frappe File once it
 * has uploaded. The local copy is preferred for display because it is the one
 * that exists in a shed with no signal — which is precisely when an operator
 * wants to check what they just shot.
 */
data class ReviewablePhoto(
    val id: String,
    val localPath: String? = null,
    val remoteUrl: String? = null,
    val caption: String? = null,
    val uploaded: Boolean = false,
) {
    /** What Coil should load: the file if we still hold it, else the server copy. */
    val model: Any?
        get() = localPath?.takeIf { File(it).exists() }?.let { File(it) }
            ?: remoteUrl?.takeIf { it.isNotBlank() }
                ?.let { if (it.startsWith("http")) it else BuildConfig.BASE_URL.trimEnd('/') + it }
}

/**
 * Thumbnails of what has been photographed, and a full-screen viewer.
 *
 * The app could take pictures long before it could show one back: a step
 * displayed a count and nothing else, so an operator had no way to tell a good
 * photograph from a thumb over the lens until somebody opened the record days
 * later. A count is not evidence anybody has checked.
 *
 * Deliberately reads the local file first. Reviewing your own photo must not
 * require the upload to have finished, or the feature stops working exactly
 * where the app is supposed to work best.
 */
@Composable
fun PhotoStrip(
    photos: List<ReviewablePhoto>,
    modifier: Modifier = Modifier,
    onDelete: ((ReviewablePhoto) -> Unit)? = null,
) {
    if (photos.isEmpty()) return
    var viewing by remember { mutableStateOf<Int?>(null) }

    LazyRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(photos, key = { _, p -> p.id }) { index, photo ->
            Box(
                Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AppSurface.sunken)
                    .clickable { viewing = index },
            ) {
                AsyncImage(
                    model = photo.model,
                    contentDescription = photo.caption ?: "Photo ${index + 1}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // Whether it has reached the server. An operator in a dead zone
                // should be able to see that the photo is safe on the phone and
                // simply has not gone yet — not wonder whether it was lost.
                Icon(
                    if (photo.uploaded) Icons.Rounded.CloudQueue else Icons.Rounded.CloudOff,
                    contentDescription = if (photo.uploaded) "Uploaded" else "Waiting to upload",
                    tint = if (photo.uploaded) Semantic.positive else Semantic.caution,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(3.dp)
                        .size(14.dp),
                )
            }
        }
    }

    viewing?.let { index ->
        PhotoViewer(
            photos = photos,
            startIndex = index,
            onClose = { viewing = null },
            onDelete = onDelete?.let { delete ->
                { photo: ReviewablePhoto ->
                    delete(photo)
                    viewing = null
                }
            },
        )
    }
}

/**
 * Full-screen review.
 *
 * Left/right taps move between photographs rather than a pager, because the
 * gesture has to work through a glove on a phone held at arm's length, and a
 * swipe that is half-registered leaves the image mid-slide.
 */
@Composable
fun PhotoViewer(
    photos: List<ReviewablePhoto>,
    startIndex: Int,
    onClose: () -> Unit,
    onDelete: ((ReviewablePhoto) -> Unit)? = null,
) {
    var index by remember { mutableStateOf(startIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0))) }
    var confirmDelete by remember { mutableStateOf(false) }
    val photo = photos.getOrNull(index) ?: return

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(
                model = photo.model,
                contentDescription = photo.caption ?: "Photo ${index + 1} of ${photos.size}",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )

            if (photos.size > 1) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxSize().clickable(enabled = index > 0) { index-- })
                    Box(Modifier.weight(1f).fillMaxSize().clickable(enabled = index < photos.lastIndex) { index++ })
                }
            }

            Row(
                Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White)
                }
                Text(
                    "${index + 1} of ${photos.size}",
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                if (onDelete != null) {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Delete photo", tint = Color.White)
                    }
                }
            }

            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                photo.caption?.takeIf { it.isNotBlank() }?.let {
                    Surface(color = Color.Black.copy(alpha = 0.55f), shape = RoundedCornerShape(8.dp)) {
                        Text(it, color = Color.White, modifier = Modifier.padding(10.dp))
                    }
                }
                if (!photo.uploaded) {
                    Text(
                        "On this phone — uploads when there is signal",
                        color = Semantic.caution,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Rounded.PhotoCamera, contentDescription = null) },
            title = { Text("Delete this photo?") },
            text = { Text("You can take another one straight away.") },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = false
                    onDelete(photo)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}
