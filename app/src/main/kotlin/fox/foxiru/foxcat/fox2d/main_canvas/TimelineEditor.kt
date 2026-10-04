package fox.foxiru.foxcat.fox2d.timeline

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import fox.foxiru.foxcat.fox2d.jnicallers.AudioHandlerNative
import fox.foxiru.foxcat.fox2d.main_canvas.EditSnap
import fox.foxiru.foxcat.fox2d.main_canvas.EditorState
import fox.foxiru.foxcat.fox2d.main_canvas.FoxIcon
import fox.foxiru.foxcat.fox2d.main_canvas.Ico
import fox.foxiru.foxcat.fox2d.main_canvas.AudioKeyDrawer
import fox.foxiru.foxcat.fox2d.main_canvas.KeyIcon
import fox.foxiru.foxcat.fox2d.main_canvas.PropRef
import fox.foxiru.foxcat.fox2d.main_canvas.drawKeyMark
import fox.foxiru.foxcat.fox2d.main_canvas.moveAllKeys
import fox.foxiru.foxcat.fox2d.main_canvas.paintCel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val HEADER_W = 68.dp
private val RULER_H = 22.dp
private val CEL_H = 46.dp
private val AUDIO_H = 40.dp
private val GAP = 3.dp

private val AudioBlue = Color(0xFF2F9BD6)
private val Selected = Color(0xFFFFD60A)

private sealed interface Hit {
    object Ruler : Hit
    class CelTrim(val track: Int) : Hit
    class Key(val track: Int, val frame: Int) : Hit
    object Empty : Hit
    class Cel(val track: Int, val index: Int) : Hit
    class Audio(val id: Int) : Hit
    class AudioL(val id: Int) : Hit
    class AudioR(val id: Int) : Hit
}

private class FlingHolder { var job: Job? = null }

private val RowSettle = spring<Float>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)

/**
 * Long-press-and-drag reordering of the drawing rows (front <-> behind). Shared by the header column and the
 * timeline canvas so both show the same animated state: the picked row follows the finger, the rows it passes
 * glide out of the way, and on release it settles into its slot and the order is committed as ONE undo step
 * (canvas + export follow). Slots count from the top of the stack: slot 0 = front row (painted last).
 */
private class RowReorder(
    private val state: EditorState,
    private val scope: CoroutineScope,
    private val scroll: ScrollState,
) {
    var id by mutableIntStateOf(-1)     // picked row, -1 = none
    var dy by mutableFloatStateOf(0f)   // picked row's displacement from its resting slot (px)
    var rowH = 0f
    var celTop = 0f
    var viewportH = 0f
    private var from = 0                // resting slot of the picked row
    private var target = 0              // slot it currently hovers over
    private var autoJob: Job? = null

    private class Off { var v by mutableFloatStateOf(0f); var job: Job? = null }
    private val offs = HashMap<Int, Off>()
    private fun off(trackId: Int) = offs.getOrPut(trackId) { Off() }

    fun offsetOf(trackId: Int): Float = if (trackId == id) dy else off(trackId).v

    fun start(trackId: Int): Boolean {
        val n = state.drawTracks.size
        val idx = state.drawTracks.indexOfFirst { it.id == trackId }
        if (id >= 0 || n < 2 || idx < 0 || rowH <= 0f) return false
        from = n - 1 - idx
        target = from
        dy = 0f
        id = trackId
        state.timeline.selectedClip = -1
        state.activeTrack = trackId
        return true
    }

    fun drag(d: Float) {
        moveBy(d)
        autoScroll()
    }

    private fun moveBy(d: Float) {
        if (id < 0) return
        val n = state.drawTracks.size
        dy = (dy + d).coerceIn(-from * rowH, (n - 1 - from) * rowH)
        val t = (from + (dy / rowH).roundToInt()).coerceIn(0, n - 1)
        if (t != target) { target = t; makeRoom() }
    }

    /** Rows between the pick-up slot and the hover slot slide one slot toward the vacated place. */
    private fun makeRoom() {
        val n = state.drawTracks.size
        for ((idx, tr) in state.drawTracks.withIndex()) {
            if (tr.id == id) continue
            val slot = n - 1 - idx
            val goal = when {
                target > from && slot in (from + 1)..target -> -rowH
                target < from && slot in target until from -> rowH
                else -> 0f
            }
            val o = off(tr.id)
            o.job?.cancel()
            o.job = scope.launch { animate(o.v, goal, animationSpec = RowSettle) { v, _ -> o.v = v } }
        }
    }

    /** Dragging near the top / bottom edge of the track list scrolls it, the row keeps following the finger. */
    private fun autoScroll() {
        if (autoJob?.isActive == true) return
        autoJob = scope.launch {
            while (id >= 0) {
                val top = celTop + from * rowH + dy
                val bottom = top + rowH
                val v0 = scroll.value.toFloat()
                val edge = rowH * 0.35f
                val delta = when {
                    top < v0 + edge -> -12f
                    bottom > v0 + viewportH - edge -> 12f
                    else -> 0f
                }
                if (delta == 0f) break
                val got = scroll.scrollBy(delta)
                if (got == 0f) break
                moveBy(got)
                delay(16)
            }
        }
    }

    fun end() {
        if (id < 0) return
        autoJob?.cancel()
        val picked = id
        val slot = target
        val rest = from
        scope.launch {
            animate(dy, (slot - rest) * rowH, animationSpec = RowSettle) { v, _ -> dy = v }
            // commit + reset in one snapshot so no frame shows the new order with the old offsets
            Snapshot.withMutableSnapshot {
                if (slot != rest) state.moveDrawTrack(picked, slot)
                offs.values.forEach { it.job?.cancel(); it.v = 0f }
                id = -1
                dy = 0f
            }
        }
    }
}

