package com.naarni.service.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.naarni.service.data.dto.Occurrence
import com.naarni.service.data.dto.QuickResponse
import com.naarni.service.data.dto.TicketDetail
import com.naarni.service.data.dto.TicketEpisode
import com.naarni.service.ui.AppViewModel
import com.naarni.service.ui.components.LoadingOverlay
import com.naarni.service.ui.components.MetaChip
import com.naarni.service.ui.components.SectionHeader
import com.naarni.service.ui.components.SeverityPill
import com.naarni.service.ui.components.SkeletonBox
import com.naarni.service.ui.components.StatusChip
import com.naarni.service.ui.components.VehicleNumber
import kotlinx.coroutines.launch

private val ResolvedGreen = Color(0xFF16A34A)

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

/** Compact identity card: severity rail + bus number (last-4 enlarged) + alert
 *  name + severity/status chips + one meta line. No wasted vertical space. */
@Composable
private fun CompactHeader(reg: String?, alertName: String?, severity: String?, status: String?, meta: String?, fallback: String) {
    val color = com.naarni.service.ui.components.severityColor(severity)
    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 1.dp, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(color))
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(44.dp).background(color.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.DirectionsBus, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        VehicleNumber(reg?.takeIf { it.isNotBlank() } ?: fallback, style = MaterialTheme.typography.titleLarge)
                        if (!alertName.isNullOrBlank()) {
                            Text(alertName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SeverityPill(severity)
                        StatusChip(status)
                    }
                }
                if (!meta.isNullOrBlank()) MetaChip(Icons.Filled.NotificationsActive, meta)
            }
        }
    }
}

/** Skeleton silhouette mirroring a detail screen (header + two cards). */
@Composable
private fun DetailSkeleton() {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SkeletonBox(Modifier.size(52.dp), shape = CircleShape)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeletonBox(Modifier.fillMaxWidth(0.6f).height(20.dp))
                SkeletonBox(Modifier.fillMaxWidth(0.4f).height(14.dp))
            }
        }
        SkeletonBox(Modifier.fillMaxWidth().height(150.dp))
        SkeletonBox(Modifier.fillMaxWidth().height(120.dp))
    }
}

