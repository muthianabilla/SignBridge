package edu.postech.signbridge.turn

import edu.postech.signbridge.model.ConversationEntry
import edu.postech.signbridge.model.MicrophoneStatus
import edu.postech.signbridge.model.SignDetectionResult
import edu.postech.signbridge.model.SpokenWordToken
import edu.postech.signbridge.model.TurnPhase

/**
 * Immutable snapshot driving the unified SignBridge interface.
 */
data class SignBridgeScreenState(
    val turnPhase: TurnPhase = TurnPhase.IDLE_LISTENING,
    val microphoneStatus: MicrophoneStatus = MicrophoneStatus.PERMISSION_REQUIRED,
    val isMicrophoneEnabledByUser: Boolean = true,
    val isPracticeModeActive: Boolean = false,
    val hasCameraPermission: Boolean = false,
    val hasMicrophonePermission: Boolean = false,
    val liveDetectionResult: SignDetectionResult = SignDetectionResult(),
    val signHoldProgress: Float = 0f,
    val lastSpokenWord: String? = null,
    val lastSpokenTranscript: String = "",
    val partialSpeechTranscript: String = "",
    val speechTokens: List<SpokenWordToken> = emptyList(),
    val activeSpeechTokenIndex: Int? = null,
    val activeClipProgress: Float = 0f,
    val missingVideoFileName: String? = null,
    val playbackSpeedMultiplier: Float = 1.0f,
    val transientStatusMessage: String? = null,
    val conversationHistory: List<ConversationEntry> = emptyList(),
    val isFakeSignRecognizerActive: Boolean = false,
) {
    val activeSignWordLabel: String?
        get() {
            val activeIndex = activeSpeechTokenIndex ?: return null
            val activeToken = speechTokens.getOrNull(activeIndex)
            return (activeToken as? SpokenWordToken.MatchedSign)?.vocabularyWord?.displayLabel
        }
}
