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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.naarni.service.core.feedback.LocalFeedback
import com.naarni.service.data.chat.ChatMessageEntity
import com.naarni.service.ui.components.EmptyState
import com.naarni.service.ui.theme.BrandGradient
import kotlinx.coroutines.launch

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

    var viewing by remember { mutableStateOf<ChatMessageEntity?>(null) }
    var opening by remember { mutableStateOf<String?>(null) }
    var openError by remember { mutableStateOf<String?>(null) }

    viewing?.let { shot ->
        MediaViewer(
            model = shot.localPath ?: shot.fileUrl?.let { absoluteUrl(it) },
            title = if (vm.isMine(shot)) "You" else shot.authorName,
            subtitle = "${dayLabel(shot.createdAt)} · ${clockTime(shot.createdAt)}",
            caption = shot.body,
            onClose = { viewing = null },
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
        Row(
            Modifier
                .fillMaxWidth()
                .background(Brush.horizontalGradient(BrandGradient))
                .statusBarsPadding()
                .padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { feedback.tap(); onBack() }
                    .padding(12.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    "Shared in this conversation",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                room?.title?.let { title ->
                    Text(
                        title,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        ScrollableTabRow(
            selectedTabIndex = tab.ordinal,
            edgePadding = 8.dp,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Gallery.Tab.entries.forEach { entry ->
                val count = buckets[entry].orEmpty().size
                Tab(
                    selected = tab == entry,
                    onClick = { feedback.tap(); tab = entry },
                    text = {
                        Text(
                            if (count > 0) "${entry.label}  $count" else entry.label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (tab == entry) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                )
            }
        }

        val rowsForTab = buckets[tab].orEmpty()
        if (rowsForTab.isEmpty()) {
            EmptyState(
                icon = when (tab) {
                    Gallery.Tab.MEDIA -> Icons.Default.PhotoLibrary
                    Gallery.Tab.DOCUMENTS -> Icons.Default.Description
                    Gallery.Tab.VOICE -> Icons.Default.Mic
                    Gallery.Tab.LINKS -> Icons.Default.Link
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

        when (tab) {
            Gallery.Tab.MEDIA -> MediaGrid(rowsForTab) { viewing = it }
            Gallery.Tab.DOCUMENTS -> LazyColumn(Modifier.fillMaxSize()) {
                items(rowsForTab, key = { it.clientId }) { row ->
                    DocumentRow(
                        message = row,
                        busy = opening == row.clientId,
                        onClick = { feedback.tap(); openDocument(row) },
                    )
                }
            }

            Gallery.Tab.VOICE -> LazyColumn(Modifier.fillMaxSize()) {
                items(rowsForTab, key = { it.clientId }) { row ->
                    DocumentRow(
                        message = row,
                        busy = opening == row.clientId,
                        onClick = { feedback.tap(); openDocument(row) },
                    )
                }
            }

            Gallery.Tab.LINKS -> LazyColumn(Modifier.fillMaxSize()) {
                items(rowsForTab, key = { it.clientId }) { row ->
                    LinkRow(row) { url ->
                        feedback.tap()
                        runCatching { uriHandler.openUri(url) }
                            .onFailure { openError = "No app on this phone can open that link." }
                    }
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
 * Square thumbnails, three across.
 *
 * A fixed count rather than adaptive sizing: three is what makes a face
 * recognisable on a 5" screen held at arm's length in daylight, which is the
 * condition this is actually used in.
 */
@Composable
private fun MediaGrid(rows: List<ChatMessageEntity>, onOpen: (ChatMessageEntity) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(rows, key = { it.clientId }) { row ->
            Box(
                Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(3.dp))
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
                // without this — the play badge is the only thing telling you
                // whether a tap opens a still or starts playback.
                if (row.kind == "video") {
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Video",
                            tint = Color.White,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
            }
        }
    }
}

/** One document or voice note: type badge, name, who sent it and when. */
@Composable
private fun DocumentRow(message: ChatMessageEntity, busy: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            shape = RoundedCornerShape(10.dp),
        ) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                if (busy) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                    )
                } else if (message.kind == "audio") {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(21.dp),
                    )
                } else {
                    Text(
                        Gallery.docKindOf(message.fileName),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                message.fileName ?: if (message.kind == "audio") "Voice note" else "Attachment",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                listOfNotNull(
                    humanSize(message.fileSize).ifBlank { null },
                    message.authorName.takeIf { it.isNotBlank() },
                    dayLabel(message.createdAt),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A posted link, with the message it arrived in as context. */
@Composable
private fun LinkRow(message: ChatMessageEntity, onOpen: (String) -> Unit) {
    val links = remember(message.clientId, message.body) { Gallery.linksIn(message.body) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        links.forEach { url ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(url) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    url,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            "${message.authorName} · ${dayLabel(message.createdAt)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
