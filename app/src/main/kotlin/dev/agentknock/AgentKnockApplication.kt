package dev.agentknock

import android.app.Application
import dev.agentknock.storage.AgentKnockDatabase
import dev.agentknock.storage.crypto.AesGcmEncryption
import dev.agentknock.storage.crypto.AndroidEncryptionKeyStore
import dev.agentknock.storage.crypto.LocalEncryptionKeyManager
import dev.agentknock.storage.profile.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

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

    val profiles = ProfileRepository(
        dao = database.profileDao(),
        keyManager = encryptionKeyManager,
        encryption = AesGcmEncryption(encryptionKeyStore),
    )

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Every future worker and messaging entry point must await this before using local state.
    val localStorage = applicationScope.async(start = CoroutineStart.DEFAULT) {
        encryptionKeyManager.initialize()
    }
}
