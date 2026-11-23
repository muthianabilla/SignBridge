package edu.postech.signbridge.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import edu.postech.signbridge.model.MicrophoneStatus
import edu.postech.signbridge.model.TurnPhase
import edu.postech.signbridge.ui.theme.CardSurfaceWhite
import edu.postech.signbridge.ui.theme.DeepNavyPrimary
import edu.postech.signbridge.ui.theme.GatedWarningBackground
import edu.postech.signbridge.ui.theme.GatedWarningForeground
import edu.postech.signbridge.ui.theme.MatchedWordContainer
import edu.postech.signbridge.ui.theme.MatchedWordGreen
import edu.postech.signbridge.ui.theme.MutedTextSlate
import edu.postech.signbridge.ui.theme.NeutralOutlineSlate
import edu.postech.signbridge.ui.theme.SignToSpeechAccent
import edu.postech.signbridge.ui.theme.SignToSpeechContainer
import edu.postech.signbridge.ui.theme.SlateSecondary
import edu.postech.signbridge.ui.theme.UnmatchedWordAmber
import edu.postech.signbridge.ui.theme.UnmatchedWordContainer

/**
 * Displays the latest Sign-To-Speech output alongside Teal Start/Running/End visual cues,
 * plus the text input bar whose trailing button switches from Microphone status/toggle to Text Send
 * whenever at least one character is typed.
 */
