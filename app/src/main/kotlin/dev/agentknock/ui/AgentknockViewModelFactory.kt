package dev.agentknock.ui

import android.app.Application
import android.app.ActivityManager
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.agentknock.ApplicationContainer
import dev.agentknock.R
import dev.agentknock.protocol.PairingAddressGenerator
import dev.agentknock.ui.clients.ClientsViewModel
import dev.agentknock.ui.device.DeviceSetupViewModel
import dev.agentknock.ui.requests.RequestsViewModel
import dev.agentknock.ui.secrets.SecretsViewModel
import dev.agentknock.ui.settings.SettingsViewModel
import dev.agentknock.ui.settings.SubscriptionViewModel

/** The single Android-aware composition root for UI state holders. */
internal fun agentknockViewModelFactory(
    application: Application,
    container: ApplicationContainer,
): ViewModelProvider.Factory {
    val pairingAddresses = PairingAddressGenerator(
        application.resources.openRawResource(R.raw.pairing_address_words)
            .bufferedReader()
            .use { reader -> reader.readLines() },
    )
    val awaitStorageReady: suspend () -> Unit = container.localStorage::await

    return viewModelFactory {
        initializer {
            DeviceSetupViewModel(
                repository = container.deviceIdentity,
                addressGenerator = pairingAddresses,
                refreshConnection = container.requestConnection::refresh,
            )
        }
        initializer {
            RequestsViewModel(
                repository = container.requests,
                inbox = container.requestInbox,
                connection = container.requestConnection,
                awaitStorageReady = awaitStorageReady,
            )
        }
        initializer {
            SecretsViewModel(
                repository = container.secrets,
                requests = container.requests,
                inbox = container.requestInbox,
                deviceIdentity = container.deviceIdentity,
                awaitStorageReady = awaitStorageReady,
            )
        }
        initializer {
            ClientsViewModel(
                repository = container.requests,
                inbox = container.requestInbox,
                secrets = container.secrets,
                deviceIdentity = container.deviceIdentity,
                deviceManagement = container.deviceManagement,
                awaitStorageReady = awaitStorageReady,
            )
        }
        initializer {
            SubscriptionViewModel(
                repository = container.subscription,
                awaitStorageReady = awaitStorageReady,
            )
        }
        initializer {
            SettingsViewModel(
                deviceIdentity = container.deviceIdentity,
                secrets = container.secrets,
                requests = container.requests,
                audit = container.audit,
                vaultKeys = container.vaultKeyManager,
                beginFactoryReset = container::beginFactoryReset,
                cancelFactoryReset = container::cancelFactoryReset,
                clearApplicationData = {
                    application.getSystemService(ActivityManager::class.java)
                        .clearApplicationUserData()
                },
                awaitStorageReady = awaitStorageReady,
            )
        }
    }
}
