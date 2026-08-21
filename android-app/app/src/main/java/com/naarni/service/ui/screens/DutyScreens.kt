package com.naarni.service.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.naarni.service.ui.theme.AppSurface
import com.naarni.service.ui.theme.Semantic
import androidx.core.app.ActivityCompat
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
import com.naarni.service.ui.theme.Stroke
import com.naarni.service.ui.theme.Elevation
import com.naarni.service.ui.theme.Radii

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
    val context = LocalContext.current
    var duty by remember { mutableStateOf<DutyState?>(null) }
    var busy by remember { mutableStateOf(false) }
    var warnings by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    // Re-read on every punch attempt rather than caching: the engineer may have
    // granted the permission in Settings since the card was drawn.
    var permissionTick by remember { mutableIntStateOf(0) }
    val hasLocation = remember(permissionTick) { vm.roster.hasLocationPermission() }

    var askLocation by remember { mutableStateOf(false) }
    // Set when the system declines to show its own dialog — the engineer has
    // already refused twice, and Android will never prompt again from here.
    var needsSettings by remember { mutableStateOf(false) }

    val activity = context.findActivity()

    suspend fun load() {
        runCatching { vm.roster.myDuty() }.onSuccess { duty = it }
    }
    LaunchedEffect(Unit) { load() }

    val state = duty ?: return

    fun punch() {
        val current = duty ?: return
        scope.launch {
            busy = true
            error = null
            val result = runCatching {
                if (current.next_action == RosterRepository.ACTION_CHECK_OUT) vm.roster.checkOut()
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
    }

    // Both are requested together because Android will hand back only the
    // coarse one if the engineer picks "Approximate", and a punch located to
    // the nearest block still tells a depot manager what they need to know.
    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        permissionTick++
        val ok = granted.values.any { it }
        // Denied *and* the system will not ask again — the only remaining route
        // is Settings, so say so rather than silently doing nothing next time.
        if (!ok && activity?.canStillPrompt() == false) needsSettings = true
        // The punch happens either way. An engineer standing at a gate who
        // declines a permission must still end up recorded as present.
        punch()
    }
    // Roles outside the punch list get no card at all rather than a disabled one:
    // a control you can never use is noise on a screen that has to stay scannable.
    if (!state.can_punch) return

    // Flat and hairlined rather than elevated. `tonalElevation` composites
    // `surfaceTint` — the accent — over the surface, which is what turned this
    // whole card lavender and made it read as a highlighted region rather than
    // as an ordinary card.
    Surface(
        shape = MaterialTheme.shapes.large,
        color = AppSurface.raised,
        border = BorderStroke(1.dp, AppSurface.hairline),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Rounded.AccessTime,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text("Today's duty", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onOpenDuty) {
                    Icon(Icons.Rounded.CalendarMonth, contentDescription = "My roster")
                }
            }

            DutyHeadline(state)

            PunchButton(
                state = state,
                busy = busy,
                onPunch = {
                    permissionTick++
                    // Ask at the moment it is needed, not on first launch: a
                    // permission prompt fired during onboarding, before the
                    // engineer has any idea what it is for, is the one most
                    // often refused outright.
                    if (vm.roster.hasLocationPermission()) punch()
                    else if (activity?.canStillPrompt() != false) askLocation = true
                    else needsSettings = true
                },
            )

            error?.let { AdvisoryLine(it, isError = true) }
            warnings.forEach { AdvisoryLine(it, isError = false) }

            if (!hasLocation) {
                AdvisoryLine(
                    "Location is off, so your punches will not carry a place. They are still recorded.",
                    isError = false,
                    action = "Turn on" to {
                        if (activity?.canStillPrompt() != false) askLocation = true
                        else needsSettings = true
                    },
                )
            }
        }
    }

    // Google Play requires the reason for location access to be given *before*
    // the system prompt, and it is the honest thing to do anyway: the engineer
    // is agreeing to have their whereabouts recorded at work.
    if (askLocation) {
        AlertDialog(
            onDismissRequest = { askLocation = false },
            icon = { Icon(Icons.Rounded.LocationOn, contentDescription = null) },
            title = { Text("Add your location to this punch?") },
            text = {
                Text(
                    "NaArNi Care records where you check in and out so your depot can " +
                        "confirm attendance without calling round. The location is read only " +
                        "at the moment you punch — never in the background.\n\n" +
                        "You can say no and still check in; the punch is recorded either way, " +
                        "just without a place.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askLocation = false
                    hasAskedLocationBefore = true
                    locationPermission.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                    )
                }) { Text("Allow location") }
            },
            dismissButton = {
                TextButton(onClick = { askLocation = false; punch() }) {
                    Text("Not now")
                }
            },
        )
    }

    if (needsSettings) {
        AlertDialog(
            onDismissRequest = { needsSettings = false },
            icon = { Icon(Icons.Rounded.LocationOff, contentDescription = null) },
            title = { Text("Location is blocked") },
            text = {
                Text(
                    "Android will not ask again from inside the app. To have your punches " +
                        "carry a place, turn Location on for NaArNi Care in Settings.\n\n" +
                        "Your check-ins are still recorded without it.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    needsSettings = false
                    context.openAppSettings()
                }) { Text("Open settings") }
            },
            dismissButton = {
                TextButton(onClick = { needsSettings = false }) { Text("Not now") }
            },
        )
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
            Icon(Icons.Rounded.CheckCircle, null, tint = Ok, modifier = Modifier.size(20.dp))
            Text("Day recorded", style = MaterialTheme.typography.bodyMedium, color = Ok)
        }
        return
    }

    // 64dp: the shopfloor minimum for a gloved thumb. Anything smaller gets
    // mis-tapped at a gate in the rain and the engineer gives up.
    // Tonal, not filled.
    //
    // This is the *card's* action, not the screen's. Home already carries one
    // filled accent button — Create Job Card — and on the handset the two
    // full-width saturated blocks fought each other for the eye, which is
    // exactly the problem a single-accent scheme exists to prevent. The tint
    // still reads as a button at arm's length while conceding the hierarchy.
    //
    // Check-out keeps a distinct tone: ending a shift is not the same action as
    // starting one, and a technician reaching for it in a hurry should not be
    // able to confuse the two.
    val tone = if (isOut) Semantic.critical else MaterialTheme.colorScheme.primary
    Surface(
        onClick = { if (!busy) { feedback.tap(); onPunch() } },
        shape = MaterialTheme.shapes.large,
        color = Semantic.tint(tone),
        modifier = Modifier.fillMaxWidth().height(64.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = tone)
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        if (isOut) Icons.Rounded.Logout else Icons.Rounded.Login,
                        contentDescription = null,
                        tint = tone,
                    )
                    Text(
                        if (isOut) "Check out" else "Check in",
                        style = MaterialTheme.typography.titleMedium,
                        color = tone,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun AdvisoryLine(
    text: String,
    isError: Boolean,
    action: Pair<String, () -> Unit>? = null,
) {
    val tint = if (isError) MaterialTheme.colorScheme.error else Semantic.caution
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(
            if (isError) Icons.Rounded.WarningAmber else Icons.Rounded.LocationOff,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = tint)
            // A warning that names a problem without offering the fix makes the
            // reader hunt through system settings for it.
            action?.let { (label, onClick) ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onClick),
                )
            }
        }
    }
}

