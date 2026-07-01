package com.naarni.service.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Pending
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.naarni.service.ui.components.Refreshable
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.naarni.service.data.dto.JobCardListItem
import com.naarni.service.data.dto.SuggestionItem
import com.naarni.service.ui.AppViewModel
import com.naarni.service.ui.components.EmptyState
import com.naarni.service.ui.components.MetaChip
import com.naarni.service.ui.components.PriorityPill
import com.naarni.service.ui.components.RefreshableList
import com.naarni.service.ui.components.SectionHeader
import com.naarni.service.ui.components.SmartSelect
import com.naarni.service.ui.components.StatTile
import com.naarni.service.ui.components.StatusChip
import com.naarni.service.ui.components.VehicleNumber
import com.naarni.service.ui.components.statusColor
import com.naarni.service.ui.theme.BrandGradient

@Composable
fun HomeScreen(
    vm: AppViewModel,
    onCreateJobCard: () -> Unit,
    onOpenNotifications: () -> Unit = {},
    onOpenJobCard: (String) -> Unit = {},
) {
    var items by remember { mutableStateOf<List<JobCardListItem>>(emptyList()) }
    var unread by remember { mutableStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun load() {
        runCatching { vm.jobCards.myJobCards() }.onSuccess { items = it }
        runCatching { vm.jobCards.unreadCount() }.onSuccess { unread = it }
    }
    LaunchedEffect(Unit) { load() }
    val active = items.count { it.workflow_state?.lowercase() != "closed" }

    Refreshable(
        refreshing = refreshing,
        onRefresh = { scope.launch { refreshing = true; load(); refreshing = false } },
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Welcome back 👋", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        vm.session.fullName ?: vm.session.user ?: "Service Engineer",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BadgedBox(badge = {
                    if (unread > 0) Badge { Text(if (unread > 99) "99+" else unread.toString()) }
                }) {
                    IconButton(onClick = onOpenNotifications) {
                        Icon(Icons.Filled.Notifications, contentDescription = "Notifications")
                    }
                }
            }

        // Stats
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("Active jobs", active.toString(), Icons.Filled.Pending, Modifier.weight(1f))
            StatTile("Total", items.size.toString(), Icons.Filled.CheckCircle, Modifier.weight(1f))
        }

        // Primary gradient CTA
        val feedback = com.naarni.service.core.feedback.LocalFeedback.current
        Surface(
            onClick = { feedback.tap(); onCreateJobCard() },
            shape = MaterialTheme.shapes.large,
            color = Color.Transparent,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier
                    .background(Brush.horizontalGradient(BrandGradient), MaterialTheme.shapes.large)
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    Modifier.size(48.dp).background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text("Create Job Card", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text("PMS, Repair, Software or Breakdown", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
                }
            }
        }

            // Recent
            if (items.isNotEmpty()) {
                SectionHeader("Recent")
                items.take(4).forEach { JobCardRow(it, onClick = { onOpenJobCard(it.name) }) }
            }
        }
    }
}

@Composable
fun JobCardsScreen(vm: AppViewModel, onOpenJobCard: (String) -> Unit = {}) {
    RefreshableList(
        title = "My Job Cards",
        load = { vm.jobCards.myJobCards() },
        itemKey = { it.name },
        empty = { EmptyState(Icons.Filled.Inbox, "No job cards yet", "Cards assigned to you will appear here.") },
        row = { JobCardRow(it, onClick = { onOpenJobCard(it.name) }) },
    )
}

/** "2026-06-29" → "29 Jun 2026"; passes through anything unexpected. */
private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private fun prettyDate(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val p = raw.take(10).split("-")
    if (p.size != 3) return raw
    val y = p[0]; val m = p[1].toIntOrNull() ?: return raw; val d = p[2].toIntOrNull() ?: return raw
    if (m !in 1..12) return raw
    return "$d ${MONTHS[m - 1]} $y"
}

/** "12345" → "12,345 km". */
private fun prettyKm(km: Int?): String? {
    if (km == null || km <= 0) return null
    return "%,d km".format(km)
}

