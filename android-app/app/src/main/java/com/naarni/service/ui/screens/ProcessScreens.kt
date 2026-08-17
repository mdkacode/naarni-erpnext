package com.naarni.service.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naarni.service.core.inspection.InspectionWork
import com.naarni.service.data.dto.OpenRun
import com.naarni.service.data.dto.ProcessDefinition
import com.naarni.service.data.dto.ProcessStep
import com.naarni.service.data.dto.ProcessSummary
import com.naarni.service.data.inspection.LocalRunEntity
import com.naarni.service.data.inspection.PendingSummary
import com.naarni.service.data.inspection.SyncState
import com.naarni.service.data.repo.ProcessScreen
import com.naarni.service.data.repo.screensFor
import com.naarni.service.ui.AppViewModel
import com.naarni.service.ui.components.BarcodeScannerScreen
import com.naarni.service.ui.components.EmptyState
import com.naarni.service.ui.components.HairlineDivider
import com.naarni.service.ui.components.OfflineStrip
import com.naarni.service.ui.components.PendingWorkCard
import com.naarni.service.ui.components.RunnerStep
import com.naarni.service.ui.components.RunSyncLine
import com.naarni.service.ui.components.LoadingOverlay
import com.naarni.service.ui.components.SectionHeader
import com.naarni.service.ui.components.StampingCamera
import com.naarni.service.ui.components.StepAnswer
import com.naarni.service.ui.components.StepCard
import com.naarni.service.ui.components.SyncBadge
import com.naarni.service.ui.components.wantsPhoto
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * The process engine's three screens — a list, a start screen, and one runner
 * that renders every process there will ever be.
 *
 * Nothing here knows what a battery is. The runner reads a definition and draws
 * it, so publishing Vehicle PDI or a depot opening check tomorrow needs no
 * change to this file.
 */

private val PassGreen = Color(0xFF17784A)
private val FailRed = Color(0xFFB62F27)
private val WarnAmber = Color(0xFF99630A)

// ------------------------------------------------------------------ list

