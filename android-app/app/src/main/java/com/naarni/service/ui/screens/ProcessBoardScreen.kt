package com.naarni.service.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.People
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naarni.service.core.inspection.InspectionWork
import com.naarni.service.data.dto.BoardStage
import com.naarni.service.data.dto.ProcessDefinition
import com.naarni.service.data.dto.RunBoard
import com.naarni.service.data.dto.RunParticipant
import com.naarni.service.data.inspection.LocalAnswerEntity
import com.naarni.service.ui.AppViewModel
import com.naarni.service.ui.components.EmptyState
import com.naarni.service.ui.components.LoadingOverlay
import com.naarni.service.ui.components.OfflineStrip
import com.naarni.service.ui.components.SectionHeader
import com.naarni.service.ui.theme.AppSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import com.naarni.service.ui.theme.Radii

/**
 * The pack, before the questions: which modules are done, and who is on them.
 *
 * This screen exists because a battery is fifty-nine checks and a shift puts more
 * than one person on it. An operator arriving at a pack somebody else has been
 * working needs three facts, in this order — what is finished, what is being
 * worked right now, and what is free for them — and a stepper that opens straight
 * onto question one can tell them none of it. Opening on the board is what makes
 * two people at one bench able to divide the work instead of colliding over it.
 *
 * **Progress is local; presence is remote.** Every count here is computed from
 * this handset's own answers against the cached definition, so the board is
 * complete and correct in a shed with no signal. What the network adds is the one
 * thing the device cannot know on its own: what a colleague has been doing on the
 * same pack from another phone. When that is unavailable the board simply shows
 * fewer names — it never shows fewer modules or a wrong count.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessBoardScreen(
    vm: AppViewModel,
    runName: String,
    onOpenModule: (runUuid: String, stageCode: String) -> Unit,
    onBack: () -> Unit,
    onFinished: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var runUuid by remember(runName) { mutableStateOf<String?>(null) }
    var definition by remember { mutableStateOf<ProcessDefinition?>(null) }
    var board by remember { mutableStateOf<RunBoard?>(null) }
    var busy by remember { mutableStateOf(true) }
    var banner by remember { mutableStateOf<String?>(null) }

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
            definition = vm.inspections.run(resolved)?.let { vm.inspections.definition(it.definitionName) }
        }
        busy = false
    }

    // Polled while the board is open. This is what makes a colleague's progress
    // appear without anybody pulling to refresh — the moment that matters is the
    // one where two people are choosing which module to pick up.
    LaunchedEffect(runUuid, online) {
        val id = runUuid ?: return@LaunchedEffect
        while (online) {
            board = vm.inspections.serverBoard(id) ?: board
            delay(POLL_MS)
        }
    }

    val uuid = runUuid
    val localRun by (uuid?.let { vm.inspections.runFlow(it) } ?: flowOf(null)).collectAsState(initial = null)
    val answers by (uuid?.let { vm.inspections.answersFlow(it) } ?: flowOf(emptyMap()))
        .collectAsState(initial = emptyMap())

    val def = definition
    val modules = remember(def, answers, board) { def?.let { modulesFor(it, answers, board) } ?: emptyList() }
    val answered = modules.sumOf { it.answered }
    val total = modules.sumOf { it.total }
    val allDone = modules.isNotEmpty() && modules.all { it.isDone || it.blocked }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            // The pack number, whole. It is the one string on this
                            // screen that identifies a physical battery, and a
                            // serial with its tail clipped identifies nothing.
                            localRun?.identifier ?: board?.run_identifier ?: "Inspection",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            localRun?.processName ?: board?.process_name.orEmpty(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            if (allDone && uuid != null) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Button(
                        onClick = {
                            scope.launch {
                                InspectionWork.sync(context, uuid)
                                onFinished()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                            .height(60.dp),
                        shape = Radii.lg,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PassGreen,
                            contentColor = Color.White,
                        ),
                    ) {
                        Text("Everything done — send it in", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                OfflineStrip(online)
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 14.dp),
                ) {
                    banner?.let { message -> item { BoardBanner(message) } }

                    localRun?.takeIf { it.status == "Quarantined" }?.let { run ->
                        item { BoardQuarantine(run.quarantineReason) }
                    }

                    item { PackProgress(answered, total) }

                    board?.participants?.takeIf { it.isNotEmpty() }?.let { people ->
                        item { WhoIsOnThisPack(people) }
                    }

                    item { SectionHeader(def?.stage_label?.plus("s") ?: "Modules") }

                    items(modules, key = { it.stage_code }) { module ->
                        ModuleCard(
                            module = module,
                            // The module this operator was last in, on this phone.
                            // Marked rather than auto-opened: several people work
                            // one pack, and jumping somebody straight back into a
                            // module a colleague has since finished would be worse
                            // than letting them choose.
                            youWereHere = localRun?.lastStage == module.stage_code,
                            onClick = {
                                uuid?.let { onOpenModule(it, module.stage_code) }
                            },
                        )
                    }

                    if (modules.isEmpty() && !busy) {
                        item {
                            EmptyState(
                                Icons.Rounded.CheckCircle,
                                "Nothing to do here",
                                banner ?: "This process has no modules on this phone yet.",
                            )
                        }
                    }
                }
            }
            LoadingOverlay(busy)
        }
    }
}

/** How often the board asks the server what everyone else has been doing. */
private const val POLL_MS = 8_000L

/**
 * Merge what this phone knows with what the server knows.
 *
 * Local counts win, always. They are computed from the same answers the runner is
 * about to show and they are right with no network; the server's copy can be
 * minutes stale or absent. What the server contributes is the half the device
 * cannot see — a colleague's answers from another handset, and who they were.
 */
