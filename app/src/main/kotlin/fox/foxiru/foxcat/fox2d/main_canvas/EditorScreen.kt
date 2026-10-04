package fox.foxiru.foxcat.fox2d.main_canvas

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import fox.foxiru.foxcat.fox2d.jnicallers.AudioHandlerNative
import fox.foxiru.foxcat.fox2d.jnicallers.NativeCanvas
import fox.foxiru.foxcat.fox2d.jnicallers.NativeCanvasSurface
import fox.foxiru.foxcat.fox2d.timeline.AudioClip
import fox.foxiru.foxcat.fox2d.timeline.AudioTrack
import fox.foxiru.foxcat.fox2d.timeline.TimelineEditor
import fox.foxiru.foxcat.fox2d.timeline.TimelineState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

enum class Tool { Brush, Deform, Bone }
enum class Panel { None, Color, Layers, Brush }

/** Editor chrome floats over the full-screen canvas; this is how opaque it is. */
internal const val UI_ALPHA = 0.82f

/** Only Pen + Eraser are wired up. The rest are stubs until the native (JNI / GLES) renderer. */
enum class BrushKind(val label: String, val ico: Ico, val ready: Boolean) {
    Pen("Brush", Ico.Brush, true),
    Eraser("Eraser", Ico.Eraser, true),
    Smudge("Smudge", Ico.Smudge, false),
    Blur("Blur", Ico.Blur, false),
}

/** One drawing (exposure) on timeline row [track]. Held on screen for [len] timeline frames. */
data class Cel(val id: Int, val len: Int = 4, val track: Int = 0)

/** One drawing row of the timeline (its own run of cels). Rows are stored bottom -> top. */
/**
 * Whole-row transform in PAPER units (paper = 0..1, y down). A drawn point p is shown at
 *   pivot + t + R(rot, clockwise) * S * (p - pivot).
 * The pivot is the centre of the drawing, fixed when the row is first transformed so it never jumps.
 */
data class LayerXf(
    val tx: Float = 0f, val ty: Float = 0f,
    val sx: Float = 1f, val sy: Float = 1f,
    val rot: Float = 0f,
    val px: Float = 0.5f, val py: Float = 0.5f,
) {
    val isIdentity: Boolean get() = tx == 0f && ty == 0f && sx == 1f && sy == 1f && rot == 0f

    /** Value of one animatable property (what a slider shows / a keyframe stores). */
    fun get(c: Chan): Float = when (c) {
        Chan.PosX -> tx; Chan.PosY -> ty; Chan.Width -> sx; Chan.Height -> sy; Chan.Rotation -> rot
    }

    fun set(c: Chan, v: Float): LayerXf = when (c) {
        Chan.PosX -> copy(tx = v); Chan.PosY -> copy(ty = v); Chan.Width -> copy(sx = v)
        Chan.Height -> copy(sy = v); Chan.Rotation -> copy(rot = v)
    }

    /** Layer-local paper point -> where it is shown. */
    fun apply(p: Offset): Offset {
        val r = rot * 0.017453292f
        val c = cos(r); val s = sin(r)
        val dx = (p.x - px) * sx; val dy = (p.y - py) * sy
        return Offset(px + tx + c * dx - s * dy, py + ty + s * dx + c * dy)
    }

    /** Shown paper point (touch) -> layer-local paper point (where a new stroke point belongs). */
    fun invert(q: Offset): Offset {
        if (isIdentity) return q
        val r = rot * 0.017453292f
        val c = cos(r); val s = sin(r)
        val x = q.x - px - tx; val y = q.y - py - ty
        val rx = c * x + s * y; val ry = -s * x + c * y
        return Offset(px + rx / (if (sx == 0f) 1f else sx), py + ry / (if (sy == 0f) 1f else sy))
    }

    /** Moves the pivot to [np] (layer-local paper point) WITHOUT moving the picture: t' = t + (M - I) * (np - pivot). */
    fun withPivot(np: Offset): LayerXf {
        val r = rot * 0.017453292f
        val c = cos(r); val s = sin(r)
        val dx = np.x - px; val dy = np.y - py
        val ax = dx * sx; val ay = dy * sy
        val mx = c * ax - s * ay; val my = s * ax + c * ay
        return copy(px = np.x, py = np.y, tx = tx + mx - dx, ty = ty + my - dy)
    }

    /** 7 floats: tx, ty, sx, sy, rotDeg, pivotX, pivotY (what the native canvas / exporter read). */
    fun writeTo(a: FloatArray, at: Int) {
        a[at] = tx; a[at + 1] = ty; a[at + 2] = sx; a[at + 3] = sy; a[at + 4] = rot; a[at + 5] = px; a[at + 6] = py
    }
}

/** [offset] = timeline frame where the row's first drawing starts (drag the row left / right to change it). [xf] = move / rotate / resize. */
data class DrawTrack(
    val id: Int, val name: String, val locked: Boolean = false, val offset: Int = 0,
    val xf: LayerXf = LayerXf(),
    /**
     * Transform keyframes, PER PROPERTY: sorted by (channel, row-local frame), one key per channel per frame.
     * A channel without keys uses its value in [xf]; a channel with keys is driven by its curve (C++).
     */
    val keys: List<ChanKey> = emptyList(),
    /** Bones + deform curves + their keys (own channel space, see Rig.kt / fox_rig.h). Part of EditSnap, so undo / redo covers it. */
    val rig: Rig = Rig(),
    /** This row follows a bone of another row (rigid): see fox_rig.h attachMatrices. */
    val attach: BoneAttach? = null,
    /**
     * This row's OWN drawing layers (Body / Head / Tail ...), bottom -> top. Layer ids are unique across ALL rows,
     * so a stroke's layer id alone says which row it belongs to; duplicating a row gives the copy fresh ids.
     */
    val layers: List<Layer> = emptyList(),
) {
    /** Flat key array for native; built once per immutable copy. */
    val packedKeys: FloatArray by lazy(LazyThreadSafetyMode.NONE) { keys.pack() }
    /** Every frame that carries a key on ANY property (the marks on the timeline). */
    val keyFrames: List<Int> by lazy(LazyThreadSafetyMode.NONE) { keys.map { it.frame }.distinct().sorted() }
    /** Transform keys + rig keys. Channel numbers of the two kinds overlap, so use this for frames / easing only. */
    val allKeys: List<ChanKey> by lazy(LazyThreadSafetyMode.NONE) { if (rig.keys.isEmpty()) keys else keys + rig.keys }
    /** Every frame that carries a key of ANY kind (timeline marks, ruler, prev / next key). */
    val markFrames: List<Int> by lazy(LazyThreadSafetyMode.NONE) { allKeys.map { it.frame }.distinct().sorted() }
    fun lane(c: Chan): List<ChanKey> = keys.filter { it.chan == c.id }
    fun baseArray(): FloatArray = FloatArray(7).also { xf.writeTo(it, 0) }
}

data class InkStroke(
    val cel: Int,
    val layer: Int,
    val color: Color,
    val size: Float,
    val pts: List<Offset>,
    val erase: Boolean = false,
)

data class Layer(val id: Int, val name: String, val visible: Boolean = true)

/** Immutable copy of everything the timeline edits touch: audio clips/tracks and the drawing (cel) sequence. */
data class EditSnap(
    val clips: List<AudioClip>,
    val tracks: List<AudioTrack>,
    val cels: List<Cel>,
    val drawTracks: List<DrawTrack>,
    val strokes: List<InkStroke>,
    val selected: Int,
) {
    /** Selection alone is not an edit. */
    fun sameAs(o: EditSnap) =
        clips == o.clips && tracks == o.tracks && cels == o.cels && drawTracks == o.drawTracks && strokes == o.strokes
}

/** App-wide clipboard. Audio shares the decoded PCM handle (no copy); drawings carry their strokes. */
sealed interface Clipboard {
    class Audio(val clip: AudioClip) : Clipboard
    /** A whole drawing (cel): hold length + every stroke on every layer. */
    class CelArt(val len: Int, val strokes: List<InkStroke>) : Clipboard
    /** One layer's strokes of one drawing. */
    class LayerArt(val strokes: List<InkStroke>) : Clipboard
    /** Whole drawing rows and audio layers (select-tracks mode). Audio shares the decoded PCM handles. */
    class Tracks(val draw: List<TrackArt>, val audio: List<AudioArt>) : Clipboard
}

/** One drawing row: its settings + every drawing as (hold length, strokes). */
class TrackArt(val track: DrawTrack, val cels: List<Pair<Int, List<InkStroke>>>)

/** One audio layer: its settings + its clips. */
class AudioArt(val track: AudioTrack, val clips: List<AudioClip>)

/** One undo step. Strokes stay in strokes/redoStack (native canvas owns their pixels); this only orders them. */
sealed interface HistoryOp {
    object Stroke : HistoryOp
    class Edit(val before: EditSnap, val after: EditSnap) : HistoryOp
}

private const val HISTORY_MAX = 64

@Stable
class EditorState {
    var tool by mutableStateOf(Tool.Brush)
    /** Bone / deform editor selection + mode (UI only, not in history). */
    val rigUi = RigUiState()
    var brush by mutableStateOf(BrushKind.Pen)
    var hue by mutableFloatStateOf(14f)
    var sat by mutableFloatStateOf(0.75f)
    var bri by mutableFloatStateOf(1f)
    val color: Color get() = Color.hsv(hue, sat, bri)
    var brushSize by mutableFloatStateOf(10f)

    var railOpen by mutableStateOf(true)
    /** Floating timeline toolbar: which edge it is docked to, where along that edge (0..1), folded to just its handle. */
    var tbDock by mutableStateOf(ToolbarDock.Bottom)
    var tbAlong by mutableFloatStateOf(0.5f)
    var tbCollapsed by mutableStateOf(false)
    /** Floating tool rail (Brush / Deform / Bone / Transform / Layers): docked edge + where along it (0..1, centre of the rail). */
    var railDock by mutableStateOf(ToolbarDock.Right)
    var railAlong by mutableFloatStateOf(0.5f)
    /** Transform popup (replaces the player controls + timeline) and the on-canvas handles. */
    var transformOpen by mutableStateOf(false)
    /** false = a drag anywhere on the screen moves the layer; true = only touches on the box / its handles do anything. */
    var transformBoxOnly by mutableStateOf(false)
    var panel by mutableStateOf(Panel.None)
    var propsOpen by mutableStateOf(false)
    var exportOpen by mutableStateOf(false)

    var playing by mutableStateOf(false)
    var frame by mutableIntStateOf(0)
    var fps by mutableIntStateOf(12)

    // Timeline = drawing rows (DrawTrack, bottom -> top), each an ordered run of cels held for `len` frames.
    // Trim a cel's edge to stretch/shrink its hold. The timeline is as long as its longest row.
    val drawTracks = mutableStateListOf(DrawTrack(0, "Drawing 1", layers = listOf(Layer(0, "Body"), Layer(1, "Head"), Layer(2, "Tail"))))
    var activeTrack by mutableIntStateOf(0)
    val cels = mutableStateListOf(Cel(0, 4), Cel(1, 4), Cel(2, 4))
    val frameCount: Int get() = maxOf(1, drawTracks.maxOfOrNull { trackLen(it.id) } ?: 1)

