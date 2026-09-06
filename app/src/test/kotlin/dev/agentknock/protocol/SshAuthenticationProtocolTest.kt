package dev.agentknock.protocol

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SshAuthenticationProtocolTest {
    private val protocol = SshAuthenticationProtocol()
    private val publicKeyBlob = sshStrings(
        "ssh-ed25519".encodeToByteArray(),
        ByteArray(32) { 7 },
    )

    @Test
    fun `decodes and validates ordinary Ed25519 authentication`() {
        val message = authenticationMessage("publickey")
        val request = protocol.decodeRequest(request(message))

        assertEquals("01ARZ3NDEKTSV4RRFFQ69G5FAV", request.invocationId)
        assertArrayEquals(ByteArray(32), request.invocationToken)
        assertEquals("production-ssh", request.secret)
        assertArrayEquals(message, request.message)
        assertEquals(
            SshAuthenticationMessageDetails(
                username = "deploy",
                method = SshAuthenticationMethod.PUBLIC_KEY,
                algorithm = SshSignatureAlgorithm.ED25519,
            ),
            protocol.validateMessage(message, publicKeyBlob, "ssh-ed25519"),
        )
    }

    @Test
    fun `validates host-bound authentication and reports the host key`() {
        val hostKey = sshStrings("ssh-ed25519".encodeToByteArray(), ByteArray(32) { 9 })
        val details = protocol.validateMessage(
            authenticationMessage("publickey-hostbound-v00@openssh.com", hostKey),
            publicKeyBlob,
            "ssh-ed25519",
        )

        assertEquals(SshAuthenticationMethod.HOST_BOUND, details.method)
        assertEquals("ssh-ed25519", details.hostKeyAlgorithm)
        requireNotNull(details.hostKeyFingerprint)
        assert(details.hostKeyFingerprint.startsWith("SHA256:"))
    }

    @Test
    fun `accepts modern RSA authentication algorithms and rejects mismatches`() {
        val rsaPublicKey = sshStrings(
            "ssh-rsa".encodeToByteArray(),
            byteArrayOf(1, 0, 1),
            byteArrayOf(0, 1, 2, 3, 4),
        )
        for (algorithm in listOf("rsa-sha2-256", "rsa-sha2-512")) {
            val details = protocol.validateMessage(
                authenticationMessage(
                    method = "publickey",
                    algorithm = algorithm,
                    publicKey = rsaPublicKey,
                ),
                rsaPublicKey,
                "ssh-rsa",
            )
            assertEquals(algorithm, details.algorithm.wireName)
        }
        assertThrows(IllegalArgumentException::class.java) {
            protocol.validateMessage(
                authenticationMessage(
                    method = "publickey",
                    algorithm = "ssh-ed25519",
                    publicKey = rsaPublicKey,
                ),
                rsaPublicKey,
                "ssh-rsa",
            )
        }
    }

    @Test
    fun `rejects a different key and malformed authentication`() {
        assertThrows(IllegalArgumentException::class.java) {
            protocol.validateMessage(
                authenticationMessage("publickey"),
                publicKeyBlob.copyOf().also { it[it.lastIndex]++ },
                "ssh-ed25519",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            protocol.validateMessage("arbitrary".encodeToByteArray(), publicKeyBlob, "ssh-ed25519")
        }
    }

    @Test
    fun `encodes signature response and completion variants`() {
        val signature = sshSignatureBlob(SshSignatureAlgorithm.ED25519, ByteArray(64) { 5 })
        val response = Json.parseToJsonElement(
            protocol.approvedResponse(signature).decodeToString(),
        ).let { it as kotlinx.serialization.json.JsonObject }
        assertEquals("APPROVED", response.getValue("result").toString().trim('"'))
        assertArrayEquals(
            signature,
            Base64.getDecoder().decode(response.getValue("signature").toString().trim('"')),
        )
        val approved = protocol.decodeCompletion(
            """{${testClientSoftwareFields("0.3.0", "0.1.0")},"result":"APPROVED"}"""
                .encodeToByteArray(),
        )
        assert(approved is ApprovalCompletion.Approved)
        val denied = protocol.decodeCompletion(
            """{${testClientSoftwareFields("0.3.0", "0.1.0")},"result":"DENIED","reason":"USER_DENIED","message":"No"}"""
                .encodeToByteArray(),
        )
        require(denied is ApprovalCompletion.Denied)
        assertEquals("USER_DENIED", denied.reason)
        assertNull(approved.reason)
    }

    private fun request(message: ByteArray): ByteArray =
        """{${testClientSoftwareFields("0.3.0", "0.1.0")},"method":"SshAuthenticate","invocation_id":"01ARZ3NDEKTSV4RRFFQ69G5FAV","invocation_token":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","secret":"production-ssh","message":"${Base64.getEncoder().encodeToString(message)}"}"""
            .encodeToByteArray()

    private fun authenticationMessage(
        method: String,
        hostKey: ByteArray? = null,
        algorithm: String = "ssh-ed25519",
        publicKey: ByteArray = publicKeyBlob,
    ): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeSshString("session identifier".encodeToByteArray())
                output.writeByte(50)
                output.writeSshString("deploy".encodeToByteArray())
                output.writeSshString("ssh-connection".encodeToByteArray())
                output.writeSshString(method.encodeToByteArray())
                output.writeByte(1)
                output.writeSshString(algorithm.encodeToByteArray())
                output.writeSshString(publicKey)
                hostKey?.let { output.writeSshString(it) }
            }
            bytes.toByteArray()
        }

    private fun sshStrings(vararg values: ByteArray): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                values.forEach { output.writeSshString(it) }
            }
            bytes.toByteArray()
        }

    private fun DataOutputStream.writeSshString(value: ByteArray) {
        writeInt(value.size)
        write(value)
    }
}
