package com.naarni.service.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.naarni.service.core.feedback.LocalFeedback
import com.naarni.service.data.dto.AttendanceDay
import com.naarni.service.data.dto.DutyState
import com.naarni.service.data.dto.RosterDay
import com.naarni.service.data.repo.RosterRepository
import com.naarni.service.ui.AppViewModel
import com.naarni.service.ui.components.EmptyState
import com.naarni.service.ui.components.Refreshable
import com.naarni.service.ui.components.SectionHeader
import com.naarni.service.ui.components.StatTile
import kotlinx.coroutines.launch

/**
 * Duty check-in.
 *
 * Design notes, because this screen is used twice a day by someone wearing
 * gloves at a depot gate:
 *
 * * **One button, never two.** The server's `next_action` decides whether it
 *   says Check In or Check Out, so there is no state where both are offered and
 *   the engineer has to think about which one applies.
 * * **Nothing blocks the punch.** A missing GPS fix or a punch outside the
 *   geofence is reported *after* it succeeds, as a warning line. The punch is
 *   already recorded by then — an engineer stopped at the gate simply works
 *   unrecorded, which is the outcome this module exists to prevent.
 * * **The card lives on Home**, because check-in is the highest-frequency
 *   action in the app and burying it behind a tab costs two taps twice a day.
 */

// -------------------------------------------------------------------- home card

@Composable
fun DutyCard(vm: AppViewModel, onOpenDuty: () -> Unit) {
    val scope = rememberCoroutineScope()
    val feedback = LocalFeedback.current
    var duty by remember { mutableStateOf<DutyState?>(null) }
    var busy by remember { mutableStateOf(false) }
    var warnings by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        runCatching { vm.roster.myDuty() }.onSuccess { duty = it }
    }
    LaunchedEffect(Unit) { load() }

    val state = duty ?: return
    // Roles outside the punch list get no card at all rather than a disabled one:
    // a control you can never use is noise on a screen that has to stay scannable.
    if (!state.can_punch) return

    Surface(
        shape = MaterialTheme.shapes.large,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Filled.AccessTime,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text("Today's duty", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onOpenDuty) {
                    Icon(Icons.Filled.CalendarMonth, contentDescription = "My roster")
                }
            }

            DutyHeadline(state)

            PunchButton(
                state = state,
                busy = busy,
                onPunch = {
                    scope.launch {
                        busy = true
                        error = null
                        val result = runCatching {
                            if (state.next_action == RosterRepository.ACTION_CHECK_OUT) vm.roster.checkOut()
                            else vm.roster.checkIn()
                        }
                        result
                            .onSuccess {
                                duty = it.duty
                                warnings = it.warnings
                                feedback.success()
                            }
                            .onFailure {
                                error = it.message ?: "Could not record that. Try again."
                                feedback.error()
                            }
                        busy = false
                    }
                },
            )

            error?.let { AdvisoryLine(it, isError = true) }
            warnings.forEach { AdvisoryLine(it, isError = false) }

            if (!vm.roster.hasLocationPermission()) {
                AdvisoryLine(
                    "Location is off, so your punches will not carry a place. They are still recorded.",
                    isError = false,
                )
            }
        }
    }
}

