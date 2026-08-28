package dev.agentknock.storage.request

import androidx.room3.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.agentknock.storage.AgentknockDatabase
import dev.agentknock.storage.crypto.VaultKeyEntity
import dev.agentknock.storage.crypto.VaultKeyPurpose
import dev.agentknock.storage.secret.SecretClientApprovalOverrideEntity
import dev.agentknock.storage.secret.SecretEntity
import dev.agentknock.storage.secret.TemporaryAccessGrantEntity
import dev.agentknock.storage.vault.DeviceIdentityEntity
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
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun pairingActivationAndItsFixedResponseAreAtomic() = runTest {
        val rootId = dao.insertPairingRequest(rootRequest(), pendingPairing())
        val root = checkNotNull(dao.getRequestById(rootId))
        val pairing = checkNotNull(dao.getPairing(rootId))
        dao.insertPairingSecret(pairingSecret(rootId, CURRENT_KIND, CURRENT_ID, byteArrayOf(1)))

        val failed = runCatching {
            dao.finishPairing(
                rootRequest = activatedRoot(root),
                pairing = activePairing(pairing),
                finishRequest = finishRequest(root.relayRequestId, rootId),
                requestSecret = requestSecret(),
                currentPairingSecret = pairingSecret(
                    rootId,
                    CURRENT_KIND,
                    CURRENT_ID,
                    byteArrayOf(2),
                ),
                previousPairingSecret = pairingSecret(
                    rootId,
                    PREVIOUS_KIND,
                    PREVIOUS_ID,
                    byteArrayOf(1),
                ),
            )
        }
        assertTrue(failed.isFailure)
        assertEquals("waiting", dao.getRequestById(rootId)?.state)
        assertEquals("waiting_for_finish", dao.getPairing(rootId)?.state)
        assertArrayEquals(
            byteArrayOf(1),
            dao.getPairingSecret(rootId, CURRENT_KIND)?.ciphertext,
        )
        assertNull(dao.getPairingSecret(rootId, PREVIOUS_KIND))
        assertNull(dao.getRequestByRelayId(FINISH_REQUEST_ID))

        dao.finishPairing(
            rootRequest = activatedRoot(root),
            pairing = activePairing(pairing),
            finishRequest = finishRequest(FINISH_REQUEST_ID, rootId),
            requestSecret = requestSecret(),
            currentPairingSecret = pairingSecret(
                rootId,
                CURRENT_KIND,
                CURRENT_ID,
                byteArrayOf(2),
            ),
            previousPairingSecret = pairingSecret(
                rootId,
                PREVIOUS_KIND,
                PREVIOUS_ID,
                byteArrayOf(1),
            ),
        )

        val storedFinish = checkNotNull(dao.getRequestByRelayId(FINISH_REQUEST_ID))
        assertEquals("completed", dao.getRequestById(rootId)?.state)
        assertEquals("active", dao.getPairing(rootId)?.state)
        assertEquals(RESPONSE_JSON, storedFinish.responseJson)
        assertNotNull(dao.getRequestSecret(storedFinish.id))
        assertArrayEquals(
            byteArrayOf(2),
            dao.getPairingSecret(rootId, CURRENT_KIND)?.ciphertext,
        )
        assertArrayEquals(
            byteArrayOf(1),
            dao.getPairingSecret(rootId, PREVIOUS_KIND)?.ciphertext,
        )
    }

    @Test
    fun pairingRejectionErasesThePendingBinding() = runTest {
        val rootId = dao.insertPairingRequest(rootRequest(), pendingPairing())
        val root = checkNotNull(dao.getRequestById(rootId))
        val pairing = checkNotNull(dao.getPairing(rootId))
        dao.insertPairingSecret(pairingSecret(rootId, CURRENT_KIND, CURRENT_ID, byteArrayOf(1)))

        dao.rejectPairing(
            request = root.copy(state = "completed", completedAt = 2, updatedAt = 2),
            pairing = pairing.copy(state = "rejected", completedAt = 2, updatedAt = 2),
        )

        assertEquals("completed", dao.getRequestById(rootId)?.state)
        assertEquals("rejected", dao.getPairing(rootId)?.state)
        assertNull(dao.getPairingSecret(rootId, CURRENT_KIND))
    }

    @Test
    fun completedHistoryKeepsUsablePairingsButCanClearRevokedPairings() = runTest {
        database.vaultDao().insertIdentity(
            DeviceIdentityEntity(
                id = DEVICE_IDENTITY_ID,
                role = "active",
                address = "write-leader-hungry",
                addressId = "address-id",
                deviceId = DEVICE_ID,
                devicePublicKey = ByteArray(32),
                createdAt = 1,
                claimedAt = 1,
            ),
        )
        val rootId = dao.insertPairingRequest(
            rootRequest().copy(state = "completed", completedAt = 2, updatedAt = 2),
            pendingPairing().copy(
                deviceIdentityId = DEVICE_IDENTITY_ID,
                state = "active",
                desiredRelayClientState = "active",
                relayClientState = "active",
                completedAt = 2,
            ),
        )

        assertEquals(0, dao.clearCompletedHistory())
        assertTrue(checkNotNull(dao.getRequestById(rootId)).listed)

        val pairing = checkNotNull(dao.getPairing(rootId))
        dao.updatePairing(
            pairing.copy(
                desiredRelayClientState = "revoked",
                relayClientState = "revoked",
                updatedAt = 3,
            ),
        )

        assertEquals(1, dao.clearCompletedHistory())
        assertTrue(!checkNotNull(dao.getRequestById(rootId)).listed)
    }

    @Test
    fun pairingRemovalAndItsFixedResponseAreAtomic() = runTest {
        val rootId = dao.insertPairingRequest(rootRequest(), pendingPairing())
        val pairing = checkNotNull(dao.getPairing(rootId)).copy(state = "active")
        dao.updatePairing(pairing)
        dao.insertPairingSecret(pairingSecret(rootId, CURRENT_KIND, CURRENT_ID, byteArrayOf(2)))
        dao.insertPairingSecret(pairingSecret(rootId, PREVIOUS_KIND, PREVIOUS_ID, byteArrayOf(1)))
        database.secretDao().insertSecret(
            SecretEntity(
                id = "secret",
                name = "github",
                description = "",
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
        val removal = pairingRemovalRequest(rootId)
        val revokedPairing = pairing.copy(desiredRelayClientState = "revoked", updatedAt = 2)

        val failed = runCatching {
            dao.insertPairingRemoval(
                request = removal,
                requestSecret = requestSecret().copy(encryptionKeyId = "missing-key"),
                pairing = revokedPairing,
            )
        }
        assertTrue(failed.isFailure)
        assertNull(dao.getRequestByRelayId(REMOVE_REQUEST_ID))
        assertEquals("active", dao.getPairing(rootId)?.desiredRelayClientState)
        assertNotNull(dao.getPairingSecret(rootId, CURRENT_KIND))
        assertNotNull(dao.getPairingSecret(rootId, PREVIOUS_KIND))
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
            requestSecret = requestSecret(),
            pairing = revokedPairing,
        )

        val storedRemoval = checkNotNull(dao.getRequestByRelayId(REMOVE_REQUEST_ID))
        assertEquals(RESPONSE_JSON, storedRemoval.responseJson)
        assertNotNull(dao.getRequestSecret(storedRemoval.id))
        assertEquals("revoked", dao.getPairing(rootId)?.desiredRelayClientState)
        assertNull(dao.getPairingSecret(rootId, CURRENT_KIND))
        assertNull(dao.getPairingSecret(rootId, PREVIOUS_KIND))
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
        val requestId = dao.insertSecretUploadRequest(
            request = rootRequest().copy(
                relayRequestId = "upload-request",
                kind = "secret_upload",
                state = "action_required",
            ),
            secretUpload = SecretUploadRequestEntity(
                requestId = 0,
                pairingRequestId = null,
                clientId = CLIENT_ID,
                clientName = "Test client",
                state = "review_pending",
                clientSoftwareJson = "{}",
                mode = "CREATE",
                uploadedName = "test-secret",
                approvedName = null,
                descriptionProvided = false,
                description = null,
                secretType = "environment",
                summaryJson = "{\"type\":\"environment\",\"variableNames\":[\"TOKEN\"]}",
                error = null,
                transportResult = "RECEIVED",
                transportMessage = null,
                createdAt = 1,
                updatedAt = 1,
                decidedAt = null,
                transportCompletedAt = null,
            ),
            environmentVariables = listOf(
                SecretUploadEnvironmentVariableEntity(
                    id = "upload-variable",
                    requestId = 0,
                    name = "TOKEN",
                    sensitive = true,
                    encryptionFormat = 1,
                    encryptionKeyId = KEY_ID,
                    nonce = ByteArray(12),
                    ciphertext = byteArrayOf(4),
                    createdAt = 1,
                ),
            ),
            sshKey = null,
            requestSecret = requestSecret(),
            currentPairingSecret = null,
            previousPairingSecret = null,
        )
        assertEquals(1, dao.getSecretUploadEnvironmentVariables(requestId).size)

        val request = checkNotNull(dao.getRequestById(requestId))
        val upload = checkNotNull(dao.getSecretUploadRequest(requestId))
        dao.updateSecretUploadRequest(
            request = request.copy(state = "completed", completedAt = 2, updatedAt = 2),
            secretUpload = upload.copy(state = "rejected", decidedAt = 2, updatedAt = 2),
            discardUploadedValues = true,
        )

        assertTrue(dao.getSecretUploadEnvironmentVariables(requestId).isEmpty())
        assertEquals(
            "{\"type\":\"environment\",\"variableNames\":[\"TOKEN\"]}",
            dao.getSecretUploadRequest(requestId)?.summaryJson,
        )
    }

    @Test
    fun authorizationCommitmentRequiresExactLiveState() = runTest {
        val pairingRequestId = insertActivePairing()
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

        val secret = checkNotNull(secretDao.getSecret("secret"))
        secretDao.updateSecret(secret.copy(revision = 5))
        assertFalse(dao.authorizationMatches(commitment, CLIENT_ID, "invocation", 999))
        secretDao.updateSecret(secret)

        val pairing = checkNotNull(dao.getPairing(pairingRequestId))
        dao.updatePairing(pairing.copy(relayClientState = "suspended"))
        assertFalse(dao.authorizationMatches(commitment, CLIENT_ID, "invocation", 999))
    }

    @Test
    fun authorizationCommitmentRejectsChangedDeviceInstructions() = runTest {
        insertActivePairing()
        database.vaultDao().insertIdentity(
            DeviceIdentityEntity(
                id = DEVICE_IDENTITY_ID,
                role = "active",
                address = "write-leader-hungry",
                addressId = "address-id",
                deviceId = DEVICE_ID,
                devicePublicKey = ByteArray(32),
                createdAt = 1,
                claimedAt = 1,
                instructions = "Allow repository inspection.",
            ),
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

        database.vaultDao().updateActiveInstructions(
            activeRole = "active",
            instructions = "Ask before every use.",
        )
        assertFalse(dao.authorizationMatches(commitment, CLIENT_ID, "invocation", 1))
    }

    @Test
    fun approvedRequestUpdateIsRejectedWhenAuthorizationChanged() = runTest {
        val pairingRequestId = insertActivePairing()
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
        val requestId = dao.insertRequest(
            rootRequest().copy(
                relayRequestId = "invocation-request",
                parentRequestId = null,
                kind = "secret_use",
                state = "action_required",
                responseJson = null,
            ),
        )
        dao.insertSecretUseRequestRow(secretUseRequest(requestId, pairingRequestId))
        val request = checkNotNull(dao.getRequestById(requestId))
        val secretUse = checkNotNull(dao.getSecretUseRequest(requestId))
        val stale = AuthorizationCommitment(
            secretRevisions = mapOf("secret" to 1),
            policies = mapOf(
                "secret" to AuthorizationPolicyCommitment("approve", null),
            ),
        )

        assertFalse(
            dao.updateSecretUseRequestIfAuthorized(
                request.copy(state = "waiting", responseJson = RESPONSE_JSON),
                secretUse.copy(state = "waiting_for_completion", decision = "approved"),
                stale,
                CLIENT_ID,
                "invocation",
                10,
            ),
        )
        assertEquals("action_required", dao.getRequestById(requestId)?.state)
        assertNull(dao.getSecretUseRequest(requestId)?.decision)

        val exact = stale.copy(secretRevisions = mapOf("secret" to 2))
        assertTrue(
            dao.updateSecretUseRequestIfAuthorized(
                request.copy(state = "waiting", responseJson = RESPONSE_JSON),
                secretUse.copy(state = "waiting_for_completion", decision = "approved"),
                exact,
                CLIENT_ID,
                "invocation",
                10,
            ),
        )
        assertEquals("waiting", dao.getRequestById(requestId)?.state)
        assertEquals("approved", dao.getSecretUseRequest(requestId)?.decision)
    }

    private suspend fun insertActivePairing(): Long = dao.insertPairingRequest(
        rootRequest().copy(state = "completed", completedAt = 2),
        pendingPairing().copy(state = "active", completedAt = 2),
    )

    private fun secretUseRequest(requestId: Long, pairingRequestId: Long) =
        SecretUseRequestEntity(
            requestId = requestId,
            pairingRequestId = pairingRequestId,
            clientId = CLIENT_ID,
            clientName = "Test client",
            pairingAddress = "write-leader-hungry",
            hostname = "test",
            platform = "linux",
            architecture = "x86_64",
            machineId = null,
            osVersion = null,
            state = "approval_pending",
            invocationTokenHash = ByteArray(32),
            containsSensitiveMaterial = true,
            clientSoftwareJson = "{}",
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
            error = null,
            createdAt = 1,
            updatedAt = 1,
            decidedAt = null,
            completedAt = null,
        )

    private fun rootRequest() = InboxRequestEntity(
        relayRequestId = ROOT_REQUEST_ID,
        parentRequestId = null,
        kind = "pairing",
        state = "waiting",
        listed = true,
        requestJson = "{}",
        responseJson = "{}",
        completionJson = "{}",
        receivedAt = 1,
        updatedAt = 1,
        completedAt = null,
        requestAcknowledgedAt = 1,
        responseAcknowledgedAt = 1,
        completionAcknowledgedAt = 1,
    )

    private fun pendingPairing() = PairingEntity(
        requestId = 0,
        deviceIdentityId = null,
        pairingAddress = "write-leader-hungry",
        deviceId = DEVICE_ID,
        clientId = CLIENT_ID,
        friendlyName = "Test client",
        deviceRandom = ByteArray(32),
        desiredRelayClientState = "active",
        relayClientState = "active",
        state = "waiting_for_finish",
        sasOption0 = 1,
        sasOption1 = 2,
        sasOption2 = 3,
        correctSasIndex = 0,
        clientSoftwareJson = "{}",
        platform = "linux",
        architecture = "x86_64",
        hostname = "test",
        machineId = null,
        osVersion = null,
        error = null,
        createdAt = 1,
        updatedAt = 1,
        decidedAt = 1,
        completedAt = null,
    )

    private fun activatedRoot(root: InboxRequestEntity) = root.copy(
        state = "completed",
        updatedAt = 2,
        completedAt = 2,
    )

    private fun activePairing(pairing: PairingEntity) = pairing.copy(
        state = "active",
        updatedAt = 2,
        completedAt = 2,
    )

    private fun finishRequest(relayRequestId: String, rootId: Long) = InboxRequestEntity(
        relayRequestId = relayRequestId,
        parentRequestId = rootId,
        kind = "pairing_finish",
        state = "completed",
        listed = false,
        requestJson = "{}",
        responseJson = RESPONSE_JSON,
        completionJson = null,
        receivedAt = 2,
        updatedAt = 2,
        completedAt = 2,
        requestAcknowledgedAt = null,
        responseAcknowledgedAt = null,
        completionAcknowledgedAt = null,
    )

    private fun pairingRemovalRequest(rootId: Long) = InboxRequestEntity(
        relayRequestId = REMOVE_REQUEST_ID,
        parentRequestId = rootId,
        kind = "pairing_remove",
        state = "waiting",
        listed = false,
        requestJson = "{}",
        responseJson = RESPONSE_JSON,
        completionJson = null,
        receivedAt = 2,
        updatedAt = 2,
        completedAt = null,
        requestAcknowledgedAt = null,
        responseAcknowledgedAt = null,
        completionAcknowledgedAt = null,
    )

    private fun requestSecret() = RequestSecretEntity(
        id = REQUEST_SECRET_ID,
        requestId = 0,
        encryptionFormat = 1,
        encryptionKeyId = KEY_ID,
        nonce = ByteArray(12),
        ciphertext = byteArrayOf(3),
        createdAt = 2,
    )

    private fun pairingSecret(
        pairingRequestId: Long,
        kind: String,
        id: String,
        ciphertext: ByteArray,
    ) = PairingSecretEntity(
        id = id,
        pairingRequestId = pairingRequestId,
        kind = kind,
        encryptionFormat = 1,
        encryptionKeyId = KEY_ID,
        nonce = ByteArray(12),
        ciphertext = ciphertext,
        createdAt = 1,
        updatedAt = 2,
    )

    private companion object {
        const val KEY_ID = "key"
        const val DEVICE_IDENTITY_ID = "device-identity"
        const val CURRENT_ID = "current"
        const val PREVIOUS_ID = "previous"
        const val REQUEST_SECRET_ID = "request"
        const val CURRENT_KIND = "current_client_psk"
        const val PREVIOUS_KIND = "previous_client_psk"
        const val DEVICE_ID = "01K2ENXDTW1P3XAR4J7V7C9D0H"
        const val CLIENT_ID = "01K2EP16NWNAGJYF8J1Q2V6P3X"
        const val ROOT_REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        const val FINISH_REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAW"
        const val REMOVE_REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAY"
        const val RESPONSE_JSON = "{\"nonce\":\"fixed\",\"ciphertext\":\"fixed\"}"
    }
}
