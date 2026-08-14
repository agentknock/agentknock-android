package dev.agentknock.protocol

import java.security.SecureRandom
import java.util.Base64
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultProtocolTest {
    @Test
    fun `derives the same route id as the cli`() {
        assertEquals(
            "0b7d7963604cba911e9c03e727688b89",
            VaultProtocol.routeId("yup-its-free"),
        )
    }

    @Test
    fun `uses the cli vault address alphabet`() {
        assertTrue(VaultProtocol.validAddress("amber-river-maple"))
        assertFalse(VaultProtocol.validAddress(""))
        assertFalse(VaultProtocol.validAddress("Amber-river-maple"))
        assertFalse(VaultProtocol.validAddress("amber_river_maple"))
        assertFalse(VaultProtocol.validAddress("amber-rivér-maple"))
    }

    @Test
    fun `generates an x25519 route key pair`() {
        val pair = VaultProtocol.generateRouteKeyPair(SecureRandom(byteArrayOf(1, 2, 3)))

        assertEquals(32, pair.privateKey.size)
        assertEquals(32, pair.publicKey.size)
        assertArrayEquals(
            pair.publicKey,
            X25519PrivateKeyParameters(pair.privateKey, 0).generatePublicKey().encoded,
        )
    }

    @Test
    fun `encodes a 32 byte relay token as unpadded base64url`() {
        val token = ByteArray(32) { index -> index.toByte() }

        val encoded = VaultProtocol.encodeAuthenticationToken(token)

        assertTrue(encoded.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertArrayEquals(token, Base64.getUrlDecoder().decode(encoded))
    }
}
