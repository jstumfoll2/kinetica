package com.kinetica.keyboard.engine

/**
 * Walks the trie extending an [Interleave] reading one letter per edge, and
 * returns the words whose complete readings fit best, scored the way the rest
 * of the decoder scores: frequency weight times a geometric term times the
 * bigram multiplier.
 *
 * Pruning is the model's own: a letter no thumb came within
 * [Interleave.MAX_LETTER_KW] of, at a moment the order allows, kills the
 * branch. [maxNodes] bounds the walk regardless.
 */
class InterleavedSearch(
    private val trie: Trie,
    private val il: Interleave,
    private val maxNodes: Int = MAX_NODES,
) {
    class Hit(val node: Int, val word: String, val cost: Float, val fw: Float)

    var visited = 0
        private set

    fun run(keep: Int): List<Hit> {
        val rows = Array(KineticaConstants.MAX_WORD_LEN + 1) { FloatArray(il.rowSize) }
        il.initialRow().copyInto(rows[0])
        val letters = IntArray(KineticaConstants.MAX_WORD_LEN)
        val visits = IntArray(KineticaConstants.MAX_WORD_LEN + 1)
        val hits = ArrayList<Hit>()
        // Iterative DFS: (node, depth) with the child cursor kept per depth.
        val nodeAt = IntArray(KineticaConstants.MAX_WORD_LEN + 1)
        val cursor = IntArray(KineticaConstants.MAX_WORD_LEN + 1)
        nodeAt[0] = trie.root
        cursor[0] = 0
        var depth = 0
        while (depth >= 0) {
            val node = nodeAt[depth]
            if (cursor[depth] >= trie.childCount(node) || depth >= KineticaConstants.MAX_WORD_LEN || visited >= maxNodes) {
                depth--
                continue
            }
            val child = trie.firstChild(node) + cursor[depth]
            cursor[depth]++
            visited++
            val code = trie.letter(child)
            // A doubled letter, or an apostrophe (no key), adds no visit.
            val repeat = code == Alphabet.APOSTROPHE || (depth > 0 && letters[depth - 1] == code)
            val next = rows[depth + 1]
            val ok = if (repeat) {
                rows[depth].copyInto(next)
                depth > 0
            } else {
                il.step(rows[depth], code, visits[depth] == 0, next) &&
                    // Already too far off on average to come back: the word's
                    // remaining letters would have to sit on their keys.
                    il.lastMin <= PRUNE_MEAN_KW * (visits[depth] + 1)
            }
            if (!ok) continue
            letters[depth] = code
            visits[depth + 1] = visits[depth] + if (repeat) 0 else 1
            if (trie.isWord(child)) {
                val c = il.finalCost(next, visits[depth + 1])
                if (c < Interleave.INF) {
                    val sb = StringBuilder(depth + 1)
                    for (i in 0..depth) sb.append(Alphabet.charOf(letters[i]))
                    val fw = KineticaConstants.FREQ_WEIGHT_FLOOR +
                        (1f - KineticaConstants.FREQ_WEIGHT_FLOOR) * trie.frequency(child) / 255f
                    hits.add(Hit(child, sb.toString(), c, fw))
                }
            }
            depth++
            nodeAt[depth] = child
            cursor[depth] = 0
        }
        return hits.sortedByDescending { score(it.fw, it.cost) }.take(keep)
    }

    companion object {
        const val MAX_NODES = 60_000

        /** A prefix whose best reading averages more than this per key is dropped. */
        const val PRUNE_MEAN_KW = 0.9f

        /** How sharply the interleaved fit discounts: per kw of mean distance. */
        const val COST_SCALE = 3.0f

        fun geometric(cost: Float): Float = kotlin.math.exp(-COST_SCALE * cost * cost)

        fun score(fw: Float, cost: Float): Float = fw * geometric(cost)
    }
}
