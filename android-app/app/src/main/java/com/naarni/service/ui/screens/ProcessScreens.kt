package com.naarni.service.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naarni.service.core.inspection.InspectionWork
import com.naarni.service.data.dto.FoundRun
import com.naarni.service.data.dto.OpenRun
import com.naarni.service.data.dto.ProcessDefinition
import com.naarni.service.data.dto.ProcessStep
import com.naarni.service.data.dto.ProcessSummary
import com.naarni.service.data.inspection.LocalRunEntity
import com.naarni.service.data.inspection.PendingSummary
import com.naarni.service.data.repo.InspectionRepository
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
import com.naarni.service.core.ocr.WeightOcr
import com.naarni.service.ui.components.OcrSuggestion
import com.naarni.service.ui.components.ReviewablePhoto
import com.naarni.service.ui.components.RunVerifyCard
import com.naarni.service.ui.components.VerifyLine
import com.naarni.service.ui.components.LinkPickerSheet
import com.naarni.service.ui.components.StepAnswer
import com.naarni.service.ui.components.StepCard
import com.naarni.service.ui.components.SyncBadge
import com.naarni.service.ui.components.wantsPhoto
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import androidx.compose.material.icons.rounded.FactCheck
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material.icons.rounded.Refresh

/**
 * The process engine's three screens — a list, a start screen, and one runner
 * that renders every process there will ever be.
 *
 * Nothing here knows what a battery is. The runner reads a definition and draws
 * it, so publishing Vehicle PDI or a depot opening check tomorrow needs no
 * change to this file.
 */

// Shared with the board and the history screen: one verdict palette, so a green
// on one screen means exactly what it means on the next.
internal val PassGreen = Color(0xFF17784A)
internal val FailRed = Color(0xFFB62F27)
internal val WarnAmber = Color(0xFF99630A)

// ------------------------------------------------------------------ list