@Composable
fun SpokenOutputAndTextInputSection(
    turnPhase: TurnPhase,
    microphoneStatus: MicrophoneStatus,
    lastSpokenWord: String?,
    partialSpeechTranscript: String,
    transientStatusMessage: String?,
    onReplaySpokenWord: () -> Unit,
    onToggleMicrophone: () -> Unit,
    onSubmitTypedPhrase: (String) -> Unit,
    onDismissTransientMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var typedPhrase by rememberSaveable { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current

    val cardElevation by animateDpAsState(
        targetValue = if (turnPhase.isSignToSpeechActive) 6.dp else 1.dp,
        animationSpec = tween(durationMillis = 200),
        label = "signToSpeechCardElevation",
    )
    val cardBorderColor by animateColorAsState(
        targetValue = when (turnPhase) {
            TurnPhase.SIGN_TO_SPEECH_STARTING,
            TurnPhase.SIGN_TO_SPEECH_RUNNING,
                -> SignToSpeechAccent
            TurnPhase.SIGN_TO_SPEECH_ENDING -> MatchedWordGreen
            else -> NeutralOutlineSlate
        },
        animationSpec = tween(durationMillis = 200),
        label = "signToSpeechBorderColor",
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (transientStatusMessage != null) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MatchedWordContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = transientStatusMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = DeepNavyPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = onDismissTransientMessage,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(text = "OK")
                    }
                }
            }
        }

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (turnPhase.isSignToSpeechActive) {
                    SignToSpeechContainer
                } else {
                    CardSurfaceWhite
                },
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = cardElevation),
            border = BorderStroke(
                width = if (turnPhase.isSignToSpeechActive) 2.dp else 1.dp,
                color = cardBorderColor,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SignToSpeechCueIndicator(
                    turnPhase = turnPhase,
                    isReplayEnabled = turnPhase == TurnPhase.IDLE_LISTENING && lastSpokenWord != null,
                    onReplay = onReplaySpokenWord,
                )

                Column(modifier = Modifier.weight(1f)) {
                    val caption = when (turnPhase) {
                        TurnPhase.SIGN_TO_SPEECH_STARTING -> "Preparing speech output…"
                        TurnPhase.SIGN_TO_SPEECH_RUNNING -> "Speaking aloud…"
                        TurnPhase.SIGN_TO_SPEECH_ENDING -> "Speech finished"
                        else -> "Signed → Spoken"
                    }
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (turnPhase.isSignToSpeechActive) {
                            SignToSpeechAccent
                        } else {
                            MutedTextSlate
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = lastSpokenWord ?: "Waiting for sign…",
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (lastSpokenWord != null) DeepNavyPrimary else MutedTextSlate,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Keep full microphone status visible above the input bar even while typing replaces
        // the trailing button with Send.
        MicrophoneStatusBanner(
            microphoneStatus = microphoneStatus,
            partialSpeechTranscript = partialSpeechTranscript,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = typedPhrase,
                onValueChange = { updatedText -> typedPhrase = updatedText },
                modifier = Modifier.weight(1f),
                placeholder = { Text(text = "Type a phrase instead of speaking…") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                enabled = turnPhase == TurnPhase.IDLE_LISTENING,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (typedPhrase.isNotBlank()) {
                            onSubmitTypedPhrase(typedPhrase)
                            typedPhrase = ""
                            keyboardController?.hide()
                        }
                    },
                ),
            )

            AnimatedContent(
                targetState = typedPhrase.isNotEmpty(),
                transitionSpec = {
                    fadeIn(tween(140)) togetherWith fadeOut(tween(140))
                },
                label = "inputTrailingActionButton",
            ) { hasTypedCharacters ->
                if (hasTypedCharacters) {
                    FilledTonalIconButton(
                        onClick = {
                            if (typedPhrase.isNotBlank()) {
                                onSubmitTypedPhrase(typedPhrase)
                                typedPhrase = ""
                                keyboardController?.hide()
                            }
                        },
                        enabled = turnPhase == TurnPhase.IDLE_LISTENING && typedPhrase.isNotBlank(),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = DeepNavyPrimary,
                            contentColor = Color.White,
                        ),
                        modifier = Modifier.size(52.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Send,
                            contentDescription = "Send phrase to sign output",
                        )
                    }
                } else {
                    TrailingMicrophoneStatusButton(
                        microphoneStatus = microphoneStatus,
                        onClick = onToggleMicrophone,
                        modifier = Modifier.size(52.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MicrophoneStatusBanner(
    microphoneStatus: MicrophoneStatus,
    partialSpeechTranscript: String,
) {
    val configuration = resolveMicrophoneVisualConfiguration(microphoneStatus)
    val pulseTransition = rememberInfiniteTransition(label = "microphoneBannerPulse")
    val listeningDotAlpha by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "listeningBannerDotAlpha",
    )

    val bannerText = if (microphoneStatus == MicrophoneStatus.LISTENING &&
        partialSpeechTranscript.isNotBlank()
    ) {
        "Hearing: \"$partialSpeechTranscript\""
    } else {
        configuration.detailedStatusText
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (microphoneStatus == MicrophoneStatus.LISTENING) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .alpha(listeningDotAlpha)
                    .clip(CircleShape)
                    .background(SignToSpeechAccent),
            )
        } else {
            Icon(
                imageVector = configuration.icon,
                contentDescription = null,
                tint = configuration.contentColor,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = bannerText,
            style = MaterialTheme.typography.labelMedium,
            color = configuration.contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TrailingMicrophoneStatusButton(
    microphoneStatus: MicrophoneStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val configuration = resolveMicrophoneVisualConfiguration(microphoneStatus)
    val animatedBackgroundColor by animateColorAsState(
        targetValue = configuration.containerColor,
        animationSpec = tween(durationMillis = 200),
        label = "trailingMicrophoneBackground",
    )
    val animatedContentColor by animateColorAsState(
        targetValue = configuration.contentColor,
        animationSpec = tween(durationMillis = 200),
        label = "trailingMicrophoneForeground",
    )

    FilledTonalIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = animatedBackgroundColor,
            contentColor = animatedContentColor,
        ),
        modifier = modifier,
    ) {
        Icon(
            imageVector = configuration.icon,
            contentDescription = configuration.detailedStatusText,
        )
    }
}

@Composable
private fun SignToSpeechCueIndicator(
    turnPhase: TurnPhase,
    isReplayEnabled: Boolean,
    onReplay: () -> Unit,
) {
    val startRippleProgress = remember { Animatable(0f) }
    LaunchedEffect(turnPhase) {
        if (turnPhase == TurnPhase.SIGN_TO_SPEECH_STARTING) {
            startRippleProgress.snapTo(0f)
            startRippleProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 240),
            )
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(46.dp),
    ) {
        if (turnPhase == TurnPhase.SIGN_TO_SPEECH_STARTING) {
            Canvas(modifier = Modifier.size(46.dp)) {
                val radius = (size.minDimension / 2f) * (0.55f + 0.45f * startRippleProgress.value)
                drawCircle(
                    color = SignToSpeechAccent.copy(alpha = 1f - startRippleProgress.value),
                    radius = radius,
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
        }

        when (turnPhase) {
            TurnPhase.SIGN_TO_SPEECH_RUNNING -> {
                AnimatedTealEqualizer(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(SignToSpeechAccent)
                        .padding(8.dp),
                )
            }
            TurnPhase.SIGN_TO_SPEECH_ENDING -> {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(MatchedWordGreen.copy(alpha = 0.2f)),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = "Speech finished",
                        tint = MatchedWordGreen,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            else -> {
                FilledTonalIconButton(
                    onClick = onReplay,
                    enabled = isReplayEnabled,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if (isReplayEnabled) {
                            SignToSpeechAccent
                        } else {
                            Color(0xFFE2E8F0)
                        },
                        contentColor = if (isReplayEnabled) Color.White else MutedTextSlate,
                    ),
                    modifier = Modifier.size(42.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
                        contentDescription = "Speak last sign aloud",
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AnimatedTealEqualizer(
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "tealSpeakingWave")
    val barOneFraction by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(tween(260), RepeatMode.Reverse),
        label = "barOneFraction",
    )
    val barTwoFraction by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(320), RepeatMode.Reverse),
        label = "barTwoFraction",
    )
    val barThreeFraction by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(290), RepeatMode.Reverse),
        label = "barThreeFraction",
    )
    val barFourFraction by infiniteTransition.animateFloat(
        initialValue = 0.75f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(350), RepeatMode.Reverse),
        label = "barFourFraction",
    )

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val barFractions = listOf(barOneFraction, barTwoFraction, barThreeFraction, barFourFraction)
        for (fraction in barFractions) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(20.dp * fraction)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White),
            )
        }
    }
}

