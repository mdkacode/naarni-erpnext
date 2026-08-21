package com.naarni.service.data.repo

import com.naarni.service.core.network.FrappeApi
import com.naarni.service.core.network.payload
import com.naarni.service.data.dto.APP_STEP_CAPABILITY
import com.naarni.service.data.dto.VerificationQueue
import com.naarni.service.data.dto.FoundRun
import com.naarni.service.data.dto.OpenRun
import com.naarni.service.data.dto.ProcessDefinition
import com.naarni.service.data.dto.RunBoard
import com.naarni.service.data.dto.ProcessHistory
import com.naarni.service.data.dto.RunReport
import com.naarni.service.data.dto.ProcessRun
import com.naarni.service.data.dto.ProcessStep
import com.naarni.service.data.dto.LinkOption
import com.naarni.service.data.dto.ProcessSummary
import com.naarni.service.data.dto.RecordScanResponse
import com.naarni.service.data.dto.SaveResultResponse

/**
 * The app's whole process-engine surface.
 *
 * Definitions are cached in memory by name+version: they are immutable once
 * published, so a cached copy can never go stale, and a plant dead zone during
 * a run costs nothing.
 *
 * [APP_STEP_CAPABILITY] is the compatibility contract. Bump it only when this
 * build actually renders a newly added step type; the server flags anything
 * above it as unsupported and the runner shows a read-only card with an update
 * prompt instead of crashing. Without that, the first new step type published
 * would break every phone on the floor at once.
 */
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

class ProcessRepository(private val api: FrappeApi) {

    private val definitions = mutableMapOf<String, ProcessDefinition>()

    /** Last blank-query page per Link doctype, so the picker opens offline. */
    private val linkCache = mutableMapOf<String, List<LinkOption>>()

    suspend fun listProcesses(): List<ProcessSummary> = api.listProcesses().payload()

    /** The definition, from cache when we already hold it. */
    suspend fun definition(process: String, refresh: Boolean = false): ProcessDefinition {
        if (!refresh) definitions[process]?.let { return it }
        val fetched = api.getProcessDefinition(process, APP_STEP_CAPABILITY).payload()
        definitions[process] = fetched
        definitions[fetched.name] = fetched
        definitions[fetched.family] = fetched
        return fetched
    }

    fun cached(process: String): ProcessDefinition? = definitions[process]

    /**
     * Options behind a `Link` step, with the last answer for each doctype cached.
     *
     * The cache is what makes a Link step usable in a shed: the catalogue is 151
     * rows and changes about as often as the process does, so holding the last
     * blank-query page per doctype means opening the picker offline shows the
     * list rather than a spinner and an error. A typed search still needs the
     * network — and falls back to filtering the cached page rather than showing
     * nothing.
     */
    suspend fun linkOptions(doctype: String, txt: String = ""): List<LinkOption> {
        val cached = linkCache[doctype].orEmpty()
        return try {
            api.linkOptions(doctype = doctype, txt = txt).payload().also { fresh ->
                if (txt.isBlank()) linkCache[doctype] = fresh
            }
        } catch (_: Exception) {
            if (txt.isBlank()) cached
            else cached.filter {
                it.label.contains(txt, ignoreCase = true) ||
                    it.value.contains(txt, ignoreCase = true) ||
                    it.sublabel?.contains(txt, ignoreCase = true) == true
            }
        }
    }

    /** Add a missing option. Needs the network — creating offline would invent an id. */
    suspend fun createLinkOption(doctype: String, label: String): LinkOption =
        api.createLinkOption(doctype, label).payload().also { linkCache.remove(doctype) }

    suspend fun startRun(
        process: String,
        identifier: String?,
        clientUuid: String,
        station: String? = null,
        shift: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
    ): ProcessRun = api.startProcessRun(
        process = process,
        identifier = identifier,
        clientUuid = clientUuid,
        station = station,
        shift = shift,
        latitude = latitude,
        longitude = longitude,
    ).payload()

    suspend fun run(name: String): ProcessRun = api.getProcessRun(name).payload()

    suspend fun openRuns(): List<OpenRun> = api.myOpenProcessRuns().payload()

    /** This operator's own record — never cached, because the point is the tally. */
    suspend fun history(limit: Int = 30, offset: Int = 0, scope: String = "finished"): ProcessHistory =
        api.myProcessHistory(limit, offset, scope).payload()

    suspend fun report(name: String): RunReport = api.processRunReport(name).payload()

    suspend fun saveResult(
        run: String,
        stepCode: String,
        response: String? = null,
        value: String? = null,
        remark: String? = null,
        skipped: Boolean = false,
        skipReason: String? = null,
        secondsSpent: Int = 0,
    ): SaveResultResponse = api.saveStepResult(
        run = run,
        stepCode = stepCode,
        response = response,
        value = value,
        remark = remark,
        skipped = if (skipped) 1 else 0,
        skipReason = skipReason,
        secondsSpent = secondsSpent,
    ).payload()

