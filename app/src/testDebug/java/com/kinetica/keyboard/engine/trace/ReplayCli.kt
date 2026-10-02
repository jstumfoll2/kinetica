package com.kinetica.keyboard.engine.trace

import com.kinetica.keyboard.engine.CtcScorer
import java.io.File

/**
 * Command line for the replay harness. Run through `tools/replay` (see its
 * README), which builds the engine and this harness as a plain JVM program.
 *
 *   replay [--assets DIR] [--k N] TRACE.jsonl...
 *       Replays every v1 line, checks exactness, prints top-1, top-3, MRR and
 *       recall@N (default 50) overall and per bucket. Exits 1 when any
 *       comparable line fails to reproduce.
 *       With --ctc MODEL.kctc [--beta B] [--depth D] the shipping run adds the
 *       stage-A CTC rerank over a heap D deep (default 50).
 *   tune --ctc MODEL.kctc [--betas 0,0.05,...] [--depth D] TRACE.jsonl...
 *       Picks beta on one half of the lines (by top-1, then MRR) and reports
 *       it once against beta 0 on the other half. With several files the
 *       split is by file, which keeps a session on one side; with one file it
 *       alternates lines.
 *   synth [--assets DIR] [--words N] [--alt LANG] [--noise X] [--seed S] OUT.jsonl
 *       Records a synthetic English session to OUT (default 300 words).
 */
object ReplayCli {

    @JvmStatic
    fun main(args: Array<String>) {
        val a = args.toMutableList()
        val cmd = a.removeFirstOrNull() ?: usage()
        val assets = File(opt(a, "--assets") ?: defaultAssets())
        when (cmd) {
            "replay" -> {
                val k = opt(a, "--k")?.toInt() ?: ReplayHarness.DEFAULT_DEEP_K
                val ctc = opt(a, "--ctc")?.let { f -> File(f).inputStream().use { CtcScorer.load(it) } }
                val beta = opt(a, "--beta")?.toFloat() ?: 0f
                val depth = opt(a, "--depth")?.toInt() ?: ReplayHarness.DEFAULT_DEEP_K
                val ilw = opt(a, "--ilw")?.toFloat()
                if (a.isEmpty()) usage()
                val harness = if (ilw == null) {
                    ReplayHarness(assets, k, ctc, beta, depth)
                } else {
                    ReplayHarness(assets, k, ctc, beta, depth, interleave = ilw > 0f, interleaveWeight = ilw)
                }
                val report = run(harness, a.map { File(it) })
                println(report.format())
                if (ctc == null && report.exact < report.comparable) System.exit(1)
            }
            "misses" -> {
                if (a.isEmpty()) usage()
                val h = ReplayHarness(assets)
                val sink = ReplayReport(h.deepK)
                for (f in a.map { File(it) }) for ((n, w) in read(f, sink)) {
                    val r = try { h.replay(w) } catch (e: RuntimeException) { continue }
                    if (r.rank == 1) continue
                    println(describe(n, r))
                }
            }
            "interleave" -> {
                if (a.isEmpty()) usage()
                println(interleaveReport(assets, a.map { File(it) }))
            }
            "tune" -> {
                val ctc = File(opt(a, "--ctc") ?: usage()).inputStream().use { CtcScorer.load(it) }
                val betas = (opt(a, "--betas") ?: "0,0.02,0.05,0.1,0.2,0.3,0.5,0.8,1.2")
                    .split(",").map { it.trim().toFloat() }
                val depth = opt(a, "--depth")?.toInt() ?: ReplayHarness.DEFAULT_DEEP_K
                if (a.isEmpty()) usage()
                println(tune(assets, ctc, betas, depth, a.map { File(it) }))
            }
            "synth" -> {
                val n = opt(a, "--words")?.toInt() ?: 300
                val alt = opt(a, "--alt")
                val noise = opt(a, "--noise")?.toFloat() ?: 1f
                val seed = opt(a, "--seed")?.toLong() ?: 7L
                val out = File(a.singleOrNull() ?: usage())
                val s = SyntheticSession(assets, seed, noise)
                out.bufferedWriter().use { w -> s.record(s.words("en", n), "en", alt) { w.write(it); w.newLine() } }
                println("wrote ${out.path}")
            }
            else -> usage()
        }
    }

    fun run(harness: ReplayHarness, files: List<File>): ReplayReport {
        val report = ReplayReport(harness.deepK)
        for (f in files) {
            for ((lineNo, w) in read(f, report)) {
                try {
                    report.add(harness.replay(w))
                } catch (e: RuntimeException) {
                    report.error(lineNo, e)
                }
            }
        }
        return report
    }

