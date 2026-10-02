package com.kinetica.keyboard.engine

import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.WordCandidate

/**
 * The per-piece shape cost inside the trie search: how far one swipe piece's
 * resampled path is from the path a run of letters would draw.
 *
 * The seam exists so a different scorer can be measured on replayed traces
 * without touching the search. One constraint travels with it: [WordPredictor]
 * derives the early-abandon [budget] by inverting the geometric term of the
 * score, so a replacement must return a cost on the DTW scale (summed per-step
 * kw) or the budget stops being admissible and winners get dropped. A scorer
 * with a different scale belongs in a [CandidateReranker] instead.
 *
 * Thread-confined, like the predictor that owns it.
 */
interface SegmentScorer {
    /**
     * Summed per-step cost of [resampled] against `letters[from until to]`;
     * +Infinity once it exceeds [budget]; [NO_PATH] when those letters have
     * no path on [g] (no key for any of them).
     */
    fun cost(
        resampled: FloatArray,
        letters: IntArray,
        from: Int,
        to: Int,
        g: KeyboardGeometry,
        budget: Float,
    ): Float

    companion object {
        const val NO_PATH = Float.NEGATIVE_INFINITY
    }
}

/** Today's scorer: banded DTW against the ideal key-centre path. */
class DtwSegmentScorer : SegmentScorer {
    private val dtw = DtwMatcher()
    private val ideal = FloatArray(2 * KineticaConstants.RESAMPLE_N)

    override fun cost(
        resampled: FloatArray,
        letters: IntArray,
        from: Int,
        to: Int,
        g: KeyboardGeometry,
        budget: Float,
    ): Float {
        if (!dtw.idealPath(letters, from, to, g, ideal)) return SegmentScorer.NO_PATH
        return dtw.distanceAccum(resampled, ideal, budget)
    }
}

/**
 * Reorders a finished candidate list, after the search and before the list is
 * cut to TOP_K. Stage A of the neural plan: the search stays exactly as it is,
 * so a reranker can only reorder what the search found, and its value is capped
 * by recall at [WordPredictor]'s rerank depth. Candidates carry their
 * [WordCandidate.segmentation] whenever a reranker is installed.
 */
fun interface CandidateReranker {
    fun rerank(tokens: List<InputToken>, candidates: List<WordCandidate>): List<WordCandidate>
}
