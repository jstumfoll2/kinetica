package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.SwipeToken
import com.kinetica.keyboard.engine.models.WordCandidate

/**
 * A short hold on a key mid-swipe, read as a doubled letter.
 *
 * A swipe passes a doubled letter's key once, so `hoping` and `hopping`, `holy` and `holly` draw
 * the same path and frequency decides. Some people pause on the key to mark the double. In the
 * owner's traces the thumb stayed within [KineticaConstants.DOUBLE_HOLD_RADIUS_KW] for
 * [KineticaConstants.DOUBLE_HOLD_MS] on a doubled letter's key in 38% of swipes, on any other
 * inner letter's key in 5%.
 *
 * Only as a tie-break between readings that differ in doubling: when the top reading lacks an
 * inner double the thumb held and a listed reading with the same letters has it, that reading
 * goes first. Nothing else moves, and a word's first and last letters are left out, where every
 * swipe slows to start and lift.
 */
object HeldLetters {

    /** Letters whose key a [SwipeToken] in [tokens] rested on mid-gesture. */
    fun held(tokens: List<InputToken>, g: KeyboardGeometry): Set<Char> {
        val out = HashSet<Char>()
        for (t in tokens) {
            if (t !is SwipeToken) continue
            val path = t.rawPath
            if (path.size < 3) continue
            val t0 = path.first().t
            val t1 = path.last().t
            var anchor = -1
            for (i in path.indices) {
                val p = path[i]
                val inner = p.t - t0 > KineticaConstants.DOUBLE_HOLD_EDGE_MS &&
                    t1 - p.t > KineticaConstants.DOUBLE_HOLD_EDGE_MS
                val key = if (inner) g.keyAt(p.x, p.y) else -1
                if (key < 0) {
                    anchor = -1
                    continue
                }
                val a = if (anchor >= 0) path[anchor] else null
                val r = KineticaConstants.DOUBLE_HOLD_RADIUS_KW
                if (a == null || g.keyAt(a.x, a.y) != key ||
                    (p.x - a.x) * (p.x - a.x) + (p.y - a.y) * (p.y - a.y) > r * r
                ) {
                    anchor = i
                    continue
                }
                if (p.t - a.t >= KineticaConstants.DOUBLE_HOLD_MS) out.add(g.alphabet.charOf(key))
            }
        }
        return out
    }

    /** [list] with a doubled reading of its head moved to the front when [held] says so. */
    fun preferHeldDouble(list: List<WordCandidate>, held: Set<Char>): List<WordCandidate> {
        if (held.isEmpty() || list.size < 2) return list
        val top = list[0]
        val topKey = AccentFolder.fold(top.word.lowercase())
        val topDoubles = innerDoubles(topKey)
        if (topDoubles.any { it in held }) return list
        for (i in 1 until list.size) {
            val c = list[i]
            val key = AccentFolder.fold(c.word.lowercase())
            if (collapsed(key) != collapsed(topKey) || TRIPLE.containsMatchIn(key)) continue
            val gained = innerDoubles(key) - topDoubles
            if (gained.none { it in held }) continue
            val out = ArrayList<WordCandidate>(list.size)
            out.add(c.copy(score = top.score))
            for ((j, w) in list.withIndex()) if (j != i) out.add(w)
            return out
        }
        return list
    }

    /** Letters doubled inside [w], not at its first or last letter. */
    internal fun innerDoubles(w: String): Set<Char> {
        val out = HashSet<Char>()
        for (i in 1 until w.length - 2) if (w[i] == w[i + 1]) out.add(w[i])
        return out
    }

    private val RUNS = Regex("(.)\\1+")
    private val TRIPLE = Regex("(.)\\1\\1")

    private fun collapsed(w: String): String = RUNS.replace(w, "$1")
}
