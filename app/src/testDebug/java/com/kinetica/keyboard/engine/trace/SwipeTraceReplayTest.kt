package com.kinetica.keyboard.engine.trace

import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.SwipeToken
import com.kinetica.keyboard.engine.models.TapToken
import com.kinetica.keyboard.engine.models.PathPoint
import com.kinetica.keyboard.engine.models.KeyContact
import com.kinetica.keyboard.engine.models.Dwell
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Gate for trace format v1: a decode recorded through the real input path must
 * replay to the identical candidate list, scores bit for bit. This is the
 * property the old DecodeTrace lines lacked (6 of 764 synthetic cases).
 */
class SwipeTraceReplayTest {

    private val assets = File("src/main/assets")

    private fun haveAssets(vararg langs: String) =
        langs.all { File(assets, "dictionaries/${it}_wordlist.txt").exists() }

    @Test
    fun recordedSessionReplaysExactly() {
        assumeTrue(haveAssets("en"))
        val lines = ArrayList<String>()
        val s = SyntheticSession(assets)
        s.record(s.words("en", 220), "en", null) { lines.add(it) }
        val report = replayAll(lines)
        println(report.format())
        assertEquals(0, report.failed)
        assertTrue("no comparable lines", report.comparable > 200)
        assertEquals(report.format(), report.comparable, report.exact)
        // Every shape the doc buckets on was actually exercised.
        for (b in listOf("single-swipe", "two-thumb-overlap", "tap+swipe", "taps", "multi-swipe", "double-letter", "short")) {
            assertTrue("bucket $b empty", report.byBucket.getValue(b).n > 0)
        }
        assertTrue("no abandoned buffer recorded", report.unlabelled > 0)
    }

    @Test
    fun twoLanguageSessionReplaysExactly() {
        assumeTrue(haveAssets("en", "de"))
        val lines = ArrayList<String>()
        val s = SyntheticSession(assets, seed = 11)
        s.record(s.words("en", 60, step = 13), "en", "de") { lines.add(it) }
        val report = replayAll(lines)
        assertEquals(0, report.failed)
        assertEquals(report.format(), report.comparable, report.exact)
    }

    @Test
    fun personalLinesAreScoredButNotCompared() {
        assumeTrue(haveAssets("en"))
        val lines = ArrayList<String>()
        val s = SyntheticSession(assets)
        s.record(s.words("en", 10), "en", null) { lines.add(it) }
        val personal = lines.map {
            val w = SwipeTrace.decode(it)
            SwipeTrace.encode(w.copy(config = w.config.copy(personal = true)))
        }
        val report = replayAll(personal)
        assertEquals(0, report.comparable)
        assertEquals(report.lines - report.unlabelled, report.all.n)
    }

    @Test
    fun linesRoundTripBitForBit() {
        assumeTrue(haveAssets("en"))
        val lines = ArrayList<String>()
        val s = SyntheticSession(assets)
        s.record(s.words("en", 40), "en", null) { lines.add(it) }
        for (l in lines) assertEquals(l, SwipeTrace.encode(SwipeTrace.decode(l)))
    }

    @Test
    fun literalTokensRoundTrip() {
        val g = SwipeTrace.Geometry(108f, 540f, 37.8f, mapOf(0 to floatArrayOf(0.5f, 1.5f, 1.5f, 3f)))
        val swipe = SwipeToken(
            StreamId.RIGHT,
            listOf(PathPoint(0.1f, 0.2f, 5), PathPoint(1.0f / 3f, 2.7182817f, 9)),
            floatArrayOf(0.1f, 1e-7f, -3.4028235e38f),
            listOf(KeyContact(0, 5, 9)), 1.2345679f, 5, 9, true, false, listOf(Dwell(0, 1, 5, 9)),
        )
        val tap = TapToken(StreamId.LEFT, 25, 0.123f, 4.56f, true, 1, 2)
        val w = SwipeTrace.Word(
            SwipeTrace.Config("en", "de", true, false, true), g, listOf("it's", "a \"quote\""),
            listOf(SwipeTrace.Literal(swipe), SwipeTrace.Literal(tap)),
            SwipeTrace.Shown(2, listOf(SwipeTrace.Candidate("naïve", 1.0E-5f, "fr"))), null, "picked",
        )
        val line = SwipeTrace.encode(w)
        val back = SwipeTrace.decode(line)
        assertEquals(line, SwipeTrace.encode(back))
        val s2 = (back.tokens[0] as SwipeTrace.Literal).token as SwipeToken
        assertTrue(s2.resampled.contentEquals(swipe.resampled))
        assertEquals(swipe.rawPath, s2.rawPath)
        assertEquals(tap, (back.tokens[1] as SwipeTrace.Literal).token)
    }

    @Test
    fun correctionsAndPracticeTargetsRelabel() {
        assumeTrue(haveAssets("en"))
        val lines = ArrayList<String>()
        val s = SyntheticSession(assets)
        s.record(listOf("them", "hello"), "en", null) { lines.add(it) }
        val practice = SwipeTrace.decode(lines[1]).let { SwipeTrace.encode(it.copy(target = "jello")) }
        val f = File.createTempFile("trace", ".jsonl")
        try {
            f.writeText(listOf(lines[0], SwipeTrace.encodeCorrection("them", "then"), practice).joinToString("\n"))
            val words = ReplayCli.read(f, ReplayReport(ReplayHarness.DEFAULT_DEEP_K)).map { it.second }
            assertEquals(listOf("then", "jello"), words.map { it.label })
            assertEquals("corrected", words[0].how)
        } finally {
            f.delete()
        }
    }

    @Test
    fun discardDropsTheLastAttempt() {
        assumeTrue(haveAssets("en"))
        val lines = ArrayList<String>()
        SyntheticSession(assets).record(listOf("them", "hello", "world"), "en", null) { lines.add(it) }
        val f = File.createTempFile("trace", ".jsonl")
        try {
            val discard = SwipeTrace.encodeDiscard()
            f.writeText(listOf(lines[0], lines[1], discard, lines[2]).joinToString("\n"))
            val words = ReplayCli.read(f, ReplayReport(ReplayHarness.DEFAULT_DEEP_K)).map { it.second }
            assertEquals(listOf("them", "world"), words.map { it.label })
        } finally {
            f.delete()
        }
    }

    private fun replayAll(lines: List<String>): ReplayReport {
        val h = ReplayHarness(assets)
        val r = ReplayReport(h.deepK)
        for ((i, l) in lines.withIndex()) {
            try {
                r.add(h.replay(SwipeTrace.decode(l)))
            } catch (e: RuntimeException) {
                r.error(i + 1, e)
            }
        }
        return r
    }
}
