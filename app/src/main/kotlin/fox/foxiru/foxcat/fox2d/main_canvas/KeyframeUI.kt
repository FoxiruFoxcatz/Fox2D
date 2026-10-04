package fox.foxiru.foxcat.fox2d.main_canvas

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fox.foxiru.foxcat.fox2d.jnicallers.AudioHandlerNative
import fox.foxiru.foxcat.fox2d.jnicallers.NativeCanvas
import fox.foxiru.foxcat.fox2d.timeline.AudioClip
import fox.foxiru.foxcat.fox2d.timeline.AudioKey
import fox.foxiru.foxcat.fox2d.timeline.TimelineState
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal val KeyGold = Color(0xFFFFD60A)

internal fun DrawScope.drawKeyMark(c: Offset, r: Float, hold: Boolean, selected: Boolean, fill: Color) {
    val col = if (selected) KeyGold else fill
    if (hold) {
        drawRect(col, Offset(c.x - r * 0.8f, c.y - r * 0.8f), Size(r * 1.6f, r * 1.6f))
    } else {
        val p = Path().apply {
            moveTo(c.x, c.y - r); lineTo(c.x + r, c.y); lineTo(c.x, c.y + r); lineTo(c.x - r, c.y); close()
        }
        drawPath(p, col)
    }
    if (selected) drawCircle(Color.Black.copy(alpha = 0.55f), r * 0.28f, c)
}

// ------------------------------------------------------------------------------------------------ graph zoom

/**
 * Zoom / pan window of a graph. [ox] / [oy] = top-left of the visible part, [zx] / [zy] = zoom, all as fractions of the
 * FULL data range (y counted from the TOP). Two fingers: each axis zooms with the finger spread along it, the centroid pans.
 */
@Stable
internal class GraphView(private val maxZoom: Float) {
    var zx by mutableFloatStateOf(1f)
    var zy by mutableFloatStateOf(1f)
    var ox by mutableFloatStateOf(0f)
    var oy by mutableFloatStateOf(0f)
    val zoomed: Boolean get() = zx > 1.001f || zy > 1.001f

    fun reset() { zx = 1f; zy = 1f; ox = 0f; oy = 0f }

    /** [ax] / [ay] = anchor inside the plot (0..1, y from the top); the data under it stays under the fingers. */
    fun zoomAt(ax: Float, ay: Float, fx: Float, fy: Float) {
        val ux = ox + ax / zx
        val uy = oy + ay / zy
        zx = (zx * fx).coerceIn(1f, maxZoom)
        zy = (zy * fy).coerceIn(1f, maxZoom)
        ox = (ux - ax / zx).coerceIn(0f, 1f - 1f / zx)
        oy = (uy - ay / zy).coerceIn(0f, 1f - 1f / zy)
    }

    /** [dx] / [dy] = finger movement as a fraction of the plot size. */
    fun panBy(dx: Float, dy: Float) {
        ox = (ox - dx / zx).coerceIn(0f, 1f - 1f / zx)
        oy = (oy - dy / zy).coerceIn(0f, 1f - 1f / zy)
    }

    /** One step of a 2+ finger gesture. A spread under [minSpread] px on an axis leaves that axis alone. */
    fun pinch(ev: PointerEvent, pad: Float, plotW: Float, plotH: Float, minSpread: Float) {
        val p = ev.changes.filter { it.pressed }
        if (p.size < 2) return
        val a = p[0]
        val b = p[1]
        fun ratio(cur: Float, prev: Float) = if (cur > minSpread && prev > minSpread) cur / prev else 1f
        val fx = ratio(abs(a.position.x - b.position.x), abs(a.previousPosition.x - b.previousPosition.x))
        val fy = ratio(abs(a.position.y - b.position.y), abs(a.previousPosition.y - b.previousPosition.y))
        val cen = ev.calculateCentroid(useCurrent = false)
        val pan = ev.calculatePan()
        zoomAt(((cen.x - pad) / plotW).coerceIn(0f, 1f), ((cen.y - pad) / plotH).coerceIn(0f, 1f), fx, fy)
        panBy(pan.x / plotW, pan.y / plotH)
    }
}

