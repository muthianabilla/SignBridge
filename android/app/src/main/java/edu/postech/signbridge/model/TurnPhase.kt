package edu.postech.signbridge.model

/**
 * Exclusive turn-taking phases enforcing one active communicator at a time.
 */
enum class TurnPhase {
    IDLE_LISTENING,
    SIGN_TO_SPEECH_STARTING,
    SIGN_TO_SPEECH_RUNNING,
    SIGN_TO_SPEECH_ENDING,
    SPEECH_TO_SIGN_STARTING,
    SPEECH_TO_SIGN_RUNNING,
    SPEECH_TO_SIGN_ENDING,
    ;

    val isSignToSpeechActive: Boolean
        get() = this == SIGN_TO_SPEECH_STARTING ||
                this == SIGN_TO_SPEECH_RUNNING ||
                this == SIGN_TO_SPEECH_ENDING

    val isSpeechToSignActive: Boolean
        get() = this == SPEECH_TO_SIGN_STARTING ||
                this == SPEECH_TO_SIGN_RUNNING ||
                this == SPEECH_TO_SIGN_ENDING

    val isOutputting: Boolean
        get() = this != IDLE_LISTENING
}