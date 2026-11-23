package edu.postech.signbridge.model

/**
 * Represents a single American Sign Language entry supported by this 20-word prototype.
 *
 * @property identifier Stable lowercase key shared between speech matching and sign recognition.
 * @property displayLabel Human-readable label shown on video captions and practice cards.
 * @property videoFileName Filename located under `assets/signs/`.
 * @property spokenAliases Lowercase words or phrases in spoken English that map to this sign.
 */
data class VocabularyWord(
    val identifier: String,
    val displayLabel: String,
    val videoFileName: String,
    val spokenAliases: List<String>,
)