package com.naarni.service.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.naarni.service.App
import kotlinx.coroutines.launch

data class AppUiState(
    val loggedIn: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    /** True once an OTP has been sent — the login screen then shows the code field. */
    val otpSent: Boolean = false,
    /** Phone the OTP was sent to (shown on the verify step). */
    val otpPhone: String = "",
)

/**
 * Top-level app state: auth/session. Screen-specific data is loaded in the
 * screens via the repositories exposed here.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as App).container
    val session = container.session
    val auth = container.authRepo
    val jobCards = container.jobCardRepo

    /** Process engine — one repository serving every configured process. */
    val processes = container.processRepo

    var ui by mutableStateOf(AppUiState(loggedIn = session.isLoggedIn))
        private set

    /** Live dropdown options (admin-editable via Customize Form), with offline fallback. */
    var options by mutableStateOf(AppOptions.DEFAULTS)
        private set

    /** The option list for [key] — live when fetched, else the bundled default. */
    fun opt(key: String): List<String> = options[key] ?: AppOptions.DEFAULTS[key] ?: emptyList()

    /** Refresh dropdown options from the backend; keeps defaults for any missing key. */
    fun loadOptions() {
        viewModelScope.launch {
            runCatching { jobCards.appFieldOptions() }
                .onSuccess { fetched -> options = AppOptions.DEFAULTS + fetched.filterValues { it.isNotEmpty() } }
        }
    }

    init {
        if (session.isLoggedIn) loadOptions()
    }

    /** Step 1: request an OTP for [phone]. On success the screen reveals the code field. */
    fun requestOtp(phone: String) {
        viewModelScope.launch {
            ui = ui.copy(loading = true, error = null)
            val result = auth.requestOtp(phone)
            ui = if (result.isSuccess) {
                ui.copy(loading = false, otpSent = true, otpPhone = phone)
            } else {
                ui.copy(loading = false, error = result.exceptionOrNull()?.message ?: "Could not send code")
            }
        }
    }

    /** Step 2: verify the [otp] for the phone we sent it to; on success, log in. */
    fun verifyOtp(otp: String) {
        viewModelScope.launch {
            ui = ui.copy(loading = true, error = null)
            val result = auth.verifyOtp(ui.otpPhone, otp)
            ui = if (result.isSuccess) {
                loadOptions()
                ui.copy(loading = false, loggedIn = true)
            } else {
                ui.copy(loading = false, error = result.exceptionOrNull()?.message ?: "Invalid code")
            }
        }
    }

    /**
     * Debug-only password sign-in.
     *
     * The production path is OTP, which is issued by the Naarni backend — so a
     * debug build pointed at a local bench (`-PdevBackend=true`) has no way to
     * authenticate against test users that only exist there. This uses the
     * existing `login_with_phone` endpoint and is gated on BuildConfig.DEBUG at
     * the call site, so it cannot ship.
     */
    fun devLogin(phone: String, password: String) {
        viewModelScope.launch {
            ui = ui.copy(loading = true, error = null)
            val result = auth.login(phone, password)
            ui = if (result.isSuccess) {
                loadOptions()
                ui.copy(loading = false, loggedIn = true)
            } else {
                ui.copy(loading = false, error = result.exceptionOrNull()?.message ?: "Sign-in failed")
            }
        }
    }

    /** Back to the phone step (change number / resend). */
    fun resetOtp() {
        ui = ui.copy(otpSent = false, otpPhone = "", error = null)
    }

    fun logout() {
        auth.logout()
        ui = AppUiState(loggedIn = false)
    }

    /**
     * Request account deletion (Play requirement). On success the backend has
     * deactivated the account and the local session is cleared — drop to login.
     * [onResult] reports success/failure so the screen can show a message.
     */
    fun deleteAccount(onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            ui = ui.copy(loading = true, error = null)
            val result = auth.requestAccountDeletion()
            if (result.isSuccess) {
                ui = AppUiState(loggedIn = false)
                onResult(true, null)
            } else {
                val msg = result.exceptionOrNull()?.message ?: "Could not delete account"
                ui = ui.copy(loading = false, error = msg)
                onResult(false, msg)
            }
        }
    }
}
