package com.naarni.service.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.naarni.service.core.audio.VoicePlayer
import com.naarni.service.core.feedback.LocalFeedback
import com.naarni.service.data.chat.ChatMessageEntity
import com.naarni.service.ui.components.AppBar
import com.naarni.service.ui.components.EmptyState
import com.naarni.service.ui.components.HairlineDivider
import com.naarni.service.ui.theme.AppSurface
import kotlinx.coroutines.launch
import com.naarni.service.ui.theme.Radii

/**
 * Everything that has ever been shared in one conversation, by category.
 *
 * Reads the same Room rows the thread does, so it opens instantly and works
 * with no signal — the alternative, a dedicated endpoint, would have made the
 * one screen people reach for while standing at a vehicle the one screen that
 * needs a connection.
 *
 * The tabs mirror what Telegram and WhatsApp settled on, because a technician
 * who already knows where photos live in one app should not have to learn a
 * second arrangement here.
 */
@Composable
fun ChatGalleryScreen(
    vm: ChatViewModel,
    roomName: String,
    onBack: () -> Unit,
) {
    val roomFlow = remember(roomName) { vm.observeRoom(roomName) }
    val galleryFlow = remember(roomName) { vm.gallery(roomName) }
    val room by roomFlow.collectAsStateWithLifecycle(null)
    val rows by galleryFlow.collectAsStateWithLifecycle(emptyList())

    val buckets = remember(rows) { Gallery.bucket(rows) }
    var tab by remember { mutableStateOf(Gallery.Tab.MEDIA) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val feedback = LocalFeedback.current
    val uriHandler = LocalUriHandler.current

    var viewingIndex by remember { mutableStateOf<Int?>(null) }
    var opening by remember { mutableStateOf<String?>(null) }
    var openError by remember { mutableStateOf<String?>(null) }

    val mediaRows = buckets[Gallery.Tab.MEDIA].orEmpty()

    // The whole media run is handed to the viewer, not just the tapped photo, so
    // it can be flicked through. Rebuilt only when the rows change.
    val mediaPages = remember(mediaRows) {
        mediaRows.map { shot ->
            MediaPage(
                key = shot.clientId,
                model = shot.localPath ?: shot.fileUrl?.let { absoluteUrl(it) },
                title = if (shot.author == vm.me) "You" else shot.authorName,
                subtitle = "${dayLabel(shot.createdAt)} · ${clockTime(shot.createdAt)}",
                caption = shot.body,
                isVideo = shot.kind == "video",
            )
        }
    }

    viewingIndex?.let { index ->
        MediaPagerViewer(
            pages = mediaPages,
            startIndex = index,
            onClose = { viewingIndex = null },
        )
        return
    }

    fun openDocument(message: ChatMessageEntity) {
        if (opening != null) return
        opening = message.clientId
        scope.launch {
            when (val result = FileOpener.open(context, message)) {
                is FileOpener.Result.Failed -> openError = result.reason
                FileOpener.Result.Opened -> Unit
            }
            opening = null
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val rowsForTab = buckets[tab].orEmpty()

        AppBar(
            title = room?.title ?: "Shared files",
            // The count of what you are actually looking at, not a restatement
            // of the screen's own name. "Shared in this conversation" above the
            // conversation's name told the reader nothing twice.
            subtitle = Gallery.countLabel(tab, rowsForTab.size),
            onBack = { feedback.tap(); onBack() },
        )

        GalleryTabs(
            selected = tab,
            countOf = { buckets[it].orEmpty().size },
            onSelect = { feedback.tap(); tab = it },
        )

        if (rowsForTab.isEmpty()) {
            EmptyState(
                icon = when (tab) {
                    Gallery.Tab.MEDIA -> Icons.Rounded.PhotoLibrary
                    Gallery.Tab.DOCUMENTS -> Icons.Rounded.Description
                    Gallery.Tab.VOICE -> Icons.Rounded.Mic
                    Gallery.Tab.LINKS -> Icons.Rounded.Link
                },
                title = Gallery.countLabel(tab, 0),
                body = when (tab) {
                    Gallery.Tab.MEDIA -> "Photos and videos sent here will collect in this tab."
                    Gallery.Tab.DOCUMENTS -> "Files sent here will collect in this tab."
                    Gallery.Tab.VOICE -> "Voice notes sent here will collect in this tab."
                    Gallery.Tab.LINKS -> "Links anyone posts here will collect in this tab."
                },
                modifier = Modifier.fillMaxSize(),
            )
            return@Column
        }

        // Every tab is grouped by day. Recomputed only when the rows change, not
        // per scroll frame.
        val sections = remember(rowsForTab) {
            Gallery.groupByDay(rowsForTab) { dayLabel(it.createdAt) }
        }

        when (tab) {
            Gallery.Tab.MEDIA -> MediaGrid(sections) { tapped ->
                viewingIndex = rowsForTab.indexOfFirst { it.clientId == tapped.clientId }
                    .takeIf { it >= 0 } ?: 0
            }

            Gallery.Tab.DOCUMENTS, Gallery.Tab.VOICE -> SectionedList(sections) { row ->
                DocumentRow(
                    message = row,
                    busy = opening == row.clientId,
                    onClick = {
                        feedback.tap()
                        // A voice note plays here rather than being handed to
                        // whatever media app the phone happens to have. Bouncing
                        // out of the app to hear two seconds of someone saying
                        // "pack 2 is at 61" — and then having to navigate back —
                        // is the whole reason people stop using a files tab.
                        if (row.kind == "audio") {
                            val source = row.localPath ?: row.fileUrl?.let(::absoluteUrl)
                            if (source != null) VoicePlayer.toggle(context, row.clientId, source)
                        } else {
                            openDocument(row)
                        }
                    },
                )
            }

            Gallery.Tab.LINKS -> SectionedList(sections) { row ->
                LinkRow(row) { url ->
                    feedback.tap()
                    runCatching { uriHandler.openUri(url) }
                        .onFailure { openError = "No app on this phone can open that link." }
                }
            }
        }
    }

    openError?.let { message ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { openError = null },
            title = { Text("Couldn't open that") },
            text = { Text(message) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { openError = null }) { Text("OK") }
            },
        )
    }
}

