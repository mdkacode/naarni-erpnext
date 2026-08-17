package com.naarni.service.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.naarni.service.ui.theme.AppSurface

/**
 * The app's one top bar.
 *
 * Five screens previously each drew their own `Row` filled with the brand
 * gradient, which had three costs. Every one of them had to force white text and
 * white icons regardless of theme, so a dark-mode header was a bright indigo band
 * above a near-black screen. The gradient consumed the brand colour on furniture,
 * leaving nothing to mark the actual action on the screen. And because each was
 * hand-rolled, the back button sat at a different inset on each one.
 *
 * This is neutral, sits on the same ground as the content beneath it, and is
 * separated by a hairline rather than by colour — so the accent is free for the
 * thing the user came to press.
 */
@Composable
fun AppBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    /** Drawn between the back button and the title — an avatar, usually. */
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    /**
     * A subtitle the caller draws itself, for the cases where the second line is
     * not plain grey text — a live "typing…" that wants the accent, say. Takes
     * precedence over [subtitle]; pass one or the other, never both.
     */
    subtitleContent: (@Composable () -> Unit)? = null,
    /**
     * Makes the avatar-and-title block a tap target.
     *
     * The convention every messaging app shares: the name at the top of a
     * conversation is how you get to what that conversation *is* — its people,
     * its shared files. Leaving it inert means the only route is a kebab menu,
     * which is the one place nobody looks first.
     */
    onTitleClick: (() -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(AppSurface.raised)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = if (onBack != null) 4.dp else 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            onBack?.let {
                IconButton(onClick = it, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(21.dp),
                    )
                }
            }
            // The avatar and the text are one target, not two. Splitting them
            // gives a 34dp-tall strip either side of the avatar that looks
            // pressable and is not, which reads as the tap having failed.
            Row(
                Modifier
                    .weight(1f)
                    .then(
                        if (onTitleClick != null) {
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(onClick = onTitleClick)
                                .padding(vertical = 2.dp)
                        } else {
                            Modifier
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leading?.let {
                    it()
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Reserves no space when absent, so a bar without one is not
                    // a two-line bar with a blank second line.
                    when {
                        subtitleContent != null -> subtitleContent()
                        !subtitle.isNullOrBlank() -> Text(
                            subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                content = actions,
            )
        }
        HairlineDivider()
    }
}

/**
 * The separator between a bar and its content.
 *
 * One physical pixel wherever possible: at 1.dp on a 3x-density screen this is a
 * three-pixel-thick grey band, which on a neutral scheme reads as a deliberate
 * rule rather than as an edge.
 */
@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(AppSurface.hairline),
    )
}

/**
 * The app's search field.
 *
 * Three screens each had their own, all of them white-on-translucent-white
 * because all three sat on the brand gradient — which meant the placeholder text
 * was at 70% white on indigo and effectively unreadable in daylight. One neutral
 * field instead, on the sunken ground, with real ink for the text.
 *
 * `BasicTextField` rather than Material's: the Material one reserves vertical
 * space for a floating label whether or not it has one, which is most of the
 * reason the old pills were 56dp tall.
 */
@Composable
fun SearchField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector = Icons.Rounded.Search,
    busy: Boolean = false,
) {
    Surface(
        color = AppSurface.sunken,
        shape = RoundedCornerShape(22.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                leadingIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.weight(1f).padding(start = 9.dp, top = 10.dp, bottom = 10.dp),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
            // The spinner and the clear button occupy the same slot: a field can
            // be searching or clearable, never both, and giving them separate
            // slots made the text jump sideways as results arrived.
            if (busy) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
            } else if (value.isNotEmpty()) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "Clear search",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .clickable { onChange("") },
                )
            }
        }
    }
}
