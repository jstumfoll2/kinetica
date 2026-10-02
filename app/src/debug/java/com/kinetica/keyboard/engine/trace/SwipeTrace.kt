package com.kinetica.keyboard.engine.trace

import com.kinetica.keyboard.engine.Alphabet
import com.kinetica.keyboard.engine.GestureEngine
import com.kinetica.keyboard.engine.KeyboardGeometry
import com.kinetica.keyboard.engine.models.Dwell
import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.KeyContact
import com.kinetica.keyboard.engine.models.PathPoint
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.SwipeToken
import com.kinetica.keyboard.engine.models.TapToken
import com.kinetica.keyboard.engine.models.WordCandidate

/**
 * Trace format v1: one JSON object per line, one line per word buffer.
 *
 * The existing DecodeTrace lines keep key contacts, not the path, so a decode
 * rebuilt from them is a reachability fixture, not a ranking one (replaying
 * them matched the full list in 6 of 764 synthetic cases). v1 records what the
 * streams were built from - the geometry and every raw pointer sample of each
 * gesture - so replay drives a fresh [GestureEngine] and reproduces the tokens,
 * and from them the decode, exactly.
 *
 * A line holds, in order:
 *  - `v`, `type`: 1, "word".
 *  - `cfg`: what the decode depended on besides the input: active language,
 *    the other language decoded alongside it (or null), British spelling, and
 *    two flags, `personal` (per-user counts, pairs, user words or blocked
 *    words were loaded) and `dictOverride` (a wordlist replaced the bundled
 *    one). With either flag set the bundled dictionaries cannot reproduce the
 *    live list, and the harness says so rather than counting a mismatch.
 *  - `geom`: key width in px, the stream-split midline in px, the tap
 *    displacement bound in px, and each letter's rect in kw (left, top, right,
 *    bottom). Stored in kw because that is what the engine uses; see
 *    [KeyboardGeometry.fromKw].
 *  - `ctx`: the composer's context, oldest first, lowercased.
 *  - `tokens`: the buffer in insertion order. `src:"ev"` is a gesture as its
 *    samples: `s` the stream the engine assigned, `ev` rows of [x px, y px,
 *    t ms], down first, lift last. Tokens that never came from the engine (a
 *    word reloaded from the editor as taps, an accent-popup letter) are
 *    `src:"tap"` or `src:"swipe"`, written field for field.
 *  - `shown`: the last candidate list the bar received, `n` the token count
 *    its decode saw and `c` rows of [word, score, language]. `n` short of the
 *    buffer means the word was committed before its final decode landed.
 *  - `word`: what was committed, or null for an abandoned buffer. `how`: how it
 *    was committed, when the recorder's owner knows (null otherwise):
 *    "picked" (from the bar), "tentative" (a decode a delimiter committed),
 *    "autocorrect" (tap autocorrect replaced the literal), "typed" (the tap
 *    literal as is).
 *  - `target`: in practice mode, the word the user was asked to type. It is
 *    the ground-truth label; `word` is what the keyboard made of it.
 *
 * A second line type, `{"v":1,"type":"correction","from":..,"to":..}`, records a
 * pick from the correction strip after a commit: the most recent word line
 * whose `word` is `from` was really meant as `to`.
 *
 * A third, `{"v":1,"type":"discard"}`, withdraws the most recent word line: the
 * practice screen writes it when the person says their last attempt was a bad
 * swipe, so a sloppy attempt never becomes a labelled example.
 *
 * Personal data: every line carries what was typed, so a trace file is
 * personal by construction and never belongs in the repository. What is
 * deliberately absent is the personal dictionary state itself; `cfg.personal`
 * only says whether it existed.
 */
object SwipeTrace {
    const val VERSION = 1

    data class Config(
        val language: String,
        val alternate: String? = null,
        val britishSpelling: Boolean = false,
        val personal: Boolean = false,
        val dictOverride: Boolean = false,
    )

