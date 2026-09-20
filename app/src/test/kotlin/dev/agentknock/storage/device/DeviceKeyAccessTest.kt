package dev.agentknock.storage.device

import dev.agentknock.protocol.DeviceProtocol
import dev.agentknock.storage.crypto.DecryptionResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceKeyAccessTest {
    @Test
    fun `decrypts afresh per operation and clears each borrowed key`() = runTest {
        val buffers = mutableListOf<ByteArray>()
        val borrowed = mutableListOf<ByteArray>()
        val expected = ByteArray(32) { (it + 1).toByte() }
        val key =
            DeviceKeyAccess(StandardTestDispatcher(testScheduler)) {
                DecryptionResult.Plaintext(expected.copyOf().also(buffers::add))
            }
        assertTrue(buffers.isEmpty())
        repeat(2) {
            val publicKey = key.use { pair ->
                assertArrayEquals(expected, pair.privateKey)
                borrowed += pair.privateKey
                pair.publicKey
            }
            assertArrayEquals(DeviceProtocol.deriveDevicePublicKey(expected), publicKey)
            assertTrue((buffers + borrowed).all { bytes -> bytes.all { it == 0.toByte() } })
        }
        assertEquals(2, buffers.size)
    }

    @Test
    fun `clears the key when cryptography throws`() = runTest {
        val bytes = ByteArray(32) { 73 }
        val key =
            DeviceKeyAccess(StandardTestDispatcher(testScheduler)) {
                DecryptionResult.Plaintext(bytes)
            }
        val failure = IllegalArgumentException("malformed request")
        val result = runCatching { key.use { throw failure } }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals(failure.message, result.exceptionOrNull()?.message)
        assertArrayEquals(ByteArray(32), bytes)
    }

    @Test
    fun `rejects and clears invalid key lengths without invoking cryptography`() = runTest {
        for (length in listOf(0, 31, 33)) {
            val bytes = ByteArray(length) { 73 }
            var called = false
            val key =
                DeviceKeyAccess(StandardTestDispatcher(testScheduler)) {
                    DecryptionResult.Plaintext(bytes)
                }
            val result = runCatching { key.use { called = true } }
            assertTrue(result.exceptionOrNull() is DeviceKeyAccessException)
            assertFalse(called)
            assertArrayEquals(ByteArray(length), bytes)
        }
    }

    @Test
    fun `decryption failures do not invoke cryptography`() = runTest {
        for (failure in
            listOf(
                DecryptionResult.KeyUnavailable,
                DecryptionResult.AuthenticationFailed,
                DecryptionResult.UnsupportedFormat,
            )) {
            var called = false
            val key = DeviceKeyAccess(StandardTestDispatcher(testScheduler)) { failure }
            val result = runCatching { key.use { called = true } }
            assertTrue(result.exceptionOrNull() is DeviceKeyAccessException)
            assertFalse(called)
        }
    }

    @Test
    fun `provider exceptions are key access failures rather than malformed requests`() = runTest {
        val key =
            DeviceKeyAccess(StandardTestDispatcher(testScheduler)) {
                throw IllegalArgumentException("provider failure")
            }
        val result = runCatching { key.use { error("must not run") } }
        val failure = result.exceptionOrNull() as DeviceKeyAccessException
        assertEquals(DeviceCredentialResult.Unavailable, failure.failure)
        assertEquals("provider failure", failure.cause?.message)
    }

    @Test
    fun `clears the key before cancellation discards the operation result`() = runTest {
        val bytes = ByteArray(32) { 73 }
        val key =
            DeviceKeyAccess(StandardTestDispatcher(testScheduler)) {
                DecryptionResult.Plaintext(bytes)
            }
        var returned = false
        lateinit var job: Job
        job = launch {
            key.use {
                job.cancel()
                "completed cryptography"
            }
            returned = true
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(returned)
        assertArrayEquals(ByteArray(32), bytes)
    }
}
