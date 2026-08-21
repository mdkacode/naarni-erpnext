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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Assignment
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Pending
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.naarni.service.ui.components.Refreshable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.naarni.service.ui.components.statusColor
import com.naarni.service.ui.theme.AppSurface
import com.naarni.service.ui.theme.Stroke
import com.naarni.service.ui.theme.Elevation
import com.naarni.service.ui.theme.Radii
import com.naarni.service.ui.theme.Semantic
import androidx.compose.runtime.ReadOnlyComposable

@Composable
fun HomeScreen(
    vm: AppViewModel,
    onCreateJobCard: () -> Unit,
    onOpenNotifications: () -> Unit = {},
    onOpenJobCard: (String) -> Unit = {},
    onOpenDuty: () -> Unit = {},
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
                        Icon(Icons.Rounded.Notifications, contentDescription = "Notifications")
                    }
                }
            }

        // Duty check-in. First on the page because it is the first thing that
        // happens on arriving at the depot, and it renders nothing at all for
        // roles that cannot punch.
        DutyCard(vm, onOpenDuty = onOpenDuty)

        // Stats
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("Active jobs", active.toString(), Icons.Rounded.Pending, Modifier.weight(1f))
            StatTile("Total", items.size.toString(), Icons.Rounded.CheckCircle, Modifier.weight(1f))
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
                    // Solid accent, not a gradient. This is the screen's one
                    // primary action; a gradient made it read as a banner —
                    // decoration to scroll past — rather than as a button.
                    .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.large)
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    Modifier.size(48.dp).background(Color.White.copy(alpha = 0.18f), Radii.lg),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Add, contentDescription = null, tint = Color.White) }
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
        empty = { EmptyState(Icons.Rounded.Inbox, "No job cards yet", "Cards assigned to you will appear here.") },
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
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = Radii.pill) {
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
    val title = operator ?: jc.vehicle_number ?: jc.name
    val subtitle = if (operator != null) jc.vehicle_number else jc.vehicle_make_model

    Surface(color = AppSurface.raised, border = Stroke.card, shape = MaterialTheme.shapes.large,
        tonalElevation = Elevation.e0,
        shadowElevation = Elevation.e0,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            // Status-coloured accent strip down the left edge.
            Box(Modifier.width(5.dp).fillMaxHeight().background(accent))
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Header: bus avatar + operator/registration + status
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier.size(44.dp).background(accent.copy(alpha = 0.12f), Radii.lg),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.DirectionsBus, contentDescription = null, tint = accent) }
                    Column(Modifier.weight(1f)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        if (!subtitle.isNullOrBlank()) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                    StatusChip(jc.workflow_state)
                }
                // Badges: type + criticality + SLA
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TypeBadge(jc.job_card_type)
                    PriorityPill(criticality)
                    if (jc.sla_breached == 1) {
                        MetaChip(Icons.Rounded.Warning, "SLA", tint = MaterialTheme.colorScheme.error)
                    }
                }
                // Meta: customer · created date · odometer
                val customer = jc.customer_name?.takeIf { it.isNotBlank() }
                val date = prettyDate(jc.job_card_date) ?: prettyDate(jc.creation)
                val km = prettyKm(jc.odometer_reading)
                if (customer != null || date != null || km != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (customer != null) MetaChip(Icons.Rounded.Person, customer, modifier = Modifier.weight(1f, fill = false))
                        if (date != null) MetaChip(Icons.Rounded.Event, date)
                        if (km != null) MetaChip(Icons.Rounded.Speed, km)
                    }
                }
            }
        }
    }
}

/** "abs_ebsamberwarningsignal" → "Abs Ebsamberwarningsignal" (readable). */
internal fun humanize(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return raw.trim().replace('_', ' ').replace('-', ' ')
        .split(' ').filter { it.isNotBlank() }
        .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
}

/** "2026-07-01 08:05:00" → "1 Jul, 08:05". */
internal fun prettyDateTime(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val date = prettyDate(raw) ?: return raw
    val short = date.split(" ").let { if (it.size >= 2) "${it[0]} ${it[1]}" else date } // "1 Jul"
    val time = raw.drop(11).take(5).takeIf { it.length == 5 }  // "08:05"
    return if (time != null) "$short, $time" else short
}

@Composable
@ReadOnlyComposable
internal fun alertSeverityColor(sev: String?): Color = when (sev?.lowercase()) {
    "critical" -> Semantic.critical
    "warning" -> Semantic.caution
    else -> Semantic.idle
}

/** "2026-07-06 10:00:00" → "2m ago" / "3h ago" / "2d ago" (times are IST-naive). */
// Reused across all calls (main-thread only) — building a SimpleDateFormat per call
// was a real per-scroll cost in the alert/ticket lists.
private val IST_DATE_PARSER: java.text.SimpleDateFormat by lazy {
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
    }
}

