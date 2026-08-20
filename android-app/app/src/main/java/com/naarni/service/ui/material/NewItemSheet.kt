package com.naarni.service.ui.material

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.naarni.service.data.dto.GateItemGroup
import com.naarni.service.data.dto.GateItemSuggestion

/**
 * "Item not in the list" — create it here and then.
 *
 * A gate clerk holding a part the catalogue does not have cannot be told to
 * phone an administrator, so this exists. Four fields, three of them
 * pre-answered: the name comes pre-filled with whatever was typed into the
 * search box, the group defaults to the one last used, and the unit defaults to
 * Nos.
 *
 * The server, not this sheet, is what stops the catalogue growing four spellings
 * of "headlamp": it folds the typed name against every existing item and returns
 * the match instead of creating a twin. The caller reports which happened, so a
 * clerk is never told we added something we did not.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewItemSheet(
    initialName: String,
    groups: List<GateItemGroup>,
    uoms: List<String>,
    lastGroup: String?,
    saving: Boolean,
    onCreate: (name: String, group: String, uom: String, hasQr: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(initialName) }
    var group by remember { mutableStateOf(lastGroup.orEmpty()) }
    var uom by remember { mutableStateOf(uoms.firstOrNull() ?: "Nos") }
    var hasQr by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Add a new item", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "It becomes usable straight away. An administrator reviews items added at the gate later.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Item name") },
                singleLine = true,
                supportingText = { Text("Write it the way the store would say it.") },
                modifier = Modifier.fillMaxWidth(),
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Group", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    groups.forEach { option ->
                        FilterChip(
                            selected = group == option.name,
                            onClick = { group = if (group == option.name) "" else option.name },
                            label = { Text(option.part_group_name.ifBlank { option.name }) },
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Unit", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    uoms.forEach { option ->
                        FilterChip(
                            selected = uom == option,
                            onClick = { uom = option },
                            label = { Text(option) },
                        )
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Has a QR / serial label", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "The scanner will open whenever this item is picked.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = hasQr, onCheckedChange = { hasQr = it })
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = { onCreate(name.trim(), group, uom, hasQr) },
                    enabled = name.trim().length >= 3 && !saving,
                    modifier = Modifier.weight(1f),
                ) { Text("Add item") }
            }
        }
    }
}

/** Convenience: the picked suggestion, ready to become a movement line. */
fun GateItemSuggestion.defaultQty(): Double = if (qty_per_bus > 0) qty_per_bus else 1.0