    /** Layers of the ACTIVE drawing row (every row has its own set). */
    val layers: List<Layer> get() = layersOf(activeTrack)
    fun layersOf(track: Int): List<Layer> = drawTracks.firstOrNull { it.id == track }?.layers ?: emptyList()
    /** Native keeps the same number of layer slots for every row: the largest layer count of any row. */
    val layerSlots: Int get() = drawTracks.maxOfOrNull { it.layers.size } ?: 0
    fun newLayerId(): Int = (drawTracks.maxOfOrNull { t -> t.layers.maxOfOrNull { it.id } ?: -1 } ?: -1) + 1

    private fun editLayers(track: Int, f: (List<Layer>) -> List<Layer>) {
        val i = drawTracks.indexOfFirst { it.id == track }
        if (i >= 0) drawTracks[i] = drawTracks[i].copy(layers = f(drawTracks[i].layers))
    }

    /** Same layer names / visibility with brand-new ids (for a new or copied row); also old id -> new id. */
    private fun remapLayers(from: List<Layer>): Pair<List<Layer>, Map<Int, Int>> {
        var next = newLayerId()
        val map = HashMap<Int, Int>()
        val out = from.map { l -> Layer(next, l.name, l.visible).also { map[l.id] = next; next++ } }
        return out to map
    }

    private var activeLayerRaw by mutableIntStateOf(0)
    private val layerOfTrack = HashMap<Int, Int>()   // last layer used on each row, so switching rows keeps your place
    /** The layer strokes go to. Always one of the ACTIVE row's layers: switching rows falls back to that row's own pick. */
    var activeLayer: Int
        get() {
            val ls = layers
            if (ls.any { it.id == activeLayerRaw }) return activeLayerRaw
            return layerOfTrack[activeTrack]?.takeIf { id -> ls.any { it.id == id } } ?: ls.firstOrNull()?.id ?: activeLayerRaw
        }
        set(v) { activeLayerRaw = v; layerOfTrack[activeTrack] = v }

    val strokes = mutableStateListOf<InkStroke>()
    val redoStack = mutableStateListOf<InkStroke>()

    var posX by mutableFloatStateOf(0f)
    var posY by mutableFloatStateOf(0f)
    var rotation by mutableFloatStateOf(0f)
    var scale by mutableFloatStateOf(1f)
    var opacity by mutableFloatStateOf(1f)

    val viewport = CanvasViewport()
    val timeline = TimelineState()

