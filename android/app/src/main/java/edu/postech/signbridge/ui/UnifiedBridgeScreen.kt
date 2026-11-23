package edu.postech.signbridge.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.postech.signbridge.model.MicrophoneStatus
import edu.postech.signbridge.turn.SignBridgeViewModel
import edu.postech.signbridge.ui.components.BottomModeNavigationBar
import edu.postech.signbridge.ui.components.DockedPracticeSheet
import edu.postech.signbridge.ui.components.FrontCameraAlignmentCard
import edu.postech.signbridge.ui.components.SignVideoStageCard
import edu.postech.signbridge.ui.components.SpokenOutputAndTextInputSection
import edu.postech.signbridge.ui.theme.NeutralSurfaceBackground

/**
 * Headerless unified screen hosting Conversation mode (live camera, sign video stage,
 * Sign-To-Speech card, and text/microphone input) and Practice mode above a bottom menu bar.
 */
@Composable
fun UnifiedBridgeScreen(
    viewModel: SignBridgeViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val screenState by viewModel.screenState.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grantResults ->
        val cameraGranted = grantResults[Manifest.permission.CAMERA]
            ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED)
        val microphoneGranted = grantResults[Manifest.permission.RECORD_AUDIO]
            ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED)

        viewModel.updatePermissions(
            hasCameraPermission = cameraGranted,
            hasMicrophonePermission = microphoneGranted,
        )
    }

    LaunchedEffect(Unit) {
        val hasCamera = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        val hasMicrophone = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

        viewModel.updatePermissions(
            hasCameraPermission = hasCamera,
            hasMicrophonePermission = hasMicrophone,
        )

        val missingPermissions = buildList {
            if (!hasCamera) add(Manifest.permission.CAMERA)
            if (!hasMicrophone) add(Manifest.permission.RECORD_AUDIO)
        }
        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    // Wrap native surface hosts with movableContentOf so CameraX PreviewView and Media3 PlayerView
    // keep their Android View instances intact when switching between Conversation and Practice.
    val cameraAlignmentSlot = remember(viewModel) {
        movableContentOf { isCompact: Boolean, slotModifier: Modifier ->
            FrontCameraAlignmentCard(
                hasCameraPermission = screenState.hasCameraPermission,
                turnPhase = screenState.turnPhase,
                detectionResult = screenState.liveDetectionResult,
                signHoldProgress = screenState.signHoldProgress,
                signRecognizer = viewModel.signRecognizer,
                isFakeSignRecognizerActive = screenState.isFakeSignRecognizerActive,
                isCompactLayout = isCompact,
                onRequestCameraPermission = {
                    permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                },
                onAppendSimulatedSign = viewModel::appendSimulatedSignWord,
                modifier = slotModifier,
            )
        }
    }

    val signVideoStageSlot = remember(viewModel) {
        movableContentOf { isCompact: Boolean, slotModifier: Modifier ->
            SignVideoStageCard(
                exoPlayer = viewModel.videoSequencePlayer.exoPlayer,
                turnPhase = screenState.turnPhase,
                speechTokens = screenState.speechTokens,
                activeSpeechTokenIndex = screenState.activeSpeechTokenIndex,
                activeSignWordLabel = screenState.activeSignWordLabel,
                activeClipProgress = screenState.activeClipProgress,
                missingVideoFileName = screenState.missingVideoFileName,
                playbackSpeedMultiplier = screenState.playbackSpeedMultiplier,
                isCompactLayout = isCompact,
                onReplaySequence = viewModel::replayLastSpeechToSignSequence,
                onToggleSlowSpeed = viewModel::toggleSlowPlaybackSpeed,
                onSkipNextWord = viewModel::skipToNextSignClip,
                modifier = slotModifier,
            )
        }
    }

    val cameraStageWeight by animateFloatAsState(
        targetValue = if (screenState.turnPhase.isSpeechToSignActive) 0.28f else 0.54f,
        animationSpec = tween(durationMillis = 280),
        label = "cameraStageWeight",
    )
    val videoStageWeight by animateFloatAsState(
        targetValue = if (screenState.turnPhase.isSpeechToSignActive) 0.72f else 0.46f,
        animationSpec = tween(durationMillis = 280),
        label = "videoStageWeight",
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NeutralSurfaceBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (screenState.isPracticeModeActive) {
            Row(
                modifier = Modifier
                    .weight(0.44f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                signVideoStageSlot(
                    true,
                    Modifier
                        .weight(0.58f)
                        .fillMaxHeight(),
                )
                cameraAlignmentSlot(
                    true,
                    Modifier
                        .weight(0.42f)
                        .fillMaxHeight(),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                cameraAlignmentSlot(
                    screenState.turnPhase.isSpeechToSignActive,
                    Modifier
                        .weight(cameraStageWeight)
                        .fillMaxWidth(),
                )
                signVideoStageSlot(
                    false,
                    Modifier
                        .weight(videoStageWeight)
                        .fillMaxWidth(),
                )
            }

            SpokenOutputAndTextInputSection(
                turnPhase = screenState.turnPhase,
                microphoneStatus = screenState.microphoneStatus,
                lastSpokenWord = screenState.lastSpokenWord,
                partialSpeechTranscript = screenState.partialSpeechTranscript,
                transientStatusMessage = screenState.transientStatusMessage,
                onReplaySpokenWord = viewModel::replayLastSpokenWord,
                onToggleMicrophone = {
                    if (screenState.microphoneStatus == MicrophoneStatus.PERMISSION_REQUIRED) {
                        permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                    } else {
                        viewModel.toggleMicrophoneEnabled()
                    }
                },
                onSubmitTypedPhrase = viewModel::submitTypedPhrase,
                onDismissTransientMessage = viewModel::dismissTransientStatusMessage,
            )
        }

        AnimatedVisibility(
            visible = screenState.isPracticeModeActive,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
            modifier = if (screenState.isPracticeModeActive) {
                Modifier.weight(0.56f)
            } else {
                Modifier
            },
        ) {
            DockedPracticeSheet(
                turnPhase = screenState.turnPhase,
                activeSignWordLabel = screenState.activeSignWordLabel,
                onSelectWord = viewModel::practiceVocabularyWord,
                modifier = Modifier.fillMaxSize(),
            )
        }

        BottomModeNavigationBar(
            isPracticeModeActive = screenState.isPracticeModeActive,
            onSelectConversationMode = { viewModel.setPracticeModeActive(false) },
            onSelectPracticeMode = { viewModel.setPracticeModeActive(true) },
        )
    }
}