/** A small rounded badge for the job-card type. */
@Composable
private fun TypeBadge(type: String?) {
    if (type.isNullOrBlank()) return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(50)) {
        Text(
            type,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun JobCardRow(jc: JobCardListItem, onClick: () -> Unit = {}) {
    val criticality = jc.force_close_severity?.takeIf { it.isNotBlank() } ?: jc.priority
    val accent = statusColor(jc.workflow_state)
    // Lead with the fleet operator (easiest to identify); registration is the sub-line.
    val operator = jc.operator?.takeIf { it.isNotBlank() }

    Surface(
        shape = MaterialTheme.shapes.large,
        tonalElevation = 1.dp,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            // Status-coloured accent strip down the left edge.
            Box(Modifier.width(5.dp).fillMaxHeight().background(accent))
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Header: bus avatar + operator/registration + status
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier.size(44.dp).background(accent.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.DirectionsBus, contentDescription = null, tint = accent) }
                    Column(Modifier.weight(1f)) {
                        val reg = jc.vehicle_number?.takeIf { it.isNotBlank() }
                        if (operator != null) {
                            // Operator headline, registration (last-4 highlighted) below.
                            Text(operator, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            if (reg != null) VehicleNumber(reg, style = MaterialTheme.typography.bodyMedium)
                        } else if (reg != null) {
                            VehicleNumber(reg, style = MaterialTheme.typography.titleMedium)
                            jc.vehicle_make_model?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        } else {
                            Text(jc.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                    StatusChip(jc.workflow_state)
                }
                // Badges: type + criticality + SLA
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TypeBadge(jc.job_card_type)
                    PriorityPill(criticality)
                    if (jc.sla_breached == 1) {
                        MetaChip(Icons.Filled.Warning, "SLA", tint = MaterialTheme.colorScheme.error)
                    }
                }
                // Meta: customer · created date · odometer
                val customer = jc.customer_name?.takeIf { it.isNotBlank() }
                val date = prettyDate(jc.job_card_date) ?: prettyDate(jc.creation)
                val km = prettyKm(jc.odometer_reading)
                if (customer != null || date != null || km != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (customer != null) MetaChip(Icons.Filled.Person, customer, modifier = Modifier.weight(1f, fill = false))
                        if (date != null) MetaChip(Icons.Filled.Event, date)
                        if (km != null) MetaChip(Icons.Filled.Speed, km)
                    }
                }
            }
        }
    }
}

/** "abs_ebsamberwarningsignal" → "Abs Ebsamberwarningsignal" (readable). */
private fun humanize(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return raw.trim().replace('_', ' ').replace('-', ' ')
        .split(' ').filter { it.isNotBlank() }
        .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
}

/** "2026-07-01 08:05:00" → "1 Jul, 08:05". */
private fun prettyDateTime(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val date = prettyDate(raw) ?: return raw
    val short = date.split(" ").let { if (it.size >= 2) "${it[0]} ${it[1]}" else date } // "1 Jul"
    val time = raw.drop(11).take(5).takeIf { it.length == 5 }  // "08:05"
    return if (time != null) "$short, $time" else short
}

private fun alertSeverityColor(sev: String?): Color = when (sev?.lowercase()) {
    "critical" -> Color(0xFFEF4444)
    "warning" -> Color(0xFFF59E0B)
    else -> Color(0xFF64748B)
}

