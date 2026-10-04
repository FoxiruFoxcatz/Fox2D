package fox.foxiru.foxcat.fox2d

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.contentColorFor
import androidx.compose.ui.graphics.Color

private val OuterCorner = 28.dp
private val InnerCorner = 4.dp
private val PanelItemCorner = 20.dp
private val ItemGap = 2.dp
private val PillGap = 8.dp
private const val DisabledAlpha = 0.38f

val LocalFoxiruShape = compositionLocalOf<Shape> { RoundedCornerShape(OuterCorner) }

private fun segmentShape(
    index: Int,
    count: Int,
    outer: Dp = OuterCorner,
    inner: Dp = InnerCorner,
): RoundedCornerShape {
    val top = if (index == 0) outer else inner
    val bottom = if (index == count - 1) outer else inner
    return RoundedCornerShape(top, top, bottom, bottom)
}

// ====================== Group ======================

class FoxiruGroupScope {
    internal val entries = mutableListOf<@Composable () -> Unit>()
    fun item(content: @Composable () -> Unit) {
        entries += content
    }
}

@Composable
fun FoxiruGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector? = null,
    topBar: (@Composable RowScope.() -> Unit)? = null,
    topBarColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    topBarContentColor: Color = contentColorFor(topBarColor),
    panel: Boolean = topBar != null,
    scrollable: Boolean = false,
    segmented: Boolean = true,
    content: FoxiruGroupScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val entries = FoxiruGroupScope().apply(content).entries
    val scrollState = rememberScrollState()
    val topBarScroll = rememberScrollState()
    val itemCorner = if (panel) PanelItemCorner else OuterCorner
    val gap = if (segmented) ItemGap else PillGap
    val scrollModifier = if (scrollable) Modifier.verticalScroll(scrollState) else Modifier

    val body: @Composable (Modifier) -> Unit = { bodyModifier ->
        Column(
            modifier = bodyModifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            entries.forEachIndexed { index, entry ->
                key(index) {
                    val shape = if (segmented) {
                        segmentShape(index, entries.size, outer = itemCorner)
                    } else {
                        RoundedCornerShape(itemCorner)
                    }
                    CompositionLocalProvider(LocalFoxiruShape provides shape) { entry() }
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) FoxiruGroupHeader(title = title, icon = icon)

        if (!panel) {
            val fill = if (scrollable) Modifier.weight(1f, fill = false) else Modifier
            body(fill.then(scrollModifier))
        } else {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (scrollable) Modifier.weight(1f, fill = false) else Modifier),
                shape = RoundedCornerShape(OuterCorner),
                color = cs.surfaceContainer,
            ) {
                Column {
                    if (topBar != null) {
                        Surface(color = topBarColor, contentColor = topBarContentColor) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 56.dp)
                                    .horizontalScroll(topBarScroll)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                content = topBar,
                            )
                        }
                        HorizontalDivider(color = cs.outlineVariant)
                    }
                    val innerFill = if (scrollable) Modifier.weight(1f, fill = false) else Modifier
                    body(innerFill.then(scrollModifier).padding(8.dp))
                }
            }
        }
    }
}

@Composable
private fun FoxiruGroupHeader(title: String, icon: ImageVector?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
    }
}

/** Icon + text button for FoxiruGroup's topBar. */
@Composable
fun FoxiruTopBarAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(
            contentColor = LocalContentColor.current,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            softWrap = false,
        )
    }
}

// ====================== Item ======================

/**
 * @param leading   Anything on the left (RadioButton, avatar...). Replaces [icon].
 * @param trailing  Anything on the right (Switch, Buttons, chip...). Gets the enabled flag.
 * @param content   Always-visible area under the texts (slider, progress...).
 * @param expandedContent  Animated body shown when the header is clicked (radios, checks, buttons...).
 * @param enabled   false = greyed out, not clickable, cannot expand, children are blocked too.
 */
