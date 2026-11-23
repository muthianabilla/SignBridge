package edu.postech.signbridge.model

/**
 * Normalized two-dimensional hand landmark emitted in camera image coordinates in `[0.0f, 1.0f]`.
 *
 * Coordinates represent raw sensor-space ratios prior to front-camera horizontal mirroring in UI.
 */
data class HandLandmarkPoint(
    val horizontalRatio: Float,
    val verticalRatio: Float,
)

/**
 * Latest per-frame inference produced by a [edu.postech.signbridge.vision.SignRecognizer].
 *
 * @property recognizedWord Matched vocabulary identifier or label when a sign is detected, else null.
 * @property confidenceScore Model confidence within `[0.0f, 1.0f]`.
 * @property detectedHands List of detected hands, each containing 21 landmarks in MediaPipe order.
 * @property timestampMilliseconds Frame timestamp used to distinguish consecutive detections.
 */
data class SignDetectionResult(
    val recognizedWord: String? = null,
    val confidenceScore: Float = 0f,
    val detectedHands: List<List<HandLandmarkPoint>> = emptyList(),
    val timestampMilliseconds: Long = 0L,
) {
    val hasDetectedHands: Boolean
        get() = detectedHands.isNotEmpty()
}

/**
 * Standard 21-point MediaPipe hand bone segments connecting pairs of landmark indices.
 */
object HandSkeletonTopology {
    val boneConnections: List<Pair<Int, Int>> = listOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 4,
        0 to 5, 5 to 6, 6 to 7, 7 to 8,
        5 to 9, 9 to 10, 10 to 11, 11 to 12,
        9 to 13, 13 to 14, 14 to 15, 15 to 16,
        13 to 17, 0 to 17, 17 to 18, 18 to 19, 19 to 20,
    )
}
