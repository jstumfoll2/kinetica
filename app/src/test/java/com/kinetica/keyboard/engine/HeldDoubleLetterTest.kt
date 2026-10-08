package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.PathPoint
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.SwipeToken
import java.io.File
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A key held mid-swipe marks a doubled letter ([HeldDoubles]). The path of "god" and "good" is
 * the same line, so without the hold the two are told apart by frequency alone.
 */
class HeldDoubleLetterTest {

    private val g = TestData.qwertyGeometry()

    /**
     * A swipe through [letters]' keys at [legMs] per key-to-key leg, resting [holdMs] on the
     * key at letter index [holdAt] (no rest when negative).
     */
    private fun heldSwipe(
        letters: String,
        holdAt: Int,
        holdMs: Long,
        t0: Long = 10_000,
        legMs: Long = 60,
        stream: StreamId = StreamId.RIGHT,
    ): SwipeToken {
        val codes = letters.map { g.alphabet.codeOf(it) }
        val path = ArrayList<PathPoint>()
        var t = t0
        for (i in codes.indices) {
            if (i > 0 && codes[i] == codes[i - 1]) continue
            val x1 = g.centerX(codes[i])
            val y1 = g.centerY(codes[i])
            if (path.isEmpty()) {
                path.add(PathPoint(x1, y1, t))
            } else {
                val p = path.last()
                for (k in 1..10) {
                    val f = k / 10f
                    path.add(PathPoint(p.x + f * (x1 - p.x), p.y + f * (y1 - p.y), t + legMs * k / 10))
                }
                t += legMs
            }
            if (i == holdAt) {
                for (k in 1..10) path.add(PathPoint(x1, y1, t + holdMs * k / 10))
                t += holdMs
            }
        }
        var arc = 0f
        for (i in 1 until path.size) {
            val dx = path[i].x - path[i - 1].x
            val dy = path[i].y - path[i - 1].y
            arc += sqrt(dx * dx + dy * dy)
        }
        val resampled = FloatArray(2 * KineticaConstants.RESAMPLE_N)
        DtwMatcher().resample(path, resampled)
        return SwipeToken(stream, path, resampled, TestData.contactsAlong(path, g), arc, t0, t)
    }

    private fun english(): WordPredictor {
        val dir = listOf(File("src/main/assets/dictionaries"), File("app/src/main/assets/dictionaries"))
            .firstOrNull { File(it, "en_wordlist.txt").exists() }
        assumeTrue("en wordlist not found", dir != null)
        val d = File(dir, "en_wordlist.txt").bufferedReader().use { DictionaryLoader.load(it) }
        return WordPredictor(d.trie, BigramTable.EMPTY, g, d.forms, language = "en")
    }

    private fun lead(p: WordPredictor, tokens: List<InputToken>): String? =
        p.decode(tokens, emptyList()).firstOrNull()?.word

    private fun held(vararg tokens: InputToken): String =
        HeldDoubles.heldKeys(tokens.toList(), KineticaConstants.HELD_DOUBLE_MS, KineticaConstants.HELD_DOUBLE_END_MS)
            .map { g.alphabet.charOf(it) }.sorted().joinToString("")

    @Test
    fun anInteriorHoldMarksItsKey() {
        assertEquals("l", held(heldSwipe("holy", 2, 300)))
        assertEquals("", held(heldSwipe("holy", -1, 0)))
        assertEquals("", held(heldSwipe("holy", 2, KineticaConstants.HELD_DOUBLE_MS - 80)))
    }

    @Test
    fun theLandingNeverCountsAndTheLastKeyNeedsLess() {
        assertEquals("", held(heldSwipe("god", 0, 400)))
        assertEquals("o", held(heldSwipe("to", 1, KineticaConstants.HELD_DOUBLE_END_MS)))
    }

    @Test
    fun aTurnBackNeedsALongerHold() {
        // a-n-a turns on n: the thumb stops there whatever it meant.
        assertEquals("", held(heldSwipe("ana", 1, 300)))
        assertEquals("n", held(heldSwipe("ana", 1, 2 * KineticaConstants.HELD_DOUBLE_MS)))
    }

    @Test
    fun aLongTapMarksItsKey() {
        val t = TestData.tap('o', g, 10_000)
        assertEquals("", held(t))
        assertEquals("o", held(t.copy(tEnd = t.tStart + KineticaConstants.HELD_DOUBLE_MS)))
    }

    @Test
    fun aThumbParkedWhileTheOtherTypesIsNoHold() {
        val left = heldSwipe("holy", 2, 400, stream = StreamId.LEFT)
        val other = TestData.tap('p', g, left.tStart + 120, StreamId.RIGHT)
        assertEquals("", held(left, other))
    }

    @Test
    fun heldLLeadsWithHolly() {
        // "holy" is four times as frequent; the hold is what says "holly".
        val p = english()
        assertEquals("holly", lead(p, listOf(heldSwipe("holy", 2, 300))))
        assertNotEquals("holly", lead(p, listOf(heldSwipe("holy", -1, 0))))
    }

    @Test
    fun heldLastOLeadsWithToo() {
        val p = english()
        assertEquals("too", lead(p, listOf(heldSwipe("to", 1, 250))))
        assertEquals("to", lead(p, listOf(heldSwipe("to", -1, 0))))
    }

    @Test
    fun boostOneIsTodaysList() {
        val d = english()
        val dir = listOf(File("src/main/assets/dictionaries"), File("app/src/main/assets/dictionaries"))
            .first { File(it, "en_wordlist.txt").exists() }
        val loaded = File(dir, "en_wordlist.txt").bufferedReader().use { DictionaryLoader.load(it) }
        val off = WordPredictor(loaded.trie, BigramTable.EMPTY, g, loaded.forms, language = "en", heldDoubleBoost = 1f)
        val plain = listOf(heldSwipe("god", -1, 0))
        val words = { p: WordPredictor, t: List<InputToken> -> p.decode(t, emptyList()).map { it.word }.toTypedArray() }
        // No hold: the boost never applies, so the list is the one the boost-off decoder gives.
        assertArrayEquals(words(off, plain), words(d, plain))
    }
}
