package fox.foxiru.foxcat.fox2d

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Long-press + drag reorderable list, built on sh.calvin.reorderable (libs.reorderable).
 * The library does the hard parts (drag translation, neighbour swaps, auto-scroll, settle); this wrapper adds
 * the Foxiru feel: bouncy lift (scale + shadow), spring placement for the neighbours, fade for insert / remove.
 *
 * @param onMove called each time the dragged row passes a neighbour: move item [from] to index [to] in your list.
 * @param onDragFinished called once on release: persist the order here.
 */
@Composable
fun <T : Any> FoxiruReorderList(
    items: List<T>,
    key: (T) -> Any,
    onMove: (from: Int, to: Int) -> Unit,
    onDragFinished: () -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    spacing: Dp = 12.dp,
    liftShape: Shape = RoundedCornerShape(28.dp),
    content: @Composable (item: T, index: Int) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val latestOnMove by rememberUpdatedState(onMove)
    val latestOnFinished by rememberUpdatedState(onDragFinished)

    val reorder = rememberReorderableLazyListState(state) { from, to ->
        latestOnMove(from.index, to.index)
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    LazyColumn(
        modifier = modifier,
        state = state,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        itemsIndexed(items, key = { _, item -> key(item) }) { index, item ->
            ReorderableItem(
                state = reorder,
                key = key(item),
                animateItemModifier = Modifier.animateItem(
                    fadeInSpec = tween(320),
                    placementSpec = spring(dampingRatio = 0.82f, stiffness = 380f),
                    fadeOutSpec = tween(200),
                ),
            ) { dragging ->
                val lift by animateFloatAsState(
                    targetValue = if (dragging) 1f else 0f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                    label = "lift",
                )
                Box(
                    Modifier
                        .longPressDraggableHandle(
                            onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                            onDragStopped = { latestOnFinished() },
                        )
                        .graphicsLayer {
                            val s = 1f + 0.035f * lift
                            scaleX = s
                            scaleY = s
                            shadowElevation = 18.dp.toPx() * lift
                            shape = liftShape
                            clip = false
                        },
                ) {
                    content(item, index)
                }
            }
        }
    }
}
