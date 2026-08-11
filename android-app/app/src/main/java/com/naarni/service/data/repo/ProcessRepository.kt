package com.naarni.service.data.repo

import com.naarni.service.core.network.FrappeApi
import com.naarni.service.core.network.payload
import com.naarni.service.data.dto.OpenRun
import com.naarni.service.data.dto.ProcessDefinition
import com.naarni.service.data.dto.ProcessRun
import com.naarni.service.data.dto.ProcessStep
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
class ProcessRepository(private val api: FrappeApi) {

    private val definitions = mutableMapOf<String, ProcessDefinition>()

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

    suspend fun submitStage(run: String, stage: String, remarks: String? = null): ProcessRun =
        api.submitProcessStage(run, stage, remarks).payload()

    suspend fun verifyStage(run: String, stage: String, approve: Boolean, remarks: String? = null): ProcessRun =
        api.verifyProcessStage(run, stage, if (approve) "Approved" else "Rejected", remarks).payload()

    companion object {
        /** Step-type capability this build renders. See the class docstring. */
        const val APP_STEP_CAPABILITY = 1
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
