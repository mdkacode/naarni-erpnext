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

    var ui by mutableStateOf(AppUiState(loggedIn = session.isLoggedIn))
        private set

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
                ui.copy(loading = false, loggedIn = true)
            } else {
                ui.copy(loading = false, error = result.exceptionOrNull()?.message ?: "Invalid code")
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
}