    /**
     * Word lines of [f] with their line numbers, correction and discard lines
     * applied: a correction relabels the most recent earlier word committed as
     * its `from`, a discard drops the most recent earlier word.
     */
    fun read(f: File, report: ReplayReport): List<Pair<Int, SwipeTrace.Word>> {
        val words = ArrayList<Pair<Int, SwipeTrace.Word>>()
        f.useLines { lines ->
            for ((i, line) in lines.withIndex()) {
                if (line.isBlank()) continue
                try {
                    when (val l = SwipeTrace.decodeLine(line)) {
                        is SwipeTrace.Line.WordLine -> words.add(i + 1 to l.word)
                        is SwipeTrace.Line.Discard -> words.removeLastOrNull()
                        is SwipeTrace.Line.Correction -> {
                            val k = words.indexOfLast { it.second.committed.equals(l.from, ignoreCase = true) }
                            if (k >= 0) {
                                val (n, w) = words[k]
                                words[k] = n to w.copy(committed = l.to, how = "corrected")
                            }
                        }
                    }
                } catch (e: RuntimeException) {
                    report.error(i + 1, e)
                }
            }
        }
        return words
    }

    /**
     * One line per miss: label, shipping and deep rank, buckets, the top three,
     * then each token in time order - T for a tap (key), S for a swipe (its key
     * contacts, arc length) - with stream, start and end relative to the first.
     */
    fun describe(lineNo: Int, r: ReplayHarness.Result): String {
        val sb = StringBuilder()
        sb.append("#$lineNo ${r.word.label} rank=${r.rank} deep=${r.deepRank} ${r.buckets.joinToString(",")}")
        sb.append(" top=").append(r.shipping.take(3).joinToString("/") { it.word })
        val t0 = r.tokens.minOfOrNull { it.tStart } ?: 0L
        for (t in r.tokens.sortedBy { it.tStart }) {
            val s = if (t.streamId == com.kinetica.keyboard.engine.models.StreamId.LEFT) "L" else "R"
            sb.append("\n    ")
            when (t) {
                is com.kinetica.keyboard.engine.models.TapToken ->
                    sb.append("T$s ${com.kinetica.keyboard.engine.Alphabet.charOf(t.code)}")
                is com.kinetica.keyboard.engine.models.SwipeToken -> {
                    sb.append("S$s ")
                    t.keyContacts.forEach { sb.append(com.kinetica.keyboard.engine.Alphabet.charOf(it.code)) }
                    sb.append(String.format(java.util.Locale.ROOT, " arc=%.1f dwells=%d", t.arcLen, t.dwells.size))
                }
            }
            sb.append(" ${t.tStart - t0}..${t.tEnd - t0}ms")
        }
        return sb.toString()
    }

    /**
     * The interleaved two-thumb reading on its own: for every line it applies
     * to, the label's rank among [com.kinetica.keyboard.engine.InterleavedSearch]
     * hits under several geometric scales, next to the shipping rank.
     */
    fun interleaveReport(assets: File, files: List<File>): String {
        val h = ReplayHarness(assets)
        val sink = ReplayReport(h.deepK)
        val scales = listOf(1f, 2f, 3f, 5f, 8f, 12f)
        val top1 = IntArray(scales.size)
        val top3 = IntArray(scales.size)
        val any = IntArray(1)
        var n = 0
        var ship1 = 0
        var ship3 = 0
        var maxMs = 0L
        val times = ArrayList<Long>()
        val sb = StringBuilder()
        for (f in files) for ((lineNo, w) in read(f, sink)) {
            val label = w.label?.lowercase() ?: continue
            val tokens = SwipeTrace.replayTokens(w)
            val g = w.geometry.build()
            val il = com.kinetica.keyboard.engine.Interleave.of(tokens, g) ?: continue
            val trie = h.trieFor(w.config.language, w.config.britishSpelling)
            val t0 = System.nanoTime()
            val search = com.kinetica.keyboard.engine.InterleavedSearch(trie, il)
            val hits = search.run(400)
            val ms = (System.nanoTime() - t0) / 1_000_000
            times.add(ms)
            if (ms > maxMs) maxMs = ms
            val r = h.replay(w)
            n++
            if (r.rank == 1) ship1++
            if (r.rank in 1..3) ship3++
            if (hits.any { it.word == label }) any[0]++
            val ranks = scales.map { sc ->
                val sorted = hits.sortedByDescending { it.fw * kotlin.math.exp(-sc * it.cost * it.cost) }
                sorted.indexOfFirst { it.word == label } + 1
            }
            for ((i, rk) in ranks.withIndex()) {
                if (rk == 1) top1[i]++
                if (rk in 1..3) top3[i]++
            }
            val best = hits.sortedByDescending { it.fw * kotlin.math.exp(-3f * it.cost * it.cost) }.take(3)
            sb.appendLine("#$lineNo $label ship=${r.rank} il=${ranks.joinToString("/")} nodes=${search.visited} ${ms}ms top=${best.joinToString("/") { it.word + String.format(java.util.Locale.ROOT, ":%.2f", it.cost) }}")
        }
        times.sort()
        sb.appendLine("lines=$n shipping top1=${pct(ship1, n)} top3=${pct(ship3, n)}; label among hits ${pct(any[0], n)}")
        for ((i, sc) in scales.withIndex()) sb.appendLine("  scale $sc: top1=${pct(top1[i], n)} top3=${pct(top3[i], n)}")
        if (times.isNotEmpty()) sb.appendLine("search ms on this JVM: p50 ${times[times.size / 2]} p95 ${times[(times.size * 95) / 100]} max $maxMs")
        return sb.toString()
    }

