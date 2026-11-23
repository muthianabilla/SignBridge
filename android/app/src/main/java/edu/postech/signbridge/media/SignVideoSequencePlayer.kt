package edu.postech.signbridge.media

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import edu.postech.signbridge.data.SignVocabulary
import edu.postech.signbridge.model.SpokenWordToken
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Plays matched sign video clips sequentially from `assets/signs/` using Media3 [ExoPlayer].
 *
 * Loads one [MediaItem] at a time in a loop rather than queuing a multi-item ExoPlayer playlist,
 * preventing premature `STATE_ENDED` transitions across PopSign files that differ in resolution
 * or encoding parameters. If any individual clip is missing from `assets/signs/`, its timed
 * caption preview plays inline without interrupting the rest of the sequence.
 */
class SignVideoSequencePlayer(
    applicationContext: Context,
) {

    companion object {
        private const val LOG_TAG = "SignVideoSequencePlayer"
        const val NORMAL_PLAYBACK_SPEED = 1.0f
        const val SLOW_PLAYBACK_SPEED = 0.75f
        private const val MISSING_VIDEO_PREVIEW_DURATION_MILLISECONDS = 1_200L
        private const val INTER_WORD_TRANSITION_GAP_MILLISECONDS = 120L
        private const val VIDEO_STEP_INTERVAL_MILLISECONDS = 50L
        private const val MAXIMUM_SINGLE_CLIP_DURATION_MILLISECONDS = 15_000L
    }

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(applicationContext).build().apply {
        // Sign clips carry no meaningful audio track; muting prevents accidental feedback.
        volume = 0f
    }

    private val mutableCurrentTokenIndex = MutableStateFlow<Int?>(null)
    val currentTokenIndex: StateFlow<Int?> = mutableCurrentTokenIndex.asStateFlow()

    private val mutablePlaybackSpeed = MutableStateFlow(NORMAL_PLAYBACK_SPEED)
    val playbackSpeed: StateFlow<Float> = mutablePlaybackSpeed.asStateFlow()

    private val mutableActiveClipProgress = MutableStateFlow(0f)
    val activeClipProgress: StateFlow<Float> = mutableActiveClipProgress.asStateFlow()

    private val mutableMissingAssetFileName = MutableStateFlow<String?>(null)
    val missingAssetFileName: StateFlow<String?> = mutableMissingAssetFileName.asStateFlow()

    private val mutablePlaybackErrorMessage = MutableStateFlow<String?>(null)
    val playbackErrorMessage: StateFlow<String?> = mutablePlaybackErrorMessage.asStateFlow()

    private var activeClipCompletion: CompletableDeferred<Unit>? = null

    init {
        exoPlayer.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        completeActiveClip()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(LOG_TAG, "Sign video playback failed: ${error.errorCodeName}", error)
                    mutablePlaybackErrorMessage.value =
                        "Video decode error (${error.errorCodeName}). Try H.264 720p MP4."
                    completeActiveClip()
                }
            },
        )
    }

    /**
     * Toggles playback speed between `1.0x` normal speed and `0.75x` slow-motion learning speed.
     */
    fun toggleSlowPlaybackSpeed() {
        val nextSpeed = if (mutablePlaybackSpeed.value < NORMAL_PLAYBACK_SPEED) {
            NORMAL_PLAYBACK_SPEED
        } else {
            SLOW_PLAYBACK_SPEED
        }
        mutablePlaybackSpeed.value = nextSpeed
        exoPlayer.setPlaybackSpeed(nextSpeed)
    }

    /**
     * Skips the currently playing word clip so the sequence immediately advances to the next word.
     */
    fun skipToNextClip() {
        exoPlayer.stop()
        completeActiveClip()
    }

    /**
     * Plays every matched sign in [tokens] starting at or after [startFromTokenIndex] in sequence.
     *
     * Suspends until the final matched word finishes or playback is cancelled via [stop].
     */
    suspend fun playTokenSequence(
        tokens: List<SpokenWordToken>,
        startFromTokenIndex: Int = 0,
    ) {
        stop()
        mutablePlaybackErrorMessage.value = null

        val matchedEntries = tokens.mapIndexedNotNull { tokenIndex, token ->
            if (tokenIndex >= startFromTokenIndex && token is SpokenWordToken.MatchedSign) {
                tokenIndex to token
            } else {
                null
            }
        }

        if (matchedEntries.isEmpty()) {
            return
        }

        try {
            for ((entryPosition, entry) in matchedEntries.withIndex()) {
                val (tokenIndex, matchedToken) = entry
                mutableCurrentTokenIndex.value = tokenIndex
                mutableActiveClipProgress.value = 0f

                if (matchedToken.isVideoAssetAvailable) {
                    mutableMissingAssetFileName.value = null
                    playSingleVideoClip(matchedToken)
                } else {
                    mutableMissingAssetFileName.value = matchedToken.vocabularyWord.videoFileName
                    runMissingAssetCaptionStep()
                    mutableMissingAssetFileName.value = null
                }

                val hasMoreWords = entryPosition < matchedEntries.lastIndex
                if (hasMoreWords) {
                    delay(INTER_WORD_TRANSITION_GAP_MILLISECONDS)
                }
            }
        } finally {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            mutableActiveClipProgress.value = 0f
            mutableCurrentTokenIndex.value = null
            mutableMissingAssetFileName.value = null
            completeActiveClip()
        }
    }

    fun stop() {
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        mutableActiveClipProgress.value = 0f
        mutableCurrentTokenIndex.value = null
        mutableMissingAssetFileName.value = null
        completeActiveClip()
    }

    fun release() {
        stop()
        exoPlayer.release()
    }

    private suspend fun playSingleVideoClip(matchedToken: SpokenWordToken.MatchedSign) {
        // Stop before creating the completion signal so ExoPlayer returns to STATE_IDLE
        // synchronously and cannot deliver a stale STATE_ENDED from any prior clip.
        exoPlayer.stop()
        exoPlayer.clearMediaItems()

        val clipCompletion = CompletableDeferred<Unit>()
        activeClipCompletion = clipCompletion

        val mediaItem = MediaItem.fromUri(
            SignVocabulary.assetUriForWord(matchedToken.vocabularyWord),
        )
        exoPlayer.setMediaItem(mediaItem, true)
        exoPlayer.setPlaybackSpeed(mutablePlaybackSpeed.value)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true

        var elapsedMilliseconds = 0L
        while (!clipCompletion.isCompleted &&
            elapsedMilliseconds < MAXIMUM_SINGLE_CLIP_DURATION_MILLISECONDS
        ) {
            updatePlaybackProgress()
            delay(VIDEO_STEP_INTERVAL_MILLISECONDS)
            elapsedMilliseconds += VIDEO_STEP_INTERVAL_MILLISECONDS
        }

        completeActiveClip()
    }

    private suspend fun runMissingAssetCaptionStep() {
        val durationForWord = (MISSING_VIDEO_PREVIEW_DURATION_MILLISECONDS /
                mutablePlaybackSpeed.value).toLong()
        val stepCount = (durationForWord / VIDEO_STEP_INTERVAL_MILLISECONDS)
            .toInt()
            .coerceAtLeast(1)

        for (stepIndex in 1..stepCount) {
            mutableActiveClipProgress.value = stepIndex.toFloat() / stepCount.toFloat()
            delay(VIDEO_STEP_INTERVAL_MILLISECONDS)
        }
    }

    private fun updatePlaybackProgress() {
        val durationMilliseconds = exoPlayer.duration
        if (durationMilliseconds > 0L) {
            val fraction = exoPlayer.currentPosition.toFloat() / durationMilliseconds.toFloat()
            mutableActiveClipProgress.value = fraction.coerceIn(0f, 1f)
        }
    }

    private fun completeActiveClip() {
        activeClipCompletion?.complete(Unit)
        activeClipCompletion = null
    }
}
