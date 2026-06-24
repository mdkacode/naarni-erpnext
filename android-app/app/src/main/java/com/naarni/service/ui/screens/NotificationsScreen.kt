package com.naarni.service.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.naarni.service.data.dto.NotificationItem
import com.naarni.service.ui.AppViewModel
import com.naarni.service.ui.components.EmptyState
import com.naarni.service.ui.components.Refreshable
import kotlinx.coroutines.launch

/**
 * The notification inbox: the user's Notification Log, newest first, with unread
 * highlighting, tap-to-read (+ open the linked job card), "mark all read", and
 * pull-to-refresh.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(vm: AppViewModel, onBack: () -> Unit, onOpenJobCard: (String) -> Unit = {}) {
    var items by remember { mutableStateOf<List<NotificationItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun fetch() {
        runCatching { vm.jobCards.notifications(limit = 50, offset = 0) }.onSuccess { items = it }
    }
    LaunchedEffect(Unit) { fetch(); loading = false }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notifications") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (items.any { !it.read }) {
                        TextButton(onClick = {
                            scope.launch { runCatching { vm.jobCards.markAllNotificationsRead() }; fetch() }
                        }) { Text("Mark all read") }
                    }
                },
            )
        },
    ) { pad ->
        Refreshable(
            refreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; fetch(); refreshing = false } },
            modifier = Modifier.padding(pad).fillMaxSize().background(MaterialTheme.colorScheme.background),
        ) {
            LazyColumn(
                Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when {
                    loading -> item { Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items.isEmpty() -> item {
                        Box(Modifier.fillMaxWidth().padding(top = 72.dp), contentAlignment = Alignment.Center) {
                            EmptyState(Icons.Filled.NotificationsNone, "No notifications", "You're all caught up.")
                        }
                    }
                    else -> items(items, key = { it.name }) { n ->
                        NotificationRow(n, onClick = {
                            scope.launch {
                                if (!n.read) runCatching { vm.jobCards.markNotificationRead(n.name) }
                                fetch()
                            }
                            n.job_card?.let(onOpenJobCard)
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(n: NotificationItem, onClick: () -> Unit) {
    val bg = if (n.read) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = bg,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Unread dot
            Box(
                Modifier.size(10.dp).padding(top = 6.dp).clip(CircleShape)
                    .background(if (n.read) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    n.subject ?: "Notification",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (n.read) FontWeight.Medium else FontWeight.Bold,
                )
                n.body?.let {
                    Text(
                        it.replace(Regex("<[^>]*>"), "").trim(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                    )
                }
                n.creation?.let {
                    Text(
                        it.take(16).replace("T", " "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}
