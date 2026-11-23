package edu.postech.signbridge.ui.components

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SlowMotionVideo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import edu.postech.signbridge.R
import edu.postech.signbridge.model.SpokenWordToken
import edu.postech.signbridge.model.TurnPhase
import edu.postech.signbridge.ui.theme.CardSurfaceWhite
import edu.postech.signbridge.ui.theme.DeepNavyLightContainer
import edu.postech.signbridge.ui.theme.DeepNavyPrimary
import edu.postech.signbridge.ui.theme.MatchedWordGreen
import edu.postech.signbridge.ui.theme.NeutralOutlineSlate
import edu.postech.signbridge.ui.theme.SlateSecondary
import edu.postech.signbridge.ui.theme.SpeechToSignAccent
import edu.postech.signbridge.ui.theme.SpeechToSignContainer
import edu.postech.signbridge.ui.theme.UnmatchedWordAmber
import edu.postech.signbridge.ui.theme.UnmatchedWordContainer

/**
 * Plays word-by-word ASL clips for Speech-To-Sign with distinct Start, Running, and End visual cues.
 *
 * The Running visual cue is intentionally restricted to a thin peripheral bottom rail and token
 * chip highlight so nothing flashes inside the video viewport or steals focus from the signer.
 */
@OptIn(UnstableApi::class)
@Composable
fun SignVideoStageCard(
    exoPlayer: ExoPlayer,
    turnPhase: TurnPhase,
    speechTokens: List<SpokenWordToken>,
    activeSpeechTokenIndex: Int?,
    activeSignWordLabel: String?,
    activeClipProgress: Float,
    missingVideoFileName: String?,
    playbackSpeedMultiplier: Float,
    isCompactLayout: Boolean,
    onReplaySequence: (Int) -> Unit,
    onToggleSlowSpeed: () -> Unit,
    onSkipNextWord: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isPrototypeScopeDialogVisible by remember { mutableStateOf(false) }

    val stageBorderColor by animateColorAsState(
        targetValue = when (turnPhase) {
            TurnPhase.SPEECH_TO_SIGN_STARTING -> SpeechToSignAccent
            TurnPhase.SPEECH_TO_SIGN_RUNNING -> SpeechToSignAccent.copy(alpha = 0.75f)
            TurnPhase.SPEECH_TO_SIGN_ENDING -> MatchedWordGreen
            else -> NeutralOutlineSlate
        },
        animationSpec = tween(durationMillis = 220),
        label = "speechToSignBorderColor",
    )
    val startBracketScale by animateFloatAsState(
        targetValue = if (turnPhase == TurnPhase.SPEECH_TO_SIGN_STARTING) 1.0f else 1.06f,
        animationSpec = tween(durationMillis = 160),
        label = "speechToSignBracketScale",
    )

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceWhite),
        border = BorderStroke(
            width = if (turnPhase.isSpeechToSignActive) 2.dp else 1.dp,
            color = stageBorderColor,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (speechTokens.isNotEmpty() && !isCompactLayout) {
                WordSequenceTokenStrip(
                    tokens = speechTokens,
                    activeTokenIndex = activeSpeechTokenIndex,
                    turnPhase = turnPhase,
                    isInteractionEnabled = turnPhase == TurnPhase.IDLE_LISTENING,
                    onTokenClick = onReplaySequence,
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF0B1120)),
            ) {
                SignPlayerSurface(
                    exoPlayer = exoPlayer,
                    modifier = Modifier.fillMaxSize(),
                )

                if (activeSignWordLabel == null || missingVideoFileName != null) {
                    SignStageFallbackOverlay(
                        activeSignWordLabel = activeSignWordLabel,
                        missingVideoFileName = missingVideoFileName,
                        turnPhase = turnPhase,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                if (turnPhase == TurnPhase.SPEECH_TO_SIGN_STARTING) {
                    StartSnapBracketOverlay(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = startBracketScale,
                                scaleY = startBracketScale,
                            ),
                    )
                }

                if (activeSignWordLabel != null) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = Color.Black.copy(alpha = 0.72f),
                    ) {
                        Text(
                            text = activeSignWordLabel,
                            style = if (isCompactLayout) {
                                MaterialTheme.typography.titleMedium
                            } else {
                                MaterialTheme.typography.headlineSmall
                            },
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                }

                IconButton(
                    onClick = { isPrototypeScopeDialogVisible = true },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = "About SignBridge prototype",
                        tint = Color.White.copy(alpha = 0.78f),
                        modifier = Modifier.size(18.dp),
                    )
                }

                if (turnPhase == TurnPhase.SPEECH_TO_SIGN_RUNNING) {
                    LinearProgressIndicator(
                        progress = { activeClipProgress },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(3.dp),
                        color = SpeechToSignAccent,
                        trackColor = Color.White.copy(alpha = 0.2f),
                    )
                }
            }

            PlaybackControlsRow(
                hasPreviousSequence = speechTokens.any { token ->
                    token is SpokenWordToken.MatchedSign
                },
                isSpeechToSignRunning = turnPhase == TurnPhase.SPEECH_TO_SIGN_RUNNING,
                isIdle = turnPhase == TurnPhase.IDLE_LISTENING,
                playbackSpeedMultiplier = playbackSpeedMultiplier,
                onReplay = { onReplaySequence(0) },
                onToggleSlowSpeed = onToggleSlowSpeed,
                onSkipNext = onSkipNextWord,
            )
        }
    }

    if (isPrototypeScopeDialogVisible) {
        AlertDialog(
            onDismissRequest = { isPrototypeScopeDialogVisible = false },
            title = { Text(text = "About SignBridge Prototype") },
            text = {
                Text(
                    text = "Spoken US English sentences are mapped word-for-word to 20 " +
                            "American Sign Language (ASL) clips. ASL grammatical ordering " +
                            "(Time-Topic-Comment) and facial expressions are outside the " +
                            "scope of this proof of concept.\n\n" +
                            "ASL Video Clips Attribution\n" +
                            "Sign language video clips are derived from the PopSign ASL " +
                            "v1.0 test dataset, created by the Georgia Institute of " +
                            "Technology and the Deaf Professional Arts Network (DPAN).\n" +
                            "Source: " +
                            "https://signdata.cc.gatech.edu/view/datasets/popsign_v1_0/index.html\n" +
                            "Modifications: Subset of 20 test-set labels (hello, thankyou, " +
                            "please, bye, yes, no, mom, dad, hungry, drink, potty, owie, " +
                            "sick, hot, sleep, wait, go, finish, mad, sad).\n" +
                            "License: Licensed under CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/).",
                )
            },
            confirmButton = {
                TextButton(onClick = { isPrototypeScopeDialogVisible = false }) {
                    Text(text = "Got it")
                }
            },
        )
    }
}

