package edu.postech.signbridge.model

/**
 * Combined user preference and runtime gating status shown on the persistent microphone toggle button.
 */
enum class MicrophoneStatus {
    LISTENING,
    GATED_FOR_TURN,
    MUTED_BY_USER,
    PERMISSION_REQUIRED,
    OFFLINE_MODEL_UNAVAILABLE,
}