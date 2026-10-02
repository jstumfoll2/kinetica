package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.PathPoint
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.SwipeToken
import com.kinetica.keyboard.engine.models.TapToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The digitizer merging two close thumbs into one contact for a few ms. Shape
 * and timings follow the developer's "positive" trace: both strokes stop at
 * 431 ms, a 2-sample touch at their midpoint 439-447, both resume at 456.
 */
class ContactRepairTest {

    private fun swipe(s: StreamId, x0: Float, y0: Float, x1: Float, y1: Float, t0: Long, t1: Long): SwipeToken {
        val path = (0..10).map { k -> PathPoint(x0 + (x1 - x0) * k / 10, y0 + (y1 - y0) * k / 10, t0 + (t1 - t0) * k / 10) }
        val r = FloatArray(2 * KineticaConstants.RESAMPLE_N)
        DtwMatcher().resample(path, r)
        return SwipeToken(s, path, r, emptyList(), 1f, t0, t1)
    }

    private val r1 = swipe(StreamId.RIGHT, 9.6f, 0.4f, 7.4f, 1.0f, 0, 431)
    private val l1 = swipe(StreamId.LEFT, 1.7f, 2.3f, 4.5f, 1.6f, 132, 431)
    private val blip = TapToken(StreamId.RIGHT, 5, 6.2f, 1.7f, false, 439, 447)
    private val r2 = swipe(StreamId.RIGHT, 7.3f, 0.7f, 7.1f, 0.6f, 456, 581)
    private val l2 = swipe(StreamId.LEFT, 4.9f, 3.3f, 2.8f, 1.2f, 456, 728)

    @Test
    fun aMergeBlipIsDroppedAndBothStrokesRejoined() {
        val out = ContactRepair.repair(listOf(r1, l1, blip, r2, l2))
        assertEquals(2, out.size)
        val r = out.single { it.streamId == StreamId.RIGHT } as SwipeToken
        val l = out.single { it.streamId == StreamId.LEFT } as SwipeToken
        assertEquals(0L to 581L, r.tStart to r.tEnd)
        assertEquals(132L to 728L, l.tStart to l.tEnd)
        assertEquals(r1.rawPath.size + r2.rawPath.size, r.rawPath.size)
    }

    @Test
    fun anOrdinaryLiftAndRetouchIsLeftAlone() {
        // One thumb lifts and comes back (a peck), the other keeps going: no blip.
        val l = swipe(StreamId.LEFT, 1.7f, 2.3f, 4.5f, 1.6f, 0, 600)
        val tokens: List<InputToken> = listOf(
            swipe(StreamId.RIGHT, 8f, 1f, 7f, 1f, 0, 200),
            TapToken(StreamId.RIGHT, 8, 7f, 1f, false, 230, 300),
            l,
        )
        assertSame(tokens, ContactRepair.repair(tokens))
    }
}
