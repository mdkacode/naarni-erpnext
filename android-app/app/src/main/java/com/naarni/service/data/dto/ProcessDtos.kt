package com.naarni.service.data.dto

import kotlinx.serialization.Serializable

/**
 * Wire types for the process engine.
 *
 * These describe *any* process, not battery QC — the app ships a renderer, not a
 * screen per process. Adding a process on the server is zero app changes;
 * adding a new [ProcessStep.response_type] is the one thing that needs a release,
 * which is what [ProcessStep.supported] exists to survive.
 */

/** A process the logged-in user may run, as shown in the process list. */
@Serializable
data class ProcessSummary(
    val name: String = "",
    val process_code: String = "",
    val process_name: String = "",
    val family: String = "",
    val version: Int = 1,
    val description: String? = null,
    val icon: String? = null,
    val subject_label: String? = null,
    val identifier_mode: String = "Scan QR",
    val stage_label: String = "Stage",
    val expected_minutes: Int = 0,
    val stage_count: Int = 0,
    val step_count: Int = 0,
)

/** One selectable outcome. `is_pass` and `is_critical` come from config, never from the label. */
@Serializable
data class ProcessOption(
    val value: String = "",
    val label: String = "",
    val label_alt: String? = null,
    val is_pass: Int = 0,
    val is_critical: Int = 0,
    val requires_remark: Int = 0,
    val requires_photo: Int = 0,
    val color: String = "grey",
    val icon: String? = null,
)

@Serializable
data class ProcessCondition(
    val when_step: String = "",
    val operator: String = "Equals",
    val value: String? = null,
    val join: String = "AND",
)

@Serializable
data class ProcessStage(
    val stage_code: String = "",
    val label: String = "",
    val label_alt: String? = null,
    val sequence: Int = 0,
    val instructions: String? = null,
    val signoff_role: String? = null,
    val requires_second_signoff: Int = 0,
    val second_signoff_role: String? = null,
    val screen_grouping: String = "By Section",
    val steps_per_screen: Int = 5,
)

@Serializable
data class ProcessStep(
    val step_code: String = "",
    val stage: String = "",
    val sequence: Int = 0,
    val display_no: String? = null,
    val section: String? = null,
    val section_alt: String? = null,
    val label: String = "",
    val label_alt: String? = null,
    val method_label: String? = null,
    val help_text: String? = null,
    val help_text_alt: String? = null,
    val reference_image_good: String? = null,
    val reference_image_bad: String? = null,
    val reaction_plan: String? = null,
    val response_type: String = "Choice",
    val options: List<ProcessOption> = emptyList(),
    val link_doctype: String? = null,
    /** Whether the runner may add a missing option to [link_doctype] inline. */
    val allow_inline_create: Int = 0,
    val computed_expression: String? = null,
    val unit: String? = null,
    val min_value: Double = 0.0,
    val max_value: Double = 0.0,
    val nominal_value: Double = 0.0,
    val tolerance: Double = 0.0,
    val pass_condition: String? = null,
    val decimals: Int = 2,
    val default_value: String? = null,
    /** Pre-rendered by the server, e.g. "10 ± 1 Nm (9–11)". Shown verbatim. */
    val spec_summary: String? = null,
    val requires_photo: Int = 0,
    val photo_policy: String? = null,
    val min_photos: Int = 1,
    val max_photos: Int = 3,
    val photo_hint: String? = null,
    val requires_scan: Int = 0,
    val scan_entity_type: String? = null,
    val scan_count: Int = 1,
    val requires_signature: Int = 0,
    val is_critical: Int = 0,
    val is_mandatory: Int = 0,
    val allow_skip: Int = 1,
    val skip_reasons: List<String> = emptyList(),
    val weight: Double = 1.0,
    val expected_seconds: Int = 0,
    val visibility_conditions: List<ProcessCondition> = emptyList(),
    /**
     * False when this install is too old to render the step type. The runner
     * shows a read-only card with an update prompt rather than crashing, so one
     * new step type cannot break every phone on the floor at once.
     */
    val supported: Boolean = true,
    val required_capability: Int = 1,
)

@Serializable
data class ProcessEntityType(
    val entity_code: String = "",
    val label: String = "",
    val label_alt: String? = null,
    val icon: String? = null,
    val expected_count: Int = 1,
    val is_scan_enabled: Int = 1,
)

