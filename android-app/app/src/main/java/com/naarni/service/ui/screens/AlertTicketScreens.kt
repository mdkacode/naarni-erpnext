package com.naarni.service.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.naarni.service.data.dto.AlertDetail
import com.naarni.service.data.dto.TicketDetail
import com.naarni.service.ui.AppViewModel
import com.naarni.service.ui.components.PriorityPill
import com.naarni.service.ui.components.StatusChip
import kotlinx.coroutines.launch

// ─────────────────────────── shared bits ───────────────────────────

private fun fmtNum(d: Double?): String? {
    if (d == null) return null
    return if (d == d.toLong().toDouble()) d.toLong().toString() else String.format("%.1f", d)
}

private fun openMaps(ctx: android.content.Context, link: String?, lat: Double?, lng: Double?) {
    val uri = when {
        !link.isNullOrBlank() -> android.net.Uri.parse(link)
        lat != null && lng != null -> android.net.Uri.parse("geo:$lat,$lng?q=$lat,$lng")
        else -> return
    }
    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)) }
}

/** A titled card with a coloured left rail. */
@Composable
private fun DetailCard(title: String, rail: Color = MaterialTheme.colorScheme.primary, content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 1.dp, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = rail, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

/** A label → value row; renders nothing when value is blank. */
@Composable
private fun KV(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(140.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun VehicleHeader(reg: String?, subtitle: String?, severity: String?, status: String?, fallback: String) {
    val color = alertSeverityColor(severity)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(52.dp).background(color.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.DirectionsBus, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(reg?.takeIf { it.isNotBlank() } ?: fallback, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!severity.isNullOrBlank()) {
            Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(50)) {
                Text(severity.replaceFirstChar { it.uppercase() }, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
            }
        }
        StatusChip(status)
    }
}

@Composable
private fun Loader(pad: androidx.compose.foundation.layout.PaddingValues) {
    Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

// ─────────────────────────── Alert detail ───────────────────────────

/**
 * Detailed Alert page — everything about one Alert Event: the vehicle, the exact
 * reading that fired vs its threshold, where/when it happened, and a link through
 * to the ticket raised from it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertDetailScreen(
    vm: AppViewModel,
    alertName: String,
    onBack: () -> Unit,
    onOpenTicket: (String) -> Unit = {},
    onOpenJobCard: (String) -> Unit = {},
    onOpenVehicle: (String) -> Unit = {},
) {
    val ctx = LocalContext.current
    var a by remember { mutableStateOf<AlertDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(alertName) {
        loading = true; error = null
        runCatching { vm.jobCards.alertDetail(alertName) }
            .onSuccess { a = it; loading = false }
            .onFailure { error = it.message ?: "Could not load alert"; loading = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Alert details") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        when {
            loading -> Loader(pad)
            a == null -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text(error ?: "Alert not found", color = MaterialTheme.colorScheme.error)
            }
            else -> {
                val d = a!!
                Column(
                    Modifier.fillMaxSize().padding(pad).background(MaterialTheme.colorScheme.background)
                        .verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    VehicleHeader(d.registration_number, humanize(d.alert_type ?: d.title), d.severity, d.status, d.vehicle ?: d.name)

                    // What fired
                    DetailCard("What happened", alertSeverityColor(d.severity)) {
                        Text(humanize(d.title ?: d.alert_type) ?: d.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        // Reading line: parameter op value unit (vs threshold)
                        val reading = buildString {
                            humanize(d.parameter)?.let { append(it) }
                            (d.value_text ?: fmtNum(d.value))?.let { v ->
                                if (isNotEmpty()) append(" = ")
                                append(v); d.unit?.let { append(" $it") }
                            }
                        }.trim()
                        if (reading.isNotBlank()) KV("Reading", reading)
                        d.value_meaning?.let { KV("Meaning", it) }
                        (fmtNum(d.threshold) ?: d.match_value)?.let { KV("Threshold", it + (d.unit?.let { u -> " $u" } ?: "")) }
                        d.condition_text?.let { KV("Condition", it) }
                        d.message?.let { KV("Message", it) }
                        d.details?.let { KV("Details", it) }
                        prettyDateTime(d.occurred_at ?: d.triggered_at)?.let { KV("Occurred", it) }
                        d.channel?.let { KV("Channel", it) }
                    }

                    // Location
                    if (!d.maps_link.isNullOrBlank() || (d.latitude != null && d.longitude != null)) {
                        DetailCard("Location") {
                            d.latitude?.let { lat -> d.longitude?.let { lng -> KV("Coordinates", "%.5f, %.5f".format(lat, lng)) } }
                            OutlinedButton(onClick = { openMaps(ctx, d.maps_link, d.latitude, d.longitude) }) {
                                Icon(Icons.Filled.Place, null); Spacer(Modifier.width(8.dp)); Text("Open in Maps")
                            }
                        }
                    }

                    // Links out
                    d.ticket?.takeIf { it.isNotBlank() }?.let { t ->
                        Button(onClick = { onOpenTicket(t) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                            Icon(Icons.Filled.ConfirmationNumber, null); Spacer(Modifier.width(8.dp)); Text("View linked ticket")
                        }
                    }
                    d.job_card?.takeIf { it.isNotBlank() }?.let { jc ->
                        OutlinedButton(onClick = { onOpenJobCard(jc) }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                            Icon(Icons.AutoMirrored.Filled.Assignment, null); Spacer(Modifier.width(8.dp)); Text("Open job card")
                        }
                    }
                    d.vehicle?.takeIf { it.isNotBlank() }?.let { v ->
                        OutlinedButton(onClick = { onOpenVehicle(v) }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                            Icon(Icons.Filled.DirectionsBus, null); Spacer(Modifier.width(8.dp)); Text("Open vehicle")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

// ─────────────────────────── Ticket detail ───────────────────────────

/**
 * Ticket screen behind an alert — the full Service Ticket: status/severity, the
 * captured vehicle telemetry snapshot, location, timeline, and the actions an
 * engineer can take (acknowledge, resolve, raise a job card).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TicketDetailScreen(
    vm: AppViewModel,
    ticketName: String,
    onBack: () -> Unit,
    onOpenJobCard: (String) -> Unit = {},
    onOpenVehicle: (String) -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var t by remember { mutableStateOf<TicketDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showResolve by remember { mutableStateOf(false) }

    suspend fun reload() {
        runCatching { vm.jobCards.ticketDetail(ticketName) }
            .onSuccess { t = it; loading = false }
            .onFailure { error = it.message ?: "Could not load ticket"; loading = false }
    }
    LaunchedEffect(ticketName) { loading = true; error = null; reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ticket") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        when {
            loading -> Loader(pad)
            t == null -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text(error ?: "Ticket not found", color = MaterialTheme.colorScheme.error)
            }
            else -> {
                val d = t!!
                Column(
                    Modifier.fillMaxSize().padding(pad).background(MaterialTheme.colorScheme.background)
                        .verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    VehicleHeader(d.registration_number, humanize(d.title), d.severity, d.status, d.vehicle ?: d.name)

                    DetailCard("Ticket") {
                        Text(humanize(d.title) ?: d.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        d.message?.let { KV("Message", it) }
                        d.source?.let { KV("Source", it) }
                        d.depot?.let { KV("Depot", it) }
                        d.assigned_to?.let { KV("Assigned to", it) }
                    }

                    // Vehicle telemetry snapshot captured at raise time
                    val hasSnap = listOf(d.operator, d.make_model, d.route_name, d.activity, d.connectivity_status).any { !it.isNullOrBlank() } ||
                        d.odometer != null || d.battery_soc != null
                    if (hasSnap) {
                        DetailCard("Vehicle at alert time") {
                            d.operator?.let { KV("Operator", it) }
                            d.make_model?.let { KV("Make / model", it) }
                            d.route_name?.let { KV("Route", it) }
                            fmtNum(d.odometer)?.let { KV("Odometer", "$it km") }
                            d.activity?.let { KV("Activity", it) }
                            d.connectivity_status?.let { KV("Connectivity", it) }
                            fmtNum(d.battery_soc)?.let { KV("Battery SOC", "$it%") }
                            prettyDateTime(d.telemetry_at)?.let { KV("Reading time", it) }
                        }
                    }

                    if (!d.maps_link.isNullOrBlank() || (d.latitude != null && d.longitude != null)) {
                        DetailCard("Location") {
                            d.latitude?.let { lat -> d.longitude?.let { lng -> KV("Coordinates", "%.5f, %.5f".format(lat, lng)) } }
                            OutlinedButton(onClick = { openMaps(ctx, d.maps_link, d.latitude, d.longitude) }) {
                                Icon(Icons.Filled.Place, null); Spacer(Modifier.width(8.dp)); Text("Open in Maps")
                            }
                        }
                    }

                    DetailCard("Timeline") {
                        prettyDateTime(d.creation)?.let { KV("Raised", it) }
                        prettyDateTime(d.acknowledged_at)?.let { KV("Acknowledged", it) }
                        prettyDateTime(d.resolved_at)?.let { KV("Resolved", it) }
                        d.resolution_reason?.let { KV("Resolution", it) }
                    }

                    // Actions
                    val status = d.status ?: ""
                    if (status.equals("Open", true)) {
                        Button(
                            enabled = !busy,
                            onClick = { scope.launch { busy = true; runCatching { vm.jobCards.acknowledgeTicket(d.name) }; reload(); busy = false } },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                        ) { Icon(Icons.Filled.CheckCircle, null); Spacer(Modifier.width(8.dp)); Text("Acknowledge") }
                    }
                    if (!status.equals("Resolved", true)) {
                        OutlinedButton(enabled = !busy, onClick = { showResolve = true }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                            Text("Mark resolved")
                        }
                    }
                    if (d.job_card.isNullOrBlank()) {
                        Button(
                            enabled = !busy,
                            onClick = {
                                scope.launch {
                                    busy = true
                                    runCatching { vm.jobCards.createJobCardFromTicket(d.name) }
                                        .onSuccess { jc -> onOpenJobCard(jc) }
                                    reload(); busy = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) { Icon(Icons.AutoMirrored.Filled.Assignment, null); Spacer(Modifier.width(8.dp)); Text("Create job card") }
                    } else {
                        OutlinedButton(onClick = { onOpenJobCard(d.job_card!!) }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                            Icon(Icons.AutoMirrored.Filled.Assignment, null); Spacer(Modifier.width(8.dp)); Text("Open job card")
                        }
                    }
                    d.vehicle?.takeIf { it.isNotBlank() }?.let { v ->
                        OutlinedButton(onClick = { onOpenVehicle(v) }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                            Icon(Icons.Filled.DirectionsBus, null); Spacer(Modifier.width(8.dp)); Text("Open vehicle")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    if (showResolve) {
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (!busy) showResolve = false },
            title = { Text("Mark ticket resolved") },
            text = {
                OutlinedTextField(value = reason, onValueChange = { reason = it }, label = { Text("Resolution note (optional)") }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    scope.launch { busy = true; runCatching { vm.jobCards.resolveTicket(ticketName, reason) }; showResolve = false; reload(); busy = false }
                }) { Text("Resolve") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { showResolve = false }) { Text("Cancel") } },
        )
    }
}
