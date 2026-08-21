package com.naarni.service.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.dp

/**
 * NaArNi Design Language — the values Material has no slot for.
 *
 * `Theme.kt` owns colour, type and the `Shapes` Material components read from.
 * This file owns everything a call site would otherwise write as a magic number:
 * spacing, radius, stroke width, elevation and motion.
 *
 * Mirrors `design/DESIGN_LANGUAGE.md` §3.6–3.8. A `dp` literal in a screen file
 * that is not one of these is a review blocker — before this existed the app had
 * **eighteen distinct corner radii** across its screens, from 2dp to 28dp, which
 * is not a system, it is a habit.
 */

/**
 * A 4dp base. Nothing between the steps.
 *
 * Gaps *inside* a component come from the lower half of the scale; gaps
 * *between* sections come from the upper half. That contrast is what lets a
 * group read as a group without a box drawn around it.
 */
object Space {
    val none = 0.dp
    val hair = 2.dp
    val xs = 4.dp
    val sm = 6.dp
    val md = 8.dp
    val lg = 12.dp
    val xl = 16.dp
    val xxl = 20.dp
    val x3 = 24.dp
    val x4 = 32.dp
    val x5 = 40.dp
    val x6 = 48.dp
    val x7 = 64.dp

    /** The standard horizontal inset of a screen's content. */
    val screen = 16.dp

    /** Padding inside a card at the app's (comfortable) density. */
    val card = 14.dp

    /** The gap between two stacked cards. */
    val stack = 12.dp
}

/**
 * Corner radius.
 *
 * These are the same five steps the web uses. `xl` exists only for full-bleed
 * bottom sheets and the sign-in mark; a card, a chip or a field never reaches
 * for it. Anything rounder than `xl` reads as a consumer app, and on a list row
 * it also eats horizontal space that a registration number needs.
 */
object Radii {
    val xs = RoundedCornerShape(4.dp)
    val sm = RoundedCornerShape(6.dp)
    val md = RoundedCornerShape(8.dp)
    val lg = RoundedCornerShape(12.dp)
    val xl = RoundedCornerShape(16.dp)
    val pill = RoundedCornerShape(50)

    /** Only for a bottom sheet, whose lower corners are square against the edge. */
    val sheet = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
}

/**
 * Stroke width.
 *
 * `hairline` is everything. `focus` is 2dp and is a *state* — selection, focus,
 * a drag target — never decoration.
 */
object Stroke {
    val hairline = 1.dp
    val focus = 2.dp

    /** The border that replaces elevation on every static surface. */
    val card: BorderStroke
        @Composable @ReadOnlyComposable
        get() = BorderStroke(hairline, AppSurface.hairline)
}

/**
 * Elevation — four levels, and three of them float.
 *
 * **Never use `tonalElevation`.** Material composites `surfaceTint` (which is the
 * accent) over the surface, so any card with `tonalElevation > 0` comes out
 * tinted lavender under a single-accent scheme — that is exactly what made the
 * Home duty card look permanently highlighted. Use [AppSurface.raised] plus
 * [Stroke.card] instead; `Elevation.e0` is the default for everything static.
 *
 * The shadow levels are for surfaces that genuinely float: menus, dialogs,
 * sheets, toasts. On the near-black canvas a shadow has almost nothing to fall
 * on, which is a second reason a static card should not spend one.
 */
object Elevation {
    /** Everything static. Cards, tiles, list rows, the bottom bar. */
    val e0 = 0.dp

    /** A sticky header, once the content has scrolled under it. */
    val e1 = 1.dp

    /** Dropdowns, popovers, tooltips. */
    val e2 = 4.dp

    /** Dialogs, bottom sheets, snackbars. */
    val e3 = 12.dp
}

/**
 * Motion explains a change of state. It never decorates.
 *
 * Durations are in milliseconds so they drop straight into `tween()`.
 */
object Motion {
    /** The one curve. Fast out, settled in. */
    val ease: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Press, hover, focus ring. */
    const val instant = 90

    /** Chips, checkboxes, colour changes, tooltips. */
    const val fast = 150

    /** Menus, dialogs, sheets, expand and collapse. */
    const val base = 220

    /** Screen transitions, the nav drawer. */
    const val slow = 320
}

/**
 * Sizes for things a finger has to hit.
 *
 * The painted size and the touch size are different numbers. A 32dp icon button
 * still needs a 48dp box around it: in a depot the input device is a gloved
 * thumb, and Android's own minimum is 48dp for a reason.
 */
object Hit {
    /** The floor for any tappable thing. Never smaller, whatever it looks like. */
    val min = 48.dp

    /** A list row a person taps to open a record. */
    val row = 56.dp

    /** A primary action on a field screen — the runner's Next, a form's Save. */
    val primaryAction = 52.dp

    /** An icon button's painted box. Its touch box is [min]. */
    val iconButton = 40.dp
}
