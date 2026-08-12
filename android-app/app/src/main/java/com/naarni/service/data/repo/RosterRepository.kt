package com.naarni.service.data.repo

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.naarni.service.core.location.LocationProvider
import com.naarni.service.core.network.FrappeApi
import com.naarni.service.core.network.payload
import com.naarni.service.data.dto.DutyState
import com.naarni.service.data.dto.MyAttendance
import com.naarni.service.data.dto.MyRoster
import com.naarni.service.data.dto.PunchResult
import java.util.UUID

/**
 * Duty roster & check-in.
 *
 * The one rule that shapes this class: **a missing location must never stop a
 * punch.** GPS fails at a depot gate under a metal roof, and an engineer who
 * cannot check in does not go home — they start work and the record is simply
 * lost, which is worse than a punch flagged "location not captured". So
 * [punch] asks for a fix, waits a few seconds, and posts either way; the server
 * records the shortfall and the depot manager sees it.
 *
 * Each punch carries a client-generated UUID so a retry after a timeout on a
 * depot's flaky Wi-Fi resolves to the punch already recorded rather than
 * creating a second one.
 */
class RosterRepository(
    private val api: FrappeApi,
    private val context: Context,
) {
    private val location = LocationProvider(context)

    /** Stable per-install id, so a depot manager can tell two devices apart. */
    private val deviceId: String? by lazy {
        runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
    }

    suspend fun myDuty(onDate: String? = null): DutyState = api.getMyDuty(onDate).payload()

    suspend fun myRoster(fromDate: String? = null, toDate: String? = null): MyRoster =
        api.getMyRoster(fromDate, toDate).payload()

    suspend fun myAttendance(fromDate: String? = null, toDate: String? = null): MyAttendance =
        api.getMyAttendance(fromDate, toDate).payload()

    suspend fun checkIn(note: String? = null, photo: String? = null): PunchResult =
        punch(PUNCH_IN, note, photo)

    suspend fun checkOut(note: String? = null, photo: String? = null): PunchResult =
        punch(PUNCH_OUT, note, photo)

    private suspend fun punch(type: String, note: String?, photo: String?): PunchResult {
        val fix = if (hasLocationPermission()) location.current() else null
        return api.dutyPunch(
            punchType = type,
            latitude = fix?.latitude,
            longitude = fix?.longitude,
            accuracyM = fix?.accuracy?.toDouble(),
            deviceUuid = deviceId,
            clientUuid = UUID.randomUUID().toString(),
            note = note,
            photo = photo,
        ).payload()
    }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val PUNCH_IN = "In"
        const val PUNCH_OUT = "Out"

        const val ACTION_CHECK_IN = "check_in"
        const val ACTION_CHECK_OUT = "check_out"
        const val ACTION_DONE = "done"
        const val ACTION_NONE = "none"
    }
}