/** The whole process, fetched once and cached by version so it runs offline. */
@Serializable
data class ProcessDefinition(
    val name: String = "",
    val process_code: String = "",
    val process_name: String = "",
    val family: String = "",
    val version: Int = 1,
    val description: String? = null,
    /** What operators call a stage — "Module" for battery QC. Never hardcode "Stage". */
    val stage_label: String = "Stage",
    val subject_doctype: String? = null,
    val subject_label: String? = null,
    val identifier_mode: String = "Scan QR",
    val identifier_pattern: String? = null,
    /**
     * "Text" or "Numbers Only". A pack number that is digits deserves the digits
     * keypad: on a full QWERTY the numbers are the small row along the top, and
     * one mistyped character opens an inspection of a battery that does not exist.
     */
    val identifier_keypad: String = "Text",
    val allow_offline: Int = 1,
    val allow_resume: Int = 1,
    val expected_minutes: Int = 0,
    val scoring_enabled: Int = 1,
    val pass_threshold_pct: Double = 0.0,
    val stages: List<ProcessStage> = emptyList(),
    val steps: List<ProcessStep> = emptyList(),
    val entity_types: Map<String, ProcessEntityType> = emptyMap(),
)

@Serializable
data class ProcessResultRow(
    val step_code: String = "",
    val stage: String? = null,
    val display_no: String? = null,
    val response: String? = null,
    val value_numeric: Double? = null,
    val value_text: String? = null,
    val is_pass: Int = 0,
    val is_deviation: Int = 0,
    val is_critical: Int = 0,
    val is_skipped: Int = 0,
    val skip_reason: String? = null,
    val remark: String? = null,
    val photo_count: Int = 0,
    val entry_flag: String? = null,
    val answered_at: String? = null,
)

@Serializable
data class ProcessScanRow(
    val entity_type: String = "",
    val step_code: String? = null,
    val position_index: Int = 0,
    val serial_no: String? = null,
    val mfg_date: String? = null,
    val module_number: String? = null,
    val batch_ref: String? = null,
    val is_manual_entry: Int = 0,
    val parse_failed: Int = 0,
    val duplicate_of: String? = null,
)

@Serializable
data class ProcessPhotoRow(
    val step_code: String? = null,
    val file_url: String = "",
    val captured_at: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val geofence_status: String? = null,
)

@Serializable
data class ProcessSignoffRow(
    val stage: String = "",
    val level: String = "Operator",
    val decision: String = "Submitted",
    val user: String? = null,
    val user_full_name: String? = null,
    val signed_at: String? = null,
    val remarks: String? = null,
)

// ------------------------------------------------------------ shared packs
//
// A battery is fifty-nine checks across several modules and a shift puts more
// than one person on it. These types are how the app shows that: who is on this
// pack, which module they were last in, and what is still free to pick up.

/** One person who has actually done something on a run. */
@Serializable
data class RunParticipant(
    val user: String = "",
    val full_name: String = "",
    val answers: Int = 0,
    val last_at: String? = null,
    val last_stage: String? = null,
)

/** One module of a run, as it appears on the board. */
@Serializable
data class BoardStage(
    val stage_code: String = "",
    val label: String = "",
    val sequence: Int = 0,
    val total: Int = 0,
    val answered: Int = 0,
    /** Mandatory checks still missing — what actually holds the run open. */
    val outstanding: Int = 0,
    val blocked: Boolean = false,
    val submitted: Boolean = false,
    val submitted_by: String? = null,
    val submitted_at: String? = null,
    val last_user: String? = null,
    val last_by: String? = null,
    val last_at: String? = null,
    /** Somebody answered here within the last ten minutes. */
    val active_now: Boolean = false,
) {
    val isDone: Boolean get() = submitted || (total > 0 && answered >= total)
    val fraction: Float get() = if (total <= 0) 0f else answered.toFloat() / total
}

/** Every module of one run, plus everyone working it. */
@Serializable
data class RunBoard(
    val run: String = "",
    val run_identifier: String? = null,
    val process_name: String = "",
    /** What operators call a module here — never hardcode "Stage". */
    val stage_label: String = "Stage",
    val status: String = "",
    val current_stage: String? = null,
    val answered_count: Int = 0,
    val quarantine_reason: String? = null,
    val stages: List<BoardStage> = emptyList(),
    val participants: List<RunParticipant> = emptyList(),
)

