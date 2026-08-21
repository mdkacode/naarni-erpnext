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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Send
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.naarni.service.core.audio.VoicePlayer
import com.naarni.service.core.audio.VoiceRecorder
import com.naarni.service.core.push.ChatNotifications
import com.naarni.service.data.chat.ChatMessageEntity
import com.naarni.service.data.chat.ChatRoomEntity
import com.naarni.service.data.chat.SendStatus
import com.naarni.service.data.dto.ChatUserDto
import com.naarni.service.ui.components.AppBar
import com.naarni.service.ui.components.StampingCamera
import com.naarni.service.ui.components.VideoRecorderScreen
import com.naarni.service.core.media.VideoCompressor
import com.naarni.service.ui.theme.AppSurface
import com.naarni.service.ui.theme.Semantic
import kotlinx.coroutines.launch
import com.naarni.service.ui.theme.Radii
import com.naarni.service.ui.theme.Elevation

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
    // Quoted originals for every reply in the room, resolved in one query rather
    // than one per bubble. See ChatDao.observeReplyParents.
    val quoteFlow = remember(roomName) { vm.replyParents(roomName) }
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
    val quotes by quoteFlow.collectAsStateWithLifecycle(emptyMap())
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val feedback = LocalFeedback.current

    var replyTo by remember { mutableStateOf<ChatMessageEntity?>(null) }
    var selected by remember { mutableStateOf<ChatMessageEntity?>(null) }
    var viewing by remember { mutableStateOf<ChatMessageEntity?>(null) }
    var showCamera by remember { mutableStateOf(false) }
    var showVideo by remember { mutableStateOf(false) }

    /**
     * Set while a picked video is being transcoded.
     *
     * Shown as a blocking note rather than silently: shrinking a 4K clip takes
     * the better part of a minute, and without it the app looks frozen between
     * choosing a video and the bubble appearing.
     */
    var preparingVideo by remember { mutableStateOf(false) }
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

    /** The message a delete has been asked for, held while it is confirmed. */
    var deleting by remember { mutableStateOf<ChatMessageEntity?>(null) }

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
                // A clip straight out of the gallery is whatever the phone's own
                // camera app produced, which on a recent handset means 4K. It is
                // shrunk before it is queued, so the outbox holds the bytes that
                // will actually be sent and a retry never repeats the work.
                var file = picked.file
                var duration: Long? = null
                if (picked.kind == "video") {
                    preparingVideo = true
                    file = VideoCompressor.compress(context, picked.file)
                    duration = VideoCompressor.durationMs(file)
                    preparingVideo = false
                }
                feedback.messageSent()
                vm.sendAttachment(
                    room = roomName,
                    file = file,
                    contentType = picked.contentType,
                    kind = picked.kind,
                    fileName = file.name,
                    durationMs = duration,
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

    /**
     * A finished voice note rolls into the next one.
     *
     * Somebody catching up on a run of six notes from a breakdown should not have
     * to tap six times, each tap requiring them to find the next bubble first.
     * "Next" means the newer neighbour in the thread and only if it is *directly*
     * adjacent — running on past an intervening photo or a text message would be
     * playing audio the user never asked for, which is a far worse failure than
     * making them tap once more.
     */
    DisposableEffect(messages) {
        VoicePlayer.onFinished = { finishedId ->
            val rows = messages.itemSnapshotList.items
            val at = rows.indexOfFirst { it.clientId == finishedId }
            // Newest-first, so the *next* note chronologically is at index - 1.
            val next = if (at > 0) rows[at - 1] else null
            val source = next?.takeIf { it.kind == "audio" }
                ?.let { it.localPath ?: it.fileUrl?.let(::absoluteUrl) }
            if (next != null && source != null) {
                VoicePlayer.toggle(context, next.clientId, source)
            } else {
                VoicePlayer.stop()
            }
        }
        onDispose { VoicePlayer.onFinished = null }
    }

    // Reading the newest message is what clears the badge.
    val newestSeq = remember(messages.itemCount) {
        if (messages.itemCount == 0) 0L else messages.peek(0)?.seq ?: 0L
    }
    LaunchedEffect(newestSeq) { if (newestSeq > 0) vm.markRead(roomName, newestSeq) }

    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex <= 2 } }

    /**
     * The message a reply quote just jumped to, flashed briefly.
     *
     * Without this the jump is disorienting: the list moves, and the reader has
     * to work out which of the messages now on screen was the one they asked
     * for. The flash answers that before they have to look for it.
     */
    var highlighted by remember(roomName) { mutableStateOf<String?>(null) }

    /**
     * Scroll to a message by client id.
     *
     * Only reaches messages Paging has actually loaded, which is the honest
     * limit here — the alternative is to keep paging backwards until the target
     * appears, and a quote of something from six months ago would then pull the
     * entire thread into memory to answer one tap. When it is not loaded the
     * list stays put and nothing flashes, which reads as "that is too far back"
     * rather than as a broken control.
     */
    fun jumpTo(clientId: String) {
        val index = messages.itemSnapshotList.items.indexOfFirst { it.clientId == clientId }
        if (index < 0) return
        feedback.tap()
        scope.launch {
            listState.animateScrollToItem(index)
            highlighted = clientId
            kotlinx.coroutines.delay(HIGHLIGHT_MS)
            highlighted = null
        }
    }

    // One object per room update rather than two Longs down every call site, and
    // stable enough that unchanged rows are not recomposed when it is rebuilt.
    val receipts = remember(room?.deliveredUpto, room?.readUpto) {
        Receipts(room?.deliveredUpto ?: 0L, room?.readUpto ?: 0L)
    }

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
            onCaptured = { file, _ ->
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

    if (showVideo) {
        VideoRecorderScreen(
            onClose = { showVideo = false },
            onRecorded = { file, durationMs ->
                showVideo = false
                feedback.messageSent()
                vm.sendAttachment(
                    room = roomName,
                    file = file,
                    contentType = "video/mp4",
                    kind = "video",
                    fileName = file.name,
                    durationMs = durationMs,
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
                    isVideo = row.kind == "video",
                )
            }
        }
        val start = pages.indexOfFirst { it.key == shot.clientId }
        if (pages.isEmpty() || start < 0) {
            // The tapped item is still in the outbox and has no row in the
            // gallery query yet; show it on its own rather than nothing. A video
            // goes through the pager even as a run of one, because the single
            // viewer only knows how to draw a still and would render the first
            // frame of a clip as an un-playable image.
            val lone = MediaPage(
                key = shot.clientId,
                model = shot.localPath ?: shot.fileUrl?.let { absoluteUrl(it) },
                title = if (vm.isMine(shot)) "You" else shot.authorName,
                subtitle = "${dayLabel(shot.createdAt)} · ${clockTime(shot.createdAt)}",
                caption = shot.body,
                isVideo = shot.kind == "video",
            )
            if (lone.isVideo) {
                MediaPagerViewer(pages = listOf(lone), startIndex = 0, onClose = { viewing = null })
            } else {
                MediaViewer(
                    model = lone.model,
                    title = lone.title,
                    subtitle = lone.subtitle,
                    caption = lone.caption,
                    onClose = { viewing = null },
                )
            }
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

    if (preparingVideo) {
        // Deliberately not dismissible. The transcode is already running and
        // cancelling it halfway would leave a part-written file to clean up for
        // no gain — the wait is under a minute and the alternative is uploading
        // ten times the bytes over a depot's link.
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Preparing video") },
            text = { Text("Shrinking it so it sends quickly. This takes a moment.") },
            confirmButton = { },
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

    deleting?.let { target ->
        // Confirmed rather than done on the tap. Deleting for everyone is not
        // undoable from here, and a long-press followed by a mis-tap in a moving
        // vehicle is exactly how it would otherwise happen.
        val everyone = !target.serverName.isNullOrBlank()
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(if (everyone) "Delete this message?" else "Remove this message?") },
            text = {
                Text(
                    if (everyone) {
                        "It will be removed for everyone in this chat."
                    } else {
                        // Never sent, so there is nobody to remove it from.
                        "It hasn't been sent, so it will just be discarded."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val message = target
                        deleting = null
                        selected = null
                        vm.deleteMessage(message) { error -> openError = error }
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
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
                // Absent, not disabled, once the window has passed — a greyed
                // bin invites a tap that can only answer with a refusal, and
                // explaining the refusal would mean naming a deadline the UI is
                // deliberately silent about.
                onDelete = selected
                    ?.takeIf { vm.canDelete(it) }
                    ?.let { target -> { deleting = target } },
                onReact = { code ->
                    feedback.tap()
                    vm.toggleReaction(selected?.serverName, code)
                    selected = null
                },
            )
        } else {
            ThreadHeader(
                title = room?.title ?: "Chat",
                // Typing replaces the member count while it lasts. It is the only
                // thing on that line anybody is reading at that moment, and
                // showing both meant the live fact was appended to a static one.
                subtitle = typingSubtitle(vm.typingNames, room?.kind)
                    ?: presenceSubtitle(room, vm.onlineUsers, vm.lastSeen)
                    ?: threadSubtitle(room?.kind, room?.memberCount ?: 0, room?.vehicle),
                subtitleIsLive = vm.typingNames.isNotEmpty(),
                avatarSeed = room?.peer ?: roomName,
                avatarImage = room?.peerImage,
                // Only meaningful one-to-one: a green dot on a group avatar would
                // be claiming something about twelve people at once.
                online = room?.kind == "Direct" && room?.peer in vm.onlineUsers,
                muted = room?.muted == true,
                onBack = onBack,
                onToggleMute = { vm.setMuted(roomName, room?.muted != true) },
                onOpenGallery = onOpenGallery,
            )
        }

        ConnectionBanner(vm.connection, vm.connectionDetail)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            // A thread opened for the first time has nothing in Room yet and has
            // to wait on sync. A blank canvas for that second reads as an empty
            // conversation — people genuinely thought messages had been lost —
            // so the shape of a conversation is drawn while it loads instead.
            // Only on a genuinely empty list: once a single row exists, Room is
            // the truth and a skeleton over real content would be a lie.
            val coldOpen = messages.itemCount == 0 &&
                messages.loadState.refresh is androidx.paging.LoadState.Loading
            if (coldOpen) {
                ThreadSkeleton(Modifier.fillMaxSize())
            }

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
                        // Either long-pressed, or briefly flashed because a reply
                        // quote just jumped here.
                        isSelected = selected?.clientId == message.clientId ||
                            highlighted == message.clientId,
                        replyPreview = message.replyTo?.let { quotes[it] },
                        onOpenQuote = { parent -> jumpTo(parent.clientId) },
                        // A tombstone offers nothing: there is no text to quote,
                        // nothing to react to and nothing left to raise a ticket
                        // from. Swipe and long-press simply do not respond.
                        onReply = {
                            if (!message.deleted) {
                                feedback.replyTriggered()
                                replyTo = message
                            }
                        },
                        onLongPress = {
                            if (!message.deleted) {
                                feedback.selectionEntered()
                                selected = message
                            }
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
                        receipts = receipts,
                        me = vm.me,
                        onReact = { code -> vm.toggleReaction(message.serverName, code) },
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
                // How much is below the fold, so the button answers "is it worth
                // going back down" rather than only offering to. Counted from the
                // read cursor, which is what actually stopped moving when the user
                // scrolled up.
                val missed = ((room?.lastSeq ?: 0L) - (room?.lastReadSeq ?: 0L))
                    .coerceAtLeast(0L)
                    .toInt()
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(14.dp),
                    contentAlignment = Alignment.TopEnd,
                ) {
                    Surface(
                        color = ChatTokens.field,
                        shape = CircleShape,
                        shadowElevation = Elevation.e2,
                        modifier = Modifier
                            .padding(top = if (missed > 0) 7.dp else 0.dp)
                            .size(40.dp)
                            .clickable { scope.launch { listState.scrollToItem(0) } },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.KeyboardArrowDown,
                                contentDescription = if (missed > 0) {
                                    "Jump to latest, $missed unread"
                                } else {
                                    "Jump to latest"
                                },
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (missed > 0) {
                        Surface(color = MaterialTheme.colorScheme.primary, shape = CircleShape) {
                            Text(
                                if (missed > 99) "99+" else "$missed",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
            }
        }

        replyTo?.let { ReplyBar(it) { replyTo = null } }

        Composer(
            members = vm.members,
            onTyping = { active -> vm.setTyping(roomName, active) },
            onSend = { text, mentions ->
                val parent = replyTo
                replyTo = null
                feedback.messageSent()
                vm.sendText(roomName, text, parent?.serverName, mentions)
                scope.launch { listState.scrollToItem(0) }
            },
            onCamera = { feedback.tap(); showCamera = true },
            onRecordVideo = { feedback.tap(); showVideo = true },
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
    receipts: Receipts,
    me: String,
    onReact: (String) -> Unit,
    onOpenQuote: (ChatMessageEntity) -> Unit,
) {
    var dragX by remember { mutableFloatStateOf(0f) }
    val triggerPx = with(LocalDensity.current) { REPLY_TRIGGER_DP.dp.toPx() }
    var fired by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxWidth()) {
        // Composed once and left alone; its alpha is a deferred read so the
        // icon fades in during the drag without recomposing anything.
        Icon(
            Icons.AutoMirrored.Rounded.Reply,
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
                receipts = receipts,
                onOpenQuote = onOpenQuote,
                me = me,
                onReact = onReact,
            )
        }
    }
}

/**
 * The shape of a conversation, drawn while the first page loads.
 *
 * Alternating sides and varied widths, because a column of identical grey blocks
 * reads as a broken list rather than as a thread arriving. Deliberately static:
 * a shimmer on a placeholder that is typically on screen for under a second is
 * an animation nobody sees the start or end of, and it competes with the real
 * content landing on top of it.
 */
@Composable
private fun ThreadSkeleton(modifier: Modifier = Modifier) {
    // Fixed, not random: recomposing into a different arrangement mid-load is
    // the one thing that would make this read as broken.
    val run = listOf(
        0.55f to false, 0.38f to true, 0.72f to false,
        0.46f to true, 0.61f to false, 0.34f to true,
    )
    Column(
        modifier.padding(horizontal = ChatTokens.threadGutter, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        run.forEach { (fraction, mine) ->
            Box(
                Modifier.fillMaxWidth(),
                contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(38.dp)
                        .clip(RoundedCornerShape(ChatTokens.bubbleRadius))
                        .background(
                            if (mine) {
                                ChatTokens.outgoing.copy(alpha = 0.45f)
                            } else {
                                ChatTokens.incoming.copy(alpha = 0.55f)
                            },
                        ),
                )
            }
        }
    }
}

private const val REPLY_TRIGGER_DP = 64

/** How far left the finger must travel before releasing destroys the recording. */
private const val CANCEL_SLIDE_DP = 70

/** Bars held in the live waveform's ring buffer. */
private const val WAVE_BARS = 48

/**
 * Waveform / timer sample interval.
 *
 * `getMaxAmplitude` reports the peak since the previous call and resets, so this
 * doubles as the waveform's resolution: faster looks smoother and costs a
 * recomposition each time, slower reads as laggy against the user's own voice.
 */
private const val WAVE_TICK_MS = 70L

/** How long a jumped-to message stays highlighted. */
private const val HIGHLIGHT_MS = 1_400L

/**
 * Minimum gap between two typing signals for the same draft.
 *
 * Comfortably inside the server's 8-second TTL so a continuing typist never
 * flickers, and long enough that a fast typist sends single figures of requests
 * per message rather than one per keystroke.
 */
private const val TYPING_PING_MS = 4_000L

@Composable
private fun ThreadHeader(
    title: String,
    subtitle: String,
    subtitleIsLive: Boolean,
    avatarSeed: String,
    avatarImage: String?,
    online: Boolean,
    muted: Boolean,
    onBack: () -> Unit,
    onToggleMute: () -> Unit,
    onOpenGallery: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    AppBar(
        title = title,
        onBack = onBack,
        // Tapping the name is the shortest route to what has been shared here —
        // the gesture people already have from every other messaging app. It was
        // previously only reachable through the kebab menu, which is the last
        // place anyone looks.
        onTitleClick = onOpenGallery,
        leading = {
            Box {
                // Who you are talking to, not just their name. On a handset held
                // at arm's length in a depot, the face is the faster identifier.
                Avatar(title, avatarImage, avatarSeed, size = 34)
                if (online) PresenceDot(Modifier.align(Alignment.BottomEnd))
            }
        },
        actions = {
            if (muted) {
                Icon(
                    Icons.Rounded.NotificationsOff,
                    contentDescription = "Muted",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = "Conversation options",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Media, files and links") },
                        leadingIcon = { Icon(Icons.Rounded.PhotoLibrary, null) },
                        onClick = { onOpenGallery(); menuOpen = false },
                    )
                    DropdownMenuItem(
                        text = { Text(if (muted) "Unmute" else "Mute notifications") },
                        leadingIcon = {
                            Icon(
                                if (muted) Icons.Rounded.Notifications else Icons.Rounded.NotificationsOff,
                                null,
                            )
                        },
                        onClick = { onToggleMute(); menuOpen = false },
                    )
                }
            }
        },
        subtitleContent = {
            // Drawn here rather than passed as a string so typing can be tinted
            // without the bar knowing what typing is. Accent while live, neutral
            // otherwise — the one moving fact on a static header earns the colour.
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = if (subtitleIsLive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

/**
 * "Ravi is typing…", or null when nobody is.
 *
 * Names are only useful in a group. In a direct thread there is exactly one
 * person it could be and their name is already the title of the screen, so
 * repeating it underneath is noise.
 */
private fun typingSubtitle(names: List<String>, kind: String?): String? = when {
    names.isEmpty() -> null
    kind == "Direct" -> "typing…"
    names.size == 1 -> "${names.first().substringBefore(' ')} is typing…"
    names.size == 2 ->
        "${names[0].substringBefore(' ')} and ${names[1].substringBefore(' ')} are typing…"
    else -> "${names.size} people are typing…"
}

/**
 * "online", or when they were last around. Null in a group.
 *
 * A group has no single answer, and the plausible substitutes are all worse: a
 * count of who is online right now turns the header into a live audience meter
 * for a work conversation, and the most recent member's time answers a question
 * nobody asked. So groups keep their member count.
 *
 * Null also when the peer is offline and there is no recorded time — a new
 * colleague, or one who has hidden it. Falling back to "last seen a long time
 * ago" would invent a fact from an absence.
 */
private fun presenceSubtitle(
    room: ChatRoomEntity?,
    online: Set<String>,
    lastSeen: Map<String, Long>,
): String? {
    if (room?.kind != "Direct") return null
    val peer = room.peer ?: return null
    if (peer in online) return "online"
    return lastSeen[peer]?.let { lastSeenLabel(it) }
}

/** The green "reachable now" dot on an avatar. */
@Composable
private fun PresenceDot(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(11.dp)
            .clip(CircleShape)
            // A ring in the bar's own colour, so the dot reads as sitting on top
            // of the avatar rather than as a hole punched through it.
            .background(AppSurface.raised),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(Semantic.online))
    }
}

/** Contextual action mode — long-press a message. */
@Composable
private fun SelectionBar(
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onRaiseTicket: () -> Unit,
    onReact: (String) -> Unit,
    /** Null when this message can no longer be taken back. */
    onDelete: (() -> Unit)? = null,
) {
  Column(
      Modifier
          .fillMaxWidth()
          .background(MaterialTheme.colorScheme.primary)
          .statusBarsPadding(),
  ) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onDismiss) {
            Icon(Icons.Rounded.Close, contentDescription = "Cancel", tint = Color.White)
        }
        Text(
            "1 selected",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onReply) {
            Icon(Icons.AutoMirrored.Rounded.Reply, contentDescription = "Reply", tint = Color.White)
        }
        // Turning a field observation straight into a Service Ticket is the whole
        // point of chat living inside this app rather than in WhatsApp.
        IconButton(onClick = onRaiseTicket) {
            Icon(
                Icons.Rounded.ConfirmationNumber,
                contentDescription = "Raise ticket from this message",
                tint = Color.White,
            )
        }
        onDelete?.let { delete ->
            IconButton(onClick = delete) {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = "Delete message",
                    tint = Color.White,
                )
            }
        }
    }

    // The emoji row sits inside the selection bar rather than floating over the
    // message. A popup anchored to a bubble has to be positioned against a list
    // that is still settling from the long-press, and it lands off-screen for
    // the last message in the thread — which is the one people react to most.
    ReactionPicker(
        onPick = onReact,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 6.dp, bottom = 6.dp),
    )
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
                        .clip(Radii.xs)
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
                        Icons.Rounded.Close,
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
    onTyping: (Boolean) -> Unit,
    onCamera: () -> Unit,
    onAttach: () -> Unit,
    onRecordVideo: () -> Unit,
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

    /**
     * Typing signal, throttled.
     *
     * Emphatically not one call per keystroke: a technician typing a sentence on
     * a depot's link would put thirty requests on the wire to communicate one
     * bit. A signal is sent on the first character and then at most once per
     * [TYPING_PING_MS] while typing continues, which is what the server's TTL is
     * sized around.
     *
     * The "stopped" edge is sent when the field empties, and again when the
     * composer leaves the composition — closing a thread mid-word must not leave
     * a dot ticking in front of everybody else until it times out.
     */
    var lastPing by remember { mutableStateOf(0L) }
    var wasTyping by remember { mutableStateOf(false) }
    // Hoisted out of VoiceButton so the text pill can yield its width to the
    // recording strip. Leaving it inside meant the strip had to float over a
    // field that was still sitting there, which read as two composers at once.
    var recording by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { if (wasTyping) onTyping(false) } }

    fun noteDraftChanged(next: TextFieldValue) {
        val typing = next.text.isNotBlank()
        val now = System.currentTimeMillis()
        when {
            typing && (!wasTyping || now - lastPing > TYPING_PING_MS) -> {
                lastPing = now
                wasTyping = true
                onTyping(true)
            }
            !typing && wasTyping -> {
                wasTyping = false
                onTyping(false)
            }
        }
        draft = next
    }

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
            if (!recording) Surface(
                color = ChatTokens.field,
                shape = Radii.xl,
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
                        onValueChange = ::noteDraftChanged,
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
                        ComposerAction(Icons.Rounded.AttachFile, "Attach") {
                            feedback.tap()
                            menuOpen = true
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Photo or video") },
                                leadingIcon = { Icon(Icons.Rounded.Image, null) },
                                onClick = { menuOpen = false; onAttach() },
                            )
                            DropdownMenuItem(
                                text = { Text("Camera") },
                                leadingIcon = { Icon(Icons.Rounded.PhotoCamera, null) },
                                onClick = { menuOpen = false; onCamera() },
                            )
                            DropdownMenuItem(
                                text = { Text("Record video") },
                                leadingIcon = { Icon(Icons.Rounded.Videocam, null) },
                                onClick = { menuOpen = false; onRecordVideo() },
                            )
                            // The reason chat lives in this app rather than in
                            // WhatsApp: the work item comes with it.
                            DropdownMenuItem(
                                text = { Text("Document") },
                                leadingIcon = { Icon(Icons.Rounded.InsertDriveFile, null) },
                                onClick = { menuOpen = false; onAttachFile() },
                            )
                            DropdownMenuItem(
                                text = { Text("Service ticket") },
                                leadingIcon = { Icon(Icons.Rounded.ConfirmationNumber, null) },
                                onClick = { menuOpen = false; onShareTicket() },
                            )
                        }
                    }
                    ComposerAction(Icons.Rounded.PhotoCamera, "Take a stamped photo", onCamera)
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
                            // Sending is a hard stop on typing, and must fire
                            // before the field clears — the throttle would
                            // otherwise swallow the "stopped" edge.
                            if (wasTyping) { wasTyping = false; onTyping(false) }
                            draft = TextFieldValue("")
                            picked.clear()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Send,
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
                VoiceButton(
                    onRecorded = onVoice,
                    recording = recording,
                    onRecordingChange = { recording = it },
                )
            }
        }
    }
}

