package fox.foxiru.foxcat.fox2d.main_canvas

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
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
import fox.foxiru.foxcat.fox2d.jnicallers.NativeCanvas
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

// ======================================================================================================
// KeyGraph Editor (Blender-style layout: menu bar + box menus / channel list | graph / footer): graph view of the keyframes of the ACTIVE row (transform + rig channels). Lives entirely in this file
// (+ KeyGraphMath.kt for the pure maths) so EditorScreen.kt stays untouched except for hosting the panel in the Dock.
//   - every keyed channel = one curve; drag keys (frame + value), drag bezier handles, box-select, scrub the ruler
//   - Ease: presets / bezier   - Curve: auto-smooth tangents, relax, bake, reduce   - Keys: key all, insert, nudge, scale, flip
//   - Blend: copy / paste across rows with Replace / Mix / Add + cross-fade, repeat / ping-pong, reverse
// Every edit is ONE undo step (edit{} or snapshot()+commitEdit() for drags), same as the timeline / drawers.
// ======================================================================================================

/** One animated channel of one row: [rig] = rig channel space (fox_rig.h), else a transform [Chan] id. */
internal data class GKLane(val track: Int, val rig: Boolean, val chan: Int)

internal data class GKRef(val lane: GKLane, val frame: Int)

/** UI-only state of the editor. Not part of undo history, not saved. */
@Stable
internal class KeyGraphUi {
    var open by mutableStateOf(false)
    var focus by mutableStateOf<GKLane?>(null)
    var hidden by mutableStateOf<Set<GKLane>>(emptySet())
    var sel by mutableStateOf<Set<GKRef>>(emptySet())
    var addMode by mutableStateOf(false)
    var multi by mutableStateOf(false)
    /** Alt+click of the pen tool: tapping a point toggles corner (polygon vertex) <-> smooth (handles out).  (the old long-press toggle is gone so a resting finger never changes a point). */
    var convert by mutableStateOf(false)
    /** Show the handles of EVERY point of the focused curve, not only of the selected ones. */
    var allHandles by mutableStateOf(true)
    var norm by mutableStateOf(true)
    var aligned by mutableStateOf(true)
    var lock by mutableIntStateOf(0)            // 0 free, 1 frames only, 2 values only
    var big by mutableStateOf(false)
    /** Channel list column on the left of the graph. */
    var chans by mutableStateOf(true)
    var tab by mutableIntStateOf(0)
    var fam by mutableStateOf(EaseFamily.Cubic)
    var smoothMode by mutableStateOf(GKSmooth.Monotone)
    var tension by mutableFloatStateOf(1f)
    var bakeStep by mutableIntStateOf(2)
    var reducePct by mutableIntStateOf(2)
    var clip by mutableStateOf<GKClip?>(null)
    var blend by mutableStateOf(GKBlend.Replace)
    var mix by mutableFloatStateOf(0.5f)
    var fade by mutableIntStateOf(0)
    var repeatN by mutableIntStateOf(1)
    var pingPong by mutableStateOf(false)
    val view = GraphView(12f)
    /** Box-select rectangle being dragged. */
    var box by mutableStateOf<Pair<Offset, Offset>?>(null)
}

private val holders = WeakHashMap<EditorState, KeyGraphUi>()

/** The KeyGraph UI state of this editor, kept OUTSIDE EditorState (a weak side table) so that class does not grow. */
internal val EditorState.keyGraph: KeyGraphUi
    get() = synchronized(holders) { holders.getOrPut(this) { KeyGraphUi() } }

// ------------------------------------------------------------------------------------------------ data access

private val easeCache = HashMap<Easing, FloatArray>()

/** Easing curves from the native side, cached (a bezier drag creates many distinct ones, so the cache is capped). */
internal val GKS: EaseSampler = { e ->
    synchronized(easeCache) {
        if (easeCache.size > 600) easeCache.clear()
        easeCache.getOrPut(e) { NativeCanvas.easeCurve(e.id, e.x1, e.y1, e.x2, e.y2, 256) }
    }
}

internal fun EditorState.gkKeys(l: GKLane): List<ChanKey> {
    val t = drawTracks.firstOrNull { it.id == l.track } ?: return emptyList()
    return (if (l.rig) t.rig.keys else t.keys).filter { it.chan == l.chan }
}

/** Value of the lane while it has no keys. */
internal fun EditorState.gkStatic(l: GKLane): Float {
    val t = drawTracks.firstOrNull { it.id == l.track } ?: return 0f
    if (l.rig) return t.rig.baseOf(l.chan)
    return Chan.of(l.chan)?.let { t.xf.get(it) } ?: 0f
}

/** Value the lane has at the playhead right now. */
internal fun EditorState.gkNow(l: GKLane): Float =
    if (l.rig) rigValue(l.track, l.chan) else Chan.of(l.chan)?.let { trackXf(l.track).get(it) } ?: 0f

private fun EditorState.gkMaxF(track: Int, keys: List<ChanKey>): Int =
    max((trackSpan(track) - 1).coerceAtLeast(0), keys.lastOrNull()?.frame ?: 0)

/** Raw (wrap in edit / snapshot+commitEdit): replace ALL keys of the lane. An emptied lane keeps its playhead value as the static one. */
internal fun EditorState.gkPut(l: GKLane, keys: List<ChanKey>) {
    val i = drawTracks.indexOfFirst { it.id == l.track }
    if (i < 0) return
    val t = drawTracks[i]
    val old = if (l.rig) t.rig.keys else t.keys
    val rest = old.filter { it.chan != l.chan }
    val emptied = keys.isEmpty() && rest.size != old.size
    val keep = if (emptied) gkNow(l) else 0f
    val merged = (rest + keys.map { it.copy(chan = l.chan) }).sortedWith(ChanKeyOrder)
    if (l.rig) {
        var r = t.rig.copy(keys = merged)
        if (emptied) r = r.withBase(l.chan, keep)
        drawTracks[i] = t.copy(rig = r)
    } else {
        var xf = t.xf
        if (emptied) Chan.of(l.chan)?.let { xf = xf.set(it, keep) }
        drawTracks[i] = t.copy(keys = merged, xf = xf)
    }
}

internal fun gkRigLabel(rig: Rig, chan: Int): String {
    if (RigChan.isPin(chan)) return "Pin ${RigChan.pinOf(chan) + 1} · " + listOf("Offset X", "Offset Y", "Rotation")[RigChan.pinSlot(chan).coerceIn(0, 2)]
    if (RigChan.isBone(chan)) {
        val b = rig.bones.getOrNull(RigChan.boneOf(chan))?.name ?: "Bone ${RigChan.boneOf(chan) + 1}"
        return b + " · " + (BoneChan.values().getOrNull(RigChan.boneChanOf(chan))?.label ?: "?")
    }
    val k = RigChan.curveOf(chan)
    val name = rig.curves.getOrNull(k)?.name ?: "Curve ${k + 1}"
    val slot = RigChan.curveSlot(chan)
    if (slot == 0) return "$name · Deform"
    val j = slot - 1
    return "$name · Pt ${j / 3 + 1} " + listOf("X", "Y", "Thick")[j % 3]
}

internal fun EditorState.gkLabel(l: GKLane): String =
    if (l.rig) gkRigLabel(trackRig(l.track), l.chan) else Chan.of(l.chan)?.label?.trim() ?: "Chan ${l.chan}"

/** Does the rig own the object this channel belongs to (a clip pasted from another row may name a bone this one lacks)? */
private fun gkRigHas(rig: Rig, chan: Int): Boolean = when {
    RigChan.isPin(chan) -> RigChan.pinOf(chan) in rig.pins.indices
    RigChan.isBone(chan) -> RigChan.boneOf(chan) in rig.bones.indices
    else -> {
        val c = rig.curves.getOrNull(RigChan.curveOf(chan))
        c != null && (RigChan.curveSlot(chan) == 0 || (RigChan.curveSlot(chan) - 1) / 3 < c.pts.size)
    }
}

