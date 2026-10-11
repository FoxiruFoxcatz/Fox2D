package fox.foxiru.foxcat.fox2d.main_canvas

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.max

// ======================================================================================================
// Shared "Blender-style" widgets of the editor panels (header bar buttons, box menus, chips, grids, sliders).
// Same look as the KeyGraph Editor's private GK* widgets (KeyGraphUI.kt), made internal so RigUI.kt (and any
// other panel) can use them. KeyGraphUI.kt is untouched; its GK* copies can be swapped for these later.
// ======================================================================================================

/** Flat 28 dp text tab / button of a header bar: transparent until selected. Width follows the text. */
@Composable
internal fun FxTab(text: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) cs.primaryContainer else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, style = MaterialTheme.typography.labelLarge, maxLines = 1,
            color = if (!enabled) cs.onSurface.copy(alpha = 0.32f) else if (selected) cs.onPrimaryContainer else cs.onSurface,
        )
    }
}

/** Flat 30 x 28 dp icon-ish button (undo / redo / fold) of a header bar. */
@Composable
internal fun FxTBtn(text: String, enabled: Boolean = true, selected: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(width = 30.dp, height = 28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) cs.primaryContainer else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, style = MaterialTheme.typography.labelLarge,
            color = if (!enabled) cs.onSurface.copy(alpha = 0.32f) else if (selected) cs.onPrimaryContainer else cs.onSurface,
        )
    }
}

/** Opens a popup box above / below the anchor, whichever side has more room. */
private class FxMenuPos : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = anchorBounds.left.coerceIn(0, max(0, windowSize.width - popupContentSize.width))
        val above = anchorBounds.top
        val below = windowSize.height - anchorBounds.bottom
        val y = if (above >= below) anchorBounds.top - popupContentSize.height else anchorBounds.bottom
        return IntOffset(x, y.coerceIn(0, max(0, windowSize.height - popupContentSize.height)))
    }
}

/**
 * Header menu: the title is a flat button, tapping it opens a BOX of grouped controls.
 * [content] gets `close` so one-shot actions can dismiss the box while option chips / toggles keep it open.
 */
@Composable
internal fun FxMenu(title: String, enabled: Boolean = true, content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .height(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (open) cs.primaryContainer else Color.Transparent)
                .clickable(enabled = enabled) { open = !open }
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                title, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                color = if (!enabled) cs.onSurface.copy(alpha = 0.32f) else if (open) cs.onPrimaryContainer else cs.onSurface,
            )
        }
        if (open && enabled) Popup(FxMenuPos(), { open = false }, PopupProperties(focusable = true)) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = cs.surfaceContainerHigh,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, cs.outlineVariant),
                modifier = Modifier.padding(6.dp).width(272.dp),
            ) {
                Column(
                    Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) { content { open = false } }
            }
        }
    }
}

@Composable
internal fun FxSection(text: String) {
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 2.dp, top = 4.dp),
    )
}

@Composable
internal fun FxHint(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp, top = 4.dp))
}

/** Option / action chip. Pass `Modifier.weight(1f)` (FxGrid does) so a row of chips shares the width; [height] 26 dp fits the body strips. */
@Composable
internal fun FxChip(
    text: String, modifier: Modifier = Modifier, selected: Boolean = false, enabled: Boolean = true,
    height: Dp = 30.dp, onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) cs.primaryContainer else cs.surfaceContainerHighest)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (height < 30.dp) 8.dp else 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (!enabled) cs.onSurface.copy(alpha = 0.34f) else if (selected) cs.onPrimaryContainer else cs.onSurface,
        )
    }
}

/** [cols] equal-width cells per row; the last row is padded so cells keep the same width. */
@Composable
internal fun <T> FxGrid(items: List<T>, cols: Int, cell: @Composable (T, Modifier) -> Unit) {
    for (rowItems in items.chunked(cols)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (item in rowItems) cell(item, Modifier.weight(1f))
            repeat(cols - rowItems.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
internal fun FxCheck(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(7.dp)).clickable { onChange(!checked) }.padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (checked) cs.primary else Color.Transparent)
                .border(1.5f.dp, if (checked) cs.primary else cs.outline, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) { if (checked) Text("✓", fontSize = 11.sp, color = cs.onPrimary) }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface, maxLines = 1)
    }
}

@Composable
internal fun FxAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(7.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (enabled) cs.onSurface else cs.onSurface.copy(alpha = 0.34f), maxLines = 1)
    }
}

/** Compact "label | slider | value" line for a box menu. [onDone] fires when the drag ends (commit the undo step there). */
@Composable
internal fun FxSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, text: String,
    onChange: (Float) -> Unit, onDone: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label, Modifier.width(64.dp).padding(start = 2.dp), style = MaterialTheme.typography.labelMedium,
            color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Slider(value.coerceIn(range), onChange, onValueChangeFinished = onDone, valueRange = range, modifier = Modifier.weight(1f).height(28.dp))
        Text(
            text, Modifier.widthIn(min = 40.dp).padding(horizontal = 2.dp), style = MaterialTheme.typography.labelMedium,
            color = cs.primary, textAlign = TextAlign.End, maxLines = 1,
        )
    }
}
