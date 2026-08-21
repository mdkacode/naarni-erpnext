package com.naarni.service.core.inspection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Watches for a network coming back, and drains the queue when it does.
 *
 * Registered once from `App.onCreate` and never torn down, which is the
 * important difference from the chat socket's equivalent: that one is tied to a
 * composable, so it only listens while a chat screen exists. An inspection
 * queued in a shed has to go up when the van reaches signal whether or not
 * anybody has opened the app, so this listens for the life of the process.
 *
 * It also exposes [online] for the UI. Telling an engineer plainly that they are
 * offline and that their work is saved is not decoration — without it, a queued
 * answer and a lost answer look exactly the same, and an engineer who suspects
 * the second will stop trusting the first.
 */
class InspectionConnectivity(private val context: Context) {

	private val _online = MutableStateFlow(true)

	/** Whether the handset currently has a usable network. */
	val online: StateFlow<Boolean> = _online.asStateFlow()

	fun start() {
		val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
		_online.value = manager?.hasInternet() ?: true

		val callback = object : ConnectivityManager.NetworkCallback() {
			override fun onAvailable(network: Network) {
				_online.value = true
				// The moment there is a link. Not on the next periodic sweep
				// fifteen minutes later — an engineer walking out of a shed
				// should find the work already gone by the time they look.
				InspectionWork.sweep(context)
			}

			override fun onLost(network: Network) {
				_online.value = manager?.hasInternet() ?: false
			}

			override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
				// A connection that exists but cannot reach anything — a captive
				// depot Wi-Fi portal, most often — is not online, and treating it
				// as such is how a queue appears to stall for no reason.
				_online.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
			}
		}

		runCatching {
			manager?.registerNetworkCallback(
				NetworkRequest.Builder()
					.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
					.build(),
				callback,
			)
		}

		ProcessLifecycleOwner.get().lifecycle.addObserver(
			object : DefaultLifecycleObserver {
				override fun onStart(owner: LifecycleOwner) {
					// Covers the phone that was rebooted, or whose jobs an
					// aggressive OEM battery manager quietly cleared — which on
					// the handsets this fleet actually uses is not a rare event.
					InspectionWork.sweep(context)
					InspectionWork.ensurePeriodic(context)
				}
			},
		)

		InspectionWork.ensurePeriodic(context)
	}

	private fun ConnectivityManager.hasInternet(): Boolean {
		val caps = getNetworkCapabilities(activeNetwork) ?: return false
		return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
			caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
	}
}
