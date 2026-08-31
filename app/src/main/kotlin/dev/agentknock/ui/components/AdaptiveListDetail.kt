package dev.agentknock.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun AdaptiveListDetail(
    hasDetail: Boolean,
    listWidth: Dp,
    onBack: () -> Unit,
    onTopLevelChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    obscured: Boolean = false,
    list: @Composable (Modifier) -> Unit,
    emptyDetail: @Composable (Modifier) -> Unit,
    detail: @Composable (showBack: Boolean, Modifier) -> Unit,
) {
    val currentList = rememberUpdatedState(list)
    val currentDetail = rememberUpdatedState(detail)
    val retainedList = remember {
        movableContentOf<Modifier> { childModifier -> currentList.value(childModifier) }
    }
    val retainedDetail = remember {
        movableContentOf<Boolean, Modifier> { showBack, childModifier ->
            currentDetail.value(showBack, childModifier)
        }
    }

    BoxWithConstraints(modifier) {
        val expanded = maxWidth >= EXPANDED_CONTENT_WIDTH
        LaunchedEffect(expanded, hasDetail, obscured) {
            onTopLevelChanged((expanded || !hasDetail) && !obscured)
        }

        if (expanded) {
            Row(Modifier.fillMaxSize()) {
                retainedList(Modifier.width(listWidth).fillMaxHeight())
                VerticalDivider()
                if (hasDetail) {
                    retainedDetail(false, Modifier.weight(1f).fillMaxHeight())
                } else {
                    emptyDetail(Modifier.weight(1f).fillMaxHeight())
                }
            }
        } else if (hasDetail) {
            BackHandler(onBack = onBack)
            retainedDetail(true, Modifier.fillMaxSize())
        } else {
            retainedList(Modifier.fillMaxSize())
        }
    }
}

private val EXPANDED_CONTENT_WIDTH = 720.dp
