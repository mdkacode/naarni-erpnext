package com.naarni.service

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.naarni.service.ui.navigation.DeepLinkBus
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.naarni.service.core.feedback.LocalFeedback
import com.naarni.service.core.feedback.rememberFeedback
import com.naarni.service.core.push.registerFcmToken
import com.naarni.service.ui.AppViewModel
import com.naarni.service.data.dto.AppUpdateInfo
import com.naarni.service.ui.navigation.MainShell
import com.naarni.service.ui.screens.LoginScreen
import com.naarni.service.ui.screens.OptionalUpdateDialog
import com.naarni.service.ui.screens.UpdateRequiredScreen
import com.naarni.service.ui.screens.openStore
import com.naarni.service.ui.theme.NaarniTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate — swaps the launch/splash theme for the app theme.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        captureDeepLink(intent)
        enableEdgeToEdge()
        setContent {
            NaarniTheme {
                CompositionLocalProvider(LocalFeedback provides rememberFeedback()) {
                    val vm: AppViewModel = viewModel()
                    val context = LocalContext.current

                    // App version gate — checked on launch (before login). A backend
                    // failure returns no-update, so it can never lock users out.
                    var update by remember { mutableStateOf<AppUpdateInfo?>(null) }
                    var optionalDismissed by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        runCatching { vm.jobCards.appUpdate(BuildConfig.VERSION_CODE) }
                            .onSuccess { update = it }
                    }

                    if (update?.force_update == true) {
                        UpdateRequiredScreen(
                            message = update?.message,
                            onUpdate = { openStore(context, update?.update_url) },
                        )
                    } else {
                        // Ask for notification permission once (Android 13+).
                        val notifPermission = rememberLauncherForActivityResult(
                            ActivityResultContracts.RequestPermission(),
                        ) { /* result ignored — push still arrives, just silently if denied */ }
                        LaunchedEffect(Unit) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }

                        // Register the FCM token with the backend whenever we're logged in.
                        LaunchedEffect(vm.ui.loggedIn) {
                            if (vm.ui.loggedIn) registerFcmToken(context)
                        }

                        if (vm.ui.loggedIn) MainShell(vm) else LoginScreen(vm)

                        if (update?.update_available == true && !optionalDismissed) {
                            OptionalUpdateDialog(
                                message = update?.message,
                                onUpdate = { openStore(context, update?.update_url) },
                                onDismiss = { optionalDismissed = true },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureDeepLink(intent)
    }

    /** Stash a notification's deep-link route so MainShell can navigate to it. */
    private fun captureDeepLink(intent: Intent?) {
        val route = intent?.data?.toString() ?: intent?.getStringExtra("route")
        if (!route.isNullOrBlank()) DeepLinkBus.pending = route
    }
}
