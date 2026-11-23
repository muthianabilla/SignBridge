package edu.postech.signbridge.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import edu.postech.signbridge.ui.theme.CardSurfaceWhite
import edu.postech.signbridge.ui.theme.DeepNavyPrimary
import edu.postech.signbridge.ui.theme.NeutralOutlineSlate
import edu.postech.signbridge.ui.theme.SlateSecondary

/**
 * Persistent bottom menu switching between Conversation (live bidirectional translation) and
 * Practice (multi-word vocabulary exploration).
 */
@Composable
fun BottomModeNavigationBar(
    isPracticeModeActive: Boolean,
    onSelectConversationMode: () -> Unit,
    onSelectPracticeMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModeSelectionButton(
            label = "Conversation",
            icon = Icons.Rounded.Forum,
            isSelected = !isPracticeModeActive,
            onClick = onSelectConversationMode,
            modifier = Modifier.weight(1f),
        )
        ModeSelectionButton(
            label = "Practice",
            icon = Icons.Rounded.MenuBook,
            isSelected = isPracticeModeActive,
            onClick = onSelectPracticeMode,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ModeSelectionButton(
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor by animateColorAsState(
        targetValue = if (isSelected) DeepNavyPrimary else CardSurfaceWhite,
        animationSpec = tween(durationMillis = 180),
        label = "modeButtonBackground",
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) Color.White else SlateSecondary,
        animationSpec = tween(durationMillis = 180),
        label = "modeButtonForeground",
    )

    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        border = if (isSelected) null else BorderStroke(1.dp, NeutralOutlineSlate),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}
