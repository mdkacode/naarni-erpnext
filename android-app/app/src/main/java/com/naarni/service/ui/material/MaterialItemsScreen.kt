package com.naarni.service.ui.material

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.naarni.service.data.dto.GateItemSuggestion
import com.naarni.service.data.dto.MovementItem
import com.naarni.service.data.dto.MovementPhoto
import com.naarni.service.data.dto.SuggestionItem
import com.naarni.service.data.repo.MaterialRepository
import com.naarni.service.ui.components.AppBar
import com.naarni.service.ui.components.BarcodeScannerScreen
import com.naarni.service.ui.components.EmptyState
import com.naarni.service.ui.components.LoadingOverlay
import com.naarni.service.ui.components.PhotoStrip
import com.naarni.service.ui.components.ReviewablePhoto
import com.naarni.service.ui.components.SmartSelect
import com.naarni.service.ui.components.StampingCamera
import com.naarni.service.ui.theme.AppSurface
import com.naarni.service.ui.theme.Semantic

/**
 * The screen a gate clerk lives in.
 *
 * A running list of captured lines, a fat Add button, and per row an inline
 * quantity, a condition chip, a QR chip and a photo button. Each line reads as
 * one sentence — *HVAC Unit · 1 Nos · OK · QR ✓ · 2 photos* — so a forty-item
 * movement is scannable without opening anything.
 *
 * Evidence is advisory throughout: a row with no photo is marked, never blocked,
 * and submitting with rows below the bar asks rather than refuses. A clerk who
 * cannot record the truck records nothing, and nothing is worse than imperfect.
 */
/**
 * Camera target meaning "this photo belongs to the movement, not to a line".
 *
 * A sentinel rather than a nullable target, because `photoFor` being null is
 * already how the camera stays shut.
 */
