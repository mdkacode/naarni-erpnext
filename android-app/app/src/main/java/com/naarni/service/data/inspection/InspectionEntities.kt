package com.naarni.service.data.inspection

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The inspection an engineer is doing, as it exists on the handset.
 *
 * Everything here is written before the network is consulted and read back
 * without consulting it, which is the whole point: a service engineer in a shed
 * with no signal must be able to work a pack at full speed and find the record
 * intact hours later.
 *
 * **This store is not a cache.** Chat's local database is disposable — every
 * message can be re-fetched from the server, so it takes a destructive schema
 * fallback and thinks nothing of it. An inspection that has not synced yet
 * exists *only* here. Wiping it would destroy work nobody can get back, which
 * is why [InspectionDatabase] has no destructive fallback and why every write
 * path below is append-or-update rather than replace.
 */

/** How far a piece of local work has got towards the server. */
object SyncState {
	/** Written locally, not yet accepted by the server. */
	const val PENDING = "pending"

	/** In flight right now. */
	const val SYNCING = "syncing"

	/** The server has it. */
	const val SYNCED = "synced"

	/** The server refused it and retrying will not help — a person must look. */
	const val ATTENTION = "attention"
}

/**
 * One run — created the instant the engineer presses Start, network or not.
 *
 * [clientUuid] is the identity, not [serverName]. The server's `start_run` is
 * idempotent on this UUID, so the same run started offline, synced, retried and
 * synced again resolves to one record however many times the response is lost.
 */
@Entity(tableName = "runs", indices = [Index("syncState"), Index("serverName")])
data class LocalRunEntity(
	@PrimaryKey val clientUuid: String,
	/** Null until the server has seen this run. */
	val serverName: String? = null,
	/** What to start — a family or definition name, resolved server-side. */
	val processFamily: String,
	val definitionName: String,
	val definitionVersion: Int = 1,
	val processName: String = "",
	val identifier: String? = null,
	val stageLabel: String = "Stage",
	/** The device's view. Replaced by the server's after every sync. */
	val status: String = "In Progress",
	val currentStage: String? = null,
	val startedAt: Long,
	/** Stage codes the operator finished, waiting to be signed off server-side. */
	val pendingSubmits: String = "",
	// Live counters, derived on-device so the summary strip never waits on a
	// round trip. Overwritten by the server's own numbers after each sync.
	val passCount: Int = 0,
	val failCount: Int = 0,
	val criticalCount: Int = 0,
	val answeredCount: Int = 0,
	val scorePct: Double = 0.0,
	val tracePct: Double = 0.0,
	val quarantineReason: String? = null,
	/** Terminal server-side — nothing more will be sent for this run. */
	val closed: Boolean = false,
	val syncState: String = SyncState.PENDING,
	val lastSyncAt: Long? = null,
	val lastError: String? = null,
	/** What the server said the run still needs, verbatim, after the last sync. */
	val outstanding: String? = null,
	val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * One answer.
 *
 * [clientSeq] is a per-run counter that only ever goes up. It does two jobs: it
 * tells the server the order the operator actually answered in, and it is how a
 * sync knows whether the row it just pushed is still the row on the device — if
 * the engineer corrected an answer while the batch was in flight, the sequence
 * moved and the reconcile leaves it queued rather than marking stale work sent.
 *
 * The `local*` verdict fields are the on-device evaluator's opinion, and exist
 * so the engineer sees a result the moment they tap. They are advisory. The
 * record is [serverPass], written when the server judges the same answer, and
 * the two are compared on arrival — a disagreement is a bug worth knowing about.
 */
@Entity(
	tableName = "answers",
	primaryKeys = ["runUuid", "stepCode"],
	indices = [Index("runUuid"), Index(value = ["runUuid", "synced"])],
)
data class LocalAnswerEntity(
	val runUuid: String,
	val stepCode: String,
	val response: String? = null,
	val value: String? = null,
	val remark: String? = null,
	val skipped: Boolean = false,
	val skipReason: String? = null,
	val secondsSpent: Int = 0,
	val clientSeq: Long,
	val answeredAt: Long,
	// -- the on-device evaluator's advisory reading
	val localPass: Boolean = false,
	val localDeviation: Boolean = false,
	val localCritical: Boolean = false,
	val localAnswered: Boolean = false,
	val specSummary: String? = null,
	// -- what the server made of it
	val serverPass: Int? = null,
	val synced: Boolean = false,
	/** Set when the server refused this row — shown to the engineer, never retried blindly. */
	val rejectedReason: String? = null,
)

/** One captured component identity, queued for the genealogy record. */
@Entity(tableName = "scans", indices = [Index("runUuid"), Index(value = ["runUuid", "synced"])])
data class LocalScanEntity(
	@PrimaryKey val scanUuid: String,
	val runUuid: String,
	val entityType: String,
	val stepCode: String? = null,
	val positionIndex: Int = 0,
	val payload: String,
	val manual: Boolean = false,
	val latitude: Double? = null,
	val longitude: Double? = null,
	val scannedAt: Long,
	val synced: Boolean = false,
	/** Filled in from the server's reply — the run this serial was seen on before. */
	val duplicateOf: String? = null,
)

/**
 * One photo, queued as a file on disk.
 *
 * Kept out of the answer batch deliberately. A batch of forty answers is a few
 * kilobytes and succeeds on one bar; a photo is two megabytes and fails on its
 * own terms, and bundling them would mean a bad image costs the whole
 * inspection a retry.
 *
 * [path] points into app-private storage, never the cache: a run can sit queued
 * for a shift, and a cache directory can be reclaimed under storage pressure
 * with the evidence still in it.
 */
@Entity(tableName = "photos", indices = [Index("runUuid"), Index("state")])
data class LocalPhotoEntity(
	@PrimaryKey val photoUuid: String,
	val runUuid: String,
	val stepCode: String,
	val path: String,
	val capturedAt: Long,
	val latitude: Double? = null,
	val longitude: Double? = null,
	val accuracyM: Double? = null,
	val state: String = SyncState.PENDING,
	val attempts: Int = 0,
	val lastError: String? = null,
	/** The Frappe File URL, once uploaded. */
	val fileUrl: String? = null,
)

/**
 * A process definition, held so the app can start work with no network at all.
 *
 * Definitions are immutable once published — a change is a new version — so a
 * cached copy can never go stale, only be superseded. That is what makes it
 * safe to run an inspection for an hour against a definition fetched last week.
 */
@Entity(tableName = "definitions", indices = [Index("family")])
data class CachedDefinitionEntity(
	@PrimaryKey val name: String,
	val family: String,
	val version: Int,
	/** The serialised `ProcessDefinition`, exactly as the server sent it. */
	val json: String,
	val fetchedAt: Long = System.currentTimeMillis(),
)

/** The process list, cached so the home screen is never empty offline. */
@Entity(tableName = "process_summaries")
data class CachedProcessEntity(
	@PrimaryKey val name: String,
	val json: String,
	val sortKey: String,
	val fetchedAt: Long = System.currentTimeMillis(),
)