/** Long press (no slop movement for the system timeout) picks the row up; then vertical drag reorders it. */
private fun Modifier.longPressReorder(rd: RowReorder, trackId: Int, onPick: () -> Unit): Modifier =
    pointerInput(trackId) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val slop = viewConfiguration.touchSlop
            var cancelled = false
            val timedOut = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (!cancelled) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    val c = ev.changes.firstOrNull { it.id == down.id }
                    if (c == null || !c.pressed || (c.position - down.position).getDistance() > slop) cancelled = true
                }
            } == null
            if (cancelled || !timedOut || !rd.start(trackId)) return@awaitEachGesture
            onPick()
            down.consume()
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!c.pressed) { c.consume(); break }
                val d = c.position.y - c.previousPosition.y
                if (d != 0f) rd.drag(d)
                c.consume()
            }
            rd.end()
        }
    }

/**
 * Pan / pinch-zoom timeline: ruler, animation (cel) track and any number of audio tracks with waveforms.
 *
 *  - one finger drag  : pan (vertical drags fall through to the track list scroll) + fling
 *  - two finger pinch : zoom around the fingers
 *  - ruler drag       : scrub the playhead
 *  - tap              : select a clip / move the playhead
 *  - selected audio   : drag body = move, drag yellow edges = trim
 *  - selected cel     : drag the right edge = change its hold (ripples what follows)
 *  - drawing rows     : any number, stacked (top row paints on top); brush icon = active row; ••• menu duplicates / deletes them
 *  - selected drawing : drag its body = move the whole layer left / right in time (like an audio clip)
 *  - reorder rows     : long-press a row's header and drag up / down (front <-> behind); one undo step
 *  - + button         : menu to add a drawing track or an audio track
 */
