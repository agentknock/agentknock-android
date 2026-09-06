package dev.agentknock

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import dev.agentknock.network.AgentknockUserAgentInterceptor
import dev.agentknock.push.PushRegistrationRepository
import dev.agentknock.push.FirebaseRegistrationWorker
import dev.agentknock.push.RequestNotifications
import dev.agentknock.push.PushSynchronizationWorker
import dev.agentknock.push.RequestNotificationCoordinator
import dev.agentknock.storage.AgentknockDatabase
import dev.agentknock.storage.RoomWriteTransaction
import dev.agentknock.storage.audit.AuditRepository
import dev.agentknock.storage.crypto.AesGcmEncryption
import dev.agentknock.storage.crypto.AndroidEncryptionKeyStore
import dev.agentknock.storage.crypto.VaultKeyManager
import dev.agentknock.storage.secret.SecretRepository
import dev.agentknock.storage.secret.SecretSummary
import dev.agentknock.relay.HttpRelayClaimClient
import dev.agentknock.relay.HttpRelayApprovalReviewClient
import dev.agentknock.relay.HttpRelayPushRegistrationClient
import dev.agentknock.relay.HttpRelaySubscriptionClient
import dev.agentknock.relay.HttpRelayDeviceManagementClient
import dev.agentknock.relay.RelayHttpTransport
import dev.agentknock.relay.WebSocketRelayDeviceClient
import dev.agentknock.storage.request.RequestRepository
import dev.agentknock.storage.request.RequestInbox
import dev.agentknock.storage.request.RequestConnectionManager
import dev.agentknock.storage.request.RequestMaterialStore
import dev.agentknock.storage.request.persistentRelayRetryDeadline
import dev.agentknock.storage.request.AiReviewCoordinator
import dev.agentknock.storage.request.ClientRemovalRequests
import dev.agentknock.storage.request.ClientRepository
import dev.agentknock.storage.request.ClientSummary
import dev.agentknock.storage.request.GitSigningRequests
import dev.agentknock.storage.request.InboxRequestSummary
import dev.agentknock.storage.request.InvocationRequests
import dev.agentknock.storage.request.PairingRequests
import dev.agentknock.storage.request.SecretManagementRequests
import dev.agentknock.storage.request.SshAuthenticationRequests
import dev.agentknock.storage.device.DeviceIdentityRepository
import dev.agentknock.storage.device.DeviceManagementRepository
import dev.agentknock.storage.device.DeviceSettingsCoordinator
import dev.agentknock.protocol.PairingProtocol
import dev.agentknock.subscription.GooglePlaySubscriptionBilling
import dev.agentknock.subscription.SubscriptionRepository
import dev.agentknock.ui.auth.AuthenticationSession
import dev.agentknock.ui.auth.DeviceAuthenticationCoordinator
import dev.agentknock.ui.auth.ProtectedActionAuthorizer
import dev.agentknock.ui.auth.SensitiveDataBackgroundGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class AgentknockApplication : Application() {
    internal lateinit var container: ApplicationContainer
        private set

    override fun onCreate() {
        super.onCreate()
        RequestNotifications.createChannel(this)
        val createdContainer = ApplicationContainer(this)
        container = createdContainer
        ProcessLifecycleOwner.get().lifecycle.addObserver(createdContainer.requestConnection)
        createdContainer.start()
    }
}

internal class ApplicationContainer(private val application: Application) {
    val authentication = AuthenticationSession(application)
    val deviceAuthentication = DeviceAuthenticationCoordinator()
    val protectedActions = ProtectedActionAuthorizer(authentication, deviceAuthentication)
    val sensitiveDataBackgroundGuard = SensitiveDataBackgroundGuard()
    private val database = AgentknockDatabase.create(application)
    private val writeTransaction = RoomWriteTransaction(database)
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
    private var scheduleRequestSynchronization: () -> Unit

    @Volatile
    var factoryResetInProgress = false
        private set

