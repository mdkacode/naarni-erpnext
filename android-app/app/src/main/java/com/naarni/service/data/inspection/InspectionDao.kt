package com.naarni.service.data.inspection

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Every read and write the inspection store supports.
 *
 * Two rules run through all of it. Writes are upserts, because the same answer
 * arriving twice — from a double tap, from a replayed reconcile — must leave one
 * row. And nothing is ever deleted while it is unsynced: the only `DELETE` on a
 * run is [purgeSyncedRun], and it refuses to touch anything the server has not
 * acknowledged.
 */
@Dao
interface InspectionDao {

	// ------------------------------------------------------------------ runs

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun upsertRun(run: LocalRunEntity)

	@Query("SELECT * FROM runs WHERE clientUuid = :uuid")
	suspend fun run(uuid: String): LocalRunEntity?

	@Query("SELECT * FROM runs WHERE clientUuid = :uuid")
	fun runFlow(uuid: String): Flow<LocalRunEntity?>

	@Query("SELECT * FROM runs WHERE serverName = :name LIMIT 1")
	suspend fun runByServerName(name: String): LocalRunEntity?

	/** Runs the engineer can pick back up, newest first. */
	@Query("SELECT * FROM runs WHERE closed = 0 ORDER BY startedAt DESC")
	fun openRuns(): Flow<List<LocalRunEntity>>

	@Query("SELECT * FROM runs ORDER BY startedAt DESC LIMIT :limit")
	fun recentRuns(limit: Int = 50): Flow<List<LocalRunEntity>>

	/**
	 * Everything with work the server has not got yet.
	 *
	 * The `closed = 0` guard is not an optimisation. A finished run whose photos
	 * all landed has nothing left to say, and leaving it in the sweep would have
	 * the device re-push completed inspections for ever.
	 */
	@Query(
		"""
        SELECT * FROM runs
        WHERE closed = 0 AND (
            syncState IN ('pending', 'syncing')
            OR EXISTS (SELECT 1 FROM answers a WHERE a.runUuid = runs.clientUuid AND a.synced = 0
                       AND a.rejectedReason IS NULL)
            OR EXISTS (SELECT 1 FROM scans s WHERE s.runUuid = runs.clientUuid AND s.synced = 0)
            OR pendingSubmits != ''
        )
        ORDER BY startedAt ASC
        """
	)
	suspend fun runsNeedingSync(): List<LocalRunEntity>

	/** Runs holding a photo that has not reached the server. */
	@Query(
		"""
        SELECT DISTINCT runUuid FROM photos
        WHERE state IN ('pending', 'syncing') AND attempts < :maxAttempts
        """
	)
	suspend fun runsWithPendingPhotos(maxAttempts: Int = PHOTO_MAX_ATTEMPTS): List<String>

	@Query("UPDATE runs SET syncState = :state, lastError = :error, lastSyncAt = :at WHERE clientUuid = :uuid")
	suspend fun setRunSync(uuid: String, state: String, error: String?, at: Long = System.currentTimeMillis())

	@Query("UPDATE runs SET serverName = :serverName WHERE clientUuid = :uuid")
	suspend fun setServerName(uuid: String, serverName: String)

	@Query("UPDATE runs SET pendingSubmits = :stages WHERE clientUuid = :uuid")
	suspend fun setPendingSubmits(uuid: String, stages: String)

	@Query(
		"""
        UPDATE runs SET status = :status, currentStage = :stage, passCount = :pass,
            failCount = :fail, criticalCount = :critical, answeredCount = :answered,
            scorePct = :score, tracePct = :trace, quarantineReason = :quarantine,
            closed = :closed, outstanding = :outstanding, updatedAt = :now
        WHERE clientUuid = :uuid
        """
	)
	suspend fun applyServerRun(
		uuid: String,
		status: String,
		stage: String?,
		pass: Int,
		fail: Int,
		critical: Int,
		answered: Int,
		score: Double,
		trace: Double,
		quarantine: String?,
		closed: Boolean,
		outstanding: String?,
		now: Long = System.currentTimeMillis(),
	)

	/** Local counters, so the summary strip moves the instant an answer is tapped. */
	@Query(
		"""
        UPDATE runs SET
            passCount = (SELECT COUNT(*) FROM answers WHERE runUuid = :uuid AND localPass = 1 AND skipped = 0),
            failCount = (SELECT COUNT(*) FROM answers WHERE runUuid = :uuid AND localDeviation = 1 AND skipped = 0),
            criticalCount = (SELECT COUNT(*) FROM answers WHERE runUuid = :uuid AND localCritical = 1 AND skipped = 0),
            answeredCount = (SELECT COUNT(*) FROM answers WHERE runUuid = :uuid AND localAnswered = 1),
            updatedAt = :now
        WHERE clientUuid = :uuid
        """
	)
	suspend fun recountRun(uuid: String, now: Long = System.currentTimeMillis())

