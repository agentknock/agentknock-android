package dev.agentknock.storage.device

import androidx.room3.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.agentknock.storage.AgentknockDatabase
import dev.agentknock.storage.audit.AuditEventEntity
import dev.agentknock.storage.crypto.VaultKeyEntity
import dev.agentknock.storage.crypto.VaultKeyPurpose
import dev.agentknock.storage.crypto.EncryptedValue
import dev.agentknock.storage.request.ClientEntity
import dev.agentknock.storage.request.ClientPskEntity
import dev.agentknock.storage.request.InboxRequestEntity
import dev.agentknock.storage.request.PairingAttemptEntity
import dev.agentknock.storage.request.RequestPskEntity
import dev.agentknock.storage.request.SecretUploadEnvironmentVariableEntity
import dev.agentknock.storage.request.SecretUploadRequestEntity
import dev.agentknock.storage.secret.SecretClientApprovalOverrideEntity
import dev.agentknock.storage.secret.SecretEntity
import dev.agentknock.storage.secret.TemporaryAccessGrantEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceIdentityRetentionTest {
    private lateinit var database: AgentknockDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AgentknockDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun replacingIdentityPurgesObsoleteStateAndRetainsRequestAndAuditProvenance() = runTest {
        database.vaultKeyDao().insertKey(
            VaultKeyEntity(
                id = "device-state-key",
                purpose = VaultKeyPurpose.DEVICE_STATE.storedName,
                active = true,
                createdAt = 1,
                backing = "SOFTWARE",
            ),
        )
        val original = identity("identity-1", "device-1", "active", 1)
        val replacement = identity("identity-2", "device-2", "candidate", 2)
        database.deviceIdentityDao().insertIdentity(original)
        database.deviceIdentityDao().insertCredentials(credentials(original.id, 1))
        database.deviceIdentityDao().insertIdentity(replacement)
        database.deviceIdentityDao().insertCredentials(credentials(replacement.id, 2))
        database.requestDao().insertClient(client(original.id))
        val historicalRequest = request(original.id).copy(listed = true)
        database.requestDao().insertRequest(historicalRequest)
        database.auditDao().insertEvents(listOf(auditEvent()))

        assertEquals(
            true,
            database.deviceIdentityDao().promoteCandidate(
                candidateId = replacement.id,
                now = 3,
                activeRole = "active",
                candidateRole = "candidate",
                retiredRole = "retired",
            ),
        )

        val identities = database.deviceIdentityDao().observeIdentities().first()
        assertEquals("retired", identities.single { it.id == original.id }.role)
        assertEquals("active", identities.single { it.id == replacement.id }.role)
        assertTrue(database.deviceIdentityDao().getCredentials(original.id).isEmpty())
        assertEquals(2, database.deviceIdentityDao().getCredentials(replacement.id).size)
        assertNull(database.requestDao().getClient(CLIENT_ID))
        assertNull(database.requestDao().getClientById(CLIENT_ID))
        assertEquals(
            historicalRequest.copy(
                listed = false,
                exchangeEndedAt = 3,
                responseOutboxFinished = true,
            ),
            database.requestDao().getRequestById(REQUEST_ID),
        )
        assertAuditSnapshotRemains()

        val secondReplacement = identity("identity-3", "device-3", "candidate", 4)
        database.deviceIdentityDao().insertIdentity(secondReplacement)
        database.deviceIdentityDao().insertCredentials(credentials(secondReplacement.id, 3))
        assertEquals(
            true,
            database.deviceIdentityDao().promoteCandidate(
                candidateId = secondReplacement.id,
                now = 5,
                activeRole = "active",
                candidateRole = "candidate",
                retiredRole = "retired",
            ),
        )

        val twiceReplaced = database.deviceIdentityDao().observeIdentities().first()
        assertEquals(2, twiceReplaced.count { it.role == "retired" })
        assertNotNull(database.deviceIdentityDao().getIdentityById(original.id))
        assertTrue(database.deviceIdentityDao().getCredentials(original.id).isEmpty())
        assertTrue(database.deviceIdentityDao().getCredentials(replacement.id).isEmpty())
        assertEquals(2, database.deviceIdentityDao().getCredentials(secondReplacement.id).size)
        assertNull(database.requestDao().getClient(CLIENT_ID))
        assertNull(database.requestDao().getClientById(CLIENT_ID))
        assertEquals(
            historicalRequest.copy(
                listed = false,
                exchangeEndedAt = 3,
                responseOutboxFinished = true,
            ),
            database.requestDao().getRequestById(REQUEST_ID),
        )
        assertAuditSnapshotRemains()
    }

    @Test
    fun replacingIdentityAtomicallyAbandonsOnlyItsUnfinishedWork() = runTest {
        database.vaultKeyDao().insertKey(
            VaultKeyEntity(
                id = "device-state-key",
                purpose = VaultKeyPurpose.DEVICE_STATE.storedName,
                active = true,
                createdAt = 1,
                backing = "SOFTWARE",
            ),
        )
        val original = identity("identity-1", "device-1", "active", 1)
        val replacement = identity("identity-2", "device-2", "candidate", 2)
        database.deviceIdentityDao().insertIdentity(original)
        database.deviceIdentityDao().insertCredentials(credentials(original.id, 1))
        database.deviceIdentityDao().insertIdentity(replacement)
        database.deviceIdentityDao().insertCredentials(credentials(replacement.id, 2))
        val requestDao = database.requestDao()
        requestDao.insertClient(
            client(original.id).copy(
                desiredRelayClientState = "suspended",
            ),
        )
        requestDao.insertClientPsk(clientPsk())
        database.secretDao().insertSecret(secret())
        database.secretDao().upsertClientApprovalOverride(
            SecretClientApprovalOverrideEntity(
                secretId = SECRET_ID,
                clientId = CLIENT_ID,
                approvalMode = "approve",
            ),
        )
        database.secretDao().upsertTemporaryAccessGrants(
            listOf(
                TemporaryAccessGrantEntity(
                    secretId = SECRET_ID,
                    clientId = CLIENT_ID,
                    operation = "secret_use",
                    expiresAt = 1_000,
                ),
            ),
        )
        database.auditDao().insertEvents(listOf(auditEvent()))

        val pairingId = "pending-pairing"
        requestDao.insertPairingRequest(
            request(original.id).copy(
                id = pairingId,
                clientId = "pending-client",
                kind = "pairing",
                state = "waiting",
                listed = true,
                error = "The pairing completion was malformed.",
                completedAt = null,
            ),
            PairingAttemptEntity(
                requestId = pairingId,
                pairingAddress = original.address,
                clientId = "pending-client",
                friendlyName = "Pending client",
                deviceRandom = ByteArray(32),
                desiredRelayClientState = "active",
                relayClientState = "pending",
                state = "receiving",
                sasOption0 = null,
                sasOption1 = null,
                sasOption2 = null,
                correctSasIndex = null,
                platform = null,
                architecture = null,
                hostname = null,
                machineId = null,
                osVersion = null,
                pendingPsk = encryptedValue(byteArrayOf(1)),
                decidedAt = null,
            ),
        )

        val invocationId = "pending-invocation"
        requestDao.insertRequest(
            request(original.id).copy(
                id = invocationId,
                kind = "invocation",
                state = "action_required",
                listed = false,
                responseJson = "{}",
                completedAt = null,
                responseOutboxFinished = false,
            ),
        )
        requestDao.insertRequestPsk(requestPsk(invocationId))

        val uploadId = "pending-upload"
        requestDao.insertSecretUploadRequest(
            request = request(original.id).copy(
                id = uploadId,
                kind = "secret_upload",
                state = "action_required",
                listed = true,
                completedAt = null,
            ),
            secretUpload = upload(uploadId, decision = null),
            client = checkNotNull(requestDao.getClientById(CLIENT_ID)),
            environmentVariables = listOf(uploadValue(uploadId)),
            sshKey = null,
            requestPsk = requestPsk(uploadId),
            currentClientPsk = null,
            previousClientPsk = null,
        )
        val approvedUploadId = "approved-upload"
        requestDao.insertSecretUploadRequest(
            request = request(original.id).copy(
                id = approvedUploadId,
                kind = "secret_upload",
                state = "waiting",
                listed = true,
                completedAt = null,
            ),
            secretUpload = upload(approvedUploadId, decision = "approved"),
            client = checkNotNull(requestDao.getClientById(CLIENT_ID)),
            environmentVariables = emptyList(),
            sshKey = null,
            requestPsk = requestPsk(approvedUploadId),
            currentClientPsk = null,
            previousClientPsk = null,
        )
        requestDao.insertRequest(request(original.id).copy(listed = true))
        val completedBefore = checkNotNull(requestDao.getRequestById(REQUEST_ID))

        assertTrue(
            database.deviceIdentityDao().promoteCandidate(
                candidateId = replacement.id,
                now = 30,
                activeRole = "active",
                candidateRole = "candidate",
                retiredRole = "retired",
            ),
        )

        listOf(pairingId, invocationId, uploadId, approvedUploadId).forEach { id ->
            val abandoned = checkNotNull(requestDao.getRequestById(id))
            assertEquals("completed", abandoned.state)
            assertEquals(false, abandoned.listed)
            assertEquals(30L, abandoned.completedAt)
            assertTrue(checkNotNull(abandoned.error).contains("previous device identity"))
        }
        assertNull(requestDao.getPairingAttempt(pairingId))
        assertTrue(
            checkNotNull(checkNotNull(requestDao.getRequestById(pairingId)).error)
                .contains("pairing completion was malformed"),
        )
        assertEquals("rejected", requestDao.getSecretUploadRequest(uploadId)?.decision)
        assertEquals("approved", requestDao.getSecretUploadRequest(approvedUploadId)?.decision)
        assertTrue(requestDao.getSecretUploadEnvironmentVariables(uploadId).isEmpty())
        assertNull(requestDao.getClientById(CLIENT_ID))
        assertNull(requestDao.getClientPsk(CLIENT_ID, "current"))
        listOf(invocationId, uploadId, approvedUploadId).forEach { id ->
            assertNull(requestDao.getRequestPsk(id))
        }
        assertTrue(database.deviceIdentityDao().getCredentials(original.id).isEmpty())
        assertEquals(2, database.deviceIdentityDao().getCredentials(replacement.id).size)
        assertTrue(
            database.secretDao().getClientApprovalOverrides(CLIENT_ID, listOf(SECRET_ID)).isEmpty(),
        )
        assertTrue(
            database.secretDao().getActiveTemporaryAccessGrants(
                clientId = CLIENT_ID,
                secretIds = listOf(SECRET_ID),
                operation = "secret_use",
                now = 0,
            ).isEmpty(),
        )
        assertEquals(
            completedBefore.copy(
                listed = false,
                exchangeEndedAt = 30,
                responseOutboxFinished = true,
            ),
            requestDao.getRequestById(REQUEST_ID),
        )
        listOf(pairingId, invocationId, uploadId, approvedUploadId, REQUEST_ID).forEach { id ->
            val historical = checkNotNull(requestDao.getRequestById(id))
            assertEquals(original.id, historical.deviceIdentityId)
            assertEquals(if (id == pairingId) "pending-client" else CLIENT_ID, historical.clientId)
            assertEquals("Original client", historical.clientNameSnapshot)
            assertEquals(false, historical.listed)
        }
        assertAuditSnapshotRemains()

        val abandonedInvocation = checkNotNull(requestDao.getRequestById(invocationId))
        assertTrue(abandonedInvocation.responseOutboxFinished)
        assertNotNull(abandonedInvocation.exchangeEndedAt)
        assertTrue(requestDao.getUnfinishedResponseOutboxes().isEmpty())
        assertTrue(requestDao.getOpenExchanges().isEmpty())
        assertTrue(requestDao.observeListedRequests().first().isEmpty())
        assertNull(requestDao.observeRequest(uploadId).first())

        val staleUploadWrite = runCatching {
            requestDao.updateSecretUploadRequest(
                request = checkNotNull(requestDao.getRequestById(uploadId)).copy(
                    state = "action_required",
                    error = null,
                    completedAt = null,
                ),
                secretUpload = checkNotNull(requestDao.getSecretUploadRequest(uploadId)).copy(
                    decision = null,
                    decidedAt = null,
                ),
            )
        }
        assertTrue(staleUploadWrite.isFailure)
        assertEquals("completed", requestDao.getRequestById(uploadId)?.state)
        assertEquals("rejected", requestDao.getSecretUploadRequest(uploadId)?.decision)
    }

    @Test
    fun changingOnlyTheAddressDoesNotAbandonTheActiveIdentityWork() = runTest {
        val original = identity("identity-1", "device-1", "active", 1)
        val candidate = identity("identity-2", "device-1", "candidate", 2).copy(
            address = "new-pairing-address",
        )
        database.deviceIdentityDao().insertIdentity(original)
        database.deviceIdentityDao().insertIdentity(candidate)
        database.requestDao().insertClient(
            client(original.id).copy(desiredRelayClientState = "suspended"),
        )
        val pending = request(original.id).copy(
            id = "still-pending",
            kind = "invocation",
            state = "action_required",
            listed = true,
            error = null,
            completedAt = null,
        )
        database.requestDao().insertRequest(pending)

        assertTrue(
            database.deviceIdentityDao().promoteCandidate(
                candidateId = candidate.id,
                now = 3,
                activeRole = "active",
                candidateRole = "candidate",
                retiredRole = "retired",
            ),
        )

        val active = checkNotNull(database.deviceIdentityDao().getIdentityById(original.id))
        assertEquals("active", active.role)
        assertEquals(candidate.address, active.address)
        assertNull(database.deviceIdentityDao().getIdentityById(candidate.id))
        assertEquals("action_required", database.requestDao().getRequestById(pending.id)?.state)
        assertNull(database.requestDao().getRequestById(pending.id)?.completedAt)
        assertEquals(
            "suspended",
            database.requestDao().getClientById(CLIENT_ID)?.desiredRelayClientState,
        )
    }

    private fun identity(id: String, deviceId: String, role: String, createdAt: Long) =
        DeviceIdentityEntity(
            id = id,
            role = role,
            address = "same-address-for-history",
            deviceId = deviceId,
            createdAt = createdAt,
        )

    private fun credentials(identityId: String, marker: Int) = listOf(
        deviceCredential(identityId, "device_token", marker),
        deviceCredential(identityId, "device_private_key", marker + 1),
    )

    private fun deviceCredential(identityId: String, kind: String, marker: Int) =
        DeviceCredentialEntity(
            identityId = identityId,
            kind = kind,
            encryptedValue = encryptedValue(
                ciphertext = ByteArray(48) { marker.toByte() },
                nonce = ByteArray(12) { marker.toByte() },
            ),
        )

    private fun client(deviceIdentityId: String) = ClientEntity(
        clientId = CLIENT_ID,
        deviceIdentityId = deviceIdentityId,
        name = "Original client",
        instructions = "Keep this provenance",
        desiredRelayClientState = null,
        relayClientState = "active",
        clientSoftwareJson = null,
        platform = "linux",
        architecture = "x86_64",
        hostname = "client-host",
        machineId = null,
        osVersion = null,
        pairedAt = 10,
        lastSeenAt = 11,
    )

    private fun secret() = SecretEntity(
        id = SECRET_ID,
        name = "Original secret",
        description = "Kept independently of the retired client",
        type = "environment",
        createdAt = 9,
        updatedAt = 9,
    )

    private fun auditEvent() = AuditEventEntity(
        occurredAt = 13,
        eventType = "secret_use",
        subject = "Original secret",
        context = "git status",
        detail = "Historical audit detail",
        outcome = "approved",
        decisionSource = "manual",
        expiresAt = null,
        clientId = CLIENT_ID,
        clientName = "Original client",
        relayRequestId = REQUEST_ID,
    )

    private suspend fun assertAuditSnapshotRemains() {
        val audit = database.auditDao().observeEvents().first().single()
        assertEquals(CLIENT_ID, audit.clientId)
        assertEquals("Original client", audit.clientName)
        assertEquals(REQUEST_ID, audit.relayRequestId)
        assertEquals("Historical audit detail", audit.detail)
    }

    private fun clientPsk() = ClientPskEntity(
        clientId = CLIENT_ID,
        slot = "current",
        encryptedPsk = encryptedValue(byteArrayOf(1)),
        storedAt = 10,
    )

    private fun requestPsk(requestId: String) = RequestPskEntity(
        requestId = requestId,
        encryptedPsk = encryptedValue(byteArrayOf(2)),
    )

    private fun upload(requestId: String, decision: String?) = SecretUploadRequestEntity(
        requestId = requestId,
        decision = decision,
        mode = "CREATE",
        uploadedName = "uploaded-$requestId",
        approvedName = decision?.let { "approved-$requestId" },
        descriptionProvided = false,
        description = null,
        secretType = "environment",
        targetSecretId = null,
        targetSecretRevision = null,
        summaryJson = "{\"variableNames\":[\"TOKEN\"]}",
        intakeError = null,
        decidedAt = decision?.let { 20 },
    )

    private fun uploadValue(requestId: String) = SecretUploadEnvironmentVariableEntity(
        id = "value-$requestId",
        requestId = requestId,
        name = "TOKEN",
        sensitive = true,
        encryptedValue = encryptedValue(byteArrayOf(3)),
    )

    private fun encryptedValue(
        ciphertext: ByteArray,
        nonce: ByteArray = ByteArray(12),
    ) = EncryptedValue(
        formatVersion = 1,
        keyId = "device-state-key",
        nonce = nonce,
        ciphertext = ciphertext,
    )

    private fun request(deviceIdentityId: String) = InboxRequestEntity(
        id = REQUEST_ID,
        parentRequestId = null,
        deviceIdentityId = deviceIdentityId,
        clientId = CLIENT_ID,
        clientNameSnapshot = "Original client",
        clientSoftwareJson = null,
        kind = "unknown",
        state = "completed",
        listed = false,
        requestJson = "{}",
        responseJson = null,
        completionJson = null,
        error = null,
        receivedAt = 12,
        completedAt = 12,
        exchangeEndedAt = null,
        responseOutboxFinished = false,
    )

    private companion object {
        const val CLIENT_ID = "01K2EP16NWNAGJYF8J1Q2V6P3X"
        const val REQUEST_ID = "01K2EP16NWNAGJYF8J1Q2V6P3Z"
        const val SECRET_ID = "01K2EP16NWNAGJYF8J1Q2V6P4A"
    }
}
