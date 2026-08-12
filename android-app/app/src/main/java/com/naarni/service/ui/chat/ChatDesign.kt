package com.naarni.service.ui.chat

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Chat's own colour and geometry tokens.
 *
 * A messaging surface needs three grounds that are legible against each other —
 * the canvas, the incoming bubble and the outgoing bubble — and the app's
 * general scheme does not supply that: `background` (#F6F7FB) and `surface`
 * (white) sit two percent apart, so an incoming bubble drawn on `surface`
 * effectively vanished into the page. These are picked as a set instead.
 *
 * The role WhatsApp gives its green, Naarni's indigo takes here. Nothing is
 * borrowed but the grammar.
 */
object ChatTokens {

    /** The conversation canvas. Deliberately tinted, so a white bubble reads. */
    val ground: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFF0B1020) else Color(0xFFEFEDF6)

    /** Somebody else's message. */
    val incoming: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFF1B2338) else Color.White

    val onIncoming: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFFE8EAF2) else Color(0xFF111827)

    /** Your own message. */
    val outgoing: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFF3A3184) else Color(0xFFDDD7FB)

    val onOutgoing: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFFEDEAFF) else Color(0xFF241C5C)

    /** The composer pill and the search field sit on this. */
    val field: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFF161C2E) else Color.White

    /** Day dividers and other floating chips over the canvas. */
    val chip: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFF1E2740) else Color(0xFFE2DEF2)

    val onChip: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFFA9B2C7) else Color(0xFF5B5470)

    /** Read receipts. Blue is near-universally understood as "seen". */
    val readTick: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFF60C5FF) else Color(0xFF1DA1F2)

    /** Bubble corner radius, and the tighter radius on the tail corner. */
    val bubbleRadius = 18.dp
    val tailRadius = 6.dp

    /** Gap between two messages from the same author, versus a new run. */
    val gapWithinRun = 3.dp
    val gapBetweenRuns = 10.dp

    /** Space between the bubble column and the edge of the screen. */
    val threadGutter = 10.dp

    /** Inside a bubble. Generous enough that the text is not touching the edge. */
    val bubblePadH = 13.dp
    val bubblePadV = 8.dp

    val mediaWidth = 250.dp
}

/**
 * How wide a bubble may get.
 *
 * A share of the screen rather than a fixed dp, so there is always a visible
 * gutter on the opposite side — that gutter is what tells you at a glance which
 * side a message came from, and a fixed 292dp cap swallowed it on a 360dp-wide
 * handset. Capped in absolute terms as well, because a full-width line of text
 * on a tablet is miserable to read.
 */
val bubbleMaxWidth: Dp
    @Composable
    @ReadOnlyComposable
    get() = minOf(LocalConfiguration.current.screenWidthDp * 0.78f, 340f).dp

/** The meta line (time + ticks) at 55% of whatever the bubble's text colour is. */
@Composable
@ReadOnlyComposable
fun mutedOn(base: Color): Color = base.copy(alpha = 0.55f)

/** Grey-on-canvas for secondary text outside bubbles. */
val secondaryText: Color
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.onSurfaceVariant
