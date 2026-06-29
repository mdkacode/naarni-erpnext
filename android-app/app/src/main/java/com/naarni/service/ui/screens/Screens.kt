package com.naarni.service.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.naarni.service.ui.AppViewModel
import com.naarni.service.ui.components.EmptyState
import com.naarni.service.ui.components.MetaChip
import com.naarni.service.ui.components.PriorityPill
import com.naarni.service.ui.components.RefreshableList
import com.naarni.service.ui.components.SectionHeader
import com.naarni.service.ui.components.StatTile
import com.naarni.service.ui.components.StatusChip
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
        Surface(
            onClick = onCreateJobCard,
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

@Composable
private fun JobCardRow(jc: JobCardListItem, onClick: () -> Unit = {}) {
    val criticality = jc.force_close_severity?.takeIf { it.isNotBlank() } ?: jc.priority
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.DirectionsBus, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Line 1: registration number + status
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        jc.vehicle_number ?: jc.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    StatusChip(jc.workflow_state)
                }
                // Line 2: type + criticality + SLA breach
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        jc.job_card_type ?: jc.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PriorityPill(criticality)
                    if (jc.sla_breached == 1) {
                        MetaChip(Icons.Filled.Warning, "SLA", tint = MaterialTheme.colorScheme.error)
                    }
                }
                // Line 3: customer · created date · odometer
                val customer = jc.customer_name?.takeIf { it.isNotBlank() }
                // Prefer the business "Date"; fall back to the system created-on so a
                // date always shows on the card.
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

@Composable
fun AlertsScreen(vm: AppViewModel) {
    RefreshableList(
        title = "Alerts",
        load = { vm.jobCards.myAlertEvents() },
        itemKey = { it.name },
        empty = { EmptyState(Icons.Filled.NotificationsActive, "No alerts", "Alerts for the buses at your depot show up here. (None yet, or your depot has no assigned buses.)") },
        row = { a ->
            Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(a.title ?: a.parameter ?: a.name, style = MaterialTheme.typography.titleMedium)
                        Text("${a.registration_number ?: ""}  ·  ${a.message ?: ""}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StatusChip(a.severity)
                }
            }
        },
    )
}

@Composable
fun TicketsScreen(vm: AppViewModel) {
    RefreshableList(
        title = "Tickets",
        load = { vm.jobCards.myTickets() },
        itemKey = { it.name },
        empty = { EmptyState(Icons.Filled.ConfirmationNumber, "No tickets", "Tickets auto-raised from alerts for your depot's buses appear here.") },
        row = { t ->
            Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(t.title ?: t.name, style = MaterialTheme.typography.titleMedium)
                        Text(t.registration_number ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StatusChip(t.status)
                }
            }
        },
    )
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
        OutlinedButton(onClick = { vm.logout() }, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
            Spacer(Modifier.height(0.dp))
            Text("  Log out")
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
