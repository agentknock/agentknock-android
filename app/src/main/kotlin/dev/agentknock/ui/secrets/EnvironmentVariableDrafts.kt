package dev.agentknock.ui.secrets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
internal fun EnvironmentVariableDrafts(
    variables: List<EnvironmentVariableDraft>,
    enabled: Boolean,
    onChange: (List<EnvironmentVariableDraft>) -> Unit,
) {
    val duplicateNames =
        variables
            .filter { it.name.isNotBlank() }
            .groupingBy { it.name }
            .eachCount()
            .filterValues { it > 1 }
            .keys
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Environment variables",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            TextButton(
                onClick = {
                    val nextId = (variables.maxOfOrNull { it.id } ?: -1) + 1
                    onChange(variables + EnvironmentVariableDraft(id = nextId))
                },
                enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "Add environment variable" },
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add")
            }
        }
        if (variables.isEmpty()) {
            Text(
                "You can create an empty secret and add environment variables later.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        variables.forEach { variable ->
            val invalidName =
                variable.name.isNotEmpty() && !environmentVariableName.matches(variable.name)
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.large,
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = variable.name,
                        onValueChange = { name ->
                            onChange(variables.replace(variable.id) { it.copy(name = name) })
                        },
                        label = { Text("Environment variable name") },
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    onChange(variables.filterNot { it.id == variable.id })
                                },
                                enabled = enabled,
                            ) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = "Remove environment variable",
                                )
                            }
                        },
                        textStyle =
                            MaterialTheme.typography.bodyLarge.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                        singleLine = true,
                        enabled = enabled,
                        isError = invalidName || variable.name in duplicateNames,
                        supportingText =
                            when {
                                variable.name in duplicateNames -> ({ Text("Name is duplicated") })
                                invalidName -> ({ Text("Enter a valid environment variable name") })
                                else -> null
                            },
                        keyboardOptions =
                            KeyboardOptions(
                                capitalization = KeyboardCapitalization.Characters,
                                autoCorrectEnabled = false,
                                keyboardType = KeyboardType.Ascii,
                            ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    EnvironmentVariableDraftValue(
                        variable = variable,
                        enabled = enabled,
                        onChange = { updated ->
                            onChange(variables.replace(variable.id) { updated })
                        },
                    )
                    EnvironmentVariableSensitivity(
                        sensitive = variable.sensitive,
                        enabled = enabled,
                        onChange = { sensitive ->
                            onChange(
                                variables.replace(variable.id) { it.copy(sensitive = sensitive) }
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EnvironmentVariableDraftValue(
    variable: EnvironmentVariableDraft,
    enabled: Boolean,
    onChange: (EnvironmentVariableDraft) -> Unit,
) {
    var visible by remember(variable.id) { mutableStateOf(false) }
    EnvironmentVariableValueField(
        value = variable.value,
        sensitive = variable.sensitive,
        visible = visible,
        onValueChange = { onChange(variable.copy(value = it)) },
        onVisibilityChange = { visible = it },
        enabled = enabled,
    )
}

private fun List<EnvironmentVariableDraft>.replace(
    id: Long,
    transform: (EnvironmentVariableDraft) -> EnvironmentVariableDraft,
): List<EnvironmentVariableDraft> = map { if (it.id == id) transform(it) else it }

internal fun sensitivityDescription(sensitive: Boolean): String =
    if (sensitive) {
        "Follows approval settings. Hidden on this device until revealed."
    } else {
        "Visible and provided without approval; may be shared with AI."
    }