@Composable
fun TimelineEditor(state: EditorState, modifier: Modifier = Modifier) {
    val tl = state.timeline
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer()
    val fling = remember { FlingHolder() }
    val vScroll = rememberScrollState()
    val waveBuf = remember { FloatArray(2 * 1000) }
    val envBuf = remember { FloatArray(240) }
    val rd = remember(state, vScroll) { RowReorder(state, scope, vScroll) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { state.importAudio(context, uri, state.frame * 1000L / state.fps) }
    }

    // clip table -> native mixer, content length -> scroll limits
    LaunchedEffect(tl) { snapshotFlow { tl.clips.toList() to tl.tracks.toList() }.collect { tl.pushToNative() } }
    LaunchedEffect(tl) {
        // no fixed cap anywhere: the timeline is as long as its longest drawing track / audio layer
        snapshotFlow { max(state.frameCount / state.fps.toFloat(), tl.endMs() / 1000f) }.collect {
            tl.lengthSec = it
            tl.contentSec = it + 2f
        }
    }
    // the ••• menu asks for the picker; consume the flag so a rotation can't re-open it
    LaunchedEffect(state.audioPickRequested) {
        if (state.audioPickRequested) {
            state.audioPickRequested = false
            picker.launch(arrayOf("audio/*", "video/*"))
        }
    }
    // keep the playhead on screen while playing
    LaunchedEffect(state.frame) {
        if (state.playing && tl.viewW > 0f) {
            val x = state.frame / state.fps.toFloat() * tl.pxPerSec - tl.scrollPx
            if (x > tl.viewW * 0.85f || x < 0f) {
                tl.scrollPx = (state.frame / state.fps.toFloat() * tl.pxPerSec - tl.viewW * 0.3f).coerceIn(tl.minScroll, tl.maxScroll)
            }
        }
    }

    val rulerPx = with(density) { RULER_H.toPx() }
    val celH = with(density) { CEL_H.toPx() }
    val audioH = with(density) { AUDIO_H.toPx() }
    val gap = with(density) { GAP.toPx() }
    val handleTouch = with(density) { 20.dp.toPx() }
    val keyBand = with(density) { 24.dp.toPx() }    // bottom strip of a row where its keyframe marks live
    val keyTouch = with(density) { 14.dp.toPx() }
    val celTop = rulerPx + gap
    rd.rowH = celH + gap
    rd.celTop = celTop
    // NOT a val: the pointerInput(Unit) block below captures these local functions once, so the row count
    // has to be read live or hit-testing keeps using the row count from the first composition.
    fun nd() = state.drawTracks.size
    // drawing rows: the last track (top of the stack) is the first row under the ruler
    fun celTopOf(ti: Int) = celTop + (nd() - 1 - ti) * (celH + gap)
    fun audioTop(i: Int) = celTop + nd() * (celH + gap) + i * (audioH + gap)
    fun trackIndexAt(y: Float) = floor((y - audioTop(0)) / (audioH + gap)).toInt().coerceIn(0, tl.tracks.lastIndex)
    val contentH: Dp = RULER_H + (CEL_H + GAP) * nd() + (AUDIO_H + GAP) * tl.tracks.size

    fun xOfSec(s: Float) = s * tl.pxPerSec - tl.scrollPx
    fun xOfMs(ms: Long) = xOfSec(ms / 1000f)
    fun msAtX(x: Float) = ((x + tl.scrollPx) / tl.pxPerSec * 1000f).toLong()
    fun seekAt(x: Float) = state.seek(floor((x + tl.scrollPx) / tl.pxPerSec * state.fps).toInt())

    fun hit(p: Offset): Hit {
        if (p.y < rulerPx) return Hit.Ruler
        val row = floor((p.y - celTop) / (celH + gap)).toInt()
        val rows = nd()
        if (row in 0 until rows && p.y <= celTop + row * (celH + gap) + celH) {
            val t = state.drawTracks[rows - 1 - row]
            // keyframe marks win over the cel body / trim handle in their strip
            if (!t.locked && t.allKeys.isNotEmpty() && p.y >= celTop + row * (celH + gap) + celH - keyBand) {
                t.markFrames.firstOrNull { abs(p.x - xOfSec((t.offset + it) / state.fps.toFloat())) < keyTouch }
                    ?.let { return Hit.Key(t.id, it) }
            }
            if (tl.selectedClip < 0 && !t.locked && t.id == state.activeTrack) {
                val cur = state.celIndexAt(state.frame, t.id)
                if (cur >= 0) {
                    val end = (state.celStart(cur, t.id) + state.trackCels(t.id)[cur].len) / state.fps.toFloat()
                    if (abs(p.x - xOfSec(end)) < handleTouch) return Hit.CelTrim(t.id)
                }
            }
            val f = floor((p.x + tl.scrollPx) / tl.pxPerSec * state.fps).toInt()
            val ci = state.celIndexAt(f, t.id)
            return if (ci >= 0) Hit.Cel(t.id, ci) else Hit.Empty
        }
        tl.tracks.forEachIndexed { ti, track ->
            val top = audioTop(ti)
            if (p.y in top..(top + audioH)) {
                tl.clip(tl.selectedClip)?.takeIf { it.track == track.id && !track.locked }?.let { c ->
                    if (abs(p.x - xOfMs(c.startMs)) < handleTouch) return Hit.AudioL(c.id)
                    if (abs(p.x - xOfMs(c.startMs + c.lenMs)) < handleTouch) return Hit.AudioR(c.id)
                }
                val t = msAtX(p.x)
                tl.clips.lastOrNull { it.track == track.id && t >= it.startMs && t < it.startMs + it.lenMs }
                    ?.let { return Hit.Audio(it.id) }
                return Hit.Empty
            }
        }
        return Hit.Empty
    }

    val rowBg = cs.surfaceContainerHighest.copy(alpha = 0.55f)
    val rowBgLifted = cs.surfaceContainerHighest
    val celCol = cs.secondaryContainer
    val celSelCol = cs.primaryContainer
    val tick = cs.outline
    val labelCol = cs.onSurfaceVariant
    val headCol = cs.onSurface
    val paper = cs.surfaceContainerLowest

    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().heightIn(max = 168.dp)
                .onSizeChanged { rd.viewportH = it.height.toFloat() }
                .verticalScroll(vScroll)
        ) {
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.zIndex(1f).background(MaterialTheme.colorScheme.surface)) {
                    TrackHeaders(state, rd)
                }
                Canvas(
                    Modifier
                        .weight(1f)
                        .height(contentH)
                        .clipToBounds() // Canvas doesn't clip: negative x0 was painting over the headers
                        .onSizeChanged { tl.viewW = it.width.toFloat() }
                        .pointerInput(Unit) {
                            val slop = viewConfiguration.touchSlop
                            val onBgTap: (Offset) -> Unit = { p ->
                                val hh = hit(p)
                                tl.selectedClip = -1
                                if (hh is Hit.Cel) {
                                    state.activeTrack = hh.track
                                    state.seek(state.celStart(hh.index, hh.track))
                                } else seekAt(p.x)
                            }
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                fling.job?.cancel()
                                val h = hit(down.position)
                                when (h) {
                                    is Hit.Ruler -> {
                                        down.consume()
                                        state.playing = false
                                        seekAt(down.position.x)
                                        drag(down.id) { c -> seekAt(c.position.x); c.consume() }
                                    }
                                    is Hit.Key -> {
                                        // tap = select the key and park the playhead on it; drag = retime it (one undo step)
                                        down.consume()
                                        val tr = h.track
                                        val off = state.trackOffset(tr)
                                        state.playing = false
                                        tl.selectedClip = -1
                                        state.activeTrack = tr
                                        state.selectedKey = h.frame
                                        state.seek(off + h.frame)
                                        val before = state.snapshot()
                                        var cur = h.frame
                                        var moved = false
                                        drag(down.id) { c ->
                                            val df = ((c.position.x - down.position.x) / tl.pxPerSec * state.fps).roundToInt()
                                            val r = state.moveAllKeys(tr, cur, h.frame + df)
                                            if (r != cur) { cur = r; moved = true; state.seek(off + cur) }
                                            c.consume()
                                        }
                                        if (moved) state.commitEdit(before)
                                    }
                                    is Hit.CelTrim -> {
                                        down.consume()
                                        val tr = h.track
                                        val cur = state.celIndexAt(state.frame, tr)
                                        if (cur < 0) return@awaitEachGesture
                                        val len0 = state.trackCels(tr)[cur].len
                                        val before = state.snapshot()
                                        drag(down.id) { c ->
                                            val df = (c.position.x - down.position.x) / tl.pxPerSec * state.fps
                                            state.setLen(cur, len0 + df.roundToInt(), tr)
                                            c.consume()
                                        }
                                        state.commitEdit(before)
                                    }
                                    is Hit.AudioL, is Hit.AudioR -> {
                                        down.consume()
                                        val id = if (h is Hit.AudioL) h.id else (h as Hit.AudioR).id
                                        val c0 = tl.clip(id) ?: return@awaitEachGesture
                                        val before = state.snapshot()
                                        drag(down.id) { c ->
                                            val d = ((c.position.x - down.position.x) / tl.pxPerSec * 1000f).toLong()
                                            if (h is Hit.AudioL) {
                                                val dd = d.coerceIn(-min(c0.startMs, c0.inMs), c0.lenMs - TimelineState.MIN_CLIP_MS)
                                                tl.update(id) { it.copy(startMs = c0.startMs + dd, inMs = c0.inMs + dd, lenMs = c0.lenMs - dd) }
                                            } else {
                                                val len = (c0.lenMs + d).coerceIn(TimelineState.MIN_CLIP_MS, c0.srcDurMs - c0.inMs)
                                                tl.update(id) { it.copy(lenMs = len) }
                                            }
                                            c.consume()
                                        }
                                        state.commitEdit(before)
                                    }
                                    is Hit.Audio -> {
                                        val c0 = tl.clip(h.id)
                                        val locked = c0 == null || tl.tracks.firstOrNull { it.id == c0.track }?.locked == true
                                        if (h.id == tl.selectedClip && !locked) {
                                            down.consume()
                                            val start0 = c0!!.startMs
                                            var track = c0.track
                                            val before = state.snapshot()
                                            drag(down.id) { c ->
                                                val d = ((c.position.x - down.position.x) / tl.pxPerSec * 1000f).toLong()
                                                // vertical: follow the finger onto any unlocked audio layer
                                                tl.tracks[trackIndexAt(c.position.y)].let { if (!it.locked) track = it.id }
                                                tl.update(h.id) { it.copy(startMs = max(0L, start0 + d), track = track) }
                                                c.consume()
                                            }
                                            state.commitEdit(before)
                                        } else {
                                            panZoomTap(down, tl, slop, onTap = { tl.selectedClip = h.id }, onFling = { v -> startFling(scope, fling, tl, v) })
                                        }
                                    }
                                    is Hit.Cel -> if (tl.selectedClip < 0 && h.track == state.activeTrack &&
                                        state.drawTracks.any { it.id == h.track && !it.locked } &&
                                        h.index == state.celIndexAt(state.frame, h.track)
                                    ) {
                                        // selected drawing: drag its body = move the whole layer left / right (like an audio clip).
                                        // The playhead rides along, so the same drawing stays selected.
                                        down.consume()
                                        val tr = h.track
                                        val before = state.snapshot()
                                        val off0 = state.trackOffset(tr)
                                        val frame0 = state.frame
                                        drag(down.id) { c ->
                                            val df = ((c.position.x - down.position.x) / tl.pxPerSec * state.fps).roundToInt()
                                            state.setTrackOffset(tr, off0 + df)
                                            state.frame = (frame0 + (state.trackOffset(tr) - off0)).coerceAtLeast(0)
                                            c.consume()
                                        }
                                        state.commitEdit(before)
                                    } else {
                                        panZoomTap(down, tl, slop, onTap = onBgTap, onFling = { v -> startFling(scope, fling, tl, v) })
                                    }
                                    else -> panZoomTap(down, tl, slop, onTap = onBgTap, onFling = { v -> startFling(scope, fling, tl, v) })
                                }
                            }
                        }
                ) {
                    val w = size.width
                    val pps = tl.pxPerSec
                    val fps = state.fps
                    fun x(sec: Float) = sec * pps - tl.scrollPx

                    // ---- ruler ----
                    drawRuler(measurer, tl, fps, w, rulerPx, tick, labelCol)

                    // ---- drawing rows (top row = top of the stack) ----
                    // the row being dragged is painted last (on top of the rows it passes)
                    state.drawTracks.withIndex().sortedBy { if (it.value.id == rd.id) 1 else 0 }.forEach { (ti, track) ->
                    val rowTop = celTopOf(ti) + rd.offsetOf(track.id)
                    val lifted = track.id == rd.id
                    if (lifted) drawRoundRect(Color.Black.copy(alpha = 0.28f), Offset(0f, rowTop + 3.dp.toPx()), Size(w, celH), CornerRadius(6.dp.toPx()))
                    drawRoundRect(if (lifted) rowBgLifted else rowBg, Offset(0f, rowTop), Size(w, celH), CornerRadius(6.dp.toPx()))
                    if (state.trackSelectMode && track.id in state.selDraw) drawRoundRect(Selected.copy(alpha = 0.22f), Offset(0f, rowTop), Size(w, celH), CornerRadius(6.dp.toPx()))
                    val cur = state.celIndexAt(state.frame, track.id)
                    val celSelected = tl.selectedClip < 0 && track.id == state.activeTrack
                    var a = track.offset
                    state.trackCels(track.id).forEachIndexed { i, c ->
                        val x0 = x(a / fps.toFloat())
                        val x1 = x((a + c.len) / fps.toFloat())
                        a += c.len
                        if (x1 < 0f || x0 > w) return@forEachIndexed
                        val cw = x1 - x0 - 2.dp.toPx()
                        drawRoundRect(if (i == cur) celSelCol else celCol, Offset(x0 + 1.dp.toPx(), rowTop), Size(cw, celH), CornerRadius(6.dp.toPx()))

                        val fw = pps / fps
                        if (fw >= 7f) {
                            val k0 = max(1, ceil((0f - x0) / fw).toInt())
                            val k1 = min(c.len - 1, floor((w - x0) / fw).toInt())
                            for (k in k0..k1) {
                                val hx = x0 + k * fw
                                drawLine(labelCol.copy(alpha = 0.25f), Offset(hx, rowTop + celH * 0.7f), Offset(hx, rowTop + celH - 5.dp.toPx()), 1.dp.toPx())
                            }
                        }
                        // thumbnail of the drawing
                        val th = celH - 8.dp.toPx()
                        if (cw > th + 8.dp.toPx()) {
                            val tx = x0 + 5.dp.toPx()
                            val ty = rowTop + 4.dp.toPx()
                            inset(tx, ty, size.width - tx - th, size.height - ty - th) {
                                drawRect(paper)
                                clipRect { paintCel(state, c.id, size.width) }
                            }
                            if (cw > th + 60.dp.toPx()) {
                                drawText(
                                    measurer, "${i + 1} \u00b7 ${c.len}f", Offset(tx + th + 6.dp.toPx(), rowTop + 6.dp.toPx()),
                                    style = TextStyle(fontSize = 10.sp, color = cs.onSecondaryContainer),
                                    maxLines = 1, overflow = TextOverflow.Clip,
                                )
                            }
                        }
                        if (i == cur && celSelected) {
                            drawRoundRect(Selected, Offset(x0 + 1.dp.toPx(), rowTop), Size(cw, celH), CornerRadius(6.dp.toPx()), style = Stroke(2.dp.toPx()))
                            if (!track.locked) {
                                drawRoundRect(Selected, Offset(x1 - 9.dp.toPx(), rowTop + 8.dp.toPx()), Size(7.dp.toPx(), celH - 16.dp.toPx()), CornerRadius(3.dp.toPx()))
                            }
                        }
                    }
                    // keyframes: one mark per frame that carries a key on ANY property (diamond = eased, square = every key there is Hold)
                    if (track.allKeys.isNotEmpty()) {
                        val ky = rowTop + celH - 10.dp.toPx()
                        val marks = track.markFrames
                        for (i in 0 until marks.size - 1) {
                            drawLine(
                                Selected.copy(alpha = 0.55f),
                                Offset(x((track.offset + marks[i]) / fps.toFloat()), ky),
                                Offset(x((track.offset + marks[i + 1]) / fps.toFloat()), ky),
                                2.dp.toPx(), StrokeCap.Round,
                            )
                        }
                        for (fr in marks) {
                            val kx = x((track.offset + fr) / fps.toFloat())
                            if (kx < -10f || kx > w + 10f) continue
                            val hold = track.allKeys.filter { it.frame == fr }.all { it.ease.isHold }
                            drawKeyMark(Offset(kx, ky), 6.dp.toPx(), hold, track.id == state.activeTrack && fr == state.selectedKey, cs.tertiary)
                        }
                    }
                    if (lifted) drawRoundRect(Selected, Offset(0f, rowTop), Size(w, celH), CornerRadius(6.dp.toPx()), style = Stroke(2.dp.toPx()))
                    }

                    // ---- audio tracks ----
                    tl.tracks.forEachIndexed { ti, track ->
                        val top = audioTop(ti)
                        drawRoundRect(rowBg, Offset(0f, top), Size(w, audioH), CornerRadius(6.dp.toPx()))
                        if (state.trackSelectMode && track.id in state.selAudio) drawRoundRect(Selected.copy(alpha = 0.22f), Offset(0f, top), Size(w, audioH), CornerRadius(6.dp.toPx()))
                        for (c in tl.clips) {
                            if (c.track != track.id) continue
                            val x0 = xOfMs(c.startMs)
                            val x1 = xOfMs(c.startMs + c.lenMs)
                            if (x1 < 0f || x0 > w) continue
                            val dim = if (track.muted) 0.45f else 1f
                            drawRoundRect(AudioBlue.copy(alpha = dim), Offset(x0, top), Size(x1 - x0, audioH), CornerRadius(6.dp.toPx()))
                            drawWave(c, tl, x0, x1, w, top, audioH, waveBuf)
                            if (c.gainKeys.isNotEmpty()) drawGainLine(c, tl, x0, x1, w, top, audioH, envBuf, state.activeKeyDot(c))
                            drawText(
                                measurer, c.name, Offset(max(x0, 0f) + 6.dp.toPx(), top + 2.dp.toPx()),
                                style = TextStyle(fontSize = 10.sp, color = Color.White.copy(alpha = dim)),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                size = Size(max(0f, min(x1, w) - max(x0, 0f) - 12.dp.toPx()), 14.dp.toPx()),
                            )
                            if (c.id == tl.selectedClip) {
                                drawRoundRect(Selected, Offset(x0, top), Size(x1 - x0, audioH), CornerRadius(6.dp.toPx()), style = Stroke(2.dp.toPx()))
                                if (!track.locked) {
                                    drawRoundRect(Selected, Offset(x0, top + 4.dp.toPx()), Size(8.dp.toPx(), audioH - 8.dp.toPx()), CornerRadius(3.dp.toPx()))
                                    drawRoundRect(Selected, Offset(x1 - 8.dp.toPx(), top + 4.dp.toPx()), Size(8.dp.toPx(), audioH - 8.dp.toPx()), CornerRadius(3.dp.toPx()))
                                }
                            }
                        }
                    }

                    // ---- playhead ----
                    val hx = x(state.frame / fps.toFloat())
                    drawLine(headCol, Offset(hx, 0f), Offset(hx, size.height), 2.dp.toPx())
                    drawCircle(headCol, 5.dp.toPx(), Offset(hx, 6.dp.toPx()))
                }
            }
        }
        // the open volume drawer of the selected clip
        (state.kfOpen as? PropRef.AudioGain)?.takeIf { it.clip == tl.selectedClip }?.let { AudioKeyDrawer(state, it) }
    }
}

