package com.naarni.service.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.HistoryToggleOff
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.naarni.service.BuildConfig
import com.naarni.service.data.dto.HistoryRun
import com.naarni.service.data.dto.HistoryStats
import com.naarni.service.data.dto.ProcessHistory
import com.naarni.service.data.dto.ReportPhoto
import com.naarni.service.data.dto.ReportStep
import com.naarni.service.data.dto.RunReport
import com.naarni.service.ui.AppViewModel
import com.naarni.service.ui.components.AppBar
import com.naarni.service.ui.components.EmptyState
import com.naarni.service.ui.components.HairlineDivider
import com.naarni.service.ui.components.LoadingOverlay
import com.naarni.service.ui.theme.AppSurface

/**
 * What this operator has actually done.
 *
 * Two screens: a list of their own inspections with the count at the top, and
 * one report showing every answer they gave and every photo they took.
 *
 * The audience is the person who did the work, not a supervisor. That decides
 * most of the design: no filters by depot or by operator, no export, no charts —
 * just "how many have I done" and "what did I put on that one", which are the
 * two questions someone actually asks about their own record. The server scopes
 * every query to the caller, so there is nothing here to leak.
 */

private val PassTone = Color(0xFF17784A)
private val FailTone = Color(0xFFB62F27)
private val WarnTone = Color(0xFF99630A)

private fun fullUrl(fileUrl: String?): String? =
    fileUrl?.takeIf { it.isNotBlank() }?.let {
        if (it.startsWith("http")) it else BuildConfig.BASE_URL.trimEnd('/') + it
    }

// ---------------------------------------------------------------- the list

@Composable
fun ProcessHistoryScreen(vm: AppViewModel, onBack: () -> Unit, onOpenRun: (String) -> Unit) {
    var history by remember { mutableStateOf(ProcessHistory()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        runCatching { vm.processes.history() }
            .onSuccess { history = it }
            .onFailure { error = it.message }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        AppBar(title = "My inspections", onBack = onBack)
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { StatsHeader(history.stats) }

                if (!loading && history.runs.isEmpty()) {
                    item {
                        EmptyState(
                            Icons.Rounded.HistoryToggleOff,
                            "No finished inspections yet",
                            error ?: "Runs you complete will be listed here with the photos you took.",
                        )
                    }
                }

                items(history.runs, key = { it.name }) { run ->
                    HistoryCard(run) { onOpenRun(run.name) }
                }
            }
            LoadingOverlay(loading)
        }
    }
}

/**
 * The tally, first thing on the screen.
 *
 * "Today" is deliberately the largest number. Over a shift it is the one that
 * changes, and a total that only creeps up by one every twenty minutes tells an
 * operator nothing about how their day is going.
 */
