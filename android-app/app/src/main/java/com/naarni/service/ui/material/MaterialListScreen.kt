package com.naarni.service.ui.material

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.naarni.service.data.dto.MovementSummary
import com.naarni.service.ui.components.AppBar
import com.naarni.service.ui.components.EmptyState
import com.naarni.service.ui.components.LoadingOverlay
import com.naarni.service.ui.components.MetaChip
import com.naarni.service.ui.components.Refreshable
import com.naarni.service.ui.theme.AppSurface
import com.naarni.service.ui.theme.Semantic

/**
 * The Material tab: gate movements, newest first.
 *
 * The scope chips are the whole navigation. "To verify" only appears for
 * somebody who can actually verify — a chip that answers every tap with a
 * permission error is worse than no chip.
 */
@Composable
fun MaterialListScreen(
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    vm: MaterialViewModel = viewModel(),
) {
    val ui = vm.ui
    LaunchedEffect(Unit) { vm.load() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppBar(
                title = "Material",
                subtitle = ui.context?.locations
                    ?.firstOrNull { it.name == ui.context?.default_location }
                    ?.location_name
                    ?: "Inward & outward gate register",
            )
        },
        floatingActionButton = {
            if (ui.context?.can_write != false) {
                // One button, not two. Inward or outward is the first
                // question of the entry itself, so asking it here as well would
                // be asking it twice — and a wrong tap here used to mean backing
                // out of a movement that had already been created.
                ExtendedFloatingActionButton(
                    onClick = onNew,
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text("New entry") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                ScopeChips(
                    scope = ui.scope,
                    canVerify = ui.canVerify,
                    awaiting = ui.awaitingCount,
                    onPick = { vm.load(it) },
                )

                Refreshable(
                    refreshing = ui.loading,
                    onRefresh = { vm.load(refreshContext = true) },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (ui.movements.isEmpty() && !ui.loading) {
                        EmptyState(
                            icon = Icons.Rounded.Inventory2,
                            title = when (ui.scope) {
                                "awaiting" -> "Nothing waiting"
                                "finished" -> "Nothing closed yet"
                                else -> "No open movements"
                            },
                            body = "Tap New entry when something crosses the gate.",
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(ui.movements, key = { it.name }) { row ->
                                MovementCard(row) { onOpen(row.name) }
                            }
                        }
                    }
                }
            }
            LoadingOverlay(visible = ui.saving)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScopeChips(
    scope: String,
    canVerify: Boolean,
    awaiting: Int,
    onPick: (String) -> Unit,
) {
    val scopes = buildList {
        add("open" to "Open")
        if (canVerify) add("awaiting" to if (awaiting > 0) "To verify ($awaiting)" else "To verify")
        add("finished" to "Closed")
    }
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        scopes.forEach { (key, label) ->
            FilterChip(
                selected = scope == key,
                onClick = { onPick(key) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun MovementCard(row: MovementSummary, onClick: () -> Unit) {
    val inward = row.movement_type == "Inward"
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = AppSurface.raised,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Direction is carried by the arrow first and the tint second, so
                // it still reads for anyone who cannot separate the two colours.
                Icon(
                    if (inward) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,
                    contentDescription = row.movement_type,
                    tint = if (inward) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    "  ${row.movement_type}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (inward) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "  ·  ${row.name}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(Modifier.weight(1f))
                MovementStatusChip(row.status)
            }

            Text(
                row.party_name?.takeIf { it.isNotBlank() } ?: "Unnamed party",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )

            val subtitle = listOfNotNull(
                row.purpose?.takeIf { it.isNotBlank() },
                row.reference_no?.takeIf { it.isNotBlank() },
                row.transport_vehicle_no?.takeIf { it.isNotBlank() },
            ).joinToString("  ·  ")
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MetaChip(Icons.Rounded.Inventory2, "${row.total_items} items")
                MetaChip(Icons.Rounded.PhotoCamera, "${row.evidence_pct.toInt()}%")
                if (row.qr_count > 0) MetaChip(Icons.Rounded.QrCode2, "${row.qr_count}")
                if (row.damaged_count > 0) {
                    MetaChip(
                        Icons.Rounded.WarningAmber,
                        "${row.damaged_count} not OK",
                        tint = Semantic.caution,
                    )
                }
            }
        }
    }
}

/** Status pill. Kept local so the gate's five states never drift into the shared one. */
@Composable
fun MovementStatusChip(status: String) {
    val tint: Color = when (status) {
        "Completed" -> Semantic.positive
        "Awaiting Verification" -> Semantic.caution
        "Cancelled" -> MaterialTheme.colorScheme.onSurfaceVariant
        "In Progress" -> Semantic.active
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(6.dp), color = tint.copy(alpha = 0.14f)) {
        Text(
            status,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = tint,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
