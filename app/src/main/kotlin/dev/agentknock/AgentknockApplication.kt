package dev.agentknock

import android.app.Application
import android.util.Log
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.messaging.FirebaseMessaging
import dev.agentknock.push.PushRegistrationRepository
import dev.agentknock.push.RequestNotifications
import dev.agentknock.storage.AgentknockDatabase
import dev.agentknock.storage.FactoryResetRepository
import dev.agentknock.storage.audit.AuditRepository
import dev.agentknock.storage.crypto.AesGcmEncryption
import dev.agentknock.storage.crypto.AndroidEncryptionKeyStore
import dev.agentknock.storage.crypto.VaultKeyManager
import dev.agentknock.storage.secret.SecretRepository
import dev.agentknock.relay.HttpRelayClaimClient
import dev.agentknock.relay.HttpRelayPushRegistrationClient
import dev.agentknock.relay.HttpRelayDeviceManagementClient
import dev.agentknock.relay.WebSocketRelayDeviceClient
import dev.agentknock.storage.request.RequestRepository
import dev.agentknock.storage.request.RequestConnectionManager
import dev.agentknock.storage.rule.ApprovalRuleRepository
import dev.agentknock.storage.vault.VaultRepository
import dev.agentknock.storage.vault.DeviceManagementRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AgentknockApplication : Application() {
    internal lateinit var container: ApplicationContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = ApplicationContainer(this)
        RequestNotifications.createChannel(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(container.requestConnection)
    }
}

internal class ApplicationContainer(application: Application) {
    private val database = AgentknockDatabase.create(application)
    private val encryptionKeyStore = AndroidEncryptionKeyStore(application.packageManager)
    val vaultKeyManager = VaultKeyManager(
        dao = database.vaultKeyDao(),
        keyStore = encryptionKeyStore,
    )
    private val encryption = AesGcmEncryption(encryptionKeyStore)
    private val httpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Every future worker and messaging entry point must await this before using local state.
    val localStorage = applicationScope.async(start = CoroutineStart.DEFAULT) {
        vaultKeyManager.initialize()
        database.requestDao().discardDecidedSecretUploadValues()
    }

    val audit = AuditRepository(database.auditDao())

    val secrets = SecretRepository(
        dao = database.secretDao(),
        keyManager = vaultKeyManager,
        encryption = encryption,
        audit = audit,
    )

    val vault = VaultRepository(
        dao = database.vaultDao(),
        keyManager = vaultKeyManager,
        encryption = encryption,
        relay = HttpRelayClaimClient(httpClient),
        audit = audit,
    )

    val pushRegistration = PushRegistrationRepository(
        deviceCredentials = vault,
        relay = HttpRelayPushRegistrationClient(httpClient),
    )

    val deviceManagement = DeviceManagementRepository(
        vaultDao = database.vaultDao(),
        credentials = vault,
        relay = HttpRelayDeviceManagementClient(httpClient),
        audit = audit,
    )

    val approvalRules = ApprovalRuleRepository(
        dao = database.approvalRuleDao(),
        audit = audit,
    )

    val requests = RequestRepository(
        dao = database.requestDao(),
        deviceCredentials = vault,
        secrets = secrets,
        approvalRules = approvalRules,
        relay = WebSocketRelayDeviceClient(httpClient),
        keyManager = vaultKeyManager,
        encryption = encryption,
        audit = audit,
        requestPushRegistration = {
            FirebaseMessaging.getInstance().register().addOnFailureListener { failure ->
                Log.w("Agentknock", "FCM registration failed", failure)
            }
        },
    )

    val requestConnection = RequestConnectionManager(
        scope = applicationScope,
        listen = { onCaughtUp ->
            localStorage.await()
            requests.listen(
                onCaughtUp = {
                    onCaughtUp()
                    applicationScope.launch {
                        RequestNotifications.showRequests(
                            application,
                            requests.pendingNotifications(),
                        )
                    }
                },
                onInboxChanged = {
                    RequestNotifications.showRequests(
                        application,
                        requests.pendingNotifications(),
                    )
                },
            )
        },
    )

    val factoryReset = FactoryResetRepository(
        database = database,
        encryptionKeys = vaultKeyManager,
        deviceManagement = deviceManagement,
    )
}
