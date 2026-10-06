package com.kinetica.keyboard.engine

import kotlin.math.sqrt

/**
 * Immutable letter-key geometry in key-width (kw) units, rebuilt by the UI on
 * every size or layout-mode change. Only letter keys participate: gesture
 * decoding never involves space, backspace, or mode keys (those pointers are
 * routed to dedicated controllers before reaching the engine).
 */
class KeyboardGeometry private constructor(
    val keyWidthPx: Float,
    /**
     * The x that divides the LEFT thumb's half of the board from the RIGHT
     * one's, in view pixels. Its ONLY consumer is
     * [GestureEngine.onPointerDown]'s stream assignment.
     *
     * The centre of the LETTER BLOCK, not half the view width, and the
     * difference is load-bearing once the board can be inset: with side
     * padding the keys no longer span the view, so half the view width would
     * put the divider off the board's actual centre and misassign a thumb.
     */
    val midlinePx: Float,
    private val present: BooleanArray,       // [26]
    private val centersX: FloatArray,        // [26] kw
    private val centersY: FloatArray,        // [26] kw
    private val rects: FloatArray,           // [26*4] kw: left, top, right, bottom
    /**
     * The main-page apostrophe key's rect in kw (left, top, right, bottom), or null when
     * the layout has none. Not a letter: [keyAt] and [nearestKey] never return it and no
     * pointer starts a stream on it. Its one reader is [GestureStream.finish], which
     * looks for a swipe that went out to it and came back mid-word (see
     * [ApostropheExcursion]).
     */
    private val apostrophe: FloatArray? = null,
) {
    /** A copy of the apostrophe key's kw rect, or null; see [apostrophe]. */
    fun apostropheRectKw(): FloatArray? = apostrophe?.copyOf()

    fun hasKey(code: Int): Boolean = code in 0 until Alphabet.LETTERS && present[code]

    fun centerX(code: Int): Float = centersX[code]
    fun centerY(code: Int): Float = centersY[code]

    /** Euclidean distance between two key centers, kw. */
    fun keyDist(a: Int, b: Int): Float {
        val dx = centersX[a] - centersX[b]
        val dy = centersY[a] - centersY[b]
        return sqrt(dx * dx + dy * dy)
    }

    fun distToCenter(xKw: Float, yKw: Float, code: Int): Float {
        val dx = xKw - centersX[code]
        val dy = yKw - centersY[code]
        return sqrt(dx * dx + dy * dy)
    }

    /** Letter code whose rect contains the point, or -1. */
    fun keyAt(xKw: Float, yKw: Float): Int {
        for (code in 0 until Alphabet.LETTERS) {
            if (!present[code]) continue
            val base = code * 4
            if (xKw >= rects[base] && xKw < rects[base + 2] &&
                yKw >= rects[base + 1] && yKw < rects[base + 3]
            ) return code
        }
        return -1
    }

    /** True while the point is inside the key's rect inflated by [inflateKw]. */
    fun insideInflated(xKw: Float, yKw: Float, code: Int, inflateKw: Float): Boolean {
        val base = code * 4
        return xKw >= rects[base] - inflateKw && xKw < rects[base + 2] + inflateKw &&
            yKw >= rects[base + 1] - inflateKw && yKw < rects[base + 3] + inflateKw
    }

    /**
     * The key's rect in kw as (left, top, right, bottom), or null when the
     * layout has no such key. A copy: for the trace recorder, which stores kw
     * rects so [fromKw] rebuilds this geometry bit for bit.
     */
    fun rectKw(code: Int): FloatArray? =
        if (!hasKey(code)) null else rects.copyOfRange(code * 4, code * 4 + 4)

    /** Letter code nearest to the point by center distance, or -1 if none present. */
    fun nearestKey(xKw: Float, yKw: Float): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        for (code in 0 until Alphabet.LETTERS) {
            if (!present[code]) continue
            val d = distToCenter(xKw, yKw, code)
            if (d < bestD) {
                bestD = d
                best = code
            }
        }
        return best
    }

    companion object {
        /**
         * Builds geometry from pixel-space letter-key rects. Each entry is
         * (code, leftPx, topPx, rightPx, bottomPx). [keyWidthPx] is the width
         * of a standard letter key and defines the kw unit. An entry with code
         * [Alphabet.APOSTROPHE] is the apostrophe key, kept apart from the letters.
         */
        fun fromPx(
            keyWidthPx: Float,
            midlinePx: Float,
            letterRectsPx: List<FloatArray>,
            codes: IntArray,
        ): KeyboardGeometry {
            require(keyWidthPx > 0f) { "keyWidthPx must be positive" }
            require(letterRectsPx.size == codes.size)
            return fromKw(
                keyWidthPx, midlinePx,
                letterRectsPx.map { r -> FloatArray(4) { r[it] / keyWidthPx } }, codes,
            )
        }

        /**
         * Same as [fromPx] with the rects already in kw. Replay uses it: a px
         * rect rebuilt from a kw one does not always divide back to the same
         * float, so a trace stores what the engine actually used.
         */
        fun fromKw(
            keyWidthPx: Float,
            midlinePx: Float,
            letterRectsKw: List<FloatArray>,
            codes: IntArray,
        ): KeyboardGeometry {
            require(keyWidthPx > 0f) { "keyWidthPx must be positive" }
            require(letterRectsKw.size == codes.size)
            val present = BooleanArray(Alphabet.LETTERS)
            val cx = FloatArray(Alphabet.LETTERS)
            val cy = FloatArray(Alphabet.LETTERS)
            val rects = FloatArray(Alphabet.LETTERS * 4)
            var apostrophe: FloatArray? = null
            for (i in codes.indices) {
                val code = codes[i]
                if (code == Alphabet.APOSTROPHE) {
                    apostrophe = letterRectsKw[i].copyOf()
                    continue
                }
                if (code !in 0 until Alphabet.LETTERS) continue
                val r = letterRectsKw[i]
                val base = code * 4
                rects[base] = r[0]
                rects[base + 1] = r[1]
                rects[base + 2] = r[2]
                rects[base + 3] = r[3]
                cx[code] = (rects[base] + rects[base + 2]) / 2f
                cy[code] = (rects[base + 1] + rects[base + 3]) / 2f
                present[code] = true
            }
            return KeyboardGeometry(keyWidthPx, midlinePx, present, cx, cy, rects, apostrophe)
        }
    }
}