@Composable
fun AlertsScreen(
    vm: AppViewModel,
    onOpenJobCard: (String) -> Unit = {},
    onOpenVehicle: (String) -> Unit = {},
) {
    var severity by remember { mutableStateOf("") } // "", "warning", "critical"
    var items by remember { mutableStateOf<List<com.naarni.service.data.dto.AlertEventItem>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun load() {
        runCatching { vm.jobCards.myAlertEvents(severity) }.onSuccess { items = it; loaded = true }
    }
    LaunchedEffect(severity) { load() }

    Refreshable(
        refreshing = refreshing,
        onRefresh = { scope.launch { refreshing = true; load(); refreshing = false } },
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Alerts", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (loaded) Text("${items.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Severity filter
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = severity == "", onClick = { severity = "" }, label = { Text("All") })
                    FilterChip(selected = severity == "warning", onClick = { severity = "warning" }, label = { Text("Warning") })
                    FilterChip(selected = severity == "critical", onClick = { severity = "critical" }, label = { Text("Critical") })
                }
            }
            if (loaded && items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(Icons.Filled.NotificationsActive, "No alerts", "No ${severity.ifBlank { "" }} alerts for your depot's buses right now.")
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(items, key = { it.name }) { a ->
                        AlertCard(
                            a,
                            onClick = {
                                val jc = a.job_card?.takeIf { it.isNotBlank() }
                                val v = a.vehicle?.takeIf { it.isNotBlank() }
                                when {
                                    jc != null -> onOpenJobCard(jc)
                                    v != null -> onOpenVehicle(v)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertCard(a: com.naarni.service.data.dto.AlertEventItem, onClick: () -> Unit = {}) {
    val color = alertSeverityColor(a.severity)
    val openable = !a.job_card.isNullOrBlank() || !a.vehicle.isNullOrBlank()
    Surface(
        shape = MaterialTheme.shapes.large,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().then(if (openable) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(color))
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Line 1: highlighted VEHICLE NUMBER + severity
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.size(36.dp).background(color.copy(alpha = 0.14f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.DirectionsBus, contentDescription = null, tint = color, modifier = Modifier.size(20.dp)) }
                    if (a.registration_number.isNullOrBlank()) {
                        Text("Unknown vehicle", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    } else {
                        VehicleNumber(a.registration_number, modifier = Modifier.weight(1f))
                    }
                    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(50)) {
                        Text(
                            (a.severity ?: "—").replaceFirstChar { it.uppercase() },
                            color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
                // Alert type
                Text(
                    humanize(a.title ?: a.parameter) ?: a.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                // Readings / message
                a.message?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Meta: date · status · open hint
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    prettyDateTime(a.occurred_at)?.let { MetaChip(Icons.Filled.Event, it) }
                    a.status?.takeIf { it.isNotBlank() }?.let {
                        MetaChip(Icons.Filled.Pending, it)
                    }
                    Spacer(Modifier.weight(1f))
                    if (openable) {
                        Text(
                            if (!a.job_card.isNullOrBlank()) "Open job card ›" else "Open vehicle ›",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TicketsScreen(
    vm: AppViewModel,
    onOpenJobCard: (String) -> Unit = {},
    onOpenVehicle: (String) -> Unit = {},
) {
    RefreshableList(
        title = "Tickets",
        load = { vm.jobCards.myTickets() },
        itemKey = { it.name },
        empty = { EmptyState(Icons.Filled.ConfirmationNumber, "No tickets", "Tickets auto-raised from alerts for your depot's buses appear here.") },
        row = { t ->
            TicketCard(
                t,
                onClick = {
                    val jc = t.job_card?.takeIf { it.isNotBlank() }
                    val v = t.vehicle?.takeIf { it.isNotBlank() }
                    when {
                        jc != null -> onOpenJobCard(jc)
                        v != null -> onOpenVehicle(v)
                    }
                },
            )
        },
    )
}

@Composable
private fun TicketCard(t: com.naarni.service.data.dto.TicketItem, onClick: () -> Unit = {}) {
    val color = alertSeverityColor(t.severity)
    val openable = !t.job_card.isNullOrBlank() || !t.vehicle.isNullOrBlank()
    Surface(
        shape = MaterialTheme.shapes.large,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().then(if (openable) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(color))
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Line 1: highlighted VEHICLE NUMBER + status
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.size(36.dp).background(color.copy(alpha = 0.14f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.ConfirmationNumber, contentDescription = null, tint = color, modifier = Modifier.size(20.dp)) }
                    if (t.registration_number.isNullOrBlank()) {
                        Text(t.title ?: t.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    } else {
                        VehicleNumber(t.registration_number, modifier = Modifier.weight(1f))
                    }
                    StatusChip(t.status)
                }
                // Ticket title
                Text(
                    humanize(t.title) ?: t.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                // Meta: date · severity · open hint
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    prettyDateTime(t.creation)?.let { MetaChip(Icons.Filled.Event, it) }
                    t.severity?.takeIf { it.isNotBlank() }?.let { PriorityPill(it) }
                    Spacer(Modifier.weight(1f))
                    if (openable) {
                        Text(
                            if (!t.job_card.isNullOrBlank()) "Open job card ›" else "Open vehicle ›",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileScreen(vm: AppViewModel) {
    val name = vm.session.fullName ?: vm.session.user ?: "—"
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Profile", style = MaterialTheme.typography.titleLarge)
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 2.dp, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    Modifier.size(56.dp).background(Brush.linearGradient(BrandGradient), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(name.take(1).uppercase(), style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    StatusChip(vm.session.primaryRole)
                }
            }
        }

        MyDepotCard(vm)

        OutlinedButton(onClick = { vm.logout() }, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
            Spacer(Modifier.height(0.dp))
            Text("  Log out")
        }
    }
}

/**
 * "My Depot" — shows the Service Engineer's current depot and lets them change it.
 * Changing the depot re-scopes the Alerts and Tickets tabs to that depot's buses.
 */
@Composable
private fun MyDepotCard(vm: AppViewModel) {
    val scope = rememberCoroutineScope()
    val feedback = com.naarni.service.core.feedback.LocalFeedback.current
    var current by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    suspend fun load() {
        runCatching { vm.jobCards.myDepots() }
            .onSuccess { current = it.depots.firstOrNull()?.let { d -> d.depot_name ?: d.name }; loaded = true }
    }
    LaunchedEffect(Unit) { load() }

    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 2.dp, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Business, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text("My Depot", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (saving) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            Text(
                current ?: if (loaded) "Not assigned — pick your depot to see its alerts" else "Loading…",
                style = MaterialTheme.typography.bodyMedium,
                color = if (current != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (current != null) FontWeight.SemiBold else FontWeight.Normal,
            )
            SmartSelect(
                label = "Change depot",
                value = null,
                placeholder = "Pick your depot",
                fetchOnOpen = true,
                fetch = { q ->
                    vm.jobCards.searchDepots(q).map {
                        SuggestionItem(
                            value = it.name,
                            label = it.depot_name ?: it.name,
                            sublabel = listOfNotNull(it.city, it.state).joinToString(", ").ifBlank { null },
                        )
                    }
                },
                onSelect = { sel ->
                    scope.launch {
                        saving = true
                        runCatching { vm.jobCards.setMyDepot(sel.value) }
                            .onSuccess { feedback.success(); load() }
                            .onFailure { feedback.error() }
                        saving = false
                    }
                },
            )
        }
    }
}

@Composable
private fun PlaceholderTab(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(20.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(icon, title, body)
        }
    }
}
