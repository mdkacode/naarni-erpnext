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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.naarni.service.data.dto.FormContext
import com.naarni.service.data.dto.SuggestionItem
import com.naarni.service.ui.AppViewModel
import com.naarni.service.ui.components.SmartSelect
import com.naarni.service.ui.components.StampingCamera
import kotlinx.coroutines.launch
import java.io.File

private val JOB_CARD_TYPES = listOf("PMS + Repair", "Only Repair", "Software Update", "Breakdown")

/**
 * Auto-fill create flow: pick type + vehicle, one `get_job_card_form_context`
 * call pre-fills everything, the engineer taps a complaint and an optional
 * stamped photo, then submits (creates the card + uploads the photo).
 */
@Composable
fun CreateJobCardScreen(vm: AppViewModel, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(JOB_CARD_TYPES.first()) }
    var vehicle by remember { mutableStateOf<SuggestionItem?>(null) }
    var context by remember { mutableStateOf<FormContext?>(null) }
    var odometer by remember { mutableStateOf("") }
    var complaint by remember { mutableStateOf<SuggestionItem?>(null) }
    var photo by remember { mutableStateOf<File?>(null) }
    var showCamera by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var createdName by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    if (showCamera) {
        StampingCamera(
            onCaptured = { file -> photo = file; showCamera = false },
            onClose = { showCamera = false },
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
        Text("New Job Card", style = MaterialTheme.typography.titleLarge)

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            JOB_CARD_TYPES.forEach { t ->
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
                    AutoRow("Customer", ctx.customer_name)
                    AutoRow("Depot", ctx.depot_name)
                    AutoRow("OEM", ctx.oem)
                    AutoRow("Service type", ctx.service_type)
                    AutoRow("Priority", ctx.priority)
                    if (type == "PMS + Repair") AutoRow("Check sheet", ctx.check_sheet)
                    if (type == "Breakdown") AutoRow("Last PMS", ctx.last_pms_date)
                }
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

            Button(
                onClick = {
                    val c = context ?: return@Button
                    submitting = true
                    error = null
                    scope.launch {
                        runCatching {
                            val name = vm.jobCards.createJobCard(
                                vehicleNumber = c.vehicle_number ?: vehicle?.label.orEmpty(),
                                odometer = odometer.toIntOrNull() ?: (c.odometer_estimate ?: 0),
                                serviceType = c.service_type ?: "Other",
                                jobCardType = type,
                                complaint = complaint?.label.orEmpty(),
                            )
                            photo?.let { runCatching { vm.jobCards.uploadPhoto(it, name) } }
                            name
                        }.onSuccess { createdName = it; submitting = false }
                            .onFailure { error = it.message; submitting = false }
                    }
                },
                enabled = complaint != null && odometer.isNotBlank() && !submitting,
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

@Composable
private fun AutoRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}
