package edu.postech.signbridge.ui.components

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import edu.postech.signbridge.data.SignVocabulary
import edu.postech.signbridge.model.SignDetectionResult
import edu.postech.signbridge.model.TurnPhase
import edu.postech.signbridge.model.VocabularyWord
import edu.postech.signbridge.ui.theme.DeepNavyLightContainer
import edu.postech.signbridge.ui.theme.DeepNavyPrimary
import edu.postech.signbridge.ui.theme.NeutralOutlineSlate
import edu.postech.signbridge.ui.theme.SignToSpeechAccent
import edu.postech.signbridge.ui.theme.SlateSecondary
import edu.postech.signbridge.vision.SignRecognizer
import java.util.concurrent.Executors

/**
 * Displays the mirrored front-facing camera feed without wireframe overlays, plus a single
 * scrollable row of test-sign buttons covering all 20 labels when [FakeSignRecognizer] is active.
 */
@Composable
fun FrontCameraAlignmentCard(
    hasCameraPermission: Boolean,
    turnPhase: TurnPhase,
    detectionResult: SignDetectionResult,
    signHoldProgress: Float,
    signRecognizer: SignRecognizer,
    isFakeSignRecognizerActive: Boolean,
    isCompactLayout: Boolean,
    onRequestCameraPermission: () -> Unit,
    onAppendSimulatedSign: (VocabularyWord) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isSigningOutputActive = turnPhase.isSpeechToSignActive
    val borderColor = when {
        turnPhase.isSignToSpeechActive -> SignToSpeechAccent
        detectionResult.hasDetectedHands && !isSigningOutputActive ->
            SignToSpeechAccent.copy(alpha = 0.65f)
        else -> NeutralOutlineSlate
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
        border = BorderStroke(
            width = if (turnPhase.isSignToSpeechActive) 2.5.dp else 1.dp,
            color = borderColor,
        ),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (hasCameraPermission) {
                    FrontCameraPreviewSurface(
                        signRecognizer = signRecognizer,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    CameraPermissionPrompt(
                        onRequestCameraPermission = onRequestCameraPermission,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                CameraStatusBadge(
                    turnPhase = turnPhase,
                    detectionResult = detectionResult,
                    signHoldProgress = signHoldProgress,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                )

                if (signHoldProgress > 0f && !isSigningOutputActive) {
                    LinearProgressIndicator(
                        progress = { signHoldProgress },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(4.dp),
                        color = SignToSpeechAccent,
                        trackColor = Color.White.copy(alpha = 0.25f),
                    )
                }
            }

            if (isFakeSignRecognizerActive && !isCompactLayout) {
                SimulatedSignTestBar(
                    isInteractionEnabled = turnPhase == TurnPhase.IDLE_LISTENING,
                    onAppendSimulatedSign = onAppendSimulatedSign,
                )
            }
        }
    }
}

@Composable
private fun FrontCameraPreviewSurface(
    signRecognizer: SignRecognizer,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(lifecycleOwner, previewView, signRecognizer) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)

        cameraProviderFuture.addListener(
            {
                val cameraProvider = cameraProviderFuture.get()
                val previewUseCase = Preview.Builder().build().apply {
                    surfaceProvider = previewView.surfaceProvider
                }
                val imageAnalysisUseCase = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .apply {
                        setAnalyzer(analysisExecutor) { imageProxy ->
                            signRecognizer.analyzeCameraFrame(imageProxy)
                        }
                    }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    previewUseCase,
                    imageAnalysisUseCase,
                )
            },
            mainExecutor,
        )

        onDispose {
            runCatching {
                if (cameraProviderFuture.isDone) {
                    cameraProviderFuture.get().unbindAll()
                }
            }
            analysisExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier,
    )
}

@Composable
private fun CameraStatusBadge(
    turnPhase: TurnPhase,
    detectionResult: SignDetectionResult,
    signHoldProgress: Float,
    modifier: Modifier = Modifier,
) {
    val badgeText = when {
        turnPhase.isSpeechToSignActive -> "Paused · Signing output"
        turnPhase.isSignToSpeechActive -> "Speaking sign"
        detectionResult.recognizedWord != null && signHoldProgress > 0f -> {
            "Hold: ${detectionResult.recognizedWord.uppercase()}"
        }
        detectionResult.hasDetectedHands -> "Hands in view"
        else -> "Front camera ready"
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = Color.Black.copy(alpha = 0.6f),
    ) {
        Text(
            text = badgeText,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun CameraPermissionPrompt(
    onRequestCameraPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Videocam,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(32.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Front camera needed for sign alignment",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Button(
            onClick = onRequestCameraPermission,
            colors = ButtonDefaults.buttonColors(containerColor = SignToSpeechAccent),
        ) {
            Text(text = "Allow Camera")
        }
    }
}

@Composable
private fun SimulatedSignTestBar(
    isInteractionEnabled: Boolean,
    onAppendSimulatedSign: (VocabularyWord) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E293B))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Test Sign:",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.78f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(
                items = SignVocabulary.allWords,
                key = { word -> word.identifier },
            ) { vocabularyWord ->
                Button(
                    onClick = { onAppendSimulatedSign(vocabularyWord) },
                    enabled = isInteractionEnabled,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DeepNavyLightContainer,
                        contentColor = DeepNavyPrimary,
                        disabledContainerColor = SlateSecondary.copy(alpha = 0.35f),
                        disabledContentColor = Color.White.copy(alpha = 0.5f),
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(30.dp),
                ) {
                    Text(
                        text = vocabularyWord.displayLabel,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}
