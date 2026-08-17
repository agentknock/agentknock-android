package dev.agentknock.protocol

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.hpke.HPKE
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingProtocolTest {
    private val json = Json
    private val protocol = PairingProtocol()
    private val devicePrivateKey = ByteArray(32) { 0x42 }
    private val devicePublicKey = X25519PrivateKeyParameters(devicePrivateKey, 0)
        .generatePublicKey().encoded

    @Test
    fun `validates the initial pairing request envelope`() {
        val request = json.parseToJsonElement(
            """{"version":"agentknock-v1","commitment":"${BASE64.encodeToString(ByteArray(32))}"}""",
        )
        val wrongVersion = json.parseToJsonElement(
            """{"version":"agentknock-v2","commitment":"${BASE64.encodeToString(ByteArray(32))}"}""",
        )
        val wrongLength = json.parseToJsonElement(
            """{"version":"agentknock-v1","commitment":"${BASE64.encodeToString(ByteArray(12))}"}""",
        )
        val malformed = json.parseToJsonElement(
            """{"version":"agentknock-v1","commitment":"not base64"}""",
        )

        assertTrue(protocol.isInitialRequest(request))
        assertTrue(protocol.validateInitialRequest(request))
        assertFalse(protocol.validateInitialRequest(wrongVersion))
        assertFalse(protocol.validateInitialRequest(wrongLength))
        assertFalse(protocol.validateInitialRequest(malformed))
    }

    @Test
    fun `opens the initial hpke exchange and derives the cli sas`() {
        val sender = baseHpke.setupBaseS(
            baseHpke.deserializePublicKey(devicePublicKey),
            baseProtocolInfo(),
        )
        val clientRandom = ByteArray(32) { it.toByte() }
        val initialRequest = pairingRequest(clientRandom)
        val completion = initialCompletion(sender, clientRandom)

        val established = protocol.establish(
            deviceId = DEVICE_ID,
            clientId = CLIENT_ID,
            devicePrivateKey = devicePrivateKey,
            devicePublicKey = devicePublicKey,
            deviceRandom = DEVICE_RANDOM,
            initialRequest = initialRequest,
            completion = completion,
        )

        assertArrayEquals(sender.export("agentknock-v1 psk".encodeToByteArray(), 32), established.clientPsk)
        assertEquals(802_590_831_137L, established.sas)
        assertEquals("8025 9083 1137", protocol.formatSas(established.sas))
        assertEquals("survo", established.clientMetadata.hostname)
        assertEquals("linux", established.clientMetadata.platform)
        assertEquals("x86_64", established.clientMetadata.architecture)
    }

    @Test
    fun `rejects a completion whose client random does not match its commitment`() {
        val sender = baseHpke.setupBaseS(
            baseHpke.deserializePublicKey(devicePublicKey),
            baseProtocolInfo(),
        )
        val committedRandom = ByteArray(32) { it.toByte() }
        val revealedRandom = ByteArray(32) { (it + 1).toByte() }

        val failure = runCatching {
            protocol.establish(
                deviceId = DEVICE_ID,
                clientId = CLIENT_ID,
                devicePrivateKey = devicePrivateKey,
                devicePublicKey = devicePublicKey,
                deviceRandom = DEVICE_RANDOM,
                initialRequest = pairingRequest(committedRandom),
                completion = initialCompletion(sender, revealedRandom),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals("Pairing commitment mismatch", failure?.message)
    }

    @Test
    fun `answers and verifies the finish pairing exchange`() {
        val clientPsk = ByteArray(32) { (it + 1).toByte() }
        val sender = pskHpke.SetupPSKS(
            pskHpke.deserializePublicKey(devicePublicKey),
            pairedProtocolInfo(FINISH_REQUEST_ID),
            clientPsk,
            CLIENT_ID.ulidBytes(),
        )
        val requestPlaintext =
            """{"cli_version":"0.1.0","method":"PairingFinish"}""".encodeToByteArray()
        val request = json.parseToJsonElement(
            """{"version":"agentknock-v1","key":"${BASE64.encodeToString(sender.encapsulation)}","ciphertext":"${BASE64.encodeToString(sender.seal(EMPTY, requestPlaintext))}"}""",
        )

        val prepared = protocol.prepareFinishResponse(
            deviceId = DEVICE_ID,
            requestId = FINISH_REQUEST_ID,
            clientId = CLIENT_ID,
            clientPsk = clientPsk,
            devicePrivateKey = devicePrivateKey,
            devicePublicKey = devicePublicKey,
            request = request,
            accepted = true,
        )

        assertEquals("0.1.0", prepared.cliVersion)
        assertEquals(
            "{\"result\":\"ACCEPTED\"}",
            openResponse(sender, prepared.response).decodeToString(),
        )

        val completionPlaintext =
            """{"cli_version":"0.1.0","result":"ACCEPTED"}""".encodeToByteArray()
        val completion = json.parseToJsonElement(
            """{"ciphertext":"${BASE64.encodeToString(sender.seal(EMPTY, completionPlaintext))}"}""",
        )

        protocol.verifyFinishCompletion(
            deviceId = DEVICE_ID,
            requestId = FINISH_REQUEST_ID,
            clientId = CLIENT_ID,
            clientPsk = clientPsk,
            devicePrivateKey = devicePrivateKey,
            devicePublicKey = devicePublicKey,
            request = request,
            completion = completion,
        )
    }

    @Test
    fun `opens a rotated credential request and its response and completion`() {
        val oldClientPsk = ByteArray(32) { (it + 1).toByte() }
        val rotationSender = pskHpke.SetupPSKS(
            pskHpke.deserializePublicKey(devicePublicKey),
            pairedProtocolInfo(ByteArray(16)),
            oldClientPsk,
            CLIENT_ID.ulidBytes(),
        )
        val rotatedClientPsk = rotationSender.export(
            "agentknock-v1 psk".encodeToByteArray(),
            32,
        )
        val requestSender = pskHpke.SetupPSKS(
            pskHpke.deserializePublicKey(devicePublicKey),
            pairedProtocolInfo(CREDENTIAL_REQUEST_ID),
            rotatedClientPsk,
            CLIENT_ID.ulidBytes(),
        )
        val requestPlaintext =
            """{"cli_version":"0.1.0","method":"CredentialRequest","profiles":["test"],"operation":{"type":"exec","command":"env","arguments":[],"working_directory":"/tmp","stdin":"NULL_DEVICE","stdout":"TERMINAL","stderr":"TERMINAL"},"launcher_chain":[]}"""
                .encodeToByteArray()
        val request = json.parseToJsonElement(
            """{"version":"agentknock-v1","key":"${BASE64.encodeToString(requestSender.encapsulation)}","ciphertext":"${BASE64.encodeToString(requestSender.seal(EMPTY, requestPlaintext))}","rotation_key":"${BASE64.encodeToString(rotationSender.encapsulation)}"}""",
        )

        val opened = protocol.openPairedRequest(
            deviceId = DEVICE_ID,
            requestId = CREDENTIAL_REQUEST_ID,
            clientId = CLIENT_ID,
            clientPsk = oldClientPsk,
            devicePrivateKey = devicePrivateKey,
            devicePublicKey = devicePublicKey,
            request = request,
        )

        assertArrayEquals(requestPlaintext, opened.plaintext)
        assertArrayEquals(rotatedClientPsk, opened.clientPsk)

        val responsePlaintext =
            """{"result":"APPROVED","environment":{"TOKEN":"value"}}""".encodeToByteArray()
        val response = protocol.sealPairedResponse(
            deviceId = DEVICE_ID,
            requestId = CREDENTIAL_REQUEST_ID,
            clientId = CLIENT_ID,
            clientPsk = opened.clientPsk,
            devicePrivateKey = devicePrivateKey,
            devicePublicKey = devicePublicKey,
            request = request,
            plaintext = responsePlaintext,
        )
        assertArrayEquals(responsePlaintext, openResponse(requestSender, response))

        val completionPlaintext =
            """{"cli_version":"0.1.0","result":"APPROVED"}""".encodeToByteArray()
        val completion = json.parseToJsonElement(
            """{"ciphertext":"${BASE64.encodeToString(requestSender.seal(EMPTY, completionPlaintext))}"}""",
        )
        assertArrayEquals(
            completionPlaintext,
            protocol.openPairedCompletion(
                deviceId = DEVICE_ID,
                requestId = CREDENTIAL_REQUEST_ID,
                clientId = CLIENT_ID,
                clientPsk = opened.clientPsk,
                devicePrivateKey = devicePrivateKey,
                devicePublicKey = devicePublicKey,
                request = request,
                completion = completion,
            ),
        )
    }

    @Test
    fun `offers one real and two distinct decoy sas values`() {
        val choices = protocol.sasChoices(123_456_789_012L)

        assertEquals(3, choices.values.size)
        assertEquals(3, choices.values.distinct().size)
        assertEquals(123_456_789_012L, choices.values[choices.correctIndex])
    }

    private fun pairingRequest(clientRandom: ByteArray): JsonElement {
        val commitment = derive(
            input = clientRandom,
            salt = "agentknock-v1".encodeToByteArray(),
            info = "agentknock-v1 commitment".encodeToByteArray(),
            length = 32,
        )
        return json.parseToJsonElement(
            """{"version":"agentknock-v1","commitment":"${BASE64.encodeToString(commitment)}"}""",
        )
    }

    private fun initialCompletion(
        sender: org.bouncycastle.crypto.hpke.HPKEContext,
        clientRandom: ByteArray,
    ): JsonElement {
        val plaintext = json.parseToJsonElement(
            """{"cli_version":"0.1.0","client_random":"${BASE64.encodeToString(clientRandom)}","platform":"linux","architecture":"x86_64","hostname":"survo","machine_id":"machine","os_version":"NixOS"}""",
        ).toString().encodeToByteArray()
        return json.parseToJsonElement(
            """{"key":"${BASE64.encodeToString(senderEncapsulation(sender))}","ciphertext":"${BASE64.encodeToString(sender.seal(EMPTY, plaintext))}"}""",
        )
    }

    private fun baseProtocolInfo(): ByteArray =
        protocolVersionInfo() + DEVICE_ID.ulidBytes() + CLIENT_ID.ulidBytes()

    private fun pairedProtocolInfo(requestId: String): ByteArray =
        pairedProtocolInfo(requestId.ulidBytes())

    private fun pairedProtocolInfo(requestId: ByteArray): ByteArray =
        protocolVersionInfo() + DEVICE_ID.ulidBytes() + requestId

    private fun protocolVersionInfo(): ByteArray =
        "agentknock-v1".encodeToByteArray() + ByteArray(3)

    private fun openResponse(
        sender: org.bouncycastle.crypto.hpke.HPKEContext,
        response: kotlinx.serialization.json.JsonElement,
    ): ByteArray {
        val encoded = response.jsonObject
        val responseRandom = BASE64_DECODER.decode(
            encoded.getValue("nonce").jsonPrimitive.content,
        )
        assertEquals(32, responseRandom.size)
        val ciphertext = BASE64_DECODER.decode(
            encoded.getValue("ciphertext").jsonPrimitive.content,
        )
        val salt = senderEncapsulation(sender) + responseRandom
        val exported = sender.export("agentknock-v1 response".encodeToByteArray(), 32)
        val key = derive(exported, salt, "key".encodeToByteArray(), 32)
        val nonce = derive(exported, salt, "nonce".encodeToByteArray(), 12)
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), 128, nonce))
        val output = ByteArray(cipher.getOutputSize(ciphertext.size))
        var length = cipher.processBytes(ciphertext, 0, ciphertext.size, output, 0)
        length += cipher.doFinal(output, length)
        return output.copyOf(length)
    }

    private fun senderEncapsulation(sender: org.bouncycastle.crypto.hpke.HPKEContext): ByteArray {
        val withEncapsulation = sender as org.bouncycastle.crypto.hpke.HPKEContextWithEncapsulation
        return withEncapsulation.encapsulation
    }

    private fun derive(input: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val output = ByteArray(length)
        HKDFBytesGenerator(SHA256Digest()).run {
            init(HKDFParameters(input, salt, info))
            generateBytes(output, 0, output.size)
        }
        return output
    }

    private fun String.ulidBytes(): ByteArray {
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        var value = java.math.BigInteger.ZERO
        for (character in this) {
            value = value.shiftLeft(5).or(
                java.math.BigInteger.valueOf(alphabet.indexOf(character).toLong()),
            )
        }
        val encoded = value.toByteArray()
        return ByteArray(16).also { output ->
            encoded.copyInto(output, 16 - encoded.size, 0, encoded.size)
        }
    }

    private companion object {
        const val DEVICE_ID = "01K2ENXDTW1P3XAR4J7V7C9D0H"
        const val CLIENT_ID = "01K2EP16NWNAGJYF8J1Q2V6P3X"
        const val FINISH_REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAW"
        const val CREDENTIAL_REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAX"
        val DEVICE_RANDOM = ByteArray(32) { (0xa0 + it).toByte() }
        val EMPTY = ByteArray(0)
        val BASE64: Base64.Encoder = Base64.getEncoder()
        val BASE64_DECODER: Base64.Decoder = Base64.getDecoder()
        val baseHpke = HPKE(
            HPKE.mode_base,
            HPKE.kem_X25519_SHA256,
            HPKE.kdf_HKDF_SHA256,
            HPKE.aead_CHACHA20_POLY1305,
        )
        val pskHpke = HPKE(
            HPKE.mode_psk,
            HPKE.kem_X25519_SHA256,
            HPKE.kdf_HKDF_SHA256,
            HPKE.aead_CHACHA20_POLY1305,
        )
    }
}
