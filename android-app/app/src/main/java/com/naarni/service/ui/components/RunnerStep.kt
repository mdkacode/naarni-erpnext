package com.naarni.service.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naarni.service.data.dto.ProcessOption
import com.naarni.service.data.dto.ProcessStep
import com.naarni.service.ui.theme.AppSurface
import kotlinx.coroutines.delay

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
    /** Opens the searchable picker for a `Link` step. */
    onPickRequest: () -> Unit = {},
    /**
     * What on-device OCR read off the last scale photograph for this step, if
     * anything. A suggestion the operator confirms — never an answer.
     */
    ocrReading: OcrSuggestion? = null,
    /** Photos already attached to this step. */
    photoCount: Int = 0,
    /**
     * Those photos, for review.
     *
     * The app could take pictures long before it could show one back — a step
     * displayed a count and nothing else, so an operator had no way to tell a
     * good photograph from a thumb over the lens. A count is not evidence
     * anybody has looked.
     */
    photos: List<ReviewablePhoto> = emptyList(),
    /** Drawn above the question — progress, section, any error banner. */
    header: @Composable () -> Unit = {},
    /** Drawn under the controls — the skip affordance. */
    footer: @Composable () -> Unit = {},
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // The check sizes itself to the screen it is on, so it fits in one view.
        //
        // Fixed 84dp buttons and a 26sp question are right on a 6.7" phone at
        // default scaling and wrong everywhere else. On a OnePlus with display
        // size turned up — which operators do, because they are reading at arm's
        // length in a bright plant — three answer buttons, the photo row and the
        // note row consumed the whole screen and the question disappeared above
        // them: the check being answered was invisible while its answers were not.
        //
        // So the sizes are derived from the height actually available rather than
        // hardcoded. Everything shrinks together, down to a floor that is still
        // comfortably gloved-thumb sized, and only past that does anything scroll.
        val metrics = stepMetrics(maxHeight, step)
        val controlsCap = maxHeight * CONTROLS_MAX_SHARE
        val questionFloor = minOf(maxHeight * QUESTION_MIN_SHARE, 200.dp)

        Column(Modifier.fillMaxSize()) {
            // Reading half.
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = questionFloor)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                header()
                StepQuestion(step, metrics)
                if (answer.skipped) {
                    RunnerNotice("Skipped — ${answer.skipReason ?: "no reason given"}", tone = WarnAmber)
                }
            }

            Spacer(Modifier.height(12.dp))

            // Working half: within reach, always.
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = controlsCap)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            if (!step.supported) {
                RunnerNotice("This check needs a newer version of the app.")
            } else {
                when (step.response_type) {
                    "Choice", "Yes No" -> BigChoices(step, answer, onAnswer, metrics)
                    "Choice Multi" -> BigChoices(step, answer, onAnswer, metrics, multi = true)
                    "Number", "Number in Range", "Number with Tolerance" ->
                        BigNumber(step, answer, onAnswer, metrics)
                    "Computed" -> BigReadout(answer.value, step.unit, metrics)
                    "Scan" -> BigScan(step.step_code, answer, onScanRequest, onAnswer, metrics)
                    "Text Short", "Date", "Datetime" ->
                        BigText(step.step_code, answer, onAnswer, singleLine = true, metrics = metrics)
                    "Text Long" ->
                        BigText(step.step_code, answer, onAnswer, singleLine = false, metrics = metrics)
                    "Photo Only" -> RunnerNotice("Take a photo to complete this check.")
                    // Weight typed beside a photograph of the scale. The number
                    // box is an ordinary one — what makes this its own type is
                    // that the camera hands back a reading to pre-fill it, which
                    // the runner does via [ocrReading].
                    "Weight from Photo" -> BigNumber(step, answer, onAnswer, metrics, reading = ocrReading)
                    "Link" -> BigLink(step, answer, onAnswer, onPickRequest, metrics)
                    "Signature" -> RunnerNotice("You will sign once at the end of this module.")
                    "Section Note" -> Unit
                    else -> BigText(step.step_code, answer, onAnswer, singleLine = true, metrics = metrics)
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
                        metrics = metrics,
                    )
                    if (photos.isNotEmpty()) {
                        PhotoStrip(
                            photos = photos,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    NoteRow(step.step_code, answer, onAnswer, metrics)
                }
            }
                footer()
            }
        }
    }
}

