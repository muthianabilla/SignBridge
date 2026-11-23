package edu.postech.signbridge.vision

import androidx.camera.core.ImageProxy
import edu.postech.signbridge.model.SignDetectionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stub implementation of [SignRecognizer] used until MediaPipe sign recognition is integrated.
 *
 * Keeps camera frame buffers drained cleanly without injecting a synthetic hand wireframe over
 * the live camera feed.
 */
class FakeSignRecognizer : SignRecognizer {

    private val mutableDetectionResults = MutableStateFlow(SignDetectionResult())
    override val detectionResults: StateFlow<SignDetectionResult> =
        mutableDetectionResults.asStateFlow()

    @Volatile
    private var recognitionEnabled: Boolean = true

    override fun analyzeCameraFrame(imageProxy: ImageProxy) {
        // Always close CameraX buffers immediately so the preview pipeline never stalls.
        imageProxy.close()
    }

    override fun setRecognitionEnabled(isEnabled: Boolean) {
        recognitionEnabled = isEnabled
        if (!isEnabled) {
            mutableDetectionResults.value = SignDetectionResult(
                timestampMilliseconds = System.currentTimeMillis(),
            )
        }
    }

    override fun release() {
        mutableDetectionResults.value = SignDetectionResult()
    }
}