/** What a scanned label turns out to be: a pack in progress, or a new one. */
@Serializable
data class FoundRun(
    val found: Boolean = false,
    val run: String = "",
    val run_identifier: String? = null,
    val process_name: String = "",
    val stage_label: String = "Stage",
    val status: String = "",
    val current_stage: String? = null,
    val answered_count: Int = 0,
    val started_at: String? = null,
    val stages: List<BoardStage> = emptyList(),
    val participants: List<RunParticipant> = emptyList(),
)

/** A run in progress or finished — the whole state the runner needs to resume. */
@Serializable
data class ProcessRun(
    val name: String = "",
    val process_definition: String = "",
    val process_name: String = "",
    val definition_version: Int = 1,
    val run_identifier: String? = null,
    val subject_doctype: String? = null,
    val subject_name: String? = null,
    val status: String = "Draft",
    val current_stage: String? = null,
    val result: String? = null,
    val score_pct: Double = 0.0,
    val score_earned: Double = 0.0,
    val score_max: Double = 0.0,
    val pass_count: Int = 0,
    val fail_count: Int = 0,
    val skip_count: Int = 0,
    val critical_count: Int = 0,
    val answered_count: Int = 0,
    val is_first_pass: Int = 0,
    val trace_completeness_pct: Double = 0.0,
    val quarantine_reason: String? = null,
    val blocked_stages: List<String> = emptyList(),
    val started_at: String? = null,
    val completed_at: String? = null,
    /**
     * Present only when a submit did *not* finish the run: what is still to do.
     *
     * The server keeps a run open rather than stamping a verdict on a half-done
     * inspection, so the operator has to be told why they are back on the first
     * screen instead of on the summary.
     */
    val outstanding: ProcessOutstanding? = null,
    val is_test_run: Int = 0,
    /** Everyone who has worked this pack, most recent first. */
    val participants: List<RunParticipant> = emptyList(),
    val results: List<ProcessResultRow> = emptyList(),
    val scans: List<ProcessScanRow> = emptyList(),
    val photos: List<ProcessPhotoRow> = emptyList(),
    val signoffs: List<ProcessSignoffRow> = emptyList(),
)

/** Live totals returned alongside a saved answer, so the runner updates without a refetch. */
@Serializable
data class ProcessOutstanding(
    val stages: List<String> = emptyList(),
    val steps: List<String> = emptyList(),
) {
    fun isEmpty() = stages.isEmpty() && steps.isEmpty()

    fun summary(): String = (stages + steps).joinToString(", ")
}

@Serializable
data class ProcessRunTotals(
    val status: String = "",
    val score_pct: Double = 0.0,
    val pass_count: Int = 0,
    val fail_count: Int = 0,
    val critical_count: Int = 0,
)

@Serializable
data class ProcessClientHint(
    val action: String = "",
    val target: String? = null,
    val message: String? = null,
)

/** What the server sends back after judging one answer. */
@Serializable
data class SaveResultResponse(
    val result: ProcessResultRow = ProcessResultRow(),
    val needs_photo: Boolean = false,
    val needs_remark: Boolean = false,
    val client_hints: List<ProcessClientHint> = emptyList(),
    val run: ProcessRunTotals = ProcessRunTotals(),
)

@Serializable
data class ParsedScan(
    val raw_payload: String = "",
    val serial_no: String? = null,
    val mfg_date: String? = null,
    val module_number: String? = null,
    val batch_ref: String? = null,
    val revision: String? = null,
    val parse_failed: Int = 0,
)

@Serializable
data class RecordScanResponse(
    val scan: ParsedScan = ParsedScan(),
    val duplicate_of: String? = null,
    val trace_completeness_pct: Double = 0.0,
)

/** A run this user started and can pick back up. */
@Serializable
data class OpenRun(
    val name: String = "",
    val process_definition: String = "",
    val process_name: String = "",
    val run_identifier: String? = null,
    val status: String = "",
    val current_stage: String? = null,
    val answered_count: Int = 0,
    val started_at: String? = null,
    val started_by: String? = null,
    /** How many people have worked this pack. 1 means it is yours alone. */
    val participant_count: Int = 1,
)

// ---------------------------------------------------------- operator history
//
// What one person did, read back to that person. Distinct from [ProcessRun],
// which is the live state of a run being worked: these types are a record, so
// they carry the label and spec that were in force at the time rather than a
// pointer into a definition that may since have been re-published.

