package com.kinetica.keyboard.ime

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import com.kinetica.keyboard.engine.CandidateReranker
import com.kinetica.keyboard.engine.CtcReranker
import com.kinetica.keyboard.engine.CtcScorer
import com.kinetica.keyboard.engine.KineticaConstants
import com.kinetica.keyboard.engine.WordPredictor
import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.WordCandidate
import com.kinetica.keyboard.engine.trace.SwipeTrace
import java.io.IOException

/**
 * Developer build: the neural CTC rerank, switchable on the phone.
 *
 * The release build has a stub that always builds today's decoder. Here the
 * trace screen can switch the rerank on for the active language and pick its
 * beta; off (the default) builds exactly the predictor the release build does,
 * no reranker and a [KineticaConstants.TOP_K] heap.
 *
 * Practice mode also decodes each finished buffer both ways, on two private
 * predictors kept off the live decode thread, so every prompt scores the two
 * decoders on the same swipe whichever one is live. The model ships as a DEV
 * asset only: trained by the project's train workflow from scratch on FUTO's
 * MIT swipe dataset (no FUTO weights), and never in a release APK.
 */
object NeuralRerank {

    const val MODEL_ASSET = "ctc/futo_v1.kctc"
    const val MODEL_NAME = "futo_v1"

    /** Picked on Jason's first practice traces (tune_real.txt): 0.05 was best held-out. */
    const val DEFAULT_BETA = 0.05f
    val BETAS = floatArrayOf(0.02f, 0.05f, 0.1f, 0.2f, 0.3f)

    /** Heap depth the rerank reorders, as in the replay harness. */
    const val DEPTH = 50

    private const val PREFS = "neural_v1"
    private const val PREF_ENABLED = "enabled"
    private const val PREF_BETA = "beta"

    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null
    private var model: ByteArray? = null
    private var onChange: (() -> Unit)? = null

    var enabled: Boolean
        get() = prefs?.getBoolean(PREF_ENABLED, false) == true
        set(on) {
            prefs?.edit()?.putBoolean(PREF_ENABLED, on)?.apply()
            onChange?.invoke()
        }

    var beta: Float
        get() = prefs?.getFloat(PREF_BETA, DEFAULT_BETA) ?: DEFAULT_BETA
        set(b) {
            prefs?.edit()?.putFloat(PREF_BETA, b)?.apply()
            onChange?.invoke()
        }

    /** Beta of the predictor the keyboard is decoding with now: 0 when the rerank is off. */
    @Volatile
    var liveBeta: Float = 0f
        private set

    /** Main thread only, like the IME. [onChange] rebuilds the keyboard's predictors. */
    fun install(context: Context, onChange: (() -> Unit)?) {
        useSettings(context)
        this.onChange = onChange
    }

