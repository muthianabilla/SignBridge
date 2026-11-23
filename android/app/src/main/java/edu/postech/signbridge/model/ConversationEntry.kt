package edu.postech.signbridge.model

/**
 * Distinguishes who initiated a completed turn in the compact conversation strip.
 */
enum class CommunicatorRole {
    SIGNER,
    SPEAKER,
}

/**
 * Single recent turn record displayed for quick review or replay.
 */
data class ConversationEntry(
    val identifier: Long,
    val role: CommunicatorRole,
    val text: String,
)