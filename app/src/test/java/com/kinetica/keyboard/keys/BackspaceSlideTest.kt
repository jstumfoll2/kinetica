package com.kinetica.keyboard.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backspace slide's travel-to-count mapping. The step is 10 throughout, so a travel of 23
 * is 2.3 steps, and times are milliseconds since the finger went down.
 */
class BackspaceSlideTest {

    private val step = 10f

    private fun slide() = BackspaceSlide().apply { reset(0L) }

    @Test
    fun aUnitIsStagedAtEachBoundary() {
        val s = slide()
        assertFalse(s.update(9f, step, 10))
        assertEquals(0, s.units)
        assertTrue(s.update(10f, step, 20))
        assertEquals(1, s.units)
        assertTrue(s.update(25f, step, 30))
        assertEquals(2, s.units)
    }

    @Test
    fun restingOnABoundaryDoesNotFlicker() {
        val s = slide()
        s.update(20f, step, 10)
        // Tremor just back over the boundary keeps the count.
        for ((i, x) in listOf(19f, 20.5f, 18f, 21f, 17.5f).withIndex()) {
            assertFalse("travel $x", s.update(x, step, 20L + i))
            assertEquals(2, s.units)
        }
    }

    @Test
    fun slidingBackRetractsOncePastTheHysteresis() {
        val s = slide()
        s.update(30f, step, 10)
        assertEquals(3, s.units)
        assertTrue(s.update(16f, step, 20))
        assertEquals(1, s.units)
        assertTrue(s.update(6f, step, 30))
        assertEquals(0, s.units)
        // Back out again: growth is at the boundary itself.
        assertTrue(s.update(10f, step, 40))
        assertEquals(1, s.units)
    }

    @Test
    fun rightOfTheStartStagesNothing() {
        val s = slide()
        assertFalse(s.update(-40f, step, 10))
        assertEquals(0, s.units)
        assertEquals(0, s.atLift(20))
    }

    @Test
    fun aStepTheLiftRolledInIsDropped() {
        val s = slide()
        s.update(12f, step, 100) // rests on one word...
        s.update(21f, step, 400) // ...and the lift's roll crosses into a second
        assertEquals(2, s.units)
        assertEquals(1, s.atLift(430))
    }

    @Test
    fun aStepHeldBeforeTheLiftIsKept() {
        val s = slide()
        s.update(12f, step, 100)
        s.update(21f, step, 400)
        assertEquals(2, s.atLift(400 + BackspaceSlide.LIFT_GUARD_MS))
    }

    @Test
    fun aFastFlickKeepsEveryStep() {
        val s = slide()
        // No count was rested on, so nothing is mistaken for a roll.
        s.update(11f, step, 10)
        s.update(22f, step, 20)
        s.update(33f, step, 30)
        assertEquals(3, s.atLift(35))
        // A single quick unit is never dropped either.
        val t = slide()
        t.update(11f, step, 10)
        assertEquals(1, t.atLift(15))
    }

    @Test
    fun aRetractionAtTheLiftIsKept() {
        val s = slide()
        s.update(22f, step, 100)
        s.update(15f, step, 400)
        assertEquals(1, s.atLift(410))
    }
}
