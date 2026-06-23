package com.naarni.service.core.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

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
        set(value) = prefs.edit().putString(KEY_SID, value).apply()

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

    val isLoggedIn: Boolean get() = !sid.isNullOrBlank()

    /** Primary role to show in the photo stamp / profile (best-effort). */
    val primaryRole: String
        get() = listOf("Service Engineer", "Depot Manager", "Technician", "Central Ops")
            .firstOrNull { it in roles } ?: roles.firstOrNull() ?: "User"

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
    }
}
