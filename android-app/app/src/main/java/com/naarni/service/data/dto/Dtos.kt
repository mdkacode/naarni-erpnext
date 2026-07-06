package com.naarni.service.data.dto

import kotlinx.serialization.Serializable

/** Result of an OTP-send request. */
@Serializable
data class OtpStatus(val sent: Boolean = false)

/** Result of phone login. Auth itself rides on the session cookie; this is display data. */
@Serializable
data class LoginData(
    val user: String? = null,
    val full_name: String? = null,
    val roles: List<String> = emptyList(),
)

/** The depots a Service Engineer is assigned to (from `get_my_depots`). */
@Serializable
data class MyDepots(
    val user: String? = null,
    val depots: List<DepotHit> = emptyList(),
)

/** A user holding a given role (from `list_users_by_role`) — for POC pickers. */
@Serializable
data class RoleUser(
    val user: String,
    val full_name: String? = null,
)

/** A Subsystem master row (from `list_subsystems`) — for the multiselect. */
@Serializable
data class SubsystemItem(
    val name: String,
    val subsystem_name: String? = null,
    val category: String? = null,
)

/** One component on a PMS inspection check sheet (from `get_inspection_sheet`). */
@Serializable
data class InspectionCheckItem(
    val id: String,
    val label: String,
    val category: String,
    val input_type: String,            // "three_tier" | "measurement" | "text"
    val unit: String? = null,
    val min: Double? = null,
    val max: Double? = null,
)

/** A PMS inspection check sheet (A/B/C/D) with its component grid. */
@Serializable
data class InspectionSheet(
    val sheet_id: String,
    val sheet_label: String,
    val items: List<InspectionCheckItem> = emptyList(),
)

/**
 * Everything the Job Card create form needs, pre-filled — from
 * `get_job_card_form_context`. The user types nothing else.
 */
@Serializable
data class FormContext(
    val vehicle: String? = null,
    val vehicle_number: String? = null,
    val make_model: String? = null,
    val oem: String? = null,
    val customer: String? = null,
    val customer_name: String? = null,
    val customer_phone: String? = null,
    val depot: String? = null,
    val depot_name: String? = null,
    val service_contract: String? = null,
    val service_type: String? = null,
    val priority: String? = null,
    val check_sheet: String? = null,
    val pms_tolerance_level: String? = null,
    val odometer_estimate: Int? = null,
    val last_pms_date: String? = null,
    val last_pms_odometer: Int? = null,
    val last_serviced_by: String? = null,
    val last_service_tolerance_level: String? = null,
    val suggested_complaints: List<SuggestionItem> = emptyList(),
    val suggested_subsystems: List<SuggestionItem> = emptyList(),
)

/** A row for the SmartSelect dropdown (search results + suggestions). */
@Serializable
data class SuggestionItem(
    val value: String,
    val label: String,
    val sublabel: String? = null,
    val badge: String? = null,
    val recent: Boolean = false,
)

@Serializable
data class VehicleHit(
    val name: String,
    val registration_number: String? = null,
    val make_model: String? = null,
    val customer: String? = null,
    val operator: String? = null,
    val depot: String? = null,
)

/** A vehicle row in the Service Engineer's fleet list (synced from Naarni). */
@Serializable
data class FleetVehicle(
    val name: String,
    val registration_number: String? = null,
    val make_model: String? = null,
    val operator: String? = null,
    val depot: String? = null,
    val vehicle_status: String? = null,
    val naarni_vehicle_id: String? = null,
    val last_synced_at: String? = null,
)

@Serializable
data class FleetResponse(
    val vehicles: List<FleetVehicle> = emptyList(),
    val total: Int = 0,
)

/** Live Naarni telemetry for one vehicle (timestamps already in IST). */
@Serializable
data class VehicleLive(
    val registration_number: String? = null,
    val operator: String? = null,
    val make: String? = null,
    val model: String? = null,
    val route_name: String? = null,
    val activity: String? = null,
    val connectivity_status: String? = null,
    val odometer: Int? = null,
    val distance_to_empty: Double? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val ground_speed_kmph: Double? = null,
    val maps_link: String? = null,
    val battery_soc: Double? = null,
    val battery_soh: Double? = null,
    val battery_voltage: Double? = null,
    val battery_current: Double? = null,
    val battery_coolant_temp: Double? = null,
    val motor_rpm: Double? = null,
    val motor_temp: Double? = null,
    val charger_current: Double? = null,
    val charger_voltage: Double? = null,
    val gun_thermal_status: String? = null,
    val pack_thermal_status: String? = null,
    val ac_status: String? = null,
    val telemetry_at_ist: String? = null,
)

