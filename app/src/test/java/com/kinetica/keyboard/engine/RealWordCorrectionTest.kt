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
        val lead = p.decode(tapped(typed), emptyList()).firstOrNull()
        return p.tapAutocorrect(typed, lead, threshold)?.word
    }

    @Test
    fun rareWordsOneKeyFromCommonOnesAreCorrected() {
        val p = english()
        for ((typed, meant) in listOf("iy" to "it", "eben" to "even", "thr" to "the", "yhe" to "the")) {
            assertTrue("$typed must be in the list for this test to mean anything", p.isWord(typed))
            assertEquals(typed, meant, correction(p, typed))
        }
    }

    @Test
    fun commonAndLongerRealWordsAreKept() {
        val p = english()
        // Common words, and rare five-letter words one key from common ones.
        // `whos` is a contraction typed without its apostrophe.
        for (typed in listOf("so", "in", "on", "wafer", "jello", "excise", "fable", "whos")) {
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
        val it = cand("it", 0.1f, WordCandidate.Source.FUZZY_TAP)
        assertEquals("it", p.realWordCorrection("iy", it, threshold)?.word)
        // Not confident enough.
        val far = cand("it", 0.5f, WordCandidate.Source.FUZZY_TAP)
        assertNull(p.realWordCorrection("iy", far, threshold))
        // Two letters apart.
        assertNull(p.realWordCorrection("iy", cand("to", 0.1f, WordCandidate.Source.FUZZY_TAP), threshold))
        // A completion never corrects.
        assertNull(p.realWordCorrection("iy", cand("it", 0.1f, WordCandidate.Source.COMPLETION), threshold))
        assertTrue(p.frequencyByte("it") > p.frequencyByte("iy"))
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