/**
 * Whether Android will still show its own permission dialog.
 *
 * After two refusals it silently returns "denied" without ever appearing, so a
 * button wired straight to the launcher would look broken. `false` here means
 * Settings is the only route left.
 */
private fun Activity.canStillPrompt(): Boolean =
    ActivityCompat.shouldShowRequestPermissionRationale(
        this,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) || !hasAskedLocationBefore

/**
 * Whether we have ever asked. `shouldShowRequestPermissionRationale` is false
 * both before the first ask and after a permanent refusal, so on its own it
 * cannot tell those apart.
 */
private var hasAskedLocationBefore = false

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private fun Context.openAppSettings() {
    runCatching {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
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
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
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
                    StatTile("Days present", presentDays.toString(), Icons.Rounded.CheckCircle, Modifier.weight(1f))
                    StatTile("Hours (30d)", "%.0f".format(totalHours), Icons.Rounded.AccessTime, Modifier.weight(1f))
                    StatTile("Late", lateDays.toString(), Icons.Rounded.WarningAmber, Modifier.weight(1f))
                }

                SectionHeader("Upcoming")
                if (roster.isEmpty() && loaded) {
                    EmptyState(
                        Icons.Rounded.CalendarMonth,
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
    Surface(color = AppSurface.raised, border = Stroke.card, shape = MaterialTheme.shapes.medium, tonalElevation = Elevation.e0, modifier = Modifier.fillMaxWidth()) {
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
    Surface(color = AppSurface.raised, border = Stroke.card, shape = MaterialTheme.shapes.medium, tonalElevation = Elevation.e0, modifier = Modifier.fillMaxWidth()) {
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
    Surface(shape = Radii.md, color = tone.copy(alpha = 0.14f)) {
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
//
// Drawn from the app-wide ramp rather than this file's own greens and ambers,
// so "Present" here and "Closed" on a job card are the same green, and neither
// invents a hue the rest of the app does not use.
private val Ok: Color
    @Composable @ReadOnlyComposable get() = Semantic.positive

@Composable
@ReadOnlyComposable
private fun dutyStatusColor(status: String?): Color = when (status?.lowercase()) {
    "present" -> Semantic.positive
    "on duty" -> Semantic.active
    "half day" -> Semantic.caution
    "absent" -> Semantic.critical
    "week off" -> Semantic.idle
    else -> Semantic.idle
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
