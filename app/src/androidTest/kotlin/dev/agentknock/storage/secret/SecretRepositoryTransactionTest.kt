package dev.agentknock.storage.secret

import androidx.room3.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.agentknock.storage.AgentknockDatabase
import dev.agentknock.storage.RoomWriteTransaction
import dev.agentknock.storage.audit.AuditEventType
import dev.agentknock.storage.audit.AuditRecord
import dev.agentknock.storage.audit.AuditRepository
import dev.agentknock.storage.audit.AuditSink
import dev.agentknock.storage.crypto.AesGcmEncryption
import dev.agentknock.storage.crypto.EncryptionKeyBacking
import dev.agentknock.storage.crypto.EncryptionKeyStore
import dev.agentknock.storage.crypto.GeneratedEncryptionKey
import dev.agentknock.storage.crypto.VaultKeyManager
import dev.agentknock.storage.device.DeviceIdentityEntity
import dev.agentknock.storage.request.ClientEntity
import javax.crypto.SecretKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecretRepositoryTransactionTest {
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
    fun secretAndAuditEventCommitTogether() = runTest {
        val audit = AuditRepository(database.auditDao(), currentTimeMillis = { 5L })
        val repository = repository(audit)

        assertEquals(
            CreateSecretResult.Created("secret-id"),
            repository.createEnvironmentSecret("production", "Deployment credentials"),
        )

        assertEquals(listOf("production"), database.secretDao().getSecrets().map { it.name })
        val events = audit.observeEvents().first()
        assertEquals(1, events.size)
        assertEquals(AuditEventType.SECRET_CREATED, events.single().type)
        assertEquals("production", events.single().subject)
    }

    @Test
    fun auditFailureRollsBackSecretCreation() = runTest {
        val failure = IllegalStateException("audit failed")
        val audit = AuditRepository(database.auditDao(), currentTimeMillis = { 5L })
        val repository = repository(
            object : AuditSink {
                override suspend fun record(record: AuditRecord) {
                    audit.record(record)
                    throw failure
                }

                override suspend fun append(records: List<AuditRecord>, occurredAt: Long) {
                    audit.append(records, occurredAt)
                    throw failure
                }
            },
        )

        val thrown = runCatching {
            repository.createEnvironmentSecret("production", "Deployment credentials")
        }.exceptionOrNull()

        assertEquals(failure, thrown)
        assertTrue(database.secretDao().getSecrets().isEmpty())
        assertTrue(database.auditDao().observeEvents().first().isEmpty())
    }

    @Test
    fun temporaryGrantsAndAuditEventsCommitOrRollBackAsOneBatch() = runTest {
        database.deviceIdentityDao().insertIdentity(
            DeviceIdentityEntity(
                id = "device-identity",
                role = "active",
                address = "amber-river-maple",
                deviceId = "01JDEVICE000000000000000000",
                createdAt = 1L,
            ),
        )
        database.requestDao().insertClient(
            ClientEntity(
                clientId = "workstation",
                deviceIdentityId = "device-identity",
                name = "Workstation",
                instructions = "",
                desiredRelayClientState = null,
                relayClientState = "active",
                clientSoftwareJson = null,
                platform = null,
                architecture = null,
                hostname = null,
                machineId = null,
                osVersion = null,
                pairedAt = 2L,
                lastSeenAt = null,
            ),
        )
        database.secretDao().insertSecret(environmentSecret("first", "First"))
        database.secretDao().insertSecret(environmentSecret("second", "Second"))
        val audit = AuditRepository(database.auditDao(), currentTimeMillis = { 5L })
        val failing = repository(
            object : AuditSink {
                override suspend fun record(record: AuditRecord) = audit.record(record)

                override suspend fun append(records: List<AuditRecord>, occurredAt: Long) {
                    audit.append(records, occurredAt)
                    error("audit failed")
                }
            },
        )
        val policies = failing.approvalPoliciesForNames(
            names = listOf("First", "Second"),
            clientId = "workstation",
            operation = TemporaryAccessOperation.INVOCATION,
        )

        assertTrue(
            runCatching {
                failing.allowTemporaryAccess(
                    policies = policies,
                    clientId = "workstation",
                    operation = TemporaryAccessOperation.INVOCATION,
                    expiresAt = 1_000L,
                )
            }.isFailure,
        )
        assertTrue(
            database.secretDao().getActiveTemporaryAccessGrants(
                clientId = "workstation",
                secretIds = listOf("first", "second"),
                operation = TemporaryAccessOperation.INVOCATION.storedName,
                now = 5L,
            ).isEmpty(),
        )
        assertTrue(database.auditDao().observeEvents().first().isEmpty())

        assertTrue(
            repository(audit).allowTemporaryAccess(
                policies = policies,
                clientId = "workstation",
                operation = TemporaryAccessOperation.INVOCATION,
                expiresAt = 1_000L,
            ),
        )
        assertEquals(
            2,
            database.secretDao().getActiveTemporaryAccessGrants(
                clientId = "workstation",
                secretIds = listOf("first", "second"),
                operation = TemporaryAccessOperation.INVOCATION.storedName,
                now = 5L,
            ).size,
        )
        val summaries = repository(audit).observeSecrets().first()
        assertEquals(2, summaries.size)
        summaries.forEach { secret ->
            val grant = secret.temporaryAccessGrants.single()
            assertEquals(secret.id, grant.secretId)
            assertEquals("workstation", grant.clientId)
            assertEquals(1_000L, grant.expiresAt)
        }
        assertEquals(
            listOf(
                AuditEventType.TEMPORARY_ACCESS_ALLOWED,
                AuditEventType.TEMPORARY_ACCESS_ALLOWED,
            ),
            audit.observeEvents().first().map { it.type },
        )
    }

    private fun repository(audit: AuditSink) = SecretRepository(
        dao = database.secretDao(),
        keyManager = VaultKeyManager(database.vaultKeyDao(), UnusedEncryptionKeyStore),
        encryption = AesGcmEncryption(UnusedEncryptionKeyStore),
        audit = audit,
        writeTransaction = RoomWriteTransaction(database),
        newId = { "secret-id" },
        currentTimeMillis = { 5L },
    )

    private fun environmentSecret(id: String, name: String) = SecretEntity(
        id = id,
        name = name,
        description = "",
        type = ENVIRONMENT_SECRET_TYPE,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private object UnusedEncryptionKeyStore : EncryptionKeyStore {
        override fun get(keyId: String): SecretKey? = null

        override fun generate(keyId: String): GeneratedEncryptionKey =
            GeneratedEncryptionKey(EncryptionKeyBacking.SOFTWARE)

        override fun delete(keyId: String) = Unit
    }
}