/** Keyed lanes of a row, transform first. */
internal fun EditorState.gkLanes(track: Int): List<GKLane> {
    val t = drawTracks.firstOrNull { it.id == track } ?: return emptyList()
    val out = ArrayList<GKLane>()
    for (c in Chan.values()) if (t.keys.any { it.chan == c.id }) out.add(GKLane(track, false, c.id))
    for (c in t.rig.keys.map { it.chan }.distinct().sorted()) out.add(GKLane(track, true, c))
    return out
}

/** Every channel of the row that has no keys yet (for "+ Lane"). */
private fun EditorState.gkUnkeyed(track: Int): List<GKLane> {
    val t = drawTracks.firstOrNull { it.id == track } ?: return emptyList()
    val have = gkLanes(track).toHashSet()
    val all = ArrayList<GKLane>()
    for (c in Chan.values()) all.add(GKLane(track, false, c.id))
    val r = t.rig
    for ((bi, b) in r.bones.withIndex()) for (c in BoneChan.values()) {
        if (c.id >= BoneChan.IkX.id && b.ikChain <= 0) continue
        all.add(GKLane(track, true, RigChan.bone(bi, c)))
    }
    for ((ki, c) in r.curves.withIndex()) {
        all.add(GKLane(track, true, RigChan.curveMix(ki)))
        for (i in c.pts.indices) for (s in 0..2) all.add(GKLane(track, true, RigChan.ctl(ki, i, s)))
    }
    for (i in r.pins.indices) { all.add(GKLane(track, true, RigChan.pin(i, 0))); all.add(GKLane(track, true, RigChan.pin(i, 1))); all.add(GKLane(track, true, RigChan.pinRot(i))) }
    return all.filter { it !in have }
}

private val GKPalette = listOf(
    Color(0xFFFF6B6B), Color(0xFF4ECB71), Color(0xFF4DA3FF), Color(0xFFFFB84D), Color(0xFFB784FF),
    Color(0xFF3FD0D0), Color(0xFFFF7EC8), Color(0xFFB5D334), Color(0xFFA0A8FF), Color(0xFFFF9F7A),
)

private fun laneColor(l: GKLane): Color = GKPalette[(if (l.rig) 5 + (l.chan * 7 + 3) % 5 else l.chan) % GKPalette.size]

private fun KeyGraphUi.selByLane(state: EditorState): Map<GKLane, Set<Int>> {
    val out = HashMap<GKLane, MutableSet<Int>>()
    for (r in sel) if (state.gkKeys(r.lane).any { it.frame == r.frame }) out.getOrPut(r.lane) { HashSet() }.add(r.frame)
    return out
}

/** One undo step: [fn] gets every lane that has selected keys. */
private fun EditorState.gkOnSel(ui: KeyGraphUi, fn: (GKLane, List<ChanKey>, Set<Int>) -> List<ChanKey>) {
    val by = ui.selByLane(this)
    if (by.isEmpty()) return
    playing = false
    edit { for ((l, s) in by) gkPut(l, fn(l, gkKeys(l), s)) }
}

/** Lanes an action without a selection works on: the focused one. */
private fun KeyGraphUi.targets(state: EditorState): Map<GKLane, Set<Int>> {
    val by = selByLane(state)
    if (by.isNotEmpty()) return by
    return focus?.let { mapOf(it to emptySet<Int>()) } ?: emptyMap()
}

// ------------------------------------------------------------------------------------------------ icon / rail button

/** GRID with a small KEYFRAME diamond on its top right. */
@Composable
internal fun KeyGraphIcon(modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant, iconSize: Dp = 24.dp) {
    Canvas(modifier.size(iconSize)) {
        val u = size.minDimension / 24f
        val sw = 1.7f * u
        val l = 2.5f * u; val t = 6.5f * u; val r = 18f * u; val b = 21.5f * u
        drawRoundRect(tint, Offset(l, t), Size(r - l, b - t), CornerRadius(2.2f * u), style = Stroke(sw))
        val x1 = l + (r - l) / 3f; val x2 = l + (r - l) * 2f / 3f
        val y1 = t + (b - t) / 3f; val y2 = t + (b - t) * 2f / 3f
        drawLine(tint, Offset(x1, t), Offset(x1, b), sw * 0.8f)
        drawLine(tint, Offset(x2, t), Offset(x2, b), sw * 0.8f)
        drawLine(tint, Offset(l, y1), Offset(r, y1), sw * 0.8f)
        drawLine(tint, Offset(l, y2), Offset(r, y2), sw * 0.8f)
        // the keyframe: gold diamond with a dark rim so it reads on top of the grid corner
        val c = Offset(19f * u, 5f * u)
        val d = Path().apply { moveTo(c.x, c.y - 4.6f * u); lineTo(c.x + 4.6f * u, c.y); lineTo(c.x, c.y + 4.6f * u); lineTo(c.x - 4.6f * u, c.y); close() }
        drawPath(d, Color.Black.copy(alpha = 0.55f))
        val d2 = Path().apply { moveTo(c.x, c.y - 3.4f * u); lineTo(c.x + 3.4f * u, c.y); lineTo(c.x, c.y + 3.4f * u); lineTo(c.x - 3.4f * u, c.y); close() }
        drawPath(d2, KeyGold)
    }
}

/** Rail button: same look as ToolBtn, opens / closes the KeyGraph Editor. */
@Composable
internal fun KeyGraphToolBtn(state: EditorState, size: Dp = 36.dp) {
    val cs = MaterialTheme.colorScheme
    val ui = state.keyGraph
    val bg by animateColorAsState(if (ui.open) cs.primaryContainer else Color.Transparent, label = "kgBg")
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable {
                ui.open = !ui.open
            }
            .semantics { contentDescription = "KeyGraph Editor" },
        contentAlignment = Alignment.Center,
    ) { KeyGraphIcon(tint = if (ui.open) cs.onPrimaryContainer else cs.onSurfaceVariant) }
}

// ------------------------------------------------------------------------------------------------ small widgets

/** Flat 28 dp text button of the header / footer bars: transparent until selected (Blender look). */
@Composable
private fun GKTBtn(text: String, enabled: Boolean = true, selected: Boolean = false, onClick: () -> Unit) {
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

/** Small square button floating on top of the graph (add-key tool, fit, size). */
@Composable
private fun GKFloatBtn(text: String, selected: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) cs.primary else cs.surfaceContainerHigh.copy(alpha = 0.88f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) cs.onPrimary else cs.onSurface)
    }
}

/** Opens a popup box above / below the anchor, whichever side has more room. */
private class GKMenuPos : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = anchorBounds.left.coerceIn(0, max(0, windowSize.width - popupContentSize.width))
        val above = anchorBounds.top
        val below = windowSize.height - anchorBounds.bottom
        val y = if (above >= below) anchorBounds.top - popupContentSize.height else anchorBounds.bottom
        return IntOffset(x, y.coerceIn(0, max(0, windowSize.height - popupContentSize.height)))
    }
}

/**
 * Header menu (Blender: View / Select / Key ...). The title is a flat button; tapping it opens a BOX of grouped controls.
 * [content] gets `close` so one-shot actions can dismiss the box while option chips (families, steps, toggles) keep it open.
 */
@Composable
private fun GKMenu(title: String, content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .height(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (open) cs.primaryContainer else Color.Transparent)
                .clickable { open = !open }
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = if (open) cs.onPrimaryContainer else cs.onSurface, maxLines = 1)
        }
        if (open) Popup(GKMenuPos(), { open = false }, PopupProperties(focusable = true)) {
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
private fun GKSection(text: String) {
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 2.dp, top = 4.dp),
    )
}

@Composable
private fun GKHint(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp, top = 4.dp))
}

