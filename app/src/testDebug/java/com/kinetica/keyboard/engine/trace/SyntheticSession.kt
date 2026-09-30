package com.kinetica.keyboard.engine.trace

import com.kinetica.keyboard.engine.DictionaryLoader
import com.kinetica.keyboard.engine.GestureEngine
import com.kinetica.keyboard.engine.KeyboardGeometry
import com.kinetica.keyboard.engine.WordComposer
import com.kinetica.keyboard.engine.WordPredictor
import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.TapToken
import com.kinetica.keyboard.engine.models.WordCandidate
import java.io.File
import java.util.Random
import java.util.concurrent.Executor

/**
 * Types synthetic words through the real input path - px pointer events into a
 * [GestureEngine] (two pointers), tokens into a [WordComposer] - with a
 * [SwipeTraceRecorder] attached to both, the way the developer build attaches
 * it. The lines it writes are what replay has to reproduce.
 *
 * The shapes cover the reference doc's buckets: one-thumb swipes, overlapping
 * two-thumb halves, a tap then a swipe, all taps, two swipes on one thumb, plus
 * a word reloaded from the editor as literal taps and an abandoned buffer.
 * Paths go through key centres with gaussian noise, which makes these a check
 * of the recorder and replay, not a measure of accuracy on real thumbs.
 */
class SyntheticSession(assets: File, seed: Long = 7) {
    private val rnd = Random(seed)
    private val dir = File(assets, "dictionaries")

    val kwPx = 108f
    val geometry: KeyboardGeometry = run {
        val rows = listOf("qwertyuiop" to 0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f)
        val rects = ArrayList<FloatArray>()
        val codes = ArrayList<Int>()
        for ((ri, r) in rows.withIndex()) for ((i, ch) in r.first.withIndex()) {
            val l = (r.second + i) * kwPx
            val top = ri * 1.5f * kwPx
            rects.add(floatArrayOf(l, top, l + kwPx, top + 1.5f * kwPx))
            codes.add(ch - 'a')
        }
        KeyboardGeometry.fromPx(kwPx, 540f, rects, codes.toIntArray())
    }
    private val tapMaxDispPx = 0.35f * kwPx

    private fun predictor(lang: String): WordPredictor {
        val d = File(dir, "${lang}_wordlist.txt").bufferedReader().use { DictionaryLoader.load(it) }
        val b = File(dir, "${lang}_bigrams.txt").bufferedReader().use { DictionaryLoader.loadBigrams(it, d.trie) }
        return WordPredictor(d.trie, b, geometry, d.forms, language = lang)
    }

    /** Frequent a-z words of 1..10 letters, every [step]th, up to [n]. */
    fun words(lang: String, n: Int, step: Int = 7): List<String> =
        File(dir, "${lang}_wordlist.txt").readLines().asSequence()
            .map { it.substringBefore('\t') }
            .filter { w -> w.length in 1..10 && w.all { it in 'a'..'z' } }
            .filterIndexed { i, _ -> i % step == 0 }
            .take(n).toList()

    private data class Ev(val kind: Char, val pid: Int, val x: Float, val y: Float, val t: Long)

    /**
     * Records [words] as one session (context carries across words) and hands
     * each trace line to [out]. Returns the live top-1 per word, for sanity.
     */
    fun record(words: List<String>, lang: String, alternate: String?, out: (String) -> Unit): List<String?> {
        val cfg = SwipeTrace.Config(lang, alternate)
        val recorder = SwipeTraceRecorder({ cfg }, out)
        val direct = Executor { it.run() }
        var shown: List<WordCandidate> = emptyList()
        val composer = WordComposer(predictor(lang), direct, direct, object : WordComposer.Callbacks {
            override fun onCandidates(
                candidates: List<WordCandidate>,
                tentative: WordCandidate?,
                literal: String,
                generation: Int,
            ) { shown = candidates }
        })
        composer.alternatePredictor = alternate?.let { predictor(it) }
        composer.observer = recorder
        val engine = GestureEngine(object : GestureEngine.Listener {
            override fun onTokenFinalized(token: InputToken) = composer.onToken(token)
            override fun onKeyTransition(streamId: StreamId, code: Int) = Unit
            override fun onAllPointersUp() = Unit
        })
        engine.maxPointers = 2
        engine.observer = recorder
        engine.setGeometry(geometry, tapMaxDispPx)

        val top1 = ArrayList<String?>()
        for ((i, w) in words.withIndex()) {
            val t0 = 10_000L + i * 4_000L
            val evs = ArrayList<Ev>()
            if (i % 23 == 11 && w.length >= 3) {
                // Abandoned attempt before the real word: a stray swipe, then clear.
                stroke(evs, "qaz", 0, t0 - 1_500)
                play(engine, evs)
                evs.clear()
                composer.clear()
            }
            if (i % 17 == 5 && w.length >= 5) {
                // Reloaded from the editor: the first two letters come back as taps.
                composer.seed(w.take(2).mapIndexed { k, ch ->
                    TapToken(StreamId.LEFT, ch - 'a', geometry.centerX(ch - 'a'), geometry.centerY(ch - 'a'), false, t0 + k, t0 + k + 1)
                })
                stroke(evs, w.substring(2), 0, t0 + 100)
            } else {
                shape(evs, w, i, t0)
            }
            play(engine, evs)
            top1.add(shown.firstOrNull()?.word)
            composer.commitWord(w)
        }
        return top1
    }

