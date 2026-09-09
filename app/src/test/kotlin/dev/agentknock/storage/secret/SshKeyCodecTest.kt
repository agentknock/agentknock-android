package dev.agentknock.storage.secret

import dev.agentknock.protocol.SshSignatureAlgorithm
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.RSAPrivateCrtKeySpec
import java.util.Base64
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertArrayEquals
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
        val publicKey = codec.publicKey(key.algorithm, key.publicKey, key.comment)
        assertTrue(publicKey.fingerprintHex.matches(Regex("(?:[0-9A-F]{2}:){31}[0-9A-F]{2}")))
        assertEquals(256, codec.bitLength(publicKey))
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
        codec.fromStored(
            first.algorithm.storedName,
            first.privateKey,
            first.publicKey,
            first.comment,
        )
        codec.fromStored(
            second.algorithm.storedName,
            second.privateKey,
            second.publicKey,
            second.comment,
        )
    }

    @Test
    fun `imports a standard unencrypted OpenSSH RSA private key`() {
        val fixture = rsaFixture()

        val key = codec.importOpenSshPrivateKey(fixture.privateKey)

        assertEquals(SshKeyAlgorithm.RSA, key.algorithm)
        assertEquals("rsa@test", key.comment)
        assertEquals(fixture.publicKey, key.publicKeyLine)
        assertTrue(key.fingerprint.startsWith("SHA256:"))
        val publicKey = codec.publicKey(key.algorithm, key.publicKey, key.comment)
        assertTrue(publicKey.fingerprintHex.matches(Regex("(?:[0-9A-F]{2}:){31}[0-9A-F]{2}")))
        assertEquals(2048, codec.bitLength(publicKey))
        codec.fromStored(
            key.algorithm.storedName,
            key.privateKey,
            key.publicKey,
            key.comment,
        )
    }

    @Test
    fun `generates a 3072-bit RSA key with an ordinary OpenSSH public key`() {
        val key = codec.generateRsa("generated@example")

        assertEquals(SshKeyAlgorithm.RSA, key.algorithm)
        assertTrue(key.publicKeyLine.startsWith("ssh-rsa "))
        assertTrue(key.publicKeyLine.endsWith(" generated@example"))
        val stored =
            codec.fromStored(
                key.algorithm.storedName,
                key.privateKey,
                key.publicKey,
                key.comment,
            )
        val privateKey =
            java.security.KeyFactory.getInstance("RSA")
                .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(stored.privateKey))
                as RSAPrivateCrtKey
        assertEquals(3072, privateKey.modulus.bitLength())
    }

    @Test
    fun `rejects a private key whose public half was changed`() {
        val key = codec.importOpenSshPrivateKey(TEST_PRIVATE_KEY)
        val changed =
            key.copy(publicKey = key.publicKey.copyOf().also { it[0] = (it[0] + 1).toByte() })

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
            codec.importOpenSshPrivateKey(
                "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----"
            )
        }
    }

    @Test
    fun `rejects an encrypted OpenSSH private key before parsing its key material`() {
        val lines = TEST_PRIVATE_KEY.lines()
        val decoded = Base64.getDecoder().decode(lines.drop(1).dropLast(1).joinToString(""))
        val cipherNameOffset = "openssh-key-v1\u0000".encodeToByteArray().size + 4
        "lock".encodeToByteArray().copyInto(decoded, destinationOffset = cipherNameOffset)
        val encoded = Base64.getMimeEncoder(70, "\n".encodeToByteArray()).encodeToString(decoded)
        val encrypted =
            "-----BEGIN OPENSSH PRIVATE KEY-----\n$encoded\n" + "-----END OPENSSH PRIVATE KEY-----"

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                codec.importOpenSshPrivateKey(encrypted)
            }
        assertEquals("Encrypted SSH private keys are not supported", error.message)
    }

    @Test
    fun `rejects noncanonical OpenSSH none-cipher padding`() {
        val lines = TEST_PRIVATE_KEY.lines()
        val decoded = Base64.getDecoder().decode(lines.drop(1).dropLast(1).joinToString(""))
        decoded[decoded.lastIndex] = 0
        val encoded = Base64.getMimeEncoder(70, "\n".encodeToByteArray()).encodeToString(decoded)
        val tampered =
            "-----BEGIN OPENSSH PRIVATE KEY-----\n$encoded\n" + "-----END OPENSSH PRIVATE KEY-----"

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                codec.importOpenSshPrivateKey(tampered)
            }
        assertEquals("Invalid OpenSSH private key padding", error.message)
    }

    @Test
    fun `imports an OpenSSH private key whose private block needs no padding`() {
        val key = codec.importOpenSshPrivateKey(withoutFinalPaddingByte(TEST_PRIVATE_KEY))

        assertEquals(SshKeyAlgorithm.ED25519, key.algorithm)
        assertEquals("test@examplex", key.comment)
        assertEquals(TEST_PUBLIC_KEY + "x", key.publicKeyLine)
    }

    @Test
    fun `rejects RSA private material with inconsistent CRT exponents`() {
        val key = codec.importOpenSshPrivateKey(rsaFixture().privateKey)
        val privateKey =
            KeyFactory.getInstance("RSA")
                .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(key.privateKey))
                as RSAPrivateCrtKey
        val inconsistent =
            KeyFactory.getInstance("RSA")
                .generatePrivate(
                    RSAPrivateCrtKeySpec(
                        privateKey.modulus,
                        privateKey.publicExponent,
                        privateKey.privateExponent.add(BigInteger.TWO),
                        privateKey.primeP,
                        privateKey.primeQ,
                        privateKey.primeExponentP,
                        privateKey.primeExponentQ,
                        privateKey.crtCoefficient,
                    )
                )
                .encoded

        assertThrows(IllegalArgumentException::class.java) {
            codec.fromStored(
                SshKeyAlgorithm.RSA.storedName,
                inconsistent,
                key.publicKey,
                key.comment,
            )
        }
    }

    @Test
    fun `rejects an invalid RSA public exponent`() {
        val fixture = rsaFixture()
        val privateKey = fixture.keyPair.private as RSAPrivateCrtKey
        val blob =
            ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.writeSshString("ssh-rsa".encodeToByteArray())
                    output.writeMpint(BigInteger.TWO)
                    output.writeMpint(privateKey.modulus)
                }
                bytes.toByteArray()
            }
        val encoded = "ssh-rsa ${Base64.getEncoder().encodeToString(blob)} invalid@test"

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                codec.importOpenSshPublicKey(encoded)
            }
        assertEquals("Invalid RSA public exponent", error.message)
    }

    @Test
    fun `rejects oversized PEM before Base64 decoding`() {
        val oversized =
            "-----BEGIN OPENSSH PRIVATE KEY-----\n" +
                "A".repeat(310_000) +
                "\n-----END OPENSSH PRIVATE KEY-----"

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                codec.importOpenSshPrivateKey(oversized)
            }
        assertEquals("The OpenSSH private key is too large", error.message)
    }

    @Test
    fun `creates a valid OpenSSH SSHSIG envelope over the exact message`() {
        val key = codec.importOpenSshPrivateKey(TEST_PRIVATE_KEY)
        val message = "tree 1234\n\nSign this exact change\n".encodeToByteArray()

        val armored = codec.signGitSignature(key, message)

        assertTrue(armored.startsWith("-----BEGIN SSH SIGNATURE-----\n"))
        assertTrue(armored.endsWith("-----END SSH SIGNATURE-----\n"))
        val body = armored.lines().drop(1).dropLast(2).joinToString("")
        val input = DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(body)))
        assertArrayEquals("SSHSIG".encodeToByteArray(), ByteArray(6).also(input::readFully))
        assertEquals(1, input.readInt())
        assertArrayEquals(
            codec.importOpenSshPublicKey(TEST_PUBLIC_KEY).blob(),
            input.readSshBytes(),
        )
        assertEquals("git", input.readSshString())
        assertArrayEquals(byteArrayOf(), input.readSshBytes())
        assertEquals("sha512", input.readSshString())
        val signatureBlob = DataInputStream(ByteArrayInputStream(input.readSshBytes()))
        assertEquals("ssh-ed25519", signatureBlob.readSshString())
        val signature = signatureBlob.readSshBytes()
        assertEquals(0, signatureBlob.available())
        assertEquals(0, input.available())

        val signedData =
            buildList<Byte> {
                    addAll("SSHSIG".encodeToByteArray().toList())
                    addAll(sshString("git".encodeToByteArray()).toList())
                    addAll(sshString(byteArrayOf()).toList())
                    addAll(sshString("sha512".encodeToByteArray()).toList())
                    addAll(sshString(MessageDigest.getInstance("SHA-512").digest(message)).toList())
                }
                .toByteArray()
        val verifier =
            Ed25519Signer().apply {
                init(false, Ed25519PublicKeyParameters(key.publicKey))
                update(signedData, 0, signedData.size)
            }
        assertTrue(verifier.verifySignature(signature))
    }

    @Test
    fun `creates an RSA SHA-512 SSHSIG envelope`() {
        val fixture = rsaFixture()
        val key = codec.importOpenSshPrivateKey(fixture.privateKey)
        val message = "tree 5678\n\nSign with RSA\n".encodeToByteArray()

        val armored = codec.signGitSignature(key, message)

        val body = armored.lines().drop(1).dropLast(2).joinToString("")
        val input = DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(body)))
        assertArrayEquals("SSHSIG".encodeToByteArray(), ByteArray(6).also(input::readFully))
        assertEquals(1, input.readInt())
        assertArrayEquals(
            codec.importOpenSshPublicKey(fixture.publicKey).blob(),
            input.readSshBytes(),
        )
        assertEquals("git", input.readSshString())
        assertArrayEquals(byteArrayOf(), input.readSshBytes())
        assertEquals("sha512", input.readSshString())
        val signatureBlob = DataInputStream(ByteArrayInputStream(input.readSshBytes()))
        assertEquals("rsa-sha2-512", signatureBlob.readSshString())
        val signature = signatureBlob.readSshBytes()
        assertEquals(0, signatureBlob.available())
        assertEquals(0, input.available())
        val signedData =
            ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.write("SSHSIG".encodeToByteArray())
                    output.writeSshString("git".encodeToByteArray())
                    output.writeSshString(byteArrayOf())
                    output.writeSshString("sha512".encodeToByteArray())
                    output.writeSshString(MessageDigest.getInstance("SHA-512").digest(message))
                }
                bytes.toByteArray()
            }
        val verifier =
            Signature.getInstance("SHA512withRSA").apply {
                initVerify(fixture.keyPair.public)
                update(signedData)
            }
        assertTrue(verifier.verify(signature))
    }

    @Test
    fun `creates a valid Ed25519 SSH authentication signature blob`() {
        val key = codec.importOpenSshPrivateKey(TEST_PRIVATE_KEY)
        val message = "exact SSH authentication packet".encodeToByteArray()

        val blob =
            DataInputStream(
                ByteArrayInputStream(
                    codec.signSshAuthentication(key, message, SshSignatureAlgorithm.ED25519)
                )
            )
        assertEquals("ssh-ed25519", blob.readSshString())
        val signature = blob.readSshBytes()
        assertEquals(0, blob.available())
        val verifier =
            Ed25519Signer().apply {
                init(false, Ed25519PublicKeyParameters(key.publicKey))
                update(message, 0, message.size)
            }
        assertTrue(verifier.verifySignature(signature))
    }

    @Test
    fun `creates RSA SHA-2 SSH authentication signature blobs`() {
        val fixture = rsaFixture()
        val key = codec.importOpenSshPrivateKey(fixture.privateKey)
        val message = "exact RSA SSH authentication packet".encodeToByteArray()
        val algorithms =
            listOf(
                SshSignatureAlgorithm.RSA_SHA256 to "SHA256withRSA",
                SshSignatureAlgorithm.RSA_SHA512 to "SHA512withRSA",
            )

        algorithms.forEach { (algorithm, javaAlgorithm) ->
            val blob =
                DataInputStream(
                    ByteArrayInputStream(codec.signSshAuthentication(key, message, algorithm))
                )
            assertEquals(algorithm.wireName, blob.readSshString())
            val signature = blob.readSshBytes()
            assertEquals(0, blob.available())
            val verifier =
                Signature.getInstance(javaAlgorithm).apply {
                    initVerify(fixture.keyPair.public)
                    update(message)
                }
            assertTrue(verifier.verify(signature))
        }
    }

    private fun DataInputStream.readSshBytes(): ByteArray = ByteArray(readInt()).also(::readFully)

    private fun DataInputStream.readSshString(): String = readSshBytes().decodeToString()

    private fun sshString(value: ByteArray): ByteArray =
        byteArrayOf(
            (value.size ushr 24).toByte(),
            (value.size ushr 16).toByte(),
            (value.size ushr 8).toByte(),
            value.size.toByte(),
        ) + value

    private fun withoutFinalPaddingByte(pem: String): String {
        val lines = pem.lines()
        val encoded = Base64.getDecoder().decode(lines.drop(1).dropLast(1).joinToString(""))
        val paddedComment = sshString("test@example".encodeToByteArray()) + byteArrayOf(1)
        val unpaddedComment = sshString("test@examplex".encodeToByteArray())
        assertEquals(paddedComment.size, unpaddedComment.size)
        val commentOffset = encoded.size - paddedComment.size
        assertArrayEquals(
            paddedComment,
            encoded.copyOfRange(commentOffset, encoded.size),
        )
        unpaddedComment.copyInto(encoded, destinationOffset = commentOffset)
        return "-----BEGIN OPENSSH PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(70, "\n".encodeToByteArray()).encodeToString(encoded) +
            "\n-----END OPENSSH PRIVATE KEY-----"
    }

    private fun rsaFixture(): RsaFixture {
        val keyPair =
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val privateKey = keyPair.private as RSAPrivateCrtKey
        val publicBlob =
            ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.writeSshString("ssh-rsa".encodeToByteArray())
                    output.writeMpint(privateKey.publicExponent)
                    output.writeMpint(privateKey.modulus)
                }
                bytes.toByteArray()
            }
        val privateBlock =
            ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.writeInt(0x1234_5678)
                    output.writeInt(0x1234_5678)
                    output.writeSshString("ssh-rsa".encodeToByteArray())
                    output.writeMpint(privateKey.modulus)
                    output.writeMpint(privateKey.publicExponent)
                    output.writeMpint(privateKey.privateExponent)
                    output.writeMpint(privateKey.crtCoefficient)
                    output.writeMpint(privateKey.primeP)
                    output.writeMpint(privateKey.primeQ)
                    output.writeSshString("rsa@test".encodeToByteArray())
                    var padding = 1
                    do {
                        output.writeByte(padding++)
                    } while (bytes.size() % 8 != 0)
                }
                bytes.toByteArray()
            }
        val encoded =
            ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.write("openssh-key-v1\u0000".encodeToByteArray())
                    output.writeSshString("none".encodeToByteArray())
                    output.writeSshString("none".encodeToByteArray())
                    output.writeSshString(byteArrayOf())
                    output.writeInt(1)
                    output.writeSshString(publicBlob)
                    output.writeSshString(privateBlock)
                }
                bytes.toByteArray()
            }
        val pem =
            "-----BEGIN OPENSSH PRIVATE KEY-----\n" +
                Base64.getMimeEncoder(70, "\n".encodeToByteArray()).encodeToString(encoded) +
                "\n-----END OPENSSH PRIVATE KEY-----"
        val publicKey = "ssh-rsa ${Base64.getEncoder().encodeToString(publicBlob)} rsa@test"
        return RsaFixture(pem, publicKey, keyPair)
    }

    private fun DataOutputStream.writeSshString(value: ByteArray) {
        writeInt(value.size)
        write(value)
    }

    private fun DataOutputStream.writeMpint(value: BigInteger) = writeSshString(value.toByteArray())

    private data class RsaFixture(
        val privateKey: String,
        val publicKey: String,
        val keyPair: java.security.KeyPair,
    )

    companion object {
        private val TEST_PRIVATE_KEY =
            """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
            QyNTUxOQAAACDFWrVoDowh5mZBmpXaupzu8anhOZUnvLU4cuFdgQuXNQAAAJAz/VUjM/1V
            IwAAAAtzc2gtZWQyNTUxOQAAACDFWrVoDowh5mZBmpXaupzu8anhOZUnvLU4cuFdgQuXNQ
            AAAED/jbzSJM+mIIGmb5NRwmCMOha2lr76MMRszR6laOg1x8VatWgOjCHmZkGaldq6nO7x
            qeE5lSe8tThy4V2BC5c1AAAADHRlc3RAZXhhbXBsZQE=
            -----END OPENSSH PRIVATE KEY-----
            """
                .trimIndent()
        private const val TEST_PUBLIC_KEY =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIMVatWgOjCHmZkGaldq6nO7xqeE5lSe8tThy4V2BC5c1 test@example"
    }
}