@Composable
fun ProcessListScreen(
    vm: AppViewModel,
    onOpenProcess: (String) -> Unit,
    onResumeRun: (String) -> Unit,
    onOpenHistory: () -> Unit = {},
    onOpenVerifyQueue: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var processes by remember { mutableStateOf<List<ProcessSummary>>(emptyList()) }
    var doneToday by remember { mutableStateOf(0) }
    var doneTotal by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var remote by remember { mutableStateOf<List<OpenRun>>(emptyList()) }

    /** What the phone is still holding, shown after a manual push. */
    var queueReport by remember { mutableStateOf<String?>(null) }

    /** Non-null while a hard push is running, so it can be watched. */
    var pushing by remember { mutableStateOf<InspectionRepository.PushProgress?>(null) }

    // How many packs are waiting on this person's signature. Fetched quietly and
    // shown only when there are some: a verification card on the screen of
    // somebody who verifies nothing is a permanent nought.
    var toVerify by remember { mutableIntStateOf(0) }

    // Local, so the resume list is right in a shed. A run started offline has no
    // server name to appear under, and the server's own open-runs list would
    // simply not know it exists.
    val openRuns by vm.inspections.openRuns().collectAsState(initial = emptyList())
    val adopted = remember(openRuns) { openRuns.mapNotNull { it.serverName }.toSet() }
    val remoteOnly = remember(remote, adopted) { remote.filter { it.name !in adopted } }
    val pending by vm.inspections.pendingSummary().collectAsState(initial = PendingSummary())
    val online by vm.online.collectAsState()

    LaunchedEffect(Unit) {
        // Needs the network by nature, and must never hold up the list: an
        // engineer with no signal still starts inspections from this screen.
        toVerify = runCatching { vm.processes.verificationQueue().runs.size }.getOrDefault(0)
    }

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

    pushing?.let { progress ->
        // Not dismissable by tapping outside: this is doing real network work
        // and an engineer who dismisses it by accident has no way to tell
        // whether it carried on.
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Uploading") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(progress.label, style = MaterialTheme.typography.bodyMedium)
                    if (progress.total > 0) {
                        LinearProgressIndicator(
                            progress = { progress.done.toFloat() / progress.total.coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Text(
                        "Keep this screen open until it finishes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { },
        )
    }

    queueReport?.let { report ->
        AlertDialog(
            onDismissRequest = { queueReport = null },
            title = { Text("What this phone is still holding") },
            text = {
                Text(
                    report,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { queueReport = null }) { Text("OK") } },
        )
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
            if (toVerify > 0) {
                item { VerifyQueueCard(count = toVerify, onClick = onOpenVerifyQueue) }
            }

            // Silent when there is nothing outstanding — see PendingWorkCard.
            item {
                PendingWorkCard(pending, online) {
                    // Uploads here and now rather than only asking the job
                    // scheduler to, because the scheduler is exactly what an
                    // OEM battery manager holds back — and then reports what
                    // happened, since "try again" with no answer afterwards is
                    // what makes somebody assume their morning has been lost.
                    scope.launch {
                        pushing = InspectionRepository.PushProgress(0, 0, "Starting…")
                        val report = runCatching {
                            vm.inspections.hardPush { pushing = it }
                        }.getOrElse { "Couldn't finish: ${it.message}\n\nNothing was lost — it is all still on this phone." }
                        // Belt and braces: whatever this could not finish before
                        // the engineer walks away still goes up on its own.
                        InspectionWork.sweep(context)
                        pushing = null
                        queueReport = report
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
    // What this label turns out to be: a pack somebody is already inspecting, or
    // nothing yet. Looked up the moment there is a label to look up.
    var found by remember { mutableStateOf<FoundRun?>(null) }
    var looking by remember { mutableStateOf(false) }
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

    // Ask before creating anything. Somebody has to be able to walk up to a pack
    // a colleague is half-way through and see that, rather than start a second
    // record of the same battery and find out at the end of the shift.
    LaunchedEffect(identifier, online, definition) {
        val def = definition
        val key = identifier.trim()
        if (def == null || key.length < MIN_LOOKUP_CHARS || !online) {
            found = null
            return@LaunchedEffect
        }
        // Typing settles first: a lookup per keystroke would be twelve requests
        // for one serial.
        kotlinx.coroutines.delay(LOOKUP_DEBOUNCE_MS)
        looking = true
        found = runCatching {
            vm.processes.findOpenRun(def.family.ifBlank { def.name }, key)
        }.getOrNull()?.takeIf { it.found }
        looking = false
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

            // The escape hatch, and it is not optional.
            //
            // The pack number is digits, so the digits pad is the default and
            // letters are filtered out — a stray character in this field opens an
            // inspection of a battery that does not exist. But some labels on this
            // site genuinely carry letters, and this is the same field a person
            // falls back to when a label is too scratched to scan. A field that
            // physically cannot express the serial in front of somebody would stop
            // the inspection, which no check in this app is ever allowed to do.
            var lettersToo by remember(definition) { mutableStateOf(false) }
            val digitsOnly = definition?.identifier_keypad == "Numbers Only" && !lettersToo

            OutlinedTextField(
                value = identifier,
                onValueChange = { typed ->
                    // Digits only means digits only — the filter, not just the
                    // keyboard. A hardware keypad, a paste, or an IME that ignores
                    // the hint can all put a letter into a field that is a serial
                    // number.
                    identifier = if (digitsOnly) typed.filter { it.isDigit() } else typed.uppercase()
                },
                label = { Text("$subject number") },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(
                    // NumberPassword, not Number: it is the one type every IME
                    // renders as a plain digits pad. `Number` still shows the full
                    // QWERTY on several of the keyboards these phones ship with,
                    // which is exactly the complaint.
                    keyboardType = if (digitsOnly) KeyboardType.NumberPassword else KeyboardType.Text,
                    imeAction = ImeAction.Done,
                ),
                trailingIcon = if (definition?.identifier_keypad == "Numbers Only") {
                    {
                        TextButton(onClick = { lettersToo = !lettersToo }) {
                            Text(
                                if (lettersToo) "123" else "ABC",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp),
            )

            // Somebody is already on this pack. Said before anything is created,
            // because the alternative — finding out at the end of the shift that
            // one battery has two records — is not recoverable.
            found?.let { open -> PackInProgress(open, looking) }

            error?.let { Text(it, color = FailRed, style = MaterialTheme.typography.bodySmall) }

            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        // Local, and instant, unless this pack is already being
                        // inspected — in which case this joins that inspection
                        // rather than opening a rival copy of it. The server is
                        // idempotent on the UUID minted here *and* dedupes on the
                        // pack label, so neither a retry nor a second phone can
                        // produce a second record.
                        runCatching {
                            vm.inspections.startOrJoinRun(
                                processFamily = processFamily,
                                identifier = identifier.trim().ifBlank { null },
                                online = online,
                            )
                        }
                            .onSuccess { onRunStarted(it.runUuid) }
                            .onFailure { error = it.message }
                        busy = false
                    }
                },
                enabled = !busy && definition != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(10.dp),
                colors = if (found != null) {
                    ButtonDefaults.buttonColors(containerColor = WarnAmber, contentColor = Color.White)
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text(
                    when {
                        found != null -> "Join this inspection"
                        else -> "Start"
                    },
                    fontWeight = FontWeight.Bold,
                )
            }
            }
        }
    }
}

/**
 * "This pack is already being inspected — here is where they got to."
 *
 * The panel that makes joining a decision rather than an accident. It answers the
 * three things somebody standing at a bench with a scanner needs: who is on it,
 * how far along it is, and which modules are still free — so two people can split
 * a fifty-nine check sheet instead of both starting at question one.
 */
@Composable
private fun PackInProgress(open: FoundRun, looking: Boolean) {
    val done = open.stages.count { it.isDone }
    val free = open.stages.filter { !it.isDone && !it.active_now && !it.blocked }
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = WarnAmber.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, WarnAmber),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Already being inspected",
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = WarnAmber,
            )
            Text(
                buildString {
                    append("${open.answered_count} checks done")
                    if (done > 0) append(" · $done ${open.stage_label.lowercase()}s finished")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            open.participants.takeIf { it.isNotEmpty() }?.let { people ->
                Text(
                    // Where the last person got to, in the words an operator
                    // would use to ask.
                    people.first().let { p ->
                        val name = p.full_name.ifBlank { p.user }
                        val where = open.stages.firstOrNull { it.stage_code == p.last_stage }?.label
                        if (where != null) "$name was last in $where" else "$name is on this pack"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (people.size > 1) {
                    Text(
                        "${people.size} people have worked it: " +
                            people.joinToString(", ") { it.full_name.ifBlank { it.user } },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            free.firstOrNull()?.let {
                Text(
                    "Free to pick up: ${free.joinToString(", ") { s -> s.label }}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = PassGreen,
                )
            }
            if (looking) {
                Text(
                    "Checking…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Characters before a scan is worth asking the server about. */
private const val MIN_LOOKUP_CHARS = 3

/** How long typing settles before the pack lookup fires. */
private const val LOOKUP_DEBOUNCE_MS = 350L

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
fun ProcessRunnerScreen(
    vm: AppViewModel,
    runName: String,
    /** Back to the module board — where a submitted module lands, too. */
    onBack: () -> Unit,
    /**
     * The module to work, chosen on the board.
     *
     * Null means "wherever this run is up to", which is what a deep link or an
     * older resume entry gives us. Passing it explicitly is what lets two people
     * be in two different modules of one pack at the same time — the run's own
     * `current_stage` is a single value and cannot represent that.
     */
    stageCode: String? = null,
) {
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
    var pickingFor by remember { mutableStateOf<String?>(null) }
    // What OCR read off the most recent scale photo, per step.
    var ocrReadings by remember { mutableStateOf<Map<String, OcrSuggestion>>(emptyMap()) }
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
    // Null means "not loaded yet", and that distinction is the whole fix for a
    // resume that landed on check one. `emptyMap()` as the initial value is
    // indistinguishable from a run with no answers, so the restore below ran
    // against an empty map on the very first frame — before Room, or before a
    // colleague's answers had been adopted — decided nothing was answered, and
    // latched. It was guaranteed to happen to the second person on a pack.
    val loadedAnswers by (uuid?.let { vm.inspections.answersFlow(it) } ?: flowOf(emptyMap()))
        .collectAsState(initial = null)
    val storedAnswers = loadedAnswers.orEmpty()
    // The photographs themselves, not just how many. Read from Room, so reviewing
    // a shot you have just taken never waits on the upload — which matters most
    // in exactly the dead zones this app is built for.
    val localPhotos by (uuid?.let { vm.inspections.photosFlow(it) } ?: flowOf(emptyList()))
        .collectAsState(initial = emptyList())
    val photosByStep = remember(localPhotos) {
        localPhotos.groupBy { it.stepCode }.mapValues { (_, rows) ->
            rows.sortedBy { it.capturedAt }.map { row ->
                ReviewablePhoto(
                    id = row.photoUuid,
                    localPath = row.path,
                    remoteUrl = row.fileUrl,
                    uploaded = row.fileUrl != null,
                )
            }
        }
    }
    val photoCounts by (uuid?.let { vm.inspections.photoCountsFlow(it) } ?: flowOf(emptyMap()))
        .collectAsState(initial = emptyMap())

    // The names behind Link answers, for display only.
    //
    // A Link answer is a document name — "EXT-013" — which is what the server
    // stores and what every report joins on, and it is not what the operator
    // picked. They picked "Front Windshield — Top". Showing them the code reads
    // as a different answer, and on a review screen it reads as a mistake.
    //
    // Held beside the answers rather than in them because the label is not part
    // of the record: it is re-derived, never sent, and its absence degrades to
    // showing the code.
    val linkLabels = remember(runName) { mutableStateMapOf<String, String>() }

    // Deliberately not `remember`ed: `linkLabels` is snapshot state, and reading
    // it here is what makes a resolved name appear without another trigger.
    val answers: Map<String, StepAnswer> = storedAnswers.mapValues { (code, row) ->
        StepAnswer(
            response = row.response,
            value = row.value,
            remark = row.remark,
            skipped = row.skipped,
            skipReason = row.skipReason,
            valueLabel = linkLabels[code],
        )
    }

    val currentRun = localRun
    val def = definition

    // Resumed runs, and runs a colleague started: the picker never ran on this
    // screen, so the names have to be looked up. One call per Link step, and it
    // falls back to the cached page offline — worst case the code stands.
    LaunchedEffect(def, storedAnswers.keys.joinToString()) {
        val steps = def?.steps.orEmpty().filter { it.response_type == "Link" }
        for (step in steps) {
            val name = storedAnswers[step.step_code]?.response?.takeIf { it.isNotBlank() } ?: continue
            if (linkLabels.containsKey(step.step_code)) continue
            val doctype = step.link_doctype?.takeIf { it.isNotBlank() } ?: continue
            val match = runCatching { vm.processes.linkOptions(doctype, name) }
                .getOrNull()
                ?.firstOrNull { it.value == name }
            if (match != null) linkLabels[step.step_code] = match.label
        }
    }
    val activeStage = stageCode ?: currentRun?.currentStage ?: def?.stages?.firstOrNull()?.stage_code
    val stage = def?.stages?.firstOrNull { it.stage_code == activeStage }
    val screens: List<ProcessScreen> =
        if (def != null && activeStage != null) screensFor(def, activeStage) else emptyList()
    val screen = screens.getOrNull(screenIndex)

    // Open on the check they were last on. Not the first one, and not a guess.
    //
    // "Continue where you left off" was true of the run and false of the screen:
    // resuming a pack with forty answers put the operator back on check one of the
    // module, to tap Next past everything they had already done.
    //
    // The remembered position wins, because it is the only thing that is actually
    // *true* — somebody who stepped back to re-read check 3 and then walked away
    // must come back to check 3, and no rule derived from the answers can know
    // that. The first unanswered check is the fallback for a module this phone has
    // never opened, which is also the right answer when a colleague has been
    // working it from another handset: their answers are in the same store, so
    // their progress carries this operator forward.
    //
    // Keyed on the run and module so it fires once per entry; after that the
    // operator's own navigation owns the position.
    var restored by remember(runName, activeStage) { mutableStateOf(false) }
    LaunchedEffect(screens.size, loadedAnswers, activeStage, currentRun?.lastStep) {
        // Nothing is decided until the store has actually answered. Restoring
        // from a half-loaded screen is what sent the second operator on a pack
        // back to check one with nine checks already filled in.
        if (restored || screens.isEmpty()) return@LaunchedEffect
        if (loadedAnswers == null || currentRun == null) return@LaunchedEffect
        val remembered = currentRun
            ?.takeIf { it.lastStage == activeStage }
            ?.lastStep
            ?.let { step -> screens.indexOfFirst { s -> s.steps.any { it.step_code == step } } }
            ?.takeIf { it >= 0 }

        val firstOpen = screens.indexOfFirst { candidate ->
            candidate.steps.any { !answers[it.step_code].isAnswered(it, photoCounts[it.step_code] ?: 0) }
        }
        // Every check answered and nothing remembered: leave the operator on the
        // last screen rather than the first, so Submit is one tap away instead of
        // a scroll through work that is already done.
        screenIndex = remembered ?: if (firstOpen >= 0) firstOpen else screens.lastIndex
        restored = true
    }

    // Written as they move, never on the way out. There is no reliable "way out"
    // on a phone that gets pocketed mid-check or runs out of battery, and a
    // position saved only on a clean exit is one that is usually never saved.
    LaunchedEffect(screenIndex, uuid, activeStage, restored) {
        if (!restored) return@LaunchedEffect
        val id = uuid ?: return@LaunchedEffect
        val stepCode = screens.getOrNull(screenIndex)?.steps?.firstOrNull()?.step_code
        runCatching { vm.inspections.rememberPosition(id, activeStage, stepCode) }
    }

    // The step the operator is on, across the whole stage — not "screen n of m".
    // With one step per screen those are the same number, and "screen" is a word
    // about the software rather than about the work being done.
    val totalSteps = screens.sumOf { it.steps.size }
    val stepsBefore = screens.take(screenIndex).sumOf { it.steps.size }
    val single = screen?.steps?.size == 1

    photoFor?.let { stepCode ->
        val target = def?.steps?.firstOrNull { it.step_code == stepCode }
        StampingCamera(
            // INWARD or OUTWARD, painted across the top of the picture.
            //
            // Read from whichever step in this process is a direction choice
            // rather than hardcoding a step code: the engine's premise is that a
            // process is data, so the app must not know that the material gate
            // calls its first step DIRECTION. Any process whose answer is the
            // literal INWARD or OUTWARD gets the banner; every other process
            // passes null and the stamp is unchanged.
            banner = answers.values
                .firstNotNullOfOrNull { a ->
                    a.response?.uppercase()?.takeIf { it == "INWARD" || it == "OUTWARD" }
                },
            // The whole title. It wraps inside the stamp now — a photograph of
            // "Check the busbar torque and mark the bolt head" stamped with the
            // first forty characters is a photograph of a different check.
            label = target?.label,
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
                        // A weight step's photograph is of a scale, so read it.
                        // Best effort and entirely on-device: a failed or absent
                        // reading just leaves the operator to type the number,
                        // which is what they would have done anyway.
                        if (forStep.response_type == "Weight from Photo") {
                            WeightOcr.read(context, file)?.let { reading ->
                                ocrReadings = ocrReadings + (
                                    forStep.step_code to OcrSuggestion(
                                        value = reading.kilograms,
                                        sourceText = reading.sourceText,
                                        confident = reading.confidence >= WeightOcr.LOW_CONFIDENCE,
                                    )
                                    )
                            }
                        }
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

    pickingFor?.let { stepCode ->
        val target = def?.steps?.firstOrNull { it.step_code == stepCode }
        val doctype = target?.link_doctype
        if (target == null || doctype.isNullOrBlank()) {
            pickingFor = null
        } else {
            LinkPickerSheet(
                title = target.label,
                allowCreate = target.allow_inline_create == 1,
                fetch = { query -> vm.processes.linkOptions(doctype, query) },
                onCreate = { label ->
                    runCatching { vm.processes.createLinkOption(doctype, label) }.getOrNull()
                },
                onPick = { option ->
                    val existing = answers[stepCode] ?: StepAnswer()
                    linkLabels[stepCode] = option.label
                    // `response` is the document name — what the server stores and
                    // every report joins on. The label rides alongside for display
                    // only, so the operator sees "HVAC Unit" and not "AGG-001".
                    commit(target, existing.copy(response = option.value, valueLabel = option.label))
                    pickingFor = null
                },
                onDismiss = { pickingFor = null },
            )
        }
    }

    // What Next would leave behind, if anything. Held in state so the dialog and
    // the button agree on the reason.
    val advance: () -> Unit = {
        if (screenIndex < screens.lastIndex) {
            screenIndex++
        } else if (uuid != null && activeStage != null) {
            val id = uuid
            scope.launch {
                busy = true
                runCatching { vm.inspections.submitStage(id, activeStage) }
                    .onSuccess { result ->
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
                            // Back to the board, always — not on to the next
                            // module. Which module a person takes next is theirs
                            // to choose when several of them are working one
                            // pack, and marching them into the next one in
                            // sequence is how two operators end up in the same
                            // module while a third sits untouched.
                            onBack()
                        } else {
                            screenIndex = 0
                        }
                    }
                    .onFailure { banner = it.message }
                busy = false
            }
        }
    }

    // The read-back is agreed with and the entry is finished in one tap. Two
    // buttons that both mean "yes, that is right" is one too many, and the
    // second is the one people learn to press without reading. Saving is
    // awaited before submitting: the submit interlock reads the same store the
    // answer is written to, so racing them reports the check as missing.
    val confirmAndFinish: (ProcessStep) -> Unit = { step ->
        uuid?.let { id ->
            scope.launch {
                runCatching {
                    vm.inspections.saveAnswer(runUuid = id, step = step, response = CONFIRMED)
                }.onFailure { banner = it.message }
                advance()
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
                val answered = current.steps.count { s ->
                    answers[s.step_code].isAnswered(s, photoCounts[s.step_code] ?: 0)
                }
                val gate = gateFor(current.steps, answers, photoCounts)
                val reviewStep = current.steps.firstOrNull {
                    it.response_type == REVIEW_STEP && it.supported
                }
                RunnerNavBar(
                    canGoBack = screenIndex > 0,
                    onBack = { screenIndex-- },
                    // A "1 / 1" counter next to a single question is noise.
                    counter = if (single) null else "$answered / ${current.steps.size}",
                    // The read-back has its own button and its own meaning; the
                    // generic "you have not answered this" prompt on top of it
                    // would be a warning about a screen that is nothing but
                    // other screens' answers.
                    gate = if (reviewStep != null) null else gate,
                    nextLabel = when {
                        reviewStep != null -> "Confirm & finish"
                        screenIndex < screens.lastIndex && single -> "Next"
                        screenIndex < screens.lastIndex -> "Next section"
                        else -> "Submit ${stage?.label ?: ""}"
                    },
                    enabled = !busy,
                    // The gate interrupts; it never refuses. Pressing Next with
                    // something missing opens the dialog, and the dialog can
                    // still let the operator through.
                    onNext = {
                        when {
                            reviewStep != null -> confirmAndFinish(reviewStep)
                            gate != null -> pendingGate = gate
                            else -> advance()
                        }
                    },
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
                //
                // Wrapped in a transition, because one check looks exactly like
                // the next one: operators told us they could not tell whether the
                // screen had moved on, and were re-answering checks they had
                // already done. A slide is not decoration here — it is the only
                // cue that anything happened.
                AnimatedContent(
                    targetState = screenIndex,
                    transitionSpec = {
                        val forward = targetState >= initialState
                        val width = { w: Int -> if (forward) w else -w }
                        (
                            slideInHorizontally(tween(220)) { width(it) } + fadeIn(tween(160))
                            ) togetherWith (
                            slideOutHorizontally(tween(220)) { -width(it) } + fadeOut(tween(120))
                            )
                    },
                    label = "check",
                    modifier = Modifier.fillMaxSize(),
                ) { index ->
                val liveScreen = screens.getOrNull(index) ?: current
                val step = liveScreen.steps.first()
                val stepAnswer = answers[step.step_code] ?: StepAnswer()
                val stepHeader: @Composable () -> Unit = {
                    StepProgress(index, screens.size)
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            liveScreen.section.ifBlank { "Checks" }.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        AnsweredChip(stepAnswer)
                    }
                    banner?.let { RunnerBanner(it) }
                }
                if (step.response_type == REVIEW_STEP && step.supported) {
                    // The read-back owns the whole screen: it is a list of every
                    // other question, so drawing it inside the one-question card
                    // would put a scrolling list where the answer buttons live.
                    RunVerifyCard(
                        step = step,
                        lines = verifyLines(screens, index, answers, photosByStep, photoCounts),
                        confirmed = stepAnswer.isAnswered(),
                        onJump = { target -> screenIndex = target },
                        onConfirm = { confirmAndFinish(step) },
                        header = stepHeader,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 18.dp)
                            .padding(top = 10.dp, bottom = 4.dp),
                    )
                    return@AnimatedContent
                }
                RunnerStep(
                    step = step,
                    answer = stepAnswer,
                    onAnswer = { next -> commit(step, next) },
                    onScanRequest = { scanningFor = step.step_code },
                    onPhotoRequest = { photoFor = step.step_code },
                    photoCount = photoCounts[step.step_code] ?: 0,
                    photos = photosByStep[step.step_code].orEmpty(),
                    onPickRequest = { pickingFor = step.step_code },
                    ocrReading = ocrReadings[step.step_code],
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 18.dp)
                        .padding(top = 10.dp, bottom = 4.dp),
                    header = {
                        // Says which of the two screens this is, among other
                        // things. Coming back to a check you have already marked
                        // and being unable to tell is how an answer gets
                        // overwritten by accident.
                        stepHeader()
                        currentRun?.takeIf { it.status in HELD_STATUSES }?.let {
                            QuarantineBanner(it.quarantineReason, it.status)
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
                }
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
                        if (r.status in HELD_STATUSES) {
                            item { QuarantineBanner(r.quarantineReason, r.status) }
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

/**
 * Whether this step has been dealt with.
 *
 * [step] and [photoCount] are optional because most call sites do not have them
 * — but on a `Photo Only` step the photograph *is* the answer and no answer row
 * is ever written for one, so judging that step on its response alone reports a
 * step the operator has just photographed as untouched. That put the resume
 * logic back on a finished check and made the Next gate nag on every photo
 * step. The server makes the same judgement, in `evaluation.evaluate`.
 */
private fun StepAnswer?.isAnswered(step: ProcessStep? = null, photoCount: Int = 0): Boolean {
    if (step?.response_type == PHOTO_ONLY_STEP) return photoCount > 0 || this?.skipped == true
    return this != null && (response != null || !value.isNullOrBlank() || skipped)
}

/** Statuses that owe the operator an explanation before they carry on. */
private val HELD_STATUSES = setOf("Quarantined", "In Rework")

/** Response types this file branches on by name. The server's `constants.py`. */
private const val PHOTO_ONLY_STEP = "Photo Only"
internal const val REVIEW_STEP = "Review & Confirm"

/** What a confirmed read-back stores. Any non-empty response would do; this reads. */
internal const val CONFIRMED = "CONFIRMED"

/**
 * Everything answered so far, read back for the review screen.
 *
 * Built from the screens rather than from the definition's step list so a step
 * hidden by a visibility condition is absent from the summary too — a read-back
 * that lists questions the operator was never asked is a read-back nobody
 * trusts.
 */
private fun verifyLines(
    screens: List<ProcessScreen>,
    reviewIndex: Int,
    answers: Map<String, StepAnswer>,
    photos: Map<String, List<ReviewablePhoto>>,
    photoCounts: Map<String, Int>,
): List<VerifyLine> = screens.take(reviewIndex).flatMapIndexed { index, screen ->
    screen.steps
        .filter { it.response_type != "Section Note" && it.response_type != REVIEW_STEP }
        .map { step ->
            val answer = answers[step.step_code]
            val count = photoCounts[step.step_code] ?: 0
            VerifyLine(
                screenIndex = index,
                displayNo = step.display_no?.takeIf { it.isNotBlank() } ?: "${index + 1}",
                label = step.label,
                answer = answerText(step, answer, count),
                answered = answer.isAnswered(step, count) && answer?.skipped != true,
                photos = photos[step.step_code].orEmpty(),
            )
        }
}

/** One answer in the words the operator used, not the words the database stores. */
private fun answerText(step: ProcessStep, answer: StepAnswer?, photoCount: Int): String {
    if (answer?.skipped == true) {
        return "Skipped — " + (answer.skipReason?.takeIf { it.isNotBlank() } ?: "no reason given")
    }
    val unit = step.unit?.trim().orEmpty()
    return when (step.response_type) {
        PHOTO_ONLY_STEP -> when (photoCount) {
            0 -> "No photo yet"
            1 -> "1 photo"
            else -> "$photoCount photos"
        }
        // The label, never the document name. An operator who picked "HVAC Unit"
        // did not pick "AGG-001", and showing them the code reads as a different
        // answer — see StepAnswer.valueLabel.
        "Link" -> answer?.valueLabel?.takeIf { it.isNotBlank() }
            ?: answer?.response?.takeIf { it.isNotBlank() }
            ?: "Not chosen"
        "Choice", "Choice Multi", "Yes No" -> answer?.response
            ?.split(",")
            ?.mapNotNull { pick ->
                val key = pick.trim()
                step.options.firstOrNull { it.value == key }?.label ?: key.ifBlank { null }
            }
            ?.joinToString(", ")
            ?.takeIf { it.isNotBlank() }
            ?: "Not answered"
        else -> {
            val raw = answer?.value?.takeIf { it.isNotBlank() }
                ?: answer?.response?.takeIf { it.isNotBlank() }
                ?: return "Not entered"
            if (unit.isNotEmpty()) "$raw $unit" else raw
        }
    }
}

/** The first thing missing on this screen, or null when it is complete. */
private fun gateFor(
    steps: List<ProcessStep>,
    answers: Map<String, StepAnswer>,
    photoCounts: Map<String, Int>,
): RunnerGate? {
    if (steps.any { !answers[it.step_code].isAnswered(it, photoCounts[it.step_code] ?: 0) }) {
        return RunnerGate.NOT_ANSWERED
    }
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

/**
 * Whether this check already carries an answer.
 *
 * Two checks in a row look identical from a metre away — same layout, same
 * buttons, same colours — so an operator coming back to one they have already
 * marked had no way to tell it from a fresh one, and the safe assumption
 * ("answer it again") quietly overwrites work. This is the difference, stated
 * where the eye already is.
 */
@Composable
private fun AnsweredChip(answer: StepAnswer) {
    val (text, tint) = when {
        answer.skipped -> "SKIPPED" to WarnAmber
        !answer.response.isNullOrBlank() -> "ANSWERED · ${answer.response.uppercase()}" to PassGreen
        !answer.value.isNullOrBlank() -> "ANSWERED" to PassGreen
        else -> return
    }
    Surface(shape = RoundedCornerShape(8.dp), color = tint.copy(alpha = 0.14f)) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = tint,
        )
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
private fun QuarantineBanner(reason: String?, status: String? = null) {
    // Two different messages, because they are two different situations and the
    // operator's next move differs. Quarantined means stop; sent back means the
    // verifier has told you what to redo, and the words are theirs.
    val rework = status == "In Rework"
    val tint = if (rework) WarnAmber else FailRed
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = tint.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, tint),
    ) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (rework) Icons.Rounded.Refresh else Icons.Rounded.Lock,
                contentDescription = null,
                tint = tint,
            )
            Column {
                Text(
                    if (rework) "Sent back by the verifier" else "Quarantined for QC review",
                    fontWeight = FontWeight.SemiBold,
                    color = tint,
                )
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


/**
 * "Twelve packs are waiting for you." The way into the verification queue.
 *
 * At the very top, above the operator's own work, and absent entirely when the
 * count is nought. A verifier's outstanding queue is somebody else's despatch
 * waiting — it outranks anything this person might start next.
 */
@Composable
private fun VerifyQueueCard(count: Int, onClick: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = WarnAmber.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, WarnAmber),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.FactCheck, contentDescription = null, tint = WarnAmber)
            Column(Modifier.weight(1f)) {
                Text(
                    if (count == 1) "1 pack to verify" else "$count packs to verify",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Waiting on your sign-off",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = null,
                tint = WarnAmber,
            )
        }
    }
}
