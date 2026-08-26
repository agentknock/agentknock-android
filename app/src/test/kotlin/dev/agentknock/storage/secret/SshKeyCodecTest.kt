package dev.agentknock.storage.secret

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SshKeyCodecTest {
    private val codec = SshKeyCodec()

    @Test
    fun `imports a standard unencrypted OpenSSH Ed25519 private key`() {
        val key = codec.importOpenSshPrivateKey(TEST_PRIVATE_KEY)

        assertEquals(SshKeyAlgorithm.ED25519, key.algorithm)
        assertEquals(32, key.privateKey.size)
        assertEquals(32, key.publicKey.size)
        assertEquals("test@example", key.comment)
        assertEquals(TEST_PUBLIC_KEY, key.publicKeyLine)
        assertTrue(key.fingerprint.startsWith("SHA256:"))
        codec.fromStored(
            key.algorithm.storedName,
            key.privateKey,
            key.publicKey,
            key.comment,
        )
    }

    @Test
    fun `generates independent Ed25519 keys with ordinary OpenSSH public keys`() {
        val first = codec.generateEd25519("alice@example")
        val second = codec.generateEd25519("alice@example")

        assertFalse(first.privateKey.contentEquals(second.privateKey))
        assertTrue(first.publicKeyLine.startsWith("ssh-ed25519 "))
        assertTrue(first.publicKeyLine.endsWith(" alice@example"))
        codec.fromStored(first.algorithm.storedName, first.privateKey, first.publicKey, first.comment)
        codec.fromStored(second.algorithm.storedName, second.privateKey, second.publicKey, second.comment)
    }

    @Test
    fun `rejects a private key whose public half was changed`() {
        val key = codec.importOpenSshPrivateKey(TEST_PRIVATE_KEY)
        val changed = key.copy(publicKey = key.publicKey.copyOf().also { it[0] = (it[0] + 1).toByte() })

        assertThrows(IllegalArgumentException::class.java) {
            codec.fromStored(
                changed.algorithm.storedName,
                changed.privateKey,
                changed.publicKey,
                changed.comment,
            )
        }
    }

    @Test
    fun `rejects PEM that is not an OpenSSH private key`() {
        assertThrows(IllegalArgumentException::class.java) {
            codec.importOpenSshPrivateKey("-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----")
        }
    }

    @Test
    fun `rejects an encrypted OpenSSH private key before parsing its key material`() {
        val lines = TEST_PRIVATE_KEY.lines()
        val decoded = Base64.getDecoder().decode(lines.drop(1).dropLast(1).joinToString(""))
        val cipherNameOffset = "openssh-key-v1\u0000".encodeToByteArray().size + 4
        "lock".encodeToByteArray().copyInto(decoded, destinationOffset = cipherNameOffset)
        val encoded = Base64.getMimeEncoder(70, "\n".encodeToByteArray()).encodeToString(decoded)
        val encrypted = "-----BEGIN OPENSSH PRIVATE KEY-----\n$encoded\n" +
            "-----END OPENSSH PRIVATE KEY-----"

        val error = assertThrows(IllegalArgumentException::class.java) {
            codec.importOpenSshPrivateKey(encrypted)
        }
        assertEquals("Encrypted SSH private keys are not supported", error.message)
    }

    companion object {
        private val TEST_PRIVATE_KEY = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
            QyNTUxOQAAACDFWrVoDowh5mZBmpXaupzu8anhOZUnvLU4cuFdgQuXNQAAAJAz/VUjM/1V
            IwAAAAtzc2gtZWQyNTUxOQAAACDFWrVoDowh5mZBmpXaupzu8anhOZUnvLU4cuFdgQuXNQ
            AAAED/jbzSJM+mIIGmb5NRwmCMOha2lr76MMRszR6laOg1x8VatWgOjCHmZkGaldq6nO7x
            qeE5lSe8tThy4V2BC5c1AAAADHRlc3RAZXhhbXBsZQE=
            -----END OPENSSH PRIVATE KEY-----
        """.trimIndent()
        private const val TEST_PUBLIC_KEY =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIMVatWgOjCHmZkGaldq6nO7xqeE5lSe8tThy4V2BC5c1 test@example"
    }
}
