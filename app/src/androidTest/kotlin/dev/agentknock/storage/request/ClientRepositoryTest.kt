package dev.agentknock.storage.request

import androidx.room3.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.agentknock.relay.RelayClientState
import dev.agentknock.storage.AgentknockDatabase
import dev.agentknock.storage.RoomWriteTransaction
import dev.agentknock.storage.audit.AuditEventType
import dev.agentknock.storage.audit.AuditRecord
import dev.agentknock.storage.audit.AuditRepository
import dev.agentknock.storage.audit.AuditSink
import dev.agentknock.storage.device.DeviceIdentityEntity
import dev.agentknock.storage.secret.TemporaryAccessGrant
import dev.agentknock.storage.secret.TemporaryAccessOperation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClientRepositoryTest {
    private lateinit var database: AgentknockDatabase
    private lateinit var audit: AuditRepository
    private lateinit var grants: MutableStateFlow<List<TemporaryAccessGrant>>

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AgentknockDatabase::class.java,
        ).build()
        database.deviceIdentityDao().insertIdentity(
            DeviceIdentityEntity(
                id = DEVICE_IDENTITY_ID,
                role = "active",
                address = "amber-river-maple",
                deviceId = "01JDEVICE000000000000000000",
                createdAt = 1L,
            ),
        )
        audit = AuditRepository(database.auditDao(), currentTimeMillis = { 10L })
        grants = MutableStateFlow(emptyList())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun projectionsExposeDurableClientMetadataAndHideRevocation() = runTest {
        database.requestDao().insertClient(client())
        grants.value = listOf(
            TemporaryAccessGrant(
                secretId = "secret",
                secretName = "Deployment",
                clientId = CLIENT_ID,
                operation = TemporaryAccessOperation.INVOCATION,
                expiresAt = 1_000L,
            ),
        )
        val repository = repository(audit)

        val summary = repository.observeClients().first().single()
        assertEquals(CLIENT_ID, summary.clientId)
        assertEquals("Workstation", summary.name)
        assertEquals(1, summary.temporaryAccessCount)
        assertEquals("host", repository.observeClient(CLIENT_ID).first()?.hostname)

        assertEquals(
            ClientChangeResult.CHANGED,
            repository.setDesiredRelayState(CLIENT_ID, RelayClientState.REVOKED),
        )
        assertTrue(repository.observeClients().first().isEmpty())
        assertNull(repository.observeClient(CLIENT_ID).first())
    }

    @Test
    fun desiredRevocationCannotBeOverwrittenBeforeRelayAcknowledgesIt() = runTest {
        database.requestDao().insertClient(
            client(desiredState = RelayClientState.REVOKED.wireName),
        )
        val repository = repository(audit)

        assertEquals(
            ClientChangeResult.INVALID_STATE,
            repository.setDesiredRelayState(CLIENT_ID, RelayClientState.SUSPENDED),
        )
        assertEquals(
            ClientChangeResult.INVALID_STATE,
            repository.setDesiredRelayState(CLIENT_ID, RelayClientState.ACTIVE),
        )
        assertEquals(
            ClientChangeResult.CHANGED,
            repository.setDesiredRelayState(CLIENT_ID, RelayClientState.REVOKED),
        )
        assertEquals(
            RelayClientState.REVOKED.wireName,
            database.requestDao().getClient(CLIENT_ID)?.desiredRelayClientState,
        )
    }

    @Test
    fun renameIsANoOpForTheExistingTrimmedNameAndRollsBackWithItsAudit() = runTest {
        database.requestDao().insertClient(client())
        val repository = repository(InsertThenFailAuditSink(audit))

        assertEquals(
            ClientChangeResult.CHANGED,
            repository.rename(CLIENT_ID, "  Workstation  "),
        )
        assertTrue(audit.observeEvents().first().isEmpty())

        assertTrue(runCatching { repository.rename(CLIENT_ID, "Laptop") }.isFailure)
        assertEquals("Workstation", database.requestDao().getClient(CLIENT_ID)?.name)
        assertTrue(audit.observeEvents().first().isEmpty())

        assertEquals(ClientChangeResult.CHANGED, repository(audit).rename(CLIENT_ID, "Laptop"))
        assertEquals("Laptop", database.requestDao().getClient(CLIENT_ID)?.name)
        assertEquals(
            listOf(AuditEventType.CLIENT_RENAMED),
            audit.observeEvents().first().map { it.type },
        )
    }

    @Test
    fun instructionsAndTheirAuditRollBackTogether() = runTest {
        database.requestDao().insertClient(client(instructions = "Original"))
        val repository = repository(InsertThenFailAuditSink(audit))

        assertTrue(runCatching { repository.saveInstructions(CLIENT_ID, "Changed") }.isFailure)
        assertEquals("Original", database.requestDao().getClient(CLIENT_ID)?.instructions)
        assertTrue(audit.observeEvents().first().isEmpty())

        assertEquals(
            ClientChangeResult.CHANGED,
            repository(audit).saveInstructions(CLIENT_ID, "Changed"),
        )
        assertEquals("Changed", database.requestDao().getClient(CLIENT_ID)?.instructions)
        assertEquals(
            listOf(AuditEventType.CLIENT_INSTRUCTIONS_CHANGED),
            audit.observeEvents().first().map { it.type },
        )
    }

    @Test
    fun appliedRelayStateAndItsAuditRollBackTogether() = runTest {
        database.requestDao().insertClient(
            client(desiredState = RelayClientState.REVOKED.wireName),
        )
        val failingRepository = repository(InsertThenFailAuditSink(audit))

        assertTrue(
            runCatching {
                failingRepository.applyRelayState(CLIENT_ID, RelayClientState.REVOKED)
            }.isFailure,
        )
        assertEquals("active", database.requestDao().getClient(CLIENT_ID)?.relayClientState)
        assertTrue(audit.observeEvents().first().isEmpty())

        repository(audit).applyRelayState(CLIENT_ID, RelayClientState.REVOKED)
        assertNull(database.requestDao().getClient(CLIENT_ID))
        assertEquals(
            listOf(AuditEventType.CLIENT_REVOKED),
            audit.observeEvents().first().map { it.type },
        )
    }

    private fun repository(auditSink: AuditSink) = ClientRepository(
        dao = database.requestDao(),
        temporaryAccessGrants = grants,
        audit = auditSink,
        writeTransaction = RoomWriteTransaction(database),
    )

    private fun client(
        instructions: String = "",
        desiredState: String? = null,
    ) = ClientEntity(
        clientId = CLIENT_ID,
        deviceIdentityId = DEVICE_IDENTITY_ID,
        name = "Workstation",
        instructions = instructions,
        desiredRelayClientState = desiredState,
        relayClientState = RelayClientState.ACTIVE.wireName,
        clientSoftwareJson =
            """{"app_info":{"name":"agentknock","version":"0.3.0"},"lib_info":{"name":"agentknock","version":"0.3.0"}}""",
        platform = "linux",
        architecture = "x86_64",
        hostname = "host",
        machineId = "machine",
        osVersion = "NixOS",
        pairedAt = 2L,
        lastSeenAt = 3L,
    )

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

    private companion object {
        const val DEVICE_IDENTITY_ID = "device-identity"
        const val CLIENT_ID = "01JCLIENT000000000000000000"
    }
}