    fun setColor(c: Color) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(c.toArgb(), hsv)
        hue = hsv[0]
        sat = hsv[1]
        bri = hsv[2]
    }

    // One chronological history for strokes, audio edits and drawing (cel) edits.
    val undoOps = mutableStateListOf<HistoryOp>()
    val redoOps = mutableStateListOf<HistoryOp>()
    val canUndo: Boolean get() = undoOps.isNotEmpty()
    val canRedo: Boolean get() = redoOps.isNotEmpty()

    var clipboard by mutableStateOf<Clipboard?>(null)

    fun snapshot() = EditSnap(
        timeline.clips.toList(), timeline.tracks.toList(), cels.toList(), drawTracks.toList(),
        strokes.toList(), timeline.selectedClip,
    )

    /** Close a finished edit/gesture: pushes one undo step if anything changed since [before]. */
    fun commitEdit(before: EditSnap) {
        val after = snapshot()
        if (before.sameAs(after)) return
        val hadRedo = redoStack.isNotEmpty()
        pushOp(HistoryOp.Edit(before, after)) // clears redoStack
        // native canvas must follow stroke edits, and drop its own redo stack when ours was cleared
        if (hadRedo || before.strokes != after.strokes) rebuildNative()
    }

    /** Replay every committed stroke (and the pending redo stack) into the native canvas. */
    /** Native layer index of (timeline row, layer): rows bottom -> top, layers bottom -> top inside each row. */
    fun nativeLayer(track: Int, layer: Int): Int {
        val t = drawTracks.indexOfFirst { it.id == track }.coerceAtLeast(0)
        val l = layersOf(track).indexOfFirst { it.id == layer }.coerceAtLeast(0)
        return t * layerSlots + l
    }

    /** Changes when the native layer table must be rebuilt: row order / row list / layer list (+ visibility). */
    fun structureKey(): Any = listOf(drawTracks.map { it.id }, drawTracks.map { it.layers })

    /** Changes when any row's transform changes (cheap update, no replay). */
    fun xfKey(): Any = drawTracks.map { listOf(it.xf, it.keys, it.offset) }

    /** Changes when any rig (bones, curves, keys, mesh, paint) changes -> resend to native. */
    fun rigKey(): Any = drawTracks.map { it.rig to it.attach }

    /**
     * Send every row's static transform, start offset and keyframes to the native canvas. Native evaluates the
     * curves itself (easing included) at the time set by [syncTime], so playback / scrubbing costs one float.
     */
    fun syncXf() {
        val rows = drawTracks.toList()
        val n = layerSlots
        if (rows.isEmpty() || n == 0) return
        val statics = FloatArray(rows.size * 7)
        rows.forEachIndexed { i, r -> r.xf.writeTo(statics, i * 7) }
        val keys = FloatArray(rows.sumOf { it.keys.size } * KEY_STRIDE)
        var o = 0
        for (r in rows) { r.packedKeys.copyInto(keys, o); o += r.packedKeys.size }
        NativeCanvas.setAnim(
            n, IntArray(rows.size) { rows[it].offset }, IntArray(rows.size) { rows[it].keys.size }, statics, keys,
        )
        syncTime()
    }

    /** Playhead -> native (it re-evaluates every row's curve on the GL thread). */
    fun syncTime() = NativeCanvas.setTime(frame.toFloat())

    // ---------------------------------------------------------------- layer transform
    /** The row's pose at the playhead. Animated rows ask C++ for it (curve + easing), so UI and canvas always agree. */
    fun trackXf(track: Int): LayerXf {
        val t = drawTracks.firstOrNull { it.id == track } ?: return LayerXf()
        if (t.keys.isEmpty()) return t.xf
        val v = NativeCanvas.evalXf(t.packedKeys, t.keys.size, (frame - t.offset).toFloat(), t.baseArray())
        return LayerXf(v[0], v[1], v[2], v[3], v[4], v[5], v[6])
    }

    /**
     * Raw change (no history): gestures / sliders wrap a whole drag in snapshot() + commitEdit().
     * Static value: edits the row. A property that HAS a lane is AUTO-KEYED - the edited value becomes its key at the
     * playhead (created if missing, easing kept if it exists). Properties without a lane just change their static value.
     * A pivot move never keys: it re-bases the keys so the picture stays where it is.
     */
    fun updateXf(track: Int, f: (LayerXf) -> LayerXf) {
        val i = drawTracks.indexOfFirst { it.id == track }
        if (i < 0) return
        val t = drawTracks[i]
        if (t.keys.isEmpty()) { drawTracks[i] = t.copy(xf = f(t.xf)); return }
        playing = false   // an edit keys the frame under the playhead, so it must not be moving
        val cur = trackXf(track)
        val next = f(cur)
        if (next == cur) return
        if (next.px != cur.px || next.py != cur.py) { rebasePivot(i, Offset(next.px, next.py)); return }
        var xf = t.xf
        var keys = t.keys
        val local = frame - t.offset
        for (c in Chan.values()) {
            if (next.get(c) == cur.get(c)) continue
            if (keys.any { it.chan == c.id }) keys = upsertKey(keys, c, local, next.get(c), null, trackSpan(t.id))
            else xf = xf.set(c, next.get(c))
        }
        drawTracks[i] = t.copy(xf = xf, keys = keys)
        selectedKey = local.coerceAtLeast(0)
    }

    /** Pivot moved on an animated row. Position keys are shifted (or, when scale / rotation are animated, baked at every key frame). */
    private fun rebasePivot(i: Int, np: Offset) {
        val t = drawTracks[i]
        val q = t.xf.withPivot(np)
        if (t.keys.none { it.chan >= Chan.Width.id }) {
            val dx = q.tx - t.xf.tx; val dy = q.ty - t.xf.ty
            drawTracks[i] = t.copy(
                xf = q,
                keys = t.keys.map { if (it.chan == Chan.PosX.id) it.copy(value = it.value + dx) else if (it.chan == Chan.PosY.id) it.copy(value = it.value + dy) else it },
            )
            return
        }
        var keys = t.keys
        for (fr in t.keyFrames) {
            val v = NativeCanvas.evalXf(t.packedKeys, t.keys.size, fr.toFloat(), t.baseArray())
            val p = LayerXf(v[0], v[1], v[2], v[3], v[4], v[5], v[6]).withPivot(np)
            keys = upsertKey(keys, Chan.PosX, fr, p.tx, null, Int.MAX_VALUE)
            keys = upsertKey(keys, Chan.PosY, fr, p.ty, null, Int.MAX_VALUE)
        }
        drawTracks[i] = t.copy(xf = q, keys = keys)
    }

    /** Back to untransformed; the pivot goes back to the centre of the drawing. Also drops the row's keys. */
    fun resetXf(track: Int) = edit {
        val c = trackBounds(track)?.center ?: Offset(0.5f, 0.5f)
        val i = drawTracks.indexOfFirst { it.id == track }
        if (i < 0) return@edit
        drawTracks[i] = drawTracks[i].copy(xf = LayerXf(px = c.x, py = c.y), keys = emptyList())
        selectedKey = -1
        kfOpen = null
    }

    // ------------------------------------------------------------------ keyframes (data here, maths in C++)
    // Every slider / switch with a keyframe icon is a PropRef. The icon opens ONE drawer (kfOpen) under that slider.

    /** Easing given to newly created keys. */
    var defaultEase by mutableStateOf(Easing.Smooth)

    /** Selected key frame of the active row (row-local frame), -1 = none. */
    var selectedKey by mutableIntStateOf(-1)

    /** The property whose keyframe drawer is open (null = none). */
    var kfOpen by mutableStateOf<PropRef?>(null)
    var keyRulerShown by mutableStateOf(true)

    fun toggleDrawer(p: PropRef) { kfOpen = if (kfOpen == p) null else p }

    fun trackKeys(track: Int = activeTrack): List<ChanKey> = drawTracks.firstOrNull { it.id == track }?.keys ?: emptyList()
    fun laneKeys(track: Int, c: Chan): List<ChanKey> = trackKeys(track).filter { it.chan == c.id }
    fun isAnimated(track: Int = activeTrack) = trackKeys(track).isNotEmpty()
    fun localFrame(track: Int = activeTrack, f: Int = frame) = f - trackOffset(track)

    /** Drawing frames in the row (keys beyond this would sit where the row is already gone). */
    fun trackSpan(track: Int): Int = cels.sumOf { if (it.track == track) it.len else 0 }

    /** 0 = not animated, 1 = animated (no key under the playhead), 2 = a key sits under the playhead. Drives the icon. */
    fun keyState(p: PropRef): Int = when (p) {
        is PropRef.Draw -> {
            val lane = laneKeys(p.track, p.chan)
            if (lane.isEmpty()) 0 else if (lane.any { it.frame == localFrame(p.track) }) 2 else 1
        }
        is PropRef.RigChannel -> rigKeyState(p)
        is PropRef.AudioGain -> {
            val c = timeline.clip(p.clip)
            if (c == null || c.gainKeys.isEmpty()) 0 else if (timeline.gainKeyAt(c, frameToMs(frame), 1000L / fps / 2) != null) 2 else 1
        }
    }

    /** The key whose OUTGOING segment the easing editor shows: the selected one, else the last one before the playhead. */
    fun segmentKey(p: PropRef.Draw): ChanKey? {
        val lane = laneKeys(p.track, p.chan)
        val local = localFrame(p.track)
        return lane.firstOrNull { it.frame == selectedKey && p.track == activeTrack } ?: lane.lastOrNull { it.frame <= local } ?: lane.firstOrNull()
    }

    /** Raw: set / replace one key. The easing of an existing key is kept unless [ease] is given. Keeps (channel, frame) order. */
    private fun upsertKey(keys: List<ChanKey>, c: Chan, local: Int, value: Float, ease: Easing?, span: Int): List<ChanKey> {
        val fr = local.coerceIn(0, (span - 1).coerceAtLeast(0))
        val out = keys.toMutableList()
        val at = out.indexOfFirst { it.chan == c.id && it.frame == fr }
        if (at >= 0) out[at] = out[at].copy(value = value, ease = ease ?: out[at].ease)
        else { out.add(ChanKey(c.id, fr, value, ease ?: defaultEase)); out.sortWith(ChanKeyOrder) }
        return out
    }

    /** Key [p] at the playhead with its current value (one undo step). Audio: see [TimelineState.putGainKey]. */
    fun addKey(p: PropRef) {
        if (p is PropRef.RigChannel) { rigAddKey(p); return }
        edit { addKeyRaw(p) }
    }

    private fun addKeyRaw(p: PropRef) {
        when (p) {
            is PropRef.Draw -> {
                val i = drawTracks.indexOfFirst { it.id == p.track }
                if (i < 0) return
                val t = drawTracks[i]
                val local = localFrame(p.track)
                drawTracks[i] = t.copy(keys = upsertKey(t.keys, p.chan, local, trackXf(p.track).get(p.chan), null, trackSpan(p.track)))
                selectedKey = local.coerceIn(0, (trackSpan(p.track) - 1).coerceAtLeast(0))
            }
            is PropRef.RigChannel -> Unit
            is PropRef.AudioGain -> timeline.clip(p.clip)?.let { c ->
                timeline.putGainKey(c.id, timeline.srcMsAt(c, frameToMs(frame)), timeline.gainAt(c, frameToMs(frame)))
            }
        }
    }

    /** Remove the key of [p] at the playhead. The last key of a property going away leaves its value as the static one. */
    fun removeKey(p: PropRef) {
        if (p is PropRef.RigChannel) { rigRemoveKey(p); return }
        edit { removeKeyRaw(p) }
    }

    private fun removeKeyRaw(p: PropRef) {
        when (p) {
            is PropRef.Draw -> {
                val i = drawTracks.indexOfFirst { it.id == p.track }
                if (i < 0) return
                val t = drawTracks[i]
                val local = localFrame(p.track)
                val k = t.keys.firstOrNull { it.chan == p.chan.id && it.frame == local } ?: return
                val rest = t.keys.filter { it !== k }
                drawTracks[i] = t.copy(keys = rest, xf = if (rest.none { it.chan == p.chan.id }) t.xf.set(p.chan, k.value) else t.xf)
                selectedKey = -1
            }
            is PropRef.RigChannel -> Unit
            is PropRef.AudioGain -> timeline.clip(p.clip)?.let { c ->
                timeline.removeGainKeyNear(c.id, frameToMs(frame), 1000L / fps / 2)
            }
        }
    }

    /** Long-press on the icon: key / un-key at the playhead. */
    fun toggleKey(p: PropRef) { if (keyState(p) == 2) removeKey(p) else addKey(p) }

    /** Drop every key of [p]; its value at the playhead stays as the static value. */
    fun clearKeys(p: PropRef) {
        if (p is PropRef.RigChannel) { rigClearKeys(p); return }
        edit { clearKeysRaw(p) }
    }

    private fun clearKeysRaw(p: PropRef) {
        when (p) {
            is PropRef.Draw -> {
                val i = drawTracks.indexOfFirst { it.id == p.track }
                if (i < 0 || drawTracks[i].lane(p.chan).isEmpty()) return
                val t = drawTracks[i]
                drawTracks[i] = t.copy(xf = t.xf.set(p.chan, trackXf(p.track).get(p.chan)), keys = t.keys.filter { it.chan != p.chan.id })
                selectedKey = -1
            }
            is PropRef.RigChannel -> Unit
            is PropRef.AudioGain -> timeline.clip(p.clip)?.let { c ->
                val g = timeline.gainAt(c, frameToMs(frame))
                timeline.update(c.id) { it.copy(gain = g, gainKeys = emptyList()) }
            }
        }
    }

    /** Raw (timeline / ruler drag; wrap in snapshot + commitEdit). Moves every key on [from] of the row; returns the frame it ended on. */
    fun moveKey(track: Int, from: Int, to: Int, only: Chan? = null): Int {
        val i = drawTracks.indexOfFirst { it.id == track }
        if (i < 0) return from
        val t = drawTracks[i]
        fun hit(k: ChanKey) = k.frame == from && (only == null || k.chan == only.id)
        if (t.keys.none(::hit)) return from
        val dst = to.coerceIn(0, (trackSpan(track) - 1).coerceAtLeast(0))
        if (dst == from) return from
        val moving = t.keys.filter(::hit)
        if (t.keys.any { k -> k.frame == dst && moving.any { it.chan == k.chan } }) return from   // never land on a key of the same property
        drawTracks[i] = t.copy(keys = (t.keys.filterNot(::hit) + moving.map { it.copy(frame = dst) }).sortedWith(ChanKeyOrder))
        if (selectedKey == from) selectedKey = dst
        return dst
    }

    /** Raw: easing of the segment leaving the key at [local] on one property. Chips wrap it in [edit]; the bezier handles in snapshot + commitEdit. */
    fun setEase(track: Int, c: Chan, local: Int, e: Easing) {
        val i = drawTracks.indexOfFirst { it.id == track }
        if (i < 0) return
        val t = drawTracks[i]
        drawTracks[i] = t.copy(keys = t.keys.map { if (it.chan == c.id && it.frame == local) it.copy(ease = e) else it })
    }

    fun setEaseAll(track: Int, c: Chan, e: Easing) = edit {
        val i = drawTracks.indexOfFirst { it.id == track }
        if (i >= 0) drawTracks[i] = drawTracks[i].copy(keys = drawTracks[i].keys.map { if (it.chan == c.id) it.copy(ease = e) else it })
        defaultEase = e
    }

    /** Playhead to the next ([dir] > 0) / previous key of [p]. */
    fun jumpKey(p: PropRef, dir: Int) {
        when (p) {
            is PropRef.Draw -> {
                val off = trackOffset(p.track)
                val cur = frame - off
                val ks = laneKeys(p.track, p.chan)
                val k = if (dir > 0) ks.firstOrNull { it.frame > cur } else ks.lastOrNull { it.frame < cur }
                if (k != null) { seek(off + k.frame); selectedKey = k.frame }
            }
            is PropRef.RigChannel -> rigJumpKey(p, dir)
            is PropRef.AudioGain -> timeline.clip(p.clip)?.let { c ->
                val cur = timeline.srcMsAt(c, frameToMs(frame))
                val ks = c.gainKeys
                val k = if (dir > 0) ks.firstOrNull { it.ms > cur + 1000L / fps / 2 } else ks.lastOrNull { it.ms < cur - 1000L / fps / 2 }
                if (k != null) seek(msToFrame(c.startMs + (k.ms - c.inMs)).coerceAtLeast(0))
            }
        }
    }

    /** Playhead to the next / previous key mark of the row: of the OPEN property when a drawer is open, else of any property. */
    fun jumpMark(dir: Int, track: Int = activeTrack) {
        val open = kfOpen
        if ((open is PropRef.Draw || open is PropRef.RigChannel) && openLaneKeys(track) != null) { jumpKey(open!!, dir); return }
        val off = trackOffset(track)
        val k = marksAround(track, dir) ?: return
        seek(off + k); selectedKey = k
    }

    /** Row-local frame of the nearest mark in [dir], or null. Same scope rule as [jumpMark]. */
    fun marksAround(track: Int, dir: Int): Int? {
        val cur = frame - trackOffset(track)
        val lane = openLaneKeys(track)
        val frames = if (lane != null) lane.map { it.frame }
        else drawTracks.firstOrNull { it.id == track }?.markFrames ?: emptyList()
        return if (dir > 0) frames.firstOrNull { it > cur } else frames.lastOrNull { it < cur }
    }

    fun keyOf(p: PropRef.Draw, local: Int): ChanKey? = laneKeys(p.track, p.chan).firstOrNull { it.frame == local }

    /** Box around everything drawn on [track] (all its drawings), paper units; null when it is empty. */
    fun trackBounds(track: Int): Rect? {
        val ids = HashSet<Int>()
        for (c in cels) if (c.track == track) ids.add(c.id)
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (s in strokes) {
            if (s.erase || s.cel !in ids) continue
            val r = s.size / 512f * 0.5f
            for (p in s.pts) {
                if (p.x - r < x0) x0 = p.x - r
                if (p.y - r < y0) y0 = p.y - r
                if (p.x + r > x1) x1 = p.x + r
                if (p.y + r > y1) y1 = p.y + r
            }
        }
        return if (x0 > x1 || y0 > y1) null else Rect(x0, y0, x1, y1)
    }

    /** Pivot = centre of the drawing; only set while the row is untouched, so it never jumps afterwards. */
    private fun initPivot(track: Int) {
        val i = drawTracks.indexOfFirst { it.id == track }
        if (i < 0) return
        val x = drawTracks[i].xf
        if (!x.isIdentity || x.px != 0.5f || x.py != 0.5f) return   // already placed (or moved by the user)
        val b = trackBounds(track) ?: return
        drawTracks[i] = drawTracks[i].copy(xf = drawTracks[i].xf.copy(px = b.center.x, py = b.center.y))
    }

    fun openTransform() {
        if (drawTracks.isEmpty()) return
        playing = false
        panel = Panel.None
        timeline.selectedClip = -1
        initPivot(activeTrack)
        if (rigOpen) tool = Tool.Brush
        transformOpen = true
    }

    fun selectTransformTrack(id: Int) {
        timeline.selectedClip = -1
        selectedKey = -1
        kfOpen = null
        activeTrack = id
        initPivot(id)
    }

    /** Changes when playback or seeking makes some row show another drawing (cheap update, no replay). */
    fun shownKey(): Any = drawTracks.map { celAt(frame, it.id)?.id }

    /** Tell native which drawing each row shows now; it redraws only the layers whose drawing changed. */
    fun syncShown() {
        NativeCanvas.setShown(drawTracks.mapNotNull { celAt(frame, it.id)?.id }.toIntArray())
    }

    /**
     * Replay EVERY stroke of every row into the native canvas (native history == [strokes], same order) and tell it
     * which drawings are on screen. Native keeps one lazily-allocated texture per (row, layer) that has something
     * to show and composites them all bottom -> top in a single GL surface.
     */
    fun rebuildNative() {
        NativeCanvas.resetStrokes()
        val rows = drawTracks.toList()
        val n = layerSlots
        NativeCanvas.setLayers(
            IntArray(rows.size * n) { it },
            // a row with fewer layers than the widest row leaves its spare slots hidden
            BooleanArray(rows.size * n) { rows[it / n].layers.getOrNull(it % n)?.visible ?: false },
        )
        syncXf()
        syncRig()
        syncShown() // before the strokes, so they are drawn straight into the right layers
        val trackOf = HashMap<Int, Int>().also { m -> cels.forEach { m[it.id] = it.track } }
        fun push(st: InkStroke): Boolean {
            val track = trackOf[st.cel] ?: return false
            val xy = FloatArray(st.pts.size * 2)
            for (i in st.pts.indices) { xy[i * 2] = st.pts[i].x; xy[i * 2 + 1] = st.pts[i].y }
            NativeCanvas.addStroke(st.cel, nativeLayer(track, st.layer), st.color.toArgb(), st.size, st.erase, xy)
            return true
        }
        for (st in strokes) push(st)
        // newest-redo first, then undo them all, so redo pops in the right order
        if (redoStack.isNotEmpty()) {
            var pushed = 0
            for (st in redoStack.asReversed()) if (push(st)) pushed++
            repeat(pushed) { NativeCanvas.undo() }
        }
    }

    private fun keepHandles(): Set<Int> {
        val keep = HashSet<Int>()
        fun add(s: EditSnap) = s.clips.forEach { keep.add(it.handle) }
        for (o in undoOps) if (o is HistoryOp.Edit) { add(o.before); add(o.after) }
        for (o in redoOps) if (o is HistoryOp.Edit) { add(o.before); add(o.after) }
        (clipboard as? Clipboard.Audio)?.let { keep.add(it.clip.handle) }
        (clipboard as? Clipboard.Tracks)?.audio?.forEach { a -> a.clips.forEach { keep.add(it.handle) } }
        return keep
    }

    private fun putClipboard(c: Clipboard) {
        clipboard = c
        timeline.collectGarbage(keepHandles()) // an overwritten audio clipboard may have been the last reference
    }

    /** Run a discrete timeline edit as a single undo step. */
    inline fun edit(block: () -> Unit) {
        val before = snapshot()
        block()
        commitEdit(before)
    }

    private fun pushOp(op: HistoryOp) {
        undoOps.add(op)
        redoOps.clear()
        redoStack.clear()
        while (undoOps.size > HISTORY_MAX) undoOps.removeAt(0)
        // free native PCM that only evicted/dropped history entries could still bring back
        timeline.collectGarbage(keepHandles())
    }

    private fun restore(s: EditSnap) {
        val strokesChanged = strokes.toList() != s.strokes
        Snapshot.withMutableSnapshot {
            timeline.clips.clear(); timeline.clips.addAll(s.clips)
            timeline.tracks.clear(); timeline.tracks.addAll(s.tracks)
            cels.clear(); cels.addAll(s.cels)
            drawTracks.clear(); drawTracks.addAll(s.drawTracks)
            if (drawTracks.none { it.id == activeTrack }) activeTrack = drawTracks.firstOrNull()?.id ?: 0
            if (strokesChanged) { strokes.clear(); strokes.addAll(s.strokes) }
            timeline.selectedClip = if (s.clips.any { it.id == s.selected }) s.selected else -1
        }
        timeline.pushToNative()
        seek(frame) // cels may have shrunk
        if (strokesChanged) rebuildNative()
    }

    /** The native layer already holds the stroke that was drawn live; this only closes it into history. */
    fun commit(s: InkStroke) {
        strokes.add(s)
        pushOp(HistoryOp.Stroke)
        NativeCanvas.endStroke()
    }

    fun undo() {
        val op = undoOps.removeLastOrNull() ?: return
        when (op) {
            HistoryOp.Stroke -> {
                val s = strokes.removeLastOrNull() ?: return
                redoStack.add(s)
                NativeCanvas.undo() // native history mirrors strokes; it only redraws if the drawing is on screen
            }
            is HistoryOp.Edit -> restore(op.before)
        }
        redoOps.add(op)
    }

    fun redo() {
        val op = redoOps.removeLastOrNull() ?: return
        when (op) {
            HistoryOp.Stroke -> {
                val s = redoStack.removeLastOrNull() ?: return
                strokes.add(s)
                NativeCanvas.redo()
            }
            is HistoryOp.Edit -> restore(op.after)
        }
        undoOps.add(op)
    }

    fun seek(i: Int) {
        frame = i.coerceIn(0, frameCount - 1)
    }

    fun step(d: Int) = seek(frame + d)

    fun frameToMs(f: Int): Long = f * 1000L / fps
    fun msToFrame(ms: Long): Int = ((ms * fps + 500L) / 1000L).toInt()   // nearest frame (frameToMs floors)

    // ------------------------------------------------------------------ timeline rows (drawing layers)
    // Every cel helper takes a row id and defaults to the active row; indices are local to that row.

    val activeTrackObj: DrawTrack? get() = drawTracks.firstOrNull { it.id == activeTrack }
    val activeLocked: Boolean get() = activeTrackObj?.locked == true

    /** Can a stroke be started right now? (row exists, unlocked, and has a drawing under the playhead) */
    val canDrawHere: Boolean get() = activeTrackObj?.locked == false && currentCel != null

    fun trackCels(track: Int = activeTrack): List<Cel> = cels.filter { it.track == track }
    /** Frame where [track]'s first drawing starts. */
    fun trackOffset(track: Int): Int = drawTracks.firstOrNull { it.id == track }?.offset ?: 0

    /** Frame where [track] ends (offset + all its holds). */
    fun trackLen(track: Int): Int = trackOffset(track) + cels.sumOf { if (it.track == track) it.len else 0 }

    /** Raw move of a whole row to start at [offset] (no history; wrap in [edit] / commitEdit). */
    fun setTrackOffset(track: Int, offset: Int) {
        val i = drawTracks.indexOfFirst { it.id == track }
        if (i >= 0) drawTracks[i] = drawTracks[i].copy(offset = offset.coerceAtLeast(0))
    }

    /** Nudge the active row by [d] frames; the playhead goes with it so the same drawing stays selected. One undo step. */
    fun shiftLayer(d: Int) = edit {
        val t = activeTrack
        val before = trackOffset(t)
        setTrackOffset(t, before + d)
        frame = (frame + (trackOffset(t) - before)).coerceAtLeast(0)
    }

    /** Index (inside [track]) of the drawing shown at frame [f]; -1 before the row starts or after it has ended. */
    fun celIndexAt(f: Int, track: Int = activeTrack): Int {
        val start = trackOffset(track)
        if (f < start) return -1
        var end = start
        var i = 0
        for (c in cels) {
            if (c.track != track) continue
            end += c.len
            if (f < end) return i
            i++
        }
        return -1
    }

    fun celStart(i: Int, track: Int = activeTrack): Int {
        var a = trackOffset(track)
        var k = 0
        for (c in cels) {
            if (c.track != track) continue
            if (k == i) break
            a += c.len
            k++
        }
        return a
    }

    fun celAt(f: Int, track: Int = activeTrack): Cel? =
        celIndexAt(f, track).let { if (it < 0) null else trackCels(track)[it] }

    val currentCel: Cel? get() = celAt(frame)

    /** Position in [cels] of the [i]-th drawing of [track]. */
    private fun gi(track: Int, i: Int): Int {
        var k = -1
        for (n in cels.indices) if (cels[n].track == track && ++k == i) return n
        return -1
    }

    /** Insert [c] so it becomes the [local]-th drawing of [track]. */
    private fun insertCel(track: Int, local: Int, c: Cel) {
        val n = trackCels(track).size
        val at = if (n == 0) cels.size else if (local <= 0) gi(track, 0) else gi(track, local.coerceAtMost(n) - 1) + 1
        cels.add(at, c)
    }

    fun setLen(i: Int, len: Int, track: Int = activeTrack) {
        val g = gi(track, i)
        if (g < 0) return
        cels[g] = cels[g].copy(len = len.coerceAtLeast(1)) // no upper cap: the timeline grows with its content
        seek(frame)
    }

    /** New blank drawing on the active row, right after the current one (or at the row's end). */
    fun addCel(len: Int = 4) {
        val t = activeTrack
        val at = celIndexAt(frame, t).let { if (it < 0) trackCels(t).size else it + 1 }
        insertCel(t, at, Cel(newCelId(), len, t))
        frame = celStart(at, t)
    }

    // ------------------------------------------------------------------ drawing rows: add / duplicate / delete
    // Each public op is ONE undo step.

    private fun newTrackId() = (drawTracks.maxOfOrNull { it.id } ?: -1) + 1

    /** New empty drawing row above the active one, one blank drawing as long as the whole timeline. */
    fun addDrawTrack() = edit {
        val id = newTrackId()
        val at = drawTracks.indexOfFirst { it.id == activeTrack }.let { if (it < 0) drawTracks.size else it + 1 }
        drawTracks.add(at, DrawTrack(id, "Drawing ${id + 1}", layers = remapLayers(layers.ifEmpty { listOf(Layer(0, "Body")) }.map { it.copy(visible = true) }).first))
        cels.add(Cel(newCelId(), frameCount, id))
        activeTrack = id
    }

    /** Copy of the active row (every drawing, hold and stroke) placed directly above it. */
    fun duplicateDrawTrack() = edit {
        val i = drawTracks.indexOfFirst { it.id == activeTrack }
        if (i < 0) return@edit
        val src = drawTracks[i]
        val id = newTrackId()
        val (ls, map) = remapLayers(src.layers)
        drawTracks.add(i + 1, DrawTrack(id, "${src.name} copy", offset = src.offset, xf = src.xf, keys = src.keys, rig = src.rig, attach = src.attach, layers = ls))
        for (c in trackCels(src.id)) {
            val nid = newCelId()
            cels.add(Cel(nid, c.len, id))
            strokes.addAll(strokesOf(c.id).map { it.copy(cel = nid, layer = map[it.layer] ?: it.layer) })
        }
        activeTrack = id
    }

    /** Removes the active row with all its drawings (the last row stays). */
    fun deleteDrawTrack() = edit {
        if (drawTracks.size <= 1) return@edit
        val i = drawTracks.indexOfFirst { it.id == activeTrack }
        if (i < 0) return@edit
        val id = drawTracks[i].id
        val dead = cels.filter { it.track == id }.mapTo(HashSet()) { it.id }
        strokes.removeAll { it.cel in dead }
        cels.removeAll { it.track == id }
        drawTracks.removeAt(i)
        activeTrack = drawTracks[i.coerceAtMost(drawTracks.lastIndex)].id
        seek(frame)
    }

    /**
     * Moves drawing row [id] to display slot [toSlot] (0 = top of the stack = painted in front, last slot = behind
     * everything). One undo step; the canvas and the export both follow [drawTracks] order.
     */
    fun moveDrawTrack(id: Int, toSlot: Int) = edit {
        val from = drawTracks.indexOfFirst { it.id == id }
        if (from < 0) return@edit
        val to = (drawTracks.lastIndex - toSlot).coerceIn(0, drawTracks.lastIndex)
        if (to != from) drawTracks.add(to, drawTracks.removeAt(from))
    }

    // ------------------------------------------------------------------ drawing (cel / frame) ops
    // Each public op is ONE undo step. Stroke edits are mirrored to the native canvas by commitEdit().

    private fun newCelId() = (cels.maxOfOrNull { it.id } ?: -1) + 1 // unique across ALL rows
    private fun strokesOf(cel: Int) = strokes.filter { it.cel == cel }

    fun copyCel() {
        val c = currentCel ?: return
        putClipboard(Clipboard.CelArt(c.len, strokesOf(c.id)))
    }

    fun cutCel() { copyCel(); deleteCel() }

    fun pasteCel() = edit {
        val c = clipboard as? Clipboard.CelArt ?: return@edit
        val t = activeTrack
        val at = celIndexAt(frame, t).let { if (it < 0) trackCels(t).size else it + 1 }
        val id = newCelId()
        insertCel(t, at, Cel(id, c.len, t))
        strokes.addAll(c.strokes.map { it.copy(cel = id) })
        frame = celStart(at, t)
    }

    /** New drawing right after this one with the same hold and a copy of all its strokes. */
    fun duplicateCel() = edit {
        val t = activeTrack
        val i = celIndexAt(frame, t)
        if (i < 0) return@edit
        val src = trackCels(t)[i]
        val id = newCelId()
        insertCel(t, i + 1, Cel(id, src.len, t))
        strokes.addAll(strokesOf(src.id).map { it.copy(cel = id) })
        frame = celStart(i + 1, t)
    }

    /** Removes the drawing; a row's last remaining one is only cleared (a row needs at least one). */
    fun deleteCel() = edit {
        val t = activeTrack
        val i = celIndexAt(frame, t)
        if (i < 0) return@edit
        val id = trackCels(t)[i].id
        strokes.removeAll { it.cel == id }
        if (trackCels(t).size > 1) {
            cels.removeAt(gi(t, i))
            frame = celStart(i.coerceAtMost(trackCels(t).lastIndex), t)
        }
    }

    fun clearCel() = edit {
        val id = currentCel?.id ?: return@edit
        strokes.removeAll { it.cel == id }
    }

    /** Cut the hold at the playhead: the second half becomes its own drawing, starting as a copy of the first. */
    fun splitCel() = edit {
        val t = activeTrack
        val i = celIndexAt(frame, t)
        if (i < 0) return@edit
        val c = trackCels(t)[i]
        val at = frame - celStart(i, t)
        if (at <= 0 || at >= c.len) return@edit
        val id = newCelId()
        cels[gi(t, i)] = c.copy(len = at)
        insertCel(t, i + 1, Cel(id, c.len - at, t))
        strokes.addAll(strokesOf(c.id).map { it.copy(cel = id) })
    }

    /** Raw reorder inside one row (no history): keeps the playhead at the same spot inside the moved drawing. */
    fun moveCel(from: Int, to: Int, track: Int = activeTrack) {
        val n = trackCels(track).size
        if (from == to || from !in 0 until n || to !in 0 until n) return
        val off = frame - celStart(from, track)
        val c = cels.removeAt(gi(track, from))
        insertCel(track, to, c)
        frame = celStart(to, track) + off
    }

    fun moveCelBy(d: Int) = edit {
        val i = celIndexAt(frame)
        if (i >= 0) moveCel(i, i + d)
    }

    /** Which slot a dragged drawing (centre at [centerFrame]) should take: one step per call, no jitter. */
    fun reorderTarget(from: Int, centerFrame: Float, track: Int = activeTrack): Int {
        val l = trackCels(track)
        val right = from + 1 < l.size && centerFrame > celStart(from + 1, track) + l[from + 1].len / 2f
        val left = from > 0 && centerFrame < celStart(from - 1, track) + l[from - 1].len / 2f
        return if (right) from + 1 else if (left) from - 1 else from
    }

    fun changeHold(delta: Int) = edit {
        val i = celIndexAt(frame)
        if (i < 0) return@edit
        setLen(i, trackCels()[i].len + delta)
    }

    // ------------------------------------------------------------------ drawing layer ops (Body / Head / Tail ...)

    fun copyLayerArt() {
        val id = currentCel?.id ?: return
        putClipboard(Clipboard.LayerArt(strokes.filter { it.cel == id && it.layer == activeLayer }))
    }

    fun pasteLayerArt() = edit {
        val c = clipboard as? Clipboard.LayerArt ?: return@edit
        val id = currentCel?.id ?: return@edit
        strokes.addAll(c.strokes.map { it.copy(cel = id, layer = activeLayer) })
    }

    /** Clears the active layer on the current drawing only. */
    fun clearLayerArt() = edit {
        val id = currentCel?.id ?: return@edit
        strokes.removeAll { it.cel == id && it.layer == activeLayer }
    }

    /** New layer above the active one with a copy of its strokes on every drawing of this row. */
    fun duplicateLayer() = edit {
        val i = layers.indexOfFirst { it.id == activeLayer }
        if (i < 0) return@edit
        val l = layers[i]
        val id = newLayerId()
        editLayers(activeTrack) { ls -> ls.toMutableList().also { it.add(i + 1, Layer(id, "${l.name} copy", l.visible)) } }
        strokes.addAll(strokes.filter { it.layer == l.id }.map { it.copy(layer = id) })
        activeLayer = id
    }

    fun deleteLayer() = edit {
        if (layers.size <= 1) return@edit
        val i = layers.indexOfFirst { it.id == activeLayer }
        if (i < 0) return@edit
        val id = layers[i].id
        editLayers(activeTrack) { ls -> ls.filter { it.id != id } }
        strokes.removeAll { it.layer == id }
        activeLayer = layers[i.coerceAtMost(layers.lastIndex)].id
    }

    /** [d] = +1 raises the layer (layers are stored bottom -> top). */
    fun moveLayer(d: Int) = edit {
        val i = layers.indexOfFirst { it.id == activeLayer }
        val j = i + d
        if (i < 0 || j !in layers.indices) return@edit
        editLayers(activeTrack) { ls -> ls.toMutableList().also { it.add(j, it.removeAt(i)) } }
    }

    fun toggleLayerVisible(id: Int) = edit {
        val t = drawTracks.firstOrNull { tr -> tr.layers.any { it.id == id } }?.id ?: return@edit
        editLayers(t) { ls -> ls.map { if (it.id == id) it.copy(visible = !it.visible) else it } }
    }

    // ------------------------------------------------------------------ audio clip ops

    fun copyAudio() {
        timeline.clip(timeline.selectedClip)?.let { putClipboard(Clipboard.Audio(it)) }
    }

    fun cutAudio() { copyAudio(); edit { timeline.deleteSelected() } }

    /** Paste at the playhead on the selected clip's layer (else the first unlocked one); pushed after a collision. */
    fun pasteAudio(playheadMs: Long) = edit {
        val c = (clipboard as? Clipboard.Audio)?.clip ?: return@edit
        val tl = timeline
        val trackId = tl.clip(tl.selectedClip)?.track?.takeIf { id -> tl.tracks.any { it.id == id && !it.locked } }
            ?: tl.tracks.firstOrNull { !it.locked }?.id
            ?: run { tl.addTrack(); tl.tracks.last().id }
        val onTrack = tl.clips.filter { it.track == trackId }
        val overlaps = onTrack.any { playheadMs < it.startMs + it.lenMs && playheadMs + c.lenMs > it.startMs }
        val start = if (overlaps) onTrack.maxOf { it.startMs + it.lenMs } else playheadMs
        val n = c.copy(id = tl.nextClipId(), track = trackId, startMs = start)
        tl.clips.add(n)
        tl.selectedClip = n.id
    }

    // ------------------------------------------------------------------ selection-based ops (toolbar + ••• menu)

    /** Set by the ••• menu; TimelineEditor owns the file picker, launches it and clears the flag. */
    var audioPickRequested by mutableStateOf(false)

    val hasAudioSelection: Boolean get() = timeline.clip(timeline.selectedClip) != null

    /** The edit target is the selected audio clip, otherwise the drawing under the playhead. */
    val selectionLocked: Boolean
        get() {
            val c = timeline.clip(timeline.selectedClip)
            return if (c != null) timeline.tracks.any { it.id == c.track && it.locked } else activeLocked
        }

    fun copySelection() { if (hasAudioSelection) copyAudio() else copyCel() }
    fun cutSelection() { if (hasAudioSelection) cutAudio() else cutCel() }
    fun duplicateSelection() { if (hasAudioSelection) edit { timeline.duplicateSelected() } else duplicateCel() }
    fun splitSelection(playheadMs: Long) { if (hasAudioSelection) edit { timeline.split(playheadMs) } else splitCel() }
    fun deleteSelection() { if (hasAudioSelection) edit { timeline.deleteSelected() } else deleteCel() }

    /** Paste whatever is on the clipboard: audio at the playhead, drawings after the current frame. */
    fun paste(playheadMs: Long) {
        when (clipboard) {
            is Clipboard.Audio -> pasteAudio(playheadMs)
            is Clipboard.CelArt -> pasteCel()
            is Clipboard.LayerArt -> pasteLayerArt()
            is Clipboard.Tracks -> pasteTracks()
            null -> Unit
        }
    }

    // ------------------------------------------------------------------ select tracks (toolbar "Select")
    // Drawing rows and audio layers have separate id spaces, so each keeps its own selection list.

    var trackSelectMode by mutableStateOf(false)
    val selDraw = mutableStateListOf<Int>()
    val selAudio = mutableStateListOf<Int>()
    val selCount: Int get() = selDraw.size + selAudio.size

    fun toggleDrawSel(id: Int) { if (!selDraw.remove(id)) selDraw.add(id) }
    fun toggleAudioSel(id: Int) { if (!selAudio.remove(id)) selAudio.add(id) }
    fun clearTrackSel() { selDraw.clear(); selAudio.clear() }
    fun endTrackSelect() { trackSelectMode = false; clearTrackSel() }

    fun selectAllTracks() {
        clearTrackSel()
        selDraw.addAll(drawTracks.map { it.id })
        selAudio.addAll(timeline.tracks.map { it.id })
    }

    /** Selected rows that are locked are left alone; at least one drawing row and one audio layer always remain. */
    fun deleteSelectedTracks() = edit {
        val tl = timeline
        var dr = drawTracks.filter { it.id in selDraw && !it.locked }.map { it.id }
        if (dr.size >= drawTracks.size) dr = dr.dropLast(1)
        for (id in dr) {
            val dead = cels.filter { it.track == id }.mapTo(HashSet()) { it.id }
            strokes.removeAll { it.cel in dead }
            cels.removeAll { it.track == id }
            drawTracks.removeAll { it.id == id }
        }
        if (drawTracks.isNotEmpty() && drawTracks.none { it.id == activeTrack }) activeTrack = drawTracks.last().id
        var au = tl.tracks.filter { it.id in selAudio && !it.locked }.map { it.id }
        if (au.size >= tl.tracks.size) au = au.dropLast(1)
        for (id in au) tl.removeTrack(id)
        clearTrackSel()
        seek(frame)
    }

    private fun cloneDrawTrack(src: DrawTrack): Int {
        val i = drawTracks.indexOfFirst { it.id == src.id }
        val id = newTrackId()
        val (ls, map) = remapLayers(src.layers)
        drawTracks.add(i + 1, DrawTrack(id, "${src.name} copy", offset = src.offset, xf = src.xf, keys = src.keys, rig = src.rig, attach = src.attach, layers = ls))
        for (c in trackCels(src.id)) {
            val nid = newCelId()
            cels.add(Cel(nid, c.len, id))
            strokes.addAll(strokesOf(c.id).map { it.copy(cel = nid, layer = map[it.layer] ?: it.layer) })
        }
        return id
    }

    private fun cloneAudioTrack(t: AudioTrack, clips: List<AudioClip>): Int {
        val tl = timeline
        val id = tl.nextTrackId()
        tl.tracks.add(AudioTrack(id, "${t.name} copy", t.muted, false))
        for (c in clips) tl.clips.add(c.copy(id = tl.nextClipId(), track = id))
        return id
    }

    /** Copies of every selected row, each directly above its original; the copies become the selection. */
    fun duplicateSelectedTracks() = edit {
        val tl = timeline
        val nd = ArrayList<Int>()
        for (t in drawTracks.toList()) if (t.id in selDraw) nd.add(cloneDrawTrack(t))
        val na = ArrayList<Int>()
        for (t in tl.tracks.toList()) if (t.id in selAudio) na.add(cloneAudioTrack(t, tl.clips.filter { it.track == t.id }))
        clearTrackSel()
        selDraw.addAll(nd)
        selAudio.addAll(na)
        nd.lastOrNull()?.let { activeTrack = it }
    }

    fun copySelectedTracks() {
        val d = drawTracks.filter { it.id in selDraw }.map { t -> TrackArt(t, trackCels(t.id).map { c -> c.len to strokesOf(c.id) }) }
        val a = timeline.tracks.filter { it.id in selAudio }.map { t -> AudioArt(t, timeline.clips.filter { it.track == t.id }) }
        if (d.isEmpty() && a.isEmpty()) return
        putClipboard(Clipboard.Tracks(d, a))
    }

    fun cutSelectedTracks() { copySelectedTracks(); deleteSelectedTracks() }

    /** Pastes the copied tracks as NEW rows: drawings above the active row, audio at the end. */
    fun pasteTracks() = edit {
        val c = clipboard as? Clipboard.Tracks ?: return@edit
        val tl = timeline
        val nd = ArrayList<Int>()
        for (art in c.draw) {
            val id = newTrackId()
            val at = drawTracks.indexOfFirst { it.id == activeTrack }.let { if (it < 0) drawTracks.size else it + 1 }
            val t = art.track
            val (ls, map) = remapLayers(t.layers)
            drawTracks.add(at, DrawTrack(id, "${t.name} copy", offset = t.offset, xf = t.xf, keys = t.keys, rig = t.rig, attach = t.attach, layers = ls))
            for ((len, st) in art.cels) {
                val nid = newCelId()
                cels.add(Cel(nid, len, id))
                strokes.addAll(st.map { it.copy(cel = nid, layer = map[it.layer] ?: it.layer) })
            }
            activeTrack = id
            nd.add(id)
        }
        val na = ArrayList<Int>()
        for (a in c.audio) na.add(cloneAudioTrack(a.track, a.clips))
        if (trackSelectMode) { clearTrackSel(); selDraw.addAll(nd); selAudio.addAll(na) }
    }

    fun setSelectedLocked(lock: Boolean) = edit {
        for (i in drawTracks.indices) if (drawTracks[i].id in selDraw) drawTracks[i] = drawTracks[i].copy(locked = lock)
        val tl = timeline
        for (i in tl.tracks.indices) if (tl.tracks[i].id in selAudio) tl.tracks[i] = tl.tracks[i].copy(locked = lock)
    }

    fun setSelectedMuted(mute: Boolean) = edit {
        val tl = timeline
        for (i in tl.tracks.indices) if (tl.tracks[i].id in selAudio) tl.tracks[i] = tl.tracks[i].copy(muted = mute)
    }

    fun addLayer() = edit {
        val id = newLayerId()
        val name = "Layer ${layers.size + 1}"
        editLayers(activeTrack) { it + Layer(id, name) }
        activeLayer = id
    }

    fun isVisible(layerId: Int) = drawTracks.any { t -> t.layers.any { it.id == layerId && it.visible } }
}

