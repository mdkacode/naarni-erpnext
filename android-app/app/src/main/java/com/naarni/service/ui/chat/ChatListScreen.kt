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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
 */
@Composable
fun ChatListScreen(vm: ChatViewModel, onOpenRoom: (String) -> Unit) {
    val rooms by vm.rooms.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val filtered = remember(rooms, query) { vm.filterRooms(rooms, query) }
    val feedback = LocalFeedback.current

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {

        // Brand header, matching the login hero and primary headers elsewhere.
        Box(
            Modifier
                .fillMaxWidth()
                .background(Brush.horizontalGradient(BrandGradient))
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Column {
                Text(
                    "Chat",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitleFor(rooms),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
        }

        ConnectionBanner(vm.connection)

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search depots, vehicles, messages") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )

        if (filtered.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Forum,
                title = if (query.isBlank()) "No conversations yet" else "Nothing matches",
                body = if (query.isBlank()) {
                    "Depot and vehicle threads you are added to will appear here."
                } else {
                    "Try a vehicle number or a depot name."
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(filtered, key = { it.name }) { room ->
                    RoomRow(
                        room = room,
                        onClick = {
                            feedback.tap()
                            onOpenRoom(room.name)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RoomRow(room: ChatRoomEntity, onClick: () -> Unit) {
    val unread = (room.lastSeq - room.lastReadSeq).coerceAtLeast(0)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
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
 * Initials on a per-room colour. A vehicle-scoped room shows the registration
 * fragment instead, because that is what a technician actually recognises.
 */
@Composable
private fun RoomAvatar(room: ChatRoomEntity) {
    val label = when {
        room.vehicle != null -> room.vehicle.takeLast(4)
        else -> room.title.split(" ").take(2).mapNotNull { it.firstOrNull() }
            .joinToString("").uppercase().ifBlank { "?" }
    }
    Box(
        Modifier
            .size(46.dp)
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
fun ConnectionBanner(state: ConnectionState) {
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
                text,
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
