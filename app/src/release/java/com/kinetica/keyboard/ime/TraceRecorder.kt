package com.kinetica.keyboard.ime

import android.content.Context
import com.kinetica.keyboard.engine.GestureEngine
import com.kinetica.keyboard.engine.WordComposer

/**
 * Release build: decode tracing does not exist.
 *
 * The developer build's recorder writes every decoded gesture to a file, so it lives in the debug
 * source set and this stub replaces it. A released APK contains no code at all that can write what
 * was typed to disk, not even disabled code.
 */
@Suppress("UNUSED_PARAMETER")
object TraceRecorder {
    /** No trace sink is installed in a release build. */
    fun install(context: Context) = Unit

    /** No word trace either: nothing observes the engine or the composer. */
    fun attachEngine(engine: GestureEngine, info: TraceInfo) = Unit

    fun attachComposer(composer: WordComposer) = Unit

    fun label(how: String) = Unit

    fun correction(from: String, to: String) = Unit
}
