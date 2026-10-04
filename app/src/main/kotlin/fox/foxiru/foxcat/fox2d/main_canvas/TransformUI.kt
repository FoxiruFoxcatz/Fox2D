package fox.foxiru.foxcat.fox2d.main_canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// ======================================================================================================
// Whole-layer transform (drawing row): move / rotate / resize.
//   - TransformOverlay : box + handles on the canvas, one finger (handles / drag) or two fingers (pinch, twist)
//   - TransformPanel   : popup that replaces the player controls + timeline while Transform is open
// Everything is in paper units (paper = 0..1, y down); the native canvas and the video export apply the same maths.
// ======================================================================================================

/** Paper (0..1) <-> screen px of the GL canvas. Mirrors quadMVP() in fox_canvas.cpp. */
internal class PaperMap(
    private val w: Float, private val h: Float,
    private val scale: Float, rotDeg: Float, private val ox: Float, private val oy: Float,
) {
    private val side = minOf(w, h)
    private val c = cos(rotDeg * (PI / 180.0)).toFloat()
    private val s = sin(rotDeg * (PI / 180.0)).toFloat()

    fun toScreen(p: Offset): Offset {
        val lx = (p.x - 0.5f) * side * scale
        val ly = (p.y - 0.5f) * side * scale
        return Offset(w / 2f + ox + c * lx - s * ly, h / 2f + oy + s * lx + c * ly)
    }

    /** A screen-space movement as a paper-space movement. */
    fun deltaToPaper(d: Offset): Offset {
        val k = side * scale
        return Offset((c * d.x + s * d.y) / k, (-s * d.x + c * d.y) / k)
    }

    fun toPaper(p: Offset): Offset {
        val q = deltaToPaper(Offset(p.x - w / 2f - ox, p.y - h / 2f - oy))
        return Offset(q.x + 0.5f, q.y + 0.5f)
    }
}

private enum class Grab { Move, Scale, Rotate, Pivot, EdgeX, EdgeY, None }

/** Box in screen px. corners = TL, TR, BR, BL; edges = top, right, bottom, left; ex / ey = unit directions of the box's own x / y axes. */
private class Geo(
    val corners: List<Offset>, val edges: List<Offset>, val ex: Offset, val ey: Offset,
    val rotHandle: Offset, val pivot: Offset,
)

private fun unit(v: Offset): Offset {
    val l = v.getDistance()
    return if (l < 1e-3f) Offset(1f, 0f) else v / l
}

private fun geo(m: PaperMap, xf: LayerXf, b: Rect, gapPx: Float): Geo {
    val c = listOf(Offset(b.left, b.top), Offset(b.right, b.top), Offset(b.right, b.bottom), Offset(b.left, b.bottom))
        .map { m.toScreen(xf.apply(it)) }
    val edges = listOf((c[0] + c[1]) / 2f, (c[1] + c[2]) / 2f, (c[2] + c[3]) / 2f, (c[3] + c[0]) / 2f)
    val center = (c[0] + c[2]) / 2f
    val up = unit(edges[0] - center)
    return Geo(
        c, edges, unit(c[1] - c[0]), unit(c[3] - c[0]),
        edges[0] + up * gapPx, m.toScreen(Offset(xf.px + xf.tx, xf.py + xf.ty)),
    )
}

/** Point inside the (convex) box? */
private fun inside(c: List<Offset>, p: Offset): Boolean {
    var pos = false
    var neg = false
    for (i in 0..3) {
        val a = c[i]
        val b = c[(i + 1) % 4]
        val cr = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
        if (cr > 0f) pos = true else if (cr < 0f) neg = true
    }
    return !(pos && neg)
}

private fun dot(a: Offset, b: Offset) = a.x * b.x + a.y * b.y

private fun scaled(v: Float, f: Float): Float {
    val sign = if (v < 0f) -1f else 1f
    return (abs(v) * f).coerceIn(0.05f, 8f) * sign
}

private fun wrapDeg(d: Float): Float {
    var r = d % 360f
    if (r > 180f) r -= 360f
    if (r < -180f) r += 360f
    return r
}

