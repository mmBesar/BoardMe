package com.mmbesar.boardme.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Colours from the Catppuccin palette (https://catppuccin.com):
// light = "Latte", dark = "Macchiato", accent = "Mauve". Follows the system dark mode.

/** Light theme (Latte). Public because the home-screen widget reuses it. */
val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF8839EF),            // Mauve
    onPrimary = Color(0xFFEFF1F5),          // Base
    primaryContainer = Color(0xFFDCE0E8),   // Crust
    onPrimaryContainer = Color(0xFF4C4F69), // Text
    secondary = Color(0xFF7287FD),          // Lavender
    onSecondary = Color(0xFFEFF1F5),
    background = Color(0xFFEFF1F5),         // Base
    onBackground = Color(0xFF4C4F69),       // Text
    surface = Color(0xFFEFF1F5),
    onSurface = Color(0xFF4C4F69),
    surfaceVariant = Color(0xFFCCD0DA),     // Surface0
    onSurfaceVariant = Color(0xFF6C6F85),   // Subtext0
    surfaceContainerLowest = Color(0xFFDCE0E8),
    surfaceContainerLow = Color(0xFFE6E9EF), // Mantle
    surfaceContainer = Color(0xFFE6E9EF),
    surfaceContainerHigh = Color(0xFFCCD0DA),
    surfaceContainerHighest = Color(0xFFBCC0CC),
    outline = Color(0xFF9CA0B0),            // Overlay0
    error = Color(0xFFD20F39),              // Red
    onError = Color(0xFFEFF1F5),
)

/** Dark theme (Macchiato). */
val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFC6A0F6),            // Mauve
    onPrimary = Color(0xFF24273A),          // Base
    primaryContainer = Color(0xFF363A4F),   // Surface0
    onPrimaryContainer = Color(0xFFCAD3F5), // Text
    secondary = Color(0xFFB7BDF8),          // Lavender
    onSecondary = Color(0xFF24273A),
    background = Color(0xFF24273A),         // Base
    onBackground = Color(0xFFCAD3F5),       // Text
    surface = Color(0xFF24273A),
    onSurface = Color(0xFFCAD3F5),
    surfaceVariant = Color(0xFF363A4F),     // Surface0
    onSurfaceVariant = Color(0xFFA5ADCB),   // Subtext0
    surfaceContainerLowest = Color(0xFF181926), // Crust
    surfaceContainerLow = Color(0xFF1E2030),    // Mantle
    surfaceContainer = Color(0xFF1E2030),
    surfaceContainerHigh = Color(0xFF363A4F),
    surfaceContainerHighest = Color(0xFF494D64), // Surface1
    outline = Color(0xFF6E738D),            // Overlay0
    error = Color(0xFFED8796),              // Red
    onError = Color(0xFF24273A),
)

/** Wrap every screen in this to get the BoardMe colours. */
@Composable
fun BoardMeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
