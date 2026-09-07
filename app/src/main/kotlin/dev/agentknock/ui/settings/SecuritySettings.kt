package dev.agentknock.ui.settings

import android.app.KeyguardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.agentknock.storage.crypto.EncryptionKeyBacking
import dev.agentknock.storage.crypto.VaultKeyPurpose
import dev.agentknock.storage.crypto.VaultProtection
import dev.agentknock.ui.auth.DeviceAuthenticationMode
import dev.agentknock.ui.auth.DeviceAuthenticationChoices

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
    SecuritySettingsContent(
        protection = protection,
        authenticationMode = authenticationMode,
        deviceSecure = deviceSecure,
        onAuthenticationModeChange = onAuthenticationModeChange,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
internal fun SecuritySettingsContent(
    protection: VaultProtection?,
    authenticationMode: DeviceAuthenticationMode,
    deviceSecure: Boolean,
    onAuthenticationModeChange: (DeviceAuthenticationMode) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    Column(modifier) {
        PageTopBar("Security and backup", onBack)
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
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
                        SettingsGroupDivider()
                        SettingsValueRow(
                            "Android screen lock",
                            if (deviceSecure) "Configured" else "Not configured",
                        )
                    }
                }
            }
            item {
                Column {
                    SettingsSectionLabel("Encryption")
                    SettingsGroup {
                        SettingsValueRow("Algorithm", "AES-128-GCM")
                        SettingsGroupDivider()
                        SettingsValueRow("Current key storage", protection.keyStorageDescription())
                        SettingsGroupDivider()
                        SettingsValueRow(
                            "Stored encrypted data",
                            protection.storedDataDescription(),
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
                            value = "Metadata, history and encrypted values are eligible for Android backup. Encryption keys stay on this installation and are not backed up.",
                        )
                        SettingsGroupDivider()
                        SettingsValueRow(
                            label = "After a restore",
                            value = "Metadata and history can return, but secret values cannot be recovered with this backup. You must replace those values and pair your clients again.",
                        )
                    }
                }
            }
        }
    }
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