private fun modulesFor(
    definition: ProcessDefinition,
    answers: Map<String, LocalAnswerEntity>,
    board: RunBoard?,
): List<BoardStage> {
    val remote = board?.stages?.associateBy { it.stage_code }.orEmpty()

    return definition.stages.sortedBy { it.sequence }.map { stage ->
        val steps = definition.steps.filter { it.stage == stage.stage_code && it.response_type != "Section Note" }
        val localAnswered = steps.count { answers[it.step_code]?.localAnswered == true }
        val server = remote[stage.stage_code]

        // The higher of the two counts, not the local one: a colleague's twelve
        // answers are real work on this pack, and a board that showed 0 of 12 for
        // a module somebody else has finished would send two people to redo it.
        val answeredCount = maxOf(localAnswered, server?.answered ?: 0)
        val totalCount = if (steps.isNotEmpty()) steps.size else (server?.total ?: 0)

        BoardStage(
            stage_code = stage.stage_code,
            label = stage.label.ifBlank { stage.stage_code },
            sequence = stage.sequence,
            total = totalCount,
            answered = minOf(answeredCount, totalCount.coerceAtLeast(answeredCount)),
            outstanding = server?.outstanding ?: (totalCount - localAnswered).coerceAtLeast(0),
            blocked = server?.blocked ?: false,
            submitted = server?.submitted ?: false,
            submitted_by = server?.submitted_by,
            submitted_at = server?.submitted_at,
            last_user = server?.last_user,
            last_by = server?.last_by,
            last_at = server?.last_at,
            active_now = server?.active_now ?: false,
        )
    }
}

@Composable
private fun PackProgress(answered: Int, total: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$answered of $total checks done",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        LinearProgressIndicator(
            progress = { if (total <= 0) 0f else answered.toFloat() / total },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(Radii.xs),
            trackColor = AppSurface.sunken,
        )
    }
}

/**
 * Who is working this battery.
 *
 * Initials rather than photographs: this is read at arm's length in a bright
 * plant, and a row of 28dp faces is unreadable there while two bold letters is
 * not. The names follow underneath, in full, because "RS" alone identifies
 * nobody on a shift with two of them.
 */
@Composable
private fun WhoIsOnThisPack(people: List<RunParticipant>) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = Radii.lg,
        color = AppSurface.sunken,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Rounded.People,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    when (people.size) {
                        1 -> "You are the only one on this pack"
                        2 -> "2 people on this pack"
                        else -> "${people.size} people on this pack"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                people.take(6).forEach { person -> InitialsChip(person.full_name.ifBlank { person.user }) }
            }
            Text(
                people.joinToString(", ") { p ->
                    val name = p.full_name.ifBlank { p.user }
                    if (p.answers > 0) "$name (${p.answers})" else name
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InitialsChip(name: String) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initialsOf(name),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun initialsOf(name: String): String {
    val parts = name.trim().split(" ").filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(2).uppercase()
        else -> "${parts.first().first()}${parts.last().first()}".uppercase()
    }
}

/**
 * One module, as a row you can pick up.
 *
 * The state reads in one glance from three places at once — the leading glyph,
 * the bar, and the line underneath — because in a plant a card is read from
 * further away than a designer ever tests at.
 */
@Composable
private fun ModuleCard(module: BoardStage, youWereHere: Boolean, onClick: () -> Unit) {
    val tint = when {
        module.blocked -> FailRed
        module.isDone -> PassGreen
        module.active_now -> WarnAmber
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        Modifier
            .fillMaxWidth()
            .clip(Radii.lg)
            .clickable(enabled = !module.blocked, onClick = onClick),
        shape = Radii.lg,
        color = AppSurface.raised,
        border = BorderStroke(if (module.active_now) 2.dp else 1.dp, if (module.active_now) WarnAmber else AppSurface.hairline),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                when {
                    module.blocked -> Icons.Rounded.Lock
                    module.isDone -> Icons.Rounded.CheckCircle
                    else -> Icons.AutoMirrored.Rounded.ArrowForward
                },
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(26.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        module.label,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (youWereHere && !module.isDone) {
                        Surface(
                            shape = Radii.md,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        ) {
                            Text(
                                "YOU WERE HERE",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                LinearProgressIndicator(
                    progress = { module.fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(Radii.xs),
                    color = tint,
                    trackColor = AppSurface.sunken,
                )
                Text(
                    buildString {
                        append("${module.answered} of ${module.total}")
                        when {
                            module.blocked -> append(" · locked pending review")
                            module.submitted ->
                                append(" · submitted${module.submitted_by?.let { " by $it" }.orEmpty()}")
                            module.active_now && module.last_by != null ->
                                append(" · ${module.last_by} is here now")
                            module.last_by != null -> append(" · last: ${module.last_by}")
                            module.answered == 0 -> append(" · not started")
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (module.active_now) WarnAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BoardBanner(message: String) {
    Surface(Modifier.fillMaxWidth(), shape = Radii.lg, color = FailRed.copy(alpha = 0.12f)) {
        Text(message, Modifier.padding(12.dp), color = FailRed, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun BoardQuarantine(reason: String?) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = Radii.lg,
        color = FailRed.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, FailRed),
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = FailRed)
            Column {
                Text("Quarantined for QC review", fontWeight = FontWeight.SemiBold, color = FailRed)
                // The remaining checks are still recordable: a pack that failed
                // early still has to be inspected, or nobody learns what else was
                // wrong with it.
                Text(
                    reason ?: "Finish the remaining checks — the pack is held either way.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
