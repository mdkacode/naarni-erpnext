package com.naarni.service

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.naarni.service.core.feedback.LocalFeedback
import com.naarni.service.core.feedback.rememberFeedback
import com.naarni.service.core.push.registerFcmToken
import com.naarni.service.ui.AppViewModel
import com.naarni.service.ui.navigation.MainShell
import com.naarni.service.ui.screens.LoginScreen
import com.naarni.service.ui.theme.NaarniTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate — swaps the launch/splash theme for the app theme.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NaarniTheme {
                CompositionLocalProvider(LocalFeedback provides rememberFeedback()) {
                    val vm: AppViewModel = viewModel()
                    val context = LocalContext.current

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
                }
            }
        }
    }
}
