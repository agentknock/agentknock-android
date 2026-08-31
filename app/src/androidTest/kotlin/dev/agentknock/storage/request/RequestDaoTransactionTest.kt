package dev.agentknock.storage.request

import androidx.room3.Room
import androidx.room3.executeSQL
import androidx.room3.useWriterConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.agentknock.storage.AgentknockDatabase
import dev.agentknock.storage.crypto.AesGcmEncryption
import dev.agentknock.storage.crypto.DecryptionResult
import dev.agentknock.storage.crypto.EncryptionKeySource
import dev.agentknock.storage.crypto.EncryptionLocation
import dev.agentknock.storage.crypto.VaultKeyEntity
import dev.agentknock.storage.crypto.VaultKeyPurpose
import dev.agentknock.storage.crypto.EncryptedValue
import dev.agentknock.storage.secret.SecretClientApprovalOverrideEntity
import dev.agentknock.storage.secret.SecretEntity
import dev.agentknock.storage.secret.TemporaryAccessGrantEntity
import dev.agentknock.storage.device.DeviceIdentityEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RequestDaoTransactionTest {
    private lateinit var database: AgentknockDatabase
    private lateinit var dao: RequestDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AgentknockDatabase::class.java,
        ).build()
        dao = database.requestDao()
        runBlocking {
            database.vaultKeyDao().activate(
                VaultKeyEntity(
                    id = KEY_ID,
                    purpose = VaultKeyPurpose.DEVICE_STATE.storedName,
                    active = true,
                    createdAt = 1,
                    backing = "SOFTWARE",
                ),
            )
            database.deviceIdentityDao().insertIdentity(
                DeviceIdentityEntity(
                    id = DEVICE_IDENTITY_ID,
                    role = "active",
                    address = "write-leader-hungry",
                    deviceId = DEVICE_ID,
                    createdAt = 1,
                    claimedAt = 1,
                ),
            )
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun pairingPromotionAndItsFixedResponseAreAtomic() = runTest {
        val pending = pendingAttempt(withPendingPsk = true)
        dao.insertPairingRequest(rootRequest(), pending)
        val root = checkNotNull(dao.getRequestById(ROOT_REQUEST_ID))

        val failed = runCatching {
            dao.finishPairing(
                rootRequest = activatedRoot(root),
                attempt = completedAttempt(pending),
                client = activeClient(),
                clientPsk = clientPsk(CURRENT_SLOT, byteArrayOf(2)),
                finishRequest = finishRequest(root.id, root.id),
                requestPsk = requestPsk(root.id),
            )
        }
        assertTrue(failed.isFailure)
        assertEquals("waiting", dao.getRequestById(ROOT_REQUEST_ID)?.state)
        assertEquals("waiting_for_finish", dao.getPairingAttempt(ROOT_REQUEST_ID)?.state)
        assertArrayEquals(
            byteArrayOf(1),
            dao.getPairingAttempt(ROOT_REQUEST_ID)?.pendingPsk?.ciphertext,
        )
        assertNull(dao.getClient(CLIENT_ID))
        assertNull(dao.getClientPsk(CLIENT_ID, CURRENT_SLOT))
        assertNull(dao.getRequestById(FINISH_REQUEST_ID))

        dao.finishPairing(
            rootRequest = activatedRoot(root),
            attempt = completedAttempt(pending),
            client = activeClient(),
            clientPsk = clientPsk(CURRENT_SLOT, byteArrayOf(2)),
            finishRequest = finishRequest(FINISH_REQUEST_ID, root.id),
            requestPsk = requestPsk(FINISH_REQUEST_ID),
        )

        val storedFinish = checkNotNull(dao.getRequestById(FINISH_REQUEST_ID))
        assertEquals("completed", dao.getRequestById(ROOT_REQUEST_ID)?.state)
        val storedAttempt = checkNotNull(dao.getPairingAttempt(ROOT_REQUEST_ID))
        assertEquals("completed", storedAttempt.state)
        assertNull(storedAttempt.pendingPsk)
        assertEquals(RESPONSE_JSON, storedFinish.responseJson)
        assertNotNull(dao.getRequestPsk(storedFinish.id))
        assertNotNull(dao.getClient(CLIENT_ID))
        assertArrayEquals(
            byteArrayOf(2),
            dao.getClientPsk(CLIENT_ID, CURRENT_SLOT)?.encryptedPsk?.ciphertext,
        )
        assertNull(dao.getClientPsk(CLIENT_ID, PREVIOUS_SLOT))
    }

    @Test
    fun pairingRejectionErasesThePendingBindingWithoutCreatingAClient() = runTest {
        dao.insertPairingRequest(rootRequest(), pendingAttempt(withPendingPsk = true))
        val request = checkNotNull(dao.getRequestById(ROOT_REQUEST_ID))
        val attempt = checkNotNull(dao.getPairingAttempt(ROOT_REQUEST_ID))

        dao.rejectPairing(
            request = request.copy(state = "completed", completedAt = 2, updatedAt = 2),
            attempt = attempt.copy(
                desiredRelayClientState = "revoked",
                state = "rejected",
                pendingPsk = null,
                decidedAt = 2,
            ),
        )

        assertEquals("completed", dao.getRequestById(ROOT_REQUEST_ID)?.state)
        val rejected = checkNotNull(dao.getPairingAttempt(ROOT_REQUEST_ID))
        assertEquals("rejected", rejected.state)
        assertNull(rejected.pendingPsk)
        assertNull(dao.getClient(CLIENT_ID))
    }

    @Test
    fun nullableEncryptedValueRoundTripsOnlyAsACompleteQuartet() = runTest {
        val request = rootRequest()
        dao.insertPairingRequest(request, pendingAttempt())
        assertNull(checkNotNull(dao.getPairingAttempt(ROOT_REQUEST_ID)).pendingPsk)

        val complete = pendingAttempt(withPendingPsk = true)
        dao.updatePairingRequest(request, complete)

        val stored = checkNotNull(dao.getPairingAttempt(ROOT_REQUEST_ID)).pendingPsk
        checkNotNull(stored)
        assertEquals(1, stored.formatVersion)
        assertEquals(KEY_ID, stored.keyId)
        assertArrayEquals(ByteArray(12), stored.nonce)
        assertArrayEquals(byteArrayOf(1), stored.ciphertext)
    }

    @Test
    fun partialNullableEncryptedValueFailsLoudlyWhenRead() = runTest {
        dao.insertPairingRequest(rootRequest(), pendingAttempt())
        val columns = mapOf(
            "pending_psk_encryption_format" to "1",
            "pending_psk_encryption_key_id" to "'$KEY_ID'",
            "pending_psk_nonce" to "X'00'",
            "pending_psk_ciphertext" to "X'00'",
        )

        columns.keys.forEach { missingColumn ->
            database.useWriterConnection { connection ->
                connection.executeSQL(
                    "UPDATE pairing_attempts SET " +
                        columns.entries.joinToString { (column, value) ->
                            "$column = ${if (column == missingColumn) "NULL" else value}"
                        } +
                        " WHERE request_id = '$ROOT_REQUEST_ID'",
                )
            }

            val read = runCatching { dao.getPairingAttempt(ROOT_REQUEST_ID) }
            if (missingColumn == "pending_psk_encryption_format") {
                val partial = checkNotNull(read.getOrThrow()?.pendingPsk)
                assertEquals(0, partial.formatVersion)
                assertEquals(
                    DecryptionResult.UnsupportedFormat,
                    AesGcmEncryption(
                        object : EncryptionKeySource {
                            override fun get(keyId: String) =
                                error("Invalid metadata looked up a key")
                        },
                    ).decrypt(
                        partial,
                        EncryptionLocation(
                            "test",
                            ROOT_REQUEST_ID,
                            "pending_psk",
                        ),
                    ),
                )
            } else {
                assertTrue(
                    "A pending PSK with a missing $missingColumn column was accepted",
                    read.isFailure,
                )
            }
        }
    }

    @Test
    fun completedHistoryCanBePrunedWithoutDeletingTheDurableClient() = runTest {
        insertActivePairing()

        assertEquals(0, dao.clearCompletedHistory())
        assertFalse(checkNotNull(dao.getRequestById(ROOT_REQUEST_ID)).listed)
        assertNotNull(dao.getClient(CLIENT_ID))

        assertEquals(1, dao.deleteSettledHiddenRequests(receivedBefore = 2))
        assertNull(dao.getRequestById(ROOT_REQUEST_ID))
        assertNull(dao.getPairingAttempt(ROOT_REQUEST_ID))
        assertNotNull(dao.getClient(CLIENT_ID))
        assertNotNull(dao.getClientPsk(CLIENT_ID, CURRENT_SLOT))
    }

    @Test
    fun unresolvedRejectedPairingRevocationPreventsPruningUntilRelayAcknowledgesIt() = runTest {
        dao.insertPairingRequest(
            rootRequest().copy(
                state = "completed",
                listed = false,
                completedAt = 2,
                updatedAt = 2,
            ),
            pendingAttempt().copy(
                desiredRelayClientState = "revoked",
                relayClientState = "active",
                state = "rejected",
                decidedAt = 2,
            ),
        )

        assertEquals(0, dao.deleteSettledHiddenRequests(receivedBefore = 2))
        assertNotNull(dao.getRequestById(ROOT_REQUEST_ID))
        val attempt = checkNotNull(dao.getPairingAttempt(ROOT_REQUEST_ID))

        dao.updatePairingAttempt(attempt.copy(relayClientState = "revoked"))

        assertEquals(1, dao.deleteSettledHiddenRequests(receivedBefore = 2))
        assertNull(dao.getRequestById(ROOT_REQUEST_ID))
        assertNull(dao.getPairingAttempt(ROOT_REQUEST_ID))
    }

    @Test
    fun requestChronologyUsesReceivedTimeWithTheWireIdOnlyAsATieBreaker() = runTest {
        dao.insertRequest(
            rootRequest().copy(
                id = "request-z",
                kind = "invocation",
                state = "action_required",
                receivedAt = 1,
            ),
        )
        dao.insertRequest(
            rootRequest().copy(
                id = "request-a",
                kind = "invocation",
                state = "action_required",
                receivedAt = 2,
            ),
        )
        dao.insertRequest(
            rootRequest().copy(
                id = "request-b",
                kind = "invocation",
                state = "action_required",
                receivedAt = 2,
            ),
        )

        assertEquals(
            listOf("request-b", "request-a", "request-z"),
            dao.getActionRequiredRequests().map(InboxRequestEntity::id),
        )
    }

    @Test
    fun historyLimitNeverHidesPendingPairingsOrUploads() = runTest {
        val pendingInvocationId = "old-pending-invocation"
        dao.insertRequest(
            rootRequest().copy(
                id = pendingInvocationId,
                kind = "secret_use",
                state = "action_required",
                receivedAt = 0,
            ),
        )
        repeat(101) { index ->
            dao.insertRequest(
                rootRequest().copy(
                    id = "history-$index",
                    kind = "secret_use",
                    state = "completed",
                    receivedAt = (index + 10).toLong(),
                    updatedAt = (index + 10).toLong(),
                    completedAt = (index + 10).toLong(),
                ),
            )
        }

        val pairingId = "old-pending-pairing"
        dao.insertPairingRequest(
            rootRequest().copy(
                id = pairingId,
                clientId = "pending-pairing-client",
                kind = "pairing",
                state = "waiting",
                receivedAt = 1,
            ),
            pendingAttempt().copy(
                requestId = pairingId,
                clientId = "pending-pairing-client",
                state = "receiving",
                decidedAt = null,
            ),
        )
        val resolvedPairingId = "resolved-pairing"
        dao.insertPairingRequest(
            rootRequest().copy(
                id = resolvedPairingId,
                clientId = "resolved-pairing-client",
                kind = "pairing",
                state = "completed",
                receivedAt = 1_000,
                completedAt = 1_000,
            ),
            completedAttempt(pendingAttempt()).copy(
                requestId = resolvedPairingId,
                clientId = "resolved-pairing-client",
            ),
        )

        val uploadId = "old-pending-upload"
        dao.insertRequest(
            rootRequest().copy(
                id = uploadId,
                clientId = "upload-client",
                kind = "secret_upload",
                state = "action_required",
                receivedAt = 2,
            ),
        )
        dao.insertSecretUploadRequestRow(secretUploadRequest(uploadId, decision = null))
        val resolvedUploadId = "resolved-upload"
        dao.insertRequest(
            rootRequest().copy(
                id = resolvedUploadId,
                clientId = "resolved-upload-client",
                kind = "secret_upload",
                state = "completed",
                receivedAt = 1_001,
                completedAt = 1_001,
            ),
        )
        dao.insertSecretUploadRequestRow(
            secretUploadRequest(resolvedUploadId, decision = "approved"),
        )

        dao.trimCompletedHistory()

        val history = dao.observeListedRequests().first()
        assertEquals(101, history.size)
        assertTrue(history.all { it.kind == "secret_use" })
        assertTrue(history.any { it.id == pendingInvocationId })
        assertEquals(100, history.count { it.completedAt != null })
        assertEquals(
            listOf(pairingId),
            dao.observePendingPairingRequests().first().map(InboxRequestEntity::id),
        )
        assertEquals(
            listOf(uploadId),
            dao.observePendingSecretUploadRequests().first().map(InboxRequestEntity::id),
        )
        assertEquals(false, dao.getRequestById(resolvedPairingId)?.listed)
        assertEquals(false, dao.getRequestById(resolvedUploadId)?.listed)
    }

    @Test
    fun acknowledgementTimestampsAndRequestUpdateTimeNeverMoveBackward() = runTest {
        val requestId = "acknowledgement-request"
        dao.insertRequest(
            rootRequest().copy(
                id = requestId,
                kind = "unknown",
                state = "completed",
                updatedAt = 10,
                completedAt = 10,
                requestAcknowledgedAt = null,
                responseAcknowledgedAt = null,
                completionAcknowledgedAt = null,
            ),
        )

        dao.markRequestAcknowledged(requestId, 20)
        dao.markResponseAcknowledged(requestId, 21)
        dao.markCompletionAcknowledged(requestId, 22)
        dao.markRequestAcknowledged(requestId, 5)
        dao.markResponseAcknowledged(requestId, 6)
        dao.markCompletionAcknowledged(requestId, 7)

        val stored = checkNotNull(dao.getRequestById(requestId))
        assertEquals(20L, stored.requestAcknowledgedAt)
        assertEquals(21L, stored.responseAcknowledgedAt)
        assertEquals(22L, stored.completionAcknowledgedAt)
        assertEquals(22L, stored.updatedAt)
    }

    @Test
    fun settledParentRequestsArePrunedOnlyAfterTheirChildren() = runTest {
        val parent = rootRequest().copy(
            id = "parent-request",
            kind = "invocation",
            state = "completed",
            listed = false,
            completedAt = 2,
        )
        val child = parent.copy(
            id = "child-request",
            parentRequestId = parent.id,
            kind = "git_sign",
        )
        dao.insertRequest(parent)
        dao.insertRequest(child)

        assertEquals(1, dao.deleteSettledHiddenRequests(receivedBefore = 2))
        assertNotNull(dao.getRequestById(parent.id))
        assertNull(dao.getRequestById(child.id))

        assertEquals(1, dao.deleteSettledHiddenRequests(receivedBefore = 2))
        assertNull(dao.getRequestById(parent.id))
    }

    @Test
    fun pairingRemovalAndItsFixedResponseAreAtomic() = runTest {
        insertActivePairing()
        dao.insertClientPsk(clientPsk(PREVIOUS_SLOT, byteArrayOf(1)))
        database.secretDao().insertSecret(
            SecretEntity(
                id = "secret",
                name = "github",
                description = "",
                type = "environment",
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        database.secretDao().upsertTemporaryAccessGrants(
            listOf(
                TemporaryAccessGrantEntity(
                    secretId = "secret",
                    clientId = CLIENT_ID,
                    operation = "invocation",
                    expiresAt = 10_000,
                ),
            ),
        )
        val removal = pairingRemovalRequest(ROOT_REQUEST_ID)
        val revokedClient = checkNotNull(dao.getClient(CLIENT_ID)).copy(
            desiredRelayClientState = "revoked",
            updatedAt = 2,
        )

        val failed = runCatching {
            dao.insertPairingRemoval(
                request = removal,
                requestPsk = requestPsk(REMOVE_REQUEST_ID).let { requestPsk ->
                    requestPsk.copy(
                        encryptedPsk = requestPsk.encryptedPsk.copy(keyId = "missing-key"),
                    )
                },
                client = revokedClient,
            )
        }
        assertTrue(failed.isFailure)
        assertNull(dao.getRequestById(REMOVE_REQUEST_ID))
        assertNull(dao.getClient(CLIENT_ID)?.desiredRelayClientState)
        assertNotNull(dao.getClientPsk(CLIENT_ID, CURRENT_SLOT))
        assertNotNull(dao.getClientPsk(CLIENT_ID, PREVIOUS_SLOT))
        assertEquals(
            1,
            database.secretDao().getActiveTemporaryAccessGrants(
                CLIENT_ID,
                listOf("secret"),
                "invocation",
                0,
            ).size,
        )

        dao.insertPairingRemoval(
            request = removal,
            requestPsk = requestPsk(REMOVE_REQUEST_ID),
            client = revokedClient,
        )

        val storedRemoval = checkNotNull(dao.getRequestById(REMOVE_REQUEST_ID))
        assertEquals(RESPONSE_JSON, storedRemoval.responseJson)
        assertNotNull(dao.getRequestPsk(storedRemoval.id))
        assertEquals("revoked", dao.getClient(CLIENT_ID)?.desiredRelayClientState)
        assertNull(dao.getClientPsk(CLIENT_ID, CURRENT_SLOT))
        assertNull(dao.getClientPsk(CLIENT_ID, PREVIOUS_SLOT))
        assertTrue(
            database.secretDao().getActiveTemporaryAccessGrants(
                CLIENT_ID,
                listOf("secret"),
                "invocation",
                0,
            ).isEmpty(),
        )
    }

    @Test
    fun decidingASecretUploadDiscardsItsUploadedValues() = runTest {
        insertActivePairing()
        val requestId = UPLOAD_REQUEST_ID
        dao.insertSecretUploadRequest(
            request = rootRequest().copy(
                id = requestId,
                kind = "secret_upload",
                state = "action_required",
            ),
            secretUpload = SecretUploadRequestEntity(
                requestId = requestId,
                decision = null,
                mode = "CREATE",
                uploadedName = "test-secret",
                approvedName = null,
                descriptionProvided = false,
                description = null,
                secretType = "environment",
                targetSecretId = null,
                targetSecretRevision = null,
                summaryJson = "{\"variableNames\":[\"TOKEN\"]}",
                intakeError = null,
                decidedAt = null,
            ),
            client = checkNotNull(dao.getClient(CLIENT_ID)),
            environmentVariables = listOf(
                SecretUploadEnvironmentVariableEntity(
                    id = "upload-variable",
                    requestId = requestId,
                    name = "TOKEN",
                    sensitive = true,
                    encryptedValue = encryptedValue(byteArrayOf(4)),
                ),
            ),
            sshKey = null,
            requestPsk = requestPsk(requestId),
            currentClientPsk = null,
            previousClientPsk = null,
        )
        assertEquals(1, dao.getSecretUploadEnvironmentVariables(requestId).size)

        val request = checkNotNull(dao.getRequestById(requestId))
        val upload = checkNotNull(dao.getSecretUploadRequest(requestId))
        dao.updateSecretUploadRequest(
            request = request.copy(state = "completed", completedAt = 2, updatedAt = 2),
            secretUpload = upload.copy(decision = "rejected", decidedAt = 2),
            discardUploadedValues = true,
        )

        assertTrue(dao.getSecretUploadEnvironmentVariables(requestId).isEmpty())
        assertEquals(
            "{\"variableNames\":[\"TOKEN\"]}",
            dao.getSecretUploadRequest(requestId)?.summaryJson,
        )
    }

    @Test
    fun authorizationCommitmentRequiresExactLiveState() = runTest {
        insertActivePairing()
        val secretDao = database.secretDao()
        secretDao.insertSecret(
            SecretEntity(
                id = "secret",
                name = "github",
                description = "",
                type = "environment",
                createdAt = 1,
                updatedAt = 1,
                revision = 4,
                approvalMode = "temporary",
            ),
        )
        secretDao.upsertTemporaryAccessGrants(
            listOf(
                TemporaryAccessGrantEntity(
                    secretId = "secret",
                    clientId = CLIENT_ID,
                    operation = "invocation",
                    expiresAt = 1_000,
                ),
            ),
        )
        val commitment = AuthorizationCommitment(
            secretRevisions = mapOf("secret" to 4),
            policies = mapOf(
                "secret" to AuthorizationPolicyCommitment(
                    mode = "temporary",
                    temporaryAccessExpiresAt = 1_000,
                ),
            ),
        )

        assertTrue(dao.authorizationMatches(commitment, CLIENT_ID, "invocation", 999))
        assertFalse(dao.authorizationMatches(commitment, CLIENT_ID, "invocation", 1_000))

        secretDao.upsertClientApprovalOverride(
            SecretClientApprovalOverrideEntity("secret", CLIENT_ID, "ask_me"),
        )
        assertFalse(dao.authorizationMatches(commitment, CLIENT_ID, "invocation", 999))
        secretDao.deleteClientApprovalOverride("secret", CLIENT_ID)

        secretDao.updateSecretInstructions("secret", "changed", updatedAt = 5)
        assertFalse(dao.authorizationMatches(commitment, CLIENT_ID, "invocation", 999))

        val client = checkNotNull(dao.getClient(CLIENT_ID))
        dao.updateClient(client.copy(relayClientState = "suspended"))
        assertFalse(dao.authorizationMatches(commitment, CLIENT_ID, "invocation", 999))
    }

    @Test
    fun authorizationCommitmentRejectsChangedDeviceInstructions() = runTest {
        insertActivePairing()
        database.deviceIdentityDao().updateActiveInstructions(
            activeRole = "active",
            instructions = "Allow repository inspection.",
        )
        val commitment = AuthorizationCommitment(
            secretRevisions = emptyMap(),
            policies = emptyMap(),
            deviceInstructions = AuthorizationDeviceInstructionsCommitment(
                deviceIdentityId = DEVICE_IDENTITY_ID,
                instructions = "Allow repository inspection.",
            ),
        )

        assertTrue(dao.authorizationMatches(commitment, CLIENT_ID, "invocation", 1))

        database.deviceIdentityDao().updateActiveInstructions(
            activeRole = "active",
            instructions = "Ask before every use.",
        )
        assertFalse(dao.authorizationMatches(commitment, CLIENT_ID, "invocation", 1))
    }

    @Test
    fun aiApprovalRaceAtomicallyFallsBackToHumanReviewForEveryRequestKind() = runTest {
        insertActivePairing()
        val secretDao = database.secretDao()
        secretDao.insertSecret(
            SecretEntity(
                id = "secret",
                name = "github",
                description = "",
                type = "environment",
                createdAt = 1,
                updatedAt = 1,
                revision = 2,
                approvalMode = "approve",
            ),
        )
        val requestId = INVOCATION_REQUEST_ID
        dao.insertRequest(
            rootRequest().copy(
                id = requestId,
                parentRequestId = null,
                kind = "invocation",
                state = "reviewing",
                responseJson = null,
            ),
        )
        dao.insertSecretUseRequestRow(secretUseRequest(requestId))
        val request = checkNotNull(dao.getRequestById(requestId))
        val secretUse = checkNotNull(dao.getSecretUseRequest(requestId))
        val stale = AuthorizationCommitment(
            secretRevisions = mapOf("secret" to 1),
            policies = mapOf(
                "secret" to AuthorizationPolicyCommitment("approve", null),
            ),
        )

        assertEquals(
            ConditionalRequestUpdate.ACTION_REQUIRED,
            dao.updateSecretUseRequestIfAuthorized(
                request.copy(state = "waiting", responseJson = RESPONSE_JSON),
                secretUse.copy(
                    decision = "approved",
                    decisionSource = "ai",
                    approvalEvaluationJson = "{\"review\":\"refreshed\"}",
                    providedSecretsJson = "{\"github\":{}}",
                    decidedAt = 10,
                ),
                stale,
                CLIENT_ID,
                "invocation",
                10,
            ),
        )
        assertEquals("action_required", dao.getRequestById(requestId)?.state)
        assertNull(dao.getRequestById(requestId)?.responseJson)
        val fallbackInvocation = checkNotNull(dao.getSecretUseRequest(requestId))
        assertNull(fallbackInvocation.decision)
        assertNull(fallbackInvocation.decisionSource)
        assertEquals("{\"github\":{}}", fallbackInvocation.providedSecretsJson)
        assertEquals("{\"review\":\"refreshed\"}", fallbackInvocation.approvalEvaluationJson)

        val gitRequestId = "$INVOCATION_REQUEST_ID-git"
        dao.insertRequest(
            rootRequest().copy(
                id = gitRequestId,
                parentRequestId = null,
                kind = "git_sign",
                state = "reviewing",
                responseJson = null,
            ),
        )
        dao.insertGitSignRequestRow(gitSignRequest(gitRequestId))
        val gitRequest = checkNotNull(dao.getRequestById(gitRequestId))
        val gitSign = checkNotNull(dao.getGitSignRequest(gitRequestId))
        assertEquals(
            ConditionalRequestUpdate.ACTION_REQUIRED,
            dao.updateGitSignRequestIfAuthorized(
                gitRequest.copy(state = "waiting", responseJson = RESPONSE_JSON),
                gitSign.copy(
                    decision = "approved",
                    approvalEvaluationJson = "{\"review\":\"refreshed\"}",
                    decidedAt = 10,
                ),
                stale,
                CLIENT_ID,
                "git_sign",
                10,
            ),
        )
        assertEquals("action_required", dao.getRequestById(gitRequestId)?.state)
        assertNull(dao.getRequestById(gitRequestId)?.responseJson)
        assertNull(dao.getGitSignRequest(gitRequestId)?.decision)
        assertEquals(
            "{\"review\":\"refreshed\"}",
            dao.getGitSignRequest(gitRequestId)?.approvalEvaluationJson,
        )

        val sshRequestId = "$INVOCATION_REQUEST_ID-ssh"
        dao.insertRequest(
            rootRequest().copy(
                id = sshRequestId,
                parentRequestId = null,
                kind = "ssh_authenticate",
                state = "reviewing",
                responseJson = null,
            ),
        )
        dao.insertSshAuthenticationRequestRow(sshAuthenticationRequest(sshRequestId))
        val sshRequest = checkNotNull(dao.getRequestById(sshRequestId))
        val authentication = checkNotNull(dao.getSshAuthenticationRequest(sshRequestId))
        assertEquals(
            ConditionalRequestUpdate.ACTION_REQUIRED,
            dao.updateSshAuthenticationRequestIfAuthorized(
                sshRequest.copy(state = "waiting", responseJson = RESPONSE_JSON),
                authentication.copy(
                    decision = "approved",
                    approvalEvaluationJson = "{\"review\":\"refreshed\"}",
                    decidedAt = 10,
                ),
                stale,
                CLIENT_ID,
                "ssh_authenticate",
                10,
            ),
        )
        assertEquals("action_required", dao.getRequestById(sshRequestId)?.state)
        assertNull(dao.getRequestById(sshRequestId)?.responseJson)
        assertNull(dao.getSshAuthenticationRequest(sshRequestId)?.decision)
        assertEquals(
            "{\"review\":\"refreshed\"}",
            dao.getSshAuthenticationRequest(sshRequestId)?.approvalEvaluationJson,
        )

        val exact = stale.copy(secretRevisions = mapOf("secret" to 2))
        assertEquals(
            ConditionalRequestUpdate.APPLIED,
            dao.updateSecretUseRequestIfAuthorized(
                checkNotNull(dao.getRequestById(requestId)).copy(
                    state = "waiting",
                    responseJson = RESPONSE_JSON,
                ),
                checkNotNull(dao.getSecretUseRequest(requestId)).copy(decision = "approved"),
                exact,
                CLIENT_ID,
                "invocation",
                10,
            ),
        )
        assertEquals("waiting", dao.getRequestById(requestId)?.state)
        assertEquals("approved", dao.getSecretUseRequest(requestId)?.decision)
    }

    private suspend fun insertActivePairing() {
        dao.insertPairingRequest(
            rootRequest().copy(
                state = "completed",
                listed = false,
                completedAt = 2,
                updatedAt = 2,
            ),
            completedAttempt(pendingAttempt()),
        )
        dao.insertClient(activeClient())
        dao.insertClientPsk(clientPsk(CURRENT_SLOT, byteArrayOf(2)))
    }

    private fun secretUseRequest(requestId: String) = SecretUseRequestEntity(
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
        command = "git",
        argumentsJson = "[]",
        workingDirectory = "/tmp/project",
        executablePath = "/usr/bin/git",
        executableHash = null,
        executableMode = "direct",
        stdinKind = "terminal",
        stdoutKind = "terminal",
        stderrKind = "terminal",
        launcherChainJson = "[]",
        decision = null,
        decisionSource = null,
        approvalEvaluationJson = null,
        completionResult = null,
        completionReason = null,
        completionMessage = null,
        decidedAt = null,
    )

    private fun gitSignRequest(requestId: String) = GitSignRequestEntity(
        requestId = requestId,
        secretName = "github",
        message = "commit".encodeToByteArray(),
        repositoryJson = null,
        approvalEvaluationJson = null,
        decision = null,
        completionResult = null,
        completionReason = null,
        completionMessage = null,
        decidedAt = null,
    )

    private fun sshAuthenticationRequest(requestId: String) =
        SshAuthenticationRequestEntity(
            requestId = requestId,
            secretName = "github",
            message = "authentication".encodeToByteArray(),
            username = "git",
            method = "publickey",
            algorithm = "ssh-ed25519",
            hostKeyAlgorithm = null,
            hostKeyFingerprint = null,
            approvalEvaluationJson = null,
            decision = null,
            completionResult = null,
            completionReason = null,
            completionMessage = null,
            decidedAt = null,
        )

    private fun secretUploadRequest(
        requestId: String,
        decision: String?,
    ) = SecretUploadRequestEntity(
        requestId = requestId,
        decision = decision,
        mode = "create",
        uploadedName = "uploaded-$requestId",
        approvedName = if (decision == "approved") "uploaded-$requestId" else null,
        descriptionProvided = false,
        description = null,
        secretType = "environment",
        targetSecretId = null,
        targetSecretRevision = null,
        summaryJson = "{\"variableNames\":[]}",
        intakeError = null,
        decidedAt = decision?.let { 1L },
    )

    private fun rootRequest() = InboxRequestEntity(
        id = ROOT_REQUEST_ID,
        parentRequestId = null,
        deviceIdentityId = DEVICE_IDENTITY_ID,
        clientId = CLIENT_ID,
        clientNameSnapshot = "Test client",
        clientSoftwareJson = "{}",
        kind = "pairing",
        state = "waiting",
        listed = true,
        requestJson = "{}",
        responseJson = "{}",
        completionJson = "{}",
        error = null,
        receivedAt = 1,
        updatedAt = 1,
        completedAt = null,
        requestAcknowledgedAt = 1,
        responseAcknowledgedAt = 1,
        completionAcknowledgedAt = 1,
    )

    private fun pendingAttempt(withPendingPsk: Boolean = false) = PairingAttemptEntity(
        requestId = ROOT_REQUEST_ID,
        pairingAddress = "write-leader-hungry",
        clientId = CLIENT_ID,
        friendlyName = "Test client",
        deviceRandom = ByteArray(32),
        desiredRelayClientState = null,
        relayClientState = "active",
        state = "waiting_for_finish",
        sasOption0 = 1,
        sasOption1 = 2,
        sasOption2 = 3,
        correctSasIndex = 0,
        platform = "linux",
        architecture = "x86_64",
        hostname = "test",
        machineId = null,
        osVersion = null,
        pendingPsk = if (withPendingPsk) encryptedValue(byteArrayOf(1)) else null,
        decidedAt = 1,
    )

    private fun completedAttempt(attempt: PairingAttemptEntity) = attempt.copy(
        state = "completed",
        desiredRelayClientState = null,
        pendingPsk = null,
        decidedAt = 2,
    )

    private fun activeClient() = ClientEntity(
        clientId = CLIENT_ID,
        deviceIdentityId = DEVICE_IDENTITY_ID,
        name = "Test client",
        instructions = "",
        desiredRelayClientState = null,
        relayClientState = "active",
        clientSoftwareJson = "{}",
        platform = "linux",
        architecture = "x86_64",
        hostname = "test",
        machineId = null,
        osVersion = null,
        pairedAt = 2,
        lastSeenAt = 2,
        updatedAt = 2,
    )

    private fun activatedRoot(root: InboxRequestEntity) = root.copy(
        state = "completed",
        updatedAt = 2,
        completedAt = 2,
    )

    private fun finishRequest(requestId: String, rootId: String) = InboxRequestEntity(
        id = requestId,
        parentRequestId = rootId,
        deviceIdentityId = DEVICE_IDENTITY_ID,
        clientId = CLIENT_ID,
        clientNameSnapshot = "Test client",
        clientSoftwareJson = "{}",
        kind = "pairing_finish",
        state = "completed",
        listed = false,
        requestJson = "{}",
        responseJson = RESPONSE_JSON,
        completionJson = null,
        error = null,
        receivedAt = 2,
        updatedAt = 2,
        completedAt = 2,
        requestAcknowledgedAt = null,
        responseAcknowledgedAt = null,
        completionAcknowledgedAt = null,
    )

    private fun pairingRemovalRequest(rootId: String) = InboxRequestEntity(
        id = REMOVE_REQUEST_ID,
        parentRequestId = rootId,
        deviceIdentityId = DEVICE_IDENTITY_ID,
        clientId = CLIENT_ID,
        clientNameSnapshot = "Test client",
        clientSoftwareJson = "{}",
        kind = "pairing_remove",
        state = "waiting",
        listed = false,
        requestJson = "{}",
        responseJson = RESPONSE_JSON,
        completionJson = null,
        error = null,
        receivedAt = 2,
        updatedAt = 2,
        completedAt = null,
        requestAcknowledgedAt = null,
        responseAcknowledgedAt = null,
        completionAcknowledgedAt = null,
    )

    private fun requestPsk(requestId: String) = RequestPskEntity(
        requestId = requestId,
        encryptedPsk = encryptedValue(byteArrayOf(3)),
    )

    private fun clientPsk(slot: String, ciphertext: ByteArray) = ClientPskEntity(
        clientId = CLIENT_ID,
        slot = slot,
        encryptedPsk = encryptedValue(ciphertext),
        storedAt = 2,
    )

    private fun encryptedValue(ciphertext: ByteArray) = EncryptedValue(
        formatVersion = 1,
        keyId = KEY_ID,
        nonce = ByteArray(12),
        ciphertext = ciphertext,
    )

    private companion object {
        const val KEY_ID = "key"
        const val DEVICE_IDENTITY_ID = "device-identity"
        const val CURRENT_SLOT = "current"
        const val PREVIOUS_SLOT = "previous"
        const val DEVICE_ID = "01K2ENXDTW1P3XAR4J7V7C9D0H"
        const val CLIENT_ID = "01K2EP16NWNAGJYF8J1Q2V6P3X"
        const val ROOT_REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        const val FINISH_REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAW"
        const val REMOVE_REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAY"
        const val UPLOAD_REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAZ"
        const val INVOCATION_REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FB0"
        const val RESPONSE_JSON = "{\"nonce\":\"fixed\",\"ciphertext\":\"fixed\"}"
    }
}
