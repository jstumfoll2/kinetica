package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.WordCandidate
import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.IdentityHashMap
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.tanh

/**
 * A small CTC swipe encoder, run from scratch in plain Kotlin: two 1-D
 * convolutions, one bidirectional GRU, a linear layer to blank + a..z +
 * apostrophe. Trained by tools/experiment/swipe_ctc.py, which defines the same
 * graph and writes the "KCTC" weight file read here.
 *
 * Input is one swipe piece's resampled path (RESAMPLE_N points, kw). Each point
 * becomes a gaussian proximity to every letter key centre plus its step from
 * the previous point, so the encoder sees letters, not coordinates, and any
 * layout's geometry works without retraining.
 *
 * Not thread-safe: scratch buffers are reused. One instance per decode thread.
 */
class CtcScorer private constructor(
    private val sigmaKw: Float,
    private val conv1W: FloatArray, private val conv1B: FloatArray,
    private val conv2W: FloatArray, private val conv2B: FloatArray,
    private val gru: Array<FloatArray>,      // ih, hh, bih, bhh, then the reverse direction
    private val outW: FloatArray, private val outB: FloatArray,
    private val width: Int,
    private val hidden: Int,
) {
    private val n = KineticaConstants.RESAMPLE_N
    private val x = FloatArray(n * IN_DIM)
    private val h1 = FloatArray(n * width)
    private val h2 = FloatArray(n * width)
    private val hs = FloatArray(n * 2 * hidden)
    private val gates = FloatArray(3 * hidden)
    private val gatesH = FloatArray(3 * hidden)
    private val hPrev = FloatArray(hidden)

    /** Log-probabilities, [RESAMPLE_N * CLASSES], for one resampled piece on [g]. */
    fun logProbs(resampled: FloatArray, g: KeyboardGeometry): FloatArray {
        features(resampled, g)
        conv(x, IN_DIM, conv1W, conv1B, h1)
        conv(h1, width, conv2W, conv2B, h2)
        gruPass(forward = true)
        gruPass(forward = false)
        val out = FloatArray(n * CLASSES)
        for (t in 0 until n) {
            var mx = Float.NEGATIVE_INFINITY
            for (c in 0 until CLASSES) {
                var s = outB[c]
                val wRow = c * 2 * hidden
                val hRow = t * 2 * hidden
                for (k in 0 until 2 * hidden) s += outW[wRow + k] * hs[hRow + k]
                out[t * CLASSES + c] = s
                if (s > mx) mx = s
            }
            var sum = 0.0
            for (c in 0 until CLASSES) sum += exp((out[t * CLASSES + c] - mx).toDouble())
            val lse = mx + ln(sum).toFloat()
            for (c in 0 until CLASSES) out[t * CLASSES + c] -= lse
        }
        return out
    }

    private fun features(p: FloatArray, g: KeyboardGeometry) {
        val inv = 1f / (2f * sigmaKw * sigmaKw)
        for (t in 0 until n) {
            val px = p[2 * t]
            val py = p[2 * t + 1]
            for (k in 0 until Alphabet.LETTERS) {
                x[t * IN_DIM + k] = if (g.hasKey(k)) {
                    val dx = px - g.centerX(k)
                    val dy = py - g.centerY(k)
                    exp(-(dx * dx + dy * dy) * inv)
                } else {
                    0f
                }
            }
            x[t * IN_DIM + 26] = if (t == 0) 0f else px - p[2 * (t - 1)]
            x[t * IN_DIM + 27] = if (t == 0) 0f else py - p[2 * (t - 1) + 1]
        }
    }

    /** Conv1d, kernel 5, padding 2, then ReLU. [inp] is time-major [n * cin]; weight [cout, cin, 5]. */
    private fun conv(inp: FloatArray, cin: Int, w: FloatArray, b: FloatArray, out: FloatArray) {
        for (t in 0 until n) for (o in 0 until width) {
            var s = b[o]
            for (k in 0 until KERNEL) {
                val ti = t + k - KERNEL / 2
                if (ti < 0 || ti >= n) continue
                val wBase = (o * cin) * KERNEL + k
                val iBase = ti * cin
                for (c in 0 until cin) s += w[wBase + c * KERNEL] * inp[iBase + c]
            }
            out[t * width + o] = max(s, 0f)
        }
    }

    /** One direction of PyTorch's GRU (gate order r, z, n) into its half of [hs]. */
    private fun gruPass(forward: Boolean) {
        val base = if (forward) 0 else 4
        val wih = gru[base]
        val whh = gru[base + 1]
        val bih = gru[base + 2]
        val bhh = gru[base + 3]
        hPrev.fill(0f)
        val off = if (forward) 0 else hidden
        for (step in 0 until n) {
            val t = if (forward) step else n - 1 - step
            for (r in 0 until 3 * hidden) {
                var a = bih[r]
                for (c in 0 until width) a += wih[r * width + c] * h2[t * width + c]
                gates[r] = a
                var b = bhh[r]
                for (c in 0 until hidden) b += whh[r * hidden + c] * hPrev[c]
                gatesH[r] = b
            }
            for (j in 0 until hidden) {
                val rg = sigmoid(gates[j] + gatesH[j])
                val zg = sigmoid(gates[hidden + j] + gatesH[hidden + j])
                val ng = tanh(gates[2 * hidden + j] + rg * gatesH[2 * hidden + j])
                val h = (1f - zg) * ng + zg * hPrev[j]
                hs[t * 2 * hidden + off + j] = h
            }
            for (j in 0 until hidden) hPrev[j] = hs[t * 2 * hidden + off + j]
        }
    }

    companion object {
        const val CLASSES = 28
        private const val IN_DIM = 28
        private const val KERNEL = 5

        private fun sigmoid(v: Float): Float = 1f / (1f + exp(-v))

        /** CTC class of a letter code: a..z are 1..26, the apostrophe 27. */
        fun classOf(code: Int): Int = if (code == Alphabet.APOSTROPHE) 27 else code + 1

        /**
         * Negative log-likelihood of [labels] (CTC classes, no blanks) under
         * [lp] ([frames] x [CLASSES] log-probabilities): the CTC forward pass.
         * +Infinity when the label cannot fit in the frames.
         */
        fun nll(lp: FloatArray, frames: Int, labels: IntArray): Float {
            val s = 2 * labels.size + 1
            var alpha = DoubleArray(s) { Double.NEGATIVE_INFINITY }
            var next = DoubleArray(s)
            fun ext(i: Int) = if (i % 2 == 0) 0 else labels[i / 2]
            alpha[0] = lp[0].toDouble()
            if (s > 1) alpha[1] = lp[ext(1)].toDouble()
            for (t in 1 until frames) {
                for (i in 0 until s) {
                    var a = alpha[i]
                    if (i >= 1) a = logAdd(a, alpha[i - 1])
                    if (i >= 2 && i % 2 == 1 && ext(i) != ext(i - 2)) a = logAdd(a, alpha[i - 2])
                    next[i] = a + lp[t * CLASSES + ext(i)]
                }
                val tmp = alpha; alpha = next; next = tmp
            }
            val end = if (s > 1) logAdd(alpha[s - 1], alpha[s - 2]) else alpha[s - 1]
            return if (end == Double.NEGATIVE_INFINITY) Float.POSITIVE_INFINITY else (-end).toFloat()
        }

        private fun logAdd(a: Double, b: Double): Double {
            if (a == Double.NEGATIVE_INFINITY) return b
            if (b == Double.NEGATIVE_INFINITY) return a
            val m = maxOf(a, b)
            return m + ln(exp(a - m) + exp(b - m))
        }

        /** Reads a "KCTC" v1 file as written by swipe_ctc.py's export(). */
        fun load(input: InputStream): CtcScorer {
            val d = DataInputStream(input.buffered())
            val magic = ByteArray(4).also { d.readFully(it) }
            require(String(magic, Charsets.US_ASCII) == "KCTC") { "not a KCTC file" }
            fun int(): Int = ByteBuffer.wrap(ByteArray(4).also { d.readFully(it) }).order(ByteOrder.LITTLE_ENDIAN).int
            fun float(): Float = java.lang.Float.intBitsToFloat(int())
            val version = int()
            require(version == 1) { "unsupported KCTC version $version" }
            val count = int()
            require(count == 14) { "expected 14 tensors, found $count" }
            val sigma = float()
            val shapes = ArrayList<IntArray>(count)
            val t = Array(count) {
                val nd = int()
                val dims = IntArray(nd) { int() }
                shapes.add(dims)
                val size = dims.fold(1) { a, b -> a * b }
                val bytes = ByteArray(4 * size).also { d.readFully(it) }
                val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                FloatArray(size).also { fb.get(it) }
            }
            val width = shapes[0][0]
            val hidden = shapes[5][1]
            require(shapes[0][1] == IN_DIM && shapes[0][2] == KERNEL) { "conv1 shape ${shapes[0].toList()}" }
            require(shapes[12][0] == CLASSES && shapes[12][1] == 2 * hidden) { "output shape ${shapes[12].toList()}" }
            return CtcScorer(
                sigma, t[0], t[1], t[2], t[3],
                arrayOf(t[4], t[5], t[6], t[7], t[8], t[9], t[10], t[11]),
                t[12], t[13], width, hidden,
            )
        }
    }
}