/**
 * Hold-to-record microphone, with slide-to-cancel.
 *
 * Release sends; a press shorter than the recorder's floor is discarded as a
 * mis-tap rather than sent as a half-second of nothing. The permission is
 * requested on first press — asking for a microphone during onboarding, before
 * anyone has tried to record, is the request people refuse.
 *
 * **Cancel is the point of this component.** Without it, the only way out of a
 * recording you have changed your mind about is to send it and then wish you
 * had not — which, in a depot thread with a customer in it, is a real cost. The
 * finger slides left past a threshold and the note is destroyed rather than
 * uploaded, and it is destroyed on the *release*, so the gesture stays
 * reversible right up until the finger lifts.
 *
 * The waveform is genuine: `VoiceRecorder.amplitude()` is the microphone's own
 * peak reading, sampled on the same tick that advances the timer. That matters
 * more here than it looks — it is the only confirmation the user gets that the
 * microphone is actually picking them up, which is otherwise something they
 * find out after sending.
 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.VoiceButton(
    onRecorded: (java.io.File, Long) -> Unit,
    recording: Boolean,
    onRecordingChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val feedback = LocalFeedback.current
    val recorder = remember { VoiceRecorder(context) }
    var elapsed by remember { mutableStateOf(0L) }
    var armedToCancel by remember { mutableStateOf(false) }

    // A ring buffer of recent amplitudes. Fixed length so the waveform scrolls
    // rather than compressing, and a plain list rather than state-per-bar so one
    // sample is one recomposition of one row, not of forty bars.
    val levels = remember { mutableStateListOf<Float>() }

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
        if (!recording) {
            levels.clear()
            return@LaunchedEffect
        }
        while (recording) {
            elapsed = recorder.elapsedMs()
            levels += recorder.amplitude()
            while (levels.size > WAVE_BARS) levels.removeAt(0)
            kotlinx.coroutines.delay(WAVE_TICK_MS)
        }
    }

    if (recording) {
        RecordingStrip(
            elapsed = elapsed,
            levels = levels,
            armedToCancel = armedToCancel,
            // No end padding: the Row already spaces its children, and adding
            // more here put a wider gap before the mic while recording than the
            // text pill has when not, so the button visibly shifted on press.
            modifier = Modifier.weight(1f),
        )
    }

    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            // Solid, like the send button it replaces. Red once the slide has
            // passed the threshold, so releasing-to-destroy is never a surprise.
            .background(
                when {
                    armedToCancel -> scheme.error
                    recording -> Semantic.critical
                    else -> scheme.primary
                },
            )
            // One gesture loop, not a tap detector plus a drag detector.
            //
            // Two `pointerInput` modifiers on the same element both receive the
            // stream and race over consumption: `detectTapGestures` ends its
            // gesture on the up event while `detectHorizontalDragGestures` waits
            // out touch slop first, so on a slow slide the release could be
            // processed before the travel that was supposed to arm the cancel —
            // which sends the note the user was in the middle of destroying.
            // That is the one failure this whole control exists to prevent, so
            // press and travel are read from the same pointer here.
            .pointerInput(hasMic) {
                val cancelPx = CANCEL_SLIDE_DP.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)

                    if (!hasMic) {
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        return@awaitEachGesture
                    }
                    if (!recorder.start()) return@awaitEachGesture

                    onRecordingChange(true)
                    armedToCancel = false
                    feedback.recordStart()

                    var armed = false
                    // Tracks the pointer until it lifts or the system takes the
                    // gesture away, which is what makes this hold-to-record
                    // rather than tap-to-toggle.
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) break

                        val travelled = change.position.x - down.position.x
                        val nowArmed = travelled < -cancelPx
                        if (nowArmed != armed) {
                            armed = nowArmed
                            armedToCancel = nowArmed
                            if (nowArmed) feedback.cancelArmed()
                        }
                        // Consumed so an ancestor cannot steal the gesture and
                        // strand a live MediaRecorder with nothing listening for
                        // the release.
                        change.consume()
                    }

                    onRecordingChange(false)
                    if (armed) {
                        recorder.cancel()
                        feedback.recordCancel()
                    } else {
                        val note = recorder.stop()
                        if (note != null) {
                            feedback.messageSent()
                            onRecorded(note.file, note.durationMs)
                        } else {
                            // Under the recorder's floor: a mis-tap, and saying
                            // so is better than silence, which reads as a
                            // message that vanished.
                            feedback.recordCancel()
                        }
                    }
                    armedToCancel = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (armedToCancel) Icons.Rounded.Delete else Icons.Rounded.Mic,
            contentDescription = if (armedToCancel) {
                "Release to discard this recording"
            } else {
                "Hold to record a voice note, slide left to cancel"
            },
            tint = Color.White,
            modifier = Modifier.size(21.dp),
        )
    }
}

/**
 * What replaces the composer pill while the microphone is live.
 *
 * The timer, the live waveform and the cancel instruction, in the space the text
 * field was using. Taking the field over rather than floating above it is what
 * makes the recording state unmistakable — there is nothing else to look at, and
 * no way to think a recording is not running.
 */
