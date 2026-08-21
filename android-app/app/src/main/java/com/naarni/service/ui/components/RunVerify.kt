package com.naarni.service.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naarni.service.data.dto.ProcessStep

/** One line of the read-back: what was asked, what was answered, what was shot. */
data class VerifyLine(
    val screenIndex: Int,
    val displayNo: String,
    val label: String,
    val answer: String,
    val answered: Boolean,
    val photos: List<ReviewablePhoto>,
)

/**
 * The last screen of a run: everything answered, read back, before it commits.
 *
 * The point is not ceremony. The person who captured the evidence is the only
 * person who can still tell that the photograph is of the wrong crate or that
 * the weight went in with a digit missing — and they can only tell while the
 * item is still in front of them. A record checked at a desk the next morning
 * is checked by somebody who has to believe it.
 *
 * So every line is a tap back to the question that produced it. A review screen
 * that can only be agreed with is a button, not a review.
 */
@Composable
fun RunVerifyCard(
    step: ProcessStep,
    lines: List<VerifyLine>,
    confirmed: Boolean,
    onJump: (Int) -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {},
) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        header()

        Text(
            step.label,
            fontSize = 22.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        step.help_text?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        lines.forEach { line -> VerifyRow(line, onJump) }

        Button(
            onClick = onConfirm,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(top = 6.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (confirmed) PassGreen else MaterialTheme.colorScheme.primary,
            ),
        ) {
            if (confirmed) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null)
            }
            Text(
                if (confirmed) "  Confirmed — finish" else "This is all correct",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun VerifyRow(line: VerifyLine, onJump: (Int) -> Unit) {
    Surface(
        Modifier
            .fillMaxWidth()
            .clickable { onJump(line.screenIndex) },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(
            1.dp,
            // Amber, not red. Nothing on this screen is wrong — steps 2, 3 and 6
            // are all legitimately empty — but an operator scanning the list
            // needs to see at a glance which of them they left behind.
            if (line.answered) MaterialTheme.colorScheme.outlineVariant else WarnAmber,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${line.displayNo}. ${line.label}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        line.answer,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (line.answered) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            WarnAmber
                        },
                    )
                }
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = "Go back and change this",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
            if (line.photos.isNotEmpty()) {
                PhotoStrip(photos = line.photos)
            }
        }
    }
}
