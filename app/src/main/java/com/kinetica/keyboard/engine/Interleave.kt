package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.SwipeToken
import com.kinetica.keyboard.engine.models.TapToken
import kotlin.math.sqrt

/**
 * Two-thumb input read as ONE timeline: the model behind [InterleavedSearch].
 *
 * Nintype-style typing runs both thumbs at once. Each thumb swipes through its
 * own letters, and the word's letter order is the order in which EITHER thumb
 * reached its keys - "identify" is the right thumb on i..n..i while the left
 * goes d-e..t..f-y, the two strokes overlapping almost end to end. Sorting
 * tokens by start time and cutting them at a few guessed points
 * ([MergeAlternatives]) cannot recover that order once the letters alternate
 * more than a couple of times; reading positions against time can.
 *
 * Every token (a swipe, or a tap as a still stroke) is a stroke. A reading of a
 * word assigns each letter to one stroke at one moment, such that:
 *  - moments do not go backwards along the word, beyond [ORDER_SLACK_MS] and
 *    then only at [ORDER_PENALTY_PER_MS] - thumbs are not perfectly in step,
 *    and a lift a little before the other thumb's last key is common;
 *  - each stroke's first letter is at its touch-down and its last at its lift
 *    (the ends of a stroke are the most deliberate points of it);
 *  - every stroke carries at least one letter.
 * The cost of a reading is the distance from the visiting thumb to each
 * letter's key centre, kw; a word's cost is the best reading's mean per key
 * visit. A doubled letter is one visit.
 *
 * The dynamic programme runs letter by letter, so the search can extend it one
 * trie edge at a time ([step]). A row is indexed by (stroke states, moment):
 * each stroke is unused, open or closed, base 3, and the moment is the grid
 * index of the latest letter.
 */
