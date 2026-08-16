package com.naarni.service.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.naarni.service.core.audio.VoicePlayer
import com.naarni.service.data.chat.ChatMessageEntity
import com.naarni.service.data.chat.SendStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

/**
 * How far every *other* member of the room has got through my messages.
 *
 * Room-level rather than per-message because the server keeps one cursor per
 * member: a message is delivered when everyone's delivery cursor has passed its
 * seq, and read when everyone's read cursor has. Carrying it per row would mean
 * rewriting the whole thread every time somebody opened it.
 */
data class Receipts(val deliveredUpto: Long = 0, val readUpto: Long = 0)

/**
 * One message row.
 *
 * The grammar is the one everybody already knows from WhatsApp — side-anchored
 * bubbles, a tail corner on the outer edge, time and delivery state tucked into
 * the bubble's bottom-right, author name only on the first message of a run.
 * The palette is Naarni's: indigo where WhatsApp is green, on a tinted canvas
 * chosen so that a white incoming bubble actually reads (see [ChatTokens]).
 *
 * A media message with no caption gets no bubble chrome at all — the photo *is*
 * the bubble, with the timestamp floated over a gradient in its corner. Wrapping
 * a photo in a coloured box the same width is the thing that made this screen
 * look like a form rather than a conversation.
 */
@Composable
fun MessageBubble(
    message: ChatMessageEntity,
    isMine: Boolean,
    showAuthor: Boolean,
    isSelected: Boolean,
    replyPreview: ChatMessageEntity?,
    onRetry: () -> Unit,
    onOpenMedia: () -> Unit,
    modifier: Modifier = Modifier,
    mentionLabels: List<String> = emptyList(),
    onAssignTicket: (String) -> Unit = {},
    isOpening: Boolean = false,
    receipts: Receipts = Receipts(),
    /** Jump to the quoted message. */
    onOpenQuote: (ChatMessageEntity) -> Unit = {},
) {
    // A server-authored notice is about the conversation, not part of it, so it
    // gets no bubble and no side — the same treatment WhatsApp gives "you were
    // added to this group".
    if (message.kind == "system") {
        SystemNotice(message.body)
        return
    }

    val bubbleColor = if (isMine) ChatTokens.outgoing else ChatTokens.incoming
    val textColor = if (isMine) ChatTokens.onOutgoing else ChatTokens.onIncoming

    val isMedia = message.kind == "image" || message.kind == "video"
    // No caption → the meta line floats over the photo instead of below it.
    val bare = isMedia && message.body.isBlank()
    // Plain text can carry the stamp on its last line. A card or an attachment
    // cannot: the meta would end up beside the card instead of under it.
    val inlineMeta = message.kind == "text" && message.body.isNotBlank()

    // A tail on the outer corner only: cheaper and steadier than drawing a real
    // tail path, and it reads the same at a glance.
    val shape = RoundedCornerShape(
        topStart = ChatTokens.bubbleRadius,
        topEnd = ChatTokens.bubbleRadius,
        bottomEnd = if (isMine) ChatTokens.tailRadius else ChatTokens.bubbleRadius,
        bottomStart = if (isMine) ChatTokens.bubbleRadius else ChatTokens.tailRadius,
    )

    // No animateFloatAsState here on purpose: it allocates an animation object
    // per row, and across a few hundred messages that is a measurable slice of
    // the frame budget for an effect nobody notices.
    val selectionBg = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    } else {
        Color.Transparent
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(selectionBg)
            .padding(horizontal = ChatTokens.threadGutter, vertical = 1.dp),
        contentAlignment = if (isMine) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Surface(
            color = bubbleColor,
            shape = shape,
            // A message that names you is the one you must not scroll past, so
            // it carries a border rather than only a tinted word inside it —
            // visible while the thread is moving, not only once it has stopped.
            border = if (message.mentionsMe) {
                androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
            } else {
                null
            },
            // Both elevations zero deliberately: a shadow is a separate render
            // pass per row, and flat bubbles read fine against a tinted canvas.
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            modifier = Modifier.widthIn(max = bubbleMaxWidth),
        ) {
            Column(
                Modifier.padding(
                    horizontal = if (bare) 3.dp else ChatTokens.bubblePadH,
                    vertical = if (bare) 3.dp else ChatTokens.bubblePadV,
                ),
            ) {
                if (showAuthor && !isMine) {
                    Text(
                        message.authorName,
                        style = MaterialTheme.typography.labelLarge,
                        color = authorColor(message.author),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 2.dp, start = if (bare) 5.dp else 0.dp),
                    )
                }

                replyPreview?.let { ReplyQuote(it, textColor) { onOpenQuote(it) } }

                when (message.kind) {
                    "image" -> MediaContent(message, isVideo = false, overlayMeta = bare, onOpen = onOpenMedia) {
                        MetaRow(message, isMine, Color.White, onRetry, receipts = receipts)
                    }

                    "video" -> MediaContent(message, isVideo = true, overlayMeta = bare, onOpen = onOpenMedia) {
                        MetaRow(message, isMine, Color.White, onRetry, receipts = receipts)
                    }

                    "audio" -> AudioContent(message, textColor)
                    "file" -> FileCard(message, textColor, isOpening, onOpenMedia)
                    "ticket" -> TicketCard(message, textColor, onAssignTicket)
                    "alert" -> AlertCard(message)
                }

                // A ticket or alert card already renders its own body.
                if (message.body.isNotBlank() && message.kind != "ticket" && message.kind != "alert") {
                    if (message.kind != "text") Spacer(Modifier.height(5.dp))
                    val rendered =
                        // Only pay for the scan when the message actually names
                        // somebody; the overwhelming majority do not.
                        if (mentionLabels.isEmpty()) {
                            androidx.compose.ui.text.AnnotatedString(message.body)
                        } else {
                            Mentions.annotate(message.body, mentionLabels, mentionTint(isMine))
                        }

                    if (inlineMeta) {
                        // Time and ticks tuck in beside the last line rather than
                        // claiming a line of their own. Weighting the text without
                        // filling reserves just enough room for the stamp, so it
                        // lands to the right of the final line whether the message
                        // is two words or two paragraphs — and a two-word message
                        // stops being two storeys tall.
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                rendered,
                                style = MaterialTheme.typography.bodyLarge,
                                color = textColor,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.width(7.dp))
                            MetaRow(message, isMine, textColor, onRetry, receipts = receipts)
                        }
                    } else {
                        Text(rendered, style = MaterialTheme.typography.bodyLarge, color = textColor)
                    }
                }

                // Tags a technician attached via long-press.
                message.vehicle?.let {
                    Spacer(Modifier.height(5.dp))
                    TagChip(Icons.Rounded.DirectionsBus, it)
                }

                if (!bare && !inlineMeta) {
                    // align(End) rather than fillMaxWidth: filling stretches the
                    // bubble to its full width even for a two-letter message,
                    // which is the least conversational thing a bubble can do.
                    MetaRow(message, isMine, textColor, onRetry, Modifier.align(Alignment.End), receipts)
                }
            }
        }
    }
}

