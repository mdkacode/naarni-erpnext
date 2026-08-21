package com.naarni.service.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naarni.service.data.dto.ProcessOption
import com.naarni.service.data.dto.ProcessStep
import kotlinx.coroutines.delay
import com.naarni.service.ui.theme.Radii
import androidx.compose.runtime.ReadOnlyComposable
import com.naarni.service.ui.theme.Semantic

/**
 * The single renderer that draws every step type.
 *
 * This `when` is the whole reason adding a process needs no app release: the
 * server sends a definition, this file turns it into controls. Adding a *new
 * step type* is the one change that needs a build — which is exactly why
 * [ProcessStep.supported] exists.
 *
 * The sizing here is not consumer-mobile sizing. Shop-floor UX guidance is
 * specific and it disagrees: gloved operators cannot reliably hit the 44dp
 * consumer target, so answer buttons are 72dp; colour is never the only signal,
 * so every option carries a glyph and a word as well; and vibration defeats
 * sliders, so numbers use a keypad.
 */

/** The verdict ramp. One definition, shared — see [Semantic]. */
internal val PassGreen: Color
    @Composable @ReadOnlyComposable
    get() = Semantic.positive
internal val FailRed: Color
    @Composable @ReadOnlyComposable
    get() = Semantic.critical
internal val WarnAmber: Color
    @Composable @ReadOnlyComposable
    get() = Semantic.caution
internal val NeutralGrey: Color
    @Composable @ReadOnlyComposable
    get() = Semantic.idle

/** What the operator has entered for one step, before it is sent. */
data class StepAnswer(
    val response: String? = null,
    val value: String? = null,
    val remark: String? = null,
    val skipped: Boolean = false,
    val skipReason: String? = null,
    /**
     * The human-readable name behind [response] on a `Link` step.
     *
     * A Link answer is a document name — "AGG-001", "NEW-0004" — which is what
     * the server stores and what every report joins on. Showing that back to the
     * operator who picked "HVAC Unit" would be a different answer as far as they
     * are concerned, so the label rides alongside for display only. It is not
     * sent to the server and not persisted; a resumed run re-derives it from the
     * cached option list, and its absence degrades to showing the code.
     */
    val valueLabel: String? = null,
)

@Composable
@ReadOnlyComposable
fun optionColor(option: ProcessOption): Color = when (option.color) {
    "green" -> PassGreen
    "red" -> FailRed
    "amber" -> WarnAmber
    "blue" -> Semantic.active
    else -> NeutralGrey
}

