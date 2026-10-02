package com.kinetica.keyboard.engine.trace

/**
 * The smallest JSON reader and writer the trace format needs, because the
 * engine stays free of Android (no org.json) and of third-party libraries.
 *
 * Values map to Kotlin as: object -> Map<String, Any?> (insertion ordered),
 * array -> List<Any?>, string -> String, number -> [JsonNum], true/false ->
 * Boolean, null -> null. Numbers keep their text so a float is parsed straight
 * to a float: the writer prints a decimal that identifies the float uniquely,
 * and parsing it as a float gives that float back bit for bit, whereas going
 * through a double first can round twice.
 */
internal object Json {

    fun parse(text: String): Any? {
        val r = Reader(text)
        r.ws()
        val v = r.value()
        r.ws()
        require(r.i == text.length) { "trailing data at ${r.i}" }
        return v
    }

    private class Reader(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            require(i < s.length) { "unexpected end" }
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else error("unexpected '$c' at $i")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            require(s.startsWith(word, i)) { "bad literal at $i" }
            i += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws()
                val k = str()
                ws()
                require(s[i] == ':') { "expected ':' at $i" }
                i++
                ws()
                m[k] = value()
                ws()
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return m
                    else -> error("expected ',' or '}' at ${i - 1}")
                }
            }
        }

        private fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                ws()
                l.add(value())
                ws()
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return l
                    else -> error("expected ',' or ']' at ${i - 1}")
                }
            }
        }

        private fun str(): String {
            require(s[i] == '"') { "expected string at $i" }
            i++
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[i++]) {
                        '"', '\\', '/' -> sb.append(e)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000c')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> error("bad escape at ${i - 1}")
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): JsonNum {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return JsonNum(s.substring(start, i))
        }
    }
}

@JvmInline
internal value class JsonNum(val text: String) {
    fun toFloat(): Float = text.toFloat()
    fun toLong(): Long = text.toLong()
    fun toInt(): Int = text.toInt()
    fun toDouble(): Double = text.toDouble()
}

/** Streaming writer: the caller is responsible for balanced begin/end calls. */
internal class JsonWriter {
    private val sb = StringBuilder(1024)
    // One flag per open container: whether the next element needs a comma.
    private val needComma = ArrayList<Boolean>()
    private var afterKey = false

    private fun sep() {
        if (afterKey) { afterKey = false; return }
        if (needComma.isNotEmpty()) {
            if (needComma[needComma.size - 1]) sb.append(',')
            needComma[needComma.size - 1] = true
        }
    }

    fun beginObject() = apply { sep(); sb.append('{'); needComma.add(false) }
    fun endObject() = apply { needComma.removeAt(needComma.size - 1); sb.append('}') }
    fun beginArray() = apply { sep(); sb.append('['); needComma.add(false) }
    fun endArray() = apply { needComma.removeAt(needComma.size - 1); sb.append(']') }

    fun key(k: String) = apply { sep(); string(k); sb.append(':'); afterKey = true }

    fun value(v: String?) = apply { sep(); if (v == null) sb.append("null") else string(v) }
    fun value(v: Boolean) = apply { sep(); sb.append(v) }
    fun value(v: Long) = apply { sep(); sb.append(v) }
    fun value(v: Int) = apply { sep(); sb.append(v) }

    fun value(v: Float) = apply {
        require(v.isFinite()) { "non-finite float in trace: $v" }
        sep()
        sb.append(v.toString())
    }

    private fun string(v: String) {
        sb.append('"')
        for (c in v) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append("\\u%04x".format(java.util.Locale.ROOT, c.code))
            else -> sb.append(c)
        }
        sb.append('"')
    }

    override fun toString(): String = sb.toString()
}
