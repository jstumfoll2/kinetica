package com.kinetica.keyboard.keys

import kotlin.math.floor

/**
 * Turns the backspace slide's leftward travel into a staged unit count, steadily.
 *
 * Two things made the plain `travel / step` count feel touchy: a finger resting near a step
 * boundary flickered between two counts, and the small roll a thumb makes as it lifts often
 * crossed one more boundary just before the lift, so one more word went than the highlight had
 * shown while the finger was still. This keeps the count still at a boundary ([HYSTERESIS]) and
 * drops a step that appeared only in the lift ([atLift]).
 *
 * Pure, with times passed in, so the behaviour is testable without a Handler or a view.
 */
class BackspaceSlide {

    /** Units staged now, as the highlight shows them. */
    var units = 0
        private set

    private var prevUnits = 0
    private var changedAt = 0L
    private var prevHeldMs = 0L

    fun reset(t: Long) {
        units = 0
        prevUnits = 0
        changedAt = t
        prevHeldMs = 0L
    }

    /** Feeds a travel of [travelPx] (leftward positive) at time [t]; true when [units] changed. */
    fun update(travelPx: Float, stepPx: Float, t: Long): Boolean {
        val next = next(units, travelPx / stepPx)
        if (next == units) return false
        prevUnits = units
        prevHeldMs = t - changedAt
        changedAt = t
        units = next
        return true
    }

    /**
     * The count to delete when the finger lifts at [t]. It is [units], except when the last step
     * grew the span within [LIFT_GUARD_MS] of the lift after the finger had rested on the count
     * before it for [DWELL_MS]: that step is the lift's roll, and the rested count is what the
     * user saw and meant. A fast flick never rests, so it keeps every step it made.
     */
    fun atLift(t: Long): Int {
        val rolledOnLift = units > prevUnits && prevUnits > 0 &&
            t - changedAt < LIFT_GUARD_MS && prevHeldMs >= DWELL_MS
        return if (rolledOnLift) prevUnits else units
    }

    companion object {
        /**
         * Fraction of a step the finger must come back past a boundary before a unit retracts.
         * Growth still happens at the boundary itself, so the highlight leads the finger.
         */
        const val HYSTERESIS = 0.3f

        /** A step this close to the lift counts as the lift's roll, if the finger had rested. */
        const val LIFT_GUARD_MS = 80L

        /** How long the finger must have rested on a count for the lift guard to protect it. */
        const val DWELL_MS = 150L

        /** The count after [current] for a travel of [steps] steps. */
        fun next(current: Int, steps: Float): Int {
            val grown = floor(steps).toInt().coerceAtLeast(0)
            if (grown > current) return grown
            val shrunk = floor(steps + HYSTERESIS).toInt().coerceAtLeast(0)
            return if (shrunk < current) shrunk else current
        }
    }
}
