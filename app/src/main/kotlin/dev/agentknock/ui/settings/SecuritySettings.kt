package dev.agentknock.ui.settings

import android.app.KeyguardManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.agentknock.storage.crypto.EncryptionKeyBacking
import dev.agentknock.storage.crypto.VaultProtection
import dev.agentknock.ui.auth.DeviceAuthenticationMode
import dev.agentknock.ui.theme.agentknockColors

@Composable
internal fun SecuritySettings(
    counts: DataCounts,
    protection: VaultProtection?,
    authenticationMode: DeviceAuthenticationMode,
    onAuthenticationModeChange: (DeviceAuthenticationMode) -> Unit,
    onFactoryReset: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val deviceSecure = context.getSystemService(KeyguardManager::class.java).isDeviceSecure
    var chooseAuthentication by remember { mutableStateOf(false) }
    Column(modifier) {
        PageTopBar("Security and backup", onBack)
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { EncryptionStatus(protection) }
            item {
                Column {
                    SettingsSectionLabel("Protection")
                    SettingsGroup {
                        SettingsRow(
                            title = "Device authentication",
                            summary = authenticationMode.displayLabel(),
                            onClick = { chooseAuthentication = true },
                        )
                        SettingsGroupDivider()
                        SettingsValueRow("Encryption", "AES-128-GCM")
                        SettingsGroupDivider()
                        SettingsValueRow("Key storage", protection.keyStorageDescription())
                        SettingsGroupDivider()
                        SettingsValueRow(
                            "Device screen lock",
                            if (deviceSecure) "Configured" else "Not configured",
                        )
                    }
                }
            }
            item {
                Column {
                    SettingsSectionLabel("Backup")
                    SettingsGroup {
                        SettingsRow(
                            title = "Android backup",
                            summary = "Metadata and encrypted values are backed up; device-bound encryption keys are not.",
                        )
                        SettingsGroupDivider()
                        SettingsRow(
                            title = "Key recovery",
                            summary = "Not configured. Restored secrets and client pairings cannot be decrypted on another device.",
                        )
                        SettingsGroupDivider()
                        SettingsRow(
                            title = "Stored data",
                            summary = "${counts.secrets.countLabel("secret")} · ${counts.clients.countLabel("client")}",
                        )
                    }
                }
            }
            item {
                Column {
                    SettingsSectionLabel("Reset")
                    SettingsGroup {
                        SettingsRow(
                            title = "Factory reset Agentknock",
                            summary = "Erase the device identity, encryption keys, secrets, clients, and history",
                            destructive = true,
                            onClick = onFactoryReset,
                        )
                    }
                }
            }
        }
    }
    if (chooseAuthentication) {
        AlertDialog(
            onDismissRequest = { chooseAuthentication = false },
            title = { Text("Device authentication") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    DeviceAuthenticationMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    chooseAuthentication = false
                                    onAuthenticationModeChange(mode)
                                }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            RadioButton(selected = mode == authenticationMode, onClick = null)
                            Column(Modifier.weight(1f)) {
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
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { chooseAuthentication = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EncryptionStatus(protection: VaultProtection?) {
    val hardwareBacked = protection is VaultProtection.Available &&
        protection.backings.values.all(EncryptionKeyBacking::isHardwareBacked)
    val softwareBacked = protection is VaultProtection.Available &&
        protection.backings.values.any { it == EncryptionKeyBacking.SOFTWARE }
    val style = when {
        hardwareBacked -> StatusStyle(
            "Hardware-backed encryption",
            "Stored secret values and device credentials are encrypted with keys protected by secure hardware.",
            MaterialTheme.agentknockColors.successContainer,
            MaterialTheme.agentknockColors.onSuccessContainer,
        )
        softwareBacked -> StatusStyle(
            "Android Keystore encryption",
            "Stored secret values and device credentials are encrypted on this device.",
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurface,
        )
        protection is VaultProtection.KeyUnavailable -> StatusStyle(
            "Encryption key unavailable",
            "One or more keys cannot be used on this device.",
            MaterialTheme.agentknockColors.dangerContainer,
            MaterialTheme.agentknockColors.onDangerContainer,
        )
        protection == null -> StatusStyle(
            "Checking encryption",
            "Reading this device's key protection.",
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurface,
        )
        else -> StatusStyle(
            "Stored values are encrypted",
            "Android did not report the exact hardware protection for the encryption keys.",
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurface,
        )
    }
    Surface(
        color = style.container,
        contentColor = style.content,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = style.content.copy(alpha = 0.14f),
                contentColor = style.content,
                shape = androidx.compose.foundation.shape.CircleShape,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Security, contentDescription = null)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(style.title, style = MaterialTheme.typography.titleLarge)
                Text(style.detail, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private data class StatusStyle(
    val title: String,
    val detail: String,
    val container: Color,
    val content: Color,
)

internal fun EncryptionKeyBacking.isHardwareBacked(): Boolean = when (this) {
    EncryptionKeyBacking.STRONGBOX,
    EncryptionKeyBacking.TRUSTED_ENVIRONMENT,
    EncryptionKeyBacking.UNKNOWN_SECURE,
    -> true
    EncryptionKeyBacking.SOFTWARE,
    EncryptionKeyBacking.UNKNOWN,
    -> false
}

private fun VaultProtection?.keyStorageDescription(): String = when (this) {
    null -> "Checking this device…"
    is VaultProtection.Available -> backings.values.distinct().let { kinds ->
        if (kinds.size != 1) {
            "Mixed Android Keystore protection"
        } else {
            when (kinds.single()) {
                EncryptionKeyBacking.STRONGBOX -> "Android StrongBox"
                EncryptionKeyBacking.TRUSTED_ENVIRONMENT -> "Trusted execution environment"
                EncryptionKeyBacking.SOFTWARE -> "Android Keystore, software-backed"
                EncryptionKeyBacking.UNKNOWN_SECURE -> "Secure hardware"
                EncryptionKeyBacking.UNKNOWN -> "Android Keystore, backing not reported"
            }
        }
    }
    is VaultProtection.KeyUnavailable -> "Key unavailable on this device"
    VaultProtection.Unknown -> "Android Keystore, protection not reported"
}

private fun DeviceAuthenticationMode.displayLabel(): String = when (this) {
    DeviceAuthenticationMode.DEVICE_LOCK -> "Rely on device lock"
    DeviceAuthenticationMode.SENSITIVE_VALUES_AND_PAIRING -> "Sensitive values and pairing"
    DeviceAuthenticationMode.APP_LOCK -> "Lock Agentknock"
}

private fun DeviceAuthenticationMode.explanation(): String = when (this) {
    DeviceAuthenticationMode.DEVICE_LOCK ->
        "No additional Agentknock prompts. Request decisions and destructive confirmations still apply."
    DeviceAuthenticationMode.SENSITIVE_VALUES_AND_PAIRING ->
        "Authenticate before showing, copying, or editing sensitive values, weakening their protection, or accepting a new client."
    DeviceAuthenticationMode.APP_LOCK ->
        "Authenticate before any Agentknock content is shown. One unlock lasts for the foreground session."
}

private fun Int.countLabel(noun: String): String = "$this $noun${if (this == 1) "" else "s"}"
