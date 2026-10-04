package fox.foxiru.foxcat.fox2d.main_canvas

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Space the docked tool rail takes on [side] (thickness + gap); 0 when the rail sits on another edge.
 * Other floating chrome (TrackToolbar, ViewChip) adds this to its padding so nothing overlaps the rail.
 */
internal fun EditorState.railInset(side: ToolbarDock): Dp = if (railDock == side) 52.dp else 0.dp

/** The rail runs along a vertical edge (Left / Right) -> its buttons stack in a column. */
private val ToolbarDock.runsVertical: Boolean get() = this == ToolbarDock.Left || this == ToolbarDock.Right

/** Edge closest to [c], measured as a fraction of the area so a wide screen doesn't favour top / bottom. */
private fun nearestDock(c: Offset, w: Float, h: Float): ToolbarDock {
    val ww = w.coerceAtLeast(1f); val hh = h.coerceAtLeast(1f)
    val dL = c.x / ww; val dR = (ww - c.x) / ww; val dT = c.y / hh; val dB = (hh - c.y) / hh
    return when (minOf(dL, dR, dT, dB)) {
        dL -> ToolbarDock.Left; dR -> ToolbarDock.Right; dT -> ToolbarDock.Top; else -> ToolbarDock.Bottom
    }
}

/** Where the layout pass put the rail (area px). Plain fields on purpose: read only when a drag starts / ends. */
private class RailGeom { var x = 0; var y = 0; var w = 0; var h = 0; var areaW = 1; var areaH = 1 }

/**
 * Brush / Deform / Bone / Transform / Layers rail, floating over the canvas area.
 * Drag the grip bar to move it: while dragging, the edge it will snap to lights up; on release it docks to
 * that edge (Left / Right = column, Top / Bottom = row) at the spot where you dropped it ([EditorState.railAlong]
 * is the rail's CENTRE as a 0..1 fraction of that edge, so there is no jump on drop). The Brush / Color / Layers
 * panels open on the inner side of the rail, whatever edge it is on.
 */
@Composable
fun FloatingToolRail(state: EditorState) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val dock = state.railDock
    val geom = remember { RailGeom() }

    var drag by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf(false) }
    var start by remember { mutableStateOf(Offset.Zero) }   // rail centre (area px) when the drag began
    val hot by remember {
        derivedStateOf { if (dragging) nearestDock(start + drag, geom.areaW.toFloat(), geom.areaH.toFloat()) else null }
    }

    // tick each time the drop target changes edge
    var lastHot by remember { mutableStateOf<ToolbarDock?>(null) }
    LaunchedEffect(hot) {
        val h = hot
        if (h != null && lastHot != null && h != lastHot) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        lastHot = h
    }

    fun finishDrag() {
        if (!dragging) return
        val c = start + drag
        val d = nearestDock(c, geom.areaW.toFloat(), geom.areaH.toFloat())
        state.railDock = d
        state.railAlong = (if (d.runsVertical) c.y / geom.areaH.coerceAtLeast(1) else c.x / geom.areaW.coerceAtLeast(1)).coerceIn(0f, 1f)
        dragging = false
        drag = Offset.Zero
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    val gripDrag = Modifier.pointerInput(Unit) {
        detectDragGestures(
            onDragStart = {
                start = Offset(geom.x + geom.w / 2f, geom.y + geom.h / 2f)
                drag = Offset.Zero
                dragging = true
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            },
            onDragEnd = { finishDrag() },
            onDragCancel = { finishDrag() },
            onDrag = { change, amount -> change.consume(); drag += amount },
        )
    }

    Box(Modifier.fillMaxSize()) {
        if (dragging) DockHint(hot, cs.primary)

        Layout(
            content = {
                RailBody(state, dock, dragging, { drag }, gripDrag)
                PanelHost(state, dock) { drag }
            },
            modifier = Modifier.fillMaxSize(),
        ) { measurables, constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val rail = measurables[0].measure(loose)
            val panel = measurables[1].measure(loose)
            val w = constraints.maxWidth
            val h = constraints.maxHeight
            val gap = 4.dp.roundToPx()
            val frac = state.railAlong

            fun along(total: Int, len: Int) =
                (frac * total - len / 2f).roundToInt().coerceIn(0, (total - len).coerceAtLeast(0))

            val rx = when (dock) {
                ToolbarDock.Left -> 0
                ToolbarDock.Right -> w - rail.width
                else -> along(w, rail.width)
            }
            val ry = when (dock) {
                ToolbarDock.Top -> 0
                ToolbarDock.Bottom -> h - rail.height
                else -> along(h, rail.height)
            }
            geom.x = rx; geom.y = ry; geom.w = rail.width; geom.h = rail.height
            geom.areaW = w; geom.areaH = h

            layout(w, h) {
                rail.place(rx, ry)
                // panel sits on the inner side of the rail, centred on it, kept inside the area
                val px = when (dock) {
                    ToolbarDock.Right -> rx - gap - panel.width
                    ToolbarDock.Left -> rx + rail.width + gap
                    else -> (rx + rail.width / 2 - panel.width / 2).coerceIn(0, (w - panel.width).coerceAtLeast(0))
                }
                val py = when (dock) {
                    ToolbarDock.Top -> ry + rail.height + gap
                    ToolbarDock.Bottom -> ry - gap - panel.height
                    else -> (ry + rail.height / 2 - panel.height / 2).coerceIn(0, (h - panel.height).coerceAtLeast(0))
                }
                panel.place(px, py)
            }
        }
    }
}

