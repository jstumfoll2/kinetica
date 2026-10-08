package com.kinetica.keyboard.engine.trace

import com.kinetica.keyboard.engine.trace.PracticeWords.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class PracticeWordsTest {

    @Test
    fun wordsLandInTheKindsTheirShapeSays() {
        assertEquals(setOf(Kind.SHORT), PracticeWords.kindsOf("the"))
        assertTrue(Kind.ONE_FINGER in PracticeWords.kindsOf("respond"))
        assertTrue(Kind.DOUBLE in PracticeWords.kindsOf("technically"))
        assertTrue(Kind.CENTER in PracticeWords.kindsOf("right"))
        assertTrue(Kind.CROSSING in PracticeWords.kindsOf("people"))
        // One half only: nothing for the thumbs to share.
        assertFalse(Kind.ONE_FINGER in PracticeWords.kindsOf("streets"))
        assertFalse(Kind.CENTER in PracticeWords.kindsOf("water"))
    }

    @Test
    fun switchesCountHalfChanges() {
        assertEquals(0, PracticeWords.switches("west"))
        assertEquals(4, PracticeWords.switches("right"))
    }

    @Test
    fun pickFollowsTheMix() {
        val pool = PracticeWords.Pool(listOf("the", "all", "respond", "right", "people", "water", "nothing"))
        val rnd = Random(7)
        val counts = HashMap<Kind, Int>()
        repeat(2000) {
            val (w, k) = pool.pick(rnd)
            if (k != Kind.ANY) assertTrue("$w as $k", k in PracticeWords.kindsOf(w))
            counts[k] = (counts[k] ?: 0) + 1
        }
        // Out of 20: ONE_FINGER 5, CENTER 5, CROSSING 4, the rest 2 each.
        val oneFinger = counts[Kind.ONE_FINGER] ?: 0
        assertTrue("one finger $oneFinger", oneFinger in 400..600)
        val center = counts[Kind.CENTER] ?: 0
        assertTrue("center $center", center in 400..600)
    }

    @Test
    fun anEmptyListStillGivesAPrompt() {
        assertEquals("hello" to Kind.ANY, PracticeWords.Pool(emptyList()).pick(Random(1)))
    }
}
