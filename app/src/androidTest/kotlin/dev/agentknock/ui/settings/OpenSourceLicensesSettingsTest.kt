package dev.agentknock.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import dev.agentknock.ui.theme.AgentknockTheme
import org.junit.Rule
import org.junit.Test

class OpenSourceLicensesSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun aboutOpensBundledNoticeAndRestoresItBeforeBackReturnsToAbout() {
        // BIP-39 is bundled data, so an inventory made only from Maven metadata would miss it.
        // Compare with the original checked-in notice to protect its complete attribution text.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val notice =
            context.assets.open("licenses/bip39.txt").bufferedReader().use { it.readText() }
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            AgentknockTheme {
                AboutSettings(
                    identity = null,
                    onBack = {},
                    report = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        compose
            .onNode(hasScrollToIndexAction())
            .performScrollToNode(hasText("Open source licenses"))
        compose.onNodeWithText("Open source licenses").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty()
        }
        compose
            .onNode(hasScrollToIndexAction())
            .performScrollToNode(hasText("BIP-39 English word list"))
        compose.onNodeWithText("BIP-39 English word list").performClick()
        compose.onNodeWithText(notice).assertExists()

        restoration.emulateSavedInstanceStateRestore()

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasText(notice)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(notice).assertExists()
        pressBack()
        compose.onNodeWithText(notice).assertDoesNotExist()
        compose.onNodeWithText("Open source licenses").assertIsDisplayed()
        pressBack()
        compose.onNodeWithText("About Agentknock").assertIsDisplayed()
    }
}