/**
 * The tab strip.
 *
 * A segmented control rather than Material's `ScrollableTabRow`. Four short
 * labels fit a handset without scrolling, so a scrollable strip only ever hid
 * the fact that Links existed; and the underline indicator is a thin accent line
 * that disappears entirely on a sunlit screen. A filled pill survives daylight
 * and states the count in the same breath.
 */
@Composable
private fun GalleryTabs(
    selected: Gallery.Tab,
    countOf: (Gallery.Tab) -> Int,
    onSelect: (Gallery.Tab) -> Unit,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .background(AppSurface.raised)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Gallery.Tab.entries.forEach { entry ->
                val isOn = entry == selected
                val count = countOf(entry)
                Surface(
                    color = if (isOn) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        AppSurface.sunken
                    },
                    shape = Radii.xl,
                    modifier = Modifier
                        .weight(1f)
                        .clip(Radii.xl)
                        .clickable { onSelect(entry) },
                ) {
                    Column(
                        Modifier.padding(vertical = 7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            entry.label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isOn) FontWeight.Bold else FontWeight.Medium,
                            color = if (isOn) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        // An empty tab shows a dash rather than a zero: "0" reads
                        // as a value that might change while you look at it,
                        // where "—" reads as nothing here.
                        Text(
                            if (count > 0) "$count" else "—",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isOn) {
                                Color.White.copy(alpha = 0.85f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            },
                        )
                    }
                }
            }
        }
        HairlineDivider()
    }
}

/** The day heading above each section, in every tab. */
@Composable
private fun DaySection(label: String) {
    Text(
        label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
    )
}

