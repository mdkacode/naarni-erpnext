package com.naarni.service.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * A titled list screen with **pull-to-refresh** built in. Loads on first show,
 * reloads on pull, and renders loading / error / empty / rows uniformly so every
 * screen behaves identically. [reloadKey] bumps to force a reload (e.g. after an
 * action elsewhere).
 */
@Composable
fun <T> RefreshableList(
    title: String,
    load: suspend () -> List<T>,
    itemKey: (T) -> Any,
    empty: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    reloadKey: Any = Unit,
    trailingHeader: @Composable RowScope.() -> Unit = {},
    row: @Composable (T) -> Unit,
) {
    var items by remember { mutableStateOf<List<T>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun fetch() {
        runCatching { load() }
            .onSuccess { items = it; error = null }
            .onFailure { error = it.message ?: "Could not load" }
    }

    LaunchedEffect(reloadKey) { loading = true; fetch(); loading = false }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            trailingHeader()
        }
        Refreshable(
            refreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; fetch(); refreshing = false } },
            modifier = Modifier.weight(1f),
        ) {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when {
                    loading -> item {
                        Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    error != null && items.isEmpty() -> item {
                        Text("Could not load: $error", color = MaterialTheme.colorScheme.error)
                    }
                    items.isEmpty() -> item {
                        Box(Modifier.fillMaxWidth().padding(top = 64.dp), contentAlignment = Alignment.Center) {
                            empty()
                        }
                    }
                    else -> items(items, key = itemKey) { row(it) }
                }
            }
        }
    }
}
