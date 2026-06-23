package com.naarni.service.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.naarni.service.data.dto.SuggestionItem
import kotlinx.coroutines.delay

/**
 * The one searchable dropdown used everywhere (KOTLIN_APP_PLAN.md §2.A / §4).
 * Opens a bottom sheet showing suggestions BEFORE the user types; debounced
 * server search as they type. Big tap rows. No bare spinner/free-text where a
 * list exists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartSelect(
    label: String,
    value: SuggestionItem?,
    placeholder: String,
    fetch: suspend (String) -> List<SuggestionItem>,
    onSelect: (SuggestionItem) -> Unit,
    modifier: Modifier = Modifier,
    suggestions: List<SuggestionItem> = emptyList(),
) {
    var open by remember { mutableStateOf(false) }

    Column(modifier) {
        Text(label, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
        Surface(
            onClick = { open = true },
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(value?.label ?: placeholder, fontWeight = if (value != null) FontWeight.SemiBold else FontWeight.Normal)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
        }
    }

    if (open) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var query by remember { mutableStateOf("") }
        var results by remember { mutableStateOf(suggestions) }
        var loading by remember { mutableStateOf(false) }

        // Debounced search; empty query shows the eager suggestions.
        LaunchedEffect(query) {
            if (query.isBlank()) {
                results = suggestions
                return@LaunchedEffect
            }
            loading = true
            delay(250)
            results = runCatching { fetch(query) }.getOrDefault(emptyList())
            loading = false
        }

        ModalBottomSheet(onDismissRequest = { open = false }, sheetState = sheet) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search $label…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            HorizontalDivider(Modifier.padding(top = 8.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                items(results, key = { it.value }) { item ->
                    ListItem(
                        headlineContent = { Text(item.label, fontWeight = FontWeight.Medium) },
                        supportingContent = item.sublabel?.let { { Text(it) } },
                        trailingContent = {
                            when {
                                item.badge != null -> AssistChip(onClick = {}, label = { Text(item.badge) })
                                item.recent -> AssistChip(onClick = {}, label = { Text("recent") })
                                else -> {}
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable {
                                onSelect(item)
                                open = false
                            },
                    )
                    HorizontalDivider()
                }
                if (results.isEmpty() && !loading) {
                    item {
                        Text(
                            if (query.isBlank()) "Start typing to search…" else "No matches",
                            Modifier.fillMaxWidth().padding(24.dp),
                        )
                    }
                }
            }
        }
    }
}
