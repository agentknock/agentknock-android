package dev.agentknock.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF006A68),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9CF1EE),
    onPrimaryContainer = Color(0xFF00201F),
    secondary = Color(0xFF4A6362),
    tertiary = Color(0xFF65587B),
    background = Color(0xFFFFFBF7),
    surface = Color(0xFFFFFBF7),
    surfaceVariant = Color(0xFFDAE5E3),
    outline = Color(0xFF6F7978),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF80D5D2),
    onPrimary = Color(0xFF003735),
    primaryContainer = Color(0xFF00504E),
    onPrimaryContainer = Color(0xFF9CF1EE),
    secondary = Color(0xFFB1CCCA),
    tertiary = Color(0xFFD0BFE7),
    background = Color(0xFF191C1C),
    surface = Color(0xFF191C1C),
    surfaceVariant = Color(0xFF3F4948),
    error = Color(0xFFFFB4AB),
)

@Composable
fun AgentknockTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