@Serializable
data class JobCardListItem(
    val name: String,
    val job_card_type: String? = null,
    val workflow_state: String? = null,
    val vehicle_number: String? = null,
    val vehicle_make_model: String? = null,
    val operator: String? = null,
    val customer_name: String? = null,
    val job_card_date: String? = null,
    val odometer_reading: Int? = null,
    val priority: String? = null,
    val force_close_severity: String? = null,
    val sla_breached: Int? = null,
    val creation: String? = null,
)

/** Full job-card detail for the SE detail/edit screen (from get_job_card_summary). */
@Serializable
data class JobCardDetail(
    val name: String,
    val vehicle_number: String? = null,
    val vehicle_make_model: String? = null,
    val customer_name: String? = null,
    val job_card_type: String? = null,
    val service_type: String? = null,
    val priority: String? = null,
    val workflow_state: String? = null,
    val complaint_description: String? = null,
    val se_observations: String? = null,
    val odometer_reading: Int? = null,
    val depot: String? = null,
    val opened_at: String? = null,
    val closed_at: String? = null,
    val estimated_cost: Double? = null,
    val actual_cost: Double? = null,
    val pre_pms_score: Double? = null,
    val post_pms_score: Double? = null,
    val score_improvement: Double? = null,
    val sla_breached: Int? = null,
    val force_closed: Int? = null,
    val force_close_severity: String? = null,
    val force_close_reason: String? = null,
    val requires_customer_approval: Int? = null,
    val health_card: String? = null,
    val send_report_to_customer: Int? = null,
    val followup_job_card: String? = null,
    val source_force_close_job_card: String? = null,
    val viewer_roles: List<String> = emptyList(),
    val viewer_is_depot_manager: Boolean = false,
    val assigned_service_engineer: String? = null,
    val assigned_technician: String? = null,
    val subsystems: List<String> = emptyList(),
    val repair_items: List<RepairItem> = emptyList(),
    val maintenance_items: List<MaintenanceItem> = emptyList(),
    val category_scores: List<CategoryScore> = emptyList(),
    val software_components: List<SoftwareComponent> = emptyList(),
    val inventory_requests: List<InventoryRequestItem> = emptyList(),
    val breakdown: BreakdownInfo? = null,
    val available_actions: List<String> = emptyList(),
)

@Serializable
data class RepairItem(
    val part_group: String? = null,
    val bus_system: String? = null,
    val description: String? = null,
    val activity_type: String? = null,
    val component_status: String? = null,
    val qty: Double? = null,
    val estimated_amount: Double? = null,
    val actual_amount: Double? = null,
    val item_status: String? = null,
    val pre_repair_photo: String? = null,
    val post_repair_photo: String? = null,
)

@Serializable
data class MaintenanceItem(
    val maintenance_type: String? = null,
    val description: String? = null,
    val action: String? = null,
    val qty: Double? = null,
    val unit: String? = null,
    val estimated_amount: Double? = null,
    val actual_amount: Double? = null,
    val item_status: String? = null,
    val pre_photo: String? = null,
    val post_photo: String? = null,
)

@Serializable
data class CategoryScore(
    val category: String? = null,
    val component_count: Int? = null,
    val pre_pms_score: Double? = null,
    val post_pms_score: Double? = null,
    val improvement: Double? = null,
)

@Serializable
data class SoftwareComponent(
    val component: String? = null,
    val reason: String? = null,
    val status: String? = null,
    val retry_count: Int? = null,
    val pre_version: String? = null,
    val post_version: String? = null,
    val calibration_values: String? = null,
    val failure_notes: String? = null,
    val pre_version_photo: String? = null,
    val post_version_photo: String? = null,
    val calibration_photo: String? = null,
)

@Serializable
data class CustomerFeedbackData(
    val name: String? = null,
    val rating: Int? = null,
    val nps_score: Int? = null,
    val comments: String? = null,
    val would_recommend: String? = null,
    val submitted_at: String? = null,
    val submitted_by: String? = null,
)

@Serializable
data class ShareReport(
    val pdf_url: String? = null,
    val whatsapp_link: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val message: String? = null,
)

@Serializable
data class CustomerHit(
    val name: String,
    val customer_name: String? = null,
    val mobile_no: String? = null,
)

@Serializable
data class DepotHit(
    val name: String,
    val depot_name: String? = null,
    val city: String? = null,
    val state: String? = null,
)

