package edu.postech.signbridge.ml

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Checks that SignPreprocessor.kt reproduces what the Python notebook produced.
 *
 * Put parity_fixture.json (from make_fixture.py) in src/main/assets/ and call
 * ParityTest.run(context) once at startup during development.
 *
 * A mismatch here is the one bug in this project that reports nothing: the app keeps
 * running, the model keeps answering, and the answers are just worse than they should be.
 */
object ParityTest {

    private const val TAG = "SignBridgeParity"
    private const val TOLERANCE = 1e-4f   // float32 rounding between numpy and Kotlin

    fun run(context: Context): Boolean {
        // Skip quietly when the fixture has not been generated yet, so the app still starts.
        if ("parity_fixture.json" !in (context.assets.list("") ?: emptyArray())) {
            Log.w(TAG, "SKIP  parity_fixture.json not in assets")
            return false
        }
        val j = context.assets.open("parity_fixture.json")
            .bufferedReader().use { JSONObject(it.readText()) }

        val n = j.getInt("n_frames")
        val lh = j.getJSONArray("left_hand")
        val rh = j.getJSONArray("right_hand")
        val sh = j.getJSONArray("shoulders")
        val fc = j.getJSONArray("face")

        val frames = (0 until n).map { f ->
            SignPreprocessor.Frame(
                leftHand = points(lh.getJSONArray(f), SignPreprocessor.N_HAND),
                rightHand = points(rh.getJSONArray(f), SignPreprocessor.N_HAND),
                leftShoulder = point(sh.getJSONArray(f), 0),
                rightShoulder = point(sh.getJSONArray(f), 1),
                forehead = point(fc.getJSONArray(f), 0),
                nose = point(fc.getJSONArray(f), 1),
                chin = point(fc.getJSONArray(f), 2),
            )
        }

        val got = SignPreprocessor.buildInput(frames)

        val exp = j.getJSONArray("expected")
        val want = FloatArray(exp.length()) { exp.getDouble(it).toFloat() }

        if (got.size != want.size) {
            Log.e(TAG, "FAIL size: kotlin ${got.size}, python ${want.size}")
            return false
        }

        val diff = SignPreprocessor.maxDiff(got, want)
        var worstAt = 0
        for (i in got.indices) {
            if (kotlin.math.abs(got[i] - want[i]) >= diff) { worstAt = i; break }
        }

        return if (diff <= TOLERANCE) {
            Log.i(TAG, "PASS  max diff %.2e over ${got.size} values".format(diff))
            true
        } else {
            val t = worstAt / SignPreprocessor.FEAT
            val c = worstAt % SignPreprocessor.FEAT
            Log.e(TAG, "FAIL  max diff %.4f at frame $t feature $c ".format(diff) +
                "(kotlin ${got[worstAt]}, python ${want[worstAt]})")
            Log.e(TAG, "feature $c is " + describe(c))
            false
        }
    }

    /** Says which part of the layout a feature index belongs to, to localise a mismatch. */
    private fun describe(c: Int): String {
        val half = if (c < SignPreprocessor.BASE) "base" else "motion delta"
        val b = c % SignPreprocessor.BASE
        val hand = if (b < SignPreprocessor.PER_HAND) "left hand" else "right hand"
        val k = b % SignPreprocessor.PER_HAND
        val part = when {
            k < SignPreprocessor.N_HAND * 2 -> "landmark ${k / 2} ${if (k % 2 == 0) "x" else "y"}"
            k < SignPreprocessor.PER_HAND - 1 -> {
                val a = (k - SignPreprocessor.N_HAND * 2) / 2
                val name = listOf("shoulder midpoint", "forehead", "nose", "chin")[a]
                "$name offset ${if (k % 2 == 0) "x" else "y"}"
            }
            else -> "presence flag"
        }
        return "$half, $hand, $part"
    }

    private fun points(arr: JSONArray, n: Int) = (0 until n).map { point(arr, it) }

    private fun point(arr: JSONArray, i: Int): SignPreprocessor.Point {
        if (arr.isNull(i)) return SignPreprocessor.MISSING
        val p = arr.getJSONArray(i)
        return SignPreprocessor.Point(p.getDouble(0).toFloat(), p.getDouble(1).toFloat())
    }
}