// ------------------------------------------------------------------------------------------ gestures

private fun startFling(scope: kotlinx.coroutines.CoroutineScope, holder: FlingHolder, tl: TimelineState, velocity: Float) {
    holder.job?.cancel()
    holder.job = scope.launch {
        var last = 0f
        AnimationState(0f, -velocity).animateDecay(exponentialDecay()) {
            tl.panBy(value - last)
            last = value
        }
    }
}

private suspend fun AwaitPointerEventScope.panZoomTap(
    down: PointerInputChange,
    tl: TimelineState,
    slop: Float,
    onTap: (Offset) -> Unit,
    onFling: (Float) -> Unit,
) {
    val tracker = VelocityTracker()
    tracker.addPosition(down.uptimeMillis, down.position)
    var moved = false
    var horizontal = false
    var multi = false
    do {
        val ev = awaitPointerEvent()
        val pressed = ev.changes.count { it.pressed }
        if (pressed >= 2) {
            multi = true
            val zoom = ev.calculateZoom()
            val centroid = ev.calculateCentroid()
            val pan = ev.calculatePan()
            if (centroid.isSpecified) {
                if (zoom != 1f) tl.zoomAt(centroid.x, zoom)
                tl.panBy(-pan.x)
            }
            ev.changes.forEach { if (it.positionChanged()) it.consume() }
        } else if (!multi) {
            val c = ev.changes.firstOrNull { it.id == down.id } ?: ev.changes.first()
            if (!moved) {
                val d = c.position - down.position
                if (d.getDistance() > slop) {
                    moved = true
                    horizontal = abs(d.x) >= abs(d.y)
                }
            }
            if (moved) {
                if (!horizontal) return          // vertical: let the track list scroll
                tl.panBy(-(c.position.x - c.previousPosition.x))
                tracker.addPosition(c.uptimeMillis, c.position)
                c.consume()
            }
        }
    } while (ev.changes.any { it.pressed })
    if (moved && horizontal && !multi) onFling(tracker.calculateVelocity().x)
    else if (!moved && !multi) onTap(down.position)
}

