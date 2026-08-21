package com.naarni.service.ui.material

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.naarni.service.App
import com.naarni.service.data.dto.GateContext
import com.naarni.service.data.dto.Movement
import com.naarni.service.data.dto.MovementSummary
import kotlinx.coroutines.launch

data class MaterialUiState(
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val context: GateContext? = null,
    val scope: String = "open",
    val movements: List<MovementSummary> = emptyList(),
    val awaitingCount: Int = 0,
    val canVerify: Boolean = false,
    val current: Movement? = null,
)

/**
 * State for the material register: the list, and one movement read back.
 *
 * Writing is not here. An entry is a run of `MATERIAL_GATE`, held in Room and
 * synced by the process engine, and the movement it produces is a projection of
 * that run — so this view model reads, verifies and rejects, and never creates.
 *
 * Errors are surfaced, never swallowed. A gate clerk who thinks something saved
 * when it did not is worse off than one who is told it failed.
 */
class MaterialViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as App).container.materialRepo

    var ui by mutableStateOf(MaterialUiState())
        private set

    // ── context & list ──

    fun load(scope: String = ui.scope, refreshContext: Boolean = false) {
        viewModelScope.launch {
            ui = ui.copy(loading = true, error = null, scope = scope)
            runCatching {
                val context = repo.context(refresh = refreshContext)
                val page = repo.list(scope = scope)
                context to page
            }.onSuccess { (context, page) ->
                ui = ui.copy(
                    loading = false,
                    context = context,
                    movements = page.movements,
                    awaitingCount = page.awaiting_count,
                    canVerify = page.can_verify || context.can_verify,
                )
            }.onFailure { ui = ui.copy(loading = false, error = it.readable()) }
        }
    }

    // ── one movement ──

    fun open(name: String) {
        viewModelScope.launch {
            ui = ui.copy(loading = true, error = null)
            runCatching { repo.get(name) }
                .onSuccess { ui = ui.copy(loading = false, current = it) }
                .onFailure { ui = ui.copy(loading = false, error = it.readable()) }
        }
    }

    fun verify(remarks: String = "", onDone: () -> Unit) = act(onDone) { repo.verify(it, remarks) }

    fun reject(reason: String, onDone: () -> Unit) = act(onDone) { repo.reject(it, reason) }

    private fun act(onDone: () -> Unit, block: suspend (String) -> Movement) {
        val movement = ui.current?.name ?: return
        viewModelScope.launch {
            ui = ui.copy(saving = true, error = null)
            runCatching { block(movement) }
                .onSuccess {
                    ui = ui.copy(saving = false, current = it)
                    onDone()
                }
                .onFailure { ui = ui.copy(saving = false, error = it.readable()) }
        }
    }

    fun clearMessages() {
        ui = ui.copy(error = null, notice = null)
    }
}

/** Server messages are written for the person holding the phone; keep them. */
private fun Throwable.readable(): String =
    message?.takeIf { it.isNotBlank() } ?: "Something went wrong. Try again."