    /** The settings alone, for the trace screen, which can open before the keyboard has. */
    fun useSettings(context: Context) {
        if (prefs != null) return
        appContext = context.applicationContext
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun detach() {
        onChange = null
    }

    /** Whether the model asset loads at all; the trace screen says so if not. */
    fun modelAvailable(context: Context): Boolean {
        useSettings(context)
        return scorer() != null
    }

    /** A fresh scorer per user: [CtcScorer] keeps scratch buffers, so one per thread. */
    private fun scorer(): CtcScorer? {
        val bytes = model ?: try {
            appContext?.assets?.open(MODEL_ASSET)?.use { it.readBytes() }?.also { model = it }
        } catch (e: IOException) {
            Log.w(TAG, "neural model asset unavailable", e)
            null
        } ?: return null
        return try {
            CtcScorer.load(bytes.inputStream())
        } catch (e: Exception) {
            Log.w(TAG, "neural model unreadable", e)
            null
        }
    }

    // ----------------------------------------------------- live predictor

    private var factory: ((CandidateReranker?, Int) -> WordPredictor)? = null
    private var live: WordPredictor? = null
    private var plainShadow: WordPredictor? = null
    private var neuralShadow: WordPredictor? = null

    /**
     * Builds the active language's predictor with [make] (a reranker or null, and
     * the heap depth). Off, that is `make(null, TOP_K)`, the release decode.
     */
    fun primary(make: (CandidateReranker?, Int) -> WordPredictor): WordPredictor {
        factory = make
        plainShadow = null
        neuralShadow = null
        val b = beta
        val s = if (enabled) scorer() else null
        val p = if (s == null) {
            liveBeta = 0f
            make(null, KineticaConstants.TOP_K)
        } else {
            liveBeta = b
            var built: WordPredictor? = null
            make(Timed(CtcReranker(s, { built?.geometry }, b)), DEPTH).also { built = it }
        }
        live = p
        return p
    }

    /**
     * Spacebar tag while the rerank is switched on, so a screenshot says which
     * decoder made it. Read from the switch rather than [liveBeta] because the
     * label is redrawn before the rebuilt predictor lands.
     */
    fun spacebarTag(): String? = if (enabled) "NN $beta" else null

    // ------------------------------------------------------- latency

    private val lock = Any()
    private val rerankMs = FloatArray(RING)
    private var rerankCount = 0

    /** p50 and p95 of the live rerank step in ms, and how many were timed; null before any. */
    fun rerankStats(): Triple<Float, Float, Int>? {
        synchronized(lock) {
            val n = minOf(rerankCount, RING)
            if (n == 0) return null
            val sorted = rerankMs.copyOf(n).also { it.sort() }
            return Triple(sorted[(n - 1) / 2], sorted[((n - 1) * 95) / 100], rerankCount)
        }
    }

    private fun recordRerank(ms: Float) {
        synchronized(lock) {
            rerankMs[rerankCount % RING] = ms
            rerankCount++
        }
    }

    /** The rerank step alone, timed on the decode thread. */
    private class Timed(private val inner: CtcReranker) : CandidateReranker {
        override fun rerank(tokens: List<InputToken>, candidates: List<WordCandidate>): List<WordCandidate> {
            val t0 = SystemClock.elapsedRealtimeNanos()
            val out = inner.rerank(tokens, candidates)
            recordRerank((SystemClock.elapsedRealtimeNanos() - t0) / 1e6f)
            return out
        }
    }

    // ---------------------------------------------------------- A/B

    /** Set while a practice comparison decodes, so the decode-trace sink can drop its lines. */
    val comparing: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

    /** Practice screen: told of each comparison as it is made (main thread). */
    var onCompare: ((SwipeTrace.AB, String) -> Unit)? = null

    /**
     * Decodes a finished practice buffer both ways, on the main thread, with
     * predictors of their own (the live one belongs to the decode thread).
     * Null outside practice, before a dictionary loads, or without the model.
     */
    fun compare(tokens: List<InputToken>, context: List<String>, target: String?): SwipeTrace.AB? {
        if (target == null || tokens.isEmpty()) return null
        val make = factory ?: return null
        val g = live?.geometry ?: return null
        val b = if (liveBeta != 0f) liveBeta else beta
        val plain = plainShadow ?: make(null, KineticaConstants.TOP_K).also { plainShadow = it }
        val neural = neuralShadow ?: run {
            val s = scorer() ?: return null
            var built: WordPredictor? = null
            make(CtcReranker(s, { built?.geometry }, b), DEPTH).also { built = it; neuralShadow = it }
        }
        plain.geometry = g
        neural.geometry = g
        comparing.set(true)
        try {
            val t0 = SystemClock.elapsedRealtimeNanos()
            val a = plain.decode(tokens, context)
            val t1 = SystemClock.elapsedRealtimeNanos()
            val n = neural.decode(tokens, context)
            val t2 = SystemClock.elapsedRealtimeNanos()
            val ab = SwipeTrace.AB(
                b, MODEL_NAME,
                SwipeTrace.candidates(a.take(SHOWN)), SwipeTrace.candidates(n.take(SHOWN)),
                (t1 - t0) / 1e6f, (t2 - t1) / 1e6f,
            )
            onCompare?.invoke(ab, target)
            return ab
        } catch (e: RuntimeException) {
            // A comparison must never take the keyboard down with it.
            Log.w(TAG, "practice comparison failed", e)
            return null
        } finally {
            comparing.set(false)
        }
    }

    private const val TAG = "KineticaNeural"
    private const val RING = 256

    /** Rows of each arm kept in a trace line. */
    private const val SHOWN = 3
}
