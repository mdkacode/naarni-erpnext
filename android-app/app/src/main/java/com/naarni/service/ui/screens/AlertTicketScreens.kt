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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naarni.service.data.dto.AlertGroupDetail
import com.naarni.service.data.dto.QuickResponse
import com.naarni.service.data.dto.TicketDetail
import com.naarni.service.ui.AppViewModel
import com.naarni.service.ui.components.StatusChip
import com.naarni.service.ui.components.VehicleNumber
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

@Composable
private fun DetailCard(title: String, rail: Color = MaterialTheme.colorScheme.primary, content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 1.dp, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = rail, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

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
            VehicleNumber(reg?.takeIf { it.isNotBlank() } ?: fallback, style = MaterialTheme.typography.headlineSmall)
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

/**
 * Tap-to-pick canned resolutions — no typing. Big full-width buttons (most-used
 * first) plus an "Other…" escape hatch that promotes a typed answer server-side.
 */
@Composable
private fun QuickResponsePicker(responses: List<QuickResponse>, busy: Boolean, onPick: (QuickResponse) -> Unit, onOther: (String) -> Unit) {
    var showOther by remember { mutableStateOf(false) }
    DetailCard("How did you fix it?") {
        Text("Tap the closest match — no typing needed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        responses.forEach { r ->
            FilledTonalButton(
                enabled = !busy,
                onClick = { onPick(r) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text(r.response_text, modifier = Modifier.weight(1f)) }
        }
        OutlinedButton(enabled = !busy, onClick = { showOther = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Icon(Icons.Filled.Edit, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Other…")
        }
    }
    if (showOther) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (!busy) showOther = false },
            title = { Text("How did you fix it?") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Type your answer") }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(enabled = !busy && text.isNotBlank(), onClick = { onOther(text.trim()); showOther = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showOther = false }) { Text("Cancel") } },
        )
    }
}

// ─────────────────────────── Alert group detail ───────────────────────────

/**
 * Detailed Alert page for one (bus, issue) group: the latest reading, tap-to-pick
 * Quick Responses to resolve the open ticket, and the full occurrence history
 * (every time it fired, and who resolved it with what response).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertDetailScreen(
    vm: AppViewModel,
    dedupKey: String? = null,
    alertEvent: String? = null,
    onBack: () -> Unit,
    onOpenTicket: (String) -> Unit = {},
    onOpenVehicle: (String) -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val feedback = com.naarni.service.core.feedback.LocalFeedback.current
    var d by remember { mutableStateOf<AlertGroupDetail?>(null) }
    var responses by remember { mutableStateOf<List<QuickResponse>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        runCatching { vm.jobCards.alertGroup(dedupKey, alertEvent) }
            .onSuccess {
                d = it; loading = false
                responses = if (it.open_ticket != null) {
                    runCatching { vm.jobCards.alertResponses(it.alert_type ?: "") }.getOrDefault(emptyList())
                } else emptyList()
            }
            .onFailure { error = it.message ?: "Could not load alert"; loading = false }
    }
    LaunchedEffect(dedupKey, alertEvent) { loading = true; error = null; reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                // Show the actual alert name + bus number, not a generic "Alert details".
                title = {
                    Column {
                        Text(
                            d?.let { humanize(it.alert_name) } ?: "Alert",
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        d?.registration_number?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        when {
            loading -> Loader(pad)
            d == null -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text(error ?: "Alert not found", color = MaterialTheme.colorScheme.error)
            }
            else -> {
                val g = d!!
                val lr = g.latest_reading
                Column(
                    Modifier.fillMaxSize().padding(pad).background(MaterialTheme.colorScheme.background)
                        .verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    VehicleHeader(g.registration_number, humanize(g.alert_name), g.latest_severity, g.latest_status, g.vehicle ?: g.dedup_key)

                    // Latest reading
                    DetailCard("Latest reading", alertSeverityColor(g.latest_severity)) {
                        val reading = buildString {
                            humanize(lr?.parameter)?.let { append(it) }
                            (lr?.value_text ?: fmtNum(lr?.value))?.let { v ->
                                if (isNotEmpty()) append(" = ")
                                append(v); lr?.unit?.let { append(" $it") }
                            }
                        }.trim()
                        if (reading.isNotBlank()) KV("Reading", reading)
                        lr?.value_meaning?.let { KV("Meaning", it) }
                        (fmtNum(lr?.threshold) ?: lr?.match_value)?.let { KV("Threshold", it + (lr?.unit?.let { u -> " $u" } ?: "")) }
                        lr?.message?.let { KV("Message", it) }
                        relExact(lr?.triggered_at ?: lr?.occurred_at)?.let { KV("When", it) }
                        KV("Times fired", "${g.occurrence_count}")
                    }

                    // Location
                    if (!lr?.maps_link.isNullOrBlank() || (lr?.latitude != null && lr.longitude != null)) {
                        DetailCard("Location") {
                            OutlinedButton(onClick = { openMaps(ctx, lr?.maps_link, lr?.latitude, lr?.longitude) }) {
                                Icon(Icons.Filled.Place, null); Spacer(Modifier.width(8.dp)); Text("Open in Maps")
                            }
                        }
                    }

                    // Quick responses — only when there is an open ticket to resolve.
                    if (g.open_ticket != null) {
                        QuickResponsePicker(
                            responses = responses,
                            busy = busy,
                            onPick = { r ->
                                scope.launch {
                                    busy = true
                                    runCatching { vm.jobCards.resolveTicket(g.open_ticket!!, response = r.name) }
                                        .onSuccess { feedback.success() }.onFailure { feedback.error() }
                                    reload(); busy = false
                                }
                            },
                            onOther = { text ->
                                scope.launch {
                                    busy = true
                                    runCatching { vm.jobCards.resolveTicket(g.open_ticket!!, reason = text) }
                                        .onSuccess { feedback.success() }.onFailure { feedback.error() }
                                    reload(); busy = false
                                }
                            },
                        )
                    }

                    // History: who resolved (episodes) + every occurrence
                    DetailCard("History") {
                        val resolvedEpisodes = g.episodes.filter { it.status == "Resolved" }
                        resolvedEpisodes.forEach { ep ->
                            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF16A34A), modifier = Modifier.size(18.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(ep.resolution_response_text ?: ep.resolution_reason ?: "Resolved", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    Text(
                                        listOfNotNull(ep.resolved_by_name?.takeIf { it.isNotBlank() }, relExact(ep.resolved_at)).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        if (resolvedEpisodes.isNotEmpty()) HorizontalDivider()
                        Text("All occurrences (${g.occurrence_count})", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        g.occurrences.forEach { occ ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(relExact(occ.time) ?: "—", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                StatusChip(occ.status)
                            }
                        }
                    }

                    // Links out
                    (g.open_ticket ?: g.episodes.firstOrNull()?.ticket)?.let { t ->
                        OutlinedButton(onClick = { onOpenTicket(t) }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                            Icon(Icons.Filled.ConfirmationNumber, null); Spacer(Modifier.width(8.dp)); Text("View ticket")
                        }
                    }
                    g.vehicle?.takeIf { it.isNotBlank() }?.let { v ->
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

/** Full Service Ticket — telemetry snapshot, timeline (incl. who resolved), and
 * the same tap-to-pick quick responses to resolve. */
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
    val feedback = com.naarni.service.core.feedback.LocalFeedback.current
    var t by remember { mutableStateOf<TicketDetail?>(null) }
    var responses by remember { mutableStateOf<List<QuickResponse>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    suspend fun reload() {
        runCatching { vm.jobCards.ticketDetail(ticketName) }
            .onSuccess {
                t = it; loading = false
                responses = if (it.status != "Resolved") {
                    runCatching { vm.jobCards.alertResponses("") }.getOrDefault(emptyList())
                } else emptyList()
            }
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
                        d.message?.let { KV("Message", it) }
                        d.source?.let { KV("Source", it) }
                        d.depot?.let { KV("Depot", it) }
                        d.assigned_to?.let { KV("Assigned to", it) }
                    }

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
                            OutlinedButton(onClick = { openMaps(ctx, d.maps_link, d.latitude, d.longitude) }) {
                                Icon(Icons.Filled.Place, null); Spacer(Modifier.width(8.dp)); Text("Open in Maps")
                            }
                        }
                    }

                    DetailCard("Timeline") {
                        prettyDateTime(d.creation)?.let { KV("Raised", it) }
                        prettyDateTime(d.acknowledged_at)?.let { KV("Acknowledged", it) }
                        prettyDateTime(d.resolved_at)?.let { KV("Resolved", it) }
                        d.resolved_by_name?.let { KV("Resolved by", it) }
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
                        QuickResponsePicker(
                            responses = responses,
                            busy = busy,
                            onPick = { r ->
                                scope.launch {
                                    busy = true
                                    runCatching { vm.jobCards.resolveTicket(d.name, response = r.name) }
                                        .onSuccess { feedback.success() }.onFailure { feedback.error() }
                                    reload(); busy = false
                                }
                            },
                            onOther = { text ->
                                scope.launch {
                                    busy = true
                                    runCatching { vm.jobCards.resolveTicket(d.name, reason = text) }
                                        .onSuccess { feedback.success() }.onFailure { feedback.error() }
                                    reload(); busy = false
                                }
                            },
                        )
                    }
                    if (d.job_card.isNullOrBlank()) {
                        Button(
                            enabled = !busy,
                            onClick = {
                                scope.launch {
                                    busy = true
                                    runCatching { vm.jobCards.createJobCardFromTicket(d.name) }.onSuccess { jc -> onOpenJobCard(jc) }
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
}