/**
 * How the screen is divided when the controls want more room than there is.
 *
 * The controls may take at most this share; the question keeps a floor. Neither
 * can starve the other, which is what happened on a handset with large display
 * scaling — the question disappeared completely and the answer buttons stayed.
 */
private const val CONTROLS_MAX_SHARE = 0.60f
private const val QUESTION_MIN_SHARE = 0.28f

/**
 * The sizes this check draws at, derived from the screen it is actually on.
 *
 * One number does the work: how much height is left for the controls once the
 * question has its floor. Divided by the number of rows the step needs, that
 * gives a row height — clamped so it never grows silly on a tablet or shrinks
 * below what a gloved thumb can hit on a small phone at large font scale.
 */
internal data class StepMetrics(
    val rowHeight: Dp,
    val questionSize: TextUnit,
    val questionLineHeight: TextUnit,
    val optionTextSize: TextUnit,
)

/** The smallest a control may get. Shop-floor guidance, not consumer 44dp. */
private val MIN_ROW = 56.dp

/** The largest, so a tall screen does not turn three options into three slabs. */
private val MAX_ROW = 84.dp

private fun stepMetrics(available: Dp, step: ProcessStep): StepMetrics {
    // How many stacked controls this step will draw.
    val controlRows = when (step.response_type) {
        "Choice", "Yes No", "Choice Multi" -> step.options.size.coerceAtLeast(2)
        // A scan draws its camera button and a field to type into.
        "Scan" -> 2
        "Section Note" -> 0
        else -> 1
    } + if (step.response_type == "Section Note") 0 else 2 // photo row + note row

    val questionFloor = minOf(available * QUESTION_MIN_SHARE, 200.dp)
    val gaps = 12.dp * (controlRows + 2)
    val forControls = (available - questionFloor - gaps).coerceAtLeast(0.dp)
    val row = if (controlRows > 0) forControls / controlRows else MAX_ROW

    // The question tracks the same pressure: on a screen where the controls have
    // had to shrink, a 26sp question is what pushed them there.
    val tight = available < 620.dp
    val snug = available < 760.dp
    return StepMetrics(
        rowHeight = row.coerceIn(MIN_ROW, MAX_ROW),
        questionSize = if (tight) 20.sp else if (snug) 23.sp else 26.sp,
        questionLineHeight = if (tight) 26.sp else if (snug) 29.sp else 33.sp,
        optionTextSize = if (tight) 18.sp else if (snug) 20.sp else 22.sp,
    )
}

/**
 * A text field an operator can actually type in.
 *
 * The old fields were unusable, and it took a plant floor to say so. Each one was
 * bound straight to the stored answer: the value came back from a Room flow, and
 * every keystroke went out through `saveAnswer`, a database write and a
 * WorkManager enqueue before the character could return to the screen. Typing a
 * ten-digit serial meant ten round trips through storage and ten background jobs,
 * and what the operator saw was a field that dropped characters, reordered them,
 * or simply did not respond.
 *
 * The rule that fixes it: **the keyboard talks to local state, and storage is told
 * afterwards.** What is on screen is what was typed, instantly and always. The
 * answer is written [COMMIT_DEBOUNCE_MS] after typing stops, and again the moment
 * the field loses focus — so nothing is lost when somebody types and immediately
 * taps Next, which is what people actually do.
 *
 * The stored value is adopted back only while the field is *not* being typed in.
 * That is what lets a colleague's synced answer appear here without yanking the
 * cursor out from under a thumb mid-word.
 */
