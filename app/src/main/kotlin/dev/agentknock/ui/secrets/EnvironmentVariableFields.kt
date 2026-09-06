package dev.agentknock.ui.secrets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.agentknock.R

@Composable
internal fun EnvironmentVariableValueField(
    value: String,
    sensitive: Boolean,
    visible: Boolean,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onVisibilityChange: (Boolean) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.variable_value)) },
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        enabled = enabled,
        minLines = 1,
        maxLines = 6,
        visualTransformation = if (sensitive && !visible) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = if (sensitive) KeyboardType.Password else KeyboardType.Text,
        ),
        trailingIcon = if (sensitive) {
            {
                IconButton(onClick = { onVisibilityChange(!visible) }, enabled = enabled) {
                    Icon(
                        if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = stringResource(if (visible) R.string.hide else R.string.show),
                    )
                }
            }
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun EnvironmentVariableSensitivity(
    sensitive: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(
            value = sensitive,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onChange,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.sensitive))
            Text(
                sensitivityDescription(sensitive),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = sensitive, onCheckedChange = null, enabled = enabled)
    }
}