/** Soft snap to every 15 degrees (works on the UNWRAPPED angle, so 720 + 15 snaps like 15 does). */
private fun snapDeg(d: Float): Float {
    val s = (d / 15f).roundToInt() * 15f
    return if (abs(d - s) < 2f) s else d
}

// ---- multi-turn rotation: LayerXf.rot / the Rotation keys are plain degrees and are NOT wrapped (720 = 2 full spins) ----

/** +-1000 spins. Float keeps ~0.03 deg of precision at this size. */
private const val MAX_ROT = 360_000f

private fun clampRot(d: Float) = d.coerceIn(-MAX_ROT, MAX_ROT)

/** 30 -> "30.0deg", 720 -> "x2: 360deg", -1080 -> "x-3: 360deg", 750 -> "x2: 360deg +30.0deg". */
private fun rotText(rot: Float): String {
    val r = (rot * 100f).roundToInt() / 100f
    val turns = (r / 360f).toInt()
    val off = r - turns * 360f
    if (turns == 0) return "%.1f\u00B0".format(r)
    if (abs(off) < 0.05f) return "\u00D7$turns: 360\u00B0"
    return "\u00D7$turns: 360\u00B0 ${if (off < 0f) "\u2212" else "+"}${"%.1f".format(abs(off))}\u00B0"
}

/** "720", "-90.5", "x10" / "10x" (= 10 spins), "-x3" / "x-3". null = not a number. */
private fun parseRot(input: String): Float? {
    val t = input.trim().lowercase().replace("\u00B0", "").replace("deg", "").replace("\u00D7", "x").replace(" ", "").replace(',', '.')
    if (t.isEmpty()) return null
    Regex("^(-?)x(-?\\d+(?:\\.\\d+)?)$").matchEntire(t)?.let { m ->
        val n = m.groupValues[2].toFloatOrNull() ?: return null
        return clampRot((if (m.groupValues[1] == "-") -1f else 1f) * n * 360f)
    }
    Regex("^(-?\\d+(?:\\.\\d+)?)x$").matchEntire(t)?.let { m ->
        return clampRot((m.groupValues[1].toFloatOrNull() ?: return null) * 360f)
    }
    return t.toFloatOrNull()?.takeIf { it.isFinite() }?.let(::clampRot)
}

