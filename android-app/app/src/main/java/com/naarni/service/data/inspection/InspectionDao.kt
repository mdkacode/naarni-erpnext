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

	/**
	 * Remember exactly where the operator is.
	 *
	 * Written as they move rather than when they leave, because there is no
	 * "leave": the app is backgrounded mid-check, the battery dies, the shift
	 * ends. Anything that waits for a clean exit will miss most of them.
	 *
	 * `updatedAt` is deliberately untouched — this is a cursor, not a change to
	 * the inspection, and bumping it would reorder the resume list every time
	 * somebody so much as scrolled through a module.
	 */
	@Query("UPDATE runs SET lastStage = :stage, lastStep = :step WHERE clientUuid = :uuid")
	suspend fun rememberPosition(uuid: String, stage: String?, step: String?)

	/**
	 * An unfinished run this handset already holds for the same pack.
	 *
	 * One pack is one record, and that has to hold on the device too: scanning a
	 * label twice on one phone — which is what happens when somebody walks away
	 * and comes back — must reopen the inspection rather than start a second.
	 */
	@Query(
		"SELECT * FROM runs WHERE closed = 0 AND definitionName = :definition " +
			"AND UPPER(TRIM(identifier)) = :identifier ORDER BY startedAt DESC LIMIT 1",
	)
	suspend fun openRunForPack(definition: String, identifier: String): LocalRunEntity?

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

	/**
	 * Runs holding a photo that has not reached the server.
	 *
	 * **No attempt ceiling here, and that is the whole point.** This filtered on
	 * `attempts < PHOTO_MAX_ATTEMPTS`, which meant a photo that had failed a
	 * dozen times stopped being *findable* — the sweep no longer returned its
	 * run, so nothing ever tried it again and nothing said so. An engineer who
	 * inspected several packs on a slow depot link could lose the lot that way,
	 * quietly, because each failure that ceiling counted was the upload being
	 * killed at WorkManager's ten-minute limit rather than anything wrong with
	 * the photograph.
	 *
	 * The only state excluded now is `attention`, which means the file is gone
	 * from the handset — there is genuinely nothing left to send.
	 */
	@Query(
		"""
        SELECT DISTINCT runUuid FROM photos
        WHERE state IN ('pending', 'syncing')
        """
	)
	suspend fun runsWithPendingPhotos(): List<String>

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

	/** One run's queued photos, oldest first. Uncapped — see [runsWithPendingPhotos]. */
	@Query(
		"""
        SELECT * FROM photos
        WHERE runUuid = :uuid AND state IN ('pending', 'syncing')
        ORDER BY capturedAt ASC
        """
	)
	suspend fun pendingPhotos(uuid: String): List<LocalPhotoEntity>

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

	/**
	 * Put every given-up photo back in the queue.
	 *
	 * The ceiling exists to stop a photo whose file is gone retrying forever. It
	 * also catches photos that failed for a reason that has since been fixed —
	 * an endpoint that was not deployed yet — and those deserve another go.
	 */
	@Query("UPDATE photos SET state = 'pending', attempts = 0, lastError = NULL WHERE state != 'synced'")
	suspend fun resetExhaustedPhotos()

	@Query("UPDATE answers SET rejectedReason = NULL WHERE rejectedReason IS NOT NULL")
	suspend fun clearAnswerRejections()

	/** Photos nothing will retry — their file is gone. The only real dead end. */
	@Query("SELECT * FROM photos WHERE state = 'attention'")
	suspend fun photosNeedingAttention(): List<LocalPhotoEntity>

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

	/**
	 * Unsynced work across every run, split by whether it is still being tried.
	 *
	 * The split is not cosmetic. The first version counted everything not yet on
	 * the server as "going up", including rows nothing would ever pick up again
	 * — a photo past its attempt ceiling, or anything belonging to a run the
	 * sweep no longer looks at. The badge then sat on the screen for ever saying
	 * "Uploading now" about an upload that had stopped, which is the single most
	 * corrosive thing this feature could tell an engineer: it makes the honest
	 * badge unreadable too.
	 *
	 * `stuck` is counted with exactly the inverse of the conditions the sweep
	 * uses to find work, so the two can never disagree about what is in flight.
	 */
	@Query(
		"""
        SELECT
            (SELECT COUNT(*) FROM answers a
               JOIN runs r ON r.clientUuid = a.runUuid
               WHERE a.synced = 0 AND a.rejectedReason IS NULL AND r.closed = 0) AS answers,
            (SELECT COUNT(*) FROM photos
               WHERE state IN ('pending', 'syncing')) AS photos,
            (SELECT COUNT(DISTINCT clientUuid) FROM runs
               WHERE closed = 0 AND syncState != 'synced') AS runs,
            (SELECT
               (SELECT COUNT(*) FROM answers WHERE rejectedReason IS NOT NULL)
             + (SELECT COUNT(*) FROM photos WHERE state = 'attention')
            ) AS stuck,
            (SELECT COUNT(*) FROM photos
               WHERE state IN ('pending', 'syncing') AND attempts >= :hardGoing) AS struggling
        """
	)
	fun pendingSummary(hardGoing: Int = PHOTO_MAX_ATTEMPTS): Flow<PendingSummary>
}

data class PhotoCount(val stepCode: String, val count: Int)

data class PendingSummary(
	val answers: Int = 0,
	val photos: Int = 0,
	val runs: Int = 0,
	/**
	 * Rows nothing will retry, because there is nothing left to send — a photo
	 * whose file has gone from the handset, or an answer the server explicitly
	 * refused. A person has to look. Never counted as in flight.
	 *
	 * Deliberately *not* "has failed a lot". Everything that can still be sent
	 * is now retried for as long as it takes, so a high attempt count is a slow
	 * upload, not an abandoned one, and calling it stuck would be a lie in the
	 * one place an engineer has to be able to trust the wording.
	 */
	val stuck: Int = 0,
	/** Still queued and still being retried, but it is taking many goes. */
	val struggling: Int = 0,
) {
	val isEmpty: Boolean get() = answers == 0 && photos == 0 && runs == 0 && stuck == 0
	val total: Int get() = answers + photos
}

/**
 * How many goes a photo takes before the app admits it is having trouble.
 *
 * This used to be a ceiling: past it a photo was moved to `attention` and
 * dropped out of every query the sweep runs, so nothing tried it again. That
 * was wrong, and it cost real work. The failures being counted were almost
 * never the photograph's fault — an upload killed at WorkManager's ten-minute
 * limit, a depot link that dropped, a server restart — and every one of them is
 * fixed by trying again later. Twelve of them in an afternoon is easy.
 *
 * Now it only changes what the engineer is *told*: past this the queue says a
 * photo is having trouble, while still retrying it for as long as it takes. The
 * only thing that ends a photo's life is its file no longer existing, because
 * then there is genuinely nothing left to send.
 */
const val PHOTO_MAX_ATTEMPTS = 12