@Composable
private fun StatsHeader(stats: HistoryStats) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.primary,
        ) {
            Row(
                Modifier.padding(vertical = 18.dp, horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${stats.today}",
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Text(
                        if (stats.today == 1) "inspection today" else "inspections today",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${stats.week}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Text(
                        "last 7 days",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("All time", "${stats.total}", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            StatTile("Passed", "${stats.passed}", PassTone, Modifier.weight(1f))
            StatTile("Held", "${stats.quarantined}", FailTone, Modifier.weight(1f))
            StatTile("Open", "${stats.open}", WarnTone, Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, tint: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier,
        shape = RoundedCornerShape(14.dp),
        color = AppSurface.raised,
        border = BorderStroke(1.dp, AppSurface.hairline),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = tint)
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One inspection, recognisable at a glance.
 *
 * The thumbnail earns its 72dp: a list of runs is otherwise a list of serial
 * numbers that differ in the last three characters, and nobody scans that to
 * find the pack they remember. The photo is the memory hook.
 */
@Composable
private fun HistoryCard(run: HistoryRun, onClick: () -> Unit) {
    val tone = statusTone(run.status)
    Surface(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = AppSurface.raised,
        border = BorderStroke(1.dp, AppSurface.hairline),
    ) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(AppSurface.sunken),
                contentAlignment = Alignment.Center,
            ) {
                val thumb = fullUrl(run.thumb)
                if (thumb != null) {
                    AsyncImage(
                        model = thumb,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        Icons.Rounded.CameraAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    run.run_identifier?.takeIf { it.isNotBlank() } ?: run.name,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    run.process_name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(run.status, tone)
                    Text(
                        buildString {
                            append("${run.answered_count} answered")
                            if (run.fail_count > 0) append(" · ${run.fail_count} failed")
                            if (run.photo_count > 0) append(" · ${run.photo_count} 📷")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                shortWhen(run.completed_at ?: run.started_at),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusChip(status: String, tone: Color) {
    Surface(color = tone.copy(alpha = 0.14f), shape = RoundedCornerShape(6.dp)) {
        Text(
            status,
            Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = tone,
        )
    }
}

private fun statusTone(status: String): Color = when (status) {
    "Passed" -> PassTone
    "Quarantined" -> FailTone
    else -> WarnTone
}

/** "14 Aug · 09:42" from a Frappe datetime, without dragging in a formatter. */
private fun shortWhen(raw: String?): String {
    val s = raw?.takeIf { it.length >= 16 } ?: return ""
    val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val month = s.substring(5, 7).toIntOrNull()?.minus(1)?.takeIf { it in months.indices } ?: return ""
    return "${s.substring(8, 10)} ${months[month]}\n${s.substring(11, 16)}"
}

// -------------------------------------------------------------- the report

/**
 * One inspection, read back.
 *
 * Every answered step is listed with the answer the operator gave and the photos
 * they took at it — not a summary. "What did I fill" is a question about the
 * individual rows, and a score percentage cannot answer it.
 */
@Composable
fun ProcessRunReportScreen(vm: AppViewModel, runName: String, onBack: () -> Unit) {
    var report by remember { mutableStateOf<RunReport?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var viewing by remember { mutableStateOf<ReportPhoto?>(null) }

    LaunchedEffect(runName) {
        loading = true
        runCatching { vm.processes.report(runName) }
            .onSuccess { report = it }
            .onFailure { error = it.message }
        loading = false
    }

    viewing?.let { photo ->
        PhotoViewer(photo) { viewing = null }
    }

    Column(Modifier.fillMaxSize()) {
        AppBar(
            title = report?.run_identifier?.takeIf { it.isNotBlank() } ?: "Inspection",
            subtitle = report?.process_name,
            onBack = onBack,
        )
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                report?.let { r ->
                    item { ReportHeader(r) }

                    r.stages.forEach { stage ->
                        item {
                            Text(
                                stage.label.uppercase(),
                                Modifier.padding(top = 10.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        items(stage.steps, key = { "${stage.stage}:${it.step_code}" }) { step ->
                            AnsweredStepCard(step) { viewing = it }
                        }
                    }

                    if (r.unmatched_photos.isNotEmpty()) {
                        item {
                            Text(
                                "OTHER PHOTOS",
                                Modifier.padding(top = 10.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        items(r.unmatched_photos, key = { it.step_code }) { group ->
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(group.label, style = MaterialTheme.typography.bodyMedium)
                                PhotoStrip(group.photos) { viewing = it }
                            }
                        }
                    }
                }

                if (!loading && report == null) {
                    item {
                        EmptyState(
                            Icons.Rounded.Warning,
                            "Could not open this inspection",
                            error ?: "It may have been removed.",
                        )
                    }
                }
            }
            LoadingOverlay(loading)
        }
    }
}

@Composable
private fun ReportHeader(r: RunReport) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = AppSurface.raised,
        border = BorderStroke(1.dp, AppSurface.hairline),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(r.status, statusTone(r.status))
                Text(
                    "${r.score_pct.toInt()}%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            r.quarantine_reason?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = FailTone)
            }
            HairlineDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                MiniStat("Passed", "${r.pass_count}", PassTone)
                MiniStat("Failed", "${r.fail_count}", FailTone)
                MiniStat("Skipped", "${r.skip_count}", WarnTone)
                MiniStat("Photos", "${r.photo_count}", MaterialTheme.colorScheme.onSurface)
            }
            Text(
                buildString {
                    r.started_by_name?.let { append(it) }
                    // A quarantined run has no completion time — falling back to
                    // the start keeps a date on every report rather than showing
                    // a bare name on exactly the runs worth going back to.
                    (r.completed_at ?: r.started_at)?.let {
                        append(" · ${it.take(16).replace("T", " ")}")
                    }
                    r.station?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, tint: Color) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = tint)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AnsweredStepCard(step: ReportStep, onOpenPhoto: (ReportPhoto) -> Unit) {
    val unanswered = !step.wasAnswered()
    val tone = when {
        step.is_skipped == 1 -> WarnTone
        unanswered -> MaterialTheme.colorScheme.onSurfaceVariant
        step.is_pass == 1 -> PassTone
        else -> FailTone
    }
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = AppSurface.raised,
        border = BorderStroke(1.dp, AppSurface.hairline),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                step.display_no?.takeIf { it.isNotBlank() && it != "—" }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    step.label,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            // The answer, in the operator's own terms — the option they tapped or
            // the number they typed, with its unit and the band it was checked
            // against. A bare "Fail" tells them nothing a week later.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    when {
                        unanswered -> Icons.Rounded.RadioButtonUnchecked
                        step.is_pass == 1 -> Icons.Rounded.CheckCircle
                        else -> Icons.Rounded.Warning
                    },
                    contentDescription = null,
                    tint = tone,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    answerText(step),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = tone,
                )
                step.spec_summary?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            step.remark?.takeIf { it.isNotBlank() }?.let {
                Text("“$it”", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (step.photos.isNotEmpty()) PhotoStrip(step.photos, onOpenPhoto)
        }
    }
}

/** Step types where a number is the answer — and where 0 is a real one. */
private val NUMERIC_TYPES = setOf("Number", "Number in Range", "Number with Tolerance", "Computed")

/**
 * Did the operator put anything here?
 *
 * `value_numeric` cannot be trusted on its own. MariaDB stores an untouched
 * Float column as 0.0 rather than NULL, so every unanswered step arrives
 * carrying a zero — which rendered a blank scan step as the answer "0", with a
 * red warning beside it. A record that invents an answer nobody gave is worse
 * than one that admits the gap.
 */
private fun ReportStep.wasAnswered(): Boolean = when {
    is_skipped == 1 -> true
    !response.isNullOrBlank() -> true
    !value_text.isNullOrBlank() -> true
    else -> value_numeric != null && response_type in NUMERIC_TYPES
}

private fun answerText(step: ReportStep): String = when {
    step.is_skipped == 1 -> "Skipped — ${step.skip_reason ?: "no reason given"}"
    !step.response.isNullOrBlank() -> step.response
    !step.value_text.isNullOrBlank() -> step.value_text
    step.value_numeric != null && step.response_type in NUMERIC_TYPES -> {
        val n = step.value_numeric
        val shown = if (n % 1.0 == 0.0) n.toInt().toString() else n.toString()
        listOfNotNull(shown, step.unit?.takeIf { it.isNotBlank() }).joinToString(" ")
    }
    else -> "Not answered"
}

@Composable
private fun PhotoStrip(photos: List<ReportPhoto>, onOpen: (ReportPhoto) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(photos) { photo ->
            Box(
                Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppSurface.sunken)
                    .clickable { onOpen(photo) },
            ) {
                AsyncImage(
                    model = fullUrl(photo.file_url),
                    contentDescription = photo.caption,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // A pin on the corner when the shot carries a fix. The serial and
                // the coordinates are already burnt into the pixels; this only
                // says the *record* has them too, which is what a report can query.
                if (photo.latitude != null) {
                    Icon(
                        Icons.Rounded.LocationOn,
                        contentDescription = "Has location",
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .size(16.dp),
                    )
                }
            }
        }
    }
}

/** Full-bleed photo, with the metadata the pixels already carry spelled out. */
@Composable
private fun PhotoViewer(photo: ReportPhoto, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(18.dp), color = AppSurface.raised) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth()) {
                    Text(
                        photo.caption ?: "Photo",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Close",
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(22.dp)
                            .clickable(onClick = onClose),
                    )
                }
                AsyncImage(
                    model = fullUrl(photo.file_url),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f / 4f)
                        .clip(RoundedCornerShape(12.dp)),
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    photo.captured_at?.let {
                        MetaLine("Taken", it.take(16).replace("T", " "))
                    }
                    photo.captured_by?.let { MetaLine("By", it) }
                    if (photo.latitude != null && photo.longitude != null) {
                        MetaLine(
                            "Where",
                            buildString {
                                append("%.5f, %.5f".format(photo.latitude, photo.longitude))
                                photo.accuracy_m?.let { append(" ±${it.toInt()} m") }
                            },
                        )
                    }
                    photo.geofence_status?.takeIf { it != "Unknown" }?.let {
                        MetaLine("Plant", if (it == "Inside") "Inside the plant" else "Outside the plant")
                    }
                }
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

@Composable
private fun MetaLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            Modifier.width(56.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}