/** Headline counts for the operator's own work. */
@Serializable
data class HistoryStats(
    val total: Int = 0,
    val today: Int = 0,
    val week: Int = 0,
    val passed: Int = 0,
    val quarantined: Int = 0,
    val open: Int = 0,
)

/** One finished inspection, as a row in the history list. */
@Serializable
data class HistoryRun(
    val name: String = "",
    val process_definition: String = "",
    val process_name: String = "",
    val run_identifier: String? = null,
    val status: String = "",
    val result: String? = null,
    val score_pct: Double = 0.0,
    val pass_count: Int = 0,
    val fail_count: Int = 0,
    val skip_count: Int = 0,
    val critical_count: Int = 0,
    val answered_count: Int = 0,
    val trace_completeness_pct: Double = 0.0,
    val started_at: String? = null,
    val completed_at: String? = null,
    val photo_count: Int = 0,
    /** First photo of the run — what makes a row of identical serials recognisable. */
    val thumb: String? = null,
    val started_by: String? = null,
    /** How many people worked this pack. Shown when it is more than one. */
    val participant_count: Int = 1,
)

@Serializable
data class ProcessHistory(
    val stats: HistoryStats = HistoryStats(),
    val runs: List<HistoryRun> = emptyList(),
)

@Serializable
data class ReportPhoto(
    val file_url: String = "",
    val caption: String? = null,
    val captured_at: String? = null,
    val captured_by: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy_m: Double? = null,
    val location_source: String? = null,
    val geofence_status: String? = null,
)

/** One answer as it was recorded, with the photos taken at that step. */
@Serializable
data class ReportStep(
    val step_code: String = "",
    val display_no: String? = null,
    val section: String? = null,
    val label: String = "",
    val response_type: String? = null,
    val response: String? = null,
    val value_numeric: Double? = null,
    val value_text: String? = null,
    val unit: String? = null,
    val spec_summary: String? = null,
    val is_pass: Int = 0,
    val is_deviation: Int = 0,
    val is_critical: Int = 0,
    val is_skipped: Int = 0,
    val skip_reason: String? = null,
    val remark: String? = null,
    val answered_at: String? = null,
    val photos: List<ReportPhoto> = emptyList(),
)

@Serializable
data class ReportStage(
    val stage: String = "",
    val label: String = "",
    val steps: List<ReportStep> = emptyList(),
)

/** Photos taken against a step with no answer row — evidence someone looked. */
@Serializable
data class UnmatchedPhotoGroup(
    val step_code: String = "",
    val label: String = "",
    val photos: List<ReportPhoto> = emptyList(),
)

// ------------------------------------------------------------- offline sync
//
// What travels between the handset's local store and `api/process_sync.py`.
// The batch types are what the device *sends*; everything else here is what it
// gets back and treats as authoritative — the server's judgement replaces the
// on-device one on arrival, always.

/** One queued answer, as it goes up. */
@Serializable
data class SyncAnswer(
    val step_code: String,
    val response: String? = null,
    val value: String? = null,
    val remark: String? = null,
    val skipped: Int = 0,
    val skip_reason: String? = null,
    val seconds_spent: Int = 0,
    /**
     * The device's own monotonic counter for this run.
     *
     * Two jobs: it tells the server the order the engineer actually answered
     * in, and it is what the reconcile matches on — an answer corrected while
     * the batch was in flight has a higher sequence and stays queued rather
     * than being marked delivered.
     */
    val client_seq: Long = 0,
    /** `yyyy-MM-dd HH:mm:ss`, from the handset's clock. When the work was done. */
    val answered_at: String? = null,
)

/** One queued scan. `client_uuid` is the dedup key — never the serial. */
@Serializable
data class SyncScan(
    val client_uuid: String,
    val entity_type: String,
    val payload: String,
    val step_code: String? = null,
    val position_index: Int = 0,
    val is_manual_entry: Int = 0,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val scanned_at: String? = null,
)

/** One inspection's worth of queued work. */
@Serializable
data class SyncBatch(
    /** The run's identity, minted when the engineer pressed Start. */
    val client_uuid: String? = null,
    /** Instead of the UUID, for a run that was created online before signal went. */
    val run: String? = null,
    val process: String? = null,
    val identifier: String? = null,
    val started_at: String? = null,
    val station: String? = null,
    val shift: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val answers: List<SyncAnswer> = emptyList(),
    val scans: List<SyncScan> = emptyList(),
    val submit_stages: List<String> = emptyList(),
)