/** Option / action chip of a box menu. Pass `Modifier.weight(1f)` (GKGrid does) so a row of chips shares the width. */
@Composable
private fun GKChip(text: String, modifier: Modifier = Modifier, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .height(30.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) cs.primaryContainer else cs.surfaceContainerHighest)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp),
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
private fun <T> GKGrid(items: List<T>, cols: Int, cell: @Composable (T, Modifier) -> Unit) {
    for (rowItems in items.chunked(cols)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (item in rowItems) cell(item, Modifier.weight(1f))
            repeat(cols - rowItems.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun GKCheck(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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
private fun GKAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(7.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (enabled) cs.onSurface else cs.onSurface.copy(alpha = 0.34f), maxLines = 1)
    }
}

// ------------------------------------------------------------------------------------------------ geometry

private class GKCtx(
    val track: Int,
    val off: Int,
    val maxF: Int,
    val lanes: List<GKLane>,
    val visible: List<GKLane>,
    val keys: Map<GKLane, List<ChanKey>>,
    val rng: Map<GKLane, Pair<Float, Float>>,
)

private class GKGeo(val w: Float, val h: Float, val pad: Float, val view: GraphView, val maxF: Int) {
    val pw = w - 2 * pad
    val ph = h - 2 * pad
    private val span = max(maxF, 1).toFloat()
    fun px(f: Float) = pad + (f / span - view.ox) * view.zx * pw
    fun fx(x: Float) = (view.ox + ((x - pad) / pw) / view.zx) * span
    fun py(v: Float, r: Pair<Float, Float>) = pad + (((r.second - v) / (r.second - r.first)) - view.oy) * view.zy * ph
    fun vy(y: Float, r: Pair<Float, Float>) = r.second - (view.oy + ((y - pad) / ph) / view.zy) * (r.second - r.first)
}

/** Value window of one lane: its keys + curve, with 12% air. */
private fun laneRange(k: List<ChanKey>, st: Float): Pair<Float, Float> {
    var lo = Float.MAX_VALUE
    var hi = -Float.MAX_VALUE
    if (k.isEmpty()) { lo = st; hi = st }
    for (c in k) { lo = min(lo, c.value); hi = max(hi, c.value) }
    for (i in 0 until k.size - 1) {
        if (!k[i].ease.isBezier && k[i].ease.id < 17) continue     // only overshooting / bezier segments can leave the key range
        for (j in 1..8) { val v = GK.seg(k[i], k[i + 1], k[i].frame + (k[i + 1].frame - k[i].frame) * j / 9f, GKS); lo = min(lo, v); hi = max(hi, v) }
    }
    var d = hi - lo
    if (d < 1e-3f) { lo -= 0.5f; hi += 0.5f; d = hi - lo }
    return Pair(lo - d * 0.12f, hi + d * 0.12f)
}

private fun fmtV(v: Float) = if (abs(v) >= 100f) "%.0f".format(v) else if (abs(v) >= 10f) "%.1f".format(v) else "%.2f".format(v)

/** 1 / 2 / 5 x 10^n step so that about [target] grid lines fit into [span] (Blender-like round value grid). */
private fun gkNiceStep(span: Float, target: Int): Float {
    val raw = span / target
    val p = 10.0.pow(floor(log10(raw.toDouble()))).toFloat()
    val n = raw / p
    return p * (if (n < 1.5f) 1f else if (n < 3.5f) 2f else if (n < 7.5f) 5f else 10f)
}

private fun gkFmtStep(v: Float, st: Float): String {
    val d = if (st >= 1f) 0 else ceil(-log10(st.toDouble())).toInt().coerceIn(1, 4)
    return "%.${d}f".format(v)
}

// ------------------------------------------------------------------------------------------------ panel

@Composable
internal fun KeyGraphPanel(state: EditorState) {
    val cs = MaterialTheme.colorScheme
    val ui = state.keyGraph
    val track = state.activeTrack

    val open = state.kfOpen
    val openLane: GKLane? = when (open) {
        is PropRef.Draw -> if (open.track == track) GKLane(track, false, open.chan.id) else null
        is PropRef.RigChannel -> if (open.track == track) GKLane(track, true, open.chan) else null
        else -> null
    }
    val keyed = state.gkLanes(track)
    val lanes = if (openLane != null && openLane !in keyed) keyed + openLane else keyed
    val visible = lanes.filter { it !in ui.hidden }

    LaunchedEffect(track) { ui.sel = emptySet(); ui.focus = null; ui.view.reset() }
    LaunchedEffect(openLane) { if (openLane != null) { ui.focus = openLane; ui.hidden = ui.hidden - openLane } }
    LaunchedEffect(lanes, ui.focus) { if (ui.focus == null || ui.focus !in lanes) ui.focus = lanes.firstOrNull() }

    val maxF = state.gkMaxF(track, lanes.flatMap { state.gkKeys(it) })
    val keys = HashMap<GKLane, List<ChanKey>>()
    for (l in lanes) keys[l] = state.gkKeys(l)
    // value windows: per lane (Normalize) or one shared window; frozen while a drag is running so the plot does not slide under the finger
    var frozen by remember { mutableStateOf<Map<GKLane, Pair<Float, Float>>?>(null) }
    val own = HashMap<GKLane, Pair<Float, Float>>()
    for (l in lanes) own[l] = laneRange(keys[l]!!, state.gkStatic(l))
    val shared = if (visible.isEmpty()) Pair(-1f, 1f) else Pair(visible.minOf { own[it]!!.first }, visible.maxOf { own[it]!!.second })
    val rng: Map<GKLane, Pair<Float, Float>> = frozen ?: if (ui.norm) own else lanes.associateWith { shared }
    val ctx = rememberUpdatedState(GKCtx(track, state.trackOffset(track), maxF, lanes, visible, keys, rng))

    // Blender-style stack: menu bar / [channel list | graph] / footer. ~235 dp instead of ~420 dp.
    val graphH = if (state.transformOpen) 104.dp else if (ui.big) 240.dp else 150.dp
    Surface(
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
        color = cs.surfaceContainer,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            GKHeader(state, ui, ctx.value)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp).height(graphH),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (ui.chans) GKChannelList(state, ui, lanes, Modifier.width(112.dp).fillMaxHeight())
                GKGraphArea(state, ui, ctx, { frozen = it }, Modifier.weight(1f).fillMaxHeight())
            }
            GKFooter(state, ui, ctx.value)
        }
    }
}

/** Menu bar: title, one box menu per job, undo / redo and close on the right. */
@Composable
private fun GKHeader(state: EditorState, ui: KeyGraphUi, c: GKCtx) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(34.dp).background(cs.surfaceContainerHigh).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        KeyGraphIcon(Modifier.padding(end = 4.dp), iconSize = 20.dp, tint = cs.primary)
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            GKViewMenu(ui, c)
            GKSelectMenu(state, ui, c)
            GKKeyMenu(state, ui, c)
            GKEaseMenu(state, ui)
            GKCurveMenu(state, ui)
            GKClipMenu(state, ui, c)
        }
        GKTBtn("↶", enabled = state.canUndo) { state.undo() }
        GKTBtn("↷", enabled = state.canRedo) { state.redo() }
        GKTBtn("✕") { ui.open = false }
    }
}

/** Adds a key at the playhead so an unkeyed channel becomes a lane. */
private fun EditorState.gkAddLane(ui: KeyGraphUi, l: GKLane) {
    playing = false
    edit { gkPut(l, GK.put(emptyList(), l.chan, localFrame(l.track).coerceIn(0, gkMaxF(l.track, emptyList())), gkNow(l), defaultEase)) }
    ui.focus = l; ui.hidden = ui.hidden - l
}

/** Left column like Blender's channel list: colour dot = show / hide, name = focus. */
@Composable
private fun GKChannelList(state: EditorState, ui: KeyGraphUi, lanes: List<GKLane>, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.clip(RoundedCornerShape(8.dp)).background(cs.surfaceContainerLowest)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (lanes.isEmpty()) Text(
                "No keys yet.\nUse + Lane or the ◇ below.", style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant, modifier = Modifier.padding(8.dp),
            )
            for (l in lanes) {
                val hide = l in ui.hidden
                val foc = ui.focus == l
                val col = laneColor(l)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .background(if (foc) col.copy(alpha = 0.20f) else Color.Transparent)
                        .clickable { ui.focus = l; ui.hidden = ui.hidden - l },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(if (foc) col else Color.Transparent))
                    Box(Modifier.size(28.dp).clickable { ui.hidden = if (hide) ui.hidden - l else ui.hidden + l }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(if (hide) col.copy(alpha = 0.25f) else col))
                    }
                    Text(
                        state.gkLabel(l), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (hide) cs.onSurface.copy(alpha = 0.4f) else cs.onSurface,
                        modifier = Modifier.weight(1f).padding(end = 4.dp),
                    )
                }
            }
        }
        var menu by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxWidth().height(28.dp).background(cs.surfaceContainerHigh).clickable { menu = true }, contentAlignment = Alignment.Center) {
            Text("+ Lane", style = MaterialTheme.typography.labelMedium, color = cs.primary)
            DropdownMenu(menu, { menu = false }) {
                for (l in state.gkUnkeyed(state.activeTrack)) DropdownMenuItem(
                    text = { Text(state.gkLabel(l)) },
                    onClick = { menu = false; state.gkAddLane(ui, l) },
                )
            }
        }
    }
}

