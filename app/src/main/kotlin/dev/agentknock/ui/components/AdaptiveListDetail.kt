package dev.agentknock.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldDefaults
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldDestinationItem
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.calculateThreePaneScaffoldValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun AdaptiveListDetail(
    hasDetail: Boolean,
    listWidth: Dp,
    onBack: () -> Unit,
    onTopLevelChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    directive: PaneScaffoldDirective = calculatePaneScaffoldDirective(
        currentWindowAdaptiveInfoV2(),
    ),
    list: @Composable (Modifier) -> Unit,
    emptyDetail: @Composable (Modifier) -> Unit,
    detail: @Composable (showBack: Boolean, Modifier) -> Unit,
) {
    val paneState = rememberSaveableStateHolder()
    val destination = ThreePaneScaffoldDestinationItem<String>(
        if (hasDetail) {
            ListDetailPaneScaffoldRole.Detail
        } else {
            ListDetailPaneScaffoldRole.List
        },
    )
    val scaffoldValue = calculateThreePaneScaffoldValue(
        maxHorizontalPartitions = directive.maxHorizontalPartitions,
        adaptStrategies = ListDetailPaneScaffoldDefaults.adaptStrategies(),
        currentDestination = destination,
        maxVerticalPartitions = directive.maxVerticalPartitions,
    )
    val listVisible = scaffoldValue[ListDetailPaneScaffoldRole.List] != PaneAdaptedValue.Hidden

    BackHandler(enabled = hasDetail, onBack = onBack)

    LaunchedEffect(listVisible, hasDetail) {
        onTopLevelChanged(listVisible || !hasDetail)
    }

    ListDetailPaneScaffold(
        directive = directive,
        value = scaffoldValue,
        modifier = modifier,
        listPane = {
            AnimatedPane(modifier = Modifier.preferredWidth(listWidth)) {
                paneState.SaveableStateProvider(LIST_PANE_KEY) {
                    list(Modifier.fillMaxSize())
                }
            }
        },
        detailPane = {
            AnimatedPane {
                if (hasDetail) {
                    paneState.SaveableStateProvider(DETAIL_PANE_KEY) {
                        detail(!listVisible, Modifier.fillMaxSize())
                    }
                } else {
                    emptyDetail(Modifier.fillMaxSize())
                }
            }
        },
    )
}

private const val LIST_PANE_KEY = "list"
private const val DETAIL_PANE_KEY = "detail"
