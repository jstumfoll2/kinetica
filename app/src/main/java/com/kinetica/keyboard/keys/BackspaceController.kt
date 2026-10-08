package com.kinetica.keyboard.keys

import android.os.Handler

/**
 * Backspace behaviors: a tap deletes one char; holding past the arm delay repeats char deletion
 * at 50 ms; sliding left stages one unit per threshold crossed without deleting anything. The
 * staged span is highlighted in a preview chip and deleted at lift. Sliding back right retracts it
 * unit by unit, and lifting with nothing staged is a no-op, so a slide too far is recoverable.
 *
 * A unit is a whole word by default, or a single character when [charMode] is set. Only the
 * threshold and the meaning of the count change; staging, retraction and lift are shared, so the
 * mode is a flag and not a second gesture.
 *
 * The travel-to-count mapping lives in [BackspaceSlide]: it holds the count still at a step
 * boundary and drops a step that only the lift's roll added, so what is deleted is what was
 * highlighted while the finger rested.
 */
class BackspaceController(
    private val density: Float,
    private val handler: Handler,
    private val onDeleteChar: () -> Unit,
    /**
     * Staged unit count changed; 0 clears the preview without deleting. The boolean is
     * [charMode] at the time of the event, so the receiver needs no second copy of the setting
     * to know what the count means.
     */
    private val onStageUnits: (Int, Boolean) -> Unit,
    /** Pointer lifted with a staged span: delete that span. */
    private val onCommitStaged: () -> Unit,
) {
    /**
     * Stage single characters instead of whole words. Pushed from
     * `KeyboardConfig` on every preference change, like the hold-arm delay.
     */
    var charMode = false

    /** Travel per staged word, in dp; a character step is a fixed fraction of it. */
    var stepDp = DeleteSpan.DEFAULT_WORD_STEP_DP

    private var startX = 0f
    private val slide = BackspaceSlide()
    private var everStaged = false
    private var repeating = false

    private val repeatRunnable = object : Runnable {
        override fun run() {
            repeating = true
            onDeleteChar()
            handler.postDelayed(this, REPEAT_MS)
        }
    }

    fun onDown(x: Float, holdArmMs: Long, t: Long) {
        startX = x
        slide.reset(t)
        everStaged = false
        repeating = false
        handler.postDelayed(repeatRunnable, holdArmMs)
    }

    fun onMove(x: Float, t: Long) {
        val threshold = DeleteSpan.slideDpPerUnit(charMode, stepDp) * density
        if (slide.update(startX - x, threshold, t)) {
            val units = slide.units
            if (units > 0) {
                // Sliding cancels the hold-repeat: the two must not stack.
                handler.removeCallbacks(repeatRunnable)
                everStaged = true
            }
            onStageUnits(units, charMode)
        }
    }

    fun onUp(t: Long) {
        handler.removeCallbacks(repeatRunnable)
        val units = slide.atLift(t)
        // The lift's roll added a step: stage the rested count again before deleting it.
        if (units != slide.units) onStageUnits(units, charMode)
        when {
            units > 0 -> onCommitStaged()
            // Slid out and back to zero: an explicit cancel, not a tap. A
            // finished hold-repeat run also must not add a tap delete.
            everStaged || repeating -> Unit
            else -> onDeleteChar()
        }
        slide.reset(t)
        everStaged = false
    }

    fun cancel() {
        handler.removeCallbacks(repeatRunnable)
        if (everStaged) onStageUnits(0, charMode)
        slide.reset(0L)
        everStaged = false
    }

    private companion object {
        const val REPEAT_MS = 50L
    }
}