private data class MicrophoneVisualConfiguration(
    val detailedStatusText: String,
    val icon: ImageVector,
    val containerColor: Color,
    val contentColor: Color,
)

private fun resolveMicrophoneVisualConfiguration(
    microphoneStatus: MicrophoneStatus,
): MicrophoneVisualConfiguration {
    return when (microphoneStatus) {
        MicrophoneStatus.LISTENING -> MicrophoneVisualConfiguration(
            detailedStatusText = "Mic On · Listening",
            icon = Icons.Rounded.Mic,
            containerColor = SignToSpeechContainer,
            contentColor = SignToSpeechAccent,
        )
        MicrophoneStatus.GATED_FOR_TURN -> MicrophoneVisualConfiguration(
            detailedStatusText = "Mic Gated · Outputting turn",
            icon = Icons.Rounded.Lock,
            containerColor = GatedWarningBackground,
            contentColor = GatedWarningForeground,
        )
        MicrophoneStatus.MUTED_BY_USER -> MicrophoneVisualConfiguration(
            detailedStatusText = "Mic Off · Tap mic to turn on",
            icon = Icons.Rounded.MicOff,
            containerColor = Color(0xFFE2E8F0),
            contentColor = SlateSecondary,
        )
        MicrophoneStatus.PERMISSION_REQUIRED -> MicrophoneVisualConfiguration(
            detailedStatusText = "Mic Permission Required · Tap mic to allow",
            icon = Icons.Rounded.MicOff,
            containerColor = UnmatchedWordContainer,
            contentColor = UnmatchedWordAmber,
        )
        MicrophoneStatus.OFFLINE_MODEL_UNAVAILABLE -> MicrophoneVisualConfiguration(
            detailedStatusText = "Offline Speech Unavailable · Type phrase",
            icon = Icons.Rounded.WarningAmber,
            containerColor = UnmatchedWordContainer,
            contentColor = UnmatchedWordAmber,
        )
    }
}