@Composable
fun FoxiruItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    icon: ImageVector? = null,
    leading: (@Composable (enabled: Boolean) -> Unit)? = null,
    enabled: Boolean = true,
    shape: Shape = LocalFoxiruShape.current,
    initiallyExpanded: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable (enabled: Boolean) -> Unit)? = null,
    content: (@Composable ColumnScope.(enabled: Boolean) -> Unit)? = null,
    expandedContent: (@Composable ColumnScope.(enabled: Boolean) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val expandable = expandedContent != null
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val isExpanded = expanded && enabled && expandable

    val containerColor by animateColorAsState(
        targetValue = if (enabled) cs.surfaceContainerHigh
        else cs.onSurface.copy(alpha = 0.08f).compositeOver(cs.surface),
        label = "container",
    )
    val titleColor by animateColorAsState(
        targetValue = if (enabled) cs.onSurface else cs.onSurface.copy(alpha = DisabledAlpha),
        label = "title",
    )
    val subtitleColor by animateColorAsState(
        targetValue = if (enabled) cs.onSurfaceVariant else cs.onSurface.copy(alpha = DisabledAlpha),
        label = "subtitle",
    )
    val arrowRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "arrow",
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { if (!enabled) disabled() },
        shape = shape,
        color = containerColor,
        contentColor = titleColor,
    ) {
        Column {
            // Header (only this part toggles / clicks)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled && (expandable || onClick != null)) {
                        if (expandable) expanded = !expanded
                        onClick?.invoke()
                    }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (leading != null) {
                    Box(Modifier.blockTouches(!enabled)) { leading(enabled) }
                } else if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (enabled) cs.primary else titleColor,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = title, style = MaterialTheme.typography.titleMedium, color = titleColor)
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = subtitleColor,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    if (value != null) {
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodyMedium,
                            color = subtitleColor,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                if (trailing != null) {
                    Box(Modifier.blockTouches(!enabled)) { trailing(enabled) }
                }
                if (expandable) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = titleColor,
                        modifier = Modifier.rotate(arrowRotation),
                    )
                }
            }

            // Always-visible body
            if (content != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .blockTouches(!enabled)
                        .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                ) { content(enabled) }
            }

            // Animated expandable body
            if (expandedContent != null) {
                AnimatedVisibility(
                    visible = isExpanded,
                    enter = expandVertically(tween(300, easing = FastOutSlowInEasing)) +
                        fadeIn(tween(250, delayMillis = 50)),
                    exit = shrinkVertically(tween(250, easing = FastOutSlowInEasing)) +
                        fadeOut(tween(150)),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                    ) { expandedContent(enabled) }
                }
            }
        }
    }
}

@Composable
fun FoxiruTwoPane(
    modifier: Modifier = Modifier,
    breakpoint: Dp = 600.dp,
    spacing: Dp = 12.dp,
    secondMaxFraction: Float = 0.45f,
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        // Read these here: inside Row/Column the implicit receiver is blocked
        val availableWidth = maxWidth
        val availableHeight = maxHeight

        if (availableWidth >= breakpoint) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                first(Modifier.weight(1f).fillMaxHeight())
                second(Modifier.weight(1f))
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(spacing),
            ) {
                first(Modifier.weight(1f))
                second(Modifier.heightIn(max = availableHeight * secondMaxFraction))
            }
        }
    }
}

// Swallows every touch before children see it (used for the disabled state)
private fun Modifier.blockTouches(block: Boolean): Modifier =
    if (!block) this else pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }
    }

// ====================== Option rows for expandedContent ======================

@Composable
fun FoxiruRadioOption(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.let { if (enabled) it else it.copy(alpha = DisabledAlpha) },
        )
    }
}

@Composable
fun FoxiruCheckOption(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .selectable(
                selected = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onClick = { onCheckedChange(!checked) },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.let { if (enabled) it else it.copy(alpha = DisabledAlpha) },
        )
    }
}