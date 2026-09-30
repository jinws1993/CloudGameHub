package com.nasgame.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4F9EFF),
    onPrimary = Color.White,
    secondary = Color(0xFFA06BFF),
    background = Color(0xFF0F1419),
    surface = Color(0xFF1A1F29),
    surfaceVariant = Color(0xFF242A36),
    onBackground = Color(0xFFE5E9EF),
    onSurface = Color(0xFFE5E9EF),
)

@Composable
fun NASGameTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = Typography(),
        content = content,
    )
}