/** "Fit x3.2" chip, only while zoomed; tap = back to the whole graph. */
@Composable
private fun ZoomBadge(view: GraphView, modifier: Modifier) {
    if (!view.zoomed) return
    val cs = MaterialTheme.colorScheme
    Text(
        "Fit  \u00D7${"%.1f".format(max(view.zx, view.zy))}",
        modifier
            .padding(6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(cs.secondaryContainer)
            .clickable { view.reset() }
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        color = cs.onSecondaryContainer,
    )
}

// ------------------------------------------------------------------------------------------------ icon

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun KeyIcon(state: EditorState, prop: PropRef, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val st = state.keyState(prop)
    val open = state.kfOpen == prop
    Box(
        modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(if (open) cs.primaryContainer else Color.Transparent)
            .combinedClickable(
                onClick = { state.toggleDrawer(prop) },
                onLongClick = {
                    if (enabled) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        state.playing = false
                        state.toggleKey(prop)
                    }
                },
            )
            .semantics { contentDescription = "Keyframes" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val c = center
            val r = size.minDimension / 2f - 1.dp.toPx()
            val d = Path().apply { moveTo(c.x, c.y - r); lineTo(c.x + r, c.y); lineTo(c.x, c.y + r); lineTo(c.x - r, c.y); close() }
            when (st) {
                2 -> { drawPath(d, KeyGold); drawCircle(Color.Black.copy(alpha = 0.5f), r * 0.22f, c) }
                1 -> drawPath(d, KeyGold, style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
                else -> drawPath(d, cs.onSurfaceVariant, style = Stroke(1.5f.dp.toPx(), join = StrokeJoin.Round))
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ drawing drawer

@Composable
internal fun KeyDrawer(state: EditorState, prop: PropRef) {
    AnimatedVisibility(
        state.kfOpen == prop,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) { DrawerBody(state, prop) }
}

@Composable
private fun DrawerBody(state: EditorState, prop: PropRef) {
    val cs = MaterialTheme.colorScheme
    val lane = state.propLane(prop)
    val local = state.localFrame(state.propTrack(prop))
    val keyHere = lane.any { it.frame == local }
    val locked = state.drawTracks.firstOrNull { it.id == state.propTrack(prop) }?.locked == true

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(cs.surfaceContainerHighest.copy(alpha = 0.7f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { state.jumpKey(prop, -1) }, enabled = lane.any { it.frame < local }) { Text("\u2039 Key") }
            FilledTonalButton(onClick = { if (keyHere) state.removeKey(prop) else state.addKey(prop) }, enabled = !locked) {
                Text(if (keyHere) "Remove key" else "Add key")
            }
            TextButton(onClick = { state.jumpKey(prop, 1) }, enabled = lane.any { it.frame > local }) { Text("Key \u203A") }
        }
        Text(
            if (lane.isEmpty()) "${state.propLabel(prop)} is not animated \u00B7 add a key here, move the playhead, change the slider"
            else "${state.propLabel(prop)} \u00B7 frame ${state.frame + 1} \u00B7 ${lane.size} key${if (lane.size == 1) "" else "s"} \u00B7 slider edits key this frame",
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
        )
        if (lane.isNotEmpty()) EasingEditor(state, prop)
    }
}

/** Easing of the segment leaving the selected key (or the key before the playhead) of ONE property. */
@Composable
private fun EasingEditor(state: EditorState, prop: PropRef) {
    val cs = MaterialTheme.colorScheme
    val key = state.propSegmentKey(prop) ?: return
    val last = state.propLane(prop).lastOrNull()?.frame == key.frame
    val e = key.ease

    fun pick(n: Easing) {
        state.edit { state.propSetEase(prop, key.frame, n) }
        state.defaultEase = n
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            if (last) "Easing \u00B7 key at frame ${key.frame + 1} is the last one, so nothing follows it"
            else "Easing \u00B7 frame ${key.frame + 1} \u2192 next key (${e.label})",
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = e.isHold, onClick = { pick(Easing.Hold) }, label = { Text("Hold") })
            FilterChip(selected = e.isLinear, onClick = { pick(Easing.Linear) }, label = { Text("Linear") })
            for (f in EaseFamily.values()) {
                FilterChip(selected = e.family == f, onClick = { pick(Easing.of(f, e.dir ?: EaseDir.InOut)) }, label = { Text(f.label) })
            }
            FilterChip(selected = e.isBezier, onClick = { pick(e.copy(id = Easing.BEZIER)) }, label = { Text("Bezier") })
        }
        if (e.family != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (d in EaseDir.values()) {
                    FilterChip(selected = e.dir == d, onClick = { pick(Easing.of(e.family!!, d)) }, label = { Text(d.label) })
                }
            }
        }
        EaseCurve(state, prop, key.frame, e)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { state.propSetEaseAll(prop, e) }) { Text("Use for all keys") }
            TextButton(onClick = { state.clearKeys(prop) }) { Text("Clear keys") }
        }
    }
}

/**
 * Easing GRAPH: the curve of the segment (progress 0 -> 1 against time between the two keys). The dot is the playhead,
 * so scrubbing shows where on the curve the value is. With Bezier selected, drag its two handles.
 * Two fingers zoom / pan it (see [GraphView]); the "Fit" chip puts it back.
 */
