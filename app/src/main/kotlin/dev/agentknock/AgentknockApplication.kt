package dev.agentknock

import android.app.Application
import android.util.Log
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.messaging.FirebaseMessaging
import dev.agentknock.network.AgentknockUserAgentInterceptor
import dev.agentknock.push.PushRegistrationRepository
import dev.agentknock.push.RequestNotifications
import dev.agentknock.push.PushSynchronizationWorker
import dev.agentknock.push.RequestNotificationCoordinator
import dev.agentknock.storage.AgentknockDatabase
import dev.agentknock.storage.FactoryResetCoordinator
import dev.agentknock.storage.LocalStorageInitializer
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
import dev.agentknock.storage.request.AiReviewCoordinator
import dev.agentknock.storage.device.DeviceIdentityRepository
import dev.agentknock.storage.device.DeviceManagementRepository
import dev.agentknock.storage.device.DeviceOperationGate
import dev.agentknock.subscription.SubscriptionRepository
import dev.agentknock.ui.auth.AuthenticationSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class AgentknockApplication : Application() {
    internal lateinit var container: ApplicationContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val createdContainer = ApplicationContainer(this)
        container = createdContainer
        RequestNotifications.createChannel(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(createdContainer.requestConnection)
        createdContainer.start()
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
    private val deviceOperations = DeviceOperationGate()
    private var scheduleRequestSynchronization: () -> Unit

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
        deviceOperations = deviceOperations,
    )

    val pushRegistration = PushRegistrationRepository(
        deviceAuthorization = deviceIdentity,
        relay = HttpRelayPushRegistrationClient(relayHttp),
    )

    val subscription = SubscriptionRepository(
        deviceAuthorization = deviceIdentity,
        relay = HttpRelaySubscriptionClient(relayHttp),
    )

    val deviceManagement = DeviceManagementRepository(
        deviceIdentityDao = database.deviceIdentityDao(),
        deviceAuthorization = deviceIdentity,
        relay = HttpRelayDeviceManagementClient(relayHttp),
        audit = audit,
        deviceOperations = deviceOperations,
    )

    private val aiReviews = AiReviewCoordinator(applicationScope)

    val requests: RequestRepository = RequestRepository(
        database = database,
        dao = database.requestDao(),
        deviceCredentials = deviceIdentity,
        secrets = secrets,
        approvalReviewer = HttpRelayApprovalReviewClient(
            RelayHttpTransport(approvalReviewHttpClient(httpClient)),
        ),
        relay = WebSocketRelayDeviceClient(httpClient),
        keyManager = vaultKeyManager,
        encryption = encryption,
        aiReviews = aiReviews,
        scheduleSynchronization = { scheduleRequestSynchronization() },
        audit = audit,
        requestPushRegistration = {
            FirebaseMessaging.getInstance().register().addOnFailureListener { failure ->
                Log.w("Agentknock", "FCM registration failed", failure)
            }
        },
    )

    val requestNotifications = RequestNotificationCoordinator(
        scope = applicationScope,
        currentRequests = requests::pendingNotifications,
        displayRequests = { RequestNotifications.showRequests(application, it) },
        displayWake = { RequestNotifications.showWake(application) },
        clear = { RequestNotifications.clear(application) },
    )

    // Every worker, UI mutation, and connection entry point awaits this same initialization.
    // Recovering REVIEWING here is race-free: no relay synchronization can begin before storage
    // is ready, and process-local review jobs do not survive application creation.
    val localStorage = LocalStorageInitializer(applicationScope) {
        initializeLocalStorage(application)
    }

    private suspend fun initializeLocalStorage(application: Application) {
        vaultKeyManager.initialize()
        initializeTemporaryAccessStorage(
            marker = application.noBackupFilesDir.resolve("temporary_access_initialized_v1"),
            now = System.currentTimeMillis(),
            clearAll = { database.secretDao().deleteAllTemporaryAccessGrants() },
            clearExpired = { now -> database.secretDao().deleteExpiredTemporaryAccessGrants(now) },
        )
        database.requestDao().discardDecidedSecretUploadValues()
        requests.recoverInterruptedAiReviews()
        if (requests.hasPendingRelayWork()) {
            requestConnection.requestSynchronization()
        }
    }

    val requestConnection: RequestConnectionManager = RequestConnectionManager(
        scope = applicationScope,
        synchronizeOnce = {
            localStorage.await()
            requests.sync()
        },
        listen = { onCaughtUp ->
            localStorage.await()
            requests.listen(
                onCaughtUp = {
                    onCaughtUp()
                    applicationScope.launch {
                        requestNotifications.refresh()
                    }
                },
                onInboxChanged = {
                    requestNotifications.refresh()
                },
            )
        },
        scheduleBackgroundSynchronization = {
            PushSynchronizationWorker.enqueue(application)
        },
    )

    init {
        scheduleRequestSynchronization = requestConnection::requestSynchronization
    }

    fun start() {
        check(!started) { "The application container was already started" }
        started = true
        localStorage.start()
        applicationScope.launch {
            requests.inboxChanges.collect {
                requestNotifications.refresh()
            }
        }
    }

    val factoryReset = FactoryResetCoordinator(
        deleteRemoteDevice = deviceManagement::deleteRemoteDevice,
        awaitReady = localStorage::await,
        awaitReadyForRecovery = localStorage::awaitSettled,
        pauseRuntime = {
            requestNotifications.pauseAndClear()
            requestConnection.pauseAndJoin()
            aiReviews.pauseAndCancel()
        },
        recoverInterruptedWork = { requests.recoverInterruptedAiReviews() },
        clearLocalState = { markIrreversiblyCleared ->
            var roomCleared = false
            try {
                requests.resetRuntimeState {
                    try {
                        vaultKeyManager.erase(
                            clearData = {
                                database.resetDao().clearAllData()
                                roomCleared = true
                                markIrreversiblyCleared()
                            },
                            afterKeyDeletion = {
                                // Room reclaims deleted pages before fresh vault metadata exists.
                                database.clearAllTables()
                            },
                        )
                    } catch (failure: Throwable) {
                        if (!roomCleared) throw failure
                        Log.e("Agentknock", "Post-reset cleanup was incomplete", failure)
                    }
                }
            } finally {
                if (roomCleared) {
                    requestConnection.resetRuntimeState()
                    authentication.reset()
                }
            }
        },
        resumeRuntime = { localStateCleared ->
            if (localStateCleared) localStorage.restart()
            requestNotifications.resume(refresh = !localStateCleared)
            aiReviews.resume()
            requestConnection.resume()
        },
        deviceOperations = deviceOperations,
    )

    private var started = false
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
