package dev.agentknock.storage.request

import androidx.room3.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import dev.agentknock.storage.secret.CreateSecretResult
import dev.agentknock.storage.secret.SaveSecretResult
import dev.agentknock.storage.secret.SecretApprovalMode
import dev.agentknock.storage.secret.SecretEntity
import dev.agentknock.storage.secret.SecretRepository
import dev.agentknock.storage.secret.SshKeyAlgorithm
import dev.agentknock.storage.secret.TemporaryAccessOperation
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
class GitSigningRequestsTest {
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
            newId = { SECRET_ID },
            currentTimeMillis = { NOW },
            cryptographyDispatcher = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun receiveAndAuditRollBackTogetherAndAuthorizationRaceBecomesActionable() = runTest {
        insertParent()
        val requestId = "git-receive"
        assertTrue(
            runCatching {
                requests(InsertThenFailAuditSink(audit)).receive(
                    request = request(requestId),
                    gitSign = gitSign(requestId),
                    client = client(),
                    acceptedPsks = acceptedPsks(requestId),
                    authorization = null,
                    automaticDecisionAudit = null,
                )
            }.isFailure,
        )
        assertNull(database.requestDao().getRequestById(requestId))
        assertNull(database.requestDao().getGitSignRequest(requestId))
        assertNull(database.requestDao().getRequestPsk(requestId))

        database.secretDao().insertSecret(secret("appeared"))
        val authorization = AuthorizationCommitment(
            secretRevisions = emptyMap(),
            policies = emptyMap(),
            expectedAbsentSecretNames = setOf("appeared"),
        )
        assertEquals(
            ConditionalRequestUpdate.ACTION_REQUIRED,
            requests(audit).receive(
                request = request(requestId).copy(
                    state = InboxRequestState.WAITING.storedName,
                    responseJson = RESPONSE_JSON,
                ),
                gitSign = gitSign(requestId).copy(
                    decision = ApprovalDecision.DENIED.storedName,
                    decisionSource = DECISION_SOURCE_VALIDATION,
                    completionReason = "INVALID_REQUEST",
                    completionMessage = "Invalid request.",
                    decidedAt = NOW,
                ),
                client = client(),
                acceptedPsks = acceptedPsks(requestId),
                authorization = authorization,
                automaticDecisionAudit = decisionAudit(requestId, AuditOutcome.REJECTED),
            ),
        )
        val storedRequest = checkNotNull(database.requestDao().getRequestById(requestId))
        val storedGitSign = checkNotNull(database.requestDao().getGitSignRequest(requestId))
        assertEquals(InboxRequestState.ACTION_REQUIRED.storedName, storedRequest.state)
        assertNull(storedRequest.responseJson)
        assertNull(storedGitSign.decision)
        assertNull(storedGitSign.decisionSource)
        assertEquals(
            listOf(AuditEventType.GIT_SIGN_RECEIVED),
            audit.observeEvents().first().map { it.type },
        )
    }

    @Test
    fun automaticReceivePersistsTheDecisionAndItsAudit() = runTest {
        insertParent()
        val requestId = "git-automatic-receive"
        assertEquals(
            ConditionalRequestUpdate.APPLIED,
            requests(audit).receive(
                request = request(requestId).copy(
                    state = InboxRequestState.WAITING.storedName,
                    responseJson = RESPONSE_JSON,
                ),
                gitSign = gitSign(requestId).copy(
                    decision = ApprovalDecision.DENIED.storedName,
                    decisionSource = DECISION_SOURCE_VALIDATION,
                    completionReason = "INVALID_REQUEST",
                    completionMessage = "Invalid request.",
                    decidedAt = NOW,
                ),
                client = client(),
                acceptedPsks = acceptedPsks(requestId),
                authorization = authorizationCommitment(),
                automaticDecisionAudit = decisionAudit(requestId, AuditOutcome.REJECTED),
            ),
        )
        assertEquals(
            InboxRequestState.WAITING.storedName,
            database.requestDao().getRequestById(requestId)?.state,
        )
        assertEquals(
            DECISION_SOURCE_VALIDATION,
            database.requestDao().getGitSignRequest(requestId)?.decisionSource,
        )
        assertEquals(
            setOf(AuditEventType.GIT_SIGN_RECEIVED, AuditEventType.GIT_SIGN_DECIDED),
            audit.observeEvents().first().take(2).map { it.type }.toSet(),
        )
    }

