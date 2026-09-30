package com.kinetica.keyboard.engine.trace

import com.kinetica.keyboard.engine.KineticaConstants
import java.util.Locale

/**
 * Top-1, top-3, MRR, presence anywhere in the shipping list, and recall at the
 * deep K, overall and per bucket, plus the
 * exactness count. Only committed lines are scored: an abandoned buffer has no
 * label of its own.
 */
class ReplayReport(private val deepK: Int) {

    class Tally {
        var n = 0
        var top1 = 0
        var top3 = 0
        var rr = 0.0
        var in10 = 0
        var recall = 0

        fun add(r: ReplayHarness.Result) {
            n++
            if (r.rank == 1) top1++
            if (r.rank in 1..3) top3++
            if (r.rank > 0) in10++
            if (r.rank > 0) rr += 1.0 / r.rank
            if (r.deepRank > 0) recall++
        }
    }

    val all = Tally()
    val byBucket = LinkedHashMap<String, Tally>().apply { for (b in ReplayHarness.BUCKETS) put(b, Tally()) }
    var lines = 0
    var unlabelled = 0
    var comparable = 0
    var exact = 0
    var failed = 0
    val mismatches = ArrayList<String>()
    /** Lines whose label the shipping list holds and the deep list lost; see [format]. */
    var deepLost = 0
    val deepLostExamples = ArrayList<String>()
    val errors = ArrayList<String>()

    fun add(r: ReplayHarness.Result) {
        lines++
        when (r.exact) {
            null -> Unit
            true -> { comparable++; exact++ }
            false -> {
                comparable++
                if (mismatches.size < MAX_EXAMPLES) {
                    mismatches.add(
                        "${r.word.label}: live=${r.word.shown.candidates.take(3).map { it.word }} " +
                            "replay=${r.shipping.take(3).map { it.word }}",
                    )
                }
            }
        }
        if (r.word.label == null) { unlabelled++; return }
        if (r.rank > 0 && r.deepRank == 0) {
            deepLost++
            if (deepLostExamples.size < MAX_EXAMPLES) deepLostExamples.add("${r.word.label} (shipping rank ${r.rank})")
        }
        all.add(r)
        for (b in r.buckets) byBucket.getValue(b).add(r)
    }

    fun error(lineNo: Int, e: Exception) {
        failed++
        if (errors.size < MAX_EXAMPLES) errors.add("line $lineNo: ${e.message}")
    }

    fun format(): String = buildString {
        appendLine("lines=$lines scored=${all.n} abandoned=$unlabelled unreadable=$failed")
        appendLine("exact replay: $exact/$comparable comparable lines (identical list, scores bit for bit)")
        appendLine()
        appendLine(
            String.format(
                Locale.ROOT,
                "%-18s %6s %7s %7s %7s %8s %9s", "bucket", "n", "top1", "top3", "MRR",
                "in@${KineticaConstants.TOP_K}", "recall@$deepK",
            ),
        )
        row("all", all)
        for ((b, t) in byBucket) if (t.n > 0) row(b, t)
        if (deepLost > 0) {
            // Not a replay fault. The deep decode is a different search: a bigger
            // heap has a lower minimum, so more words pass and the fixed
            // MAX_EMIT_ATTEMPTS budget runs out earlier in the trie walk. Measured
            // on the synthetic set: "pants" is rank 1 at K=10 and 15 and gone from
            // the list at K=20, 30 and 50.
            appendLine()
            appendLine("deep list missed the label in $deepLost lines where the shipping list had it:")
            for (m in deepLostExamples) appendLine("  $m")
        }
        if (mismatches.isNotEmpty()) {
            appendLine()
            appendLine("first mismatches:")
            for (m in mismatches) appendLine("  $m")
        }
        if (errors.isNotEmpty()) {
            appendLine()
            appendLine("first unreadable lines:")
            for (m in errors) appendLine("  $m")
        }
    }

    private fun StringBuilder.row(name: String, t: Tally) {
        fun pct(k: Int) = if (t.n == 0) "-" else String.format(Locale.ROOT, "%.1f%%", 100.0 * k / t.n)
        val mrr = if (t.n == 0) "-" else String.format(Locale.ROOT, "%.3f", t.rr / t.n)
        appendLine(String.format(Locale.ROOT, "%-18s %6d %7s %7s %7s %8s %9s", name, t.n, pct(t.top1), pct(t.top3), mrr, pct(t.in10), pct(t.recall)))
    }

    private companion object {
        const val MAX_EXAMPLES = 10
    }
}
