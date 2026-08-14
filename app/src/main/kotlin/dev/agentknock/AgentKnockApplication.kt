package dev.agentknock

import android.app.Application
import dev.agentknock.storage.AgentKnockDatabase
import dev.agentknock.storage.crypto.AesGcmEncryption
import dev.agentknock.storage.crypto.AndroidEncryptionKeyStore
import dev.agentknock.storage.crypto.LocalEncryptionKeyManager
import dev.agentknock.storage.profile.ProfileRepository
import dev.agentknock.relay.HttpRelayClaimClient
import dev.agentknock.relay.HttpRelayInboxClient
import dev.agentknock.storage.request.RequestRepository
import dev.agentknock.storage.vault.VaultRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import okhttp3.OkHttpClient

class AgentKnockApplication : Application() {
    internal lateinit var container: ApplicationContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = ApplicationContainer(this)
    }
}

internal class ApplicationContainer(application: Application) {
    private val database = AgentKnockDatabase.create(application)
    private val encryptionKeyStore = AndroidEncryptionKeyStore(application.packageManager)
    private val encryptionKeyManager = LocalEncryptionKeyManager(
        dao = database.localEncryptionDao(),
        keyStore = encryptionKeyStore,
    )
    private val encryption = AesGcmEncryption(encryptionKeyStore)
    private val httpClient = OkHttpClient()

    val profiles = ProfileRepository(
        dao = database.profileDao(),
        keyManager = encryptionKeyManager,
        encryption = encryption,
    )

    val vault = VaultRepository(
        dao = database.vaultDao(),
        keyManager = encryptionKeyManager,
        encryption = encryption,
        relay = HttpRelayClaimClient(httpClient),
    )

    val requests = RequestRepository(
        dao = database.requestDao(),
        vault = vault,
        profiles = profiles,
        relay = HttpRelayInboxClient(httpClient),
        keyManager = encryptionKeyManager,
        encryption = encryption,
    )

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Every future worker and messaging entry point must await this before using local state.
    val localStorage = applicationScope.async(start = CoroutineStart.DEFAULT) {
        encryptionKeyManager.initialize()
    }
}
