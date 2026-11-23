package edu.postech.signbridge.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import edu.postech.signbridge.data.SignVocabulary
import edu.postech.signbridge.model.TurnPhase
import edu.postech.signbridge.model.VocabularyWord
import edu.postech.signbridge.ui.theme.CardSurfaceWhite
import edu.postech.signbridge.ui.theme.DeepNavyLightContainer
import edu.postech.signbridge.ui.theme.DeepNavyPrimary
import edu.postech.signbridge.ui.theme.MutedTextSlate
import edu.postech.signbridge.ui.theme.NeutralOutlineSlate
import edu.postech.signbridge.ui.theme.SpeechToSignAccent
import edu.postech.signbridge.ui.theme.SpeechToSignContainer

/**
 * Vocabulary grid for multi-word sign practice.
 *
 * Remains visible across word taps until the user switches back to Conversation mode in the
 * bottom navigation bar.
 */
@Composable
fun DockedPracticeSheet(
    turnPhase: TurnPhase,
    activeSignWordLabel: String?,
    onSelectWord: (VocabularyWord) -> Unit,
    modifier: Modifier = Modifier,
) {
    val applicationContext = LocalContext.current
    val wordAvailabilityMap = remember(applicationContext) {
        SignVocabulary.allWords.associate { word ->
            word.identifier to SignVocabulary.hasVideoAsset(applicationContext, word)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardSurfaceWhite),
        border = BorderStroke(1.dp, NeutralOutlineSlate),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                text = "Tap words to practice",
                style = MaterialTheme.typography.titleMedium,
                color = DeepNavyPrimary,
            )

            Spacer(modifier = Modifier.height(8.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(
                    items = SignVocabulary.allWords,
                    key = { word -> word.identifier },
                ) { vocabularyWord ->
                    val isCurrentlyPlaying = activeSignWordLabel == vocabularyWord.displayLabel &&
                            turnPhase.isSpeechToSignActive
                    val hasVideoClip = wordAvailabilityMap[vocabularyWord.identifier] == true

                    PracticeWordTile(
                        vocabularyWord = vocabularyWord,
                        isCurrentlyPlaying = isCurrentlyPlaying,
                        hasVideoClip = hasVideoClip,
                        isEnabled = turnPhase == TurnPhase.IDLE_LISTENING,
                        onClick = { onSelectWord(vocabularyWord) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PracticeWordTile(
    vocabularyWord: VocabularyWord,
    isCurrentlyPlaying: Boolean,
    hasVideoClip: Boolean,
    isEnabled: Boolean,
    onClick: () -> Unit,
) {
    val containerColor = when {
        isCurrentlyPlaying -> SpeechToSignAccent
        hasVideoClip -> SpeechToSignContainer
        else -> DeepNavyLightContainer.copy(alpha = 0.6f)
    }
    val contentColor = if (isCurrentlyPlaying) Color.White else DeepNavyPrimary

    Surface(
        onClick = onClick,
        enabled = isEnabled,
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        border = BorderStroke(
            width = if (isCurrentlyPlaying) 2.dp else 1.dp,
            color = if (isCurrentlyPlaying) SpeechToSignAccent else NeutralOutlineSlate,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = vocabularyWord.displayLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!hasVideoClip) {
                    Text(
                        text = "(clip missing)",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isCurrentlyPlaying) {
                            Color.White.copy(alpha = 0.85f)
                        } else {
                            MutedTextSlate
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = "Practice ${vocabularyWord.displayLabel}",
                tint = contentColor,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