/** Thin bar along the edge the rail will snap to while it is being dragged. */
@Composable
private fun DockHint(dock: ToolbarDock?, color: Color) {
    Canvas(Modifier.fillMaxSize()) {
        val t = 6.dp.toPx()
        val m = 12.dp.toPx()
        val r = CornerRadius(t / 2f)
        val c = color.copy(alpha = 0.45f)
        when (dock) {
            ToolbarDock.Left -> drawRoundRect(c, Offset(0f, m), Size(t, size.height - 2 * m), r)
            ToolbarDock.Right -> drawRoundRect(c, Offset(size.width - t, m), Size(t, size.height - 2 * m), r)
            ToolbarDock.Top -> drawRoundRect(c, Offset(m, 0f), Size(size.width - 2 * m, t), r)
            ToolbarDock.Bottom -> drawRoundRect(c, Offset(m, size.height - t), Size(size.width - 2 * m, t), r)
            null -> Unit
        }
    }
}

@Composable
private fun RailBody(state: EditorState, dock: ToolbarDock, dragging: Boolean, drag: () -> Offset, grip: Modifier) {
    val cs = MaterialTheme.colorScheme
    val col = dock.runsVertical
    val r = 24.dp
    // flush against its edge: only the inner corners are round; free-floating while dragged
    val shape = if (dragging) RoundedCornerShape(r) else when (dock) {
        ToolbarDock.Right -> RoundedCornerShape(topStart = r, bottomStart = r)
        ToolbarDock.Left -> RoundedCornerShape(topEnd = r, bottomEnd = r)
        ToolbarDock.Top -> RoundedCornerShape(bottomStart = r, bottomEnd = r)
        ToolbarDock.Bottom -> RoundedCornerShape(topStart = r, topEnd = r)
    }
    Surface(
        modifier = Modifier.graphicsLayer {
            val d = drag()
            translationX = d.x
            translationY = d.y
            val s = if (dragging) 1.04f else 1f
            scaleX = s; scaleY = s
            alpha = if (dragging) 0.92f else 1f
        },
        shape = shape,
        color = cs.surfaceContainer.copy(alpha = UI_ALPHA),
        tonalElevation = 3.dp,
        shadowElevation = if (dragging) 14.dp else 4.dp,
    ) {
        val scroll = rememberScrollState()
        if (col) {
            Column(Modifier.width(48.dp).padding(bottom = 4.dp).animateContentSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                RailGrip(true, dragging, grip)
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(scroll),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { RailItems(state, true, dock) }
            }
        } else {
            Row(Modifier.height(48.dp).padding(end = 4.dp).animateContentSize(), verticalAlignment = Alignment.CenterVertically) {
                RailGrip(false, dragging, grip)
                Row(
                    Modifier.weight(1f, fill = false).horizontalScroll(scroll),
                    verticalAlignment = Alignment.CenterVertically,
                ) { RailItems(state, false, dock) }
            }
        }
    }
}