    data class Geometry(
        val keyWidthPx: Float,
        val midlinePx: Float,
        val tapMaxDispPx: Float,
        /** Letter code to (left, top, right, bottom) in kw. */
        val keys: Map<Int, FloatArray>,
    ) {
        fun build(): KeyboardGeometry {
            val codes = keys.keys.toIntArray()
            return KeyboardGeometry.fromKw(keyWidthPx, midlinePx, codes.map { keys.getValue(it) }, codes)
        }

        fun sameAs(o: Geometry): Boolean =
            keyWidthPx.toRawBits() == o.keyWidthPx.toRawBits() &&
                midlinePx.toRawBits() == o.midlinePx.toRawBits() &&
                tapMaxDispPx.toRawBits() == o.tapMaxDispPx.toRawBits() &&
                keys.keys == o.keys.keys &&
                keys.all { (k, r) -> r.contentEquals(o.keys.getValue(k)) }

        companion object {
            fun of(g: KeyboardGeometry, tapMaxDispPx: Float): Geometry {
                val keys = LinkedHashMap<Int, FloatArray>()
                for (code in 0 until Alphabet.LETTERS) g.rectKw(code)?.let { keys[code] = it }
                return Geometry(g.keyWidthPx, g.midlinePx, tapMaxDispPx, keys)
            }
        }
    }

    /** One raw sample, px and ms, as the view handed it to the engine. */
    data class Sample(val x: Float, val y: Float, val t: Long)

    sealed class Token

    /** A gesture as samples: down first, lift last. */
    data class Gesture(val stream: StreamId, val samples: List<Sample>) : Token()

    /** A token that did not come from the engine, kept as is. */
    data class Literal(val token: InputToken) : Token()

    data class Shown(val tokenCount: Int, val candidates: List<Candidate>)

    data class Candidate(val word: String, val score: Float, val language: String)

    data class Word(
        val config: Config,
        val geometry: Geometry,
        val context: List<String>,
        val tokens: List<Token>,
        val shown: Shown,
        val committed: String?,
        val how: String? = null,
        val target: String? = null,
    ) {
        /** What the user meant: the practice prompt when there was one, else the commit. */
        val label: String? get() = target ?: committed

        /** Whether [shown] can be compared with a replay: same input, same dictionaries. */
        val comparable: Boolean
            get() = !config.personal && !config.dictOverride && shown.tokenCount == tokens.size
    }

    fun candidates(list: List<WordCandidate>): List<Candidate> =
        list.map { Candidate(it.word, it.score, it.language) }

    // ---------------------------------------------------------------- replay

    /**
     * Rebuilds the buffer: each gesture through its own fresh [GestureEngine].
     *
     * One engine per gesture rather than one for the whole word, because a
     * stream depends only on its own samples and the geometry, while the
     * engine's stream assignment depends on what else was down at the time,
     * which may include a pointer outside this buffer. The recorded stream id
     * is therefore applied, not re-derived.
     */
    fun replayTokens(w: Word): List<InputToken> {
        val g = w.geometry.build()
        return w.tokens.map { t ->
            when (t) {
                is Literal -> t.token
                is Gesture -> withStream(runGesture(g, w.geometry.tapMaxDispPx, t), t.stream)
            }
        }
    }

    private fun runGesture(g: KeyboardGeometry, tapMaxDispPx: Float, t: Gesture): InputToken {
        var out: InputToken? = null
        val e = GestureEngine(object : GestureEngine.Listener {
            override fun onTokenFinalized(token: InputToken) { out = token }
            override fun onKeyTransition(streamId: StreamId, code: Int) = Unit
            override fun onAllPointersUp() = Unit
        })
        e.setGeometry(g, tapMaxDispPx)
        val s = t.samples
        require(s.size >= 2) { "a gesture needs a down and a lift" }
        check(e.onPointerDown(0, s[0].x, s[0].y, s[0].t)) { "replayed down landed off the letter keys" }
        for (i in 1 until s.size - 1) e.onPointerMove(0, s[i].x, s[i].y, s[i].t)
        val up = s[s.size - 1]
        e.onPointerUp(0, up.x, up.y, up.t)
        return checkNotNull(out)
    }

