package dev.agentknock.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun DeviceAuthenticationChoices(
    selected: DeviceAuthenticationMode,
    enabled: Boolean = true,
    onSelect: (DeviceAuthenticationMode) -> Unit,
) {
    Column(
        Modifier.selectableGroup().padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DeviceAuthenticationMode.entries.forEach { mode ->
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(
                            if (mode == selected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                            } else {
                                Color.Transparent
                            }
                        )
                        .selectable(
                            selected = mode == selected,
                            enabled = enabled,
                            role = Role.RadioButton,
                            onClick = { if (mode != selected) onSelect(mode) },
                        )
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                RadioButton(
                    selected = mode == selected,
                    enabled = enabled,
                    onClick = null,
                )
            }
        }
    }
}

internal fun DeviceAuthenticationMode.displayLabel(): String =
    when (this) {
        DeviceAuthenticationMode.DEVICE_LOCK -> "Rely on device lock"
        DeviceAuthenticationMode.SENSITIVE_VALUES_AND_PAIRING -> "Protect sensitive actions"
        DeviceAuthenticationMode.APP_LOCK -> "Lock Agentknock"
    }

internal fun DeviceAuthenticationMode.explanation(): String =
    when (this) {
        DeviceAuthenticationMode.DEVICE_LOCK -> "Use Android's screen lock, with no extra prompts."
        DeviceAuthenticationMode.SENSITIVE_VALUES_AND_PAIRING ->
            "Authenticate to view, copy or edit sensitive values, or pair a client."
        DeviceAuthenticationMode.APP_LOCK -> "Authenticate each time you return to Agentknock."
    }
