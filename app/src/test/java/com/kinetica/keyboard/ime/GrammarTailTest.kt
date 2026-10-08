package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The grammar pass reads the two words before the one just committed off the editor text. */
class GrammarTailTest {

    @Test
    fun twoWordsBackAreRead() {
        assertEquals("think" to "your", grammarWords("I think your going", "going"))
        assertEquals(null to "Its", grammarWords("Its raining", "raining"))
        assertEquals(null to "its", grammarWords("Hello. its raining", "raining"))
        assertEquals("ate" to "a", grammarWords("we ate a apple", "apple"))
    }

    @Test
    fun punctuationOrDoubleSpacesStopIt() {
        assertNull(grammarWords("me too, when", "when"))
        assertNull(grammarWords("your  going", "going"))
        assertNull(grammarWords("going", "going"))
        assertNull(grammarWords("your going", "gone"))
    }
}