/**
 * Stage A: `score x exp(-beta x cost)`, where cost is the CTC negative
 * log-likelihood of each swipe piece's letters under the encoder, averaged per
 * letter so long and short words pay on one scale. Tap-anchored letters carry no
 * cost; a candidate with no swipe piece is left as it is.
 *
 * At [beta] = 0 the list comes back untouched, the same objects in the same
 * order, which is what makes the term safe to ship dark.
 */
class CtcReranker(
    private val scorer: CtcScorer,
    private val geometry: () -> KeyboardGeometry?,
    var beta: Float,
) : CandidateReranker {

    override fun rerank(tokens: List<InputToken>, candidates: List<WordCandidate>): List<WordCandidate> {
        if (beta == 0f || candidates.isEmpty()) return candidates
        val g = geometry() ?: return candidates
        if (g !== cacheGeometry) {
            encoded.clear()
            cacheGeometry = g
        }
        val cache = IdentityHashMap<FloatArray, FloatArray>()
        val rescored = candidates.map { c ->
            val cost = cost(c, g, cache)
            if (cost == null) c else c.copy(score = c.score * exp(-beta * cost))
        }
        return rescored.sortedByDescending { it.score }
    }

    /** Mean per-letter CTC cost over [c]'s swipe pieces, or null when it has none. */
    fun cost(c: WordCandidate, g: KeyboardGeometry, cache: IdentityHashMap<FloatArray, FloatArray>): Float? {
        val seg = c.segmentation ?: return null
        var total = 0f
        var letters = 0
        for (p in seg.pieces) {
            val spelled = p.letters ?: seg.letters
            val labels = (p.from until p.to).map { spelled[it] }
                .filter { it == Alphabet.APOSTROPHE || g.hasKey(it) }
                .map { CtcScorer.classOf(it) }.toIntArray()
            if (labels.isEmpty()) continue
            val lp = cache.getOrPut(p.resampled) { encode(p.resampled, g) }
            val nll = CtcScorer.nll(lp, KineticaConstants.RESAMPLE_N, labels)
            // A label longer than the frames can hold is as bad as it gets, not free.
            total += if (nll.isInfinite()) MAX_LETTER_COST * labels.size else nll
            letters += labels.size
        }
        return if (letters == 0) null else total / letters
    }

    /** A piece's samples compared by content: each decode rebuilds its pieces, the paths repeat. */
    private class PathKey(val xy: FloatArray) {
        private val hash = xy.contentHashCode()
        override fun hashCode() = hash
        override fun equals(other: Any?) = other is PathKey && xy.contentEquals(other.xy)
    }

    // The composer re-decodes the whole buffer after every token, so without this
    // every swipe is encoded again on every later token of its word.
    private val encoded = object : LinkedHashMap<PathKey, FloatArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PathKey, FloatArray>) = size > ENCODED_MAX
    }
    private var cacheGeometry: KeyboardGeometry? = null

    private fun encode(xy: FloatArray, g: KeyboardGeometry): FloatArray =
        synchronized(encoded) {
            encoded[PathKey(xy)] ?: scorer.logProbs(xy, g).also { encoded[PathKey(xy.copyOf())] = it }
        }

    private companion object {
        /** Per-letter cost charged to a piece CTC cannot align at all. */
        const val MAX_LETTER_COST = 20f

        /** Distinct piece paths kept encoded: a long buffer's pieces and cuts fit many times over. */
        const val ENCODED_MAX = 256
    }
}
