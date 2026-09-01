package dev.agentknock.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
internal fun FactoryResetSettings(
    onBack: () -> Unit,
    state: FactoryResetUiState,
    startReset: () -> Unit,
    confirmLocalClear: () -> Unit,
    cancelLocalClear: () -> Unit,
    consumeClearFailure: () -> Unit,
    report: (String) -> Unit,
    modifier: Modifier,
) {
    var phrase by rememberSaveable { mutableStateOf("") }
    val working = state == FactoryResetUiState.Working

    LaunchedEffect(state) {
        if (state == FactoryResetUiState.ClearFailed) {
            consumeClearFailure()
            report("Android could not clear Agentknock's app data. Clear its storage from Android settings.")
        }
    }

    if (state == FactoryResetUiState.ConfirmLocalClear) {
        AlertDialog(
            onDismissRequest = cancelLocalClear,
            title = { Text("Relay deletion not confirmed") },
            text = {
                Text(
                    "Clearing Agentknock now may leave the old device registration on the relay. Clear all app data anyway?",
                )
            },
            confirmButton = {
                TextButton(onClick = confirmLocalClear) {
                    Text("Clear app data", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = cancelLocalClear) { Text("Cancel") }
            },
        )
    }

    Column(modifier) {
        PageTopBar("Factory reset Agentknock", onBack = { if (!working) onBack() })
        Column(
            Modifier.verticalScroll(rememberScrollState()).imePadding().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                "This cannot be undone",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { heading() },
            )
            Text("Agentknock will ask the relay to delete this device registration and its live relay state.")
            Text("It will then permanently erase:")
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("• Device identity and encryption keys")
                Text("• Secrets and their stored values")
                Text("• Paired clients and requests")
                Text("• Audit log and settings")
            }
            Text("Setup starts again with a new device identity and pairing address. Every client must pair again.")
            Text(
                "If relay deletion cannot be confirmed, you will be asked again before any app data is cleared. Factory reset does not fix temporary connection problems.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Type RESET AGENTKNOCK to confirm.", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = phrase,
                onValueChange = { phrase = it },
                enabled = !working,
                label = { Text("Confirmation phrase") },
                supportingText = { Text("Enter the phrase exactly as shown, including spaces.") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                ),
            )
            Button(
                onClick = startReset,
                enabled = phrase == "RESET AGENTKNOCK" && !working,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                if (working) {
                    CircularProgressIndicator(
                        Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    Text("Erase and reset Agentknock")
                }
            }
        }
    }
}
