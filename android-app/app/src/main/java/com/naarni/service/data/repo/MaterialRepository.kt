package com.naarni.service.data.repo

import com.naarni.service.core.network.FrappeApi
import com.naarni.service.core.network.payload
import com.naarni.service.data.dto.GateContext
import com.naarni.service.data.dto.GateItemSuggestion
import com.naarni.service.data.dto.Movement
import com.naarni.service.data.dto.MovementPage
import com.naarni.service.data.dto.SaveItemResult
import com.naarni.service.data.dto.SerialTrace
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.UUID

/**
 * The app's whole material-gate surface.
 *
 * Two things it does that a thin Retrofit wrapper would not:
 *
 * *   **It owns the idempotency keys.** Callers ask for a movement or a line;
 *     this class decides what `client_uuid` / `row_uuid` goes on the wire. A key
 *     invented at the call site is a key that changes on retry, which defeats
 *     the whole mechanism.
 * *   **It caches the gate context.** Locations, purposes and picklists change
 *     about once a quarter; refetching them every time the New-Movement screen
 *     opens is a spinner in front of a clerk who is standing next to a truck.
 */
class MaterialRepository(private val api: FrappeApi) {

    private var contextCache: GateContext? = null

    /** Locations, picklists and permissions. Cached until [refresh]. */
    suspend fun context(refresh: Boolean = false): GateContext {
        if (!refresh) contextCache?.let { return it }
        return api.getGateContext().payload().also { contextCache = it }
    }

    // ── item picker ──

    /** The suggest dropdown. A blank [txt] returns recents topped up from the catalogue. */
    suspend fun searchItems(txt: String = "", group: String = ""): List<GateItemSuggestion> =
        api.searchGateItems(txt = txt, group = group).payload()

    /**
     * Create a catalogue item from the gate.
     *
     * The returned row carries `created = 0` when the server matched an existing
     * item instead — the near-duplicate guard — so the caller can say "we already
     * have this one" rather than implying it added something.
     */
    suspend fun createItem(
        itemName: String,
        itemGroup: String = "",
        uom: String = "Nos",
        hasQr: Boolean = false,
    ): GateItemSuggestion =
        api.createGateItem(itemName, itemGroup, uom, if (hasQr) 1 else 0).payload()

    // ── lifecycle ──

    /**
     * Open a movement.
     *
     * [clientUuid] must be minted once per attempt and reused across retries —
     * the caller holds it in saved state so a rotation or a process death does
     * not open a second gate note for the same truck.
     */
    suspend fun start(
        movementType: String,
        location: String,
        clientUuid: String,
        gate: String = "",
        purpose: String = "",
        partyType: String = "",
        partyName: String = "",
        referenceType: String = "",
        referenceNo: String = "",
        referenceDate: String = "",
        truckNo: String = "",
        driverName: String = "",
        driverPhone: String = "",
        latitude: Double? = null,
        longitude: Double? = null,
    ): Movement = api.startMovement(
        movementType = movementType,
        location = location,
        clientUuid = clientUuid,
        gate = gate,
        purpose = purpose,
        partyType = partyType,
        partyName = partyName,
        referenceType = referenceType,
        referenceNo = referenceNo,
        referenceDate = referenceDate,
        truckNo = truckNo,
        driverName = driverName,
        driverPhone = driverPhone,
        latitude = latitude,
        longitude = longitude,
    ).payload()

    suspend fun updateHeader(movement: String, updates: Map<String, String>): Movement {
        val json = buildJsonObject {
            updates.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
        }
        return api.updateMovementHeader(movement, json.toString()).payload()
    }

    /** Upsert one line. Pass the row's existing `row_uuid` to edit it, or [newRowId] to add. */
    suspend fun saveItem(
        movement: String,
        rowUuid: String,
        item: String,
        qty: Double,
        uom: String = "",
        condition: String = "OK",
        qrCode: String = "",
        qrSource: String = "",
        batchNo: String = "",
        noPhotoReason: String = "",
        remarks: String = "",
        isNewItem: Boolean = false,
    ): SaveItemResult = api.saveMovementItem(
        movement = movement,
        rowUuid = rowUuid,
        item = item,
        qty = qty,
        uom = uom,
        condition = condition,
        qrCode = qrCode,
        qrSource = qrSource,
        batchNo = batchNo,
        noPhotoReason = noPhotoReason,
        remarks = remarks,
        isNewItem = if (isNewItem) 1 else 0,
    ).payload()

    suspend fun deleteItem(movement: String, rowUuid: String): Movement =
        api.deleteMovementItem(movement, rowUuid).payload()

    /**
     * Upload a stamped photo and register it against a line.
     *
     * The coordinates are burnt into the pixels by `PhotoStamper` at capture
     * time; they are sent again because only a stored latitude/longitude can be
     * queried or geofenced. A photo whose location exists solely as painted text
     * is evidence a human can read and a report cannot.
     *
     * Uploaded private: a gate photo carries a serial, a location and a person's
     * name, and none of that belongs on a public URL.
     */
    suspend fun uploadItemPhoto(
        movement: String,
        rowUuid: String,
        file: File,
        kind: String = "Item",
        caption: String = "",
        capturedAt: String = "",
        latitude: Double? = null,
        longitude: Double? = null,
        accuracyM: Double? = null,
    ): Movement {
        val part = MultipartBody.Part.createFormData(
            "file", file.name, file.asRequestBody("image/jpeg".toMediaType()),
        )
        fun text(v: String) = v.toRequestBody("text/plain".toMediaType())
        // `upload_file` is Frappe's own endpoint and returns its payload in
        // `message`, not the app's `{success, data}` envelope.
        val fileUrl = api.uploadFile(
            part, text("Material Movement"), text(movement), text("1"),
        ).message?.file_url ?: error("Photo upload failed")

        return api.attachMovementPhoto(
            movement = movement,
            fileUrl = fileUrl,
            kind = kind,
            itemRow = rowUuid,
            caption = caption,
            clientUuid = newRowId(),
            capturedAt = capturedAt,
            latitude = latitude,
            longitude = longitude,
            accuracyM = accuracyM,
        ).payload()
    }

    suspend fun deletePhoto(movement: String, clientUuid: String): Movement =
        api.deleteMovementPhoto(movement, clientUuid).payload()

    suspend fun submit(movement: String, remarks: String = ""): Movement =
        api.submitMovement(movement, remarks).payload()

    suspend fun verify(movement: String, remarks: String = ""): Movement =
        api.verifyMovement(movement, remarks).payload()

    suspend fun reject(movement: String, reason: String): Movement =
        api.rejectMovement(movement, reason).payload()

    suspend fun cancel(movement: String, reason: String): Movement =
        api.cancelMovement(movement, reason).payload()

    // ── read ──

    suspend fun get(name: String): Movement = api.getMovement(name).payload()

    suspend fun list(scope: String = "open", location: String = "", offset: Int = 0): MovementPage =
        api.myMovements(scope = scope, location = location, offset = offset).payload()

    suspend fun trace(serial: String): SerialTrace = api.serialTrace(serial).payload()

    companion object {
        /** A fresh idempotency key for a movement or a line. */
        fun newRowId(): String = UUID.randomUUID().toString()
    }
}
