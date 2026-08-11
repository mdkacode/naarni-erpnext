package com.naarni.service.data.dto

import kotlinx.serialization.Serializable

/**
 * Wire types for duty roster & attendance.
 *
 * The server decides everything that matters — whether the button says Check In
 * or Check Out ([DutyState.next_action]), whether a punch counts as late, and
 * whether it landed inside the depot geofence. The app only reports where the
 * phone thinks it is and renders what comes back, so a policy change ships
 * without an app release.
 */

@Serializable
data class ShiftBrief(
    val name: String,
    val label: String,
    val start_time: String? = null,
    val end_time: String? = null,
    val color: String = "Blue",
)

@Serializable
data class DutyPunchItem(
    val name: String,
    val punch_type: String,
    val punch_time: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val maps_link: String? = null,
    val location_available: Int = 0,
    val distance_from_depot_m: Double? = null,
    val outside_geofence: Int = 0,
    val source: String = "App",
    val note: String? = null,
    val photo: String? = null,
)

@Serializable
data class AttendanceBrief(
    val name: String,
    val status: String,
    val first_check_in: String? = null,
    val last_check_out: String? = null,
    val worked_hours: Double = 0.0,
    val is_late: Int = 0,
    val late_by_minutes: Int = 0,
    val early_exit_minutes: Int = 0,
    val outside_geofence: Int = 0,
    val punch_count: Int = 0,
)

/** The single payload behind the duty card. */
@Serializable
data class DutyState(
    val date: String,
    val can_punch: Boolean = false,
    /** Why the card is unavailable, when [can_punch] is false. */
    val reason: String? = null,
    val depot: String? = null,
    val depot_name: String? = null,
    val shift: ShiftBrief? = null,
    val planned_start: String? = null,
    val planned_end: String? = null,
    val is_rostered: Boolean = false,
    val is_week_off: Boolean = false,
    val remarks: String? = null,
    val is_on_duty: Boolean = false,
    val elapsed_minutes: Int = 0,
    /** "check_in" | "check_out" | "done" | "none" — the authority for the button. */
    val next_action: String = "none",
    val attendance: AttendanceBrief? = null,
    val punches: List<DutyPunchItem> = emptyList(),
    val require_photo: Int = 0,
    val require_location: Int = 0,
    val geofence_mode: String = "Off",
)

@Serializable
data class PunchResult(
    val duty: DutyState,
    val punch: DutyPunchItem? = null,
    /** Advisory findings — no GPS fix, outside the geofence. Shown after the punch, never as a blocker. */
    val warnings: List<String> = emptyList(),
    val duplicate: Boolean = false,
)

@Serializable
data class RosterDay(
    val date: String,
    val shift: ShiftBrief? = null,
    val is_week_off: Int = 0,
    val remarks: String? = null,
    val depot: String? = null,
    val depot_name: String? = null,
    val status: String? = null,
    val worked_hours: Double? = null,
    val is_late: Int = 0,
)

@Serializable
data class MyRoster(
    val from_date: String,
    val to_date: String,
    val days: List<RosterDay> = emptyList(),
)

@Serializable
data class AttendanceDay(
    val name: String,
    val date: String,
    val status: String,
    val shift: String? = null,
    val first_check_in: String? = null,
    val last_check_out: String? = null,
    val worked_hours: Double = 0.0,
    val is_late: Int = 0,
    val late_by_minutes: Int = 0,
    val outside_geofence: Int = 0,
)

@Serializable
data class AttendanceTotals(
    val present_days: Int = 0,
    val late_days: Int = 0,
    val absent_days: Int = 0,
    val total_hours: Double = 0.0,
)

@Serializable
data class MyAttendance(
    val from_date: String,
    val to_date: String,
    val days: List<AttendanceDay> = emptyList(),
    val totals: AttendanceTotals = AttendanceTotals(),
)