    private fun withStream(t: InputToken, s: StreamId): InputToken = when {
        t.streamId == s -> t
        t is TapToken -> t.copy(streamId = s)
        t is SwipeToken -> SwipeToken(
            s, t.rawPath, t.resampled, t.keyContacts, t.arcLen, t.tStart, t.tEnd,
            t.softStart, t.softEnd, t.dwells,
        )
        else -> t
    }

    // ---------------------------------------------------------------- encode

    fun encode(w: Word): String {
        val j = JsonWriter().beginObject()
        j.key("v").value(VERSION).key("type").value("word")
        j.key("cfg").beginObject()
            .key("lang").value(w.config.language)
            .key("alt").value(w.config.alternate)
            .key("britishSpelling").value(w.config.britishSpelling)
            .key("personal").value(w.config.personal)
            .key("dictOverride").value(w.config.dictOverride)
            .endObject()
        val g = w.geometry
        j.key("geom").beginObject()
            .key("kwPx").value(g.keyWidthPx)
            .key("midPx").value(g.midlinePx)
            .key("tapPx").value(g.tapMaxDispPx)
            .key("keys").beginObject()
        for ((code, r) in g.keys) {
            j.key(Alphabet.charOf(code).toString()).beginArray()
            for (v in r) j.value(v)
            j.endArray()
        }
        j.endObject().endObject()
        j.key("ctx").beginArray()
        for (c in w.context) j.value(c)
        j.endArray()
        j.key("tokens").beginArray()
        for (t in w.tokens) writeToken(j, t)
        j.endArray()
        j.key("shown").beginObject().key("n").value(w.shown.tokenCount).key("c").beginArray()
        for (c in w.shown.candidates) j.beginArray().value(c.word).value(c.score).value(c.language).endArray()
        j.endArray().endObject()
        j.key("word").value(w.committed)
        j.key("how").value(w.how)
        j.key("target").value(w.target)
        return j.endObject().toString()
    }

    fun encodeCorrection(from: String, to: String): String =
        JsonWriter().beginObject().key("v").value(VERSION).key("type").value("correction")
            .key("from").value(from).key("to").value(to).endObject().toString()

    fun encodeDiscard(): String =
        JsonWriter().beginObject().key("v").value(VERSION).key("type").value("discard").endObject().toString()

    /** A line of any type: a [Word], a correction as (from, to), or a discard. */
    sealed class Line {
        data class WordLine(val word: Word) : Line()
        data class Correction(val from: String, val to: String) : Line()
        object Discard : Line()
    }

    @Suppress("UNCHECKED_CAST")
    fun decodeLine(line: String): Line {
        val o = Json.parse(line) as Map<String, Any?>
        return when (o["type"]) {
            "correction" -> {
                require((o["v"] as JsonNum).toInt() == VERSION) { "unsupported trace version ${o["v"]}" }
                Line.Correction(o["from"] as String, o["to"] as String)
            }
            "discard" -> {
                require((o["v"] as JsonNum).toInt() == VERSION) { "unsupported trace version ${o["v"]}" }
                Line.Discard
            }
            else -> Line.WordLine(decode(o))
        }
    }

    private fun writeToken(j: JsonWriter, t: Token) {
        j.beginObject()
        when (t) {
            is Gesture -> {
                j.key("src").value("ev").key("s").value(t.stream.name.substring(0, 1))
                j.key("ev").beginArray()
                for (p in t.samples) j.beginArray().value(p.x).value(p.y).value(p.t).endArray()
                j.endArray()
            }
            is Literal -> when (val k = t.token) {
                is TapToken -> j.key("src").value("tap").key("s").value(k.streamId.name.substring(0, 1))
                    .key("k").value(Alphabet.charOf(k.code).toString())
                    .key("x").value(k.x).key("y").value(k.y).key("lp").value(k.longPress)
                    .key("t0").value(k.tStart).key("t1").value(k.tEnd)
                is SwipeToken -> {
                    j.key("src").value("swipe").key("s").value(k.streamId.name.substring(0, 1))
                        .key("t0").value(k.tStart).key("t1").value(k.tEnd)
                        .key("softStart").value(k.softStart).key("softEnd").value(k.softEnd)
                        .key("arc").value(k.arcLen)
                    j.key("path").beginArray()
                    for (p in k.rawPath) j.beginArray().value(p.x).value(p.y).value(p.t).endArray()
                    j.endArray().key("res").beginArray()
                    for (v in k.resampled) j.value(v)
                    j.endArray().key("contacts").beginArray()
                    for (c in k.keyContacts) j.beginArray().value(Alphabet.charOf(c.code).toString()).value(c.tEnter).value(c.tExit).endArray()
                    j.endArray().key("dwells").beginArray()
                    for (d in k.dwells) j.beginArray().value(d.enterIdx).value(d.exitIdx).value(d.tEnter).value(d.tExit).endArray()
                    j.endArray()
                }
            }
        }
        j.endObject()
    }