@Composable
private fun DutyHeadline(state: DutyState) {
    val attendance = state.attendance
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val title = when {
            state.is_week_off -> "Week off"
            state.shift != null -> "${state.shift.label} · ${trimTime(state.shift.start_time)}–${trimTime(state.shift.end_time)}"
            state.is_rostered -> "Rostered"
            else -> "No shift rostered today"
        }
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)

        val sub = buildList {
            state.depot_name?.let { add(it) }
            when {
                state.is_on_duty -> add("On duty ${formatElapsed(state.elapsed_minutes)}")
                attendance?.last_check_out != null ->
                    add("Worked ${"%.1f".format(attendance.worked_hours)} h")
                !state.is_rostered && !state.is_week_off -> add("Check in anyway if you are working")
            }
            if (attendance?.is_late == 1) add("Late by ${attendance.late_by_minutes} min")
        }
        if (sub.isNotEmpty()) {
            Text(
                sub.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.remarks?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun PunchButton(state: DutyState, busy: Boolean, onPunch: () -> Unit) {
    val feedback = LocalFeedback.current
    val action = state.next_action
    if (action == RosterRepository.ACTION_NONE) return

    val isOut = action == RosterRepository.ACTION_CHECK_OUT
    val isDone = action == RosterRepository.ACTION_DONE

    if (isDone) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.CheckCircle, null, tint = Ok, modifier = Modifier.size(20.dp))
            Text("Day recorded", style = MaterialTheme.typography.bodyMedium, color = Ok)
        }
        return
    }

    // 64dp: the shopfloor minimum for a gloved thumb. Anything smaller gets
    // mis-tapped at a gate in the rain and the engineer gives up.
    Surface(
        onClick = { if (!busy) { feedback.tap(); onPunch() } },
        shape = MaterialTheme.shapes.large,
        color = if (isOut) CheckOutTone else CheckInTone,
        modifier = Modifier.fillMaxWidth().height(64.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = Color.White)
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        if (isOut) Icons.Filled.Logout else Icons.Filled.Login,
                        contentDescription = null,
                        tint = Color.White,
                    )
                    Text(
                        if (isOut) "Check out" else "Check in",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun AdvisoryLine(text: String, isError: Boolean) {
    val tint = if (isError) MaterialTheme.colorScheme.error else Warn
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(
            if (isError) Icons.Filled.WarningAmber else Icons.Filled.LocationOff,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Text(text, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

// ------------------------------------------------------------------- full screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DutyScreen(vm: AppViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var roster by remember { mutableStateOf<List<RosterDay>>(emptyList()) }
    var history by remember { mutableStateOf<List<AttendanceDay>>(emptyList()) }
    var totalHours by remember { mutableStateOf(0.0) }
    var presentDays by remember { mutableStateOf(0) }
    var lateDays by remember { mutableStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    suspend fun load() {
        runCatching { vm.roster.myRoster() }.onSuccess { roster = it.days }
        runCatching { vm.roster.myAttendance() }.onSuccess {
            history = it.days
            totalHours = it.totals.total_hours
            presentDays = it.totals.present_days
            lateDays = it.totals.late_days
        }
        loaded = true
    }
    LaunchedEffect(Unit) { load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My duty") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Refreshable(
            refreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; load(); refreshing = false } },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("Days present", presentDays.toString(), Icons.Filled.CheckCircle, Modifier.weight(1f))
                    StatTile("Hours (30d)", "%.0f".format(totalHours), Icons.Filled.AccessTime, Modifier.weight(1f))
                    StatTile("Late", lateDays.toString(), Icons.Filled.WarningAmber, Modifier.weight(1f))
                }

                SectionHeader("Upcoming")
                if (roster.isEmpty() && loaded) {
                    EmptyState(
                        Icons.Filled.CalendarMonth,
                        "No roster published",
                        "Your Depot Manager has not published a duty roster yet. You can still check in.",
                    )
                }
                roster.forEach { RosterRow(it) }

                if (history.isNotEmpty()) {
                    SectionHeader("Recent days")
                    history.take(14).forEach { AttendanceRow(it) }
                }
            }
        }
    }
}

@Composable
private fun RosterRow(day: RosterDay) {
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(prettyDate(day.date), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    when {
                        day.is_week_off == 1 -> "Week off"
                        day.shift != null ->
                            "${day.shift.label} · ${trimTime(day.shift.start_time)}–${trimTime(day.shift.end_time)}"
                        else -> "Rostered"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                day.remarks?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            day.status?.let { DutyStatusPill(it) }
        }
    }
}

@Composable
private fun AttendanceRow(day: AttendanceDay) {
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(prettyDate(day.date), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                val detail = buildList {
                    day.first_check_in?.let { add("In ${clockOf(it)}") }
                    day.last_check_out?.let { add("Out ${clockOf(it)}") }
                    if (day.worked_hours > 0) add("%.1f h".format(day.worked_hours))
                    if (day.is_late == 1) add("Late ${day.late_by_minutes}m")
                    if (day.outside_geofence == 1) add("Off-site")
                }
                if (detail.isNotEmpty()) {
                    Text(
                        detail.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            DutyStatusPill(day.status)
        }
    }
}

@Composable
private fun DutyStatusPill(status: String) {
    val tone = dutyStatusColor(status)
    Surface(shape = RoundedCornerShape(10.dp), color = tone.copy(alpha = 0.14f)) {
        Text(
            status,
            style = MaterialTheme.typography.labelMedium,
            color = tone,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

// Colour is never the only signal — every pill carries its word too, because a
// depot floor has plenty of people who cannot rely on hue alone.
private val Ok = Color(0xFF16A34A)
private val Warn = Color(0xFFD97706)
private val CheckInTone = Color(0xFF16A34A)
private val CheckOutTone = Color(0xFFDC2626)

private fun dutyStatusColor(status: String?): Color = when (status?.lowercase()) {
    "present" -> Ok
    "on duty" -> Color(0xFF2563EB)
    "half day" -> Warn
    "absent" -> Color(0xFFDC2626)
    "week off" -> Color(0xFF64748B)
    else -> Color(0xFF94A3B8)
}

/** "09:00:00" → "09:00". Seconds are noise on a shift label. */
private fun trimTime(value: String?): String {
    val v = value ?: return "—"
    val parts = v.split(":")
    if (parts.size < 2) return v
    return "${parts[0].padStart(2, '0')}:${parts[1]}"
}

/** "2026-08-12 09:14:03" → "09:14". */
private fun clockOf(datetime: String): String =
    datetime.substringAfter(' ', "").take(5).ifBlank { datetime.take(5) }

private fun formatElapsed(minutes: Int): String {
    if (minutes < 60) return "${minutes}m"
    return "${minutes / 60}h ${minutes % 60}m"
}

private val MONTHS = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

/** "2026-08-12" → "12 Aug". Falls back to the raw value on anything unexpected. */
private fun prettyDate(iso: String): String {
    val parts = iso.split("-")
    if (parts.size != 3) return iso
    val month = parts[1].toIntOrNull() ?: return iso
    if (month !in 1..12) return iso
    return "${parts[2].trimStart('0')} ${MONTHS[month - 1]}"
}
