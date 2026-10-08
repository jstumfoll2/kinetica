package com.kinetica.keyboard.ime

import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import com.kinetica.keyboard.R
import com.kinetica.keyboard.engine.trace.PracticeWords
import com.kinetica.keyboard.engine.trace.SwipeTrace
import java.io.IOException
import java.util.Random

/**
 * Practice mode: shows one word at a time and records how it was swiped.
 *
 * The point is the label. An ordinary word trace is labelled by what the keyboard
 * committed, which is the decoder's own answer and useless for measuring the
 * decoder where it is wrong; here the prompt is the ground truth, stored as the
 * line's `target`. Words are drawn from the bundled English list, weighted toward
 * the shapes the decoder still misreads ([PracticeWords]); for the two-thumb and
 * one-finger kinds a hint under the word says how to draw it.
 *
 * Each prompt is asked [REPEATS] times, since one attempt says little about how a
 * word is usually drawn, and "Discard last" withdraws an attempt the person knows
 * went wrong (a discard line in the trace) and asks for it again.
 *
 * Each recorded attempt is also decoded both without and with the neural rerank
 * ([NeuralRerank.compare]), whichever one the keyboard is live on, and the
 * running score of the two is shown under the prompt.
 *
 * Recording runs only while this screen is in front, whatever the trace toggle
 * says: opening practice is the consent. Personal data is the same as any word
 * trace - the typed words - but here the words were chosen by the app.
 */
class PracticeActivity : AppCompatActivity() {

    private lateinit var prompt: TextView
    private lateinit var hint: TextView
    private lateinit var progress: TextView
    private lateinit var input: EditText
    private lateinit var versus: TextView
    private val rnd = Random()
    private var words = PracticeWords.Pool(emptyList())
    private var done = 0
    private var hits = 0

    // Attempts made at the current prompt, and whether the last one hit it.
    private var attempt = 0
    private var lastHit = false
    private var lastCounted = false

    // The two decoders on this screen's attempts: compared, and how often each got the prompt.
    private var compared = 0
    private var plainHits = 0
    private var neuralHits = 0
    private var lastAb: Pair<Boolean, Boolean>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.practice_title)
        val pad = (16 * resources.displayMetrics.density).toInt()
        words = loadWords()

        prompt = TextView(this).apply { textSize = 34f }
        hint = TextView(this).apply { textSize = 16f }
        progress = TextView(this).apply { textSize = 14f }
        versus = TextView(this).apply {
            textSize = 14f
            text = getString(R.string.practice_ab_none)
        }
        val explain = TextView(this).apply {
            text = getString(R.string.practice_explain)
            textSize = 13f
        }
        input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_NONE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable) {
                    // A delimiter ends the word; by then the keyboard has already
                    // committed it and the trace line has taken this prompt.
                    val text = s.toString()
                    if (text.isNotEmpty() && text.last().isWhitespace()) {
                        val typed = text.trim()
                        if (typed.isNotEmpty()) {
                            done++
                            lastHit = typed.equals(prompt.text.toString(), ignoreCase = true)
                            if (lastHit) hits++
                            lastCounted = true
                            s.clear()
                            attempt++
                            if (attempt >= REPEATS) next() else showProgress()
                        }
                    }
                }
            })
        }
        val discard = Button(this).apply {
            text = getString(R.string.practice_discard)
            setOnClickListener { discardLast() }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad)
            addView(prompt)
            addView(hint)
            addView(input)
            addView(progress)
            addView(versus)
            addView(discard)
            addView(explain)
        }
        setContentView(column)
        next()
    }

    override fun onResume() {
        super.onResume()
        TraceRecorder.words.practiceTarget = prompt.text.toString().ifEmpty { null }
        NeuralRerank.onCompare = { ab, target -> onCompared(ab, target) }
    }

    override fun onPause() {
        NeuralRerank.onCompare = null
        TraceRecorder.words.practiceTarget = null
        super.onPause()
    }

    private fun next() {
        val (w, kind) = words.pick(rnd)
        prompt.text = w
        hint.text = kind.hint.orEmpty()
        attempt = 0
        TraceRecorder.words.practiceTarget = w
        showProgress()
    }

    private fun showProgress() {
        progress.text = getString(R.string.practice_progress, attempt + 1, REPEATS, done, hits)
    }

    private fun onCompared(ab: SwipeTrace.AB, target: String) {
        val plain = ab.plain.firstOrNull()?.word
        val neural = ab.neural.firstOrNull()?.word
        val hit = plain.equals(target, ignoreCase = true) to neural.equals(target, ignoreCase = true)
        compared++
        if (hit.first) plainHits++
        if (hit.second) neuralHits++
        lastAb = hit
        versus.text = getString(R.string.practice_ab, plainHits, compared, neuralHits, compared) + "\n" +
            getString(
                R.string.practice_ab_last, plain ?: "-", "%.0f".format(ab.plainMs),
                neural ?: "-", "%.0f".format(ab.neuralMs),
            )
    }

    /**
     * Withdraws the last recorded attempt. Within a prompt it is asked again; on
     * the first attempt of a new prompt it withdraws the previous prompt's last
     * attempt, and the new prompt stays.
     */
    private fun discardLast() {
        if (!lastCounted) return
        TraceRecorder.words.discardLast()
        done--
        if (lastHit) hits--
        lastAb?.let { (p, n) ->
            compared--
            if (p) plainHits--
            if (n) neuralHits--
            versus.text = getString(R.string.practice_ab, plainHits, compared, neuralHits, compared)
        }
        lastAb = null
        lastCounted = false
        if (attempt > 0) attempt--
        input.text.clear()
        showProgress()
    }

    private fun loadWords(): PracticeWords.Pool = PracticeWords.Pool(try {
        assets.open("dictionaries/en_wordlist.txt").bufferedReader().useLines { lines ->
            lines.map { it.substringBefore('\t') }
                .filter { w -> w.length in 2..MAX_LENGTH && w.all { it in 'a'..'z' } }
                .take(PROMPT_POOL)
                .toList()
        }
    } catch (e: IOException) {
        emptyList()
    })

    private companion object {
        /** Attempts per prompt. */
        const val REPEATS = 3

        /** The most frequent words only: a prompt nobody would type teaches nothing. */
        const val PROMPT_POOL = 12000

        /** Long enough for "specifically" and "technically" and their kin. */
        const val MAX_LENGTH = 15
    }
}
