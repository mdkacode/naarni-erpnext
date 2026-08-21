package com.naarni.service.data.dto

import kotlinx.serialization.Serializable

/**
 * Wire shapes for the material gate (`vehicle_maintenance.api.material`).
 *
 * Every field is defaulted. The gate runs on handsets that will not all be on
 * the same build the day the server adds a column, and a missing key must widen
 * a screen's blank spots, never throw.
 */

// ─────────────────────────────────────────────────────────── context

@Serializable
data class GateLocation(
    val name: String,
    val location_code: String = "",
    val location_name: String = "",
    val plant_type: String = "",
    val city: String? = null,
    val gates: List<String> = emptyList(),
)

@Serializable
data class GateParty(
    val party_name: String = "",
    val party_type: String? = null,
)

@Serializable
data class GateItemGroup(
    val name: String,
    val part_group_name: String = "",
)

/**
 * Everything the New-Movement screen needs, in one round trip.
 *
 * Six sequential calls over plant Wi-Fi is the difference between a screen that
 * opens and a screen that spins, so the server assembles this server-side.
 */
@Serializable
data class GateContext(
    val locations: List<GateLocation> = emptyList(),
    val default_location: String? = null,
    val movement_types: List<String> = emptyList(),
    val purposes: Map<String, List<String>> = emptyMap(),
    val party_types: List<String> = emptyList(),
    val reference_types: List<String> = emptyList(),
    val conditions: List<String> = emptyList(),
    val no_photo_reasons: List<String> = emptyList(),
    val uoms: List<String> = emptyList(),
    val item_groups: List<GateItemGroup> = emptyList(),
    val recent_parties: List<GateParty> = emptyList(),
    val can_verify: Boolean = false,
    val can_write: Boolean = false,
)

// ─────────────────────────────────────────────────────────── item picker

/**
 * One row in the item suggest sheet.
 *
 * Extends [SuggestionItem]'s shape with what the row needs once it is *picked* —
 * the unit, the sheet quantity and whether the item carries a QR — so choosing
 * an item fills the whole line without a second call.
 */
@Serializable
data class GateItemSuggestion(
    val value: String,
    val label: String = "",
    val sublabel: String? = null,
    val badge: String? = null,
    val recent: Boolean = false,
    val uom: String = "Nos",
    val qty_per_bus: Double = 0.0,
    val has_qr: Int = 0,
    val item_group: String? = null,
    /** 1 when `create_item` inserted a new catalogue row; 0 when it matched an existing one. */
    val created: Int = 0,
) {
    fun toSuggestion(): SuggestionItem =
        SuggestionItem(value = value, label = label, sublabel = sublabel, badge = badge, recent = recent)
}

// ─────────────────────────────────────────────────────────── movement

@Serializable
data class MovementItem(
    val row_uuid: String = "",
    val item: String = "",
    val item_name: String = "",
    val item_group: String? = null,
    val qty: Double = 0.0,
    val uom: String = "",
    val expected_qty: Double = 0.0,
    val condition: String = "OK",
    val has_qr: Int = 0,
    val qr_code: String? = null,
    val qr_source: String? = null,
    val batch_no: String? = null,
    val mfg_date: String? = null,
    /** The bus this part is going into, as answered at the gate. */
    val chassis_no: String? = null,
    val photo_count: Int = 0,
    val no_photo_reason: String? = null,
    val is_new_item: Int = 0,
    val remarks: String? = null,
) {
    val needsSerial: Boolean get() = has_qr == 1 && qr_code.isNullOrBlank()
    val needsPhoto: Boolean get() = photo_count == 0 && no_photo_reason.isNullOrBlank()
}

@Serializable
data class MovementPhoto(
    val client_uuid: String = "",
    val file_url: String = "",
    val kind: String = "Item",
    val item_row: String? = null,
    val caption: String? = null,
    val captured_at: String? = null,
    val captured_by: String? = null,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
)

@Serializable
data class Movement(
    val name: String = "",
    val movement_type: String = "Inward",
    val location: String = "",
    val location_name: String? = null,
    val gate: String? = null,
    val status: String = "Draft",
    val purpose: String? = null,
    val party_type: String? = null,
    val party_name: String? = null,
    val reference_type: String? = null,
    val reference_no: String? = null,
    val reference_date: String? = null,
    val transport_vehicle_no: String? = null,
    val driver_name: String? = null,
    val driver_phone: String? = null,
    val vehicle: String? = null,
    val job_card: String? = null,
    val total_items: Int = 0,
    val total_qty: Double = 0.0,
    val photo_count: Int = 0,
    val qr_count: Int = 0,
    val damaged_count: Int = 0,
    val new_item_count: Int = 0,
    val evidence_pct: Double = 0.0,
    val started_by: String? = null,
    val started_at: String? = null,
    val submitted_by: String? = null,
    val submitted_at: String? = null,
    val verified_by: String? = null,
    val verified_at: String? = null,
    val geofence_status: String? = null,
    val remarks: String? = null,
    val rejection_reason: String? = null,
    val can_verify: Boolean = false,
    val can_edit: Boolean = false,
    val items: List<MovementItem> = emptyList(),
    val photos: List<MovementPhoto> = emptyList(),
) {
    val isInward: Boolean get() = movement_type == "Inward"

    /** Item rows still missing a photo and without a stated reason. */
    fun rowsWithoutEvidence(): Int = items.count { it.needsPhoto }
}

/** One row in the Material list — the header fields only. */
@Serializable
data class MovementSummary(
    val name: String,
    val movement_type: String = "Inward",
    val location: String = "",
    val status: String = "Draft",
    val purpose: String? = null,
    val party_name: String? = null,
    val reference_no: String? = null,
    val transport_vehicle_no: String? = null,
    val total_items: Int = 0,
    val total_qty: Double = 0.0,
    val photo_count: Int = 0,
    val qr_count: Int = 0,
    val damaged_count: Int = 0,
    val evidence_pct: Double = 0.0,
    val started_by: String? = null,
    val started_at: String? = null,
    val submitted_at: String? = null,
    val verified_at: String? = null,
)

@Serializable
data class MovementPage(
    val movements: List<MovementSummary> = emptyList(),
    val has_more: Boolean = false,
    val awaiting_count: Int = 0,
    val can_verify: Boolean = false,
)

/** An advisory note on a saved line — a duplicate serial, a quantity that differs. */
@Serializable
data class GateWarning(val code: String = "", val message: String = "")

@Serializable
data class SaveItemResult(
    val item: MovementItem = MovementItem(),
    val movement: Movement = Movement(),
    val warnings: List<GateWarning> = emptyList(),
)

// ─────────────────────────────────────────────────────────── trace

@Serializable
data class SerialTraceRow(
    val movement: String = "",
    val movement_type: String = "",
    val location: String = "",
    val status: String = "",
    val party_name: String? = null,
    val at: String? = null,
    val item_name: String = "",
    val qty: Double = 0.0,
    val uom: String = "",
    val condition: String = "",
)

@Serializable
data class SerialTrace(
    val serial: String = "",
    val movements: List<SerialTraceRow> = emptyList(),
)