    private fun pct(a: Int, n: Int) = String.format(java.util.Locale.ROOT, "%.1f%%", 100.0 * a / maxOf(n, 1))

    /** Beta chosen on the tuning half, then one comparison on the held-out half. */
    fun tune(assets: File, ctc: CtcScorer, betas: List<Float>, depth: Int, files: List<File>): String {
        val sink = ReplayReport(ReplayHarness.DEFAULT_DEEP_K)
        val perFile = files.map { f -> read(f, sink).map { it.second } }
        val (tuneSet, testSet) = if (files.size >= 2) {
            val k = files.size / 2
            perFile.take(k).flatten() to perFile.drop(k).flatten()
        } else {
            val all = perFile.flatten()
            all.filterIndexed { i, _ -> i % 2 == 0 } to all.filterIndexed { i, _ -> i % 2 == 1 }
        }
        fun eval(beta: Float, words: List<SwipeTrace.Word>): ReplayReport {
            val h = ReplayHarness(assets, ReplayHarness.DEFAULT_DEEP_K, ctc, beta, depth)
            val r = ReplayReport(h.deepK)
            for (w in words) try { r.add(h.replay(w)) } catch (e: RuntimeException) { r.error(0, e) }
            return r
        }
        val sb = StringBuilder()
        sb.appendLine("tuning on ${tuneSet.size} lines, holding out ${testSet.size}")
        var best = 0f
        var bestKey = -1.0
        for (b in betas) {
            val r = eval(b, tuneSet)
            val n = r.all.n.coerceAtLeast(1)
            val key = r.all.top1 * 1000.0 / n + r.all.rr / n
            sb.appendLine(String.format(java.util.Locale.ROOT, "  beta %.3f  top1 %.1f%%  MRR %.3f", b, 100.0 * r.all.top1 / n, r.all.rr / n))
            if (key > bestKey) { bestKey = key; best = b }
        }
        sb.appendLine("chosen beta $best; held-out result, reported once:")
        sb.appendLine("--- beta 0 (today) ---")
        sb.append(eval(0f, testSet).format())
        sb.appendLine("--- beta $best ---")
        sb.append(eval(best, testSet).format())
        return sb.toString()
    }

    private fun opt(a: MutableList<String>, name: String): String? {
        val i = a.indexOf(name)
        if (i < 0) return null
        require(i + 1 < a.size) { "$name needs a value" }
        val v = a[i + 1]
        a.removeAt(i + 1)
        a.removeAt(i)
        return v
    }

    private fun defaultAssets(): String =
        listOf("app/src/main/assets", "src/main/assets", "../../app/src/main/assets")
            .firstOrNull { File(it, "dictionaries").isDirectory } ?: "app/src/main/assets"

    private fun usage(): Nothing {
        System.err.println(
            "usage: replay [--assets DIR] [--k N] [--ctc MODEL --beta B --depth D] TRACE.jsonl...\n" +
                "       tune --ctc MODEL [--betas LIST] [--depth D] TRACE.jsonl...\n" +
                "       synth [--assets DIR] [--words N] [--alt LANG] [--noise X] [--seed S] OUT.jsonl",
        )
        System.exit(2)
        throw IllegalStateException()
    }
}
