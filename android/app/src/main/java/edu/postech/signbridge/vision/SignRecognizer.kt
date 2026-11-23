package edu.postech.signbridge.vision

import androidx.camera.core.ImageProxy
import edu.postech.signbridge.model.SignDetectionResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Contract between the CameraX / UI layer and the Sign-To-Text machine learning pipeline.
 *
 * Implementations receive front-camera frames from CameraX and emit isolated sign predictions
 * alongside 21 normalized MediaPipe hand landmarks for live alignment feedback.
 */
interface SignRecognizer {

    /**
     * Hot stream of the most recent hand landmarks and optional sign classification.
     */
    val detectionResults: StateFlow<SignDetectionResult>

    /**
     * Processes one CameraX frame from the front camera.
     *
     * Important: The implementation owns [imageProxy] and MUST call `imageProxy.close()`
     * in a `finally` block once processing finishes, even when recognition is disabled.
     */
    fun analyzeCameraFrame(imageProxy: ImageProxy)

    /**
     * Enables or pauses sign classification while another turn is active.
     *
     * When false, implementations should still close incoming frames promptly and may keep
     * emitting landmarks for alignment or emit an empty detection.
     */
    fun setRecognitionEnabled(isEnabled: Boolean)

    /**
     * Releases any underlying MediaPipe or TensorFlow Lite native resources.
     */
    fun release()
}