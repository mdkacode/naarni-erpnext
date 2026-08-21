package com.naarni.service.ui.screens

import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.naarni.service.appContainer
import com.naarni.service.core.push.NotificationTones
import com.naarni.service.data.dto.DesignationDto
import com.naarni.service.data.dto.ProfileDto
import com.naarni.service.data.repo.ProfileRepository
import com.naarni.service.data.repo.copyToCache
import com.naarni.service.ui.theme.AppSurface
import kotlinx.coroutines.launch
import java.util.UUID
import com.naarni.service.ui.theme.Stroke
import com.naarni.service.ui.theme.Elevation

/**
 * The first thing a new joiner sees, and the profile they can come back to.
 *
 * Shaped after the one onboarding every technician in the country has already
 * done — WhatsApp's: one question per screen, the photo optional at the moment
 * of asking, and nothing that looks like a form. It saves as it goes rather than
 * holding everything for a final Done, because an app killed mid-flow on a depot
 * phone should not cost somebody their typing twice.
 *
 * It never blocks. Every step can be passed over, and the banner that brings
 * people back is dismissible. That is a deliberate policy, not an omission: a
 * technician at a gate with one bar of signal has a job to do, and an app that
 * demands a selfie first is one they will work around rather than use.
 */

// --------------------------------------------------------------------- shared

/**
 * The signed-in person's profile, fetched once per process and shared.
 *
 * Held here rather than in a ViewModel because four unrelated surfaces want it —
 * the profile tab, the banner, the onboarding gate and the sounds screen — and
 * none of them owns it. A single cached copy also stops four screens firing four
 * identical requests on a depot's connection.
 */
object MyProfile {
    var cached by mutableStateOf<ProfileDto?>(null)
        private set

    /** True until we have heard back, so the gate does not flash before it knows. */
    var loaded by mutableStateOf(false)
        private set

    fun put(profile: ProfileDto?) {
        cached = profile
        loaded = true
    }

    fun clear() {
        cached = null
        loaded = false
    }
}

@Composable
fun rememberMyProfile(): ProfileDto? {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (MyProfile.cached == null) {
            runCatching { context.appContainer.profileRepo.load() }
                .onSuccess { profile ->
                    MyProfile.put(profile)
                    // The server is the source of truth for the choice; the phone
                    // has to act on it because a channel's sound is fixed at
                    // creation. Applying it here means a reinstall or a second
                    // handset inherits what the person picked.
                    NotificationTones.applyTones(
                        context, profile.chat_tone, profile.alert_tone, profile.vibrate,
                    )
                }
                .onFailure { MyProfile.put(null) }
        }
    }
    return MyProfile.cached
}

/** The face, or initials on the app's sunken surface. One drawing, three screens. */
@Composable
fun ProfileFace(profile: ProfileDto?, size: Int = 96, local: Uri? = null) {
    val name = profile?.full_name.orEmpty()
    Box(
        Modifier.size(size.dp).background(AppSurface.sunken, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val model = local ?: ProfileRepository.avatarUrl(profile?.user, profile?.has_photo == true)
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = name.ifBlank { "Profile picture" },
                modifier = Modifier.size(size.dp).background(AppSurface.sunken, CircleShape),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        } else if (name.isNotBlank()) {
            Text(
                name.split(" ").take(2).mapNotNull { it.firstOrNull() }.joinToString("").uppercase(),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
            )
        } else {
            Icon(
                Icons.Rounded.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size((size / 2).dp),
            )
        }
    }
}

// ----------------------------------------------------------------- onboarding

