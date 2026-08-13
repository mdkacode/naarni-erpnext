package com.naarni.service.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import com.naarni.service.core.feedback.LocalFeedback
import com.naarni.service.core.audio.VoiceRecorder
import com.naarni.service.core.push.ChatNotifications
import com.naarni.service.data.chat.ChatMessageEntity
import com.naarni.service.data.chat.SendStatus
import com.naarni.service.data.dto.ChatUserDto
import com.naarni.service.ui.components.StampingCamera
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
    onOpenGallery: () -> Unit = {},
) {
    // These MUST be remembered against roomName. Calling them in the composable
    // body builds a brand-new Pager on every recomposition, which tears down and
    // rebuilds the whole paging pipeline on each keystroke in the composer — the
    // single biggest source of scroll jank here.
    val roomFlow = remember(roomName) { vm.observeRoom(roomName) }
    val messageFlow = remember(roomName) { vm.messages(roomName) }
    // Backs the swipe run in the photo viewer. Same query the gallery uses, so
    // the two surfaces never disagree about what "the next photo" is.
    //
    // Deliberately NOT collected here: subscribing at the top of the screen ran
    // a 500-row query again on every single message that arrived, for a list
    // that is only read once someone opens a photo. It is collected inside the
    // viewer branch instead, so an ordinary thread pays nothing for it.
    val galleryFlow = remember(roomName) { vm.gallery(roomName) }
    val room by roomFlow.collectAsStateWithLifecycle(null)
    val messages = messageFlow.collectAsLazyPagingItems()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val feedback = LocalFeedback.current

    var replyTo by remember { mutableStateOf<ChatMessageEntity?>(null) }
    var selected by remember { mutableStateOf<ChatMessageEntity?>(null) }
    var viewing by remember { mutableStateOf<ChatMessageEntity?>(null) }
    var showCamera by remember { mutableStateOf(false) }
    var rejected by remember { mutableStateOf<String?>(null) }
    var pickingTicket by remember { mutableStateOf(false) }
    var assigning by remember { mutableStateOf<String?>(null) }
    var assigningTo by remember { mutableStateOf<String?>(null) }
    // The client id of the attachment currently being fetched, so its card can
              // show a spinner. Not a LaunchedEffect key: clearing that key is what
              // cancels the very coroutine doing the download, which cost a
              // "coroutine scope left the composition" on the first device run.
    var openingFile by remember { mutableStateOf<String?>(null) }
    var openError by remember { mutableStateOf<String?>(null) }

    // user id → display name, for rendering the `@`s inside a received message.
    // The member list is already loaded for the composer's picker, so this costs
    // nothing extra.
    val nameOf = remember(vm.members, vm.me) {
        buildMap {
            vm.members.forEach { put(it.name, it.full_name?.takeIf(String::isNotBlank) ?: it.name) }
            put(vm.me, vm.myDisplayName)
        }
    }

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
                listState.scrollToItem(0)
            }
        }
    }

    // Any file at all. OpenDocument rather than GetContent: it returns a stable,
    // re-openable Uri from the system picker (including Drive and other
    // providers), which is what a 400 MB upload sitting in a queue needs.
    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val picked = Attachments.copyToOutbox(context, uri)
            if (picked == null) {
                rejected = "Installable and executable files can't be sent in chat."
                feedback.error()
            } else {
                feedback.messageSent()
                vm.sendAttachment(
                    room = roomName,
                    file = picked.file,
                    contentType = picked.contentType,
                    kind = picked.kind,
                    fileName = picked.displayName,
                    replyTo = replyTo?.serverName,
                )
                replyTo = null
                listState.scrollToItem(0)
            }
        }
    }

    // Join the doc room while this screen is on top; leave on the way out so we
    // are not holding a subscription for every thread ever opened.
    LaunchedEffect(roomName) { vm.openThread(roomName) }
    DisposableEffect(roomName) { onDispose { vm.closeThread(roomName) } }

    // Tell the notifier this thread is on screen, and clear anything already in
    // the tray for it. Buzzing someone's pocket about a message they are
    // watching arrive is the fastest way to get notifications switched off.
    DisposableEffect(roomName) {
        ChatNotifications.visibleRoom = roomName
        ChatNotifications.clear(context, roomName)
        onDispose { if (ChatNotifications.visibleRoom == roomName) ChatNotifications.visibleRoom = null }
    }

    // Chime only for messages that arrive while you are looking at the thread.
    LaunchedEffect(Unit) { vm.incoming.collect { feedback.messageReceived() } }

    // Reading the newest message is what clears the badge.
    val newestSeq = remember(messages.itemCount) {
        if (messages.itemCount == 0) 0L else messages.peek(0)?.seq ?: 0L
    }
    LaunchedEffect(newestSeq) { if (newestSeq > 0) vm.markRead(roomName, newestSeq) }

    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex <= 2 } }

    /**
     * Follow the conversation as it grows.
     *
     * Keyed on the newest row's identity rather than fired at the send call
     * site, which was the bug: sending scrolled immediately, before the
     * optimistic row had travelled through Room and Paging, so it animated to
     * the message that was already there and the new one appeared below the
     * fold. Incoming messages did not scroll at all.
     *
     * Your own message always wins the scroll — you just pressed send, so being
     * shown anything else is wrong. Someone else's only scrolls if you were
     * already at the bottom; yanking the view while a technician is reading
     * back through a thread is worse than making them tap the jump button,
     * which is what appears instead.
     */
    val newestKey = if (messages.itemCount == 0) null else messages.peek(0)?.clientId
    LaunchedEffect(newestKey) {
        if (newestKey == null) return@LaunchedEffect
        val mine = messages.peek(0)?.author == vm.me
        if (mine || atBottom) listState.scrollToItem(0)
    }

    /**
     * Where "New messages" goes.
     *
     * Captured the first time the room row is seen and then left alone,
     * because opening the thread immediately advances the read cursor — read
     * it live and the divider would vanish the moment it appeared. Null once
     * the thread has been open a while, which is what keeps the line from
     * re-appearing above every message that arrives while you are watching.
     */
    var unreadFrom by remember(roomName) { mutableStateOf<Long?>(null) }
    var unreadPinned by remember(roomName) { mutableStateOf(false) }
    LaunchedEffect(roomName, room?.lastReadSeq, room?.lastSeq) {
        val r = room ?: return@LaunchedEffect
        if (unreadPinned) return@LaunchedEffect
        unreadPinned = true
        unreadFrom = r.lastReadSeq.takeIf { r.lastSeq > it }
    }

    BackHandler(enabled = selected != null || replyTo != null) {
        selected = null
        replyTo = null
    }

    // Full-screen surfaces take over completely rather than stacking over the
    // thread — both own the back gesture while they are up.
    if (showCamera) {
        StampingCamera(
            // Which conversation the photo was taken in, not the literal word
            // "Chat" — that told a reviewer nothing they could act on. Truncated
            // because the stamp's background bar is sized to its widest line, so
            // a long group name would run off the edge of the picture.
            label = "Conversation: " + (
                room?.title?.trim()?.takeIf { it.isNotEmpty() }?.let { title ->
                    if (title.length > 28) title.take(27).trimEnd() + "…" else title
                } ?: "Unknown"
                ),
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

    viewing?.let { shot ->
        // Opening a photo from the thread gives you the room's whole photo run,
        // not just the one tapped — the same set the gallery shows, so flicking
        // sideways lands on the same neighbours either way you got here. Taken
        // from the gallery query rather than the paged list because paging has
        // only loaded as far back as the user happens to have scrolled.
        val galleryRows by galleryFlow.collectAsStateWithLifecycle(emptyList())
        val mediaRun = remember(galleryRows) {
            galleryRows.filter { Gallery.tabOf(it) == Gallery.Tab.MEDIA }
        }
        val pages = remember(mediaRun) {
            mediaRun.map { row ->
                MediaPage(
                    key = row.clientId,
                    model = row.localPath ?: row.fileUrl?.let { absoluteUrl(it) },
                    title = if (row.author == vm.me) "You" else row.authorName,
                    subtitle = "${dayLabel(row.createdAt)} · ${clockTime(row.createdAt)}",
                    caption = row.body,
                )
            }
        }
        val start = pages.indexOfFirst { it.key == shot.clientId }
        if (pages.isEmpty() || start < 0) {
            // The tapped photo is still in the outbox and has no row in the
            // gallery query yet; show it on its own rather than nothing.
            MediaViewer(
                model = shot.localPath ?: shot.fileUrl?.let { absoluteUrl(it) },
                title = if (vm.isMine(shot)) "You" else shot.authorName,
                subtitle = "${dayLabel(shot.createdAt)} · ${clockTime(shot.createdAt)}",
                caption = shot.body,
                onClose = { viewing = null },
            )
        } else {
            MediaPagerViewer(
                pages = pages,
                startIndex = start,
                onClose = { viewing = null },
            )
        }
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

    openError?.let { message ->
        AlertDialog(
            onDismissRequest = { openError = null },
            title = { Text("Couldn't open that") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { openError = null }) { Text("OK") } },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(ChatTokens.ground)
            // One inset call, not two. `imePadding()` on the root plus
            // `navigationBarsPadding()` on the composer *sums* the two — the
            // keyboard pushed the composer up by its own height and then the
            // nav bar's on top, which is the empty band that appeared under the
            // text field the moment anyone started typing. `union` takes the
            // larger of the two instead, which is what is actually wanted.
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
    ) {
        if (selected != null) {
            SelectionBar(
                onDismiss = { selected = null },
                onReply = { replyTo = selected; selected = null },
                onRaiseTicket = { onRaiseTicket(selected!!); selected = null },
            )
        } else {
            ThreadHeader(
                title = room?.title ?: "Chat",
                subtitle = threadSubtitle(room?.kind, room?.memberCount ?: 0, room?.vehicle),
                avatarSeed = room?.peer ?: roomName,
                avatarImage = room?.peerImage,
                muted = room?.muted == true,
                onBack = onBack,
                onToggleMute = { vm.setMuted(roomName, room?.muted != true) },
                onOpenGallery = onOpenGallery,
            )
        }

        ConnectionBanner(vm.connection, vm.connectionDetail)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 10.dp, bottom = 6.dp),
            ) {
                items(
                    count = messages.itemCount,
                    key = messages.itemKey { it.clientId },
                    // Lets the lazy list reuse a text row for a text row rather
                    // than tearing one down to build a photo row.
                    contentType = messages.itemContentType { it.kind },
                ) { index ->
                    val message = messages[index] ?: return@items
                    // reverseLayout: index+1 is the OLDER neighbour, drawn above.
                    val older = if (index + 1 < messages.itemCount) messages.peek(index + 1) else null
                    val newRun = older == null || older.author != message.author
                    val newDay = older == null || dayLabel(older.createdAt) != dayLabel(message.createdAt)

                    SwipeableMessage(
                        message = message,
                        isMine = vm.isMine(message),
                        showAuthor = newRun,
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
                        onOpenMedia = {
                            if (message.kind != "file") {
                                viewing = message
                            } else if (openingFile == null) {
                                openingFile = message.clientId
                                scope.launch {
                                    val result = FileOpener.open(context, message)
                                    openingFile = null
                                    if (result is FileOpener.Result.Failed) openError = result.reason
                                }
                            }
                        },
                        isOpening = openingFile == message.clientId,
                        mentionLabels = message.mentions
                            ?.split(",")
                            ?.mapNotNull { nameOf[it] }
                            .orEmpty(),
                        onAssignTicket = { assigning = it },
                    )

                    // Breathing room above a new speaker, so a busy depot thread
                    // reads as a set of turns rather than one wall.
                    if (newRun && !newDay) Spacer(Modifier.height(ChatTokens.gapBetweenRuns))

                    // Emitted after the message because reverseLayout draws it
                    // above — the same trick the day divider uses. This is the
                    // boundary row: the oldest message you have not read.
                    unreadFrom?.let { mark ->
                        val seq = message.seq
                        val olderSeq = older?.seq
                        val isFirstUnread = seq != null && seq > mark &&
                            (older == null || (olderSeq != null && olderSeq <= mark))
                        if (isFirstUnread && !vm.isMine(message)) {
                            UnreadDivider(((room?.lastSeq ?: 0L) - mark).toInt().coerceAtLeast(1))
                        }
                    }

                    if (newDay) DayDivider(dayLabel(message.createdAt))
                }
            }

            // Jump-to-latest, only once the user has scrolled meaningfully away.
            // No scale/fade: the button is either needed or it is not, and an
            // animation on it is 200ms of the frame budget spent on furniture.
            if (!atBottom) {
                Surface(
                    color = ChatTokens.field,
                    shape = CircleShape,
                    shadowElevation = 3.dp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(14.dp)
                        .size(40.dp)
                        .clickable { scope.launch { listState.scrollToItem(0) } },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = "Jump to latest",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        replyTo?.let { ReplyBar(it) { replyTo = null } }

        Composer(
            members = vm.members,
            onSend = { text, mentions ->
                val parent = replyTo
                replyTo = null
                feedback.messageSent()
                vm.sendText(roomName, text, parent?.serverName, mentions)
                scope.launch { listState.scrollToItem(0) }
            },
            onCamera = { feedback.tap(); showCamera = true },
            onAttach = {
                picker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                )
            },
            onAttachFile = { documentPicker.launch(arrayOf("*/*")) },
            onShareTicket = { pickingTicket = true },
            onVoice = { file, durationMs ->
                vm.sendAttachment(
                    room = roomName,
                    file = file,
                    contentType = "audio/mp4",
                    kind = "audio",
                    fileName = file.name,
                    durationMs = durationMs,
                    replyTo = replyTo?.serverName,
                )
                replyTo = null
            },
        )
    }

    if (pickingTicket) {
        TicketPickerSheet(
            vm = vm,
            room = roomName,
            onDismiss = { pickingTicket = false },
            onShared = {
                pickingTicket = false
                feedback.messageSent()
                scope.launch { listState.scrollToItem(0) }
            },
            onError = { pickingTicket = false; rejected = it },
        )
    }

    assigning?.let { ticket ->
        AssignTicketSheet(
            ticket = ticket,
            candidates = vm.members,
            busyFor = assigningTo,
            onDismiss = { assigning = null; assigningTo = null },
            onPick = { user ->
                assigningTo = user.name
                vm.assignTicket(ticket, user.name, roomName) { error ->
                    assigningTo = null
                    assigning = null
                    if (error != null) rejected = error else feedback.messageSent()
                }
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
 * threshold.
 *
 * The displacement lives in a float that is only ever read inside
 * `graphicsLayer`, so dragging a row redraws it without recomposing it, and the
 * whole gesture allocates nothing per frame. The earlier version launched a
 * coroutine per drag delta — a hundred-odd coroutines for one flick of a thumb.
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
    onOpenMedia: () -> Unit,
    mentionLabels: List<String>,
    onAssignTicket: (String) -> Unit,
    isOpening: Boolean,
) {
    var dragX by remember { mutableFloatStateOf(0f) }
    val triggerPx = with(LocalDensity.current) { REPLY_TRIGGER_DP.dp.toPx() }
    var fired by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxWidth()) {
        // Composed once and left alone; its alpha is a deferred read so the
        // icon fades in during the drag without recomposing anything.
        Icon(
            Icons.AutoMirrored.Filled.Reply,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 18.dp)
                .size(19.dp)
                .graphicsLayer { alpha = (dragX / triggerPx).coerceIn(0f, 1f) },
        )

        Box(
            Modifier
                .graphicsLayer { translationX = dragX }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        // Right-swipe only, with a hard ceiling past the trigger
                        // so the gesture feels bounded rather than loose.
                        dragX = (dragX + delta).coerceIn(0f, triggerPx * 1.35f)
                        if (!fired && dragX >= triggerPx) {
                            fired = true
                            onReply()
                        }
                    },
                    onDragStopped = {
                        fired = false
                        animate(dragX, 0f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) {
                            value, _ ->
                            dragX = value
                        }
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
                onOpenMedia = onOpenMedia,
                mentionLabels = mentionLabels,
                onAssignTicket = onAssignTicket,
                isOpening = isOpening,
            )
        }
    }
}

private const val REPLY_TRIGGER_DP = 64

@Composable
private fun ThreadHeader(
    title: String,
    subtitle: String,
    avatarSeed: String,
    avatarImage: String?,
    muted: Boolean,
    onBack: () -> Unit,
    onToggleMute: () -> Unit,
    onOpenGallery: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.horizontalGradient(BrandGradient))
            .statusBarsPadding()
            .padding(start = 2.dp, end = 4.dp, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        // Who you are talking to, not just their name. On a handset held at
        // arm's length in a depot, the face is the faster identifier.
        Avatar(title, avatarImage, avatarSeed, size = 36, onDark = true)
        Spacer(Modifier.width(10.dp))
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
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Text("⋮", color = Color.White, style = MaterialTheme.typography.titleLarge)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Media, files and links") },
                    onClick = { onOpenGallery(); menuOpen = false },
                )
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
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onRaiseTicket: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary)
            .statusBarsPadding()
            .padding(horizontal = 6.dp, vertical = 8.dp),
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
            .background(ChatTokens.ground)
            .padding(start = 7.dp, end = 7.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = ChatTokens.field,
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 4.dp, bottomEnd = 4.dp),
            modifier = Modifier.weight(1f),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .padding(start = 8.dp)
                        .width(3.dp)
                        .height(34.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(authorColor(message.author)),
                )
                Column(Modifier.weight(1f).padding(horizontal = 9.dp, vertical = 6.dp)) {
                    Text(
                        message.authorName,
                        style = MaterialTheme.typography.labelSmall,
                        color = authorColor(message.author),
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
                IconButton(onClick = onClear, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Cancel reply",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
    }
}

/**
 * The composer.
 *
 * A single pill holding the text and the attachment affordance, with the send
 * button as a separate disc — the arrangement everyone already has muscle
 * memory for.
 *
 * `BasicTextField` rather than Material's `TextField`: the Material one reserves
 * vertical space for a floating label whether or not it has one, so it is 56dp
 * tall with the text sitting low inside it, and that reserved band was most of
 * the dead space under the cursor. This one is exactly its own padding.
 *
 * The draft is owned here. Hoisting it to the screen meant every keystroke
 * recomposed the header and the message list along with the field.
 */
@Composable
private fun Composer(
    members: List<ChatUserDto>,
    onSend: (String, List<String>) -> Unit,
    onCamera: () -> Unit,
    onAttach: () -> Unit,
    onAttachFile: () -> Unit,
    onShareTicket: () -> Unit,
    onVoice: (java.io.File, Long) -> Unit,
) {
    var draft by remember { mutableStateOf(TextFieldValue("")) }
    // Display name → user id, for everyone picked from the dropdown while this
    // draft was being written. Resolved against the final text on send.
    val picked = remember { mutableStateMapOf<String, String>() }
    var menuOpen by remember { mutableStateOf(false) }
    val canSend = draft.text.isNotBlank()
    val scheme = MaterialTheme.colorScheme
    val feedback = LocalFeedback.current

    val token = Mentions.activeToken(draft.text, draft.selection.start)
    val candidates = remember(token?.query, members) {
        val q = token?.query?.trim()?.lowercase()
        when {
            q == null -> emptyList()
            q.isEmpty() -> members.take(MENTION_LIMIT)
            else -> members.filter {
                (it.full_name ?: it.name).lowercase().contains(q)
            }.take(MENTION_LIMIT)
        }
    }

    Column(Modifier.fillMaxWidth().background(ChatTokens.ground)) {

        if (candidates.isNotEmpty() && token != null) {
            MentionPicker(candidates) { user ->
                val label = user.full_name?.takeIf { it.isNotBlank() } ?: user.name
                picked[label] = user.name
                draft = Mentions.applyPick(draft, token, label)
                feedback.tap()
            }
        }

        Row(
            // Wider margins than the thread's own gutter, and a real gap before
            // the mic. At 8dp the pill ran almost to the glass and the mic sat
            // against it, so the two read as one strip of controls rather than
            // a field and a button — and the mic was easy to catch with the
            // side of a thumb while reaching for the text.
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                color = ChatTokens.field,
                shape = RoundedCornerShape(26.dp),
                tonalElevation = 0.dp,
                // A hairline rather than a drop shadow: the pill sits on a tinted
                // canvas where a shadow just muddies the edge, and an outline is
                // one draw instead of a separate render pass.
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shadowElevation = 0.dp,
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                        cursorBrush = SolidColor(scheme.primary),
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 18.dp, end = 2.dp, top = 14.dp, bottom = 14.dp),
                        decorationBox = { inner ->
                            Box {
                                if (draft.text.isEmpty()) {
                                    Text(
                                        "Message",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = scheme.onSurfaceVariant,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                    Box {
                        ComposerAction(Icons.Default.AttachFile, "Attach") {
                            feedback.tap()
                            menuOpen = true
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Photo or video") },
                                leadingIcon = { Icon(Icons.Default.Image, null) },
                                onClick = { menuOpen = false; onAttach() },
                            )
                            DropdownMenuItem(
                                text = { Text("Camera") },
                                leadingIcon = { Icon(Icons.Default.PhotoCamera, null) },
                                onClick = { menuOpen = false; onCamera() },
                            )
                            // The reason chat lives in this app rather than in
                            // WhatsApp: the work item comes with it.
                            DropdownMenuItem(
                                text = { Text("Document") },
                                leadingIcon = { Icon(Icons.Default.InsertDriveFile, null) },
                                onClick = { menuOpen = false; onAttachFile() },
                            )
                            DropdownMenuItem(
                                text = { Text("Service ticket") },
                                leadingIcon = { Icon(Icons.Default.ConfirmationNumber, null) },
                                onClick = { menuOpen = false; onShareTicket() },
                            )
                        }
                    }
                    ComposerAction(Icons.Default.PhotoCamera, "Take a stamped photo", onCamera)
                    Spacer(Modifier.width(6.dp))
                }
            }

            // Always present, greyed when there is nothing to send. Showing and
            // hiding it shifts the pill's width mid-sentence, which is worse. A
            // neutral disabled state rather than a translucent brand colour —
            // a faded indigo disc reads as a rendering fault, not as "not yet".
            if (canSend) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(scheme.primary)
                        .clickable {
                            onSend(draft.text, Mentions.survivingMentions(draft.text, picked))
                            draft = TextFieldValue("")
                            picked.clear()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Send,
                        contentDescription = "Send",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                // With nothing typed the button is a microphone, exactly where
                // the send button was. Hold to record, release to send — the
                // gesture is already muscle memory, and it means a voice note
                // costs one press rather than a trip through the attach menu.
                VoiceButton(onRecorded = onVoice)
            }
        }
    }
}

/**
 * Hold-to-record microphone.
 *
 * Release sends; a press shorter than the recorder's floor is discarded as a
 * mis-tap rather than sent as a half-second of nothing. The permission is
 * requested on first press — asking for a microphone during onboarding, before
 * anyone has tried to record, is the request people refuse.
 */
@Composable
private fun VoiceButton(onRecorded: (java.io.File, Long) -> Unit) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val feedback = LocalFeedback.current
    val recorder = remember { VoiceRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    var elapsed by remember { mutableStateOf(0L) }

    var hasMic by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { hasMic = it }

    // Releases the microphone if the screen goes away mid-recording; a
    // MediaRecorder left running holds the mic for the whole device.
    DisposableEffect(Unit) { onDispose { recorder.cancel() } }

    LaunchedEffect(recording) {
        while (recording) {
            elapsed = recorder.elapsedMs()
            kotlinx.coroutines.delay(100)
        }
    }

    if (recording) {
        Text(
            "● ${formatDuration(elapsed)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(end = 8.dp),
        )
    }

    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            // Solid, like the send button it replaces. On surfaceVariant it read
            // as an empty placeholder next to the white pill rather than
            // something you press; red while recording, so the state is obvious
            // without looking at the timer.
            .background(if (recording) MaterialTheme.colorScheme.error else scheme.primary)
            .pointerInput(hasMic) {
                detectTapGestures(
                    onPress = {
                        if (!hasMic) {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            return@detectTapGestures
                        }
                        if (!recorder.start()) return@detectTapGestures
                        recording = true
                        feedback.tap()
                        // Suspends until the finger lifts, which is what makes
                        // this hold-to-record rather than tap-to-toggle.
                        tryAwaitRelease()
                        recording = false
                        val note = recorder.stop()
                        if (note != null) {
                            feedback.messageSent()
                            onRecorded(note.file, note.durationMs)
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Mic,
            contentDescription = "Hold to record a voice note",
            tint = Color.White,
            modifier = Modifier.size(21.dp),
        )
    }
}

/** The `@` dropdown. Sits directly on the composer, as it does everywhere else. */
@Composable
private fun MentionPicker(candidates: List<ChatUserDto>, onPick: (ChatUserDto) -> Unit) {
    Surface(
        color = ChatTokens.field,
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp),
    ) {
        LazyColumn(Modifier.heightIn(max = 210.dp)) {
            items(candidates, key = { it.name }) { user ->
                val label = user.full_name?.takeIf { it.isNotBlank() } ?: user.name
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(user) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(label, user.user_image, user.name, size = 32)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** More than this and the picker covers the message being written. */
private const val MENTION_LIMIT = 6

@Composable
private fun ComposerAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(21.dp),
        )
    }
}

private fun threadSubtitle(kind: String?, members: Int, vehicle: String?): String = buildString {
    if (vehicle != null) append(vehicle).append(" · ")
    // A direct thread has exactly two members; saying "2 members" about a
    // one-to-one conversation is noise.
    if (kind == "Direct") append("Direct message")
    else append(if (members == 1) "1 member" else "$members members")
}
