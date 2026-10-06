package com.kinetica.keyboard.ime

import android.content.Context
import com.kinetica.keyboard.engine.CandidateReranker
import com.kinetica.keyboard.engine.KineticaConstants
import com.kinetica.keyboard.engine.WordPredictor

/**
 * Release build: no neural rerank, and no model in the APK.
 *
 * The developer build can switch a CTC rerank on to test it on a phone; this stub
 * takes its place here and always builds today's decoder, no reranker and a
 * [KineticaConstants.TOP_K] heap.
 */
@Suppress("UNUSED_PARAMETER")
object NeuralRerank {
    fun install(context: Context, onChange: (() -> Unit)?) = Unit

    fun detach() = Unit

    fun primary(make: (CandidateReranker?, Int) -> WordPredictor): WordPredictor =
        make(null, KineticaConstants.TOP_K)

    fun spacebarTag(): String? = null
}
