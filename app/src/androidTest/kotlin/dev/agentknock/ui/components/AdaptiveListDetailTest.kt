package dev.agentknock.ui.components

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@RunWith(AndroidJUnit4::class)
class AdaptiveListDetailTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun compactBackRestorationAndExpandedResizePreservePaneState() {
        val directive = mutableStateOf(PaneScaffoldDirective.Default.withPartitions(1))
        val restoration = StateRestorationTester(compose)
        lateinit var pressBack: () -> Unit
        restoration.setContent {
            val backDispatcher =
                checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            SideEffect { pressBack = backDispatcher::onBackPressed }
            MaterialTheme {
                TestListDetail(directive.value)
            }
        }

        compose.onNodeWithTag(LIST_INPUT).performTextInput("list draft")
        compose.onNodeWithTag(OPEN_DETAIL).performClick()
        compose.onNodeWithTag(DETAIL_INPUT).performTextInput("detail draft")

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithTag(DETAIL_INPUT).assertTextEquals("detail draft")
        compose.runOnIdle(pressBack)
        compose.onNodeWithTag(LIST_INPUT).assertTextEquals("list draft")

        compose.runOnIdle {
            directive.value = PaneScaffoldDirective.Default.withPartitions(2)
        }
        compose.onNodeWithTag(OPEN_DETAIL).performClick()

        compose.onNodeWithTag(LIST_INPUT).assertIsDisplayed()
        compose.onNodeWithTag(DETAIL_INPUT).assertIsDisplayed()
        compose.onNodeWithTag(DETAIL_INPUT).assertTextEquals("detail draft")
    }
}

@Composable
private fun TestListDetail(directive: PaneScaffoldDirective) {
    var hasDetail by rememberSaveable { mutableStateOf(false) }
    AdaptiveListDetail(
        hasDetail = hasDetail,
        listWidth = 320.dp,
        onBack = { hasDetail = false },
        onTopLevelChanged = {},
        directive = directive,
        modifier = Modifier.fillMaxSize(),
        list = { modifier ->
            Column(modifier) {
                var draft by rememberSaveable { mutableStateOf("") }
                TextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.testTag(LIST_INPUT),
                )
                Button(
                    onClick = { hasDetail = true },
                    modifier = Modifier.testTag(OPEN_DETAIL),
                ) {
                    Text("Open")
                }
            }
        },
        emptyDetail = { modifier -> Text("No detail", modifier) },
        detail = { _, modifier ->
            var draft by rememberSaveable { mutableStateOf("") }
            TextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = modifier.testTag(DETAIL_INPUT),
            )
        },
    )
}

private fun PaneScaffoldDirective.withPartitions(count: Int): PaneScaffoldDirective =
    copy(maxHorizontalPartitions = count)

private const val LIST_INPUT = "list_input"
private const val OPEN_DETAIL = "open_detail"
private const val DETAIL_INPUT = "detail_input"