// ------------------------------------------------------------------------------------------ drawing

private val rulerSteps = floatArrayOf(0.1f, 0.25f, 0.5f, 1f, 2f, 5f, 10f, 15f, 30f, 60f, 120f, 300f, 600f, 1800f)

private fun timecode(sec: Float, fps: Int): String {
    val total = (sec * fps).roundToInt().coerceAtLeast(0)
    val ff = total % fps
    val s = total / fps
    return "%02d:%02d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60, ff)
}

private fun DrawScope.drawRuler(
    measurer: androidx.compose.ui.text.TextMeasurer,
    tl: TimelineState,
    fps: Int,
    w: Float,
    rulerPx: Float,
    tick: Color,
    label: Color,
) {
    val pps = tl.pxPerSec
    val minLabelPx = 84.dp.toPx()
    val major = rulerSteps.firstOrNull { it * pps >= minLabelPx } ?: rulerSteps.last()
    val minor = major / 5f
    val showMinor = minor * pps >= 7.dp.toPx()
    // line marks only exist inside the timeline: 0 .. length of the longest layer
    val t0 = max(0, floor(tl.scrollPx / pps / minor).toInt())
    val t1 = min(ceil((tl.scrollPx + w) / pps / minor).toInt(), floor(tl.lengthSec / minor + 1e-4f).toInt())
    for (k in t0..t1) {
        val sec = k * minor
        val x = sec * pps - tl.scrollPx
        val isMajor = k % 5 == 0
        if (isMajor) {
            drawLine(tick, Offset(x, rulerPx - 10.dp.toPx()), Offset(x, rulerPx), 1.dp.toPx())
            if (sec >= 0f) {
                drawText(measurer, timecode(sec, fps), Offset(x + 3.dp.toPx(), 1.dp.toPx()), style = TextStyle(fontSize = 9.sp, color = label), maxLines = 1)
            }
        } else if (showMinor) {
            drawLine(tick.copy(alpha = 0.6f), Offset(x, rulerPx - 5.dp.toPx()), Offset(x, rulerPx), 1.dp.toPx())
        }
    }
    // end-of-timeline marker
    val xe = tl.lengthSec * pps - tl.scrollPx
    if (xe >= -2f && xe <= w + 2f) {
        drawLine(label, Offset(xe, 2.dp.toPx()), Offset(xe, rulerPx), 2.dp.toPx())
    }
}

