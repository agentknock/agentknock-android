package dev.agentknock.protocol

import java.security.SecureRandom

internal class PairingAddressGenerator(
    private val words: List<String>,
    private val nextIndex: (Int) -> Int = SecureRandom()::nextInt,
) {
    init {
        require(words.size >= WORD_COUNT) { "The pairing address word list is too short" }
        require(words.distinct().size == words.size) {
            "The pairing address word list has duplicates"
        }
        require(words.all { word -> word.isNotEmpty() && word.all { it in 'a'..'z' } }) {
            "Pairing address words must contain only lowercase ASCII letters"
        }
    }

    fun generate(): String {
        val selected = mutableListOf<Int>()
        while (selected.size < WORD_COUNT) {
            nextIndex(words.size).takeUnless(selected::contains)?.let(selected::add)
        }
        return selected.joinToString(separator = "-") { index -> words[index] }
    }

    private companion object {
        const val WORD_COUNT = 3
    }
}