/**
 * A day-grouped list, with the heading pinned while its section is on screen.
 *
 * `stickyHeader` is what makes a long back-scroll answerable: without it, the
 * only way to know which day you have reached is to scroll back up to the last
 * heading you passed.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SectionedList(
    sections: List<Pair<String, List<ChatMessageEntity>>>,
    row: @Composable (ChatMessageEntity) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        sections.forEach { (label, rows) ->
            stickyHeader(key = "hdr-$label") { DaySection(label) }
            items(rows, key = { it.clientId }, contentType = { "row" }) { row(it) }
        }
    }
}

/**
 * Square thumbnails, three across, grouped by day.
 *
 * A fixed count rather than adaptive sizing: three is what makes a face
 * recognisable on a 5" screen held at arm's length in daylight, which is the
 * condition this is actually used in.
 *
 * The day headings span the full row via `maxLineSpan`, so a section break is a
 * real break rather than a caption sitting in the first cell.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MediaGrid(
    sections: List<Pair<String, List<ChatMessageEntity>>>,
    onOpen: (ChatMessageEntity) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 3.dp, end = 3.dp, bottom = 16.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        sections.forEach { (label, rows) ->
            item(key = "hdr-$label", span = { GridItemSpan(maxLineSpan) }) {
                DaySection(label)
            }
            items(
                rows,
                key = { it.clientId },
                contentType = { "tile" },
            ) { row ->
                Box(
                    Modifier
                        .aspectRatio(1f)
                        .clip(Radii.md)
                        .background(ChatTokens.chip)
                        .clickable { onOpen(row) },
                ) {
                    AsyncImage(
                        model = row.localPath ?: row.fileUrl?.let { absoluteUrl(it) },
                        contentDescription = row.body.ifBlank { "Photo" },
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Videos are indistinguishable from photos at thumbnail size
                    // without this — the badge is the only thing telling you
                    // whether a tap opens a still or starts playback. Bottom-left
                    // with the duration rather than a centred disc, so it covers
                    // the middle of the frame — usually the subject — with
                    // nothing.
                    if (row.kind == "video") {
                        Row(
                            Modifier
                                .align(Alignment.BottomStart)
                                .padding(5.dp)
                                .clip(Radii.sm)
                                .background(Color.Black.copy(alpha = 0.55f))
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.PlayArrow,
                                contentDescription = "Video",
                                tint = Color.White,
                                modifier = Modifier.size(12.dp),
                            )
                            row.durationMs?.takeIf { it > 0 }?.let {
                                Spacer(Modifier.width(2.dp))
                                Text(
                                    formatDuration(it),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One document or voice note: type badge, name, who sent it and how big.
 *
 * The day is deliberately *not* repeated in the meta line any more — the section
 * heading above the row already says it, and printing it again on every row was
 * the longest and least useful part of the line.
 */
@Composable
private fun DocumentRow(message: ChatMessageEntity, busy: Boolean, onClick: () -> Unit) {
    val isVoice = message.kind == "audio"
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Neutral, not brand. A list of twenty documents previously put twenty
        // indigo tiles on screen, which in a single-accent scheme reads as
        // twenty things asking to be pressed.
        val playing = isVoice && VoicePlayer.playingId == message.clientId
        val loading = isVoice && VoicePlayer.loadingId == message.clientId

        Surface(color = AppSurface.sunken, shape = Radii.md) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                when {
                    busy || loading -> CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )

                    // A voice note's tile *is* its transport control, so it shows
                    // the action rather than a microphone that looks pressable and
                    // is only decoration.
                    isVoice -> Icon(
                        if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play voice note",
                        tint = if (playing) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(23.dp),
                    )

                    else -> Text(
                        Gallery.docKindOf(message.fileName),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                // Never the stored file name for a voice note. That name is
                // `vn_1786614669889.m4a` — a device timestamp, which tells the
                // reader nothing and pushes the facts that matter (how long, who,
                // when) onto the second line where they compete with it.
                if (isVoice) "Voice note" else message.fileName ?: "Attachment",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (playing) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                listOfNotNull(
                    // A voice note's length is the useful number; a file's is
                    // its size. Showing bytes for a voice note tells nobody
                    // whether it is worth two minutes of listening.
                    if (isVoice) message.durationMs?.takeIf { it > 0 }?.let(::formatDuration)
                    else humanSize(message.fileSize).ifBlank { null },
                    message.authorName.takeIf { it.isNotBlank() },
                    clockTime(message.createdAt),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A posted link.
 *
 * The host leads and the full URL is the sub-line. A raw URL as the headline was
 * unreadable at a glance — every row started with the same "https://" and the
 * part that identifies the destination was buried mid-string, so the list could
 * only be read by reading it rather than scanned.
 */
@Composable
private fun LinkRow(message: ChatMessageEntity, onOpen: (String) -> Unit) {
    val links = remember(message.clientId, message.body) { Gallery.linksIn(message.body) }
    Column(Modifier.fillMaxWidth()) {
        links.forEach { url ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(url) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(color = AppSurface.sunken, shape = Radii.md) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        Text(
                            Gallery.linkInitial(url),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        Gallery.hostOf(url),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        "${message.authorName} · ${clockTime(message.createdAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Icon(
                    Icons.Rounded.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
