package dev.agentknock.storage.device

import androidx.room3.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.agentknock.storage.AgentknockDatabase
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
    fun replacingIdentityRetainsCredentialsClientsAndRequestProvenance() = runTest {
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
        database.requestDao().insertRequest(request(original.id))

        assertEquals(
            true,
            database.deviceIdentityDao().promoteCandidate(
                candidateId = replacement.id,
                claimedAt = 3,
                activeRole = "active",
                candidateRole = "candidate",
                retiredRole = "retired",
            ),
        )

        val identities = database.deviceIdentityDao().observeIdentities().first()
        assertEquals("retired", identities.single { it.id == original.id }.role)
        assertEquals("active", identities.single { it.id == replacement.id }.role)
        assertEquals(2, database.deviceIdentityDao().getCredentials(original.id).size)
        assertEquals(2, database.deviceIdentityDao().getCredentials(replacement.id).size)
        assertNull(database.requestDao().getClient(CLIENT_ID))
        assertEquals(original.id, database.requestDao().getClientById(CLIENT_ID)?.deviceIdentityId)
        assertEquals(
            original.id,
            database.requestDao().getRequestById(REQUEST_ID)?.deviceIdentityId,
        )

        val secondReplacement = identity("identity-3", "device-3", "candidate", 4)
        database.deviceIdentityDao().insertIdentity(secondReplacement)
        database.deviceIdentityDao().insertCredentials(credentials(secondReplacement.id, 3))
        assertEquals(
            true,
            database.deviceIdentityDao().promoteCandidate(
                candidateId = secondReplacement.id,
                claimedAt = 5,
                activeRole = "active",
                candidateRole = "candidate",
                retiredRole = "retired",
            ),
        )

        val twiceReplaced = database.deviceIdentityDao().observeIdentities().first()
        assertEquals(2, twiceReplaced.count { it.role == "retired" })
        assertNotNull(database.deviceIdentityDao().getIdentityById(original.id))
        assertEquals(
            6,
            twiceReplaced.sumOf { database.deviceIdentityDao().getCredentials(it.id).size },
        )
        assertNull(database.requestDao().getClient(CLIENT_ID))
        assertEquals(original.id, database.requestDao().getClientById(CLIENT_ID)?.deviceIdentityId)
        assertEquals(
            original.id,
            database.requestDao().getRequestById(REQUEST_ID)?.deviceIdentityId,
        )
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
                updatedAt = 20,
            ),
        )
        requestDao.insertClientPsk(clientPsk())

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
                requestAcknowledgedAt = 15,
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
                requestAcknowledgedAt = 16,
                responseAcknowledgedAt = null,
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
        requestDao.insertRequest(request(original.id))
        val completedBefore = checkNotNull(requestDao.getRequestById(REQUEST_ID))

        assertTrue(
            database.deviceIdentityDao().promoteCandidate(
                candidateId = replacement.id,
                claimedAt = 30,
                activeRole = "active",
                candidateRole = "candidate",
                retiredRole = "retired",
            ),
        )

        listOf(pairingId, invocationId, uploadId, approvedUploadId).forEach { id ->
            val abandoned = checkNotNull(requestDao.getRequestById(id))
            assertEquals("completed", abandoned.state)
            assertEquals(30L, abandoned.completedAt)
            assertTrue(checkNotNull(abandoned.error).contains("previous device identity"))
        }
        val abandonedPairing = checkNotNull(requestDao.getPairingAttemptRecord(pairingId))
        assertEquals("rejected", abandonedPairing.state)
        assertEquals(30L, abandonedPairing.decidedAt)
        assertNull(abandonedPairing.desiredRelayClientState)
        assertNull(abandonedPairing.pendingPsk)
        assertTrue(
            checkNotNull(checkNotNull(requestDao.getRequestById(pairingId)).error)
                .contains("pairing completion was malformed"),
        )
        assertEquals("rejected", requestDao.getSecretUploadRequest(uploadId)?.decision)
        assertEquals("approved", requestDao.getSecretUploadRequest(approvedUploadId)?.decision)
        assertTrue(requestDao.getSecretUploadEnvironmentVariables(uploadId).isEmpty())
        assertNull(requestDao.getClientById(CLIENT_ID)?.desiredRelayClientState)
        assertNotNull(requestDao.getClientPsk(CLIENT_ID, "current"))
        assertNotNull(requestDao.getRequestPsk(invocationId))
        assertEquals(completedBefore, requestDao.getRequestById(REQUEST_ID))

        val abandonedInvocation = checkNotNull(requestDao.getRequestById(invocationId))
        assertEquals(16L, abandonedInvocation.requestAcknowledgedAt)
        assertNull(abandonedInvocation.responseAcknowledgedAt)
        assertTrue(requestDao.getUnacknowledgedResponses().isEmpty())
        assertTrue(requestDao.getUnsettledRequests().isEmpty())
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
                claimedAt = 3,
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
            claimedAt = createdAt.takeIf { role == "active" },
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
        updatedAt = 11,
    )

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
        updatedAt = 12,
        completedAt = 12,
        requestAcknowledgedAt = 12,
        responseAcknowledgedAt = null,
        completionAcknowledgedAt = null,
    )

    private companion object {
        const val CLIENT_ID = "01K2EP16NWNAGJYF8J1Q2V6P3X"
        const val REQUEST_ID = "01K2EP16NWNAGJYF8J1Q2V6P3Z"
    }
}
