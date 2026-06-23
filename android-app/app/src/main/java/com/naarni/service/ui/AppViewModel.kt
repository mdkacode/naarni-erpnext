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

    fun login(phone: String, password: String) {
        viewModelScope.launch {
            ui = ui.copy(loading = true, error = null)
            val result = auth.login(phone, password)
            ui = if (result.isSuccess) {
                ui.copy(loading = false, loggedIn = true)
            } else {
                ui.copy(loading = false, error = result.exceptionOrNull()?.message ?: "Login failed")
            }
        }
    }

    fun logout() {
        auth.logout()
        ui = AppUiState(loggedIn = false)
    }
}
