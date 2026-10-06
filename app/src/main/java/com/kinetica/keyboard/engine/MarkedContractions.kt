package com.kinetica.keyboard.engine

/**
 * Contractions that only exist when the user asked for an apostrophe.
 *
 * The bundled English list holds 40 contractions and leaves out every one whose letters
 * already spell another word: "we're" is "were", "we'll" is "well", "i'll" is "ill". That is
 * deliberate. The search is apostrophe-transparent, so a dictionary "we're" would compete
 * with "were" on every swipe of those letters, at a corpus frequency close enough to win
 * some of them.
 *
 * A word marked as wanting an apostrophe (a swipe out to the apostrophe key, or a tap on it
 * mid-word) is the one case where the answer is not in doubt, so these are offered then and
 * only then, in place of the plain word, at the plain word's own score (see
 * WordPredictor.preferApostrophe). No frequency is invented for them.
 */
object MarkedContractions {

    private val EN = mapOf(
        "were" to "we're",
        "well" to "we'll",
        "wed" to "we'd",
        "ill" to "i'll",
        "id" to "i'd",
        "hell" to "he'll",
        "shell" to "she'll",
        "shed" to "she'd",
    )

    /** The contraction a marked [word] stands for in [language], or null. */
    fun of(language: String, word: String): String? = when (language) {
        "en" -> EN[word]
        else -> null
    }
}