internal fun relativeTime(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val t = runCatching { IST_DATE_PARSER.parse(raw.take(19))?.time }.getOrNull() ?: return prettyDateTime(raw)
    val m = (System.currentTimeMillis() - t) / 60000
    return when {
        m < 0 -> prettyDateTime(raw)
        m < 1L -> "just now"
        m < 60L -> "${m}m ago"
        m < 1440L -> "${m / 60}h ago"
        m < 10080L -> "${m / 1440}d ago"
        else -> prettyDateTime(raw)
    }
}

/** "2h ago · 7 Jul, 14:30" — relative time with the exact time adjacent. */
internal fun relExact(raw: String?): String? {
    val rel = relativeTime(raw) ?: return null
    val exact = prettyDateTime(raw)
    return if (exact != null && exact != rel) "$rel · $exact" else rel
}

@Composable
fun AlertsScreen(
    vm: AppViewModel,
    onOpenGroup: (String) -> Unit = {},
) {
    var search by remember { mutableStateOf("") }
    var severity by remember { mutableStateOf("") } // "", "warning", "critical"
    var status by remember { mutableStateOf("") }   // "", "Open", "Resolved"
    var sort by remember { mutableStateOf("latest") } // "latest", "frequent"
    var items by remember { mutableStateOf<List<com.naarni.service.data.dto.AlertGroup>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        runCatching { vm.jobCards.alertGroups(search.trim(), severity, status, sort) }
            .onSuccess { items = it; loaded = true }
    }
    // Debounced: filter taps and typed search both re-query (autocomplete-style).
    LaunchedEffect(search, severity, status, sort) {
        delay(250)
        load()
    }

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
                // Search (live)
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search bus number or alert") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        if (search.isNotEmpty()) {
                            IconButton(onClick = { search = "" }) { Icon(Icons.Rounded.Close, contentDescription = "Clear") }
                        }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                )
                // Filters + sort (tap-only, horizontally scrollable)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(selected = severity == "", onClick = { severity = "" }, label = { Text("All") })
                    FilterChip(selected = severity == "warning", onClick = { severity = "warning" }, label = { Text("Warning") })
                    FilterChip(selected = severity == "critical", onClick = { severity = "critical" }, label = { Text("Critical") })
                    Text("·", color = MaterialTheme.colorScheme.outline)
                    FilterChip(selected = status == "Open", onClick = { status = if (status == "Open") "" else "Open" }, label = { Text("Open") })
                    FilterChip(selected = status == "Resolved", onClick = { status = if (status == "Resolved") "" else "Resolved" }, label = { Text("Resolved") })
                    Text("·", color = MaterialTheme.colorScheme.outline)
                    FilterChip(
                        selected = sort == "frequent",
                        onClick = { sort = if (sort == "frequent") "latest" else "frequent" },
                        label = { Text("Most frequent") },
                    )
                }
            }
            if (loaded && items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        Icons.Rounded.NotificationsActive,
                        "No alerts",
                        if (search.isNotBlank()) "No alerts match \"$search\"." else "No alerts for your depot's buses right now.",
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(items, key = { it.dedup_key }) { g ->
                        AlertGroupCard(g, onClick = { onOpenGroup(g.dedup_key) })
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertGroupCard(g: com.naarni.service.data.dto.AlertGroup, onClick: () -> Unit = {}) {
    val color = alertSeverityColor(g.latest_severity)
    Surface(color = AppSurface.raised, border = Stroke.card, shape = MaterialTheme.shapes.large,
        tonalElevation = Elevation.e0,
        shadowElevation = Elevation.e0,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(color))
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.size(36.dp).background(color.copy(alpha = 0.14f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.DirectionsBus, contentDescription = null, tint = color, modifier = Modifier.size(20.dp)) }
                    com.naarni.service.ui.components.VehicleNumber(
                        g.registration_number,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Surface(color = color.copy(alpha = 0.14f), shape = Radii.pill) {
                        Text(
                            (g.latest_severity ?: "—").replaceFirstChar { it.uppercase() },
                            color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
                val name = remember(g.alert_name, g.dedup_key) { humanize(g.alert_name) ?: g.dedup_key }
                Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                // occurrence count · relative + exact time (memoized — no re-parse on scroll)
                val meta = remember(g.latest_time, g.occurrence_count) {
                    (if (g.occurrence_count > 1) "${g.occurrence_count}× · " else "") + (relExact(g.latest_time) ?: "—")
                }
                MetaChip(Icons.Rounded.NotificationsActive, meta)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatusChip(g.open_ticket_status ?: g.latest_status)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "View details ›",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
fun TicketsScreen(
    vm: AppViewModel,
    onOpenTicket: (String) -> Unit = {},
) {
    RefreshableList(
        title = "Tickets",
        load = { vm.jobCards.myTickets() },
        itemKey = { it.name },
        empty = { EmptyState(Icons.Rounded.ConfirmationNumber, "No tickets", "Tickets auto-raised from alerts for your depot's buses appear here.") },
        row = { t -> TicketCard(t, onClick = { onOpenTicket(t.name) }) },
    )
}

@Composable
private fun TicketCard(t: com.naarni.service.data.dto.TicketItem, onClick: () -> Unit = {}) {
    val color = alertSeverityColor(t.severity)
    val openable = true
    Surface(color = AppSurface.raised, border = Stroke.card, shape = MaterialTheme.shapes.large,
        tonalElevation = Elevation.e0,
        shadowElevation = Elevation.e0,
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
                    ) { Icon(Icons.Rounded.ConfirmationNumber, contentDescription = null, tint = color, modifier = Modifier.size(20.dp)) }
                    com.naarni.service.ui.components.VehicleNumber(
                        t.registration_number?.takeIf { it.isNotBlank() } ?: (t.title ?: t.name),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
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
                    prettyDateTime(t.creation)?.let { MetaChip(Icons.Rounded.Event, it) }
                    t.severity?.takeIf { it.isNotBlank() }?.let { PriorityPill(it) }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "View details ›",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
fun ProfileScreen(
    vm: AppViewModel,
    onEditProfile: () -> Unit = {},
    onSounds: () -> Unit = {},
) {
    val profile = rememberMyProfile()
    val name = profile?.full_name?.takeIf { it.isNotBlank() }
        ?: vm.session.fullName ?: vm.session.user ?: "—"
    val context = LocalContext.current
    val feedback = com.naarni.service.core.feedback.LocalFeedback.current
    var showDelete by remember { mutableStateOf(false) }

    fun openPage(path: String) {
        runCatching {
            context.startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(com.naarni.service.BuildConfig.BASE_URL + path),
                ),
            )
        }.onFailure { feedback.error() }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Profile", style = MaterialTheme.typography.titleLarge)
        Surface(color = AppSurface.raised, border = Stroke.card, shape = MaterialTheme.shapes.large, tonalElevation = Elevation.e0, shadowElevation = Elevation.e0, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ProfileFace(profile, size = 56)
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    profile?.designation?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    StatusChip(vm.session.primaryRole)
                }
                TextButton(onClick = onEditProfile) { Text("Edit") }
            }
        }

        MyDepotCard(vm)

        // Legal, privacy & account management (Play Store requirements)
        Surface(color = AppSurface.raised, border = Stroke.card, shape = MaterialTheme.shapes.large, tonalElevation = Elevation.e0, shadowElevation = Elevation.e0, modifier = Modifier.fillMaxWidth()) {
            Column {
                ProfileRow(Icons.Rounded.MusicNote, "Notification sounds") { onSounds() }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                ProfileRow(Icons.Rounded.PrivacyTip, "Privacy Policy") { openPage("privacy-policy") }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                ProfileRow(Icons.Rounded.Description, "Terms & Conditions") { openPage("terms-and-conditions") }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                ProfileRow(Icons.Rounded.DeleteForever, "Delete account", destructive = true) { showDelete = true }
            }
        }

        OutlinedButton(onClick = { vm.logout() }, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null)
            Spacer(Modifier.height(0.dp))
            Text("  Log out")
        }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { if (!vm.ui.loading) showDelete = false },
            icon = { Icon(Icons.Rounded.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete your account?") },
            text = {
                Text(
                    "This deactivates your account and signs you out immediately. Your " +
                        "personal data is deleted within 30 days. Job records you created may " +
                        "be retained for audit. This can't be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !vm.ui.loading,
                    onClick = {
                        vm.deleteAccount { ok, msg ->
                            showDelete = false
                            if (ok) feedback.success() else feedback.error()
                            android.widget.Toast.makeText(
                                context,
                                if (ok) "Account deleted. You've been signed out." else (msg ?: "Could not delete account"),
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(enabled = !vm.ui.loading, onClick = { showDelete = false }) { Text("Cancel") }
            },
        )
    }
}

/** A tappable row (icon + label) used in the Profile legal/account section. */
@Composable
private fun ProfileRow(icon: ImageVector, label: String, destructive: Boolean = false, onClick: () -> Unit) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
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

    Surface(color = AppSurface.raised, border = Stroke.card, shape = MaterialTheme.shapes.large, tonalElevation = Elevation.e0, shadowElevation = Elevation.e0, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Business, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
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
