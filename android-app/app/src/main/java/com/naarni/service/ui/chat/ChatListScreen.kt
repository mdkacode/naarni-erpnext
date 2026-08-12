package com.naarni.service.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naarni.service.core.feedback.LocalFeedback
import com.naarni.service.data.chat.ChatRoomEntity
import com.naarni.service.ui.components.EmptyState
import com.naarni.service.ui.theme.BrandGradient
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The Chat tab's landing screen.
 *
 * Reads entirely from Room, so it renders instantly on a cold start with no
 * network and shows the same thing in a tunnel as it does on wifi. The
 * connection banner is the only place the transport is ever mentioned.
 *
 * The search box spans two sources on purpose. Typing filters the threads you
 * are already in *and*, below them, searches the whole staff directory — so
 * reaching someone you have never messaged is the same gesture as finding a
 * conversation you already have, rather than a separate screen you have to know
 * exists.
 */
@Composable
fun ChatListScreen(
    vm: ChatViewModel,
    onOpenRoom: (String) -> Unit,
    onNewChat: () -> Unit = {},
) {
    val rooms by vm.rooms.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val filtered = remember(rooms, query) { vm.filterRooms(rooms, query) }
    val feedback = LocalFeedback.current
    var opening by remember { mutableStateOf<String?>(null) }

    val searching = query.isNotBlank()
    val people = vm.contacts.results

    // Only reach for the network once there is something to look up; an empty
    // box should not pull the whole directory down a depot's link.
    LaunchedEffect(query) {
        if (searching) vm.contacts.query(query) else vm.contacts.clear()
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {

            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.horizontalGradient(BrandGradient))
                    .statusBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
            ) {
                Text("Chat", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitleFor(rooms),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                )
                Spacer(Modifier.height(11.dp))
                // Search lives inside the header rather than as a boxed field
                // below it: one band of chrome instead of two stacked ones.
                SearchPill(query, onChange = { query = it }, busy = vm.contacts.busy)
            }

            ConnectionBanner(vm.connection, vm.connectionDetail)

            val nothing = filtered.isEmpty() && (!searching || (people.isEmpty() && !vm.contacts.busy))
            if (nothing) {
                EmptyState(
                    icon = Icons.Default.Forum,
                    title = if (searching) "Nothing matches" else "No conversations yet",
                    body = if (searching) {
                        "Try a colleague's name, a phone number, a depot or a vehicle."
                    } else {
                        "Depot and vehicle threads you are added to appear here. Tap the pencil to message anyone."
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (searching && filtered.isNotEmpty()) {
                        item(key = "hdr-chats") { SectionHeader("Chats") }
                    }
                    items(filtered, key = { "room-${it.name}" }) { room ->
                        RoomRow(
                            room = room,
                            onClick = {
                                feedback.tap()
                                onOpenRoom(room.name)
                            },
                        )
                    }
                    if (searching && people.isNotEmpty()) {
                        item(key = "hdr-people") { SectionHeader("Contacts") }
                        items(people, key = { "user-${it.name}" }) { user ->
                            ContactRow(
                                user = user,
                                busy = opening == user.name,
                                onClick = {
                                    if (opening != null) return@ContactRow
                                    feedback.tap()
                                    opening = user.name
                                    vm.openDirect(user.name) { room ->
                                        opening = null
                                        onOpenRoom(room)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { feedback.tap(); onNewChat() },
            containerColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp),
        ) {
            Icon(Icons.Default.Edit, contentDescription = "New chat", tint = Color.White)
        }
    }
}

/** The rounded search field that sits on the brand gradient. */
@Composable
private fun SearchPill(value: String, onChange: (String) -> Unit, busy: Boolean) {
    Surface(color = Color.White.copy(alpha = 0.16f), shape = RoundedCornerShape(22.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(18.dp),
            )
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.weight(1f).padding(start = 9.dp, top = 11.dp, bottom = 11.dp),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                "Search people, depots, vehicles",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White.copy(alpha = 0.7f),
                            )
                        }
                        inner()
                    }
                },
            )
            if (busy) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = Color.White,
                    modifier = Modifier.size(15.dp),
                )
            } else if (value.isNotEmpty()) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Clear search",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .clickable { onChange("") },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
    )
}

