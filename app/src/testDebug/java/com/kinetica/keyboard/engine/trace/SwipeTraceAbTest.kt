package com.kinetica.keyboard.engine.trace

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The developer build's neural fields on a v1 word line: `cfg.rerank` and the
 * practice comparison `ab`. Both are optional, so a line without them reads as
 * before, and a line decoded with the rerank on is never counted as a replay
 * mismatch.
 */
class SwipeTraceAbTest {

    private val assets = File("src/main/assets")

    private fun oneLine(): SwipeTrace.Word {
        assumeTrue(File(assets, "dictionaries/en_wordlist.txt").exists())
        val lines = ArrayList<String>()
        val s = SyntheticSession(assets)
        s.record(s.words("en", 1), "en", null) { lines.add(it) }
        return SwipeTrace.decode(lines.first())
    }

    @Test
    fun linesWithoutNeuralFieldsReadAsBefore() {
        val w = oneLine()
        assertEquals(0f, w.config.rerankBeta)
        assertNull(w.config.rerankModel)
        assertNull(w.ab)
        val text = SwipeTrace.encode(w)
        assertFalse(text.contains("\"rerank\""))
        assertFalse(text.contains("\"ab\""))
    }

    @Test
    fun rerankAndComparisonRoundTrip() {
        val base = oneLine()
        val ab = SwipeTrace.AB(
            0.05f, "futo_v1",
            listOf(SwipeTrace.Candidate("the", 1.5f, "en"), SwipeTrace.Candidate("they", 0.5f, "en")),
            listOf(SwipeTrace.Candidate("they", 1.25f, "en")),
            3.5f, 21.25f,
        )
        val w = base.copy(
            config = base.config.copy(rerankBeta = 0.05f, rerankModel = "futo_v1"),
            target = "they",
            ab = ab,
        )
        val back = SwipeTrace.decode(SwipeTrace.encode(w))
        assertEquals(0.05f, back.config.rerankBeta)
        assertEquals("futo_v1", back.config.rerankModel)
        assertEquals(ab, back.ab)
        assertFalse("a reranked live list cannot replay without the model", back.comparable)
        assertTrue(base.comparable || base.config.personal)
    }
}