    suspend fun recordScan(
        run: String,
        entityType: String,
        payload: String,
        stepCode: String? = null,
        positionIndex: Int = 0,
        manual: Boolean = false,
        latitude: Double? = null,
        longitude: Double? = null,
    ): RecordScanResponse = api.recordProcessScan(
        run = run,
        entityType = entityType,
        payload = payload,
        stepCode = stepCode,
        positionIndex = positionIndex,
        isManualEntry = if (manual) 1 else 0,
        latitude = latitude,
        longitude = longitude,
    ).payload()

    suspend fun attachPhoto(
        run: String,
        stepCode: String,
        fileUrl: String,
        capturedAt: String?,
        latitude: Double?,
        longitude: Double?,
        accuracyM: Double?,
        locationSource: String,
    ) {
        api.attachProcessPhoto(
            run = run,
            stepCode = stepCode,
            fileUrl = fileUrl,
            capturedAt = capturedAt,
            latitude = latitude,
            longitude = longitude,
            accuracyM = accuracyM,
            locationSource = locationSource,
        )
    }

    /**
     * Upload a stamped photo and map it to a step, in one call.
     *
     * Two round trips, deliberately sequenced: Frappe's `upload_file` stores the
     * bytes and hands back a File URL, and only then can `attach_photo` record
     * what that URL *is*. Doing it the other way round would leave a Process Run
     * Photo row pointing at a file that may never arrive.
     *
     * The coordinates are not passed here because they are already burnt into
     * the image by `PhotoStamper` at capture time; the server row keeps its own
     * copy for querying, which the caller supplies when it has a fix.
     */
    suspend fun uploadStepPhoto(
        run: String,
        stepCode: String,
        file: File,
        latitude: Double? = null,
        longitude: Double? = null,
        accuracyM: Double? = null,
    ) {
        val part = MultipartBody.Part.createFormData(
            "file", file.name, file.asRequestBody("image/jpeg".toMediaType()),
        )
        fun text(v: String) = v.toRequestBody("text/plain".toMediaType())
        // `upload_file` returns its payload in `message`, not the app's own
        // `{success,data}` envelope — it is Frappe's endpoint, not ours.
        val fileUrl = api.uploadFile(
            part,
            text("Process Run"),
            text(run),
            // Private: an inspection photo carries a serial, a location and a
            // person's name, and none of that belongs on a public URL.
            text("1"),
        ).message?.file_url ?: error("Photo upload failed")
        attachPhoto(
            run = run,
            stepCode = stepCode,
            fileUrl = fileUrl,
            capturedAt = null,
            latitude = latitude,
            longitude = longitude,
            accuracyM = accuracyM,
            locationSource = if (latitude != null) "GPS" else "Unavailable",
        )
    }

    suspend fun submitStage(run: String, stage: String, remarks: String? = null): ProcessRun =
        api.submitProcessStage(run, stage, remarks).payload()

    /** The modules of one run, with the last pair of hands on each. */
    suspend fun board(run: String): RunBoard = api.processRunBoard(run).payload()

    /** Is this pack already being inspected? Asked straight off the scan. */
    suspend fun findOpenRun(process: String, identifier: String): FoundRun =
        api.findOpenProcessRun(process, identifier).payload()

    /** Packs waiting on this person's signature. Needs the network, by nature. */
    suspend fun verificationQueue(limit: Int = 50): VerificationQueue =
        api.verificationQueue(limit).payload()

    suspend fun verifyStage(run: String, stage: String, approve: Boolean, remarks: String? = null): ProcessRun =
        api.verifyProcessStage(run, stage, if (approve) "Approved" else "Rejected", remarks).payload()

    companion object {
    }
}

/**
 * Groups a stage's steps into screens.
 *
 * Sections come straight from the process definition, and that is deliberate:
 * the paper sheets these processes replace already group checks 2–5 at a time,
 * which is exactly the chunk size that keeps a checklist honest. One section =
 * one screen means the operator answers the cooling-plate questions while
 * standing at the cooling plate.
 */
fun screensFor(definition: ProcessDefinition, stageCode: String): List<ProcessScreen> {
    val stage = definition.stages.firstOrNull { it.stage_code == stageCode }
    val steps = definition.steps.filter { it.stage == stageCode }.sortedBy { it.sequence }
    if (steps.isEmpty()) return emptyList()

    return when (stage?.screen_grouping) {
        "One Per Screen" -> steps.map { ProcessScreen(it.section.orEmpty(), listOf(it)) }
        "Single Screen" -> listOf(ProcessScreen(stage.label, steps))
        "Fixed Count" -> steps.chunked(stage.steps_per_screen.coerceAtLeast(1))
            .map { ProcessScreen(it.first().section.orEmpty(), it) }
        else -> {
            // By Section, preserving definition order rather than sorting names.
            val order = LinkedHashMap<String, MutableList<ProcessStep>>()
            steps.forEach { order.getOrPut(it.section.orEmpty()) { mutableListOf() }.add(it) }
            order.map { (section, items) -> ProcessScreen(section, items) }
        }
    }
}

data class ProcessScreen(val section: String, val steps: List<ProcessStep>)
