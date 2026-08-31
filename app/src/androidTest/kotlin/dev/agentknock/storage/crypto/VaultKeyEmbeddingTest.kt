package dev.agentknock.storage.crypto

import androidx.room3.Room
import androidx.room3.executeSQL
import androidx.room3.useWriterConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.agentknock.storage.AgentknockDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultKeyEmbeddingTest {
    private lateinit var database: AgentknockDatabase
    private lateinit var dao: VaultKeyDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AgentknockDatabase::class.java,
        ).build()
        dao = database.vaultKeyDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun nullableWrappingRoundTripsAsOneValue() = runTest {
        dao.insertKey(key("unwrapped", wrapping = null))
        assertNull(dao.getKey("unwrapped")?.wrapping)

        val wrapping = WrappedVaultKey(
            formatVersion = 1,
            recoveryRootId = "recovery-root",
            nonce = ByteArray(12) { it.toByte() },
            ciphertext = ByteArray(32) { (it + 12).toByte() },
        )
        dao.insertKey(key("wrapped", wrapping))

        val stored = checkNotNull(dao.getKey("wrapped")?.wrapping)
        assertEquals(wrapping.formatVersion, stored.formatVersion)
        assertEquals(wrapping.recoveryRootId, stored.recoveryRootId)
        assertArrayEquals(wrapping.nonce, stored.nonce)
        assertArrayEquals(wrapping.ciphertext, stored.ciphertext)
    }

    @Test
    fun partialWrappingNeverBecomesAnApparentlyValidValue() = runTest {
        val columns = mapOf(
            "wrapping_format" to "1",
            "recovery_root_id" to "'recovery-root'",
            "wrapping_nonce" to "X'000000000000000000000000'",
            "wrapped_key" to "X'0000000000000000000000000000000000000000000000000000000000000000'",
        )

        columns.keys.forEach { missingColumn ->
            dao.insertKey(
                key(
                    id = "partial-$missingColumn",
                    wrapping = WrappedVaultKey(
                        formatVersion = 1,
                        recoveryRootId = "recovery-root",
                        nonce = ByteArray(12),
                        ciphertext = ByteArray(32),
                    ),
                ),
            )
            database.useWriterConnection { connection ->
                connection.executeSQL(
                    "UPDATE vault_keys SET $missingColumn = NULL " +
                        "WHERE id = 'partial-$missingColumn'",
                )
            }

            val read = runCatching { dao.getKey("partial-$missingColumn") }
            if (missingColumn == "wrapping_format") {
                val partial = checkNotNull(read.getOrThrow()?.wrapping)
                assertEquals(0, partial.formatVersion)
                assertEquals(
                    VaultKeyUnwrapResult.UnsupportedFormat,
                    VaultKeyWrapping.unwrap(
                        wrapped = partial,
                        recoveryRoot = ByteArray(16),
                        vaultKeyId = "partial-$missingColumn",
                        purpose = VaultKeyPurpose.SECRET_VALUES,
                    ),
                )
            } else {
                assertTrue(
                    "A wrapping with a missing $missingColumn column was accepted",
                    read.isFailure,
                )
            }
        }
    }

    private fun key(id: String, wrapping: WrappedVaultKey?) = VaultKeyEntity(
        id = id,
        purpose = VaultKeyPurpose.SECRET_VALUES.storedName,
        active = false,
        createdAt = 1,
        backing = EncryptionKeyBacking.SOFTWARE.name,
        wrapping = wrapping,
    )
}