/** The hover bar: a pill across a column rail / along a row rail. [modifier] carries the drag gesture. */
@Composable
private fun RailGrip(col: Boolean, active: Boolean, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val tint by animateColorAsState(if (active) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.55f), label = "grip")
    Box(
        modifier
            .size(if (col) 48.dp else 22.dp, if (col) 22.dp else 48.dp)
            .semantics { contentDescription = "Drag to move the toolbar" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(if (col) 22.dp else 4.dp, if (col) 4.dp else 22.dp)
                .clip(CircleShape)
                .background(tint)
        )
    }
}

/** Row / column of the same children. */
@Composable
private fun Axis(col: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (col) {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
    } else {
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) { content() }
    }
}

/** [Brush, colour] - fold handle - [Deform, Bone, Transform, Layers]; same actions as the old side rail. */
@Composable
private fun RailItems(state: EditorState, col: Boolean, dock: ToolbarDock) {
    val cs = MaterialTheme.colorScheme
    val open = state.railOpen
    // chevron points at the edge the rail is docked to (= "tuck away"), flips when folded
    val base = when (dock) {
        ToolbarDock.Right -> 0f
        ToolbarDock.Bottom -> 90f
        ToolbarDock.Left -> 180f
        ToolbarDock.Top -> 270f
    }
    val arrow by animateFloatAsState(base + if (open) 0f else 180f, label = "arrow")

    AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut()) {
        Axis(col, if (col) Modifier.padding(bottom = 10.dp) else Modifier.padding(end = 10.dp)) {
            ToolBtn(state.brush.ico, state.brush.label, state.tool == Tool.Brush) {
                // 1st tap selects the tool, tap again opens the brush picker
                if (state.tool == Tool.Brush) {
                    state.panel = if (state.panel == Panel.Brush) Panel.None else Panel.Brush
                } else {
                    state.tool = Tool.Brush
                }
            }
            ColorSizeBtn(state)
        }
    }
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable {
                state.railOpen = !state.railOpen
                if (!state.railOpen) state.panel = Panel.None
            }
            .semantics { contentDescription = if (open) "Hide tools" else "Show tools" },
        contentAlignment = Alignment.Center,
    ) {
        FoxIcon(Ico.Chevron, Modifier.rotate(arrow), cs.onSurfaceVariant)
    }
    AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut()) {
        Axis(col, if (col) Modifier.padding(top = 10.dp) else Modifier.padding(start = 10.dp)) {
            ToolBtn(Ico.Deform, "Deform", state.tool == Tool.Deform) { if (state.tool == Tool.Deform) state.tool = Tool.Brush else state.selectRigTool(Tool.Deform) }
            ToolBtn(Ico.Bone, "Bone", state.tool == Tool.Bone) { if (state.tool == Tool.Bone) state.tool = Tool.Brush else state.selectRigTool(Tool.Bone) }
            ToolBtn(Ico.Transform, "Transform", state.transformOpen) {
                if (state.transformOpen) state.transformOpen = false else state.openTransform()
            }
            ToolBtn(Ico.Layers, "Layers", state.panel == Panel.Layers) {
                state.panel = if (state.panel == Panel.Layers) Panel.None else Panel.Layers
            }
        }
    }
}

/** Brush / Color / Layers panels, stacked in one box; they grow out of the rail on whichever side it is. */
@Composable
private fun PanelHost(state: EditorState, dock: ToolbarDock, drag: () -> Offset) {
    val origin = when (dock) {
        ToolbarDock.Right -> TransformOrigin(1f, 0.5f)
        ToolbarDock.Left -> TransformOrigin(0f, 0.5f)
        ToolbarDock.Top -> TransformOrigin(0.5f, 0f)
        ToolbarDock.Bottom -> TransformOrigin(0.5f, 1f)
    }
    val enter = fadeIn() + scaleIn(transformOrigin = origin)
    val exit = fadeOut() + scaleOut(transformOrigin = origin)
    // panels ride along while the rail is being dragged
    Box(Modifier.graphicsLayer { val d = drag(); translationX = d.x; translationY = d.y }) {
        AnimatedVisibility(state.panel == Panel.Brush, enter = enter, exit = exit) { BrushPanel(state) }
        AnimatedVisibility(state.panel == Panel.Color, enter = enter, exit = exit) { ColorPanel(state) }
        AnimatedVisibility(state.panel == Panel.Layers, enter = enter, exit = exit) { LayersPanel(state) }
    }
}
