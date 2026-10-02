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
                if (a.isEmpty()) usage()
                val report = run(ReplayHarness(assets, k, ctc, beta, depth), a.map { File(it) })
                println(report.format())
                if (ctc == null && report.exact < report.comparable) System.exit(1)
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