    val audit = AuditRepository(database.auditDao())

    val secrets = SecretRepository(
        dao = database.secretDao(),
        keyManager = vaultKeyManager,
        encryption = encryption,
        audit = audit,
        writeTransaction = writeTransaction,
    )

    val deviceIdentity = DeviceIdentityRepository(
        dao = database.deviceIdentityDao(),
        keyManager = vaultKeyManager,
        encryption = encryption,
        relay = HttpRelayClaimClient(relayHttp),
        audit = audit,
        writeTransaction = writeTransaction,
    )
    val deviceConfiguration = deviceIdentity.observeConfiguration().stateIn(
        scope = applicationScope,
        started = SharingStarted.Eagerly,
        initialValue = null,
    )

    val pushRegistration = PushRegistrationRepository(
        deviceAuthorization = deviceIdentity,
        relay = HttpRelayPushRegistrationClient(relayHttp),
        requestRegistration = { FirebaseRegistrationWorker.enqueue(application) },
    )

    val subscription = SubscriptionRepository(
        deviceAuthorization = deviceIdentity,
        relay = HttpRelaySubscriptionClient(relayHttp),
    )
    val playSubscriptionBilling = GooglePlaySubscriptionBilling(application)
    val deviceManagement = DeviceManagementRepository(
        deviceIdentityDao = database.deviceIdentityDao(),
        deviceAuthorization = deviceIdentity,
        relay = HttpRelayDeviceManagementClient(relayHttp),
        audit = audit,
        writeTransaction = writeTransaction,
    )
    private val aiReviews = AiReviewCoordinator(applicationScope)
    private val requestMaterial = RequestMaterialStore(
        dao = database.requestDao(),
        keyManager = vaultKeyManager,
        encryption = encryption,
    )
    private val pairingRequests = PairingRequests(
        dao = database.requestDao(),
        material = requestMaterial,
        audit = audit,
        writeTransaction = writeTransaction,
        pairingProtocol = PairingProtocol(),
        json = Json,
        currentTimeMillis = System::currentTimeMillis,
    )
    private val clientRemovalRequests = ClientRemovalRequests(
        dao = database.requestDao(),
        audit = audit,
        writeTransaction = writeTransaction,
        json = Json,
        currentTimeMillis = System::currentTimeMillis,
    )

    val requestInbox = RequestInbox(database.requestDao())
    val requestSummaries: StateFlow<List<InboxRequestSummary>> = requestInbox.observeRequests()
        .stateIn(
            scope = applicationScope,
            started = SharingStarted.WhileSubscribed(READ_MODEL_STOP_TIMEOUT_MILLIS),
            initialValue = emptyList(),
        )
    private val clients = ClientRepository(
        dao = database.requestDao(),
        temporaryAccessGrants = secrets.observeTemporaryAccessGrants(),
        audit = audit,
        writeTransaction = writeTransaction,
    )
    val clientSummaries: StateFlow<List<ClientSummary>> = clients.observeClients().stateIn(
        scope = applicationScope,
        started = SharingStarted.WhileSubscribed(READ_MODEL_STOP_TIMEOUT_MILLIS),
        initialValue = emptyList(),
    )
    val secretSummaries: StateFlow<List<SecretSummary>> = secrets.observeSecrets().stateIn(
        scope = applicationScope,
        started = SharingStarted.WhileSubscribed(READ_MODEL_STOP_TIMEOUT_MILLIS),
        initialValue = emptyList(),
    )
    private val secretManagement = SecretManagementRequests(
        dao = database.requestDao(),
        material = requestMaterial,
        secrets = secrets,
        audit = audit,
        writeTransaction = writeTransaction,
    )
    private val approvalReviewer = HttpRelayApprovalReviewClient(
        RelayHttpTransport(approvalReviewHttpClient(httpClient)),
    )
    private val invocationRequests = InvocationRequests(
        dao = database.requestDao(),
        secrets = secrets,
        deviceCredentials = deviceIdentity,
        approvalReviewer = approvalReviewer,
        subscription = subscription,
        audit = audit,
        writeTransaction = writeTransaction,
    )
    private val gitSigningRequests = GitSigningRequests(
        dao = database.requestDao(),
        secrets = secrets,
        deviceCredentials = deviceIdentity,
        approvalReviewer = approvalReviewer,
        subscription = subscription,
        audit = audit,
        writeTransaction = writeTransaction,
    )
    private val sshAuthenticationRequests = SshAuthenticationRequests(
        dao = database.requestDao(),
        secrets = secrets,
        deviceCredentials = deviceIdentity,
        approvalReviewer = approvalReviewer,
        subscription = subscription,
        audit = audit,
        writeTransaction = writeTransaction,
    )