@Composable
private fun EaseCurve(state: EditorState, prop: PropRef, keyFrame: Int, e: Easing) {
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val samples = remember(e) { NativeCanvas.easeCurve(e.id, e.x1, e.y1, e.x2, e.y2, 256) }
    val view = remember(prop, keyFrame) { GraphView(6f) }
    // fixed range while editing a bezier so the handles do not slide under the finger
    val lo = if (e.isBezier) -0.5f else min(0f, samples.min()) - 0.05f
    val hi = if (e.isBezier) 1.5f else max(1f, samples.max()) + 0.05f

    // where the playhead sits between this key and the next one (0..1), -1 = not inside the segment
    val lane = state.propLane(prop)
    val next = lane.firstOrNull { it.frame > keyFrame }
    val local = state.localFrame(state.propTrack(prop))
    val prog = if (next != null && local in keyFrame..next.frame && next.frame > keyFrame) (local - keyFrame).toFloat() / (next.frame - keyFrame) else -1f

    Box(Modifier.fillMaxWidth()) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(112.dp)
            .pointerInput(prop, keyFrame, e.isBezier) {
                val pad = 12.dp.toPx()
                val reach = 40.dp.toPx()
                val minSpread = 28.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = size.width - 2 * pad
                    val h = size.height - 2 * pad
                    fun px(x: Float) = pad + (x - view.ox) * view.zx * w
                    fun py(y: Float) = pad + (((hi - y) / (hi - lo)) - view.oy) * view.zy * h
                    var handle = 0   // 0 = none, 1 / 2 = bezier handle being dragged
                    val cur = state.propKeyOf(prop, keyFrame)?.ease
                    if (e.isBezier && cur != null) {
                        val d1 = (down.position - Offset(px(cur.x1), py(cur.y1))).getDistance()
                        val d2 = (down.position - Offset(px(cur.x2), py(cur.y2))).getDistance()
                        if (minOf(d1, d2) <= reach) { handle = if (d1 <= d2) 1 else 2; down.consume() }
                    }
                    val before = if (handle != 0) state.snapshot() else null
                    var multi = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            multi = true
                            view.pinch(ev, pad, w, h, minSpread)
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                            continue
                        }
                        if (multi) { ev.changes.forEach { if (it.positionChanged()) it.consume() }; continue }
                        if (handle == 0) {
                            if (ev.changes.any { it.isConsumed }) break   // the scroll column took the gesture
                            continue
                        }
                        val c = pressed[0]
                        val x = (view.ox + ((c.position.x - pad) / w) / view.zx).coerceIn(0f, 1f)
                        val y = (hi - (view.oy + ((c.position.y - pad) / h) / view.zy) * (hi - lo)).coerceIn(-0.5f, 1.5f)
                        val now = state.propKeyOf(prop, keyFrame)?.ease ?: break
                        state.propSetEase(prop, keyFrame, if (handle == 1) now.copy(x1 = x, y1 = y) else now.copy(x2 = x, y2 = y))
                        c.consume()
                    }
                    if (before != null) {
                        state.commitEdit(before)
                        state.defaultEase = state.propKeyOf(prop, keyFrame)?.ease ?: state.defaultEase
                    }
                }
            }
    ) {
        val pad = 12.dp.toPx()
        val w = size.width - 2 * pad
        val h = size.height - 2 * pad
        val ox = view.ox; val oy = view.oy; val zx = view.zx; val zy = view.zy
        fun px(x: Float) = pad + (x - ox) * zx * w
        fun py(y: Float) = pad + (((hi - y) / (hi - lo)) - oy) * zy * h

        drawRoundRect(cs.surfaceContainerHigh, size = size, cornerRadius = CornerRadius(10.dp.toPx()))
        clipRect {
            val guide = cs.outline.copy(alpha = 0.45f)
            drawLine(guide, Offset(px(0f), py(0f)), Offset(px(1f), py(0f)), 1.dp.toPx())
            drawLine(guide, Offset(px(0f), py(1f)), Offset(px(1f), py(1f)), 1.dp.toPx())
            drawLine(guide.copy(alpha = 0.25f), Offset(px(0.5f), py(lo)), Offset(px(0.5f), py(hi)), 1.dp.toPx())
            val lbl = TextStyle(fontSize = 9.sp, color = cs.onSurfaceVariant)
            drawText(measurer, "next key", Offset(px(1f) - 38.dp.toPx(), py(1f) - 13.dp.toPx()), style = lbl)
            drawText(measurer, "this key", Offset(px(0f) + 2.dp.toPx(), py(0f) + 1.dp.toPx()), style = lbl)

            val path = Path()
            if (e.isHold) {
                path.moveTo(px(0f), py(0f)); path.lineTo(px(1f), py(0f)); path.lineTo(px(1f), py(1f))
            } else {
                for (i in samples.indices) {
                    val x = px(i / (samples.size - 1f))
                    val y = py(samples[i])
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
            }
            drawPath(path, cs.primary, style = Stroke(2.5f.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

            if (prog >= 0f) {
                val v = if (e.isHold) (if (prog >= 1f) 1f else 0f) else samples[(prog * (samples.size - 1)).roundToInt().coerceIn(0, samples.size - 1)]
                drawLine(KeyGold.copy(alpha = 0.5f), Offset(px(prog), py(lo)), Offset(px(prog), py(hi)), 1.dp.toPx())
                drawCircle(KeyGold, 5.dp.toPx(), Offset(px(prog), py(v)))
            }

            if (e.isBezier) {
                val p0 = Offset(px(0f), py(0f)); val p1 = Offset(px(1f), py(1f))
                val h1 = Offset(px(e.x1), py(e.y1)); val h2 = Offset(px(e.x2), py(e.y2))
                drawLine(cs.tertiary, p0, h1, 1.5f.dp.toPx())
                drawLine(cs.tertiary, p1, h2, 1.5f.dp.toPx())
                drawCircle(cs.tertiary, 7.dp.toPx(), h1)
                drawCircle(cs.tertiary, 7.dp.toPx(), h2)
            }
        }
    }
    ZoomBadge(view, Modifier.align(Alignment.TopEnd))
    }
}

// ------------------------------------------------------------------------------------------------ sticky ruler

/**
 * Pinned at the top of the Transform popup (outside its scroll area): transport + a ruler of the active row.
 * One cell per frame; marks are the keys of the OPEN property (gold) or of every property (when no drawer is open).
 * Tap = playhead, drag a mark = retime it (one undo step), drag the number band = scrub, other drags pan the strip.
 */
@Composable
internal fun StickyKeyBar(state: EditorState) {
    val cs = MaterialTheme.colorScheme
    val track = state.activeTrack
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { state.jumpMark(-1) }, enabled = state.marksAround(track, -1) != null, modifier = Modifier.height(34.dp)) { Text("\u2039 Key") }
            FilledTonalIconButton(onClick = { state.step(-1) }, modifier = Modifier.size(36.dp)) { FoxIcon(Ico.Prev) }
            FilledIconButton(onClick = { state.playing = !state.playing }, modifier = Modifier.size(40.dp)) { FoxIcon(if (state.playing) Ico.Pause else Ico.Play) }
            FilledTonalIconButton(onClick = { state.step(1) }, modifier = Modifier.size(36.dp)) { FoxIcon(Ico.Next) }
            TextButton(onClick = { state.jumpMark(1) }, enabled = state.marksAround(track, 1) != null, modifier = Modifier.height(34.dp)) { Text("Key \u203A") }
        }
        AnimatedVisibility(state.keyRulerShown, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) { KeyRuler(state) }
        val openName = state.openLabel(track)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (openName != null) "Frame ${state.frame + 1} \u00B7 $openName keys"
                else "Frame ${state.frame + 1} \u00B7 tap \u25C6 by a slider to key it",
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
            IconButton(onClick = { state.keyRulerShown = !state.keyRulerShown }, modifier = Modifier.size(28.dp)) {
                FoxIcon(if (state.keyRulerShown) Ico.Eye else Ico.EyeOff, iconSize = 18.dp)
            }
        }
    }
}

