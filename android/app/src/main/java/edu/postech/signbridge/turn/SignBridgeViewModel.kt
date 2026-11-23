package edu.postech.signbridge.turn

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import edu.postech.signbridge.cues.AudioCuePlayer
import edu.postech.signbridge.cues.HapticCuePlayer
import edu.postech.signbridge.data.SignVocabulary
import edu.postech.signbridge.media.OfflineSpeechRecognizer
import edu.postech.signbridge.media.SignVideoSequencePlayer
import edu.postech.signbridge.media.VoiceCommunicationTextToSpeech
import edu.postech.signbridge.model.CommunicatorRole
import edu.postech.signbridge.model.ConversationEntry
import edu.postech.signbridge.model.MicrophoneStatus
import edu.postech.signbridge.model.SignDetectionResult
import edu.postech.signbridge.model.SpokenWordToken
import edu.postech.signbridge.model.TurnPhase
import edu.postech.signbridge.model.VocabularyWord
import edu.postech.signbridge.vision.FakeSignRecognizer
import edu.postech.signbridge.vision.SignRecognizer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Coordinates the single unified bidirectional mode, enforcing one active communicator at a time
 * via turn-based microphone/camera gating and distinct Start/Running/End sensory cues.
 */
class SignBridgeViewModel(
    private val applicationContext: Context,
    val signRecognizer: SignRecognizer,
) : ViewModel() {

    companion object {
        private const val MINIMUM_SIGN_CONFIDENCE = 0.65f
        private const val SIGN_HOLD_CONFIRMATION_MILLISECONDS = 450L
        private const val SIGN_HOLD_TICK_MILLISECONDS = 45L
        private const val SAME_WORD_REPEAT_COOLDOWN_MILLISECONDS = 2_500L
        private const val SIGN_SENTENCE_PAUSE_DELAY_MILLISECONDS = 1_500L

        private const val POST_START_CUE_SPEECH_GAP_MILLISECONDS = 50L
        private const val POST_SPEECH_END_CUE_GAP_MILLISECONDS = 50L
        private const val ECHO_DECAY_TAIL_MILLISECONDS = 300L
        private const val VISUAL_END_SETTLE_MILLISECONDS = 300L
        private const val MAXIMUM_CONVERSATION_HISTORY_ITEMS = 12

        fun provideFactory(
            applicationContext: Context,
            signRecognizer: SignRecognizer,
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SignBridgeViewModel(
                        applicationContext = applicationContext.applicationContext,
                        signRecognizer = signRecognizer,
                    ) as T
                }
            }
        }
    }

    private data class VideoPlayerSnapshot(
        val currentTokenIndex: Int?,
        val clipProgress: Float,
        val playbackSpeed: Float,
        val missingFileName: String?,
        val errorMessage: String?,
    )

    private val audioCuePlayer = AudioCuePlayer(applicationContext)
    private val hapticCuePlayer = HapticCuePlayer(applicationContext)
    private val textToSpeech = VoiceCommunicationTextToSpeech(applicationContext)
    val videoSequencePlayer = SignVideoSequencePlayer(applicationContext)

    private val speechRecognizer = OfflineSpeechRecognizer(
        applicationContext = applicationContext,
        coroutineScope = viewModelScope,
        onTranscriptFinalized = ::handleSpokenTranscript,
    )

    private val mutableScreenState = MutableStateFlow(
        SignBridgeScreenState(
            isFakeSignRecognizerActive = signRecognizer is FakeSignRecognizer,
        ),
    )
    val screenState: StateFlow<SignBridgeScreenState> = mutableScreenState.asStateFlow()

    private val pendingSignSentenceWords = mutableListOf<VocabularyWord>()
    private var pendingSignFlushJob: Job? = null
    private var activeTurnJob: Job? = null
    private var signStabilizationJob: Job? = null
    private var pendingWordIdentifier: String? = null
    private var lastStackedCameraWordIdentifier: String? = null
    private var lastStackedCameraTimestampMilliseconds: Long = 0L
    private var nextConversationEntryIdentifier: Long = 1L

    init {
        observeSubsystems()
    }

    fun updatePermissions(
        hasCameraPermission: Boolean,
        hasMicrophonePermission: Boolean,
    ) {
        mutableScreenState.update { current ->
            current.copy(
                hasCameraPermission = hasCameraPermission,
                hasMicrophonePermission = hasMicrophonePermission,
            )
        }
        speechRecognizer.updateAudioPermission(hasMicrophonePermission)
        refreshMicrophoneStatus()
    }

    fun toggleMicrophoneEnabled() {
        val nextEnabled = !mutableScreenState.value.isMicrophoneEnabledByUser
        mutableScreenState.update { current ->
            current.copy(isMicrophoneEnabledByUser = nextEnabled)
        }
        speechRecognizer.setUserEnabled(nextEnabled)
        refreshMicrophoneStatus()
    }

    fun setPracticeModeActive(isActive: Boolean) {
        if (isActive) {
            cancelPendingSignSentence()
        }
        mutableScreenState.update { current ->
            current.copy(isPracticeModeActive = isActive)
        }
    }

    fun toggleSlowPlaybackSpeed() {
        videoSequencePlayer.toggleSlowPlaybackSpeed()
    }

    fun skipToNextSignClip() {
        if (mutableScreenState.value.turnPhase == TurnPhase.SPEECH_TO_SIGN_RUNNING) {
            videoSequencePlayer.skipToNextClip()
        }
    }

    /**
     * Replays the recognized word sequence starting from [startFromTokenIndex] through the final
     * matched word, framed by Start cues before the first played word and End cues after the last.
     */
    fun replayLastSpeechToSignSequence(startFromTokenIndex: Int = 0) {
        val existingTokens = mutableScreenState.value.speechTokens
        val existingTranscript = mutableScreenState.value.lastSpokenTranscript
        if (existingTokens.isEmpty()) {
            return
        }
        if (mutableScreenState.value.turnPhase != TurnPhase.IDLE_LISTENING) {
            return
        }
        launchSpeechToSignTurn(
            transcript = existingTranscript,
            tokens = existingTokens,
            startFromTokenIndex = startFromTokenIndex,
            recordConversationEntry = false,
        )
    }

    fun submitTypedPhrase(rawPhrase: String) {
        val trimmedPhrase = rawPhrase.trim()
        if (trimmedPhrase.isEmpty()) {
            return
        }
        handleSpokenTranscript(trimmedPhrase)
    }

    /**
     * Plays a single word from the Practice menu while remaining in Practice mode.
     */
    fun practiceVocabularyWord(vocabularyWord: VocabularyWord) {
        if (mutableScreenState.value.turnPhase != TurnPhase.IDLE_LISTENING) {
            return
        }
        val singleToken = listOf(
            SpokenWordToken.MatchedSign(
                spokenText = vocabularyWord.displayLabel,
                vocabularyWord = vocabularyWord,
                isVideoAssetAvailable = SignVocabulary.hasVideoAsset(
                    applicationContext = applicationContext,
                    vocabularyWord = vocabularyWord,
                ),
            ),
        )
        launchSpeechToSignTurn(
            transcript = vocabularyWord.displayLabel,
            tokens = singleToken,
            startFromTokenIndex = 0,
            recordConversationEntry = true,
        )
    }

    /**
     * Stacks [vocabularyWord] into an internal buffer and schedules automatic speech after
     * [SIGN_SENTENCE_PAUSE_DELAY_MILLISECONDS]. Tapping another test sign interrupts and resets
     * that delay so multiple words combine into a full spoken sentence.
     */
    fun appendSimulatedSignWord(vocabularyWord: VocabularyWord) {
        if (mutableScreenState.value.turnPhase != TurnPhase.IDLE_LISTENING) {
            return
        }
        enqueueSignWordAndScheduleSpeech(vocabularyWord)
    }

    fun replayLastSpokenWord() {
        val sentenceToSpeak = mutableScreenState.value.lastSpokenWord ?: return
        if (mutableScreenState.value.turnPhase != TurnPhase.IDLE_LISTENING) {
            return
        }
        cancelPendingSignSentence()
        launchSignToSpeechTurn(sentenceToSpeak)
    }

    fun dismissTransientStatusMessage() {
        mutableScreenState.update { current -> current.copy(transientStatusMessage = null) }
    }

    override fun onCleared() {
        pendingSignFlushJob?.cancel()
        activeTurnJob?.cancel()
        signStabilizationJob?.cancel()
        speechRecognizer.release()
        textToSpeech.release()
        videoSequencePlayer.release()
        audioCuePlayer.release()
        signRecognizer.release()
        super.onCleared()
    }

    private fun observeSubsystems() {
        viewModelScope.launch {
            signRecognizer.detectionResults.collect { detectionResult ->
                mutableScreenState.update { current ->
                    current.copy(liveDetectionResult = detectionResult)
                }
                evaluateIncomingSignDetection(detectionResult)
            }
        }

        viewModelScope.launch {
            combine(
                speechRecognizer.partialTranscript,
                speechRecognizer.isActivelyListening,
                speechRecognizer.isOfflineModelAvailable,
                speechRecognizer.modelLoadHint,
            ) { partialTranscript, _, _, modelLoadHint ->
                partialTranscript to modelLoadHint
            }.collect { (partialTranscript, modelLoadHint) ->
                mutableScreenState.update { current ->
                    current.copy(
                        partialSpeechTranscript = partialTranscript,
                        transientStatusMessage = modelLoadHint ?: current.transientStatusMessage,
                    )
                }
                refreshMicrophoneStatus()
            }
        }

        viewModelScope.launch {
            combine(
                videoSequencePlayer.currentTokenIndex,
                videoSequencePlayer.activeClipProgress,
                videoSequencePlayer.playbackSpeed,
                videoSequencePlayer.missingAssetFileName,
                videoSequencePlayer.playbackErrorMessage,
            ) { currentTokenIndex, clipProgress, playbackSpeed, missingFileName, errorMessage ->
                VideoPlayerSnapshot(
                    currentTokenIndex = currentTokenIndex,
                    clipProgress = clipProgress,
                    playbackSpeed = playbackSpeed,
                    missingFileName = missingFileName,
                    errorMessage = errorMessage,
                )
            }.collect { snapshot ->
                mutableScreenState.update { current ->
                    current.copy(
                        activeSpeechTokenIndex = snapshot.currentTokenIndex,
                        activeClipProgress = snapshot.clipProgress,
                        playbackSpeedMultiplier = snapshot.playbackSpeed,
                        missingVideoFileName = snapshot.missingFileName,
                        transientStatusMessage =
                            snapshot.errorMessage ?: current.transientStatusMessage,
                    )
                }
            }
        }
    }

    private fun evaluateIncomingSignDetection(detectionResult: SignDetectionResult) {
        if (mutableScreenState.value.turnPhase != TurnPhase.IDLE_LISTENING) {
            cancelSignStabilization()
            return
        }

        val matchedWord = if (detectionResult.confidenceScore >= MINIMUM_SIGN_CONFIDENCE) {
            SignVocabulary.findWord(detectionResult.recognizedWord)
        } else {
            null
        }

        if (matchedWord == null) {
            lastStackedCameraWordIdentifier = null
            cancelSignStabilization()
            return
        }

        val elapsedSinceLastStacked =
            System.currentTimeMillis() - lastStackedCameraTimestampMilliseconds
        if (matchedWord.identifier == lastStackedCameraWordIdentifier &&
            elapsedSinceLastStacked < SAME_WORD_REPEAT_COOLDOWN_MILLISECONDS
        ) {
            return
        }

        if (pendingWordIdentifier == matchedWord.identifier && signStabilizationJob?.isActive == true) {
            return
        }

        cancelSignStabilization()
        pendingWordIdentifier = matchedWord.identifier
        signStabilizationJob = viewModelScope.launch {
            val tickCount = (SIGN_HOLD_CONFIRMATION_MILLISECONDS / SIGN_HOLD_TICK_MILLISECONDS)
                .toInt()
                .coerceAtLeast(1)

            for (tickIndex in 1..tickCount) {
                delay(SIGN_HOLD_TICK_MILLISECONDS)
                mutableScreenState.update { current ->
                    current.copy(signHoldProgress = tickIndex.toFloat() / tickCount.toFloat())
                }
            }

            if (mutableScreenState.value.turnPhase == TurnPhase.IDLE_LISTENING) {
                lastStackedCameraWordIdentifier = matchedWord.identifier
                lastStackedCameraTimestampMilliseconds = System.currentTimeMillis()
                enqueueSignWordAndScheduleSpeech(matchedWord)
                mutableScreenState.update { current -> current.copy(signHoldProgress = 0f) }
            }
        }
    }

    private fun enqueueSignWordAndScheduleSpeech(vocabularyWord: VocabularyWord) {
        pendingSignSentenceWords.add(vocabularyWord)
        pendingSignFlushJob?.cancel()
        pendingSignFlushJob = viewModelScope.launch {
            delay(SIGN_SENTENCE_PAUSE_DELAY_MILLISECONDS)
            if (mutableScreenState.value.turnPhase == TurnPhase.IDLE_LISTENING &&
                pendingSignSentenceWords.isNotEmpty()
            ) {
                val sentence = pendingSignSentenceWords.joinToString(separator = " ") { word ->
                    word.displayLabel
                }
                pendingSignSentenceWords.clear()
                launchSignToSpeechTurn(sentence)
            }
        }
    }

    private fun cancelPendingSignSentence() {
        pendingSignFlushJob?.cancel()
        pendingSignFlushJob = null
        pendingSignSentenceWords.clear()
    }

    private fun launchSignToSpeechTurn(spokenSentence: String) {
        if (activeTurnJob?.isActive == true) {
            return
        }
        cancelSignStabilization()

        activeTurnJob = viewModelScope.launch {
            gateAllInputsForTurn()
            try {
                // 1. Start cue (short quiet pip + warm firm thud + Teal ripple) strictly before TTS.
                setTurnPhase(TurnPhase.SIGN_TO_SPEECH_STARTING)
                mutableScreenState.update { current ->
                    current.copy(
                        lastSpokenWord = spokenSentence,
                        transientStatusMessage = null,
                    )
                }
                audioCuePlayer.playSignToSpeechStartCue()
                hapticCuePlayer.playSignToSpeechStartCue()
                delay(
                    AudioCuePlayer.SIGN_TO_SPEECH_CUE_DURATION_MILLISECONDS +
                            POST_START_CUE_SPEECH_GAP_MILLISECONDS,
                )

                // 2. Running output (TTS speaks the full stacked sentence; visual cue only).
                setTurnPhase(TurnPhase.SIGN_TO_SPEECH_RUNNING)
                textToSpeech.speakAndAwaitCompletion(spokenSentence)

                // 3. End cue (short quiet settling pip + trailing double-tap + check-glow).
                setTurnPhase(TurnPhase.SIGN_TO_SPEECH_ENDING)
                delay(POST_SPEECH_END_CUE_GAP_MILLISECONDS)
                audioCuePlayer.playSignToSpeechEndCue()
                hapticCuePlayer.playSignToSpeechEndCue()
                delay(
                    maxOf(
                        AudioCuePlayer.SIGN_TO_SPEECH_CUE_DURATION_MILLISECONDS,
                        VISUAL_END_SETTLE_MILLISECONDS,
                    ) + ECHO_DECAY_TAIL_MILLISECONDS,
                )

                appendConversationEntry(
                    role = CommunicatorRole.SIGNER,
                    text = spokenSentence,
                )
            } finally {
                ungateAllInputsAfterTurn()
            }
        }
    }

    private fun handleSpokenTranscript(transcript: String) {
        if (mutableScreenState.value.turnPhase != TurnPhase.IDLE_LISTENING) {
            return
        }

        val tokens = SignVocabulary.tokenizeSentence(
            sentence = transcript,
            applicationContext = applicationContext,
        )
        val hasMatchedWord = tokens.any { token -> token is SpokenWordToken.MatchedSign }

        if (!hasMatchedWord) {
            mutableScreenState.update { current ->
                current.copy(
                    lastSpokenTranscript = transcript,
                    speechTokens = tokens,
                    transientStatusMessage =
                        "Heard \"$transcript\" — no match in 20-word set.",
                )
            }
            return
        }

        launchSpeechToSignTurn(
            transcript = transcript,
            tokens = tokens,
            startFromTokenIndex = 0,
            recordConversationEntry = true,
        )
    }

    private fun launchSpeechToSignTurn(
        transcript: String,
        tokens: List<SpokenWordToken>,
        startFromTokenIndex: Int,
        recordConversationEntry: Boolean,
    ) {
        if (activeTurnJob?.isActive == true) {
            return
        }
        cancelPendingSignSentence()
        cancelSignStabilization()

        activeTurnJob = viewModelScope.launch {
            gateAllInputsForTurn()
            try {
                // 1. Start cue (bright rising bell + crisp double pulse + Indigo corner snap).
                setTurnPhase(TurnPhase.SPEECH_TO_SIGN_STARTING)
                mutableScreenState.update { current ->
                    current.copy(
                        lastSpokenTranscript = transcript,
                        speechTokens = tokens,
                        transientStatusMessage = null,
                    )
                }
                audioCuePlayer.playSpeechToSignStartCue()
                hapticCuePlayer.playSpeechToSignStartCue()
                delay(AudioCuePlayer.SPEECH_TO_SIGN_CUE_DURATION_MILLISECONDS)

                // 2. Running output (word-by-word sign video playback; peripheral progress rail only).
                setTurnPhase(TurnPhase.SPEECH_TO_SIGN_RUNNING)
                videoSequencePlayer.playTokenSequence(
                    tokens = tokens,
                    startFromTokenIndex = startFromTokenIndex,
                )

                // 3. End cue (bright settling bell + brief pulse + queue sweep) with mic still gated.
                setTurnPhase(TurnPhase.SPEECH_TO_SIGN_ENDING)
                audioCuePlayer.playSpeechToSignEndCue()
                hapticCuePlayer.playSpeechToSignEndCue()
                delay(
                    maxOf(
                        AudioCuePlayer.SPEECH_TO_SIGN_CUE_DURATION_MILLISECONDS,
                        VISUAL_END_SETTLE_MILLISECONDS,
                    ) + ECHO_DECAY_TAIL_MILLISECONDS,
                )

                if (recordConversationEntry) {
                    appendConversationEntry(
                        role = CommunicatorRole.SPEAKER,
                        text = transcript,
                    )
                }
            } finally {
                ungateAllInputsAfterTurn()
            }
        }
    }

    private fun gateAllInputsForTurn() {
        speechRecognizer.setGatedForTurn(true)
        signRecognizer.setRecognitionEnabled(false)
        refreshMicrophoneStatus()
    }

    private fun ungateAllInputsAfterTurn() {
        setTurnPhase(TurnPhase.IDLE_LISTENING)
        signRecognizer.setRecognitionEnabled(true)
        speechRecognizer.setGatedForTurn(false)
        refreshMicrophoneStatus()
    }

    private fun setTurnPhase(turnPhase: TurnPhase) {
        mutableScreenState.update { current -> current.copy(turnPhase = turnPhase) }
    }

    private fun cancelSignStabilization() {
        signStabilizationJob?.cancel()
        signStabilizationJob = null
        pendingWordIdentifier = null
        mutableScreenState.update { current -> current.copy(signHoldProgress = 0f) }
    }

    private fun appendConversationEntry(
        role: CommunicatorRole,
        text: String,
    ) {
        val entry = ConversationEntry(
            identifier = nextConversationEntryIdentifier++,
            role = role,
            text = text,
        )
        mutableScreenState.update { current ->
            current.copy(
                conversationHistory = (listOf(entry) + current.conversationHistory)
                    .take(MAXIMUM_CONVERSATION_HISTORY_ITEMS),
            )
        }
    }

    private fun refreshMicrophoneStatus() {
        val currentState = mutableScreenState.value
        val nextStatus = when {
            !currentState.hasMicrophonePermission -> MicrophoneStatus.PERMISSION_REQUIRED
            !currentState.isMicrophoneEnabledByUser -> MicrophoneStatus.MUTED_BY_USER
            !speechRecognizer.isOfflineModelAvailable.value -> {
                MicrophoneStatus.OFFLINE_MODEL_UNAVAILABLE
            }
            currentState.turnPhase.isOutputting -> MicrophoneStatus.GATED_FOR_TURN
            else -> MicrophoneStatus.LISTENING
        }
        mutableScreenState.update { state -> state.copy(microphoneStatus = nextStatus) }
    }
}
