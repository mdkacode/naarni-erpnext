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
    val is_test_run: Int = 0,
    val results: List<ProcessResultRow> = emptyList(),
    val scans: List<ProcessScanRow> = emptyList(),
    val photos: List<ProcessPhotoRow> = emptyList(),
    val signoffs: List<ProcessSignoffRow> = emptyList(),
)

/** Live totals returned alongside a saved answer, so the runner updates without a refetch. */
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
)
