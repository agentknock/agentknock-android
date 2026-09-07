package dev.agentknock.preview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import dev.agentknock.ui.MainNavigationScaffold
import dev.agentknock.ui.MainSection
import dev.agentknock.ui.components.LocalDateTimeFormatter
import dev.agentknock.ui.components.UiDateTimeFormatter
import dev.agentknock.ui.theme.AgentknockTheme
import java.util.TimeZone

@Composable
internal fun PreviewScreen(content: @Composable () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val dates = remember(locale) { UiDateTimeFormatter(locale, use24Hour = true, TimeZone.getTimeZone("UTC")) }
    CompositionLocalProvider(LocalDateTimeFormatter provides dates) {
        AgentknockTheme(dynamicColor = false) {
            Surface(Modifier.fillMaxSize(), content = content)
        }
    }
}

// Layoutlib omits native floating-window decor insets. Measured on the API 37
// emulator at the catalog's 360 dp phone width: 20 dp on either side.
@Composable
internal fun previewDialogModifier(): Modifier =
    Modifier.widthIn(max = LocalConfiguration.current.screenWidthDp.dp - 40.dp)

@Composable
internal fun PreviewNavigation(section: MainSection, empty: Boolean = false, content: @Composable (Modifier) -> Unit) {
    MainNavigationScaffold(
        section = section,
        actionRequiredCounts = if (empty) emptyMap() else mapOf(MainSection.REQUESTS to 2, MainSection.SECRETS to 1, MainSection.CLIENTS to 1),
        showNavigation = true, onSelect = {}, content = content,
    )
}

internal const val previewTimestamp = 1_704_119_700_000L

internal const val previewPairingAddress = "maple-silver-harbor"
internal const val previewNewPairingAddress = "gentle-river-stone"