@Serializable
data class BusImage(
    val angle: String,
    val image: String? = null,
    val is_primary: Int = 0,
    val captured_by: String? = null,
    val captured_at: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val notes: String? = null,
)

@Serializable
data class PartGroupItem(
    val name: String,
    val part_group_name: String? = null,
    val bus_system: String? = null,
)

@Serializable
data class PartItem(
    val name: String,
    val part_name: String? = null,
    val part_group: String? = null,
    val uom: String? = null,
)

@Serializable
data class InventoryRequestItem(
    val name: String,
    val part: String? = null,
    val part_name: String? = null,
    val part_group: String? = null,
    val quantity: Double? = null,
    val urgency_level: String? = null,
    val status: String? = null,
    val requested_by: String? = null,
)

@Serializable
data class BreakdownInfo(
    val incident_place: String? = null,
    val fault_code_1: String? = null,
    val fault_code_2: String? = null,
    val fault_code_3: String? = null,
    val remote_resolution_status: String? = null,
    val travel_started_at: String? = null,
    val arrived_at_location: String? = null,
    val travel_duration_minutes: Double? = null,
    val fix_type: String? = null,
    val recurrence_risk: String? = null,
    val occurrence_risk: String? = null,
    val trial_trip_distance_km: Double? = null,
    val trial_trip_duration_minutes: Double? = null,
    val total_downtime_minutes: Double? = null,
    val rca_notes: String? = null,
    val groups_impacted: List<String> = emptyList(),
)

@Serializable
data class NotificationItem(
    val name: String,
    val subject: String? = null,
    val body: String? = null,
    val job_card: String? = null,
    val read: Boolean = false,
    val creation: String? = null,
)

@Serializable
data class UnreadCount(val unread: Int = 0)

@Serializable
data class CreatedJobCard(
    val name: String,
    val workflow_state: String? = null,
)

/** Frappe's upload_file returns the File doc directly under `message`. */
@Serializable
data class FileUploadData(
    val name: String? = null,
    val file_url: String? = null,
)

@Serializable
data class TicketItem(
    val name: String,
    val title: String? = null,
    val status: String? = null,
    val severity: String? = null,
    val registration_number: String? = null,
    val depot: String? = null,
    val vehicle: String? = null,
    val job_card: String? = null,
    val creation: String? = null,
    val deeplink: String? = null,
)

@Serializable
data class AlertEventItem(
    val name: String,
    val title: String? = null,
    val severity: String? = null,
    val status: String? = null,
    val registration_number: String? = null,
    val parameter: String? = null,
    val value: Double? = null,
    val unit: String? = null,
    val threshold: Double? = null,
    val message: String? = null,
    val occurred_at: String? = null,
    val vehicle: String? = null,
    val job_card: String? = null,
)

/** Full Alert Event detail (get_alert_event) — powers the detailed Alert page. */
@Serializable
data class AlertDetail(
    val name: String,
    val title: String? = null,
    val alert_type: String? = null,
    val severity: String? = null,
    val status: String? = null,
    val registration_number: String? = null,
    val vehicle: String? = null,
    val customer: String? = null,
    val channel: String? = null,
    val parameter: String? = null,
    val op: String? = null,
    val value: Double? = null,
    val value_text: String? = null,
    val value_meaning: String? = null,
    val unit: String? = null,
    val threshold: Double? = null,
    val match_value: String? = null,
    val message: String? = null,
    val details: String? = null,
    val condition_text: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val maps_link: String? = null,
    val occurred_at: String? = null,
    val triggered_at: String? = null,
    val job_card: String? = null,
    /** Linked Service Ticket name, if one was raised from this alert. */
    val ticket: String? = null,
)

/** Full Service Ticket detail (get_ticket) — the ticket screen behind an alert. */
@Serializable
data class TicketDetail(
    val name: String,
    val title: String? = null,
    val status: String? = null,
    val severity: String? = null,
    val source: String? = null,
    val registration_number: String? = null,
    val vehicle: String? = null,
    val depot: String? = null,
    val assigned_to: String? = null,
    val message: String? = null,
    val alert_event: String? = null,
    val job_card: String? = null,
    val deeplink: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val maps_link: String? = null,
    val operator: String? = null,
    val make_model: String? = null,
    val route_name: String? = null,
    val odometer: Double? = null,
    val activity: String? = null,
    val connectivity_status: String? = null,
    val battery_soc: Double? = null,
    val telemetry_at: String? = null,
    val acknowledged_at: String? = null,
    val resolved_at: String? = null,
    val resolution_reason: String? = null,
    val creation: String? = null,
)
