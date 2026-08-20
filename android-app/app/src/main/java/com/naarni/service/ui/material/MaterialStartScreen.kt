package com.naarni.service.ui.material

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.naarni.service.core.location.LocationProvider
import com.naarni.service.data.dto.SuggestionItem
import com.naarni.service.ui.components.AppBar
import com.naarni.service.ui.components.LoadingOverlay
import com.naarni.service.ui.components.SmartSelect
import com.naarni.service.ui.theme.AppSurface
import kotlinx.coroutines.launch

/**
 * The New-Movement wizard.
 *
 * Three steps of four fields, not one form of nineteen — the EAS rule the whole
 * app is built to. Steps 2 and 3 are explicitly skippable: a truck at the gate
 * is not always accompanied by paperwork, and a clerk blocked on a challan
 * number they do not have will record the load on a piece of paper instead.
 *
 * Nothing here is free text where a list exists. Location, purpose, party type
 * and document type are all pickers; only names and numbers are typed.
 */
@Composable
fun MaterialStartScreen(
    initialType: String,
    onOpened: (String) -> Unit,
    onBack: () -> Unit,
    vm: MaterialViewModel = viewModel(),
) {
    val ui = vm.ui
    val draft = vm.draft
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        vm.load()
        vm.newDraft(initialType)
    }
    // The wizard cannot pre-fill a location until the context has landed.
    LaunchedEffect(ui.context?.default_location) {
        if (draft.location.isBlank()) {
            vm.editDraft { it.copy(location = ui.context?.default_location.orEmpty()) }
        }
    }

    val locations = ui.context?.locations.orEmpty()
    val gates = locations.firstOrNull { it.name == draft.location }?.gates.orEmpty()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppBar(
                title = "New movement",
                subtitle = "Step ${step + 1} of 3",
                onBack = { if (step == 0) onBack() else step-- },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                LinearProgressIndicator(
                    progress = { (step + 1) / 3f },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                )

                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    when (step) {
                        0 -> StepWhatAndWhere(vm, locations.map { it.name to it.location_name }, gates)
                        1 -> StepCounterparty(vm)
                        else -> StepTransport(vm)
                    }
                    ui.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }

                Surface(color = AppSurface.raised) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (step > 0) {
                            TextButton(onClick = { step-- }) { Text("Back") }
                        }
                        Box(Modifier.weight(1f))
                        if (step < 2) {
                            // Skipping is offered, not hidden behind Next-with-blanks:
                            // a clerk who has no challan should not have to work out
                            // that leaving the box empty is allowed.
                            if (step == 1) TextButton(onClick = { step++ }) { Text("Skip") }
                            Button(
                                onClick = { step++ },
                                enabled = step != 0 || draft.location.isNotBlank(),
                            ) { Text("Next") }
                        } else {
                            Button(
                                onClick = {
                                    scope.launch {
                                        // Best effort: a fix that never arrives must
                                        // not stop the truck being recorded.
                                        val fix = runCatching {
                                            LocationProvider(context).current(timeoutMs = 4000)
                                        }.getOrNull()
                                        vm.startMovement(fix?.latitude, fix?.longitude, onOpened)
                                    }
                                },
                                enabled = !ui.saving,
                            ) { Text("Start adding items") }
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
private fun StepWhatAndWhere(
    vm: MaterialViewModel,
    locations: List<Pair<String, String>>,
    gates: List<String>,
) {
    val draft = vm.draft

    Text("What is moving?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        DirectionButton(
            label = "Inward",
            hint = "Coming in",
            icon = Icons.Rounded.ArrowDownward,
            selected = draft.movementType == "Inward",
            modifier = Modifier.weight(1f),
        ) { vm.editDraft { d -> d.copy(movementType = "Inward", purpose = "") } }
        DirectionButton(
            label = "Outward",
            hint = "Going out",
            icon = Icons.Rounded.ArrowUpward,
            selected = draft.movementType == "Outward",
            modifier = Modifier.weight(1f),
        ) { vm.editDraft { d -> d.copy(movementType = "Outward", purpose = "") } }
    }

    SmartSelect(
        label = "Location",
        value = locations.firstOrNull { it.first == draft.location }
            ?.let { SuggestionItem(value = it.first, label = it.second) },
        placeholder = "Pick the plant",
        fetch = { query ->
            locations
                .filter { query.isBlank() || it.second.contains(query, ignoreCase = true) }
                .map { SuggestionItem(value = it.first, label = it.second) }
        },
        onSelect = { vm.editDraft { d -> d.copy(location = it.value, gate = "") } },
        fetchOnOpen = true,
    )

    if (gates.isNotEmpty()) {
        ChipRow("Gate", gates, draft.gate) { vm.editDraft { d -> d.copy(gate = it) } }
    }

    val purposes = vm.purposesForDraft()
    if (purposes.isNotEmpty()) {
        ChipRow("Why", purposes, draft.purpose) { vm.editDraft { d -> d.copy(purpose = it) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepCounterparty(vm: MaterialViewModel) {
    val draft = vm.draft
    val context = vm.ui.context

    Text("Who and what document?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text(
        "Skip this if the paperwork is not with the truck — you can add it later.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    ChipRow("Party type", context?.party_types.orEmpty(), draft.partyType) {
        vm.editDraft { d -> d.copy(partyType = it) }
    }

    // Recents first: the same dozen suppliers arrive week after week, and typing
    // "Sri Lakshmi Engineering" on a phone at a gate is how spellings multiply.
    val recents = context?.recent_parties.orEmpty()
    if (recents.isNotEmpty()) {
        SmartSelect(
            label = "Party",
            value = draft.partyName.takeIf { it.isNotBlank() }
                ?.let { SuggestionItem(value = it, label = it) },
            placeholder = "Pick or type below",
            fetch = { query ->
                recents
                    .filter { query.isBlank() || it.party_name.contains(query, ignoreCase = true) }
                    .map { SuggestionItem(value = it.party_name, label = it.party_name, sublabel = it.party_type, recent = true) }
            },
            onSelect = { vm.editDraft { d -> d.copy(partyName = it.value) } },
            fetchOnOpen = true,
        )
    }

    OutlinedTextField(
        value = draft.partyName,
        onValueChange = { vm.editDraft { d -> d.copy(partyName = it) } },
        label = { Text(if (recents.isEmpty()) "Party name" else "…or a new name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    ChipRow("Document", context?.reference_types.orEmpty(), draft.referenceType) {
        vm.editDraft { d -> d.copy(referenceType = it) }
    }

    OutlinedTextField(
        value = draft.referenceNo,
        onValueChange = { vm.editDraft { d -> d.copy(referenceNo = it) } },
        label = { Text("Document number") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun StepTransport(vm: MaterialViewModel) {
    val draft = vm.draft

    Text("Transport", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text(
        "Optional. It is what links this gate note to the truck if anything is queried later.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OutlinedTextField(
        value = draft.truckNo,
        onValueChange = { vm.editDraft { d -> d.copy(truckNo = it.uppercase()) } },
        label = { Text("Truck number") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = draft.driverName,
        onValueChange = { vm.editDraft { d -> d.copy(driverName = it) } },
        label = { Text("Driver name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = draft.driverPhone,
        onValueChange = { value -> vm.editDraft { d -> d.copy(driverPhone = value.filter(Char::isDigit).take(10)) } },
        label = { Text("Driver phone") },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Phone),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DirectionButton(
    label: String,
    hint: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val border = if (selected) MaterialTheme.colorScheme.primary else AppSurface.hairline
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(84.dp),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, border),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A labelled row of single-choice chips. Tapping the selected chip clears it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(label: String, options: List<String>, selected: String, onPick: (String) -> Unit) {
    if (options.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = selected == option,
                    onClick = { onPick(if (selected == option) "" else option) },
                    label = { Text(option) },
                )
            }
        }
    }
}
