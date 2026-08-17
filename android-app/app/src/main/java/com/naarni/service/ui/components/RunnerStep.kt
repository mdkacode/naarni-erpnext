package com.naarni.service.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naarni.service.data.dto.ProcessOption
import com.naarni.service.data.dto.ProcessStep
import com.naarni.service.ui.theme.AppSurface

/**
 * One check, filling the screen.
 *
 * This is a deliberate departure from [StepCard], which renders a step as a row
 * in a list. When a stage is one-step-per-screen there is no list to be a row
 * in, and carrying the card's chrome — border, tight padding, 14sp body type —
 * meant a 6" screen showed one small card floating in a field of empty grey.
 *
 * The proportions here are set by how it is actually used: read at arm's length,
 * in a bright plant, by someone wearing gloves who is looking at a battery pack
 * rather than at the phone. So the question is 26sp, the answers are 84dp tall,
 * and nothing else competes with them.
 *
 * **The controls sit at the bottom.** The question takes the space that is left
 * and scrolls inside it; everything tappable is pinned to the bottom of the
 * screen. On a 6.7" handset held one-handed, the top half of the display is
 * outside the thumb's arc entirely, so a layout that flows question-then-answers
 * from the top puts the answers exactly where they cannot be reached. Weighting
 * the question rather than the controls also fixes the failure mode: when
 * content overflows, the question scrolls — the buttons never do.
 *
 * **English only.** The bilingual pairing put a second full-width line of Devanagari
 * under every question, which halved the space available to the question itself
 * and doubled the reading before an answer. The engine still stores `label_alt`
 * and the language toggle can bring it back per-process.
 */
@Composable
fun RunnerStep(
    step: ProcessStep,
    answer: StepAnswer,
    onAnswer: (StepAnswer) -> Unit,
    onScanRequest: () -> Unit,
    modifier: Modifier = Modifier,
    /** Opens the stamping camera for this step. */
    onPhotoRequest: () -> Unit = {},
    /** Photos already attached to this step. */
    photoCount: Int = 0,
    /** Drawn above the question — progress, section, any error banner. */
    header: @Composable () -> Unit = {},
    /** Drawn under the controls — the skip affordance. */
    footer: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxWidth()) {
        // Reading half: gives up its space to the controls, and scrolls when a
        // question runs long rather than pushing anything off the screen.
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            header()
            StepQuestion(step)
            if (answer.skipped) {
                RunnerNotice("Skipped — ${answer.skipReason ?: "no reason given"}", tone = WarnAmber)
            }
        }

        Spacer(Modifier.height(16.dp))

        // Working half: within reach, always.
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!step.supported) {
                RunnerNotice("This check needs a newer version of the app.")
            } else {
                when (step.response_type) {
                    "Choice", "Yes No" -> BigChoices(step, answer, onAnswer)
                    "Choice Multi" -> BigChoices(step, answer, onAnswer, multi = true)
                    "Number", "Number in Range", "Number with Tolerance" -> BigNumber(step, answer, onAnswer)
                    "Computed" -> BigReadout(answer.value, step.unit)
                    "Scan" -> BigScan(answer, onScanRequest, onAnswer)
                    "Text Short", "Date", "Datetime" -> BigText(answer, onAnswer, singleLine = true)
                    "Text Long" -> BigText(answer, onAnswer, singleLine = false)
                    "Photo Only" -> RunnerNotice("Take a photo to complete this check.")
                    "Signature" -> RunnerNotice("You will sign once at the end of this module.")
                    "Section Note" -> Unit
                    else -> BigText(answer, onAnswer, singleLine = true)
                }

                // Evidence, on every check that is actually a check.
                //
                // The definition still decides whether a photo is *demanded* —
                // that drives the red border and the submit-time report. What it
                // no longer decides is whether one is *possible*: an operator who
                // sees something worth photographing on a step nobody thought to
                // flag had, until now, nowhere to put it, and a photo that cannot
                // be taken at the bench does not get taken later.
                if (step.response_type != "Section Note") {
                    PhotoRow(
                        required = step.wantsPhoto(answer),
                        count = photoCount,
                        hint = step.photo_hint,
                        onCapture = onPhotoRequest,
                    )
                }
            }
            footer()
        }
    }
}

/** Step types where the operator marks a verdict rather than entering data. */
private val VERDICT_TYPES = setOf("Choice", "Yes No", "Choice Multi")

