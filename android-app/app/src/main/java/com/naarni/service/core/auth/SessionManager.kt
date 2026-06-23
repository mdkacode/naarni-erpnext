package com.naarni.service.core.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persists the logged-in session securely (EncryptedSharedPreferences).
 *
 * Auth currently rides on the Frappe session cookie (`sid`) obtained from
 * `login_with_phone` — we store it here and replay it on every request. When the
 * mobile api_key/secret endpoint lands (KOTLIN_APP_PLAN §9.5) this is where the
 * token would live instead.
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

    val isLoggedIn: Boolean get() = !sid.isNullOrBlank()

    /** Primary role to show in the photo stamp / profile (best-effort). */
    val primaryRole: String
        get() = listOf("Service Engineer", "Depot Manager", "Technician", "Central Ops")
            .firstOrNull { it in roles } ?: roles.firstOrNull() ?: "User"

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val KEY_SID = "sid"
        const val KEY_USER = "user"
        const val KEY_FULL_NAME = "full_name"
        const val KEY_ROLES = "roles"
    }
}
