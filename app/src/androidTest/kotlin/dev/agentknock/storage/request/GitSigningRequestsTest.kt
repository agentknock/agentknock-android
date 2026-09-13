package dev.agentknock.storage.request

import androidx.room3.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.agentknock.relay.RelayApprovalReview
import dev.agentknock.relay.RelayApprovalReviewClient
import dev.agentknock.relay.RelayApprovalReviewDecision
import dev.agentknock.relay.RelayApprovalReviewResult
import dev.agentknock.relay.RelayEndpointResult
import dev.agentknock.review.ApprovalReviewOperation
import dev.agentknock.review.ApprovalReviewRequest
import dev.agentknock.review.ApprovalReviewSecretFacts
import dev.agentknock.review.ApprovalReviewSshSecretFacts
import dev.agentknock.storage.AgentknockDatabase
import dev.agentknock.storage.RoomWriteTransaction
import dev.agentknock.storage.approval.AiReviewDecision
import dev.agentknock.storage.approval.AiReviewFailure
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
import dev.agentknock.storage.crypto.DecryptionResult
import dev.agentknock.storage.crypto.EncryptedValue
import dev.agentknock.storage.crypto.EncryptionKeyBacking
import dev.agentknock.storage.crypto.EncryptionKeyStore
import dev.agentknock.storage.crypto.GeneratedEncryptionKey
import dev.agentknock.storage.crypto.VaultKeyEntity
import dev.agentknock.storage.crypto.VaultKeyManager
import dev.agentknock.storage.crypto.VaultKeyPurpose
import dev.agentknock.storage.device.DeviceCredentialResult
import dev.agentknock.storage.device.DeviceIdentityEntity
import dev.agentknock.storage.device.DeviceKeyAccess
import dev.agentknock.storage.device.RelayDeviceCredentialSource
import dev.agentknock.storage.device.RelayDeviceCredentials
import dev.agentknock.storage.secret.CreateSecretResult
import dev.agentknock.storage.secret.SaveSecretResult
import dev.agentknock.storage.secret.SaveSshSecretResult
import dev.agentknock.storage.secret.SecretApprovalMode
import dev.agentknock.storage.secret.SecretEntity
import dev.agentknock.storage.secret.SecretRepository
import dev.agentknock.storage.secret.SshKeyAlgorithm
import dev.agentknock.storage.secret.TemporaryAccessOperation
import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    private val subscription = FakeSubscription(DEVICE_ID)
    private lateinit var database: AgentknockDatabase
    private lateinit var audit: AuditRepository
    private lateinit var secrets: SecretRepository

    @Before
    fun setUp() = runTest {
        database =
            Room.inMemoryDatabaseBuilder(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    AgentknockDatabase::class.java,
                )
                .build()
        val keyStore = MemoryEncryptionKeyStore()
        keyStore.generate(KEY_ID)
        database
            .vaultKeyDao()
            .activate(
                VaultKeyEntity(
                    id = KEY_ID,
                    purpose = VaultKeyPurpose.DEVICE_STATE.storedName,
                    active = true,
                    createdAt = 1,
                    backing = EncryptionKeyBacking.SOFTWARE.storedName,
                )
            )
        database
            .deviceIdentityDao()
            .insertIdentity(
                DeviceIdentityEntity(
                    id = DEVICE_IDENTITY_ID,
                    role = "active",
                    address = "quiet-river-maple",
                    deviceId = DEVICE_ID,
                    createdAt = 1,
                )
            )
        database.requestDao().insertClient(client())
        val keyManager =
            VaultKeyManager(
                dao = database.vaultKeyDao(),
                keyStore = keyStore,
                newKeyId = { "unused-key" },
                currentTimeMillis = { NOW },
                keyStoreDispatcher = Dispatchers.Unconfined,
            )
        audit = AuditRepository(database.auditDao(), currentTimeMillis = { NOW })
        secrets =
            SecretRepository(
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
    fun inactiveAiAccessFallsBackWithoutLaunchingReviewOrChangingThePolicy() = runTest {
        val metadata = createSigningSecret(SecretApprovalMode.ASK_AI)
        val token = ByteArray(32) { it.toByte() }
        insertParent(
            secretDetailsJson = Json.encodeToString(metadata),
            invocationTokenHash = invocationTokenHash(token),
            providedSecretsJson = sshSecretFactsJson(),
        )
        subscription.active = false
        val requestId = "inactive-ai"
        val target = requests(audit, StaticCredentialSource(credentials()))
        assertEquals(
            ProcessedRelayMessage,
            target.processIncoming(
                client = client(),
                relayRequestId = requestId,
                requestPayload = Json.parseToJsonElement("{}"),
                plaintext = gitSignPlaintext(token),
                acceptedPsks = acceptedPsks(requestId),
                credentials = credentials(),
                sealResponse = { error("Manual requests must not produce an automatic response") },
                launchAiReview = { _, _, _, _ ->
                    error("Inactive access must not launch AI review")
                },
            ),
        )
        assertEquals(
            InboxRequestState.ACTION_REQUIRED.storedName,
            database.requestDao().getRequestById(requestId)?.state,
        )
        val stored = checkNotNull(database.requestDao().getGitSignRequest(requestId))
        assertNull(stored.decision)
        val evaluation =
            Json.decodeFromString<ApprovalEvaluation>(checkNotNull(stored.approvalEvaluationJson))
        assertEquals(AiReviewFailure.SUBSCRIPTION_REQUIRED, evaluation.aiReview?.failure)
        assertEquals(
            SecretApprovalMode.ASK_AI,
            secrets.observeSecret(SECRET_ID).first()?.approvalMode,
        )
        assertEquals(
            AuditOutcome.DEFERRED,
            audit
                .observeEvents()
                .first()
                .single {
                    it.relayRequestId == requestId && it.type == AuditEventType.GIT_SIGN_AI_REVIEWED
                }
                .outcome,
        )
    }

    @Test
    fun receiveAndAuditRollBackTogetherAndAuthorizationRaceBecomesActionable() = runTest {
        insertParent()
        val requestId = "git-receive"
        assertTrue(
            runCatching {
                requests(InsertThenFailAuditSink(audit))
                    .receive(
                        request = request(requestId),
                        gitSign = gitSign(requestId),
                        client = client(),
                        acceptedPsks = acceptedPsks(requestId),
                        authorization = null,
                        automaticDecisionAudit = null,
                    )
            }
                .isFailure
        )
        assertNull(database.requestDao().getRequestById(requestId))
        assertNull(database.requestDao().getGitSignRequest(requestId))
        assertNull(database.requestDao().getRequestPsk(requestId))

        database.secretDao().insertSecret(secret("appeared"))
        val authorization =
            AuthorizationCommitment(
                secretRevisions = emptyMap(),
                policies = emptyMap(),
                expectedAbsentSecretNames = setOf("appeared"),
            )
        assertEquals(
            ConditionalRequestUpdate.ACTION_REQUIRED,
            requests(audit)
                .receive(
                    request =
                        request(requestId)
                            .copy(
                                state = InboxRequestState.WAITING.storedName,
                                responseJson = RESPONSE_JSON,
                            ),
                    gitSign =
                        gitSign(requestId)
                            .copy(
                                decision = ApprovalDecision.DENIED.storedName,
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
            requests(audit)
                .receive(
                    request =
                        request(requestId)
                            .copy(
                                state = InboxRequestState.WAITING.storedName,
                                responseJson = RESPONSE_JSON,
                            ),
                    gitSign =
                        gitSign(requestId)
                            .copy(
                                decision = ApprovalDecision.DENIED.storedName,
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
        val events = audit.observeEvents().first()
        assertEquals(
            setOf(AuditEventType.GIT_SIGN_RECEIVED, AuditEventType.GIT_SIGN_DECIDED),
            events.take(2).map { it.type }.toSet(),
        )
        assertEquals(
            AuditDecisionSource.VALIDATION,
            events.first { it.type == AuditEventType.GIT_SIGN_DECIDED }.decisionSource,
        )
    }

    @Test
    fun incomingRequestAfterParentCompletionPersistsAnActionableSigningRequest() = runTest {
        val key = secrets.generateSshKey(SshKeyAlgorithm.ED25519, "test@example")
        assertTrue(
            secrets.createSshSecret(SECRET_NAME, "Signing key", key) is CreateSecretResult.Created
        )
        val description = secrets.describeRequestedSecrets(listOf(SECRET_NAME))
        val token = ByteArray(32) { it.toByte() }
        insertParent(
            secretDetailsJson = Json.encodeToString(description.secrets),
            invocationTokenHash = invocationTokenHash(token),
        )
        // A child process can report different software while retaining the same authenticated
        // parent.
        val parent = checkNotNull(database.requestDao().getRequestById(PARENT_ID))
        database.requestDao().updateRequest(parent.copy(clientSoftwareJson = "{}"))
        val requestId = "git-valid-intake"

        val processed =
            requests(audit)
                .processIncoming(
                    client = client(),
                    relayRequestId = requestId,
                    requestPayload = Json.parseToJsonElement("""{"envelope":"request"}"""),
                    plaintext = gitSignPlaintext(token),
                    acceptedPsks = acceptedPsks(requestId),
                    credentials = credentials(),
                    sealResponse = { error("An actionable request must not be sealed") },
                    launchAiReview = { _, _, _, _ ->
                        error("Temporary approval mode must not launch AI review")
                    },
                )

        assertEquals(ProcessedRelayMessage, processed)
        val request = checkNotNull(database.requestDao().getRequestById(requestId))
        val signing = checkNotNull(database.requestDao().getGitSignRequest(requestId))
        assertEquals(InboxRequestState.ACTION_REQUIRED.storedName, request.state)
        assertEquals(PARENT_ID, request.parentRequestId)
        assertEquals("git-signing", signing.secretName)
        assertEquals("commit to sign", signing.message.decodeToString())
        assertNotNull(signing.approvalEvaluationJson)
        assertNotNull(database.requestDao().getRequestPsk(requestId))
        assertEquals(
            AuditEventType.GIT_SIGN_RECEIVED,
            audit.observeEvents().first().single { it.relayRequestId == requestId }.type,
        )
    }

    @Test
    fun incomingRequestRejectsMalformedParentSecretMetadataCleanly() = runTest {
        val token = ByteArray(32) { it.toByte() }
        insertParent(
            secretDetailsJson = "not-json",
            invocationTokenHash = invocationTokenHash(token),
        )
        val requestId = "git-malformed-parent"

        val processed =
            requests(audit)
                .processIncoming(
                    client = client(),
                    relayRequestId = requestId,
                    requestPayload = Json.parseToJsonElement("{}"),
                    plaintext = gitSignPlaintext(token),
                    acceptedPsks = acceptedPsks(requestId),
                    credentials = credentials(),
                    sealResponse = { error("A rejected request must not be sealed") },
                    launchAiReview = { _, _, _, _ ->
                        error("A rejected request must not launch review")
                    },
                )

        assertNull(processed)
        assertNull(database.requestDao().getRequestById(requestId))
    }

    @Test
    fun incomingRequestRejectsAnInvocationTokenMismatch() = runTest {
        val expectedToken = ByteArray(32) { it.toByte() }
        val suppliedToken = expectedToken.copyOf().also { it[0] = (it[0] + 1).toByte() }
        insertParent(invocationTokenHash = invocationTokenHash(expectedToken))
        val requestId = "git-token-mismatch"
        var sealed = false
        var launched = false

        val processed =
            requests(audit)
                .processIncoming(
                    client = client(),
                    relayRequestId = requestId,
                    requestPayload = Json.parseToJsonElement("{}"),
                    plaintext = gitSignPlaintext(suppliedToken),
                    acceptedPsks = acceptedPsks(requestId),
                    credentials = credentials(),
                    sealResponse = {
                        sealed = true
                        Json.parseToJsonElement(RESPONSE_JSON)
                    },
                    launchAiReview = { _, _, _, _ ->
                        launched = true
                        true
                    },
                )

        assertNull(processed)
        assertFalse(sealed)
        assertFalse(launched)
        assertNull(database.requestDao().getRequestById(requestId))
        assertNull(database.requestDao().getGitSignRequest(requestId))
        assertNull(database.requestDao().getRequestPsk(requestId))
    }

    @Test
    fun automaticSigningPersistsAfterTheParentExchangeHasEnded() = runTest {
        val metadata = createSigningSecret(SecretApprovalMode.APPROVE)
        val token = ByteArray(32) { it.toByte() }
        insertParent(
            secretDetailsJson = Json.encodeToString(metadata),
            invocationTokenHash = invocationTokenHash(token),
        )
        val requestId = "git-parent-ended-during-seal"
        var sealed = false

        val processed =
            requests(audit)
                .processIncoming(
                    client = client(),
                    relayRequestId = requestId,
                    requestPayload = Json.parseToJsonElement("{}"),
                    plaintext = gitSignPlaintext(token),
                    acceptedPsks = acceptedPsks(requestId),
                    credentials = credentials(),
                    sealResponse = {
                        sealed = true
                        Json.parseToJsonElement(RESPONSE_JSON)
                    },
                    launchAiReview = { _, _, _, _ ->
                        error("Approve-always mode must not launch AI review")
                    },
                )

        assertEquals(ProcessedRelayMessage, processed)
        assertTrue(sealed)
        val request = checkNotNull(database.requestDao().getRequestById(requestId))
        val signing = checkNotNull(database.requestDao().getGitSignRequest(requestId))
        assertEquals(InboxRequestState.WAITING.storedName, request.state)
        assertNotNull(request.responseJson)
        assertEquals(ApprovalDecision.APPROVED.storedName, signing.decision)
        assertNotNull(database.requestDao().getRequestPsk(requestId))
        assertTrue(
            audit.observeEvents().first().any {
                it.relayRequestId == requestId &&
                    it.type == AuditEventType.GIT_SIGN_DECIDED &&
                    it.outcome == AuditOutcome.APPROVED
            }
        )
    }

    @Test
    fun askAiIntakeMapsEveryRelayVerdictToItsDurableOutcome() = runTest {
        val metadata = createSigningSecret(SecretApprovalMode.ASK_AI)
        val token = ByteArray(32) { it.toByte() }
        insertParent(
            secretDetailsJson = Json.encodeToString(metadata),
            invocationTokenHash = invocationTokenHash(token),
            providedSecretsJson = sshSecretFactsJson(),
        )
        val reviewer = RecordingApprovalReviewer()
        val target =
            requests(
                auditSink = audit,
                credentialSource = StaticCredentialSource(credentials()),
                reviewer = reviewer,
            )
        val outcomes =
            listOf(
                Triple(
                    RelayApprovalReviewDecision.ASK_USER,
                    InboxRequestState.ACTION_REQUIRED,
                    null,
                ),
                Triple(
                    RelayApprovalReviewDecision.DENY,
                    InboxRequestState.WAITING,
                    ApprovalDecision.DENIED,
                ),
                Triple(
                    RelayApprovalReviewDecision.APPROVE,
                    InboxRequestState.WAITING,
                    ApprovalDecision.APPROVED,
                ),
            )

        outcomes.forEachIndexed { index, (relayDecision, expectedState, expectedDecision) ->
            val requestId = "git-ai-verdict-$index"
            val explanation =
                "  Review $index: \"${relayDecision.name}\".\nDetailed explanation — unchanged.  "
            reviewer.result = reviewed(relayDecision, explanation)
            var responsePlaintext: ByteArray? = null
            var pendingReview: PendingAiReview? = null
            val processed =
                target.processIncoming(
                    client = client(),
                    relayRequestId = requestId,
                    requestPayload = Json.parseToJsonElement("{}"),
                    plaintext = gitSignPlaintext(token),
                    acceptedPsks = acceptedPsks(requestId),
                    credentials = credentials(),
                    sealResponse = {
                        responsePlaintext = it
                        Json.parseToJsonElement(RESPONSE_JSON)
                    },
                    launchAiReview = { launchedId, _, review, complete ->
                        assertEquals(requestId, launchedId)
                        pendingReview = PendingAiReview(review, complete)
                        true
                    },
                )

            assertEquals(ProcessedRelayMessage, processed)
            assertEquals(
                InboxRequestState.REVIEWING.storedName,
                database.requestDao().getRequestById(requestId)?.state,
            )
            val pending = checkNotNull(pendingReview)
            val mappedReview = pending.review()
            assertEquals(relayDecision.toAiDecision(), mappedReview.review.decision)
            pending.complete(mappedReview)

            val storedRequest = checkNotNull(database.requestDao().getRequestById(requestId))
            val storedGitSign = checkNotNull(database.requestDao().getGitSignRequest(requestId))
            val evaluation =
                Json.decodeFromString<ApprovalEvaluation>(
                    checkNotNull(storedGitSign.approvalEvaluationJson)
                )
            assertEquals(expectedState.storedName, storedRequest.state)
            assertEquals(expectedDecision?.storedName, storedGitSign.decision)
            assertEquals(relayDecision.toAiDecision(), evaluation.aiReview?.decision)
            if (relayDecision == RelayApprovalReviewDecision.APPROVE) {
                assertNull(storedGitSign.completionReason)
                assertNull(storedGitSign.completionMessage)
                assertTrue(
                    audit.observeEvents().first().any {
                        it.relayRequestId == requestId &&
                            it.type == AuditEventType.GIT_SIGN_DECIDED &&
                            it.outcome == AuditOutcome.APPROVED
                    }
                )
            }
            if (relayDecision == RelayApprovalReviewDecision.DENY) {
                val response =
                    Json.parseToJsonElement(checkNotNull(responsePlaintext).decodeToString())
                        .jsonObject
                assertEquals("DENIED", response.getValue("result").jsonPrimitive.content)
                assertEquals("POLICY_DENIED", response.getValue("reason").jsonPrimitive.content)
                assertEquals(explanation, response.getValue("message").jsonPrimitive.content)
                assertEquals("POLICY_DENIED", storedGitSign.completionReason)
                assertEquals(explanation, storedGitSign.completionMessage)
            }
            assertEquals(
                expectedDecision != null,
                storedRequest.responseJson != null,
            )
        }

        assertEquals(3, reviewer.requests.size)
        reviewer.requests.forEach { call ->
            assertEquals(DEVICE_ID, call.deviceId)
            assertEquals("device-token", call.deviceToken)
            assertEquals(ApprovalReviewOperation.GIT_SIGN, call.request.facts.operation)
            assertEquals(SECRET_NAME, call.request.facts.secret)
            assertEquals(SECRET_NAME, call.request.instructions.secrets.keys.single())
        }
    }

    @Test
    fun manualApprovalRejectsMalformedStoredParentMetadataBeforeSealing() = runTest {
        createSigningSecret(SecretApprovalMode.ASK_ME)
        insertParent(secretDetailsJson = "not-json")
        val requestId = "git-malformed-parent-manual"
        val evaluation = currentGitSignEvaluation()
        val target = requests(audit)
        receivePending(
            target,
            requestId,
            evaluationJson = Json.encodeToString(evaluation),
        )
        var sealed = false

        assertEquals(
            RequestDecisionResult.ParentUnavailable,
            target.approve(
                requestId,
                allowTemporaryAccess = false,
                sealResponse = { _, _ ->
                    sealed = true
                    Json.parseToJsonElement(RESPONSE_JSON)
                },
            ),
        )
        assertFalse(sealed)
        assertEquals(
            InboxRequestState.ACTION_REQUIRED.storedName,
            database.requestDao().getRequestById(requestId)?.state,
        )
        assertNull(database.requestDao().getRequestById(requestId)?.responseJson)
        assertNull(database.requestDao().getGitSignRequest(requestId)?.decision)
    }

    @Test
    fun manualApprovalRequiresRestartAfterTheInvocationSshKeyChanges() = runTest {
        val invocationSecrets = createSigningSecret(SecretApprovalMode.ASK_ME)
        insertParent(secretDetailsJson = Json.encodeToString(invocationSecrets))
        val replacement = secrets.generateSshKey(SshKeyAlgorithm.ED25519, "replacement@example")
        assertTrue(secrets.replaceSshKey(SECRET_ID, replacement) is SaveSshSecretResult.Saved)
        val requestId = "git-key-changed"
        val target = requests(audit)
        receivePending(
            target,
            requestId,
            evaluationJson = Json.encodeToString(currentGitSignEvaluation()),
        )
        var sealed = false

        assertEquals(
            RequestDecisionResult.SecretChangedSinceInvocation,
            target.approve(
                requestId,
                allowTemporaryAccess = false,
                sealResponse = { _, _ ->
                    sealed = true
                    Json.parseToJsonElement(RESPONSE_JSON)
                },
            ),
        )
        assertTrue(sealed)
        assertEquals(
            ApprovalDecision.DENIED.storedName,
            database.requestDao().getGitSignRequest(requestId)?.decision,
        )
    }

    @Test
    fun completedParentAllowsManualApproval() = runTest {
        val metadata = createSigningSecret(SecretApprovalMode.ASK_ME)
        insertParent(secretDetailsJson = Json.encodeToString(metadata))
        val requestId = "git-stale-parent-decision"
        val target = requests(audit)
        receivePending(
            target,
            requestId,
            evaluationJson = Json.encodeToString(currentGitSignEvaluation()),
        )
        var sealed = false
        val seal: suspend (InboxRequestEntity, ByteArray) -> JsonElement? = { _, _ ->
            sealed = true
            Json.parseToJsonElement(RESPONSE_JSON)
        }

        assertEquals(
            RequestDecisionResult.Decided,
            target.approve(requestId, allowTemporaryAccess = false, sealResponse = seal),
        )
        assertTrue(sealed)
        assertEquals(
            ApprovalDecision.APPROVED.storedName,
            database.requestDao().getGitSignRequest(requestId)?.decision,
        )
    }

    @Test
    fun manualDenialAndAuditCommitOrRollBackTogether() = runTest {
        insertParent()
        val requestId = "git-denial"
        val regular = requests(audit)
        receivePending(regular, requestId)
        val auditCount = audit.observeEvents().first().size
        val seal: suspend (InboxRequestEntity, ByteArray) -> JsonElement? = { _, _ ->
            Json.parseToJsonElement(RESPONSE_JSON)
        }

        assertTrue(
            runCatching {
                requests(InsertThenFailAuditSink(audit)).deny(requestId, seal)
            }
                .isFailure
        )
        assertEquals(
            InboxRequestState.ACTION_REQUIRED.storedName,
            database.requestDao().getRequestById(requestId)?.state,
        )
        assertNull(database.requestDao().getGitSignRequest(requestId)?.decision)
        assertEquals(auditCount, audit.observeEvents().first().size)

        assertEquals(RequestDecisionResult.Decided, regular.deny(requestId, seal))
        val decided = checkNotNull(database.requestDao().getGitSignRequest(requestId))
        assertEquals(ApprovalDecision.DENIED.storedName, decided.decision)
        assertEquals(GIT_SIGN_DENIAL_MESSAGE, decided.completionMessage)
        assertEquals(auditCount + 1, audit.observeEvents().first().size)
        assertEquals(
            AuditDecisionSource.USER,
            audit
                .observeEvents()
                .first()
                .first { it.type == AuditEventType.GIT_SIGN_DECIDED }
                .decisionSource,
        )

        val competingRequestId = "git-competing-denial"
        receivePending(regular, competingRequestId)
        val innerResponse = Json.parseToJsonElement("""{"ciphertext":"inner"}""")
        val outerResponse = Json.parseToJsonElement("""{"ciphertext":"outer"}""")
        var competingResult: RequestDecisionResult? = null
        assertEquals(
            RequestDecisionResult.NotPending,
            regular.deny(competingRequestId) { _, _ ->
                competingResult = regular.deny(competingRequestId) { _, _ -> innerResponse }
                outerResponse
            },
        )
        assertEquals(RequestDecisionResult.Decided, competingResult)
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
        val finalRequest =
            reviewing.copy(
                state = InboxRequestState.WAITING.storedName,
                responseJson = RESPONSE_JSON,
            )
        val finalGitSign =
            storedGitSign.copy(
                approvalEvaluationJson = FINAL_EVALUATION_JSON,
                decision = ApprovalDecision.DENIED.storedName,
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
                requests(InsertThenFailAuditSink(audit))
                    .finishAiReview(
                        request = finalRequest,
                        gitSign =
                            storedGitSign.copy(approvalEvaluationJson = FINAL_EVALUATION_JSON),
                        authorization = authorizationCommitment(),
                        aiReviewAudit = aiReviewAudit(requestId, AuditOutcome.DEFERRED),
                        automaticDecisionAudit = null,
                    )
            }
                .isFailure
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
                request =
                    finalRequest.copy(
                        state = InboxRequestState.WAITING.storedName,
                        responseJson = RESPONSE_JSON,
                    ),
                gitSign =
                    storedGitSign.copy(
                        approvalEvaluationJson = FINAL_EVALUATION_JSON,
                        decision = ApprovalDecision.DENIED.storedName,
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
            secrets.saveApprovalMode(SECRET_ID, SecretApprovalMode.ASK_ME),
        )
        val description = secrets.describeRequestedSecrets(listOf(SECRET_NAME))
        insertParent(Json.encodeToString(description.secrets))
        val policy =
            secrets
                .approvalPoliciesForNames(
                    listOf(SECRET_NAME),
                    CLIENT_ID,
                    TemporaryAccessOperation.GIT_SIGN,
                )
                .single()
        val evaluation =
            ApprovalEvaluation(
                secrets =
                    listOf(
                        SecretApprovalEvaluation(
                            secretId = policy.secretId,
                            secretName = policy.secretName,
                            action = ApprovalAction.ASK_ME,
                            temporaryAccessEligible = true,
                            revision = policy.revision,
                        )
                    )
            )
        val requestId = "git-temporary"
        receivePending(
            requests(audit),
            requestId,
            evaluationJson = Json.encodeToString(evaluation),
        )
        val eventCount = audit.observeEvents().first().size
        val seal: suspend (InboxRequestEntity, ByteArray) -> JsonElement? = { _, _ ->
            Json.parseToJsonElement(RESPONSE_JSON)
        }

        assertTrue(
            runCatching {
                requests(InsertThenFailAuditSink(audit))
                    .approve(
                        requestId,
                        allowTemporaryAccess = true,
                        sealResponse = seal,
                    )
            }
                .isFailure
        )
        assertNull(database.requestDao().getGitSignRequest(requestId)?.decision)
        assertTrue(secrets.observeTemporaryAccessGrants().first().isEmpty())
        assertEquals(eventCount, audit.observeEvents().first().size)

        assertEquals(
            RequestDecisionResult.Decided,
            requests(audit).approve(requestId, allowTemporaryAccess = true, sealResponse = seal),
        )
        val decided = checkNotNull(database.requestDao().getGitSignRequest(requestId))
        assertEquals(ApprovalDecision.APPROVED.storedName, decided.decision)
        assertEquals(1, secrets.observeTemporaryAccessGrants().first().size)
        val events = audit.observeEvents().first()
        assertEquals(
            setOf(
                AuditEventType.TEMPORARY_ACCESS_ALLOWED,
                AuditEventType.GIT_SIGN_DECIDED,
            ),
            events.take(2).map { it.type }.toSet(),
        )
        assertEquals(
            AuditDecisionSource.TEMPORARY_ACCESS,
            events.first { it.type == AuditEventType.GIT_SIGN_DECIDED }.decisionSource,
        )
    }

    @Test
    fun approvalAndTemporaryApprovalRejectAClientRevokedWhileSealing() = runTest {
        insertParent(Json.encodeToString(createSigningSecret(SecretApprovalMode.ASK_ME)))
        val requestId = "git-client-race"
        receivePending(
            requests(audit),
            requestId,
            evaluationJson = Json.encodeToString(currentGitSignEvaluation()),
        )
        val eventCount = audit.observeEvents().first().size
        for (allowTemporaryAccess in listOf(false, true)) {
            assertEquals(
                RequestDecisionResult.ApprovalChanged,
                requests(audit).approve(requestId, allowTemporaryAccess) { _, _ ->
                    database
                        .requestDao()
                        .updateClient(client().copy(desiredRelayClientState = "revoked"))
                    Json.parseToJsonElement(RESPONSE_JSON)
                },
            )
            assertNull(database.requestDao().getRequestById(requestId)?.responseJson)
            assertNull(database.requestDao().getGitSignRequest(requestId)?.decision)
            assertTrue(secrets.observeTemporaryAccessGrants().first().isEmpty())
            assertEquals(eventCount, audit.observeEvents().first().size)
            database.requestDao().updateClient(client())
        }
    }

    @Test
    fun completionIsSanitizedAtomicAndEveryTerminalReplayIsAcknowledged() = runTest {
        insertParent()
        val requestId = "git-completion"
        val regular = requests(audit)
        receivePending(regular, requestId)
        val request = checkNotNull(database.requestDao().getRequestById(requestId))
        val malicious = "raw-client-controlled-message"
        val plaintext = abortedCompletionPlaintext(malicious)
        val eventCount = audit.observeEvents().first().size

        assertTrue(
            runCatching {
                requests(InsertThenFailAuditSink(audit)).complete(request) {
                    CompletionOpenResult.Opened(plaintext)
                }
            }
                .isFailure
        )
        assertNull(database.requestDao().getRequestById(requestId)?.completedAt)
        assertNotNull(database.requestDao().getRequestPsk(requestId))
        assertEquals(eventCount, audit.observeEvents().first().size)

        assertTrue(regular.complete(request) { CompletionOpenResult.Opened(plaintext) })
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
        assertTrue(regular.complete(request) { error("Must not reopen") })
        assertNull(database.requestDao().getRequestPsk(requestId))
        assertTrue(regular.complete(completed) { error("Must not reopen") })
        assertEquals(auditCount, audit.observeEvents().first().size)

        val racingRequestId = "git-completion-race"
        receivePending(regular, racingRequestId)
        val racingRequest = checkNotNull(database.requestDao().getRequestById(racingRequestId))
        assertTrue(
            regular.complete(racingRequest) {
                assertTrue(
                    regular.complete(racingRequest) {
                        CompletionOpenResult.Opened(plaintext)
                    }
                )
                CompletionOpenResult.RetryLater
            }
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
            }
                .isFailure
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

    private suspend fun insertParent(
        secretDetailsJson: String = "[]",
        invocationTokenHash: ByteArray = ByteArray(32),
        providedSecretsJson: String? = null,
    ) {
        database.requestDao().insertRequest(parentRequest())
        database
            .requestDao()
            .insertSecretUseRequestRow(
                parentInvocation(secretDetailsJson, invocationTokenHash, providedSecretsJson)
            )
    }

    private suspend fun createSigningSecret(
        approvalMode: SecretApprovalMode
    ): List<dev.agentknock.storage.secret.SecretMetadata> {
        val key = secrets.generateSshKey(SshKeyAlgorithm.ED25519, "test@example")
        assertTrue(
            secrets.createSshSecret(SECRET_NAME, "Signing key", key) is CreateSecretResult.Created
        )
        assertEquals(
            SaveSecretResult.SAVED,
            secrets.saveApprovalMode(SECRET_ID, approvalMode),
        )
        return secrets.describeRequestedSecrets(listOf(SECRET_NAME)).secrets
    }

    private suspend fun currentGitSignEvaluation(): ApprovalEvaluation {
        val policy =
            secrets
                .approvalPoliciesForNames(
                    listOf(SECRET_NAME),
                    CLIENT_ID,
                    TemporaryAccessOperation.GIT_SIGN,
                )
                .single()
        return dev.agentknock.storage.approval.ApprovalEvaluation(listOf(policy.evaluate()))
    }

    private fun sshSecretFactsJson(): String =
        storedJson.encodeToString<Map<String, ApprovalReviewSecretFacts>>(
            mapOf(SECRET_NAME to ApprovalReviewSshSecretFacts)
        )

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

    private fun requests(
        auditSink: AuditSink,
        credentialSource: RelayDeviceCredentialSource = UnavailableCredentialSource,
        reviewer: RelayApprovalReviewClient = FailingApprovalReviewer,
    ) =
        GitSigningRequests(
            dao = database.requestDao(),
            secrets = secrets,
            deviceCredentials = credentialSource,
            approvalReviewer = reviewer,
            subscription = subscription.repository,
            audit = auditSink,
            writeTransaction = RoomWriteTransaction(database),
            currentTimeMillis = { NOW },
        )

    private fun parentRequest() =
        InboxRequestEntity(
            id = PARENT_ID,
            parentRequestId = null,
            deviceIdentityId = DEVICE_IDENTITY_ID,
            clientId = CLIENT_ID,
            clientNameSnapshot = "Test client",
            clientSoftwareJson = SOFTWARE_JSON,
            kind = RequestKind.SECRET_USE.storedName,
            state = InboxRequestState.COMPLETED.storedName,
            listed = true,
            requestJson = "{}",
            responseJson = RESPONSE_JSON,
            error = null,
            receivedAt = NOW - 1,
            completedAt = NOW,
            exchangeEndedAt = NOW,
            responseOutboxFinished = true,
        )

    private fun request(requestId: String) =
        InboxRequestEntity(
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
            error = null,
            receivedAt = NOW,
            completedAt = null,
            exchangeEndedAt = null,
            responseOutboxFinished = false,
        )

    private fun parentInvocation(
        secretDetailsJson: String,
        invocationTokenHash: ByteArray,
        providedSecretsJson: String?,
    ) =
        SecretUseRequestEntity(
            requestId = PARENT_ID,
            hostname = "test",
            platform = "linux",
            architecture = "x86_64",
            machineId = null,
            osVersion = null,
            invocationTokenHash = invocationTokenHash,
            containsSensitiveMaterial = true,
            secretsJson = Json.encodeToString(listOf(SECRET_NAME)),
            secretDetailsJson = secretDetailsJson,
            providedSecretsJson = providedSecretsJson,
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

    private fun gitSign(requestId: String) =
        GitSignRequestEntity(
            requestId = requestId,
            secretName = SECRET_NAME,
            message = "commit to sign".encodeToByteArray(),
            repositoryJson = null,
            approvalEvaluationJson = null,
            decision = null,
            completionResult = null,
            completionReason = null,
            completionMessage = null,
            decidedAt = null,
        )

    private fun client() =
        ClientEntity(
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

    private fun credentials() =
        RelayDeviceCredentials(
            deviceIdentityId = DEVICE_IDENTITY_ID,
            address = "quiet-river-maple",
            addressId = "address-id",
            deviceId = DEVICE_ID,
            deviceKey =
                DeviceKeyAccess(Dispatchers.Unconfined) {
                    DecryptionResult.Plaintext(ByteArray(32))
                },
            deviceToken = "device-token",
        )

    private fun gitSignPlaintext(invocationToken: ByteArray): ByteArray {
        val encodedToken = Base64.getEncoder().encodeToString(invocationToken)
        val encodedMessage =
            Base64.getEncoder().encodeToString("commit to sign".encodeToByteArray())
        return """
            {
              $SOFTWARE_FIELDS,
              "method":"GitSign",
              "invocation_id":"$PARENT_ID",
              "invocation_token":"$encodedToken",
              "secret":"$SECRET_NAME",
              "message":"$encodedMessage"
            }
        """
            .trimIndent()
            .encodeToByteArray()
    }

    private fun acceptedPsks(requestId: String) =
        AcceptedRequestPsks(
            requestPsk =
                RequestPskEntity(
                    requestId = requestId,
                    encryptedPsk =
                        EncryptedValue(
                            formatVersion = 1,
                            keyId = KEY_ID,
                            nonce = ByteArray(12),
                            ciphertext = byteArrayOf(1),
                        ),
                ),
            currentClientPsk = null,
            previousClientPsk = null,
        )

    private fun secret(name: String) =
        SecretEntity(
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
            instructions =
                AuthorizationInstructionsCommitment(
                    deviceIdentityId = DEVICE_IDENTITY_ID,
                    deviceInstructions = "",
                    clientId = CLIENT_ID,
                    clientName = clientName,
                    clientInstructions = "",
                ),
        )

    private fun decisionAudit(requestId: String, outcome: AuditOutcome) =
        AuditRecord(
            type = AuditEventType.GIT_SIGN_DECIDED,
            outcome = outcome,
            decisionSource = AuditDecisionSource.VALIDATION,
            subject = SECRET_NAME,
            clientId = CLIENT_ID,
            clientName = "Test client",
            relayRequestId = requestId,
        )

    private fun aiReviewAudit(requestId: String, outcome: AuditOutcome) =
        AuditRecord(
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

    private class InsertThenFailAuditSink(private val delegate: AuditSink) : AuditSink {
        override suspend fun record(record: AuditRecord) {
            delegate.record(record)
            error("Injected audit failure")
        }

        override suspend fun append(records: List<AuditRecord>, occurredAt: Long) {
            delegate.append(records, occurredAt)
            error("Injected audit failure")
        }
    }

    private object UnavailableCredentialSource : RelayDeviceCredentialSource {
        override suspend fun activeDeviceCredentials():
            DeviceCredentialResult<RelayDeviceCredentials>? = null

        override suspend fun deviceCredentials(
            deviceIdentityId: String
        ): DeviceCredentialResult<RelayDeviceCredentials>? = null
    }

    private object FailingApprovalReviewer : RelayApprovalReviewClient {
        override suspend fun review(
            deviceId: String,
            deviceToken: String,
            request: ApprovalReviewRequest,
        ): Nothing = error("AI review is not expected in this test")
    }

    private class StaticCredentialSource(private val credentials: RelayDeviceCredentials) :
        RelayDeviceCredentialSource {
        override suspend fun activeDeviceCredentials():
            DeviceCredentialResult<RelayDeviceCredentials>? =
            DeviceCredentialResult.Available(credentials)

        override suspend fun deviceCredentials(
            deviceIdentityId: String
        ): DeviceCredentialResult<RelayDeviceCredentials>? =
            if (deviceIdentityId == credentials.deviceIdentityId) {
                DeviceCredentialResult.Available(credentials)
            } else {
                null
            }
    }

    private data class ApprovalReviewCall(
        val deviceId: String,
        val deviceToken: String,
        val request: ApprovalReviewRequest,
    )

    private class RecordingApprovalReviewer : RelayApprovalReviewClient {
        lateinit var result: RelayApprovalReviewResult
        val requests = mutableListOf<ApprovalReviewCall>()

        override suspend fun review(
            deviceId: String,
            deviceToken: String,
            request: ApprovalReviewRequest,
        ): RelayApprovalReviewResult {
            requests += ApprovalReviewCall(deviceId, deviceToken, request)
            return result
        }
    }

    private data class PendingAiReview(
        val review: suspend () -> AiReviewAttempt,
        val complete: suspend (AiReviewAttempt) -> Unit,
    )

    private fun reviewed(
        decision: RelayApprovalReviewDecision,
        explanation: String,
    ): RelayApprovalReviewResult =
        RelayEndpointResult.Success(RelayApprovalReview(decision, explanation))

    private fun RelayApprovalReviewDecision.toAiDecision(): AiReviewDecision =
        when (this) {
            RelayApprovalReviewDecision.APPROVE -> AiReviewDecision.APPROVE
            RelayApprovalReviewDecision.DENY -> AiReviewDecision.DENY
            RelayApprovalReviewDecision.ASK_USER -> AiReviewDecision.ASK_USER
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
