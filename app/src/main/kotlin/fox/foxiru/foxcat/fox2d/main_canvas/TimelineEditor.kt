package fox.foxiru.foxcat.fox2d.timeline

import android.content.Context
import android.net.Uri
import fox.foxiru.foxcat.fox2d.project.ProjectRepository
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
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Canvas as GfxCanvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
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
import androidx.compose.ui.text.TextMeasurer
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
import fox.foxiru.foxcat.fox2d.main_canvas.Cel
import fox.foxiru.foxcat.fox2d.main_canvas.DrawTrack
import fox.foxiru.foxcat.fox2d.main_canvas.FILL_LOOP
import fox.foxiru.foxcat.fox2d.main_canvas.FILL_LOOP_STRETCH
import fox.foxiru.foxcat.fox2d.main_canvas.FILL_NONE
import fox.foxiru.foxcat.fox2d.main_canvas.RETIME_MODES
import fox.foxiru.foxcat.fox2d.main_canvas.retimeName
import fox.foxiru.foxcat.fox2d.main_canvas.FxMenuItems
import fox.foxiru.foxcat.fox2d.main_canvas.FxTarget
import fox.foxiru.foxcat.fox2d.main_canvas.KeyIcon
import fox.foxiru.foxcat.fox2d.main_canvas.PropRef
import fox.foxiru.foxcat.fox2d.main_canvas.RenameTarget
import fox.foxiru.foxcat.fox2d.main_canvas.TrackGroup
import fox.foxiru.foxcat.fox2d.main_canvas.drawKeyMark
import fox.foxiru.foxcat.fox2d.main_canvas.groupKey
import fox.foxiru.foxcat.fox2d.main_canvas.moveAllKeys
import fox.foxiru.foxcat.fox2d.main_canvas.paintLayers
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

private val HEADER_W = 88.dp
private val RULER_H = 22.dp
private val CEL_H = 46.dp
private val AUDIO_H = 40.dp
private val GROUP_H = 30.dp
private val GAP = 3.dp

private val AudioBlue = Color(0xFF2F9BD6)
private val Selected = Color(0xFFFFD60A)

/** One colour per group (by id), shown on the folder bar and as the accent strip of its members. */
private val GroupColors = listOf(
    Color(0xFF8E6CEF), Color(0xFF1FA39A), Color(0xFFE8873A),
    Color(0xFFD94F78), Color(0xFF4C8DEB), Color(0xFF86B83C),
)

private fun groupColor(g: Int): Color = GroupColors[g.coerceAtLeast(0) % GroupColors.size]

private sealed interface Hit {
    object Ruler : Hit
    class CelTrim(val track: Int) : Hit
    class Key(val track: Int, val frame: Int) : Hit
    object Empty : Hit
    class Cel(val track: Int, val index: Int) : Hit
    class Audio(val id: Int) : Hit
    class AudioL(val id: Int) : Hit
    class AudioR(val id: Int) : Hit
    /** The bar of a group folder: tap selects it, drag (when selected) moves every member in time. */
    class Group(val id: Int) : Hit
    /** Right edge of the selected group bar: drag = set the group length (its Re-timing mode decides what the extra time shows). */
    class GroupEnd(val id: Int) : Hit
    /** A track's name tag at the top-left of its row: long-press renames it. */
    class Label(val target: RenameTarget) : Hit
}

/**
 * One line of the timeline, top to bottom: a group folder bar, a drawing row or an audio layer.
 * [Draw.g] / [Audio.g] = id of the group the row is shown in, -1 = top level.
 */
private sealed class TlRow {
    class Group(val grp: TrackGroup, val open: Boolean, val count: Int, val hasDraw: Boolean, val locked: Boolean) : TlRow()
    class Draw(val t: DrawTrack, val g: Int) : TlRow()
    class Audio(val t: AudioTrack, val g: Int, val groupHasDraw: Boolean) : TlRow()

    val stableKey: String
        get() = when (this) {
            is Group -> "g${grp.id}"
            is Draw -> "d${t.id}"
            is Audio -> "a${t.id}"
        }

    /** Group this row belongs to (a folder bar belongs to its own group), -1 = top level. */
    val groupId: Int
        get() = when (this) {
            is Group -> grp.id
            is Draw -> g
            is Audio -> g
        }

    /**
     * Reorder key while TOP-LEVEL items are dragged: an ungrouped drawing row is its own item, a group (bar, its
     * drawing rows and its audio rows) is ONE block. null = this row never takes part (plain audio rows).
     */
    val topKey: Int?
        get() = when (this) {
            is Group -> if (hasDraw) groupKey(grp.id) else null
            is Draw -> if (g >= 0) groupKey(g) else t.id
            is Audio -> if (g >= 0 && groupHasDraw) groupKey(g) else null
        }
}

private class Placed(val row: TlRow, val top: Float, val h: Float)

/**
 * The rows in display order. Drawing rows come first, top of the stack first; a group appears where its topmost
 * drawing row is (a group with only audio layers appears among the audio layers). An open group lists its drawing
 * rows (top first) and then its audio layers under its bar; a closed group is just its bar.
 */
private fun buildRows(state: EditorState): List<TlRow> {
    val tl = state.timeline
    val out = ArrayList<TlRow>()
    val done = HashSet<Int>()
    fun emitGroup(g: TrackGroup) {
        val dm = state.drawTracks.filter { it.group == g.id }
        val am = tl.tracks.filter { it.group == g.id }
        val open = !state.isCollapsed(g.id)
        val locked = (dm.isNotEmpty() || am.isNotEmpty()) && dm.all { it.locked } && am.all { it.locked }
        out.add(TlRow.Group(g, open, dm.size + am.size, dm.isNotEmpty(), locked))
        if (open) {
            for (t in dm.asReversed()) out.add(TlRow.Draw(t, g.id))
            for (t in am) out.add(TlRow.Audio(t, g.id, dm.isNotEmpty()))
        }
    }
    for (t in state.drawTracks.asReversed()) {
        val g = state.groupById(t.group)
        if (g == null) out.add(TlRow.Draw(t, -1)) else if (done.add(g.id)) emitGroup(g)
    }
    for (t in tl.tracks) {
        val g = state.groupById(t.group)
        if (g == null) out.add(TlRow.Audio(t, -1, false)) else if (done.add(g.id)) emitGroup(g)
    }
    return out
}

private fun placeRows(rows: List<TlRow>, top0: Float, celH: Float, audioH: Float, groupH: Float, gap: Float): List<Placed> {
    var y = top0
    val out = ArrayList<Placed>(rows.size)
    for (r in rows) {
        val h = when (r) {
            is TlRow.Group -> groupH
            is TlRow.Draw -> celH
            is TlRow.Audio -> audioH
        }
        out.add(Placed(r, y, h))
        y += h + gap
    }
    return out
}

private class FlingHolder { var job: Job? = null }

// ------------------------------------------------------------------------------------------ per-edit caches (PERF)

/** The cels of one row with their start frames (row-local, prefix sums), so "which cel is at frame f" is a binary search. */
private class RowCels(val cels: List<Cel>, val rel: IntArray) {
    val total: Int get() = rel[cels.size]

    /** Index of the cel shown at row-local frame [f]; -1 before the row starts or after it has ended. */
    fun indexAt(f: Int): Int {
        if (f < 0 || f >= total) return -1
        var lo = 0
        var hi = cels.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (rel[mid] <= f) lo = mid else hi = mid - 1
        }
        return lo
    }
}

private fun buildRowCels(all: List<Cel>): Map<Int, RowCels> {
    val by = HashMap<Int, ArrayList<Cel>>()
    for (c in all) by.getOrPut(c.track) { ArrayList() }.add(c)
    val out = HashMap<Int, RowCels>(by.size * 2)
    for ((t, list) in by) {
        val rel = IntArray(list.size + 1)
        for (i in list.indices) rel[i + 1] = rel[i] + list[i].len
        out[t] = RowCels(list, rel)
    }
    return out
}

/** Bar geometry of a group, computed once per edit instead of several full scans per group per frame. */
private class GroupGeom(
    val span: ClosedFloatingPointRange<Float>?,
    val barEnd: Int,
    val srcStart: Int?,
    val srcEnd: Int?,
    val members: List<ClosedFloatingPointRange<Float>>,
)

