package edu.postech.signbridge.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Light high-contrast scheme is locked so Speech-To-Sign (Indigo) and Sign-To-Speech (Teal)
// cues remain visually distinct across different Android devices and lighting conditions.
private val SignBridgeLightColorScheme = lightColorScheme(
    primary = DeepNavyPrimary,
    onPrimary = Color.White,
    primaryContainer = DeepNavyLightContainer,
    onPrimaryContainer = DeepNavyPrimary,
    secondary = SlateSecondary,
    onSecondary = Color.White,
    tertiary = SignToSpeechAccent,
    onTertiary = Color.White,
    background = NeutralSurfaceBackground,
    onBackground = DeepNavyPrimary,
    surface = CardSurfaceWhite,
    onSurface = DeepNavyPrimary,
    outline = NeutralOutlineSlate,
    error = ActiveAlertRed,
    onError = Color.White,
)

@Composable
fun SignBridgeTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = SignBridgeLightColorScheme,
        typography = Typography,
        content = content,
    )
}