/**
 * Does this step want a photo, given what has been answered so far?
 *
 * Two sources, deliberately combined rather than either alone:
 *
 * 1. **The definition** — `requires_photo`, honouring "On Fail" so the prompt
 *    appears the moment a failing option is chosen and the operator photographs
 *    the defect while still standing in front of it.
 * 2. **Any marked verdict.** Once someone has said Yes, No, Pass or Fail about a
 *    battery, that claim is the record. A verdict with no picture behind it
 *    cannot be checked by anyone afterwards, and the person who has to defend it
 *    six months later is the operator who marked it.
 *
 * Wanting a photo is not the same as blocking on one — see the gate dialog in
 * `ProcessScreens`, which warns and then lets the operator through.
 */
internal fun ProcessStep.wantsPhoto(answer: StepAnswer): Boolean {
    if (response_type == "Section Note") return false
    if (answer.skipped) return false

    val marked = response_type in VERDICT_TYPES && !answer.response.isNullOrBlank()
    if (marked) return true

    if (requires_photo != 1 || photo_policy == "Never") return false
    if (photo_policy != "On Fail") return true
    return options.firstOrNull { it.value == answer.response }?.is_pass == 0
}

/**
 * The question.
 *
 * Big, and given the top of the screen on its own. The number badge and the
 * method/critical tags sit above it as a quiet eyebrow rather than beside it,
 * because on a narrow screen an inline badge steals a whole line's width from
 * the sentence that actually matters.
 */
