package com.naarni.service.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.naarni.service.data.dto.LinkOption
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The picker behind a `Link` step.
 *
 * Opens already populated — the operator sees the list before typing, which is
 * the app's standing rule for every dropdown and the difference between a
 * picker and a search box. A blank query is served from the repository's cache
 * when the network is gone, so this still works in a shed.
 *
 * When the step allows it, the sheet also offers to add what is missing. That
 * exists because a clerk holding an uncatalogued part cannot be told to stop and
 * phone an administrator — and it is guarded on the server, which folds the
 * typed name against every existing row rather than creating a fourth spelling
 * of something that is already there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkPickerSheet(
    title: String,
    allowCreate: Boolean,
    /** Blank query first, then debounced as they type. */
    fetch: suspend (String) -> List<LinkOption>,
    onCreate: suspend (String) -> LinkOption?,
    onPick: (LinkOption) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<LinkOption>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var creating by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(query) {
        loading = true
        if (query.isNotBlank()) delay(250)
        results = runCatching { fetch(query) }.getOrDefault(emptyList())
        loading = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search…") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )

            notice?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            HorizontalDivider()

            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(results, key = { it.value }) { option ->
                    ListItem(
                        headlineContent = { Text(option.label, fontWeight = FontWeight.Medium) },
                        supportingContent = option.sublabel?.let { { Text(it) } },
                        trailingContent = option.badge?.let { { AssistChip(onClick = {}, label = { Text(it) }) } },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 60.dp)
                            .clickable { onPick(option) },
                    )
                    HorizontalDivider()
                }
                if (results.isEmpty() && !loading) {
                    item {
                        Text(
                            if (query.isBlank()) "Nothing here yet" else "No matches for “$query”",
                            Modifier.fillMaxWidth().padding(24.dp),
                        )
                    }
                }
                if (loading) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(24.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
                    }
                }
            }

            if (allowCreate) {
                HorizontalDivider()
                val typed = query.trim()
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (creating) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Adding…", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Button(
                            onClick = {
                                scope.launch {
                                    creating = true
                                    val created = runCatching { onCreate(typed) }.getOrNull()
                                    creating = false
                                    if (created == null) {
                                        // Creating needs the network — an id invented
                                        // on the phone would collide with the server's.
                                        notice = "Couldn't add it. Check your signal and try again."
                                    } else {
                                        onPick(created)
                                    }
                                }
                            },
                            // Three characters is the floor: anything shorter is not
                            // a name somebody will recognise in the register later.
                            enabled = typed.length >= 3,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(
                                if (typed.isEmpty()) "  Type a name to add it"
                                else "  Add “${typed.take(24)}”",
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}