private fun buildGroupGeom(state: EditorState): Map<Int, GroupGeom> {
    val out = HashMap<Int, GroupGeom>()
    for (grp in state.groups) {
        val members = state.groupMemberSpans(grp.id)
        val span = if (members.isEmpty()) null else members.minOf { it.start }..members.maxOf { it.endInclusive }
        val src = state.groupSourceSpan(grp.id)
        out[grp.id] = GroupGeom(span, state.groupBarEndFrame(grp.id), src?.first, src?.second, members)
    }
    return out
}

/**
 * Drawing thumbnails painted ONCE into small bitmaps (re-painted only when that drawing's strokes change), and painted OFF the
 * UI thread: a drawing with thousands of brush strokes + an image used to be re-inked synchronously inside the timeline's draw
 * after every stroke, which froze the screen. Now the draw keeps showing the previous bitmap until the new one lands.
 */
private class ThumbCache {
    private class T(val sig: Int, val px: Int, val bmp: ImageBitmap)

    private val map = object : LinkedHashMap<Int, T>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, T>) = size > 96
    }
    private val busy = HashSet<Int>()

    /** Bumped when a background paint lands. The timeline canvas reads it, so it redraws and picks the new bitmap up. */
    var landed by mutableIntStateOf(0)

    /** Newest finished bitmap of [id] (null the first time); queues a repaint on [Dispatchers.Default] when [sig] / [px] changed. */
    fun get(id: Int, sig: Int, px: Int, scope: CoroutineScope, paint: (ImageBitmap) -> Unit): ImageBitmap? {
        val t = map[id]
        if (t != null && t.sig == sig && t.px == px) return t.bmp
        if (busy.add(id)) scope.launch(Dispatchers.Default) {
            val bmp = runCatching { ImageBitmap(px, px).also(paint) }.getOrNull()
            withContext(Dispatchers.Main) {
                busy.remove(id)
                if (bmp != null) { map[id] = T(sig, px, bmp); landed++ }
            }
        }
        return t?.bmp
    }
}

/** First index whose value is >= [v] in a sorted list. */
private fun List<Int>.lowerBound(v: Int): Int {
    val i = binarySearch(v)
    return if (i >= 0) i else -i - 1
}

private val RowSettle = spring<Float>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)

/**
 * Long-press-and-drag reordering of the drawing stack (front <-> behind). Shared by the header column and the
 * timeline canvas so both show the same animated state: the picked item follows the finger, the items it passes
 * glide out of the way, and on release it settles into its slot and the order is committed as ONE undo step
 * (canvas + export follow). Items can have different heights (a whole open group is one tall item). Two kinds of drag:
 *  - a drawing row inside a group moves among that group's rows only (it cannot leave the folder);
 *  - an ungrouped row, or a whole group (long-press its bar header), moves among the top-level items.
 */