/** Time, geotag marker and delivery state — the bubble's footer. */
@Composable
private fun MetaRow(
    message: ChatMessageEntity,
    isMine: Boolean,
    textColor: Color,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    receipts: Receipts = Receipts(),
) {
    val muted = mutedOn(textColor)
    // Formatting is not free, and without this it re-runs for every visible row
    // on every recomposition of the thread.
    val stamp = remember(message.createdAt) { timeFmt.format(Date(message.createdAt)) }

    Row(
        modifier,
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (message.geotagged) {
            Icon(
                Icons.Rounded.LocationOn,
                contentDescription = "Location attached",
                tint = muted,
                modifier = Modifier.size(11.dp),
            )
            Spacer(Modifier.width(3.dp))
        }
        Text(stamp, style = MaterialTheme.typography.labelSmall, color = muted)
        if (isMine) {
            Spacer(Modifier.width(4.dp))
            DeliveryTick(message, muted, onRetry, receipts)
        }
    }
}

/**
 * Delivery state, in the tick grammar everyone already reads.
 *
 * One tick: the server allocated a seq. Two grey: every other member's device
 * has actually pulled it. Two blue: every one of them has opened the thread
 * past it. In a group that means the *slowest* member, not the fastest —
 * otherwise "read" would mean "somebody read it", which is not what a
 * dispatcher chasing an unanswered instruction needs it to mean.
 */