@Composable
fun StepCard(
    step: ProcessStep,
    answer: StepAnswer,
    showAltLanguage: Boolean,
    onAnswer: (StepAnswer) -> Unit,
    modifier: Modifier = Modifier,
) {
    val answered = answer.response != null || answer.value != null || answer.skipped
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = Radii.lg,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            1.dp,
            if (answered) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.outline,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StepHeader(step, showAltLanguage)

            if (!step.supported) {
                UnsupportedNotice()
                return@Column
            }

            when (step.response_type) {
                "Choice", "Yes No" -> ChoiceControl(step, answer, showAltLanguage, onAnswer)
                "Choice Multi" -> MultiChoiceControl(step, answer, onAnswer)
                "Number", "Number in Range", "Number with Tolerance" -> NumericControl(step, answer, onAnswer)
                "Computed" -> ComputedControl(step, answer)
                "Text Short" -> TextControl(step, answer, onAnswer, singleLine = true)
                "Text Long" -> TextControl(step, answer, onAnswer, singleLine = false)
                "Date", "Datetime" -> TextControl(step, answer, onAnswer, singleLine = true, hint = "YYYY-MM-DD")
                "Photo Only" -> EvidenceNotice("Take a photo to complete this step")
                "Scan" -> EvidenceNotice("Scan or type the label — never mandatory, you can skip")
                "Signature" -> EvidenceNotice("Signature is captured at sign-off")
                "Section Note" -> Unit // The header already carries the whole instruction.
                else -> TextControl(step, answer, onAnswer, singleLine = true)
            }

            if (answer.skipped) {
                Text(
                    "Skipped — ${answer.skipReason ?: "no reason given"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = WarnAmber,
                )
            }
            if (!answer.remark.isNullOrBlank()) {
                Text(
                    answer.remark,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StepHeader(step: ProcessStep, showAlt: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!step.display_no.isNullOrBlank() && step.display_no != "—") {
                Surface(
                    shape = Radii.sm,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        step.display_no,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Text(
                step.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
        }
        if (showAlt && !step.label_alt.isNullOrBlank()) {
            Text(
                step.label_alt,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!step.method_label.isNullOrBlank()) {
                Text(
                    step.method_label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (step.is_critical == 1) {
                Text(
                    "CRITICAL",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = FailRed,
                )
            }
            if (step.requires_photo == 1 && step.photo_policy != "Never") {
                Text(
                    if (step.photo_policy == "Always") "PHOTO" else "PHOTO ON FAIL",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Large answer buttons — 72dp so a gloved hand can hit them, and each carries
 * glyph + word + colour so the meaning survives glare, colour-blindness and a
 * scratched screen protector.
 */
@Composable
private fun ChoiceControl(
    step: ProcessStep,
    answer: StepAnswer,
    showAlt: Boolean,
    onAnswer: (StepAnswer) -> Unit,
) {
    val options = step.options.ifEmpty {
        listOf(
            ProcessOption(value = "Yes", label = "Yes", is_pass = 1, color = "green", icon = "✓"),
            ProcessOption(value = "No", label = "No", color = "red", icon = "✕"),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        options.forEach { option ->
            val selected = answer.response == option.value
            val tint = optionColor(option)
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(72.dp),
                shape = Radii.md,
                color = if (selected) tint.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) tint else MaterialTheme.colorScheme.outline),
                onClick = { onAnswer(answer.copy(response = option.value, skipped = false, skipReason = null)) },
            ) {
                Column(
                    Modifier.padding(4.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        option.icon ?: "",
                        fontSize = 20.sp,
                        color = if (selected) tint else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        option.label.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = if (selected) tint else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (showAlt && !option.label_alt.isNullOrBlank()) {
                        Text(
                            option.label_alt,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) tint else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MultiChoiceControl(step: ProcessStep, answer: StepAnswer, onAnswer: (StepAnswer) -> Unit) {
    val picked = answer.response?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        step.options.forEach { option ->
            val on = option.value in picked
            val tint = optionColor(option)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = Radii.md,
                color = if (on) tint.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(if (on) 2.dp else 1.dp, if (on) tint else MaterialTheme.colorScheme.outline),
                onClick = {
                    val next = if (on) picked - option.value else picked + option.value
                    onAnswer(answer.copy(response = next.joinToString(","), skipped = false))
                },
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(if (on) "☑" else "☐", fontSize = 18.sp, color = if (on) tint else NeutralGrey)
                    Text(option.label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/**
 * A numeric entry with a live verdict against the configured band.
 *
 * No slider: shop-floor panels vibrate and gloved precision is poor, so numbers
 * are typed. The spec line is rendered by the server so the certificate and the
 * screen can never disagree about what the band was.
 */
@Composable
private fun NumericControl(step: ProcessStep, answer: StepAnswer, onAnswer: (StepAnswer) -> Unit) {
    val raw = answer.value.orEmpty()
    val parsed = raw.toDoubleOrNull()
    val inBand = parsed?.let { withinBand(step, it) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Local state, storage told afterwards — the same rule as the full-screen
        // renderer, and for the same reason: binding the field straight to a value
        // that comes back from Room made it drop and reorder characters.
        var typed by remember(step.step_code) { mutableStateOf(raw) }
        var typing by remember(step.step_code) { mutableStateOf(false) }
        LaunchedEffect(raw) { if (!typing && raw != typed) typed = raw }
        LaunchedEffect(typed, typing) {
            if (!typing) return@LaunchedEffect
            delay(FIELD_COMMIT_MS)
            onAnswer(answer.copy(value = typed, skipped = false, skipReason = null))
            typing = false
        }
        OutlinedTextField(
            value = typed,
            onValueChange = { text ->
                if (text.isEmpty() || text.matches(Regex("^-?[0-9]*\\.?[0-9]*$"))) {
                    typed = text
                    typing = true
                }
            },
            label = { Text(step.unit?.let { "Value ($it)" } ?: "Value") },
            isError = inBand == false,
            singleLine = true,
            textStyle = MaterialTheme.typography.headlineSmall,
            shape = Radii.md,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().onFocusChanged { state ->
                if (!state.isFocused && typing) {
                    onAnswer(answer.copy(value = typed, skipped = false, skipReason = null))
                    typing = false
                }
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                step.spec_summary.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (inBand) {
                true -> Text("✓ within spec", style = MaterialTheme.typography.labelMedium, color = PassGreen)
                false -> Text("✕ out of spec", style = MaterialTheme.typography.labelMedium, color = FailRed)
                null -> Unit
            }
        }
    }
}

/**
 * Mirrors the server's band logic so the operator sees the verdict instantly.
 * The server still judges the saved answer — this is feedback, not authority.
 */
private fun withinBand(step: ProcessStep, value: Double): Boolean {
    if (step.response_type == "Number with Tolerance") {
        val tol = kotlin.math.abs(step.tolerance)
        return value >= step.nominal_value - tol && value <= step.nominal_value + tol
    }
    return when (step.pass_condition) {
        "Any Value", null -> true
        "Greater Than Min" -> value >= step.min_value
        "Less Than Max" -> value <= step.max_value
        "Equals Nominal" -> kotlin.math.abs(value - step.nominal_value) <= kotlin.math.abs(step.tolerance)
        else -> value >= step.min_value && value <= step.max_value
    }
}

/** Derived server-side from earlier answers, so it is shown, never typed. */
@Composable
private fun ComputedControl(step: ProcessStep, answer: StepAnswer) {
    val shown = answer.value?.takeIf { it.isNotBlank() } ?: "—"
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = Radii.md,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "$shown ${step.unit.orEmpty()}".trim(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (answer.value == null) "Calculated once the readings above are entered"
                else step.spec_summary.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TextControl(
    step: ProcessStep,
    answer: StepAnswer,
    onAnswer: (StepAnswer) -> Unit,
    singleLine: Boolean,
    hint: String? = null,
) {
    val stored = answer.response.orEmpty()
    var typed by remember(step.step_code) { mutableStateOf(stored) }
    var typing by remember(step.step_code) { mutableStateOf(false) }
    LaunchedEffect(stored) { if (!typing && stored != typed) typed = stored }
    LaunchedEffect(typed, typing) {
        if (!typing) return@LaunchedEffect
        delay(FIELD_COMMIT_MS)
        onAnswer(answer.copy(response = typed, skipped = false))
        typing = false
    }
    OutlinedTextField(
        value = typed,
        onValueChange = { typed = it; typing = true },
        label = { Text(hint ?: step.photo_hint ?: "Notes") },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        shape = Radii.md,
        modifier = Modifier.fillMaxWidth().onFocusChanged { state ->
            if (!state.isFocused && typing) {
                onAnswer(answer.copy(response = typed, skipped = false))
                typing = false
            }
        },
    )
}

/**
 * How long typing has to stop before the answer is written.
 *
 * Every commit is a Room write *and* a WorkManager enqueue. At one per keystroke
 * — which is what a directly-bound field does — a ten character serial meant ten
 * of each, and the field stopped responding to the person typing into it.
 */
private const val FIELD_COMMIT_MS = 400L

@Composable
private fun EvidenceNotice(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            Icons.Rounded.Info,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Shown when the server sent a step type this build predates. The rest of the
 * process still runs — one new step type must never brick a phone mid-shift.
 */
@Composable
private fun UnsupportedNotice() {
    Box(
        Modifier
            .fillMaxWidth()
            .background(WarnAmber.copy(alpha = 0.12f), Radii.md)
            .padding(12.dp),
    ) {
        Text(
            "This check needs a newer version of the app. Update to answer it — everything else still works.",
            style = MaterialTheme.typography.bodySmall,
            color = WarnAmber,
        )
    }
}
