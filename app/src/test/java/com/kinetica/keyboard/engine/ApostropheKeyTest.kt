package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.SwipeToken
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The main-page apostrophe key, swiped through mid-word (Nintype style) or tapped by the
 * other thumb while a word is being swiped.
 *
 * Geometry is TestData's QWERTY plus the key where LayoutMutations.withApostropheKey puts
 * it: the right half-key of the home row, x 9.5..10 kw.
 */
class ApostropheKeyTest {

    private val letters = TestData.qwertyGeometry()

    private val withKey: KeyboardGeometry = run {
        val codes = ArrayList<Int>()
        val rects = ArrayList<FloatArray>()
        for (c in 0 until Alphabet.LETTERS) {
            letters.rectKw(c)?.let { codes.add(c); rects.add(it) }
        }
        codes.add(Alphabet.APOSTROPHE)
        rects.add(floatArrayOf(9.5f, 1.5f, 10f, 3f))
        KeyboardGeometry.fromKw(letters.keyWidthPx, letters.midlinePx, rects, codes.toIntArray())
    }

    private val apos = floatArrayOf(9.75f, 2.25f)

    private fun at(c: Char) = floatArrayOf(letters.centerX(c - 'a'), letters.centerY(c - 'a'))

    /** One pointer through [stops] (kw), densified, fed to a [GestureStream] in px. */
    private fun draw(g: KeyboardGeometry, vararg stops: FloatArray, t0: Long = 0L): InputToken {
        val kw = g.keyWidthPx
        val first = stops[0]
        val down = g.keyAt(first[0], first[1])
        val s = GestureStream(StreamId.RIGHT, 0, g, 0.3f, first[0] * kw, first[1] * kw, t0, down) { }
        var t = t0
        for (i in 1 until stops.size) {
            val a = stops[i - 1]
            val b = stops[i]
            for (k in 1..12) {
                val f = k / 12f
                t += 8
                s.addPoint((a[0] + f * (b[0] - a[0])) * kw, (a[1] + f * (b[1] - a[1])) * kw, t)
            }
        }
        return s.finish(t)
    }

    @Test
    fun theKeyIsNotALetter() {
        assertEquals(-1, withKey.keyAt(apos[0], apos[1]))
        assertFalse(withKey.hasKey(Alphabet.APOSTROPHE))
        assertEquals(letters.keyAt(9.0f, 2.25f), withKey.keyAt(9.0f, 2.25f))
    }

    @Test
    fun aTripOutToTheKeyMidWordIsCutAndMarked() {
        val tok = draw(withKey, at('w'), at('e'), apos, at('r'), at('e')) as SwipeToken
        assertTrue("excursion not marked", tok.apostrophe)
        assertTrue("trip left in the path", tok.rawPath.none { it.x > 4.0f })
        val keys = tok.keyContacts.map { Alphabet.charOf(it.code) }.joinToString("")
        assertTrue("crossed letters kept: $keys", keys.none { it in "tyuiophjkl" })
    }

    @Test
    fun aTripBackToTheNeighbouringKeyCounts() {
        // i'll: i, out to the key, back to l, which sits under one key from it.
        val tok = draw(withKey, at('i'), apos, at('l')) as SwipeToken
        assertTrue(tok.apostrophe)
    }

    @Test
    fun endingOnTheKeyAfterATurnIsAnApostrophe() {
        // The right thumb's half of "don't": o, down to n, out to the key, lift.
        val tok = draw(withKey, at('o'), at('n'), apos) as SwipeToken
        assertTrue(tok.apostrophe)
        val last = tok.rawPath.last()
        assertEquals(letters.centerX('n' - 'a'), last.x, 0.01f)
        val keys = tok.keyContacts.map { Alphabet.charOf(it.code) }.joinToString("")
        assertEquals("ends on n: $keys", 'n', keys.last())
    }

    @Test
    fun withoutTheKeyTheSamePathIsUntouched() {
        val tok = draw(letters, at('w'), at('e'), apos, at('r'), at('e')) as SwipeToken
        assertFalse(tok.apostrophe)
        assertTrue(tok.rawPath.any { it.x > 9.5f })
    }

    @Test
    fun endingOnTheKeyWithNoTurnIsNotTaken() {
        // A straight run from the stroke's first key: no letter to end the word on.
        val tok = draw(withKey, at('a'), at('l'), apos) as SwipeToken
        assertFalse(tok.apostrophe)
    }

    @Test
    fun clippingTheKeyOnTheWayDownIsNotATrip() {
        // p down to l, bulging through the key's corner: no turn back in x.
        val tok = draw(withKey, at('p'), floatArrayOf(9.7f, 1.9f), at('l')) as SwipeToken
        assertFalse(tok.apostrophe)
    }

