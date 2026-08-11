package com.naarni.service.ui.chat

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.naarni.service.data.chat.ChatMessageEntity
import com.naarni.service.data.chat.SendStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

/**
 * One message row.
 *
 * The visual language is deliberately Naarni's, not WhatsApp's: outgoing
 * messages use the indigo primary container rather than the familiar green, and
 * the corner radii come from the app's shape scale. What *is* borrowed from
 * WhatsApp is the grammar people already know — side-anchored bubbles, a tail
 * corner on the outer edge, time and delivery state tucked into the bubble's
 * bottom-right, and grouped runs from the same author.
 */
@Composable
fun MessageBubble(
    message: ChatMessageEntity,
    isMine: Boolean,
    showAuthor: Boolean,
    isSelected: Boolean,
    replyPreview: ChatMessageEntity?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme

    val bubbleColor = when {
        isMine -> scheme.primaryContainer
        else -> scheme.surface
    }
    val textColor = when {
        isMine -> scheme.onPrimaryContainer
        else -> scheme.onSurface
    }

    // A tail on the outer corner only. Cheaper and steadier than drawing an
    // actual tail path, and reads the same at a glance.
    val shape = if (isMine) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp)
    }

    // No animateFloatAsState here on purpose. It allocates an animation object
    // per row, and in a thread of a few hundred messages that is a measurable
    // amount of the frame budget for an effect nobody sees.
    val selectionBg = if (isSelected) scheme.primary.copy(alpha = 0.10f) else Color.Transparent

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(selectionBg)
            .padding(horizontal = 10.dp, vertical = 1.dp),
        contentAlignment = if (isMine) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Surface(
            color = bubbleColor,
            shape = shape,
            // Both elevations are zero deliberately: a shadow is a separate
            // render pass per row, and WhatsApp's bubbles are flat anyway. The
            // bubble reads against the background on colour alone.
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {

                // Author name only on the first message of a run, and never on
                // your own — you know who you are.
                if (showAuthor && !isMine) {
                    Text(
                        message.authorName,
                        style = MaterialTheme.typography.labelLarge,
                        color = authorColor(message.author),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                }

                replyPreview?.let { ReplyQuote(it, isMine) }

                when (message.kind) {
                    "image" -> ImageContent(message)
                    "video" -> VideoContent(message)
                    "audio" -> AudioContent(message, textColor)
                }

                if (message.body.isNotBlank()) {
                    if (message.kind != "text") Spacer(Modifier.height(5.dp))
                    Text(message.body, style = MaterialTheme.typography.bodyLarge, color = textColor)
                }

                // Tags a technician attached via long-press.
                if (message.vehicle != null) {
                    Spacer(Modifier.height(5.dp))
                    TagChip(Icons.Default.DirectionsBus, message.vehicle)
                }

                Spacer(Modifier.height(3.dp))
                // align(End) rather than fillMaxWidth: filling stretches the
                // bubble to its max width even for a two-letter message, which
                // is the single most un-WhatsApp-like thing a bubble can do.
                MetaRow(message, isMine, textColor, onRetry, Modifier.align(Alignment.End))
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
) {
    val muted = textColor.copy(alpha = 0.55f)
    Row(
        modifier,
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (message.geotagged) {
            Icon(
                Icons.Default.LocationOn,
                contentDescription = "Location attached",
                tint = muted,
                modifier = Modifier.size(11.dp),
            )
            Spacer(Modifier.width(3.dp))
        }
        Text(
            timeFmt.format(Date(message.createdAt)),
            style = MaterialTheme.typography.labelSmall,
            color = muted,
        )
        if (isMine) {
            Spacer(Modifier.width(4.dp))
            DeliveryTick(message, muted, onRetry)
        }
    }
}

/**
 * Delivery state. Familiar tick grammar, but honest about what we actually
 * know: a single tick means the server allocated a seq, a double tick means
 * every member's read cursor has passed it. We never show a "delivered to
 * device" state, because nothing in the protocol reports that.
 */
@Composable
private fun DeliveryTick(message: ChatMessageEntity, muted: Color, onRetry: () -> Unit) {
    when (message.status) {
        SendStatus.PENDING -> Icon(
            Icons.Default.Schedule,
            contentDescription = "Waiting to send",
            tint = muted,
            modifier = Modifier.size(12.dp),
        )

        SendStatus.UPLOADING -> Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { message.uploadPct / 100f },
                strokeWidth = 1.5.dp,
                color = muted,
                modifier = Modifier.size(12.dp),
            )
        }

        SendStatus.SENT -> Icon(
            Icons.Default.Check,
            contentDescription = "Sent",
            tint = muted,
            modifier = Modifier.size(13.dp),
        )

        SendStatus.FAILED -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).padding(1.dp),
        ) {
            Icon(
                Icons.Default.ErrorOutline,
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
                modifier = Modifier.clickableNoRipple(onRetry),
            )
        }

        else -> Icon(
            Icons.Default.DoneAll,
            contentDescription = "Read",
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(13.dp),
        )
    }
}

@Composable
private fun ReplyQuote(source: ChatMessageEntity, isMine: Boolean) {
    val accent = if (isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
    Row(
        Modifier
            .padding(bottom = 5.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .heightIn(min = 34.dp),
    ) {
        Box(Modifier.width(3.dp).heightIn(min = 34.dp).background(accent))
        Column(Modifier.padding(horizontal = 7.dp, vertical = 4.dp)) {
            Text(
                source.authorName,
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                source.body.ifBlank { source.kind.replaceFirstChar { it.uppercase() } },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ImageContent(message: ChatMessageEntity) {
    Box(
        Modifier
            .widthIn(max = 260.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        AsyncImage(
            // Local path while queued so the photo is visible instantly, server
            // URL once committed.
            model = message.localPath ?: message.fileUrl?.let { absoluteUrl(it) },
            contentDescription = message.body.ifBlank { "Photo" },
            contentScale = ContentScale.Crop,
            modifier = Modifier.widthIn(max = 260.dp).heightIn(min = 120.dp, max = 300.dp),
        )
        if (message.status == SendStatus.UPLOADING) UploadScrim(message.uploadPct)
    }
}

@Composable
private fun VideoContent(message: ChatMessageEntity) {
    Box(
        Modifier
            .widthIn(max = 260.dp)
            .height(150.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = message.localPath ?: message.fileUrl?.let { absoluteUrl(it) },
            contentDescription = "Video",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(150.dp),
        )
        Surface(color = Color.Black.copy(alpha = 0.45f), shape = RoundedCornerShape(50)) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = "Play",
                tint = Color.White,
                modifier = Modifier.size(38.dp).padding(6.dp),
            )
        }
        if (message.status == SendStatus.UPLOADING) UploadScrim(message.uploadPct)
    }
}

@Composable
private fun AudioContent(message: ChatMessageEntity, textColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Icon(Icons.Default.Mic, contentDescription = null, tint = textColor, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            formatDuration(message.durationMs),
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
        )
        // Transcript arrives later from speech-to-text; shown inline when present.
        message.transcript?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.width(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = textColor.copy(alpha = 0.7f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun UploadScrim(pct: Int) {
    Box(
        Modifier.fillMaxWidth().height(150.dp).background(Color.Black.copy(alpha = 0.35f)),
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

/** Day divider between message runs. */
@Composable
fun DayDivider(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(10.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/** "Unread messages" separator, WhatsApp-style. */
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

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = MutableInteractionSource(),
        indication = null,
        onClick = onClick,
    )
