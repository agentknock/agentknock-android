package dev.agentknock

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import dev.agentknock.storage.AgentKnockDatabase
import dev.agentknock.storage.crypto.AesGcmEncryption
import dev.agentknock.storage.crypto.AndroidEncryptionKeyStore
import dev.agentknock.storage.crypto.LocalEncryptionKeyManager
import dev.agentknock.storage.profile.ProfileRepository
import dev.agentknock.relay.HttpRelayClaimClient
import dev.agentknock.relay.WebSocketRelayDeviceClient
import dev.agentknock.storage.request.RequestRepository
import dev.agentknock.storage.request.RequestConnectionManager
import dev.agentknock.storage.vault.VaultRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AgentKnockApplication : Application() {
    internal lateinit var container: ApplicationContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = ApplicationContainer(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(container.requestConnection)
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
    private val httpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Every future worker and messaging entry point must await this before using local state.
    val localStorage = applicationScope.async(start = CoroutineStart.DEFAULT) {
        encryptionKeyManager.initialize()
    }

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
        deviceCredentials = vault,
        profiles = profiles,
        relay = WebSocketRelayDeviceClient(httpClient),
        keyManager = encryptionKeyManager,
        encryption = encryption,
    )

    val requestConnection = RequestConnectionManager(
        scope = applicationScope,
        listen = { onCaughtUp ->
            localStorage.await()
            requests.listen(onCaughtUp)
        },
    )
}
