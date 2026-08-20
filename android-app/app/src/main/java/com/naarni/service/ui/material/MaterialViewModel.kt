package com.naarni.service.ui.material

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.naarni.service.App
import com.naarni.service.data.dto.GateContext
import com.naarni.service.data.dto.GateItemSuggestion
import com.naarni.service.data.dto.GateWarning
import com.naarni.service.data.dto.Movement
import com.naarni.service.data.dto.MovementSummary
import com.naarni.service.data.repo.MaterialRepository
import kotlinx.coroutines.launch
import java.io.File

/** What the New-Movement wizard has collected so far. */
data class GateDraft(
    val movementType: String = "Inward",
    val location: String = "",
    val gate: String = "",
    val purpose: String = "",
    val partyType: String = "",
    val partyName: String = "",
    val referenceType: String = "",
    val referenceNo: String = "",
    val truckNo: String = "",
    val driverName: String = "",
    val driverPhone: String = "",
    /**
     * Minted once when the wizard opens and reused for every retry.
     *
     * A key generated at send time changes on the second attempt, which is
     * exactly when it needs not to — a timed-out create followed by a retry
     * would open a second gate note for the same truck.
     */
    val clientUuid: String = MaterialRepository.newRowId(),
)

data class MaterialUiState(
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val context: GateContext? = null,
    val scope: String = "open",
    val movements: List<MovementSummary> = emptyList(),
    val awaitingCount: Int = 0,
    val canVerify: Boolean = false,
    val current: Movement? = null,
    /** Advisory notes on the line just saved, keyed by row. Cleared as rows are edited. */
    val warnings: Map<String, List<GateWarning>> = emptyMap(),
)

/**
 * State for the material gate.
 *
 * Deliberately a single view model shared by the list, the wizard and the items
 * screen: they are one task, and splitting them would mean re-fetching the
 * movement on every navigation — a spinner between "add item" and the list of
 * items already added, which is the screen a clerk lives in.
 *
 * Errors are surfaced, never swallowed. A gate clerk who thinks a line saved
 * when it did not is worse off than one who is told it failed.
 */
class MaterialViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as App).container.materialRepo

    var ui by mutableStateOf(MaterialUiState())
        private set

    var draft by mutableStateOf(GateDraft())
        private set

    // ── context & list ──

    fun load(scope: String = ui.scope, refreshContext: Boolean = false) {
        viewModelScope.launch {
            ui = ui.copy(loading = true, error = null, scope = scope)
            runCatching {
                val context = repo.context(refresh = refreshContext)
                val page = repo.list(scope = scope)
                context to page
            }.onSuccess { (context, page) ->
                ui = ui.copy(
                    loading = false,
                    context = context,
                    movements = page.movements,
                    awaitingCount = page.awaiting_count,
                    canVerify = page.can_verify || context.can_verify,
                )
            }.onFailure { ui = ui.copy(loading = false, error = it.readable()) }
        }
    }

    // ── the wizard ──

    /** Reset the wizard, pre-filling the location the operator last worked at. */
    fun newDraft(movementType: String) {
        draft = GateDraft(
            movementType = movementType,
            location = ui.context?.default_location.orEmpty(),
        )
    }

    fun editDraft(block: (GateDraft) -> GateDraft) {
        draft = block(draft)
    }

    /** Purposes valid for the type being recorded — never the other type's list. */
    fun purposesForDraft(): List<String> =
        ui.context?.purposes?.get(draft.movementType).orEmpty()

    fun startMovement(latitude: Double?, longitude: Double?, onOpened: (String) -> Unit) {
        val d = draft
        if (d.location.isBlank()) {
            ui = ui.copy(error = "Pick a location first.")
            return
        }
        viewModelScope.launch {
            ui = ui.copy(saving = true, error = null)
            runCatching {
                repo.start(
                    movementType = d.movementType,
                    location = d.location,
                    clientUuid = d.clientUuid,
                    gate = d.gate,
                    purpose = d.purpose,
                    partyType = d.partyType,
                    partyName = d.partyName,
                    referenceType = d.referenceType,
                    referenceNo = d.referenceNo,
                    truckNo = d.truckNo,
                    driverName = d.driverName,
                    driverPhone = d.driverPhone,
                    latitude = latitude,
                    longitude = longitude,
                )
            }.onSuccess {
                ui = ui.copy(saving = false, current = it)
                onOpened(it.name)
            }.onFailure { ui = ui.copy(saving = false, error = it.readable()) }
        }
    }

    // ── one movement ──

    fun open(name: String) {
        viewModelScope.launch {
            ui = ui.copy(loading = true, error = null)
            runCatching { repo.get(name) }
                .onSuccess { ui = ui.copy(loading = false, current = it) }
                .onFailure { ui = ui.copy(loading = false, error = it.readable()) }
        }
    }

    suspend fun searchItems(txt: String, group: String = ""): List<GateItemSuggestion> =
        runCatching { repo.searchItems(txt, group) }.getOrDefault(emptyList())

    /**
     * Add a catalogue item from the gate.
     *
     * A `created = 0` answer means the server matched an existing item rather
     * than adding one, so the notice says so — telling a clerk we created
     * something we did not is how the same part gets added a third time.
     */
    fun createItem(
        name: String,
        group: String,
        uom: String,
        hasQr: Boolean,
        onCreated: (GateItemSuggestion) -> Unit,
    ) {
        viewModelScope.launch {
            ui = ui.copy(saving = true, error = null)
            runCatching { repo.createItem(name, group, uom, hasQr) }
                .onSuccess { item ->
                    ui = ui.copy(
                        saving = false,
                        notice = if (item.created == 1) {
                            "“${item.label}” added to the catalogue."
                        } else {
                            "We already have this one — using “${item.label}”."
                        },
                    )
                    onCreated(item)
                }
                .onFailure { ui = ui.copy(saving = false, error = it.readable()) }
        }
    }

    fun saveItem(
        rowUuid: String,
        item: String,
        qty: Double,
        uom: String,
        condition: String = "OK",
        qrCode: String = "",
        qrSource: String = "",
        noPhotoReason: String = "",
        remarks: String = "",
        isNewItem: Boolean = false,
        onSaved: (String) -> Unit = {},
    ) {
        val movement = ui.current?.name ?: return
        viewModelScope.launch {
            ui = ui.copy(saving = true, error = null)
            runCatching {
                repo.saveItem(
                    movement = movement,
                    rowUuid = rowUuid,
                    item = item,
                    qty = qty,
                    uom = uom,
                    condition = condition,
                    qrCode = qrCode,
                    qrSource = qrSource,
                    noPhotoReason = noPhotoReason,
                    remarks = remarks,
                    isNewItem = isNewItem,
                )
            }.onSuccess { result ->
                // The save returns the header only; the full document is refetched
                // so the items list and every rollup come from one source rather
                // than being patched together on the client and drifting.
                ui = ui.copy(
                    saving = false,
                    warnings = ui.warnings + (rowUuid to result.warnings),
                )
                refreshCurrent()
                onSaved(rowUuid)
            }.onFailure { ui = ui.copy(saving = false, error = it.readable()) }
        }
    }

    fun deleteItem(rowUuid: String) {
        val movement = ui.current?.name ?: return
        viewModelScope.launch {
            ui = ui.copy(saving = true, error = null)
            runCatching { repo.deleteItem(movement, rowUuid) }
                .onSuccess {
                    ui = ui.copy(saving = false, current = it, warnings = ui.warnings - rowUuid)
                }
                .onFailure { ui = ui.copy(saving = false, error = it.readable()) }
        }
    }

    fun attachPhoto(
        rowUuid: String,
        file: File,
        latitude: Double?,
        longitude: Double?,
        kind: String = "Item",
    ) {
        val movement = ui.current?.name ?: return
        viewModelScope.launch {
            ui = ui.copy(saving = true, error = null)
            runCatching {
                repo.uploadItemPhoto(
                    movement = movement,
                    rowUuid = rowUuid,
                    file = file,
                    kind = kind,
                    latitude = latitude,
                    longitude = longitude,
                )
            }.onSuccess { ui = ui.copy(saving = false, current = it) }
                .onFailure { ui = ui.copy(saving = false, error = it.readable()) }
        }
    }

    fun submit(remarks: String = "", onDone: () -> Unit) = act(onDone) { repo.submit(it, remarks) }

    fun verify(remarks: String = "", onDone: () -> Unit) = act(onDone) { repo.verify(it, remarks) }

    fun reject(reason: String, onDone: () -> Unit) = act(onDone) { repo.reject(it, reason) }

    fun cancel(reason: String, onDone: () -> Unit) = act(onDone) { repo.cancel(it, reason) }

    private fun act(onDone: () -> Unit, block: suspend (String) -> Movement) {
        val movement = ui.current?.name ?: return
        viewModelScope.launch {
            ui = ui.copy(saving = true, error = null)
            runCatching { block(movement) }
                .onSuccess {
                    ui = ui.copy(saving = false, current = it)
                    onDone()
                }
                .onFailure { ui = ui.copy(saving = false, error = it.readable()) }
        }
    }

    private suspend fun refreshCurrent() {
        val movement = ui.current?.name ?: return
        runCatching { repo.get(movement) }.onSuccess { ui = ui.copy(current = it) }
    }

    fun clearMessages() {
        ui = ui.copy(error = null, notice = null)
    }
}

/** Server messages are written for the person holding the phone; keep them. */
private fun Throwable.readable(): String =
    message?.takeIf { it.isNotBlank() } ?: "Something went wrong. Try again."
