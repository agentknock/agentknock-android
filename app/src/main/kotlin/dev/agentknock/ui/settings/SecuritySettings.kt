package dev.agentknock.ui.settings

import android.app.KeyguardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.agentknock.storage.crypto.EncryptionKeyBacking
import dev.agentknock.storage.crypto.VaultKeyPurpose
import dev.agentknock.storage.crypto.VaultProtection
import dev.agentknock.ui.auth.DeviceAuthenticationMode
import dev.agentknock.ui.auth.DeviceAuthenticationChoices
import dev.agentknock.ui.theme.agentknockColors

@Composable
internal fun SecuritySettings(
    protection: VaultProtection?,
    authenticationMode: DeviceAuthenticationMode,
    onAuthenticationModeChange: (DeviceAuthenticationMode) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val deviceSecure = context.getSystemService(KeyguardManager::class.java).isDeviceSecure
    Column(modifier) {
        PageTopBar("Security and backup", onBack)
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column {
                    SettingsSectionLabel("Device authentication")
                    SettingsGroup {
                        DeviceAuthenticationChoices(
                            selected = authenticationMode,
                            onSelect = onAuthenticationModeChange,
                        )
                    }
                }
            }
            item { EncryptionStatus(protection) }
            item {
                Column {
                    SettingsSectionLabel("Encryption")
                    SettingsGroup {
                        SettingsValueRow("Encryption", "AES-128-GCM")
                        SettingsGroupDivider()
                        SettingsValueRow("Current key storage", protection.keyStorageDescription())
                        SettingsGroupDivider()
                        SettingsValueRow(
                            "Stored encrypted data",
                            protection.storedDataDescription(),
                        )
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
                        SettingsValueRow(
                            label = "Android backup",
                            value = "Eligible for Android backup. Metadata and encrypted values can be restored, but device-bound encryption keys cannot.",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EncryptionStatus(protection: VaultProtection?) {
    val hardwareBacked = protection is VaultProtection.ActiveKeysAvailable &&
        protection.backings.values.all(EncryptionKeyBacking::isHardwareBacked)
    val softwareBacked = protection is VaultProtection.ActiveKeysAvailable &&
        protection.backings.values.any { it == EncryptionKeyBacking.SOFTWARE }
    val style = when {
        protection != null && protection.unavailableStoredData.isNotEmpty() -> StatusStyle(
            "Stored data unavailable",
            protection.unavailableStoredData.explanation(),
            MaterialTheme.agentknockColors.dangerContainer,
            MaterialTheme.agentknockColors.onDangerContainer,
        )
        hardwareBacked -> StatusStyle(
            "Hardware-backed encryption",
            "Current encryption keys are protected by secure hardware, and all stored encrypted data is available.",
            MaterialTheme.agentknockColors.successContainer,
            MaterialTheme.agentknockColors.onSuccessContainer,
        )
        softwareBacked -> StatusStyle(
            "Android Keystore encryption",
            "Current encryption keys are held by Android Keystore, and all stored encrypted data is available.",
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurface,
        )
        protection is VaultProtection.ActiveKeysUnavailable -> StatusStyle(
            "Current encryption key unavailable",
            "Agentknock cannot encrypt new secret or device data on this device.",
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
    is VaultProtection.ActiveKeysAvailable -> backings.values.distinct().let { kinds ->
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
    is VaultProtection.ActiveKeysUnavailable -> "Current key unavailable on this device"
    is VaultProtection.ActiveKeyProtectionUnknown -> "Android Keystore, protection not reported"
}

private fun VaultProtection?.storedDataDescription(): String = when {
    this == null -> "Checking this device…"
    unavailableStoredData.isEmpty() -> "Available on this device"
    else -> unavailableStoredData.explanation()
}

private fun Set<VaultKeyPurpose>.explanation(): String = when (this) {
    setOf(VaultKeyPurpose.SECRET_VALUES) ->
        "Some secret values cannot be decrypted on this device."
    setOf(VaultKeyPurpose.DEVICE_STATE) ->
        "Some client pairings, device credentials, or in-progress requests cannot be decrypted on this device."
    else -> "Some secret values or encrypted device state cannot be decrypted on this device."
}
