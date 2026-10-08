package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/** Backspace straight after an autocorrect puts the typed letters back. */
class AutocorrectUndoTest {

    @Test
    fun theWordAndTheSpaceAfterItGoBack() {
        assertEquals(4, autocorrectUndoSpan("I said and ", "and", typedAfter = 1))
        assertEquals(4, autocorrectUndoSpan("so and,", "and", typedAfter = 1))
        assertEquals(3, autocorrectUndoSpan("so and", "and", typedAfter = 0))
    }

    @Test
    fun anythingTypedSinceRefusesIt() {
        assertEquals(-1, autocorrectUndoSpan("and x", "and", typedAfter = 2))
        assertEquals(-1, autocorrectUndoSpan("andx", "and", typedAfter = 1))
        assertEquals(-1, autocorrectUndoSpan("anb ", "and", typedAfter = 1))
        assertEquals(-1, autocorrectUndoSpan("d ", "and", typedAfter = 1))
    }

    @Test
    fun typedLettersSitRightAfterTheCorrectedWord() {
        // Ten candidates used to push the letters off the strip's end.
        val options = mutableListOf("tino", "tin", "tine", "ting", "tiny", "tint", "tion")
        moveTypedSecond(options, "tion")
        assertEquals(listOf("tino", "tion", "tin", "tine", "ting", "tiny", "tint"), options)
    }

    @Test
    fun aStripPickOfTheTypedLettersRevertsTheCorrection() {
        assertEquals(true, revertsAutocorrect("tion", "tino", current = "tino", replacement = "tion"))
        assertEquals(true, revertsAutocorrect("dd", "did", current = "Did", replacement = "DD"))
        assertEquals(false, revertsAutocorrect("tion", "tino", current = "tino", replacement = "tine"))
        assertEquals(false, revertsAutocorrect("tion", "tino", current = "then", replacement = "tion"))
    }

    @Test
    fun oneRevertLearnsTheLettersUpToTheMergeFloor() {
        assertEquals(2, typedLettersTopUp(0))
        assertEquals(1, typedLettersTopUp(1))
        assertEquals(0, typedLettersTopUp(2))
        assertEquals(0, typedLettersTopUp(7))
    }
}
