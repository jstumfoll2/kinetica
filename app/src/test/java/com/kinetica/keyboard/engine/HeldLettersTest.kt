package com.kinetica.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A hold on a key mid-swipe marks its letter doubled. */
class HeldLettersTest {

    private val g = TestData.qwertyGeometry()

    private fun cand(word: String, score: Float) =
        com.kinetica.keyboard.engine.models.WordCandidate(
            word, score, 0.1f, 0.5f, 1f, 0, com.kinetica.keyboard.engine.models.WordCandidate.Source.MERGED, "en",
        )

    @Test
    fun aRestMidSwipeIsAHold() {
        val held = TestData.dwellSwipe("hop", "ing", g, t0 = 0L, travelMs = 300L, restMs = 200L, resumeMs = 300L)
        assertTrue('p' in HeldLetters.held(listOf(held), g))
        val passed = TestData.swipe("hoping", g, t0 = 0L, durMs = 600L)
        assertFalse('p' in HeldLetters.held(listOf(passed), g))
    }

    @Test
    fun theHeldDoubleGoesFirst() {
        val list = listOf(cand("hoping", 0.9f), cand("hopping", 0.6f), cand("homing", 0.3f))
        val out = HeldLetters.preferHeldDouble(list, setOf('p'))
        assertEquals(listOf("hopping", "hoping", "homing"), out.map { it.word })
        assertEquals(0.9f, out[0].score)
    }

    @Test
    fun nothingMovesWithoutAHoldOnTheDoubledKey() {
        val list = listOf(cand("hoping", 0.9f), cand("hopping", 0.6f))
        assertEquals(list, HeldLetters.preferHeldDouble(list, emptySet()))
        assertEquals(list, HeldLetters.preferHeldDouble(list, setOf('o')))
        // A first or last letter is no inner double, and a stretched interjection is no word.
        val ends = listOf(cand("al", 0.9f), cand("all", 0.6f), cand("ahh", 0.5f), cand("ahhh", 0.4f))
        assertEquals(ends, HeldLetters.preferHeldDouble(ends, setOf('l', 'h')))
    }

    @Test
    fun innerDoublesSkipTheEnds() {
        assertEquals(setOf('p'), HeldLetters.innerDoubles("hopping"))
        assertEquals(emptySet<Char>(), HeldLetters.innerDoubles("all"))
        assertEquals(emptySet<Char>(), HeldLetters.innerDoubles("eel"))
        assertEquals(setOf('o', 'k', 'e'), HeldLetters.innerDoubles("bookkeeper"))
    }
}
