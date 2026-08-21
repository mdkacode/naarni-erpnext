package com.naarni.service.core.inspection

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.naarni.service.App
import com.naarni.service.R
import com.naarni.service.appContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Getting an engineer's work off the handset and onto the server.
 *
 * The design goal is stated plainly because it drives every decision below:
 * **an inspection recorded on this phone reaches the server, eventually, without
 * anybody thinking about it.** Not "usually". The engineer must never have to
 * remember to open the app, or press a button, or check a queue.
 *
 * That takes four independent triggers, and the redundancy is the point — each
 * one covers a case the others miss:
 *
 * 1. **On every local write.** Online, work lands within a second of the tap.
 * 2. **When the network returns.** [InspectionConnectivity] watches for it, so
 *    walking out of a shed drains the queue without opening the app.
 * 3. **When the app comes forward.** Covers the phone that was rebooted, or
 *    whose jobs were cleared by an aggressive OEM battery manager — which on
 *    the handsets this fleet actually uses is not a rare event.
 * 4. **Every fifteen minutes, forever.** The backstop. If all three above fail,
 *    this still drains. It is why the guarantee can be stated without an
 *    asterisk.
 *
 * Retries are exponential with no attempt ceiling on the batch worker. A cap
 * would mean queued work that stops trying while the engineer still believes it
 * is on its way, and there is no honest number to pick — a phone can be off the
 * network for a whole shift. The one thing that *is* capped is a photo, which
 * can fail for reasons retrying will never fix; see `PHOTO_MAX_ATTEMPTS`.
 */

private const val TAG = "InspectionSync"
private const val KEY_RUN = "run_uuid"

/** Tags every inspection job, so a sign-out can cancel the lot in one call. */
const val INSPECTION_WORK_TAG = "inspection"

private const val UNIQUE_SWEEP = "inspection-sweep"
private const val UNIQUE_PERIODIC = "inspection-periodic"

private fun connected() = Constraints.Builder()
	.setRequiredNetworkType(NetworkType.CONNECTED)
	.build()

/**
 * Pushes one run's queued answers, scans and submits, then its photos.
 *
 * Ordered, and the order matters: a photo can only be attached to a run the
 * server knows about, and the batch is what creates it. Photos follow in the
 * same worker rather than being fanned out, so a run's evidence arrives with
 * its answers instead of trickling in behind them.
 */
class InspectionSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

	/**
	 * Runs as a foreground data-sync service, and this is the fix for the
	 * failure that lost a day's inspections.
	 *
	 * An ordinary worker gets about ten minutes before the system stops it. An
	 * engineer who has done several packs is carrying dozens of full-resolution
	 * stamped photographs, and on a depot link that is comfortably more than ten
	 * minutes of uploading — so every attempt was killed part-way, for ever,
	 * and the queue never drained. Nothing about it looked like a failure: the
	 * worker was simply stopped, and the next sweep started the same doomed
	 * climb again.
	 *
	 * As a foreground service it keeps running until the work is done, and the
	 * engineer can watch it go. That is what FOREGROUND_SERVICE_DATA_SYNC is
	 * declared for.
	 */
	override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0)

	private fun foregroundInfo(done: Int, total: Int): ForegroundInfo {
		val notification = NotificationCompat.Builder(applicationContext, App.CHANNEL_INSPECTION_UPLOADS)
			.setSmallIcon(R.drawable.ic_stat_notify)
			.setContentTitle("Uploading inspection")
			.setContentText(if (total > 0) "Photo $done of $total" else "Sending answers…")
			.setProgress(total.coerceAtLeast(1), done, total == 0)
			.setOngoing(true)
			.setSilent(true)
			.build()
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			ForegroundInfo(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
		} else {
			ForegroundInfo(NOTIF_ID, notification)
		}
	}

	override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
		val runUuid = inputData.getString(KEY_RUN) ?: return@withContext Result.failure()
		val repo = applicationContext.appContainer.inspectionRepo

		// Best-effort: on the handful of OEM builds that refuse the promotion we
		// carry on as an ordinary worker rather than failing outright, because a
		// ten-minute upload is still better than none.
		runCatching { setForeground(foregroundInfo(0, 0)) }

		val outcome = try {
			repo.syncRun(runUuid)
		} catch (e: Exception) {
			Log.w(TAG, "sync failed for $runUuid: ${e.message}")
			return@withContext Result.retry()
		}

		// A failed batch no longer holds back the photographs. If the server
		// already knows this run, its evidence can go up regardless of what just
		// happened to the answers — those two used to share one early return,
		// so a single bad response stranded both.
		val canSendPhotos = outcome.ok || repo.hasServerName(runUuid)
		if (!canSendPhotos) {
			// Nothing was marked delivered, so retrying is safe — and it is the
			// only thing that gets this engineer's morning onto the server.
			return@withContext Result.retry()
		}

		val photos = repo.pendingPhotos(runUuid)
		var landed = 0
		var allPhotosLanded = true
		photos.forEachIndexed { index, photo ->
			// A clean stop, so the interrupted photo is retried rather than
			// counted against. Everything already uploaded stays uploaded — each
			// one is marked the moment the server takes it.
			if (isStopped) return@withContext Result.retry()
			runCatching { setForeground(foregroundInfo(index + 1, photos.size)) }
			if (repo.uploadPhoto(photo.photoUuid)) landed++ else allPhotosLanded = false
		}
		if (photos.isNotEmpty()) {
			Log.i(TAG, "run $runUuid: $landed/${photos.size} photo(s) uploaded")
		}

		if (!outcome.ok || !allPhotosLanded) Result.retry() else Result.success()
	}

	private companion object {
		const val NOTIF_ID = 4802
	}
}

