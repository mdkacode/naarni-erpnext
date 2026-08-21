package com.naarni.service.ui.material

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naarni.service.ui.AppViewModel

/** The process the gate runs on. The register is projected from its runs. */
const val GATE_FAMILY = "MATERIAL_GATE"

/** Its one stage, so the entry opens on question one instead of a board of one. */
const val GATE_STAGE = "GATE"

/**
 * Opening a gate entry: no form, no picker, no board — just question one.
 *
 * The generic process flow asks for a subject label before it starts and then
 * shows a module board. Both are right for a battery pack, which has a serial
 * printed on it and fifty-nine checks across four modules, and both are wrong
 * here: a clerk has no number to type before the truck is open, and a board of
 * one module is a screen whose only purpose is to be tapped through.
 *
 * So this screen exists to not be seen. It starts the run and moves on; it only
 * draws anything when starting fails, which offline means the definition has
 * never reached this phone.
 */
@Composable
fun GateEntryScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onStarted: (String) -> Unit,
) {
    val online by vm.online.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    // Bumped by Try again, which is what re-fires the effect.
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        error = null
        runCatching {
            vm.inspections.startOrJoinRun(
                processFamily = GATE_FAMILY,
                // Auto Generate: the run names itself GN-YYYY-#####.
                identifier = null,
                online = online,
            )
        }
            .onSuccess { onStarted(it.runUuid) }
            .onFailure { error = it.message ?: "Could not open a new entry." }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val message = error
            if (message == null) {
                CircularProgressIndicator()
                Text(
                    "Opening a new entry…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Cannot start an entry",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    message,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { attempt++ },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("Try again", fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onBack) { Text("Back to the register") }
            }
        }
    }
}
