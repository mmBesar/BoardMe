package com.mmbesar.boardme

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mmbesar.boardme.security.Mode
import com.mmbesar.boardme.ui.BoardMeTheme
import com.mmbesar.boardme.ui.BoardScreen
import com.mmbesar.boardme.ui.BoardViewModel
import com.mmbesar.boardme.ui.SetupScreen
import com.mmbesar.boardme.ui.ThemePrefs

/** The one and only screen container; Compose draws everything inside it. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themePrefs = (application as BoardMeApplication).themePrefs
            val settings by themePrefs.settings.collectAsStateWithLifecycle()
            BoardMeTheme(settings) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    BoardMeRoot(themePrefs)
                }
            }
        }
    }
}

/** First start -> setup screen; afterwards -> the board. */
@Composable
private fun BoardMeRoot(themePrefs: ThemePrefs, viewModel: BoardViewModel = viewModel()) {
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    if (mode == Mode.UNSET) {
        SetupScreen(viewModel)
    } else {
        BoardScreen(viewModel, themePrefs)
    }
}