@Composable
private fun RoomRow(room: ChatRoomEntity, onClick: () -> Unit) {
    val unread = (room.lastSeq - room.lastReadSeq).coerceAtLeast(0)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoomAvatar(room)
        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    room.title,
                    style = MaterialTheme.typography.titleSmall,
                    // An unread thread reads heavier — the standard cue, and it
                    // survives colour-blindness where a dot alone would not.
                    fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (room.muted) {
                    Spacer(Modifier.width(5.dp))
                    Icon(
                        Icons.Default.NotificationsOff,
                        contentDescription = "Muted",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                room.lastMessagePreview.orEmpty().ifBlank { "No messages yet" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (unread > 0) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                relativeTime(room.lastMessageAt),
                style = MaterialTheme.typography.labelSmall,
                color = if (unread > 0) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(5.dp))
            if (unread > 0) {
                Surface(
                    color = if (room.muted) MaterialTheme.colorScheme.outline
                    else MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                ) {
                    Text(
                        if (unread > 99) "99+" else "$unread",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * A direct thread shows the other person's photo; a group shows initials, and a
 * vehicle-scoped room shows the registration fragment, because that is what a
 * technician actually recognises.
 */
@Composable
private fun RoomAvatar(room: ChatRoomEntity) {
    if (room.kind == "Direct") {
        Avatar(room.title, room.peerImage, room.peer ?: room.name, size = 48)
        return
    }
    val label = when {
        room.vehicle != null -> room.vehicle.takeLast(4)
        else -> room.title.split(" ").take(2).mapNotNull { it.firstOrNull() }
            .joinToString("").uppercase().ifBlank { "?" }
    }
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(authorColor(room.name).copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = authorColor(room.name),
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Shown only when the transport is not healthy — silent when things work. */
@Composable
fun ConnectionBanner(state: ConnectionState, detail: String? = null) {
    val visible = state != ConnectionState.Live
    AnimatedVisibility(visible, enter = expandVertically(), exit = shrinkVertically()) {
        val (text, color) = when (state) {
            ConnectionState.Connecting -> "Connecting…" to MaterialTheme.colorScheme.secondaryContainer
            ConnectionState.Offline ->
                "Offline — messages will send when you reconnect" to MaterialTheme.colorScheme.surfaceVariant
            ConnectionState.Rejected ->
                "Session expired — sign in again" to MaterialTheme.colorScheme.errorContainer
            ConnectionState.Live -> "" to MaterialTheme.colorScheme.surfaceVariant
        }
        Row(
            Modifier.fillMaxWidth().background(color).padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Default.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                // The raw server reason beats a generic "offline" when someone
                // has to work out why a depot's phones are silent.
                if (detail.isNullOrBlank()) text else "$text · $detail",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun subtitleFor(rooms: List<ChatRoomEntity>): String {
    val unread = rooms.sumOf { (it.lastSeq - it.lastReadSeq).coerceAtLeast(0) }
    return when {
        rooms.isEmpty() -> "No conversations"
        unread == 0L -> "${rooms.size} conversations · all caught up"
        else -> "${rooms.size} conversations · $unread unread"
    }
}

private val hhmm = SimpleDateFormat("HH:mm", Locale.getDefault())
private val dayFmt = SimpleDateFormat("dd MMM", Locale.getDefault())
private val parser = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

/** Today → time, yesterday → "Yesterday", older → date. */
fun relativeTime(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    val date = runCatching { parser.parse(raw.substringBefore(".")) }.getOrNull() ?: return ""
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { time = date }
    return when {
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR) -> hhmm.format(date)

        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR) == 1 -> "Yesterday"

        else -> dayFmt.format(date)
    }
}

/** Day divider label for a message timestamp. */
fun dayLabel(millis: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    val delta = now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)
    return when {
        sameYear && delta == 0 -> "Today"
        sameYear && delta == 1 -> "Yesterday"
        else -> dayFmt.format(Date(millis))
    }
}

/** Wall-clock time for a message timestamp, used by the media viewer's header. */
fun clockTime(millis: Long): String = hhmm.format(Date(millis))