/**
 * Finds every run with something to say and enqueues a sync for each.
 *
 * The entry point for all three of the "something changed in the world" triggers
 * — connectivity, foreground, and the periodic backstop. Deliberately dumb: it
 * asks the store what is outstanding rather than being told, so a run queued by
 * a build that has since been replaced is still picked up.
 */
class InspectionSweepWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

	override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
		val container = applicationContext.appContainer
		val repo = container.inspectionRepo

		val pending = buildSet {
			runCatching { repo.runsNeedingSync().map { it.clientUuid } }.getOrNull()?.let(::addAll)
			runCatching { repo.runsWithPendingPhotos() }.getOrNull()?.let(::addAll)
		}
		// Logged every sweep, including when it finds nothing. A queue that has
		// stalled is otherwise indistinguishable from one that is empty — the
		// sweep returns SUCCESS either way — and that ambiguity cost an
		// afternoon of guessing at a badge that said "Uploading now" while no
		// sync worker had run at all.
		Log.i(TAG, "sweep: ${pending.size} run(s) with work — $pending")
		pending.forEach { InspectionWork.sync(applicationContext, it) }

		// While there is a network anyway: top up the definition cache, so the
		// next time there is not one, the app can still start an inspection.
		runCatching { repo.refreshCatalogue() }
			.onFailure { Log.d(TAG, "catalogue refresh skipped: ${it.message}") }

		runCatching { repo.purgeOldRuns() }

		Result.success()
	}
}

/** Enqueue helpers. Unique work per run, so a burst of taps cannot stack workers. */
object InspectionWork {

	/**
	 * Queue a sync for one run.
	 *
	 * `REPLACE`, and the reason is a stall seen on a real handset.
	 *
	 * This was `APPEND_OR_REPLACE`, reasoning that an answer tapped while a sync
	 * is running must not be dropped from the schedule. But appending puts the
	 * new job *behind* whatever is already in that unique chain — including a
	 * job sitting in a long exponential backoff. Every sync during the window
	 * when the endpoint was not yet deployed backed off to minutes, and after
	 * that every new sync queued behind them: the sweep reported work, enqueued
	 * it, and no sync worker ever ran again. A queue that cannot recover from a
	 * bad afternoon is not a queue.
	 *
	 * Replacing is safe precisely because the batch is a description of state
	 * rather than a list of operations. Cancelling one mid-flight loses nothing
	 * — no row is marked delivered until the server acknowledges it — and the
	 * replacement re-reads the queue, so it carries the interrupted work *and*
	 * the answer that triggered it.
	 */
	fun sync(context: Context, runUuid: String) {
		val request = OneTimeWorkRequestBuilder<InspectionSyncWorker>()
			.addTag(INSPECTION_WORK_TAG)
			.setInputData(workDataOf(KEY_RUN to runUuid))
			.setConstraints(connected())
			.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
			.build()
		WorkManager.getInstance(context)
			.enqueueUniqueWork("inspection-sync-$runUuid", ExistingWorkPolicy.REPLACE, request)
	}

	/** Drain everything outstanding — on connectivity, on foreground, on a timer. */
	fun sweep(context: Context) {
		val request = OneTimeWorkRequestBuilder<InspectionSweepWorker>()
			.addTag(INSPECTION_WORK_TAG)
			.setConstraints(connected())
			.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
			.build()
		WorkManager.getInstance(context)
			.enqueueUniqueWork(UNIQUE_SWEEP, ExistingWorkPolicy.REPLACE, request)
	}

	/**
	 * The backstop. Registered once and left running for the life of the install.
	 *
	 * Fifteen minutes is WorkManager's floor for periodic work, and it is the
	 * right choice here — the cost of a no-op sweep is a database query, and the
	 * cost of not having it is an inspection that never arrives.
	 */
	fun ensurePeriodic(context: Context) {
		val request = PeriodicWorkRequestBuilder<InspectionSweepWorker>(15, TimeUnit.MINUTES)
			.addTag(INSPECTION_WORK_TAG)
			.setConstraints(connected())
			.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
			.build()
		WorkManager.getInstance(context).enqueueUniquePeriodicWork(
			UNIQUE_PERIODIC,
			// KEEP, so an app restart does not reset the schedule and push the
			// next run fifteen minutes further out every time the app opens.
			ExistingPeriodicWorkPolicy.KEEP,
			request,
		)
	}

	/**
	 * Cancel every inspection job.
	 *
	 * Called on sign-out. The queued *work* survives in the database on purpose
	 * — see [com.naarni.service.data.inspection.InspectionDatabase] — but the
	 * jobs must not, or a sync belonging to the previous engineer would fire
	 * under the next person's session on a shared depot handset.
	 */
	fun cancelAll(context: Context) {
		WorkManager.getInstance(context).cancelAllWorkByTag(INSPECTION_WORK_TAG)
	}
}