/**
 * Name, then face, then what you do — in that order and one at a time.
 *
 * The order is not arbitrary. The name is the only one that is genuinely needed
 * (a thread full of phone numbers is unusable), so it is asked first, while the
 * person still has the patience for it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingFlow(onDone: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { context.appContainer.profileRepo }
    val scope = rememberCoroutineScope()

    var profile by remember { mutableStateOf<ProfileDto?>(null) }
    var step by remember { mutableStateOf(0) }
    var name by remember { mutableStateOf("") }
    var localPhoto by remember { mutableStateOf<Uri?>(null) }
    var designations by remember { mutableStateOf<List<DesignationDto>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { repo.load() }.onSuccess {
            profile = it
            // The invite already carried a name; showing it beats an empty box
            // somebody has to retype from their own ID card.
            if (it.has_name) name = it.full_name
        }
        designations = runCatching { repo.designations() }.getOrDefault(emptyList())
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        localPhoto = uri
        busy = true
        scope.launch {
            runCatching {
                val file = uri.copyToCache(context, "avatar-${UUID.randomUUID()}.jpg")
                repo.setPhoto(file)
            }.onSuccess { profile = it; error = null }
                .onFailure { error = "That photo did not upload. You can add one later."; localPhoto = null }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        when (step) {
            0 -> {
                Text("What should we call you?", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Your colleagues see this on every message and inspection you send.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(140) },
                    label = { Text("Full name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            1 -> {
                Text("Add a photo", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "So people recognise you in a thread. Only signed-in Naarni users can see it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.clickable(enabled = !busy) {
                            picker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        ProfileFace(profile, size = 132, local = localPhoto)
                        if (busy) CircularProgressIndicator(Modifier.size(40.dp))
                    }
                }
                TextButton(
                    onClick = {
                        picker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.CameraAlt, contentDescription = null)
                    Text("  ${if (profile?.has_photo == true) "Change photo" else "Choose a photo"}")
                }
            }

            else -> {
                Text("What do you do?", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Pick the closest one — it helps people know who to ask.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(color = AppSurface.raised, border = Stroke.card, shape = MaterialTheme.shapes.large, tonalElevation = Elevation.e0, modifier = Modifier.fillMaxWidth()) {
                    Column {
                        designations.forEachIndexed { index, d ->
                            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    profile = profile?.copy(designation = d.name)
                                }.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = profile?.designation == d.name,
                                    onClick = { profile = profile?.copy(designation = d.name) },
                                )
                                Text(d.designation_name, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        }

        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Button(
            onClick = {
                busy = true
                scope.launch {
                    when (step) {
                        0 -> runCatching { repo.save(fullName = name.trim()) }
                            .onSuccess { profile = it; step = 1; error = null }
                            .onFailure { error = "Enter your name to continue." }
                        1 -> step = 2
                        else -> {
                            runCatching { repo.save(designation = profile?.designation) }
                            onDone()
                        }
                    }
                    busy = false
                }
            },
            enabled = !busy && (step != 0 || name.trim().length >= 2),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(if (step == 2) "Done" else "Continue")
        }

        // Skippable at every step, on purpose. See the file header.
        if (step > 0) {
            TextButton(onClick = { if (step == 2) onDone() else step += 1 }, modifier = Modifier.fillMaxWidth()) {
                Text("Not now")
            }
        }
    }
}

// -------------------------------------------------------------------- banner

/**
 * The nudge that brings people back, for the accounts that predate onboarding.
 *
 * Dismissible, and it stays dismissed for a week server-side so a second handset
 * agrees. It never blocks anything.
 */
@Composable
fun ProfilePromptBanner(profile: ProfileDto?, onOpen: () -> Unit, onDismiss: () -> Unit) {
    if (profile == null || profile.profile_complete) return
    val missing = when {
        !profile.has_name && !profile.has_photo -> "your name and photo"
        !profile.has_name -> "your name"
        else -> "a photo"
    }
    Surface(color = AppSurface.raised, border = Stroke.card, shape = MaterialTheme.shapes.large,
        tonalElevation = Elevation.e0,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier.clickable { onOpen() }.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProfileFace(profile, size = 40)
            Column(Modifier.weight(1f)) {
                Text("Finish your profile", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Add $missing so colleagues recognise you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Rounded.Close, contentDescription = "Dismiss")
            }
        }
    }
}

// -------------------------------------------------------------- edit profile