	// --------------------------------------------------------------- answers

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun upsertAnswer(answer: LocalAnswerEntity)

	@Query("SELECT * FROM answers WHERE runUuid = :uuid")
	suspend fun answers(uuid: String): List<LocalAnswerEntity>

	@Query("SELECT * FROM answers WHERE runUuid = :uuid")
	fun answersFlow(uuid: String): Flow<List<LocalAnswerEntity>>

	@Query("SELECT * FROM answers WHERE runUuid = :uuid AND stepCode = :step")
	suspend fun answer(uuid: String, step: String): LocalAnswerEntity?

	/**
	 * What to put in the next batch.
	 *
	 * A rejected row is excluded on purpose. The server has already told us it
	 * cannot accept this step, and resending it would make the queue undrainable
	 * — the exact failure that ends with an engineer reinstalling the app and
	 * taking the rest of the work with it.
	 */
	@Query("SELECT * FROM answers WHERE runUuid = :uuid AND synced = 0 AND rejectedReason IS NULL ORDER BY clientSeq ASC")
	suspend fun unsyncedAnswers(uuid: String): List<LocalAnswerEntity>

	@Query("SELECT COALESCE(MAX(clientSeq), 0) FROM answers WHERE runUuid = :uuid")
	suspend fun maxClientSeq(uuid: String): Long

	/**
	 * Mark one answer sent — but only if it is still the answer we sent.
	 *
	 * The `clientSeq` guard is the whole reason this is not a blanket update. An
	 * engineer who corrects a check while the batch is in flight would otherwise
	 * have the correction marked as delivered and silently dropped.
	 */
	@Query(
		"""
        UPDATE answers SET synced = 1, serverPass = :serverPass, rejectedReason = NULL
        WHERE runUuid = :uuid AND stepCode = :step AND clientSeq = :seq
        """
	)
	suspend fun markAnswerSynced(uuid: String, step: String, seq: Long, serverPass: Int?)

	@Query(
		"""
        UPDATE answers SET rejectedReason = :reason
        WHERE runUuid = :uuid AND stepCode = :step AND clientSeq = :seq
        """
	)
	suspend fun markAnswerRejected(uuid: String, step: String, seq: Long, reason: String)

	@Query("SELECT COUNT(*) FROM answers WHERE runUuid = :uuid AND synced = 0 AND rejectedReason IS NULL")
	suspend fun unsyncedAnswerCount(uuid: String): Int

	// ----------------------------------------------------------------- scans

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun upsertScan(scan: LocalScanEntity)

	@Query("SELECT * FROM scans WHERE runUuid = :uuid ORDER BY scannedAt ASC")
	suspend fun scans(uuid: String): List<LocalScanEntity>

	@Query("SELECT * FROM scans WHERE runUuid = :uuid ORDER BY scannedAt ASC")
	fun scansFlow(uuid: String): Flow<List<LocalScanEntity>>

	@Query("SELECT * FROM scans WHERE runUuid = :uuid AND synced = 0 ORDER BY scannedAt ASC")
	suspend fun unsyncedScans(uuid: String): List<LocalScanEntity>

	@Query("UPDATE scans SET synced = 1 WHERE scanUuid IN (:uuids)")
	suspend fun markScansSynced(uuids: List<String>)

	@Query("UPDATE scans SET duplicateOf = :other WHERE scanUuid = :uuid")
	suspend fun markScanDuplicate(uuid: String, other: String?)

	@Query("DELETE FROM scans WHERE scanUuid = :uuid AND synced = 0")
	suspend fun deleteUnsyncedScan(uuid: String)

	// ---------------------------------------------------------------- photos

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun upsertPhoto(photo: LocalPhotoEntity)

	@Query("SELECT * FROM photos WHERE photoUuid = :uuid")
	suspend fun photo(uuid: String): LocalPhotoEntity?

	@Query("SELECT * FROM photos WHERE runUuid = :uuid ORDER BY capturedAt ASC")
	fun photosFlow(uuid: String): Flow<List<LocalPhotoEntity>>

	@Query(
		"""
        SELECT * FROM photos
        WHERE runUuid = :uuid AND state IN ('pending', 'syncing') AND attempts < :maxAttempts
        ORDER BY capturedAt ASC
        """
	)
	suspend fun pendingPhotos(uuid: String, maxAttempts: Int = PHOTO_MAX_ATTEMPTS): List<LocalPhotoEntity>

	/** Photos already on the step, for the "no photo yet" prompt. Counts queued ones. */
	@Query("SELECT stepCode, COUNT(*) AS count FROM photos WHERE runUuid = :uuid GROUP BY stepCode")
	fun photoCounts(uuid: String): Flow<List<PhotoCount>>

