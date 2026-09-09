package dev.agentknock.storage.secret

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.agentknock.protocol.SshSignatureAlgorithm
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SshKeyCodecInstrumentedTest {
    @Test
    fun generatedRsaKeyCanBeReloadedAndUsedForSshAuthentication() {
        val codec = SshKeyCodec()
        val generated = codec.generateRsa("device-test@example")
        val stored =
            codec.fromStored(
                generated.algorithm,
                generated.privateKey,
                generated.publicKey,
                generated.comment,
            )
        assertArrayEquals(generated.privateKey, stored.privateKey)
        assertArrayEquals(generated.publicKey, stored.publicKey)

        val publicKey =
            DataInputStream(ByteArrayInputStream(stored.publicKey)).use { input ->
                val exponent = BigInteger(input.readSshBytes())
                val modulus = BigInteger(input.readSshBytes())
                assertEquals(3072, modulus.bitLength())
                assertEquals(0, input.available())
                KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
            }
        val message = "RSA authentication after reloading a stored key".encodeToByteArray()
        val blob = codec.signSshAuthentication(stored, message, SshSignatureAlgorithm.RSA_SHA512)
        DataInputStream(ByteArrayInputStream(blob)).use { input ->
            assertEquals("rsa-sha2-512", input.readSshBytes().decodeToString())
            val signature = input.readSshBytes()
            assertEquals(0, input.available())
            val verifier =
                Signature.getInstance("SHA512withRSA").apply {
                    initVerify(publicKey)
                    update(message)
                }
            assertTrue(verifier.verify(signature))
        }
    }

    private fun DataInputStream.readSshBytes(): ByteArray = ByteArray(readInt()).also(::readFully)
}
