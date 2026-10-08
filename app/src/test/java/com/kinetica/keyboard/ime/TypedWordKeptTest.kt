package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tapped word's way back from the keyboard's own guesses: letters edited by hand or an
 * autocorrect already undone stay as typed, a commit deleted on sight gives its weight back, and
 * a word reopened untouched gets the bar it was committed with.
 */
class TypedWordKeptTest {

    @Test
    fun anEditedOrUndoneWordKeepsItsLetters() {
        // Captured: `tion` typed after `gener` became `tino` three times, each after the user
        // backspaced into it and finished it again; `bandi` became `bando`, then `nandi`.
        assertTrue(keepsTypedLetters(editedByHand = true, rejected = false))
        assertTrue(keepsTypedLetters(editedByHand = false, rejected = true))
        assertFalse(keepsTypedLetters(editedByHand = false, rejected = false))
    }

    @Test
    fun deletingIntoTheLastCommitTakesItsWeightBack() {
        // backspaceLeft: -1 while the word is whole, 0 once all of it is gone.
        assertFalse(deleteTakesBackCommit(-1, 6))
        assertFalse(deleteTakesBackCommit(null, 6))
        assertTrue(deleteTakesBackCommit(0, 6))
        assertTrue(deleteTakesBackCommit(5, 6))
        assertFalse(deleteTakesBackCommit(6, 6))
    }

    @Test
    fun aReopenedWordOffersWhatItsBarHad() {
        // A swipe committed `when` with `shen` and `whe` beside it; reopened, its letters alone
        // decode to spelling neighbours of `when`.
        val history = CommitHistory.Record(10, "when", listOf("shen", "whe"), emptyMap())
        assertEquals(
            listOf("when", "shen", "whe", "whence"),
            reloadWords(listOf("when", "whence"), history, "when"),
        )
        // An autocorrected word: the letters as typed lead its alternatives.
        val corrected = CommitHistory.Record(0, "tino", listOf("tion", "toon"), emptyMap())
        assertEquals(listOf("tino", "tion", "toon", "tin"), reloadWords(listOf("tin"), corrected, "tino"))
        assertEquals(listOf("when", "whence"), reloadWords(listOf("when", "whence"), null, "when"))
    }
}