@Composable
private fun RotationDialog(current: Float, onDismiss: () -> Unit, onApply: (Float) -> Unit) {
    var text by remember {
        mutableStateOf(if (current == current.roundToInt().toFloat()) current.roundToInt().toString() else "%.1f".format(current))
    }
    val parsed = parseRot(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rotation") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                isError = parsed == null,
                label = { Text("Degrees, or x spins") },
                supportingText = { Text(if (parsed != null) rotText(parsed) else "e.g. 720    -90    x10    -x3") },
            )
        },
        confirmButton = { TextButton(onClick = { parsed?.let(onApply) }, enabled = parsed != null) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun TransformOverlay(state: EditorState, viewSize: IntSize) {
    val vp = state.viewport
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val track = state.activeTrack
    val xf = state.trackXf(track)
    // box of the drawing; cached, a big drawing has a lot of points
    val bounds = remember(track, state.strokes.size, state.cels.size) {
        state.trackBounds(track) ?: Rect(0.25f, 0.25f, 0.75f, 0.75f)
    }
    val hitPx = with(density) { 28.dp.toPx() }
    val pivotHitPx = with(density) { 22.dp.toPx() }
    val gapPx = with(density) { 36.dp.toPx() }
    fun map() = PaperMap(viewSize.width.toFloat(), viewSize.height.toFloat(), vp.scale, vp.rotation, vp.offsetX, vp.offsetY)

    val accent = cs.primary
    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(state, viewSize) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val tr = state.activeTrack
                    val before = state.snapshot()
                    val b = state.trackBounds(tr) ?: Rect(0.25f, 0.25f, 0.75f, 0.75f)
                    val g0 = geo(map(), state.trackXf(tr), b, gapPx)
                    fun near(p: Offset, r: Float = hitPx) = (down.position - p).getDistance() < r
                    val grab = when {
                        near(g0.pivot, pivotHitPx) -> Grab.Pivot
                        near(g0.rotHandle) -> Grab.Rotate
                        g0.corners.any { near(it) } -> Grab.Scale
                        near(g0.edges[1]) || near(g0.edges[3]) -> Grab.EdgeX
                        near(g0.edges[0]) || near(g0.edges[2]) -> Grab.EdgeY
                        inside(g0.corners, down.position) -> Grab.Move
                        state.transformBoxOnly -> Grab.None   // box-only: a touch off the box does nothing
                        else -> Grab.Move                      // whole screen: a drag anywhere moves the layer
                    }

                    var rawRot = state.trackXf(tr).rot   // un-snapped angle, so the snap never sticks
                    var changed = false
                    var snappedNow = false
                    // Box-only: a touch off the box, or any 2nd finger, drives the VIEW (pan / zoom / twist)
                    // instead of the layer, so the canvas can be navigated while the box stays editable.
                    val nav = ViewNav(vp)
                    var navigating = grab == Grab.None
                    if (!navigating) down.consume()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (state.transformBoxOnly && pressed.size >= 2) navigating = true
                        if (navigating) {
                            nav.step(ev, viewSize)
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                            continue
                        }
                        val m = map()
                        val x = state.trackXf(tr)
                        if (pressed.size >= 2) {
                            // two fingers: pinch = resize, twist = rotate, drag = move
                            val zoom = ev.calculateZoom()
                            val twist = ev.calculateRotation()
                            val pan = ev.calculatePan()
                            if (zoom != 1f || twist != 0f || pan != Offset.Zero) {
                                val dp = m.deltaToPaper(pan)
                                rawRot = clampRot(rawRot + twist)
                                val r = snapDeg(rawRot)
                                state.updateXf(tr) {
                                    it.copy(tx = it.tx + dp.x, ty = it.ty + dp.y, sx = scaled(it.sx, zoom), sy = scaled(it.sy, zoom), rot = r)
                                }
                                changed = true
                            }
                        } else {
                            val c = pressed[0]
                            val d = c.position - c.previousPosition
                            if (d != Offset.Zero) {
                                val pivot = m.toScreen(Offset(x.px + x.tx, x.py + x.ty))
                                when (grab) {
                                    Grab.Move -> {
                                        val dp = m.deltaToPaper(d)
                                        state.updateXf(tr) { it.copy(tx = it.tx + dp.x, ty = it.ty + dp.y) }
                                    }
                                    Grab.Pivot -> {
                                        // the pivot moves, the picture stays where it is
                                        val local = x.invert(m.toPaper(c.position))
                                        state.updateXf(tr) { it.withPivot(local) }
                                    }
                                    Grab.Scale -> {
                                        val d0 = (c.previousPosition - pivot).getDistance()
                                        val d1 = (c.position - pivot).getDistance()
                                        if (d0 > 8f) {
                                            val f = d1 / d0
                                            state.updateXf(tr) { it.copy(sx = scaled(it.sx, f), sy = scaled(it.sy, f)) }
                                        }
                                    }
                                    Grab.EdgeX -> {
                                        val a0 = dot(c.previousPosition - pivot, g0.ex)
                                        val a1 = dot(c.position - pivot, g0.ex)
                                        if (abs(a0) > 8f) {
                                            val f = abs(a1 / a0).coerceIn(0.02f, 50f)
                                            state.updateXf(tr) { it.copy(sx = scaled(it.sx, f)) }
                                        }
                                    }
                                    Grab.EdgeY -> {
                                        val a0 = dot(c.previousPosition - pivot, g0.ey)
                                        val a1 = dot(c.position - pivot, g0.ey)
                                        if (abs(a0) > 8f) {
                                            val f = abs(a1 / a0).coerceIn(0.02f, 50f)
                                            state.updateXf(tr) { it.copy(sy = scaled(it.sy, f)) }
                                        }
                                    }
                                    Grab.Rotate -> {
                                        val a0 = atan2(c.previousPosition.y - pivot.y, c.previousPosition.x - pivot.x)
                                        val a1 = atan2(c.position.y - pivot.y, c.position.x - pivot.x)
                                        // per-event delta is tiny, so wrapping IT only fixes the atan2 seam; the total keeps every turn
                                        rawRot = clampRot(rawRot + wrapDeg(((a1 - a0) * 180.0 / PI).toFloat()))
                                        val r = snapDeg(rawRot)
                                        val onStop = r != rawRot
                                        if (onStop && !snappedNow) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        snappedNow = onStop
                                        state.updateXf(tr) { it.copy(rot = r) }
                                    }
                                    Grab.None -> Unit
                                }
                                changed = true
                            }
                        }
                        ev.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                    nav.end()
                    if (changed) state.commitEdit(before)   // one undo step per gesture
                }
            }
    ) {
        val g = geo(map(), xf, bounds, gapPx)
        val box = Path().apply {
            moveTo(g.corners[0].x, g.corners[0].y)
            for (i in 1..3) lineTo(g.corners[i].x, g.corners[i].y)
            close()
        }
        drawPath(box, accent.copy(alpha = if (state.transformBoxOnly) 0.14f else 0.08f))
        drawPath(box, accent, style = Stroke(2.dp.toPx()))

        // rotate handle: stem + knob
        drawLine(accent, g.edges[0], g.rotHandle, 2.dp.toPx(), StrokeCap.Round)
        drawCircle(cs.surface, 11.dp.toPx(), g.rotHandle)
        drawCircle(accent, 11.dp.toPx(), g.rotHandle, style = Stroke(2.dp.toPx()))
        drawCircle(accent, 3.5f.dp.toPx(), g.rotHandle)

        // edge handles (stretch width / height): small bars
        val bar = 9.dp.toPx()
        for (i in 0..3) {
            val dir = if (i % 2 == 0) g.ex else g.ey
            drawLine(cs.surface, g.edges[i] - dir * bar, g.edges[i] + dir * bar, 7.dp.toPx(), StrokeCap.Round)
            drawLine(accent, g.edges[i] - dir * bar, g.edges[i] + dir * bar, 4.dp.toPx(), StrokeCap.Round)
        }

        // corner (resize) handles
        for (p in g.corners) {
            drawCircle(cs.surface, 9.dp.toPx(), p)
            drawCircle(accent, 9.dp.toPx(), p, style = Stroke(2.dp.toPx()))
        }

        // pivot: ring + cross, drag to move it (the picture does not move)
        val pr = 11.dp.toPx()
        drawCircle(cs.surface.copy(alpha = 0.85f), pr, g.pivot)
        drawCircle(cs.tertiary, pr, g.pivot, style = Stroke(2.dp.toPx()))
        drawLine(cs.tertiary, g.pivot - Offset(pr * 0.55f, 0f), g.pivot + Offset(pr * 0.55f, 0f), 1.5f.dp.toPx(), StrokeCap.Round)
        drawLine(cs.tertiary, g.pivot - Offset(0f, pr * 0.55f), g.pivot + Offset(0f, pr * 0.55f), 1.5f.dp.toPx(), StrokeCap.Round)
    }
}

