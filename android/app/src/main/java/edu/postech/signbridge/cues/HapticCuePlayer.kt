package edu.postech.signbridge.cues

import android.content.Context
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Produces distinct, clearly perceptible tactile patterns for Start and End events.
 *
 * Uses [VibrationAttributes.USAGE_ACCESSIBILITY] so haptic cues remain active even when the
 * user disables system keyboard touch-feedback haptics or silences ringer notifications.
 */
class HapticCuePlayer(
    applicationContext: Context,
) {

    private val vibrator: Vibrator = applicationContext
        .getSystemService(VibratorManager::class.java)
        .defaultVibrator

    private val accessibilityAttributes: VibrationAttributes = VibrationAttributes.Builder()
        .setUsage(VibrationAttributes.USAGE_ACCESSIBILITY)
        .build()

    // Crisp double ascending pulse (~235 ms total).
    fun playSpeechToSignStartCue() {
        vibratePattern(
            timingsMilliseconds = longArrayOf(0, 75, 65, 95),
            amplitudes = intArrayOf(0, 210, 0, 255),
        )
    }

    // Single brief settling pulse (85 ms).
    fun playSpeechToSignEndCue() {
        vibratePattern(
            timingsMilliseconds = longArrayOf(0, 85),
            amplitudes = intArrayOf(0, 200),
        )
    }

    // Single deep, longer firm pulse (150 ms).
    fun playSignToSpeechStartCue() {
        vibratePattern(
            timingsMilliseconds = longArrayOf(0, 150),
            amplitudes = intArrayOf(0, 255),
        )
    }

    // Soft double trailing tap (~170 ms total).
    fun playSignToSpeechEndCue() {
        vibratePattern(
            timingsMilliseconds = longArrayOf(0, 50, 70, 50),
            amplitudes = intArrayOf(0, 175, 0, 175),
        )
    }

    private fun vibratePattern(
        timingsMilliseconds: LongArray,
        amplitudes: IntArray,
    ) {
        if (!vibrator.hasVibrator()) {
            return
        }
        val effect = if (vibrator.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(timingsMilliseconds, amplitudes, -1)
        } else {
            val binaryAmplitudes = IntArray(amplitudes.size) { index ->
                if (amplitudes[index] > 0) VibrationEffect.DEFAULT_AMPLITUDE else 0
            }
            VibrationEffect.createWaveform(timingsMilliseconds, binaryAmplitudes, -1)
        }
        vibrator.vibrate(effect, accessibilityAttributes)
    }
}