    @Test
    fun manualDenialAndAuditCommitOrRollBackTogether() = runTest {
        insertParent()
        val requestId = "git-denial"
        val regular = requests(audit)
        receivePending(regular, requestId)
        val auditCount = audit.observeEvents().first().size
        val seal: suspend (InboxRequestEntity, ByteArray) -> JsonElement? =
            { _, _ -> Json.parseToJsonElement(RESPONSE_JSON) }

        assertTrue(
            runCatching {
                requests(InsertThenFailAuditSink(audit)).deny(requestId, seal)
            }.isFailure,
        )
        assertEquals(
            InboxRequestState.ACTION_REQUIRED.storedName,
            database.requestDao().getRequestById(requestId)?.state,
        )
        assertNull(database.requestDao().getGitSignRequest(requestId)?.decision)
        assertEquals(auditCount, audit.observeEvents().first().size)

        assertEquals(GitSignDecisionResult.Decided, regular.deny(requestId, seal))
        val decided = checkNotNull(database.requestDao().getGitSignRequest(requestId))
        assertEquals(ApprovalDecision.DENIED.storedName, decided.decision)
        assertEquals(DECISION_SOURCE_USER, decided.decisionSource)
        assertEquals(GIT_SIGN_DENIAL_MESSAGE, decided.completionMessage)
        assertEquals(auditCount + 1, audit.observeEvents().first().size)

        val competingRequestId = "git-competing-denial"
        receivePending(regular, competingRequestId)
        val innerResponse = Json.parseToJsonElement("""{"ciphertext":"inner"}""")
        val outerResponse = Json.parseToJsonElement("""{"ciphertext":"outer"}""")
        var competingResult: GitSignDecisionResult? = null
        assertEquals(
            GitSignDecisionResult.NotPending,
            regular.deny(competingRequestId) { _, _ ->
                competingResult = regular.deny(competingRequestId) { _, _ -> innerResponse }
                outerResponse
            },
        )
        assertEquals(GitSignDecisionResult.Decided, competingResult)
        assertEquals(
            innerResponse.toString(),
            database.requestDao().getRequestById(competingRequestId)?.responseJson,
        )
    }

    @Test
    fun successfulAiFinalizationCannotOverwriteACompetingDecision() = runTest {
        insertParent()
        val requestId = "git-ai-success"
        val regular = requests(audit)
        receivePending(
            regular,
            requestId,
            state = InboxRequestState.REVIEWING,
            evaluationJson = INITIAL_EVALUATION_JSON,
        )
        val reviewing = checkNotNull(database.requestDao().getRequestById(requestId))
        val storedGitSign = checkNotNull(database.requestDao().getGitSignRequest(requestId))
        val finalRequest = reviewing.copy(
            state = InboxRequestState.WAITING.storedName,
            responseJson = RESPONSE_JSON,
        )
        val finalGitSign = storedGitSign.copy(
            approvalEvaluationJson = FINAL_EVALUATION_JSON,
            decision = ApprovalDecision.DENIED.storedName,
            decisionSource = DECISION_SOURCE_AI,
            completionReason = "POLICY_DENIED",
            completionMessage = "AI review denied signing.",
            decidedAt = NOW,
        )
        val aiAudit = aiReviewAudit(requestId, AuditOutcome.DENIED)
        val decisionAudit = decisionAudit(requestId, AuditOutcome.DENIED)

        assertEquals(
            ConditionalRequestUpdate.APPLIED,
            regular.finishAiReview(
                finalRequest,
                finalGitSign,
                authorizationCommitment(),
                aiAudit,
                decisionAudit,
            ),
        )
        assertEquals(
            DECISION_SOURCE_AI,
            database.requestDao().getGitSignRequest(requestId)?.decisionSource,
        )
        assertEquals(
            FINAL_EVALUATION_JSON,
            database.requestDao().getGitSignRequest(requestId)?.approvalEvaluationJson,
        )
        val eventCount = audit.observeEvents().first().size
        assertEquals(
            ConditionalRequestUpdate.UNAVAILABLE,
            regular.finishAiReview(
                finalRequest.copy(responseJson = """{"ciphertext":"stale"}"""),
                finalGitSign.copy(decision = ApprovalDecision.APPROVED.storedName),
                authorizationCommitment(),
                aiAudit,
                decisionAudit,
            ),
        )
        assertEquals(RESPONSE_JSON, database.requestDao().getRequestById(requestId)?.responseJson)
        assertEquals(eventCount, audit.observeEvents().first().size)
    }

