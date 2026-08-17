package com.naarni.service.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.naarni.service.data.dto.MaintenanceItem
import com.naarni.service.data.dto.PartGroupItem
import com.naarni.service.data.dto.RepairItem
import com.naarni.service.ui.AppViewModel
import com.naarni.service.ui.components.ItemPhotos
import com.naarni.service.ui.components.PhotoSlot
import com.naarni.service.ui.components.StampingCamera
import kotlinx.coroutines.launch

/** Full-screen editor for the Repair Job List (PRD repair flow). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RepairItemsEditor(
    vm: AppViewModel,
    jobCardName: String,
    initial: List<RepairItem>,
    partGroups: List<PartGroupItem>,
    saving: Boolean,
    onSave: (List<RepairItem>) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val rows = remember { initial.toMutableStateList() }
        val scope = rememberCoroutineScope()
        var capture by remember { mutableStateOf<Pair<Int, String>?>(null) }
        var uploadingKey by remember { mutableStateOf<String?>(null) }
        val cap = capture
        if (cap != null) {
            StampingCamera(
                onCaptured = { file, _ ->
                    val (idx, slot) = cap
                    capture = null
                    uploadingKey = "$idx:$slot"
                    scope.launch {
                        runCatching { vm.jobCards.uploadItemPhoto(file, jobCardName) }.onSuccess { url ->
                            rows[idx] = if (slot == "pre") rows[idx].copy(pre_repair_photo = url)
                            else rows[idx].copy(post_repair_photo = url)
                        }
                        uploadingKey = null
                    }
                },
                onClose = { capture = null },
            )
            return@Dialog
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Repair Jobs") },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close") } },
                )
            },
        ) { pad ->
            Column(
                Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                rows.forEachIndexed { i, r ->
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(1.dp),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text("Job ${i + 1}", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                                IconButton(onClick = { rows.removeAt(i) }) {
                                    Icon(Icons.Rounded.Delete, "Remove", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            ChipSelect("Part group", partGroups.map { it.part_group_name ?: it.name }, r.part_group) {
                                rows[i] = r.copy(part_group = it)
                            }
                            ChipSelect("Activity", vm.opt("activity_type"), r.activity_type) { rows[i] = r.copy(activity_type = it) }
                            ChipSelect("Condition", vm.opt("component_status"), r.component_status) { rows[i] = r.copy(component_status = it) }
                            OutlinedTextField(
                                value = r.description ?: "", onValueChange = { rows[i] = r.copy(description = it) },
                                label = { Text("Description") }, modifier = Modifier.fillMaxWidth(), minLines = 1,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                NumberField("Qty", r.qty, Modifier.weight(1f)) { rows[i] = r.copy(qty = it) }
                                NumberField("Amount ₹", r.estimated_amount, Modifier.weight(1f)) { rows[i] = r.copy(estimated_amount = it) }
                            }
                            ChipSelect("Status", vm.opt("repair_item_status"), r.item_status) { rows[i] = r.copy(item_status = it) }
                            ItemPhotos(
                                slots = listOf(
                                    PhotoSlot("pre", "Pre-repair", r.pre_repair_photo),
                                    PhotoSlot("post", "Post-repair", r.post_repair_photo),
                                ),
                                uploadingKey = uploadingKey?.takeIf { it.startsWith("$i:") }?.substringAfter(":"),
                                onCapture = { slot -> capture = i to slot },
                            )
                        }
                    }
                }
                OutlinedButton(onClick = { rows.add(RepairItem(activity_type = vm.opt("activity_type").firstOrNull(), component_status = vm.opt("component_status").firstOrNull(), qty = 1.0)) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Add, null); Text("  Add repair job")
                }
                Button(
                    onClick = { scope.launch { onSave(rows.toList()) } },
                    enabled = !saving, modifier = Modifier.fillMaxWidth(),
                ) { Text(if (saving) "Saving…" else "Save ${rows.size} job(s)") }
            }
        }
    }
}

/** Full-screen editor for the Maintenance Job List (oils/filters/coolant — PRD PMS flow). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MaintenanceItemsEditor(
    vm: AppViewModel,
    jobCardName: String,
    initial: List<MaintenanceItem>,
    saving: Boolean,
    onSave: (List<MaintenanceItem>) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val rows = remember { initial.toMutableStateList() }
        val scope = rememberCoroutineScope()
        var capture by remember { mutableStateOf<Pair<Int, String>?>(null) }
        var uploadingKey by remember { mutableStateOf<String?>(null) }
        val cap = capture
        if (cap != null) {
            StampingCamera(
                onCaptured = { file, _ ->
                    val (idx, slot) = cap
                    capture = null
                    uploadingKey = "$idx:$slot"
                    scope.launch {
                        runCatching { vm.jobCards.uploadItemPhoto(file, jobCardName) }.onSuccess { url ->
                            rows[idx] = if (slot == "pre") rows[idx].copy(pre_photo = url)
                            else rows[idx].copy(post_photo = url)
                        }
                        uploadingKey = null
                    }
                },
                onClose = { capture = null },
            )
            return@Dialog
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Maintenance Jobs") },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close") } },
                )
            },
        ) { pad ->
            Column(
                Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                rows.forEachIndexed { i, m ->
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(1.dp),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text("Item ${i + 1}", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                                IconButton(onClick = { rows.removeAt(i) }) {
                                    Icon(Icons.Rounded.Delete, "Remove", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            ChipSelect("Type", vm.opt("maintenance_type"), m.maintenance_type) { rows[i] = m.copy(maintenance_type = it) }
                            ChipSelect("Action", vm.opt("maintenance_action"), m.action) { rows[i] = m.copy(action = it) }
                            OutlinedTextField(
                                value = m.description ?: "", onValueChange = { rows[i] = m.copy(description = it) },
                                label = { Text("Description") }, modifier = Modifier.fillMaxWidth(), minLines = 1,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                NumberField("Qty", m.qty, Modifier.weight(1f)) { rows[i] = m.copy(qty = it) }
                                NumberField("Amount ₹", m.estimated_amount, Modifier.weight(1f)) { rows[i] = m.copy(estimated_amount = it) }
                            }
                            ChipSelect("Unit", vm.opt("maintenance_unit"), m.unit) { rows[i] = m.copy(unit = it) }
                            ChipSelect("Status", vm.opt("maintenance_item_status"), m.item_status) { rows[i] = m.copy(item_status = it) }
                            ItemPhotos(
                                slots = listOf(
                                    PhotoSlot("pre", "Pre photo", m.pre_photo),
                                    PhotoSlot("post", "Post photo", m.post_photo),
                                ),
                                uploadingKey = uploadingKey?.takeIf { it.startsWith("$i:") }?.substringAfter(":"),
                                onCapture = { slot -> capture = i to slot },
                            )
                        }
                    }
                }
                OutlinedButton(onClick = { rows.add(MaintenanceItem(maintenance_type = vm.opt("maintenance_type").firstOrNull(), action = vm.opt("maintenance_action").firstOrNull(), qty = 1.0, unit = vm.opt("maintenance_unit").firstOrNull())) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Add, null); Text("  Add maintenance item")
                }
                Button(
                    onClick = { scope.launch { onSave(rows.toList()) } },
                    enabled = !saving, modifier = Modifier.fillMaxWidth(),
                ) { Text(if (saving) "Saving…" else "Save ${rows.size} item(s)") }
            }
        }
    }
}

/** Full-screen editor for the Software Update component list (PRD software flow). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SoftwareComponentsEditor(
    vm: AppViewModel,
    jobCardName: String,
    initial: List<com.naarni.service.data.dto.SoftwareComponent>,
    saving: Boolean,
    onSave: (List<com.naarni.service.data.dto.SoftwareComponent>) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val rows = remember { initial.toMutableStateList() }
        val scope = rememberCoroutineScope()
        var capture by remember { mutableStateOf<Pair<Int, String>?>(null) }
        var uploadingKey by remember { mutableStateOf<String?>(null) }
        val cap = capture
        if (cap != null) {
            StampingCamera(
                onCaptured = { file, _ ->
                    val (idx, slot) = cap
                    capture = null
                    uploadingKey = "$idx:$slot"
                    scope.launch {
                        runCatching { vm.jobCards.uploadItemPhoto(file, jobCardName) }.onSuccess { url ->
                            rows[idx] = when (slot) {
                                "pre" -> rows[idx].copy(pre_version_photo = url)
                                "post" -> rows[idx].copy(post_version_photo = url)
                                else -> rows[idx].copy(calibration_photo = url)
                            }
                        }
                        uploadingKey = null
                    }
                },
                onClose = { capture = null },
            )
            return@Dialog
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Software Update") },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close") } },
                )
            },
        ) { pad ->
            Column(
                Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                rows.forEachIndexed { i, s ->
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(1.dp),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text("Component ${i + 1}", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                                IconButton(onClick = { rows.removeAt(i) }) {
                                    Icon(Icons.Rounded.Delete, "Remove", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            OutlinedTextField(
                                value = s.component ?: "", onValueChange = { rows[i] = s.copy(component = it) },
                                label = { Text("Component / ECU") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                            )
                            ChipSelect("Reason", vm.opt("software_reason"), s.reason) { rows[i] = s.copy(reason = it) }
                            ChipSelect("Status", vm.opt("software_status"), s.status) { rows[i] = s.copy(status = it) }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = s.pre_version ?: "", onValueChange = { rows[i] = s.copy(pre_version = it) },
                                    label = { Text("Pre version") }, modifier = Modifier.weight(1f), singleLine = true,
                                )
                                OutlinedTextField(
                                    value = s.post_version ?: "", onValueChange = { rows[i] = s.copy(post_version = it) },
                                    label = { Text("Post version") }, modifier = Modifier.weight(1f), singleLine = true,
                                )
                            }
                            NumberField("Retry count", s.retry_count?.toDouble()) { rows[i] = s.copy(retry_count = it.toInt()) }
                            OutlinedTextField(
                                value = s.calibration_values ?: "", onValueChange = { rows[i] = s.copy(calibration_values = it) },
                                label = { Text("Calibration values") }, modifier = Modifier.fillMaxWidth(), minLines = 1,
                            )
                            if (s.status == "Failed") {
                                OutlinedTextField(
                                    value = s.failure_notes ?: "", onValueChange = { rows[i] = s.copy(failure_notes = it) },
                                    label = { Text("Failure notes") }, modifier = Modifier.fillMaxWidth(), minLines = 1,
                                )
                            }
                            ItemPhotos(
                                slots = listOf(
                                    PhotoSlot("pre", "Pre ver.", s.pre_version_photo),
                                    PhotoSlot("post", "Post ver.", s.post_version_photo),
                                    PhotoSlot("cal", "Calibration", s.calibration_photo),
                                ),
                                uploadingKey = uploadingKey?.takeIf { it.startsWith("$i:") }?.substringAfter(":"),
                                onCapture = { slot -> capture = i to slot },
                            )
                        }
                    }
                }
                OutlinedButton(onClick = { rows.add(com.naarni.service.data.dto.SoftwareComponent(reason = vm.opt("software_reason").firstOrNull(), status = vm.opt("software_status").firstOrNull(), retry_count = 0)) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Add, null); Text("  Add component")
                }
                Button(
                    onClick = { scope.launch { onSave(rows.toList()) } },
                    enabled = !saving, modifier = Modifier.fillMaxWidth(),
                ) { Text(if (saving) "Saving…" else "Save ${rows.size} component(s)") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipSelect(label: String, options: List<String>, selected: String?, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o ->
                FilterChip(selected = selected == o, onClick = { onSelect(o) }, label = { Text(o) })
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: Double?, modifier: Modifier = Modifier, onChange: (Double) -> Unit) {
    var text by remember { mutableStateOf(value?.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() } ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = { s -> text = s; s.toDoubleOrNull()?.let(onChange) ?: if (s.isBlank()) onChange(0.0) else Unit },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = modifier,
    )
}