private class RowReorder(
    private val state: EditorState,
    private val scope: CoroutineScope,
    private val scroll: ScrollState,
) {
    var id by mutableIntStateOf(-1)     // key of the picked item, -1 = none
    var dy by mutableFloatStateOf(0f)   // picked item's displacement from its resting slot (px)
    var viewportH = 0f
    var gap = 0f
    var edge = 0f                       // auto-scroll zone at the top / bottom edge of the list (px)
    /** Group a top-level row will drop INTO on release (highlighted), -1 = none. */
    var joinGroup by mutableIntStateOf(-1)
    /** -1 / +1: the picked row will leave its folder upward / downward on release, 0 = it stays. */
    var leave by mutableIntStateOf(0)
    /** Live geometry, handed in by the timeline on every composition (never cached across edits). */
    var layout: () -> List<Placed> = { emptyList() }

    private var ctx = -1                // group the picked row is reordered inside, -1 = top-level items
    private var keys = IntArray(0)      // sibling keys, top -> bottom
    private var tops = FloatArray(0)    // resting top of each sibling (canvas px)
    private var hs = FloatArray(0)      // height of each sibling (all of its rows)
    private var from = 0                // resting slot of the picked item
    private var target = 0              // slot it currently hovers over
    private var autoJob: Job? = null

    private class Off { var v by mutableFloatStateOf(0f); var job: Job? = null }
    private val offs = HashMap<Int, Off>()
    private fun off(key: Int) = offs.getOrPut(key) { Off() }

    /** Which sibling this row belongs to in the current drag, null = it stays put. */
    private fun keyOf(r: TlRow): Int? = when {
        id == -1 -> null
        ctx < 0 -> r.topKey
        r is TlRow.Draw && r.g == ctx -> r.t.id
        else -> null
    }

    fun isLifted(r: TlRow): Boolean = keyOf(r).let { it != null && it == id }

    fun offsetOf(r: TlRow): Float {
        val k = keyOf(r) ?: return 0f
        return if (k == id) dy else off(k).v
    }

    /** [key] = track id, or groupKey(group) for a whole group; [group] >= 0 reorders inside that group only. */
    fun start(key: Int, group: Int): Boolean {
        if (id != -1) return false
        val k = ArrayList<Int>()
        val t = ArrayList<Float>()
        val h = ArrayList<Float>()
        for (pl in layout()) {
            val r = pl.row
            val sk: Int? = if (group >= 0) (if (r is TlRow.Draw && r.g == group) r.t.id else null) else r.topKey
            if (sk == null) continue
            val i = k.indexOf(sk)
            if (i < 0) { k.add(sk); t.add(pl.top); h.add(pl.h) } else h[i] = pl.top + pl.h - t[i]
        }
        val idx = k.indexOf(key)
        if (idx < 0 || (k.size < 2 && group < 0)) return false   // a lone folder member can still be dragged out
        keys = k.toIntArray()
        tops = t.toFloatArray()
        hs = h.toFloatArray()
        ctx = group
        from = idx
        target = idx
        dy = 0f
        joinGroup = -1
        leave = 0
        id = key
        state.timeline.selectedClip = -1
        if (key >= 0) state.activeTrack = key
        return true
    }

    fun drag(d: Float) {
        moveBy(d)
        autoScroll()
    }

    /** Resting top of the picked item if it were dropped into slot [s]. */
    private fun slotTop(s: Int): Float {
        var y = tops[from]
        if (s > from) {
            for (j in from + 1..s) y += hs[j] + gap
        } else {
            for (j in s until from) y -= hs[j] + gap
        }
        return y
    }

    /** The slot whose resting position is closest to where the item is now. */
    private fun nearest(): Int {
        val want = tops[from] + dy
        var best = from
        var bd = Float.MAX_VALUE
        for (s in keys.indices) {
            val d = abs(slotTop(s) - want)
            if (d < bd) { bd = d; best = s }
        }
        return best
    }

    private fun moveBy(d: Float) {
        if (id == -1) return
        val lo = slotTop(0) - tops[from]
        val hi = slotTop(keys.lastIndex) - tops[from]
        val slack = if (ctx >= 0) hs[from] * 1.6f else 0f   // a folder member may be pulled past its folder's edge to leave it
        dy = (dy + d).coerceIn(lo - slack, hi + slack)
        // inside a folder: pulled clearly past its first / last drawing row = leave the folder on release
        leave = if (ctx < 0) 0 else if (dy < lo - hs[from] * 0.5f) -1 else if (dy > hi + hs[from] * 0.5f) 1 else 0
        // top-level drawing row: hovering a group's bar = drop INTO it (the group stays put and lights up)
        var join = -1
        if (ctx < 0 && id >= 0) {
            val mid = tops[from] + dy + hs[from] / 2f
            for (j in keys.indices) {
                if (keys[j] >= 0 || j == from) continue
                if (mid >= tops[j] && mid <= tops[j] + minOf(hs[j], hs[from] * 1.6f)) { join = -2 - keys[j]; break }
            }
        }
        joinGroup = join
        val t = if (join >= 0 || leave != 0) from else nearest()
        if (t != target) { target = t; makeRoom() }
    }

    /** Items between the pick-up slot and the hover slot slide by the picked item's height toward the vacated place. */
    private fun makeRoom() {
        val step = hs[from] + gap
        for (j in keys.indices) {
            if (j == from) continue
            val goal = when {
                target > from && j in (from + 1)..target -> -step
                target < from && j in target until from -> step
                else -> 0f
            }
            val o = off(keys[j])
            o.job?.cancel()
            o.job = scope.launch { animate(o.v, goal, animationSpec = RowSettle) { v, _ -> o.v = v } }
        }
    }

    /** Dragging near the top / bottom edge of the track list scrolls it, the item keeps following the finger. */
    private fun autoScroll() {
        if (autoJob?.isActive == true) return
        autoJob = scope.launch {
            while (id != -1) {
                val top = tops[from] + dy
                val bottom = top + hs[from]
                val v0 = scroll.value.toFloat()
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
        if (id == -1) return
        autoJob?.cancel()
        val slot = target
        val rest = from
        val c = ctx
        val pid = id
        val join = joinGroup
        val lv = leave
        val order = keys.toMutableList().also { it.add(slot, it.removeAt(rest)) }
        val goal = if (join >= 0) 0f else slotTop(slot) - tops[rest]
        scope.launch {
            animate(dy, goal, animationSpec = RowSettle) { v, _ -> dy = v }
            // commit + reset in one snapshot so no frame shows the new order with the old offsets
            Snapshot.withMutableSnapshot {
                when {
                    join >= 0 -> state.moveRowIntoGroup(pid, join)
                    lv != 0 -> state.moveRowOutOfGroup(pid, above = lv < 0)
                    slot != rest -> state.reorderDrawItems(c, order)
                }
                offs.values.forEach { it.job?.cancel(); it.v = 0f }
                id = -1
                dy = 0f
                joinGroup = -1
                leave = 0
            }
        }
    }
}

/** Long press (no slop movement for the system timeout) picks the item up; then vertical drag reorders it. */
private fun Modifier.longPressReorder(rd: RowReorder, key: Int, group: Int, onPick: () -> Unit): Modifier =
    pointerInput(key, group) {
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
            if (cancelled || !timedOut || !rd.start(key, group)) return@awaitEachGesture
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
 *  - groups           : select tracks (Select mode) -> Group. A folder holds drawing rows AND audio layers: arrow = open / close,
 *                       ••• = rename / lock / ungroup, tap its bar to select it, then drag the bar to move every member in time,
 *                       long-press its header to move the whole folder front / behind (rows inside only reorder inside it)
 *  - group time       : ••• -> Re-timing (Off / Freeze / Stretch / Loop / Loop & Stretch / Blank). It is only a time mapping, nothing is
 *                       generated: drag the big handle at the end of the bar longer / shorter any time; edit the original drawings
 *  - name tags        : every row shows its name at the top-left; long-press the tag to rename
 */
@Composable
fun TimelineEditor(state: EditorState, modifier: Modifier = Modifier) {
    val tl = state.timeline
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer(cacheSize = 256)  // one cached layout per name tag + ruler label + cel label
    val fling = remember { FlingHolder() }
    val vScroll = rememberScrollState()
    val waveTmp = remember { FloatArray(2 * WAVE_CHUNK) }
    val wavePts = remember { FloatArray(4 * 1500) }
    val wavePaint = remember { android.graphics.Paint().apply { isAntiAlias = true; color = Color.White.copy(alpha = 0.85f).toArgb() } }
    val waves = remember { WaveCache() }
    val thumbs = remember { ThumbCache() }
    // PERF: everything the canvas reads per frame is derived ONCE per edit (derivedStateOf), not once per draw. While you
    // pan or scrub nothing here changes, so a frame only walks what is visible.
    val rowCelsState = remember(state) { derivedStateOf { buildRowCels(state.cels) } }
    val clipsByTrackState = remember(tl) { derivedStateOf { tl.clips.groupBy { it.track } } }
    val groupGeomState = remember(state) { derivedStateOf { buildGroupGeom(state) } }
    val envBuf = remember { FloatArray(240) }
    val rd = remember(state, vScroll) { RowReorder(state, scope, vScroll) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { state.importAudio(context, uri, state.frame * 1000L / state.fps) }
    }

    // clip table -> native mixer, content length -> scroll limits
    LaunchedEffect(tl) { snapshotFlow { tl.clips.toList() to tl.tracks.toList() }.collect { tl.pushToNative() } }
    LaunchedEffect(tl) {
        // no fixed cap anywhere: the timeline is as long as its longest drawing track / audio layer
        snapshotFlow {
            max(max(state.frameCount, state.groups.maxOfOrNull { it.endFrame } ?: 0) / state.fps.toFloat(), tl.endMs() / 1000f)
        }.collect {
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
    val groupH = with(density) { GROUP_H.toPx() }
    val gap = with(density) { GAP.toPx() }
    val handleTouch = with(density) { 20.dp.toPx() }
    val keyBand = with(density) { 24.dp.toPx() }    // bottom strip of a row where its keyframe marks live
    val keyTouch = with(density) { 14.dp.toPx() }
    val celTop = rulerPx + gap

    // name tag: the little label pinned to the top-left of every row (drawing row, audio layer, group)
    val tagBase = TextStyle(fontSize = 9.sp)
    val tagPadX = with(density) { 5.dp.toPx() }
    val tagH = with(density) { 14.dp.toPx() }
    val tagX = with(density) { 4.dp.toPx() }
    val tagY = with(density) { 3.dp.toPx() }
    val tagMaxW = with(density) { 150.dp.toPx() }
    fun tagW(name: String): Float = min(measurer.measure(name, tagBase).size.width + 2 * tagPadX, tagMaxW)

    // NOT vals: the pointerInput(Unit) block below captures these local functions once, so the rows have
    // to be read live or hit-testing keeps using the track list from the first composition.
    val layoutState = remember(state, celTop, celH, audioH, groupH, gap) {
        derivedStateOf { placeRows(buildRows(state), celTop, celH, audioH, groupH, gap) }
    }
    fun layout(): List<Placed> = layoutState.value
    rd.layout = { layout() }
    rd.gap = gap
    rd.edge = with(density) { 16.dp.toPx() }
    // read in composition so the canvas height follows the rows (adding / grouping / opening / closing)
    val contentBottom = layout().lastOrNull()?.let { it.top + it.h } ?: rulerPx
    val contentH: Dp = with(density) { contentBottom.toDp() }

    fun xOfSec(s: Float) = s * tl.pxPerSec - tl.scrollPx
    fun xOfMs(ms: Long) = xOfSec(ms / 1000f)
    fun msAtX(x: Float) = ((x + tl.scrollPx) / tl.pxPerSec * 1000f).toLong()
    fun seekAt(x: Float) = state.seek(floor((x + tl.scrollPx) / tl.pxPerSec * state.fps).toInt())

    /** The audio layer under [y] (the nearest one when the finger is above / below / between them); closed groups hide theirs. */
    fun audioTrackAt(y: Float): AudioTrack? {
        val a = layout().filter { it.row is TlRow.Audio }
        if (a.isEmpty()) return null
        val pick = a.firstOrNull { y <= it.top + it.h + gap / 2f } ?: a.last()
        return (pick.row as TlRow.Audio).t
    }

    fun hit(p: Offset, labels: Boolean = true): Hit {
        if (p.y < rulerPx) return Hit.Ruler
        val pl = layout().firstOrNull { p.y >= it.top && p.y <= it.top + it.h } ?: return Hit.Empty
        fun onTag(name: String) = labels && p.y <= pl.top + tagY + tagH && p.x <= tagX + tagW(name)
        when (val r = pl.row) {
            is TlRow.Group -> {
                val gid = r.grp.id
                val live = r.grp.fill != FILL_NONE
                if ((gid == state.selectedGroup || live) && !r.locked) {
                    // the end is easy to grab: a wide strip around the handle, and for a re-timed group the whole re-timed part
                    val endX = xOfSec(state.groupBarEndFrame(gid) / state.fps.toFloat())
                    val srcX = state.groupSourceSpan(gid)?.second?.let { xOfSec(it / state.fps.toFloat()) } ?: endX
                    val from = if (live) min(srcX, endX - handleTouch * 1.5f) else endX - handleTouch * 1.5f
                    if (p.x >= from && p.x <= endX + handleTouch * 1.5f) return Hit.GroupEnd(gid)
                }
                if (onTag(r.grp.name)) return Hit.Label(RenameTarget.Group(r.grp.id))
                return Hit.Group(r.grp.id)
            }
            is TlRow.Draw -> {
                val t = r.t
                if (onTag(t.name)) return Hit.Label(RenameTarget.Draw(t.id))
                // keyframe marks win over the cel body / trim handle in their strip
                if (!t.locked && t.allKeys.isNotEmpty() && p.y >= pl.top + pl.h - keyBand) {
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
            is TlRow.Audio -> {
                val track = r.t
                // trim handles of the selected clip win over the name tag
                tl.clip(tl.selectedClip)?.takeIf { it.track == track.id && !track.locked }?.let { c ->
                    if (abs(p.x - xOfMs(c.startMs)) < handleTouch) return Hit.AudioL(c.id)
                    if (abs(p.x - xOfMs(c.startMs + c.lenMs)) < handleTouch) return Hit.AudioR(c.id)
                }
                if (onTag(track.name)) return Hit.Label(RenameTarget.Audio(track.id))
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
        // select-tracks mode: group / ungroup what is ticked
        if (state.trackSelectMode) GroupBar(state)
        state.renameTarget?.let { RenameDialog(state, it) }
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
                            // Re-order: long-press a drawing, then drag. Left / right = reorder inside its row, up / down = drop it into
                            // another drawing row, or onto a group bar (it joins that folder's top row). One undo step. Runs in the
                            // Initial pass and only consumes once it has picked the drawing up, so taps / pans / trims are untouched.
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                val h = hit(down.position, labels = false) as? Hit.Cel ?: return@awaitEachGesture
                                if (tl.selectedClip >= 0 || !state.canLiftCel(h.track, h.index)) return@awaitEachGesture
                                val lpSlop = viewConfiguration.touchSlop
                                var cancelled = false
                                val timedOut = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                    while (!cancelled) {
                                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                                        val c = ev.changes.firstOrNull { it.id == down.id }
                                        if (c == null || !c.pressed || (c.position - down.position).getDistance() > lpSlop) cancelled = true
                                    }
                                } == null
                                if (cancelled || !timedOut) return@awaitEachGesture
                                val celId = state.trackCels(h.track)[h.index].id
                                val before = state.snapshot()
                                down.consume()
                                state.playing = false
                                state.activeTrack = h.track
                                state.seek(state.celStart(h.index, h.track))
                                state.liftedCel = celId
                                var moved = false
                                while (true) {
                                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!c.pressed) { c.consume(); break }
                                    c.consume()
                                    val p = c.position
                                    val f = (p.x + tl.scrollPx) / tl.pxPerSec * state.fps
                                    // near the top / bottom edge of the list: scroll it so every row can be reached
                                    val vy = p.y - vScroll.value
                                    if (vy < rd.edge) vScroll.dispatchRawDelta(-10f) else if (vy > rd.viewportH - rd.edge) vScroll.dispatchRawDelta(10f)
                                    var target = state.celTrack(celId)
                                    state.dropGroup = -1
                                    when (val row = layout().firstOrNull { p.y >= it.top && p.y <= it.top + it.h }?.row) {
                                        is TlRow.Draw -> if (!row.t.locked) target = row.t.id
                                        is TlRow.Group -> state.groupDropTrack(row.grp.id).let { if (it >= 0) { target = it; state.dropGroup = row.grp.id } }
                                        else -> {}
                                    }
                                    state.dropTrack = target
                                    if (state.carryCel(celId, target, f)) moved = true
                                }
                                state.liftedCel = -1; state.dropTrack = -1; state.dropGroup = -1
                                if (moved) state.commitEdit(before)
                            }
                        }
                        .pointerInput(Unit) {
                            val slop = viewConfiguration.touchSlop
                            val onBgTap: (Offset) -> Unit = { p ->
                                val hh = hit(p, labels = false)   // a tap on a name tag acts like a tap on the row under it
                                tl.selectedClip = -1
                                state.selectedGroup = -1
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
                                        var lastDf = Int.MIN_VALUE
                                        drag(down.id) { c ->
                                            val df = ((c.position.x - down.position.x) / tl.pxPerSec * state.fps).roundToInt()
                                            if (df != lastDf) {   // PERF: a pointer move inside the same frame changes nothing
                                                lastDf = df
                                                val r = state.moveAllKeys(tr, cur, h.frame + df)
                                                if (r != cur) { cur = r; moved = true; state.seek(off + cur) }
                                            }
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
                                        var lastLen = Int.MIN_VALUE
                                        drag(down.id) { c ->
                                            val df = (c.position.x - down.position.x) / tl.pxPerSec * state.fps
                                            val len = len0 + df.roundToInt()
                                            if (len != lastLen) { lastLen = len; state.setLen(cur, len, tr) }
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
                                                audioTrackAt(c.position.y)?.let { if (!it.locked) track = it.id }
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
                                        var lastDf = Int.MIN_VALUE
                                        drag(down.id) { c ->
                                            val df = ((c.position.x - down.position.x) / tl.pxPerSec * state.fps).roundToInt()
                                            if (df != lastDf) {
                                                lastDf = df
                                                state.setTrackOffset(tr, off0 + df)
                                                state.frame = (frame0 + (state.trackOffset(tr) - off0)).coerceAtLeast(0)
                                            }
                                            c.consume()
                                        }
                                        state.commitEdit(before)
                                    } else {
                                        panZoomTap(down, tl, slop, onTap = onBgTap, onFling = { v -> startFling(scope, fling, tl, v) })
                                    }
                                    is Hit.GroupEnd -> {
                                        // set the group length; one undo step. The group's Re-timing mode (group menu) decides what the extra time shows.
                                        down.consume()
                                        val g = h.id
                                        val before = state.snapshot()
                                        val base = state.groupBarEndFrame(g)
                                        val tol = max(1, (10.dp.toPx() / tl.pxPerSec * state.fps).roundToInt())
                                        var lastDf = Int.MIN_VALUE
                                        drag(down.id) { c ->
                                            val df = ((c.position.x - down.position.x) / tl.pxPerSec * state.fps).roundToInt()
                                            if (df != lastDf) {   // snap + regenerate the loop only when the end moved a whole frame
                                                lastDf = df
                                                state.setGroupEnd(g, state.snapGroupEnd(g, base + df, tol))
                                            }
                                            c.consume()
                                        }
                                        state.commitEdit(before)
                                    }
                                    is Hit.Group -> {
                                        val g = h.id
                                        val span = state.groupSpanSec(g)
                                        val onBar = span != null && down.position.x in xOfSec(span.start)..xOfSec(span.endInclusive)
                                        if (g == state.selectedGroup && onBar && !state.isGroupLocked(g)) {
                                            // selected group bar: drag = move every member in time, together (one undo step)
                                            down.consume()
                                            val before = state.snapshot()
                                            val shift = state.beginGroupShift(g)
                                            var lastDf = Int.MIN_VALUE
                                            drag(down.id) { c ->
                                                val df = ((c.position.x - down.position.x) / tl.pxPerSec * state.fps).roundToInt()
                                                if (df != lastDf) { lastDf = df; state.shiftGroup(shift, df) }
                                                c.consume()
                                            }
                                            state.commitEdit(before)
                                        } else {
                                            panZoomTap(
                                                down, tl, slop,
                                                onTap = { tl.selectedClip = -1; state.selectedGroup = g },
                                                onFling = { v -> startFling(scope, fling, tl, v) },
                                            )
                                        }
                                    }
                                    is Hit.Label -> when (labelPress(down, slop)) {
                                        // held: rename. lifted early: a plain tap on the row. moved: a pan.
                                        1 -> { down.consume(); state.renameTarget = h.target; waitForUpOrCancellation() }
                                        0 -> onBgTap(down.position)
                                        else -> panZoomTap(down, tl, slop, onTap = onBgTap, onFling = { v -> startFling(scope, fling, tl, v) })
                                    }
                                    else -> panZoomTap(down, tl, slop, onTap = onBgTap, onFling = { v -> startFling(scope, fling, tl, v) })
                                }
                            }
                        }
                ) {
                    val w = size.width
                    val pps = tl.pxPerSec
                    val fps = state.fps
                    val rowCels = rowCelsState.value
                    val clipsByTrack = clipsByTrackState.value
                    val groupGeom = groupGeomState.value
                    fun x(sec: Float) = sec * pps - tl.scrollPx

                    // ---- ruler ----
                    drawRuler(measurer, tl, fps, w, rulerPx, tick, labelCol)

                    // ---- rows: group bars, drawing rows (top of the stack first), audio layers ----
                    val tagBg = cs.surface.copy(alpha = 0.78f)
                    // name tag pinned to the top-left of a row (stays visible while the timeline scrolls); returns its right edge
                    fun tag(top: Float, name: String, bg: Color, fg: Color): Float {
                        val tw = tagW(name)
                        drawRoundRect(bg, Offset(tagX, top + tagY), Size(tw, tagH), CornerRadius(4.dp.toPx()))
                        drawText(
                            measurer, name, Offset(tagX + tagPadX, top + tagY + 1.dp.toPx()),
                            style = tagBase.copy(color = fg), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            size = Size(max(0f, tw - 2 * tagPadX), tagH),
                        )
                        return tagX + tw
                    }
                    val round = CornerRadius(6.dp.toPx())

                    // the picked item (a row, or a whole group) is painted last: on top of the rows it passes
                    val placedNow = layout()
                    for (pl in (if (rd.id == -1) placedNow else placedNow.sortedBy { if (rd.isLifted(it.row)) 1 else 0 })) {
                    val row = pl.row
                    val lifted = rd.isLifted(row)
                    val rowTop = pl.top + rd.offsetOf(row)
                    val accent: Color? = if (row !is TlRow.Group && row.groupId >= 0) groupColor(row.groupId) else null
                    when (row) {
                    is TlRow.Group -> {
                        val gid = row.grp.id
                        val gc = groupColor(gid)
                        if (lifted) drawRoundRect(Color.Black.copy(alpha = 0.28f), Offset(0f, rowTop + 3.dp.toPx()), Size(w, groupH), round)
                        drawRoundRect(gc.copy(alpha = if (lifted) 0.32f else 0.16f), Offset(0f, rowTop), Size(w, groupH), round)
                        if (state.trackSelectMode && state.groupMembersSelected(gid)) drawRoundRect(Selected.copy(alpha = 0.22f), Offset(0f, rowTop), Size(w, groupH), round)
                        if (state.dropGroup == gid || rd.joinGroup == gid) {
                            drawRoundRect(Selected.copy(alpha = 0.18f), Offset(0f, rowTop), Size(w, groupH), round)
                            drawRoundRect(Selected, Offset(0f, rowTop), Size(w, groupH), round, style = Stroke(3.dp.toPx()))
                        }
                        val gg = groupGeom[gid]
                        val span = gg?.span
                        if (span != null && gg != null) {
                            val gx0 = x(span.start)
                            val gx1 = x(span.endInclusive)
                            val gxe = x(gg.barEnd / fps.toFloat())   // stretched end (>= gx1)
                            if (gxe >= 0f && gx0 <= w) {
                                val by = rowTop + 3.dp.toPx()
                                val bh = groupH - 6.dp.toPx()
                                val bw = max(gx1 - gx0, 2.dp.toPx())
                                drawRoundRect(gc.copy(alpha = if (row.open) 0.42f else 0.78f), Offset(gx0, by), Size(bw, bh), CornerRadius(5.dp.toPx()))
                                val srcEndF = if (row.grp.fill != FILL_NONE) gg.srcEnd else null
                                val srcStartF = gg.srcStart
                                if (srcEndF != null && srcStartF != null) {
                                    // re-timed part of the bar. Pure drawing: nothing is generated, so only the repeat marks on screen are touched
                                    val sx = x(srcEndF / fps.toFloat())
                                    if (gxe > sx + 1f) {
                                        drawRoundRect(Color.White.copy(alpha = 0.18f), Offset(sx, by), Size(gxe - sx, bh), CornerRadius(5.dp.toPx()))
                                        drawLine(Color.White.copy(alpha = 0.8f), Offset(sx, by + 3.dp.toPx()), Offset(sx, by + bh - 3.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
                                        val mode = row.grp.fill
                                        val p = srcEndF - srcStartF
                                        val l = gg.barEnd - srcStartF
                                        var repeats = 0
                                        if (p > 0 && l > 0 && (mode == FILL_LOOP || mode == FILL_LOOP_STRETCH)) {
                                            val n = if (mode == FILL_LOOP) ceil(l.toFloat() / p).toInt() else max(1, Math.round(l.toFloat() / p))
                                            repeats = n
                                            val per = if (mode == FILL_LOOP) p.toFloat() else l.toFloat() / n
                                            if (per * pps / fps >= 3f) {   // marks closer than 3 dp would only be a smear
                                                val visL = floor(tl.scrollPx / pps * fps) - srcStartF
                                                val visR = ceil((tl.scrollPx + w) / pps * fps) - srcStartF
                                                val k0 = max(1, ceil(visL / per).toInt())
                                                val k1 = min(ceil(l / per).toInt() - 1, floor(visR / per).toInt())
                                                for (k in k0..k1) {
                                                    val mx = x((srcStartF + k * per) / fps)
                                                    drawLine(Color.White.copy(alpha = 0.45f), Offset(mx, by + 5.dp.toPx()), Offset(mx, by + bh - 5.dp.toPx()), 1.dp.toPx())
                                                }
                                            }
                                        }
                                        if (sx + 6.dp.toPx() < w && gxe - sx > 40.dp.toPx()) {
                                            drawText(
                                                measurer, retimeName(mode) + if (repeats > 1) " \u00d7$repeats" else "", Offset(sx + 6.dp.toPx(), by + 3.dp.toPx()),
                                                style = TextStyle(fontSize = 10.sp, color = Color.White),
                                                maxLines = 1, overflow = TextOverflow.Clip,
                                            )
                                        }
                                    }
                                }
                                if (gxe > gx1 + 1f) {
                                    // stretched part: empty while Re-timing is Off
                                    drawRoundRect(gc.copy(alpha = 0.16f), Offset(gx1, by), Size(gxe - gx1, bh), CornerRadius(5.dp.toPx()))
                                    drawRoundRect(gc, Offset(gx1, by), Size(gxe - gx1, bh), CornerRadius(5.dp.toPx()), style = Stroke(1.5f.dp.toPx()))
                                }
                                if (!row.open) {
                                    // closed folder: one thin line per member shows what is inside
                                    val spans = gg.members
                                    val n = min(spans.size, 8)
                                    if (n > 0) {
                                        val pad = 5.dp.toPx()
                                        val band = bh - 2 * pad
                                        for (k in 0 until n) {
                                            val sp = spans[k]
                                            val yy = by + pad + band * (k + 0.5f) / n
                                            drawLine(
                                                Color.White.copy(alpha = 0.7f), Offset(x(sp.start), yy), Offset(x(sp.endInclusive), yy),
                                                (band / n - 1f).coerceIn(1.5f, 3.dp.toPx()), StrokeCap.Round,
                                            )
                                        }
                                    }
                                }
                                if (state.selectedGroup == gid) drawRoundRect(Selected, Offset(gx0, by), Size(max(gxe - gx0, bw), bh), CornerRadius(5.dp.toPx()), style = Stroke(2.dp.toPx()))
                                // end handle: big, and always there while Re-timing is on
                                if ((state.selectedGroup == gid || row.grp.fill != FILL_NONE) && !row.locked) {
                                    drawRoundRect(Selected, Offset(gxe - 12.dp.toPx(), by + 2.dp.toPx()), Size(12.dp.toPx(), bh - 4.dp.toPx()), CornerRadius(4.dp.toPx()))
                                    drawLine(Color.Black.copy(alpha = 0.55f), Offset(gxe - 6.dp.toPx(), by + 7.dp.toPx()), Offset(gxe - 6.dp.toPx(), by + bh - 7.dp.toPx()), 1.5f.dp.toPx(), StrokeCap.Round)
                                }
                            }
                        }
                        tag(rowTop, "${row.grp.name} · ${row.count}", gc, Color.White)
                        if (lifted) drawRoundRect(Selected, Offset(0f, rowTop), Size(w, groupH), round, style = Stroke(2.dp.toPx()))
                    }
                    is TlRow.Draw -> {
                    val track = row.t
                    if (lifted) drawRoundRect(Color.Black.copy(alpha = 0.28f), Offset(0f, rowTop + 3.dp.toPx()), Size(w, celH), round)
                    drawRoundRect(if (lifted) rowBgLifted else rowBg, Offset(0f, rowTop), Size(w, celH), round)
                    if (accent != null) drawRoundRect(accent.copy(alpha = 0.10f), Offset(0f, rowTop), Size(w, celH), round)
                    if (state.trackSelectMode && track.id in state.selDraw) drawRoundRect(Selected.copy(alpha = 0.22f), Offset(0f, rowTop), Size(w, celH), round)
                    if (state.liftedCel >= 0 && track.id == state.dropTrack) drawRoundRect(Selected.copy(alpha = 0.14f), Offset(0f, rowTop), Size(w, celH), round)
                    if (track.clip) {   // clipping arrow: this row only shows where the row below has pixels
                        val ax = 7.dp.toPx(); val ay = rowTop + 5.dp.toPx(); val st = 1.6.dp.toPx()
                        drawLine(Selected, Offset(ax, ay), Offset(ax, ay + 9.dp.toPx()), st)
                        drawLine(Selected, Offset(ax, ay + 9.dp.toPx()), Offset(ax + 9.dp.toPx(), ay + 9.dp.toPx()), st)
                        drawLine(Selected, Offset(ax + 6.dp.toPx(), ay + 6.dp.toPx()), Offset(ax + 9.dp.toPx(), ay + 9.dp.toPx()), st)
                        drawLine(Selected, Offset(ax + 6.dp.toPx(), ay + 12.dp.toPx()), Offset(ax + 9.dp.toPx(), ay + 9.dp.toPx()), st)
                    }
                    if (lifted && rd.leave != 0) drawRoundRect(Selected, Offset(0f, rowTop), Size(w, celH), round, style = Stroke(3.dp.toPx())) // lets go of its folder on release
                    val rc = rowCels[track.id]
                    // the drawing the playhead SHOWS in this row; inside a re-timed group that is the source drawing of the repeat
                    val shownF = state.rowFrame(track.id, state.frame)
                    val cur = if (shownF < 0) -1 else rc?.indexAt(shownF - track.offset) ?: -1
                    val celSelected = tl.selectedClip < 0 && track.id == state.activeTrack
                    // PERF: only the part of this row that is on screen is touched (row-local visible frames), however long the row is
                    val relL = floor(tl.scrollPx / pps * fps).toInt() - track.offset
                    val relR = ceil((tl.scrollPx + w) / pps * fps).toInt() - track.offset
                    if (rc != null) {
                        val n = rc.cels.size
                        val i0 = if (relL < 0) 0 else rc.indexAt(relL).let { if (it < 0) n else it }
                        val i1 = if (relR < 0) -1 else rc.indexAt(relR).let { if (it < 0) n - 1 else it }
                        val fw = pps / fps
                        val th = celH - 8.dp.toPx()
                        val thPx = th.roundToInt().coerceAtLeast(1)
                        val layerVis = state.layersOf(track.id).fold(7) { h, l -> if (l.visible) 31 * h + l.id else h }
                        for (i in i0..i1) {
                            val c = rc.cels[i]
                            val start = track.offset + rc.rel[i]
                            val x0 = x(start / fps.toFloat())
                            val x1 = x((start + c.len) / fps.toFloat())
                            val cw = x1 - x0 - 2.dp.toPx()
                            drawRoundRect(if (i == cur) celSelCol else celCol, Offset(x0 + 1.dp.toPx(), rowTop), Size(cw, celH), CornerRadius(6.dp.toPx()))

                            if (fw >= 7f) {
                                val k0 = max(1, ceil((0f - x0) / fw).toInt())
                                val k1 = min(c.len - 1, floor((w - x0) / fw).toInt())
                                for (k in k0..k1) {
                                    val hx = x0 + k * fw
                                    drawLine(labelCol.copy(alpha = 0.25f), Offset(hx, rowTop + celH * 0.7f), Offset(hx, rowTop + celH - 5.dp.toPx()), 1.dp.toPx())
                                }
                            }
                            // thumbnail of the drawing. PERF: painted into a bitmap ONCE and then just blitted.
                            if (cw > th + 8.dp.toPx()) {
                                val tx = x0 + 5.dp.toPx()
                                val ty = rowTop + 4.dp.toPx()
                                run {
                                    val srcId = c.id
                                    val sig = 31 * (state.celStrokeSig[srcId] ?: 0) + layerVis
                                    thumbs.landed   // read: redraw when a background thumbnail lands
                                    val layersNow = state.layersOf(track.id)
                                    val strokesNow = state.strokesByCel[srcId] ?: emptyList()
                                    val dens = Density(this.density, this.fontScale)
                                    val dir = this.layoutDirection
                                    val img = thumbs.get(srcId, sig, thPx, scope) { image ->
                                        CanvasDrawScope().draw(dens, dir, GfxCanvas(image), Size(thPx.toFloat(), thPx.toFloat())) {
                                            drawRect(paper)
                                            paintLayers(layersNow, strokesNow, size.width)
                                        }
                                    }
                                    if (img != null) drawImage(img, Offset(tx, ty))
                                }
                                if (cw > th + 60.dp.toPx()) {
                                    drawText(
                                        measurer, "${i + 1} · ${c.len}f", Offset(tx + th + 6.dp.toPx(), rowTop + 6.dp.toPx()),
                                        style = TextStyle(fontSize = 10.sp, color = cs.onSecondaryContainer),
                                        maxLines = 1, overflow = TextOverflow.Clip,
                                    )
                                }
                            }
                            if (c.id == state.liftedCel) {
                                drawRoundRect(Selected.copy(alpha = 0.30f), Offset(x0 + 1.dp.toPx(), rowTop), Size(cw, celH), CornerRadius(6.dp.toPx()))
                                drawRoundRect(Selected, Offset(x0 + 1.dp.toPx(), rowTop), Size(cw, celH), CornerRadius(6.dp.toPx()), style = Stroke(3.dp.toPx()))
                            }
                            if (i == cur && celSelected) {
                                drawRoundRect(Selected, Offset(x0 + 1.dp.toPx(), rowTop), Size(cw, celH), CornerRadius(6.dp.toPx()), style = Stroke(2.dp.toPx()))
                                if (!track.locked && shownF == state.frame) {   // the trim handle only exists on real (not re-timed) frames
                                    drawRoundRect(Selected, Offset(x1 - 9.dp.toPx(), rowTop + 8.dp.toPx()), Size(7.dp.toPx(), celH - 16.dp.toPx()), CornerRadius(3.dp.toPx()))
                                }
                            }
                        }
                    }
                    // keyframes: one mark per frame that carries a key on ANY property (diamond = eased, square = every key there is Hold).
                    // PERF: marks are sorted, so binary-search the visible window instead of walking (and filtering) every key.
                    val marks = track.markFrames
                    if (marks.isNotEmpty()) {
                        val ky = rowTop + celH - 10.dp.toPx()
                        val holds = track.markHold
                        val m0 = (marks.lowerBound(relL) - 1).coerceAtLeast(0)
                        for (i in m0 until marks.size - 1) {
                            if (marks[i] > relR) break
                            drawLine(
                                Selected.copy(alpha = 0.55f),
                                Offset(x((track.offset + marks[i]) / fps.toFloat()), ky),
                                Offset(x((track.offset + marks[i + 1]) / fps.toFloat()), ky),
                                2.dp.toPx(), StrokeCap.Round,
                            )
                        }
                        for (i in m0 until marks.size) {
                            val fr = marks[i]
                            if (fr > relR + 1) break
                            val kx = x((track.offset + fr) / fps.toFloat())
                            if (kx < -10f || kx > w + 10f) continue
                            drawKeyMark(Offset(kx, ky), 6.dp.toPx(), holds[i], track.id == state.activeTrack && fr == state.selectedKey, cs.tertiary)
                        }
                    }
                    if (accent != null) drawRoundRect(accent, Offset(0f, rowTop + 3.dp.toPx()), Size(3.dp.toPx(), celH - 6.dp.toPx()), CornerRadius(2.dp.toPx()))
                    // name tag, top-left; the active row's tag is highlighted
                    tag(rowTop, track.name, if (celSelected) cs.primaryContainer else tagBg, if (celSelected) cs.onPrimaryContainer else cs.onSurface)
                    if (lifted) drawRoundRect(Selected, Offset(0f, rowTop), Size(w, celH), round, style = Stroke(2.dp.toPx()))
                    }
                    is TlRow.Audio -> {
                        val track = row.t
                        val top = rowTop
                        if (lifted) drawRoundRect(Color.Black.copy(alpha = 0.28f), Offset(0f, top + 3.dp.toPx()), Size(w, audioH), round)
                        drawRoundRect(if (lifted) rowBgLifted else rowBg, Offset(0f, top), Size(w, audioH), round)
                        if (accent != null) drawRoundRect(accent.copy(alpha = 0.10f), Offset(0f, top), Size(w, audioH), round)
                        if (state.trackSelectMode && track.id in state.selAudio) drawRoundRect(Selected.copy(alpha = 0.22f), Offset(0f, top), Size(w, audioH), round)
                        val tagRight = tagX + tagW(track.name)
                        for (c in clipsByTrack[track.id] ?: emptyList()) {
                            val x0 = xOfMs(c.startMs)
                            val x1 = xOfMs(c.startMs + c.lenMs)
                            if (x1 < 0f || x0 > w) continue
                            val dim = if (track.muted) 0.45f else 1f
                            drawRoundRect(AudioBlue.copy(alpha = dim), Offset(x0, top), Size(x1 - x0, audioH), CornerRadius(6.dp.toPx()))
                            drawWave(c, tl, x0, x1, w, top, audioH, waves, waveTmp, wavePts, wavePaint)
                            if (c.gainKeys.isNotEmpty()) drawGainLine(c, tl, x0, x1, w, top, audioH, envBuf, state.activeKeyDot(c))
                            // the clip's file name starts after the track's name tag when the two would overlap
                            val nameX = max(max(x0, 0f) + 6.dp.toPx(), if (x0 < tagRight) tagRight + 4.dp.toPx() else 0f)
                            val nameW = min(x1, w) - nameX - 6.dp.toPx()
                            if (nameW > 12.dp.toPx()) {
                                drawText(
                                    measurer, c.name, Offset(nameX, top + 2.dp.toPx()),
                                    style = TextStyle(fontSize = 10.sp, color = Color.White.copy(alpha = dim)),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    size = Size(nameW, 14.dp.toPx()),
                                )
                            }
                            if (c.id == tl.selectedClip) {
                                drawRoundRect(Selected, Offset(x0, top), Size(x1 - x0, audioH), CornerRadius(6.dp.toPx()), style = Stroke(2.dp.toPx()))
                                if (!track.locked) {
                                    drawRoundRect(Selected, Offset(x0, top + 4.dp.toPx()), Size(8.dp.toPx(), audioH - 8.dp.toPx()), CornerRadius(3.dp.toPx()))
                                    drawRoundRect(Selected, Offset(x1 - 8.dp.toPx(), top + 4.dp.toPx()), Size(8.dp.toPx(), audioH - 8.dp.toPx()), CornerRadius(3.dp.toPx()))
                                }
                            }
                        }
                        if (accent != null) drawRoundRect(accent, Offset(0f, top + 3.dp.toPx()), Size(3.dp.toPx(), audioH - 6.dp.toPx()), CornerRadius(2.dp.toPx()))
                        tag(top, track.name, tagBg, cs.onSurface)
                        if (lifted) drawRoundRect(Selected, Offset(0f, top), Size(w, audioH), round, style = Stroke(2.dp.toPx()))
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

/** Name-tag press: 1 = held for the long-press time (rename), 0 = lifted before that (a tap), 2 = moved past the slop (a pan; finger still down). */
private suspend fun AwaitPointerEventScope.labelPress(down: PointerInputChange, slop: Float): Int {
    var result = 1
    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        while (true) {
            val ev = awaitPointerEvent()
            val c = ev.changes.firstOrNull { it.id == down.id }
            if (c == null || !c.pressed) { result = 0; break }
            if ((c.position - down.position).getDistance() > slop) { result = 2; break }
        }
    }
    return result
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

private const val WAVE_CHUNK = 128   // columns per native call / cache chunk

/**
 * Waveform peaks of a clip, anchored to the CLIP (column j = the j-th 2dp slice of the clip) instead of the screen.
 * Panning therefore never calls into C++ again: a chunk is asked once per (clip, zoom) and then re-used.
 */
private class WaveCache {
    class Entry(val cols: Int) { val chunks = arrayOfNulls<FloatArray>((cols + WAVE_CHUNK - 1) / WAVE_CHUNK) }
    private data class K(val handle: Int, val name: String, val srcDurMs: Long, val inMs: Long, val lenMs: Long, val pps: Float, val step: Float)

    private val map = object : LinkedHashMap<K, Entry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry>) = size > 40
    }

    fun entry(c: AudioClip, pps: Float, step: Float): Entry =
        map.getOrPut(K(c.handle, c.name, c.srcDurMs, c.inMs, c.lenMs, pps, step)) {
            Entry(ceil(c.lenMs / 1000f * pps / step).toInt().coerceAtLeast(1))
        }

    /** min / max pairs of chunk [ci] (columns ci * WAVE_CHUNK ...). */
    fun chunk(e: Entry, c: AudioClip, ci: Int, pps: Float, step: Float, tmp: FloatArray): FloatArray {
        e.chunks[ci]?.let { return it }
        val first = ci * WAVE_CHUNK
        val n = min(WAVE_CHUNK, e.cols - first)
        val msPerCol = (step / pps) * 1000.0
        val srcA = c.inMs + first * msPerCol
        val srcB = min(srcA + n * msPerCol, (c.inMs + c.lenMs).toDouble())
        AudioHandlerNative.waveform(c.handle, srcA, srcB, n, tmp)
        return tmp.copyOf(n * 2).also { e.chunks[ci] = it }
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
    cache: WaveCache,
    tmp: FloatArray,
    pts: FloatArray,
    paint: android.graphics.Paint,
) {
    val vx0 = max(x0, 0f)
    val vx1 = min(x1, w)
    if (vx1 - vx0 < 2f) return
    val pps = tl.pxPerSec
    val step = 2.dp.toPx()
    val e = cache.entry(c, pps, step)
    val j0 = floor((vx0 - x0) / step).toInt().coerceIn(0, e.cols - 1)
    val j1 = floor((vx1 - x0) / step).toInt().coerceIn(j0, e.cols - 1)
    val n = min(j1 - j0 + 1, pts.size / 4)

    val wTop = top + 15.dp.toPx()
    val hh = (h - 15.dp.toPx() - 3.dp.toPx()) / 2f
    val mid = wTop + hh
    var o = 0
    for (i in 0 until n) {
        val j = j0 + i
        val ch = cache.chunk(e, c, j / WAVE_CHUNK, pps, step, tmp)
        val k = (j % WAVE_CHUNK) * 2
        var y0 = mid - ch[k + 1] * hh
        var y1 = mid - ch[k] * hh
        if (y1 - y0 < 1.5f) { y0 = mid - 0.75f; y1 = mid + 0.75f }
        val x = x0 + (j + 0.5f) * step
        pts[o++] = x; pts[o++] = y0; pts[o++] = x; pts[o++] = y1
    }
    paint.strokeWidth = step * 0.75f
    // one native call for the whole waveform instead of one drawLine per column
    drawIntoCanvas { it.nativeCanvas.drawLines(pts, 0, o, paint) }
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
    val rows = buildRows(state)
    Column(Modifier.width(HEADER_W), verticalArrangement = Arrangement.spacedBy(GAP)) {
        Spacer(Modifier.height(RULER_H))
        // same order as the canvas: drawing rows (top of the stack first), then audio layers; groups are folders in between
        for (row in rows) {
            key(row.stableKey) {
                // every row follows the reorder animation; the picked item (a row, or a whole group) is lifted
                val lifted = rd.isLifted(row)
                val pick: Modifier = when (row) {
                    is TlRow.Draw ->
                        Modifier.longPressReorder(rd, row.t.id, row.g) { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
                    is TlRow.Group ->
                        if (row.hasDraw) Modifier.longPressReorder(rd, groupKey(row.grp.id), -1) { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
                        else Modifier
                    is TlRow.Audio -> Modifier
                }
                Box(
                    Modifier
                        .zIndex(if (lifted) 2f else 0f)
                        .offset { IntOffset(0, rd.offsetOf(row).roundToInt()) }
                        .then(if (lifted) Modifier.background(cs.surfaceContainerHighest, RoundedCornerShape(6.dp)) else Modifier)
                        .then(pick)
                ) {
                    when (row) {
                        is TlRow.Group -> GroupHeader(state, row)
                        is TlRow.Draw -> {
                            val t = row.t
                            HeaderCell(CEL_H, accent = if (row.g >= 0) groupColor(row.g) else null) {
                                if (state.trackSelectMode) {
                                    SelCell(t.name, t.id in state.selDraw) { state.toggleDrawSel(t.id) }
                                } else {
                                    MiniBtn(Ico.Brush, t.id == state.activeTrack, 24.dp) { tl.selectedClip = -1; state.activeTrack = t.id }
                                    MiniBtn(if (t.locked) Ico.Lock else Ico.Unlock, t.locked, 24.dp) {
                                        state.edit {
                                            val i = state.drawTracks.indexOfFirst { it.id == t.id }
                                            if (i >= 0) state.drawTracks[i] = state.drawTracks[i].copy(locked = !state.drawTracks[i].locked)
                                        }
                                    }
                                    // Edit Track / Blend / Clipping for the whole row
                                    var fxMenu by remember { mutableStateOf(false) }
                                    Box {
                                        MiniBtn(Ico.Blend, t.blend != 0 || t.opacity < 0.999f || t.clip, 24.dp) { fxMenu = true }
                                        DropdownMenu(expanded = fxMenu, onDismissRequest = { fxMenu = false }) {
                                            FxMenuItems(state, FxTarget.Row(t.id), t.id) { fxMenu = false }
                                        }
                                    }
                                }
                            }
                        }
                        is TlRow.Audio -> {
                            val t = row.t
                            HeaderCell(AUDIO_H, accent = if (row.g >= 0) groupColor(row.g) else null) {
                                if (state.trackSelectMode) {
                                    SelCell(t.name, t.id in state.selAudio) { state.toggleAudioSel(t.id) }
                                } else {
                                    MiniBtn(if (t.muted) Ico.VolumeOff else Ico.Volume, t.muted) {
                                        state.edit {
                                            val i = tl.tracks.indexOfFirst { it.id == t.id }
                                            if (i >= 0) tl.tracks[i] = tl.tracks[i].copy(muted = !tl.tracks[i].muted)
                                        }
                                    }
                                    MiniBtn(if (t.locked) Ico.Lock else Ico.Unlock, t.locked) {
                                        state.edit {
                                            val i = tl.tracks.indexOfFirst { it.id == t.id }
                                            if (i >= 0) tl.tracks[i] = tl.tracks[i].copy(locked = !tl.tracks[i].locked)
                                        }
                                    }
                                }
                            }
                        }
                    }
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

/** Header of a group folder: arrow = open / close, ••• = rename / lock / ungroup. In select mode: tick the whole group. */
@Composable
private fun GroupHeader(state: EditorState, row: TlRow.Group) {
    val g = row.grp
    HeaderCell(GROUP_H, accent = groupColor(g.id)) {
        if (state.trackSelectMode) {
            SelCell(g.name, state.groupMembersSelected(g.id)) { state.toggleGroupSel(g.id) }
        } else {
            ChevronBtn(row.open) { state.toggleCollapsed(g.id) }
            var menu by remember { mutableStateOf(false) }
            var retimeMenu by remember { mutableStateOf(false) }
            Box {
                MiniBtn(Ico.More, menu || g.blend != 0 || g.opacity < 0.999f || g.clip) { menu = true }
                DropdownMenu(expanded = retimeMenu, onDismissRequest = { retimeMenu = false }) {
                    for (m in RETIME_MODES) DropdownMenuItem(
                        text = { Text(if (g.fill == m) "\u2713 ${retimeName(m)}" else retimeName(m)) },
                        onClick = { retimeMenu = false; state.setGroupFill(g.id, m) },
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; state.renameTarget = RenameTarget.Group(g.id) })
                    DropdownMenuItem(text = { Text("Add drawing track here") }, enabled = !row.locked, onClick = { menu = false; state.addDrawTrackIn(g.id) })
                    // Blend / Clipping of the whole folder: only its drawing tracks are composited (audio members draw nothing)
                    if (state.groupDrawMembers(g.id).isNotEmpty()) FxMenuItems(state, FxTarget.Grp(g.id), -1) { menu = false }
                    // Re-timing: what the group shows after its content / when the bar is stretched. Drag the right edge of the bar to set the length.
                    DropdownMenuItem(text = { Text("Re-timing \u00b7 ${retimeName(g.fill)}") }, onClick = { menu = false; retimeMenu = true })
                    DropdownMenuItem(text = { Text("Clear stretch") }, enabled = g.fill == FILL_NONE && g.endFrame >= 0, onClick = { menu = false; state.clearGroupEnd(g.id) })
                    DropdownMenuItem(
                        text = { Text(if (row.locked) "Unlock group" else "Lock group") },
                        onClick = { menu = false; state.setGroupLocked(g.id, !row.locked) },
                    )
                    DropdownMenuItem(text = { Text("Ungroup") }, onClick = { menu = false; state.ungroup(g.id) })
                }
            }
        }
    }
}

/** [accent] = the group colour: a thin strip on the left edge of every row inside a group. */
@Composable
private fun HeaderCell(h: Dp, accent: Color? = null, content: @Composable RowScope.() -> Unit) {
    Box(Modifier.fillMaxWidth().height(h)) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
        if (accent != null) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(3.dp)
                    .fillMaxHeight(0.8f)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent)
            )
        }
    }
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
        ) { if (on) Text("✓", fontSize = 12.sp, color = cs.onPrimary) }
        Text(name, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = cs.onSurface)
    }
}

@Composable
private fun MiniBtn(kind: Ico, active: Boolean, size: Dp = 28.dp, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(size).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { FoxIcon(kind, tint = if (active) cs.primary else cs.onSurfaceVariant, iconSize = if (size < 28.dp) 16.dp else 18.dp) }
}

/** Folder arrow: points right when closed, down when open. */
@Composable
private fun ChevronBtn(open: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { FoxIcon(Ico.Chevron, Modifier.rotate(if (open) 90f else 0f), tint = cs.onSurface, iconSize = 18.dp) }
}

/** Select-tracks mode: how many rows are ticked + Group / Ungroup. */
@Composable
private fun GroupBar(state: EditorState) {
    val cs = MaterialTheme.colorScheme
    val pad = PaddingValues(horizontal = 10.dp)
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${state.selCount} selected", Modifier.weight(1f), fontSize = 12.sp, color = cs.onSurfaceVariant)
        TextButton(onClick = { state.groupSelectedTracks() }, enabled = state.selCount > 0, contentPadding = pad) { Text("Group") }
        TextButton(onClick = { state.ungroupSelectedTracks() }, enabled = state.selectionInGroup, contentPadding = pad) { Text("Ungroup") }
    }
}

/** Rename a drawing row, audio layer or group (long-press its name tag on the timeline, or a group's ••• menu). */
@Composable
private fun RenameDialog(state: EditorState, target: RenameTarget) {
    var text by remember(target) { mutableStateOf(state.nameOf(target)) }
    val done = { state.rename(target, text); state.renameTarget = null }
    AlertDialog(
        onDismissRequest = { state.renameTarget = null },
        title = { Text("Rename") },
        text = { OutlinedTextField(value = text, onValueChange = { text = it.take(32) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { done() }) { Text("OK") } },
        dismissButton = { TextButton(onClick = { state.renameTarget = null }) { Text("Cancel") } },
    )
}

// ------------------------------------------------------------------------------------------ import

/** Decode [uri] natively and drop it on the first unlocked audio track at the playhead (one undo step). */
suspend fun EditorState.importAudio(context: Context, uri: Uri, playheadMs: Long) {
    with(timeline) {
        loading = true
        error = null
        try {
            // keep a private copy inside the project, so the clip survives the picked Uri going away
            val stored = projectId?.let { ProjectRepository.get(context).copyAudioIn(it, context, uri) }
            val a = AudioHandlerNative.import(context, stored?.let { Uri.fromFile(it) } ?: uri)
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
                val clip = AudioClip(nextClipId(), track.id, a.handle, a.name, a.durationMs, start, 0L, a.durationMs, src = stored?.name ?: "")
                clips.add(clip)
                selectedClip = clip.id
            }
        } finally {
            loading = false
        }
    }
}