@Composable
private fun StepQuestion(step: ProcessStep) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!step.display_no.isNullOrBlank() && step.display_no != "—") {
                Surface(color = AppSurface.sunken, shape = RoundedCornerShape(8.dp)) {
                    Text(
                        step.display_no,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            step.method_label?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (step.is_critical == 1) {
                Surface(color = FailRed.copy(alpha = 0.12f), shape = RoundedCornerShape(8.dp)) {
                    Text(
                        "CRITICAL",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = FailRed,
                    )
                }
            }
        }

        Text(
            step.label,
            // 26sp with a tight line height: the largest size at which the
            // longest question in the battery sheet still fits three lines.
            fontSize = 26.sp,
            lineHeight = 33.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.4).sp,
            color = MaterialTheme.colorScheme.onSurface,
        )

        step.help_text?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Pass / Fail / N-A as full-width rows.
 *
 * Rows, not a three-across grid. Across a 360dp screen three buttons are ~110dp
 * each, which is a small target for a gloved thumb and forces the labels to
 * abbreviate. Stacked, each answer is the full width of the screen and 84dp
 * tall — impossible to miss and impossible to mistake for its neighbour.
 */
@Composable
private fun BigChoices(
    step: ProcessStep,
    answer: StepAnswer,
    onAnswer: (StepAnswer) -> Unit,
    multi: Boolean = false,
) {
    val chosen = answer.response
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        step.options.forEach { option ->
            val selected = chosen == option.value
            val tint = optionColor(option)
            Surface(
                color = if (selected) tint.copy(alpha = 0.14f) else AppSurface.raised,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) tint else AppSurface.hairline),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(84.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .clickable {
                        onAnswer(
                            if (selected && multi) answer.copy(response = null)
                            else answer.copy(response = option.value, skipped = false),
                        )
                    },
            ) {
                Row(
                    Modifier.padding(horizontal = 22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // A filled disc when chosen, a hollow ring when not — the
                    // state survives being looked at in sunlight, where a colour
                    // difference alone often does not.
                    Box(
                        Modifier.size(30.dp).clip(RoundedCornerShape(50)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Icon(
                                Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(30.dp),
                            )
                        } else {
                            Box(
                                Modifier
                                    .size(26.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(AppSurface.sunken),
                            )
                        }
                    }
                    Text(
                        option.label,
                        fontSize = 22.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (selected) tint else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** A number pad-friendly field, sized to be read across a workbench. */
@Composable
private fun BigNumber(step: ProcessStep, answer: StepAnswer, onAnswer: (StepAnswer) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            color = AppSurface.raised,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, AppSurface.hairline),
            modifier = Modifier.fillMaxWidth().height(84.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = answer.value.orEmpty(),
                    onValueChange = { onAnswer(answer.copy(value = it, skipped = false)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = MaterialTheme.typography.headlineLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box {
                            if (answer.value.isNullOrBlank()) {
                                Text(
                                    "0",
                                    style = MaterialTheme.typography.headlineLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                )
                            }
                            inner()
                        }
                    },
                )
                step.unit?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // The acceptable band, stated plainly — an operator should not have to
        // work out whether 10 Nm ±1 means 9–11.
        //
        // Taken verbatim from the server's `spec_summary`, never recomputed
        // here. A zero bound is indistinguishable from an unset one, so only the
        // step's `pass_condition` says which of min/max/nominal are actually
        // operands — and the engine is the thing that knows. Deriving it
        // client-side printed "Accepted 0 – 0" under every plain number field.
        step.spec_summary?.takeIf { it.isNotBlank() }?.let {
            Text(
                "Accepted $it",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A server-computed value — shown, never typed. */
@Composable
private fun BigReadout(value: String?, unit: String?) {
    Surface(
        color = AppSurface.sunken,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().height(84.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                value?.takeIf { it.isNotBlank() } ?: "—",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            unit?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A scan step: a big camera button, and whatever has been captured so far.
 *
 * The button is the primary route and the typed field sits under it, because the
 * label is usually right there on the part — but see [BarcodeScannerScreen] for
 * why typing is never removed.
 */
@Composable
private fun BigScan(
    answer: StepAnswer,
    onScanRequest: () -> Unit,
    onAnswer: (StepAnswer) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .clip(RoundedCornerShape(18.dp))
                .clickable(onClick = onScanRequest),
        ) {
            Row(
                Modifier.padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(
                    Icons.Rounded.QrCodeScanner,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(38.dp),
                )
                Text(
                    if (answer.value.isNullOrBlank()) "Scan QR or barcode" else "Scan again",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }

        BigText(answer, onAnswer, singleLine = true, hint = "Or type the code")

        answer.value?.takeIf { it.isNotBlank() }?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = PassGreen,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Captured",
                    style = MaterialTheme.typography.titleMedium,
                    color = PassGreen,
                )
            }
        }
    }
}

@Composable
private fun BigText(
    answer: StepAnswer,
    onAnswer: (StepAnswer) -> Unit,
    singleLine: Boolean,
    hint: String = "Type here",
) {
    Surface(
        color = AppSurface.raised,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, AppSurface.hairline),
        modifier = Modifier.fillMaxWidth().heightIn(min = if (singleLine) 76.dp else 120.dp),
    ) {
        Box(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            BasicTextField(
                value = answer.value.orEmpty(),
                onValueChange = { onAnswer(answer.copy(value = it, skipped = false)) },
                singleLine = singleLine,
                textStyle = MaterialTheme.typography.titleLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box {
                        if (answer.value.isNullOrBlank()) {
                            Text(
                                hint,
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        }
                        inner()
                    }
                },
            )
        }
    }
}

/**
 * The photo affordance.
 *
 * Every photo taken here is burnt with the pack serial, the GPS fix, the time
 * and the operator's name — see `PhotoStamper`. That is the whole reason the
 * app takes the picture rather than letting someone use the phone's own camera
 * and attach it afterwards: an un-stamped photo of a battery pack is evidence of
 * nothing, because there is no way to tell which pack, when, or who looked at it.
 */
@Composable
private fun PhotoRow(
    required: Boolean,
    count: Int,
    hint: String?,
    onCapture: () -> Unit,
) {
    val satisfied = count > 0
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            color = if (satisfied) PassGreen.copy(alpha = 0.10f) else AppSurface.raised,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(
                if (required && !satisfied) 2.dp else 1.dp,
                when {
                    satisfied -> PassGreen
                    required -> FailRed
                    else -> AppSurface.hairline
                },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .clip(RoundedCornerShape(18.dp))
                .clickable(onClick = onCapture),
        ) {
            Row(
                Modifier.padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(
                    if (satisfied) Icons.Rounded.CheckCircle else Icons.Rounded.PhotoCamera,
                    contentDescription = null,
                    tint = if (satisfied) PassGreen else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(32.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            count == 1 -> "1 photo attached"
                            count > 1 -> "$count photos attached"
                            required -> "Photo required"
                            else -> "Add a photo"
                        },
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (satisfied) PassGreen else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "Stamped with serial, GPS, time and your name",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        hint?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RunnerNotice(text: String, tone: Color? = null) {
    Surface(
        color = (tone ?: MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.10f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            modifier = Modifier.padding(18.dp),
            style = MaterialTheme.typography.titleMedium,
            color = tone ?: MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
        )
    }
}
