package edu.postech.signbridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import edu.postech.signbridge.turn.SignBridgeViewModel
import edu.postech.signbridge.ui.UnifiedBridgeScreen
import edu.postech.signbridge.ui.theme.SignBridgeTheme
import edu.postech.signbridge.vision.FakeSignRecognizer

class MainActivity : ComponentActivity() {

    // Replace FakeSignRecognizer() with your teammate's MediaPipe SignRecognizer when ready.
    private val viewModel: SignBridgeViewModel by viewModels {
        SignBridgeViewModel.provideFactory(
            applicationContext = applicationContext,
            signRecognizer = MediaPipeSignRecognizer(applicationContext),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SignBridgeTheme {
                UnifiedBridgeScreen(viewModel = viewModel)
            }
        }
    }
}
