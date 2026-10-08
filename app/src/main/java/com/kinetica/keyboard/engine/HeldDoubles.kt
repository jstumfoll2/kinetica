package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.SwipeToken
import com.kinetica.keyboard.engine.models.TapToken

/**
 * A key the thumb held on mid-swipe, read as "this letter is doubled".
 *
 * A doubled letter adds nothing to a swipe's path ("helo" and "hello" draw the same line), so
 * without a signal of its own the choice falls to frequency. Some typists pause on the key to
 * say "twice". The evidence is the hysteresis contact: how long the swipe stayed on a key.
 *
 * Measured on the developer's 2026-10-08 traces (sticky contacts, interior letters, a label's
 * doubled letter against its single letters): a contact of 250 ms or more held on 33% of
 * doubled letters and 1.8% of single ones. Over all four batches it is 17% against 2.3%: the
 * habit is recent and not every double gets it, so a missing hold is no evidence against a
 * double and nothing here penalises one.
 *
 * A swipe's last contact runs to the lift, and the thumb slows into it anyway (median 62 ms on
 * single final letters, 114 ms on doubled ones), so it has its own, shorter threshold. The
 * first contact is the landing and never counts. A key the swipe turns back on needs twice the
 * time: a slow 600 ms "ana" stopped 320 ms on its turn (NonLatinDictionaryTest), and only 4 of
 * the 26 held doubles on 2026-10-08 were turns back. A tap held as long as an interior contact
 * counts too (doubled taps on 2026-10-08: 5% held 250 ms or more, single taps none). A swipe
 * that never leaves its key does not: single letters were held that way as often as doubles.
 * A contact or tap while another token is down is a thumb parked for the other one, not a
 * hold, and does not count either.
 */
internal object HeldDoubles {

    /** Letter codes held on, in no order; empty for most buffers. */
    fun heldKeys(tokens: List<InputToken>, midMs: Long, endMs: Long): IntArray {
        var out = IntArray(0)
        for (t in tokens) {
            if (t is TapToken) {
                if (t.tEnd - t.tStart >= midMs && !overlapsOther(tokens, t, t.tStart, t.tEnd) && t.code !in out) out += t.code
                continue
            }
            if (t !is SwipeToken) continue
            val cs = t.keyContacts
            val n = cs.size
            for (i in 1 until n) {
                val c = cs[i]
                val last = i == n - 1
                val dur = c.tExit - c.tEnter
                // A turn back the way the swipe came ("ana", "good") slows to a stop on its key
                // whatever the typist meant, so it has to be held twice as long.
                val turnBack = !last && cs[i - 1].code == cs[i + 1].code
                if (dur < if (last) endMs else if (turnBack) 2 * midMs else midMs) continue
                if (overlapsOther(tokens, t, c.tEnter, c.tExit)) continue
                if (c.code !in out) out += c.code
            }
        }
        return out
    }

    private fun overlapsOther(tokens: List<InputToken>, t: InputToken, from: Long, to: Long): Boolean =
        tokens.any { o -> o !== t && o.tStart < to && o.tEnd > from }

    /** True when [codes] has [code] twice in a row somewhere. */
    fun doubles(codes: IntArray, code: Int): Boolean {
        for (i in 1 until codes.size) if (codes[i] == code && codes[i - 1] == code) return true
        return false
    }
}
