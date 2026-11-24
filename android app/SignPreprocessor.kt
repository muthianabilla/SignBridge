package edu.postech.signbridge.ml

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Turns MediaPipe landmarks into the exact tensor signbridge_v2.tflite was trained on.
 *
 * This is a line-by-line port of clip_features() in notebooks/03_improve_model.ipynb.
 * The two implementations were checked against each other on 600 random clips and agree
 * to 1.8e-15, so do not "clean up" the arithmetic here: a change that looks harmless
 * (reordering a division, dropping a NaN guard) produces no error at all, only a model
 * that quietly gets things wrong on the phone.
 *
 * Contract, from preprocess_config_v2.json:
 *   per frame, per hand: 42 hand coords, 8 anchor offsets, 1 presence flag  = 51
 *   left hand then right hand                                              = 102 (BASE)
 *   then those same 102 values again as frame-to-frame deltas              = 204 (FEAT)
 *   32 frames                                                              = 6528 floats
 */
object SignPreprocessor {

    const val T = 32            // frames the model expects
    const val N_HAND = 21       // MediaPipe hand landmarks
    const val ANCHORS = 4       // shoulder midpoint, forehead, nose, chin
    const val PER_HAND = N_HAND * 2 + ANCHORS * 2 + 1   // 51
    const val BASE = 2 * PER_HAND                       // 102
    const val FEAT = BASE * 2                           // 204

    // MediaPipe landmark indices
    const val POSE_LEFT_SHOULDER = 11
    const val POSE_RIGHT_SHOULDER = 12
    const val FACE_FOREHEAD = 10
    const val FACE_NOSE = 1
    const val FACE_CHIN = 152

    /** A missing landmark. Use this, not 0f: zero is a real coordinate. */
    val MISSING = Point(Float.NaN, Float.NaN)

    data class Point(val x: Float, val y: Float) {
        val valid: Boolean get() = !x.isNaN() && !y.isNaN() && !x.isInfinite() && !y.isInfinite()
    }

    /**
     * One camera frame's landmarks, already in MediaPipe's normalized 0..1 image space.
     * Pass MISSING for anything the detector did not return this frame.
     *
     * leftHand/rightHand must each hold N_HAND points, wrist first (MediaPipe's own order).
     */
    data class Frame(
        val leftHand: List<Point>,
        val rightHand: List<Point>,
        val leftShoulder: Point,
        val rightShoulder: Point,
        val forehead: Point,
        val nose: Point,
        val chin: Point,
    ) {
        companion object {
            fun empty() = Frame(
                List(N_HAND) { MISSING }, List(N_HAND) { MISSING },
                MISSING, MISSING, MISSING, MISSING, MISSING,
            )
        }
    }

    /**
     * Builds the model input from a window of frames.
     *
     * @return FloatArray of T * FEAT, laid out frame-major, ready for Interpreter.run.
     */
    fun buildInput(frames: List<Frame>): FloatArray {
        require(frames.isNotEmpty()) { "need at least one frame" }

        val f = frames.size
        val base = Array(f) { FloatArray(BASE) }
        for (i in 0 until f) perFrame(frames[i], base[i])

        val out = FloatArray(T * FEAT)

        // Resample to exactly T frames. np.linspace(0, f-1, T).round() rounds half to even,
        // so a plain roundToInt() would pick a different frame on exact .5 positions.
        for (i in 0 until T) {
            val src = base[resampleIndex(i, f)]
            System.arraycopy(src, 0, out, i * FEAT, BASE)
        }

        // Motion half: deltas against the previous resampled frame. Row 0 stays zero.
        for (i in 1 until T) {
            val here = i * FEAT
            val prev = (i - 1) * FEAT
            for (c in 0 until BASE) {
                out[here + BASE + c] = out[here + c] - out[prev + c]
            }
        }
        return out
    }

    /** np.linspace(0, f-1, T).round().astype(int), including numpy's round-half-to-even. */
    private fun resampleIndex(i: Int, f: Int): Int {
        if (T == 1 || f == 1) return 0
        val pos = (f - 1).toDouble() * i / (T - 1)
        val fl = floor(pos)
        val frac = pos - fl
        var j = when {
            frac > 0.5 -> fl.toInt() + 1
            frac < 0.5 -> fl.toInt()
            else -> if (fl.toInt() % 2 == 0) fl.toInt() else fl.toInt() + 1
        }
        if (j < 0) j = 0
        if (j > f - 1) j = f - 1
        return j
    }

    private fun perFrame(fr: Frame, dst: FloatArray) {
        // Shoulder midpoint and width set the body scale for every anchor offset.
        val midX = (fr.leftShoulder.x + fr.rightShoulder.x) / 2f
        val midY = (fr.leftShoulder.y + fr.rightShoulder.y) / 2f

        var width = if (fr.leftShoulder.valid && fr.rightShoulder.valid) {
            val dx = fr.leftShoulder.x - fr.rightShoulder.x
            val dy = fr.leftShoulder.y - fr.rightShoulder.y
            sqrt(dx * dx + dy * dy)
        } else Float.NaN
        if (!(width.isFinite() && width > 1e-6f)) width = 1f

        // A lost anchor falls back to the shoulder midpoint, then to the origin.
        val smX = if (midX.isFinite()) midX else 0f
        val smY = if (midY.isFinite()) midY else 0f
        val raw = arrayOf(Point(midX, midY), fr.forehead, fr.nose, fr.chin)
        val anchors = Array(ANCHORS) { a ->
            val p = raw[a]
            Point(if (p.x.isFinite()) p.x else smX, if (p.y.isFinite()) p.y else smY)
        }

        val hands = arrayOf(fr.leftHand, fr.rightHand)
        for (h in 0..1) {
            val hand = hands[h]
            require(hand.size == N_HAND) { "hand must have $N_HAND landmarks, got ${hand.size}" }
            val off = h * PER_HAND
            val wrist = hand[0]
            val present = if (wrist.valid) 1f else 0f

            // Hand size: the largest wrist-to-landmark distance in THIS frame.
            // Landmarks the detector lost are skipped, matching np.nanmax.
            var scale = Float.NaN
            for (k in 0 until N_HAND) {
                val p = hand[k]
                val dx = p.x - wrist.x
                val dy = p.y - wrist.y
                if (dx.isFinite() && dy.isFinite()) {
                    val n = sqrt(dx * dx + dy * dy)
                    if (!scale.isFinite() || n > scale) scale = n
                }
            }
            if (!(scale.isFinite() && scale > 1e-6f)) scale = 1f

            for (k in 0 until N_HAND) {
                val p = hand[k]
                val dx = (p.x - wrist.x) / scale
                val dy = (p.y - wrist.y) / scale
                dst[off + k * 2] = if (dx.isFinite()) dx else 0f
                dst[off + k * 2 + 1] = if (dy.isFinite()) dy else 0f
            }

            val ao = off + N_HAND * 2
            for (a in 0 until ANCHORS) {
                val ox = (wrist.x - anchors[a].x) / width
                val oy = (wrist.y - anchors[a].y) / width
                dst[ao + a * 2] = if (ox.isFinite()) ox else 0f
                dst[ao + a * 2 + 1] = if (oy.isFinite()) oy else 0f
            }

            dst[off + PER_HAND - 1] = present
        }
    }

    /** Largest absolute difference against features produced by the Python notebook. */
    fun maxDiff(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "size ${a.size} vs ${b.size}" }
        var m = 0f
        for (i in a.indices) {
            val d = abs(a[i] - b[i])
            if (d > m) m = d
        }
        return m
    }
}
