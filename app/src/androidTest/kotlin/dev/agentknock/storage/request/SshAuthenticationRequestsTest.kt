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
import dev.agentknock.storage.secret.SecretApprovalMode
import dev.agentknock.storage.secret.SecretEntity
import dev.agentknock.storage.secret.SecretRepository
import dev.agentknock.storage.secret.SshKeyAlgorithm
import dev.agentknock.storage.secret.SshPrivateKey
import dev.agentknock.storage.secret.TemporaryAccessOperation
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
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
class SshAuthenticationRequestsTest {
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
        val key = createAuthenticationSecret(SecretApprovalMode.ASK_AI)
        val metadata = secrets.describeRequestedSecrets(listOf(SECRET_NAME)).secrets
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
                plaintext = sshAuthenticationPlaintext(token, key),
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
        val stored = checkNotNull(database.requestDao().getSshAuthenticationRequest(requestId))
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
                    it.relayRequestId == requestId &&
                        it.type == AuditEventType.SSH_AUTHENTICATION_AI_REVIEWED
                }
                .outcome,
        )
    }

    @Test
    fun incomingRequestAfterParentCompletionPersistsAnActionableRequest() = runTest {
        val key = createAuthenticationSecret(SecretApprovalMode.ASK_ME)
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
        val requestId = "ssh-valid-intake"

        val processed =
            requests(audit)
                .processIncoming(
                    client = client(),
                    relayRequestId = requestId,
                    requestPayload = Json.parseToJsonElement("""{"envelope":"request"}"""),
                    plaintext = sshAuthenticationPlaintext(token, key),
                    acceptedPsks = acceptedPsks(requestId),
                    credentials = credentials(),
                    sealResponse = { error("An actionable request must not be sealed") },
                    launchAiReview = { _, _, _, _ ->
                        error("Ask-me mode must not launch AI review")
                    },
                )

        assertEquals(ProcessedRelayMessage, processed)
        val request = checkNotNull(database.requestDao().getRequestById(requestId))
        val authentication =
            checkNotNull(database.requestDao().getSshAuthenticationRequest(requestId))
        assertEquals(InboxRequestState.ACTION_REQUIRED.storedName, request.state)
        assertEquals(PARENT_ID, request.parentRequestId)
        assertEquals(SECRET_NAME, authentication.secretName)
        assertEquals("deploy", authentication.username)
        assertEquals("publickey", authentication.method)
        assertEquals("ssh-ed25519", authentication.algorithm)
        assertTrue(authentication.message!!.contentEquals(authenticationMessage(key)))
        assertNotNull(authentication.approvalEvaluationJson)
        assertNotNull(database.requestDao().getRequestPsk(requestId))
    }

    @Test
    fun incomingRequestRejectsMalformedParentSecretMetadataCleanly() = runTest {
        val key = createAuthenticationSecret(SecretApprovalMode.ASK_ME)
        val token = ByteArray(32) { it.toByte() }
        insertParent(
            secretDetailsJson = "not-json",
            invocationTokenHash = invocationTokenHash(token),
        )
        assertIncomingRejected("ssh-malformed-parent", token, key)
    }

    @Test
    fun incomingRequestRejectsAnInvocationTokenMismatch() = runTest {
        val key = createAuthenticationSecret(SecretApprovalMode.ASK_ME)
        val expectedToken = ByteArray(32) { it.toByte() }
        val suppliedToken =
            expectedToken.copyOf().also {
                it[0] = (it[0] + 1).toByte()
            }
        insertParent(invocationTokenHash = invocationTokenHash(expectedToken))
        assertIncomingRejected("ssh-token-mismatch", suppliedToken, key)
    }

    @Test
    fun automaticApprovalPersistsAfterTheParentExchangeHasEnded() = runTest {
        val key = createAuthenticationSecret(SecretApprovalMode.APPROVE)
        val description = secrets.describeRequestedSecrets(listOf(SECRET_NAME))
        val token = ByteArray(32) { it.toByte() }
        insertParent(
            secretDetailsJson = Json.encodeToString(description.secrets),
            invocationTokenHash = invocationTokenHash(token),
        )
        val requestId = "ssh-parent-ended-during-seal"
        var sealed = false

        val processed =
            requests(audit)
                .processIncoming(
                    client = client(),
                    relayRequestId = requestId,
                    requestPayload = Json.parseToJsonElement("{}"),
                    plaintext = sshAuthenticationPlaintext(token, key),
                    acceptedPsks = acceptedPsks(requestId),
                    credentials = credentials(),
                    sealResponse = {
                        sealed = true
                        Json.parseToJsonElement(RESPONSE_JSON)
                    },
                    launchAiReview = { _, _, _, _ ->
                        error("Approve mode must not launch AI review")
                    },
                )

        assertEquals(ProcessedRelayMessage, processed)
        assertTrue(sealed)
        val request = checkNotNull(database.requestDao().getRequestById(requestId))
        val authentication =
            checkNotNull(database.requestDao().getSshAuthenticationRequest(requestId))
        assertEquals(InboxRequestState.WAITING.storedName, request.state)
        assertNotNull(request.responseJson)
        assertEquals(ApprovalDecision.APPROVED.storedName, authentication.decision)
        assertNull(authentication.message)
        assertNotNull(database.requestDao().getRequestPsk(requestId))
    }

    @Test
    fun askAiMapsRelayVerdictsAfterTheParentExchangeHasEnded() = runTest {
        val key = createAuthenticationSecret(SecretApprovalMode.ASK_AI)
        val description = secrets.describeRequestedSecrets(listOf(SECRET_NAME))
        val token = ByteArray(32) { it.toByte() }
        insertParent(
            secretDetailsJson = Json.encodeToString(description.secrets),
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
                RelayApprovalReviewDecision.ASK_USER to null,
                RelayApprovalReviewDecision.DENY to ApprovalDecision.DENIED,
                RelayApprovalReviewDecision.APPROVE to ApprovalDecision.APPROVED,
            )

        outcomes.forEachIndexed { index, (relayDecision, expectedDecision) ->
            val requestId = "ssh-ai-verdict-$index"
            val explanation =
                "  Review $index: \"${relayDecision.name}\".\nDetailed explanation — unchanged.  "
            reviewer.result = reviewed(relayDecision, explanation)
            var responsePlaintext: ByteArray? = null
            var pendingReview: PendingAiReview? = null
            assertEquals(
                ProcessedRelayMessage,
                target.processIncoming(
                    client = client(),
                    relayRequestId = requestId,
                    requestPayload = Json.parseToJsonElement("{}"),
                    plaintext = sshAuthenticationPlaintext(token, key),
                    acceptedPsks = acceptedPsks(requestId),
                    credentials = credentials(),
                    sealResponse = {
                        responsePlaintext = it
                        Json.parseToJsonElement(RESPONSE_JSON)
                    },
                    launchAiReview = { _, _, review, complete ->
                        pendingReview = PendingAiReview(review, complete)
                        true
                    },
                ),
            )
            val pending = checkNotNull(pendingReview)
            val mapped = pending.review()
            assertEquals(relayDecision.toAiDecision(), mapped.review.decision)
            pending.complete(mapped)
            val stored = checkNotNull(database.requestDao().getSshAuthenticationRequest(requestId))
            assertEquals(expectedDecision?.storedName, stored.decision)
            val request = checkNotNull(database.requestDao().getRequestById(requestId))
            assertEquals(
                if (expectedDecision == null) {
                    InboxRequestState.ACTION_REQUIRED.storedName
                } else {
                    InboxRequestState.WAITING.storedName
                },
                request.state,
            )
            assertEquals(expectedDecision != null, request.responseJson != null)
            if (expectedDecision != null) {
                assertNull(stored.message)
                val decisionAudit =
                    audit.observeEvents().first().single {
                        it.relayRequestId == requestId &&
                            it.type == AuditEventType.SSH_AUTHENTICATION_DECIDED
                    }
                assertEquals(
                    if (expectedDecision == ApprovalDecision.APPROVED) {
                        AuditOutcome.APPROVED
                    } else {
                        AuditOutcome.DENIED
                    },
                    decisionAudit.outcome,
                )
            }
            if (relayDecision == RelayApprovalReviewDecision.APPROVE) {
                assertNull(stored.completionReason)
                assertNull(stored.completionMessage)
            }
            if (relayDecision == RelayApprovalReviewDecision.DENY) {
                val response =
                    Json.parseToJsonElement(checkNotNull(responsePlaintext).decodeToString())
                        .jsonObject
                assertEquals("DENIED", response.getValue("result").jsonPrimitive.content)
                assertEquals("POLICY_DENIED", response.getValue("reason").jsonPrimitive.content)
                assertEquals(explanation, response.getValue("message").jsonPrimitive.content)
                assertEquals("POLICY_DENIED", stored.completionReason)
                assertEquals(explanation, stored.completionMessage)
            }
        }

        assertEquals(3, reviewer.requests.size)
        assertEquals(
            ApprovalReviewOperation.SSH_AUTHENTICATE,
            reviewer.requests.first().request.facts.operation,
        )
    }

    @Test
    fun manualApprovalWorksAfterTheParentExchangeHasEnded() = runTest {
        val key = createAuthenticationSecret(SecretApprovalMode.ASK_ME)
        val description = secrets.describeRequestedSecrets(listOf(SECRET_NAME))
        insertParent(Json.encodeToString(description.secrets))
        val parent = checkNotNull(database.requestDao().getRequestById(PARENT_ID))
        database.requestDao().updateRequest(parent.copy(clientSoftwareJson = "{}"))
        val requestId = "ssh-manual-completed-parent"
        receivePending(
            requests(audit),
            requestId,
            evaluationJson = currentAuthenticationEvaluationJson(),
            message = authenticationMessage(key),
        )
        var sealed = false
        val seal: suspend (InboxRequestEntity, ByteArray) -> JsonElement? = { _, _ ->
            sealed = true
            Json.parseToJsonElement(RESPONSE_JSON)
        }

        assertEquals(
            RequestDecisionResult.Decided,
            requests(audit).approve(requestId, false, seal),
        )
        assertTrue(sealed)
        assertEquals(
            ApprovalDecision.APPROVED.storedName,
            database.requestDao().getSshAuthenticationRequest(requestId)?.decision,
        )
    }

    @Test
    fun receiveIsAtomicAndAuthorizationRaceRetainsTheTranscript() = runTest {
        insertParent()
        val requestId = "ssh-receive"
        assertTrue(
            runCatching {
                requests(InsertThenFailAuditSink(audit))
                    .receive(
                        request(requestId),
                        authentication(requestId),
                        client(),
                        acceptedPsks(requestId),
                        authorization = null,
                        automaticDecisionAudit = null,
                    )
            }
                .isFailure
        )
        assertNull(database.requestDao().getRequestById(requestId))
        assertNull(database.requestDao().getSshAuthenticationRequest(requestId))
        assertNull(database.requestDao().getRequestPsk(requestId))

        val changedClient =
            checkNotNull(database.requestDao().getClient(CLIENT_ID))
                .copy(
                    name = "Renamed while receiving",
                    instructions = "Preserve these instructions.",
                    lastSeenAt = NOW + 1,
                )
        assertEquals(1, database.requestDao().updateClient(changedClient))
        database.secretDao().insertSecret(secret("appeared"))
        val stale =
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
                    authentication =
                        authentication(requestId)
                            .copy(
                                decision = ApprovalDecision.DENIED.storedName,
                                completionReason = "INVALID_REQUEST",
                                completionMessage = "Invalid request.",
                                decidedAt = NOW,
                            ),
                    client = client(),
                    acceptedPsks = acceptedPsks(requestId),
                    authorization = stale,
                    automaticDecisionAudit = decisionAudit(requestId, AuditOutcome.REJECTED),
                ),
        )
        val storedRequest = checkNotNull(database.requestDao().getRequestById(requestId))
        val storedAuthentication =
            checkNotNull(database.requestDao().getSshAuthenticationRequest(requestId))
        assertEquals(InboxRequestState.ACTION_REQUIRED.storedName, storedRequest.state)
        assertNull(storedRequest.responseJson)
        assertNull(storedAuthentication.decision)
        assertTrue(storedAuthentication.message.contentEquals(AUTHENTICATION_MESSAGE))
        assertEquals(changedClient, database.requestDao().getClient(CLIENT_ID))
        assertEquals(
            listOf(AuditEventType.SSH_AUTHENTICATION_RECEIVED),
            audit.observeEvents().first().map { it.type },
        )
    }

    @Test
    fun automaticReceiveClearsTheTranscriptAndPersistsItsAudit() = runTest {
        insertParent()
        val requestId = "ssh-automatic"
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
                    authentication =
                        authentication(requestId)
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
        val stored = checkNotNull(database.requestDao().getSshAuthenticationRequest(requestId))
        assertNull(stored.message)
        assertEquals(
            setOf(
                AuditEventType.SSH_AUTHENTICATION_RECEIVED,
                AuditEventType.SSH_AUTHENTICATION_DECIDED,
            ),
            audit.observeEvents().first().take(2).map { it.type }.toSet(),
        )
    }

    @Test
    fun aiFinalizationIsAtomicAndAuthorizationRacePreservesTheTranscript() = runTest {
        insertParent()
        val requestId = "ssh-ai"
        val regular = requests(audit)
        receivePending(
            regular,
            requestId,
            state = InboxRequestState.REVIEWING,
            evaluationJson = INITIAL_EVALUATION_JSON,
        )
        val reviewing = checkNotNull(database.requestDao().getRequestById(requestId))
        val stored = checkNotNull(database.requestDao().getSshAuthenticationRequest(requestId))
        val finalRequest =
            reviewing.copy(
                state = InboxRequestState.WAITING.storedName,
                responseJson = RESPONSE_JSON,
            )
        val finalAuthentication =
            stored.copy(
                approvalEvaluationJson = FINAL_EVALUATION_JSON,
                decision = ApprovalDecision.DENIED.storedName,
                completionReason = "POLICY_DENIED",
                completionMessage = "AI review denied authentication.",
                decidedAt = NOW,
            )
        assertTrue(
            runCatching {
                requests(InsertThenFailAuditSink(audit))
                    .finishAiReview(
                        finalRequest,
                        finalAuthentication,
                        authorization = authorizationCommitment(),
                        aiReviewAudit = aiReviewAudit(requestId, AuditOutcome.DENIED),
                        automaticDecisionAudit = decisionAudit(requestId, AuditOutcome.DENIED),
                    )
            }
                .isFailure
        )
        assertEquals(
            InboxRequestState.REVIEWING.storedName,
            database.requestDao().getRequestById(requestId)?.state,
        )
        assertTrue(
            database
                .requestDao()
                .getSshAuthenticationRequest(requestId)
                ?.message
                .contentEquals(AUTHENTICATION_MESSAGE)
        )

        assertEquals(
            ConditionalRequestUpdate.ACTION_REQUIRED,
            regular.finishAiReview(
                finalRequest,
                finalAuthentication,
                authorization = authorizationCommitment(clientName = "Old client name"),
                aiReviewAudit = aiReviewAudit(requestId, AuditOutcome.DENIED),
                automaticDecisionAudit = decisionAudit(requestId, AuditOutcome.DENIED),
            ),
        )
        val deferred = checkNotNull(database.requestDao().getSshAuthenticationRequest(requestId))
        assertNull(deferred.decision)
        assertEquals(INITIAL_EVALUATION_JSON, deferred.approvalEvaluationJson)
        assertTrue(deferred.message.contentEquals(AUTHENTICATION_MESSAGE))
        val aiAudit = audit.observeEvents().first().first()
        assertEquals(AuditEventType.SSH_AUTHENTICATION_AI_REVIEWED, aiAudit.type)
        assertEquals(AuditOutcome.DEFERRED, aiAudit.outcome)

        val appliedId = "ssh-ai-applied"
        receivePending(
            regular,
            appliedId,
            state = InboxRequestState.REVIEWING,
            evaluationJson = INITIAL_EVALUATION_JSON,
        )
        val appliedRequest = checkNotNull(database.requestDao().getRequestById(appliedId))
        val appliedAuthentication =
            checkNotNull(database.requestDao().getSshAuthenticationRequest(appliedId))
        assertEquals(
            ConditionalRequestUpdate.APPLIED,
            regular.finishAiReview(
                request =
                    appliedRequest.copy(
                        state = InboxRequestState.WAITING.storedName,
                        responseJson = RESPONSE_JSON,
                    ),
                authentication =
                    appliedAuthentication.copy(
                        approvalEvaluationJson = FINAL_EVALUATION_JSON,
                        decision = ApprovalDecision.DENIED.storedName,
                        completionReason = "POLICY_DENIED",
                        completionMessage = "AI review denied authentication.",
                        decidedAt = NOW,
                    ),
                authorization = authorizationCommitment(),
                aiReviewAudit = aiReviewAudit(appliedId, AuditOutcome.DENIED),
                automaticDecisionAudit = decisionAudit(appliedId, AuditOutcome.DENIED),
            ),
        )
        val applied = checkNotNull(database.requestDao().getSshAuthenticationRequest(appliedId))
        assertNull(applied.message)
        assertEquals(ApprovalDecision.DENIED.storedName, applied.decision)
    }

    @Test
    fun manualDenialClearsTheTranscriptAndCannotOverwriteACompetingDecision() = runTest {
        insertParent()
        val regular = requests(audit)
        val requestId = "ssh-denial"
        receivePending(regular, requestId)
        val eventCount = audit.observeEvents().first().size
        val seal: suspend (InboxRequestEntity, ByteArray) -> JsonElement? = { _, _ ->
            Json.parseToJsonElement(RESPONSE_JSON)
        }

        assertTrue(
            runCatching {
                requests(InsertThenFailAuditSink(audit)).deny(requestId, seal)
            }
                .isFailure
        )
        assertNull(database.requestDao().getSshAuthenticationRequest(requestId)?.decision)
        assertTrue(
            database
                .requestDao()
                .getSshAuthenticationRequest(requestId)
                ?.message
                .contentEquals(AUTHENTICATION_MESSAGE)
        )
        assertEquals(eventCount, audit.observeEvents().first().size)

        assertEquals(RequestDecisionResult.Decided, regular.deny(requestId, seal))
        val denied = checkNotNull(database.requestDao().getSshAuthenticationRequest(requestId))
        assertNull(denied.message)
        assertEquals(ApprovalDecision.DENIED.storedName, denied.decision)

        val competingId = "ssh-competing"
        receivePending(regular, competingId)
        val innerResponse = Json.parseToJsonElement("""{"ciphertext":"inner"}""")
        val outerResponse = Json.parseToJsonElement("""{"ciphertext":"outer"}""")
        var competingResult: RequestDecisionResult? = null
        assertEquals(
            RequestDecisionResult.NotPending,
            regular.deny(competingId) { _, _ ->
                competingResult = regular.deny(competingId) { _, _ -> innerResponse }
                outerResponse
            },
        )
        assertEquals(RequestDecisionResult.Decided, competingResult)
        assertEquals(
            innerResponse.toString(),
            database.requestDao().getRequestById(competingId)?.responseJson,
        )
    }

    @Test
    fun temporaryApprovalRevalidatesTheParentAndCommitsWithItsGrant() = runTest {
        val key = secrets.generateSshKey(SshKeyAlgorithm.ED25519, "test@example")
        assertTrue(
            secrets.createSshSecret(SECRET_NAME, "Authentication key", key)
                is CreateSecretResult.Created
        )
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
                    TemporaryAccessOperation.SSH_AUTHENTICATE,
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
        val requestId = "ssh-temporary"
        val message = authenticationMessage(key)
        val authentication =
            authentication(requestId, message, key.algorithm.publicName)
                .copy(approvalEvaluationJson = Json.encodeToString(evaluation))
        assertEquals(
            ConditionalRequestUpdate.APPLIED,
            requests(audit)
                .receive(
                    request(requestId),
                    authentication,
                    client(),
                    acceptedPsks(requestId),
                    authorization = null,
                    automaticDecisionAudit = null,
                ),
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
        assertNull(database.requestDao().getSshAuthenticationRequest(requestId)?.decision)
        assertTrue(secrets.observeTemporaryAccessGrants().first().isEmpty())
        assertEquals(eventCount, audit.observeEvents().first().size)

        val parentDetails = Json.encodeToString(description.secrets)
        for (allowTemporaryAccess in listOf(false, true)) {
            assertEquals(
                if (allowTemporaryAccess) {
                    RequestDecisionResult.ApprovalChanged
                } else {
                    RequestDecisionResult.ParentUnavailable
                },
                requests(audit)
                    .approve(
                        requestId,
                        allowTemporaryAccess,
                        sealResponse = { _, _ ->
                            assertEquals(
                                1,
                                database
                                    .requestDao()
                                    .updateSecretUseRequestRow(parentInvocation("[]")),
                            )
                            Json.parseToJsonElement(RESPONSE_JSON)
                        },
                    ),
            )
            assertNull(database.requestDao().getSshAuthenticationRequest(requestId)?.decision)
            assertTrue(
                database
                    .requestDao()
                    .getSshAuthenticationRequest(requestId)
                    ?.message
                    .contentEquals(message)
            )
            assertTrue(secrets.observeTemporaryAccessGrants().first().isEmpty())
            assertEquals(eventCount, audit.observeEvents().first().size)
            assertEquals(
                1,
                database.requestDao().updateSecretUseRequestRow(parentInvocation(parentDetails)),
            )
        }

        assertEquals(
            RequestDecisionResult.Decided,
            requests(audit).approve(requestId, allowTemporaryAccess = true, sealResponse = seal),
        )
        val approved = checkNotNull(database.requestDao().getSshAuthenticationRequest(requestId))
        assertNull(approved.message)
        assertEquals(ApprovalDecision.APPROVED.storedName, approved.decision)
        assertEquals(1, secrets.observeTemporaryAccessGrants().first().size)
        assertEquals(
            setOf(
                AuditEventType.TEMPORARY_ACCESS_ALLOWED,
                AuditEventType.SSH_AUTHENTICATION_DECIDED,
            ),
            audit.observeEvents().first().take(2).map { it.type }.toSet(),
        )
    }

    @Test
    fun completionIsSanitizedAtomicAndEveryTerminalReplayIsAcknowledged() = runTest {
        insertParent()
        val regular = requests(audit)
        val requestId = "ssh-completion"
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
        assertTrue(
            database
                .requestDao()
                .getSshAuthenticationRequest(requestId)
                ?.message
                .contentEquals(AUTHENTICATION_MESSAGE)
        )
        assertEquals(eventCount, audit.observeEvents().first().size)

        val retryableId = "ssh-unopened-completion"
        receivePending(regular, retryableId)
        val retryable = checkNotNull(database.requestDao().getRequestById(retryableId))
        assertFalse(regular.complete(retryable) { CompletionOpenResult.RetryLater })
        assertNull(database.requestDao().getRequestById(retryableId)?.completedAt)
        assertNotNull(database.requestDao().getRequestPsk(retryableId))
        assertTrue(
            database
                .requestDao()
                .getSshAuthenticationRequest(retryableId)
                ?.message
                .contentEquals(AUTHENTICATION_MESSAGE)
        )

        assertTrue(regular.complete(request) { CompletionOpenResult.Opened(plaintext) })
        val completed = checkNotNull(database.requestDao().getRequestById(requestId))
        val authentication =
            checkNotNull(database.requestDao().getSshAuthenticationRequest(requestId))
        assertNull(authentication.message)
        assertEquals(ApprovalCompletionResult.ABORTED.storedName, authentication.completionResult)
        assertNull(authentication.completionReason)
        assertNull(authentication.completionMessage)
        assertNull(database.requestDao().getRequestPsk(requestId))
        val completionAudit = audit.observeEvents().first().first()
        assertEquals(AuditEventType.SSH_AUTHENTICATION_COMPLETED, completionAudit.type)
        assertFalse(checkNotNull(completionAudit.detail).contains(malicious))

        val auditCount = audit.observeEvents().first().size
        assertTrue(regular.complete(request) { error("Must not reopen") })
        assertNull(database.requestDao().getRequestPsk(requestId))
        assertTrue(regular.complete(completed) { error("Must not reopen") })
        assertEquals(auditCount, audit.observeEvents().first().size)

        val malformedId = "ssh-malformed-completion"
        receivePending(regular, malformedId)
        val malformed = checkNotNull(database.requestDao().getRequestById(malformedId))
        assertTrue(
            regular.complete(malformed) {
                CompletionOpenResult.Opened("not a completion".encodeToByteArray())
            }
        )
        val failedRequest = checkNotNull(database.requestDao().getRequestById(malformedId))
        val failedAuthentication =
            checkNotNull(database.requestDao().getSshAuthenticationRequest(malformedId))
        assertEquals(InboxRequestState.COMPLETED.storedName, failedRequest.state)
        assertEquals(
            "SSH authentication completion could not be verified.",
            failedRequest.error,
        )
        assertNull(failedAuthentication.message)
        assertNull(failedAuthentication.completionResult)
        assertNull(failedAuthentication.completionReason)
        assertNull(failedAuthentication.completionMessage)
        assertNull(database.requestDao().getRequestPsk(malformedId))
        val malformedAudit =
            audit.observeEvents().first().single { event ->
                event.type == AuditEventType.SSH_AUTHENTICATION_COMPLETED &&
                    event.relayRequestId == malformedId
            }
        assertEquals(AuditOutcome.FAILED, malformedAudit.outcome)
        assertEquals(
            "SSH authentication completion could not be verified.",
            malformedAudit.detail,
        )

        val racingId = "ssh-completion-race"
        receivePending(regular, racingId)
        val racing = checkNotNull(database.requestDao().getRequestById(racingId))
        assertTrue(
            regular.complete(racing) {
                assertTrue(
                    regular.complete(racing) {
                        CompletionOpenResult.Opened(plaintext)
                    }
                )
                CompletionOpenResult.RetryLater
            }
        )
        assertNull(database.requestDao().getRequestPsk(racingId))

        val postOpenRaceId = "ssh-completion-post-open-race"
        receivePending(regular, postOpenRaceId)
        val postOpenRace = checkNotNull(database.requestDao().getRequestById(postOpenRaceId))
        val preRaceAuditCount = audit.observeEvents().first().size
        assertTrue(
            regular.complete(postOpenRace) {
                assertTrue(
                    regular.complete(postOpenRace) {
                        CompletionOpenResult.Opened(plaintext)
                    }
                )
                CompletionOpenResult.Opened(plaintext)
            }
        )
        assertNull(database.requestDao().getRequestPsk(postOpenRaceId))
        assertEquals(preRaceAuditCount + 1, audit.observeEvents().first().size)
    }

    @Test
    fun expiryStateAuditTranscriptAndKeyCommitOrRollBackTogether() = runTest {
        insertParent()
        val regular = requests(audit)
        val requestId = "ssh-expiry"
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
        assertTrue(
            database
                .requestDao()
                .getSshAuthenticationRequest(requestId)
                ?.message
                .contentEquals(AUTHENTICATION_MESSAGE)
        )
        assertEquals(eventCount, audit.observeEvents().first().size)

        regular.expire(request, EXPIRY_MESSAGE, NOW)
        val expired = checkNotNull(database.requestDao().getRequestById(requestId))
        assertEquals(InboxRequestState.COMPLETED.storedName, expired.state)
        assertEquals(EXPIRY_MESSAGE, expired.error)
        assertNull(database.requestDao().getSshAuthenticationRequest(requestId)?.message)
        assertNull(database.requestDao().getRequestPsk(requestId))
        val expiryAudit = audit.observeEvents().first().first()
        assertEquals(AuditEventType.SSH_AUTHENTICATION_COMPLETED, expiryAudit.type)
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

    private suspend fun createAuthenticationSecret(
        approvalMode: SecretApprovalMode
    ): SshPrivateKey {
        val key = secrets.generateSshKey(SshKeyAlgorithm.ED25519, "test@example")
        assertTrue(
            secrets.createSshSecret(SECRET_NAME, "Authentication key", key)
                is CreateSecretResult.Created
        )
        assertEquals(
            SaveSecretResult.SAVED,
            secrets.saveApprovalMode(SECRET_ID, approvalMode),
        )
        return key
    }

    private suspend fun assertIncomingRejected(
        requestId: String,
        suppliedToken: ByteArray,
        key: SshPrivateKey,
    ) {
        var sealed = false
        var launched = false
        val processed =
            requests(audit)
                .processIncoming(
                    client = client(),
                    relayRequestId = requestId,
                    requestPayload = Json.parseToJsonElement("{}"),
                    plaintext = sshAuthenticationPlaintext(suppliedToken, key),
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
        assertNull(database.requestDao().getSshAuthenticationRequest(requestId))
        assertNull(database.requestDao().getRequestPsk(requestId))
    }

    private suspend fun currentAuthenticationEvaluationJson(): String {
        val policy =
            secrets
                .approvalPoliciesForNames(
                    listOf(SECRET_NAME),
                    CLIENT_ID,
                    TemporaryAccessOperation.SSH_AUTHENTICATE,
                )
                .single()
        return Json.encodeToString(
            dev.agentknock.storage.approval.ApprovalEvaluation(listOf(policy.evaluate()))
        )
    }

    private fun sshSecretFactsJson(): String =
        storedJson.encodeToString<Map<String, ApprovalReviewSecretFacts>>(
            mapOf(SECRET_NAME to ApprovalReviewSshSecretFacts)
        )

    private suspend fun receivePending(
        target: SshAuthenticationRequests,
        requestId: String,
        state: InboxRequestState = InboxRequestState.ACTION_REQUIRED,
        evaluationJson: String? = null,
        message: ByteArray = AUTHENTICATION_MESSAGE,
    ) {
        assertEquals(
            ConditionalRequestUpdate.APPLIED,
            target.receive(
                request = request(requestId).copy(state = state.storedName),
                authentication =
                    authentication(requestId, message)
                        .copy(approvalEvaluationJson = evaluationJson),
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
        SshAuthenticationRequests(
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
            kind = RequestKind.SSH_AUTHENTICATE.storedName,
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
        secretDetailsJson: String = "[]",
        invocationTokenHash: ByteArray = ByteArray(32),
        providedSecretsJson: String? = null,
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
            command = "ssh",
            argumentsJson = "[]",
            workingDirectory = "/tmp/project",
            executablePath = "/usr/bin/ssh",
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

    private fun authentication(
        requestId: String,
        message: ByteArray = AUTHENTICATION_MESSAGE,
        algorithm: String = "ssh-ed25519",
    ) =
        SshAuthenticationRequestEntity(
            requestId = requestId,
            secretName = SECRET_NAME,
            message = message,
            username = "deploy",
            method = "publickey",
            algorithm = algorithm,
            hostKeyAlgorithm = null,
            hostKeyFingerprint = null,
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

    private fun sshAuthenticationPlaintext(
        invocationToken: ByteArray,
        key: SshPrivateKey,
    ): ByteArray {
        val token = Base64.getEncoder().encodeToString(invocationToken)
        val message = Base64.getEncoder().encodeToString(authenticationMessage(key))
        return """
            {
              $SOFTWARE_FIELDS,
              "method":"SshAuthenticate",
              "invocation_id":"$PARENT_ID",
              "invocation_token":"$token",
              "secret":"$SECRET_NAME",
              "message":"$message"
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
            type = AuditEventType.SSH_AUTHENTICATION_DECIDED,
            outcome = outcome,
            decisionSource = AuditDecisionSource.VALIDATION,
            subject = SECRET_NAME,
            clientId = CLIENT_ID,
            clientName = "Test client",
            relayRequestId = requestId,
        )

    private fun aiReviewAudit(requestId: String, outcome: AuditOutcome) =
        AuditRecord(
            type = AuditEventType.SSH_AUTHENTICATION_AI_REVIEWED,
            outcome = outcome,
            decisionSource = AuditDecisionSource.AI_REVIEW,
            subject = SECRET_NAME,
            clientId = CLIENT_ID,
            clientName = "Test client",
            relayRequestId = requestId,
        )

    private fun authenticationMessage(key: SshPrivateKey): ByteArray {
        val publicKeyBlob =
            sshStrings(
                key.algorithm.publicName.encodeToByteArray(),
                key.publicKey,
            )
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeSshString("session identifier".encodeToByteArray())
                output.writeByte(50)
                output.writeSshString("deploy".encodeToByteArray())
                output.writeSshString("ssh-connection".encodeToByteArray())
                output.writeSshString("publickey".encodeToByteArray())
                output.writeByte(1)
                output.writeSshString(key.algorithm.publicName.encodeToByteArray())
                output.writeSshString(publicKeyBlob)
            }
            bytes.toByteArray()
        }
    }

    private fun sshStrings(vararg values: ByteArray): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                values.forEach { output.writeSshString(it) }
            }
            bytes.toByteArray()
        }

    private fun DataOutputStream.writeSshString(value: ByteArray) {
        writeInt(value.size)
        write(value)
    }

    private fun abortedCompletionPlaintext(message: String): ByteArray =
        """{$SOFTWARE_FIELDS,"result":"ABORTED","reason":"CANCELLED","message":"$message"}"""
            .encodeToByteArray()

    private fun secret(name: String) =
        SecretEntity(
            id = "secret-$name",
            name = name,
            description = "",
            type = "environment",
            createdAt = 1,
            updatedAt = 1,
        )

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
        const val SECRET_NAME = "ssh-authentication"
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
        val AUTHENTICATION_MESSAGE = "authentication transcript".encodeToByteArray()
    }
}