    // ---------------------------------------------------------------- decode

    /** Parses one line; throws on anything that is not a v1 word line. */
    @Suppress("UNCHECKED_CAST")
    fun decode(line: String): Word = decode(Json.parse(line) as Map<String, Any?>)

    @Suppress("UNCHECKED_CAST")
    private fun decode(o: Map<String, Any?>): Word {
        require((o["v"] as JsonNum).toInt() == VERSION) { "unsupported trace version ${o["v"]}" }
        require(o["type"] == "word") { "not a word line: ${o["type"]}" }
        val c = o["cfg"] as Map<String, Any?>
        val cfg = Config(
            c["lang"] as String, c["alt"] as String?, c["britishSpelling"] as Boolean,
            c["personal"] as Boolean, c["dictOverride"] as Boolean,
        )
        val g = o["geom"] as Map<String, Any?>
        val keys = LinkedHashMap<Int, FloatArray>()
        for ((k, r) in g["keys"] as Map<String, Any?>) {
            keys[Alphabet.codeOf(k[0])] = (r as List<Any?>).map { f(it) }.toFloatArray()
        }
        val geom = Geometry(f(g["kwPx"]), f(g["midPx"]), f(g["tapPx"]), keys)
        val tokens = (o["tokens"] as List<Any?>).map { readToken(it as Map<String, Any?>) }
        val s = o["shown"] as Map<String, Any?>
        val shown = Shown(
            (s["n"] as JsonNum).toInt(),
            (s["c"] as List<Any?>).map { val r = it as List<Any?>; Candidate(r[0] as String, f(r[1]), r[2] as String) },
        )
        return Word(
            cfg, geom, (o["ctx"] as List<Any?>).map { it as String }, tokens, shown,
            o["word"] as String?, o["how"] as String?, o["target"] as String?,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun readToken(m: Map<String, Any?>): Token {
        val stream = if (m["s"] == "L") StreamId.LEFT else StreamId.RIGHT
        return when (m["src"]) {
            "ev" -> Gesture(stream, (m["ev"] as List<Any?>).map { val r = it as List<Any?>; Sample(f(r[0]), f(r[1]), l(r[2])) })
            "tap" -> Literal(
                TapToken(stream, Alphabet.codeOf((m["k"] as String)[0]), f(m["x"]), f(m["y"]), m["lp"] as Boolean, l(m["t0"]), l(m["t1"])),
            )
            "swipe" -> Literal(
                SwipeToken(
                    stream,
                    (m["path"] as List<Any?>).map { val r = it as List<Any?>; PathPoint(f(r[0]), f(r[1]), l(r[2])) },
                    (m["res"] as List<Any?>).map { f(it) }.toFloatArray(),
                    (m["contacts"] as List<Any?>).map { val r = it as List<Any?>; KeyContact(Alphabet.codeOf((r[0] as String)[0]), l(r[1]), l(r[2])) },
                    f(m["arc"]), l(m["t0"]), l(m["t1"]), m["softStart"] as Boolean, m["softEnd"] as Boolean,
                    (m["dwells"] as List<Any?>).map { val r = it as List<Any?>; Dwell(i(r[0]), i(r[1]), l(r[2]), l(r[3])) },
                ),
            )
            else -> error("unknown token source ${m["src"]}")
        }
    }

    private fun f(v: Any?) = (v as JsonNum).toFloat()
    private fun l(v: Any?) = (v as JsonNum).toLong()
    private fun i(v: Any?) = (v as JsonNum).toInt()
}
