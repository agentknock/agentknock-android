package dev.agentknock.ui

import android.app.Application
import android.app.ActivityManager
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.agentknock.ApplicationContainer
import dev.agentknock.R
import dev.agentknock.protocol.PairingAddressGenerator
import dev.agentknock.ui.clients.ClientsViewModel
import dev.agentknock.ui.device.DeviceSetupViewModel
import dev.agentknock.ui.requests.RequestsViewModel
import dev.agentknock.ui.secrets.SecretsViewModel
import dev.agentknock.ui.settings.AuditViewModel
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
            AgentknockViewModel(
                configuration = container.deviceConfiguration,
                requests = container.requestSummaries,
                inbox = container.requestInbox,
                protectedActions = container.protectedActions,
            )
        }
        initializer {
            DeviceSetupViewModel(
                settings = container.deviceSettings,
                addressGenerator = pairingAddresses,
            )
        }
        initializer {
            RequestsViewModel(
                savedStateHandle = createSavedStateHandle(),
                repository = container.requests,
                inbox = container.requestInbox,
                requestSummaries = container.requestSummaries,
                connection = container.requestConnection,
                awaitStorageReady = awaitStorageReady,
            )
        }
        initializer {
            SecretsViewModel(
                savedStateHandle = createSavedStateHandle(),
                repository = container.secrets,
                requests = container.requests,
                inbox = container.requestInbox,
                requestSummaries = container.requestSummaries,
                clientSummaries = container.clientSummaries,
                secretSummaries = container.secretSummaries,
                configuration = container.deviceConfiguration,
                deviceSettings = container.deviceSettings,
                awaitStorageReady = awaitStorageReady,
                protectedActions = container.protectedActions,
            )
        }
        initializer {
            ClientsViewModel(
                savedStateHandle = createSavedStateHandle(),
                repository = container.requests,
                inbox = container.requestInbox,
                requestSummaries = container.requestSummaries,
                clientSummaries = container.clientSummaries,
                secrets = container.secrets,
                configuration = container.deviceConfiguration,
                deviceManagement = container.deviceSettings,
                awaitStorageReady = awaitStorageReady,
                protectedActions = container.protectedActions,
            )
        }
        initializer {
            SubscriptionViewModel(
                repository = container.subscription,
                awaitStorageReady = awaitStorageReady,
            )
        }
        initializer {
            AuditViewModel(
                audit = container.audit,
                savedStateHandle = createSavedStateHandle(),
            )
        }
        initializer {
            SettingsViewModel(
                configuration = container.deviceConfiguration,
                secretSummaries = container.secretSummaries,
                clientSummaries = container.clientSummaries,
                pushRegistration = container.pushRegistration,
                vaultKeys = container.vaultKeyManager,
                beginFactoryReset = container::beginFactoryReset,
                cancelFactoryReset = container::cancelFactoryReset,
                clearApplicationData = {
                    application.getSystemService(ActivityManager::class.java)
                        .clearApplicationUserData()
                },
                awaitStorageReady = awaitStorageReady,
                protectedActions = container.protectedActions,
            )
        }
    }
}
