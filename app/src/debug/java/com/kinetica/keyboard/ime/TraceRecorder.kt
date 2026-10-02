package com.kinetica.keyboard.ime

import android.content.Context
import android.util.Log
import com.kinetica.keyboard.engine.DecodeTrace
import com.kinetica.keyboard.engine.GestureEngine
import com.kinetica.keyboard.engine.WordComposer
import com.kinetica.keyboard.engine.trace.SwipeTrace
import com.kinetica.keyboard.engine.trace.SwipeTraceRecorder
import java.io.File
import java.io.IOException

/**
 * Developer build: the decode trace goes to a file as well as to logcat.
 *
 * The trace was logcat-only until 2026-08-18, which meant it could not be read at all
 * from the build that matters - a release APK installs no sink, so every gate step
 * asking for an `fw` value has been unrunnable since the app was published
 * (KNOWN_ISSUES item 28). Writing to a file removes the computer from the loop
 * entirely: type normally for a week, then export.
 *
 * That last part is the point. A trace taken while tethered to a desk is a trace of
 * typing at a desk, and the open engine work is about how two thumbs actually move.
 */
object TraceRecorder {

    /**
     * One rotation at this size, so the file cannot grow without bound while still
     * holding far more than a session: the largest capture in the project's history
     * is 222 KB of a long deliberate run.
     */
    private const val MAX_BYTES = 4L * 1024 * 1024
    private const val DIR = "trace"
    private const val NAME = "kinetica_trace.log"

    private val lock = Any()

    @Volatile
    private var file: File? = null

    @Volatile
    var lines: Long = 0L
        private set

    /**
     * On by default. This build has one purpose and arming it by hand every time is
     * how a week of ordinary typing turns into no data.
     */
    @Volatile
    var recording: Boolean = true

    fun install(context: Context) {
        val dir = File(context.filesDir, DIR)
        val f = File(dir, NAME)
        file = f
        lines = countLines(f)
        words.install(context, File(dir, WORDS_NAME))
        DecodeTrace.sink = { m ->
            Log.d("KineticaTrace", m)
            append(m)
        }
    }

    private fun append(message: String) {
        if (!recording) return
        val f = file ?: return
        synchronized(lock) {
            try {
                f.parentFile?.mkdirs()
                if (f.length() > MAX_BYTES) rotate(f)
                f.appendText(message + "\n")
                lines++
            } catch (e: IOException) {
                // A failed write must never take the keyboard down with it.
                Log.w("KineticaTrace", "trace write failed", e)
            }
        }
    }

    private fun rotate(f: File) {
        val old = File(f.parentFile, "$NAME.1")
        if (old.exists()) old.delete()
        f.renameTo(old)
        lines = 0L
    }

    /** Oldest first, so an exported trace reads in the order it happened. */
    fun readAll(): String {
        val f = file ?: return ""
        synchronized(lock) {
            val previous = File(f.parentFile, "$NAME.1")
            val head = if (previous.exists()) previous.readText() else ""
            val tail = if (f.exists()) f.readText() else ""
            return head + tail
        }
    }

    fun sizeBytes(): Long {
        val f = file ?: return 0L
        val previous = File(f.parentFile, "$NAME.1")
        return (if (f.exists()) f.length() else 0L) + (if (previous.exists()) previous.length() else 0L)
    }

    fun clear() {
        val f = file ?: return
        synchronized(lock) {
            File(f.parentFile, "$NAME.1").delete()
            f.delete()
            lines = 0L
        }
    }

    // ------------------------------------------------------ word traces (v1)

    /**
     * The v1 word trace: every raw touch sample and the committed word, one JSON
     * line per word (see SwipeTrace). Separate from the decode log above and,
     * unlike it, OFF until switched on in the trace screen: a v1 line is a
     * complete, replayable record of what was typed, which is exactly what makes
     * it worth recording and exactly why it needs a yes first. Practice mode
     * records regardless, because opening practice is that yes.
     */
    val words = WordTraces()

    /** The keyboard's engine: its pointer samples become the gestures in each line. */
    fun attachEngine(engine: GestureEngine, info: TraceInfo) {
        words.attach(engine, info)
    }

    /** Each composer the keyboard builds (one per dictionary load). */
    fun attachComposer(composer: WordComposer) {
        composer.observer = words.recorder
    }

    /** How the next committed word was committed: picked, tentative, autocorrect or typed. */
    fun label(how: String) {
        words.recorder?.nextHow = how
    }

    fun correction(from: String, to: String) {
        words.recorder?.onCorrection(from, to)
    }

    class WordTraces {
        private val lock = Any()
        private var prefs: android.content.SharedPreferences? = null
        private var file: File? = null
        var recorder: SwipeTraceRecorder? = null
            private set

        @Volatile
        var lines: Long = 0L
            private set

        /** Set by the practice screen while it is showing: its current prompt. */
        @Volatile
        var practiceTarget: String? = null

        var enabled: Boolean
            get() = prefs?.getBoolean(PREF_ENABLED, false) == true
            set(on) { prefs?.edit()?.putBoolean(PREF_ENABLED, on)?.apply() }

        internal fun install(context: Context, f: File) {
            prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            file = f
            lines = TraceRecorder.countLines(f)
        }

        internal fun attach(engine: GestureEngine, info: TraceInfo) {
            val r = SwipeTraceRecorder(
                {
                    SwipeTrace.Config(
                        info.language(), info.alternate(), info.britishSpelling(),
                        info.personal(), info.dictOverride(),
                    )
                },
                ::append,
            )
            r.enabled = { (enabled || practiceTarget != null) && !info.suppressed() }
            r.target = { practiceTarget }
            recorder = r
            engine.observer = r
        }

        /** Withdraws the most recent word line (the practice screen's "bad swipe"). */
        fun discardLast() {
            if (lines > 0) append(SwipeTrace.encodeDiscard())
        }

        private fun append(line: String) {
            val f = file ?: return
            synchronized(lock) {
                try {
                    f.parentFile?.mkdirs()
                    if (f.length() > MAX_WORD_BYTES) {
                        val old = File(f.parentFile, f.name + ".1")
                        if (old.exists()) old.delete()
                        f.renameTo(old)
                        lines = 0L
                    }
                    f.appendText(line + "\n")
                    lines++
                } catch (e: IOException) {
                    Log.w("KineticaTrace", "word trace write failed", e)
                }
            }
        }

        fun readAll(): String {
            val f = file ?: return ""
            synchronized(lock) {
                val previous = File(f.parentFile, f.name + ".1")
                return (if (previous.exists()) previous.readText() else "") +
                    (if (f.exists()) f.readText() else "")
            }
        }

        fun sizeBytes(): Long {
            val f = file ?: return 0L
            val previous = File(f.parentFile, f.name + ".1")
            return (if (f.exists()) f.length() else 0L) + (if (previous.exists()) previous.length() else 0L)
        }

        fun clear() {
            val f = file ?: return
            synchronized(lock) {
                File(f.parentFile, f.name + ".1").delete()
                f.delete()
                lines = 0L
            }
        }
    }

    private const val WORDS_NAME = "words_v1.jsonl"
    private const val PREFS = "trace_v1"
    private const val PREF_ENABLED = "enabled"

    /** Two files of this size: at ~3 KB a word, about 5 000 words before the oldest go. */
    private const val MAX_WORD_BYTES = 8L * 1024 * 1024

    private fun countLines(f: File): Long =
        if (!f.exists()) 0L else try {
            f.useLines { seq -> seq.count().toLong() }
        } catch (e: IOException) {
            0L
        }
}