/** Owns the editor state so strokes, layers, cels and the view survive rotation / theme / locale changes. */
class EditorViewModel : ViewModel() {
    val state = EditorState()

    override fun onCleared() {
        AudioHandlerNative.pause()
        state.timeline.releaseAll()
    }
}

@Composable
fun rememberEditorState(): EditorState = viewModel<EditorViewModel>().state

@Composable
fun EditorScreen(
    modifier: Modifier = Modifier,
    state: EditorState = rememberEditorState(),
    surface: @Composable (PaddingValues) -> Unit = { EditorSurface(state, it) },
) {
    val density = LocalDensity.current
    var dockH by remember { mutableStateOf(0.dp) }
    val topH = 44.dp

    LaunchedEffect(state.playing, state.fps) {
        if (!state.playing) return@LaunchedEffect
        if (state.timeline.clips.isEmpty()) {
            while (state.playing) {
                delay(1000L / state.fps)
                state.frame = (state.frame + 1) % state.frameCount
            }
        } else {
            // audio clock drives the frame so picture and sound never drift apart
            AudioHandlerNative.play(state.frameToMs(state.frame))
            try {
                while (state.playing) {
                    delay(8)
                    val f = (AudioHandlerNative.positionMs() * state.fps / 1000).toInt()
                    if (f >= state.frameCount) {
                        state.frame = 0
                        AudioHandlerNative.play(0)
                    } else if (f != state.frame) {
                        state.frame = f
                    }
                }
            } finally {
                AudioHandlerNative.pause()
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        surface(PaddingValues(top = topH, bottom = dockH))

        TopBar(state, Modifier.align(Alignment.TopCenter).height(topH))

        Box(
            Modifier
                .fillMaxSize()
                .padding(top = topH, bottom = dockH)
        ) {
            ViewChip(state, Modifier.align(Alignment.TopStart).padding(start = 10.dp + state.railInset(ToolbarDock.Left), top = 6.dp + state.railInset(ToolbarDock.Top)))
            // same visibility rule the toolbar had inside the dock: hidden while Transform / bone-building owns the dock
            if (!state.transformOpen && state.rigPosedView) TrackToolbar(state)
            FloatingToolRail(state)
        }

        Dock(
            state,
            Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { dockH = with(density) { it.height.toDp() } },
        )

        if (state.propsOpen) PropsSheet(state)
        if (state.exportOpen) ExportSheet(state)
    }
}

@Composable
private fun TopBar(state: EditorState, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = cs.surfaceContainer.copy(alpha = UI_ALPHA),
            modifier = Modifier.clickable {
                state.fps = when (state.fps) { 12 -> 24; 24 -> 30; else -> 12 }
            },
        ) {
            Text(
                "${state.frame + 1} / ${state.frameCount}   ${state.fps} fps",
                Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.weight(1f))
        FilledTonalIconButton(
            onClick = { state.exportOpen = true },
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = cs.secondaryContainer.copy(alpha = UI_ALPHA),
            ),
            modifier = Modifier.size(36.dp).semantics { contentDescription = "Export MP4" },
        ) {
            FoxIcon(Ico.Export, iconSize = 20.dp)
        }
        Spacer(Modifier.width(6.dp))
        FilledTonalButton(
            onClick = { state.propsOpen = true },
            modifier = Modifier.height(36.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = cs.secondaryContainer.copy(alpha = UI_ALPHA),
            ),
        ) {
            FoxIcon(Ico.Props, iconSize = 18.dp)
            Spacer(Modifier.width(6.dp))
            Text("Properties")
        }
    }
}