private fun DrawScope.drawWave(
    c: AudioClip,
    tl: TimelineState,
    x0: Float,
    x1: Float,
    w: Float,
    top: Float,
    h: Float,
    buf: FloatArray,
) {
    val vx0 = max(x0, 0f)
    val vx1 = min(x1, w)
    if (vx1 - vx0 < 2f) return
    val step = max(2.dp.toPx(), (vx1 - vx0) / 1000f)
    val cols = ceil((vx1 - vx0) / step).toInt().coerceIn(1, 1000)
    val pps = tl.pxPerSec
    val srcA = c.inMs + (vx0 - x0) / pps * 1000.0
    val srcB = min(c.inMs + (vx0 + cols * step - x0) / pps * 1000.0, (c.inMs + c.lenMs).toDouble())
    AudioHandlerNative.waveform(c.handle, srcA, srcB, cols, buf)

    val wTop = top + 15.dp.toPx()
    val hh = (h - 15.dp.toPx() - 3.dp.toPx()) / 2f
    val mid = wTop + hh
    val col = Color.White.copy(alpha = 0.85f)
    val sw = step * 0.75f
    for (i in 0 until cols) {
        val mn = buf[i * 2]
        val mx = buf[i * 2 + 1]
        var y0 = mid - mx * hh
        var y1 = mid - mn * hh
        if (y1 - y0 < 1.5f) { y0 = mid - 0.75f; y1 = mid + 0.75f }
        val x = vx0 + (i + 0.5f) * step
        drawLine(col, Offset(x, y0), Offset(x, y1), sw)
    }
}