    private fun shape(evs: ArrayList<Ev>, w: String, i: Int, t0: Long) {
        when {
            w.length == 1 -> tap(evs, w[0], 0, t0)
            w.length <= 3 && i % 3 == 0 -> w.forEachIndexed { k, ch -> tap(evs, ch, k % 2, t0 + k * 180L) }
            w.length >= 4 && i % 5 == 1 -> {
                // Two thumbs, overlapping: the right half starts before the left lifts.
                val k = w.length / 2
                part(evs, w.substring(0, k), 0, t0)
                part(evs, w.substring(k), 1, t0 + 60L * k)
            }
            w.length >= 3 && i % 5 == 2 -> {
                tap(evs, w[0], 0, t0)
                part(evs, w.substring(1), 1, t0 + 150)
            }
            w.length >= 6 && i % 5 == 3 -> {
                // One thumb, two strokes in a row.
                val k = w.length / 2
                part(evs, w.substring(0, k), 0, t0)
                part(evs, w.substring(k), 0, t0 + 120L * k + 200)
            }
            else -> part(evs, w, 0, t0)
        }
    }

    /** A swipe when the letters cross at least two keys, else a tap. */
    private fun part(evs: ArrayList<Ev>, s: String, pid: Int, t0: Long) {
        if (s.toSet().size >= 2) stroke(evs, s, pid, t0) else tap(evs, s[0], pid, t0)
    }

    private fun centre(ch: Char): Pair<Float, Float> =
        geometry.centerX(ch - 'a') * kwPx to geometry.centerY(ch - 'a') * kwPx

    private fun noise(sigmaPx: Float, capPx: Float): Float =
        (rnd.nextGaussian().toFloat() * sigmaPx).coerceIn(-capPx, capPx)

    private fun tap(evs: ArrayList<Ev>, ch: Char, pid: Int, t0: Long) {
        val (cx, cy) = centre(ch)
        val x = cx + noise(0.15f * kwPx, 0.35f * kwPx)
        val y = cy + noise(0.15f * kwPx, 0.35f * kwPx)
        evs.add(Ev('D', pid, x, y, t0))
        evs.add(Ev('M', pid, x + 1f, y, t0 + 30))
        evs.add(Ev('U', pid, x + 1f, y + 1f, t0 + 60))
    }

    private fun stroke(evs: ArrayList<Ev>, s: String, pid: Int, t0: Long) {
        val keys = s.filterIndexed { k, ch -> k == 0 || ch != s[k - 1] }
        val pts = keys.map { ch ->
            val (cx, cy) = centre(ch)
            (cx + noise(0.15f * kwPx, 0.35f * kwPx)) to (cy + noise(0.15f * kwPx, 0.35f * kwPx))
        }
        var t = t0
        evs.add(Ev('D', pid, pts[0].first, pts[0].second, t))
        for (k in 1 until pts.size) {
            val (ax, ay) = pts[k - 1]
            val (bx, by) = pts[k]
            val steps = (kotlin.math.hypot(bx - ax, by - ay) / 20f).toInt().coerceAtLeast(3)
            for (st in 1..steps) {
                t += 8
                val f = st.toFloat() / steps
                evs.add(Ev('M', pid, ax + f * (bx - ax) + noise(4f, 12f), ay + f * (by - ay) + noise(4f, 12f), t))
            }
        }
        evs.add(Ev('U', pid, pts.last().first, pts.last().second, t + 8))
    }

    private fun play(engine: GestureEngine, evs: List<Ev>) {
        // Stable sort: one pointer's own events keep their order at equal times.
        for (v in evs.sortedWith(compareBy { it.t })) when (v.kind) {
            'D' -> engine.onPointerDown(v.pid, v.x, v.y, v.t)
            'M' -> engine.onPointerMove(v.pid, v.x, v.y, v.t)
            else -> engine.onPointerUp(v.pid, v.x, v.y, v.t)
        }
    }
}
