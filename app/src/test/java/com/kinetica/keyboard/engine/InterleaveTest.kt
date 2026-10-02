package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.PathPoint
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.SwipeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * The interleaved two-thumb reading: both thumbs swipe at once and the word's
 * letter order is the order in which either thumb reached its keys.
 *
 * "discussion" is the developer's own report: left thumb d..s..c..ss while the
 * right goes i..u..i-o-n, with no lift or peck in the middle. Timings below are
 * a plausible schedule of that (one key per ~70 ms, thumbs alternating), and
 * the paths turn ON each key at its moment, slightly off centre.
 */
class InterleaveTest {

    private val g = TestData.qwertyGeometry()

    /** A swipe through (letter, ms) waypoints, 10 ms samples, a little off centre. */
    private fun stroke(stream: StreamId, vararg keys: Pair<Char, Long>): SwipeToken {
        val pts = keys.map { (ch, t) -> Triple(g.centerX(ch - 'a') + 0.12f, g.centerY(ch - 'a') - 0.08f, t) }
        val path = ArrayList<PathPoint>()
        for (i in 0 until pts.size - 1) {
            val (x0, y0, t0) = pts[i]
            val (x1, y1, t1) = pts[i + 1]
            var t = t0
            while (t < t1) {
                val f = (t - t0).toFloat() / (t1 - t0)
                path.add(PathPoint(x0 + f * (x1 - x0), y0 + f * (y1 - y0), t))
                t += 10
            }
        }
        path.add(PathPoint(pts.last().first, pts.last().second, pts.last().third))
        var arc = 0f
        for (i in 1 until path.size) {
            val dx = path[i].x - path[i - 1].x
            val dy = path[i].y - path[i - 1].y
            arc += sqrt(dx * dx + dy * dy)
        }
        val resampled = FloatArray(2 * KineticaConstants.RESAMPLE_N)
        DtwMatcher().resample(path, resampled)
        return SwipeToken(stream, path, resampled, TestData.contactsAlong(path, g), arc, path.first().t, path.last().t)
    }

    private fun discussion() = listOf(
        stroke(StreamId.LEFT, 'd' to 0L, 's' to 140L, 'c' to 210L, 's' to 350L),
        stroke(StreamId.RIGHT, 'i' to 70L, 'u' to 280L, 'i' to 420L, 'o' to 490L, 'n' to 560L),
    )

    private val words = Trie.build(
        listOf(
            "discussion" to 30_000, "discussions" to 8_000, "discussing" to 6_000,
            "dismission" to 200, "decision" to 40_000, "scission" to 100,
            "disunion" to 300, "succession" to 9_000,
            "the" to 1_000_000, "is" to 900_000, "in" to 900_000,
        ),
    )

    @Test
    fun theTimingOrderReadsTheWord() {
        val il = Interleave.of(discussion(), g)
        assertNotNull(il)
        il!!
        val c = il.cost("discussion")
        assertTrue("discussion should fit closely, cost $c", c < 0.3f)
        // The same letters read one thumb after the other do not fit the timeline.
        assertTrue(il.cost("dscssiuion") > c)
        assertTrue(il.cost("decision") > c)
    }

    @Test
    fun overlappingThumbsDecodeTheWordFirst() {
        val out = WordPredictor(words, BigramTable.EMPTY, g).decode(discussion(), emptyList())
        assertEquals("discussion", out.first().word)
    }

    @Test
    fun oneThumbAfterTheOtherIsNotInterleaved() {
        val apart = listOf(
            stroke(StreamId.LEFT, 'd' to 0L, 's' to 100L),
            stroke(StreamId.RIGHT, 'i' to 200L, 'n' to 300L),
        )
        assertNull(Interleave.of(apart, g))
        val same = listOf(
            stroke(StreamId.LEFT, 'd' to 0L, 's' to 300L),
            stroke(StreamId.LEFT, 'i' to 100L, 'n' to 400L),
        )
        assertNull(Interleave.of(same, g))
    }
}