private const val DOCUMENT_ROW = "__document__"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaterialItemsScreen(
    movementName: String,
    onDone: () -> Unit,
    onBack: () -> Unit,
    vm: MaterialViewModel = viewModel(),
) {
    val ui = vm.ui
    val movement = ui.current

    LaunchedEffect(movementName) {
        if (ui.current?.name != movementName) vm.open(movementName)
        if (ui.context == null) vm.load()
    }

    val photosByRow = remember(movement?.photos) { movement?.photos.orEmpty().byItemRow() }

    // ── transient UI state ──
    var editing by remember { mutableStateOf<EditingRow?>(null) }
    var scanningFor by remember { mutableStateOf<EditingRow?>(null) }
    var photoFor by remember { mutableStateOf<String?>(null) }
    var photoKind by remember { mutableStateOf("Item") }
    var newItemName by remember { mutableStateOf<String?>(null) }
    var lastGroup by remember { mutableStateOf<String?>(null) }
    var confirmSubmit by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<MovementItem?>(null) }

    // ── full-screen takeovers ──

    scanningFor?.let { row ->
        BarcodeScannerScreen(
            title = row.itemName,
            hint = "Point at the QR or barcode on the label",
            onScanned = { code ->
                editing = row.copy(qrCode = code, qrSource = "Scanned")
                scanningFor = null
            },
            // Labels get scratched, greasy and heat-damaged. Typing is always
            // offered, and the source is recorded so an audit can tell a reading
            // from a claim.
            onManualEntry = {
                editing = row.copy(qrSource = "Typed")
                scanningFor = null
            },
            onClose = { scanningFor = null },
        )
        return
    }

    photoFor?.let { rowUuid ->
        val header = rowUuid == DOCUMENT_ROW
        val row = movement?.items?.firstOrNull { it.row_uuid == rowUuid }
        StampingCamera(
            onCaptured = { file, fix ->
                vm.attachPhoto(
                    // A document belongs to the movement, not to a line, so it is
                    // attached with no item row — which is what puts it in the
                    // header bucket the Documents strip reads.
                    rowUuid = if (header) "" else rowUuid,
                    file = file,
                    latitude = fix?.latitude,
                    longitude = fix?.longitude,
                    kind = photoKind,
                )
                photoFor = null
                photoKind = "Item"
            },
            onClose = { photoFor = null; photoKind = "Item" },
            label = if (header) "Document" else row?.item_name,
            subject = if (header) movement?.reference_no else row?.qr_code,
            // The direction, across the top — the same word the gate process
            // prints, so a photo from either route reads identically later.
            banner = movement?.movement_type?.uppercase(),
        )
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppBar(
                title = movement?.let { "${it.movement_type} · ${it.name}" } ?: "Items",
                subtitle = movement?.party_name?.takeIf { it.isNotBlank() }
                    ?: movement?.location_name,
                onBack = onBack,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                movement?.let { EvidenceBar(it.total_items, it.evidence_pct, it.rowsWithoutEvidence()) }

                movement?.let {
                    DocumentRow(
                        photos = photosByRow[null].orEmpty(),
                        editable = it.can_edit,
                        onCapture = { photoKind = "Document"; photoFor = DOCUMENT_ROW },
                    )
                }

                Box(Modifier.weight(1f)) {
                    if (movement == null || movement.items.isEmpty()) {
                        EmptyState(
                            icon = Icons.Rounded.Inventory2,
                            title = "No items yet",
                            body = "Add the first item that came off the truck.",
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(movement.items, key = { it.row_uuid }) { row ->
                                ItemRow(
                                    row = row,
                                    warnings = ui.warnings[row.row_uuid].orEmpty().map { it.message },
                                    editable = movement.can_edit,
                                    photos = photosByRow[row.row_uuid].orEmpty(),
                                    onEdit = { editing = EditingRow.of(row) },
                                    onPhoto = { photoFor = row.row_uuid },
                                    onDelete = { deleting = row },
                                )
                            }
                        }
                    }
                }

                if (movement?.can_edit == true) {
                    BottomBar(
                        itemCount = movement.total_items,
                        saving = ui.saving,
                        onAdd = { editing = EditingRow.blank() },
                        onSubmit = {
                            // Every item needs a photograph, or an explicit reason
                            // it has none. The confirmation is not a nag to click
                            // through: it names the rows and sends the operator
                            // back to them, because the fix takes one tap and
                            // whoever verifies this later cannot take the photo.
                            if (movement.rowsWithoutEvidence() > 0) confirmSubmit = true
                            else vm.submit(onDone = onDone)
                        },
                    )
                }
            }
            LoadingOverlay(visible = ui.saving || ui.loading)
        }
    }

    // ── the line editor ──

    editing?.let { row ->
        ItemEditorSheet(
            row = row,
            conditions = ui.context?.conditions.orEmpty(),
            noPhotoReasons = ui.context?.no_photo_reasons.orEmpty(),
            fetch = { query -> vm.searchItems(query) },
            onPick = { picked ->
                lastGroup = picked.item_group
                editing = row.picked(picked)
                // A QR-tracked item opens the scanner the moment it is chosen —
                // one tap saved on every serialised aggregate, all day.
                if (picked.has_qr == 1 && row.qrCode.isBlank()) {
                    scanningFor = row.picked(picked)
                }
            },
            onAddNew = { typed -> newItemName = typed },
            onScan = { scanningFor = row },
            onChange = { editing = it },
            onSave = {
                val needsShot = row.noPhotoReason.isBlank() &&
                    movement?.photos.orEmpty().none { it.item_row == row.rowUuid }
                vm.saveItem(
                    rowUuid = row.rowUuid,
                    item = row.item,
                    qty = row.qty.toDoubleOrNull() ?: 1.0,
                    uom = row.uom,
                    condition = row.condition,
                    qrCode = row.qrCode,
                    qrSource = row.qrSource,
                    noPhotoReason = row.noPhotoReason,
                    remarks = row.remarks,
                    isNewItem = row.isNewItem,
                    onSaved = { saved ->
                        // Every item needs a photograph, so the camera opens on
                        // saving rather than leaving a red row for the operator to
                        // notice later. Asking at the moment they are still stood
                        // in front of the item is the only time the answer is easy.
                        if (needsShot) {
                            photoKind = "Item"
                            photoFor = saved
                        }
                    },
                )
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    newItemName?.let { typed ->
        NewItemSheet(
            initialName = typed,
            groups = ui.context?.item_groups.orEmpty(),
            uoms = ui.context?.uoms.orEmpty(),
            lastGroup = lastGroup,
            saving = ui.saving,
            onCreate = { name, group, uom, hasQr ->
                vm.createItem(name, group, uom, hasQr) { created ->
                    newItemName = null
                    editing = editing?.picked(created)?.copy(isNewItem = created.created == 1)
                }
            },
            onDismiss = { newItemName = null },
        )
    }

    // ── confirmations ──

    if (confirmSubmit && movement != null) {
        val missing = movement.items.filter { it.needsPhoto }
        AlertDialog(
            onDismissRequest = { confirmSubmit = false },
            icon = { Icon(Icons.Rounded.PhotoCamera, contentDescription = null) },
            title = { Text("${missing.size} ${if (missing.size == 1) "item has" else "items have"} no photo") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Every item needs one before this can be submitted:")
                    missing.take(6).forEach { Text("•  ${it.item_name}", fontWeight = FontWeight.Medium) }
                    if (missing.size > 6) Text("…and ${missing.size - 6} more")
                    Text(
                        "If one genuinely cannot be photographed, open it and give a reason.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    confirmSubmit = false
                    // Straight to the camera for the first one that needs it —
                    // the whole point is that fixing this is easier than dismissing it.
                    missing.firstOrNull()?.let { photoKind = "Item"; photoFor = it.row_uuid }
                }) { Text("Photograph it") }
            },
            dismissButton = { TextButton(onClick = { confirmSubmit = false }) { Text("Back") } },
        )
    }

    deleting?.let { row ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Remove ${row.item_name}?") },
            text = { Text("Its photos are removed with it.") },
            confirmButton = {
                Button(onClick = {
                    vm.deleteItem(row.row_uuid)
                    deleting = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

// ────────────────────────────────────────────────────────── editing state

/**
 * One line being edited.
 *
 * Held apart from the saved [MovementItem] so a half-filled row never reaches
 * the server, and so cancelling leaves the saved row untouched. [rowUuid] is
 * minted once when the row is opened and survives every retry — that is what
 * makes a timed-out save update its own row instead of adding a second.
 */
data class EditingRow(
    val rowUuid: String,
    val item: String = "",
    val itemName: String = "",
    val itemGroup: String? = null,
    val qty: String = "1",
    val uom: String = "Nos",
    val condition: String = "OK",
    val hasQr: Boolean = false,
    val qrCode: String = "",
    val qrSource: String = "",
    val noPhotoReason: String = "",
    val remarks: String = "",
    val isNewItem: Boolean = false,
) {
    val isValid: Boolean get() = item.isNotBlank() && (qty.toDoubleOrNull() ?: 0.0) > 0

    fun picked(suggestion: GateItemSuggestion) = copy(
        item = suggestion.value,
        itemName = suggestion.label,
        itemGroup = suggestion.item_group,
        uom = suggestion.uom,
        hasQr = suggestion.has_qr == 1,
        qty = if (item.isBlank() && suggestion.qty_per_bus > 0) {
            suggestion.defaultQty().trimTrailingZero()
        } else {
            qty
        },
    )

    companion object {
        fun blank() = EditingRow(rowUuid = MaterialRepository.newRowId())

        fun of(row: MovementItem) = EditingRow(
            rowUuid = row.row_uuid,
            item = row.item,
            itemName = row.item_name,
            itemGroup = row.item_group,
            qty = row.qty.trimTrailingZero(),
            uom = row.uom,
            condition = row.condition,
            hasQr = row.has_qr == 1,
            qrCode = row.qr_code.orEmpty(),
            qrSource = row.qr_source.orEmpty(),
            noPhotoReason = row.no_photo_reason.orEmpty(),
            remarks = row.remarks.orEmpty(),
            isNewItem = row.is_new_item == 1,
        )
    }
}

/**
 * The movement's photos, bucketed by the item row each belongs to.
 *
 * `null` is the key for header photos — the challan, the truck — which belong to
 * the movement rather than to any one line.
 */
fun List<MovementPhoto>.byItemRow(): Map<String?, List<ReviewablePhoto>> =
    groupBy { it.item_row?.takeIf { row -> row.isNotBlank() } }
        .mapValues { (_, rows) ->
            rows.map {
                ReviewablePhoto(
                    id = it.client_uuid.ifBlank { it.file_url },
                    remoteUrl = it.file_url,
                    caption = it.caption,
                    // It came back from the server, so by definition it is up.
                    uploaded = true,
                )
            }
        }

/** "12.0" reads wrong on a quantity box; "12" does. */
fun Double.trimTrailingZero(): String =
    if (this % 1.0 == 0.0) toLong().toString() else toString()

// ────────────────────────────────────────────────────────── pieces

@Composable
private fun EvidenceBar(items: Int, evidencePct: Double, missing: Int) {
    Surface(color = AppSurface.raised, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$items ${if (items == 1) "item" else "items"}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Box(Modifier.weight(1f))
                Text(
                    if (missing == 0) "All photographed" else "$missing without a photo",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (missing == 0) Semantic.positive else Semantic.caution,
                )
            }
            LinearProgressIndicator(
                progress = { (evidencePct / 100.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                color = if (missing == 0) Semantic.positive else Semantic.caution,
            )
        }
    }
}

/**
 * The paperwork: invoice, delivery challan, e-way bill, gate pass.
 *
 * Optional, deliberately — a truck at the gate is not always accompanied by its
 * documents, and a clerk blocked on a challan they have not been handed records
 * the load on a scrap of paper instead. But when the paperwork *is* there,
 * photographing it is the cheapest evidence in the whole movement, so it gets a
 * row of its own rather than being buried as one more item.
 */
@Composable
private fun DocumentRow(
    photos: List<ReviewablePhoto>,
    editable: Boolean,
    onCapture: () -> Unit,
) {
    if (!editable && photos.isEmpty()) return
    Surface(color = AppSurface.raised, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Description,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "  Documents",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "  optional",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(Modifier.weight(1f))
                if (editable) {
                    TextButton(onClick = onCapture) {
                        Icon(Icons.Rounded.PhotoCamera, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(if (photos.isEmpty()) "  Add" else "  Another")
                    }
                }
            }
            if (photos.isNotEmpty()) {
                PhotoStrip(photos = photos)
            } else if (editable) {
                Text(
                    "Invoice, delivery challan, e-way bill — photograph them if you have them.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemRow(
    row: MovementItem,
    warnings: List<String>,
    editable: Boolean,
    photos: List<ReviewablePhoto>,
    onEdit: () -> Unit,
    onPhoto: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = AppSurface.raised,
        border = if (row.needsPhoto) BorderStroke(1.dp, Semantic.caution.copy(alpha = 0.4f)) else null,
        modifier = Modifier.fillMaxWidth().clickable(enabled = editable, onClick = onEdit),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        row.item_name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        listOfNotNull(row.item, row.item_group).joinToString("  ·  "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "${row.qty.trimTrailingZero()} ${row.uom}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                if (editable) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = "Remove ${row.item_name}",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (row.condition != "OK") {
                    Pill(row.condition, Semantic.caution, Icons.Rounded.WarningAmber)
                } else {
                    Pill("OK", Semantic.positive, Icons.Rounded.CheckCircle)
                }
                when {
                    !row.qr_code.isNullOrBlank() -> Pill(row.qr_code, MaterialTheme.colorScheme.primary, Icons.Rounded.QrCode2)
                    row.needsSerial -> Pill("No serial", Semantic.caution, Icons.Rounded.QrCode2)
                }
                if (row.photo_count > 0) {
                    Pill("${row.photo_count}", MaterialTheme.colorScheme.onSurfaceVariant, Icons.Rounded.PhotoCamera)
                }
                if (row.is_new_item == 1) {
                    AssistChip(onClick = {}, label = { Text("new item") })
                }
            }

            // The photographs themselves, not a count. A count tells you a number
            // was incremented; it does not tell you the picture is of the item
            // rather than of somebody's boot.
            if (photos.isNotEmpty()) {
                PhotoStrip(photos = photos)
            }

            warnings.forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = Semantic.caution,
                )
            }

            if (editable) {
                if (row.needsPhoto) {
                    Text(
                        "Every item needs a photo before this can be submitted.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Semantic.caution,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // A required photo gets the filled button; once there is one,
                    // taking another is a secondary action.
                    if (row.needsPhoto) {
                        Button(onClick = onPhoto) {
                            Icon(Icons.Rounded.PhotoCamera, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("  Add photo")
                        }
                        TextButton(onClick = onEdit) { Text("Can't photograph this") }
                    } else {
                        OutlinedButton(onClick = onPhoto) {
                            Icon(Icons.Rounded.PhotoCamera, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("  Another photo")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Pill(
    text: String,
    tint: androidx.compose.ui.graphics.Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Surface(shape = RoundedCornerShape(6.dp), color = tint.copy(alpha = 0.14f)) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
            Text(
                "  $text",
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun BottomBar(itemCount: Int, saving: Boolean, onAdd: () -> Unit, onSubmit: () -> Unit) {
    Surface(color = AppSurface.raised) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .navigationBarsPadding(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(onClick = onAdd, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Add item")
            }
            OutlinedButton(
                onClick = onSubmit,
                enabled = itemCount > 0 && !saving,
                modifier = Modifier.weight(1f),
            ) { Text("Submit") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ItemEditorSheet(
    row: EditingRow,
    conditions: List<String>,
    noPhotoReasons: List<String>,
    fetch: suspend (String) -> List<GateItemSuggestion>,
    onPick: (GateItemSuggestion) -> Unit,
    onAddNew: (String) -> Unit,
    onScan: () -> Unit,
    onChange: (EditingRow) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Held so "+ Add a new item" can pre-fill the create form with whatever the
    // clerk typed, rather than making them type it a second time.
    var lastQuery by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<GateItemSuggestion>>(emptyList()) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                if (row.item.isBlank()) "Add item" else "Edit item",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            SmartSelect(
                label = "Item",
                value = row.item.takeIf { it.isNotBlank() }
                    ?.let { SuggestionItem(value = it, label = row.itemName, sublabel = row.itemGroup) },
                placeholder = "Search the catalogue",
                fetch = { query ->
                    lastQuery = query
                    val hits = fetch(query)
                    suggestions = hits
                    hits.map { it.toSuggestion() }
                },
                onSelect = { picked ->
                    suggestions.firstOrNull { it.value == picked.value }?.let(onPick)
                },
                fetchOnOpen = true,
            )

            // The escape hatch, always visible rather than buried in an empty
            // search result: a clerk who has looked and not found it should not
            // have to guess that typing more will eventually offer a way out.
            TextButton(onClick = { onAddNew(lastQuery) }) { Text("+ Item not in the list") }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = row.qty,
                    onValueChange = { onChange(row.copy(qty = it.filter { c -> c.isDigit() || c == '.' })) },
                    label = { Text("Quantity") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                    ),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = row.uom,
                    onValueChange = { onChange(row.copy(uom = it)) },
                    label = { Text("Unit") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Condition", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    conditions.forEach { option ->
                        FilterChip(
                            selected = row.condition == option,
                            onClick = { onChange(row.copy(condition = option)) },
                            label = { Text(option) },
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("QR / serial", style = MaterialTheme.typography.labelLarge)
                    Box(Modifier.weight(1f))
                    TextButton(onClick = onScan) {
                        Icon(Icons.Rounded.QrCode2, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("  Scan")
                    }
                }
                OutlinedTextField(
                    value = row.qrCode,
                    onValueChange = { onChange(row.copy(qrCode = it, qrSource = if (it.isBlank()) "" else "Typed")) },
                    placeholder = { Text(if (row.hasQr) "Scan, or type it from the label" else "Optional") },
                    singleLine = true,
                    supportingText = {
                        if (row.hasQr && row.qrCode.isBlank()) {
                            Text("This item normally carries a label.", color = Semantic.caution)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("If you can't photograph it", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    noPhotoReasons.forEach { option ->
                        FilterChip(
                            selected = row.noPhotoReason == option,
                            onClick = {
                                onChange(row.copy(noPhotoReason = if (row.noPhotoReason == option) "" else option))
                            },
                            label = { Text(option) },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = row.remarks,
                onValueChange = { onChange(row.copy(remarks = it)) },
                label = { Text("Remarks") },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(onClick = onSave, enabled = row.isValid, modifier = Modifier.weight(1f)) {
                    Text("Save item")
                }
            }
        }
    }
}
