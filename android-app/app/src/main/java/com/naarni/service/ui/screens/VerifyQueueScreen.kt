package com.naarni.service.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.FactCheck
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naarni.service.data.dto.VerificationQueue
import com.naarni.service.data.dto.VerificationRun
import com.naarni.service.ui.AppViewModel
import com.naarni.service.ui.components.AppBar
import com.naarni.service.ui.components.EmptyState
import com.naarni.service.ui.components.LoadingOverlay
import com.naarni.service.ui.components.MetaChip
import com.naarni.service.ui.components.Refreshable
import com.naarni.service.ui.theme.AppSurface
import com.naarni.service.ui.theme.Semantic
import com.naarni.service.ui.theme.Radii

/**
 * Packs waiting for a signature, oldest first.
 *
 * Oldest first is deliberate and it is the whole ordering decision: a queue that
 * shows the newest pack at the top is a queue whose bottom never gets read, and
 * the pack that has been waiting longest is the one holding up a despatch.
 *
 * Needs the network. Verification is not offline work — the point of it is that
 * a second person, who was not there, reads what was recorded; doing that
 * against a stale copy of the record would defeat it.
 */
@Composable
fun VerifyQueueScreen(
    vm: AppViewModel,
    onOpen: (run: String, stage: String) -> Unit,
    onBack: () -> Unit,
) {
    var queue by remember { mutableStateOf<VerificationQueue?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        loading = true
        error = null
        runCatching { vm.processes.verificationQueue() }
            .onSuccess { queue = it }
            .onFailure { error = it.message ?: "Could not load the queue." }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        AppBar(
            title = "To verify",
            subtitle = queue?.plant_name?.takeIf { it.isNotBlank() } ?: "Awaiting your sign-off",
            onBack = onBack,
        )
        Box(Modifier.fillMaxSize()) {
            Refreshable(
                refreshing = loading,
                onRefresh = { reload++ },
                modifier = Modifier.fillMaxSize(),
            ) {
                val runs = queue?.runs.orEmpty()
                when {
                    error != null -> EmptyState(
                        icon = Icons.Rounded.FactCheck,
                        title = "Could not load the queue",
                        body = error!!,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Told apart on purpose. One of these is a good morning; the
                    // other two are somebody else's job to fix, and showing all
                    // three as "nothing here" is how a misconfigured account
                    // looks like an empty day for a week.
                    queue?.reason == "no_role" -> EmptyState(
                        icon = Icons.Rounded.FactCheck,
                        title = "You do not verify any process",
                        body = "Ask an administrator to give you the verification role for your line.",
                        modifier = Modifier.fillMaxSize(),
                    )
                    queue?.plantMissing == true -> EmptyState(
                        icon = Icons.Rounded.FactCheck,
                        title = "No plant on your profile",
                        body = "A verifier only sees their own plant's packs, so an administrator " +
                            "has to set which plant you work at before this list can fill.",
                        modifier = Modifier.fillMaxSize(),
                    )
                    runs.isEmpty() && !loading -> EmptyState(
                        icon = Icons.Rounded.FactCheck,
                        title = "Nothing waiting",
                        body = "Every pack at ${queue?.plant_name.orEmpty().ifBlank { "your plant" }} " +
                            "has been signed off.",
                        modifier = Modifier.fillMaxSize(),
                    )
                    else -> LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(runs, key = { it.name }) { run ->
                            QueueCard(run) { onOpen(run.name, run.stage) }
                        }
                    }
                }
            }
            LoadingOverlay(visible = loading && queue == null)
        }
    }
}

@Composable
private fun QueueCard(run: VerificationRun, onClick: () -> Unit) {
    Surface(
        shape = Radii.lg,
        color = AppSurface.raised,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        run.run_identifier?.takeIf { it.isNotBlank() } ?: run.name,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "${run.process_name} · ${run.stage_label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }

            Text(
                buildString {
                    append(run.started_by_name?.takeIf { it.isNotBlank() } ?: run.started_by.orEmpty())
                    run.completed_at?.takeIf { it.isNotBlank() }?.let { append("  ·  ${it.take(16)}") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MetaChip(Icons.Rounded.FactCheck, "${run.answered_count} checks")
                // Failures and photographs are what decide whether this is a
                // thirty-second sign-off or a walk to the bench, so they are on
                // the card rather than one tap in.
                MetaChip(
                    Icons.Rounded.PhotoCamera,
                    "${run.photo_count} photos",
                    tint = if (run.photo_count > 0) Semantic.positive else Semantic.caution,
                )
                if (run.fail_count > 0) {
                    MetaChip(
                        Icons.Rounded.FactCheck,
                        "${run.fail_count} failed",
                        tint = Semantic.caution,
                    )
                }
            }
        }
    }
}
