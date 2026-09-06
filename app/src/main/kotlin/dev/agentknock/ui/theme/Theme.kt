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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF8A5F17),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF6E8C8),
    onPrimaryContainer = Color(0xFF4A320A),
    secondary = Color(0xFF6A5F4F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3E3CF),
    onSecondaryContainer = Color(0xFF241A0D),
    tertiary = Color(0xFF4B607C),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD2E4FF),
    onTertiaryContainer = Color(0xFF031C35),
    background = Color(0xFFFFFBF7),
    onBackground = Color(0xFF201B13),
    surface = Color(0xFFFFFBF7),
    onSurface = Color(0xFF201B13),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAF5F1),
    surfaceContainer = Color(0xFFF4EFEB),
    surfaceContainerHigh = Color(0xFFEEE9E5),
    surfaceContainerHighest = Color(0xFFE8E3DF),
    surfaceVariant = Color(0xFFEDE1D4),
    onSurfaceVariant = Color(0xFF4D463C),
    outline = Color(0xFF7F7669),
    outlineVariant = Color(0xFFD1C5B6),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE8C77C),
    onPrimary = Color(0xFF402D05),
    primaryContainer = Color(0xFF5C3E10),
    onPrimaryContainer = Color(0xFFF6E8C8),
    secondary = Color(0xFFD5C3AC),
    onSecondary = Color(0xFF3A2F20),
    secondaryContainer = Color(0xFF514534),
    onSecondaryContainer = Color(0xFFF2E0C8),
    tertiary = Color(0xFFB3C8E8),
    onTertiary = Color(0xFF1C314B),
    tertiaryContainer = Color(0xFF334863),
    onTertiaryContainer = Color(0xFFD2E4FF),
    background = Color(0xFF15171A),
    onBackground = Color(0xFFE4E2DE),
    surface = Color(0xFF15171A),
    onSurface = Color(0xFFE4E2DE),
    surfaceContainerLowest = Color(0xFF101214),
    surfaceContainerLow = Color(0xFF1D2023),
    surfaceContainer = Color(0xFF22252A),
    surfaceContainerHigh = Color(0xFF2B2F35),
    surfaceContainerHighest = Color(0xFF363A40),
    surfaceVariant = Color(0xFF3A3F46),
    onSurfaceVariant = Color(0xFFC9C5BD),
    outline = Color(0xFF958F86),
    outlineVariant = Color(0xFF494640),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Immutable
data class AgentknockColors(
    val brand: Color,
    val onBrand: Color,
    val attentionContainer: Color,
    val onAttentionContainer: Color,
    val attentionAccent: Color,
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val danger: Color,
    val dangerContainer: Color,
    val onDangerContainer: Color,
)

private val LightAgentknockColors = AgentknockColors(
    brand = Color(0xFFE3A43B),
    onBrand = Color(0xFF22252A),
    attentionContainer = Color(0xFFF6E8C8),
    onAttentionContainer = Color(0xFF4A320A),
    attentionAccent = Color(0xFF8A5F17),
    success = Color(0xFF087548),
    onSuccess = Color.White,
    successContainer = Color(0xFFD9EFE3),
    onSuccessContainer = Color(0xFF053522),
    danger = Color(0xFFB42318),
    dangerContainer = Color(0xFFFFF1F0),
    onDangerContainer = Color(0xFF601410),
)

private val DarkAgentknockColors = AgentknockColors(
    brand = Color(0xFFE3A43B),
    onBrand = Color(0xFF22252A),
    attentionContainer = Color(0xFF5C3E10),
    onAttentionContainer = Color(0xFFF6E8C8),
    attentionAccent = Color(0xFFE8C77C),
    success = Color(0xFFB5DEC8),
    onSuccess = Color(0xFF053522),
    successContainer = Color(0xFF053522),
    onSuccessContainer = Color(0xFFB5DEC8),
    danger = Color(0xFFFFB4AB),
    dangerContainer = Color(0xFF690005),
    onDangerContainer = Color(0xFFFFDAD6),
)

private val LocalAgentknockColors = staticCompositionLocalOf<AgentknockColors> {
    error("Agentknock colors were not provided")
}

val MaterialTheme.agentknockColors: AgentknockColors
    @Composable
    @ReadOnlyComposable
    get() = LocalAgentknockColors.current

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
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (darkTheme) {
        DarkColors
    } else {
        LightColors
    }
    CompositionLocalProvider(
        LocalAgentknockColors provides if (darkTheme) {
            DarkAgentknockColors
        } else {
            LightAgentknockColors
        },
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = AppShapes,
            content = content,
        )
    }
}