@Composable
private fun DeliveryTick(
    message: ChatMessageEntity,
    muted: Color,
    onRetry: () -> Unit,
    receipts: Receipts,
) {
    when (message.status) {
        SendStatus.PENDING -> Icon(
            Icons.Rounded.Schedule,
            contentDescription = "Waiting to send",
            tint = muted,
            modifier = Modifier.size(12.dp),
        )

        SendStatus.UPLOADING -> CircularProgressIndicator(
            progress = { message.uploadPct / 100f },
            strokeWidth = 1.5.dp,
            color = muted,
            modifier = Modifier.size(12.dp),
        )

        SendStatus.SENT -> {
            val seq = message.seq ?: 0L
            when {
                seq > 0 && seq <= receipts.readUpto -> Icon(
                    Icons.Rounded.DoneAll,
                    contentDescription = "Read",
                    tint = ChatTokens.readTick,
                    modifier = Modifier.size(14.dp),
                )

                seq > 0 && seq <= receipts.deliveredUpto -> Icon(
                    Icons.Rounded.DoneAll,
                    contentDescription = "Delivered",
                    tint = muted,
                    modifier = Modifier.size(14.dp),
                )

                else -> Icon(
                    Icons.Rounded.Check,
                    contentDescription = "Sent",
                    tint = muted,
                    modifier = Modifier.size(13.dp),
                )
            }
        }

        SendStatus.FAILED -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickableNoRipple(onRetry),
        ) {
            Icon(
                Icons.Rounded.ErrorOutline,
                contentDescription = "Failed to send",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(3.dp))
            Text(
                "Tap to retry",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold,
            )
        }

        else -> Icon(
            Icons.Rounded.DoneAll,
            contentDescription = "Read",
            tint = ChatTokens.readTick,
            modifier = Modifier.size(13.dp),
        )
    }
}

/**
 * The quoted message above a reply.
 *
 * Tapping it jumps to the original, which is the behaviour that makes replies
 * worth having at all: without it a quote is a screenshot of context, and the
 * reader still has to scroll and hunt for what was actually being answered.
 *
 * An attachment gets an icon and a word rather than its raw `kind`, because
 * "Image" is a database value and "Photo" is what the thing is.
 */
@Composable
private fun ReplyQuote(source: ChatMessageEntity, onBubble: Color, onOpen: () -> Unit) {
    val accent = authorColor(source.author)
    val (icon, fallback) = when (source.kind) {
        "image" -> Icons.Rounded.Image to "Photo"
        "video" -> Icons.Rounded.Videocam to "Video"
        "audio" -> Icons.Rounded.Mic to "Voice note"
        "file" -> Icons.Rounded.InsertDriveFile to (source.fileName ?: "Document")
        "ticket" -> Icons.Rounded.ConfirmationNumber to (source.ticket ?: "Service ticket")
        else -> null to ""
    }
    val summary = source.body.ifBlank { fallback }

    Row(
        Modifier
            .padding(bottom = 4.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(onBubble.copy(alpha = 0.07f))
            .clickable(onClick = onOpen)
            .heightIn(min = 34.dp),
    ) {
        Box(Modifier.width(3.dp).heightIn(min = 34.dp).background(accent))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                source.authorName,
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                fontWeight = FontWeight.SemiBold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon?.let {
                    Icon(
                        it,
                        contentDescription = null,
                        tint = mutedOn(onBubble),
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedOn(onBubble),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * A photo or a video still.
 *
 * When there is no caption the timestamp is floated over the bottom of the
 * image on a short gradient — a solid chip would sit as a hard rectangle on the
 * photo, and plain white text is unreadable over a bright one.
 */
@Composable
private fun MediaContent(
    message: ChatMessageEntity,
    isVideo: Boolean,
    overlayMeta: Boolean,
    onOpen: () -> Unit,
    meta: @Composable () -> Unit,
) {
    Box(
        Modifier
            .width(ChatTokens.mediaWidth)
            .clip(RoundedCornerShape(13.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onOpen),
    ) {
        AsyncImage(
            // The local file while queued, so the photo is visible the instant
            // it is picked, and the server URL once committed.
            model = message.localPath ?: message.fileUrl?.let { absoluteUrl(it) },
            contentDescription = message.body.ifBlank { if (isVideo) "Video" else "Photo" },
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(ChatTokens.mediaWidth)
                .heightIn(min = 140.dp, max = 300.dp),
        )

        if (isVideo) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(44.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = "Play",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
        }

        if (overlayMeta) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)),
                        ),
                    )
                    .padding(start = 24.dp, end = 8.dp, top = 18.dp, bottom = 5.dp),
            ) {
                meta()
            }
        }

        if (message.status == SendStatus.UPLOADING) UploadScrim(message.uploadPct)
    }
}

/**
 * A document attachment — anything that is not a photo, video or voice note.
 *
 * Name and size only: there is no thumbnail to show and no honest way to
 * preview a spreadsheet in a bubble, so the card's job is to be unambiguous
 * about what it is and obviously tappable.
 */
