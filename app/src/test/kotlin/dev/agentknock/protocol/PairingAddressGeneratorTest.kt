package dev.agentknock.protocol

import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingAddressGeneratorTest {
    @Test
    fun `generates three different words separated by dashes`() {
        val indices = ArrayDeque(listOf(2, 2, 0, 1))
        val generator =
            PairingAddressGenerator(
                words = listOf("amber", "river", "maple"),
                nextIndex = { bound ->
                    indices.removeFirst() % bound
                },
            )

        val address = generator.generate()

        assertEquals(3, address.split('-').size)
        assertEquals(setOf("maple", "amber", "river"), address.split('-').toSet())
        assertTrue(DeviceProtocol.validPairingAddress(address))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects words outside the pairing address alphabet`() {
        PairingAddressGenerator(
            words = listOf("amber", "blue-sky", "maple"),
            nextIndex = { 0 },
        )
    }
}