/** Row-agnostic: the key (source ms) under the playhead of [c], -1 = none. Lets the timeline paint it gold. */
private fun EditorState.activeKeyDot(c: AudioClip): Long =
    timeline.gainKeyAt(c, frameToMs(frame), 1000L / fps / 2)?.ms ?: -1L

/**
 * The clip's volume curve over its waveform: the SMOOTH spline from C++ (no polygon lines) + a dot per key.
 * 0 % sits on the clip's bottom edge, 200 % on the top of the waveform area.
 */
private fun DrawScope.drawGainLine(
    c: AudioClip, tl: TimelineState, x0: Float, x1: Float, w: Float, top: Float, h: Float, buf: FloatArray, goldMs: Long,
) {
    val vx0 = max(x0, 0f)
    val vx1 = min(x1, w)
    if (vx1 - vx0 < 2f || c.lenMs <= 0L) return
    val yTop = top + 15.dp.toPx()
    val yBot = top + h - 3.dp.toPx()
    fun yOf(g: Float) = yBot - (g / TimelineState.MAX_GAIN).coerceIn(0f, 1f) * (yBot - yTop)
    val pps = tl.pxPerSec
    val srcA = c.inMs + (vx0 - x0) / pps * 1000.0
    val srcB = min(c.inMs + (vx1 - x0) / pps * 1000.0, (c.inMs + c.lenMs).toDouble())
    AudioHandlerNative.envelope(tl.packGainKeys(c.gainKeys), c.gainKeys.size, srcA, srcB, buf)
    val path = Path()
    for (i in buf.indices) {
        val x = vx0 + i / (buf.size - 1f) * (vx1 - vx0)
        val y = yOf(buf[i])
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, Color.Black.copy(alpha = 0.35f), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, Selected, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    for (k in c.gainKeys) {
        val kx = x0 + (k.ms - c.inMs) / 1000f * pps
        if (kx < vx0 - 6f || kx > vx1 + 6f) continue
        val at = Offset(kx, yOf(k.gain))
        drawCircle(Color.Black.copy(alpha = 0.5f), 5.5f.dp.toPx(), at)
        drawCircle(if (k.ms == goldMs) Color.White else Selected, 4.dp.toPx(), at)
    }
}

// ------------------------------------------------------------------------------------------ chrome

@Composable
private fun TrackHeaders(state: EditorState, rd: RowReorder) {
    val tl = state.timeline
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    Column(Modifier.width(HEADER_W), verticalArrangement = Arrangement.spacedBy(GAP)) {
        Spacer(Modifier.height(RULER_H))
        // top row = top of the stack; tap the brush to make a row the active one
        state.drawTracks.asReversed().forEach { t ->
            val lifted = rd.id == t.id
            Box(
                Modifier
                    .zIndex(if (lifted) 2f else 0f)
                    .offset { IntOffset(0, rd.offsetOf(t.id).roundToInt()) }
                    .then(if (lifted) Modifier.background(cs.surfaceContainerHighest, RoundedCornerShape(6.dp)) else Modifier)
                    .longPressReorder(rd, t.id) { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
            ) {
                HeaderCell(CEL_H) {
                    if (state.trackSelectMode) {
                        SelCell(t.name, t.id in state.selDraw) { state.toggleDrawSel(t.id) }
                    } else {
                        MiniBtn(Ico.Brush, t.id == state.activeTrack) { tl.selectedClip = -1; state.activeTrack = t.id }
                        MiniBtn(if (t.locked) Ico.Lock else Ico.Unlock, t.locked) {
                            state.edit {
                                val i = state.drawTracks.indexOfFirst { it.id == t.id }
                                if (i >= 0) state.drawTracks[i] = state.drawTracks[i].copy(locked = !state.drawTracks[i].locked)
                            }
                        }
                    }
                }
            }
        }
        tl.tracks.forEachIndexed { i, t ->
            HeaderCell(AUDIO_H) {
                if (state.trackSelectMode) {
                    SelCell(t.name, t.id in state.selAudio) { state.toggleAudioSel(t.id) }
                } else {
                    MiniBtn(if (t.muted) Ico.VolumeOff else Ico.Volume, t.muted) { state.edit { tl.tracks[i] = t.copy(muted = !t.muted) } }
                    MiniBtn(if (t.locked) Ico.Lock else Ico.Unlock, t.locked) { state.edit { tl.tracks[i] = t.copy(locked = !t.locked) } }
                }
            }
        }
        var addMenu by remember { mutableStateOf(false) }
        Box(Modifier.height(24.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
            MiniBtn(Ico.Plus, addMenu) { addMenu = true }
            DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                DropdownMenuItem(text = { Text("Drawing track") }, onClick = { addMenu = false; state.addDrawTrack() })
                DropdownMenuItem(text = { Text("Audio track") }, onClick = { addMenu = false; state.edit { tl.addTrack() } })
            }
        }
    }
}

@Composable
private fun HeaderCell(h: Dp, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(h).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

/** Select-tracks mode: a check circle + the track name; the whole cell toggles. */
@Composable
private fun SelCell(name: String, on: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(if (on) cs.primary else Color.Transparent)
                .border(1.5.dp, if (on) cs.primary else cs.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (on) Text("\u2713", fontSize = 12.sp, color = cs.onPrimary) }
        Text(name, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = cs.onSurface)
    }
}

@Composable
private fun MiniBtn(kind: Ico, active: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { FoxIcon(kind, tint = if (active) cs.primary else cs.onSurfaceVariant, iconSize = 18.dp) }
}

// ------------------------------------------------------------------------------------------ import

/** Decode [uri] natively and drop it on the first unlocked audio track at the playhead (one undo step). */
suspend fun EditorState.importAudio(context: Context, uri: Uri, playheadMs: Long) {
    with(timeline) {
        loading = true
        error = null
        try {
            val a = AudioHandlerNative.import(context, uri)
            if (a == null) {
                error = "Couldn't read that file"
                return@with
            }
            adopt(a.handle)
            // snapshot is taken after the (slow) decode so edits made meanwhile are not folded into this step
            edit {
                if (tracks.none { !it.locked }) addTrack()
                val track = tracks.first { !it.locked }
                val onTrack = clips.filter { it.track == track.id }
                val overlaps = onTrack.any { playheadMs < it.startMs + it.lenMs && playheadMs + a.durationMs > it.startMs }
                val start = if (overlaps) onTrack.maxOf { it.startMs + it.lenMs } else playheadMs
                val clip = AudioClip(nextClipId(), track.id, a.handle, a.name, a.durationMs, start, 0L, a.durationMs)
                clips.add(clip)
                selectedClip = clip.id
            }
        } finally {
            loading = false
        }
    }
}
