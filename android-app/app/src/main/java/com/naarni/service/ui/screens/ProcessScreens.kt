package com.naarni.service.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naarni.service.data.dto.OpenRun
import com.naarni.service.data.dto.ProcessDefinition
import com.naarni.service.data.dto.ProcessRun
import com.naarni.service.data.dto.ProcessSummary
import com.naarni.service.data.repo.ProcessScreen
import com.naarni.service.data.repo.screensFor
import com.naarni.service.ui.AppViewModel
import com.naarni.service.ui.components.EmptyState
import com.naarni.service.ui.components.LoadingOverlay
import com.naarni.service.ui.components.SectionHeader
import com.naarni.service.ui.components.StepAnswer
import com.naarni.service.ui.components.StepCard
import kotlinx.coroutines.launch
import java.util.UUID

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
fun ProcessListScreen(vm: AppViewModel, onOpenProcess: (String) -> Unit, onResumeRun: (String) -> Unit) {
    var processes by remember { mutableStateOf<List<ProcessSummary>>(emptyList()) }
    var openRuns by remember { mutableStateOf<List<OpenRun>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        runCatching { vm.processes.listProcesses() }
            .onSuccess { processes = it }
            .onFailure { error = it.message }
        runCatching { vm.processes.openRuns() }.onSuccess { openRuns = it }
        loading = false
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
        ) {
            if (openRuns.isNotEmpty()) {
                item { SectionHeader("Continue where you left off") }
                items(openRuns) { run ->
                    Card(
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
            }

            item { SectionHeader("Available to you") }

            if (!loading && processes.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Default.BatteryChargingFull,
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
        LoadingOverlay(loading)
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

    LaunchedEffect(processFamily) {
        runCatching { vm.processes.definition(processFamily) }
            .onSuccess { definition = it }
            .onFailure { error = it.message }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(definition?.process_name ?: "Start") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val subject = definition?.subject_label ?: "item"

            Surface(
                Modifier
                    .fillMaxWidth()
                    .height(150.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Scan the $subject label",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "Scanning is optional — you can type it instead",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedTextField(
                value = identifier,
                onValueChange = { identifier = it.uppercase() },
                label = { Text("$subject number") },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            error?.let { Text(it, color = FailRed, style = MaterialTheme.typography.bodySmall) }

            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        runCatching {
                            vm.processes.startRun(
                                process = processFamily,
                                identifier = identifier.trim().ifBlank { null },
                                // Idempotency key: a retry over a flaky plant
                                // network resumes rather than creating a twin.
                                clientUuid = UUID.randomUUID().toString(),
                            )
                        }
                            .onSuccess { onRunStarted(it.name) }
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
    var run by remember { mutableStateOf<ProcessRun?>(null) }
    var definition by remember { mutableStateOf<ProcessDefinition?>(null) }
    var screenIndex by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(true) }
    var banner by remember { mutableStateOf<String?>(null) }
    var showAlt by remember { mutableStateOf(true) }
    val answers = remember { mutableStateMapOf<String, StepAnswer>() }

    LaunchedEffect(runName) {
        busy = true
        runCatching {
            val loaded = vm.processes.run(runName)
            val def = vm.processes.definition(loaded.process_definition)
            loaded to def
        }.onSuccess { (loaded, def) ->
            run = loaded
            definition = def
            // Rehydrate prior answers so a resumed run shows what was recorded.
            loaded.results.forEach { row ->
                answers[row.step_code] = StepAnswer(
                    response = row.response,
                    value = row.value_numeric?.toString(),
                    remark = row.remark,
                    skipped = row.is_skipped == 1,
                    skipReason = row.skip_reason,
                )
            }
        }.onFailure { banner = it.message }
        busy = false
    }

    val currentRun = run
    val def = definition
    val stageCode = currentRun?.current_stage ?: def?.stages?.firstOrNull()?.stage_code
    val stage = def?.stages?.firstOrNull { it.stage_code == stageCode }
    val screens: List<ProcessScreen> = if (def != null && stageCode != null) screensFor(def, stageCode) else emptyList()
    val screen = screens.getOrNull(screenIndex)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stage?.label ?: def?.process_name.orEmpty(), style = MaterialTheme.typography.titleMedium)
                        if (screens.isNotEmpty()) {
                            Text(
                                "${def?.stage_label ?: "Stage"} · screen ${screenIndex + 1} of ${screens.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { showAlt = !showAlt }) { Text(if (showAlt) "EN" else "हिं") }
                },
            )
        },
        bottomBar = {
            if (screen != null) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val answered = screen.steps.count { answers[it.step_code]?.let { a -> a.response != null || a.value != null || a.skipped } == true }
                        Text(
                            "$answered / ${screen.steps.size}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = {
                                if (screenIndex < screens.lastIndex) {
                                    screenIndex++
                                } else if (currentRun != null && stageCode != null) {
                                    scope.launch {
                                        busy = true
                                        runCatching { vm.processes.submitStage(currentRun.name, stageCode) }
                                            .onSuccess { updated ->
                                                run = updated
                                                screenIndex = 0
                                                banner = null
                                                if (updated.status in setOf("Passed", "Quarantined", "Awaiting Verification")) {
                                                    onFinished()
                                                }
                                            }
                                            .onFailure { banner = it.message }
                                        busy = false
                                    }
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(if (screenIndex < screens.lastIndex) "Next section" else "Submit ${stage?.label ?: ""}")
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
            ) {
                item {
                    LinearProgressIndicator(
                        progress = { if (screens.isEmpty()) 0f else (screenIndex + 1f) / screens.size },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                    )
                }

                currentRun?.let { r ->
                    item { RunSummaryStrip(r) }
                    if (r.status == "Quarantined") {
                        item { QuarantineBanner(r.quarantine_reason) }
                    }
                }

                banner?.let { message ->
                    item {
                        Surface(
                            Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = FailRed.copy(alpha = 0.12f),
                        ) {
                            Text(message, Modifier.padding(12.dp), color = FailRed, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                screen?.let { current ->
                    item { SectionHeader(current.section.ifBlank { "Checks" }) }
                    items(current.steps, key = { it.step_code }) { step ->
                        val answer = answers[step.step_code] ?: StepAnswer()
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            StepCard(
                                step = step,
                                answer = answer,
                                showAltLanguage = showAlt,
                                onAnswer = { next ->
                                    answers[step.step_code] = next
                                    val rn = currentRun?.name ?: return@StepCard
                                    scope.launch {
                                        runCatching {
                                            vm.processes.saveResult(
                                                run = rn,
                                                stepCode = step.step_code,
                                                response = next.response,
                                                value = next.value,
                                                remark = next.remark,
                                                skipped = next.skipped,
                                                skipReason = next.skipReason,
                                            )
                                        }.onSuccess { saved ->
                                            run = run?.copy(
                                                status = saved.run.status,
                                                score_pct = saved.run.score_pct,
                                                pass_count = saved.run.pass_count,
                                                fail_count = saved.run.fail_count,
                                                critical_count = saved.run.critical_count,
                                            )
                                            // A computed step is derived on the
                                            // server, so echo its value back.
                                            saved.result.value_numeric?.let { v ->
                                                if (step.response_type == "Computed") {
                                                    answers[step.step_code] = next.copy(value = v.toString())
                                                }
                                            }
                                        }.onFailure { banner = it.message }
                                    }
                                },
                            )
                            if (step.allow_skip == 1 && step.skip_reasons.isNotEmpty()) {
                                SkipRow(step.skip_reasons) { reason ->
                                    val next = StepAnswer(skipped = true, skipReason = reason)
                                    answers[step.step_code] = next
                                    val rn = currentRun?.name ?: return@SkipRow
                                    scope.launch {
                                        runCatching {
                                            vm.processes.saveResult(
                                                run = rn,
                                                stepCode = step.step_code,
                                                skipped = true,
                                                skipReason = reason,
                                            )
                                        }.onFailure { banner = it.message }
                                    }
                                }
                            }
                        }
                    }
                }

                if (screens.isEmpty() && !busy) {
                    item {
                        EmptyState(
                            Icons.Default.CheckCircle,
                            "Nothing left here",
                            currentRun?.let { "This run is ${it.status.lowercase()}." } ?: "No checks in this stage.",
                        )
                    }
                }
            }
            LoadingOverlay(busy)
        }
    }
}

@Composable
private fun RunSummaryStrip(run: ProcessRun) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        SummaryTile("Passed", run.pass_count.toString(), PassGreen, Modifier.weight(1f))
        SummaryTile("Failed", run.fail_count.toString(), FailRed, Modifier.weight(1f))
        SummaryTile("Score", "${run.score_pct.toInt()}%", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
        SummaryTile("Trace", "${run.trace_completeness_pct.toInt()}%", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
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
            Icon(Icons.Default.Lock, contentDescription = null, tint = FailRed)
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
