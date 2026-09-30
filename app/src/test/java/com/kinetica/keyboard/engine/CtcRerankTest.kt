package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.WordCandidate
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class CtcRerankTest {

    /** A KCTC v1 file with small random weights, laid out as swipe_ctc.py's export() writes it. */
    private fun randomModel(seed: Long, width: Int = 8, hidden: Int = 6): ByteArray {
        val rnd = Random(seed)
        val out = ByteArrayOutputStream()
        fun int(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun float(v: Float) = int(java.lang.Float.floatToRawIntBits(v))
        fun tensor(vararg dims: Int) {
            int(dims.size)
            for (d in dims) int(d)
            repeat(dims.fold(1) { a, b -> a * b }) { float((rnd.nextGaussian() * 0.3).toFloat()) }
        }
        out.write("KCTC".toByteArray())
        int(1)
        int(14)
        float(0.5f)
        tensor(width, 28, 5); tensor(width)
        tensor(width, width, 5); tensor(width)
        repeat(2) { tensor(3 * hidden, width); tensor(3 * hidden, hidden); tensor(3 * hidden); tensor(3 * hidden) }
        tensor(28, 2 * hidden); tensor(28)
        return out.toByteArray()
    }

    private data class Dict(val trie: Trie, val forms: Map<Int, List<WordForm>>, val bigrams: BigramTable)

    private val en: Dict? by lazy {
        val dir = Paths.get("src/main/assets/dictionaries")
        if (!Files.exists(dir.resolve("en_wordlist.txt"))) return@lazy null
        val d = Files.newBufferedReader(dir.resolve("en_wordlist.txt")).use { DictionaryLoader.load(it) }
        val b = Files.newBufferedReader(dir.resolve("en_bigrams.txt")).use { DictionaryLoader.loadBigrams(it, d.trie) }
        Dict(d.trie, d.forms, b)
    }

    private fun scorer(seed: Long = 1) = CtcScorer.load(ByteArrayInputStream(randomModel(seed)))

    @Test
    fun logProbsAreNormalised() {
        val g = TestData.qwertyGeometry()
        val tok = TestData.swipe("hello", g, 1000, 400)
        val lp = scorer().logProbs(tok.resampled, g)
        for (t in 0 until KineticaConstants.RESAMPLE_N) {
            var s = 0.0
            for (c in 0 until CtcScorer.CLASSES) s += exp(lp[t * CtcScorer.CLASSES + c].toDouble())
            assertEquals(1.0, s, 1e-4)
        }
    }

    /** The forward pass against brute force over every alignment of a tiny case. */
    @Test
    fun nllMatchesBruteForce() {
        val frames = 4
        val classes = CtcScorer.CLASSES
        val rnd = Random(3)
        val lp = FloatArray(frames * classes)
        for (t in 0 until frames) {
            val raw = DoubleArray(classes) { rnd.nextGaussian() }
            val lse = ln(raw.sumOf { exp(it) })
            for (c in 0 until classes) lp[t * classes + c] = (raw[c] - lse).toFloat()
        }
        val labels = intArrayOf(2, 2) // a doubled letter needs a blank between
        var total = 0.0
        val used = intArrayOf(0, 1, 2)
        fun collapse(path: IntArray): List<Int> {
            val out = ArrayList<Int>()
            var prev = -1
            for (p in path) { if (p != prev && p != 0) out.add(p); prev = p }
            return out
        }
        val path = IntArray(frames)
        fun walk(t: Int) {
            if (t == frames) {
                if (collapse(path) == labels.toList()) {
                    total += exp((0 until frames).sumOf { lp[it * classes + path[it]].toDouble() })
                }
                return
            }
            for (c in used) { path[t] = c; walk(t + 1) }
        }
        walk(0)
        val expected = -ln(total)
        assertEquals(expected, CtcScorer.nll(lp, frames, labels).toDouble(), 1e-4)
    }

    @Test
    fun labelLongerThanFramesIsImpossible() {
        val lp = FloatArray(2 * CtcScorer.CLASSES) { -ln(CtcScorer.CLASSES.toFloat()) }
        assertTrue(CtcScorer.nll(lp, 2, intArrayOf(1, 1)).isInfinite())
    }

    /**
     * Beta 0 is the shipping decode: the reranker hands back the list it was
     * given, and a predictor carrying it at TOP_K depth ranks exactly as today.
     */
    @Test
    fun betaZeroIsIdentity() {
        val g = TestData.qwertyGeometry()
        val dict = en
        assumeTrue(dict != null)
        val (trie, forms, bigrams) = dict!!
        val base = WordPredictor(trie, bigrams, g, forms)
        val reranker = CtcReranker(scorer(), { g }, beta = 0f)
        val dark = WordPredictor(trie, bigrams, g, forms, reranker = reranker)
        for (w in listOf("hello", "because", "world", "thanks", "keyboard")) {
            val tokens = listOf(TestData.sloppySwipe(w, g, 1000, 500, 0.3f))
            val a = base.decode(tokens, listOf("the"))
            val b = dark.decode(tokens, listOf("the"))
            assertEquals(a.map { it.word }, b.map { it.word })
            assertEquals(a.map { it.score.toRawBits() }, b.map { it.score.toRawBits() })
            assertSame(b, reranker.rerank(tokens, b))
        }
    }

    @Test
    fun rerankedCandidatesCarryTheirPieces() {
        val g = TestData.qwertyGeometry()
        val dict = en
        assumeTrue(dict != null)
        val (trie, forms, bigrams) = dict!!
        val r = CtcReranker(scorer(), { g }, beta = 0.3f)
        val p = WordPredictor(trie, bigrams, g, forms, reranker = r, rerankDepth = 30)
        val left = TestData.sloppySwipe("hel", g, 1000, 300, 0.3f, StreamId.LEFT)
        val right = TestData.swipe("lo", g, 1200, 200, StreamId.RIGHT)
        val out = p.decode(listOf(left, right), emptyList())
        assertTrue(out.isNotEmpty() && out.size <= KineticaConstants.TOP_K)
        val seg = out.first().segmentation
        assertNotNull(seg)
        for (piece in seg!!.pieces) assertTrue(piece.to > piece.from)
        val costs = out.mapNotNull { r.cost(it, g, java.util.IdentityHashMap()) }
        assertTrue(costs.all { it >= 0f && abs(it) < 1e6f })
        // Rescored list is sorted by the new score.
        assertEquals(out.map { it.score }.sortedDescending(), out.map { it.score })
    }

    /**
     * A deep heap prunes at TOP_K, so the search is today's: a reranker that
     * reorders nothing hands back exactly the shipping list, however deep it
     * asked the heap to go. Pruning at the deep rank lost words the top 10 finds.
     */
    @Test
    fun deepHeapKeepsTheShippingSearch() {
        val g = TestData.qwertyGeometry()
        val dict = en
        assumeTrue(dict != null)
        val (trie, forms, bigrams) = dict!!
        val base = WordPredictor(trie, bigrams, g, forms)
        var depthSeen = 0
        val keep = CandidateReranker { _, c -> depthSeen = maxOf(depthSeen, c.size); c }
        val deep = WordPredictor(trie, bigrams, g, forms, reranker = keep, rerankDepth = 50)
        for (w in listOf("hello", "because", "world", "thanks", "keyboard", "pants", "cross", "avoid")) {
            val tokens = listOf(TestData.sloppySwipe(w, g, 1000, 500, 0.3f))
            val a = base.decode(tokens, listOf("the"))
            val b = deep.decode(tokens, listOf("the"))
            // Same scores, and the same words wherever a score is not tied: each heap
            // keeps equal scores in its own slot order, so a tie may swap, or
            // swap across the cut at the last place.
            assertEquals(a.map { it.score.toRawBits() }, b.map { it.score.toRawBits() })
            val tied = (a + b).groupBy { it.score.toRawBits() }.filterValues { it.size > 2 }.keys + a.last().score.toRawBits()
            fun untied(l: List<WordCandidate>) = l.filter { it.score.toRawBits() !in tied }.map { it.word }
            assertEquals(untied(a), untied(b))
        }
        assertTrue(depthSeen > KineticaConstants.TOP_K)
    }

    @Test
    fun repeatedDecodesScoreTheSame() {
        val g = TestData.qwertyGeometry()
        val dict = en
        assumeTrue(dict != null)
        val (trie, forms, bigrams) = dict!!
        val p = WordPredictor(trie, bigrams, g, forms, reranker = CtcReranker(scorer(), { g }, 0.3f), rerankDepth = 30)
        val tokens = listOf(TestData.sloppySwipe("because", g, 1000, 500, 0.3f))
        val first = p.decode(tokens, emptyList())
        val again = p.decode(tokens, emptyList())
        assertEquals(first.map { it.word }, again.map { it.word })
        assertEquals(first.map { it.score.toRawBits() }, again.map { it.score.toRawBits() })
    }

    @Test
    fun trainedModelLoadsWhenPresent() {
        val path = System.getProperty("kinetica.ctc.model") ?: return
        assumeTrue(Files.exists(Paths.get(path)))
        val s = Files.newInputStream(Paths.get(path)).use { CtcScorer.load(it) }
        val g = TestData.qwertyGeometry()
        assertEquals(KineticaConstants.RESAMPLE_N * CtcScorer.CLASSES, s.logProbs(TestData.swipe("hello", g, 0, 300).resampled, g).size)
    }
}
