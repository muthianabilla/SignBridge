package edu.postech.signbridge.cues

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Generates and plays distinct, zero-asset audio cues via [SoundPool] over [AudioAttributes.USAGE_MEDIA].
 *
 * Using [SoundPool] with pre-written PCM WAV files avoids short-buffer underruns and static
 * `AudioTrack` replay issues, while `USAGE_MEDIA` guarantees audibility even when notification
 * ringer volume is silenced.
 */
class AudioCuePlayer(
    applicationContext: Context,
) {

    companion object {
        private const val SAMPLE_RATE_HERTZ = 44_100
        private const val MAXIMUM_SIXTEEN_BIT_AMPLITUDE = 32_767.0
        private const val FADE_DURATION_MILLISECONDS = 12
        private const val WAV_HEADER_SIZE_BYTES = 44

        private const val SPEECH_TO_SIGN_NOTE_ONE_MILLISECONDS = 85
        private const val SPEECH_TO_SIGN_NOTE_TWO_MILLISECONDS = 115
        private const val SPEECH_TO_SIGN_AMPLITUDE = 0.60
        private const val NOTE_E5_HERTZ = 659.25
        private const val NOTE_A5_HERTZ = 880.00

        private const val SIGN_TO_SPEECH_NOTE_DURATION_MILLISECONDS = 105
        private const val SIGN_TO_SPEECH_AMPLITUDE = 0.25
        private const val NOTE_C5_HERTZ = 523.25
        private const val NOTE_G4_HERTZ = 392.00

        const val SPEECH_TO_SIGN_CUE_DURATION_MILLISECONDS = 200L
        const val SIGN_TO_SPEECH_CUE_DURATION_MILLISECONDS = 105L
    }

    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val loadedSoundIdentifiers = mutableSetOf<Int>()

    private val speechToSignStartSoundIdentifier: Int
    private val speechToSignEndSoundIdentifier: Int
    private val signToSpeechStartSoundIdentifier: Int
    private val signToSpeechEndSoundIdentifier: Int

    init {
        soundPool.setOnLoadCompleteListener { _, sampleIdentifier, status ->
            if (status == 0) {
                synchronized(loadedSoundIdentifiers) {
                    loadedSoundIdentifiers.add(sampleIdentifier)
                }
            }
        }

        val cueDirectory = File(applicationContext.cacheDir, "audio_cues").apply { mkdirs() }

        speechToSignStartSoundIdentifier = loadSynthesizedCue(
            outputFile = File(cueDirectory, "speech_to_sign_start.wav"),
            noteSpecs = listOf(
                NOTE_E5_HERTZ to SPEECH_TO_SIGN_NOTE_ONE_MILLISECONDS,
                NOTE_A5_HERTZ to SPEECH_TO_SIGN_NOTE_TWO_MILLISECONDS,
            ),
            normalizedAmplitude = SPEECH_TO_SIGN_AMPLITUDE,
        )
        speechToSignEndSoundIdentifier = loadSynthesizedCue(
            outputFile = File(cueDirectory, "speech_to_sign_end.wav"),
            noteSpecs = listOf(
                NOTE_A5_HERTZ to SPEECH_TO_SIGN_NOTE_ONE_MILLISECONDS,
                NOTE_E5_HERTZ to SPEECH_TO_SIGN_NOTE_TWO_MILLISECONDS,
            ),
            normalizedAmplitude = SPEECH_TO_SIGN_AMPLITUDE,
        )
        signToSpeechStartSoundIdentifier = loadSynthesizedCue(
            outputFile = File(cueDirectory, "sign_to_speech_start.wav"),
            noteSpecs = listOf(NOTE_C5_HERTZ to SIGN_TO_SPEECH_NOTE_DURATION_MILLISECONDS),
            normalizedAmplitude = SIGN_TO_SPEECH_AMPLITUDE,
        )
        signToSpeechEndSoundIdentifier = loadSynthesizedCue(
            outputFile = File(cueDirectory, "sign_to_speech_end.wav"),
            noteSpecs = listOf(NOTE_G4_HERTZ to SIGN_TO_SPEECH_NOTE_DURATION_MILLISECONDS),
            normalizedAmplitude = SIGN_TO_SPEECH_AMPLITUDE,
        )
    }

    fun playSpeechToSignStartCue() {
        playSoundIfLoaded(speechToSignStartSoundIdentifier)
    }

    fun playSpeechToSignEndCue() {
        playSoundIfLoaded(speechToSignEndSoundIdentifier)
    }

    fun playSignToSpeechStartCue() {
        playSoundIfLoaded(signToSpeechStartSoundIdentifier)
    }

    fun playSignToSpeechEndCue() {
        playSoundIfLoaded(signToSpeechEndSoundIdentifier)
    }

    fun release() {
        soundPool.release()
    }

    private fun playSoundIfLoaded(soundIdentifier: Int) {
        val isReady = synchronized(loadedSoundIdentifiers) {
            loadedSoundIdentifiers.contains(soundIdentifier)
        }
        if (isReady) {
            soundPool.play(soundIdentifier, 1.0f, 1.0f, 1, 0, 1.0f)
        }
    }

    private fun loadSynthesizedCue(
        outputFile: File,
        noteSpecs: List<Pair<Double, Int>>,
        normalizedAmplitude: Double,
    ): Int {
        val samples = synthesizeSineSequence(
            noteSpecs = noteSpecs,
            normalizedAmplitude = normalizedAmplitude,
        )
        writeMonoPcmWavFile(outputFile = outputFile, pcmSamples = samples)
        return soundPool.load(outputFile.absolutePath, 1)
    }

    private fun synthesizeSineSequence(
        noteSpecs: List<Pair<Double, Int>>,
        normalizedAmplitude: Double,
    ): ShortArray {
        val totalSamples = noteSpecs.sumOf { (_, durationMilliseconds) ->
            (SAMPLE_RATE_HERTZ * durationMilliseconds) / 1000
        }
        val combinedSamples = ShortArray(totalSamples)
        var writeOffset = 0

        for ((frequencyHertz, durationMilliseconds) in noteSpecs) {
            val samplesInNote = (SAMPLE_RATE_HERTZ * durationMilliseconds) / 1000
            val fadeSamples = ((SAMPLE_RATE_HERTZ * FADE_DURATION_MILLISECONDS) / 1000)
                .coerceAtMost(samplesInNote / 2)

            for (sampleIndex in 0 until samplesInNote) {
                val timeSeconds = sampleIndex.toDouble() / SAMPLE_RATE_HERTZ
                val envelopeMultiplier = computeRaisedCosineEnvelope(
                    sampleIndex = sampleIndex,
                    totalSamples = samplesInNote,
                    fadeSamples = fadeSamples,
                )
                val rawSample = sin(2.0 * PI * frequencyHertz * timeSeconds)
                val scaledSample = rawSample * normalizedAmplitude * envelopeMultiplier
                combinedSamples[writeOffset + sampleIndex] =
                    (scaledSample * MAXIMUM_SIXTEEN_BIT_AMPLITUDE)
                        .toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()
            }
            writeOffset += samplesInNote
        }
        return combinedSamples
    }

    private fun computeRaisedCosineEnvelope(
        sampleIndex: Int,
        totalSamples: Int,
        fadeSamples: Int,
    ): Double {
        if (fadeSamples <= 0) {
            return 1.0
        }
        return when {
            sampleIndex < fadeSamples -> {
                0.5 * (1.0 - cos(PI * sampleIndex / fadeSamples))
            }
            sampleIndex >= totalSamples - fadeSamples -> {
                val trailingIndex = totalSamples - 1 - sampleIndex
                0.5 * (1.0 - cos(PI * trailingIndex / fadeSamples))
            }
            else -> 1.0
        }
    }

    private fun writeMonoPcmWavFile(
        outputFile: File,
        pcmSamples: ShortArray,
    ) {
        val dataByteCount = pcmSamples.size * Short.SIZE_BYTES
        val byteBuffer = ByteBuffer
            .allocate(WAV_HEADER_SIZE_BYTES + dataByteCount)
            .order(ByteOrder.LITTLE_ENDIAN)

        // Standard 44-byte RIFF/WAVE PCM header (16-bit mono at 44.1 kHz).
        byteBuffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        byteBuffer.putInt(36 + dataByteCount)
        byteBuffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        byteBuffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        byteBuffer.putInt(16)
        byteBuffer.putShort(1.toShort())
        byteBuffer.putShort(1.toShort())
        byteBuffer.putInt(SAMPLE_RATE_HERTZ)
        byteBuffer.putInt(SAMPLE_RATE_HERTZ * Short.SIZE_BYTES)
        byteBuffer.putShort(Short.SIZE_BYTES.toShort())
        byteBuffer.putShort(16.toShort())
        byteBuffer.put("data".toByteArray(Charsets.US_ASCII))
        byteBuffer.putInt(dataByteCount)

        for (sample in pcmSamples) {
            byteBuffer.putShort(sample)
        }

        FileOutputStream(outputFile).use { stream ->
            stream.write(byteBuffer.array())
        }
    }
}