class Interleave private constructor(
    val strokes: Int,
    private val times: LongArray,
    private val px: FloatArray,
    private val py: FloatArray,
    private val start: IntArray,
    private val end: IntArray,
    private val geometry: KeyboardGeometry,
) {
    val grid: Int = times.size
    val masks: Int = pow3(strokes)
    val rowSize: Int = masks * grid
    private val allClosed = masks - 1

    // Per grid index: the last index within the order slack, for the free part
    // of the reach, and the penalty's time term.
    private val hi = IntArray(grid)
    private val scratchA = FloatArray(grid)
    private val scratchB = FloatArray(grid)
    // Thumb-to-key distance per (letter, stroke, moment), filled the first time
    // a letter is stepped: the search steps the same 26 letters thousands of times.
    private val dist = FloatArray(Alphabet.LETTERS * strokes * grid)
    private val distReady = BooleanArray(Alphabet.LETTERS)

    init {
        var j = 0
        for (i in 0 until grid) {
            while (j + 1 < grid && times[j + 1] <= times[i] + ORDER_SLACK_MS) j++
            hi[i] = j
        }
    }

    /** The lowest cost [step] wrote into its last row (INF when it returned false). */
    var lastMin: Float = INF
        private set

    /** The row before any letter: nothing assigned, no moment yet. */
    fun initialRow(): FloatArray = FloatArray(rowSize) { INF }.also { it[0] = 0f }

    /**
     * Extends [prev] by the letter [code] into [out]; returns false when no
     * reading survives. [first] marks the word's first letter, which has no
     * moment to follow.
     */
    fun step(prev: FloatArray, code: Int, first: Boolean, out: FloatArray): Boolean {
        java.util.Arrays.fill(out, INF)
        lastMin = INF
        if (!geometry.hasKey(code)) return false
        val dOff = code * strokes * grid
        if (!distReady[code]) {
            fillDist(code, dOff)
            distReady[code] = true
        }
        var any = false
        for (m in 0 until masks) {
            val base = m * grid
            // reach[i]: best previous reading that letter at moment i may follow.
            val reach = scratchA
            if (first) {
                if (m != 0) continue
                java.util.Arrays.fill(reach, 0f)
            } else {
                var live = false
                for (i in 0 until grid) if (prev[base + i] < INF) { live = true; break }
                if (!live) continue
                reachOf(prev, base, reach)
            }
            var p = 1
            for (s in 0 until strokes) {
                val state = (m / p) % 3
                val dBase = dOff + s * grid
                when (state) {
                    0 -> {
                        val g0 = start[s]
                        val g1 = end[s]
                        val r = reach[g0]
                        if (r < INF) {
                            // Opened at touch-down.
                            val v = r + dist[dBase + g0]
                            val o = (m + p) * grid + g0
                            if (v < out[o]) { out[o] = v; any = any || v < INF }
                            // A one-letter stroke: down and lift on the same key.
                            val v2 = r + 0.5f * (dist[dBase + g0] + dist[dBase + g1])
                            val o2 = (m + 2 * p) * grid + g1
                            if (v2 < out[o2]) { out[o2] = v2; any = any || v2 < INF }
                        }
                    }
                    1 -> {
                        val g0 = start[s]
                        val g1 = end[s]
                        for (i in g0 + 1..g1) {
                            val r = reach[i]
                            if (r >= INF) continue
                            val v = r + dist[dBase + i]
                            if (v >= INF) continue
                            if (i == g1) {
                                // Closed at the lift.
                                val o = (m + p) * grid + i
                                if (v < out[o]) out[o] = v
                            } else {
                                val o = base + i
                                if (v < out[o]) out[o] = v
                            }
                            any = true
                        }
                    }
                }
                p *= 3
            }
        }
        if (any) for (v in out) if (v < lastMin) lastMin = v
        return any
    }

    /** Mean cost per key visit of the best complete reading in [row], or INF. */
    fun finalCost(row: FloatArray, visits: Int): Float {
        var best = INF
        val base = allClosed * grid
        for (i in 0 until grid) if (row[base + i] < best) best = row[base + i]
        return if (best >= INF || visits == 0) INF else best / visits
    }

    /** Whether any reading in [row] is still alive. */
    fun alive(row: FloatArray): Boolean {
        for (v in row) if (v < INF) return true
        return false
    }

    /** Cost of a whole word (lowercase a-z letters; others make it INF). */
    fun cost(word: String): Float {
        var row = initialRow()
        var next = FloatArray(rowSize)
        var visits = 0
        var prevCode = -1
        for (ch in word) {
            val code = Alphabet.codeOf(ch)
            if (code < 0) return INF
            if (code == prevCode || code == Alphabet.APOSTROPHE) continue
            if (!step(row, code, visits == 0, next)) return INF
            val t = row; row = next; next = t
            visits++
            prevCode = code
        }
        return finalCost(row, visits)
    }

    private fun fillDist(code: Int, off: Int) {
        val cx = geometry.centerX(code)
        val cy = geometry.centerY(code)
        for (k in 0 until strokes * grid) {
            val x = px[k]
            dist[off + k] = if (x.isNaN()) INF else {
                val dx = x - cx
                val dy = py[k] - cy
                val d = sqrt(dx * dx + dy * dy)
                if (d > MAX_LETTER_KW) INF else d
            }
        }
    }

    private fun reachOf(prev: FloatArray, base: Int, reach: FloatArray) {
        // Free part: any earlier moment up to the slack, a running minimum.
        val fwd = scratchB
        var run = INF
        for (i in 0 until grid) {
            val v = prev[base + i]
            if (v < run) run = v
            fwd[i] = run
        }
        // Penalised part: moments past the slack, min over j > hi[i] of
        // prev[j] + lambda * (t[j] - t[i] - slack), a suffix minimum of
        // prev[j] + lambda * t[j] shifted by the letter's own time.
        val suffix = reach
        var s = INF
        for (j in grid - 1 downTo 0) {
            val v = prev[base + j]
            if (v < INF) {
                val w = v + ORDER_PENALTY_PER_MS * (times[j] - times[0])
                if (w < s) s = w
            }
            suffix[j] = s
        }
        for (i in 0 until grid) {
            val free = fwd[hi[i]]
            val j = hi[i] + 1
            val pen = if (j < grid && suffix[j] < INF) {
                suffix[j] - ORDER_PENALTY_PER_MS * (times[i] - times[0] + ORDER_SLACK_MS)
            } else {
                INF
            }
            reach[i] = if (free < pen) free else pen
        }
    }

    companion object {
        const val INF = Float.POSITIVE_INFINITY

        /** More strokes than this and the state space (3^n) is not worth it. */
        const val MAX_STROKES = 4

        /** Timeline resolution: union of sample times, thinned to this many. */
        const val GRID_MAX = 96

        /** Two thumbs are this far out of step for free. */
        const val ORDER_SLACK_MS = 60L

        /** Beyond the slack, kw of cost per ms a letter comes before its predecessor. */
        const val ORDER_PENALTY_PER_MS = 0.005f

        /** A key further than this from the thumb at its moment was not visited. */
        const val MAX_LETTER_KW = 1.6f

        /**
         * The interleaved reading of [tokens], or null when the input is not
         * two-thumb overlapped: fewer than two tokens, more than [MAX_STROKES],
         * or no two SWIPES from different thumbs overlapping in time. A tap held
         * during a swipe is the pecking case the regular search already reads
         * (MergeAlternatives' tap insertion), and reading it here too crowds the
         * merged list - the Italian "sarei" gesture in LanguageDetectGoldenTest.
         */
        fun of(tokens: List<InputToken>, g: KeyboardGeometry): Interleave? {
            if (tokens.size < 2 || tokens.size > MAX_STROKES) return null
            if (tokens.none { it is SwipeToken }) return null
            var overlap = false
            for (a in tokens) for (b in tokens) {
                if (a !== b && a is SwipeToken && b is SwipeToken && a.streamId != b.streamId &&
                    a.tStart < b.tEnd && b.tStart < a.tEnd
                ) overlap = true
            }
            if (!overlap) return null
            val strokes = tokens.sortedBy { it.tStart }
            val paths = strokes.map { t ->
                when (t) {
                    is SwipeToken -> t.rawPath.map { Triple(it.x, it.y, it.t) }
                    is TapToken -> listOf(Triple(t.x, t.y, t.tStart), Triple(t.x, t.y, maxOf(t.tEnd, t.tStart + 1)))
                }
            }
            if (paths.any { it.isEmpty() }) return null
            val all = paths.flatMap { p -> p.map { it.third } }.distinct().sorted()
            val ends = paths.flatMap { listOf(it.first().third, it.last().third) }.toSet()
            val times = if (all.size <= GRID_MAX) all else {
                val keep = (0 until GRID_MAX).map { all[(it.toLong() * (all.size - 1) / (GRID_MAX - 1)).toInt()] }
                (keep + ends).distinct().sorted()
            }.toLongArray()
            val n = strokes.size
            val grid = times.size
            val px = FloatArray(n * grid) { Float.NaN }
            val py = FloatArray(n * grid) { Float.NaN }
            val start = IntArray(n)
            val end = IntArray(n)
            for ((s, p) in paths.withIndex()) {
                val t0 = p.first().third
                val t1 = p.last().third
                start[s] = times.indexOfFirst { it >= t0 }
                end[s] = times.indexOfLast { it <= t1 }
                var k = 0
                for (i in 0 until grid) {
                    val t = times[i]
                    if (t < t0 || t > t1) continue
                    while (k + 1 < p.size && p[k + 1].third <= t) k++
                    val a = p[k]
                    val b = if (k + 1 < p.size) p[k + 1] else a
                    val f = if (b.third > a.third) (t - a.third).toFloat() / (b.third - a.third) else 0f
                    px[s * grid + i] = a.first + f * (b.first - a.first)
                    py[s * grid + i] = a.second + f * (b.second - a.second)
                }
                if (start[s] < 0 || end[s] < start[s]) return null
            }
            return Interleave(n, times, px, py, start, end, g)
        }

        private fun pow3(n: Int): Int {
            var r = 1
            repeat(n) { r *= 3 }
            return r
        }
    }
}
