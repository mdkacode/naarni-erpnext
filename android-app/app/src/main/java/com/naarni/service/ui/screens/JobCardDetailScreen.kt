package com.naarni.service.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.naarni.service.data.dto.JobCardDetail
import com.naarni.service.ui.AppViewModel
import com.naarni.service.ui.components.StatusChip
import kotlinx.coroutines.launch

private val PRIORITIES = listOf("Low", "Medium", "High", "Urgent")

/**
 * Job Card detail + edit. The SE taps a card to open this: review the vehicle /
 * complaint, fill in observations + priority, and **progress the workflow** via the
 * state-appropriate action buttons (PRD job-card lifecycle).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun JobCardDetailScreen(vm: AppViewModel, jobCard: String, onBack: () -> Unit) {
    var detail by remember { mutableStateOf<JobCardDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var snack by remember { mutableStateOf<String?>(null) }

    var complaint by remember { mutableStateOf("") }
    var observations by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("Medium") }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        runCatching { vm.jobCards.jobCardDetail(jobCard) }
            .onSuccess {
                detail = it
                complaint = it.complaint_description ?: ""
                observations = it.se_observations ?: ""
                priority = it.priority ?: "Medium"
                error = null
            }
            .onFailure { error = it.message ?: "Could not load" }
    }
    LaunchedEffect(jobCard) { loading = true; load(); loading = false }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail?.vehicle_number ?: jobCard) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { pad ->
        val d = detail
        when {
            loading -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            d == null -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text(error ?: "Not found", color = MaterialTheme.colorScheme.error)
            }
            else -> Column(
                Modifier.fillMaxSize().padding(pad).background(MaterialTheme.colorScheme.background)
                    .verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Status + type
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(d.job_card_type ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StatusChip(d.workflow_state)
                }

                InfoCard(
                    "Details",
                    listOf(
                        "Vehicle" to d.vehicle_number,
                        "Make / Model" to d.vehicle_make_model,
                        "Customer" to d.customer_name,
                        "Service" to d.service_type,
                        "Depot" to d.depot,
                        "Odometer" to d.odometer_reading?.let { "$it km" },
                        "Opened" to d.opened_at?.take(16),
                    ),
                )
                if ((d.pre_pms_score ?: 0.0) > 0 || (d.post_pms_score ?: 0.0) > 0) {
                    InfoCard(
                        "Health",
                        listOf(
                            "Pre-PMS score" to d.pre_pms_score?.toString(),
                            "Post-PMS score" to d.post_pms_score?.toString(),
                        ),
                    )
                }

                // Editable fields
                Text("Complaint / VOC", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = complaint, onValueChange = { complaint = it },
                    modifier = Modifier.fillMaxWidth(), minLines = 2,
                    placeholder = { Text("What's the issue?") },
                )
                Text("Engineer observations", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = observations, onValueChange = { observations = it },
                    modifier = Modifier.fillMaxWidth(), minLines = 2,
                    placeholder = { Text("Your findings / work notes") },
                )
                Text("Priority", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PRIORITIES.forEach { p ->
                        FilterChip(selected = priority == p, onClick = { priority = p }, label = { Text(p) })
                    }
                }

                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            runCatching {
                                vm.jobCards.updateJobCard(
                                    d.name,
                                    mapOf(
                                        "complaint_description" to complaint,
                                        "se_observations" to observations,
                                        "priority" to priority,
                                    ),
                                )
                            }.onSuccess { snack = "Saved"; load() }
                                .onFailure { snack = it.message ?: "Save failed" }
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                ) { Text("Save") }

                // Workflow actions
                if (d.available_actions.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("Progress this job", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        d.available_actions.forEach { action ->
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        runCatching { vm.jobCards.transitionJobCard(d.name, action) }
                                            .onSuccess { snack = action; load() }
                                            .onFailure { snack = it.message ?: "Action failed" }
                                        busy = false
                                    }
                                },
                                enabled = !busy,
                            ) { Text(action) }
                        }
                    }
                }

                snack?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, rows: List<Pair<String, String?>>) {
    val shown = rows.filter { !it.second.isNullOrBlank() }
    if (shown.isEmpty()) return
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            shown.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(k, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(v!!, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
