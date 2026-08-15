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
 * the canvas, the incoming bubble and the outgoing bubble — and no general
 * scheme supplies that for free, so they are picked as a set here.
 *
 * Under the app's near-black scheme, chat is the one screen where the accent is
 * spent on content rather than on a control: your own messages carry it. That is
 * the right trade because "which of these did I write" is the single question a
 * reader asks most often in a thread, and it is answered here without a label, an
 * icon or a second glance.
 */
object ChatTokens {

    /**
     * The conversation canvas.
     *
     * A step below the incoming bubble in light and a step below it in dark, so
     * a bubble is legible by its ground alone with no border and no shadow.
     */
    val ground: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFF0B0B0F) else Color(0xFFF4F4F5)

    /** Somebody else's message. */
    val incoming: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFF1C1C21) else Color.White

    val onIncoming: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFFFAFAFA) else Color(0xFF0B0B0F)

    /** Your own message — the accent's one job on this screen. */
    val outgoing: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFF2C2A56) else Color(0xFFE4E2FC)

    val onOutgoing: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFFEAE8FF) else Color(0xFF221C55)

    /** The composer pill and the search field sit on this. */
    val field: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFF141418) else Color.White

    /** Day dividers and other floating chips over the canvas. */
    val chip: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFF27272A) else Color(0xFFE4E4E7)

    val onChip: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFFA1A1AA) else Color(0xFF52525B)

    /**
     * Read receipts.
     *
     * The one colour in chat that is neither neutral nor the accent. It has to be
     * distinguishable from the outgoing bubble's indigo at 13dp, which an indigo
     * tick would not be, and blue is near-universally understood as "seen".
     */
    val readTick: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFF60C5FF) else Color(0xFF0B84D9)

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
