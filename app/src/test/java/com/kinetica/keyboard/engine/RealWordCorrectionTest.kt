package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.WordCandidate
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Tapped letters that spell a rare dictionary word one key from a common one, against the real
 * English list: `iy` stayed `iy` because the subtitle corpus holds it 185 times.
 */
class RealWordCorrectionTest {

    private val g = TestData.qwertyGeometry()
    private val threshold = KineticaConstants.AUTOCORRECT_CONF_NORMAL

    private fun assetPath(name: String): Path {
        val direct = Paths.get("src/main/assets/dictionaries/$name")
        if (Files.exists(direct)) return direct
        return Paths.get("app/src/main/assets/dictionaries/$name")
    }

    private fun english(counts: Map<String, Int> = emptyMap()): WordPredictor {
        val p = assetPath("en_wordlist.txt")
        assumeTrue("wordlist asset not found", Files.exists(p))
        val trie = Files.newBufferedReader(p).use { DictionaryLoader.loadWordlist(it) }
        return WordPredictor(trie, BigramTable.EMPTY, g, personalCounts = counts)
    }

    private fun cand(word: String, d: Float, source: WordCandidate.Source) =
        WordCandidate(word, 0.5f, d, 0.5f, 1f, 0, source)

    private fun tapped(word: String) = word.mapIndexed { i, c -> TestData.tap(c, g, i * 150L) }

    private fun correction(p: WordPredictor, typed: String): String? {
        val list = p.decode(tapped(typed), emptyList())
        return p.tapAutocorrect(typed, list.firstOrNull(), threshold, list)?.word
    }

    @Test
    fun rareWordsOneKeyFromCommonOnesAreCorrected() {
        val p = english()
        for ((typed, meant) in listOf("eben" to "even")) {
            assertTrue("$typed must be in the list for this test to mean anything", p.isWord(typed))
            assertEquals(typed, meant, correction(p, typed))
        }
    }

    @Test
    fun junkTheSpellCheckerFilterDroppedIsCorrectedAsANonWord() {
        // `iy` (185 subtitle uses) and `yhe` left the list with the SCOWL filter, so the
        // ordinary non-word autocorrect reaches them.
        val p = english()
        for ((typed, meant) in listOf("iy" to "it", "yhe" to "the")) {
            assertFalse(typed, p.isWord(typed))
            assertEquals(typed, meant, correction(p, typed))
        }
    }

    @Test
    fun commonAndLongerRealWordsAreKept() {
        val p = english()
        // Common words, and rare five-letter words one key from common ones.
        // `whats` is a contraction typed without its apostrophe.
        for (typed in listOf("so", "in", "on", "wafer", "jello", "excise", "fable", "whats")) {
            assertTrue(typed, p.isWord(typed))
            assertNull(typed, correction(p, typed))
        }
    }

    @Test
    fun aRareWordTheUserCommitsIsKept() {
        // Two commits, the merge floor: one can be the uncorrected typo itself.
        val p = english(mapOf("eben" to KineticaConstants.PERSONAL_MERGE_MIN_COUNT))
        assertNull(correction(p, "eben"))
        assertEquals("even", correction(english(mapOf("eben" to 1)), "eben"))
    }

    @Test
    fun theRuleNeedsOneLetterApartAndALargeFrequencyGap() {
        val p = english()
        val even = cand("even", 0.1f, WordCandidate.Source.FUZZY_TAP)
        assertEquals("even", p.realWordCorrection("eben", even, threshold)?.word)
        // Not confident enough.
        val far = cand("even", 0.5f, WordCandidate.Source.FUZZY_TAP)
        assertNull(p.realWordCorrection("eben", far, threshold))
        // Two letters apart.
        assertNull(p.realWordCorrection("eben", cand("oven", 0.1f, WordCandidate.Source.FUZZY_TAP), threshold))
        // A completion never corrects.
        assertNull(p.realWordCorrection("eben", cand("even", 0.1f, WordCandidate.Source.COMPLETION), threshold))
        assertTrue(p.frequencyByte("even") > p.frequencyByte("eben"))
        assertEquals(-1, p.frequencyByte("qzx"))
    }

    @Test
    fun ocrMisreadingsAreNotInTheList() {
        val p = english()
        for ((junk, word) in listOf("aii" to "all", "iike" to "like", "lt" to "it", "couid" to "could")) {
            assertFalse("$junk is an OCR misreading of $word", p.isWord(junk))
            assertTrue(word, p.isWord(word))
        }
    }
}