@Composable
internal fun KeyRuler(state: EditorState) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val track = state.activeTrack
    val span = state.trackSpan(track).coerceAtLeast(1)
    val off = state.trackOffset(track)
    val scroll = rememberScrollState()
    val minCell = with(density) { 16.dp.toPx() }     // default cell width on a long track
    val maxCell = with(density) { 64.dp.toPx() }     // deepest zoom
    val reach = with(density) { 16.dp.toPx() }       // touch radius of a mark
    val keys = state.allTrackKeys(track)
    val open = state.openLaneKeys(track)   // keys of the open drawer's property on this row, null = no drawer
    val laneFrames = open ?: emptyList()
    val allFrames = remember(keys) { keys.map { it.frame }.distinct().sorted() }
    val local = state.localFrame(track)
    val cuts = HashSet<Int>().also { s -> var a = 0; for (c in state.trackCels(track)) { s.add(a); a += c.len } }

    // zoom: null = default (16dp per frame, or fit-to-width on a short track); a pinch stores an explicit cell width.
    // wantScroll = scroll offset to apply once the resized content has been laid out (-1 = none).
    var userFw by remember(track) { mutableStateOf<Float?>(null) }
    var wantScroll by remember { mutableFloatStateOf(-1f) }

    BoxWithConstraints(Modifier.fillMaxWidth().height(54.dp)) {
        val availPx = with(density) { maxWidth.toPx() }
        val fit = availPx / span                                   // whole track exactly fills the strip
        val lo = fit                                               // cannot zoom out past "whole track visible"
        val hi = max(maxCell, fit)
        val defFw = max(minCell, fit)
        val fw = (userFw ?: defFw).coerceIn(lo, hi)
        val contentW = fw * span

        val loS by rememberUpdatedState(lo)
        val hiS by rememberUpdatedState(hi)
        val defS by rememberUpdatedState(defFw)
        val availS by rememberUpdatedState(availPx)

        // keep the playhead cell on screen (playback / key jumps); NOT on zoom, the pinch anchors the scroll itself
        LaunchedEffect(local) {
            val x = (local + 0.5f) * fw
            if (x < scroll.value || x > scroll.value + availPx) scroll.scrollTo((x - availPx / 2f).toInt().coerceAtLeast(0))
        }
        // zoom / chip: scroll after the new content width is measured, otherwise the ScrollState clamps to the old max
        LaunchedEffect(fw, wantScroll) {
            if (wantScroll >= 0f) {
                withFrameNanos { }
                scroll.scrollTo(wantScroll.toInt().coerceAtLeast(0))
                wantScroll = -1f
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                // two-finger pinch: Initial pass, so it wins over the strip's scroll and the canvas gestures.
                // One finger is never consumed here. The content point under the previous centroid follows the new centroid.
                .pointerInput(track, span) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        do {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            if (ev.changes.count { it.pressed } >= 2) {
                                val z = ev.calculateZoom()
                                val c0 = ev.calculateCentroid(useCurrent = false).x
                                val c1 = ev.calculateCentroid(useCurrent = true).x
                                val old = (userFw ?: defS).coerceIn(loS, hiS)
                                val nw = (old * z).coerceIn(loS, hiS)
                                val sx = if (wantScroll >= 0f) wantScroll else scroll.value.toFloat()
                                userFw = nw
                                wantScroll = ((sx + c0) / old * nw - c1).coerceIn(0f, max(0f, nw * span - availS))
                                ev.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (ev.changes.any { it.pressed })
                    }
                }
                .horizontalScroll(scroll)
        ) {
            Canvas(
                Modifier
                    .width(with(density) { contentW.toDp() })
                    .fillMaxHeight()
                    .pointerInput(track, span, fw, open) {
                        val band = 18.dp.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val fr0 = floor(down.position.x / fw).toInt().coerceIn(0, span - 1)
                            val marks = state.openLaneKeys(track)?.map { it.frame } ?: state.allTrackKeys(track).map { it.frame }.distinct()
                            // nearest mark within the touch radius (a cell can be far narrower than a finger when zoomed out)
                            val hitMark = marks.minByOrNull { abs((it + 0.5f) * fw - down.position.x) }
                                ?.takeIf { abs((it + 0.5f) * fw - down.position.x) <= max(fw / 2f, reach) }
                            val locked = state.drawTracks.firstOrNull { it.id == track }?.locked == true
                            if (down.position.y < band) {
                                // number band: scrub
                                down.consume()
                                state.playing = false
                                state.seek(off + fr0)
                                drag(down.id) { c -> state.seek(off + floor(c.position.x / fw).toInt().coerceIn(0, span - 1)); c.consume() }
                            } else if (hitMark != null && !locked) {
                                down.consume()
                                state.playing = false
                                state.selectedKey = hitMark
                                state.seek(off + hitMark)
                                val before = state.snapshot()
                                var cur: Int = hitMark
                                var moved = false
                                drag(down.id) { c ->
                                    val to = floor(c.position.x / fw).toInt()
                                    if (to != cur) {
                                        val r = state.moveKeyFor(track, cur, to, state.kfOpen)
                                        if (r != cur) { cur = r; moved = true; state.seek(off + cur) }
                                    }
                                    c.consume()
                                }
                                if (moved) state.commitEdit(before)
                            } else {
                                // empty cell: a tap moves the playhead; a horizontal drag is left to the strip's scroll
                                val up = waitForUpOrCancellation()
                                if (up != null) {
                                    state.playing = false
                                    state.selectedKey = -1
                                    state.seek(off + fr0)
                                }
                            }
                        }
                    }
            ) {
                val h = size.height
                val band = 18.dp.toPx()
                drawRoundRect(cs.surfaceContainerHighest, size = size, cornerRadius = CornerRadius(8.dp.toPx()))
                if (local in 0 until span) drawRect(cs.primary.copy(alpha = 0.22f), Offset(local * fw, 0f), Size(fw, h))

                // only the visible frames get ticks / labels, so a 5000-frame track costs the same as a 50-frame one
                val sx = scroll.value.toFloat()
                val f0 = floor(sx / fw).toInt().coerceIn(0, span)
                val f1 = ceil((sx + availPx) / fw).toInt().coerceIn(0, span)
                val labelEvery = listOf(1, 2, 5, 10, 20, 50, 100, 200, 500, 1000).firstOrNull { it * fw >= 34.dp.toPx() } ?: 2000
                val tickEvery = listOf(1, 2, 5, 10, 20, 50, 100, 200, 500).firstOrNull { it * fw >= 6.dp.toPx() } ?: 1000
                val lbl = TextStyle(fontSize = 9.sp, color = cs.onSurfaceVariant)
                for (f in f0..f1) {
                    val x = f * fw
                    if (f in cuts) drawLine(cs.outline.copy(alpha = 0.7f), Offset(x, 0f), Offset(x, h), 1.5f.dp.toPx())
                    else if (f % tickEvery == 0) drawLine(cs.outline.copy(alpha = 0.25f), Offset(x, h * 0.66f), Offset(x, h), 1.dp.toPx())
                    if (f < span && f % labelEvery == 0) drawText(measurer, "${f + 1}", Offset(x + 2.dp.toPx(), 1.dp.toPx()), style = lbl)
                }
                drawLine(cs.outline.copy(alpha = 0.3f), Offset(0f, band), Offset(size.width, band), 1.dp.toPx())

                val cy = band + (h - band) * 0.42f
                if (open != null) {
                    // the open property: gold span + marks; other properties as quiet ticks underneath
                    val laneSet = laneFrames.mapTo(HashSet()) { it.frame }
                    for (f in allFrames) if (f !in laneSet) {
                        drawCircle(cs.outline, 2.5f.dp.toPx(), Offset((f + 0.5f) * fw, h - 7.dp.toPx()))
                    }
                    for (i in 0 until laneFrames.size - 1) {
                        val a = laneFrames[i]
                        drawLine(
                            if (a.ease.isHold) cs.outline else KeyGold.copy(alpha = 0.6f),
                            Offset((a.frame + 0.5f) * fw, cy), Offset((laneFrames[i + 1].frame + 0.5f) * fw, cy), 2.dp.toPx(), StrokeCap.Round,
                        )
                    }
                    for (k in laneFrames) drawKeyMark(Offset((k.frame + 0.5f) * fw, cy), 7.dp.toPx(), k.ease.isHold, k.frame == state.selectedKey, cs.tertiary)
                } else {
                    for (i in 0 until allFrames.size - 1) {
                        drawLine(KeyGold.copy(alpha = 0.5f), Offset((allFrames[i] + 0.5f) * fw, cy), Offset((allFrames[i + 1] + 0.5f) * fw, cy), 2.dp.toPx(), StrokeCap.Round)
                    }
                    for (f in allFrames) {
                        val hold = keys.filter { it.frame == f }.all { it.ease.isHold }
                        drawKeyMark(Offset((f + 0.5f) * fw, cy), 7.dp.toPx(), hold, f == state.selectedKey, cs.tertiary)
                    }
                }
                if (local in 0 until span) {
                    val x = (local + 0.5f) * fw
                    drawLine(cs.onSurface, Offset(x, 0f), Offset(x, h), 2.dp.toPx())
                }
            }
        }

        // "Fit all" (whole track in view) / "Reset" (back to the default cell width); only on a long or a zoomed strip
        if (userFw != null || fit < minCell) {
            val fitted = fw <= fit * 1.01f
            Text(
                if (fitted) "Reset" else "Fit all",
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 20.dp, end = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(cs.secondaryContainer.copy(alpha = 0.92f))
                    .clickable {
                        if (fitted) { userFw = null; wantScroll = ((local + 0.5f) * minCell - availPx / 2f).coerceAtLeast(0f) }
                        else { userFw = fit; wantScroll = 0f }
                    }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSecondaryContainer,
            )
        }
    }
}

