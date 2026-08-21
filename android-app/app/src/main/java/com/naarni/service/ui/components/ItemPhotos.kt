package com.naarni.service.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** One pre/post photo slot on a repair / maintenance / software row. */
data class PhotoSlot(val key: String, val label: String, val url: String?)

/**
 * A compact row of capture buttons for a line item's photo slots. A captured
 * slot shows a check; the slot currently uploading shows a spinner. Photos stay
 * optional (demo-friendly) — they document work but never block saving.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ItemPhotos(slots: List<PhotoSlot>, uploadingKey: String?, onCapture: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        slots.forEach { slot ->
            val has = !slot.url.isNullOrBlank()
            OutlinedButton(onClick = { onCapture(slot.key) }) {
                when {
                    uploadingKey == slot.key -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    has -> Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    else -> Icon(Icons.Rounded.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Text("  ${slot.label}")
            }
        }
    }
}
