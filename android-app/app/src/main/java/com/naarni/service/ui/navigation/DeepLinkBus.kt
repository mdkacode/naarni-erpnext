package com.naarni.service.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * One-shot holder for a deep-link route (`naarni://alert/{id}` etc.) captured from
 * a notification tap. [MainActivity] writes it; [MainShell] consumes and clears it
 * once the NavController is ready (and the user is logged in).
 */
object DeepLinkBus {
    var pending by mutableStateOf<String?>(null)
}
