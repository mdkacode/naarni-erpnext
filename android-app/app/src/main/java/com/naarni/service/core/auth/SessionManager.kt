package com.naarni.service.core.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persists the logged-in session securely (EncryptedSharedPreferences).
 *
 * Auth rides on the Frappe session cookie (`sid`) obtained from the OTP login
 * (`verify_otp`) — we store it here and replay it on every request.
 *
 * [deviceUuid] is this install's stable device identity. The Naarni OTP flow is
 * device-bound (the code is validated against contact + device), so we register
 * one UUID per install and send it on both the OTP request and verify. It
 * survives logout — only the device knows it, and re-registering is idempotent.
 */
class SessionManager(context: Context) {

    private val prefs: SharedPreferences = run {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "naarni_session",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var sid: String?
        get() = prefs.getString(KEY_SID, null)
        set(value) {
            prefs.edit().putString(KEY_SID, value).apply()
            // A fresh sid means whatever went wrong last time is over; re-arm the
            // one-shot guard so a *future* expiry is still reported.
            if (!value.isNullOrBlank() && value != GUEST) expiryReported.set(false)
        }

    var user: String?
        get() = prefs.getString(KEY_USER, null)
        set(value) = prefs.edit().putString(KEY_USER, value).apply()

    var fullName: String?
        get() = prefs.getString(KEY_FULL_NAME, null)
        set(value) = prefs.edit().putString(KEY_FULL_NAME, value).apply()

    var roles: Set<String>
        get() = prefs.getStringSet(KEY_ROLES, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_ROLES, value).apply()

    /** Stable per-install device id (lazily generated, preserved across logout). */
    val deviceUuid: String
        get() = prefs.getString(KEY_DEVICE_UUID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_UUID, it).apply()
        }

    /**
     * True only for a real session.
     *
     * Frappe answers a dead `sid` by *setting the cookie to `Guest`* rather than
     * deleting it, so "Guest" arrives through the cookie jar looking exactly
     * like a session and is not one. Treating it as blank everywhere keeps that
     * one Frappe quirk in a single place.
     */
    val hasLiveSession: Boolean get() = sid.let { !it.isNullOrBlank() && it != GUEST }

    val isLoggedIn: Boolean get() = hasLiveSession

    /** Primary role to show in the photo stamp / profile (best-effort). */
    val primaryRole: String
        get() = listOf("Service Engineer", "Depot Manager", "Technician", "Central Ops")
            .firstOrNull { it in roles } ?: roles.firstOrNull() ?: "User"

    // ------------------------------------------------------------- expiry

    private val _expired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Fires once when the server tells us this session no longer exists.
     *
     * Collected at the top of the app, which is what actually signs the user
     * out — see AppViewModel. It lives here rather than in a global singleton
     * because the OkHttp interceptor that detects it is already handed a
     * SessionManager, and the alternative is a process-wide mutable object that
     * every test then has to reset.
     */
    val expired: SharedFlow<Unit> = _expired

    /**
     * One expiry per session, however many requests discover it.
     *
     * A screen that fires five parallel calls gets five 403s back within a few
     * milliseconds of each other; without this guard that is five sign-outs and
     * five navigations, and the last one wins by luck.
     */
    private val expiryReported = AtomicBoolean(false)

    /**
     * The server rejected our `sid`. Called only for an unambiguous signal —
     * never for an ordinary permission error, which must leave the user exactly
     * where they are.
     */
    fun markExpired() {
        if (!hasLiveSession) return
        if (!expiryReported.compareAndSet(false, true)) return
        _expired.tryEmit(Unit)
    }

    /** Clear the session but KEEP the device identity (so re-login reuses the device). */
    fun clear() = prefs.edit()
        .remove(KEY_SID)
        .remove(KEY_USER)
        .remove(KEY_FULL_NAME)
        .remove(KEY_ROLES)
        .apply()

    private companion object {
        const val KEY_SID = "sid"
        const val KEY_USER = "user"
        const val KEY_FULL_NAME = "full_name"
        const val KEY_ROLES = "roles"
        const val KEY_DEVICE_UUID = "device_uuid"
        const val GUEST = "Guest"
    }
}
