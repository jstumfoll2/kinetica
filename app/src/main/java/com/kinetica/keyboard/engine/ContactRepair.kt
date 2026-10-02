package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.KeyContact
import com.kinetica.keyboard.engine.models.PathPoint
import com.kinetica.keyboard.engine.models.SwipeToken
import com.kinetica.keyboard.engine.models.TapToken
import kotlin.math.sqrt

/**
 * Undoes the touchscreen merging two thumbs that came close together.
 *
 * Seen in the developer's practice traces ("sitting", "positive"): with the
 * thumbs about a key apart near the middle of the board, the digitizer reports
 * them as ONE contact for a few milliseconds. Android delivers that as both
 * pointers lifting at the same moment, a one- or two-sample touch at their
 * midpoint, and then two fresh touches where the thumbs already were. The user
 * never lifted; the trail jumps to the centre and back, and the decoder gets
 * five or six tokens for a word the thumbs drew as two.
 *
 * [repair] recognises exactly that shape, drops the midpoint blip, and joins
 * each interrupted stroke to its continuation (the fresh touch nearest to where
 * it stopped). Anything else is returned untouched, so ordinary lifts, taps and
 * double-letter pecks are never rejoined.
 */
object ContactRepair {

    /** The blip lasts at most this long. */
    const val BLIP_MAX_MS = 30L

    /** Both thumbs drop within this of each other, and of the blip starting. */
    const val END_SYNC_MS = 20L

    /** A continuation starts at most this long after the blip ends. */
    const val RESUME_MS = 50L

    /** The blip sits this close to the thumbs' midpoint. */
    const val MIDPOINT_KW = 1.0f

    /** A continuation starts this close to where its stroke stopped (thumbs keep moving through the merge: 1.75 kw in "positive"). */
    const val RESUME_KW = 2.0f

    fun repair(tokens: List<InputToken>): List<InputToken> {
        if (tokens.size < 3) return tokens
        if (tokens.any { it is SwipeToken && it.rawPath.isEmpty() }) return tokens
        var cur = tokens
        // Each pass removes one blip and at least one token, so this terminates.
        while (true) {
            cur = repairOnce(cur) ?: return cur
        }
    }

    private fun repairOnce(tokens: List<InputToken>): List<InputToken>? {
        for (b in tokens) {
            if (b.tEnd - b.tStart > BLIP_MAX_MS) continue
            val ended = tokens.filter {
                it !== b && it.tEnd <= b.tStart && b.tStart - it.tEnd <= END_SYNC_MS
            }
            for (a1 in ended) for (a2 in ended) {
                if (a1 === a2 || a1.streamId == a2.streamId) continue
                if (kotlin.math.abs(a1.tEnd - a2.tEnd) > END_SYNC_MS) continue
                val (x1, y1) = endOf(a1)
                val (x2, y2) = endOf(a2)
                val (bx, by) = startOf(b)
                if (dist(bx, by, (x1 + x2) / 2, (y1 + y2) / 2) > MIDPOINT_KW) continue
                val c1 = continuation(tokens, b, a1, a2) ?: continue
                val c2 = continuation(tokens, b, a2, a1)
                if (c2 === c1) continue
                val out = ArrayList<InputToken>(tokens.size)
                for (t in tokens) {
                    when {
                        t === b || t === c1 || t === c2 -> {}
                        t === a1 -> out.add(join(a1, c1))
                        t === a2 -> out.add(if (c2 != null) join(a2, c2) else a2)
                        else -> out.add(t)
                    }
                }
                return out
            }
        }
        return null
    }

    /** The fresh touch nearest to where [a] stopped, starting just after the blip. */
    private fun continuation(tokens: List<InputToken>, b: InputToken, a: InputToken, other: InputToken): InputToken? {
        val (ax, ay) = endOf(a)
        var best: InputToken? = null
        var bestD = RESUME_KW
        for (c in tokens) {
            if (c === a || c === b || c === other) continue
            if (c.tStart < b.tEnd || c.tStart - b.tEnd > RESUME_MS) continue
            val (cx, cy) = startOf(c)
            val d = dist(ax, ay, cx, cy)
            if (d <= bestD) { bestD = d; best = c }
        }
        return best
    }

    private fun join(a: InputToken, c: InputToken): SwipeToken {
        val path = ArrayList<PathPoint>()
        path.addAll(pointsOf(a))
        path.addAll(pointsOf(c))
        val r = FloatArray(2 * KineticaConstants.RESAMPLE_N)
        DtwMatcher().resample(path, r)
        var arc = 0f
        for (i in 1 until path.size) arc += dist(path[i - 1].x, path[i - 1].y, path[i].x, path[i].y)
        val contacts = ArrayList<KeyContact>()
        for (k in contactsOf(a) + contactsOf(c)) {
            val last = contacts.lastOrNull()
            if (last != null && last.code == k.code) contacts[contacts.size - 1] = KeyContact(k.code, last.tEnter, k.tExit)
            else contacts.add(k)
        }
        val offset = pointsOf(a).size
        val dwells = (a as? SwipeToken)?.dwells.orEmpty() +
            (c as? SwipeToken)?.dwells.orEmpty().map { it.copy(enterIdx = it.enterIdx + offset, exitIdx = it.exitIdx + offset) }
        return SwipeToken(a.streamId, path, r, contacts, arc, a.tStart, c.tEnd, dwells = dwells)
    }

    private fun pointsOf(t: InputToken): List<PathPoint> = when (t) {
        is SwipeToken -> t.rawPath
        is TapToken -> listOf(PathPoint(t.x, t.y, t.tStart), PathPoint(t.x, t.y, t.tEnd))
    }

    private fun contactsOf(t: InputToken): List<KeyContact> = when (t) {
        is SwipeToken -> t.keyContacts
        is TapToken -> listOf(KeyContact(t.code, t.tStart, t.tEnd))
    }

    private fun startOf(t: InputToken): Pair<Float, Float> = when (t) {
        is SwipeToken -> t.rawPath.first().let { it.x to it.y }
        is TapToken -> t.x to t.y
    }

    private fun endOf(t: InputToken): Pair<Float, Float> = when (t) {
        is SwipeToken -> t.rawPath.last().let { it.x to it.y }
        is TapToken -> t.x to t.y
    }

    private fun dist(x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val dx = x1 - x0
        val dy = y1 - y0
        return sqrt(dx * dx + dy * dy)
    }
}
