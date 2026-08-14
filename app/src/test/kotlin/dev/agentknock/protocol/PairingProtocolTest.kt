package dev.agentknock.protocol

import java.util.Base64
import kotlinx.serialization.json.Json
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
    private val routePrivateKey = ByteArray(32) { 0x42 }
    private val routePublicKey = X25519PrivateKeyParameters(routePrivateKey, 0)
        .generatePublicKey().encoded

    @Test
    fun `validates the cli pairing commitment vector`() {
        val request = json.parseToJsonElement(
            """{"version":"agentknock-v1","commitment":"TSZ1lTkmAPehOPZpnWV5+O6AncZFD5TMKVfG30j6QVY="}""",
        )

        assertTrue(protocol.isInitialRequest(request))
        assertTrue(protocol.validateInitialRequest(request, "yup-its-free"))
        assertFalse(protocol.validateInitialRequest(request, "not-the-address"))
    }

    @Test
    fun `opens the initial hpke exchange and derives the cli sas`() {
        val sender = baseHpke.setupBaseS(
            baseHpke.deserializePublicKey(routePublicKey),
            protocolInfo(START_REQUEST_ID),
        )
        val clientRandom = ByteArray(32) { it.toByte() }
        val plaintext = json.parseToJsonElement(
            """{"cli_version":"0.1.0","client_random":"${BASE64.encodeToString(clientRandom)}","platform":"linux","architecture":"x86_64","hostname":"survo","machine_id":"machine","os_version":"NixOS"}""",
        ).toString().encodeToByteArray()
        val completion = json.parseToJsonElement(
            """{"key":"${BASE64.encodeToString(sender.encapsulation)}","ciphertext":"${BASE64.encodeToString(sender.seal(EMPTY, plaintext))}"}""",
        )

        val established = protocol.establish(
            routeId = ROUTE_ID,
            requestId = START_REQUEST_ID,
            pairingId = PAIRING_ID,
            routePrivateKey = routePrivateKey,
            routePublicKey = routePublicKey,
            completion = completion,
        )

        assertArrayEquals(sender.export("agentknock-v1 psk".encodeToByteArray(), 32), established.pairingPsk)
        assertEquals(287_708_420_069L, established.sas)
        assertEquals("2877 0842 0069", protocol.formatSas(established.sas))
        assertEquals("survo", established.client.hostname)
        assertEquals("linux", established.client.platform)
        assertEquals("x86_64", established.client.architecture)
    }

    @Test
    fun `answers and verifies the finish pairing exchange`() {
        val pairingPsk = ByteArray(32) { (it + 1).toByte() }
        val sender = pskHpke.SetupPSKS(
            pskHpke.deserializePublicKey(routePublicKey),
            protocolInfo(FINISH_REQUEST_ID),
            pairingPsk,
            PAIRING_ID.hexBytes(),
        )
        val requestPlaintext =
            """{"cli_version":"0.1.0","method":"FinishPairing"}""".encodeToByteArray()
        val request = json.parseToJsonElement(
            """{"version":"agentknock-v1","pairing_id":"$PAIRING_ID","key":"${BASE64.encodeToString(sender.encapsulation)}","ciphertext":"${BASE64.encodeToString(sender.seal(EMPTY, requestPlaintext))}"}""",
        )

        val prepared = protocol.prepareFinishResponse(
            routeId = ROUTE_ID,
            requestId = FINISH_REQUEST_ID,
            pairingId = PAIRING_ID,
            pairingPsk = pairingPsk,
            routePrivateKey = routePrivateKey,
            routePublicKey = routePublicKey,
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
            routeId = ROUTE_ID,
            requestId = FINISH_REQUEST_ID,
            pairingId = PAIRING_ID,
            pairingPsk = pairingPsk,
            routePrivateKey = routePrivateKey,
            routePublicKey = routePublicKey,
            request = request,
            completion = completion,
        )
    }

    @Test
    fun `opens a rotated credential request and its response and completion`() {
        val oldPairingPsk = ByteArray(32) { (it + 1).toByte() }
        val rotationSender = pskHpke.SetupPSKS(
            pskHpke.deserializePublicKey(routePublicKey),
            protocolInfo(ByteArray(16)),
            oldPairingPsk,
            PAIRING_ID.hexBytes(),
        )
        val rotatedPairingPsk = rotationSender.export(
            "agentknock-v1 psk".encodeToByteArray(),
            32,
        )
        val requestSender = pskHpke.SetupPSKS(
            pskHpke.deserializePublicKey(routePublicKey),
            protocolInfo(CREDENTIAL_REQUEST_ID),
            rotatedPairingPsk,
            PAIRING_ID.hexBytes(),
        )
        val requestPlaintext =
            """{"cli_version":"0.1.0","method":"CredentialRequest","profiles":["test"],"operation":{"type":"exec","command":"env","arguments":[],"working_directory":"/tmp","stdin":"NULL_DEVICE","stdout":"TERMINAL","stderr":"TERMINAL"},"launcher_chain":[]}"""
                .encodeToByteArray()
        val request = json.parseToJsonElement(
            """{"version":"agentknock-v1","pairing_id":"$PAIRING_ID","key":"${BASE64.encodeToString(requestSender.encapsulation)}","ciphertext":"${BASE64.encodeToString(requestSender.seal(EMPTY, requestPlaintext))}","rotation_key":"${BASE64.encodeToString(rotationSender.encapsulation)}"}""",
        )

        val opened = protocol.openPairedRequest(
            routeId = ROUTE_ID,
            requestId = CREDENTIAL_REQUEST_ID,
            pairingId = PAIRING_ID,
            pairingPsk = oldPairingPsk,
            routePrivateKey = routePrivateKey,
            routePublicKey = routePublicKey,
            request = request,
        )

        assertArrayEquals(requestPlaintext, opened.plaintext)
        assertArrayEquals(rotatedPairingPsk, opened.pairingPsk)

        val responsePlaintext =
            """{"result":"APPROVED","environment":{"TOKEN":"value"}}""".encodeToByteArray()
        val response = protocol.sealPairedResponse(
            routeId = ROUTE_ID,
            requestId = CREDENTIAL_REQUEST_ID,
            pairingId = PAIRING_ID,
            pairingPsk = opened.pairingPsk,
            routePrivateKey = routePrivateKey,
            routePublicKey = routePublicKey,
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
                routeId = ROUTE_ID,
                requestId = CREDENTIAL_REQUEST_ID,
                pairingId = PAIRING_ID,
                pairingPsk = opened.pairingPsk,
                routePrivateKey = routePrivateKey,
                routePublicKey = routePublicKey,
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

    private fun protocolInfo(requestId: String): ByteArray =
        protocolInfo(requestId.ulidBytes())

    private fun protocolInfo(requestId: ByteArray): ByteArray =
        "agentknock-v1".encodeToByteArray() + ByteArray(3) +
            ROUTE_ID.hexBytes() + PAIRING_ID.hexBytes() + requestId

    private fun openResponse(
        sender: org.bouncycastle.crypto.hpke.HPKEContext,
        response: kotlinx.serialization.json.JsonElement,
    ): ByteArray {
        val encoded = response.jsonObject
        val publicNonce = BASE64_DECODER.decode(encoded.getValue("nonce").jsonPrimitive.content)
        val ciphertext = BASE64_DECODER.decode(
            encoded.getValue("ciphertext").jsonPrimitive.content,
        )
        val salt = senderEncapsulation(sender) + publicNonce
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

    private fun String.hexBytes(): ByteArray = ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
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
        const val ROUTE_ID = "0b7d7963604cba911e9c03e727688b89"
        const val PAIRING_ID = "ffeeddccbbaa99887766554433221100"
        const val START_REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
        const val FINISH_REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAW"
        const val CREDENTIAL_REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAX"
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
