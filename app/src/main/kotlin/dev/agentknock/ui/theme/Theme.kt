package dev.agentknock.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF006A68),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9CF1EE),
    onPrimaryContainer = Color(0xFF00201F),
    secondary = Color(0xFF4A6362),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE8E6),
    onSecondaryContainer = Color(0xFF051F1E),
    tertiary = Color(0xFF4B607C),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD2E4FF),
    onTertiaryContainer = Color(0xFF031C35),
    background = Color(0xFFFFFBF7),
    onBackground = Color(0xFF191C1C),
    surface = Color(0xFFFFFBF7),
    onSurface = Color(0xFF191C1C),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAF5F1),
    surfaceContainer = Color(0xFFF4EFEB),
    surfaceContainerHigh = Color(0xFFEEE9E5),
    surfaceContainerHighest = Color(0xFFE8E3DF),
    surfaceVariant = Color(0xFFDAE5E3),
    onSurfaceVariant = Color(0xFF3F4948),
    outline = Color(0xFF6F7978),
    outlineVariant = Color(0xFFBEC9C7),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF80D5D2),
    onPrimary = Color(0xFF003735),
    primaryContainer = Color(0xFF00504E),
    onPrimaryContainer = Color(0xFF9CF1EE),
    secondary = Color(0xFFB1CCCA),
    onSecondary = Color(0xFF1C3533),
    secondaryContainer = Color(0xFF334B49),
    onSecondaryContainer = Color(0xFFCCE8E6),
    tertiary = Color(0xFFB3C8E8),
    onTertiary = Color(0xFF1C314B),
    tertiaryContainer = Color(0xFF334863),
    onTertiaryContainer = Color(0xFFD2E4FF),
    background = Color(0xFF191C1C),
    onBackground = Color(0xFFE0E3E2),
    surface = Color(0xFF191C1C),
    onSurface = Color(0xFFE0E3E2),
    surfaceContainerLowest = Color(0xFF0E1110),
    surfaceContainerLow = Color(0xFF1D2020),
    surfaceContainer = Color(0xFF222625),
    surfaceContainerHigh = Color(0xFF2B2F2E),
    surfaceContainerHighest = Color(0xFF363A39),
    surfaceVariant = Color(0xFF3F4948),
    onSurfaceVariant = Color(0xFFBEC9C7),
    outline = Color(0xFF899391),
    outlineVariant = Color(0xFF3F4948),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AgentknockTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (darkTheme) {
        DarkColors
    } else {
        LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = AppShapes,
        content = content,
    )
}
