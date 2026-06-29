package com.naarni.service.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.naarni.service.data.dto.SuggestionItem
import com.naarni.service.ui.AppViewModel
import com.naarni.service.ui.components.SmartSelect

/** Raise an Inventory Request against a job card (Technician / SE / Depot Manager). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InventoryRequestDialog(
    vm: AppViewModel,
    saving: Boolean,
    fetchParts: suspend (String) -> List<SuggestionItem>,
    onCreate: (part: String, quantity: Double, urgency: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val urgencies = vm.opt("urgency")
    var part by remember { mutableStateOf<SuggestionItem?>(null) }
    var qty by remember { mutableStateOf("1") }
    var urgency by remember { mutableStateOf(urgencies.getOrElse(1) { urgencies.firstOrNull() ?: "Medium" }) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Raise inventory request") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SmartSelect(
                    label = "Part",
                    value = part,
                    placeholder = "Pick a part",
                    fetch = fetchParts,
                    onSelect = { part = it },
                    fetchOnOpen = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = qty, onValueChange = { qty = it },
                    label = { Text("Quantity") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Text("Urgency", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    urgencies.forEach { u ->
                        FilterChip(selected = urgency == u, onClick = { urgency = u }, label = { Text(u) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && part != null && (qty.toDoubleOrNull() ?: 0.0) > 0,
                onClick = { onCreate(part!!.value, qty.toDouble(), urgency) },
            ) { Text(if (saving) "Raising…" else "Raise") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
