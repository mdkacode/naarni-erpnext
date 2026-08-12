package com.naarni.service.ui.chat

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.naarni.service.appContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns the chat socket's connection across the whole process (TC05).
 *
 * Backgrounding and locking the screen are the *same* signal — an activity
 * reaches `onStop` for both — so one `ProcessLifecycleOwner` observer handles
 * both cases rather than two subtly different code paths.
 *
 * The grace period is the part that matters in the field. Dropping the socket
 * the instant the screen goes dark would churn a connection every time someone
 * glances at their phone; holding it open indefinitely would burn battery on a
 * handset that spends its day in a pocket. Thirty seconds covers a screen-off /
 * screen-on tap without a reconnect, and anything longer hands delivery to FCM,
 * which is designed for exactly that.
 */
@Composable
fun ChatLifecycle(vm: ChatViewModel) {
    val context = LocalContext.current
    val socket = remember(context) { context.appContainer.chatSocket }
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }

    DisposableEffect(Unit) {
        var disconnectJob: Job? = null

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    // Coming back: cancel any pending teardown and reuse the live
                    // socket if the grace period has not elapsed.
                    disconnectJob?.cancel()
                    disconnectJob = null
                    socket.reconnectNow()
                    // Resync regardless of socket health — the cursor, not the
                    // connection, is the source of truth about what we missed.
                    vm.refresh()
                }

                Lifecycle.Event.ON_STOP -> {
                    disconnectJob?.cancel()
                    disconnectJob = scope.launch {
                        delay(GRACE_MS)
                        socket.disconnect()
                    }
                }

                else -> Unit
            }
        }

        val owner = ProcessLifecycleOwner.get()
        owner.lifecycle.addObserver(observer)

        // Short-circuit a long backoff the moment the radio comes back, rather
        // than waiting out an exponential delay that started during a dead zone.
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val netCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch {
                    socket.reconnectNow()
                    vm.refresh()
                }
            }
        }
        runCatching {
            cm?.registerNetworkCallback(NetworkRequest.Builder().build(), netCallback)
        }

        onDispose {
            owner.lifecycle.removeObserver(observer)
            disconnectJob?.cancel()
            runCatching { cm?.unregisterNetworkCallback(netCallback) }
            socket.disconnect()
        }
    }

    // First connect once the shell is composed and we know there is a session.
    LaunchedEffect(Unit) { socket.connect() }
}

private const val GRACE_MS = 30_000L