// ------------------------------------------------------------------------------------------------ audio drawer

/** Expands under the selected clip's volume slider while its [KeyIcon] is open. */
@Composable
internal fun AudioKeyDrawer(state: EditorState, prop: PropRef.AudioGain) {
    val tl = state.timeline
    val clip = tl.clip(prop.clip)
    AnimatedVisibility(
        state.kfOpen == prop && clip != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        val c = tl.clip(prop.clip)
        if (c != null) AudioDrawerBody(state, prop, c)
    }
}

@Composable
private fun AudioDrawerBody(state: EditorState, prop: PropRef.AudioGain, c: AudioClip) {
    val cs = MaterialTheme.colorScheme
    val tl = state.timeline
    val locked = tl.tracks.firstOrNull { it.id == c.track }?.locked == true
    val keyHere = state.keyState(prop) == 2
    val playMs = state.frameToMs(state.frame)
    val src = tl.srcMsAt(c, playMs)
    val tolMs = 1000L / state.fps / 2
    val inside = playMs in c.startMs..(c.startMs + c.lenMs)

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(cs.surfaceContainerHighest.copy(alpha = 0.7f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { state.jumpKey(prop, -1) }, enabled = c.gainKeys.any { it.ms < src - tolMs }) { Text("\u2039 Key") }
            FilledTonalButton(onClick = { if (keyHere) state.removeKey(prop) else state.addKey(prop) }, enabled = !locked && inside) {
                Text(if (keyHere) "Remove key" else "Add key")
            }
            TextButton(onClick = { state.jumpKey(prop, 1) }, enabled = c.gainKeys.any { it.ms > src + tolMs }) { Text("Key \u203A") }
            TextButton(onClick = { state.clearKeys(prop) }, enabled = c.gainKeys.isNotEmpty() && !locked) { Text("Clear") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (c.gainKeys.isEmpty()) "Volume is constant \u00B7 add a key at the playhead, then move it and add more"
                else "Smooth volume curve \u00B7 drag a key \u00B7 drag empty space to scrub \u00B7 pinch to zoom",
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
            Text("${(tl.gainAt(c, playMs) * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge, color = cs.primary)
        }
        EnvelopeGraph(state, c.id)
    }
}

/**
 * Volume graph of one clip over its visible length. The line is the C++ spline (monotone cubic Hermite): smooth, no
 * polygon corners, never overshoots a key. The vertical line is the playhead (it stays in view while you edit).
 * Pinch to zoom (spread along an axis zooms that axis), two fingers pan; the spline is RE-SAMPLED over the visible
 * slice, so it stays smooth at any zoom.
 */
@Composable
private fun EnvelopeGraph(state: EditorState, clipId: Int) {
    val tl = state.timeline
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val buf = remember { FloatArray(240) }
    val maxG = TimelineState.MAX_GAIN
    val view = remember(clipId) { GraphView(60f) }

    Box(Modifier.fillMaxWidth()) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(128.dp)
            .pointerInput(clipId) {
                val pad = 12.dp.toPx()
                val reach = 28.dp.toPx()
                val minSpread = 28.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val c0 = tl.clip(clipId) ?: return@awaitEachGesture
                    val locked = tl.tracks.firstOrNull { it.id == c0.track }?.locked == true
                    val w = size.width - 2 * pad
                    val h = size.height - 2 * pad
                    fun xOf(ms: Long) = pad + ((ms - c0.inMs).toFloat() / c0.lenMs.coerceAtLeast(1L) - view.ox) * view.zx * w
                    fun yOf(g: Float) = pad + ((1f - g / maxG) - view.oy) * view.zy * h
                    fun dist(k: AudioKey) = (down.position - Offset(xOf(k.ms), yOf(k.gain))).getDistance()
                    val hit = c0.gainKeys.minByOrNull { dist(it) }?.takeIf { dist(it) < reach }
                    state.playing = false
                    val dragKey = hit != null && !locked
                    val before = if (dragKey) state.snapshot() else null
                    var cur = hit?.ms ?: 0L
                    var moved = false
                    var multi = false

                    fun seekTo(x: Float) {
                        val cc = tl.clip(clipId) ?: return
                        val f = (view.ox + ((x - pad) / w) / view.zx).coerceIn(0f, 1f)
                        state.seek(state.msToFrame(cc.startMs + (f * cc.lenMs).toLong()).coerceAtLeast(0))
                    }

                    down.consume()
                    if (!dragKey) seekTo(down.position.x)   // empty space: scrub the playhead along the clip
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            multi = true
                            view.pinch(ev, pad, w, h, minSpread)
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                            continue
                        }
                        if (multi) { ev.changes.forEach { if (it.positionChanged()) it.consume() }; continue }
                        val ch = pressed[0]
                        if (dragKey) {
                            val cc = tl.clip(clipId) ?: break
                            val fx = (view.ox + ((ch.position.x - pad) / w) / view.zx).coerceIn(0f, 1f)
                            val ms = cc.inMs + (fx * cc.lenMs).toLong()
                            val fy = view.oy + ((ch.position.y - pad) / h) / view.zy
                            // volume snaps to every 5% (finer once zoomed in vertically), so 100% is easy to hit
                            val q = if (view.zy >= 6f) 100f else if (view.zy >= 2.5f) 40f else 20f
                            val g = ((maxG * (1f - fy)).coerceIn(0f, maxG) * q).roundToInt() / q
                            cur = tl.moveGainKey(clipId, cur, ms, g)
                            moved = true
                        } else {
                            seekTo(ch.position.x)
                        }
                        ch.consume()
                    }
                    if (moved && before != null) state.commitEdit(before)
                }
            }
    ) {
        val c = tl.clip(clipId) ?: return@Canvas
        val pad = 12.dp.toPx()
        val w = size.width - 2 * pad
        val h = size.height - 2 * pad
        val len = c.lenMs.coerceAtLeast(1L)
        val ox = view.ox; val oy = view.oy; val zx = view.zx; val zy = view.zy
        fun xOfRel(rel: Float) = pad + (rel / len - ox) * zx * w          // rel = ms from the clip's visible start
        fun xOf(ms: Long) = xOfRel((ms - c.inMs).toFloat())
        fun yOf(g: Float) = pad + ((1f - g / maxG) - oy) * zy * h

        drawRoundRect(cs.surfaceContainerHigh, size = size, cornerRadius = CornerRadius(10.dp.toPx()))
        clipRect {
            val lbl = TextStyle(fontSize = 9.sp, color = cs.onSurfaceVariant)
            val guide = cs.outline.copy(alpha = 0.4f)

            // gain grid: the step follows the zoom, 100% is always a bit stronger
            val visG = maxG / zy
            val gTop = maxG * (1f - oy)
            val gStep = listOf(0.01f, 0.025f, 0.05f, 0.1f, 0.25f, 0.5f).firstOrNull { it / visG * h >= 26.dp.toPx() } ?: 1f
            var gi = ceil((gTop - visG) / gStep).toInt()
            while (gi * gStep <= gTop + 1e-4f) {
                val gv = gi * gStep
                if (gv in 0f..maxG) {
                    val y = yOf(gv)
                    drawLine(if (abs(gv - 1f) < 1e-4f) guide.copy(alpha = 0.8f) else guide, Offset(pad, y), Offset(pad + w, y), 1.dp.toPx())
                    drawText(measurer, "${(gv * 100).roundToInt()}%", Offset(pad + 2.dp.toPx(), y - 11.dp.toPx()), style = lbl)
                }
                gi++
            }

            // seconds along the bottom: the graph's own ruler (follows the zoom)
            val visSecs = len / 1000f / zx
            val t0 = ox * len / 1000f
            val stepS = listOf(0.01f, 0.02f, 0.05f, 0.1f, 0.25f, 0.5f, 1f, 2f, 5f, 10f, 30f, 60f).firstOrNull { it / visSecs * w >= 44.dp.toPx() } ?: 120f
            var ti = ceil(t0 / stepS).toInt()
            while (ti * stepS <= t0 + visSecs + 1e-3f) {
                val ts = ti * stepS
                val x = xOfRel(ts * 1000f)
                drawLine(guide, Offset(x, pad + h - 5.dp.toPx()), Offset(x, pad + h), 1.dp.toPx())
                drawText(measurer, if (stepS < 1f) "%.2fs".format(ts) else "${ts.roundToInt()}s", Offset(x + 2.dp.toPx(), pad + h - 13.dp.toPx()), style = lbl)
                ti++
            }

            // the curve: sampled from C++ (the mixer's own spline) over the VISIBLE slice, 240 points -> no visible corners
            val base = yOf(0f)
            val curve = Path()
            val fill = Path()
            if (c.gainKeys.isEmpty()) {
                val y = yOf(c.gain)
                curve.moveTo(pad, y); curve.lineTo(pad + w, y)
                fill.moveTo(pad, base); fill.lineTo(pad, y); fill.lineTo(pad + w, y); fill.lineTo(pad + w, base); fill.close()
            } else {
                val fromMs = c.inMs + ox * len
                val toMs = fromMs + len / zx
                AudioHandlerNative.envelope(tl.packGainKeys(c.gainKeys), c.gainKeys.size, fromMs.toDouble(), toMs.toDouble(), buf)
                fill.moveTo(pad, base)
                for (i in buf.indices) {
                    val x = pad + i / (buf.size - 1f) * w
                    val y = yOf(buf[i])
                    if (i == 0) curve.moveTo(x, y) else curve.lineTo(x, y)
                    fill.lineTo(x, y)
                }
                fill.lineTo(pad + w, base); fill.close()
            }
            drawPath(fill, cs.primary.copy(alpha = 0.14f))
            drawPath(curve, cs.primary, style = Stroke(2.8f.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

            // playhead line (+ dot on the curve)
            val playMs = state.frameToMs(state.frame)
            if (playMs in c.startMs..(c.startMs + c.lenMs)) {
                val px = xOfRel((playMs - c.startMs).toFloat())
                drawLine(cs.onSurface, Offset(px, pad), Offset(px, pad + h), 2.dp.toPx())
                drawCircle(cs.onSurface, 4.dp.toPx(), Offset(px, yOf(tl.gainAt(c, playMs))))
            }

            val tol = 1000L / state.fps / 2
            for (k in c.gainKeys) {
                if (k.ms < c.inMs || k.ms > c.inMs + c.lenMs) continue
                val at = Offset(xOf(k.ms), yOf(k.gain))
                val here = tl.gainKeyAt(c, playMs, tol) === k
                drawCircle(cs.surface, 9.dp.toPx(), at)
                drawCircle(if (here) KeyGold else cs.tertiary, 7.dp.toPx(), at)
            }
        }
    }
    ZoomBadge(view, Modifier.align(Alignment.TopEnd))
    }
}
