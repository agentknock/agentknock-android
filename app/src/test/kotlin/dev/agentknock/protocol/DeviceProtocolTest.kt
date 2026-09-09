package dev.agentknock.protocol

import java.security.SecureRandom
import java.util.Base64
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProtocolTest {
    @Test
    fun `derives the same address id as the cli`() {
        assertEquals(
            "9e6f33bf47382846903dffa0962ea313",
            DeviceProtocol.addressId("yup-its-free"),
        )
    }

    @Test
    fun `uses the cli pairing address alphabet`() {
        assertTrue(DeviceProtocol.validPairingAddress("amber-river-maple"))
        assertFalse(DeviceProtocol.validPairingAddress(""))
        assertFalse(DeviceProtocol.validPairingAddress("Amber-river-maple"))
        assertFalse(DeviceProtocol.validPairingAddress("amber_river_maple"))
        assertFalse(DeviceProtocol.validPairingAddress("amber-rivér-maple"))
        assertFalse(DeviceProtocol.validPairingAddress("-amber-river-maple"))
        assertFalse(DeviceProtocol.validPairingAddress("amber-river-maple-"))
        assertFalse(DeviceProtocol.validPairingAddress("amber--river-maple"))
    }

    @Test
    fun `generates an x25519 device key pair`() {
        val pair = DeviceProtocol.generateDeviceKeyPair(SecureRandom(byteArrayOf(1, 2, 3)))

        assertEquals(32, pair.privateKey.size)
        assertEquals(32, pair.publicKey.size)
        assertArrayEquals(
            pair.publicKey,
            X25519PrivateKeyParameters(pair.privateKey, 0).generatePublicKey().encoded,
        )
    }

    @Test
    fun `derives the x25519 public key from stored private material`() {
        val pair = DeviceProtocol.generateDeviceKeyPair(SecureRandom(byteArrayOf(7, 8, 9)))

        assertArrayEquals(
            pair.publicKey,
            DeviceProtocol.deriveDevicePublicKey(pair.privateKey),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a malformed stored x25519 private key`() {
        DeviceProtocol.deriveDevicePublicKey(ByteArray(31))
    }

    @Test
    fun `generates a canonical device ulid`() {
        val deviceId =
            DeviceProtocol.generateDeviceId(
                timestampMillis = 1_700_000_000_000,
                random = SecureRandom(byteArrayOf(4, 5, 6)),
            )

        assertTrue(deviceId.matches(Regex("[0-7][0-9A-HJKMNP-TV-Z]{25}")))
        assertEquals("01HF7YAT00", deviceId.take(10))
    }

    @Test
    fun `encodes a 32 byte device token as unpadded base64url`() {
        val deviceToken = ByteArray(32) { index -> index.toByte() }

        val encoded = DeviceProtocol.encodeDeviceToken(deviceToken)

        assertTrue(encoded.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertArrayEquals(deviceToken, Base64.getUrlDecoder().decode(encoded))
    }
}
