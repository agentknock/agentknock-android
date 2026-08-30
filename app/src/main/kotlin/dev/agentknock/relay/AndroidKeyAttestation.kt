package dev.agentknock.relay

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID

internal fun interface DeviceAttestationProvider {
    fun attest(deviceId: String, deviceToken: String): AndroidKeyAttestation?
}

internal class AndroidKeyAttestationProvider(
    private val generateCertificateChain: (ByteArray) -> List<ByteArray> =
        ::generateAndroidKeyAttestationCertificateChain,
) : DeviceAttestationProvider {
    override fun attest(deviceId: String, deviceToken: String): AndroidKeyAttestation? {
        val challenge = deviceClaimAttestationChallenge(deviceId, deviceToken)
        val certificates = runCatching { generateCertificateChain(challenge) }
            .getOrNull()
            ?.takeIf(List<ByteArray>::isNotEmpty)
            ?: return null
        return AndroidKeyAttestation(
            type = ANDROID_KEY_ATTESTATION_TYPE,
            certificateChain = certificates.map(Base64.getEncoder()::encodeToString),
        )
    }
}

internal fun deviceClaimAttestationChallenge(deviceId: String, deviceToken: String): ByteArray {
    val context = "$DEVICE_CLAIM_ATTESTATION_CONTEXT\u0000$deviceId\u0000$deviceToken"
        .toByteArray(StandardCharsets.UTF_8)
    return MessageDigest.getInstance("SHA-256").digest(context)
}

private fun generateAndroidKeyAttestationCertificateChain(
    challenge: ByteArray,
): List<ByteArray> {
    val alias = "$ATTESTATION_KEY_ALIAS_PREFIX${UUID.randomUUID()}"
    val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
    try {
        val parameters = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(challenge)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE).run {
            initialize(parameters)
            generateKeyPair()
        }
        return checkNotNull(keyStore.getCertificateChain(alias)) {
            "Android Keystore returned no attestation certificate chain"
        }.map { certificate -> certificate.encoded }
    } finally {
        keyStore.deleteEntry(alias)
    }
}

private const val ANDROID_KEY_STORE = "AndroidKeyStore"
private const val ANDROID_KEY_ATTESTATION_TYPE = "android_key"
private const val ATTESTATION_KEY_ALIAS_PREFIX = "dev.agentknock.claim-attestation."
private const val DEVICE_CLAIM_ATTESTATION_CONTEXT = "agentknock-device-claim-v1"
