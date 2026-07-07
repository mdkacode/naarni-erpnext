package com.naarni.service.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naarni.service.ui.theme.BrandGradient

/** Rounded brand mark with a gradient fill — used on the login hero. */
@Composable
fun BrandLogo(icon: ImageVector, size: Int = 72, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size.dp)
            .background(Brush.linearGradient(BrandGradient), RoundedCornerShape((size / 3.5).dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size((size * 0.5).dp))
    }
}

/** A compact stat card (label + value) for dashboards. */
@Composable
fun StatTile(label: String, value: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
            }
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Workflow-state colour mapping (mirrors the web SPA's status palette). */
fun statusColor(state: String?): Color = when (state?.lowercase()) {
    "open" -> Color(0xFF6366F1)
    "wip", "work in progress" -> Color(0xFFF59E0B)
    "awaiting customer approval" -> Color(0xFF8B5CF6)
    "awaiting parts" -> Color(0xFFEAB308)
    "parts fitted" -> Color(0xFF06B6D4)
    "closure from technician", "verification pending" -> Color(0xFF0EA5E9)
    "closed" -> Color(0xFF16A34A)
    "reopened" -> Color(0xFFEF4444)
    else -> Color(0xFF64748B)
}

/** Priority / criticality colour mapping (Low → Urgent, plus force-close severities). */
fun priorityColor(value: String?): Color = when (value?.lowercase()) {
    "urgent", "critical" -> Color(0xFFEF4444)
    "high", "major" -> Color(0xFFF97316)
    "medium" -> Color(0xFFEAB308)
    "low", "minor" -> Color(0xFF22C55E)
    else -> Color(0xFF64748B)
}

/** A small labelled pill for priority / criticality. */
@Composable
fun PriorityPill(value: String?) {
    if (value.isNullOrBlank()) return
    val c = priorityColor(value)
    Surface(color = c.copy(alpha = 0.14f), shape = RoundedCornerShape(50)) {
        Text(
            value,
            color = c,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** Icon + text meta line (date, odometer, customer …) used on list rows. */
@Composable
fun MetaChip(icon: ImageVector, text: String, modifier: Modifier = Modifier, tint: Color? = null) {
    val color = tint ?: MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** A coloured status pill. */
@Composable
fun StatusChip(state: String?) {
    val c = statusColor(state)
    Surface(color = c.copy(alpha = 0.14f), shape = RoundedCornerShape(50)) {
        Text(
            state ?: "—",
            color = c,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/**
 * A vehicle registration number with the **last 4 characters enlarged + brand-
 * coloured** so it reads at a glance (the prefix is muted). Reused across cards
 * and detail headers.
 */
@Composable
fun VehicleNumber(
    number: String?,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    lastScale: Float = 1.35f,
    highlightColor: Color = MaterialTheme.colorScheme.primary,
    prefixColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val clean = number?.trim().orEmpty()
    if (clean.isBlank()) {
        Text("Unknown vehicle", style = style, color = prefixColor, modifier = modifier, maxLines = 1)
        return
    }
    val text = remember(clean, style.fontSize, lastScale, highlightColor, prefixColor) {
        val cut = (clean.length - 4).coerceAtLeast(0)
        buildAnnotatedString {
            if (cut > 0) {
                withStyle(SpanStyle(color = prefixColor, fontWeight = FontWeight.SemiBold)) {
                    append(clean.substring(0, cut))
                }
            }
            withStyle(
                SpanStyle(
                    color = highlightColor,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = style.fontSize * lastScale,
                    letterSpacing = 1.sp,
                )
            ) { append(clean.substring(cut)) }
        }
    }
    Text(text, style = style, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * A translucent scrim + spinner that sits ON TOP of content (which stays visible)
 * during an in-place action like resolving. Fades in/out and swallows taps.
 */
@Composable
fun LoadingOverlay(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = visible, enter = fadeIn(tween(150)), exit = fadeOut(tween(150)), modifier = modifier) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.18f))
                // Swallow all touches while busy (no ripple).
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            contentAlignment = Alignment.Center,
        ) {
            Surface(shape = RoundedCornerShape(18.dp), tonalElevation = 4.dp, shadowElevation = 6.dp) {
                Box(Modifier.padding(22.dp)) { CircularProgressIndicator(strokeWidth = 3.dp) }
            }
        }
    }
}

/** A subtle pulsing placeholder for skeleton loading states — fills whatever
 *  size the [modifier] gives it (bar, block, or a circle via [shape]). */
@Composable
fun SkeletonBox(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(8.dp)) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = alpha * 0.16f), shape))
}

/** Severity → colour (warning/critical) used for alerts. */
fun severityColor(sev: String?): Color = when (sev?.lowercase()) {
    "critical" -> Color(0xFFEF4444)
    "warning" -> Color(0xFFF59E0B)
    else -> Color(0xFF64748B)
}

/** A coloured severity pill (Warning / Critical). */
@Composable
fun SeverityPill(severity: String?) {
    if (severity.isNullOrBlank()) return
    val c = severityColor(severity)
    Surface(color = c.copy(alpha = 0.14f), shape = RoundedCornerShape(50)) {
        Text(
            severity.replaceFirstChar { it.uppercase() },
            color = c,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** Friendly empty/placeholder state with an icon. */
@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(72.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
