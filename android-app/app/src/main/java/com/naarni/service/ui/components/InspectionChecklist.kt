package com.naarni.service.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.naarni.service.data.dto.InspectionCheckItem
import com.naarni.service.data.dto.InspectionSheet
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Three-tier statuses — values mirror the backend HEALTH_SCORE_MAP keys exactly. */
const val STATUS_GOOD = "Good"
const val STATUS_RECOMMENDED = "Repair/Replace Recommended"
const val STATUS_IMMEDIATE = "Repair/Replace Immediately"

/**
 * Mutable state for a PMS inspection: per-component three-tier status and
 * measurement/text values. Three-tier items default to [STATUS_GOOD] so the
 * technician only flips the components that have an issue (EAS — Simplify).
 */
class InspectionState {
    val statuses: SnapshotStateMap<String, String> = mutableStateMapOf()
    val values: SnapshotStateMap<String, String> = mutableStateMapOf()

    fun statusOf(item: InspectionCheckItem): String =
        statuses[item.id] ?: STATUS_GOOD

    /** How many components are flagged as needing attention (not Good / out of range). */
    fun issueCount(items: List<InspectionCheckItem>): Int = items.count { item ->
        when (item.input_type) {
            "three_tier" -> statusOf(item) != STATUS_GOOD
            "measurement" -> outOfRange(item, values[item.id])
            else -> false
        }
    }

    /**
     * Build the `inspection_results` JSON the backend expects:
     * `{ id: { value, label, category, inputType, min?, max?, outOfRange? } }`.
     */
    fun toResultsJson(items: List<InspectionCheckItem>): String {
        val obj = buildJsonObject {
            for (item in items) {
                when (item.input_type) {
                    "three_tier" -> put(item.id, buildJsonObject {
                        put("value", statusOf(item))
                        put("label", item.label)
                        put("category", item.category)
                        put("inputType", "three_tier")
                    })
                    "measurement" -> {
                        val num = values[item.id]?.trim()?.toDoubleOrNull()
                        if (num != null) {
                            put(item.id, buildJsonObject {
                                put("value", JsonPrimitive(num))
                                put("label", item.label)
                                put("category", item.category)
                                put("inputType", "measurement")
                                item.min?.let { put("min", JsonPrimitive(it)) }
                                item.max?.let { put("max", JsonPrimitive(it)) }
                                if (outOfRange(item, num.toString())) put("outOfRange", JsonPrimitive(true))
                            })
                        }
                    }
                    else -> {
                        val raw = values[item.id]?.trim().orEmpty()
                        if (raw.isNotEmpty()) {
                            put(item.id, buildJsonObject {
                                put("value", raw)
                                put("label", item.label)
                                put("category", item.category)
                                put("inputType", "text")
                            })
                        }
                    }
                }
            }
        }
        return obj.toString()
    }

    companion object {
        fun outOfRange(item: InspectionCheckItem, raw: String?): Boolean {
            val v = raw?.trim()?.toDoubleOrNull() ?: return false
            val lo = item.min; val hi = item.max
            return (lo != null && v < lo) || (hi != null && v > hi)
        }
    }
}

@Composable
fun rememberInspectionState(): InspectionState = remember { InspectionState() }

/**
 * Renders the inspection check sheet grouped by category. Three-tier components
 * use Good / Recommended / Immediate chips; measurements take a numeric value
 * with a range hint; free-text components a text field.
 */
@Composable
fun InspectionChecklist(sheet: InspectionSheet, state: InspectionState, modifier: Modifier = Modifier) {
    val issues = state.issueCount(sheet.items)
    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 2.dp, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Inspection — ${sheet.sheet_label}", fontWeight = FontWeight.SemiBold)
                Text(
                    if (issues == 0) "All OK" else "$issues to fix",
                    color = if (issues == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            sheet.items.groupBy { it.category }.forEach { (category, items) ->
                SectionHeader(category)
                items.forEach { item -> InspectionRow(item, state) }
            }
        }
    }
}

@Composable
private fun InspectionRow(item: InspectionCheckItem, state: InspectionState) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(item.label, style = MaterialTheme.typography.bodyMedium)
        when (item.input_type) {
            "three_tier" -> {
                val current = state.statusOf(item)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TierChip("Good", current == STATUS_GOOD, Color(0xFF22C55E)) { state.statuses[item.id] = STATUS_GOOD }
                    TierChip("Recommend", current == STATUS_RECOMMENDED, Color(0xFFEAB308)) { state.statuses[item.id] = STATUS_RECOMMENDED }
                    TierChip("Immediate", current == STATUS_IMMEDIATE, Color(0xFFEF4444)) { state.statuses[item.id] = STATUS_IMMEDIATE }
                }
            }
            "measurement" -> {
                val raw = state.values[item.id].orEmpty()
                val bad = InspectionState.outOfRange(item, raw)
                val range = "Range ${fmt(item.min)}–${fmt(item.max)} ${item.unit ?: ""}".trim()
                OutlinedTextField(
                    value = raw,
                    onValueChange = { s -> if (s.isEmpty() || s.toDoubleOrNull() != null || s.matches(Regex("^[0-9]*\\.?[0-9]*$"))) state.values[item.id] = s },
                    label = { Text(item.unit?.let { "Value ($it)" } ?: "Value") },
                    isError = bad,
                    supportingText = { Text(if (bad) "$range — out of range" else range) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            else -> {
                OutlinedTextField(
                    value = state.values[item.id].orEmpty(),
                    onValueChange = { state.values[item.id] = it },
                    label = { Text("Notes") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun TierChip(label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = color.copy(alpha = 0.18f),
            selectedLabelColor = color,
        ),
    )
}

private fun fmt(v: Double?): String {
    if (v == null) return "—"
    return if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
}
