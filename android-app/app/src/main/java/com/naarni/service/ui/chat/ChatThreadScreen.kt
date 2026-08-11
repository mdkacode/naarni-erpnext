package com.naarni.service.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.naarni.service.core.feedback.LocalFeedback
import com.naarni.service.data.chat.ChatMessageEntity
import com.naarni.service.data.chat.SendStatus
import com.naarni.service.ui.theme.BrandGradient
import kotlinx.coroutines.launch

/**
 * One conversation.
 *
 * Messages come from Paging3 over Room, newest-first with `reverseLayout`, so
 * the list opens pinned to the latest message and older pages stream in as the
 * user scrolls up. `maxSize` in the Pager config evicts pages behind them, which
 * is what keeps a thread of 200 photos from climbing until it OOMs.
 */
@Composable
fun ChatThreadScreen(
    vm: ChatViewModel,
    roomName: String,
    onBack: () -> Unit,
    onRaiseTicket: (ChatMessageEntity) -> Unit,
    onOpenCamera: () -> Unit,
    onAttachFile: () -> Unit,
) {
    val room by vm.observeRoom(roomName).collectAsStateWithLifecycle(null)
    val messages = vm.messages(roomName).collectAsLazyPagingItems()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val feedback = LocalFeedback.current

    var draft by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<ChatMessageEntity?>(null) }
    var selected by remember { mutableStateOf<ChatMessageEntity?>(null) }

    // Join the doc room while this screen is on top; leave on the way out so we
    // are not holding a subscription for every thread ever opened.
    LaunchedEffect(roomName) { vm.openThread(roomName) }
    androidx.compose.runtime.DisposableEffect(roomName) {
        onDispose { vm.closeThread(roomName) }
    }

    // Chime only for messages that arrive while you are looking at the thread.
    LaunchedEffect(Unit) {
        vm.incoming.collect { feedback.messageReceived() }
    }

    // Reading the newest message is what clears the badge.
    val newestSeq = remember(messages.itemCount) {
        (0 until minOf(messages.itemCount, 1)).firstNotNullOfOrNull { messages.peek(it)?.seq } ?: 0L
    }
    LaunchedEffect(newestSeq) { if (newestSeq > 0) vm.markRead(roomName, newestSeq) }

    val atBottom by remember {
        derivedStateOf { listState.firstVisibleItemIndex <= 2 }
    }

    BackHandler(enabled = selected != null || replyTo != null) {
        selected = null
        replyTo = null
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding(),
    ) {
        if (selected != null) {
            SelectionBar(
                message = selected!!,
                onDismiss = { selected = null },
                onReply = { replyTo = selected; selected = null },
                onRaiseTicket = { onRaiseTicket(selected!!); selected = null },
            )
        } else {
            ThreadHeader(
                title = room?.title ?: "Chat",
                subtitle = threadSubtitle(room?.memberCount ?: 0, room?.vehicle),
                muted = room?.muted == true,
                onBack = onBack,
                onToggleMute = { vm.setMuted(roomName, room?.muted != true) },
            )
        }

        ConnectionBanner(vm.connection)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            androidx.compose.foundation.lazy.LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
            ) {
                items(
                    count = messages.itemCount,
                    key = messages.itemKey { it.clientId },
                ) { index ->
                    val message = messages[index] ?: return@items
                    // reverseLayout: index+1 is the OLDER neighbour, rendered above.
                    val older = if (index + 1 < messages.itemCount) messages.peek(index + 1) else null

                    SwipeableMessage(
                        message = message,
                        isMine = vm.isMine(message),
                        showAuthor = older == null || older.author != message.author,
                        isSelected = selected?.clientId == message.clientId,
                        replyPreview = null,
                        onReply = {
                            feedback.replyTriggered()
                            replyTo = message
                        },
                        onLongPress = {
                            feedback.selectionEntered()
                            selected = message
                        },
                        onRetry = { vm.retry(message) },
                    )

                    val newDay = older == null || dayLabel(older.createdAt) != dayLabel(message.createdAt)
                    if (newDay) DayDivider(dayLabel(message.createdAt))
                }
            }

            // Jump-to-latest, only once the user has scrolled meaningfully away.
            androidx.compose.animation.AnimatedVisibility(
                visible = !atBottom,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut(),
                modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp),
            ) {
                FloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.size(42.dp),
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Jump to latest")
                }
            }
        }

        replyTo?.let { ReplyBar(it) { replyTo = null } }

        Composer(
            draft = draft,
            onDraftChange = { draft = it },
            onSend = {
                val text = draft
                draft = ""
                val parent = replyTo
                replyTo = null
                feedback.messageSent()
                vm.sendText(roomName, text, parent?.serverName)
                scope.launch { listState.animateScrollToItem(0) }
            },
            onCamera = { feedback.tap(); onOpenCamera() },
            onAttach = { feedback.tap(); onAttachFile() },
        )
    }
}