/**
 * Full-bleed canvas. It fills the whole screen and sits *behind* the translucent editor chrome, so
 * the paper can be panned / zoomed / rotated underneath the UI. [pad] (top bar + dock) is only used
 * to fit the paper into the area the chrome leaves free.
 */
@Composable
fun EditorSurface(state: EditorState, pad: PaddingValues) {
    val cs = MaterialTheme.colorScheme
    val bg = cs.surfaceContainerLowest
    val ca = cs.surfaceContainerHigh
    val cb = cs.surfaceContainerHighest
    val vp = state.viewport
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current

    // Plain list (not snapshot state): points go straight to the GL thread, nothing recomposes per point.
    val live = remember { ArrayList<Offset>() }

    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val topPx = with(density) { pad.calculateTopPadding().toPx() }
    val bottomPx = with(density) { pad.calculateBottomPadding().toPx() }
    LaunchedEffect(viewSize, topPx, bottomPx) { vp.onLayout(viewSize, topPx, bottomPx) }

    // tick when the rotation clicks onto a snap stop
    var wasSnapped by remember { mutableStateOf(vp.snapped) }
    LaunchedEffect(vp.snapped) {
        if (vp.snapped && !wasSnapped) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        wasSnapped = vp.snapped
    }

    // pan / zoom / rotate -> GL uniforms, synchronously, no recomposition involved
    DisposableEffect(vp) {
        vp.onChanged = { NativeCanvas.setView(vp.scale, vp.rotation, vp.offsetX, vp.offsetY) }
        vp.onChanged?.invoke()
        onDispose { vp.onChanged = null }
    }

    LaunchedEffect(bg, ca, cb, density.density) {
        NativeCanvas.setTheme(bg.toArgb(), ca.toArgb(), cb.toArgb(), density.density)
    }

    // Strokes of the drawings under the playhead (one per timeline row) + row/layer order -> native.
    // Native only knows "one cel, N layers", so every (row, layer) pair is its own native layer.
    LaunchedEffect(state) {
        snapshotFlow { state.structureKey() }.distinctUntilChanged().collect { state.rebuildNative() }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.shownKey() }.distinctUntilChanged().collect { state.syncShown() }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.xfKey() }.distinctUntilChanged().collect { state.syncXf() }
    }
    // bones / curves / keys / mesh / paint -> native (canvas deforms live, exporter gets the same data); rest view while building
    LaunchedEffect(state) {
        snapshotFlow { state.rigKey() to state.rigPosedView }.distinctUntilChanged().collect { state.syncRig() }
    }
    // playhead -> native, which evaluates every row's keyframe curve (easing included) on the GL thread
    LaunchedEffect(state) {
        snapshotFlow { state.frame }.distinctUntilChanged().collect { state.syncTime() }
    }

    val listener = remember(state) {
        object : CanvasGestureListener {
            private var strokeCel = -1
            private var strokeTrack = 0
            private var strokeXf = LayerXf()
            override fun canDraw() = state.tool == Tool.Brush && state.brush.ready && state.canDrawHere && !state.transformOpen
            override fun onInkStart(p: Offset) {
                val cel = state.currentCel ?: return
                strokeCel = cel.id
                strokeTrack = cel.track
                // the row may be moved / rotated / resized: store the point where it belongs inside the layer
                strokeXf = state.trackXf(cel.track)
                val q = state.unwarp(cel.track, strokeXf.invert(state.unattach(cel.track, p)))
                live.clear(); live.add(q)
                NativeCanvas.beginStroke(
                    cel.id, state.nativeLayer(cel.track, state.activeLayer), state.color.toArgb(),
                    state.brushSize, state.brush == BrushKind.Eraser,
                )
                NativeCanvas.strokePoint(q.x, q.y)
            }
            override fun onInkMove(p: Offset) { val q = state.unwarp(strokeTrack, strokeXf.invert(state.unattach(strokeTrack, p))); live.add(q); NativeCanvas.strokePoint(q.x, q.y) }
            override fun onInkEnd() {
                if (live.isNotEmpty() && strokeCel >= 0) {
                    state.commit(
                        InkStroke(
                            strokeCel, state.activeLayer, state.color, state.brushSize,
                            live.toList(), state.brush == BrushKind.Eraser,
                        )
                    )
                }
                live.clear()
                strokeCel = -1
            }
            override fun onInkCancel() { live.clear(); NativeCanvas.cancelStroke() }
            override fun onUndo() = state.undo()
            override fun onRedo() = state.redo()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it }
    ) {
        NativeCanvasSurface(Modifier.fillMaxSize())
        // transparent gesture layer above the GL view
        Box(Modifier.fillMaxSize().systemGestureExclusion().canvasGestures(vp, listener))
        // move / rotate / resize handles; sits above the gesture layer, so it takes the touches while open
        if (state.transformOpen) TransformOverlay(state, viewSize)
        // bones / deform curves: build, pose and weight-paint gestures
        if (state.rigOpen) RigOverlay(state, viewSize)
    }
}

