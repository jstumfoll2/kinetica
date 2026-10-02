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
import java.io.IOException
import java.util.Random

/**
 * Practice mode: shows one word at a time and records how it was swiped.
 *
 * The point is the label. An ordinary word trace is labelled by what the keyboard
 * committed, which is the decoder's own answer and useless for measuring the
 * decoder where it is wrong; here the prompt is the ground truth, stored as the
 * line's `target`. Words are drawn from the bundled English list, weighted toward
 * the buckets the replay report splits on (short words, double letters, long words).
 *
 * Each prompt is asked [REPEATS] times, since one attempt says little about how a
 * word is usually drawn, and "Discard last" withdraws an attempt the person knows
 * went wrong (a discard line in the trace) and asks for it again.
 *
 * Recording runs only while this screen is in front, whatever the trace toggle
 * says: opening practice is the consent. Personal data is the same as any word
 * trace - the typed words - but here the words were chosen by the app.
 */
class PracticeActivity : AppCompatActivity() {

    private lateinit var prompt: TextView
    private lateinit var progress: TextView
    private lateinit var input: EditText
    private val rnd = Random()
    private var words: List<String> = emptyList()
    private var done = 0
    private var hits = 0

    // Attempts made at the current prompt, and whether the last one hit it.
    private var attempt = 0
    private var lastHit = false
    private var lastCounted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.practice_title)
        val pad = (16 * resources.displayMetrics.density).toInt()
        words = loadWords()

        prompt = TextView(this).apply { textSize = 34f }
        progress = TextView(this).apply { textSize = 14f }
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
            addView(input)
            addView(progress)
            addView(discard)
            addView(explain)
        }
        setContentView(column)
        next()
    }

    override fun onResume() {
        super.onResume()
        TraceRecorder.words.practiceTarget = prompt.text.toString().ifEmpty { null }
    }

    override fun onPause() {
        TraceRecorder.words.practiceTarget = null
        super.onPause()
    }

    private fun next() {
        val w = if (words.isEmpty()) "hello" else pick()
        prompt.text = w
        attempt = 0
        TraceRecorder.words.practiceTarget = w
        showProgress()
    }

    private fun showProgress() {
        progress.text = getString(R.string.practice_progress, attempt + 1, REPEATS, done, hits)
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
        lastCounted = false
        if (attempt > 0) attempt--
        input.text.clear()
        showProgress()
    }

    /** One in four short, one in four with a double letter, the rest any length. */
    private fun pick(): String {
        val pool = when (rnd.nextInt(4)) {
            0 -> words.filter { it.length <= 3 }
            1 -> words.filter { w -> (1 until w.length).any { w[it] == w[it - 1] } }
            else -> words
        }.ifEmpty { words }
        return pool[rnd.nextInt(pool.size)]
    }

    private fun loadWords(): List<String> = try {
        assets.open("dictionaries/en_wordlist.txt").bufferedReader().useLines { lines ->
            lines.map { it.substringBefore('\t') }
                .filter { w -> w.length in 2..12 && w.all { it in 'a'..'z' } }
                .take(PROMPT_POOL)
                .toList()
        }
    } catch (e: IOException) {
        emptyList()
    }

    private companion object {
        /** Attempts per prompt. */
        const val REPEATS = 3

        /** The most frequent words only: a prompt nobody would type teaches nothing. */
        const val PROMPT_POOL = 5000
    }
}