/** "How did you fix it?" — tap-to-pick canned resolutions, used inside a sheet. */
@Composable
private fun FixSheetContent(responses: List<QuickResponse>, busy: Boolean, onPick: (QuickResponse) -> Unit, onOther: (String) -> Unit) {
    var showOther by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("How did you fix it?", style = MaterialTheme.typography.titleLarge)
        Text("Tap the closest match — no typing needed.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            responses.forEach { r ->
                FilledTonalButton(enabled = !busy, onClick = { onPick(r) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text(r.response_text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                }
            }
            OutlinedButton(enabled = !busy, onClick = { showOther = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Filled.Edit, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Other…")
            }
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

@Composable
private fun EpisodeRow(ep: TicketEpisode) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = ResolvedGreen, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(ep.resolution_response_text ?: ep.resolution_reason ?: "Resolved", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                listOfNotNull(ep.resolved_by_name?.takeIf { it.isNotBlank() }, relExact(ep.resolved_at)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OccurrenceRow(occ: Occurrence) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(relExact(occ.time) ?: "—", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        StatusChip(occ.status)
    }
}

// ─────────────────────────── Alert group detail ───────────────────────────

/**
 * Detailed Alert page for one (bus, issue) group. Compact header, latest reading,
 * a short history preview, a sticky "How did you fix it?" bottom bar opening a
 * quick-response sheet, and a full-history sheet.
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
    var showFix by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

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

    fun resolve(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            val ok = runCatching { block() }.onSuccess { feedback.success() }.onFailure { feedback.error() }.isSuccess
            showFix = false
            reload()
            busy = false
            android.widget.Toast.makeText(
                ctx, if (ok) "Marked as fixed" else "Couldn't save — try again",
                if (ok) android.widget.Toast.LENGTH_SHORT else android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(d?.let { humanize(it.alert_name) } ?: "Alert", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        d?.registration_number?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        bottomBar = {
            val g = d
            if (g != null) {
                Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (g.open_ticket != null) {
                            Button(enabled = !busy, onClick = { showFix = true }, modifier = Modifier.weight(1f).height(56.dp)) {
                                Icon(Icons.Filled.CheckCircle, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("How did you fix it?", maxLines = 1)
                            }
                        } else {
                            Surface(color = ResolvedGreen.copy(alpha = 0.14f), shape = RoundedCornerShape(50), modifier = Modifier.weight(1f).height(56.dp)) {
                                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = ResolvedGreen); Spacer(Modifier.width(8.dp))
                                    Text("Resolved", color = ResolvedGreen, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                        FilledTonalIconButton(onClick = { showHistory = true }, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.Filled.History, contentDescription = "History")
                        }
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            Crossfade(targetState = loading, animationSpec = tween(220), label = "load") { isLoading ->
                if (isLoading) {
                    DetailSkeleton()
                } else {
                    val g = d
                    if (g == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(error ?: "Alert not found", color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        val lr = g.latest_reading
                        val meta = (if (g.occurrence_count > 1) "${g.occurrence_count}× · " else "") +
                            (relExact(lr?.triggered_at ?: lr?.occurred_at) ?: "—")
                        Column(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                                .verticalScroll(rememberScrollState()).padding(16.dp).animateContentSize(tween(200)),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            CompactHeader(g.registration_number, humanize(g.alert_name), g.latest_severity, g.latest_status, meta, g.vehicle ?: g.dedup_key)

                            DetailCard("Latest reading", com.naarni.service.ui.components.severityColor(g.latest_severity)) {
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
                                if (!lr?.maps_link.isNullOrBlank() || (lr?.latitude != null && lr.longitude != null)) {
                                    OutlinedButton(onClick = { openMaps(ctx, lr?.maps_link, lr?.latitude, lr?.longitude) }) {
                                        Icon(Icons.Filled.Place, null); Spacer(Modifier.width(8.dp)); Text("Open in Maps")
                                    }
                                }
                            }

                            // History — short preview (episodes first, ≤3 rows total)
                            DetailCard("History") {
                                val resolved = g.episodes.filter { it.status == "Resolved" }
                                val epShown = resolved.take(2)
                                val occShown = g.occurrences.take((3 - epShown.size).coerceAtLeast(1))
                                epShown.forEach { EpisodeRow(it) }
                                if (epShown.isNotEmpty() && occShown.isNotEmpty()) HorizontalDivider()
                                occShown.forEach { OccurrenceRow(it) }
                                if (g.occurrences.size > occShown.size || resolved.size > epShown.size) {
                                    TextButton(onClick = { showHistory = true }, modifier = Modifier.align(Alignment.End)) {
                                        Text("View all (${g.occurrence_count}) ›")
                                    }
                                }
                            }

                            // De-emphasised links
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                (g.open_ticket ?: g.episodes.firstOrNull()?.ticket)?.let { t ->
                                    OutlinedButton(onClick = { onOpenTicket(t) }, modifier = Modifier.weight(1f)) {
                                        Icon(Icons.Filled.ConfirmationNumber, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Ticket")
                                    }
                                }
                                g.vehicle?.takeIf { it.isNotBlank() }?.let { v ->
                                    OutlinedButton(onClick = { onOpenVehicle(v) }, modifier = Modifier.weight(1f)) {
                                        Icon(Icons.Filled.DirectionsBus, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Vehicle")
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
            LoadingOverlay(visible = busy)
        }
    }

    // Sheets (float above everything)
    val g = d
    if (showFix && g?.open_ticket != null) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showFix = false }, sheetState = sheet) {
            FixSheetContent(
                responses = responses,
                busy = busy,
                onPick = { r -> resolve { vm.jobCards.resolveTicket(g.open_ticket!!, response = r.name) } },
                onOther = { text -> resolve { vm.jobCards.resolveTicket(g.open_ticket!!, reason = text) } },
            )
        }
    }
    if (showHistory && g != null) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showHistory = false }, sheetState = sheet) {
            Text("History", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 560.dp),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val resolved = g.episodes.filter { it.status == "Resolved" }
                if (resolved.isNotEmpty()) {
                    item { SectionHeader("Who fixed it") }
                    items(resolved, key = { it.ticket }) { EpisodeRow(it) }
                    item { HorizontalDivider() }
                }
                item { SectionHeader("All occurrences (${g.occurrence_count})") }
                items(g.occurrences, key = { it.name }) { OccurrenceRow(it) }
            }
        }
    }
}

// ─────────────────────────── Ticket detail ───────────────────────────

/** Full Service Ticket — telemetry snapshot, timeline (incl. who resolved), and
 * the same tap-to-pick quick responses (in a sheet) to resolve. */
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
    var showFix by remember { mutableStateOf(false) }

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

    fun act(closeFix: Boolean = false, block: suspend () -> Unit) {
        scope.launch {
            busy = true
            runCatching { block() }.onSuccess { feedback.success() }.onFailure { feedback.error() }
            if (closeFix) showFix = false
            reload(); busy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ticket") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        bottomBar = {
            val d = t
            if (d != null && !(d.status ?: "").equals("Resolved", true)) {
                Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Button(enabled = !busy, onClick = { showFix = true }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("How did you fix it?")
                        }
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            Crossfade(targetState = loading, animationSpec = tween(220), label = "load") { isLoading ->
                if (isLoading) {
                    DetailSkeleton()
                } else {
                    val d = t
                    if (d == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(error ?: "Ticket not found", color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        Column(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                                .verticalScroll(rememberScrollState()).padding(16.dp).animateContentSize(tween(200)),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            CompactHeader(d.registration_number, humanize(d.title), d.severity, d.status, null, d.vehicle ?: d.name)

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

                            // Secondary actions
                            val status = d.status ?: ""
                            if (status.equals("Open", true)) {
                                OutlinedButton(enabled = !busy, onClick = { act { vm.jobCards.acknowledgeTicket(d.name) } }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                                    Icon(Icons.Filled.CheckCircle, null); Spacer(Modifier.width(8.dp)); Text("Acknowledge")
                                }
                            }
                            if (d.job_card.isNullOrBlank()) {
                                OutlinedButton(enabled = !busy, onClick = { act { vm.jobCards.createJobCardFromTicket(d.name).let { onOpenJobCard(it) } } }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                                    Icon(Icons.AutoMirrored.Filled.Assignment, null); Spacer(Modifier.width(8.dp)); Text("Create job card")
                                }
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
            LoadingOverlay(visible = busy)
        }
    }

    val d = t
    if (showFix && d != null) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showFix = false }, sheetState = sheet) {
            FixSheetContent(
                responses = responses,
                busy = busy,
                onPick = { r -> act(closeFix = true) { vm.jobCards.resolveTicket(d.name, response = r.name) } },
                onOther = { text -> act(closeFix = true) { vm.jobCards.resolveTicket(d.name, reason = text) } },
            )
        }
    }
}
