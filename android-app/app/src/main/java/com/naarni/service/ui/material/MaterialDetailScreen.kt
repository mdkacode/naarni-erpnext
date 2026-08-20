package com.naarni.service.ui.material

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.naarni.service.data.dto.Movement
import com.naarni.service.data.dto.MovementItem
import com.naarni.service.ui.components.AppBar
import com.naarni.service.ui.components.LoadingOverlay
import com.naarni.service.ui.components.MetaChip
import com.naarni.service.ui.components.PhotoStrip
import com.naarni.service.ui.components.ReviewablePhoto
import com.naarni.service.ui.components.SectionHeader
import com.naarni.service.ui.theme.AppSurface
import com.naarni.service.ui.theme.Semantic

/**
 * One movement, read-only, plus the supervisor's two decisions.
 *
 * The evidence figure sits at the top rather than buried below the item list,
 * because it is the number a supervisor is deciding on: verifying a movement
 * where a third of the items were never photographed is a choice, and it should
 * be a visible one.
 */
@Composable
fun MaterialDetailScreen(
    movementName: String,
    onEdit: (String) -> Unit,
    onBack: () -> Unit,
    vm: MaterialViewModel = viewModel(),
) {
    val ui = vm.ui
    val movement = ui.current
    var rejecting by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }

    LaunchedEffect(movementName) {
        if (ui.current?.name != movementName) vm.open(movementName)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppBar(
                title = movement?.name ?: "Movement",
                subtitle = movement?.let { "${it.movement_type} · ${it.location_name ?: it.location}" },
                onBack = onBack,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (movement != null) {
                Column(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Header(movement)
                        movement.rejection_reason?.takeIf { it.isNotBlank() }?.let {
                            Callout("Sent back", it, Semantic.caution)
                        }
                        Facts(movement)

                        val photosByRow = movement.photos.byItemRow()

                        photosByRow[null].orEmpty().takeIf { it.isNotEmpty() }?.let { documents ->
                            SectionHeader("Documents (${documents.size})")
                            PhotoStrip(photos = documents)
                        }

                        SectionHeader("Items (${movement.total_items})")
                        movement.items.forEach { ReadOnlyItem(it, photosByRow[it.row_uuid].orEmpty()) }

                        movement.remarks?.takeIf { it.isNotBlank() }?.let {
                            SectionHeader("Remarks")
                            Text(it, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    ActionBar(
                        movement = movement,
                        saving = ui.saving,
                        onEdit = { onEdit(movement.name) },
                        onVerify = { vm.verify(onDone = onBack) },
                        onReject = { rejecting = true },
                    )
                }
            }
            LoadingOverlay(visible = ui.loading || ui.saving)
        }
    }

    if (rejecting) {
        AlertDialog(
            onDismissRequest = { rejecting = false },
            title = { Text("Send back to the operator") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Say what needs fixing — this is what they will see.")
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        placeholder = { Text("e.g. Challan number missing") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        rejecting = false
                        vm.reject(reason.trim(), onDone = onBack)
                    },
                    enabled = reason.isNotBlank(),
                ) { Text("Send back") }
            },
            dismissButton = { TextButton(onClick = { rejecting = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(movement: Movement) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                movement.party_name?.takeIf { it.isNotBlank() } ?: "Unnamed party",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            MovementStatusChip(movement.status)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MetaChip(Icons.Rounded.Inventory2, "${movement.total_items} items")
            MetaChip(
                Icons.Rounded.PhotoCamera,
                "${movement.evidence_pct.toInt()}% photographed",
                tint = if (movement.rowsWithoutEvidence() == 0) Semantic.positive else Semantic.caution,
            )
            if (movement.qr_count > 0) MetaChip(Icons.Rounded.QrCode2, "${movement.qr_count} serials")
            if (movement.damaged_count > 0) {
                MetaChip(Icons.Rounded.WarningAmber, "${movement.damaged_count} not OK", tint = Semantic.caution)
            }
        }
    }
}

@Composable
private fun Facts(movement: Movement) {
    val rows = listOfNotNull(
        movement.purpose?.takeIf { it.isNotBlank() }?.let { "Purpose" to it },
        movement.gate?.takeIf { it.isNotBlank() }?.let { "Gate" to it },
        movement.reference_no?.takeIf { it.isNotBlank() }
            ?.let { (movement.reference_type ?: "Document") to it },
        movement.transport_vehicle_no?.takeIf { it.isNotBlank() }?.let { "Truck" to it },
        movement.driver_name?.takeIf { it.isNotBlank() }?.let { "Driver" to it },
        movement.started_by?.let { "Recorded by" to it },
        movement.started_at?.let { "Started" to it.take(16) },
        movement.verified_by?.let { "Verified by" to it },
    )
    if (rows.isEmpty()) return

    Surface(shape = RoundedCornerShape(14.dp), color = AppSurface.raised, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(0.4f),
                    )
                    Text(
                        value,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(0.6f),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReadOnlyItem(row: MovementItem, photos: List<ReviewablePhoto>) {
    Surface(shape = RoundedCornerShape(12.dp), color = AppSurface.raised, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row {
                Text(
                    row.item_name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${row.qty.trimTrailingZero()} ${row.uom}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            val facts = listOfNotNull(
                row.condition.takeIf { it != "OK" },
                row.qr_code?.takeIf { it.isNotBlank() },
                "${row.photo_count} ${if (row.photo_count == 1) "photo" else "photos"}",
                row.no_photo_reason?.takeIf { it.isNotBlank() }?.let { "no photo: $it" },
            ).joinToString("  ·  ")
            Text(
                facts,
                style = MaterialTheme.typography.labelSmall,
                color = if (row.condition != "OK") Semantic.caution else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The evidence itself. A supervisor deciding whether to verify has to
            // be able to look at what was photographed, not read that two things
            // were — which is the entire difference between a record and a count.
            if (photos.isNotEmpty()) {
                PhotoStrip(photos = photos)
            }
        }
    }
}

@Composable
private fun Callout(title: String, body: String, tint: androidx.compose.ui.graphics.Color) {
    Surface(shape = RoundedCornerShape(12.dp), color = tint.copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = tint, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ActionBar(
    movement: Movement,
    saving: Boolean,
    onEdit: () -> Unit,
    onVerify: () -> Unit,
    onReject: () -> Unit,
) {
    if (!movement.can_edit && !movement.can_verify) return
    Surface(color = AppSurface.raised) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (movement.can_edit) {
                Button(onClick = onEdit, modifier = Modifier.weight(1f), enabled = !saving) {
                    Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  Continue")
                }
            }
            if (movement.can_verify) {
                OutlinedButton(onClick = onReject, modifier = Modifier.weight(1f), enabled = !saving) {
                    Text("Send back")
                }
                Button(onClick = onVerify, modifier = Modifier.weight(1f), enabled = !saving) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  Verify")
                }
            }
        }
    }
}
