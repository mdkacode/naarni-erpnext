package com.naarni.service.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp

// Authority hint per severity (PRD matrix). Keyed by option label; unknown → no hint.
private val SEVERITY_HINTS = mapOf(
    "Minor" to "Closed by SE — fix at next PMS",
    "Major" to "Needs Depot Manager — 24h follow-up",
    "Critical" to "Needs N. Maintenance Head — auto job card in 24h",
)

/** Force-close with the PRD severity matrix (authority is re-validated server-side). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ForceCloseDialog(
    vm: com.naarni.service.ui.AppViewModel,
    saving: Boolean,
    onConfirm: (severity: String, reason: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val severities = vm.opt("severity")
    var severity by remember { mutableStateOf(severities.firstOrNull() ?: "Minor") }
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Force close job card") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Severity", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    severities.forEach { s ->
                        FilterChip(selected = severity == s, onClick = { severity = s }, label = { Text(s) })
                    }
                }
                SEVERITY_HINTS[severity]?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(
                    value = reason, onValueChange = { reason = it },
                    label = { Text("Reason (required)") }, minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && reason.isNotBlank(),
                onClick = { onConfirm(severity, reason.trim()) },
            ) { Text(if (saving) "Closing…" else "Force close") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Post-closure customer feedback (rating 1–5 + comment). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FeedbackCard(
    existing: com.naarni.service.data.dto.CustomerFeedbackData?,
    busy: Boolean,
    onSubmit: (rating: Int, comment: String, recommend: String) -> Unit,
) {
    var rating by remember(existing) { mutableStateOf(existing?.rating ?: 0) }
    var comment by remember(existing) { mutableStateOf(existing?.comments ?: "") }
    var recommend by remember(existing) { mutableStateOf(existing?.would_recommend ?: "") }
    androidx.compose.material3.Card(
        Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(1.dp),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Customer Feedback", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text("Rating", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..5).forEach { n ->
                    FilterChip(selected = rating == n, onClick = { rating = n }, label = { Text("$n★") })
                }
            }
            Text("Would you recommend us?", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Yes", "Maybe", "No").forEach { opt ->
                    FilterChip(selected = recommend == opt, onClick = { recommend = opt }, label = { Text(opt) })
                }
            }
            OutlinedTextField(
                value = comment, onValueChange = { comment = it },
                label = { Text("Comment") }, minLines = 2, modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                enabled = !busy && rating in 1..5,
                onClick = { onSubmit(rating, comment.trim(), recommend) },
            ) { Text(if (existing != null) "Update feedback" else "Submit feedback") }
        }
    }
}

/** Generic single-reason capture dialog (reopen, rejection, …). */
@Composable
fun ReasonDialog(
    title: String,
    hint: String,
    confirmLabel: String,
    saving: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = reason, onValueChange = { reason = it },
                label = { Text(hint) }, minLines = 2, modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = !saving && reason.isNotBlank(),
                onClick = { onConfirm(reason.trim()) },
            ) { Text(if (saving) "Saving…" else confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
