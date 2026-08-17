package com.naarni.service.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.PersonSearch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.naarni.service.core.feedback.LocalFeedback
import com.naarni.service.data.dto.ChatUserDto
import com.naarni.service.ui.components.AppBar
import com.naarni.service.ui.components.EmptyState
import com.naarni.service.ui.components.HairlineDivider
import com.naarni.service.ui.components.SearchField
import com.naarni.service.ui.theme.AppSurface

/**
 * Start a conversation with anyone in the organisation.
 *
 * The list is populated before a single character is typed — an empty query
 * returns a browsable directory. Requiring a search term first is the classic
 * way to make a feature look empty and broken to someone who does not yet know
 * a colleague's exact name.
 */
@Composable
fun NewChatScreen(
    vm: ChatViewModel,
    onBack: () -> Unit,
    onOpenRoom: (String) -> Unit,
    onNewGroup: () -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    val feedback = LocalFeedback.current
    var opening by remember { mutableStateOf<String?>(null) }
    val people = vm.directory.results

    LaunchedEffect(query) { vm.directory.query(query) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {

        AppBar(
            title = "New chat",
            subtitle = if (people.isEmpty()) "Staff directory" else "${people.size} people",
            onBack = onBack,
        )
        Box(Modifier.background(AppSurface.raised).padding(horizontal = 14.dp, vertical = 10.dp)) {
            SearchField(
                value = query,
                onChange = { query = it },
                placeholder = "Search name or phone number",
                leadingIcon = Icons.Rounded.PersonSearch,
                busy = vm.directory.busy,
            )
        }
        HairlineDivider()

        if (people.isEmpty() && !vm.directory.busy) {
            EmptyState(
                icon = Icons.Rounded.PersonSearch,
                title = "Nobody found",
                body = "Try a different name or phone number.",
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                // Where WhatsApp puts it, because that is where people look for
                // it — above the contacts, not behind another menu.
                item(key = "new-group") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { feedback.tap(); onNewGroup() }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.Groups,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(23.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "New group",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                items(people, key = { it.name }) { user ->
                    ContactRow(
                        user = user,
                        busy = opening == user.name,
                        onClick = {
                            if (opening != null) return@ContactRow
                            feedback.tap()
                            opening = user.name
                            vm.openDirect(user.name) { room ->
                                opening = null
                                onOpenRoom(room)
                            }
                        },
                    )
                }
            }
        }
    }
}

/** One person in a directory listing. Shared by this screen and the chat list. */
@Composable
fun ContactRow(user: ChatUserDto, busy: Boolean, onClick: () -> Unit) {
    val display = user.full_name?.takeIf { it.isNotBlank() } ?: user.name
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(display, user.user_image, user.name)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                display,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            user.mobile_no?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(1.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
    }
}

/**
 * Profile photo when the user has one, initials on a stable colour otherwise.
 *
 * [onDark] is for the brand gradient in a thread header, where the usual
 * 16%-tint-plus-coloured-initials disappears almost entirely: both the tint and
 * the letters are mid-tone indigo sitting on mid-tone indigo. There it needs a
 * translucent white disc and white letters instead.
 */
@Composable
fun Avatar(
    displayName: String,
    imageUrl: String?,
    seed: String,
    size: Int = 48,
    onDark: Boolean = false,
) {
    val tint = if (onDark) Color.White else authorColor(seed)
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(if (onDark) Color.White.copy(alpha = 0.25f) else tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                // The *endpoint*, not the stored path. Profile pictures live in
                // the private bucket, where Frappe checks the file against the
                // User it hangs off — so the raw url renders for its owner and
                // nobody else. `seed` is the user id at every call site that
                // passes a person; groups pass no image at all.
                model = com.naarni.service.data.repo.ProfileRepository.avatarUrl(seed)
                    ?: absoluteUrl(imageUrl),
                contentDescription = displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size.dp).clip(CircleShape),
            )
        } else {
            Text(
                displayName.split(" ").take(2).mapNotNull { it.firstOrNull() }
                    .joinToString("").uppercase().ifBlank { "?" },
                style = MaterialTheme.typography.labelLarge,
                color = tint,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