/**
 * Swipe-to-reply.
 *
 * `confirmValueChange` returns false on purpose: the row animates, fires the
 * reply, then snaps back. Letting it settle would dismiss the message, which is
 * emphatically not what a reply gesture should do.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SwipeableMessage(
    message: ChatMessageEntity,
    isMine: Boolean,
    showAuthor: Boolean,
    isSelected: Boolean,
    replyPreview: ChatMessageEntity?,
    onReply: () -> Unit,
    onLongPress: () -> Unit,
    onRetry: () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd) onReply()
            false
        },
        positionalThreshold = { it * 0.28f },
    )

    SwipeToDismissBox(
        state = state,
        enableDismissFromEndToStart = false,
        backgroundContent = {
            Row(
                Modifier.fillMaxSize().padding(start = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Reply,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
    ) {
        Box(
            Modifier.combinedClickable(
                onClick = {
                    if (message.status == SendStatus.FAILED) onRetry()
                },
                onLongClick = onLongPress,
            )
        ) {
            MessageBubble(
                message = message,
                isMine = isMine,
                showAuthor = showAuthor,
                isSelected = isSelected,
                replyPreview = replyPreview,
                onRetry = onRetry,
            )
        }
    }
}

@Composable
private fun ThreadHeader(
    title: String,
    subtitle: String,
    muted: Boolean,
    onBack: () -> Unit,
    onToggleMute: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.horizontalGradient(BrandGradient))
            .statusBarsPadding()
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.8f),
                maxLines = 1,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Text("⋮", color = Color.White, style = MaterialTheme.typography.titleLarge)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(if (muted) "Unmute" else "Mute notifications") },
                    onClick = { onToggleMute(); menuOpen = false },
                )
            }
        }
    }
}

/** Contextual action mode — long-press a message. */
@Composable
private fun SelectionBar(
    message: ChatMessageEntity,
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onRaiseTicket: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary)
            .statusBarsPadding()
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
        }
        Text(
            "1 selected",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onReply) {
            Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = "Reply", tint = Color.White)
        }
        // Turning a field observation straight into a Service Ticket is the whole
        // point of chat living inside this app rather than in WhatsApp.
        IconButton(onClick = onRaiseTicket) {
            Icon(
                Icons.Default.ConfirmationNumber,
                contentDescription = "Raise ticket from this message",
                tint = Color.White,
            )
        }
    }
}

@Composable
private fun ReplyBar(message: ChatMessageEntity, onClear: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(30.dp)
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Replying to ${message.authorName}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                message.body.ifBlank { message.kind.replaceFirstChar { it.uppercase() } },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onClear) {
            Icon(Icons.Default.Close, contentDescription = "Cancel reply", modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onCamera: () -> Unit,
    onAttach: () -> Unit,
) {
    val canSend = draft.isNotBlank()
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconButton(onClick = onAttach) {
                Icon(
                    Icons.Default.AttachFile,
                    contentDescription = "Attach a file",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextField(
                value = draft,
                onValueChange = onDraftChange,
                placeholder = { Text("Message") },
                maxLines = 5,
                shape = RoundedCornerShape(22.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onCamera) {
                Icon(
                    Icons.Default.PhotoCamera,
                    contentDescription = "Take a stamped photo",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The send button only materialises when there is something to send,
            // so the camera stays the obvious action in a photo-first workflow.
            FloatingActionButton(
                onClick = { if (canSend) onSend() },
                containerColor = if (canSend) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
                elevation = androidx.compose.material3.FloatingActionButtonDefaults.elevation(0.dp, 0.dp),
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    Icons.Default.Send,
                    contentDescription = "Send",
                    tint = if (canSend) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }
}

private fun threadSubtitle(members: Int, vehicle: String?): String = buildString {
    if (vehicle != null) append(vehicle).append(" · ")
    append(if (members == 1) "1 member" else "$members members")
}
