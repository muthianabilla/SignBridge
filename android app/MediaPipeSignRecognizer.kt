package edu.postech.signbridge.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import edu.postech.signbridge.ml.SignPreprocessor
import edu.postech.signbridge.ml.SignPreprocessor.MISSING
import edu.postech.signbridge.ml.SignPreprocessor.Point
import edu.postech.signbridge.model.HandLandmarkPoint
import edu.postech.signbridge.model.SignDetectionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.channels.FileChannel

/**
 * Real [SignRecognizer]: MediaPipe landmarks -> SignPreprocessor -> signbridge_v2.tflite.
 *
 * Drop-in replacement for [FakeSignRecognizer]. Needs in src/main/assets/:
 *   hand_landmarker.task, pose_landmarker_lite.task, face_landmarker.task,
 *   signbridge_v2.tflite, preprocess_config_v2.json
 *
 * Runs synchronously on the CameraX analysis thread (STRATEGY_KEEP_ONLY_LATEST already drops
 * frames we cannot keep up with). Hands are tracked every frame; shoulders and face move slowly,
 * so pose and face run every [BODY_EVERY] frames and the cached result is reused in between.
 *
 * The ViewModel only confirms a word after it has been reported continuously for ~450 ms, so this
 * class keeps re-emitting its latest prediction between model runs instead of dropping to null.
 */
class MediaPipeSignRecognizer(context: Context) : SignRecognizer {

    companion object {
        private const val TAG = "SignBridgeRecognizer"
        private const val PERF_TAG = "SignBridgePerf"
        private const val MODEL = "signbridge_v2.tflite"
        private const val CONFIG = "preprocess_config_v2.json"

        /** Recent frames kept; the preprocessor resamples them to the model's 32. */
        private const val WINDOW = 48
        /** Run the classifier every Nth camera frame. */
        private const val STRIDE = 3
        /** Run pose and face every Nth camera frame. */
        private const val BODY_EVERY = 5
        /** After this many frames with no hand, forget the window and the held word. */
        private const val HAND_LOST_RESET = 15

        /**
         * MediaPipe labels handedness assuming a mirrored selfie image; CameraX hands us the
         * unmirrored sensor image. The model was trained with 50% mirror augmentation so either
         * assignment should work, but if on-device accuracy is far below the notebook's,
         * flipping this is the first thing to try.
         */
        private const val SWAP_HANDS = false
    }

    private val mutableDetectionResults = MutableStateFlow(SignDetectionResult())
    override val detectionResults: StateFlow<SignDetectionResult> = mutableDetectionResults.asStateFlow()

    @Volatile private var recognitionEnabled = true

    private val handLandmarker: HandLandmarker
    private val poseLandmarker: PoseLandmarker
    private val faceLandmarker: FaceLandmarker
    private val interpreter: Interpreter
    private val words: List<String>

    private val window = ArrayDeque<SignPreprocessor.Frame>()
    private var frameIndex = 0L
    private var lastTimestamp = -1L
    private var framesWithoutHand = 0

    private var leftShoulder = MISSING
    private var rightShoulder = MISSING
    private var forehead = MISSING
    private var nose = MISSING
    private var chin = MISSING

    private var heldWord: String? = null
    private var heldConfidence = 0f

    // Timing, logged every 60 frames under SignBridgePerf. These are the numbers for the report.
    private var perfFrames = 0
    private var perfMediaPipeNanos = 0L
    private var perfModelNanos = 0L
    private var perfModelRuns = 0
    private var perfWindowStart = SystemClock.elapsedRealtime()

    init {
        val app = context.applicationContext

        handLandmarker = HandLandmarker.createFromOptions(
            app,
            HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
                .setRunningMode(RunningMode.VIDEO)
                .setNumHands(2)
                .build(),
        )
        poseLandmarker = PoseLandmarker.createFromOptions(
            app,
            PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").build())
                .setRunningMode(RunningMode.VIDEO)
                .setNumPoses(1)
                .build(),
        )
        faceLandmarker = FaceLandmarker.createFromOptions(
            app,
            FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build())
                .setRunningMode(RunningMode.VIDEO)
                .setNumFaces(1)
                .build(),
        )

        val fd = app.assets.openFd(MODEL)
        val buffer = FileInputStream(fd.fileDescriptor).use { stream ->
            stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
        interpreter = Interpreter(buffer, Interpreter.Options().apply { numThreads = 2 })

        val cfg = app.assets.open(CONFIG).bufferedReader().use { JSONObject(it.readText()) }
        val arr = cfg.getJSONArray("words")
        words = (0 until arr.length()).map { arr.getString(it) }

        // The .tflite is the authority on input shape. If this trips, the model and the
        // preprocessor disagree and every prediction would be garbage.
        val shape = interpreter.getInputTensor(0).shape()
        require(shape[1] == SignPreprocessor.T && shape[2] == SignPreprocessor.FEAT) {
            "model expects ${shape[1]}x${shape[2]}, preprocessor makes " +
                "${SignPreprocessor.T}x${SignPreprocessor.FEAT}"
        }
        Log.i(TAG, "ready: ${words.size} words, input ${shape.joinToString("x")}")
    }

