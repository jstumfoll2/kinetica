package com.kinetica.keyboard.engine

/**
 * A small English grammar pass over the word before the one just committed.
 *
 * Only the word after a word settles some choices: `a` or `an` depends on the next word's
 * sound, `your` or `you're` on whether a verb follows (`your going`, `you're welcome`), `then` or
 * `than` on a comparison. So when a word is committed, the one before it is checked against
 * both neighbours, and the keyboard rewrites it when the evidence is one-sided.
 *
 * Three kinds of rule, all on-device and from bundled data:
 * - Articles: `a` before a vowel sound becomes `an`, `an` before a consonant sound becomes `a`,
 *   with the common exceptions spelled out (`an hour`, `a user`, `a one`).
 * - `could of`, `would of`, `should of`, `must of`, `might of`: `of` becomes `have`.
 * - Confusion sets ([CONFUSIONS]): a member is swapped for another when the bundled word pairs
 *   favour the other with the next word by [KineticaConstants.GRAMMAR_PAIR_GAP] boost bytes, the
 *   pair is a real one ([KineticaConstants.GRAMMAR_MIN_PAIR_BYTE]), and the pair with the word
 *   before does not favour the word as written by as much. Word pairs are counted from Tatoeba,
 *   written by people correcting each other's English, so `your going` is rare there.
 *
 * `to`/`too` is left out: pairs cannot see punctuation, and `me too, when` reads as `to when`.
 *
 * Pure: no Android types, and the pair counts come in as a function.
 */
object GrammarCheck {

    /** Boost byte of a word pair, 0..255, 0 when the bundled table lacks it. */
    fun interface Pairs {
        fun byte(prev: String, next: String): Int
    }

    /** Words often written for one another, each set read as alternatives for its members. */
    val CONFUSIONS: List<Set<String>> = listOf(
        setOf("your", "you're"),
        setOf("its", "it's"),
        setOf("their", "there", "they're"),
        setOf("then", "than"),
        setOf("lose", "loose"),
        setOf("whose", "who's"),
        setOf("affect", "effect"),
        setOf("accept", "except"),
    )

    private val MODALS = setOf("could", "would", "should", "must", "might", "may")

    // Vowel letters that are spoken as a consonant (`a user`, `a one`, `a euro`), and consonant
    // letters that are silent (`an hour`). Prefixes, so `university`, `usually`, `honestly` follow.
    private val VOWEL_LETTER_CONSONANT_SOUND = listOf(
        "uni", "use", "usu", "uti", "ura", "uro", "ure", "eu", "ewe", "one", "once", "ufo", "ubi",
    )
    private val SILENT_H = listOf("hour", "honest", "honor", "honour", "heir")

    /**
     * The word [prev] should be, given [before] it (null at the start of the text) and [next]
     * after it, or null to leave it. Lowercase in and out; the caller restores the case.
     */
    fun fixPrevious(before: String?, prev: String, next: String, pairs: Pairs): String? {
        if (prev.isEmpty() || next.isEmpty()) return null
        article(prev, next)?.let { return it }
        if (prev == "of" && before in MODALS && next.firstOrNull()?.isLetter() == true) return "have"
        val set = CONFUSIONS.firstOrNull { prev in it } ?: return null
        return confusion(before, prev, next, set, pairs)
    }

    /** `a`/`an` for [next], or null when [prev] is no article or already right. */
    internal fun article(prev: String, next: String): String? {
        if (prev != "a" && prev != "an") return null
        // A lone letter or a symbol says nothing about the sound: `a b c`, `a 5`.
        if (next.length < 2 || !next.all { it.isLetter() || it == '\'' }) return null
        val wanted = if (vowelSound(next)) "an" else "a"
        return if (wanted == prev) null else wanted
    }

    internal fun vowelSound(word: String): Boolean {
        val w = word.lowercase()
        if (SILENT_H.any { w.startsWith(it) }) return true
        if (w[0] !in "aeiou") return false
        return VOWEL_LETTER_CONSONANT_SOUND.none { w.startsWith(it) }
    }

    private fun confusion(before: String?, prev: String, next: String, set: Set<String>, pairs: Pairs): String? {
        val gap = KineticaConstants.GRAMMAR_PAIR_GAP
        val rightPrev = pairs.byte(prev, next)
        val leftPrev = before?.let { pairs.byte(it, prev) } ?: 0
        var best: String? = null
        var bestScore = 0f
        for (alt in set) {
            if (alt == prev) continue
            val right = pairs.byte(alt, next)
            if (right < KineticaConstants.GRAMMAR_MIN_PAIR_BYTE) continue
            if (right - rightPrev < gap) continue
            val left = before?.let { pairs.byte(it, alt) } ?: 0
            // The left side may be silent but must not argue for the word as written.
            if (leftPrev - left > gap) continue
            val score = (right + left).toFloat()
            if (score > bestScore) {
                best = alt
                bestScore = score
            }
        }
        return best
    }

    /** [fixed] written in the case of [shown]: `Your` -> `You're`, `A` -> `An`. */
    fun inCaseOf(shown: String, fixed: String): String = when {
        shown.length > 1 && shown.all { !it.isLetter() || it.isUpperCase() } -> fixed.uppercase()
        shown.firstOrNull()?.isUpperCase() == true -> fixed.replaceFirstChar { it.uppercaseChar() }
        else -> fixed
    }
}