@Composable
fun EditProfileScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { context.appContainer.profileRepo }
    val scope = rememberCoroutineScope()

    var profile by remember { mutableStateOf<ProfileDto?>(null) }
    var name by remember { mutableStateOf("") }
    var about by remember { mutableStateOf("") }
    var designations by remember { mutableStateOf<List<DesignationDto>>(emptyList()) }
    var localPhoto by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { repo.load() }.onSuccess {
            profile = it
            name = it.full_name
            about = it.about.orEmpty()
        }
        designations = runCatching { repo.designations() }.getOrDefault(emptyList())
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        localPhoto = uri
        busy = true
        scope.launch {
            runCatching {
                repo.setPhoto(uri.copyToCache(context, "avatar-${UUID.randomUUID()}.jpg"))
            }.onSuccess { profile = it }.onFailure { localPhoto = null }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Edit profile", style = MaterialTheme.typography.titleLarge)

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.clickable(enabled = !busy) {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    ProfileFace(profile, size = 112, local = localPhoto)
                    if (busy) CircularProgressIndicator(Modifier.size(36.dp))
                }
                TextButton(onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text(if (profile?.has_photo == true) "Change photo" else "Add photo") }
                if (profile?.has_photo == true) {
                    TextButton(onClick = {
                        scope.launch {
                            busy = true
                            runCatching { repo.removePhoto() }.onSuccess { profile = it; localPhoto = null }
                            busy = false
                        }
                    }) { Text("Remove photo", color = MaterialTheme.colorScheme.error) }
                }
            }
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(140); saved = false },
            label = { Text("Full name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = about,
            onValueChange = { about = it.take(140); saved = false },
            label = { Text("About (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Designation", style = MaterialTheme.typography.titleSmall)
        Surface(color = AppSurface.raised, border = Stroke.card, shape = MaterialTheme.shapes.large, tonalElevation = Elevation.e0, modifier = Modifier.fillMaxWidth()) {
            Column {
                designations.forEachIndexed { index, d ->
                    if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { profile = profile?.copy(designation = d.name); saved = false }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = profile?.designation == d.name,
                            onClick = { profile = profile?.copy(designation = d.name); saved = false },
                        )
                        Text(d.designation_name)
                    }
                }
            }
        }

        Button(
            onClick = {
                busy = true
                scope.launch {
                    runCatching {
                        repo.save(
                            fullName = name.trim().ifBlank { null },
                            designation = profile?.designation,
                            about = about.trim(),
                        )
                    }.onSuccess { profile = it; saved = true }
                    busy = false
                }
            },
            enabled = !busy,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (saved) Icon(Icons.Rounded.Check, contentDescription = null)
            Text(if (saved) "  Saved" else "Save")
        }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}

// ---------------------------------------------------------------- tone picker

/**
 * Which sound, for which kind of interruption.
 *
 * Bundled tones first because they are the ones that behave the same on every
 * handset; the system picker is there for people who already know what they
 * want. Choosing plays it, so nobody has to guess from a name.
 */
@Composable
fun NotificationSoundsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { context.appContainer.profileRepo }
    val scope = rememberCoroutineScope()

    var chat by remember { mutableStateOf(NotificationTones.chatTone(context)) }
    var alert by remember { mutableStateOf(NotificationTones.alertTone(context)) }
    var vibrate by remember { mutableStateOf(NotificationTones.vibrate(context)) }
    var editing by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { repo.load() }.onSuccess {
            chat = it.chat_tone
            alert = it.alert_tone
            vibrate = it.vibrate
            NotificationTones.applyTones(context, it.chat_tone, it.alert_tone, it.vibrate)
        }
    }

    fun persist() {
        scope.launch { runCatching { repo.setTones(context, chat, alert, vibrate) } }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Sounds", style = MaterialTheme.typography.titleLarge)
        Text(
            "Messages and alerts ring differently on purpose — a breakdown at 2am " +
                "should not sound like depot chatter.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Surface(color = AppSurface.raised, border = Stroke.card, shape = MaterialTheme.shapes.large, tonalElevation = Elevation.e0, modifier = Modifier.fillMaxWidth()) {
            Column {
                ToneRow("Chat messages", NotificationTones.labelFor(context, chat)) {
                    editing = NotificationTones.KIND_CHAT
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                ToneRow("Alerts & job cards", NotificationTones.labelFor(context, alert)) {
                    editing = NotificationTones.KIND_ALERT
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Vibrate", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = vibrate, onCheckedChange = { vibrate = it; persist() })
                }
            }
        }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }

    editing?.let { kind ->
        TonePickerDialog(
            current = if (kind == NotificationTones.KIND_CHAT) chat else alert,
            onPick = {
                if (kind == NotificationTones.KIND_CHAT) chat = it else alert = it
                persist()
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun ToneRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TonePickerDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(current) }

    // The escape hatch. Anything on the handset, including whatever the person
    // already uses for everything else.
    val systemPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        if (uri != null) onPick("system:$uri")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a sound") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                NotificationTones.BUNDLED.forEach { tone ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            selected = tone.id
                            // Play it. A name is not a sound, and picking one
                            // blind is how people end up with something they
                            // dislike every time the phone buzzes.
                            NotificationTones.previewUri(context, tone.id)?.let { uri ->
                                runCatching { RingtoneManager.getRingtone(context, uri)?.play() }
                            }
                        }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == tone.id, onClick = null)
                        Spacer(Modifier.size(8.dp))
                        Text(tone.label)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                TextButton(
                    onClick = {
                        systemPicker.launch(
                            android.content.Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Choose from this phone")
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                            },
                        )
                    },
                ) { Text("Choose from this phone…") }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(selected) }) { Text("Use this") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
