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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.naarni.service.core.feedback.LocalFeedback
import com.naarni.service.data.dto.ChatUserDto
import com.naarni.service.ui.components.EmptyState
import com.naarni.service.ui.theme.BrandGradient

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
) {
    var query by remember { mutableStateOf("") }
    val feedback = LocalFeedback.current
    var opening by remember { mutableStateOf<String?>(null) }
    val people = vm.directory.results

    LaunchedEffect(query) { vm.directory.query(query) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {

        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.horizontalGradient(BrandGradient))
                .statusBarsPadding()
                .padding(start = 2.dp, end = 14.dp, top = 4.dp, bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text("New chat", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text(
                        if (people.isEmpty()) "Staff directory" else "${people.size} people",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.8f),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Surface(
                color = Color.White.copy(alpha = 0.16f),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.padding(start = 12.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.PersonSearch,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(18.dp),
                    )
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                        cursorBrush = SolidColor(Color.White),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 9.dp, top = 11.dp, bottom = 11.dp),
                        decorationBox = { inner ->
                            Box {
                                if (query.isEmpty()) {
                                    Text(
                                        "Search name or phone number",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = Color.White.copy(alpha = 0.7f),
                                    )
                                }
                                inner()
                            }
                        },
                    )
                    if (vm.directory.busy) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = Color.White,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
        }

        if (people.isEmpty() && !vm.directory.busy) {
            EmptyState(
                icon = Icons.Default.PersonSearch,
                title = "Nobody found",
                body = "Try a different name or phone number.",
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
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
                model = absoluteUrl(imageUrl),
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