    @Test
    fun aiFinalizationUsesCurrentRowsAndAuditsTheDeferredRaceAtomically() = runTest {
        insertParent()
        val requestId = "git-ai-race"
        val regular = requests(audit)
        receivePending(
            regular,
            requestId,
            state = InboxRequestState.REVIEWING,
            evaluationJson = INITIAL_EVALUATION_JSON,
        )
        val reviewing = checkNotNull(database.requestDao().getRequestById(requestId))
        val storedGitSign = checkNotNull(database.requestDao().getGitSignRequest(requestId))
        val finalRequest = reviewing.copy(state = InboxRequestState.ACTION_REQUIRED.storedName)

        assertTrue(
            runCatching {
                requests(InsertThenFailAuditSink(audit)).finishAiReview(
                    request = finalRequest,
                    gitSign = storedGitSign.copy(approvalEvaluationJson = FINAL_EVALUATION_JSON),
                    authorization = authorizationCommitment(),
                    aiReviewAudit = aiReviewAudit(requestId, AuditOutcome.DEFERRED),
                    automaticDecisionAudit = null,
                )
            }.isFailure,
        )
        assertEquals(
            InboxRequestState.REVIEWING.storedName,
            database.requestDao().getRequestById(requestId)?.state,
        )
        assertEquals(
            INITIAL_EVALUATION_JSON,
            database.requestDao().getGitSignRequest(requestId)?.approvalEvaluationJson,
        )

        assertEquals(
            ConditionalRequestUpdate.ACTION_REQUIRED,
            regular.finishAiReview(
                request = finalRequest.copy(
                    state = InboxRequestState.WAITING.storedName,
                    responseJson = RESPONSE_JSON,
                ),
                gitSign = storedGitSign.copy(
                    approvalEvaluationJson = FINAL_EVALUATION_JSON,
                    decision = ApprovalDecision.DENIED.storedName,
                    decisionSource = DECISION_SOURCE_AI,
                    completionReason = "POLICY_DENIED",
                    completionMessage = "AI review denied signing.",
                    decidedAt = NOW,
                ),
                authorization = authorizationCommitment(clientName = "Old client name"),
                aiReviewAudit = aiReviewAudit(requestId, AuditOutcome.DENIED),
                automaticDecisionAudit = decisionAudit(requestId, AuditOutcome.DENIED),
            ),
        )
        val request = checkNotNull(database.requestDao().getRequestById(requestId))
        val signing = checkNotNull(database.requestDao().getGitSignRequest(requestId))
        assertEquals(InboxRequestState.ACTION_REQUIRED.storedName, request.state)
        assertNull(request.responseJson)
        assertNull(signing.decision)
        assertNull(signing.decisionSource)
        assertEquals(INITIAL_EVALUATION_JSON, signing.approvalEvaluationJson)
        val aiAudit = audit.observeEvents().first().first()
        assertEquals(AuditEventType.GIT_SIGN_AI_REVIEWED, aiAudit.type)
        assertEquals(AuditOutcome.DEFERRED, aiAudit.outcome)
    }