@Composable
fun ProcessListScreen(
    vm: AppViewModel,
    onOpenProcess: (String) -> Unit,
    onResumeRun: (String) -> Unit,
    onOpenHistory: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var processes by remember { mutableStateOf<List<ProcessSummary>>(emptyList()) }
    var doneToday by remember { mutableStateOf(0) }
    var doneTotal by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var remote by remember { mutableStateOf<List<OpenRun>>(emptyList()) }

    // Local, so the resume list is right in a shed. A run started offline has no
    // server name to appear under, and the server's own open-runs list would
    // simply not know it exists.
    val openRuns by vm.inspections.openRuns().collectAsState(initial = emptyList())
    val adopted = remember(openRuns) { openRuns.mapNotNull { it.serverName }.toSet() }
    val remoteOnly = remember(remote, adopted) { remote.filter { it.name !in adopted } }
    val pending by vm.inspections.pendingSummary().collectAsState(initial = PendingSummary())
    val online by vm.online.collectAsState()

    LaunchedEffect(Unit) {
        loading = true
        // Cache first — this is the screen work starts from, and it must never
        // wait on a network to show a list the phone already holds.
        processes = runCatching { vm.inspections.processes() }.getOrDefault(emptyList())
        loading = false
        if (processes.isEmpty()) {
            error = "Ask an admin to publish a process and give your role access to it."
        }
        // Then top up behind the list, so the definitions are there next time
        // the engineer is somewhere without signal.
        runCatching { vm.inspections.refreshCatalogue() }
            .onSuccess { processes = vm.inspections.processes() }
        runCatching { vm.processes.history(limit = 1) }.onSuccess {
            doneToday = it.stats.today
            doneTotal = it.stats.total
        }
        // Runs that exist server-side but not on this handset — started on
        // another phone, or on a build before the local store existed. Without
        // this an engineer upgrading mid-inspection would find their open run
        // gone from the resume list, which reads as lost work.
        runCatching { vm.processes.openRuns() }.onSuccess { remote = it }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            OfflineStrip(online)
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
            ) {
            // Silent when there is nothing outstanding — see PendingWorkCard.
            item {
                PendingWorkCard(pending, online) {
                    // The escape hatch: work that gave up gets another go, on
                    // the engineer's say-so rather than on a timer.
                    scope.launch {
                        runCatching { vm.inspections.retryStuck() }
                        InspectionWork.sweep(context)
                    }
                }
            }

            // The operator's own tally, at the top of the screen they start work
            // from. Somebody who has done nine inspections today should not have
            // to go looking for that number.
            item { MyWorkCard(doneToday, doneTotal, onOpenHistory) }

            if (openRuns.isNotEmpty() || remoteOnly.isNotEmpty()) {
                item { SectionHeader("Continue where you left off") }
                items(remoteOnly, key = { it.name }) { run ->
                    Card(
                        // The server name. The runner adopts it into the local
                        // store on open, after which it behaves like any other.
                        onClick = { onResumeRun(run.name) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(run.run_identifier ?: run.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${run.process_name} · ${run.answered_count} answered",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(openRuns, key = { it.clientUuid }) { run ->
                    Card(
                        onClick = { onResumeRun(run.clientUuid) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(
                            Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    run.identifier ?: run.processName,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "${run.processName} · ${run.answeredCount} answered",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            SyncBadge(run.syncState)
                        }
                    }
                }
            }

            item { SectionHeader("Available to you") }

            if (!loading && processes.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Rounded.BatteryChargingFull,
                        "No processes yet",
                        error ?: "Ask an admin to publish a process and give your role access to it.",
                    )
                }
            }

            items(processes) { process ->
                Card(
                    onClick = { onOpenProcess(process.family.ifBlank { process.name }) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(process.icon ?: "📋", fontSize = 24.sp)
                        Column(Modifier.weight(1f)) {
                            Text(process.process_name, fontWeight = FontWeight.SemiBold)
                            Text(
                                buildString {
                                    append("${process.stage_count} ${process.stage_label.lowercase()}s")
                                    append(" · ${process.step_count} checks")
                                    if (process.expected_minutes > 0) append(" · ~${process.expected_minutes} min")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            }
        }
        LoadingOverlay(loading)
    }
}

/** Entry point into the operator's own record, carrying the number with it. */
@Composable
private fun MyWorkCard(today: Int, total: Int, onClick: () -> Unit) {
    Surface(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Rounded.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(26.dp),
            )
            Column(Modifier.weight(1f)) {
                Text("My inspections", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(
                    if (total == 0) {
                        "Your finished checks and photos will appear here"
                    } else {
                        "$today today · $total in total"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// ----------------------------------------------------------------- start

/**
 * Identify the subject and open a run.
 *
 * The manual-entry field is always present, never behind a toggle: a scanner
 * that will not read a scuffed label must never stop an operator working.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessStartScreen(
    vm: AppViewModel,
    processFamily: String,
    onBack: () -> Unit,
    onRunStarted: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var definition by remember { mutableStateOf<ProcessDefinition?>(null) }
    var identifier by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    val online by vm.online.collectAsState()

    LaunchedEffect(processFamily) {
        // Cache first. A definition is immutable once published, so a copy
        // fetched last week is as good as one fetched now — and it is the
        // difference between starting an inspection in a shed and not.
        definition = runCatching { vm.inspections.definition(processFamily) }.getOrNull()
        if (definition == null) {
            error = "This process has not been downloaded to this phone yet. " +
                "Connect to a network once and it will be available offline afterwards."
        }
    }

    if (scanning) {
        BarcodeScannerScreen(
            title = "Scan the ${definition?.subject_label ?: "item"} label",
            hint = "Hold the camera over the QR code or barcode on the pack",
            onScanned = { code ->
                identifier = code.trim().uppercase()
                scanning = false
            },
            onManualEntry = { scanning = false },
            onClose = { scanning = false },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(definition?.process_name ?: "Start") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OfflineStrip(online)
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
            val subject = definition?.subject_label ?: "item"

            // This panel used to *describe* scanning without doing any. It is now
            // the button that opens the camera — which is what an operator holding
            // a pack with a QR label on it expects the big scan panel to be.
            Surface(
                Modifier
                    .fillMaxWidth()
                    .height(168.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .clickable { scanning = true },
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primary,
            ) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Rounded.QrCodeScanner,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(52.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Scan the $subject label",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Text(
                        "QR or barcode — or type it below",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
            }

            OutlinedTextField(
                value = identifier,
                onValueChange = { identifier = it.uppercase() },
                label = { Text("$subject number") },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp),
            )

            error?.let { Text(it, color = FailRed, style = MaterialTheme.typography.bodySmall) }

            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        // Local, and instant. The run exists the moment this
                        // returns; the server learns about it on the next sync
                        // and is idempotent on the UUID minted here, so however
                        // many times that request is retried there is one run.
                        runCatching {
                            vm.inspections.startRun(
                                processFamily = processFamily,
                                identifier = identifier.trim().ifBlank { null },
                            )
                        }
                            .onSuccess { onRunStarted(it) }
                            .onFailure { error = it.message }
                        busy = false
                    }
                },
                enabled = !busy && definition != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(10.dp),
            ) { Text("Start") }
            }
        }
    }
}

// ---------------------------------------------------------------- runner

/**
 * The generic runner: one section per screen, driven entirely by the definition.
 *
 * Section-at-a-time is not an arbitrary choice. The paper sheets these processes
 * replace already group checks 2–5 at a time, and an oversized checklist is the
 * documented cause of box-ticking without doing the work. There is deliberately
 * no "mark all as pass" control — it costs an honest operator a few taps and is
 * the single highest-leverage thing the design does for data quality.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessRunnerScreen(vm: AppViewModel, runName: String, onBack: () -> Unit, onFinished: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // `runName` is a local client UUID for anything started on this build. A
    // server name arrives here only from a deep link or an older resume list, so
    // it is adopted into the local store first — after which the two paths are
    // identical and nothing downstream has to know which it was.
    var runUuid by remember(runName) { mutableStateOf<String?>(null) }
    var definition by remember { mutableStateOf<ProcessDefinition?>(null) }
    var screenIndex by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(true) }
    var banner by remember { mutableStateOf<String?>(null) }
    // English only. The bilingual pairing put a full-width Devanagari line under
    // every question, halving the room for the question itself. `label_alt` is
    // still stored server-side and can be brought back per process.
    val showAlt = false
    // The step a scan was requested for, while the scanner is up.
    var scanningFor by remember { mutableStateOf<String?>(null) }
    // The step a photo was requested for, while the stamping camera is up.
    var photoFor by remember { mutableStateOf<String?>(null) }
    // Set when Next is pressed on a screen with something missing.
    var pendingGate by remember { mutableStateOf<RunnerGate?>(null) }

    val online by vm.online.collectAsState()

    LaunchedEffect(runName) {
        busy = true
        val resolved = if (vm.inspections.run(runName) != null) {
            runName
        } else {
            runCatching { vm.inspections.adoptServerRun(runName) }.getOrNull()
        }
        if (resolved == null) {
            banner = "This inspection is not on this phone and could not be fetched."
        } else {
            runUuid = resolved
            val local = vm.inspections.run(resolved)
            definition = local?.let { vm.inspections.definition(it.definitionName) }
            if (definition == null) banner = "This process has not been downloaded to this phone."
        }
        busy = false
    }

    // Everything the screen draws comes from Room, so the runner behaves the
    // same on a good network and on none at all — and a change made here shows
    // up without waiting for a round trip to confirm it.
    val uuid = runUuid
    val localRun by (uuid?.let { vm.inspections.runFlow(it) } ?: flowOf(null))
        .collectAsState(initial = null)
    val storedAnswers by (uuid?.let { vm.inspections.answersFlow(it) } ?: flowOf(emptyMap()))
        .collectAsState(initial = emptyMap())
    val photoCounts by (uuid?.let { vm.inspections.photoCountsFlow(it) } ?: flowOf(emptyMap()))
        .collectAsState(initial = emptyMap())

    val answers: Map<String, StepAnswer> = remember(storedAnswers) {
        storedAnswers.mapValues { (_, row) ->
            StepAnswer(
                response = row.response,
                value = row.value,
                remark = row.remark,
                skipped = row.skipped,
                skipReason = row.skipReason,
            )
        }
    }

    val currentRun = localRun
    val def = definition
    val stageCode = currentRun?.currentStage ?: def?.stages?.firstOrNull()?.stage_code
    val stage = def?.stages?.firstOrNull { it.stage_code == stageCode }
    val screens: List<ProcessScreen> = if (def != null && stageCode != null) screensFor(def, stageCode) else emptyList()
    val screen = screens.getOrNull(screenIndex)

    // The step the operator is on, across the whole stage — not "screen n of m".
    // With one step per screen those are the same number, and "screen" is a word
    // about the software rather than about the work being done.
    val totalSteps = screens.sumOf { it.steps.size }
    val stepsBefore = screens.take(screenIndex).sumOf { it.steps.size }
    val single = screen?.steps?.size == 1

    photoFor?.let { stepCode ->
        val target = def?.steps?.firstOrNull { it.step_code == stepCode }
        StampingCamera(
            label = target?.label?.take(40),
            // The pack serial the run was started against, burnt into the photo
            // so the image identifies itself once it leaves this record.
            subject = currentRun?.identifier,
            onClose = { photoFor = null },
            onCaptured = { file, fix ->
                photoFor = null
                val id = uuid ?: return@StampingCamera
                val forStep = target ?: return@StampingCamera
                scope.launch {
                    // Queued, not uploaded. The file is copied into app-private
                    // storage and the count moves at once — an engineer in a
                    // shed sees the photo land against the step exactly as they
                    // would on Wi-Fi, and the upload happens when it can.
                    runCatching {
                        vm.inspections.queuePhoto(
                            runUuid = id,
                            step = forStep,
                            source = file,
                            // The fix is burnt into the pixels by the camera and
                            // stored here too: painted coordinates are readable,
                            // stored ones are queryable, and the geofence check
                            // runs on the stored pair.
                            latitude = fix?.latitude,
                            longitude = fix?.longitude,
                            accuracyM = fix?.accuracy?.toDouble(),
                        )
                        InspectionWork.sync(context, id)
                    }.onFailure { banner = it.message }
                }
            },
        )
        return
    }

    scanningFor?.let { stepCode ->
        val target = def?.steps?.firstOrNull { it.step_code == stepCode }
        BarcodeScannerScreen(
            title = target?.label ?: "Scan",
            hint = target?.help_text,
            onScanned = { code ->
                scanningFor = null
                val id = uuid ?: return@BarcodeScannerScreen
                val step = target ?: return@BarcodeScannerScreen
                scope.launch {
                    runCatching {
                        step.scan_entity_type?.let { entity ->
                            vm.inspections.queueScan(
                                runUuid = id,
                                entityType = entity,
                                payload = code,
                                stepCode = stepCode,
                            )
                        }
                        vm.inspections.saveAnswer(runUuid = id, step = step, value = code)
                        InspectionWork.sync(context, id)
                    }.onFailure { banner = it.message }
                }
            },
            onManualEntry = { scanningFor = null },
            onClose = { scanningFor = null },
        )
        return
    }

    // Saving is identical for the full-screen renderer and the list card, so it
    // lives here rather than being written twice and drifting apart.
    //
    // It no longer awaits anything. The answer is judged on the device and
    // written to Room, which is what the screen is reading — so the card turns
    // green on the tap rather than on the reply, and the reply is a background
    // job that may be hours away.
    val commit: (ProcessStep, StepAnswer) -> Unit = { step, next ->
        uuid?.let { id ->
            scope.launch {
                runCatching {
                    vm.inspections.saveAnswer(
                        runUuid = id,
                        step = step,
                        response = next.response,
                        value = next.value,
                        remark = next.remark,
                        skipped = next.skipped,
                        skipReason = next.skipReason,
                    )
                    InspectionWork.sync(context, id)
                }.onFailure { banner = it.message }
            }
        }
    }

    val skip: (ProcessStep, String) -> Unit = { step, reason ->
        commit(step, StepAnswer(skipped = true, skipReason = reason))
    }

    // What Next would leave behind, if anything. Held in state so the dialog and
    // the button agree on the reason.
    val advance: () -> Unit = {
        if (screenIndex < screens.lastIndex) {
            screenIndex++
        } else if (uuid != null && stageCode != null) {
            val id = uuid
            scope.launch {
                busy = true
                runCatching { vm.inspections.submitStage(id, stageCode) }
                    .onSuccess { result ->
                        screenIndex = 0
                        banner = when {
                            // The same interlock the server applies, run here
                            // against the cached definition — so the engineer is
                            // told what is missing while standing at the pack,
                            // not four hours later when the van finds signal.
                            !result.accepted && result.missing.isNotEmpty() ->
                                "Still to finish: ${result.missing.joinToString(", ")}"
                            !result.accepted -> "This stage could not be submitted."
                            else -> null
                        }
                        if (result.accepted) {
                            InspectionWork.sync(context, id)
                            if (result.finished) onFinished()
                        }
                    }
                    .onFailure { banner = it.message }
                busy = false
            }
        }
    }

    pendingGate?.let { gate ->
        RunnerGateDialog(
            gate = gate,
            onFix = {
                pendingGate = null
                // "Take the photo" opens the camera. A button that names an
                // action and then only closes a dialog trains people to ignore
                // the dialog, which is the one thing this warning cannot afford.
                if (gate == RunnerGate.NO_PHOTO) {
                    photoFor = screen?.steps?.firstOrNull { step ->
                        step.wantsPhoto(answers[step.step_code] ?: StepAnswer()) &&
                            (photoCounts[step.step_code] ?: 0) == 0
                    }?.step_code
                }
            },
            onContinue = {
                pendingGate = null
                advance()
            },
        )
    }

    Scaffold(
        topBar = {
            // Centred: the stage name and "Step 7 of 26" are the operator's place
            // in the work, and a heading that sits under the back arrow reads as
            // a label for the arrow rather than for the screen.
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stage?.label ?: def?.process_name.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        if (screens.isNotEmpty()) {
                            Text(
                                if (single) {
                                    "Step ${stepsBefore + 1} of $totalSteps"
                                } else {
                                    "${def?.stage_label ?: "Stage"} · screen ${screenIndex + 1} of ${screens.size}"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                // Balances the back arrow so the title lands on the true centre
                // rather than 24dp to the right of it.
                actions = { Spacer(Modifier.size(48.dp)) },
            )
        },
        bottomBar = {
            screen?.let { current ->
                val answered = current.steps.count { s -> answers[s.step_code].isAnswered() }
                val gate = gateFor(current.steps, answers, photoCounts)
                RunnerNavBar(
                    canGoBack = screenIndex > 0,
                    onBack = { screenIndex-- },
                    // A "1 / 1" counter next to a single question is noise.
                    counter = if (single) null else "$answered / ${current.steps.size}",
                    gate = gate,
                    nextLabel = when {
                        screenIndex < screens.lastIndex && single -> "Next"
                        screenIndex < screens.lastIndex -> "Next section"
                        else -> "Submit ${stage?.label ?: ""}"
                    },
                    enabled = !busy,
                    // The gate interrupts; it never refuses. Pressing Next with
                    // something missing opens the dialog, and the dialog can
                    // still let the operator through.
                    onNext = { if (gate != null) pendingGate = gate else advance() },
                )
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val current = screen
            if (single && current != null) {
                // One check, filling the screen. Not a list of one — a list puts
                // the answer wherever the content happens to end, and the whole
                // point of one-per-screen is that the answer is always in the
                // same place, at the bottom, under the thumb.
                val step = current.steps.first()
                RunnerStep(
                    step = step,
                    answer = answers[step.step_code] ?: StepAnswer(),
                    onAnswer = { next -> commit(step, next) },
                    onScanRequest = { scanningFor = step.step_code },
                    onPhotoRequest = { photoFor = step.step_code },
                    photoCount = photoCounts[step.step_code] ?: 0,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 18.dp)
                        .padding(top = 10.dp, bottom = 4.dp),
                    header = {
                        StepProgress(screenIndex, screens.size)
                        Text(
                            current.section.ifBlank { "Checks" }.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        banner?.let { RunnerBanner(it) }
                        currentRun?.takeIf { it.status == "Quarantined" }?.let {
                            QuarantineBanner(it.quarantineReason)
                        }
                        // Only when it needs saying. On a one-check screen the
                        // question should own the space, and "everything is on
                        // the server" is not worth a line of it.
                        currentRun?.takeIf {
                            it.syncState == SyncState.ATTENTION ||
                                storedAnswers.values.any { a -> !a.synced && a.rejectedReason == null }
                        }?.let { r ->
                            RunSyncLine(
                                syncState = r.syncState,
                                unsynced = storedAnswers.values.count { !it.synced && it.rejectedReason == null },
                                lastError = r.lastError,
                                online = online,
                            )
                        }
                    },
                    footer = {
                        if (step.allow_skip == 1 && step.skip_reasons.isNotEmpty()) {
                            SkipRow(step.skip_reasons) { reason -> skip(step, reason) }
                        }
                    },
                )
            } else {
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
                ) {
                    item { StepProgress(screenIndex, screens.size) }

                    currentRun?.let { r ->
                        item { RunSummaryStrip(r) }
                        item {
                            RunSyncLine(
                                syncState = r.syncState,
                                unsynced = storedAnswers.values.count { !it.synced && it.rejectedReason == null },
                                lastError = r.lastError,
                                online = online,
                            )
                        }
                        if (r.status == "Quarantined") {
                            item { QuarantineBanner(r.quarantineReason) }
                        }
                    }

                    banner?.let { message -> item { RunnerBanner(message) } }

                    current?.let { screenNow ->
                        item { SectionHeader(screenNow.section.ifBlank { "Checks" }) }
                        items(screenNow.steps, key = { it.step_code }) { step ->
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                StepCard(
                                    step = step,
                                    answer = answers[step.step_code] ?: StepAnswer(),
                                    showAltLanguage = showAlt,
                                    onAnswer = { next -> commit(step, next) },
                                )
                                if (step.allow_skip == 1 && step.skip_reasons.isNotEmpty()) {
                                    SkipRow(step.skip_reasons) { reason -> skip(step, reason) }
                                }
                            }
                        }
                    }

                    if (screens.isEmpty() && !busy) {
                        item {
                            EmptyState(
                                Icons.Rounded.CheckCircle,
                                "Nothing left here",
                                currentRun?.let { "This run is ${it.status.lowercase()}." } ?: "No checks in this stage.",
                            )
                        }
                    }
                }
            }
            LoadingOverlay(busy)
        }
    }
}

/**
 * What a screen is still missing when Next is pressed.
 *
 * Ordered by which one to raise first: an unanswered check matters more than a
 * missing photo of an answer that does not exist yet.
 */
private enum class RunnerGate(
    val strip: String,
    val title: String,
    val body: String,
    val fixLabel: String,
) {
    NOT_ANSWERED(
        strip = "Not answered — moving on leaves this blank",
        title = "Nothing marked here",
        body = "You have not marked this check. Moving on records it as blank, " +
            "and a blank check cannot be told apart from one nobody reached.\n\n" +
            "If you genuinely cannot do it right now, use \"Can't check this?\" " +
            "instead — that records the reason.",
        fixLabel = "Mark it now",
    ),
    NO_PHOTO(
        strip = "Marked, but no photo yet",
        title = "No photo of this one",
        body = "You have marked this check but not photographed it. The photo is " +
            "stamped with the serial, the place, the time and your name — it is " +
            "what backs up your answer if anyone questions it later.",
        fixLabel = "Take the photo",
    ),
}

private fun StepAnswer?.isAnswered(): Boolean =
    this != null && (response != null || !value.isNullOrBlank() || skipped)

/** The first thing missing on this screen, or null when it is complete. */
private fun gateFor(
    steps: List<ProcessStep>,
    answers: Map<String, StepAnswer>,
    photoCounts: Map<String, Int>,
): RunnerGate? {
    if (steps.any { !answers[it.step_code].isAnswered() }) return RunnerGate.NOT_ANSWERED
    val missing = steps.any { step ->
        val answer = answers[step.step_code] ?: StepAnswer()
        step.wantsPhoto(answer) && (photoCounts[step.step_code] ?: 0) == 0
    }
    return if (missing) RunnerGate.NO_PHOTO else null
}

/**
 * The warning that stands between a gap and the next screen.
 *
 * It interrupts and it explains, but "Continue anyway" is always there. A hard
 * block on a plant floor does not produce the missing answer — it produces a
 * guess, because the operator has a pack in front of them and a queue behind
 * them. Interrupting makes the gap deliberate and visible; refusing would only
 * make it dishonest.
 *
 * The safe action is the prominent one, and it is phrased as the thing to *do*
 * ("Take the photo") rather than as "Cancel", which says nothing about what
 * happens next.
 */
@Composable
private fun RunnerGateDialog(gate: RunnerGate, onFix: () -> Unit, onContinue: () -> Unit) {
    AlertDialog(
        onDismissRequest = onFix,
        icon = { Icon(Icons.Rounded.Warning, contentDescription = null, tint = WarnAmber) },
        title = { Text(gate.title, fontWeight = FontWeight.Bold) },
        text = { Text(gate.body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            Button(onClick = onFix, shape = RoundedCornerShape(10.dp)) { Text(gate.fixLabel) }
        },
        dismissButton = {
            TextButton(onClick = onContinue) {
                Text("Continue anyway", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

/**
 * The two controls that move an operator through a stage.
 *
 * Green forward, red back — asked for directly, and it survives the plant: the
 * pair is distinguishable at a glance across a bench, which is not true of two
 * grey buttons that differ only by a word.
 *
 * Red on Back is drawn as an outline, not a fill. Two solid blocks of green and
 * red side by side read as *approve* and *reject* — a verdict on the check
 * rather than a direction of travel — and an operator who taps "reject" meaning
 * "go back" has just mis-recorded a battery.
 *
 * **A blank step is never blocked.** Interlocking Next on an answer sounds
 * careful and produces the opposite: the operator who genuinely cannot see the
 * weld right now taps something to get past it, and a guess in the record is
 * worse than a gap. So the way forward stays open and the bar says plainly what
 * moving on will leave behind — the only mandatory gate in the engine is at
 * submit, where the server names the steps it still needs.
 */
@Composable
private fun RunnerNavBar(
    canGoBack: Boolean,
    onBack: () -> Unit,
    counter: String?,
    gate: RunnerGate?,
    nextLabel: String,
    enabled: Boolean,
    onNext: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth()) {
            HairlineDivider()
            gate?.let {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(WarnAmber.copy(alpha = 0.10f))
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Rounded.Info,
                        contentDescription = null,
                        tint = WarnAmber,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        it.strip,
                        style = MaterialTheme.typography.bodyMedium,
                        color = WarnAmber,
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Going back matters more than it looks on a one-step screen: an
                // operator who mis-taps otherwise has no way to reach that step
                // again, where the old multi-step list at least kept it on screen.
                if (canGoBack) {
                    OutlinedButton(
                        onClick = onBack,
                        enabled = enabled,
                        modifier = Modifier.height(60.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(2.dp, FailRed),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = FailRed),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.size(6.dp))
                        Text("Back", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
                counter?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = onNext,
                    enabled = enabled,
                    modifier = Modifier
                        .weight(1f)
                        .height(60.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PassGreen,
                        contentColor = Color.White,
                    ),
                ) {
                    Text(
                        nextLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/** How far through the stage, as one quiet line. */
@Composable
private fun StepProgress(index: Int, total: Int) {
    LinearProgressIndicator(
        progress = { if (total <= 0) 0f else (index + 1f) / total },
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp)),
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )
}

@Composable
private fun RunnerBanner(message: String) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = FailRed.copy(alpha = 0.12f),
    ) {
        Text(
            message,
            Modifier.padding(12.dp),
            color = FailRed,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun RunSummaryStrip(run: LocalRunEntity) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        SummaryTile("Passed", run.passCount.toString(), PassGreen, Modifier.weight(1f))
        SummaryTile("Failed", run.failCount.toString(), FailRed, Modifier.weight(1f))
        // Score and trace are the server's numbers, and stay at their last
        // synced value while offline rather than being guessed at locally. The
        // counts beside them are live, because those the device can be sure of.
        SummaryTile("Score", "${run.scorePct.toInt()}%", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
        SummaryTile("Trace", "${run.tracePct.toInt()}%", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
    }
}

@Composable
private fun SummaryTile(label: String, value: String, tint: Color, modifier: Modifier = Modifier) {
    Card(
        modifier,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = tint)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun QuarantineBanner(reason: String?) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = FailRed.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, FailRed),
    ) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = FailRed)
            Column {
                Text("Quarantined for QC review", fontWeight = FontWeight.SemiBold, color = FailRed)
                reason?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/**
 * Skipping is a first-class action with a canned reason — never free text, and
 * never blocked. The reason lands on the report, which is where a pattern of
 * skipping becomes visible without policing anyone at the station.
 */
@Composable
private fun SkipRow(reasons: List<String>, onSkip: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = !expanded }) {
            Text("Can't check this?", style = MaterialTheme.typography.labelMedium, color = WarnAmber)
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                reasons.forEach { reason ->
                    OutlinedButton(
                        onClick = {
                            expanded = false
                            onSkip(reason)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                    ) { Text(reason, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