@Composable
internal fun TypedTextField(
    fieldKey: String,
    stored: String,
    onCommit: (String) -> Unit,
    placeholder: String,
    textStyle: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    /** Rejects characters that cannot belong in this field, before they show. */
    accept: (String) -> Boolean = { true },
) {
    var text by remember(fieldKey) { mutableStateOf(stored) }
    var typing by remember(fieldKey) { mutableStateOf(false) }

    // Adopt the stored value only when this field is settled. Mid-word it would
    // fight the operator for the cursor.
    LaunchedEffect(stored) {
        if (!typing && stored != text) text = stored
    }

    LaunchedEffect(text, typing) {
        if (!typing) return@LaunchedEffect
        delay(COMMIT_DEBOUNCE_MS)
        onCommit(text)
        typing = false
    }

    BasicTextField(
        value = text,
        onValueChange = { next ->
            if (!accept(next)) return@BasicTextField
            text = next
            typing = true
        },
        singleLine = singleLine,
        keyboardOptions = keyboardOptions,
        textStyle = textStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier.onFocusChanged { state ->
            // Leaving the field is a commit point: a debounce that has not fired
            // yet must never be the reason an answer is missing.
            if (!state.isFocused && typing) {
                onCommit(text)
                typing = false
            }
        },
        decorationBox = { inner ->
            Box {
                if (text.isEmpty()) {
                    Text(
                        placeholder,
                        style = textStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
                inner()
            }
        },
    )
}

/** How long typing has to stop before the answer is written and queued. */
private const val COMMIT_DEBOUNCE_MS = 400L

/** A reading in progress: digits, one dot, an optional leading minus. */
private val NUMERIC = Regex("^-?[0-9]*\\.?[0-9]*$")

/**
 * Does this step want a photo, given what has been answered so far?
 *
 * **A pass does not need a photograph.** The rule here used to be "any marked
 * verdict wants one", which meant every Pass on a fifty-nine check sheet lit a
 * red border and raised a warning dialog on the way out of the screen. What that
 * produces is not fifty-nine photographs — it is an operator who learns that the
 * red border and the dialog mean nothing, and taps past both. The prompt is only
 * worth having where it is worth obeying.
 *
 * So the definition decides, exactly as it was authored to:
 *
 * * an option that carries `requires_photo` demands one on its own — which is
 *   how "Fail" asks for evidence while "Pass" does not;
 * * `photo_policy` of Always demands one either way;
 * * On Fail demands one the moment a failing option is chosen, so the defect is
 *   photographed while the operator is still standing in front of it.
 *
 * A photo can still be *taken* on any step — see the camera row, which is always
 * offered. This is only about which ones are asked for. And wanting a photo is
 * never the same as blocking on one: the gate dialog in `ProcessScreens` warns
 * and then lets the operator through.
 */
internal fun ProcessStep.wantsPhoto(answer: StepAnswer): Boolean {
    if (response_type == "Section Note") return false
    if (answer.skipped) return false

    val chosen = options.firstOrNull { it.value == answer.response }
    if (chosen?.requires_photo == 1) return true

    if (requires_photo != 1 || photo_policy == "Never") return false
    return when (photo_policy) {
        "Always" -> true
        "On Pass" -> chosen?.is_pass == 1
        // "On Fail", and the default when a definition leaves it unset. An
        // unanswered step wants nothing yet: there is no verdict to evidence.
        else -> chosen != null && chosen.is_pass == 0
    }
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
private fun StepQuestion(step: ProcessStep, metrics: StepMetrics) {
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
            // Sized to the screen — see [stepMetrics]. The largest at which the
            // longest question in the battery sheet still fits three lines, and
            // smaller when the controls below need the room.
            fontSize = metrics.questionSize,
            lineHeight = metrics.questionLineHeight,
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
    metrics: StepMetrics,
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
                    .height(metrics.rowHeight)
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
                        fontSize = metrics.optionTextSize,
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
private fun BigNumber(
    step: ProcessStep,
    answer: StepAnswer,
    onAnswer: (StepAnswer) -> Unit,
    metrics: StepMetrics,
    reading: OcrSuggestion? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        reading?.let { OcrSuggestionCard(it, answer, step.unit, onAnswer) }
        Surface(
            color = AppSurface.raised,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, AppSurface.hairline),
            modifier = Modifier.fillMaxWidth().height(metrics.rowHeight),
        ) {
            Row(
                Modifier.padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TypedTextField(
                    fieldKey = "num:${step.step_code}",
                    stored = answer.value.orEmpty(),
                    onCommit = { onAnswer(answer.copy(value = it, skipped = false)) },
                    placeholder = "0",
                    textStyle = MaterialTheme.typography.headlineLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    // A reading is a number. Letting letters in produces an answer
                    // the server cannot judge and the operator cannot see is wrong.
                    accept = { it.isEmpty() || it.matches(NUMERIC) },
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
private fun BigReadout(value: String?, unit: String?, metrics: StepMetrics) {
    Surface(
        color = AppSurface.sunken,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().height(metrics.rowHeight),
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
    fieldKey: String,
    answer: StepAnswer,
    onScanRequest: () -> Unit,
    onAnswer: (StepAnswer) -> Unit,
    metrics: StepMetrics,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(metrics.rowHeight)
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

        BigText("scan:$fieldKey", answer, onAnswer, singleLine = true, metrics = metrics, hint = "Or type the code")

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
    fieldKey: String,
    answer: StepAnswer,
    onAnswer: (StepAnswer) -> Unit,
    singleLine: Boolean,
    metrics: StepMetrics,
    hint: String = "Type here",
) {
    Surface(
        color = AppSurface.raised,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, AppSurface.hairline),
        modifier = Modifier.fillMaxWidth().heightIn(
            min = if (singleLine) metrics.rowHeight else metrics.rowHeight * 1.5f,
        ),
    ) {
        Box(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            TypedTextField(
                fieldKey = fieldKey,
                stored = answer.value.orEmpty(),
                onCommit = { onAnswer(answer.copy(value = it, skipped = false)) },
                placeholder = hint,
                textStyle = MaterialTheme.typography.titleLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier.fillMaxWidth(),
                singleLine = singleLine,
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
    metrics: StepMetrics,
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
                .height(metrics.rowHeight)
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

/**
 * An optional note, on every check.
 *
 * The engine has always carried a `remark` per answer and always sent it to the
 * server, where it lands on the report next to the answer it belongs to — but
 * nothing on this screen ever let an operator type one. So the observations that
 * matter most ("the third bolt was already marked", "this sheet is creased,
 * couldn't read the batch") had nowhere to go except a person's memory, and the
 * report showed a bare Pass with no idea that anything was odd about it.
 *
 * **Collapsed until asked for.** A permanently-open text field on every one of
 * fifty-nine checks would push the answer buttons off the bottom of the screen,
 * and the field is used on perhaps one check in twenty. Closed it is one quiet
 * line; open it is a real field, and once written the note stays visible so the
 * operator can see what they said.
 *
 * Never mandatory, on any step, in any state. A note that has to be written to
 * get past a screen is a note that says "n/a".
 */
@Composable
private fun NoteRow(
    fieldKey: String,
    answer: StepAnswer,
    onAnswer: (StepAnswer) -> Unit,
    metrics: StepMetrics,
) {
    val existing = answer.remark.orEmpty()
    // Keyed on the step, never on whether the note is empty.
    //
    // Keying it on blankness meant the box closed under the operator's thumb the
    // moment they deleted the last character — which is exactly what someone does
    // when they want to rewrite a note. Opened is opened; it closes when the
    // check does.
    var open by remember(fieldKey) { mutableStateOf(existing.isNotBlank()) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!open) {
            Surface(
                color = AppSurface.raised,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, AppSurface.hairline),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(minOf(metrics.rowHeight, 56.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { open = true },
            ) {
                Row(
                    Modifier.padding(horizontal = 22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Rounded.EditNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        "Add a note (optional)",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            // Focused the moment it opens. Tapping "Add a note" and getting a box
            // with no keyboard is a dead end — the operator taps it, nothing
            // happens, and they conclude the field does not work.
            val focus = remember(fieldKey) { FocusRequester() }
            LaunchedEffect(open) { if (open && existing.isBlank()) runCatching { focus.requestFocus() } }

            Surface(
                color = AppSurface.raised,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, AppSurface.hairline),
                modifier = Modifier.fillMaxWidth().heightIn(min = metrics.rowHeight * 1.2f),
            ) {
                Box(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                    TypedTextField(
                        fieldKey = "note:$fieldKey",
                        stored = existing,
                        onCommit = { onAnswer(answer.copy(remark = it)) },
                        placeholder = "Anything worth recording about this check",
                        textStyle = MaterialTheme.typography.titleMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        singleLine = false,
                    )
                }
            }
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


/** What the phone read off a photograph of the scale. */
data class OcrSuggestion(
    val value: Double,
    /** The exact text the number came from, so a misread is visible rather than silent. */
    val sourceText: String,
    val confident: Boolean,
)

/**
 * The OCR reading, offered rather than applied.
 *
 * It fills the box on first sight and then gets out of the way: once the
 * operator has a number in the field, this is a line of small print confirming
 * what the photo said, not a control competing with them. A seven-segment
 * display photographed at an angle in bad light is exactly the case where
 * automatic and silent is the wrong combination — so the source text is shown,
 * and correcting it takes one tap in the field above.
 */
@Composable
private fun OcrSuggestionCard(
    reading: OcrSuggestion,
    answer: StepAnswer,
    unit: String?,
    onAnswer: (StepAnswer) -> Unit,
) {
    val typed = answer.value?.trim().orEmpty()
    val formatted = if (reading.value % 1.0 == 0.0) reading.value.toLong().toString() else reading.value.toString()
    val matches = typed.toDoubleOrNull()?.let { kotlin.math.abs(it - reading.value) < 0.005 } == true

    // Fill it once, the moment a reading arrives against an empty box. Never
    // overwrite: a number the operator has already put in is a deliberate act,
    // and OCR quietly replacing it would be the worst behaviour this could have.
    LaunchedEffect(reading, typed.isEmpty()) {
        if (typed.isEmpty()) onAnswer(answer.copy(value = formatted))
    }

    Surface(
        color = if (matches) PassGreen.copy(alpha = 0.10f) else WarnAmber.copy(alpha = 0.10f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (matches) PassGreen else WarnAmber),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                if (matches) Icons.Rounded.CheckCircle else Icons.Rounded.PhotoCamera,
                contentDescription = null,
                tint = if (matches) PassGreen else WarnAmber,
                modifier = Modifier.size(20.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    if (reading.confident) "Photo reads $formatted ${unit.orEmpty()}".trim()
                    else "Photo might read $formatted ${unit.orEmpty()}".trim(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (matches) "Matches what you entered." else "From “${reading.sourceText}” — check the scale.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!matches) {
                TextButton(onClick = { onAnswer(answer.copy(value = formatted)) }) { Text("Use it") }
            }
        }
    }
}


/**
 * A `Link` step: pick a row from a master, or add one that is missing.
 *
 * These used to fall through to a bare text box. A step pointing at a doctype
 * offered no search, no create and no validation — so an operator typing
 * "sri lakshmi eng" produced a fourth spelling of a supplier that already
 * existed three times, and nothing downstream could join on it.
 *
 * The picker itself is a sheet the runner owns (it needs the network and the
 * step's doctype); this is the field that opens it and shows what was chosen.
 */
@Composable
private fun BigLink(
    step: ProcessStep,
    answer: StepAnswer,
    onAnswer: (StepAnswer) -> Unit,
    onPickRequest: () -> Unit,
    metrics: StepMetrics,
) {
    val chosen = answer.valueLabel?.takeIf { it.isNotBlank() } ?: answer.value?.takeIf { it.isNotBlank() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            color = if (chosen != null) PassGreen.copy(alpha = 0.10f) else AppSurface.raised,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(if (chosen != null) 2.dp else 1.dp, if (chosen != null) PassGreen else AppSurface.hairline),
            modifier = Modifier
                .fillMaxWidth()
                .height(metrics.rowHeight)
                .clip(RoundedCornerShape(18.dp))
                .clickable(onClick = onPickRequest),
        ) {
            Row(
                Modifier.padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(
                    if (chosen != null) Icons.Rounded.CheckCircle else Icons.Rounded.Search,
                    contentDescription = null,
                    tint = if (chosen != null) PassGreen else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(30.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        chosen ?: "Search…",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (chosen != null) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 2,
                    )
                    if (chosen != null && answer.value != null && answer.valueLabel != null) {
                        Text(
                            answer.value,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
