package dev.agentknock.protocol

import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingAddressGeneratorTest {
    @Test
    fun `generates three different words separated by dashes`() {
        val indices = ArrayDeque(listOf(2, 2, 0, 1))
        val bounds = mutableListOf<Int>()
        val generator = PairingAddressGenerator(
            words = listOf("amber", "river", "maple"),
            nextIndex = { bound ->
                bounds += bound
                indices.removeFirst()
            },
        )

        assertEquals("maple-amber-river", generator.generate())
        assertEquals(listOf(3, 3, 3, 3), bounds)
        assertTrue(DeviceProtocol.validPairingAddress("maple-amber-river"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects words outside the pairing address alphabet`() {
        PairingAddressGenerator(
            words = listOf("amber", "blue-sky", "maple"),
            nextIndex = { 0 },
        )
    }
}