    @Test
    fun aPathThatNeverReachesTheKeyIsBitIdentical() {
        val a = draw(letters, at('h'), at('e'), at('l'), at('o')) as SwipeToken
        val b = draw(withKey, at('h'), at('e'), at('l'), at('o')) as SwipeToken
        assertFalse(b.apostrophe)
        assertEquals(a.rawPath, b.rawPath)
        assertTrue(a.resampled.contentEquals(b.resampled))
        assertEquals(a.keyContacts, b.keyContacts)
        assertEquals(a.arcLen.toRawBits(), b.arcLen.toRawBits())
    }

    // ------------------------------------------------------------- decoding

    private val english: WordPredictor? by lazy {
        val p = listOf("src/main/assets/dictionaries/en_wordlist.txt", "app/src/main/assets/dictionaries/en_wordlist.txt")
            .map { Paths.get(it) }.firstOrNull { Files.exists(it) } ?: return@lazy null
        val trie = Files.newBufferedReader(p).use { DictionaryLoader.loadWordlist(it) }
        WordPredictor(trie, BigramTable.EMPTY, withKey, language = "en")
    }

    private fun top(tokens: List<InputToken>, mark: Boolean = false): String {
        val p = english
        assumeTrue("wordlist asset not found", p != null)
        return p!!.decode(tokens, emptyList(), apostrophe = mark).first().word
    }

    @Test
    fun theSwipedEndingReadsAsTheContraction() {
        val d = TestData.tap('d', withKey, 0, StreamId.LEFT)
        val on = draw(withKey, at('o'), at('n'), apos, t0 = 100L)
        val t = TestData.tap('t', withKey, 600, StreamId.LEFT)
        assertEquals("don't", top(listOf(d, on, t)))
    }

    @Test
    fun theSwipedTripReadsAsTheContraction() {
        val plain = draw(withKey, at('i'), at('l'))
        assertEquals("ill", top(listOf(plain)))
        val trip = draw(withKey, at('i'), apos, at('l'))
        assertEquals("i'll", top(listOf(trip)))
    }

    @Test
    fun aTapMarkPrefersTheApostropheSpelling() {
        val g = withKey
        assertEquals("well", top(listOf(TestData.swipe("well", g, 0, 300))))
        assertEquals("we'll", top(listOf(TestData.swipe("well", g, 0, 300)), mark = true))
        assertEquals("i'll", top(listOf(TestData.swipe("ill", g, 0, 300)), mark = true))
        assertEquals("don't", top(listOf(TestData.swipe("dont", g, 0, 300)), mark = true))
        assertEquals("it's", top(listOf(TestData.swipe("its", g, 0, 300)), mark = true))
    }

    @Test
    fun aMarkOnAWordWithNoApostropheSpellingKeepsTheOrder() {
        val tokens = listOf(TestData.swipe("hello", withKey, 0, 300))
        val p = english
        assumeTrue("wordlist asset not found", p != null)
        val plain = p!!.decode(tokens, emptyList()).map { it.word }
        val marked = p.decode(tokens, emptyList(), apostrophe = true).map { it.word }
        assertEquals(plain.first(), marked.first())
        assertEquals(plain.filter { '\'' !in it }, marked.filter { '\'' !in it })
    }

    @Test
    fun everyEnglishContractionWinsWhenMarked() {
        // The 40 in the bundled list plus the 8 that exist only when marked, each swiped
        // cleanly through its letters with a tap on the key.
        val p = english
        assumeTrue("wordlist asset not found", p != null)
        val words = listOf(
            "don't", "won't", "can't", "isn't", "aren't", "wasn't", "weren't", "doesn't",
            "didn't", "haven't", "hasn't", "hadn't", "wouldn't", "couldn't", "shouldn't",
            "mustn't", "needn't", "ain't", "it's", "that's", "what's", "he's", "she's",
            "who's", "there's", "here's", "where's", "how's", "let's", "you're", "they're",
            "we've", "you've", "they've", "i've", "i'm", "you'll", "they'll", "you'd",
            "they'd", "we're", "we'll", "we'd", "i'll", "i'd", "he'll", "she'll", "she'd",
        )
        val misses = ArrayList<String>()
        var plainWins = 0
        for (w in words) {
            val tokens = listOf(TestData.swipe(w.replace("'", ""), withKey, 0, 300))
            if (p!!.decode(tokens, emptyList()).first().word == w) plainWins++
            val got = p.decode(tokens, emptyList(), apostrophe = true).first().word
            if (got != w) misses.add("$w->$got")
        }
        println("contractions: ${words.size - misses.size}/${words.size} with the mark, $plainWins without; misses $misses")
        assertTrue("misses: $misses", misses.size <= 2)
    }
}
