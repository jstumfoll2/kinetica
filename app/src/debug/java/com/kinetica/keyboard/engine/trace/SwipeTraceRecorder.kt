package com.kinetica.keyboard.engine.trace

import com.kinetica.keyboard.engine.GestureEngine
import com.kinetica.keyboard.engine.KeyboardGeometry
import com.kinetica.keyboard.engine.WordComposer
import com.kinetica.keyboard.engine.models.InputToken
import com.kinetica.keyboard.engine.models.StreamId
import com.kinetica.keyboard.engine.models.WordCandidate

/**
 * Writes one [SwipeTrace] v1 line per word buffer.
 *
 * Attach it as both the [GestureEngine.Observer] and the [WordComposer.Observer]:
 * the engine side collects each pointer's samples and pairs them with the token
 * its lift produced, the composer side emits the line when the buffer ends. A
 * buffer token with no samples (reloaded taps, an accent letter, or a gesture
 * that began before the recorder was attached) is written as a literal token.
 *
 * Main thread only, like both observers. [sink] receives the finished line and
 * decides where it goes; [config] is read once per line.
 */
class SwipeTraceRecorder(
    private val config: () -> SwipeTrace.Config,
    private val sink: (String) -> Unit,
) : GestureEngine.Observer, WordComposer.Observer {

    /** Label for the next committed buffer, set by the owner just before commit when it knows. */
    var nextHow: String? = null

    private class Open(val stream: StreamId, val geometry: SwipeTrace.Geometry) {
        val samples = ArrayList<SwipeTrace.Sample>(64)
    }

    private class Done(val token: InputToken, val gesture: SwipeTrace.Gesture, val geometry: SwipeTrace.Geometry)

    private var geometry: SwipeTrace.Geometry? = null
    private val open = HashMap<Int, Open>()

    // Finished gestures waiting for their buffer to end, oldest first. Matched by
    // identity: the token object is the one the composer holds. Bounded, because
    // not every token reaches a composer (peck mode drops swipes).
    private val done = ArrayDeque<Done>()

    override fun onGeometry(g: KeyboardGeometry, tapMaxDispPx: Float) {
        geometry = SwipeTrace.Geometry.of(g, tapMaxDispPx)
    }

    override fun onDown(pointerId: Int, streamId: StreamId, xPx: Float, yPx: Float, t: Long) {
        val g = geometry ?: return
        open[pointerId] = Open(streamId, g).also { it.samples.add(SwipeTrace.Sample(xPx, yPx, t)) }
    }

    override fun onMove(pointerId: Int, xPx: Float, yPx: Float, t: Long) {
        open[pointerId]?.samples?.add(SwipeTrace.Sample(xPx, yPx, t))
    }

    override fun onUp(pointerId: Int, xPx: Float, yPx: Float, t: Long, token: InputToken) {
        val o = open.remove(pointerId) ?: return
        o.samples.add(SwipeTrace.Sample(xPx, yPx, t))
        done.addLast(Done(token, SwipeTrace.Gesture(o.stream, o.samples), o.geometry))
        while (done.size > MAX_PENDING) done.removeFirst()
    }

    override fun onCancel(pointerId: Int) {
        open.remove(pointerId)
    }

    override fun onBufferEnd(
        tokens: List<InputToken>,
        context: List<String>,
        shown: List<WordCandidate>,
        shownFor: Int,
        committed: String?,
    ) {
        val how = if (committed != null) nextHow else null
        nextHow = null
        val g = geometry ?: return
        val out = ArrayList<SwipeTrace.Token>(tokens.size)
        var geometryChanged = false
        for (t in tokens) {
            val d = done.firstOrNull { it.token === t }
            if (d != null) {
                done.remove(d)
                if (!d.geometry.sameAs(g)) geometryChanged = true
                out.add(d.gesture)
            } else {
                out.add(SwipeTrace.Literal(t))
            }
        }
        // A buffer whose gestures were drawn on another geometry than the one the
        // decode used cannot be replayed from one geometry; rare (a rotation
        // mid-word), and skipped rather than written wrong.
        if (geometryChanged) return
        val word = SwipeTrace.Word(
            config(), g, context, out,
            SwipeTrace.Shown(shownFor, SwipeTrace.candidates(shown)), committed, how,
        )
        sink(SwipeTrace.encode(word))
    }

    private companion object {
        const val MAX_PENDING = 64
    }
}
