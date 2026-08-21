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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.toMutableStateList
import com.naarni.service.data.dto.BreakdownInfo
import com.naarni.service.data.dto.SuggestionItem
import com.naarni.service.ui.AppViewModel
import com.naarni.service.ui.components.MultiSelectField
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun nowStamp(): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

/** Breakdown diagnosis + travel/trial-trip + RCA (PRD breakdown flow). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BreakdownEditor(
    vm: AppViewModel,
    initial: BreakdownInfo,
    saving: Boolean,
    onSave: (Map<String, String>, List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val groups = remember { initial.groups_impacted.map { SuggestionItem(value = it, label = it) }.toMutableStateList() }
        var incidentPlace by remember { mutableStateOf(initial.incident_place ?: "") }
        var fc1 by remember { mutableStateOf(initial.fault_code_1 ?: "") }
        var fc2 by remember { mutableStateOf(initial.fault_code_2 ?: "") }
        var fc3 by remember { mutableStateOf(initial.fault_code_3 ?: "") }
        var remoteStatus by remember { mutableStateOf(initial.remote_resolution_status ?: "") }
        var travelStarted by remember { mutableStateOf(initial.travel_started_at ?: "") }
        var arrived by remember { mutableStateOf(initial.arrived_at_location ?: "") }
        var trialStartKm by remember { mutableStateOf("") }
        var trialEndKm by remember { mutableStateOf("") }
        var fixType by remember { mutableStateOf(initial.fix_type ?: "") }
        var recurrence by remember { mutableStateOf(initial.recurrence_risk ?: "") }
        var occurrence by remember { mutableStateOf(initial.occurrence_risk ?: "") }
        var rca by remember { mutableStateOf(initial.rca_notes ?: "") }
        val scope = rememberCoroutineScope()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Breakdown diagnosis") },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close") } },
                )
            },
        ) { pad ->
            Column(
                Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Chips("Incident place", vm.opt("incident_place"), incidentPlace) { incidentPlace = it }
                OutlinedTextField(fc1, { fc1 = it }, label = { Text("Fault code 1") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(fc2, { fc2 = it }, label = { Text("Fault code 2") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(fc3, { fc3 = it }, label = { Text("Fault code 3") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                MultiSelectField(
                    label = "Groups impacted",
                    selected = groups,
                    placeholder = "Add affected part groups",
                    fetch = { q -> vm.jobCards.partGroupOptions(q) },
                    onAdd = { groups.add(it) },
                    onRemove = { groups.remove(it) },
                )
                Chips("Remote resolution", vm.opt("remote_status"), remoteStatus) { remoteStatus = it }

                Text("Travel tracking", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { travelStarted = nowStamp() }, modifier = Modifier.weight(1f)) {
                        Text(if (travelStarted.isBlank()) "Travel started" else "Started ✓")
                    }
                    OutlinedButton(onClick = { arrived = nowStamp() }, modifier = Modifier.weight(1f)) {
                        Text(if (arrived.isBlank()) "Arrived" else "Arrived ✓")
                    }
                }

                Text("Trial trip", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(trialStartKm, { trialStartKm = it }, label = { Text("Start km") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(trialEndKm, { trialEndKm = it }, label = { Text("End km") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.weight(1f))
                }

                Chips("Fix type", vm.opt("fix_type"), fixType) { fixType = it }
                Chips("Recurrence risk", vm.opt("risk"), recurrence) { recurrence = it }
                Chips("Occurrence risk", vm.opt("risk"), occurrence) { occurrence = it }
                OutlinedTextField(rca, { rca = it }, label = { Text("RCA notes") }, minLines = 2, modifier = Modifier.fillMaxWidth())

                Button(
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val updates = buildMap {
                            if (incidentPlace.isNotBlank()) put("incident_place", incidentPlace)
                            if (fc1.isNotBlank()) put("fault_code_1", fc1)
                            if (fc2.isNotBlank()) put("fault_code_2", fc2)
                            if (fc3.isNotBlank()) put("fault_code_3", fc3)
                            if (remoteStatus.isNotBlank()) put("remote_resolution_status", remoteStatus)
                            if (travelStarted.isNotBlank()) put("travel_started_at", travelStarted)
                            if (arrived.isNotBlank()) put("arrived_at_location", arrived)
                            if (trialStartKm.isNotBlank()) put("trial_trip_start_km", trialStartKm)
                            if (trialEndKm.isNotBlank()) put("trial_trip_end_km", trialEndKm)
                            if (fixType.isNotBlank()) put("fix_type", fixType)
                            if (recurrence.isNotBlank()) put("recurrence_risk", recurrence)
                            if (occurrence.isNotBlank()) put("occurrence_risk", occurrence)
                            if (rca.isNotBlank()) put("rca_notes", rca)
                        }
                        scope.launch { onSave(updates, groups.map { it.value }) }
                    },
                ) { Text(if (saving) "Saving…" else "Save diagnosis") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o ->
                FilterChip(selected = selected == o, onClick = { onSelect(o) }, label = { Text(o) })
            }
        }
    }
}