/**
 * One property = ONE row:  label | slider | value | keyframe icon.
 * Tap the label to reset, tap the value to type it, tap the diamond for keyframes (long-press = key this frame).
 */
@Composable
private fun XfSlider(
    state: EditorState,
    prop: PropRef.Draw?,
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    text: String,
    neutral: Float,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    onLabelTap: (() -> Unit)? = null,   // default: back to [neutral]
    onTextTap: (() -> Unit)? = null,    // tap the value to type it
    extra: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                Modifier.width(60.dp).clickable { if (onLabelTap != null) onLabelTap() else { onChange(neutral); onDone() } },
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurface,
                maxLines = 1,
            )
            Slider(
                value = value.coerceIn(range),
                onValueChange = onChange,
                onValueChangeFinished = onDone,
                valueRange = range,
                modifier = Modifier.weight(1f).height(28.dp),
            )
            Text(
                text,
                (if (onTextTap != null) Modifier.clickable { onTextTap() } else Modifier)
                    .widthIn(min = 44.dp)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = cs.primary,
                textAlign = TextAlign.End,
                maxLines = 1,
            )
            if (prop != null) KeyIcon(state, prop, enabled = !state.activeLocked) else Spacer(Modifier.width(32.dp))
        }
        extra?.invoke()
        if (prop != null) KeyDrawer(state, prop)
    }
}

