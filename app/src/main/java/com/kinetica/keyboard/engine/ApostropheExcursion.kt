package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.Dwell
import com.kinetica.keyboard.engine.models.KeyContact
import com.kinetica.keyboard.engine.models.PathPoint
import kotlin.math.sqrt

/**
 * A swipe that went out to the apostrophe key and came back mid-word, Nintype style:
 * `w-e-'-r-e` for "we're".
 *
 * The apostrophe key sits at the right edge of the home row, so the trip there and back
 * crosses a row of letters nobody meant (e to ' passes r, t, y, u, i, o). Matching that
 * path letter for letter would never find "we're". So the trip is cut out of the path,
 * which leaves the gesture the user would have drawn without it (w-e-r-e), and the token
 * is marked: the decoder then prefers the dictionary's apostrophe spelling of whatever
 * the letters say (WordPredictor.preferApostrophe). The letters themselves never had to
 * spell the apostrophe; the trie already admits it anywhere at no path cost.
 *
 * Two shapes count, and both are deliberately narrow, because a false mark turns "well"
 * into "we'll":
 *  - a TRIP: the path enters the key's rect after it started, leaves it again, arrived
 *    from at least [MIN_LEG_KW] away, went back at least as far, and turned around in x
 *    (in moving right, out moving left). A stroke that merely clips the key's corner on
 *    its way somewhere else (p down to l) is not one. The cut runs from where the path
 *    turned toward the key to where it turned back.
 *  - an ENDING: the stroke lifts on the key after travelling at least [MIN_LEG_KW] to it
 *    from a turn ("don't" as d, then o-n-' by the right thumb). The cut runs from that
 *    turn to the lift, so the stroke ends on its real last letter. An overshoot past L
 *    was the worry here; across the 7 062 recorded gestures in the developer's traces
 *    (2026-10-02 and 2026-10-06) no home-row sample ever went past x 9.44 kw, against
 *    the key's 9.5, so a lift on the key is taken as meant.
 *
 * Leg ends are found by walking away from the key while the distance to its centre keeps
 * growing, so a leg ends at the turn where the path started heading for the key, or at the
 * stroke's start or lift. That is what limits a trip to the two-thumb shape it is for, a
 * short stroke near the key ("i'll" as i, out to ', back to l): a one-thumb run from the
 * far side (w-e-'-r-e) has no turn at e or r to find, so its cut swallows those letters
 * and the word does not decode. A tap on the key is the way to write that one. An ending
 * with no turn at all is not taken.
 *
 * Only the first such visit is taken; one apostrophe per gesture covers every English
 * contraction and every elision the bundled dictionaries hold.
 */
object ApostropheExcursion {

    /**
     * How far from the key's centre a leg must start, kw. Below one key on purpose: L's
     * centre sits 0.75 to 0.9 kw from the key's, and "i'll" is i, out to ', back to l.
     */
    const val MIN_LEG_KW = 0.6f

    /** How far left of the key's centre both legs must reach, kw: the turn in x. */
    private const val TURN_X_KW = 0.3f

    /** Sample jitter allowed while walking a leg, kw. */
    private const val LEG_TOLERANCE_KW = 0.05f

    /**
     * The samples to drop, or null when [points] made no trip to [rect] (kw: left, top,
     * right, bottom) and did not end on it. The range never includes the sample where the
     * path turned toward the key, nor (for a trip) the one where it turned back: the path
     * joins them with a straight line. For an ending it runs to the last sample.
     */
    fun find(points: List<PathPoint>, rect: FloatArray): IntRange? {
        val n = points.size
        var a = -1
        for (i in 0 until n) {
            if (inside(points[i], rect)) {
                a = i
                break
            }
        }
        if (a <= 0) return null
        var b = a
        while (b + 1 < n && inside(points[b + 1], rect)) b++

        val cx = (rect[0] + rect[2]) / 2f
        val cy = (rect[1] + rect[3]) / 2f
        var s = a
        while (s > 0 && dist(points[s - 1], cx, cy) >= dist(points[s], cx, cy) - LEG_TOLERANCE_KW) s--

        if (b >= n - 1) {
            // An ending: only from a turn, and only after real travel to the key.
            if (s == 0 || dist(points[s], cx, cy) < MIN_LEG_KW) return null
            return (s + 1) until n
        }
        var e = b
        while (e < n - 1 && dist(points[e + 1], cx, cy) >= dist(points[e], cx, cy) - LEG_TOLERANCE_KW) e++

        if (dist(points[s], cx, cy) < MIN_LEG_KW || dist(points[e], cx, cy) < MIN_LEG_KW) return null
        // Turned around in x: in moving right, out moving left.
        if (cx - points[s].x < TURN_X_KW || points[e].x - cx > -TURN_X_KW) return null
        if (e - s < 2) return null
        return (s + 1) until e
    }

    /** What a swipe's per-sample records look like once [cut] is removed from its path. */
    class Cut(
        val points: List<PathPoint>,
        val contacts: List<KeyContact>,
        val dwells: List<Dwell>,
        val arcLen: Float,
    )

    /**
     * Removes [cut] from the path and drops what the trip produced: the key contacts
     * entered and left inside it (the letters it crossed) and the dwells that touch it.
     * Later dwells are re-indexed, and contacts that end up adjacent on one key merge, so
     * the contact list keeps its no-consecutive-duplicates invariant.
     */
    fun apply(
        points: List<PathPoint>,
        contacts: List<KeyContact>,
        dwells: List<Dwell>,
        cut: IntRange,
    ): Cut {
        val tFrom = points[cut.first - 1].t
        // An ending has nothing after the cut: everything entered after the turn goes.
        val tTo = if (cut.last + 1 < points.size) points[cut.last + 1].t else Long.MAX_VALUE
        val kept = ArrayList<PathPoint>(points.size - (cut.last - cut.first + 1))
        for (i in points.indices) if (i !in cut) kept.add(points[i])

        val cs = ArrayList<KeyContact>(contacts.size)
        for (c in contacts) {
            // Entered after the turn toward the key and left before the turn back: a key
            // the trip crossed. The key the turn sat on was entered before it and stays.
            if (c.tEnter > tFrom && c.tExit <= tTo) continue
            val last = cs.lastOrNull()
            if (last != null && last.code == c.code) {
                cs[cs.size - 1] = KeyContact(c.code, last.tEnter, c.tExit)
            } else {
                cs.add(c)
            }
        }

        val removed = cut.last - cut.first + 1
        val ds = ArrayList<Dwell>(dwells.size)
        for (d in dwells) {
            when {
                d.exitIdx < cut.first -> ds.add(d)
                d.enterIdx > cut.last -> ds.add(d.copy(enterIdx = d.enterIdx - removed, exitIdx = d.exitIdx - removed))
                else -> Unit
            }
        }

        var arc = 0f
        for (i in 1 until kept.size) {
            val dx = kept[i].x - kept[i - 1].x
            val dy = kept[i].y - kept[i - 1].y
            arc += sqrt(dx * dx + dy * dy)
        }
        return Cut(kept, cs, ds, arc)
    }

    private fun inside(p: PathPoint, r: FloatArray): Boolean =
        p.x >= r[0] && p.x < r[2] && p.y >= r[1] && p.y < r[3]

    private fun dist(p: PathPoint, cx: Float, cy: Float): Float {
        val dx = p.x - cx
        val dy = p.y - cy
        return sqrt(dx * dx + dy * dy)
    }
}