@Composable
private fun FileCard(
    message: ChatMessageEntity,
    textColor: Color,
    isOpening: Boolean,
    onOpen: () -> Unit,
) {
    val meta = listOfNotNull(
        humanSize(message.fileSize).ifBlank { null },
        message.fileName?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() }?.uppercase(),
    ).joinToString(" · ")

    Row(
        Modifier
            .widthIn(max = bubbleMaxWidth)
            .clip(RoundedCornerShape(11.dp))
            .background(textColor.copy(alpha = 0.06f))
            .clickable(onClick = onOpen)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            // A depot's link can take a while to pull a 40 MB export; without
            // this the card looks inert and gets tapped again.
            if (isOpening) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Icon(
                    Icons.Rounded.InsertDriveFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                message.fileName ?: "Attachment",
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotBlank()) {
                Spacer(Modifier.height(1.dp))
                Text(meta, style = MaterialTheme.typography.labelSmall, color = mutedOn(textColor))
            }
        }
    }
}

/**
 * A shared Service Ticket.
 *
 * The point of sharing one into a thread is that the next action happens here
 * rather than in another app, so Assign is on the card itself. The body is the
 * server's own one-line rendering, which is also what a push notification and
 * the Desk timeline show — one description of the ticket, not three that drift.
 */
@Composable
private fun TicketCard(message: ChatMessageEntity, textColor: Color, onAssign: (String) -> Unit) {
    val ticket = message.ticket ?: return
    val lines = message.body.split("\n")

    Column(
        Modifier
            .widthIn(max = bubbleMaxWidth)
            .clip(RoundedCornerShape(11.dp))
            .background(textColor.copy(alpha = 0.06f))
            .padding(9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.ConfirmationNumber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                ticket,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            lines.first(),
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (lines.size > 1) {
            Spacer(Modifier.height(3.dp))
            Text(
                lines.drop(1).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = mutedOn(textColor),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(7.dp))
        Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            shape = RoundedCornerShape(7.dp),
            modifier = Modifier.clickable { onAssign(ticket) },
        ) {
            Text(
                "Assign to someone",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

/**
 * An alert posted automatically into a channel.
 *
 * Severity leads, because in a channel that receives a hundred of these a day
 * the only question being asked while scrolling is "does this one need me now".
 */
@Composable
private fun AlertCard(message: ChatMessageEntity) {
    val lines = message.body.split("\n").filter { it.isNotBlank() }
    val head = lines.firstOrNull().orEmpty()
    val critical = head.startsWith("CRITICAL")
    val accent = if (critical) MaterialTheme.colorScheme.error else Color(0xFFF59E0B)

    Row(Modifier.widthIn(max = bubbleMaxWidth).clip(RoundedCornerShape(11.dp))) {
        Box(Modifier.width(4.dp).heightIn(min = 48.dp).background(accent))
        Column(
            Modifier
                .background(accent.copy(alpha = 0.09f))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    head,
                    style = MaterialTheme.typography.labelLarge,
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            lines.drop(1).take(4).forEach { line ->
                Spacer(Modifier.height(2.dp))
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = ChatTokens.onIncoming.copy(alpha = 0.85f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A centred chip for things the server said about the conversation. */
@Composable
private fun SystemNotice(body: String) {
    if (body.isBlank()) return
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(color = ChatTokens.chip, shape = RoundedCornerShape(9.dp)) {
            Text(
                body,
                style = MaterialTheme.typography.labelMedium,
                color = ChatTokens.onChip,
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
            )
        }
    }
}

/** Mentions have to read on both bubble grounds, so the tint differs by side. */
@Composable
private fun mentionTint(isMine: Boolean): Color =
    if (isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary

@Composable
private fun AudioContent(message: ChatMessageEntity, textColor: Color) {
    val context = LocalContext.current
    val source = message.localPath ?: message.fileUrl?.let { absoluteUrl(it) }
    val isPlaying = VoicePlayer.playingId == message.clientId

    // Only ticks while this note is the one playing, so an idle thread of forty
    // voice notes is not running forty timers.
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            VoicePlayer.tick()
            kotlinx.coroutines.delay(120)
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(textColor.copy(alpha = 0.14f))
                .clickable(enabled = source != null) {
                    source?.let { VoicePlayer.toggle(context, message.clientId, it) }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (VoicePlayer.loadingId == message.clientId) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = textColor,
                    modifier = Modifier.size(17.dp),
                )
            } else {
                Icon(
                    if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play voice note",
                    tint = textColor,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))

        Column {
            VoiceMeter(
                progress = if (isPlaying) VoicePlayer.progress else 0f,
                tint = textColor,
                modifier = Modifier.width(128.dp).height(20.dp),
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatDuration(message.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = mutedOn(textColor),
                )
                // Only on the note that is playing. A speed chip on all forty
                // voice notes in a breakdown thread is forty controls for a
                // setting that is global anyway.
                if (isPlaying) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        color = textColor.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(7.dp),
                        modifier = Modifier.clickable { VoicePlayer.cycleSpeed() },
                    ) {
                        Text(
                            speedLabel(VoicePlayer.speed),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = textColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
        // Transcript arrives later from speech-to-text; shown inline when present.
        message.transcript?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.width(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = mutedOn(textColor),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** "1×", "1.5×", "2×" — no trailing zero on the whole numbers. */
private fun speedLabel(speed: Float): String =
    if (speed % 1f == 0f) "${speed.toInt()}×" else "${speed}×"

/**
 * The bar meter behind a voice note.
 *
 * Deliberately **not** a waveform of the audio. Drawing a real one means
 * decoding the note before it can be displayed — per bubble, for every note in
 * the thread, most of which will never be played. The alternative some apps take
 * is to generate bar heights from a hash of the message id, which looks like a
 * waveform and is not one: it shows the reader a picture of the audio that has
 * no relationship to the audio. That is a small lie told very often, so this
 * does neither.
 *
 * What it is instead is an honest position indicator with the *shape* of a
 * waveform — uniform bars that fill as the note plays. It reads at a glance, it
 * costs one `Canvas` draw, and it never claims to know something it does not.
 * The live waveform during recording is a different matter: there the amplitude
 * is real, measured from the microphone, and is drawn as such.
 */
@Composable
private fun VoiceMeter(progress: Float, tint: Color, modifier: Modifier = Modifier) {
    val played = tint
    val pending = tint.copy(alpha = 0.24f)
    Canvas(modifier) {
        val barW = 2.5.dp.toPx()
        val gap = 2.5.dp.toPx()
        val count = ((size.width + gap) / (barW + gap)).toInt().coerceAtLeast(1)
        val filled = (count * progress).toInt()
        for (i in 0 until count) {
            // A gentle rise and fall across the run rather than a flat block, so
            // it reads as a sound rather than as a loading bar. Fixed by index,
            // so it does not shimmer as playback advances.
            val curve = 0.42f + 0.58f * kotlin.math.sin(Math.PI * (i + 0.5) / count).toFloat()
            val h = size.height * curve
            drawRoundRect(
                color = if (i <= filled) played else pending,
                topLeft = androidx.compose.ui.geometry.Offset(
                    x = i * (barW + gap),
                    y = (size.height - h) / 2f,
                ),
                size = androidx.compose.ui.geometry.Size(barW, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2f),
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.UploadScrim(pct: Int) {
    Box(
        Modifier
            .matchParentSize()
            .background(Color.Black.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$pct%", color = Color.White, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { pct / 100f },
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.3f),
                modifier = Modifier.width(120.dp),
            )
        }
    }
}

@Composable
private fun TagChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(7.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/** Floating day divider over the canvas. */
@Composable
fun DayDivider(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
        Surface(color = ChatTokens.chip, shape = RoundedCornerShape(10.dp)) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = ChatTokens.onChip,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 4.dp),
            )
        }
    }
}

/** "Unread messages" separator. */
@Composable
fun UnreadDivider(count: Int) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count == 1) "1 unread message" else "$count unread messages",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

// A stable per-author colour so a busy depot thread stays scannable. Drawn from
// the brand family rather than random hues, so it still looks like Naarni.
private val authorPalette = listOf(
    Color(0xFF6D5AE6), Color(0xFF0EA5E9), Color(0xFF10B981),
    Color(0xFFF59E0B), Color(0xFFEC4899), Color(0xFF8B5CF6),
)

fun authorColor(user: String): Color =
    authorPalette[(user.hashCode().and(Int.MAX_VALUE)) % authorPalette.size]

fun formatDuration(ms: Long?): String {
    val total = (ms ?: 0) / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

/** Resolve a Frappe `/private/files/...` path against the configured backend. */
fun absoluteUrl(path: String): String =
    if (path.startsWith("http")) path
    else com.naarni.service.BuildConfig.BASE_URL.trimEnd('/') + path

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier {
    // Unremembered, this allocated a fresh interaction source on every
    // recomposition of every failed row.
    val source = remember { MutableInteractionSource() }
    return this.clickable(interactionSource = source, indication = null, onClick = onClick)
}
