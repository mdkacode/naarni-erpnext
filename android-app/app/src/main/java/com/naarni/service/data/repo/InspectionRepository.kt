package com.naarni.service.data.repo

import android.content.Context
import com.naarni.service.core.network.FrappeApi
import com.naarni.service.core.network.payload
import com.naarni.service.data.chat.ServerTime
import com.naarni.service.data.dto.APP_STEP_CAPABILITY
import com.naarni.service.data.dto.FoundRun
import com.naarni.service.data.dto.ProcessDefinition
import com.naarni.service.data.dto.ProcessResultRow
import com.naarni.service.data.dto.ProcessStep
import com.naarni.service.data.dto.ProcessSummary
import com.naarni.service.data.dto.RunBoard
import com.naarni.service.data.dto.SyncAnswer
import com.naarni.service.data.dto.SyncBatch
import com.naarni.service.data.dto.SyncScan
import com.naarni.service.data.inspection.CachedDefinitionEntity
import com.naarni.service.data.inspection.CachedProcessEntity
import com.naarni.service.data.inspection.InspectionDao
import com.naarni.service.data.inspection.LocalAnswerEntity
import com.naarni.service.data.inspection.LocalComputed
import com.naarni.service.data.inspection.LocalConditions
import com.naarni.service.data.inspection.LocalEvaluator
import com.naarni.service.data.inspection.LocalPhotoEntity
import com.naarni.service.data.inspection.LocalRunEntity
import com.naarni.service.data.inspection.LocalScanEntity
import com.naarni.service.data.inspection.PHOTO_MAX_ATTEMPTS
import com.naarni.service.data.inspection.PendingSummary
import com.naarni.service.data.inspection.SyncReconciler
import com.naarni.service.data.inspection.SyncState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

/**
 * The inspection an engineer is doing — held locally, pushed when it can be.
 *
 * Every write here lands in Room before the network is consulted, and every
 * read comes back from Room without consulting it. That is the whole design,
 * and it buys two things at once. A service engineer in a shed with no signal
 * can work a pack end to end. And on a *good* network the app is faster than it
 * was, because tapping an answer no longer waits on a round trip to find out
 * what the answer was — the on-device evaluator already knows.
 *
 * What this class is careful about, in order:
 *
 * 1. **Nothing is lost.** The only delete is [InspectionDao.purgeSyncedRun],
 *    which refuses to touch a run holding anything the server has not got.
 * 2. **Nothing is duplicated.** Runs are keyed on a device UUID the server is
 *    idempotent against; answers upsert by step; scans and photos carry their
 *    own UUIDs. A batch replayed after a lost response changes nothing.
 * 3. **Nothing is stuck.** A row the server refuses is marked and stops being
 *    resent, because a queue that cannot drain is worse than a row that does
 *    not land — it takes every later row down with it.
 */
