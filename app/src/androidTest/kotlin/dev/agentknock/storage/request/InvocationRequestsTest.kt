package dev.agentknock.storage.request

import androidx.room3.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.agentknock.protocol.InvocationDenialReason
import dev.agentknock.protocol.InvocationProtocol
import dev.agentknock.protocol.InvocationRequestMessage
import dev.agentknock.relay.RelayApprovalReviewClient
import dev.agentknock.relay.RelayApprovalReviewResult
import dev.agentknock.review.ApprovalReviewRequest
import dev.agentknock.storage.AgentknockDatabase
import dev.agentknock.storage.RoomWriteTransaction
import dev.agentknock.storage.approval.ApprovalAction
import dev.agentknock.storage.approval.ApprovalEvaluation
import dev.agentknock.storage.approval.SecretApprovalEvaluation
import dev.agentknock.storage.audit.AuditDecisionSource
import dev.agentknock.storage.audit.AuditEventType
import dev.agentknock.storage.audit.AuditOutcome
import dev.agentknock.storage.audit.AuditRecord
import dev.agentknock.storage.audit.AuditRepository
import dev.agentknock.storage.audit.AuditSink
import dev.agentknock.storage.crypto.AesGcmEncryption
import dev.agentknock.storage.crypto.EncryptedValue
import dev.agentknock.storage.crypto.EncryptionKeyBacking
import dev.agentknock.storage.crypto.EncryptionKeyStore
import dev.agentknock.storage.crypto.GeneratedEncryptionKey
import dev.agentknock.storage.crypto.VaultKeyEntity
import dev.agentknock.storage.crypto.VaultKeyManager
import dev.agentknock.storage.crypto.VaultKeyPurpose
import dev.agentknock.storage.device.DeviceIdentityEntity
import dev.agentknock.storage.device.RelayDeviceCredentialSource
import dev.agentknock.storage.device.RelayDeviceCredentialsResult
import dev.agentknock.storage.secret.CreateEnvironmentVariableResult
import dev.agentknock.storage.secret.CreateSecretResult
import dev.agentknock.storage.secret.SaveSecretResult
import dev.agentknock.storage.secret.SecretApprovalMode
import dev.agentknock.storage.secret.SecretEntity
import dev.agentknock.storage.secret.SecretRepository
import dev.agentknock.storage.secret.TemporaryAccessOperation
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InvocationRequestsTest {
    private lateinit var database: AgentknockDatabase
    private lateinit var audit: AuditRepository
    private lateinit var secrets: SecretRepository

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AgentknockDatabase::class.java,
        ).build()
        val keyStore = MemoryEncryptionKeyStore()
        keyStore.generate(KEY_ID)
        database.vaultKeyDao().activate(
            VaultKeyEntity(
                id = KEY_ID,
                purpose = VaultKeyPurpose.DEVICE_STATE.storedName,
                active = true,
                createdAt = 1,
                backing = EncryptionKeyBacking.SOFTWARE.storedName,
            ),
        )
        database.deviceIdentityDao().insertIdentity(
            DeviceIdentityEntity(
                id = DEVICE_IDENTITY_ID,
                role = "active",
                address = "quiet-river-maple",
                deviceId = DEVICE_ID,
                createdAt = 1,
            ),
        )
        database.requestDao().insertClient(client())
        val keyManager = VaultKeyManager(
            dao = database.vaultKeyDao(),
            keyStore = keyStore,
            newKeyId = { "unused-key" },
            currentTimeMillis = { NOW },
            keyStoreDispatcher = Dispatchers.Unconfined,
        )
        audit = AuditRepository(database.auditDao(), currentTimeMillis = { NOW })
        secrets = SecretRepository(
            dao = database.secretDao(),
            keyManager = keyManager,
            encryption = AesGcmEncryption(keyStore),
            audit = audit,
            writeTransaction = RoomWriteTransaction(database),
            newId = { "secret-id" },
            currentTimeMillis = { NOW },
            cryptographyDispatcher = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun receiveAndAuditRollBackTogetherAndCanBeRetried() = runTest {
        val requestId = "invocation-receive"
        val failing = requests(InsertThenFailAuditSink(audit))

        assertTrue(
            runCatching {
                failing.receive(
                    request = request(requestId),
                    secretUseRequest = secretUse(requestId),
                    client = client(),
                    acceptedPsks = acceptedPsks(requestId),
                    authorization = null,
                    automaticDecisionAudit = null,
                )
            }.isFailure,
        )
        assertNull(database.requestDao().getRequestById(requestId))
        assertNull(database.requestDao().getSecretUseRequest(requestId))
        assertNull(database.requestDao().getRequestPsk(requestId))
        assertTrue(audit.observeEvents().first().isEmpty())

        assertEquals(
            ConditionalRequestUpdate.APPLIED,
            requests(audit).receive(
                request = request(requestId),
                secretUseRequest = secretUse(requestId),
                client = client(),
                acceptedPsks = acceptedPsks(requestId),
                authorization = null,
                automaticDecisionAudit = null,
            ),
        )
        assertNotNull(database.requestDao().getRequestById(requestId))
        assertNotNull(database.requestDao().getSecretUseRequest(requestId))
        assertNotNull(database.requestDao().getRequestPsk(requestId))
        assertEquals(
            listOf(AuditEventType.SECRET_USE_RECEIVED),
            audit.observeEvents().first().map { it.type },
        )
    }

    @Test
    fun manualDenialAndAuditRollBackTogetherAndCanBeRetried() = runTest {
        val requestId = "invocation-manual-denial"
        val regular = requests(audit)
        assertEquals(
            ConditionalRequestUpdate.APPLIED,
            regular.receive(
                request = request(requestId),
                secretUseRequest = secretUse(requestId),
                client = client(),
                acceptedPsks = acceptedPsks(requestId),
                authorization = null,
                automaticDecisionAudit = null,
            ),
        )
        val auditCount = audit.observeEvents().first().size
        val seal: suspend (InboxRequestEntity, ByteArray) -> JsonElement? =
            { _, _ -> Json.parseToJsonElement(RESPONSE_JSON) }

        assertTrue(
            runCatching {
                requests(InsertThenFailAuditSink(audit)).deny(requestId, seal)
            }.isFailure,
        )
        val rolledBackRequest = checkNotNull(database.requestDao().getRequestById(requestId))
        val rolledBackInvocation = checkNotNull(
            database.requestDao().getSecretUseRequest(requestId),
        )
        assertEquals(InboxRequestState.ACTION_REQUIRED.storedName, rolledBackRequest.state)
        assertNull(rolledBackRequest.responseJson)
        assertNull(rolledBackInvocation.decision)
        assertEquals(auditCount, audit.observeEvents().first().size)

        assertEquals(RequestDecisionResult.Decided, regular.deny(requestId, seal))
        val decidedRequest = checkNotNull(database.requestDao().getRequestById(requestId))
        val decidedInvocation = checkNotNull(database.requestDao().getSecretUseRequest(requestId))
        assertEquals(InboxRequestState.WAITING.storedName, decidedRequest.state)
        assertEquals(RESPONSE_JSON, decidedRequest.responseJson)
        assertEquals(ApprovalDecision.DENIED.storedName, decidedInvocation.decision)
        assertEquals(InvocationDenialReason.USER_DENIED.wireName, decidedInvocation.completionReason)
        assertEquals(SECRET_USE_DENIAL_MESSAGE, decidedInvocation.completionMessage)
        assertEquals(auditCount + 1, audit.observeEvents().first().size)
    }

    @Test
    fun approvalTreatsMalformedStoredPlaintextAsClientUnavailable() = runTest {
        val requestId = "invocation-malformed-stored-plaintext"
        val regular = requests(audit)
        assertEquals(
            ConditionalRequestUpdate.APPLIED,
            regular.receive(
                request = request(requestId),
                secretUseRequest = secretUse(requestId),
                client = client(),
                acceptedPsks = acceptedPsks(requestId),
                authorization = null,
                automaticDecisionAudit = null,
            ),
        )
        var sealCalled = false

        assertEquals(
            RequestDecisionResult.ClientUnavailable,
            regular.approve(
                requestId = requestId,
                allowTemporaryAccess = false,
                openRequest = { "not an invocation".encodeToByteArray() },
                sealResponse = { _, _ ->
                    sealCalled = true
                    Json.parseToJsonElement(RESPONSE_JSON)
                },
            ),
        )
        assertFalse(sealCalled)
        assertNull(database.requestDao().getRequestById(requestId)?.responseJson)
        assertNull(database.requestDao().getSecretUseRequest(requestId)?.decision)
    }

    @Test
    fun temporaryGrantAuditsAndDecisionRollBackTogetherAndCanBeRetried() = runTest {
        val requestId = "invocation-temporary-grant"
        val pending = receivePendingEnvironmentInvocation(requestId)
        val regular = requests(audit)
        val auditCount = audit.observeEvents().first().size
        val open: suspend (InboxRequestEntity) -> ByteArray? = { pending.plaintext }
        val seal: suspend (InboxRequestEntity, ByteArray) -> JsonElement? =
            { _, _ -> Json.parseToJsonElement(RESPONSE_JSON) }

        assertTrue(
            runCatching {
                requests(InsertThenFailAuditSink(audit)).approve(
                    requestId = requestId,
                    allowTemporaryAccess = true,
                    openRequest = open,
                    sealResponse = seal,
                )
            }.isFailure,
        )
        val rolledBack = checkNotNull(database.requestDao().getSecretUseRequest(requestId))
        assertNull(rolledBack.decision)
        assertTrue(secrets.observeTemporaryAccessGrants().first().isEmpty())
        assertEquals(auditCount, audit.observeEvents().first().size)

        assertEquals(
            RequestDecisionResult.Decided,
            regular.approve(
                requestId = requestId,
                allowTemporaryAccess = true,
                openRequest = open,
                sealResponse = seal,
            ),
        )
        val decided = checkNotNull(database.requestDao().getSecretUseRequest(requestId))
        assertEquals(ApprovalDecision.APPROVED.storedName, decided.decision)
        assertEquals(DECISION_SOURCE_TEMPORARY_ACCESS, decided.decisionSource)
        assertNotNull(
            Json.decodeFromString<ApprovalEvaluation>(
                checkNotNull(decided.approvalEvaluationJson),
            ).secrets.single().temporaryAccessExpiresAt,
        )
        assertEquals(1, secrets.observeTemporaryAccessGrants().first().size)
        val appendedTypes = audit.observeEvents().first().take(2).map { it.type }.toSet()
        assertEquals(
            setOf(
                AuditEventType.TEMPORARY_ACCESS_ALLOWED,
                AuditEventType.SECRET_USE_DECIDED,
            ),
            appendedTypes,
        )
    }

    @Test
    fun automaticDecisionRacePersistsActionableRequestWithoutResponse() = runTest {
        val requestId = "invocation-authorization-race"
        val authorization = AuthorizationCommitment(
            secretRevisions = emptyMap(),
            policies = emptyMap(),
            expectedAbsentSecretNames = setOf("appeared"),
        )
        database.secretDao().insertSecret(secret("appeared"))

        assertEquals(
            ConditionalRequestUpdate.ACTION_REQUIRED,
            requests(audit).receive(
                request = request(requestId).copy(
                    state = InboxRequestState.WAITING.storedName,
                    responseJson = RESPONSE_JSON,
                ),
                secretUseRequest = secretUse(requestId).copy(
                    decision = ApprovalDecision.DENIED.storedName,
                    decisionSource = DECISION_SOURCE_POLICY,
                    completionReason = "INVALID_REQUEST",
                    completionMessage = "Missing secrets: appeared",
                    decidedAt = NOW,
                ),
                client = client(),
                acceptedPsks = acceptedPsks(requestId),
                authorization = authorization,
                automaticDecisionAudit = decisionAudit(requestId, AuditOutcome.REJECTED),
            ),
        )

        val storedRequest = checkNotNull(database.requestDao().getRequestById(requestId))
        val storedInvocation = checkNotNull(database.requestDao().getSecretUseRequest(requestId))
        assertEquals(InboxRequestState.ACTION_REQUIRED.storedName, storedRequest.state)
        assertNull(storedRequest.responseJson)
        assertNull(storedInvocation.decision)
        assertNull(storedInvocation.decisionSource)
        assertEquals(
            listOf(AuditEventType.SECRET_USE_RECEIVED),
            audit.observeEvents().first().map { it.type },
        )
    }

    @Test
    fun aiReviewMutationAndAuditRollBackAndAuthorizationRaceIsDeferred() = runTest {
        val requestId = "invocation-ai-result"
        val regular = requests(audit)
        assertEquals(
            ConditionalRequestUpdate.APPLIED,
            regular.receive(
                request = request(requestId).copy(state = InboxRequestState.REVIEWING.storedName),
                secretUseRequest = secretUse(requestId),
                client = client(),
                acceptedPsks = acceptedPsks(requestId),
                authorization = null,
                automaticDecisionAudit = null,
            ),
        )
        val reviewing = checkNotNull(database.requestDao().getRequestById(requestId))
        val invocation = checkNotNull(database.requestDao().getSecretUseRequest(requestId))
        val aiAudit = aiReviewAudit(requestId, AuditOutcome.APPROVED)
        val finalRequest = reviewing.copy(
            state = InboxRequestState.ACTION_REQUIRED.storedName,
            responseJson = null,
        )

        assertTrue(
            runCatching {
                requests(InsertThenFailAuditSink(audit)).finishAiReview(
                    request = finalRequest,
                    secretUseRequest = invocation,
                    authorization = authorizationCommitment(),
                    aiReviewAudit = aiAudit,
                    automaticDecisionAudit = null,
                )
            }.isFailure,
        )
        assertEquals(
            InboxRequestState.REVIEWING.storedName,
            database.requestDao().getRequestById(requestId)?.state,
        )
        assertEquals(1, audit.observeEvents().first().size)

        val staleAuthorization = AuthorizationCommitment(
            secretRevisions = emptyMap(),
            policies = emptyMap(),
            instructions = AuthorizationInstructionsCommitment(
                deviceIdentityId = DEVICE_IDENTITY_ID,
                deviceInstructions = "",
                clientId = CLIENT_ID,
                clientName = "Old client name",
                clientInstructions = "",
            ),
        )
        assertEquals(
            ConditionalRequestUpdate.ACTION_REQUIRED,
            regular.finishAiReview(
                request = finalRequest.copy(
                    state = InboxRequestState.WAITING.storedName,
                    responseJson = RESPONSE_JSON,
                ),
                secretUseRequest = invocation.copy(
                    approvalEvaluationJson = """{"stale":"ai-review"}""",
                ),
                authorization = staleAuthorization,
                aiReviewAudit = aiAudit,
                automaticDecisionAudit = null,
            ),
        )
        val stored = checkNotNull(database.requestDao().getRequestById(requestId))
        assertEquals(InboxRequestState.ACTION_REQUIRED.storedName, stored.state)
        assertNull(stored.responseJson)
        val storedInvocation = checkNotNull(database.requestDao().getSecretUseRequest(requestId))
        assertNull(storedInvocation.decision)
        assertNull(storedInvocation.approvalEvaluationJson)
        val events = audit.observeEvents().first()
        assertEquals(2, events.size)
        assertEquals(AuditEventType.SECRET_USE_AI_REVIEWED, events.first().type)
        assertEquals(AuditOutcome.DEFERRED, events.first().outcome)
    }

    @Test
    fun policyDenialCompletionAuditAndRequestKeyCommitOrRollBackTogether() = runTest {
        val requestId = "invocation-policy-completion"
        val regular = requests(audit)
        val pending = receivePendingEnvironmentInvocation(requestId)
        assertEquals(
            SaveSecretResult.SAVED,
            secrets.saveApprovalMode(pending.secretId, SecretApprovalMode.DENY),
        )
        assertEquals(
            RequestDecisionResult.DeniedByCurrentPolicy,
            regular.approve(
                requestId = requestId,
                allowTemporaryAccess = false,
                openRequest = { pending.plaintext },
                sealResponse = { _, _ -> Json.parseToJsonElement(RESPONSE_JSON) },
            ),
        )
        val denied = checkNotNull(database.requestDao().getSecretUseRequest(requestId))
        assertEquals(ApprovalDecision.DENIED.storedName, denied.decision)
        assertEquals(DECISION_SOURCE_POLICY, denied.decisionSource)
        assertEquals(InvocationDenialReason.POLICY_DENIED.wireName, denied.completionReason)
        assertEquals(SECRET_USE_POLICY_DENIAL_MESSAGE, denied.completionMessage)
        val request = checkNotNull(database.requestDao().getRequestById(requestId))
        val plaintext = deniedCompletionPlaintext(
            InvocationDenialReason.POLICY_DENIED.wireName,
            SECRET_USE_POLICY_DENIAL_MESSAGE,
        )
        val eventCount = audit.observeEvents().first().size

        assertTrue(
            runCatching {
                requests(InsertThenFailAuditSink(audit)).complete(request) {
                    CompletionOpenResult.Opened(plaintext)
                }
            }.isFailure,
        )
        assertNotNull(database.requestDao().getRequestPsk(requestId))
        assertEquals(eventCount, audit.observeEvents().first().size)

        assertTrue(
            regular.complete(request) { CompletionOpenResult.Opened(plaintext) },
        )
        val completed = checkNotNull(database.requestDao().getRequestById(requestId))
        assertNull(completed.error)
        assertEquals(
            ApprovalCompletionResult.DENIED.storedName,
            database.requestDao().getSecretUseRequest(requestId)?.completionResult,
        )
        assertNull(database.requestDao().getRequestPsk(requestId))
        val completedAudit = audit.observeEvents().first().first()
        assertEquals(AuditEventType.SECRET_USE_COMPLETED, completedAudit.type)
        assertEquals(AuditOutcome.DENIED, completedAudit.outcome)
        assertEquals(SECRET_USE_POLICY_DENIAL_MESSAGE, completedAudit.detail)

        val auditCountAfterCompletion = audit.observeEvents().first().size
        assertTrue(
            regular.complete(completed) {
                error("A completion replay must not be reopened")
            },
        )
        assertNull(database.requestDao().getRequestPsk(requestId))
        assertTrue(
            regular.complete(
                completed,
            ) {
                error("A conflicting terminal completion must not be reopened")
            },
        )
        assertEquals(auditCountAfterCompletion, audit.observeEvents().first().size)
    }

    @Test
    fun abortedCompletionDoesNotPutClientMessageInAudit() = runTest {
        val requestId = "invocation-aborted"
        val regular = requests(audit)
        assertEquals(
            ConditionalRequestUpdate.APPLIED,
            regular.receive(
                request = request(requestId),
                secretUseRequest = secretUse(requestId),
                client = client(),
                acceptedPsks = acceptedPsks(requestId),
                authorization = null,
                automaticDecisionAudit = null,
            ),
        )
        val request = checkNotNull(database.requestDao().getRequestById(requestId))
        val malicious = "raw-client-controlled-secret"

        assertTrue(
            regular.complete(
                request,
            ) { CompletionOpenResult.Opened(abortedCompletionPlaintext(malicious)) },
        )

        val completionAudit = audit.observeEvents().first().first()
        assertEquals(AuditEventType.SECRET_USE_COMPLETED, completionAudit.type)
        assertEquals(AuditOutcome.ABORTED, completionAudit.outcome)
        assertFalse(checkNotNull(completionAudit.detail).contains(malicious))
    }

    @Test
    fun invalidCompletionDoesNotRetainDecodedClientFields() = runTest {
        val requestId = "invocation-invalid-completion"
        val regular = requests(audit)
        assertEquals(
            ConditionalRequestUpdate.APPLIED,
            regular.receive(
                request = request(requestId),
                secretUseRequest = secretUse(requestId),
                client = client(),
                acceptedPsks = acceptedPsks(requestId),
                authorization = null,
                automaticDecisionAudit = null,
            ),
        )
        val request = checkNotNull(database.requestDao().getRequestById(requestId))
        val malicious = "raw-invalid-client-message"
        val wrongSoftwareCompletion =
            """{"app_info":{"name":"attacker","version":"1"},"lib_info":{"name":"attacker","version":"1"},"result":"ABORTED","reason":"CANCELLED","message":"$malicious"}"""
                .encodeToByteArray()

        assertTrue(
            regular.complete(
                request,
            ) { CompletionOpenResult.Opened(wrongSoftwareCompletion) },
        )

        val storedRequest = checkNotNull(database.requestDao().getRequestById(requestId))
        assertEquals("Secret use completion could not be verified.", storedRequest.error)
        val storedInvocation = checkNotNull(database.requestDao().getSecretUseRequest(requestId))
        assertNull(storedInvocation.completionResult)
        assertNull(storedInvocation.completionReason)
        assertNull(storedInvocation.completionMessage)
        val completionAudit = audit.observeEvents().first().first()
        assertEquals("Secret use completion could not be verified.", completionAudit.detail)
        assertFalse(checkNotNull(completionAudit.detail).contains(malicious))
    }

    @Test
    fun irrecoverablyInvalidCompletionEndsExchangeAndDeletesRequestPsk() = runTest {
        val requestId = "invocation-invalid-envelope"
        val regular = requests(audit)
        assertEquals(
            ConditionalRequestUpdate.APPLIED,
            regular.receive(
                request = request(requestId),
                secretUseRequest = secretUse(requestId),
                client = client(),
                acceptedPsks = acceptedPsks(requestId),
                authorization = null,
                automaticDecisionAudit = null,
            ),
        )
        val request = checkNotNull(database.requestDao().getRequestById(requestId))

        assertTrue(
            regular.complete(
                request,
            ) { CompletionOpenResult.IrrecoverablyInvalid },
        )

        val ended = checkNotNull(database.requestDao().getRequestById(requestId))
        assertNotNull(ended.exchangeEndedAt)
        assertEquals("Secret use completion could not be verified.", ended.error)
        assertNull(database.requestDao().getRequestPsk(requestId))
    }

    @Test
    fun expiryStateAuditAndRequestKeyRollBackAndReplayTogether() = runTest {
        val requestId = "invocation-expiry"
        val regular = requests(audit)
        assertEquals(
            ConditionalRequestUpdate.APPLIED,
            regular.receive(
                request = request(requestId),
                secretUseRequest = secretUse(requestId),
                client = client(),
                acceptedPsks = acceptedPsks(requestId),
                authorization = null,
                automaticDecisionAudit = null,
            ),
        )
        val request = checkNotNull(database.requestDao().getRequestById(requestId))
        val eventCount = audit.observeEvents().first().size

        assertTrue(
            runCatching {
                requests(InsertThenFailAuditSink(audit)).expire(request, EXPIRY_MESSAGE, NOW)
            }.isFailure,
        )
        assertNull(database.requestDao().getRequestById(requestId)?.completedAt)
        assertNotNull(database.requestDao().getRequestPsk(requestId))
        assertEquals(eventCount, audit.observeEvents().first().size)

        regular.expire(request, EXPIRY_MESSAGE, NOW)
        val expired = checkNotNull(database.requestDao().getRequestById(requestId))
        assertEquals(InboxRequestState.COMPLETED.storedName, expired.state)
        assertEquals(EXPIRY_MESSAGE, expired.error)
        assertNull(database.requestDao().getRequestPsk(requestId))
        val expiryAudit = audit.observeEvents().first().first()
        assertEquals(AuditEventType.SECRET_USE_COMPLETED, expiryAudit.type)
        assertEquals(AuditOutcome.FAILED, expiryAudit.outcome)
        assertEquals(EXPIRY_MESSAGE, expiryAudit.detail)

        val auditCountAfterExpiry = audit.observeEvents().first().size
        regular.expire(expired, EXPIRY_MESSAGE, NOW + 1)
        assertNull(database.requestDao().getRequestPsk(requestId))
        assertEquals(auditCountAfterExpiry, audit.observeEvents().first().size)
    }

    private suspend fun receivePendingEnvironmentInvocation(
        requestId: String,
    ): PendingEnvironmentInvocation {
        val created = secrets.createEnvironmentSecret("github", "GitHub credentials")
        val secretId = (created as CreateSecretResult.Created).id
        assertTrue(
            secrets.createEnvironmentVariable(
                secretId = secretId,
                name = "TOKEN",
                value = "sensitive-value",
                sensitive = true,
                notes = "",
                nonSensitiveCreationAuthorized = true,
            ) is CreateEnvironmentVariableResult.Created,
        )
        val plaintext =
            """{$SOFTWARE_FIELDS,"method":"Invocation","secrets":{"github":{}},"operation":{"type":"exec","command":"deploy","arguments":[],"working_directory":"/tmp","executable_path":"/usr/bin/deploy","executable_mode":"BINARY","stdin":"TERMINAL","stdout":"TERMINAL","stderr":"TERMINAL"},"launcher_chain":[],"invocation_token":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="}"""
                .encodeToByteArray()
        val contents = InvocationProtocol().decodeRequest(plaintext)
        val description = secrets.resolveRequestedSecrets(contents.secrets, emptyMap()).description
        val policy = secrets.approvalPoliciesForNames(
            names = listOf("github"),
            clientId = CLIENT_ID,
            operation = TemporaryAccessOperation.INVOCATION,
        ).single()
        val evaluation = ApprovalEvaluation(
            secrets = listOf(
                SecretApprovalEvaluation(
                    secretId = policy.secretId,
                    secretName = policy.secretName,
                    action = ApprovalAction.ASK_ME,
                    temporaryAccessEligible = true,
                    revision = policy.revision,
                ),
            ),
        )
        assertEquals(
            ConditionalRequestUpdate.APPLIED,
            requests(audit).receive(
                request = request(requestId),
                secretUseRequest = secretUse(requestId).copy(
                    containsSensitiveMaterial = description.containsSensitiveMaterial,
                    secretsJson = Json.encodeToString(contents.secrets),
                    secretDetailsJson = Json.encodeToString(description.secrets),
                    missingSecretsJson = Json.encodeToString(description.missingSecrets),
                    approvalEvaluationJson = Json.encodeToString(evaluation),
                ),
                client = client(),
                acceptedPsks = acceptedPsks(requestId),
                authorization = null,
                automaticDecisionAudit = null,
            ),
        )
        return PendingEnvironmentInvocation(secretId, contents, plaintext)
    }

    private fun requests(auditSink: AuditSink) = InvocationRequests(
        dao = database.requestDao(),
        secrets = secrets,
        deviceCredentials = MissingDeviceCredentials,
        approvalReviewer = UnexpectedApprovalReviewer,
        audit = auditSink,
        writeTransaction = RoomWriteTransaction(database),
        currentTimeMillis = { NOW },
    )

    private data class PendingEnvironmentInvocation(
        val secretId: String,
        val contents: InvocationRequestMessage,
        val plaintext: ByteArray,
    )

    private fun authorizationCommitment(clientName: String = "Test client") =
        AuthorizationCommitment(
            secretRevisions = emptyMap(),
            policies = emptyMap(),
            instructions = AuthorizationInstructionsCommitment(
                deviceIdentityId = DEVICE_IDENTITY_ID,
                deviceInstructions = "",
                clientId = CLIENT_ID,
                clientName = clientName,
                clientInstructions = "",
            ),
        )

    private fun request(requestId: String) = InboxRequestEntity(
        id = requestId,
        parentRequestId = null,
        deviceIdentityId = DEVICE_IDENTITY_ID,
        clientId = CLIENT_ID,
        clientNameSnapshot = "Test client",
        clientSoftwareJson = SOFTWARE_JSON,
        kind = RequestKind.SECRET_USE.storedName,
        state = InboxRequestState.ACTION_REQUIRED.storedName,
        listed = true,
        requestJson = "{}",
        responseJson = null,
        error = null,
        receivedAt = NOW,
        completedAt = null,
        exchangeEndedAt = null,
        responseOutboxFinished = false,
    )

    private fun secretUse(requestId: String) = SecretUseRequestEntity(
        requestId = requestId,
        hostname = "test",
        platform = "linux",
        architecture = "x86_64",
        machineId = null,
        osVersion = null,
        invocationTokenHash = ByteArray(32),
        containsSensitiveMaterial = true,
        secretsJson = "[\"github\"]",
        secretDetailsJson = "[]",
        providedSecretsJson = null,
        missingSecretsJson = "[]",
        reason = null,
        command = "env",
        argumentsJson = "[]",
        workingDirectory = "/tmp",
        executablePath = "/usr/bin/env",
        executableHash = null,
        executableMode = "BINARY",
        stdinKind = "TERMINAL",
        stdoutKind = "TERMINAL",
        stderrKind = "TERMINAL",
        launcherChainJson = "[]",
        decision = null,
        decisionSource = null,
        approvalEvaluationJson = null,
        completionResult = null,
        completionReason = null,
        completionMessage = null,
        decidedAt = null,
    )

    private fun client() = ClientEntity(
        clientId = CLIENT_ID,
        deviceIdentityId = DEVICE_IDENTITY_ID,
        name = "Test client",
        instructions = "",
        desiredRelayClientState = null,
        relayClientState = "active",
        clientSoftwareJson = SOFTWARE_JSON,
        platform = "linux",
        architecture = "x86_64",
        hostname = "test",
        machineId = null,
        osVersion = null,
        pairedAt = 2,
        lastSeenAt = 2,
    )

    private fun acceptedPsks(requestId: String) = AcceptedRequestPsks(
        requestPsk = RequestPskEntity(
            requestId = requestId,
            encryptedPsk = EncryptedValue(
                formatVersion = 1,
                keyId = KEY_ID,
                nonce = ByteArray(12),
                ciphertext = byteArrayOf(1),
            ),
        ),
        currentClientPsk = null,
        previousClientPsk = null,
    )

    private fun secret(name: String) = SecretEntity(
        id = "secret-$name",
        name = name,
        description = "",
        type = "environment",
        createdAt = 1,
        updatedAt = 1,
        revision = 1,
        approvalMode = "approve",
    )

    private fun decisionAudit(requestId: String, outcome: AuditOutcome) = AuditRecord(
        type = AuditEventType.SECRET_USE_DECIDED,
        outcome = outcome,
        decisionSource = AuditDecisionSource.APPROVAL_SETTINGS,
        subject = "github",
        clientId = CLIENT_ID,
        clientName = "Test client",
        relayRequestId = requestId,
    )

    private fun aiReviewAudit(requestId: String, outcome: AuditOutcome) = AuditRecord(
        type = AuditEventType.SECRET_USE_AI_REVIEWED,
        outcome = outcome,
        decisionSource = AuditDecisionSource.AI_REVIEW,
        subject = "github",
        detail = "Reviewed request.",
        clientId = CLIENT_ID,
        clientName = "Test client",
        relayRequestId = requestId,
    )

    private fun deniedCompletionPlaintext(reason: String, message: String): ByteArray =
        """{$SOFTWARE_FIELDS,"result":"DENIED","reason":"$reason","message":"$message"}"""
            .encodeToByteArray()

    private fun abortedCompletionPlaintext(message: String): ByteArray =
        """{$SOFTWARE_FIELDS,"result":"ABORTED","reason":"CANCELLED","message":"$message"}"""
            .encodeToByteArray()

    private class InsertThenFailAuditSink(
        private val delegate: AuditSink,
    ) : AuditSink {
        override suspend fun record(record: AuditRecord) {
            delegate.record(record)
            error("Injected audit failure")
        }

        override suspend fun append(records: List<AuditRecord>, occurredAt: Long) {
            delegate.append(records, occurredAt)
            error("Injected audit failure")
        }
    }

    private class MemoryEncryptionKeyStore : EncryptionKeyStore {
        private val keys = mutableMapOf<String, SecretKey>()

        override fun get(keyId: String): SecretKey? = keys[keyId]

        override fun generate(keyId: String): GeneratedEncryptionKey {
            check(keyId !in keys)
            keys[keyId] = SecretKeySpec(ByteArray(16) { it.toByte() }, "AES")
            return GeneratedEncryptionKey(EncryptionKeyBacking.SOFTWARE)
        }

        override fun delete(keyId: String) {
            keys.remove(keyId)
        }
    }

    private companion object {
        const val DEVICE_IDENTITY_ID = "device-identity"
        const val DEVICE_ID = "01JDEVICE000000000000000000"
        const val CLIENT_ID = "01JCLIENT000000000000000000"
        const val KEY_ID = "request-key"
        const val NOW = 10L
        const val RESPONSE_JSON = "{\"ciphertext\":\"response\"}"
        const val EXPIRY_MESSAGE = "The relay exchange expired before it completed."
        const val SOFTWARE_FIELDS =
            "\"app_info\":{\"name\":\"agentknock-cli\",\"version\":\"0.3.0\"}," +
                "\"lib_info\":{\"name\":\"agentknock\",\"version\":\"0.3.0\"}"
        const val SOFTWARE_JSON = "{$SOFTWARE_FIELDS}"
    }
}

private data object MissingDeviceCredentials : RelayDeviceCredentialSource {
    override suspend fun activeDeviceCredentials(): RelayDeviceCredentialsResult =
        RelayDeviceCredentialsResult.Missing

    override suspend fun deviceCredentials(
        deviceIdentityId: String,
    ): RelayDeviceCredentialsResult = RelayDeviceCredentialsResult.Missing
}

private data object UnexpectedApprovalReviewer : RelayApprovalReviewClient {
    override suspend fun review(
        deviceId: String,
        deviceToken: String,
        request: ApprovalReviewRequest,
    ): RelayApprovalReviewResult = error("AI review was not expected")
}