/** Shows angle + zoom once the view has been moved; tap to fit the paper back into the free area. */
@Composable
private fun ViewChip(state: EditorState, modifier: Modifier) {
    val vp = state.viewport
    AnimatedVisibility(!vp.autoFit, modifier, enter = fadeIn(), exit = fadeOut()) {
        Surface(
            onClick = { vp.fit() },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = UI_ALPHA),
        ) {
            Text(
                "${vp.rotation.roundToInt()}\u00b0  \u00b7  ${(vp.scale * 100).roundToInt()}%   Fit",
                Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

private fun DrawScope.checker(tile: Float, a: Color, b: Color) {
    val cols = (size.width / tile).toInt() + 1
    val rows = (size.height / tile).toInt() + 1
    for (r in 0 until rows) for (c in 0 until cols) {
        drawRect(if ((r + c) % 2 == 0) a else b, Offset(c * tile, r * tile), Size(tile, tile))
    }
}

private fun DrawScope.ink(st: InkStroke, w: Float) {
    val pts = st.pts
    if (pts.isEmpty()) return
    val k = w / 512f
    val blend = if (st.erase) BlendMode.Clear else BlendMode.SrcOver
    if (pts.size == 1) {
        drawCircle(st.color, st.size * k / 2f, Offset(pts[0].x * w, pts[0].y * w), blendMode = blend)
        return
    }
    val p = Path().apply {
        moveTo(pts[0].x * w, pts[0].y * w)
        for (i in 1 until pts.size) lineTo(pts[i].x * w, pts[i].y * w)
    }
    drawPath(p, st.color, style = DrawStroke(st.size * k, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = blend)
}

/**
 * Composites one cel bottom->top. Each layer gets its own saveLayer so eraser strokes
 * (BlendMode.Clear) only cut through their own layer, and undo/redo stay plain list ops.
 */
internal fun DrawScope.paintCel(state: EditorState, cel: Int, w: Float, live: InkStroke? = null) {
    val bounds = Rect(0f, 0f, size.width, size.height)
    val row = state.cels.firstOrNull { it.id == cel }?.track
    for (l in (if (row != null) state.layersOf(row) else emptyList())) {
        if (!l.visible) continue
        val mine = state.strokes.filter { it.cel == cel && it.layer == l.id }
        val liveHere = live?.takeIf { it.layer == l.id }
        if (mine.isEmpty() && liveHere == null) continue
        drawIntoCanvas { c ->
            c.saveLayer(bounds, Paint())
            for (st in mine) ink(st, w)
            if (liveHere != null) ink(liveHere, w)
            c.restore()
        }
    }
}

/** Composites every timeline row bottom -> top at frame [f]. Use this for export so it matches the canvas. */
internal fun DrawScope.paintFrame(state: EditorState, f: Int, w: Float) {
    for (t in state.drawTracks) state.celAt(f, t.id)?.let { paintCel(state, it.id, w) }
}

@Composable
internal fun ToolBtn(kind: Ico, label: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) cs.primaryContainer else Color.Transparent, label = "toolBg")
    val fg = if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { FoxIcon(kind, tint = fg) }
}

@Composable
internal fun ColorSizeBtn(state: EditorState) {
    val cs = MaterialTheme.colorScheme
    val sel = state.panel == Panel.Color
    val bg by animateColorAsState(if (sel) cs.primaryContainer else Color.Transparent, label = "csBg")
    val dot by animateDpAsState((6 + state.brushSize / 64f * 22f).dp, label = "dot")
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable { state.panel = if (sel) Panel.None else Panel.Color }
            .semantics { contentDescription = "Brush color and size" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(dot)
                .clip(CircleShape)
                .background(state.color)
                .border(1.5.dp, cs.outline, CircleShape)
        )
    }
}

@Composable
internal fun BrushPanel(state: EditorState) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = cs.surfaceContainerHigh.copy(alpha = UI_ALPHA),
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.width(196.dp).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Brush", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.titleSmall)
            BrushKind.values().toList().chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { b ->
                        BrushChip(b, b == state.brush, Modifier.weight(1f)) {
                            state.brush = b
                            state.panel = Panel.None
                        }
                    }
                }
            }
            LabeledSlider("Size", state.brushSize, 1f..64f, state.color) { state.brushSize = it }
        }
    }
}