/** Popup that takes the place of the player controls + timeline while a layer is being transformed. */
@Composable
internal fun TransformPanel(state: EditorState) {
    val cs = MaterialTheme.colorScheme
    val track = state.activeTrack
    val xf = state.trackXf(track)
    var linked by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val pending = remember { arrayOfNulls<EditSnap>(1) }
    var rotDialog by remember { mutableStateOf(false) }
    var dragTurns by remember { mutableStateOf<Int?>(null) }   // whole spins frozen while the slider is dragged, so the thumb never jumps

    fun change(f: (LayerXf) -> LayerXf) {
        if (pending[0] == null) pending[0] = state.snapshot()   // sliders: one undo step per drag
        state.updateXf(track, f)
    }
    fun done() {
        pending[0]?.let { state.commitEdit(it) }
        pending[0] = null
    }
    fun sgn(v: Float) = if (v < 0f) -1f else 1f
    fun fit(v: Float) = v.coerceIn(0.05f, 8f)
    val tight = PaddingValues(horizontal = 8.dp)

    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 2.dp)) {
        // ---- STICKY: never scrolls away while the sliders below are edited ----
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            FilledTonalIconButton(onClick = { state.undo() }, enabled = state.canUndo, modifier = Modifier.size(34.dp)) { FoxIcon(Ico.Undo) }
            FilledTonalIconButton(onClick = { state.redo() }, enabled = state.canRedo, modifier = Modifier.size(34.dp)) { FoxIcon(Ico.Redo) }
            Text("Transform", Modifier.weight(1f).padding(start = 6.dp), style = MaterialTheme.typography.titleSmall)
            TextButton(
                onClick = { state.resetXf(track) },
                enabled = !xf.isIdentity || state.isAnimated(track),
                contentPadding = tight,
                modifier = Modifier.height(34.dp),
            ) { Text("Reset") }
            FilledTonalButton(
                onClick = { state.kfOpen = null; state.transformOpen = false },
                contentPadding = PaddingValues(horizontal = 14.dp),
                modifier = Modifier.height(34.dp),
            ) { Text("Done") }
        }
        StickyKeyBar(state)

        // ---- scrolling part ----
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // which layer (row) is being transformed
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val rows = state.drawTracks.asReversed()
                rows.forEachIndexed { i, t ->
                    FilterChip(
                        selected = t.id == track,
                        onClick = { state.selectTransformTrack(t.id) },
                        label = { Text(t.name.ifBlank { "Layer ${rows.size - i}" }) },
                    )
                }
            }

            // ---- the everyday five: one row each ----
            XfSlider(state, PropRef.Draw(track, Chan.PosX), "Pos X", xf.tx * 100f, -100f..100f, "${(xf.tx * 100f).roundToInt()}%", 0f,
                { v -> change { it.copy(tx = v / 100f) } }, ::done)
            XfSlider(state, PropRef.Draw(track, Chan.PosY), "Pos Y", xf.ty * 100f, -100f..100f, "${(xf.ty * 100f).roundToInt()}%", 0f,
                { v -> change { it.copy(ty = v / 100f) } }, ::done)

            // W / H of the box (100% = the drawing's own size)
            XfSlider(state, PropRef.Draw(track, Chan.Width), "Width", abs(xf.sx) * 100f, 5f..400f, "${(abs(xf.sx) * 100f).roundToInt()}%", 100f,
                { v ->
                    change {
                        val nsx = sgn(it.sx) * fit(v / 100f)
                        if (linked && abs(it.sx) > 1e-4f) it.copy(sx = nsx, sy = sgn(it.sy) * fit(abs(it.sy) * abs(nsx) / abs(it.sx)))
                        else it.copy(sx = nsx)
                    }
                }, ::done)
            XfSlider(state, PropRef.Draw(track, Chan.Height), "Height", abs(xf.sy) * 100f, 5f..400f, "${(abs(xf.sy) * 100f).roundToInt()}%", 100f,
                { v ->
                    change {
                        val nsy = sgn(it.sy) * fit(v / 100f)
                        if (linked && abs(it.sy) > 1e-4f) it.copy(sy = nsy, sx = sgn(it.sx) * fit(abs(it.sx) * abs(nsy) / abs(it.sy)))
                        else it.copy(sy = nsy)
                    }
                }, ::done)

            // Rotation is unbounded: the slider fine-tunes within the current spin; tap the value to type degrees / spins.
            val turnsNow = dragTurns ?: (xf.rot / 360f).toInt()
            XfSlider(
                state, PropRef.Draw(track, Chan.Rotation), "Rotate",
                xf.rot - turnsNow * 360f, -360f..360f, rotText(xf.rot), 0f,
                { v ->
                    val t = dragTurns ?: turnsNow.also { dragTurns = it }
                    change { it.copy(rot = clampRot(t * 360f + v)) }
                },
                { dragTurns = null; done() },
                onLabelTap = { change { it.copy(rot = 0f) }; done() },
                onTextTap = { rotDialog = true },
            )
            if (rotDialog) {
                RotationDialog(xf.rot, onDismiss = { rotDialog = false }) { v ->
                    change { it.copy(rot = v) }; done(); rotDialog = false
                }
            }

            // ---- advanced, folded away by default ----
            TextButton(
                onClick = { more = !more },
                contentPadding = tight,
                modifier = Modifier.height(32.dp),
            ) { Text(if (more) "Less \u25B4" else "More \u25BE", style = MaterialTheme.typography.labelMedium) }

            if (more) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { change { it.copy(rot = clampRot(it.rot - 360f)) }; done() }, contentPadding = tight, modifier = Modifier.height(34.dp)) { Text("\u2212 1 spin") }
                    TextButton(onClick = { change { it.copy(rot = clampRot(it.rot + 360f)) }; done() }, contentPadding = tight, modifier = Modifier.height(34.dp)) { Text("+ 1 spin") }
                    TextButton(onClick = {
                        val c = state.trackBounds(track)?.center ?: Offset(0.5f, 0.5f)
                        change { it.withPivot(c) }; done()
                    }, contentPadding = tight, modifier = Modifier.height(34.dp)) { Text("Center pivot") }
                    TextButton(onClick = { change { it.copy(sx = -it.sx) }; done() }, contentPadding = tight, modifier = Modifier.height(34.dp)) { Text("Flip H") }
                    TextButton(onClick = { change { it.copy(sy = -it.sy) }; done() }, contentPadding = tight, modifier = Modifier.height(34.dp)) { Text("Flip V") }
                }
                // pivot (rotation / resize centre) in paper %, the picture stays put while it moves
                XfSlider(state, null, "Pivot X", xf.px * 100f, -50f..150f, "${(xf.px * 100f).roundToInt()}%", 50f,
                    { v -> change { it.withPivot(Offset(v / 100f, it.py)) } }, ::done)
                XfSlider(state, null, "Pivot Y", xf.py * 100f, -50f..150f, "${(xf.py * 100f).roundToInt()}%", 50f,
                    { v -> change { it.withPivot(Offset(it.px, v / 100f)) } }, ::done)
                Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Keep width : height together", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = cs.onSurface)
                    Switch(checked = linked, onCheckedChange = { linked = it })
                }
                Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Box-only touch (drag outside pans the view)", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = cs.onSurface)
                    Switch(checked = state.transformBoxOnly, onCheckedChange = { state.transformBoxOnly = it })
                }
            }
        }
    }
}