    override fun analyzeCameraFrame(imageProxy: ImageProxy) {
        try {
            process(imageProxy)
        } catch (e: Exception) {
            Log.e(TAG, "frame failed", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun process(imageProxy: ImageProxy) {
        val t0 = System.nanoTime()

        val upright = uprightBitmap(imageProxy)
        val mpImage = BitmapImageBuilder(upright).build()

        // VIDEO mode requires strictly increasing timestamps per landmarker.
        var ts = SystemClock.uptimeMillis()
        if (ts <= lastTimestamp) ts = lastTimestamp + 1
        lastTimestamp = ts

        val hands = handLandmarker.detectForVideo(mpImage, ts)

        if (frameIndex % BODY_EVERY == 0L) {
            val pose = poseLandmarker.detectForVideo(mpImage, ts).landmarks().firstOrNull()
            leftShoulder = pose?.getOrNull(SignPreprocessor.POSE_LEFT_SHOULDER).toPoint()
            rightShoulder = pose?.getOrNull(SignPreprocessor.POSE_RIGHT_SHOULDER).toPoint()

            val face = faceLandmarker.detectForVideo(mpImage, ts).faceLandmarks().firstOrNull()
            forehead = face?.getOrNull(SignPreprocessor.FACE_FOREHEAD).toPoint()
            nose = face?.getOrNull(SignPreprocessor.FACE_NOSE).toPoint()
            chin = face?.getOrNull(SignPreprocessor.FACE_CHIN).toPoint()
        }
        frameIndex++
        perfMediaPipeNanos += System.nanoTime() - t0

        // Split detected hands into left / right by MediaPipe's handedness label.
        var left: List<Point> = List(SignPreprocessor.N_HAND) { MISSING }
        var right: List<Point> = List(SignPreprocessor.N_HAND) { MISSING }
        val uiHands = mutableListOf<List<HandLandmarkPoint>>()
        val landmarks = hands.landmarks()
        val handedness = hands.handedness()
        for (i in landmarks.indices) {
            val pts = landmarks[i].map { Point(it.x(), it.y()) }
            if (pts.size != SignPreprocessor.N_HAND) continue
            uiHands += landmarks[i].map { HandLandmarkPoint(it.x(), it.y()) }
            var isLeft = handedness.getOrNull(i)?.firstOrNull()?.categoryName() == "Left"
            if (SWAP_HANDS) isLeft = !isLeft
            if (isLeft) left = pts else right = pts
        }

        if (!recognitionEnabled) {
            resetWindow()
            emit(uiHands, ts)
            return
        }

        if (uiHands.isEmpty()) {
            if (++framesWithoutHand >= HAND_LOST_RESET) resetWindow()
        } else {
            framesWithoutHand = 0
        }

        window.addLast(
            SignPreprocessor.Frame(left, right, leftShoulder, rightShoulder, forehead, nose, chin),
        )
        while (window.size > WINDOW) window.removeFirst()

        if (frameIndex % STRIDE == 0L &&
            window.size >= SignPreprocessor.T &&
            framesWithoutHand == 0
        ) {
            val m0 = System.nanoTime()
            classify()
            perfModelNanos += System.nanoTime() - m0
            perfModelRuns++
        }

        emit(uiHands, ts)
        logPerf()
    }

    private fun classify() {
        val flat = SignPreprocessor.buildInput(window.toList())
        val input = Array(1) {
            Array(SignPreprocessor.T) { t ->
                FloatArray(SignPreprocessor.FEAT) { f -> flat[t * SignPreprocessor.FEAT + f] }
            }
        }
        val out = Array(1) { FloatArray(words.size) }
        interpreter.run(input, out)

        var best = 0
        for (i in out[0].indices) if (out[0][i] > out[0][best]) best = i
        // Report the raw top-1 and its probability; the ViewModel applies its own threshold.
        heldWord = words[best]
        heldConfidence = out[0][best]
    }

    private fun emit(uiHands: List<List<HandLandmarkPoint>>, ts: Long) {
        mutableDetectionResults.value = SignDetectionResult(
            recognizedWord = if (recognitionEnabled) heldWord else null,
            confidenceScore = if (recognitionEnabled) heldConfidence else 0f,
            detectedHands = uiHands,
            timestampMilliseconds = ts,
        )
    }

    private fun resetWindow() {
        window.clear()
        heldWord = null
        heldConfidence = 0f
    }

    private fun logPerf() {
        if (++perfFrames < 60) return
        val secs = (SystemClock.elapsedRealtime() - perfWindowStart) / 1000.0
        Log.i(
            PERF_TAG,
            "fps %.1f | mediapipe %.1f ms/frame | model %.1f ms/run (%d runs) | top=%s %.2f".format(
                perfFrames / secs,
                perfMediaPipeNanos / 1e6 / perfFrames,
                if (perfModelRuns > 0) perfModelNanos / 1e6 / perfModelRuns else 0.0,
                perfModelRuns,
                heldWord ?: "-",
                heldConfidence,
            ),
        )
        perfFrames = 0; perfMediaPipeNanos = 0; perfModelNanos = 0; perfModelRuns = 0
        perfWindowStart = SystemClock.elapsedRealtime()
    }

    private fun uprightBitmap(imageProxy: ImageProxy): Bitmap {
        val raw = imageProxy.toBitmap()
        val rotation = imageProxy.imageInfo.rotationDegrees
        if (rotation == 0) return raw
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
    }

    private fun NormalizedLandmark?.toPoint(): Point =
        if (this == null) MISSING else Point(x(), y())

    override fun setRecognitionEnabled(isEnabled: Boolean) {
        recognitionEnabled = isEnabled
        if (!isEnabled) {
            mutableDetectionResults.value =
                SignDetectionResult(timestampMilliseconds = System.currentTimeMillis())
        }
    }

    override fun release() {
        runCatching { handLandmarker.close() }
        runCatching { poseLandmarker.close() }
        runCatching { faceLandmarker.close() }
        runCatching { interpreter.close() }
        mutableDetectionResults.value = SignDetectionResult()
    }
}
