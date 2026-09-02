package dev.agentknock.ui.auth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun DeviceAuthenticationChoices(
    selected: DeviceAuthenticationMode,
    enabled: Boolean = true,
    onSelect: (DeviceAuthenticationMode) -> Unit,
) {
    Column {
        DeviceAuthenticationMode.entries.forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled && mode != selected) { onSelect(mode) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                RadioButton(
                    selected = mode == selected,
                    enabled = enabled,
                    onClick = null,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(mode.displayLabel(), style = MaterialTheme.typography.titleSmall)
                    Text(
                        mode.explanation(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

internal fun DeviceAuthenticationMode.displayLabel(): String = when (this) {
    DeviceAuthenticationMode.DEVICE_LOCK -> "Rely on device lock"
    DeviceAuthenticationMode.SENSITIVE_VALUES_AND_PAIRING -> "Protect sensitive actions"
    DeviceAuthenticationMode.APP_LOCK -> "Lock Agentknock"
}

internal fun DeviceAuthenticationMode.explanation(): String = when (this) {
    DeviceAuthenticationMode.DEVICE_LOCK ->
        "No extra prompts. Android's screen lock protects access to the app."
    DeviceAuthenticationMode.SENSITIVE_VALUES_AND_PAIRING ->
        "Authenticate before revealing or changing protected values and accepting a new client."
    DeviceAuthenticationMode.APP_LOCK ->
        "Authenticate whenever Agentknock is opened after leaving the app."
}
