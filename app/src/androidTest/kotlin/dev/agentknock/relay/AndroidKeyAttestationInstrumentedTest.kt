package dev.agentknock.relay

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidKeyAttestationInstrumentedTest {
    @Test
    fun createsCertificateChainAndDeletesEphemeralKey() {
        val aliasesBefore = attestationAliases()

        val attestation = AndroidKeyAttestationProvider().attest(DEVICE_ID, DEVICE_TOKEN)

        assertNotNull(attestation)
        checkNotNull(attestation)
        assertEquals("android_key", attestation.type)
        assertTrue(attestation.certificateChain.isNotEmpty())
        assertTrue(
            attestation.certificateChain.all { certificate ->
                Base64.getDecoder().decode(certificate).isNotEmpty()
            },
        )
        assertEquals(aliasesBefore, attestationAliases())
    }

    private fun attestationAliases(): Set<String> = KeyStore
        .getInstance("AndroidKeyStore")
        .apply { load(null) }
        .aliases()
        .toList()
        .filterTo(mutableSetOf()) { alias ->
            alias.startsWith("dev.agentknock.claim-attestation.")
        }

    private companion object {
        const val DEVICE_ID = "01K2ENXDTW1P3XAR4J7V7C9D0H"
        const val DEVICE_TOKEN = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    }
}