@Composable
private fun RecordingStrip(
    elapsed: Long,
    levels: List<Float>,
    armedToCancel: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = ChatTokens.field,
        shape = Radii.xl,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (armedToCancel) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Semantic.critical),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                formatDuration(elapsed),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(10.dp))
            LiveWaveform(
                levels = levels,
                tint = if (armedToCancel) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.weight(1f).height(22.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (armedToCancel) "Release to cancel" else "‹ Slide to cancel",
                style = MaterialTheme.typography.labelSmall,
                color = if (armedToCancel) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
            )
        }
    }
}

/**
 * The microphone's actual level, drawn as it arrives.
 *
 * Newest sample on the right, older ones scrolling left — the direction reading
 * runs in, so the bar under the user's eye is the sound they are making right
 * now. A floor of a couple of pixels on every bar keeps silence looking like a
 * flat line rather than a gap in the component.
 */
@Composable
private fun LiveWaveform(levels: List<Float>, tint: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        if (levels.isEmpty()) return@Canvas
        val barW = 2.5.dp.toPx()
        val gap = 2.dp.toPx()
        val slots = ((size.width + gap) / (barW + gap)).toInt().coerceAtLeast(1)
        val shown = levels.takeLast(slots)
        // Right-aligned, so a run that has not filled the width yet grows from
        // the right instead of sitting oddly at the left.
        val startX = size.width - shown.size * (barW + gap) + gap
        shown.forEachIndexed { i, level ->
            val h = (size.height * level).coerceAtLeast(2.dp.toPx())
            drawRoundRect(
                color = tint,
                topLeft = androidx.compose.ui.geometry.Offset(
                    x = startX + i * (barW + gap),
                    y = (size.height - h) / 2f,
                ),
                size = androidx.compose.ui.geometry.Size(barW, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2f),
            )
        }
    }
}

/** The `@` dropdown. Sits directly on the composer, as it does everywhere else. */
@Composable
private fun MentionPicker(candidates: List<ChatUserDto>, onPick: (ChatUserDto) -> Unit) {
    Surface(
        color = ChatTokens.field,
        shape = Radii.sheet,
        shadowElevation = Elevation.e3,
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
