package com.kinetica.keyboard.engine.trace

import java.util.Random

/**
 * Which words practice mode asks for, aimed at what the decoder still gets wrong.
 *
 * QWERTY splits between the thumbs at the t|y, g|h and b|n columns. Besides short
 * words and double letters, the developer's traces point at three shapes:
 *  - [Kind.ONE_FINGER]: long words spanning both halves, drawn as one swipe
 *    (2026-10-06: "respond" read as "did");
 *  - [Kind.CENTER]: words living in the middle columns ("right", "nothing"), where
 *    the two thumbs come within a key of each other and the touchscreen can merge them;
 *  - [Kind.CROSSING]: words that switch halves on most letters, so both thumbs
 *    work at once and reach across the middle ("people", "without", "beautiful").
 * The prompt carries a hint for the two-thumb and one-finger kinds, since the
 * point is to record that way of drawing it.
 */
object PracticeWords {

    enum class Kind(val hint: String?) {
        SHORT(null),
        DOUBLE(null),
        ONE_FINGER("one finger, one swipe"),
        CENTER("both thumbs"),
        CROSSING("both thumbs"),
        ANY(null),
    }

    private const val LEFT = "qwertasdfgzxcvb"

    /** The two columns either side of the split, where the thumbs meet. */
    private const val CENTER_KEYS = "rtyufghjvbn"

    fun isLeft(c: Char) = c in LEFT

    /** Times the word moves from one half to the other, letter to letter. */
    fun switches(w: String): Int = (1 until w.length).count { isLeft(w[it]) != isLeft(w[it - 1]) }

    fun kindsOf(w: String): Set<Kind> {
        val out = HashSet<Kind>()
        if (w.length <= 3) out.add(Kind.SHORT)
        if ((1 until w.length).any { w[it] == w[it - 1] }) out.add(Kind.DOUBLE)
        val sw = switches(w)
        if (w.length >= 7 && sw >= 2) out.add(Kind.ONE_FINGER)
        val center = w.count { it in CENTER_KEYS }
        if (w.length >= 5 && center * 10 >= w.length * 6 && sw >= 2) out.add(Kind.CENTER)
        if (w.length >= 6 && sw * 10 >= (w.length - 1) * 6) out.add(Kind.CROSSING)
        return out
    }

    /** Share of prompts per kind, out of 20. */
    private val MIX = listOf(
        Kind.SHORT to 2, Kind.DOUBLE to 2, Kind.ONE_FINGER to 5,
        Kind.CENTER to 5, Kind.CROSSING to 4, Kind.ANY to 2,
    )

    class Pool(words: List<String>) {
        private val all = words
        private val byKind: Map<Kind, List<String>> = Kind.values().associateWith { k ->
            if (k == Kind.ANY) words else words.filter { k in kindsOf(it) }
        }

        fun size(k: Kind) = byKind[k]?.size ?: 0

        fun pick(rnd: Random): Pair<String, Kind> {
            if (all.isEmpty()) return "hello" to Kind.ANY
            var r = rnd.nextInt(MIX.sumOf { it.second })
            for ((k, share) in MIX) {
                if (r < share) {
                    val pool = byKind[k].orEmpty().ifEmpty { all }
                    return pool[rnd.nextInt(pool.size)] to k
                }
                r -= share
            }
            return all[rnd.nextInt(all.size)] to Kind.ANY
        }
    }
}
