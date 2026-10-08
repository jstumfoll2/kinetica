package com.kinetica.keyboard.engine

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The grammar pass over the word before the one just committed. */
class GrammarCheckTest {

    private val none = GrammarCheck.Pairs { _, _ -> 0 }

    @Test
    fun theArticleFollowsTheNextWordsSound() {
        assertEquals("an", GrammarCheck.fixPrevious("ate", "a", "apple", none))
        assertEquals("a", GrammarCheck.fixPrevious("saw", "an", "dog", none))
        assertEquals("an", GrammarCheck.fixPrevious(null, "a", "hour", none))
        assertEquals("an", GrammarCheck.fixPrevious(null, "a", "honest", none))
        assertNull(GrammarCheck.fixPrevious(null, "a", "user", none))
        assertNull(GrammarCheck.fixPrevious(null, "a", "university", none))
        assertNull(GrammarCheck.fixPrevious(null, "a", "one", none))
        assertNull(GrammarCheck.fixPrevious(null, "an", "apple", none))
        // A lone letter or a number is not a word to sound out.
        assertNull(GrammarCheck.fixPrevious(null, "a", "e", none))
        assertNull(GrammarCheck.fixPrevious(null, "a", "8", none))
    }

    @Test
    fun ofAfterAModalIsHave() {
        assertEquals("have", GrammarCheck.fixPrevious("could", "of", "been", none))
        assertEquals("have", GrammarCheck.fixPrevious("should", "of", "known", none))
        assertNull(GrammarCheck.fixPrevious("most", "of", "them", none))
    }

    @Test
    fun theCaseOfTheWordAsWrittenIsKept() {
        assertEquals("You're", GrammarCheck.inCaseOf("Your", "you're"))
        assertEquals("An", GrammarCheck.inCaseOf("A", "an"))
        assertEquals("THEY'RE", GrammarCheck.inCaseOf("THEIR", "they're"))
        assertEquals("its", GrammarCheck.inCaseOf("it's", "its"))
    }

    private val pairs: GrammarCheck.Pairs? by lazy {
        val dir = listOf("src/main/assets/dictionaries", "app/src/main/assets/dictionaries")
            .map { Paths.get(it) }.firstOrNull { Files.exists(it.resolve("en_wordlist.txt")) } ?: return@lazy null
        val trie = Files.newBufferedReader(dir.resolve("en_wordlist.txt")).use { DictionaryLoader.loadWordlist(it) }
        val bigrams = Files.newBufferedReader(dir.resolve("en_bigrams.txt")).use { DictionaryLoader.loadBigrams(it, trie) }
        val p = WordPredictor(trie, bigrams, TestData.qwertyGeometry(), language = "en")
        GrammarCheck.Pairs { a, b -> p.pairByte(a, b) }
    }

    private fun fix(before: String?, prev: String, next: String): String? {
        val p = pairs
        assumeTrue("dictionary assets not found", p != null)
        return GrammarCheck.fixPrevious(before, prev, next, p!!)
    }

    @Test
    fun theWordPairsSettleConfusedSpellings() {
        assertEquals("you're", fix("think", "your", "going"))
        assertEquals("it's", fix("but", "its", "raining"))
        assertEquals("it's", fix(null, "its", "a"))
        assertEquals("they're", fix(null, "their", "going"))
        assertEquals("lose", fix("to", "loose", "weight"))
    }

    @Test
    fun rightSpellingsStay() {
        assertNull(fix("is", "its", "own"))
        assertNull(fix("love", "your", "dog"))
        assertNull(fix("lose", "weight", "fast"))
        assertNull(fix("and", "then", "we"))
        assertNull(fix("over", "there", "is"))
        // to/too is no set: pairs cannot see the comma in `me too, when`.
        assertNull(fix("me", "too", "when"))
    }
}