@Serializable
data class SyncAnswerAck(
    val step_code: String = "",
    /** False when the row already said exactly this — a replay, not a new answer. */
    val changed: Boolean = false,
    val is_pass: Int = 0,
    val is_deviation: Int = 0,
    val is_critical: Int = 0,
)

@Serializable
data class SyncRejection(
    val step_code: String = "",
    val stage: String = "",
    val reason: String = "",
    val missing: List<String> = emptyList(),
)

@Serializable
data class SyncScanWarning(
    val client_uuid: String = "",
    val serial_no: String? = null,
    val duplicate_of: String? = null,
    val message: String = "",
)

/** What the server did with each part of the batch. */
@Serializable
data class SyncReport(
    val created: Boolean = false,
    /** The run is finished server-side; the device should stop sending for it. */
    val already_closed: Boolean = false,
    val answers_applied: List<SyncAnswerAck> = emptyList(),
    val answers_rejected: List<SyncRejection> = emptyList(),
    val scans_applied: List<String> = emptyList(),
    val scan_warnings: List<SyncScanWarning> = emptyList(),
    val stages_submitted: List<String> = emptyList(),
    val stages_rejected: List<SyncRejection> = emptyList(),
    val outstanding: ProcessOutstanding = ProcessOutstanding(),
)

/**
 * A synced run: the full server-side state, plus the report.
 *
 * Every field of [ProcessRun] is repeated rather than nested, because the
 * server returns one flat object and the device wants both halves — the run to
 * replace its local copy with, and the report to reconcile its queue against.
 */
@Serializable
data class SyncRunResponse(
    val name: String = "",
    val process_definition: String = "",
    val process_name: String = "",
    val run_identifier: String? = null,
    val status: String = "In Progress",
    val current_stage: String? = null,
    val result: String? = null,
    val score_pct: Double = 0.0,
    val pass_count: Int = 0,
    val fail_count: Int = 0,
    val skip_count: Int = 0,
    val critical_count: Int = 0,
    val answered_count: Int = 0,
    val trace_completeness_pct: Double = 0.0,
    val quarantine_reason: String? = null,
    val started_at: String? = null,
    val completed_at: String? = null,
    val results: List<ProcessResultRow> = emptyList(),
    val scans: List<ProcessScanRow> = emptyList(),
    val photos: List<ProcessPhotoRow> = emptyList(),
    val sync: SyncReport = SyncReport(),
)

/** Settings the on-device evaluator needs to agree with the server. */
@Serializable
data class ProcessEngineSettings(
    val fast_entry_threshold_pct: Double = 25.0,
    val geofence_enabled: Int = 0,
    val geofence_radius_m: Int = 0,
    val plant_latitude: Double? = null,
    val plant_longitude: Double? = null,
)

/** One call that leaves the handset able to work with no network at all. */
@Serializable
data class ProcessBootstrap(
    val processes: List<ProcessSummary> = emptyList(),
    val definitions: List<ProcessDefinition> = emptyList(),
    val settings: ProcessEngineSettings = ProcessEngineSettings(),
    val server_time: String? = null,
)

@Serializable
data class RunReport(
    val name: String = "",
    val process_name: String = "",
    val run_identifier: String? = null,
    val status: String = "",
    val result: String? = null,
    val score_pct: Double = 0.0,
    val pass_count: Int = 0,
    val fail_count: Int = 0,
    val skip_count: Int = 0,
    val critical_count: Int = 0,
    val answered_count: Int = 0,
    val trace_completeness_pct: Double = 0.0,
    val quarantine_reason: String? = null,
    val station: String? = null,
    val started_by: String? = null,
    val started_by_name: String? = null,
    val started_at: String? = null,
    val completed_at: String? = null,
    val photo_count: Int = 0,
    val stages: List<ReportStage> = emptyList(),
    val unmatched_photos: List<UnmatchedPhotoGroup> = emptyList(),
)

/** One option behind a `Link` step, as `process.link_options` returns it. */
@kotlinx.serialization.Serializable
data class LinkOption(
    val value: String,
    val label: String = "",
    val sublabel: String? = null,
    val badge: String? = null,
)
