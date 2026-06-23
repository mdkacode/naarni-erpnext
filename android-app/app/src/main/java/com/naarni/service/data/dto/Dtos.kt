package com.naarni.service.data.dto

import kotlinx.serialization.Serializable

/** Result of phone login. Auth itself rides on the session cookie; this is display data. */
@Serializable
data class LoginData(
    val user: String? = null,
    val full_name: String? = null,
    val roles: List<String> = emptyList(),
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
)

@Serializable
data class JobCardListItem(
    val name: String,
    val job_card_type: String? = null,
    val workflow_state: String? = null,
    val vehicle_number: String? = null,
    val priority: String? = null,
    val sla_breached: Int? = null,
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
