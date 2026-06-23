package com.naarni.service.core.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Thin wrapper over FusedLocationProvider. Used at photo-capture time to stamp
 * coordinates. Returns null on denial/timeout — the caller stamps "Location
 * unavailable" rather than blocking the capture (soft gate, plan §5).
 */
class LocationProvider(context: Context) {

    private val client = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission") // caller checks permission before invoking
    suspend fun current(timeoutMs: Long = 6000): Location? = withTimeoutOrNull(timeoutMs) {
        runCatching {
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
        }.getOrNull() ?: runCatching { client.lastLocation.await() }.getOrNull()
    }
}