@Composable
private fun BrushChip(b: BrushKind, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg = if (selected) cs.primaryContainer else cs.surfaceContainerHighest
    val fg = if (selected) cs.onPrimaryContainer else cs.onSurface
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .alpha(if (b.ready) 1f else 0.55f)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FoxIcon(b.ico, tint = fg)
        Text(b.label, fontSize = 12.sp, color = fg)
        if (!b.ready) Text("WIP", fontSize = 9.sp, color = cs.tertiary)
    }
}

private val swatches = listOf(
    0xFF212121, 0xFFFFFFFF, 0xFFEF5350, 0xFFFF7043,
    0xFFFFCA28, 0xFF66BB6A, 0xFF29B6F6, 0xFFAB47BC,
).map { Color(it) }

@Composable
internal fun ColorPanel(state: EditorState) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = cs.surfaceContainerHigh.copy(alpha = UI_ALPHA),
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.width(220.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(14.dp)).background(cs.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size((state.brushSize / 64f * 36f + 4f).dp).clip(CircleShape).background(state.color))
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                swatches.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        row.forEach { c ->
                            Box(
                                Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(c)
                                    .border(1.dp, cs.outlineVariant, CircleShape)
                                    .clickable { state.setColor(c) }
                            )
                        }
                    }
                }
            }
            LabeledSlider("Hue", state.hue, 0f..360f, state.color) { state.hue = it }
            LabeledSlider("Shade", state.bri, 0.1f..1f, state.color) { state.bri = it }
            LabeledSlider("Size", state.brushSize, 1f..64f, state.color) { state.brushSize = it }
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, tint: Color, onChange: (Float) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = tint, activeTrackColor = tint),
            modifier = Modifier.height(28.dp),
        )
    }
}

@Composable
internal fun LayersPanel(state: EditorState) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = cs.surfaceContainerHigh.copy(alpha = UI_ALPHA),
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.width(196.dp).padding(vertical = 6.dp)) {
            Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Layers \u00B7 ${state.activeTrackObj?.name ?: ""}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                FilledTonalIconButton(onClick = { state.addLayer() }, modifier = Modifier.size(36.dp)) {
                    FoxIcon(Ico.Plus, iconSize = 18.dp)
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
                val cp = PaddingValues(horizontal = 8.dp)
                TextButton(onClick = { state.copyLayerArt() }, contentPadding = cp) { Text("Copy") }
                TextButton(onClick = { state.pasteLayerArt() }, enabled = state.clipboard is Clipboard.LayerArt, contentPadding = cp) { Text("Paste") }
                TextButton(onClick = { state.duplicateLayer() }, contentPadding = cp) { Text("Duplicate") }
                TextButton(onClick = { state.clearLayerArt() }, contentPadding = cp) { Text("Clear") }
                TextButton(onClick = { state.moveLayer(1) }, contentPadding = cp) { Text("Up") }
                TextButton(onClick = { state.moveLayer(-1) }, contentPadding = cp) { Text("Down") }
                TextButton(onClick = { state.deleteLayer() }, enabled = state.layers.size > 1, contentPadding = cp) { Text("Delete") }
            }
            Spacer(Modifier.height(2.dp))
            LazyColumn(Modifier.heightIn(max = 240.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                items(state.layers.asReversed(), key = { it.id }) { l ->
                    val sel = l.id == state.activeLayer
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (sel) cs.primaryContainer else Color.Transparent)
                            .clickable { state.activeLayer = l.id }
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .clickable { state.toggleLayerVisible(l.id) },
                            contentAlignment = Alignment.Center,
                        ) {
                            FoxIcon(
                                if (l.visible) Ico.Eye else Ico.EyeOff,
                                tint = if (sel) cs.onPrimaryContainer else cs.onSurfaceVariant,
                                iconSize = 20.dp,
                            )
                        }
                        Text(
                            l.name,
                            Modifier.padding(start = 6.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (sel) cs.onPrimaryContainer else cs.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Dock(state: EditorState, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        modifier,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = cs.surfaceContainer.copy(alpha = UI_ALPHA),
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Column {
        // Transform popup takes the place of the player controls + timeline
        AnimatedVisibility(
            state.transformOpen,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) { TransformPanel(state) }
        AnimatedVisibility(
            !state.transformOpen,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
        Column(Modifier.padding(top = 6.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // bone / deform editor sits above the transport; the timeline below stays usable for scrubbing + key marks
            AnimatedVisibility(state.rigOpen, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) { RigPanel(state) }
            // while bones / curves are being BUILT or weights painted the transport + timeline fold away (the rig panel has undo / redo)
            AnimatedVisibility(state.rigPosedView, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(onClick = { state.undo() }, enabled = state.canUndo, modifier = Modifier.size(38.dp)) {
                    FoxIcon(Ico.Undo)
                }
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.fillMaxWidth(0.62f),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TransportBtn(Ico.ToStart, "Go to start") { state.seek(0) }
                    TransportBtn(Ico.Prev, "Previous frame") { state.step(-1) }
                    FilledIconButton(onClick = { state.playing = !state.playing }, modifier = Modifier.size(44.dp)) {
                        FoxIcon(if (state.playing) Ico.Pause else Ico.Play, iconSize = 24.dp)
                    }
                    TransportBtn(Ico.Next, "Next frame") { state.step(1) }
                    TransportBtn(Ico.ToEnd, "Go to end") { state.seek(state.frameCount - 1) }
                }
                Spacer(Modifier.weight(1f))
                FilledTonalIconButton(onClick = { state.redo() }, enabled = state.canRedo, modifier = Modifier.size(38.dp)) {
                    FoxIcon(Ico.Redo)
                }
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(38.dp)) { FoxIcon(Ico.More) }
                    EditorMenu(state, menuOpen) { menuOpen = false }
                }
            }

            TimelineEditor(state, Modifier.padding(horizontal = 4.dp))
            }
            }
        }
        }
        }
    }
}

