package com.naarni.service.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.naarni.service.data.inspection.PendingSummary
import com.naarni.service.data.inspection.SyncState
import com.naarni.service.ui.theme.Radii
import androidx.compose.runtime.ReadOnlyComposable
import com.naarni.service.ui.theme.Semantic

/**
 * Telling the engineer where their work is.
 *
 * This is not decoration, and it is not a spinner. Offline support has one
 * failure mode that no amount of correct plumbing fixes: an engineer who cannot
 * tell a queued answer from a lost one. Somebody who suspects the app is
 * dropping their work will stop using it in a shed and go back to paper — and
 * they will be right to, because from where they stand the two look identical.
 *
 * So the rules for everything in this file are: say what is true, say it in
 * words about *their* work rather than about the network, and never show a
 * failure the person cannot act on without also saying what to do.
 */

/** Sync state on the same ramp as every other state in the app. */
private val Amber: Color
    @Composable @ReadOnlyComposable
    get() = Semantic.caution
private val Green: Color
    @Composable @ReadOnlyComposable
    get() = Semantic.positive
private val Red: Color
    @Composable @ReadOnlyComposable
    get() = Semantic.critical

/**
 * The strip that appears when the phone has no network.
 *
 * Deliberately reassuring rather than alarming — being offline is the *expected*
 * state in a shed, not an error, and an angry red banner over normal working
 * conditions is how a warning gets ignored when it finally matters.
 */
