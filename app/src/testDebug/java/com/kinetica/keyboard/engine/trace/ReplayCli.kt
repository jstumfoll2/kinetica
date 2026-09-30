package com.kinetica.keyboard.engine.trace

import java.io.File

/**
 * Command line for the replay harness. Run through `tools/replay` (see its
 * README), which builds the engine and this harness as a plain JVM program.
 *
 *   replay [--assets DIR] [--k N] TRACE.jsonl...
 *       Replays every v1 line, checks exactness, prints top-1, top-3, MRR and
 *       recall@N (default 50) overall and per bucket. Exits 1 when any
 *       comparable line fails to reproduce.
 *   synth [--assets DIR] [--words N] [--alt LANG] OUT.jsonl
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
                if (a.isEmpty()) usage()
                val report = run(ReplayHarness(assets, k), a.map { File(it) })
                println(report.format())
                if (report.exact < report.comparable) System.exit(1)
            }
            "synth" -> {
                val n = opt(a, "--words")?.toInt() ?: 300
                val alt = opt(a, "--alt")
                val out = File(a.singleOrNull() ?: usage())
                val s = SyntheticSession(assets)
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
     * Word lines of [f] with their line numbers, correction lines applied: a
     * correction relabels the most recent earlier word committed as its `from`.
     */
    fun read(f: File, report: ReplayReport): List<Pair<Int, SwipeTrace.Word>> {
        val words = ArrayList<Pair<Int, SwipeTrace.Word>>()
        f.useLines { lines ->
            for ((i, line) in lines.withIndex()) {
                if (line.isBlank()) continue
                try {
                    when (val l = SwipeTrace.decodeLine(line)) {
                        is SwipeTrace.Line.WordLine -> words.add(i + 1 to l.word)
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
            "usage: replay [--assets DIR] [--k N] TRACE.jsonl...\n" +
                "       synth [--assets DIR] [--words N] [--alt LANG] OUT.jsonl",
        )
        System.exit(2)
        throw IllegalStateException()
    }
}
