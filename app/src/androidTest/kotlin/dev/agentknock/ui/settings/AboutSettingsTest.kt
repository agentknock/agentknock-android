package dev.agentknock.ui.settings

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.net.MailTo
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.agentknock.BuildConfig
import dev.agentknock.ui.theme.AgentknockTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AboutSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun shareFeedbackOpensAnEmailDraftWithTheInstalledVersion() {
        val intents = mutableListOf<Intent>()
        val reports = mutableListOf<String>()
        showAbout(onStartActivity = { intents += it }, report = { reports += it })

        compose.onNodeWithText("Share feedback").performScrollTo().performClick()

        compose.runOnIdle {
            val intent = intents.single()
            // SENDTO plus mailto restricts the handoff to email apps. Use Android's parser
            // to check that the recipient and build details survive URI encoding.
            assertEquals(Intent.ACTION_SENDTO, intent.action)
            assertEquals("mailto", intent.data?.scheme)
            val mail = MailTo.parse(requireNotNull(intent.dataString))
            assertEquals("agentknock@fulldisclosure.fi", mail.to)
            val subject = requireNotNull(mail.subject)
            assertTrue(subject.contains(BuildConfig.VERSION_NAME))
            assertTrue(subject.contains(BuildConfig.VERSION_CODE.toString()))
            assertTrue(subject.contains(BuildConfig.FLAVOR))
            assertTrue(reports.isEmpty())
        }
    }

    @Test
    fun missingEmailAppReportsFailureAndKeepsBackNavigationAvailable() {
        val reports = mutableListOf<String>()
        var backCount = 0
        showAbout(
            // Android throws this when no installed activity handles the email intent.
            onStartActivity = { throw ActivityNotFoundException() },
            report = { reports += it },
            onBack = { backCount++ },
        )

        compose.onNodeWithText("Share feedback").performScrollTo().performClick()

        compose.runOnIdle { assertEquals(listOf("No email app available"), reports) }
        compose.onNodeWithText("About Agentknock").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(1, backCount) }
    }

    private fun showAbout(
        onStartActivity: (Intent) -> Unit,
        report: (String) -> Unit,
        onBack: () -> Unit = {},
    ) {
        compose.setContent {
            val baseContext = LocalContext.current
            val context =
                remember(baseContext) {
                    object : ContextWrapper(baseContext) {
                        override fun startActivity(intent: Intent) {
                            onStartActivity(intent)
                        }
                    }
                }
            CompositionLocalProvider(LocalContext provides context) {
                AgentknockTheme {
                    AboutSettings(
                        identity = null,
                        onBack = onBack,
                        report = report,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
