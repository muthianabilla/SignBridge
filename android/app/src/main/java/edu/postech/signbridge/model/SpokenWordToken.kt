package edu.postech.signbridge.model

/**
 * Represents a segment of a spoken English sentence after word-for-word dictionary lookup.
 */
sealed interface SpokenWordToken {
    val spokenText: String

    /**
     * A token that maps to a bundled sign video clip.
     */
    data class MatchedSign(
        override val spokenText: String,
        val vocabularyWord: VocabularyWord,
        val isVideoAssetAvailable: Boolean,
    ) : SpokenWordToken

    /**
     * A token outside the 20-word vocabulary, kept visible so the parent understands why it is skipped.
     */
    data class UnsupportedWord(
        override val spokenText: String,
    ) : SpokenWordToken
}