/** The ••• menu. Entries marked WIP are visible but disabled until their feature lands. */
@Composable
private fun EditorMenu(state: EditorState, open: Boolean, onDismiss: () -> Unit) {
    val tl = state.timeline
    val cs = MaterialTheme.colorScheme
    val playMs = state.frameToMs(state.frame)
    val canEdit = !state.selectionLocked
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        @Composable
        fun Section(title: String) =
            Text(
                title,
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = cs.primary,
            )

        @Composable
        fun Item(label: String, enabled: Boolean = true, wip: Boolean = false, action: () -> Unit = {}) =
            DropdownMenuItem(
                text = { Text(label) },
                enabled = enabled && !wip,
                trailingIcon = if (wip) ({ Text("WIP", style = MaterialTheme.typography.labelSmall) }) else null,
                onClick = { onDismiss(); action() },
            )

        Section("TIMELINE")
        Item("Add Frame", !state.activeLocked) { state.edit { state.addCel() } }
        Item("Add Drawing Layer") { state.addDrawTrack() }
        Item("Transform + Keyframes") { state.openTransform() }   // tap a \u25C6 icon next to any slider to key it
        Item("Duplicate Drawing Layer") { state.duplicateDrawTrack() }
        Item("Delete Drawing Layer", state.drawTracks.size > 1 && !state.activeLocked) { state.deleteDrawTrack() }
        Item("Add Audio Layer") { state.edit { tl.addTrack() } }
        Item("Import Audio\u2026", !tl.loading) { state.audioPickRequested = true }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Section("EDITING")
        Item("Copy") { state.copySelection() }
        Item("Cut", canEdit) { state.cutSelection() }
        Item("Paste", state.clipboard != null) { state.paste(playMs) }
        Item("Duplicate", canEdit) { state.duplicateSelection() }
        Item("Split at playhead", canEdit) { state.splitSelection(playMs) }
        Item("Delete", canEdit) { state.deleteSelection() }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Section("COMING SOON")
        Item("Bone Construction", wip = true)
        Item("Graph Editor", wip = true)
        Item("Onion Skin", wip = true)
        Item("Export Video", wip = true)
    }
}

@Composable
private fun TransportBtn(kind: Ico, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { FoxIcon(kind, tint = MaterialTheme.colorScheme.onSurface) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PropsSheet(state: EditorState) {
    val name = state.layers.firstOrNull { it.id == state.activeLayer }?.name ?: "Layer"
    ModalBottomSheet(onDismissRequest = { state.propsOpen = false }) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            PropRow("X", state.posX, -256f..256f) { state.posX = it }
            PropRow("Y", state.posY, -256f..256f) { state.posY = it }
            PropRow("Rotate", state.rotation, -180f..180f) { state.rotation = it }
            PropRow("Scale", state.scale, 0.1f..4f) { state.scale = it }
            PropRow("Opacity", state.opacity, 0f..1f) { state.opacity = it }
        }
    }
}

@Composable
private fun PropRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(64.dp), style = MaterialTheme.typography.bodyMedium)
        Slider(value, onChange, Modifier.weight(1f), valueRange = range)
        Text(
            "%.1f".format(value),
            Modifier.width(48.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

enum class Ico {
    Brush, Deform, Bone, Layers, Chevron, Undo, Redo, ToStart, ToEnd, Prev, Next,
    Play, Pause, Props, Plus, Eye, EyeOff, Eraser, Smudge, Blur,
    Lock, Unlock, Volume, VolumeOff, Music, Export, More, Transform,
}

@Composable
fun FoxIcon(
    kind: Ico,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    iconSize: Dp = 24.dp,
) {
    val mirror = kind == Ico.Redo || kind == Ico.Next || kind == Ico.ToEnd
    Canvas(
        modifier
            .size(iconSize)
            .graphicsLayer { scaleX = if (mirror) -1f else 1f }
    ) {
        val s = size.width / 24f
        val line = DrawStroke(2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val thin = DrawStroke(1.4f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun o(x: Float, y: Float) = Offset(x * s, y * s)
        fun Path.m(x: Float, y: Float) = moveTo(x * s, y * s)
        fun Path.l(x: Float, y: Float) = lineTo(x * s, y * s)
        fun Path.q(a: Float, b: Float, x: Float, y: Float) = quadraticTo(a * s, b * s, x * s, y * s)
        fun Path.c(a: Float, b: Float, c: Float, d: Float, x: Float, y: Float) =
            cubicTo(a * s, b * s, c * s, d * s, x * s, y * s)

        when (kind) {
            Ico.Brush -> {
                drawLine(tint, o(9f, 15f), o(18f, 6f), 4f * s, StrokeCap.Round)
                drawCircle(tint, 3.2f * s, o(6.5f, 17.5f))
            }
            Ico.Deform -> {
                for (y in listOf(6f, 12f, 18f)) {
                    drawPath(Path().apply { m(3f, y); q(8f, y - 4f, 12f, y); q(16f, y + 4f, 21f, y) }, tint, style = thin)
                }
                for (x in listOf(6f, 12f, 18f)) {
                    drawPath(Path().apply { m(x, 3f); q(x - 4f, 8f, x, 12f); q(x + 4f, 16f, x, 21f) }, tint, style = thin)
                }
            }
            Ico.Transform -> {
                drawRect(tint, o(6f, 6f), androidx.compose.ui.geometry.Size(12f * s, 12f * s), style = line)
                for ((x, y) in listOf(6f to 6f, 18f to 6f, 6f to 18f, 18f to 18f)) drawCircle(tint, 2.2f * s, o(x, y))
                drawLine(tint, o(12f, 6f), o(12f, 2.5f), 1.6f * s, StrokeCap.Round)
                drawCircle(tint, 1.6f * s, o(12f, 2.5f))
            }
            Ico.Bone -> {
                drawLine(tint, o(8.2f, 15.8f), o(15.8f, 8.2f), 2f * s, StrokeCap.Round)
                drawCircle(tint, 3f * s, o(6f, 18f), style = line)
                drawCircle(tint, 3f * s, o(18f, 6f), style = line)
            }
            Ico.Layers -> {
                drawPath(Path().apply { m(12f, 3f); l(21f, 7.5f); l(12f, 12f); l(3f, 7.5f); close() }, tint, style = line)
                drawPath(Path().apply { m(3f, 12.5f); l(12f, 17f); l(21f, 12.5f) }, tint, style = line)
                drawPath(Path().apply { m(3f, 16.5f); l(12f, 21f); l(21f, 16.5f) }, tint, style = line)
            }
            Ico.Chevron -> drawPath(Path().apply { m(9f, 5f); l(16f, 12f); l(9f, 19f) }, tint, style = line)
            Ico.Undo, Ico.Redo -> {
                drawPath(Path().apply { m(9f, 5f); l(4f, 10f); l(9f, 15f) }, tint, style = line)
                drawPath(
                    Path().apply {
                        m(4f, 10f); l(14f, 10f)
                        c(18.5f, 10f, 20f, 13f, 20f, 15.5f)
                        c(20f, 18f, 18f, 20f, 14.5f, 20f)
                    },
                    tint, style = line,
                )
            }
            Ico.ToStart, Ico.ToEnd -> {
                drawLine(tint, o(5.5f, 5f), o(5.5f, 19f), 2.4f * s, StrokeCap.Round)
                drawPath(Path().apply { m(19f, 5f); l(9f, 12f); l(19f, 19f); close() }, tint)
            }
            Ico.Prev, Ico.Next -> drawPath(Path().apply { m(17f, 5f); l(7f, 12f); l(17f, 19f); close() }, tint)
            Ico.Play -> drawPath(Path().apply { m(7f, 4f); l(20f, 12f); l(7f, 20f); close() }, tint)
            Ico.Pause -> {
                drawRoundRect(tint, o(6f, 4f), Size(4f * s, 16f * s), CornerRadius(1f * s))
                drawRoundRect(tint, o(14f, 4f), Size(4f * s, 16f * s), CornerRadius(1f * s))
            }
            Ico.Props -> {
                for ((y, x) in listOf(6f to 8f, 12f to 16f, 18f to 10f)) {
                    drawLine(tint, o(3f, y), o(21f, y), 1.8f * s, StrokeCap.Round)
                    drawCircle(tint, 2.8f * s, o(x, y))
                }
            }
            Ico.Plus -> {
                drawLine(tint, o(12f, 5f), o(12f, 19f), 2f * s, StrokeCap.Round)
                drawLine(tint, o(5f, 12f), o(19f, 12f), 2f * s, StrokeCap.Round)
            }
            Ico.Eraser -> {
                drawPath(Path().apply { m(4f, 14f); l(12f, 6f); l(18f, 12f); l(10f, 20f); close() }, tint, style = line)
                drawLine(tint, o(7.2f, 10.8f), o(13.2f, 16.8f), 1.4f * s, StrokeCap.Round)
                drawLine(tint, o(12f, 21f), o(21f, 21f), 2f * s, StrokeCap.Round)
            }
            Ico.Smudge -> {
                drawCircle(tint, 4f * s, o(16f, 8f), style = line)
                drawLine(tint, o(4f, 14f), o(15f, 14f), 2f * s, StrokeCap.Round)
                drawLine(tint, o(4f, 19f), o(11f, 19f), 2f * s, StrokeCap.Round)
            }
            Ico.Blur -> {
                drawCircle(tint, 3f * s, o(12f, 12f))
                drawCircle(tint, 6.5f * s, o(12f, 12f), style = thin)
                drawCircle(tint, 9.5f * s, o(12f, 12f), style = thin)
            }
            Ico.Lock, Ico.Unlock -> {
                drawRoundRect(tint, o(6f, 11f), Size(12f * s, 9f * s), CornerRadius(2f * s), style = line)
                drawPath(
                    Path().apply {
                        if (kind == Ico.Lock) { m(8.5f, 11f); l(8.5f, 8f); c(8.5f, 3.5f, 15.5f, 3.5f, 15.5f, 8f); l(15.5f, 11f) }
                        else { m(8.5f, 11f); l(8.5f, 8f); c(8.5f, 3.5f, 15.5f, 3.5f, 15.5f, 7f) }
                    },
                    tint, style = line,
                )
            }
            Ico.Volume, Ico.VolumeOff -> {
                drawPath(Path().apply { m(4f, 9f); l(8f, 9f); l(13f, 5f); l(13f, 19f); l(8f, 15f); l(4f, 15f); close() }, tint)
                if (kind == Ico.Volume) {
                    drawPath(Path().apply { m(16f, 9f); q(18.5f, 12f, 16f, 15f) }, tint, style = line)
                    drawPath(Path().apply { m(18.5f, 6f); q(23f, 12f, 18.5f, 18f) }, tint, style = thin)
                } else {
                    drawLine(tint, o(16f, 9f), o(21f, 15f), 2f * s, StrokeCap.Round)
                    drawLine(tint, o(21f, 9f), o(16f, 15f), 2f * s, StrokeCap.Round)
                }
            }
            Ico.Music -> {
                drawLine(tint, o(15f, 5f), o(15f, 16f), 2f * s, StrokeCap.Round)
                drawCircle(tint, 3f * s, o(12f, 17f))
                drawPath(Path().apply { m(15f, 5f); q(19f, 6f, 19f, 10f) }, tint, style = line)
            }
            Ico.Export -> {
                drawPath(Path().apply { m(12f, 15f); l(12f, 4f) }, tint, style = line)
                drawPath(Path().apply { m(7.5f, 8.5f); l(12f, 4f); l(16.5f, 8.5f) }, tint, style = line)
                drawPath(Path().apply { m(5f, 13f); l(5f, 19f); l(19f, 19f); l(19f, 13f) }, tint, style = line)
            }
            Ico.More -> for (x in listOf(5f, 12f, 19f)) drawCircle(tint, 2.1f * s, o(x, 12f))
            Ico.Eye, Ico.EyeOff -> {
                drawPath(
                    Path().apply { m(3f, 12f); c(6f, 6f, 18f, 6f, 21f, 12f); c(18f, 18f, 6f, 18f, 3f, 12f) },
                    tint, style = line,
                )
                drawCircle(tint, 3f * s, o(12f, 12f))
                if (kind == Ico.EyeOff) drawLine(tint, o(4f, 4f), o(20f, 20f), 2f * s, StrokeCap.Round)
            }
        }
    }
}
