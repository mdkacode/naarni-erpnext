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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.naarni.service.data.dto.FleetVehicle
import com.naarni.service.ui.AppViewModel
import kotlinx.coroutines.delay

/**
 * The Service Engineer's fleet: every vehicle synced from Naarni, searchable.
 * Each row shows registration, make/model, operator, depot and live-ish status.
 */
@Composable
fun VehiclesScreen(vm: AppViewModel, onOpenVehicle: (String) -> Unit = {}) {
    var query by remember { mutableStateOf("") }
    var vehicles by remember { mutableStateOf<List<FleetVehicle>>(emptyList()) }
    var total by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // Debounced server-side search (also the initial load with empty query).
    LaunchedEffect(query) {
        loading = true
        error = null
        if (query.isNotEmpty()) delay(300)
        runCatching { vm.jobCards.fleet(query) }
            .onSuccess { vehicles = it.vehicles; total = it.total; loading = false }
            .onFailure { error = it.message ?: "Could not load vehicles"; loading = false }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Fleet", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(8.dp))
            if (total > 0) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape) {
                    Text(
                        "$total",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search registration, model or operator") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        when {
            loading && vehicles.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            error != null ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                }
            vehicles.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.DirectionsBus,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.outline,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (query.isBlank()) "No vehicles synced yet" else "No matches",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            else ->
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(vehicles, key = { it.name }) { VehicleRow(it, onClick = { onOpenVehicle(it.name) }) }
                    item { Spacer(Modifier.height(12.dp)) }
                }
        }
    }
}

@Composable
private fun VehicleRow(v: FleetVehicle, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape) {
                Icon(
                    Icons.Filled.DirectionsBus,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(10.dp).size(22.dp),
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    v.registration_number ?: v.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                v.make_model?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Row {
                    v.operator?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (!v.operator.isNullOrBlank() && !v.depot.isNullOrBlank()) {
                        Text("  ·  ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                    }
                    v.depot?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            v.vehicle_status?.let { StatusPill(it) }
        }
    }
}

@Composable
private fun StatusPill(status: String) {
    val moving = status.equals("MOVING", ignoreCase = true)
    val color = if (moving) MaterialTheme.colorScheme.tertiaryContainer
    else MaterialTheme.colorScheme.surfaceVariant
    Surface(color = color, shape = CircleShape) {
        Text(
            status.replace("_", " "),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
