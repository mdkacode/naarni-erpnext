package com.naarni.service.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.naarni.service.ui.theme.AppSurface

/** Open the Play Store (or a configured URL) to update. Prefers the Play app. */
fun openStore(context: Context, url: String?) {
    val target = url?.takeIf { it.isNotBlank() }
        ?: "https://play.google.com/store/apps/details?id=com.naarni.service"
    val uri = if (target.contains("play.google.com")) {
        val id = Uri.parse(target).getQueryParameter("id")
            ?: context.packageName.removeSuffix(".debug")
        Uri.parse("market://details?id=$id")
    } else {
        Uri.parse(target)
    }
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        .onFailure { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target))) } }
}

/** Full-screen, non-dismissible gate shown when the installed build is below the
 *  minimum supported version. The engineer cannot proceed until they update. */
@Composable
fun UpdateRequiredScreen(message: String?, onUpdate: () -> Unit) {
    BackHandler(enabled = true) { /* block back — must update */ }
    // Neutral rather than a full-bleed brand gradient. This screen is a wall
    // somebody has hit, and dressing it in the brand made a blocked user look at
    // a marketing surface; the accent belongs on the one button that gets them
    // out of it.
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier.size(88.dp).background(AppSurface.sunken, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.SystemUpdate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(44.dp),
                )
            }
            Text(
                "Update required",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
            Text(
                message?.takeIf { it.isNotBlank() } ?: "A new version is available. Please update to continue.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onUpdate, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                Icon(Icons.Rounded.SystemUpdate, contentDescription = null)
                Spacer(Modifier.height(0.dp))
                Text("  Update now")
            }
        }
    }
}

/** Dismissible prompt when a newer (but not mandatory) build is available. */
@Composable
fun OptionalUpdateDialog(message: String?, onUpdate: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.SystemUpdate, contentDescription = null) },
        title = { Text("Update available") },
        text = { Text(message?.takeIf { it.isNotBlank() } ?: "A new version of NaArNi Care is available.") },
        confirmButton = { TextButton(onClick = onUpdate) { Text("Update") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } },
    )
}