    @Test
    fun temporaryGrantDecisionAndAllAuditsShareOneTransaction() = runTest {
        val key = secrets.generateSshKey(SshKeyAlgorithm.ED25519, "test@example")
        val created = secrets.createSshSecret(SECRET_NAME, "Signing key", key)
        assertTrue(created is CreateSecretResult.Created)
        assertEquals(
            SaveSecretResult.SAVED,
            secrets.saveApprovalMode(SECRET_ID, SecretApprovalMode.TEMPORARY),
        )
        val description = secrets.describeRequestedSecrets(listOf(SECRET_NAME))
        insertParent(Json.encodeToString(description.secrets))
        val policy = secrets.approvalPoliciesForNames(
            listOf(SECRET_NAME),
            CLIENT_ID,
            TemporaryAccessOperation.GIT_SIGN,
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
        val requestId = "git-temporary"
        receivePending(
            requests(audit),
            requestId,
            evaluationJson = Json.encodeToString(evaluation),
        )
        val eventCount = audit.observeEvents().first().size
        val seal: suspend (InboxRequestEntity, ByteArray) -> JsonElement? =
            { _, _ -> Json.parseToJsonElement(RESPONSE_JSON) }

        assertTrue(
            runCatching {
                requests(InsertThenFailAuditSink(audit)).approve(
                    requestId,
                    allowTemporaryAccess = true,
                    sealResponse = seal,
                )
            }.isFailure,
        )
        assertNull(database.requestDao().getGitSignRequest(requestId)?.decision)
        assertTrue(secrets.observeTemporaryAccessGrants().first().isEmpty())
        assertEquals(eventCount, audit.observeEvents().first().size)

        assertEquals(
            GitSignDecisionResult.Decided,
            requests(audit).approve(requestId, allowTemporaryAccess = true, sealResponse = seal),
        )
        val decided = checkNotNull(database.requestDao().getGitSignRequest(requestId))
        assertEquals(ApprovalDecision.APPROVED.storedName, decided.decision)
        assertEquals(DECISION_SOURCE_TEMPORARY_ACCESS, decided.decisionSource)
        assertEquals(1, secrets.observeTemporaryAccessGrants().first().size)
        assertEquals(
            setOf(
                AuditEventType.TEMPORARY_ACCESS_ALLOWED,
                AuditEventType.GIT_SIGN_DECIDED,
            ),
            audit.observeEvents().first().take(2).map { it.type }.toSet(),
        )
    }

    @Test
    fun completionIsSanitizedAtomicAndOnlyExactReplaySucceeds() = runTest {
        insertParent()
        val requestId = "git-completion"
        val regular = requests(audit)
        receivePending(regular, requestId)
        val request = checkNotNull(database.requestDao().getRequestById(requestId))
        val completion = Json.parseToJsonElement("""{"ciphertext":"completion"}""")
        val malicious = "raw-client-controlled-message"
        val plaintext = abortedCompletionPlaintext(malicious)
        val eventCount = audit.observeEvents().first().size

        assertTrue(
            runCatching {
                requests(InsertThenFailAuditSink(audit)).complete(request, completion) {
                    CompletionOpenResult.Opened(plaintext)
                }
            }.isFailure,
        )
        assertNull(database.requestDao().getRequestById(requestId)?.completedAt)
        assertNotNull(database.requestDao().getRequestPsk(requestId))
        assertEquals(eventCount, audit.observeEvents().first().size)

        assertTrue(
            regular.complete(request, completion) { CompletionOpenResult.Opened(plaintext) },
        )
        val completed = checkNotNull(database.requestDao().getRequestById(requestId))
        val signing = checkNotNull(database.requestDao().getGitSignRequest(requestId))
        assertEquals(ApprovalCompletionResult.ABORTED.storedName, signing.completionResult)
        assertNull(signing.completionReason)
        assertNull(signing.completionMessage)
        assertNull(database.requestDao().getRequestPsk(requestId))
        val completionAudit = audit.observeEvents().first().first()
        assertEquals(AuditEventType.GIT_SIGN_COMPLETED, completionAudit.type)
        assertFalse(checkNotNull(completionAudit.detail).contains(malicious))

        val auditCount = audit.observeEvents().first().size
        assertTrue(regular.complete(request, completion) { error("Must not reopen") })
        assertNull(database.requestDao().getRequestPsk(requestId))
        assertTrue(
            regular.complete(
                completed,
                Json.parseToJsonElement("""{"ciphertext":"different"}"""),
            ) { error("Must not reopen") },
        )
        assertEquals(auditCount, audit.observeEvents().first().size)

        val racingRequestId = "git-completion-race"
        receivePending(regular, racingRequestId)
        val racingRequest = checkNotNull(database.requestDao().getRequestById(racingRequestId))
        assertTrue(
            regular.complete(racingRequest, completion) {
                assertTrue(
                    regular.complete(racingRequest, completion) {
                        CompletionOpenResult.Opened(plaintext)
                    },
                )
                CompletionOpenResult.RetryLater
            },
        )
        assertNull(database.requestDao().getRequestPsk(racingRequestId))
    }

    @Test
    fun expiryStateAuditAndRequestKeyCommitOrRollBackTogether() = runTest {
        insertParent()
        val requestId = "git-expiry"
        val regular = requests(audit)
        receivePending(regular, requestId)
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
        assertEquals(AuditEventType.GIT_SIGN_COMPLETED, expiryAudit.type)
        assertEquals(AuditOutcome.FAILED, expiryAudit.outcome)

        val auditCount = audit.observeEvents().first().size
        regular.expire(expired, EXPIRY_MESSAGE, NOW + 1)
        assertNull(database.requestDao().getRequestPsk(requestId))
        assertEquals(auditCount, audit.observeEvents().first().size)
    }

    private suspend fun insertParent(secretDetailsJson: String = "[]") {
        database.requestDao().insertRequest(parentRequest())
        database.requestDao().insertSecretUseRequestRow(
            parentInvocation(secretDetailsJson),
        )
    }

    private suspend fun receivePending(
        target: GitSigningRequests,
        requestId: String,
        state: InboxRequestState = InboxRequestState.ACTION_REQUIRED,
        evaluationJson: String? = null,
    ) {
        assertEquals(
            ConditionalRequestUpdate.APPLIED,
            target.receive(
                request = request(requestId).copy(state = state.storedName),
                gitSign = gitSign(requestId).copy(approvalEvaluationJson = evaluationJson),
                client = client(),
                acceptedPsks = acceptedPsks(requestId),
                authorization = null,
                automaticDecisionAudit = null,
            ),
        )
    }

    private fun requests(auditSink: AuditSink) = GitSigningRequests(
        dao = database.requestDao(),
        secrets = secrets,
        audit = auditSink,
        writeTransaction = RoomWriteTransaction(database),
        currentTimeMillis = { NOW },
    )

    private fun parentRequest() = InboxRequestEntity(
        id = PARENT_ID,
        parentRequestId = null,
        deviceIdentityId = DEVICE_IDENTITY_ID,
        clientId = CLIENT_ID,
        clientNameSnapshot = "Test client",
        clientSoftwareJson = SOFTWARE_JSON,
        kind = RequestKind.SECRET_USE.storedName,
        state = InboxRequestState.WAITING.storedName,
        listed = true,
        requestJson = "{}",
        responseJson = RESPONSE_JSON,
        completionJson = null,
        error = null,
        receivedAt = NOW - 1,
        completedAt = null,
        exchangeEndedAt = null,
        responseOutboxFinished = false,
    )

    private fun request(requestId: String) = InboxRequestEntity(
        id = requestId,
        parentRequestId = PARENT_ID,
        deviceIdentityId = DEVICE_IDENTITY_ID,
        clientId = CLIENT_ID,
        clientNameSnapshot = "Test client",
        clientSoftwareJson = SOFTWARE_JSON,
        kind = RequestKind.GIT_SIGN.storedName,
        state = InboxRequestState.ACTION_REQUIRED.storedName,
        listed = true,
        requestJson = "{}",
        responseJson = null,
        completionJson = null,
        error = null,
        receivedAt = NOW,
        completedAt = null,
        exchangeEndedAt = null,
        responseOutboxFinished = false,
    )

    private fun parentInvocation(secretDetailsJson: String) = SecretUseRequestEntity(
        requestId = PARENT_ID,
        hostname = "test",
        platform = "linux",
        architecture = "x86_64",
        machineId = null,
        osVersion = null,
        invocationTokenHash = ByteArray(32),
        containsSensitiveMaterial = true,
        secretsJson = Json.encodeToString(listOf(SECRET_NAME)),
        secretDetailsJson = secretDetailsJson,
        providedSecretsJson = null,
        missingSecretsJson = "[]",
        reason = null,
        command = "git",
        argumentsJson = "[]",
        workingDirectory = "/tmp/project",
        executablePath = "/usr/bin/git",
        executableHash = null,
        executableMode = "BINARY",
        stdinKind = "TERMINAL",
        stdoutKind = "TERMINAL",
        stderrKind = "TERMINAL",
        launcherChainJson = "[]",
        decision = ApprovalDecision.APPROVED.storedName,
        decisionSource = DECISION_SOURCE_USER,
        approvalEvaluationJson = null,
        completionResult = null,
        completionReason = null,
        completionMessage = null,
        decidedAt = NOW - 1,
    )

    private fun gitSign(requestId: String) = GitSignRequestEntity(
        requestId = requestId,
        secretName = SECRET_NAME,
        message = "commit to sign".encodeToByteArray(),
        repositoryJson = null,
        approvalEvaluationJson = null,
        decision = null,
        decisionSource = null,
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

    private fun decisionAudit(requestId: String, outcome: AuditOutcome) = AuditRecord(
        type = AuditEventType.GIT_SIGN_DECIDED,
        outcome = outcome,
        decisionSource = AuditDecisionSource.VALIDATION,
        subject = SECRET_NAME,
        clientId = CLIENT_ID,
        clientName = "Test client",
        relayRequestId = requestId,
    )

    private fun aiReviewAudit(requestId: String, outcome: AuditOutcome) = AuditRecord(
        type = AuditEventType.GIT_SIGN_AI_REVIEWED,
        outcome = outcome,
        decisionSource = AuditDecisionSource.AI_REVIEW,
        subject = SECRET_NAME,
        clientId = CLIENT_ID,
        clientName = "Test client",
        relayRequestId = requestId,
    )

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
        const val PARENT_ID = "01JPARENT000000000000000000"
        const val SECRET_ID = "secret-id"
        const val SECRET_NAME = "git-signing"
        const val KEY_ID = "request-key"
        const val NOW = 10L
        const val RESPONSE_JSON = "{\"ciphertext\":\"response\"}"
        const val EXPIRY_MESSAGE = "The relay exchange expired before it completed."
        const val INITIAL_EVALUATION_JSON = "{\"stage\":\"initial\"}"
        const val FINAL_EVALUATION_JSON = "{\"stage\":\"final\"}"
        const val SOFTWARE_FIELDS =
            "\"app_info\":{\"name\":\"agentknock-cli\",\"version\":\"0.3.0\"}," +
                "\"lib_info\":{\"name\":\"agentknock\",\"version\":\"0.3.0\"}"
        const val SOFTWARE_JSON = "{$SOFTWARE_FIELDS}"
    }
}