class InspectionRepository(
	private val api: FrappeApi,
	private val dao: InspectionDao,
	private val context: Context,
) {

	private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

	/** Where queued photos live. App-private: a cache directory can be reclaimed. */
	private val photoDir: File
		get() = File(context.filesDir, "inspection_photos").apply { mkdirs() }

	// ------------------------------------------------------------- catalogue

	/**
	 * Pull every definition this user may run, so the app can start work offline.
	 *
	 * Called whenever there is a network and nothing more urgent to do — after
	 * login, and each time the process list is opened. Cheap to repeat: a
	 * definition is immutable once published, so re-fetching one only ever
	 * confirms what is already cached.
	 */
	suspend fun refreshCatalogue() {
		val boot = api.processBootstrap(APP_STEP_CAPABILITY).payload()
		dao.upsertProcesses(
			boot.processes.map {
				CachedProcessEntity(
					name = it.name,
					json = json.encodeToString(ProcessSummary.serializer(), it),
					sortKey = it.process_name.lowercase(),
				)
			}
		)
		boot.definitions.forEach { definition ->
			dao.upsertDefinition(
				CachedDefinitionEntity(
					name = definition.name,
					family = definition.family.ifBlank { definition.name },
					version = definition.version,
					json = json.encodeToString(ProcessDefinition.serializer(), definition),
				)
			)
		}
	}

	/**
	 * The process list — cached first, refreshed behind it.
	 *
	 * Cache-first rather than network-first on purpose: the list is the screen
	 * an engineer starts work from, and showing a spinner there when the phone
	 * has every definition it needs is the app failing at the one job offline
	 * support exists to do.
	 */
	suspend fun processes(): List<ProcessSummary> {
		val cached = dao.processes().mapNotNull {
			runCatching { json.decodeFromString(ProcessSummary.serializer(), it.json) }.getOrNull()
		}
		if (cached.isNotEmpty()) return cached
		return runCatching { api.listProcesses().payload() }.getOrDefault(emptyList())
	}

	/** Whether the app could start an inspection right now with no network. */
	suspend fun canWorkOffline(): Boolean = dao.definitionCount() > 0

	/**
	 * A definition, from cache when we hold it.
	 *
	 * Never goes to the network when a cached copy exists. Definitions are
	 * immutable once published — a change is a new version with a new name — so
	 * a cached copy cannot be stale, only superseded, and a plant dead zone
	 * mid-run costs nothing.
	 */
	suspend fun definition(processOrFamily: String): ProcessDefinition? {
		val cached = dao.definition(processOrFamily) ?: dao.definitionForFamily(processOrFamily)
		cached?.let { row ->
			runCatching { json.decodeFromString(ProcessDefinition.serializer(), row.json) }
				.getOrNull()
				?.let { return it }
		}
		return runCatching { api.getProcessDefinition(processOrFamily, APP_STEP_CAPABILITY).payload() }
			.onSuccess { fetched ->
				dao.upsertDefinition(
					CachedDefinitionEntity(
						name = fetched.name,
						family = fetched.family.ifBlank { fetched.name },
						version = fetched.version,
						json = json.encodeToString(ProcessDefinition.serializer(), fetched),
					)
				)
			}
			.getOrNull()
	}

	// ------------------------------------------------------------------ runs

	/**
	 * Start an inspection. Returns immediately, network or not.
	 *
	 * The returned UUID *is* the run as far as the app is concerned. The server
	 * learns about it on the next sync and is idempotent on the same key, so
	 * however many times the creating request is retried there is one run.
	 */
	suspend fun startRun(
		processFamily: String,
		identifier: String?,
		latitude: Double? = null,
		longitude: Double? = null,
	): String {
		val definition = definition(processFamily)
			?: error("This process has not been downloaded yet. Connect once and try again.")
		val uuid = UUID.randomUUID().toString()
		dao.upsertRun(
			LocalRunEntity(
				clientUuid = uuid,
				processFamily = processFamily,
				definitionName = definition.name,
				definitionVersion = definition.version,
				processName = definition.process_name,
				identifier = identifier,
				stageLabel = definition.stage_label,
				currentStage = definition.stages.minByOrNull { it.sequence }?.stage_code,
				startedAt = System.currentTimeMillis(),
			)
		)
		return uuid
	}

	/** What [startOrJoinRun] did, so the screen can say which of the three it was. */
	data class StartOutcome(
		val runUuid: String,
		/** Set when this pack was already being inspected — by whom, and how far. */
		val joined: FoundRun? = null,
		/** True when this handset already held an unfinished run for this pack. */
		val resumedLocally: Boolean = false,
	)

	/**
	 * Scan a pack and land in the right place: a new inspection, or the one that
	 * is already running for this battery.
	 *
	 * The whole point of the change. A pack is fifty-nine checks and a shift puts
	 * more than one person on it, so the second operator to scan a label must
	 * arrive *inside* the inspection already open rather than opening a rival copy
	 * of it — which used to leave one battery with two half-records and two
	 * verdicts.
	 *
	 * Three routes, in the order they are worth trying:
	 *
	 * 1. **This handset already has it.** Cheapest, works with no signal, and
	 *    covers the commonest case — somebody walked away and came back.
	 * 2. **The server has it.** One lookup off the scan, then the run is adopted
	 *    into the local store and behaves exactly like a locally-started one.
	 * 3. **Nobody has it.** Start fresh, locally and instantly.
	 *
	 * `online` is passed in rather than probed: offline, route 2 would otherwise
	 * spend the socket timeout blocking the Start button, and the server's own
	 * dedup catches the case on the next sync anyway.
	 */
	suspend fun startOrJoinRun(
		processFamily: String,
		identifier: String?,
		online: Boolean,
		latitude: Double? = null,
		longitude: Double? = null,
	): StartOutcome {
		val definition = definition(processFamily)
			?: error("This process has not been downloaded yet. Connect once and try again.")
		val key = identifier?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }

		if (key != null) {
			dao.openRunForPack(definition.name, key)?.let {
				return StartOutcome(it.clientUuid, resumedLocally = true)
			}
			if (online) {
				val found = runCatching {
					api.findOpenProcessRun(definition.family.ifBlank { definition.name }, key).payload()
				}.getOrNull()
				if (found != null && found.found && found.run.isNotBlank()) {
					adoptServerRun(found.run)?.let { return StartOutcome(it, joined = found) }
				}
			}
		}

		return StartOutcome(
			startRun(
				processFamily = processFamily,
				identifier = key,
				latitude = latitude,
				longitude = longitude,
			)
		)
	}

	/**
	 * The live board for a run, when there is a network to ask.
	 *
	 * Progress is drawn from Room so the board works in a shed; this adds the one
	 * thing the handset cannot know on its own — what a colleague has been doing
	 * on the same pack from another phone.
	 */
	suspend fun serverBoard(runUuid: String): RunBoard? {
		val name = dao.run(runUuid)?.serverName ?: return null
		return runCatching { api.processRunBoard(name).payload() }.getOrNull()
	}

	/**
	 * Remember exactly which check the operator is on.
	 *
	 * Called as they move through a module, not when they leave one — there is no
	 * reliable "leave" on a phone that gets pocketed mid-check, and a position
	 * saved on exit is a position that is usually never saved at all.
	 */
	suspend fun rememberPosition(runUuid: String, stage: String?, step: String?) =
		dao.rememberPosition(runUuid, stage, step)

	fun runFlow(uuid: String): Flow<LocalRunEntity?> = dao.runFlow(uuid)

	fun answersFlow(uuid: String): Flow<Map<String, LocalAnswerEntity>> =
		dao.answersFlow(uuid).map { rows -> rows.associateBy { it.stepCode } }

	fun photoCountsFlow(uuid: String): Flow<Map<String, Int>> =
		dao.photoCounts(uuid).map { rows -> rows.associate { it.stepCode to it.count } }

	fun openRuns(): Flow<List<LocalRunEntity>> = dao.openRuns()

	fun pendingSummary(): Flow<PendingSummary> = dao.pendingSummary()

	suspend fun run(uuid: String): LocalRunEntity? = dao.run(uuid)

	/**
	 * Adopt a run that already exists on the server into the local store.
	 *
	 * The bridge for a run started on an older build, or on another handset. Its
	 * answers come down once and everything after that is local, so an engineer
	 * who resumes an old run and then loses signal is in exactly the same
	 * position as one who started fresh.
	 */
	suspend fun adoptServerRun(serverName: String): String? {
		dao.runByServerName(serverName)?.let { return it.clientUuid }
		val remote = runCatching { api.getProcessRun(serverName).payload() }.getOrNull() ?: return null
		val definition = definition(remote.process_definition) ?: return null
		val uuid = UUID.randomUUID().toString()

		dao.upsertRun(
			LocalRunEntity(
				clientUuid = uuid,
				serverName = serverName,
				processFamily = definition.family.ifBlank { definition.name },
				definitionName = definition.name,
				definitionVersion = definition.version,
				processName = remote.process_name,
				identifier = remote.run_identifier,
				stageLabel = definition.stage_label,
				status = remote.status,
				currentStage = remote.current_stage,
				startedAt = ServerTime.millisOr(remote.started_at),
				passCount = remote.pass_count,
				failCount = remote.fail_count,
				criticalCount = remote.critical_count,
				answeredCount = remote.answered_count,
				scorePct = remote.score_pct,
				tracePct = remote.trace_completeness_pct,
				closed = remote.status in CLOSED_STATUSES,
				syncState = SyncState.SYNCED,
				lastSyncAt = System.currentTimeMillis(),
			)
		)
		// Already on the server, so seeded as synced — sending them back would
		// be a batch of forty rows the server would recognise and discard.
		remote.results.forEachIndexed { index, row ->
			// `value_text` is carried, not dropped. A colleague's typed serial
			// arrives in that field, and reading only `value_numeric` meant every
			// text answer came across as empty — so the second person on a pack
			// saw checks their colleague had plainly filled in as unanswered, and
			// the runner sent them back to the start of the module to redo them.
			val text = row.value_text?.takeIf { it.isNotBlank() }
			dao.upsertAnswer(
				LocalAnswerEntity(
					runUuid = uuid,
					stepCode = row.step_code,
					response = row.response,
					value = text ?: row.value_numeric?.toString(),
					remark = row.remark,
					skipped = row.is_skipped == 1,
					skipReason = row.skip_reason,
					clientSeq = index.toLong() + 1,
					answeredAt = ServerTime.millisOr(row.answered_at),
					localPass = row.is_pass == 1,
					localDeviation = row.is_deviation == 1,
					localCritical = row.is_critical == 1,
					localAnswered = !row.response.isNullOrBlank() ||
						text != null ||
						row.value_numeric != null ||
						row.is_skipped == 1,
					serverPass = row.is_pass,
					synced = true,
				)
			)
		}
		return uuid
	}

	// --------------------------------------------------------------- answers

	/**
	 * Record one answer: judged on the device, stored, queued.
	 *
	 * Returns the local verdict so the runner can react at once — turn the card
	 * green, or ask for the photograph that backs up a failure while the
	 * engineer is still holding the wrench.
	 */
	suspend fun saveAnswer(
		runUuid: String,
		step: ProcessStep,
		response: String? = null,
		value: String? = null,
		remark: String? = null,
		skipped: Boolean = false,
		skipReason: String? = null,
		secondsSpent: Int = 0,
	): LocalEvaluator.Verdict {
		val photoCount = dao.photoCountForStep(runUuid, step.step_code)
		val scanCount = dao.scans(runUuid).count { it.stepCode == step.step_code }

		// A Computed step is derived from earlier answers, never typed. The
		// posted value is ignored exactly as the server ignores it, so a derived
		// result can never disagree with its inputs.
		val effectiveValue = if (step.response_type == "Computed") {
			LocalComputed.evaluate(step.computed_expression, answeredMap(runUuid))?.toString()
		} else {
			value
		}

		val verdict = LocalEvaluator.evaluate(
			step = step,
			response = response,
			value = effectiveValue,
			skipped = skipped,
			photoCount = photoCount,
			scanCount = scanCount,
		)

		dao.upsertAnswer(
			LocalAnswerEntity(
				runUuid = runUuid,
				stepCode = step.step_code,
				response = response,
				value = effectiveValue,
				remark = remark,
				skipped = skipped,
				skipReason = skipReason,
				secondsSpent = secondsSpent,
				// Always above every sequence this run has used. A correction
				// made while a batch is in flight must sort after the row that
				// is currently travelling, or the reconcile will mark the stale
				// version delivered and quietly drop the correction.
				clientSeq = dao.maxClientSeq(runUuid) + 1,
				answeredAt = System.currentTimeMillis(),
				localPass = verdict.isPass,
				localDeviation = verdict.isDeviation,
				localCritical = verdict.isCritical,
				localAnswered = verdict.isAnswered,
				specSummary = verdict.specSummary,
			)
		)
		dao.recountRun(runUuid)
		dao.setRunSync(runUuid, SyncState.PENDING, null)
		return verdict
	}

	// ----------------------------------------------------------------- scans

	suspend fun queueScan(
		runUuid: String,
		entityType: String,
		payload: String,
		stepCode: String? = null,
		positionIndex: Int = 0,
		manual: Boolean = false,
		latitude: Double? = null,
		longitude: Double? = null,
	): String {
		val uuid = UUID.randomUUID().toString()
		dao.upsertScan(
			LocalScanEntity(
				scanUuid = uuid,
				runUuid = runUuid,
				entityType = entityType,
				stepCode = stepCode,
				positionIndex = positionIndex,
				payload = payload,
				manual = manual,
				latitude = latitude,
				longitude = longitude,
				scannedAt = System.currentTimeMillis(),
			)
		)
		dao.setRunSync(runUuid, SyncState.PENDING, null)
		return uuid
	}

	fun scansFlow(runUuid: String): Flow<List<LocalScanEntity>> = dao.scansFlow(runUuid)

	// ---------------------------------------------------------------- photos

	/**
	 * Queue a stamped photo.
	 *
	 * The file is copied into app-private storage before anything else. A run
	 * can sit queued for a whole shift, and the camera writes to the cache
	 * directory, which Android may reclaim under storage pressure — with the
	 * only evidence of a failed weld inside it.
	 */
	suspend fun queuePhoto(
		runUuid: String,
		step: ProcessStep,
		source: File,
		latitude: Double? = null,
		longitude: Double? = null,
		accuracyM: Double? = null,
	): String {
		val uuid = UUID.randomUUID().toString()
		val dest = File(photoDir, "${uuid}.jpg")
		if (source.absolutePath != dest.absolutePath) source.copyTo(dest, overwrite = true)
		dao.upsertPhoto(
			LocalPhotoEntity(
				photoUuid = uuid,
				runUuid = runUuid,
				stepCode = step.step_code,
				path = dest.absolutePath,
				capturedAt = System.currentTimeMillis(),
				latitude = latitude,
				longitude = longitude,
				accuracyM = accuracyM,
			)
		)

		// A Photo Only step *is* answered by its photographs — there is nothing
		// else to tap. Re-judging here is what turns the card green on the
		// shutter closing, and without it the step would sit unanswered and
		// block the stage submit with no way for the engineer to clear it.
		if (step.response_type == "Photo Only") {
			val existing = dao.answer(runUuid, step.step_code)
			saveAnswer(
				runUuid = runUuid,
				step = step,
				response = existing?.response,
				value = existing?.value,
				remark = existing?.remark,
			)
		}
		return uuid
	}

	fun photosFlow(runUuid: String): Flow<List<LocalPhotoEntity>> = dao.photosFlow(runUuid)

	// ---------------------------------------------------------------- submit

	/** What a local submit decided, before anything went near the network. */
	data class LocalSubmit(
		val accepted: Boolean,
		/** Mandatory checks this stage still needs — empty when accepted. */
		val missing: List<String> = emptyList(),
		/** True when this was the last stage and the inspection is done. */
		val finished: Boolean = false,
		val nextStage: String? = null,
	)

	/**
	 * Finish a stage, offline.
	 *
	 * The interlock runs here, against the cached definition and local answers,
	 * so an engineer is told what is missing while standing at the pack. The
	 * server checks again on sync and its answer wins — it may have a
	 * conditional step the device has not seen the trigger for — but by then the
	 * common case has already been caught at the right moment.
	 */
	suspend fun submitStage(runUuid: String, stageCode: String): LocalSubmit {
		val run = dao.run(runUuid) ?: return LocalSubmit(accepted = false)
		val definition = definition(run.definitionName) ?: return LocalSubmit(accepted = false)
		val answers = answeredMap(runUuid)

		val missing = LocalConditions.missingMandatory(definition.steps, stageCode, answers)
		if (missing.isNotEmpty()) return LocalSubmit(accepted = false, missing = missing)

		val submitted = (run.pendingSubmits.split(",").filter { it.isNotBlank() } + stageCode).distinct()
		val signedOff = submitted.toSet()
		val next = definition.stages
			.sortedBy { it.sequence }
			.firstOrNull { it.stage_code !in signedOff }

		dao.setPendingSubmits(runUuid, submitted.joinToString(","))
		dao.upsertRun(run.copy(pendingSubmits = submitted.joinToString(","), currentStage = next?.stage_code ?: run.currentStage, syncState = SyncState.PENDING))

		return LocalSubmit(accepted = true, finished = next == null, nextStage = next?.stage_code)
	}

	/** Local answers in the shape [LocalConditions] wants. */
	suspend fun answeredMap(runUuid: String): Map<String, LocalConditions.Answered> =
		dao.answers(runUuid).associate { row ->
			row.stepCode to LocalConditions.Answered(
				response = row.response,
				valueNumeric = row.value?.toDoubleOrNull(),
				isAnswered = row.localAnswered,
				isPass = row.localPass,
				isDeviation = row.localDeviation,
				isSkipped = row.skipped,
			)
		}

	// ------------------------------------------------------------------ sync

	/** What one sync attempt did, for the worker to decide whether to retry. */
	data class SyncOutcome(
		val ok: Boolean,
		val closed: Boolean = false,
		val rejections: List<String> = emptyList(),
		val error: String? = null,
	)

	/**
	 * Does the server know about this run yet?
	 *
	 * The worker asks so it can push photographs even when the answer batch has
	 * just failed. The two used to be one decision — a failed batch returned
	 * early and no photo was attempted — which meant one bad endpoint held back
	 * the evidence as well as the answers, for a run the server already had.
	 */
	suspend fun hasServerName(runUuid: String): Boolean = dao.run(runUuid)?.serverName != null

	/**
	 * Push one run's queued work.
	 *
	 * Called only from the sync worker, never from the UI — a screen that awaits
	 * the network is a screen that blocks in a shed, which is the thing all of
	 * this exists to prevent.
	 */
	suspend fun syncRun(runUuid: String): SyncOutcome {
		val run = dao.run(runUuid) ?: return SyncOutcome(ok = true)
		if (run.closed) return SyncOutcome(ok = true, closed = true)

		val answers = dao.unsyncedAnswers(runUuid)
		val scans = dao.unsyncedScans(runUuid)
		val submits = run.pendingSubmits.split(",").filter { it.isNotBlank() }

		// Nothing queued and the server already knows the run — a no-op, not a
		// request. Sweeps run often and most of them have nothing to say.
		if (answers.isEmpty() && scans.isEmpty() && submits.isEmpty() && run.serverName != null) {
			dao.setRunSync(runUuid, SyncState.SYNCED, null)
			return SyncOutcome(ok = true)
		}

		dao.setRunSync(runUuid, SyncState.SYNCING, null)

		val batch = SyncBatch(
			client_uuid = run.clientUuid,
			run = run.serverName,
			process = run.processFamily,
			identifier = run.identifier,
			started_at = stamp(run.startedAt),
			answers = answers.map { row ->
				SyncAnswer(
					step_code = row.stepCode,
					response = row.response,
					value = row.value,
					remark = row.remark,
					skipped = if (row.skipped) 1 else 0,
					skip_reason = row.skipReason,
					seconds_spent = row.secondsSpent,
					client_seq = row.clientSeq,
					answered_at = stamp(row.answeredAt),
				)
			},
			scans = scans.map { row ->
				SyncScan(
					client_uuid = row.scanUuid,
					entity_type = row.entityType,
					payload = row.payload,
					step_code = row.stepCode,
					position_index = row.positionIndex,
					is_manual_entry = if (row.manual) 1 else 0,
					latitude = row.latitude,
					longitude = row.longitude,
					scanned_at = stamp(row.scannedAt),
				)
			},
			submit_stages = submits,
		)

		val result = runCatching {
			api.syncProcessRun(json.encodeToString(SyncBatch.serializer(), batch)).payload()
		}.getOrElse { error ->
			dao.setRunSync(runUuid, SyncState.PENDING, error.message)
			return SyncOutcome(ok = false, error = error.message)
		}

		// -- reconcile -------------------------------------------------------
		//
		// Every rule that decides what "delivered" means lives in
		// [SyncReconciler], pure and separately tested, because this is the one
		// place a bug loses work silently rather than loudly.
		dao.setServerName(runUuid, result.name)

		if (result.sync.already_closed) {
			// The inspection is finished server-side and will accept nothing
			// more. Anything still queued for it is work the server already has
			// — or work it will never take — and leaving it pending means a
			// queue that can never drain and a "still going up" badge that never
			// clears. Settling it here is what stops the handset asking forever.
			answers.forEach { dao.markAnswerSynced(runUuid, it.stepCode, it.clientSeq, null) }
			dao.markScansSynced(scans.map { it.scanUuid })
			dao.setPendingSubmits(runUuid, "")
			dao.applyServerRun(
				uuid = runUuid,
				status = result.status,
				stage = result.current_stage,
				pass = result.pass_count,
				fail = result.fail_count,
				critical = result.critical_count,
				answered = result.answered_count,
				score = result.score_pct,
				trace = result.trace_completeness_pct,
				quarantine = result.quarantine_reason,
				closed = true,
				outstanding = null,
			)
			dao.setRunSync(runUuid, SyncState.SYNCED, null)
			return SyncOutcome(ok = true, closed = true)
		}
		val outcomes = SyncReconciler.reconcile(
			sent = answers.map { SyncReconciler.Sent(it.stepCode, it.clientSeq) },
			report = result.sync,
			serverVerdicts = result.results.associate { it.step_code to it.is_pass },
		)
		outcomes.forEach { outcome ->
			when (outcome) {
				is SyncReconciler.Outcome.Delivered -> dao.markAnswerSynced(
					uuid = runUuid,
					step = outcome.stepCode,
					seq = outcome.clientSeq,
					serverPass = outcome.serverPass,
				)
				is SyncReconciler.Outcome.Refused -> dao.markAnswerRejected(
					uuid = runUuid,
					step = outcome.stepCode,
					seq = outcome.clientSeq,
					reason = outcome.reason,
				)
			}
		}
		dao.markScansSynced(scans.map { it.scanUuid })
		result.sync.scan_warnings.forEach { warning ->
			dao.markScanDuplicate(warning.client_uuid, warning.duplicate_of)
		}
		mergeServerAnswers(runUuid, result.results)

		val stillPending = SyncReconciler.stagesStillPending(submits, result.sync)
		dao.setPendingSubmits(runUuid, stillPending.joinToString(","))

		val closed = result.status in CLOSED_STATUSES
		dao.applyServerRun(
			uuid = runUuid,
			status = result.status,
			stage = result.current_stage,
			pass = result.pass_count,
			fail = result.fail_count,
			critical = result.critical_count,
			answered = result.answered_count,
			score = result.score_pct,
			trace = result.trace_completeness_pct,
			quarantine = result.quarantine_reason,
			closed = closed,
			outstanding = result.sync.outstanding
				.takeIf { !it.isEmpty() }
				?.summary(),
		)

		val rejections = result.sync.answers_rejected.map { "${it.step_code}: ${it.reason}" } +
			result.sync.stages_rejected.filter { it.missing.isEmpty() }.map { "${it.stage}: ${it.reason}" }
		val queueRemaining = dao.unsyncedAnswerCount(runUuid) + stillPending.size
		dao.setRunSync(
			uuid = runUuid,
			state = SyncReconciler.nextState(rejections.size, queueRemaining),
			error = rejections.firstOrNull(),
		)
		return SyncOutcome(ok = true, closed = closed, rejections = rejections)
	}

	/**
	 * Bring a colleague's answers down onto this handset.
	 *
	 * Without this, sharing a pack half-worked: the board showed a colleague's
	 * progress (it reads the server) while the runner did not (it reads Room), so
	 * opening their module presented checks they had already answered as blank —
	 * and the resume logic, which counts local answers, sent the second operator
	 * back to the start.
	 *
	 * **A local row that has not synced always wins.** That row is a change this
	 * operator made which the server has not seen yet; overwriting it with the
	 * server's older copy would delete an answer somebody just gave. Everything
	 * else is replaced by the server's version, which is the authority on what the
	 * inspection says.
	 */
	private suspend fun mergeServerAnswers(runUuid: String, rows: List<ProcessResultRow>) {
		if (rows.isEmpty()) return
		val local = dao.answers(runUuid).associateBy { it.stepCode }

		rows.forEachIndexed { index, row ->
			val mine = local[row.step_code]
			// Ours and not yet delivered — keep it, it is on its way up.
			if (mine != null && !mine.synced) return@forEachIndexed

			val text = row.value_text?.takeIf { it.isNotBlank() }
			val answered = !row.response.isNullOrBlank() ||
				text != null ||
				row.value_numeric != null ||
				row.is_skipped == 1
			if (!answered) return@forEachIndexed

			dao.upsertAnswer(
				LocalAnswerEntity(
					runUuid = runUuid,
					stepCode = row.step_code,
					response = row.response,
					value = text ?: row.value_numeric?.toString(),
					remark = row.remark,
					skipped = row.is_skipped == 1,
					skipReason = row.skip_reason,
					// Keeps this device's counter monotonic: a merged row must
					// never sort above an answer this operator is still queueing.
					clientSeq = mine?.clientSeq ?: (index.toLong() + 1),
					answeredAt = ServerTime.millisOr(row.answered_at),
					localPass = row.is_pass == 1,
					localDeviation = row.is_deviation == 1,
					localCritical = row.is_critical == 1,
					localAnswered = true,
					serverPass = row.is_pass,
					synced = true,
				)
			)
		}
		dao.recountRun(runUuid)
	}

	/**
	 * Upload one queued photo and map it to its step.
	 *
	 * Requires the run to exist server-side, so the sync worker always pushes
	 * the batch first. A photo whose run has no name yet is left queued rather
	 * than failed — the next sweep, after the run is created, picks it up.
	 */
	suspend fun uploadPhoto(photoUuid: String): Boolean {
		val photo = dao.photo(photoUuid) ?: return true
		if (photo.state == SyncState.SYNCED) return true
		val run = dao.run(photo.runUuid) ?: return true
		val serverName = run.serverName ?: return false

		val file = File(photo.path)
		if (!file.exists()) {
			// Nothing to retry towards. Recorded as needing attention rather
			// than silently dropped: the engineer took a photograph and it is
			// not going to arrive, and they are the only person who can go and
			// take another one.
			dao.setPhotoState(photoUuid, SyncState.ATTENTION, "The photo file is missing", attempt = 1)
			return true
		}

		return runCatching {
			val part = MultipartBody.Part.createFormData(
				"file", file.name, file.asRequestBody("image/jpeg".toMediaType()),
			)
			fun text(v: String) = v.toRequestBody("text/plain".toMediaType())
			val fileUrl = api.uploadFile(
				part,
				text("Process Run"),
				text(serverName),
				// Private: an inspection photo carries a serial, a location and
				// a person's name, and none of that belongs on a public URL.
				text("1"),
			).message?.file_url ?: error("Upload returned no file URL")

			api.attachProcessPhotoSynced(
				run = serverName,
				stepCode = photo.stepCode,
				fileUrl = fileUrl,
				clientUuid = photoUuid,
				capturedAt = stamp(photo.capturedAt),
				latitude = photo.latitude,
				longitude = photo.longitude,
				accuracyM = photo.accuracyM,
				locationSource = if (photo.latitude != null) "GPS" else "Unavailable",
			)
			dao.markPhotoSynced(photoUuid, fileUrl)
			// The bytes are on the server now, and a field handset does not have
			// the storage to keep a second copy of every photo it ever took.
			runCatching { file.delete() }
			true
		}.getOrElse { error ->
			// Being stopped is not a failure of the photograph.
			//
			// `runCatching` catches CancellationException like anything else, so
			// without this an upload interrupted by the OS — which is what
			// happens to a long batch on a slow link — was recorded against the
			// photo as if the server had refused it. Enough of those and it used
			// to be abandoned. Rethrown so the worker can retry cleanly instead.
			if (error is kotlinx.coroutines.CancellationException) throw error

			// Stays PENDING however many times it has failed. The only exit from
			// the queue is success or a file that no longer exists; see
			// PHOTO_MAX_ATTEMPTS, which now only governs what the engineer is
			// told, not whether anything keeps trying.
			dao.setPhotoState(
				uuid = photoUuid,
				state = SyncState.PENDING,
				error = error.message,
				attempt = 1,
			)
			false
		}
	}

	suspend fun runsNeedingSync(): List<LocalRunEntity> = dao.runsNeedingSync()

	/**
	 * Give everything that gave up one more go.
	 *
	 * The escape hatch for the case the attempt ceiling exists to stop — a photo
	 * that failed twelve times because the endpoint was not deployed yet, rather
	 * than because anything about the photo is wrong. Without this the only cure
	 * is reinstalling the app, which takes the queue with it.
	 */
	suspend fun retryStuck() {
		dao.resetExhaustedPhotos()
		dao.clearAnswerRejections()
		dao.runsNeedingSync().forEach { dao.setRunSync(it.clientUuid, SyncState.PENDING, null) }
		// A photo parked as `attention` because its file was missing gets put
		// back only if the file has since reappeared — which it does after a
		// restore, or when the failure was a transient read error rather than a
		// deletion. One that really is gone stays flagged, because re-queueing
		// it would replace an honest "this one needs you" with a queue entry
		// that can never drain.
		dao.photosNeedingAttention().forEach { photo ->
			if (File(photo.path).exists()) {
				dao.setPhotoState(photo.photoUuid, SyncState.PENDING, null, attempt = 0)
			}
		}
	}

	/** Where a hard push has got to, for the progress dialog. */
	data class PushProgress(val done: Int, val total: Int, val label: String)

	/**
	 * Push everything on this handset to the server, now, on the engineer's say-so.
	 *
	 * This deliberately does the uploading **itself** rather than only asking
	 * WorkManager to. Every automatic trigger in this app goes through the job
	 * scheduler, and on the handsets this fleet uses an aggressive battery
	 * manager can hold those jobs back for hours — which is invisible from the
	 * engineer's side and indistinguishable from lost work. A person standing
	 * there tapping a button is the one moment the app is certainly in the
	 * foreground and certainly allowed to use the network, so it uses it.
	 *
	 * Everything retryable is un-stuck first, then every run with anything
	 * outstanding is pushed in turn, answers before photographs, because a photo
	 * can only attach to a run the server already knows about.
	 *
	 * A worker is enqueued as well, so anything this cannot finish before the
	 * engineer walks away still lands on its own.
	 *
	 * Returns a plain-language report of what happened.
	 */
	suspend fun hardPush(onProgress: (PushProgress) -> Unit = {}): String {
		retryStuck()

		val runs = LinkedHashSet<String>()
		runCatching { dao.runsNeedingSync().map { it.clientUuid } }.getOrNull()?.let(runs::addAll)
		runCatching { dao.runsWithPendingPhotos() }.getOrNull()?.let(runs::addAll)
		if (runs.isEmpty()) {
			return "Nothing waiting. Everything on this phone is already on the server."
		}

		var answersSent = 0
		var photosSent = 0
		val failures = mutableListOf<String>()

		// Counted up front so the dialog can show real progress rather than an
		// indeterminate spinner, which on a forty-photo batch is the difference
		// between waiting and giving up.
		val totalPhotos = runs.sumOf { runCatching { dao.pendingPhotos(it).size }.getOrDefault(0) }
		var photoIndex = 0

		runs.forEachIndexed { index, runUuid ->
			val label = dao.run(runUuid)?.identifier ?: runUuid.take(8)
			onProgress(PushProgress(index, runs.size, "Sending $label"))

			val queued = runCatching { dao.unsyncedAnswerCount(runUuid) }.getOrDefault(0)
			val outcome = runCatching { syncRun(runUuid) }
				.getOrElse { InspectionRepository.SyncOutcome(ok = false, error = it.message) }
			if (outcome.ok) {
				answersSent += queued
			} else {
				failures += "$label — ${outcome.error ?: "could not reach the server"}"
			}

			// Photographs go up whenever the server knows the run, even if the
			// answer batch just failed. They are the part that cannot be typed
			// again later.
			if (outcome.ok || dao.run(runUuid)?.serverName != null) {
				dao.pendingPhotos(runUuid).forEach { photo ->
					photoIndex++
					onProgress(PushProgress(photoIndex, totalPhotos, "Photo $photoIndex of $totalPhotos · $label"))
					if (runCatching { uploadPhoto(photo.photoUuid) }.getOrDefault(false)) {
						photosSent++
					} else if (failures.size < 6) {
						failures += "$label — a photo has not gone up yet"
					}
				}
			}
		}

		return buildString {
			append("Sent $answersSent answer(s) and $photosSent photo(s).")
			val left = runCatching { queueReport() }.getOrNull()
			if (failures.isNotEmpty()) {
				append("\n\nStill trying:\n")
				append(failures.distinct().take(6).joinToString("\n"))
			}
			if (left != null && !left.startsWith("Nothing waiting")) {
				append("\n\nStill on this phone:\n")
				append(left)
			} else {
				append("\n\nEverything on this phone is now on the server.")
			}
		}
	}

	/**
	 * Everything on this handset that has not reached the server, as one line.
	 *
	 * For the diagnostic sheet, and deliberately cheap enough to call from the
	 * UI: when an engineer says "my inspections have not gone up", the first
	 * question is what the phone thinks it is still holding, and the answer has
	 * to be available without a cable.
	 */
	suspend fun queueReport(): String {
		val runs = dao.runsNeedingSync()
		val stuckPhotos = dao.photosNeedingAttention()
		val lines = mutableListOf<String>()
		runs.forEach { run ->
			val photos = dao.pendingPhotos(run.clientUuid)
			lines += buildString {
				append(run.identifier ?: run.clientUuid.take(8))
				append(" — ")
				append(dao.unsyncedAnswerCount(run.clientUuid))
				append(" answers, ")
				append(photos.size)
				append(" photos, ")
				append(if (run.serverName == null) "not on server yet" else run.serverName)
				run.lastError?.let { append(" · ").append(it) }
			}
		}
		stuckPhotos.forEach { lines += "photo ${it.photoUuid.take(8)} — ${it.lastError ?: "needs attention"}" }
		return if (lines.isEmpty()) "Nothing waiting. Everything on this phone is on the server." else lines.joinToString("\n")
	}

	suspend fun runsWithPendingPhotos(): List<String> = dao.runsWithPendingPhotos()

	suspend fun pendingPhotos(runUuid: String): List<LocalPhotoEntity> = dao.pendingPhotos(runUuid)

	/**
	 * Drop finished runs the server has fully acknowledged.
	 *
	 * Deliberately conservative — [InspectionDao.purgeSyncedRun] refuses to
	 * delete anything holding unsynced work, whatever its age. A week's grace
	 * after completion also means an engineer can still open yesterday's
	 * inspection on the train home with no signal.
	 */
	suspend fun purgeOldRuns() {
		val cutoff = System.currentTimeMillis() - PURGE_AFTER_MS
		dao.purgeCandidates(cutoff).forEach { dao.purgeSyncedRun(it) }
	}

	private fun stamp(millis: Long): String = formatter.get()!!.format(java.util.Date(millis))

	companion object {
		private val CLOSED_STATUSES = setOf("Passed", "Quarantined", "Cancelled")

		/** A finished, delivered run stays on the handset this long. */
		private const val PURGE_AFTER_MS = 7L * 24 * 60 * 60 * 1000

		/**
		 * Frappe's timestamp format, without a timezone — matching [ServerTime]
		 * on the way in. The server and every handset that talks to it run on
		 * IST; converting in one direction only would make an inspection's start
		 * time disagree with its own answers.
		 */
		private val formatter = ThreadLocal.withInitial {
			SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
		}
	}
}
