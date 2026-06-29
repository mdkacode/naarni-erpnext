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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.naarni.service.data.dto.VehicleLive
import com.naarni.service.ui.AppViewModel

/**
 * Live detail for one vehicle — opened from the Fleet list. Shows the bus's
 * registration/operator/route plus its live telemetry (odometer, battery, speed,
 * location, connectivity), with the reading time already in IST.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleDetailScreen(vm: AppViewModel, vehicle: String, onBack: () -> Unit) {
    var live by remember { mutableStateOf<VehicleLive?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(vehicle) {
        loading = true
        error = null
        runCatching { vm.jobCards.vehicleLive(vehicle) }
            .onSuccess { live = it; loading = false }
            .onFailure { error = it.message ?: "Could not load live data"; loading = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(live?.registration_number ?: vehicle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape) {
                    Icon(
                        Icons.Filled.DirectionsBus,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(14.dp).size(28.dp),
                    )
                }
                Spacer(Modifier.size(14.dp))
                Column {
                    Text(
                        live?.registration_number ?: vehicle,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    val mm = listOfNotNull(live?.make, live?.model).joinToString(" ")
                    if (mm.isNotBlank()) {
                        Text(mm, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Multi-angle bus photo gallery (persistent identity images)
            com.naarni.service.ui.components.BusPhotoGrid(
                vm = vm,
                parentDoctype = "Vehicle",
                parentName = vehicle,
            )

            when {
                loading -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                live == null -> InfoCard("Live data", listOf("Status" to (error ?: "No live telemetry for this vehicle")))
                else -> {
                    val l = live!!
                    InfoCard(
                        "Vehicle",
                        listOf(
                            "Operator" to l.operator,
                            "Route" to l.route_name,
                            "Activity" to l.activity,
                            "Connectivity" to l.connectivity_status,
                        ),
                    )
                    InfoCard(
                        "Running",
                        listOf(
                            "Odometer" to l.odometer?.let { "$it km" },
                            "Range left" to l.distance_to_empty?.let { "${it.toInt()} km" },
                            "Speed" to l.ground_speed_kmph?.let { "${it} km/h" },
                        ),
                    )
                    InfoCard(
                        "Battery",
                        listOf(
                            "Charge (SOC)" to l.battery_soc?.let { "$it %" },
                            "Health (SOH)" to l.battery_soh?.let { "$it %" },
                            "Voltage" to l.battery_voltage?.let { "$it V" },
                            "Current" to l.battery_current?.let { "$it A" },
                            "Coolant temp" to l.battery_coolant_temp?.let { "$it °C" },
                        ),
                    )
                    InfoCard(
                        "Location",
                        listOf(
                            "Latitude" to l.latitude?.toString(),
                            "Longitude" to l.longitude?.toString(),
                            "Maps" to l.maps_link,
                        ),
                    )
                    InfoCard("Last reading (IST)", listOf("Updated" to l.telemetry_at_ist))
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun InfoCard(title: String, rows: List<Pair<String, String?>>) {
    val shown = rows.filter { !it.second.isNullOrBlank() }
    if (shown.isEmpty()) return
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            shown.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(k, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(v!!, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