/** The graph plus the tools that float on it: add-key mode, fit, size, and the selected-key readout. */
@Composable
private fun GKGraphArea(
    state: EditorState, ui: KeyGraphUi, ctx: androidx.compose.runtime.State<GKCtx>,
    freeze: (Map<GKLane, Pair<Float, Float>>?) -> Unit, modifier: Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val by = ui.selByLane(state)
    val nSel = by.values.sumOf { it.size }
    val one = by.entries.singleOrNull()?.takeIf { it.value.size == 1 }
    val info = if (ui.addMode) "Tap the graph to add a key"
    else if (one != null) {
        val k = state.gkKeys(one.key).firstOrNull { it.frame == one.value.first() }
        if (k != null) "${state.gkLabel(one.key)} · F${k.frame + 1} · ${fmtV(k.value)} · ${k.ease.label}" else ""
    } else if (nSel > 0) "$nSel keys selected" else ""
    Box(modifier.clip(RoundedCornerShape(8.dp))) {
        GKGraph(state, ui, ctx, freeze, Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            GKFloatBtn("+", selected = ui.addMode) { ui.addMode = !ui.addMode }
            if (ui.view.zoomed) GKFloatBtn("⤢") { ui.view.reset() }
            GKFloatBtn(if (ui.big) "▾" else "▴") { ui.big = !ui.big }
        }
        if (info.isNotEmpty()) Text(
            info, style = MaterialTheme.typography.labelSmall, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(cs.surfaceContainerHigh.copy(alpha = 0.88f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

// ------------------------------------------------------------------------------------------------ the graph

@Composable
private fun GKGraph(
    state: EditorState, ui: KeyGraphUi, ctx: androidx.compose.runtime.State<GKCtx>,
    freeze: (Map<GKLane, Pair<Float, Float>>?) -> Unit, modifier: Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val view = ui.view
    val track = state.activeTrack
    val playF = state.localTime(track)
    val c0 = ctx.value

    Canvas(
        modifier.pointerInput(Unit) {
            val pad = 16.dp.toPx()
            val reach = 22.dp.toPx()
            val slop = 6.dp.toPx()
            val keySlop = 2.dp.toPx()          // a key / handle is grabbed almost at once, like the Pen tool
            val minSpread = 28.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val c = ctx.value
                val g = GKGeo(size.width.toFloat(), size.height.toFloat(), pad, view, c.maxF)
                val p0 = down.position
                fun seekTo(x: Float) { state.playing = false; state.seek(c.off + g.fx(x).roundToInt().coerceIn(0, c.maxF)) }

                // ---- ruler band: scrub
                if (p0.y < pad) {
                    down.consume(); seekTo(p0.x)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pr = ev.changes.firstOrNull { it.pressed } ?: break
                        seekTo(pr.position.x); pr.consume()
                    }
                    return@awaitEachGesture
                }

                // ---- hit tests (handles of selected keys first, then keys)
                val selNow = ui.selByLane(state)
                var hLane: GKLane? = null; var hIdx = -1; var hOut = false
                var best = reach
                val hset = HashMap<GKLane, MutableSet<Int>>()
                for ((l, fr) in selNow) hset.getOrPut(l) { HashSet() }.addAll(fr)
                ui.focus?.let { fl -> if (ui.allHandles && fl in c.visible) hset.getOrPut(fl) { HashSet() }.addAll((c.keys[fl] ?: emptyList()).map { it.frame }) }
                for ((l, fr) in hset) {
                    if (l !in c.visible) continue
                    val k = c.keys[l] ?: continue
                    val r = c.rng[l] ?: continue
                    for (f in fr) {
                        val i = k.indexOfFirst { it.frame == f }
                        if (i < 0) continue
                        if (!GK.outRetracted(k[i].ease)) GK.outHandle(k, i)?.let { h -> val d = (Offset(g.px(h.first), g.py(h.second, r)) - p0).getDistance(); if (d < best) { best = d; hLane = l; hIdx = i; hOut = true } }
                        if (i > 0 && !GK.inRetracted(k[i - 1].ease)) GK.inHandle(k, i)?.let { h -> val d = (Offset(g.px(h.first), g.py(h.second, r)) - p0).getDistance(); if (d < best) { best = d; hLane = l; hIdx = i; hOut = false } }
                    }
                }
                val hBest = best
                var kRef: GKRef? = null
                var kBest = reach
                for (l in c.visible) {
                    val r = c.rng[l] ?: continue
                    for (kk in c.keys[l] ?: emptyList()) {
                        val d = (Offset(g.px(kk.frame.toFloat()), g.py(kk.value, r)) - p0).getDistance()
                        if (d < kBest || (d == kBest && ui.focus == l)) { kBest = d; kRef = GKRef(l, kk.frame) }
                    }
                }
                // a handle wins over a key unless the key is clearly closer to the finger (zoomed in, handles sit next to their keys)
                if (hLane != null && kRef != null && kBest + 6.dp.toPx() < hBest) hLane = null
                if (hLane != null) kRef = null

                // ---- bezier handle drag
                val hl = hLane
                if (hl != null) {
                    down.consume()
                    state.playing = false
                    val before = state.snapshot()
                    state.gkKeys(hl).getOrNull(hIdx)?.let { ui.sel = setOf(GKRef(hl, it.frame)); ui.focus = hl }
                    freeze(c.rng)
                    val r = c.rng[hl]!!
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pr = ev.changes.firstOrNull { it.pressed } ?: break
                        val cur = state.gkKeys(hl)
                        state.gkPut(hl, GK.dragHandle(cur, hIdx, hOut, g.fx(pr.position.x), g.vy(pr.position.y, r), ui.aligned))
                        pr.consume()
                    }
                    freeze(null)
                    state.commitEdit(before)
                    return@awaitEachGesture
                }

                // ---- add mode on empty space: create a key at the finger, then drag it like any key
                var before: EditSnap? = null
                var tapRef = kRef
                if (tapRef == null && ui.addMode) {
                    val l = ui.focus
                    val r = l?.let { c.rng[it] }
                    if (l != null && r != null) {
                        state.playing = false
                        before = state.snapshot()
                        val f = g.fx(p0.x).roundToInt().coerceIn(0, c.maxF)
                        val k = state.gkKeys(l)
                        val nk = if (k.any { it.frame == f }) k else GK.put(k, l.chan, f, g.vy(p0.y, r), state.defaultEase)
                        state.gkPut(l, nk)
                        tapRef = GKRef(l, f)
                    }
                }

                // ---- key drag / select
                if (tapRef != null) {
                    down.consume()
                    val ref: GKRef = tapRef
                    if (ui.convert && before == null) {
                        state.gkConvert(ui, ref)
                        return@awaitEachGesture
                    }
                    if (ref !in ui.sel) ui.sel = if (ui.multi) ui.sel + ref else setOf(ref)
                    ui.focus = ref.lane
                    freeze(c.rng)
                    val moving = ui.selByLane(state)
                    val orig = moving.keys.associateWith { state.gkKeys(it) }
                    var moved = false
                    var df = 0
                    var multiTouch = false
                    val addCreated = before != null
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            if (!multiTouch && moved) {
                                // the first finger drifted a little before the second one landed: it was a pinch, not a key drag -> put the keys back
                                for ((l, k) in orig) state.gkPut(l, k)
                                ui.sel = moving.flatMap { (l, s) -> s.map { GKRef(l, it) } }.toSet()
                                moved = false; df = 0
                                if (!addCreated) before = null
                            }
                            multiTouch = true; view.pinch(ev, pad, g.pw, g.ph, minSpread); ev.changes.forEach { if (it.positionChanged()) it.consume() }; continue
                        }
                        if (multiTouch) continue
                        val pr = pressed[0]
                        val dx = pr.position.x - p0.x
                        val dy = pr.position.y - p0.y
                        if (!moved && abs(dx) < keySlop && abs(dy) < keySlop) continue
                        if (!moved) { moved = true; state.playing = false; if (before == null) before = state.snapshot() }
                        var d = if (ui.lock == 2) 0 else (g.fx(pr.position.x) - g.fx(p0.x)).roundToInt()
                        // one common frame shift for every lane, shrunk until nobody leaves 0..maxF or lands on a key
                        var prev = Int.MAX_VALUE
                        while (d != prev) { prev = d; for ((l, s) in moving) d = GK.fitShift(orig[l]!!, s, d, c.maxF) }
                        df = d
                        for ((l, s) in moving) {
                            val r = c.rng[l] ?: continue
                            val dv = if (ui.lock == 1) 0f else g.vy(pr.position.y, r) - g.vy(p0.y, r)
                            state.gkPut(l, GK.move(orig[l]!!, s, df, dv))
                        }
                        ui.sel = moving.flatMap { (l, s) -> s.map { GKRef(l, it + df) } }.toSet()
                        pr.consume()
                    }
                    freeze(null)
                    before?.let { state.commitEdit(it) }
                    if (!moved && before == null && !multiTouch) {
                        // plain tap on a key: playhead to it (a tap on an already-selected key in Multi mode deselects it)
                        if (ui.multi && ref in selNow.flatMap { (l, s) -> s.map { GKRef(l, it) } }) ui.sel = ui.sel - ref
                        else seekTo(g.px(ref.frame.toFloat()))
                    }
                    return@awaitEachGesture
                }

                // ---- empty space: tap = playhead + deselect, drag = box select, two fingers = zoom / pan
                var box: Pair<Offset, Offset>? = null
                var multiTouch = false
                var dragged = false
                while (true) {
                    val ev = awaitPointerEvent()
                    val pressed = ev.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    if (pressed.size >= 2) { multiTouch = true; box = null; view.pinch(ev, pad, g.pw, g.ph, minSpread); ev.changes.forEach { if (it.positionChanged()) it.consume() }; continue }
                    if (multiTouch) continue
                    val pr = pressed[0]
                    if (!dragged && (pr.position - p0).getDistance() < slop) continue
                    dragged = true
                    box = Pair(p0, pr.position)
                    ui.box = box
                    pr.consume()
                }
                val b = box
                ui.box = null
                if (b != null) {
                    val x0 = min(b.first.x, b.second.x); val x1 = max(b.first.x, b.second.x)
                    val y0 = min(b.first.y, b.second.y); val y1 = max(b.first.y, b.second.y)
                    val hit = HashSet<GKRef>()
                    for (l in c.visible) {
                        val r = c.rng[l] ?: continue
                        for (kk in c.keys[l] ?: emptyList()) {
                            val x = g.px(kk.frame.toFloat()); val y = g.py(kk.value, r)
                            if (x in x0..x1 && y in y0..y1) hit.add(GKRef(l, kk.frame))
                        }
                    }
                    ui.sel = if (ui.multi) ui.sel + hit else hit
                    hit.firstOrNull()?.let { ui.focus = it.lane }
                } else if (!multiTouch && !dragged) {
                    if (!ui.multi) ui.sel = emptySet()
                    seekTo(p0.x)
                }
            }
        },
    ) {
        val pad = 16.dp.toPx()
        val g = GKGeo(size.width, size.height, pad, view, c0.maxF)
        drawRect(cs.surfaceContainerLowest, size = size)
        // ruler band (drag = scrub)
        drawRect(cs.surfaceContainerHigh, Offset.Zero, Size(size.width, pad))
        drawLine(cs.outline.copy(alpha = 0.35f), Offset(0f, pad), Offset(size.width, pad), 1.dp.toPx())
        val foc = ui.focus
        val lbl = TextStyle(fontSize = 9.sp, color = cs.onSurfaceVariant)

        clipRect {
            // vertical frame grid + ruler
            val span = max(c0.maxF, 1)
            val minPx = 36.dp.toPx()
            var step = 1
            val steps = intArrayOf(1, 2, 5, 10, 20, 50, 100, 200, 500)
            for (s in steps) { step = s; if (g.px(s.toFloat()) - g.px(0f) >= minPx) break }
            var f = 0
            while (f <= span) {
                val x = g.px(f.toFloat())
                if (x in 0f..size.width) {
                    drawLine(cs.outline.copy(alpha = if (f == 0) 0.5f else 0.2f), Offset(x, pad), Offset(x, size.height - pad / 2), 1.dp.toPx())
                    drawText(measurer, "${f + 1}", Offset(x + 2.dp.toPx(), 1.dp.toPx()), style = lbl)
                }
                f += step
            }
            // horizontal guides at ROUND values of the focused lane (they follow zoom / pan), the zero axis is stronger
            val fr = foc?.let { c0.rng[it] }
            if (fr != null) {
                val top = g.vy(pad, fr)
                val bot = g.vy(size.height - pad, fr)
                val vspan = top - bot
                if (vspan > 1e-6f) {
                    val st = gkNiceStep(vspan, 4)
                    val i0 = ceil(bot / st).toInt()
                    val i1 = floor(top / st).toInt()
                    for (i in i0..min(i1, i0 + 40)) {
                        val y = g.py(i * st, fr)
                        val zero = i == 0
                        drawLine(
                            if (zero) cs.onSurface.copy(alpha = 0.4f) else cs.outline.copy(alpha = 0.2f),
                            Offset(0f, y), Offset(size.width, y), (if (zero) 1.4f else 1f).dp.toPx(),
                        )
                        drawText(measurer, gkFmtStep(i * st, st), Offset(2.dp.toPx(), y - 11.dp.toPx()), style = lbl)
                    }
                }
            }
            // curves, unfocused first so the focused one is on top
            val order = c0.visible.sortedBy { it == foc }
            for (l in order) {
                val k = c0.keys[l] ?: continue
                val r = c0.rng[l] ?: continue
                if (k.isEmpty()) continue
                val col = laneColor(l).let { if (l == foc) it else it.copy(alpha = 0.7f) }
                val path = gkPath(k, r, g, size.width, 4.dp.toPx())
                drawPath(path, col, style = Stroke((if (l == foc) 2.4f else 1.4f).dp.toPx(), cap = StrokeCap.Butt, join = StrokeJoin.Round))
            }

            // playhead
            val phx = g.px(playF)
            drawLine(KeyGold.copy(alpha = 0.8f), Offset(phx, pad), Offset(phx, size.height - pad / 2), 1.5f.dp.toPx())
            // current-frame tag on the ruler (Blender's blue playhead box)
            val tag = measurer.measure("${playF.roundToInt() + 1}", TextStyle(fontSize = 9.sp, color = Color(0xFF1A1200)))
            val tagW = tag.size.width + 8.dp.toPx()
            drawRoundRect(KeyGold, Offset(phx - tagW / 2f, 0f), Size(tagW, pad - 1.dp.toPx()), CornerRadius(3.dp.toPx()))
            drawText(tag, topLeft = Offset(phx - tag.size.width / 2f, (pad - tag.size.height) / 2f))
            for (l in c0.visible) {
                val k = c0.keys[l] ?: continue
                val r = c0.rng[l] ?: continue
                if (k.isNotEmpty()) drawCircle(laneColor(l), 3.5f.dp.toPx(), Offset(phx, g.py(GK.eval(k, playF, 0f, GKS), r)))
            }

            // Blender look: round key dots, smaller hollow handle dots on thin lines. Handles of the selected points,
            // plus every point of the focused curve while "Handles" is on. A retracted handle (corner side) is not drawn.
            val selAll = ui.sel
            for (l in c0.visible) {
                val k = c0.keys[l] ?: continue
                val r = c0.rng[l] ?: continue
                val col = laneColor(l)
                val all = ui.allHandles && l == foc
                for ((i, kk) in k.withIndex()) {
                    if (!all && GKRef(l, kk.frame) !in selAll) continue
                    val kc = Offset(g.px(kk.frame.toFloat()), g.py(kk.value, r))
                    if (!GK.outRetracted(kk.ease)) GK.outHandle(k, i)?.let { h -> gkDrawHandle(kc, Offset(g.px(h.first), g.py(h.second, r)), col) }
                    if (i > 0 && !GK.inRetracted(k[i - 1].ease)) GK.inHandle(k, i)?.let { h -> gkDrawHandle(kc, Offset(g.px(h.first), g.py(h.second, r)), col) }
                }
            }
            for (l in c0.visible) {
                val k = c0.keys[l] ?: continue
                val r = c0.rng[l] ?: continue
                val col = laneColor(l)
                for (kk in k) {
                    val kc = Offset(g.px(kk.frame.toFloat()), g.py(kk.value, r))
                    if (kc.x < -20f || kc.x > size.width + 20f) continue
                    val rr = 4.4f.dp.toPx()
                    if (GKRef(l, kk.frame) in selAll) {
                        drawCircle(col, rr, kc)
                        drawCircle(Color.White, rr, kc, style = Stroke(1.4f.dp.toPx()))
                    } else {
                        drawCircle(Color(0xFF151515), rr, kc)
                        drawCircle(col, rr, kc, style = Stroke(1.6f.dp.toPx()))
                    }
                }
            }

            ui.box?.let { (a, b) ->
                val tl = Offset(min(a.x, b.x), min(a.y, b.y))
                val sz = Size(abs(a.x - b.x), abs(a.y - b.y))
                drawRect(cs.primary.copy(alpha = 0.15f), tl, sz)
                drawRect(cs.primary, tl, sz, style = Stroke(1.dp.toPx()))
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ box menus
// Same operations as the old Ease / Curve / Keys / Blend tabs, regrouped like Blender's header menus.
// Every action is still ONE undo step (edit{} / gkOnSel).

private fun EditorState.gkApplyEase(ui: KeyGraphUi, e: Easing) {
    playing = false
    edit {
        val by = ui.selByLane(this)
        if (by.isNotEmpty()) for ((l, s) in by) gkPut(l, GK.setEase(gkKeys(l), s, e))
        else ui.focus?.let { gkPut(it, GK.setEase(gkKeys(it), emptySet(), e)) }
        defaultEase = e
    }
}

@Composable
private fun GKViewMenu(ui: KeyGraphUi, c: GKCtx) {
    GKMenu("View") { close ->
        GKSection("Display")
        GKCheck("Channel list", ui.chans) { ui.chans = it }
        GKCheck("Normalize curves", ui.norm) { ui.norm = it }
        GKCheck("Handles of every key", ui.allHandles) { ui.allHandles = it }
        GKSection("Editing")
        GKCheck("Multi-select", ui.multi) { ui.multi = it }
        GKCheck("Align handles", ui.aligned) { ui.aligned = it }
        GKCheck("Tap a key: corner / smooth", ui.convert) { ui.convert = it }
        GKSection("Drag lock")
        GKGrid(listOf(0, 1, 2), 3) { i, m -> GKChip(listOf("Free", "X only", "Y only")[i], m, selected = ui.lock == i) { ui.lock = i } }
        GKSection("Curves / view")
        GKGrid(listOf(0, 1, 2, 3), 2) { i, m ->
            when (i) {
                0 -> GKChip("Show all", m) { ui.hidden = emptySet(); close() }
                1 -> GKChip("Solo focused", m, enabled = ui.focus != null) { ui.hidden = c.lanes.filter { it != ui.focus }.toSet(); close() }
                2 -> GKChip("Fit view", m) { ui.view.reset(); close() }
                else -> GKChip(if (ui.big) "Shorter graph" else "Taller graph", m) { ui.big = !ui.big; close() }
            }
        }
        GKHint("Drag empty space: box select · two fingers: zoom / pan · drag the ruler: scrub")
    }
}

@Composable
private fun GKSelectMenu(state: EditorState, ui: KeyGraphUi, c: GKCtx) {
    GKMenu("Select") { close ->
        val hasSel = ui.selByLane(state).isNotEmpty()
        GKAction("All keys · focused curve", enabled = ui.focus != null) {
            ui.focus?.let { l -> ui.sel = c.keys[l].orEmpty().map { GKRef(l, it.frame) }.toSet() }
            close()
        }
        GKAction("All keys · every visible curve") {
            ui.sel = c.visible.flatMap { l -> c.keys[l].orEmpty().map { GKRef(l, it.frame) } }.toSet()
            close()
        }
        GKAction("Invert · focused curve", enabled = ui.focus != null) {
            ui.focus?.let { l ->
                val all = c.keys[l].orEmpty().map { GKRef(l, it.frame) }.toSet()
                ui.sel = (ui.sel - all) + (all - ui.sel)
            }
            close()
        }
        GKAction("None", enabled = hasSel) { ui.sel = emptySet(); close() }
    }
}

@Composable
private fun GKKeyMenu(state: EditorState, ui: KeyGraphUi, c: GKCtx) {
    GKMenu("Key") { close ->
        val hasSel = ui.selByLane(state).isNotEmpty()
        fun vstep(l: GKLane): Float { val r = c.rng[l] ?: return 0.01f; return (r.second - r.first) * 0.02f }
        GKSection("Create / delete")
        GKGrid(listOf(0, 1, 2), 3) { i, m ->
            when (i) {
                0 -> GKChip("Insert", m, enabled = ui.focus != null) {
                    val l = ui.focus ?: return@GKChip
                    state.playing = false
                    val f = state.localFrame(l.track).coerceIn(0, c.maxF)
                    state.edit { val k = state.gkKeys(l); state.gkPut(l, if (k.isEmpty()) GK.put(k, l.chan, f, state.gkNow(l), state.defaultEase) else GK.insert(k, l.chan, f, state.gkStatic(l), GKS)) }
                    ui.sel = setOf(GKRef(l, f))
                    close()
                }
                1 -> GKChip("Key all", m) {
                    state.playing = false
                    state.edit {
                        for (l in c.visible) {
                            val f = state.localFrame(l.track).coerceIn(0, c.maxF)
                            val k = state.gkKeys(l)
                            state.gkPut(l, if (k.isEmpty()) GK.put(k, l.chan, f, state.gkNow(l), state.defaultEase) else GK.insert(k, l.chan, f, state.gkStatic(l), GKS))
                        }
                    }
                    close()
                }
                else -> GKChip("Delete", m, enabled = hasSel) { state.gkOnSel(ui) { _, k, s -> GK.delete(k, s) }; ui.sel = emptySet(); close() }
            }
        }
        GKSection("Jump to key")
        GKGrid(listOf(-1, 1), 2) { d, m ->
            GKChip(if (d < 0) "‹ Previous" else "Next ›", m, enabled = ui.focus != null) {
                ui.focus?.let { l ->
                    val cur = state.localFrame(l.track)
                    val k = c.keys[l]
                    val t = if (d < 0) k?.lastOrNull { it.frame < cur } else k?.firstOrNull { it.frame > cur }
                    t?.let { state.seek(c.off + it.frame) }
                }
            }
        }
        GKSection("Move selected · frames")
        GKGrid(listOf(-5, -1, 1, 5), 4) { d, m ->
            GKChip(if (d > 0) "+$d" else "$d", m, enabled = hasSel) {
                val by = ui.selByLane(state)
                var s = d
                var prev = Int.MAX_VALUE
                while (s != prev) { prev = s; for ((l, f) in by) s = GK.fitShift(state.gkKeys(l), f, s, c.maxF) }
                if (s != 0) {
                    state.gkOnSel(ui) { _, k, f -> GK.move(k, f, s, 0f) }
                    ui.sel = ui.sel.map { it.copy(frame = it.frame + s) }.toSet()
                }
            }
        }
        GKSection("Move selected · value")
        GKGrid(listOf(-5, -1, 1, 5), 4) { n, m ->
            GKChip(if (n > 0) "+$n" else "$n", m, enabled = hasSel) { state.gkOnSel(ui) { l, k, s -> GK.setValue(k, s) { it.value + n * vstep(l) } } }
        }
        GKSection("Scale / flip selected")
        val ops = listOf<Pair<String, () -> Unit>>(
            "Time ×0.5" to { state.gkOnSel(ui) { _, k, s -> GK.scaleTime(k, s, 0.5f, s.min(), c.maxF) }; ui.sel = emptySet() },
            "Time ×2" to { state.gkOnSel(ui) { _, k, s -> GK.scaleTime(k, s, 2f, s.min(), c.maxF) }; ui.sel = emptySet() },
            "Flip" to { state.gkOnSel(ui) { _, k, s -> GK.flip(k, s) } },
            "Value ×0.5" to { state.gkOnSel(ui) { _, k, s -> GK.scaleValue(k, s, 0.5f) } },
            "Value ×2" to { state.gkOnSel(ui) { _, k, s -> GK.scaleValue(k, s, 2f) } },
        )
        GKGrid(ops, 3) { op, m -> GKChip(op.first, m, enabled = hasSel) { op.second(); close() } }
    }
}

@Composable
private fun GKEaseMenu(state: EditorState, ui: KeyGraphUi) {
    GKMenu("Ease") { close ->
        val hasSel = ui.selByLane(state).isNotEmpty()
        GKSection(if (hasSel) "Interpolation · selected keys" else "Interpolation · whole focused curve")
        GKGrid(listOf("Hold", "Linear", "Smooth", "Bezier"), 4) { t, m ->
            GKChip(t, m) {
                when (t) {
                    "Hold" -> state.gkApplyEase(ui, Easing.Hold)
                    "Linear" -> state.gkApplyEase(ui, Easing.Linear)
                    "Smooth" -> state.gkApplyEase(ui, Easing.Smooth)
                    else -> {
                        // same shape, but with handles to drag
                        state.playing = false
                        state.edit {
                            val by = ui.selByLane(state).ifEmpty { ui.focus?.let { mapOf(it to emptySet<Int>()) } ?: emptyMap() }
                            for ((l, s) in by) state.gkPut(l, state.gkKeys(l).map { if (s.isEmpty() || it.frame in s) it.copy(ease = GK.asBezier(it.ease, GKS)) else it })
                        }
                    }
                }
                close()
            }
        }
        GKSection("Family")
        GKGrid(EaseFamily.values().toList(), 4) { f, m -> GKChip(f.label, m, selected = ui.fam == f) { ui.fam = f } }
        GKSection("${ui.fam.label} · direction")
        GKGrid(EaseDir.values().toList(), 3) { d, m -> GKChip(d.label, m) { state.gkApplyEase(ui, Easing.of(ui.fam, d)); close() } }
    }
}

@Composable
private fun GKCurveMenu(state: EditorState, ui: KeyGraphUi) {
    GKMenu("Curve") { close ->
        val hasSel = ui.selByLane(state).isNotEmpty()
        GKSection("Auto tangents")
        GKGrid(GKSmooth.values().toList(), 3) { mo, m -> GKChip(mo.label, m, selected = ui.smoothMode == mo) { ui.smoothMode = mo } }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Tangent ${"%.2f".format(ui.tension)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(78.dp))
            Slider(ui.tension, { ui.tension = it }, valueRange = 0f..1.2f, modifier = Modifier.weight(1f).height(28.dp))
        }
        GKChip(if (hasSel) "Smooth selected" else "Smooth whole curve", Modifier.fillMaxWidth()) {
            state.playing = false
            state.edit { for ((l, s) in ui.targets(state)) state.gkPut(l, GK.smooth(state.gkKeys(l), ui.smoothMode, ui.tension, s)) }
            close()
        }
        GKSection("Point type")
        GKGrid(listOf(0, 1, 2), 3) { i, m ->
            when (i) {
                0 -> GKChip("Corner", m, enabled = hasSel) {
                    state.gkOnSel(ui) { _, k, sel -> var o = k; for (f in sel) { val j = o.indexOfFirst { it.frame == f }; if (j >= 0) o = GK.setCorner(o, j, GKS) }; o }
                    close()
                }
                1 -> GKChip("Smooth", m, enabled = hasSel) {
                    state.gkOnSel(ui) { _, k, sel -> var o = k; for (f in sel) { val j = o.indexOfFirst { it.frame == f }; if (j >= 0) o = GK.setSmooth(o, j, ui.smoothMode, GKS) }; o }
                    close()
                }
                else -> GKChip("Poly curve", m, enabled = ui.focus != null) {
                    state.playing = false
                    state.edit { ui.focus?.let { l -> state.gkPut(l, GK.setEase(state.gkKeys(l), emptySet(), Easing.Linear)) } }
                    close()
                }
            }
        }
        GKSection("Clean up")
        GKChip(if (hasSel) "Relax selected" else "Relax values", Modifier.fillMaxWidth()) {
            state.playing = false
            state.edit { for ((l, s) in ui.targets(state)) state.gkPut(l, GK.relax(state.gkKeys(l), s, 0.5f)) }
            close()
        }
        GKSection("Reduce keys · tolerance")
        GKGrid(listOf(1, 2, 5, 10), 4) { p, m -> GKChip("$p%", m, selected = ui.reducePct == p) { ui.reducePct = p } }
        GKChip("Reduce keys", Modifier.fillMaxWidth()) {
            state.playing = false
            state.edit {
                for ((l, s) in ui.targets(state)) {
                    val k = state.gkKeys(l)
                    if (k.size < 3) continue
                    val span = k.maxOf { it.value } - k.minOf { it.value }
                    val f0 = if (s.size >= 2) s.min() else 0
                    val f1 = if (s.size >= 2) s.max() else k.last().frame
                    state.gkPut(l, GK.reduce(k, span * ui.reducePct / 100f, f0, f1, GKS))
                }
            }
            ui.sel = emptySet()
            close()
        }
        GKSection("Bake · every N frames")
        GKGrid(listOf(1, 2, 3, 4), 4) { n, m -> GKChip("$n f", m, selected = ui.bakeStep == n) { ui.bakeStep = n } }
        GKChip("Bake", Modifier.fillMaxWidth()) {
            state.playing = false
            state.edit {
                for ((l, s) in ui.targets(state)) {
                    val k = state.gkKeys(l)
                    if (k.size < 2) continue
                    val f0 = if (s.size >= 2) s.min() else k.first().frame
                    val f1 = if (s.size >= 2) s.max() else k.last().frame
                    state.gkPut(l, GK.bake(k, l.chan, f0, f1, ui.bakeStep, state.gkStatic(l), GKS))
                }
            }
            ui.sel = emptySet()
            close()
        }
    }
}

@Composable
private fun GKClipMenu(state: EditorState, ui: KeyGraphUi, c: GKCtx) {
    val clip = ui.clip
    fun copy(): Boolean {
        val by = ui.selByLane(state)
        if (by.isEmpty()) return false
        val g0 = by.values.minOf { it.min() }
        val lanes = ArrayList<GKClipLane>()
        var len = 0
        for ((l, s) in by) {
            val blk = GK.copyLane(state.gkKeys(l), s) ?: continue
            val sh = s.min() - g0
            val moved = blk.map { it.copy(frame = it.frame + sh) }
            len = max(len, moved.last().frame)
            lanes.add(GKClipLane(l.rig, l.chan, moved))
        }
        ui.clip = if (lanes.isEmpty()) null else GKClip(lanes, len)
        return ui.clip != null
    }
    GKMenu("Clip") { close ->
        val hasSel = ui.selByLane(state).isNotEmpty()
        GKSection("Clipboard")
        GKGrid(listOf(0, 1, 2), 3) { i, m ->
            when (i) {
                0 -> GKChip("Copy", m, enabled = hasSel) { copy(); close() }
                1 -> GKChip("Cut", m, enabled = hasSel) { if (copy()) { state.gkOnSel(ui) { _, k, s -> GK.delete(k, s) }; ui.sel = emptySet() }; close() }
                else -> GKChip("Paste", m, enabled = clip != null) {
                    val cl = ui.clip ?: return@GKChip
                    state.playing = false
                    val at = state.localFrame(c.track)
                    val track = c.track
                    val rig = state.trackRig(track)
                    val single = cl.lanes.size == 1 && ui.focus != null
                    state.edit {
                        for (cl2 in cl.lanes) {
                            val l = if (single) ui.focus!! else GKLane(track, cl2.rig, cl2.chan)
                            if (l.rig && !gkRigHas(rig, l.chan)) continue
                            val k = state.gkKeys(l)
                            val maxF = state.gkMaxF(track, k)
                            state.gkPut(l, GK.paste(k, l.chan, cl2.keys, at, ui.blend, ui.mix, ui.fade, maxF, state.gkStatic(l), GKS))
                        }
                    }
                    ui.sel = emptySet()
                    close()
                }
            }
        }
        GKHint(if (clip == null) "Clipboard empty · select keys, then Copy" else "Clipboard: ${clip.lanes.size} lane${if (clip.lanes.size == 1) "" else "s"} · ${clip.len} f · pastes at the playhead")
        GKSection("Paste mode")
        GKGrid(GKBlend.values().toList(), 3) { bm, m -> GKChip(bm.label, m, selected = ui.blend == bm) { ui.blend = bm } }
        if (ui.blend != GKBlend.Replace) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Mix ${(ui.mix * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(60.dp))
            Slider(ui.mix, { ui.mix = it }, valueRange = 0f..1f, modifier = Modifier.weight(1f).height(28.dp))
        }
        GKSection("Cross-fade")
        GKGrid(listOf(0, 2, 4, 8), 4) { n, m -> GKChip(if (n == 0) "off" else "$n f", m, selected = ui.fade == n) { ui.fade = n } }
        GKSection("Time")
        GKChip("Reverse selected", Modifier.fillMaxWidth(), enabled = hasSel) {
            state.gkOnSel(ui) { l, k, s ->
                val blk = GK.copyLane(k, s) ?: return@gkOnSel k
                GK.paste(k, l.chan, GK.reverse(blk), s.min(), GKBlend.Replace, 1f, 0, state.gkMaxF(l.track, k), state.gkStatic(l), GKS)
            }
            close()
        }
        GKSection("Repeat selection")
        GKGrid(listOf(1, 2, 3, 4), 4) { n, m -> GKChip("×$n", m, selected = ui.repeatN == n) { ui.repeatN = n } }
        GKCheck("Ping-pong", ui.pingPong) { ui.pingPong = it }
        GKChip("Repeat selection", Modifier.fillMaxWidth(), enabled = hasSel) {
            state.gkOnSel(ui) { l, k, s -> GK.repeat(k, l.chan, s, ui.repeatN, ui.pingPong, state.gkMaxF(l.track, k), state.gkStatic(l), GKS) }
            close()
        }
    }
}


// ------------------------------------------------------------------------------------------------ footer + slider link

/** Bottom bar: step / play, current frame, and the slider of the focused curve. */
@Composable
private fun GKFooter(state: EditorState, ui: KeyGraphUi, c: GKCtx) {
    val cs = MaterialTheme.colorScheme
    val l = ui.focus
    Row(
        Modifier.fillMaxWidth().height(36.dp).background(cs.surfaceContainerHigh).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        GKTBtn("‹") { state.step(-1) }
        GKTBtn(if (state.playing) "❚❚" else "▶", selected = state.playing) { state.playing = !state.playing }
        GKTBtn("›") { state.step(1) }
        Text("F${state.localFrame() + 1}", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.widthIn(min = 32.dp).padding(horizontal = 2.dp))
        if (l != null && l.track == c.track) GKValueSlider(state, ui, c, l, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
    }
}

/**
 * The slider of the focused curve: the value at the playhead, edited exactly like the Transform / Rig sliders
 * (updateXf / setRigChan = a curve with keys is auto-keyed at the playhead). Drag = one undo step. The diamond keys / un-keys the playhead frame.
 */
@Composable
private fun GKValueSlider(state: EditorState, ui: KeyGraphUi, c: GKCtx, l: GKLane, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    var before by remember { mutableStateOf<EditSnap?>(null) }
    var frozenRange by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    val keys = c.keys[l] ?: emptyList()
    val range = frozenRange ?: c.rng[l] ?: Pair(-1f, 1f)
    val v = state.gkNow(l).coerceIn(range.first, range.second)
    val local = state.localFrame(l.track)
    val keyed = keys.any { it.frame == local }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(laneColor(l)))
        Slider(
            value = v,
            onValueChange = { nv ->
                if (before == null) { before = state.snapshot(); frozenRange = range; state.playing = false }
                if (l.rig) state.setRigChan(l.track, l.chan, nv)
                else Chan.of(l.chan)?.let { ch -> state.updateXf(l.track) { it.set(ch, nv) } }
            },
            onValueChangeFinished = { before?.let { state.commitEdit(it) }; before = null; frozenRange = null },
            valueRange = range.first..range.second,
            modifier = Modifier.weight(1f).height(28.dp),
        )
        Text(fmtV(v), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.widthIn(min = 38.dp))
        GKTBtn(if (keyed) "◆" else "◇", selected = keyed) {
            state.playing = false
            val f = local.coerceIn(0, state.gkMaxF(l.track, keys))
            state.edit {
                state.gkPut(l, if (keyed) GK.delete(keys, setOf(local)) else GK.put(keys, l.chan, f, state.gkNow(l), state.defaultEase))
            }
            ui.sel = emptySet()
        }
    }
}

/** One handle: thin line anchor -> small hollow dot (Blender style). */
private fun DrawScope.gkDrawHandle(anchor: Offset, h: Offset, col: Color) {
    drawLine(col.copy(alpha = 0.75f), anchor, h, 1f.dp.toPx())
    drawCircle(Color(0xFF151515), 3.2f.dp.toPx(), h)
    drawCircle(col, 3.2f.dp.toPx(), h, style = Stroke(1.3f.dp.toPx()))
}

/** Alt+click on a point (tap in Convert mode, or Curve menu): corner <-> smooth. One undo step. */
private fun EditorState.gkConvert(ui: KeyGraphUi, ref: GKRef) {
    playing = false
    ui.sel = setOf(ref); ui.focus = ref.lane
    edit {
        val k = gkKeys(ref.lane)
        val i = k.indexOfFirst { it.frame == ref.frame }
        if (i >= 0) gkPut(ref.lane, GK.toggleCorner(k, i, ui.smoothMode, GKS))
    }
}

/** The curve as ONE path, like Blender's F-curve line: exact corners at keys, straight Linear segments, stair-step Hold, sampled easing between. */
private fun gkPath(k: List<ChanKey>, r: Pair<Float, Float>, g: GKGeo, w: Float, step: Float): Path {
    val p = Path()
    val first = k.first()
    val fy = g.py(first.value, r)
    p.moveTo(min(0f, g.px(first.frame.toFloat())), fy)
    p.lineTo(g.px(first.frame.toFloat()), fy)
    for (i in 0 until k.size - 1) {
        val a = k[i]; val b = k[i + 1]
        val ax = g.px(a.frame.toFloat()); val bx = g.px(b.frame.toFloat())
        val ay = g.py(a.value, r); val by = g.py(b.value, r)
        if (a.ease.isHold) { p.lineTo(bx, ay); p.lineTo(bx, by) }
        else if (a.ease.isLinear || bx < 0f || ax > w) p.lineTo(bx, by)
        else {
            val n = ((bx - ax) / step).toInt().coerceIn(4, 200)
            for (j in 1..n) {
                val f = a.frame + (b.frame - a.frame) * j / n.toFloat()
                p.lineTo(g.px(f), g.py(GK.seg(a, b, f, GKS), r))
            }
        }
    }
    val last = k.last()
    p.lineTo(max(w, g.px(last.frame.toFloat())), g.py(last.value, r))
    return p
}
