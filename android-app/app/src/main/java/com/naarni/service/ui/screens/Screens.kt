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
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Pending
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.naarni.service.data.dto.JobCardListItem
import com.naarni.service.ui.AppViewModel
import com.naarni.service.ui.components.EmptyState
import com.naarni.service.ui.components.SectionHeader
import com.naarni.service.ui.components.StatTile
import com.naarni.service.ui.components.StatusChip
import com.naarni.service.ui.theme.BrandGradient

@Composable
fun HomeScreen(vm: AppViewModel, onCreateJobCard: () -> Unit) {
    var items by remember { mutableStateOf<List<JobCardListItem>>(emptyList()) }
    LaunchedEffect(Unit) { runCatching { vm.jobCards.myJobCards() }.onSuccess { items = it } }
    val active = items.count { it.workflow_state?.lowercase() != "closed" }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column {
            Text("Welcome back 👋", style = MaterialTheme.typography.headlineSmall)
            Text(
                vm.session.fullName ?: vm.session.user ?: "Service Engineer",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            items.take(4).forEach { JobCardRow(it) }
        }
    }
}

@Composable
fun JobCardsScreen(vm: AppViewModel) {
    var items by remember { mutableStateOf<List<JobCardListItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { vm.jobCards.myJobCards() }
            .onSuccess { items = it; loading = false }
            .onFailure { error = it.message; loading = false }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(20.dp)) {
        Text("My Job Cards", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        when {
            loading -> Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            error != null -> Text("Could not load: $error", color = MaterialTheme.colorScheme.error)
            items.isEmpty() -> EmptyState(Icons.Filled.Inbox, "No job cards yet", "Cards assigned to you will appear here.")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(items, key = { it.name }) { JobCardRow(it) }
            }
        }
    }
}

@Composable
private fun JobCardRow(jc: JobCardListItem) {
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.DirectionsBus, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
            Column(Modifier.weight(1f)) {
                Text(jc.vehicle_number ?: jc.name, style = MaterialTheme.typography.titleMedium)
                Text(jc.job_card_type ?: "—", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusChip(jc.workflow_state)
        }
    }
}

@Composable
fun AlertsScreen() = PlaceholderTab(
    icon = Icons.Filled.NotificationsActive,
    title = "Alerts",
    body = "Live alerts for the buses and depots you cover will appear here — severity, the reading vs threshold, a map pin, and a link to the ticket. Activates with the Phase-0 backend feed.",
)

@Composable
fun TicketsScreen() = PlaceholderTab(
    icon = Icons.Filled.ConfirmationNumber,
    title = "Tickets",
    body = "Tickets auto-raised from alerts for your depot's buses. Acknowledge, resolve, or turn one into a Job Card — and push notifications deeplink straight here. Activates with the Phase-0 backend.",
)

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
