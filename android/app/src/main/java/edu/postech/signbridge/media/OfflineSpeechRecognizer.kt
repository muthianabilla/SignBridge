package edu.postech.signbridge.media

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fully offline US English speech-to-text manager with turn-based microphone gating.
 *
 * Uses embedded Vosk (`assets/vosk-model-en-us/`) via [AudioRecord] so speech recognition works
 * offline on de-Googled devices such as GrapheneOS. All calls to Vosk's native [Recognizer] are
 * serialized onto a single dedicated executor thread because Vosk's C++ decoder is not thread-safe.
 * Falls back to Android's on-device [SpeechRecognizer] if no Vosk asset folder is bundled.
 */
class OfflineSpeechRecognizer(
    private val applicationContext: Context,
    private val coroutineScope: CoroutineScope,
    private val onTranscriptFinalized: (String) -> Unit,
) {

    companion object {
        private const val LOG_TAG = "OfflineSpeechRecognizer"
        private const val VOSK_ASSET_DIRECTORY = "vosk-model-en-us"
        private const val MODEL_UNPACK_MARKER_FILE = ".unpacked_ready"
        private const val SAMPLE_RATE_HERTZ = 16_000
        private const val AUDIO_READ_CHUNK_BYTES = 4_096

        private const val RESTART_AFTER_TIMEOUT_MILLISECONDS = 350L
        private const val RESTART_AFTER_BUSY_MILLISECONDS = 650L
        private const val LANGUAGE_TAG_UNITED_STATES = "en-US"
    }

    private enum class RecognitionBackend {
        NONE,
        EMBEDDED_VOSK,
        ANDROID_ON_DEVICE_SERVICE,
    }

    private val mutablePartialTranscript = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = mutablePartialTranscript.asStateFlow()

    private val mutableIsOfflineModelAvailable = MutableStateFlow(false)
    val isOfflineModelAvailable: StateFlow<Boolean> = mutableIsOfflineModelAvailable.asStateFlow()

    private val mutableIsActivelyListening = MutableStateFlow(false)
    val isActivelyListening: StateFlow<Boolean> = mutableIsActivelyListening.asStateFlow()

    private val mutableModelLoadHint = MutableStateFlow<String?>(null)
    val modelLoadHint: StateFlow<String?> = mutableModelLoadHint.asStateFlow()

    private val voskSingleThreadExecutor = Executors.newSingleThreadExecutor()
    private val isVoskCaptureLoopActive = AtomicBoolean(false)

    private var activeBackend: RecognitionBackend = RecognitionBackend.NONE
    private var voskModel: Model? = null
    private var voskRecognizer: Recognizer? = null
    private var modelPreparationStarted: Boolean = false

    private var androidSpeechRecognizer: SpeechRecognizer? = null
    private var androidRestartJob: Job? = null

    private var hasAudioPermission: Boolean = false
    private var userEnabled: Boolean = true
    private var gatedForTurn: Boolean = false

    private val androidRecognitionIntent: Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE_TAG_UNITED_STATES)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

    init {
        prepareOfflineSpeechBackend()
    }

    fun updateAudioPermission(isGranted: Boolean) {
        hasAudioPermission = isGranted
        if (activeBackend == RecognitionBackend.NONE && !modelPreparationStarted) {
            prepareOfflineSpeechBackend()
        }
        synchronizeListeningState()
    }

    fun setUserEnabled(isEnabled: Boolean) {
        userEnabled = isEnabled
        synchronizeListeningState()
    }

    fun setGatedForTurn(isGated: Boolean) {
        gatedForTurn = isGated
        synchronizeListeningState()
    }

    fun release() {
        isVoskCaptureLoopActive.set(false)
        androidRestartJob?.cancel()
        androidRestartJob = null
        mutableIsActivelyListening.value = false

        androidSpeechRecognizer?.destroy()
        androidSpeechRecognizer = null

        voskSingleThreadExecutor.execute {
            voskRecognizer?.close()
            voskRecognizer = null
            voskModel?.close()
            voskModel = null
        }
        voskSingleThreadExecutor.shutdown()
    }

    private fun prepareOfflineSpeechBackend() {
        modelPreparationStarted = true
        val hasVoskAssetFolder = runCatching {
            val entries = applicationContext.assets.list(VOSK_ASSET_DIRECTORY)
            !entries.isNullOrEmpty()
        }.getOrDefault(false)

        if (hasVoskAssetFolder) {
            coroutineScope.launch {
                loadEmbeddedVoskModel()
            }
            return
        }

        if (SpeechRecognizer.isOnDeviceRecognitionAvailable(applicationContext)) {
            activeBackend = RecognitionBackend.ANDROID_ON_DEVICE_SERVICE
            mutableIsOfflineModelAvailable.value = true
            mutableModelLoadHint.value = null
            synchronizeListeningState()
        } else {
            activeBackend = RecognitionBackend.NONE
            mutableIsOfflineModelAvailable.value = false
            mutableModelLoadHint.value =
                "To enable offline mic on GrapheneOS, add Vosk model files to assets/$VOSK_ASSET_DIRECTORY/."
        }
    }

    private suspend fun loadEmbeddedVoskModel() {
        mutableModelLoadHint.value = "Loading offline speech model…"
        val loadedModel = withContext(Dispatchers.IO) {
            try {
                val unpackedDirectory = unpackVoskModelAssetsIfNeeded()
                Model(unpackedDirectory.absolutePath)
            } catch (exception: Exception) {
                Log.e(LOG_TAG, "Failed to unpack or open Vosk model", exception)
                null
            }
        }

        if (loadedModel == null) {
            activeBackend = RecognitionBackend.NONE
            mutableIsOfflineModelAvailable.value = false
            mutableModelLoadHint.value =
                "Failed to load assets/$VOSK_ASSET_DIRECTORY/. Check model folder contents."
            return
        }

        voskSingleThreadExecutor.execute {
            voskModel = loadedModel
            voskRecognizer = Recognizer(loadedModel, SAMPLE_RATE_HERTZ.toFloat())
        }
        activeBackend = RecognitionBackend.EMBEDDED_VOSK
        mutableIsOfflineModelAvailable.value = true
        mutableModelLoadHint.value = null
        synchronizeListeningState()
    }

    private fun unpackVoskModelAssetsIfNeeded(): File {
        val targetDirectory = File(applicationContext.filesDir, VOSK_ASSET_DIRECTORY)
        val markerFile = File(targetDirectory, MODEL_UNPACK_MARKER_FILE)
        if (markerFile.exists()) {
            return targetDirectory
        }

        if (targetDirectory.exists()) {
            targetDirectory.deleteRecursively()
        }
        copyAssetTree(assetPath = VOSK_ASSET_DIRECTORY, destinationDirectory = targetDirectory)
        markerFile.writeText("ready")
        return targetDirectory
    }

    private fun copyAssetTree(
        assetPath: String,
        destinationDirectory: File,
    ) {
        val assetManager = applicationContext.assets
        val children = assetManager.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            destinationDirectory.parentFile?.mkdirs()
            assetManager.open(assetPath).use { inputStream ->
                FileOutputStream(destinationDirectory).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            return
        }

        destinationDirectory.mkdirs()
        for (childName in children) {
            copyAssetTree(
                assetPath = "$assetPath/$childName",
                destinationDirectory = File(destinationDirectory, childName),
            )
        }
    }

    private fun synchronizeListeningState() {
        val shouldListen = hasAudioPermission &&
                userEnabled &&
                !gatedForTurn &&
                mutableIsOfflineModelAvailable.value

        when (activeBackend) {
            RecognitionBackend.EMBEDDED_VOSK -> synchronizeVoskListening(shouldListen)
            RecognitionBackend.ANDROID_ON_DEVICE_SERVICE -> synchronizeAndroidServiceListening(shouldListen)
            RecognitionBackend.NONE -> Unit
        }
    }

    private fun synchronizeVoskListening(shouldListen: Boolean) {
        if (shouldListen) {
            if (isVoskCaptureLoopActive.compareAndSet(false, true)) {
                voskSingleThreadExecutor.execute {
                    runVoskAudioCaptureLoop()
                }
            }
        } else {
            isVoskCaptureLoopActive.set(false)
            mutableIsActivelyListening.value = false
            mutablePartialTranscript.value = ""
        }
    }

    @SuppressLint("MissingPermission")
    private fun runVoskAudioCaptureLoop() {
        val recognizer = voskRecognizer
        if (recognizer == null || !hasAudioPermission) {
            isVoskCaptureLoopActive.set(false)
            return
        }

        val minimumBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HERTZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimumBufferBytes <= 0) {
            Log.e(LOG_TAG, "Invalid AudioRecord minimum buffer size: $minimumBufferBytes")
            isVoskCaptureLoopActive.set(false)
            return
        }

        val bufferSizeBytes = maxOf(minimumBufferBytes, AUDIO_READ_CHUNK_BYTES * 2)
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE_HERTZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSizeBytes,
        )

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(LOG_TAG, "AudioRecord failed to initialize")
            audioRecord.release()
            isVoskCaptureLoopActive.set(false)
            return
        }

        val audioBuffer = ByteArray(AUDIO_READ_CHUNK_BYTES)
        try {
            recognizer.reset()
            audioRecord.startRecording()
            mutableIsActivelyListening.value = true

            while (isVoskCaptureLoopActive.get()) {
                val bytesRead = audioRecord.read(audioBuffer, 0, audioBuffer.size)
                if (bytesRead <= 0) {
                    continue
                }

                if (!isVoskCaptureLoopActive.get()) {
                    break
                }

                val hasFinalPhrase = recognizer.acceptWaveForm(audioBuffer, bytesRead)
                if (hasFinalPhrase) {
                    val finalText = extractJsonField(recognizer.result, "text")
                    mutablePartialTranscript.value = ""
                    if (finalText.isNotBlank() && isVoskCaptureLoopActive.get()) {
                        coroutineScope.launch {
                            if (!gatedForTurn && userEnabled) {
                                onTranscriptFinalized(finalText)
                            }
                        }
                    }
                } else {
                    val partialText = extractJsonField(recognizer.partialResult, "partial")
                    mutablePartialTranscript.value = partialText
                }
            }
        } catch (exception: Exception) {
            Log.e(LOG_TAG, "Error during Vosk audio capture", exception)
        } finally {
            runCatching { audioRecord.stop() }
            audioRecord.release()
            recognizer.reset()
            mutableIsActivelyListening.value = false
            mutablePartialTranscript.value = ""
            isVoskCaptureLoopActive.set(false)
        }
    }

    private fun extractJsonField(
        jsonPayload: String,
        fieldName: String,
    ): String {
        return try {
            JSONObject(jsonPayload).optString(fieldName, "").trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun synchronizeAndroidServiceListening(shouldListen: Boolean) {
        if (shouldListen) {
            if (androidSpeechRecognizer == null) {
                val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(applicationContext)
                recognizer.setRecognitionListener(createAndroidRecognitionListener())
                androidSpeechRecognizer = recognizer
            }
            if (!mutableIsActivelyListening.value && androidRestartJob?.isActive != true) {
                mutablePartialTranscript.value = ""
                mutableIsActivelyListening.value = true
                androidSpeechRecognizer?.startListening(androidRecognitionIntent)
            }
        } else {
            androidRestartJob?.cancel()
            androidRestartJob = null
            mutableIsActivelyListening.value = false
            mutablePartialTranscript.value = ""
            androidSpeechRecognizer?.cancel()
        }
    }

    private fun scheduleAndroidServiceRearm(delayMilliseconds: Long) {
        androidRestartJob?.cancel()
        androidRestartJob = coroutineScope.launch {
            delay(delayMilliseconds)
            synchronizeListeningState()
        }
    }

    private fun createAndroidRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(parameters: Bundle?) {
                mutableIsActivelyListening.value = true
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(decibels: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                mutableIsActivelyListening.value = false
            }

            override fun onError(errorCode: Int) {
                mutableIsActivelyListening.value = false
                if (gatedForTurn || !userEnabled || !hasAudioPermission) {
                    return
                }
                when (errorCode) {
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                        -> mutableIsOfflineModelAvailable.value = false
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                        scheduleAndroidServiceRearm(RESTART_AFTER_BUSY_MILLISECONDS)
                    else -> scheduleAndroidServiceRearm(RESTART_AFTER_TIMEOUT_MILLISECONDS)
                }
            }

            override fun onResults(results: Bundle?) {
                mutableIsActivelyListening.value = false
                mutablePartialTranscript.value = ""
                if (gatedForTurn || !userEnabled) {
                    return
                }
                val candidates = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val topTranscript = candidates?.firstOrNull()?.trim().orEmpty()
                if (topTranscript.isNotEmpty()) {
                    onTranscriptFinalized(topTranscript)
                } else {
                    scheduleAndroidServiceRearm(RESTART_AFTER_TIMEOUT_MILLISECONDS)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (gatedForTurn || !userEnabled) {
                    return
                }
                val candidates =
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                mutablePartialTranscript.value = candidates?.firstOrNull().orEmpty()
            }

            override fun onEvent(eventType: Int, parameters: Bundle?) = Unit
        }
    }
}
