package dev.agentknock.protocol

import java.security.SecureRandom
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
    private val pairedProtocol = PairedRequestProtocol()
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

        assertTrue(protocol.validateInitialRequest(request))
        assertFalse(protocol.validateInitialRequest(wrongVersion))
        assertFalse(protocol.validateInitialRequest(wrongLength))
        assertFalse(protocol.validateInitialRequest(malformed))
    }

    @Test
    fun `derives the pairing commitment from the client secret vector`() {
        val request = pairingRequest(ByteArray(32) { (0x60 + it).toByte() })

        assertEquals(
            "jUVTSBEimLz6OdfXAA4qxemm4hHyzzc5yOj1ZdzHsq4=",
            request.jsonObject.getValue("commitment").jsonPrimitive.content,
        )
    }

    @Test
    fun `matches the pairing response vector`() {
        assertEquals(
            json.parseToJsonElement(
                """{"device_id":"01K2ENXDTW1P3XAR4J7V7C9D0H","device_key":"EyxEK+AQ+9V+cmAzKKp25x/MwVA6riGTJ9FNnJmT9HI=","device_random":"oKGio6SlpqeoqaqrrK2ur7CxsrO0tba3uLm6u7y9vr8="}""",
            ),
            protocol.initialResponse(DEVICE_ID, devicePublicKey, DEVICE_RANDOM),
        )
    }

    @Test
    fun `opens the initial hpke exchange and derives the cli sas`() {
        val sender = baseHpke.setupBaseS(
            baseHpke.deserializePublicKey(devicePublicKey),
            baseProtocolInfo(),
        )
        val clientSecret = ByteArray(32) { (0x60 + it).toByte() }
        val initialRequest = pairingRequest(clientSecret)
        val completion = initialCompletion(sender, clientSecret)

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
        assertEquals(1_543_953_892L, established.sas)
        assertEquals("0015 4395 3892", protocol.formatSas(established.sas))
        val metadata = protocol.decodeClientMetadata(established.applicationPlaintext)
        assertEquals(testClientSoftware(), metadata.clientSoftware)
        assertEquals("survo", metadata.hostname)
        assertEquals("linux", metadata.platform)
        assertEquals("x86_64", metadata.architecture)
    }

    @Test
    fun `rejects a completion whose client secret does not match its commitment`() {
        val sender = baseHpke.setupBaseS(
            baseHpke.deserializePublicKey(devicePublicKey),
            baseProtocolInfo(),
        )
        val committedSecret = ByteArray(32) { it.toByte() }
        val revealedSecret = ByteArray(32) { (it + 1).toByte() }

        val failure = runCatching {
            protocol.establish(
                deviceId = DEVICE_ID,
                clientId = CLIENT_ID,
                devicePrivateKey = devicePrivateKey,
                devicePublicKey = devicePublicKey,
                deviceRandom = DEVICE_RANDOM,
                initialRequest = pairingRequest(committedSecret),
                completion = initialCompletion(
                    sender = sender,
                    clientSecret = revealedSecret,
                    applicationPlaintext = "not valid metadata".encodeToByteArray(),
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals("Pairing commitment mismatch", failure?.message)
    }

    @Test
    fun `rejects an initial completion with a wrongly sized encapsulated key`() {
        val completion = json.parseToJsonElement(
            """{"key":"${BASE64.encodeToString(ByteArray(31))}","secret":"","ciphertext":""}""",
        )

        val failure = runCatching {
            protocol.establish(
                deviceId = DEVICE_ID,
                clientId = CLIENT_ID,
                devicePrivateKey = devicePrivateKey,
                devicePublicKey = devicePublicKey,
                deviceRandom = DEVICE_RANDOM,
                initialRequest = pairingRequest(ByteArray(32)),
                completion = completion,
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals("Invalid pairing key", failure?.message)
    }

    @Test
    fun `prepares the finish pairing response plaintext`() {
        val requestPlaintext =
            """{${testClientSoftwareFields()},"method":"PairingFinish"}"""
                .encodeToByteArray()

        val prepared = protocol.prepareFinishResponse(
            request = requestPlaintext,
        )

        assertEquals(
            "{\"result\":\"ACCEPTED\"}",
            prepared.decodeToString(),
        )
    }

    @Test
    fun `opens a rotated invocation request and its response and completion`() {
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
            pairedProtocolInfo(SECRET_USE_REQUEST_ID),
            rotatedClientPsk,
            CLIENT_ID.ulidBytes(),
        )
        val requestPlaintext =
            """{${testClientSoftwareFields()},"method":"Invocation","secrets":["test"],"operation":{"type":"exec","command":"env","arguments":[],"working_directory":"/tmp","stdin":"NULL_DEVICE","stdout":"TERMINAL","stderr":"TERMINAL"},"launcher_chain":[]}"""
                .encodeToByteArray()
        val request = json.parseToJsonElement(
            """{"version":"agentknock-v1","key":"${BASE64.encodeToString(requestSender.encapsulation)}","ciphertext":"${BASE64.encodeToString(requestSender.seal(EMPTY, requestPlaintext))}","rotation_key":"${BASE64.encodeToString(rotationSender.encapsulation)}"}""",
        )

        val opened = pairedProtocol.openPairedRequest(
            deviceId = DEVICE_ID,
            requestId = SECRET_USE_REQUEST_ID,
            clientId = CLIENT_ID,
            clientPsk = oldClientPsk,
            devicePrivateKey = devicePrivateKey,
            devicePublicKey = devicePublicKey,
            request = request,
        )

        assertArrayEquals(requestPlaintext, opened.plaintext)
        assertArrayEquals(rotatedClientPsk, opened.clientPsk)
        assertEquals(PairedRequestKeySource.ROTATED, opened.keySource)

        val responsePlaintext =
            """{"result":"APPROVED","environment":{"TOKEN":"value"}}""".encodeToByteArray()
        val response = pairedProtocol.sealPairedResponse(opened, responsePlaintext)
        assertArrayEquals(responsePlaintext, openResponse(requestSender, response))

        val completionPlaintext =
            """{${testClientSoftwareFields()},"result":"APPROVED"}"""
                .encodeToByteArray()
        val completion = json.parseToJsonElement(
            """{"ciphertext":"${BASE64.encodeToString(requestSender.seal(EMPTY, completionPlaintext))}"}""",
        )
        assertArrayEquals(
            completionPlaintext,
            pairedProtocol.openPairedCompletion(
                deviceId = DEVICE_ID,
                requestId = SECRET_USE_REQUEST_ID,
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
    fun `matches the complete cryptosystem pairing and exchange vectors`() {
        val responseRandom = hex(
            "c0c1c2c3c4c5c6c7c8c9cacbcccdcecf" +
                "d0d1d2d3d4d5d6d7d8d9dadbdcdddedf",
        )
        val vectorRandom = FixedSecureRandom(responseRandom)
        val vectorProtocol = PairingProtocol(random = vectorRandom)
        val vectorPairedProtocol = PairedRequestProtocol(random = vectorRandom)
        val initialRequest = json.parseToJsonElement(
            """{"version":"agentknock-v1","commitment":"jUVTSBEimLz6OdfXAA4qxemm4hHyzzc5yOj1ZdzHsq4="}""",
        )
        val initialCompletion = json.parseToJsonElement(
            """{"key":"sfG4QN56MkGwJ0jPmwW3TcjF6EUSmHOIF712qo6+jCs=","secret":"H8SZgAmy8nTjengFzrxzaK+MwdN/OghFgiQHHuuW2oB6l/HJpnpLGK2STwGC0YK3","ciphertext":"DAyKFf1uyVo5ACh4lY5AnOWpx2Xx68d0KQ1F"}""",
        )
        val established = vectorProtocol.establish(
            deviceId = DEVICE_ID,
            clientId = CLIENT_ID,
            devicePrivateKey = devicePrivateKey,
            devicePublicKey = devicePublicKey,
            deviceRandom = DEVICE_RANDOM,
            initialRequest = initialRequest,
            completion = initialCompletion,
        )
        assertArrayEquals(
            hex("b208e67b262c76f1bffb13acdabf34674a1e41deb1bb4fff9dbdb8a31e218bd8"),
            established.clientPsk,
        )
        assertEquals(1_543_953_892L, established.sas)
        assertEquals("application", established.applicationPlaintext.decodeToString())

        val request = json.parseToJsonElement(
            """{"version":"agentknock-v1","key":"aTZYJUYw9zrY2nj7Mxv5ds1C+Q4OnJ6D9AxRBypvdBc=","ciphertext":"LqawCio2joj6TnyKmBKHHXYuHKeWkOc="}""",
        )
        val opened = vectorPairedProtocol.openPairedRequest(
            deviceId = DEVICE_ID,
            requestId = SECRET_USE_REQUEST_ID,
            clientId = CLIENT_ID,
            clientPsk = established.clientPsk,
            devicePrivateKey = devicePrivateKey,
            devicePublicKey = devicePublicKey,
            request = request,
        )
        assertEquals("request", opened.plaintext.decodeToString())
        assertEquals(PairedRequestKeySource.CURRENT, opened.keySource)
        assertEquals(
            json.parseToJsonElement(
                """{"nonce":"wMHCw8TFxsfIycrLzM3Oz9DR0tPU1dbX2Nna29zd3t8=","ciphertext":"46147qHk5pdBCJwOz/qIuKggVHrkayAp"}""",
            ),
            vectorPairedProtocol.sealPairedResponse(
                deviceId = DEVICE_ID,
                requestId = SECRET_USE_REQUEST_ID,
                clientId = CLIENT_ID,
                clientPsk = established.clientPsk,
                devicePrivateKey = devicePrivateKey,
                devicePublicKey = devicePublicKey,
                request = request,
                plaintext = "response".encodeToByteArray(),
            ),
        )
        val completion = json.parseToJsonElement(
            """{"ciphertext":"0EtKHMu2uTMPjGoaOS3bS79LnZL/rg3BsIo="}""",
        )
        assertEquals(
            "completion",
            vectorPairedProtocol.openPairedCompletion(
                deviceId = DEVICE_ID,
                requestId = SECRET_USE_REQUEST_ID,
                clientId = CLIENT_ID,
                clientPsk = established.clientPsk,
                devicePrivateKey = devicePrivateKey,
                devicePublicKey = devicePublicKey,
                request = request,
                completion = completion,
            ).decodeToString(),
        )
    }

    @Test
    fun `matches the cryptosystem rotation vector`() {
        val oldClientPsk = hex(
            "b208e67b262c76f1bffb13acdabf34674a1e41deb1bb4fff9dbdb8a31e218bd8",
        )
        val newClientPsk = hex(
            "b0ca03f5aea63fe0810516db0bd966c1e5ae73744d3bbfdb3a9139571b1ddb9b",
        )
        val sender = pskHpke.SetupPSKS(
            pskHpke.deserializePublicKey(devicePublicKey),
            pairedProtocolInfo(SECRET_USE_REQUEST_ID),
            newClientPsk,
            CLIENT_ID.ulidBytes(),
        )
        val request = json.parseToJsonElement(
            """{"version":"agentknock-v1","key":"${BASE64.encodeToString(sender.encapsulation)}","ciphertext":"${BASE64.encodeToString(sender.seal(EMPTY, "request".encodeToByteArray()))}","rotation_key":"sln27pLcugERhQsTs/bczIJ3JvmwgjWrYpIraz8/Khk="}""",
        )
        val opened = pairedProtocol.openPairedRequest(
            deviceId = DEVICE_ID,
            requestId = SECRET_USE_REQUEST_ID,
            clientId = CLIENT_ID,
            clientPsk = oldClientPsk,
            devicePrivateKey = devicePrivateKey,
            devicePublicKey = devicePublicKey,
            request = request,
        )
        assertArrayEquals(newClientPsk, opened.clientPsk)
        assertEquals(PairedRequestKeySource.ROTATED, opened.keySource)
    }

    @Test
    fun `pending binding does not accept rotation`() {
        val oldClientPsk = hex(
            "b208e67b262c76f1bffb13acdabf34674a1e41deb1bb4fff9dbdb8a31e218bd8",
        )
        val newClientPsk = hex(
            "b0ca03f5aea63fe0810516db0bd966c1e5ae73744d3bbfdb3a9139571b1ddb9b",
        )
        val sender = pskHpke.SetupPSKS(
            pskHpke.deserializePublicKey(devicePublicKey),
            pairedProtocolInfo(SECRET_USE_REQUEST_ID),
            newClientPsk,
            CLIENT_ID.ulidBytes(),
        )
        val request = json.parseToJsonElement(
            """{"version":"agentknock-v1","key":"${BASE64.encodeToString(sender.encapsulation)}","ciphertext":"${BASE64.encodeToString(sender.seal(EMPTY, "request".encodeToByteArray()))}","rotation_key":"sln27pLcugERhQsTs/bczIJ3JvmwgjWrYpIraz8/Khk="}""",
        )

        val result = runCatching {
            pairedProtocol.openPairedRequest(
                deviceId = DEVICE_ID,
                requestId = SECRET_USE_REQUEST_ID,
                clientId = CLIENT_ID,
                clientPsk = oldClientPsk,
                allowRotation = false,
                devicePrivateKey = devicePrivateKey,
                devicePublicKey = devicePublicKey,
                request = request,
            )
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun `offers one real and two distinct decoy sas values`() {
        val choices = protocol.sasChoices(123_456_789_012L)

        assertEquals(3, choices.values.size)
        assertEquals(3, choices.values.distinct().size)
        assertEquals(123_456_789_012L, choices.values[choices.correctIndex])
    }

    @Test
    fun `uses an eligible previous psk without considering rotation metadata`() {
        val currentClientPsk = ByteArray(32) { 0x11 }
        val previousClientPsk = ByteArray(32) { 0x22 }
        val sender = pskHpke.SetupPSKS(
            pskHpke.deserializePublicKey(devicePublicKey),
            pairedProtocolInfo(SECRET_USE_REQUEST_ID),
            previousClientPsk,
            CLIENT_ID.ulidBytes(),
        )
        val plaintext = "previous request".encodeToByteArray()
        val request = json.parseToJsonElement(
            """{"version":"agentknock-v1","key":"${BASE64.encodeToString(sender.encapsulation)}","ciphertext":"${BASE64.encodeToString(sender.seal(EMPTY, plaintext))}","rotation_key":{"not":"a string"}}""",
        )

        val opened = pairedProtocol.openPairedRequest(
            deviceId = DEVICE_ID,
            requestId = SECRET_USE_REQUEST_ID,
            clientId = CLIENT_ID,
            clientPsk = currentClientPsk,
            previousClientPsk = previousClientPsk,
            devicePrivateKey = devicePrivateKey,
            devicePublicKey = devicePublicKey,
            request = request,
        )

        assertArrayEquals(plaintext, opened.plaintext)
        assertEquals(PairedRequestKeySource.PREVIOUS, opened.keySource)
    }

    @Test
    fun `uses the current psk without considering rotation metadata`() {
        val currentClientPsk = ByteArray(32) { 0x11 }
        val sender = pskHpke.SetupPSKS(
            pskHpke.deserializePublicKey(devicePublicKey),
            pairedProtocolInfo(SECRET_USE_REQUEST_ID),
            currentClientPsk,
            CLIENT_ID.ulidBytes(),
        )
        val plaintext = "current request".encodeToByteArray()
        val request = json.parseToJsonElement(
            """{"version":"agentknock-v1","key":"${BASE64.encodeToString(sender.encapsulation)}","ciphertext":"${BASE64.encodeToString(sender.seal(EMPTY, plaintext))}","rotation_key":{"not":"a string"}}""",
        )

        val opened = pairedProtocol.openPairedRequest(
            deviceId = DEVICE_ID,
            requestId = SECRET_USE_REQUEST_ID,
            clientId = CLIENT_ID,
            clientPsk = currentClientPsk,
            devicePrivateKey = devicePrivateKey,
            devicePublicKey = devicePublicKey,
            request = request,
        )

        assertArrayEquals(plaintext, opened.plaintext)
        assertEquals(PairedRequestKeySource.CURRENT, opened.keySource)
    }

    @Test
    fun `validates rotation key type and length only when fallback is needed`() {
        val currentClientPsk = ByteArray(32) { 0x11 }
        val unrelatedClientPsk = ByteArray(32) { 0x22 }
        val sender = pskHpke.SetupPSKS(
            pskHpke.deserializePublicKey(devicePublicKey),
            pairedProtocolInfo(SECRET_USE_REQUEST_ID),
            unrelatedClientPsk,
            CLIENT_ID.ulidBytes(),
        )
        val ciphertext = BASE64.encodeToString(sender.seal(EMPTY, "request".encodeToByteArray()))
        val key = BASE64.encodeToString(sender.encapsulation)
        val wrongType = json.parseToJsonElement(
            """{"version":"agentknock-v1","key":"$key","ciphertext":"$ciphertext","rotation_key":{"not":"a string"}}""",
        )
        val wrongLength = json.parseToJsonElement(
            """{"version":"agentknock-v1","key":"$key","ciphertext":"$ciphertext","rotation_key":"${BASE64.encodeToString(ByteArray(31))}"}""",
        )

        for (request in listOf(wrongType, wrongLength)) {
            val failure = runCatching {
                pairedProtocol.openPairedRequest(
                    deviceId = DEVICE_ID,
                    requestId = SECRET_USE_REQUEST_ID,
                    clientId = CLIENT_ID,
                    clientPsk = currentClientPsk,
                    devicePrivateKey = devicePrivateKey,
                    devicePublicKey = devicePublicKey,
                    request = request,
                )
            }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertEquals("Invalid rotation key", failure?.message)
        }
    }

    @Test
    fun `rejects a paired request with a wrongly sized encapsulated key`() {
        val request = json.parseToJsonElement(
            """{"version":"agentknock-v1","key":"${BASE64.encodeToString(ByteArray(31))}","ciphertext":"${BASE64.encodeToString(ByteArray(16))}"}""",
        )

        val failure = runCatching {
            pairedProtocol.openPairedRequest(
                deviceId = DEVICE_ID,
                requestId = SECRET_USE_REQUEST_ID,
                clientId = CLIENT_ID,
                clientPsk = ByteArray(32),
                devicePrivateKey = devicePrivateKey,
                devicePublicKey = devicePublicKey,
                request = request,
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals("Invalid request key", failure?.message)
    }

    @Test
    fun `validates canonical fresh nonzero request ids`() {
        val bytes = DEVICE_ID.ulidBytes()
        var timestamp = 0L
        repeat(6) { timestamp = (timestamp shl 8) or (bytes[it].toLong() and 0xff) }

        assertTrue(isFreshRelayRequestId(DEVICE_ID, timestamp))
        assertFalse(isFreshRelayRequestId(DEVICE_ID.lowercase(), timestamp))
        assertFalse(isFreshRelayRequestId("00000000000000000000000000", timestamp))
        assertFalse(isFreshRelayRequestId(DEVICE_ID, timestamp + 24 * 60 * 60 * 1_000L + 1))
    }

    private fun pairingRequest(clientSecret: ByteArray): JsonElement {
        val commitment = derive(
            input = clientSecret,
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
        clientSecret: ByteArray,
        applicationPlaintext: ByteArray = pairingMetadata(),
    ): JsonElement {
        val secretCiphertext = sender.seal(EMPTY, clientSecret)
        val ciphertext = sender.seal(EMPTY, applicationPlaintext)
        return json.parseToJsonElement(
            """{"key":"${BASE64.encodeToString(senderEncapsulation(sender))}","secret":"${BASE64.encodeToString(secretCiphertext)}","ciphertext":"${BASE64.encodeToString(ciphertext)}"}""",
        )
    }

    private fun pairingMetadata(): ByteArray = json.parseToJsonElement(
        """{${testClientSoftwareFields()},"platform":"linux","architecture":"x86_64","hostname":"survo","machine_id":"machine","os_version":"NixOS"}""",
    ).toString().encodeToByteArray()

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

    private fun hex(value: String): ByteArray = value.chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()

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
        const val SECRET_USE_REQUEST_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAX"
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

private class FixedSecureRandom(private val bytes: ByteArray) : SecureRandom() {
    override fun nextBytes(target: ByteArray) {
        require(target.size == bytes.size)
        bytes.copyInto(target)
    }
}
