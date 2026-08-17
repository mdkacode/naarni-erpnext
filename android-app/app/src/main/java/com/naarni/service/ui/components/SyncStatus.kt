package com.naarni.service.ui.components

import androidx.compose.animation.AnimatedVisibility
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

private val Amber = Color(0xFF99630A)
private val Green = Color(0xFF17784A)
private val Red = Color(0xFFB62F27)

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
fun PendingWorkCard(summary: PendingSummary, online: Boolean, modifier: Modifier = Modifier) {
	if (summary.isEmpty) return

	val (icon, tint, headline) = when {
		!online -> Triple(Icons.Rounded.CloudOff, Amber, "Saved on this phone")
		else -> Triple(Icons.Rounded.CloudUpload, Amber, "Uploading now")
	}

	Surface(
		modifier.fillMaxWidth(),
		shape = RoundedCornerShape(12.dp),
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
					},
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurface,
				)
			}
		}
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
			.background(tint.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
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

private fun badgeFor(state: String): Pair<ImageVector, Color>? = when (state) {
	SyncState.SYNCED -> null // Nothing to say. The normal case earns no ink.
	SyncState.ATTENTION -> Icons.Rounded.ErrorOutline to Red
	else -> Icons.Rounded.CloudUpload to Amber
}
