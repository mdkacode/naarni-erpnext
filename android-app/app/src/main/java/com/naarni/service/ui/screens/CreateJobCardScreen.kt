package com.naarni.service.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.naarni.service.core.feedback.LocalFeedback
import com.naarni.service.data.dto.FormContext
import com.naarni.service.data.dto.InspectionSheet
import com.naarni.service.data.dto.SuggestionItem
import com.naarni.service.ui.AppViewModel
import androidx.compose.runtime.mutableStateListOf
import com.naarni.service.ui.components.InspectionChecklist
import com.naarni.service.ui.components.MultiSelectField
import com.naarni.service.ui.components.SmartSelect
import com.naarni.service.ui.components.StampingCamera
import com.naarni.service.ui.components.rememberInspectionState
import kotlinx.coroutines.launch
import java.io.File

/**
 * Auto-fill create flow: pick type + vehicle, one `get_job_card_form_context`
 * call pre-fills everything, the engineer taps a complaint and an optional
 * stamped photo, then submits (creates the card + uploads the photo).
 */
@Composable
fun CreateJobCardScreen(vm: AppViewModel, onDone: () -> Unit, onBack: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val feedback = LocalFeedback.current
    val jobCardTypes = vm.opt("job_card_type")
    var type by remember { mutableStateOf(jobCardTypes.firstOrNull() ?: "PMS + Repair") }
    var vehicle by remember { mutableStateOf<SuggestionItem?>(null) }
    var context by remember { mutableStateOf<FormContext?>(null) }
    var odometer by remember { mutableStateOf("") }
    var complaint by remember { mutableStateOf<SuggestionItem?>(null) }
    var depotPick by remember { mutableStateOf<SuggestionItem?>(null) }
    var customerPick by remember { mutableStateOf<SuggestionItem?>(null) }
    var showAddCustomer by remember { mutableStateOf(false) }
    var photo by remember { mutableStateOf<File?>(null) }
    var showCamera by remember { mutableStateOf(false) }
    var vinPhoto by remember { mutableStateOf<File?>(null) }
    var showVinCamera by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var createdName by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Inspection POC (PRD: "Technician Name or Self") and the subsystem
    // multiselect (Only Repair / Software Update / Breakdown).
    var pocSelf by remember { mutableStateOf(true) }
    var pocTech by remember { mutableStateOf<SuggestionItem?>(null) }
    val subsystems = remember { mutableStateListOf<SuggestionItem>() }

    // PMS inspection check sheet (auto-selected by odometer band), and the
    // technician's per-component results. Only relevant for "PMS + Repair".
    var sheet by remember { mutableStateOf<InspectionSheet?>(null) }
    val inspection = rememberInspectionState()
    val odoBand = sheetBand(odometer.toIntOrNull())
    LaunchedEffect(type, odoBand) {
        val odo = odometer.toIntOrNull()
        if (type == "PMS + Repair" && odo != null && odo > 0) {
            runCatching { vm.jobCards.inspectionSheet(odo) }.onSuccess { sheet = it }
        } else {
            sheet = null
        }
    }

    if (showCamera) {
        StampingCamera(
            onCaptured = { file -> photo = file; showCamera = false },
            onClose = { showCamera = false },
        )
        return
    }

    if (showVinCamera) {
        StampingCamera(
            onCaptured = { file -> vinPhoto = file; showVinCamera = false },
            onClose = { showVinCamera = false },
        )
        return
    }

    if (createdName != null) {
        SuccessView(name = createdName!!, onDone = onDone)
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("New Job Card", style = MaterialTheme.typography.titleLarge)
        }

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            jobCardTypes.forEach { t ->
                FilterChip(selected = type == t, onClick = { type = t; context = null; complaint = null }, label = { Text(t) })
            }
        }

        SmartSelect(
            label = "Vehicle",
            value = vehicle,
            placeholder = "Tap to pick a vehicle",
            fetchOnOpen = true,
            fetch = { q ->
                vm.jobCards.searchVehicles(q).map {
                    SuggestionItem(
                        value = it.name,
                        label = it.registration_number ?: it.name,
                        sublabel = listOfNotNull(it.make_model, it.operator ?: it.customer).joinToString(" · "),
                    )
                }
            },
            onSelect = { sel ->
                vehicle = sel
                error = null
                scope.launch {
                    runCatching { vm.jobCards.formContext(sel.value, type, null) }
                        .onSuccess { context = it; odometer = it.odometer_estimate?.toString() ?: "" }
                        .onFailure { error = it.message }
                }
            },
        )

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        context?.let { ctx ->
            Surface(shape = MaterialTheme.shapes.large, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Auto-filled", fontWeight = FontWeight.SemiBold)
                    if (!ctx.customer.isNullOrBlank()) AutoRow("Customer", ctx.customer_name ?: ctx.customer)
                    else if (customerPick != null) AutoRow("Customer", customerPick?.label)
                    if (!ctx.depot.isNullOrBlank()) AutoRow("Depot", ctx.depot_name ?: ctx.depot)
                    AutoRow("OEM", ctx.oem)
                    AutoRow("Service type", ctx.service_type)
                    AutoRow("Priority", ctx.priority)
                    if (type == "PMS + Repair") AutoRow("Check sheet", ctx.check_sheet)
                    if (type == "Breakdown") AutoRow("Last PMS", ctx.last_pms_date)
                }
            }

            // This vehicle has no customer on file — pick one or add it then & there
            if (ctx.customer.isNullOrBlank()) {
                SmartSelect(
                    label = "Customer (required)",
                    value = customerPick,
                    placeholder = "Pick the customer",
                    fetchOnOpen = true,
                    fetch = { q ->
                        vm.jobCards.searchCustomers(q).map {
                            SuggestionItem(value = it.name, label = it.customer_name ?: it.name, sublabel = it.mobile_no)
                        }
                    },
                    onSelect = { customerPick = it },
                )
                TextButton(onClick = { showAddCustomer = true }) { Text("+ Add new customer") }
                if (showAddCustomer) {
                    AddCustomerDialog(
                        onDismiss = { showAddCustomer = false },
                        onCreate = { name, phone ->
                            scope.launch {
                                runCatching { vm.jobCards.createCustomer(name, phone) }
                                    .onSuccess {
                                        customerPick = SuggestionItem(value = it.name, label = it.customer_name ?: name, sublabel = phone.ifBlank { null })
                                        showAddCustomer = false
                                    }
                                    .onFailure { error = it.message ?: "Could not add customer" }
                            }
                        },
                    )
                }
            }

            // This vehicle has no depot on file — make the SE pick one (PRD: depot is mandatory)
            if (ctx.depot.isNullOrBlank()) {
                SmartSelect(
                    label = "Depot (required)",
                    value = depotPick,
                    placeholder = "Pick the servicing depot",
                    fetch = { q ->
                        vm.jobCards.searchDepots(q).map {
                            SuggestionItem(value = it.name, label = it.depot_name ?: it.name, sublabel = listOfNotNull(it.city, it.state).joinToString(", ").ifBlank { null })
                        }
                    },
                    onSelect = { depotPick = it },
                )
            }

            OutlinedTextField(
                value = odometer,
                onValueChange = { if (it.all(Char::isDigit)) odometer = it },
                label = { Text("Odometer (km)") },
                supportingText = { Text("Auto-estimated — adjust if needed") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            // Inspection POC — Self (the SE) or a named Technician.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Inspection by", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = pocSelf, onClick = { pocSelf = true; pocTech = null }, label = { Text("Self") })
                    FilterChip(selected = !pocSelf, onClick = { pocSelf = false }, label = { Text("Technician") })
                }
                if (!pocSelf) {
                    SmartSelect(
                        label = "Technician",
                        value = pocTech,
                        placeholder = "Assign a technician",
                        fetchOnOpen = true,
                        fetch = { q -> vm.jobCards.usersByRole("Technician", q) },
                        onSelect = { pocTech = it },
                    )
                }
            }

            // Subsystem multiselect — which subsystems are damaged / to be worked on.
            // PMS health comes from the check sheet, so this is for the other types.
            if (type != "PMS + Repair") {
                MultiSelectField(
                    label = "Subsystems affected",
                    selected = subsystems,
                    placeholder = "Add affected subsystems",
                    suggestions = ctx.suggested_subsystems,
                    fetch = { q -> vm.jobCards.subsystems(q) },
                    onAdd = { subsystems.add(it) },
                    onRemove = { subsystems.remove(it) },
                )
            }

            // PMS inspection checklist (component-by-component health) — feeds Pre-PMS scores.
            if (type == "PMS + Repair") {
                sheet?.let { InspectionChecklist(it, inspection) }
            }

            SmartSelect(
                label = "Complaint / VOC",
                value = complaint,
                placeholder = "Pick what the driver reported",
                suggestions = ctx.suggested_complaints,
                fetch = { q -> vm.jobCards.complaints(q) },
                onSelect = { complaint = it },
            )

            OutlinedButton(
                onClick = { showCamera = true },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text(if (photo == null) "📷  Capture stamped photo" else "✓ Photo captured — retake")
            }

            // VIN / chassis plate photo — vehicle identity for Repair / Software / Breakdown.
            if (type != "PMS + Repair") {
                OutlinedButton(
                    onClick = { showVinCamera = true },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Text(if (vinPhoto == null) "🪪  Capture VIN / chassis plate" else "✓ VIN photo captured — retake")
                }
            }

            Button(
                onClick = {
                    val c = context ?: return@Button
                    feedback.tap()
                    submitting = true
                    error = null
                    scope.launch {
                        runCatching {
                            val activeSheet = sheet.takeIf { type == "PMS + Repair" }
                            val name = vm.jobCards.createJobCard(
                                vehicleNumber = c.vehicle_number ?: vehicle?.label.orEmpty(),
                                odometer = odometer.toIntOrNull() ?: (c.odometer_estimate ?: 0),
                                serviceType = c.service_type ?: "Other",
                                jobCardType = type,
                                complaint = complaint?.label.orEmpty(),
                                depot = (c.depot?.takeIf { it.isNotBlank() } ?: depotPick?.value).orEmpty(),
                                customer = (c.customer?.takeIf { it.isNotBlank() } ?: customerPick?.value).orEmpty(),
                                inspectionSheetId = activeSheet?.sheet_id.orEmpty(),
                                inspectionResults = activeSheet?.let { inspection.toResultsJson(it.items) } ?: "{}",
                                inspectionPoc = if (pocSelf) "" else pocTech?.value.orEmpty(),
                                subsystems = if (type != "PMS + Repair") subsystems.map { it.value } else emptyList(),
                            )
                            photo?.let { runCatching { vm.jobCards.uploadPhoto(it, name) } }
                            vinPhoto?.let { runCatching { vm.jobCards.uploadPhoto(it, name) } }
                            name
                        }.onSuccess { createdName = it; submitting = false; feedback.success() }
                            .onFailure { error = it.message; submitting = false; feedback.error() }
                    }
                },
                enabled = complaint != null && odometer.isNotBlank() && !submitting &&
                    (!ctx.depot.isNullOrBlank() || depotPick != null) &&
                    (!ctx.customer.isNullOrBlank() || customerPick != null),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                if (submitting) CircularProgressIndicator(Modifier.height(22.dp), color = Color.White, strokeWidth = 2.dp)
                else Text("Create Job Card", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SuccessView(name: String, onDone: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.height(72.dp))
        Spacer(Modifier.height(16.dp))
        Text("Job Card created", style = MaterialTheme.typography.titleLarge)
        Text(name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onDone, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().height(54.dp)) {
            Text("Done")
        }
    }
}

/** PRD odometer band → sheet id, used only to avoid refetching within the same band. */
private fun sheetBand(odo: Int?): String = when {
    odo == null || odo <= 0 -> "none"
    odo > 80_000 -> "D"
    odo > 40_000 -> "C"
    odo > 20_000 -> "B"
    else -> "A"
}

@Composable
private fun AutoRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

/** Add a customer on the spot (name + phone) when the vehicle has none. */
@Composable
private fun AddCustomerDialog(onDismiss: () -> Unit, onCreate: (name: String, phone: String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add customer") },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Customer name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = phone, onValueChange = { phone = it },
                    label = { Text("Phone number") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name.trim(), phone.trim()) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
