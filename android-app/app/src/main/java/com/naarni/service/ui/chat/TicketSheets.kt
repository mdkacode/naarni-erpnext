package com.naarni.service.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naarni.service.core.feedback.LocalFeedback
import com.naarni.service.data.dto.ChatTicketDto
import com.naarni.service.data.dto.ChatUserDto

/**
 * Pick a Service Ticket and drop it into the thread.
 *
 * Opens showing recent open work rather than an empty box: most of the time the
 * ticket being discussed is one of the last few raised, and making someone
 * remember an id before the list will show them anything is how a feature ends
 * up unused.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TicketPickerSheet(
    vm: ChatViewModel,
    room: String,
    onDismiss: () -> Unit,
    onShared: () -> Unit,
    onError: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var sharing by remember { mutableStateOf<String?>(null) }
    val feedback = LocalFeedback.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(query) { vm.searchTickets(query) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                "Share a ticket",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
            )

            SheetSearchField(
                value = query,
                onChange = { query = it },
                placeholder = "Ticket number, title or vehicle",
                busy = vm.ticketsLoading,
            )

            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (vm.tickets.isEmpty() && !vm.ticketsLoading) {
                Box(
                    Modifier.fillMaxWidth().height(140.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (query.isBlank()) "No tickets yet" else "Nothing matches “$query”",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(vm.tickets, key = { it.name }) { ticket ->
                        TicketRow(
                            ticket = ticket,
                            busy = sharing == ticket.name,
                            onClick = {
                                if (sharing != null) return@TicketRow
                                feedback.tap()
                                sharing = ticket.name
                                vm.shareTicket(room, ticket.name) { error ->
                                    sharing = null
                                    if (error == null) onShared() else onError(error)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Hand a ticket to someone.
 *
 * Deliberately restricted to the room's members: assigning to a person who is
 * not in the conversation produces a handover nobody in the thread can follow
 * up on.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AssignTicketSheet(
    ticket: String,
    candidates: List<ChatUserDto>,
    busyFor: String?,
    onPick: (ChatUserDto) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                "Assign $ticket",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 2.dp),
            )
            Text(
                "People in this conversation",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (candidates.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Nobody else is in this conversation yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(candidates, key = { it.name }) { user ->
                        ContactRow(
                            user = user,
                            busy = busyFor == user.name,
                            onClick = { if (busyFor == null) onPick(user) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetSearchField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    busy: Boolean,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.weight(1f).padding(start = 9.dp, top = 12.dp, bottom = 12.dp),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
            )
            if (busy) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(15.dp))
            }
        }
    }
}

@Composable
private fun TicketRow(ticket: ChatTicketDto, busy: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(com.naarni.service.ui.theme.Semantic.tint(severityColor(ticket.severity))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.ConfirmationNumber,
                contentDescription = null,
                tint = severityColor(ticket.severity),
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                ticket.title?.takeIf { it.isNotBlank() } ?: ticket.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill(ticket.status)
                Text(
                    listOfNotNull(ticket.name, ticket.registration_number).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(17.dp))
    }
}

/** Status as a shape as well as a colour, so it survives a colour-blind reader. */
@Composable
fun StatusPill(status: String) {
    val color = when (status) {
        "Open" -> MaterialTheme.colorScheme.error
        "Acknowledged" -> Color(0xFFF59E0B)
        else -> Color(0xFF10B981)
    }
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(5.dp)) {
        Text(
            status.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

/**
 * Ticket severity, delegated to the app-wide ramp.
 *
 * This used to be its own four-colour mapping, which meant a "High" ticket was
 * orange in a chat sheet and amber on the Tickets screen. Same word, same
 * urgency, so it is now the same colour.
 */
@Composable
@ReadOnlyComposable
fun severityColor(severity: String?): Color =
    com.naarni.service.ui.components.severityColor(severity)
