package com.naarni.service

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.naarni.service.ui.navigation.DeepLinkBus
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
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
        blockScreenCapture()
        captureDeepLink(intent)
        // Deliberately *not* edge-to-edge.
        //
        // `enableEdgeToEdge()` calls `setDecorFitsSystemWindows(window, false)`,
        // and that quietly turns the manifest's `adjustResize` into a no-op: the
        // window stops resizing for the keyboard and every screen becomes
        // responsible for consuming `WindowInsets.ime` itself. Exactly one did.
        // Everywhere else — login, the inspection runner, job cards, onboarding
        // — the keyboard came up over the field being typed into, which is about
        // as fundamental as a bug gets on a phone that exists to be typed into.
        //
        // Letting the system inset the window is the fix. Drawing under the
        // status bar was never worth a field an engineer cannot see.
        setContent {
            NaarniTheme {
                CompositionLocalProvider(LocalFeedback provides rememberFeedback()) {
                    val vm: AppViewModel = viewModel()
                    val context = LocalContext.current

                    // App version gate — checked on launch (before login). A backend
                    // failure returns no-update, so it can never lock users out.
                    var update by remember { mutableStateOf<AppUpdateInfo?>(null) }
                    var optionalDismissed by remember { mutableStateOf(false) }

                    // Re-checked every time the app comes back to the foreground,
                    // not just on a cold start. A depot handset can sit in the
                    // app for days; if a build is withdrawn because it corrupts
                    // data or talks to an endpoint that no longer exists, waiting
                    // for the user to happen to swipe it away is not a gate.
                    //
                    // Only a successful answer is applied, so a lost connection
                    // can neither impose a gate nor lift one that is already up.
                    val lifecycleOwner = LocalLifecycleOwner.current
                    val updateScope = rememberCoroutineScope()
                    DisposableEffect(lifecycleOwner) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_RESUME) {
                                updateScope.launch {
                                    runCatching { vm.jobCards.appUpdate(BuildConfig.VERSION_CODE) }
                                        .onSuccess { update = it }
                                }
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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

                        // Debug-only scripted sign-in, for driving the device from
                        // adb during the test matrix:
                        //   adb shell am start -n <pkg>/.MainActivity \
                        //     --es dev_login "9990200001:secret"
                        // Absent from release builds; OTP remains the only real path.
                        if (BuildConfig.DEBUG) {
                            val creds = remember { intent?.getStringExtra("dev_login") }
                            LaunchedEffect(creds, vm.ui.loggedIn) {
                                if (!creds.isNullOrBlank() && !vm.ui.loggedIn) {
                                    val phone = creds.substringBefore(':')
                                    val password = creds.substringAfter(':', "")
                                    if (phone.isNotBlank() && password.isNotBlank()) {
                                        vm.devLogin(phone, password)
                                    }
                                }
                            }
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
    /**
     * Blocks screenshots, screen recording and cast/mirror capture across the
     * whole app.
     *
     * One flag on the one window covers every surface, because this is a
     * single-Activity app: the screenshot gesture fails with the system's
     * "can't take screenshot" toast, a recording or a cast captures a black
     * frame, and — the leak people forget — the recent-apps thumbnail renders
     * blank.
     *
     * Set unconditionally rather than per screen. The material gate, the battery
     * QC inspections and chat all carry commercially sensitive content, and a
     * per-screen allowlist is a list somebody eventually forgets to add to.
     * `BuildConfig.DEBUG` is deliberately not an exemption either: a debug build
     * on a desk is exactly where a screenshot of production data gets taken.
     *
     * Two limits, stated because a security control that is oversold is worse
     * than none: it cannot stop a photograph of the screen, and it is a request
     * the OS honours — a rooted device or a modified ROM can ignore it.
     */
    private fun blockScreenCapture() {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
    }

    private fun captureDeepLink(intent: Intent?) {
        val route = intent?.data?.toString() ?: intent?.getStringExtra("route")
        if (!route.isNullOrBlank()) DeepLinkBus.pending = route
    }
}
