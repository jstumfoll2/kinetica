package com.kinetica.keyboard.ime

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.setPadding
import com.kinetica.keyboard.R
import java.io.IOException

/**
 * The developer build's only screen: what the decode trace has recorded, and how to
 * get it off the device.
 *
 * Declared in the debug manifest alone, so nothing about it merges into a release
 * build. Export goes through the storage-access framework, the same route the
 * personal-dictionary export already uses - no file provider, no new permission, and
 * the file lands wherever the developer picks rather than somewhere they have to hunt.
 */
class TraceActivity : AppCompatActivity() {

    private lateinit var stats: TextView
    private lateinit var wordStats: TextView

    private val createWordExport =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) { uri ->
            if (uri != null) write(uri, TraceRecorder.words.readAll())
        }

    private val createExport =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) export(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.trace_title)
        val pad = (16 * resources.displayMetrics.density).toInt()

        stats = TextView(this).apply { textSize = 15f }

        val toggle = SwitchCompat(this).apply {
            text = getString(R.string.trace_recording)
            textSize = 16f
            isChecked = TraceRecorder.recording
            setOnCheckedChangeListener { _, on -> TraceRecorder.recording = on }
        }

        // Said plainly rather than buried: this build writes down what is typed on it.
        val explain = TextView(this).apply {
            text = getString(R.string.trace_explain)
            textSize = 13f
        }

        val exportButton = Button(this).apply {
            text = getString(R.string.trace_export)
            setOnClickListener { createExport.launch("kinetica_trace.log") }
        }

        val clearButton = Button(this).apply {
            text = getString(R.string.trace_clear)
            setOnClickListener {
                TraceRecorder.clear()
                refresh()
                Toast.makeText(this@TraceActivity, R.string.trace_cleared, Toast.LENGTH_SHORT).show()
            }
        }

        wordStats = TextView(this).apply { textSize = 15f }
        val wordToggle = SwitchCompat(this).apply {
            text = getString(R.string.trace_words_recording)
            textSize = 16f
            isChecked = TraceRecorder.words.enabled
            setOnCheckedChangeListener { _, on -> TraceRecorder.words.enabled = on }
        }
        val wordExplain = TextView(this).apply {
            text = getString(R.string.trace_words_explain)
            textSize = 13f
        }
        val wordExport = Button(this).apply {
            text = getString(R.string.trace_words_export)
            setOnClickListener { createWordExport.launch("kinetica_words_v1.jsonl") }
        }
        val wordClear = Button(this).apply {
            text = getString(R.string.trace_words_clear)
            setOnClickListener {
                TraceRecorder.words.clear()
                refresh()
                Toast.makeText(this@TraceActivity, R.string.trace_cleared, Toast.LENGTH_SHORT).show()
            }
        }
        val practice = Button(this).apply {
            text = getString(R.string.practice_open)
            setOnClickListener {
                startActivity(android.content.Intent(this@TraceActivity, PracticeActivity::class.java))
            }
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad)
            addView(toggle)
            addView(stats)
            addView(exportButton)
            addView(clearButton)
            addView(explain)
            addView(wordToggle)
            addView(wordStats)
            addView(wordExport)
            addView(wordClear)
            addView(practice)
            addView(wordExplain)
        }
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val bytes = TraceRecorder.sizeBytes()
        stats.text = if (bytes == 0L) {
            getString(R.string.trace_empty)
        } else {
            resources.getQuantityString(
                R.plurals.trace_stats,
                TraceRecorder.lines.toInt(),
                TraceRecorder.lines,
                readableSize(bytes),
            )
        }
        val wordBytes = TraceRecorder.words.sizeBytes()
        wordStats.text = if (wordBytes == 0L) {
            getString(R.string.trace_empty)
        } else {
            resources.getQuantityString(
                R.plurals.trace_stats,
                TraceRecorder.words.lines.toInt(),
                TraceRecorder.words.lines,
                readableSize(wordBytes),
            )
        }
    }

    private fun export(uri: Uri) = write(uri, TraceRecorder.readAll())

    private fun write(uri: Uri, text: String) {
        val ok = try {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                it.write(text)
            } != null
        } catch (e: IOException) {
            false
        }
        val msg = if (ok) R.string.trace_export_done else R.string.trace_export_failed
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun readableSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024f * 1024f))
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }
}
