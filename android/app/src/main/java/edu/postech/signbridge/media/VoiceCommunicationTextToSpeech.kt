package edu.postech.signbridge.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID

/**
 * Wraps Android's [TextToSpeech] configured with [AudioAttributes.CONTENT_TYPE_SPEECH].
 *
 * Uses [AudioAttributes.USAGE_MEDIA] with transient audio focus rather than `STREAM_VOICE_CALL`
 * or `MODE_IN_COMMUNICATION` so GrapheneOS Speech Services plays reliably through the main
 * loudspeaker without earpiece attenuation, while hard microphone gating prevents self-echo.
 */
class VoiceCommunicationTextToSpeech(
    applicationContext: Context,
) {

    companion object {
        private const val LOG_TAG = "SignBridgeTextToSpeech"
        private const val MINIMUM_UTTERANCE_TIMEOUT_MILLISECONDS = 6_000L
        private const val ESTIMATED_MILLISECONDS_PER_CHARACTER = 160L
        private const val SPEECH_RATE_MULTIPLIER = 0.95f
    }

    private val audioManager: AudioManager =
        applicationContext.getSystemService(AudioManager::class.java)

    private val speechAudioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val audioFocusRequest: AudioFocusRequest = AudioFocusRequest.Builder(
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
    )
        .setAudioAttributes(speechAudioAttributes)
        .setWillPauseWhenDucked(false)
        .build()

    private val mutableIsReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = mutableIsReady.asStateFlow()

    private val pendingUtterances = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val utteranceLock = Any()

    private var textToSpeechEngine: TextToSpeech? = null

    init {
        textToSpeechEngine = TextToSpeech(applicationContext) { initializationStatus ->
            if (initializationStatus == TextToSpeech.SUCCESS) {
                configureEngineAttributes()
                mutableIsReady.value = true
            } else {
                Log.e(
                    LOG_TAG,
                    "TextToSpeech initialization failed with status=$initializationStatus. " +
                            "Check Settings -> Speech -> Preferred engine and <queries> in manifest.",
                )
                mutableIsReady.value = false
            }
        }
    }

    /**
     * Speaks [spokenText] aloud and suspends until playback completes or reaches a safety timeout.
     */
    suspend fun speakAndAwaitCompletion(spokenText: String) {
        val engine = textToSpeechEngine
        if (!mutableIsReady.value || engine == null || spokenText.isBlank()) {
            Log.w(
                LOG_TAG,
                "Skipped speaking because TextToSpeech is not ready (ready=${mutableIsReady.value}).",
            )
            return
        }

        audioManager.requestAudioFocus(audioFocusRequest)

        val utteranceIdentifier = UUID.randomUUID().toString()
        val completionSignal = CompletableDeferred<Unit>()
        synchronized(utteranceLock) {
            pendingUtterances[utteranceIdentifier] = completionSignal
        }

        // Pass an explicit Bundle rather than null so all engines fire UtteranceProgressListener.
        val queueResult = engine.speak(
            spokenText,
            TextToSpeech.QUEUE_FLUSH,
            Bundle(),
            utteranceIdentifier,
        )

        if (queueResult == TextToSpeech.ERROR) {
            Log.e(LOG_TAG, "TextToSpeech.speak returned ERROR for text=\"$spokenText\"")
            removePendingUtterance(utteranceIdentifier)?.complete(Unit)
            audioManager.abandonAudioFocusRequest(audioFocusRequest)
            return
        }

        val timeoutMilliseconds = maxOf(
            MINIMUM_UTTERANCE_TIMEOUT_MILLISECONDS,
            spokenText.length * ESTIMATED_MILLISECONDS_PER_CHARACTER,
        )

        try {
            withTimeoutOrNull(timeoutMilliseconds) {
                completionSignal.await()
            }
        } finally {
            removePendingUtterance(utteranceIdentifier)
            audioManager.abandonAudioFocusRequest(audioFocusRequest)
        }
    }

    fun stop() {
        textToSpeechEngine?.stop()
        synchronized(utteranceLock) {
            for (deferred in pendingUtterances.values) {
                deferred.complete(Unit)
            }
            pendingUtterances.clear()
        }
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
    }

    fun release() {
        stop()
        textToSpeechEngine?.shutdown()
        textToSpeechEngine = null
        mutableIsReady.value = false
    }

    private fun configureEngineAttributes() {
        val engine = textToSpeechEngine ?: return
        engine.setAudioAttributes(speechAudioAttributes)
        engine.setSpeechRate(SPEECH_RATE_MULTIPLIER)

        val languageResult = engine.setLanguage(Locale.US)
        Log.i(
            LOG_TAG,
            "Initialized TextToSpeech engine=${engine.defaultEngine}, languageResult=$languageResult",
        )

        engine.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceIdentifier: String?) = Unit

                override fun onDone(utteranceIdentifier: String?) {
                    completeUtterance(utteranceIdentifier)
                }

                @Deprecated("Required override for legacy UtteranceProgressListener contract")
                override fun onError(utteranceIdentifier: String?) {
                    Log.e(LOG_TAG, "TextToSpeech utterance error for id=$utteranceIdentifier")
                    completeUtterance(utteranceIdentifier)
                }

                override fun onError(
                    utteranceIdentifier: String?,
                    errorCode: Int,
                ) {
                    Log.e(
                        LOG_TAG,
                        "TextToSpeech utterance error=$errorCode for id=$utteranceIdentifier",
                    )
                    completeUtterance(utteranceIdentifier)
                }

                override fun onStop(
                    utteranceIdentifier: String?,
                    interrupted: Boolean,
                ) {
                    completeUtterance(utteranceIdentifier)
                }
            },
        )
    }

    private fun completeUtterance(utteranceIdentifier: String?) {
        if (utteranceIdentifier == null) {
            return
        }
        removePendingUtterance(utteranceIdentifier)?.complete(Unit)
    }

    private fun removePendingUtterance(utteranceIdentifier: String): CompletableDeferred<Unit>? {
        return synchronized(utteranceLock) {
            pendingUtterances.remove(utteranceIdentifier)
        }
    }
}
