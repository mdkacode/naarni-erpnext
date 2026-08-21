package com.naarni.service.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.naarni.service.data.dto.SuggestionItem

/**
 * A multi-select built on [SmartSelect]: the picker appends to a list, and each
 * chosen item shows as a removable chip. No free text — every value comes from
 * the searchable source (EAS — Simplify, no typos).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MultiSelectField(
    label: String,
    selected: List<SuggestionItem>,
    placeholder: String,
    fetch: suspend (String) -> List<SuggestionItem>,
    onAdd: (SuggestionItem) -> Unit,
    onRemove: (SuggestionItem) -> Unit,
    modifier: Modifier = Modifier,
    suggestions: List<SuggestionItem> = emptyList(),
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SmartSelect(
            label = label,
            value = null,
            placeholder = placeholder,
            fetchOnOpen = true,
            suggestions = suggestions.filter { s -> selected.none { it.value == s.value } },
            fetch = { q -> fetch(q).filter { hit -> selected.none { it.value == hit.value } } },
            onSelect = { hit -> if (selected.none { it.value == hit.value }) onAdd(hit) },
        )
        if (selected.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                selected.forEach { item ->
                    AssistChip(
                        onClick = { onRemove(item) },
                        label = { Text(item.label) },
                        trailingIcon = {
                            Icon(Icons.Rounded.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp))
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    )
                }
            }
        }
    }
}