@Composable
fun OfflineStrip(online: Boolean, modifier: Modifier = Modifier) {
	AnimatedVisibility(visible = !online) {
		Row(
			modifier
				.fillMaxWidth()
				.background(Amber.copy(alpha = 0.12f))
				.padding(horizontal = 16.dp, vertical = 8.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Icon(
				Icons.Rounded.CloudOff,
				contentDescription = null,
				tint = Amber,
				modifier = Modifier.size(18.dp),
			)
			Text(
				"No network — carry on. Everything is saved on this phone and will upload by itself.",
				style = MaterialTheme.typography.bodySmall,
				color = Amber,
			)
		}
	}
}

/**
 * How much work is waiting to go up, across every inspection on the handset.
 *
 * Shown on the process list, where an engineer looks between packs. Silent when
 * there is nothing outstanding: a permanent "all synced" badge is noise, and
 * noise is what stops the badge being read on the day it says something else.
 */
@Composable
fun PendingWorkCard(
	summary: PendingSummary,
	online: Boolean,
	modifier: Modifier = Modifier,
	onRetry: () -> Unit = {},
) {
	if (summary.isEmpty) return

	// "Uploading now" has to mean it. Work nothing will retry gets its own
	// wording and its own colour, because a badge that says uploading about an
	// upload that stopped teaches people to ignore the badge that means it.
	// Tappable whatever the state, so an engineer who wants their work off the
	// phone now never has to wait for a timer. The card used to accept a tap
	// only in its red form, which meant the one person who was worried had no
	// way to act on it.
	if (summary.total == 0 && summary.stuck > 0) {
		Surface(
			modifier.fillMaxWidth(),
			shape = Radii.lg,
			color = Red.copy(alpha = 0.10f),
		) {
			Row(
				Modifier.padding(14.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(12.dp),
			) {
				Icon(
					Icons.Rounded.ErrorOutline,
					contentDescription = null,
					tint = Red,
					modifier = Modifier.size(22.dp),
				)
				Column(Modifier.weight(1f)) {
					Text("${summary.stuck} did not upload", fontWeight = FontWeight.SemiBold, color = Red)
					Text(
						"Still saved on this phone, nothing is lost.",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurface,
					)
				}
				UploadNowButton(onRetry)
			}
		}
		return
	}

	val (icon, tint, headline) = when {
		!online -> Triple(Icons.Rounded.CloudOff, Amber, "Saved on this phone")
		else -> Triple(Icons.Rounded.CloudUpload, Amber, "Uploading now")
	}

	Surface(
		modifier.fillMaxWidth(),
		shape = Radii.lg,
		color = tint.copy(alpha = 0.10f),
	) {
		Row(
			Modifier.padding(14.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
			Column(Modifier.weight(1f)) {
				Text(headline, fontWeight = FontWeight.SemiBold, color = tint)
				Text(
					buildString {
						val parts = buildList {
							if (summary.answers > 0) {
								add("${summary.answers} ${if (summary.answers == 1) "answer" else "answers"}")
							}
							if (summary.photos > 0) {
								add("${summary.photos} ${if (summary.photos == 1) "photo" else "photos"}")
							}
						}
						append(parts.joinToString(" and "))
						append(
							if (online) {
								" still going up."
							} else {
								" waiting for a network. Nothing will be lost."
							},
						)
						// Named, because a big batch on a depot link genuinely
						// takes many goes, and an engineer watching a number
						// that will not move deserves to know it is still
						// trying rather than be left to guess it has given up.
						if (summary.struggling > 0 && online) {
							append(
								" ${summary.struggling} of them are taking a while " +
									"— the phone keeps retrying until they land.",
							)
						}
					},
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurface,
				)
			}
			UploadNowButton(onRetry)
		}
	}
}

/**
 * The one control that puts an engineer's work on the server on demand.
 *
 * A real button, not a tappable card. The card was tappable before and nobody
 * found it — an affordance you have to be told about is not an affordance, and
 * this is the control somebody reaches for at the end of a shift when they want
 * to see their morning arrive before they hand the phone over.
 */
@Composable
private fun UploadNowButton(onClick: () -> Unit) {
	FilledTonalButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
		Icon(Icons.Rounded.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
		Spacer(Modifier.size(6.dp))
		Text("Upload now")
	}
}

/**
 * One run's sync state, as a line inside the runner.
 *
 * `ATTENTION` is the only one that raises its voice, and it earns it: the server
 * refused something, retrying will not help, and a person has to look. Every
 * other state is normal working life.
 */
@Composable
fun RunSyncLine(
	syncState: String,
	unsynced: Int,
	lastError: String?,
	online: Boolean,
	modifier: Modifier = Modifier,
) {
	val (icon, tint, message) = when {
		syncState == SyncState.ATTENTION -> Triple(
			Icons.Rounded.ErrorOutline,
			Red,
			lastError ?: "The server would not accept part of this inspection.",
		)
		unsynced > 0 && !online -> Triple(
			Icons.Rounded.CloudOff,
			Amber,
			"$unsynced ${if (unsynced == 1) "answer" else "answers"} saved here, waiting for a network.",
		)
		unsynced > 0 -> Triple(
			Icons.Rounded.CloudUpload,
			Amber,
			"Uploading $unsynced ${if (unsynced == 1) "answer" else "answers"}…",
		)
		else -> Triple(Icons.Rounded.CheckCircle, Green, "Everything here is on the server.")
	}

	// The all-clear is worth showing inside a run, unlike on the list: this is
	// where an engineer decides whether they can walk away from a finished pack.
	Row(
		modifier
			.fillMaxWidth()
			.background(tint.copy(alpha = 0.10f), Radii.md)
			.padding(horizontal = 12.dp, vertical = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
		Text(message, style = MaterialTheme.typography.bodySmall, color = tint)
	}
}

/** A small badge for a run row in a list. */
@Composable
fun SyncBadge(syncState: String, modifier: Modifier = Modifier) {
	val (icon, tint) = badgeFor(syncState) ?: return
	Icon(icon, contentDescription = null, tint = tint, modifier = modifier.size(16.dp))
}

@Composable
@ReadOnlyComposable
private fun badgeFor(state: String): Pair<ImageVector, Color>? = when (state) {
	SyncState.SYNCED -> null // Nothing to say. The normal case earns no ink.
	SyncState.ATTENTION -> Icons.Rounded.ErrorOutline to Red
	else -> Icons.Rounded.CloudUpload to Amber
}
