package dev.agentknock.storage.device

import dev.agentknock.protocol.DeviceKeyPair
import dev.agentknock.protocol.EstablishedPairing
import dev.agentknock.protocol.OpenedPairedRequest
import dev.agentknock.protocol.PairedRequestKeySource
import dev.agentknock.protocol.PairedResponseContext
import dev.agentknock.storage.crypto.DecryptionResult
import dev.agentknock.storage.request.CompletionOpenResult
import dev.agentknock.storage.request.PairingFinishContext
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertFalse
import org.junit.Test

class SecretFormattingTest {
    @Test
    fun `formatting secret holders and nested results never expands their contents`() {
        val token = "synthetic-secret-marker"
        val bytes = token.encodeToByteArray()
        val responseContext = PairedResponseContext(ByteArray(32), bytes)
        val key = DeviceKeyAccess(Dispatchers.Unconfined) { error("must not decrypt") }
        val credentials =
            RelayDeviceCredentials(
                deviceIdentityId = "identity",
                address = "amber-river-maple",
                addressId = "address-id",
                deviceId = "device-id",
                deviceKey = key,
                deviceToken = token,
            )
        val values =
            listOf(
                key,
                credentials,
                RelayDeviceAuthorization("identity", "device-id", token),
                DeviceKeyPair(bytes, ByteArray(32)),
                EstablishedPairing(bytes, 1L, bytes),
                responseContext,
                OpenedPairedRequest(bytes, bytes, PairedRequestKeySource.CURRENT, responseContext),
                PairingFinishContext("client-id", bytes, "request-id", "identity"),
                CompletionOpenResult.Opened(bytes),
                DecryptionResult.Plaintext(bytes),
            )
        for (value in values) {
            for (formatted in
                listOf(
                    value.toString(),
                    DeviceCredentialResult.Available(value).toString(),
                    listOf(value).toString(),
                )) {
                assertFalse(
                    "Leaked text from ${value.javaClass.simpleName}",
                    formatted.contains(token),
                )
                assertFalse(
                    "Leaked bytes from ${value.javaClass.simpleName}",
                    formatted.contains(bytes.contentToString()),
                )
            }
        }
    }
}