	/**
	 * Every photo on a step, whatever state it is in.
	 *
	 * Deliberately not [pendingPhotos]: a Photo Only step is judged on how many
	 * photographs exist, and counting only the queued ones would have the step
	 * go green when the shutter closed and red again the moment the upload
	 * succeeded — which is the exact opposite of what happened.
	 */
	@Query("SELECT COUNT(*) FROM photos WHERE runUuid = :uuid AND stepCode = :step")
	suspend fun photoCountForStep(uuid: String, step: String): Int

	@Query("UPDATE photos SET state = :state, lastError = :error, attempts = attempts + :attempt WHERE photoUuid = :uuid")
	suspend fun setPhotoState(uuid: String, state: String, error: String?, attempt: Int = 0)

	@Query("UPDATE photos SET state = 'synced', fileUrl = :url, lastError = NULL WHERE photoUuid = :uuid")
	suspend fun markPhotoSynced(uuid: String, url: String?)

	@Query("SELECT COUNT(*) FROM photos WHERE runUuid = :uuid AND state != 'synced'")
	suspend fun unsyncedPhotoCount(uuid: String): Int

	// ----------------------------------------------------------- definitions

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun upsertDefinition(definition: CachedDefinitionEntity)

	@Query("SELECT * FROM definitions WHERE name = :name")
	suspend fun definition(name: String): CachedDefinitionEntity?

	/** Highest published version held for a family — what a new run should use. */
	@Query("SELECT * FROM definitions WHERE family = :family ORDER BY version DESC LIMIT 1")
	suspend fun definitionForFamily(family: String): CachedDefinitionEntity?

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun upsertProcesses(rows: List<CachedProcessEntity>)

	@Query("SELECT * FROM process_summaries ORDER BY sortKey ASC")
	suspend fun processes(): List<CachedProcessEntity>

	@Query("SELECT COUNT(*) FROM definitions")
	suspend fun definitionCount(): Int

	@Query("DELETE FROM definitions")
	suspend fun deleteDefinitions()

	@Query("DELETE FROM process_summaries")
	suspend fun deleteProcessSummaries()

	/** Logout: the catalogue is per-role, queued runs belong to whoever made them. */
	@Transaction
	suspend fun clearCatalogue() {
		deleteDefinitions()
		deleteProcessSummaries()
	}

	// --------------------------------------------------------------- upkeep

	/**
	 * Drop a finished, fully-delivered run.
	 *
	 * Every clause is a guard against deleting work. The run must be closed
	 * server-side, hold no unsynced answer, no unsynced scan and no undelivered
	 * photo. If any of those is false the row stays, however old it is — disk is
	 * cheap and an inspection is not.
	 */
	@Transaction
	suspend fun purgeSyncedRun(uuid: String): Boolean {
		val run = run(uuid) ?: return false
		if (!run.closed || run.syncState != SyncState.SYNCED) return false
		if (unsyncedAnswerCount(uuid) > 0) return false
		if (unsyncedScans(uuid).isNotEmpty()) return false
		if (unsyncedPhotoCount(uuid) > 0) return false
		deleteAnswers(uuid)
		deleteScans(uuid)
		deletePhotos(uuid)
		deleteRun(uuid)
		return true
	}

	@Query("SELECT clientUuid FROM runs WHERE closed = 1 AND syncState = 'synced' AND updatedAt < :before")
	suspend fun purgeCandidates(before: Long): List<String>

	@Query("DELETE FROM answers WHERE runUuid = :uuid")
	suspend fun deleteAnswers(uuid: String)

	@Query("DELETE FROM scans WHERE runUuid = :uuid")
	suspend fun deleteScans(uuid: String)

	@Query("DELETE FROM photos WHERE runUuid = :uuid")
	suspend fun deletePhotos(uuid: String)

	@Query("DELETE FROM runs WHERE clientUuid = :uuid")
	suspend fun deleteRun(uuid: String)

	/** Total unsynced work across every run — what the status chip counts. */
	@Query(
		"""
        SELECT
            (SELECT COUNT(*) FROM answers WHERE synced = 0 AND rejectedReason IS NULL) AS answers,
            (SELECT COUNT(*) FROM photos WHERE state != 'synced') AS photos,
            (SELECT COUNT(DISTINCT clientUuid) FROM runs WHERE closed = 0 AND syncState != 'synced') AS runs
        """
	)
	fun pendingSummary(): Flow<PendingSummary>
}

data class PhotoCount(val stepCode: String, val count: Int)

data class PendingSummary(val answers: Int = 0, val photos: Int = 0, val runs: Int = 0) {
	val isEmpty: Boolean get() = answers == 0 && photos == 0 && runs == 0
	val total: Int get() = answers + photos
}

/**
 * How many times a photo is retried before it stops asking.
 *
 * Not infinite: a file that has been deleted from under us, or a step the
 * server has since removed, would otherwise retry for the life of the install
 * and keep the "not synced yet" warning up for ever, which teaches the engineer
 * to ignore it — and that warning is the only thing standing between them and
 * lost evidence.
 */
const val PHOTO_MAX_ATTEMPTS = 12
