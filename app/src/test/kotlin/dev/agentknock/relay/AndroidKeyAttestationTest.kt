package dev.agentknock.relay

import java.util.HexFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidKeyAttestationTest {
    @Test
    fun `derives the backend device claim challenge`() {
        assertEquals(
            "00cb71c6abdf04fbdb499d3a4f28f3f328dd380d7ead91194bd769999a575227",
            HexFormat.of().formatHex(
                deviceClaimAttestationChallenge(DEVICE_ID, DEVICE_TOKEN),
            ),
        )
    }

    @Test
    fun `encodes an unwrapped leaf to root certificate chain`() {
        var receivedChallenge: ByteArray? = null
        val provider = AndroidKeyAttestationProvider { challenge ->
            receivedChallenge = challenge
            listOf(
                "leaf".encodeToByteArray(),
                "intermediate".encodeToByteArray(),
                byteArrayOf(0xff.toByte()),
            )
        }

        val attestation = checkNotNull(provider.attest(DEVICE_ID, DEVICE_TOKEN))

        assertArrayEquals(
            deviceClaimAttestationChallenge(DEVICE_ID, DEVICE_TOKEN),
            receivedChallenge,
        )
        assertEquals("android_key", attestation.type)
        assertEquals(
            listOf("bGVhZg==", "aW50ZXJtZWRpYXRl", "/w=="),
            attestation.certificateChain,
        )
    }

    @Test
    fun `omits attestation when certificate generation fails`() {
        val provider = AndroidKeyAttestationProvider {
            throw IllegalStateException("Keystore unavailable")
        }

        assertNull(provider.attest(DEVICE_ID, DEVICE_TOKEN))
    }

    private companion object {
        const val DEVICE_ID = "01K2ENXDTW1P3XAR4J7V7C9D0H"
        const val DEVICE_TOKEN = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    }
}
