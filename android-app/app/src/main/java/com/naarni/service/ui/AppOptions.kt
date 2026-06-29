package com.naarni.service.ui

/**
 * Dropdown option lists used across the app. The DEFAULTS below are the offline
 * fallback; at runtime they are overridden by `get_app_field_options` (sourced
 * from Frappe metadata, editable by admins via Customize Form in Desk).
 *
 * Read options through [AppViewModel.opt] so a screen always gets the live list
 * when online and the bundled default otherwise.
 */
object AppOptions {
    val DEFAULTS: Map<String, List<String>> = mapOf(
        "job_card_type" to listOf("PMS + Repair", "Only Repair", "Software Update", "Breakdown"),
        "priority" to listOf("Low", "Medium", "High", "Urgent"),
        "activity_type" to listOf("Only Repair", "Spare Replacement", "Both"),
        "component_status" to listOf("Good", "Repair/Replace Recommended", "Repair/Replace Immediately"),
        "repair_item_status" to listOf("Pending", "In Progress", "Completed"),
        "maintenance_type" to listOf("Oil/Lubricant", "Coolant", "Grease", "Filter", "Consumable"),
        "maintenance_action" to listOf("Top-up", "Replacement", "Cleaning"),
        "maintenance_unit" to listOf("Litres", "Kg", "Pcs", "Set"),
        "maintenance_item_status" to listOf("Pending", "In Progress", "Completed"),
        "software_reason" to listOf("Performance Improvement", "Regular Update", "Emergency Update (Bug Fix)"),
        "software_status" to listOf("Pending", "In Progress", "Success", "Failed"),
        "urgency" to listOf("Low", "Medium", "High", "Critical"),
        "severity" to listOf("Minor", "Major", "Critical"),
        "incident_place" to listOf("Depot", "En Route"),
        "remote_status" to listOf("In Progress", "Resolved", "Failed"),
        "fix_type" to listOf("Permanent", "Temporary", "Force Closed"),
        "risk" to listOf("Low", "High"),
        "angle" to listOf("Master", "Front", "Rear", "Left", "Right", "Engine", "Interior", "Odometer", "Damage"),
    )
}
