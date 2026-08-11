package com.naarni.service.ui.chat

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import com.naarni.service.ui.components.StampingCamera
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
) {
    // These MUST be remembered against roomName. Calling them in the composable
    // body builds a brand-new Pager on every recomposition, which tears down and
    // rebuilds the whole paging pipeline on each keystroke in the composer — the
    // single biggest source of scroll jank here.
    val roomFlow = remember(roomName) { vm.observeRoom(roomName) }
    val messageFlow = remember(roomName) { vm.messages(roomName) }
    val room by roomFlow.collectAsStateWithLifecycle(null)
    val messages = messageFlow.collectAsLazyPagingItems()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val feedback = LocalFeedback.current

    var replyTo by remember { mutableStateOf<ChatMessageEntity?>(null) }
    var selected by remember { mutableStateOf<ChatMessageEntity?>(null) }
    var showCamera by remember { mutableStateOf(false) }
    var rejected by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current

    // The Android 13+ photo picker needs no permission at all, which is why it
    // is used instead of READ_MEDIA_IMAGES.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val picked = Attachments.copyToOutbox(context, uri)
            if (picked == null) {
                rejected = "That file type can't be sent in chat."
                feedback.error()
            } else {
                feedback.messageSent()
                vm.sendAttachment(
                    room = roomName,
                    file = picked.file,
                    contentType = picked.contentType,
                    kind = picked.kind,
                    replyTo = replyTo?.serverName,
                )
                replyTo = null
                listState.animateScrollToItem(0)
            }
        }
    }

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

    // Full-screen capture takes over the whole screen when active.
    if (showCamera) {
        StampingCamera(
            label = "Chat",
            onClose = { showCamera = false },
            onCaptured = { file ->
                showCamera = false
                val picked = Attachments.fromCapture(file)
                feedback.messageSent()
                vm.sendAttachment(
                    room = roomName,
                    file = picked.file,
                    contentType = picked.contentType,
                    kind = picked.kind,
                    replyTo = replyTo?.serverName,
                )
                replyTo = null
            },
        )
        return
    }

    rejected?.let { message ->
        AlertDialog(
            onDismissRequest = { rejected = null },
            title = { Text("Can't send that") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { rejected = null }) { Text("OK") } },
        )
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
            onSend = { text ->
                val parent = replyTo
                replyTo = null
                feedback.messageSent()
                vm.sendText(roomName, text, parent?.serverName)
                scope.launch { listState.animateScrollToItem(0) }
            },
            onCamera = { feedback.tap(); showCamera = true },
            onAttach = {
                feedback.tap()
                picker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                )
            },
        )
    }
}

/**
 * Swipe-to-reply.
 *
 * Hand-rolled rather than `SwipeToDismissBox`, which instantiates an anchored
 * draggable state machine and a background slot for every row — real cost in a
 * long thread, for a gesture that only needs a horizontal offset and one
 * threshold. This version keeps a single `Animatable` per row and draws the
 * reply icon only while the row is actually displaced.
 *
 * The row always springs back: a reply is not a dismissal, so the message must
 * never leave the list.
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
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val triggerPx = with(LocalDensity.current) { REPLY_TRIGGER_DP.dp.toPx() }
    var fired by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxWidth()) {
        // Only composed while the row is displaced, so a still list pays nothing.
        if (offsetX.value > 1f) {
            Row(
                Modifier.matchParentSize().padding(start = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Reply,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(
                        alpha = (offsetX.value / triggerPx).coerceIn(0f, 1f),
                    ),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Box(
            Modifier
                .offset { IntOffset(offsetX.value.toInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        // Right-swipe only, with resistance past the trigger so
                        // the gesture feels bounded rather than loose.
                        val next = (offsetX.value + delta).coerceIn(0f, triggerPx * 1.4f)
                        scope.launch { offsetX.snapTo(next) }
                        if (!fired && next >= triggerPx) {
                            fired = true
                            onReply()
                        }
                    },
                    onDragStopped = {
                        fired = false
                        offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                    },
                )
                .combinedClickable(
                    onClick = { if (message.status == SendStatus.FAILED) onRetry() },
                    onLongClick = onLongPress,
                ),
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

private const val REPLY_TRIGGER_DP = 64

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

/**
 * The composer owns its own draft.
 *
 * Hoisting the text into the parent meant every keystroke recomposed the whole
 * thread — header, message list lambda and all. Keeping it local confines
 * typing to this row.
 */
@Composable
private fun Composer(
    onSend: (String) -> Unit,
    onCamera: () -> Unit,
    onAttach: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
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
                onValueChange = { draft = it },
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
                onClick = {
                    if (canSend) {
                        onSend(draft)
                        draft = ""
                    }
                },
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
