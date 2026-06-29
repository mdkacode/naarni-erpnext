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
    var partGroups by remember { mutableStateOf<List<com.naarni.service.data.dto.PartGroupItem>>(emptyList()) }
    var showRepairEditor by remember { mutableStateOf(false) }
    var showMaintenanceEditor by remember { mutableStateOf(false) }
    var showInventoryDialog by remember { mutableStateOf(false) }
    var showForceClose by remember { mutableStateOf(false) }
    var showReopen by remember { mutableStateOf(false) }
    var showReject by remember { mutableStateOf(false) }
    var showSoftwareEditor by remember { mutableStateOf(false) }
    var showBreakdownEditor by remember { mutableStateOf(false) }
    var reportToCustomer by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<com.naarni.service.data.dto.CustomerFeedbackData?>(null) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    suspend fun load() {
        runCatching { vm.jobCards.jobCardDetail(jobCard) }
            .onSuccess {
                detail = it
                complaint = it.complaint_description ?: ""
                observations = it.se_observations ?: ""
                priority = it.priority ?: "Medium"
                reportToCustomer = it.send_report_to_customer == 1
                error = null
                if (it.workflow_state == "Closed") {
                    runCatching { vm.jobCards.customerFeedback(it.name) }.onSuccess { fb -> feedback = fb }
                }
            }
            .onFailure { error = it.message ?: "Could not load" }
    }
    LaunchedEffect(jobCard) {
        loading = true; load(); loading = false
        runCatching { vm.jobCards.partGroups() }.onSuccess { partGroups = it }
    }

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
                // Force-close / approval / SLA banners
                if (d.force_closed == 1) {
                    Banner(
                        "Force Closed — ${d.force_close_severity ?: ""}",
                        d.force_close_reason,
                        MaterialTheme.colorScheme.errorContainer,
                    )
                }
                val canRecordApproval = d.workflow_state == "Awaiting Customer Approval" &&
                    d.viewer_roles.any { it in listOf("Service Engineer", "Depot Manager") }
                if (d.workflow_state == "Awaiting Customer Approval") {
                    SectionCard("Customer Approval") {
                        Text(
                            "Estimate sent — record the customer's decision.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (canRecordApproval) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        scope.launch {
                                            busy = true
                                            runCatching {
                                                vm.jobCards.recordApprovalDecision(d.name, approved = true)
                                                vm.jobCards.transitionJobCard(d.name, "Customer Approves")
                                            }.onSuccess { snack = "Customer approved"; load() }
                                                .onFailure { snack = it.message ?: "Failed" }
                                            busy = false
                                        }
                                    },
                                ) { Text("Approved") }
                                OutlinedButton(
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f),
                                    onClick = { showReject = true },
                                ) { Text("Rejected") }
                            }
                        }
                    }
                }
                if (d.sla_breached == 1) {
                    Banner("SLA breached", "This job card has crossed its TAT target.", MaterialTheme.colorScheme.errorContainer)
                }

                if ((d.pre_pms_score ?: 0.0) > 0 || (d.post_pms_score ?: 0.0) > 0) {
                    InfoCard(
                        "Vehicle Health",
                        listOf(
                            "Pre-PMS score" to d.pre_pms_score?.let { "%.1f%%".format(it) },
                            "Post-PMS score" to d.post_pms_score?.let { "%.1f%%".format(it) },
                            "Improvement" to d.score_improvement?.let { "%.1f%%".format(it) },
                        ),
                    )
                }
                if (d.category_scores.isNotEmpty()) {
                    SectionCard("Health by Category") {
                        d.category_scores.forEach { c ->
                            LineRow(
                                c.category ?: "—",
                                "${c.pre_pms_score?.let { "%.0f".format(it) } ?: "—"}% → ${c.post_pms_score?.let { "%.0f".format(it) } ?: "—"}%",
                            )
                        }
                    }
                }

                val canEdit = d.workflow_state !in listOf("Closed", "Force Closed") &&
                    d.viewer_roles.any { it in listOf("Technician", "Service Engineer", "Depot Manager") }
                val showRepair = d.job_card_type != "Software Update"
                val showMaint = d.job_card_type == "PMS + Repair"

                // Repair Job List
                if (showRepair && (d.repair_items.isNotEmpty() || canEdit)) {
                    SectionCard("Repair Job List (${d.repair_items.size})") {
                        d.repair_items.forEach { r ->
                            ItemBlock(
                                title = listOfNotNull(r.part_group, r.activity_type).joinToString(" · ").ifBlank { r.description ?: "Item" },
                                subtitle = r.description,
                                tags = listOfNotNull(
                                    r.component_status?.let { "Status: $it" },
                                    r.qty?.let { "Qty ${fmtQty(it)}" },
                                    (r.actual_amount ?: r.estimated_amount)?.takeIf { it > 0 }?.let { "₹${fmtQty(it)}" },
                                    r.item_status,
                                ),
                            )
                        }
                        if (canEdit) {
                            OutlinedButton(onClick = { showRepairEditor = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (d.repair_items.isEmpty()) "Add repair jobs" else "Edit repair jobs")
                            }
                        }
                    }
                }

                // Maintenance Job List
                if (showMaint && (d.maintenance_items.isNotEmpty() || canEdit)) {
                    SectionCard("Maintenance Job List (${d.maintenance_items.size})") {
                        d.maintenance_items.forEach { m ->
                            ItemBlock(
                                title = listOfNotNull(m.maintenance_type, m.action).joinToString(" · ").ifBlank { m.description ?: "Item" },
                                subtitle = m.description,
                                tags = listOfNotNull(
                                    m.qty?.let { "Qty ${fmtQty(it)}${m.unit?.let { u -> " $u" } ?: ""}" },
                                    (m.actual_amount ?: m.estimated_amount)?.takeIf { it > 0 }?.let { "₹${fmtQty(it)}" },
                                    m.item_status,
                                ),
                            )
                        }
                        if (canEdit) {
                            OutlinedButton(onClick = { showMaintenanceEditor = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (d.maintenance_items.isEmpty()) "Add maintenance items" else "Edit maintenance items")
                            }
                        }
                    }
                }

                // Software components
                if (d.job_card_type == "Software Update" && (d.software_components.isNotEmpty() || canEdit)) {
                    SectionCard("Software Update (${d.software_components.size})") {
                        d.software_components.forEach { s ->
                            ItemBlock(
                                title = s.component ?: "Component",
                                subtitle = listOfNotNull(s.pre_version, s.post_version).takeIf { it.isNotEmpty() }
                                    ?.let { "${s.pre_version ?: "?"} → ${s.post_version ?: "?"}" } ?: s.reason,
                                tags = listOfNotNull(s.status, s.retry_count?.takeIf { it > 0 }?.let { "Retries: $it" }),
                            )
                        }
                        if (canEdit) {
                            OutlinedButton(onClick = { showSoftwareEditor = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (d.software_components.isEmpty()) "Add components" else "Edit components")
                            }
                        }
                    }
                }

                // Breakdown panel
                d.breakdown?.let { b ->
                    InfoCard(
                        "Breakdown",
                        listOf(
                            "Incident place" to b.incident_place,
                            "Remote status" to b.remote_resolution_status,
                            "Fault codes" to listOfNotNull(b.fault_code_1, b.fault_code_2, b.fault_code_3).joinToString(", ").ifBlank { null },
                            "Travel duration" to b.travel_duration_minutes?.takeIf { it > 0 }?.let { "${it.toInt()} min" },
                            "Trial trip" to b.trial_trip_distance_km?.takeIf { it > 0 }?.let { "${fmtQty(it)} km" },
                            "Total downtime" to b.total_downtime_minutes?.takeIf { it > 0 }?.let { "${it.toInt()} min" },
                            "Fix type" to b.fix_type,
                            "RCA" to b.rca_notes,
                        ),
                    )
                    if (canEdit) {
                        OutlinedButton(onClick = { showBreakdownEditor = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Edit breakdown diagnosis")
                        }
                    }
                }

                // Inventory requests — raise + allocate/issue/acknowledge (PRD inventory flow)
                val isDepotMgr = d.viewer_is_depot_manager
                val isTechOrSe = d.viewer_roles.any { it in listOf("Technician", "Service Engineer") }
                if (showRepair && (d.inventory_requests.isNotEmpty() || canEdit)) {
                    SectionCard("Inventory Requests (${d.inventory_requests.size})") {
                        d.inventory_requests.forEach { iv ->
                            ItemBlock(
                                title = iv.part_name ?: iv.part ?: "Part",
                                subtitle = iv.part_group,
                                tags = listOfNotNull(
                                    iv.quantity?.let { "Qty ${fmtQty(it)}" },
                                    iv.urgency_level,
                                    iv.status,
                                ),
                            )
                            // Next-step action, role + status gated
                            val (nextLabel, nextStatus, allowed) = when (iv.status) {
                                "Requested" -> Triple("Allocate parts", "Parts Allocated", isDepotMgr)
                                "Parts Allocated" -> Triple("Mark issued", "Parts Issued", isDepotMgr)
                                "Parts Issued" -> Triple("Acknowledge receipt", "Received", isTechOrSe)
                                else -> Triple(null, null, false)
                            }
                            if (nextLabel != null && allowed) {
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            busy = true
                                            runCatching { vm.jobCards.advanceInventoryStatus(iv.name, nextStatus!!) }
                                                .onSuccess { snack = nextLabel; load() }
                                                .onFailure { snack = it.message ?: "Action failed" }
                                            busy = false
                                        }
                                    },
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(nextLabel) }
                            }
                        }
                        if (canEdit && isTechOrSe || isDepotMgr) {
                            Button(onClick = { showInventoryDialog = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                Text("Raise inventory request")
                            }
                        }
                    }
                }

                // Bus photos (this visit) — multi-angle, stamped
                SectionCard("Bus Photos") {
                    com.naarni.service.ui.components.BusPhotoGrid(
                        vm = vm,
                        parentDoctype = "Job Card",
                        parentName = d.name,
                    )
                }

                // Service report (proof of service) — share / email on closure
                if (d.workflow_state == "Closed") {
                    SectionCard("Service Report (proof)") {
                        Text(
                            "NaArNi-watermarked PDF with photos, parts and health scores.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        runCatching { vm.jobCards.shareClosureReport(d.name) }
                                            .onSuccess { rep ->
                                                val text = rep.message ?: rep.pdf_url ?: ""
                                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(android.content.Intent.EXTRA_TEXT, text)
                                                }
                                                runCatching {
                                                    context.startActivity(
                                                        android.content.Intent.createChooser(send, "Share service report")
                                                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                                    )
                                                }
                                            }
                                            .onFailure { snack = it.message ?: "Could not prepare report" }
                                        busy = false
                                    }
                                },
                            ) { Text("Share / WhatsApp") }
                            OutlinedButton(
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        runCatching { vm.jobCards.emailClosureReport(d.name) }
                                            .onSuccess { to -> snack = if (to.isNotBlank()) "Emailed to $to" else "Report emailed" }
                                            .onFailure { snack = it.message ?: "Email failed" }
                                        busy = false
                                    }
                                },
                            ) { Text("Email customer") }
                        }
                    }
                }

                // Customer feedback (post-closure)
                if (d.workflow_state == "Closed") {
                    FeedbackCard(
                        existing = feedback,
                        busy = busy,
                        onSubmit = { rating, comment, recommend ->
                            scope.launch {
                                busy = true
                                // Map the 1–5 star rating onto a 0–10 NPS-style score.
                                runCatching { vm.jobCards.submitCustomerFeedback(d.name, rating, comment, rating * 2, recommend) }
                                    .onSuccess { snack = "Feedback saved"; runCatching { feedback = vm.jobCards.customerFeedback(d.name) } }
                                    .onFailure { snack = it.message ?: "Could not save feedback" }
                                busy = false
                            }
                        },
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
                    vm.opt("priority").forEach { p ->
                        FilterChip(selected = priority == p, onClick = { priority = p }, label = { Text(p) })
                    }
                }

                // Send summary report (health card) to customer on closure
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Send report to customer", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    androidx.compose.material3.Switch(
                        checked = reportToCustomer,
                        onCheckedChange = { v ->
                            reportToCustomer = v
                            scope.launch {
                                runCatching { vm.jobCards.updateJobCard(d.name, mapOf("send_report_to_customer" to if (v) "1" else "0")) }
                                    .onFailure { snack = it.message ?: "Could not update" }
                            }
                        },
                    )
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

                // Workflow actions (approval + reopen handled by dedicated controls)
                val genericActions = d.available_actions.filter {
                    it !in listOf("Customer Approves", "Customer Rejects", "Reopen")
                }
                if (genericActions.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("Progress this job", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        genericActions.forEach { action ->
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

                // Reopen (Customer / Central Ops, on a Closed card)
                if (d.available_actions.contains("Reopen")) {
                    OutlinedButton(onClick = { showReopen = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Reopen job card")
                    }
                }

                // Force close (severity matrix) — available while the card is open
                if (canEdit && d.force_closed != 1) {
                    OutlinedButton(
                        onClick = { showForceClose = true },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Force close…") }
                }

                snack?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        if (showRepairEditor && d != null) {
            RepairItemsEditor(
                vm = vm,
                jobCardName = d.name,
                initial = d.repair_items,
                partGroups = partGroups,
                saving = busy,
                onDismiss = { showRepairEditor = false },
                onSave = { rows ->
                    scope.launch {
                        busy = true
                        runCatching { vm.jobCards.saveRepairItems(d.name, rows) }
                            .onSuccess { snack = "Repair jobs saved"; showRepairEditor = false; load() }
                            .onFailure { snack = it.message ?: "Save failed" }
                        busy = false
                    }
                },
            )
        }
        if (showMaintenanceEditor && d != null) {
            MaintenanceItemsEditor(
                vm = vm,
                jobCardName = d.name,
                initial = d.maintenance_items,
                saving = busy,
                onDismiss = { showMaintenanceEditor = false },
                onSave = { rows ->
                    scope.launch {
                        busy = true
                        runCatching { vm.jobCards.saveMaintenanceItems(d.name, rows) }
                            .onSuccess { snack = "Maintenance items saved"; showMaintenanceEditor = false; load() }
                            .onFailure { snack = it.message ?: "Save failed" }
                        busy = false
                    }
                },
            )
        }
        if (showInventoryDialog && d != null) {
            InventoryRequestDialog(
                vm = vm,
                saving = busy,
                fetchParts = { q -> vm.jobCards.parts(q).map { com.naarni.service.data.dto.SuggestionItem(value = it.name, label = it.part_name ?: it.name, sublabel = it.part_group) } },
                onDismiss = { showInventoryDialog = false },
                onCreate = { part, qty, urgency ->
                    scope.launch {
                        busy = true
                        runCatching { vm.jobCards.createInventoryRequest(d.name, part, qty, urgency) }
                            .onSuccess { snack = "Request raised"; showInventoryDialog = false; load() }
                            .onFailure { snack = it.message ?: "Request failed" }
                        busy = false
                    }
                },
            )
        }
        if (showForceClose && d != null) {
            ForceCloseDialog(
                vm = vm,
                saving = busy,
                onDismiss = { showForceClose = false },
                onConfirm = { severity, reason ->
                    scope.launch {
                        busy = true
                        runCatching { vm.jobCards.forceCloseJobCard(d.name, severity, reason) }
                            .onSuccess { snack = "Force closed ($severity)"; showForceClose = false; load() }
                            .onFailure { snack = it.message ?: "Force close failed" }
                        busy = false
                    }
                },
            )
        }
        if (showReopen && d != null) {
            ReasonDialog(
                title = "Reopen job card",
                hint = "Why is this being reopened?",
                confirmLabel = "Reopen",
                saving = busy,
                onDismiss = { showReopen = false },
                onConfirm = { reason ->
                    scope.launch {
                        busy = true
                        runCatching { vm.jobCards.reopenJobCard(d.name, reason) }
                            .onSuccess { snack = "Reopened"; showReopen = false; load() }
                            .onFailure { snack = it.message ?: "Reopen failed" }
                        busy = false
                    }
                },
            )
        }
        if (showReject && d != null) {
            ReasonDialog(
                title = "Customer rejected",
                hint = "Reason for rejection",
                confirmLabel = "Record rejection",
                saving = busy,
                onDismiss = { showReject = false },
                onConfirm = { reason ->
                    scope.launch {
                        busy = true
                        runCatching {
                            vm.jobCards.recordApprovalDecision(d.name, approved = false, rejectionFeedback = reason)
                            vm.jobCards.transitionJobCard(d.name, "Customer Rejects")
                        }.onSuccess { snack = "Rejection recorded"; showReject = false; load() }
                            .onFailure { snack = it.message ?: "Failed" }
                        busy = false
                    }
                },
            )
        }
        if (showSoftwareEditor && d != null) {
            SoftwareComponentsEditor(
                vm = vm,
                jobCardName = d.name,
                initial = d.software_components,
                saving = busy,
                onDismiss = { showSoftwareEditor = false },
                onSave = { rows ->
                    scope.launch {
                        busy = true
                        runCatching { vm.jobCards.saveSoftwareComponents(d.name, rows) }
                            .onSuccess { snack = "Components saved"; showSoftwareEditor = false; load() }
                            .onFailure { snack = it.message ?: "Save failed" }
                        busy = false
                    }
                },
            )
        }
        if (showBreakdownEditor && d?.breakdown != null) {
            BreakdownEditor(
                vm = vm,
                initial = d.breakdown,
                saving = busy,
                onDismiss = { showBreakdownEditor = false },
                onSave = { updates ->
                    scope.launch {
                        busy = true
                        runCatching { vm.jobCards.updateBreakdownDiagnosis(d.name, updates) }
                            .onSuccess { snack = "Diagnosis saved"; showBreakdownEditor = false; load() }
                            .onFailure { snack = it.message ?: "Save failed" }
                        busy = false
                    }
                },
            )
        }
    }
}

private fun fmtQty(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else "%.2f".format(v)

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemBlock(title: String, subtitle: String?, tags: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyLarge)
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach { t ->
                    Box(
                        Modifier.background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.small)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(t, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        }
    }
}

@Composable
private fun LineRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Banner(title: String, body: String?, container: androidx.compose.ui.graphics.Color) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            if (!body.isNullOrBlank()) Text(body, style = MaterialTheme.typography.bodyMedium)
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
