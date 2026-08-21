package com.naarni.service.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.work.WorkManager
import com.naarni.service.App
import com.naarni.service.core.chat.ChatWork
import com.naarni.service.core.inspection.InspectionWork
import com.naarni.service.core.network.SESSION_EXPIRED_MESSAGE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /**
     * The same engine, offline-first.
     *
     * Every write the runner makes goes here rather than to [processes]: it
     * lands in Room before the network is consulted, and a background worker
     * pushes it when there is one. [processes] remains for the read-only
     * screens — history, the report — which are a record of finished work and
     * have no queue to protect.
     */
    val inspections = container.inspectionRepo

    /** Whether the handset has a usable network right now, for the offline strip. */
    val online = container.connectivity.online

    /** Duty roster & check-in / check-out. */
    val roster = container.rosterRepo

    /** The material gate — inward and outward movements at Hubli and Narsapura. */
    val material = container.materialRepo

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
        // The server is the only thing that knows a session has died, and it
        // says so on whichever request happens to be in flight. Collected once
        // here rather than handled at each call site, because the twenty
        // repositories that make requests must not each remember to.
        viewModelScope.launch {
            session.expired.collect { signOut(SESSION_EXPIRED_MESSAGE) }
        }
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

    fun logout() = signOut(null)

    /**
     * End the session and leave nothing of it behind.
     *
     * Used by the Sign out button and by an expiry the server reported. Both
     * must clear the same things: until now neither dropped the chat database,
     * so the next person to sign in on a shared depot handset inherited the
     * previous technician's threads — and their unsent outbox.
     *
     * [reason] is null for a deliberate sign-out and carries an explanation
     * when the app did this on the user's behalf, so the login screen can say
     * why they are suddenly looking at it.
     */
    private fun signOut(reason: String?) {
        runCatching { container.chatSocket.disconnect() }
        // Cancel queued sends before the rows go, so a worker cannot wake up
        // mid-wipe and re-insert what it was holding.
        runCatching { WorkManager.getInstance(getApplication()).cancelAllWorkByTag(ChatWork.TAG) }
        // Inspection jobs go too, or a queued sync belonging to this engineer
        // fires under the next person's session on a shared depot handset. The
        // queued *work* deliberately survives — see below.
        runCatching { InspectionWork.cancelAll(getApplication()) }
        auth.logout()
        ui = AppUiState(loggedIn = false, error = reason)

        // Off the main thread, and deliberately not awaited: the user is already
        // on the login screen and must never wait on a disk wipe to get there.
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { container.chatDao.wipeEverything() } }
            // Only the process catalogue, which is per-role. An inspection that
            // has not synced yet is *not* dropped: it may exist nowhere else,
            // and signing out of a shared handset must not destroy the work of
            // whoever used it before. It goes up when that account signs back in.
            runCatching {
                withContext(Dispatchers.IO) { container.inspectionDao.clearCatalogue() }
            }
        }
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