@SuppressLint("InflateParams")
@OptIn(UnstableApi::class)
@Composable
private fun SignPlayerSurface(
    exoPlayer: ExoPlayer,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val playerView = remember(context) {
        (LayoutInflater.from(context).inflate(R.layout.sign_player_view, null, false) as PlayerView)
            .apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
    }

    DisposableEffect(playerView, exoPlayer) {
        playerView.player = exoPlayer
        onDispose {
            playerView.player = null
        }
    }

    AndroidView(
        factory = { playerView },
        update = { view ->
            if (view.player !== exoPlayer) {
                view.player = exoPlayer
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun SignStageFallbackOverlay(
    activeSignWordLabel: String?,
    missingVideoFileName: String?,
    turnPhase: TurnPhase,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = when (turnPhase) {
        TurnPhase.SPEECH_TO_SIGN_STARTING,
        TurnPhase.SPEECH_TO_SIGN_RUNNING,
            -> Color(0xFF111C38)
        TurnPhase.SPEECH_TO_SIGN_ENDING -> Color(0xFF0F2922)
        else -> Color(0xFF0F172A)
    }

    Column(
        modifier = modifier
            .background(backgroundColor)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (activeSignWordLabel != null) {
            Text(
                text = activeSignWordLabel,
                style = MaterialTheme.typography.displaySmall,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            if (missingVideoFileName != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Add assets/signs/$missingVideoFileName to play clip",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.72f),
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Text(
                text = "Speak or type below to show signs",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.72f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun StartSnapBracketOverlay(
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val margin = 12.dp.toPx()
        val length = size.minDimension * 0.14f
        val strokeWidth = 4.dp.toPx()
        val left = margin
        val top = margin
        val right = size.width - margin
        val bottom = size.height - margin

        drawLine(SpeechToSignAccent, Offset(left, top), Offset(left + length, top), strokeWidth)
        drawLine(SpeechToSignAccent, Offset(left, top), Offset(left, top + length), strokeWidth)
        drawLine(SpeechToSignAccent, Offset(right, top), Offset(right - length, top), strokeWidth)
        drawLine(SpeechToSignAccent, Offset(right, top), Offset(right, top + length), strokeWidth)
        drawLine(SpeechToSignAccent, Offset(left, bottom), Offset(left + length, bottom), strokeWidth)
        drawLine(SpeechToSignAccent, Offset(left, bottom), Offset(left, bottom - length), strokeWidth)
        drawLine(SpeechToSignAccent, Offset(right, bottom), Offset(right - length, bottom), strokeWidth)
        drawLine(SpeechToSignAccent, Offset(right, bottom), Offset(right, bottom - length), strokeWidth)
    }
}

@Composable
private fun WordSequenceTokenStrip(
    tokens: List<SpokenWordToken>,
    activeTokenIndex: Int?,
    turnPhase: TurnPhase,
    isInteractionEnabled: Boolean,
    onTokenClick: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(activeTokenIndex) {
        if (activeTokenIndex != null && activeTokenIndex in tokens.indices) {
            listState.animateScrollToItem(activeTokenIndex)
        }
    }

    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        itemsIndexed(tokens) { tokenIndex, token ->
            val isCurrentlyPlaying = tokenIndex == activeTokenIndex
            val isEndSettle = turnPhase == TurnPhase.SPEECH_TO_SIGN_ENDING

            when (token) {
                is SpokenWordToken.MatchedSign -> {
                    val containerColor by animateColorAsState(
                        targetValue = when {
                            isCurrentlyPlaying -> SpeechToSignAccent
                            isEndSettle -> MatchedWordGreen.copy(alpha = 0.18f)
                            else -> SpeechToSignContainer
                        },
                        animationSpec = tween(durationMillis = 180),
                        label = "matchedChipBackground",
                    )
                    val textColor = if (isCurrentlyPlaying) Color.White else DeepNavyPrimary

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = containerColor,
                        modifier = Modifier.clickable(enabled = isInteractionEnabled) {
                            onTokenClick(tokenIndex)
                        },
                    ) {
                        Text(
                            text = token.vocabularyWord.displayLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = textColor,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
                is SpokenWordToken.UnsupportedWord -> {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = UnmatchedWordContainer.copy(alpha = 0.65f),
                        modifier = Modifier.border(
                            width = 1.dp,
                            color = UnmatchedWordAmber.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(12.dp),
                        ),
                    ) {
                        Text(
                            text = token.spokenText,
                            style = MaterialTheme.typography.labelMedium.copy(
                                textDecoration = TextDecoration.LineThrough,
                            ),
                            color = UnmatchedWordAmber,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaybackControlsRow(
    hasPreviousSequence: Boolean,
    isSpeechToSignRunning: Boolean,
    isIdle: Boolean,
    playbackSpeedMultiplier: Float,
    onReplay: () -> Unit,
    onToggleSlowSpeed: () -> Unit,
    onSkipNext: () -> Unit,
) {
    val isSlowSpeedActive = playbackSpeedMultiplier < 1.0f
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onReplay,
            enabled = isIdle && hasPreviousSequence,
            colors = ButtonDefaults.textButtonColors(contentColor = DeepNavyPrimary),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Replay,
                contentDescription = "Replay sign sequence",
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = "Replay", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        TextButton(
            onClick = onToggleSlowSpeed,
            colors = ButtonDefaults.textButtonColors(
                containerColor = if (isSlowSpeedActive) DeepNavyLightContainer else Color.Transparent,
                contentColor = DeepNavyPrimary,
            ),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.SlowMotionVideo,
                contentDescription = "Toggle slow playback speed",
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (isSlowSpeedActive) "0.75x Slow" else "1.0x",
                maxLines = 1,
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        if (isSpeechToSignRunning) {
            TextButton(
                onClick = onSkipNext,
                colors = ButtonDefaults.textButtonColors(contentColor = SlateSecondary),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(text = "Next", maxLines = 1)
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.Rounded.SkipNext,
                    contentDescription = "Next sign word",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
