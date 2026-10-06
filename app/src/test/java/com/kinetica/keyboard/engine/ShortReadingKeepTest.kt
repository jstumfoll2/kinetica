package com.kinetica.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [KineticaConstants.shortReadingKeep], on the numbers of the 2026-10-06 one-finger traces. */
class ShortReadingKeepTest {

    private fun keep(ideal: Float, arc: Float) = KineticaConstants.shortReadingKeep(ideal, arc)

    @Test
    fun shortSwipesAreNeverCharged() {
        // A slow, wiggly "are": 4.2 kw of word over 8.8 kw of travel.
        assertEquals(1f, keep(4.2f, 8.8f), 0f)
    }

    @Test
    fun theDrawnWordKeepsEverything() {
        assertEquals(1f, keep(18.3f, 18.9f), 0f) // respond
        assertEquals(1f, keep(23.5f, 26.7f), 0f) // continue, the loopier attempt
    }

    @Test
    fun aShortWordOnALongSwipeLosesEnoughToFallBehind() {
        // did vs respond scored 0.191 vs 0.178 before; come vs continue 0.164 vs 0.153.
        assertTrue(0.191f * keep(9.4f, 18.9f) < 0.178f)
        assertTrue(0.164f * keep(13.7f, 23.8f) < 0.153f)
    }
}
