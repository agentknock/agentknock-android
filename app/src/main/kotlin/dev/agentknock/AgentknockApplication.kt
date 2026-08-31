package dev.agentknock

import android.app.Application
import android.util.Log
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.messaging.FirebaseMessaging
import dev.agentknock.network.AgentknockUserAgentInterceptor
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
import dev.agentknock.relay.HttpRelayApprovalReviewClient
import dev.agentknock.relay.HttpRelayPushRegistrationClient
import dev.agentknock.relay.HttpRelaySubscriptionClient
import dev.agentknock.relay.HttpRelayDeviceManagementClient
import dev.agentknock.relay.RelayHttpTransport
import dev.agentknock.relay.WebSocketRelayDeviceClient
import dev.agentknock.storage.request.RequestRepository
import dev.agentknock.storage.request.RequestConnectionManager
import dev.agentknock.storage.device.DeviceIdentityRepository
import dev.agentknock.storage.device.DeviceManagementRepository
import dev.agentknock.subscription.SubscriptionRepository
import dev.agentknock.ui.auth.AuthenticationSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
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
    val authentication = AuthenticationSession(application)
    private val database = AgentknockDatabase.create(application)
    private val encryptionKeyStore = AndroidEncryptionKeyStore(application.packageManager)
    val vaultKeyManager = VaultKeyManager(
        dao = database.vaultKeyDao(),
        keyStore = encryptionKeyStore,
    )
    private val encryption = AesGcmEncryption(encryptionKeyStore)
    private val httpClient = OkHttpClient.Builder()
        .addInterceptor(
            AgentknockUserAgentInterceptor(
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
            ),
        )
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val relayHttp = RelayHttpTransport(httpClient)

    // Every future worker and messaging entry point must await this before using local state.
    val localStorage = applicationScope.async(start = CoroutineStart.DEFAULT) {
        vaultKeyManager.initialize()
        val temporaryAccessMarker = application.noBackupFilesDir.resolve(
            "temporary_access_initialized_v1",
        )
        initializeTemporaryAccessStorage(
            marker = temporaryAccessMarker,
            now = System.currentTimeMillis(),
            clearAll = { database.secretDao().deleteAllTemporaryAccessGrants() },
            clearExpired = { now ->
                database.secretDao().deleteExpiredTemporaryAccessGrants(now)
            },
        )
        database.requestDao().discardDecidedSecretUploadValues()
    }

    val audit = AuditRepository(database.auditDao())

    val secrets = SecretRepository(
        dao = database.secretDao(),
        keyManager = vaultKeyManager,
        encryption = encryption,
        audit = audit,
    )

    val deviceIdentity = DeviceIdentityRepository(
        dao = database.deviceIdentityDao(),
        keyManager = vaultKeyManager,
        encryption = encryption,
        relay = HttpRelayClaimClient(relayHttp),
        audit = audit,
    )

    val pushRegistration = PushRegistrationRepository(
        deviceCredentials = deviceIdentity,
        relay = HttpRelayPushRegistrationClient(relayHttp),
    )

    val subscription = SubscriptionRepository(
        deviceCredentials = deviceIdentity,
        relay = HttpRelaySubscriptionClient(relayHttp),
    )

    val deviceManagement = DeviceManagementRepository(
        deviceIdentityDao = database.deviceIdentityDao(),
        credentials = deviceIdentity,
        relay = HttpRelayDeviceManagementClient(relayHttp),
        audit = audit,
    )

    val requests = RequestRepository(
        dao = database.requestDao(),
        deviceCredentials = deviceIdentity,
        secrets = secrets,
        approvalReviewer = HttpRelayApprovalReviewClient(
            RelayHttpTransport(approvalReviewHttpClient(httpClient)),
        ),
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

internal fun approvalReviewHttpClient(base: OkHttpClient): OkHttpClient = base.newBuilder()
    // AI review is billable and not idempotent. A durable request coordinator decides whether
    // one logical review was attempted; OkHttp must not silently repeat it.
    .retryOnConnectionFailure(false)
    .readTimeout(45, TimeUnit.SECONDS)
    .callTimeout(60, TimeUnit.SECONDS)
    .build()

internal suspend fun initializeTemporaryAccessStorage(
    marker: File,
    now: Long,
    clearAll: suspend () -> Unit,
    clearExpired: suspend (Long) -> Unit,
) {
    if (!marker.exists()) {
        clearAll()
        check(marker.createNewFile() || marker.exists())
    }
    clearExpired(now)
}