    val requests: RequestRepository = RequestRepository(
        dao = database.requestDao(),
        material = requestMaterial,
        deviceCredentials = deviceIdentity,
        clients = clients,
        secretManagement = secretManagement,
        invocationRequests = invocationRequests,
        gitSigningRequests = gitSigningRequests,
        sshAuthenticationRequests = sshAuthenticationRequests,
        pairingRequests = pairingRequests,
        clientRemovalRequests = clientRemovalRequests,
        relay = WebSocketRelayDeviceClient(httpClient),
        aiReviews = aiReviews,
        scheduleSynchronization = { scheduleRequestSynchronization() },
        audit = audit,
        writeTransaction = writeTransaction,
        updatePushRegistrationState = pushRegistration::updateRelayState,
    )

    val requestNotifications = RequestNotificationCoordinator(
        scope = applicationScope,
        requests = requestInbox.observePendingNotifications(),
        displayRequests = { RequestNotifications.showRequests(application, it) },
        displayWake = { RequestNotifications.showWake(application) },
    )

    // Relay synchronization and request decisions await this initialization. Recovering
    // REVIEWING here is race-free: no relay synchronization can begin before storage is ready,
    // and process-local review jobs do not survive application creation.
    val localStorage = applicationScope.async(start = CoroutineStart.LAZY) {
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
        audit.pruneExpired()
        database.requestDao().discardDecidedSecretUploadValues()
        database.requestDao().deleteEndedRequestPsks()
        requestMaterial.deleteExpiredPreviousClientPsks()
        requests.recoverInterruptedAiReviews()
        requests.pruneExpiredRequestState()
        deviceIdentity.pruneRetiredIdentities()
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
            requests.listen {
                onCaughtUp()
                requestNotifications.reconcile()
            }
        },
        scheduleBackgroundSynchronization = {
            PushSynchronizationWorker.enqueue(application)
        },
        relayRetryDeadline = persistentRelayRetryDeadline(application),
    )

    val deviceSettings = DeviceSettingsCoordinator(
        identities = deviceIdentity,
        management = deviceManagement,
        awaitStorageReady = { localStorage.await() },
        onIdentityChanged = {
            requestConnection.refresh()
            requestConnection.requestSynchronization()
        },
    )

    val actions = AgentknockActions(
        context = application,
        authorize = protectedActions::authorize,
        requests = requests,
        secrets = secrets,
        awaitStorageReady = localStorage::await,
    )

    init {
        scheduleRequestSynchronization = requestConnection::requestSynchronization
    }

    fun start() {
        check(!started) { "The application container was already started" }
        started = true
        localStorage.start()
    }

    suspend fun beginFactoryReset(): Boolean {
        factoryResetInProgress = true
        try {
            requestConnection.pauseAndJoin()
            return deviceManagement.deleteRemoteDevice()
        } catch (cancelled: CancellationException) {
            cancelFactoryReset()
            throw cancelled
        }
    }

    fun cancelFactoryReset() {
        if (!factoryResetInProgress) return
        factoryResetInProgress = false
        requestConnection.resume()
    }

    private var started = false

    private companion object {
        const val READ_MODEL_STOP_TIMEOUT_MILLIS = 5_000L
    }
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
