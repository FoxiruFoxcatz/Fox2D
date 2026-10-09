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
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateList
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
import fox.foxiru.foxcat.fox2d.jnicallers.NativeProject
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

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope

enum class Tool { Brush, Deform, Bone, Warp }
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
data class Cel(
    val id: Int, val len: Int = 4, val track: Int = 0,
    /**
     * LEGACY, always -1 now. Loop / Freeze used to GENERATE copies of drawings (gen = source drawing id, -2 = blank hold);
     * a group's Re-timing ([TrackGroup.fill]) is now a time mapping ([retimeFrame]) and creates no cels at all. The field is
     * kept so code that still reads it (project IO, ...) keeps compiling.
     */
    val gen: Int = -1,
)

/** The drawing whose strokes this cel shows (a real drawing always shows itself). */
fun Cel.shownId(): Int = if (gen >= 0) gen else id

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
    /** Id of the [TrackGroup] this row sits in, -1 = top level. Drawing members of one group are always adjacent in the stack. */
    val group: Int = -1,
    /** Whole-row blend mode (index into [FxBlend.names]), opacity and clipping mask (visible only where the row below has pixels). */
    val blend: Int = 0,
    val opacity: Float = 1f,
    val clip: Boolean = false,
) {
    // PERF: derived key data is shared by every copy() that keeps the SAME key lists. Dragging a row / group changes only
    // [offset], and a `by lazy` would be rebuilt (pack + sort + distinct over every key) for every pointer move.
    private val derived: KeyDerived get() = KeyDerivedCache.get(keys, rig.keys)

    /** Flat key array for native. */
    val packedKeys: FloatArray get() = derived.packed
    /** Every frame that carries a key on ANY property (the marks on the timeline). */
    val keyFrames: List<Int> by lazy(LazyThreadSafetyMode.NONE) { keys.map { it.frame }.distinct().sorted() }
    /** Transform keys + rig keys. Channel numbers of the two kinds overlap, so use this for frames / easing only. */
    val allKeys: List<ChanKey> get() = derived.all
    /** Every frame that carries a key of ANY kind (timeline marks, ruler, prev / next key). Sorted. */
    val markFrames: List<Int> get() = derived.marks
    /** Parallel to [markFrames]: true when every key on that frame is Hold (the mark is drawn square). */
    val markHold: BooleanArray get() = derived.markHold
    fun lane(c: Chan): List<ChanKey> = keys.filter { it.chan == c.id }
    fun baseArray(): FloatArray = FloatArray(7).also { xf.writeTo(it, 0) }
}

/** Everything derived from a row's two key lists (see [DrawTrack.derived]). */
class KeyDerived(val packed: FloatArray, val all: List<ChanKey>, val marks: List<Int>, val markHold: BooleanArray)

/** Identity cache: the same (keys, rig keys) list objects always give the same [KeyDerived]. Bounded; a miss only costs a rebuild. */
private object KeyDerivedCache {
    private class E(val keys: List<ChanKey>, val rig: List<ChanKey>, val d: KeyDerived)
    private val map = object : LinkedHashMap<Int, E>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, E>) = size > 1024
    }

    @Synchronized
    fun get(keys: List<ChanKey>, rig: List<ChanKey>): KeyDerived {
        val h = 31 * System.identityHashCode(keys) + System.identityHashCode(rig)
        val e = map[h]
        if (e != null && e.keys === keys && e.rig === rig) return e.d
        val all = if (rig.isEmpty()) keys else keys + rig
        val frames = java.util.TreeMap<Int, Boolean>()          // frame -> every key there is Hold
        for (k in all) frames[k.frame] = (frames[k.frame] ?: true) && k.ease.isHold
        val marks = ArrayList<Int>(frames.keys)
        val hold = BooleanArray(marks.size) { frames[marks[it]] == true }
        val d = KeyDerived(keys.pack(), all, marks, hold)
        map[h] = E(keys, rig, d)
        return d
    }
}

data class InkStroke(
    val cel: Int, val layer: Int, val color: Color, val size: Float, val pts: List<Offset>,
    val erase: Boolean = false,
    val image: ImagePlace? = null,        // non-null = imported picture (pts empty)
)

/** blend: index into [FxBlend.names]; opacity 0..1; clip = only visible where the layer / row below it has pixels. */
data class Layer(val id: Int, val name: String, val visible: Boolean = true, val blend: Int = 0, val opacity: Float = 1f, val clip: Boolean = false)

/** Order matches fox_blend.h Mode. */
object FxBlend {
    val names = listOf(
        "Normal", "Multiply", "Screen", "Overlay", "Darken", "Lighten", "Add", "Color Dodge", "Color Burn",
        "Soft Light", "Hard Light", "Difference", "Exclusion",
    )
    fun name(i: Int): String = names.getOrElse(i) { names[0] }
}

/** What the Blend dialog edits: a whole drawing row, one drawing layer (Body / Head ...) of a row, or a whole group folder. */
sealed interface FxTarget {
    data class Row(val id: Int) : FxTarget
    data class Lay(val id: Int) : FxTarget
    data class Grp(val id: Int) : FxTarget
}

/**
 * A folder of tracks (like a group layer in Alight Motion). Drawing rows and audio layers join it through their
 * own `group` field. A group has no transform of its own, so the canvas and the export are not touched by it:
 * it only organises the timeline (open / close, rename, lock, move all members in time, reorder as one block).
 */
data class TrackGroup(
    val id: Int,
    val name: String,
    /**
     * End of the group bar, in timeline frames (-1 = not stretched). Drag the right edge of the selected bar to set it;
     * the Re-timing mode decides what the time between the members' content and this end shows.
     */
    val endFrame: Int = -1,
    /**
     * Re-timing mode ([FILL_NONE] = Off, [FILL_FREEZE], [FILL_STRETCH], [FILL_LOOP], [FILL_LOOP_STRETCH], [FILL_BLANK]).
     * (Named `fill` because that is what project files already store; the values 0..2 keep their old meaning.)
     * It is only a MAPPING from timeline time to source time, see [retimeFrame]: it never creates drawings.
     */
    val fill: Int = 0,
    /** Blend mode (index into [FxBlend.names]), opacity and clipping of the whole folder: its DRAWING rows are composited as one picture (audio members draw nothing). */
    val blend: Int = 0,
    val opacity: Float = 1f,
    val clip: Boolean = false,
)

// Re-timing modes. 0..2 are the values older projects already store.
const val FILL_NONE = 0          // Off: content plays once, nothing is re-timed
const val FILL_LOOP = 1          // repeat the content up to the bar end
const val FILL_FREEZE = 2        // hold every row's last drawing up to the bar end
const val FILL_STRETCH = 3       // scale the content's speed so it fills the bar
const val FILL_LOOP_STRETCH = 4  // loop a whole number of times, each repeat stretched a little so the last one ends exactly on the bar end
const val FILL_BLANK = 5         // content plays once, then nothing up to the bar end

/** Order and names of the Re-timing dropdown (same as Alight Motion). */
val RETIME_MODES = intArrayOf(FILL_NONE, FILL_FREEZE, FILL_STRETCH, FILL_LOOP, FILL_LOOP_STRETCH, FILL_BLANK)

fun retimeName(mode: Int): String = when (mode) {
    FILL_FREEZE -> "Freeze"
    FILL_STRETCH -> "Stretch"
    FILL_LOOP -> "Loop"
    FILL_LOOP_STRETCH -> "Loop & Stretch"
    FILL_BLANK -> "Blank"
    else -> "Off"
}

/** [retimeFrame] result when nothing is shown. */
const val RETIME_BLANK = -1

/**
 * Re-timing as pure arithmetic: which frame of the group's SOURCE [a, b) is shown at timeline frame [f], for a group
 * whose bar runs a..[end]. O(1), nothing is generated, so a loop an hour long costs exactly what one repeat costs.
 * Returns [RETIME_BLANK] when nothing is shown. Frames before [a] and mode Off return [f] unchanged.
 */
fun retimeFrame(mode: Int, a: Int, b: Int, end: Int, f: Int): Int {
    if (mode == FILL_NONE || f < a) return f
    val p = (b - a).toLong()
    if (p <= 0L) return f
    val e = maxOf(end, b)
    if (f >= e) return RETIME_BLANK
    val l = (e - a).toLong()          // bar length
    val x = (f - a).toLong()          // time into the bar
    return when (mode) {
        FILL_BLANK -> if (f < b) f else RETIME_BLANK
        FILL_FREEZE -> if (f < b) f else b - 1
        FILL_STRETCH -> a + (x * p / l).toInt()
        FILL_LOOP -> a + (x % p).toInt()
        FILL_LOOP_STRETCH -> {
            val n = maxOf(1L, (l + p / 2) / p)               // whole repeats that fit the bar
            a + ((x * n % l) * p / l).toInt()                // position inside the repeat, scaled back to source length
        }
        else -> f
    }
}

/** Upper bound for audio repeat copies of one group (sound is the only thing a Loop still copies). */
private const val MAX_LOOP_CLIPS = 4096

/** One row's unrolled Loop above this many keys is not baked for native (see EditorState.liveRows): it falls back to the offset shift. */
private const val BAKE_MAX_KEYS = 4096

/** What the rename dialog is editing. */
sealed interface RenameTarget {
    data class Draw(val id: Int) : RenameTarget
    data class Audio(val id: Int) : RenameTarget
    data class Group(val id: Int) : RenameTarget
}

/** Key a whole group gets while the timeline reorders top-level items (track ids are >= 0, -1 means "none"). */
internal fun groupKey(group: Int): Int = -2 - group

/** Start times of a group's unlocked members, taken when a drag begins (see EditorState.shiftGroup). */
class GroupShift(val draw: Map<Int, Int>, val clips: Map<Int, Long>)

/**
 * One pass + two snapshot writes. SnapshotStateList.removeAll { } compacts in place: every element behind the first hit is
 * moved with its own state write, which is what made a big loop crawl while its end was dragged.
 */
private inline fun <T> SnapshotStateList<T>.removeWhere(pred: (T) -> Boolean): Boolean {
    var any = false
    val kept = ArrayList<T>(size)
    for (x in this) if (pred(x)) any = true else kept.add(x)
    if (!any) return false
    Snapshot.withMutableSnapshot { clear(); addAll(kept) }
    return true
}

/** Immutable copy of everything the timeline edits touch: audio clips/tracks and the drawing (cel) sequence. */
data class EditSnap(
    val clips: List<AudioClip>,
    val tracks: List<AudioTrack>,
    val cels: List<Cel>,
    val drawTracks: List<DrawTrack>,
    val strokes: List<InkStroke>,
    val selected: Int,
    val groups: List<TrackGroup> = emptyList(),
) {
    /** Selection alone is not an edit. */
    fun sameAs(o: EditSnap) =
        clips == o.clips && tracks == o.tracks && cels == o.cels && drawTracks == o.drawTracks && strokes == o.strokes &&
            groups == o.groups
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

    /** Id of the project this state currently holds (set by applyProject); null = none opened yet. */
    var projectId by mutableStateOf<String?>(null)

    var playing by mutableStateOf(false)
    var frame by mutableIntStateOf(0)
    var fps by mutableIntStateOf(12)

    // Timeline = drawing rows (DrawTrack, bottom -> top), each an ordered run of cels held for `len` frames.
    // Trim a cel's edge to stretch/shrink its hold. The timeline is as long as its longest row.
    val drawTracks = mutableStateListOf(DrawTrack(0, "Drawing 1", layers = listOf(Layer(0, "Body"), Layer(1, "Head"), Layer(2, "Tail"))))
    var activeTrack by mutableIntStateOf(0)
    val cels = mutableStateListOf(Cel(0, 4), Cel(1, 4), Cel(2, 4))

    // PERF: lookups the timeline hits every frame. They are rebuilt ONCE per edit (derivedStateOf), not once per call,
    // so a looped group with thousands of generated cels / strokes costs O(1) per lookup instead of O(all cels).
    /** Cels of every row, in row order. */
    val celsByTrack: Map<Int, List<Cel>> by derivedStateOf { cels.groupBy { it.track } }
    /** Strokes of every drawing (cel id). */
    val strokesByCel: Map<Int, List<InkStroke>> by derivedStateOf { strokes.groupBy { it.cel } }
    /** Cheap change signature of each drawing's strokes: lets the timeline thumbnails know when to repaint. */
    val celStrokeSig: Map<Int, Int> by derivedStateOf {
        val m = HashMap<Int, Int>()
        for (s in strokes) m[s.cel] = 31 * (m[s.cel] ?: 17) + System.identityHashCode(s)
        m
    }

    /**
     * PERF: prefix sums of every row's cel lengths (rel[i] = row-local start frame of cel i, rel[size] = row length), built
     * ONCE per edit. A Loop / Freeze group holds thousands of generated cels; before this, celIndexAt / celStart / trackSpan /
     * frameCount each walked all of them on every call (seek, playback, hit-test, group geometry).
     */
    val celRel: Map<Int, IntArray> by derivedStateOf {
        val out = HashMap<Int, IntArray>()
        for ((t, row) in celsByTrack) {
            val rel = IntArray(row.size + 1)
            for (i in row.indices) rel[i + 1] = rel[i] + row[i].len
            out[t] = rel
        }
        out
    }

    /**
     * Content span of every group - first member start .. last member end, in frames - taken from the REAL drawings and the
     * real audio clips (a Loop's audio repeat copies are left out). Rebuilt once per edit: Re-timing asks it for every row
     * on every frame, so a lookup must be O(1).
     */
    private val groupSpans: Map<Int, Pair<Int, Int>> by derivedStateOf {
        val out = HashMap<Int, Pair<Int, Int>>()
        for (grp in groups) {
            var a = Int.MAX_VALUE
            var b = Int.MIN_VALUE
            for (t in drawTracks) if (t.group == grp.id) {
                val rel = celRel[t.id] ?: continue
                val len = rel[rel.size - 1]
                if (len <= 0) continue
                a = minOf(a, t.offset); b = maxOf(b, t.offset + len)
            }
            val audio = timeline.tracks.filter { it.group == grp.id }.mapTo(HashSet()) { it.id }
            if (audio.isNotEmpty()) for (c in timeline.clips) if (!c.gen && c.track in audio) {
                a = minOf(a, msToFrame(c.startMs)); b = maxOf(b, msToFrame(c.startMs + c.lenMs))
            }
            if (a <= b) out[grp.id] = a to b
        }
        out
    }

    val frameCount: Int get() {
        var best = 1
        for (t in drawTracks) best = maxOf(best, t.offset + trackSpan(t.id))
        for (g in groups) if (g.fill != FILL_NONE) best = maxOf(best, g.endFrame)   // Loop / Freeze / ... play on up to the bar end
        return best
    }

    /** Layers of the ACTIVE drawing row (every row has its own set). */
    val layers: List<Layer> get() = layersOf(activeTrack)
    fun layersOf(track: Int): List<Layer> = drawTracks.firstOrNull { it.id == track }?.layers ?: emptyList()
    /** Native keeps the same number of layer slots for every row: the largest layer count of any row. */
    val layerSlots: Int get() = drawTracks.maxOfOrNull { it.layers.size } ?: 0
    fun newLayerId(): Int = (drawTracks.maxOfOrNull { t -> t.layers.maxOfOrNull { it.id } ?: -1 } ?: -1) + 1
    
    /** Image being placed (not in history until applied), and the ••• / Layers menu -> picker request flag. */
    var placing by mutableStateOf<ImagePlacing?>(null)
    var imagePickRequested by mutableStateOf(false)
    val canImportImage: Boolean get() = canDrawHere && projectId != null

    fun applyPlacing() {
        val p = placing ?: return
        placing = null
        strokes.add(InkStroke(p.cel, p.layer, Color.Black, 0f, emptyList(), false, ImagePlace(p.name, p.cx, p.cy, p.w, p.h, p.rot)))
        pushOp(HistoryOp.Stroke)   // one undo step; native history mirrors it (Op::AddImage)
        NativeCanvas.addImage(p.cel, nativeLayer(p.track, p.layer), ImageStore.handleOf(p.name), p.cx, p.cy, p.w, p.h, p.rot)
    }

    fun cancelPlacing() {
        val p = placing ?: return
        placing = null
        ImageStore.release(p.name)
        p.file.delete()
    }
    
    private fun editLayers(track: Int, f: (List<Layer>) -> List<Layer>) {
        val i = drawTracks.indexOfFirst { it.id == track }
        if (i >= 0) drawTracks[i] = drawTracks[i].copy(layers = f(drawTracks[i].layers))
    }

    /** Same layer names / visibility with brand-new ids (for a new or copied row); also old id -> new id. */
    private fun remapLayers(from: List<Layer>): Pair<List<Layer>, Map<Int, Int>> {
        var next = newLayerId()
        val map = HashMap<Int, Int>()
        val out = from.map { l -> l.copy(id = next).also { map[l.id] = next; next++ } }
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

    // A Loop's audio repeat copies (AudioClip.gen) are DERIVED data (refreshLoops rebuilds them after every edit / undo / redo),
    // so they are left out of the undo snapshots. Drawings are never copied by Re-timing, there is nothing else to leave out.
    fun snapshot() = EditSnap(
        timeline.clips.filter { !it.gen }, timeline.tracks.toList(), cels.toList(), drawTracks.toList(),
        strokes.toList(), timeline.selectedClip, groups.toList(),
    )

    /** Close a finished edit/gesture: pushes one undo step if anything changed since [before]. */
    fun commitEdit(before: EditSnap) {
        refreshLoops() // the audio repeats of looping groups follow the edit
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
    fun structureKey(): Any = listOf(drawTracks.map { it.id }, drawTracks.map { it.layers.map { l -> listOf(l.id, l.name, l.visible) } })

    /** Changes when any blend / opacity / clipping setting changes (cheap update, no replay). */
    fun fxKey(): Any = listOf(
        drawTracks.map { t -> listOf(t.blend, t.opacity, t.clip, t.group, t.layers.map { l -> listOf(l.blend, l.opacity, l.clip) }) },
        groups.map { listOf(it.id, it.blend, it.opacity, it.clip) },
    )

    /** Blend / opacity / clipping of every drawing layer (by native layer id) and row -> native. */
    fun syncFx() {
        val rows = drawTracks.toList()
        val n = layerSlots
        if (rows.isEmpty() || n == 0) return
        fun layerAt(i: Int) = rows[i / n].layers.getOrNull(i % n)
        NativeCanvas.setFx(
            IntArray(rows.size * n) { layerAt(it)?.blend ?: 0 },
            FloatArray(rows.size * n) { layerAt(it)?.opacity ?: 1f },
            BooleanArray(rows.size * n) { layerAt(it)?.clip ?: false },
            IntArray(rows.size) { rows[it].blend },
            FloatArray(rows.size) { rows[it].opacity },
            BooleanArray(rows.size) { rows[it].clip },
        )
        val gs = groups.toList()
        NativeCanvas.setGroupFx(
            IntArray(rows.size) { i -> rows[i].group.takeIf { g -> gs.any { it.id == g } } ?: -1 },
            IntArray(gs.size) { gs[it].id }, IntArray(gs.size) { gs[it].blend }, FloatArray(gs.size) { gs[it].opacity }, BooleanArray(gs.size) { gs[it].clip },
        )
    }

    /** The same data for the exporter: `state.exportFx().applyTo(handle)` after NativeExporter.nativeCreate, before nativeRun. */
    fun exportFx(): ExportFx {
        val rows = drawTracks.toList()
        val ls = rows.flatMap { it.layers }
        return ExportFx(
            IntArray(rows.size) { rows[it].blend }, FloatArray(rows.size) { rows[it].opacity }, BooleanArray(rows.size) { rows[it].clip },
            IntArray(ls.size) { ls[it].id }, IntArray(ls.size) { ls[it].blend }, FloatArray(ls.size) { ls[it].opacity }, BooleanArray(ls.size) { ls[it].clip },
            IntArray(rows.size) { i -> rows[i].group.takeIf { g -> groups.any { it.id == g } } ?: -1 },
            IntArray(groups.size) { groups[it].id }, IntArray(groups.size) { groups[it].blend },
            FloatArray(groups.size) { groups[it].opacity }, BooleanArray(groups.size) { groups[it].clip },
        )
    }

    // ---- blend / clipping (Blend dialog + the Blend / Clipping menu items)

    var fxTarget by mutableStateOf<FxTarget?>(null)
    /** Bumped to open the ••• editing menu for the active row (menu item "Edit Track"). */
    var editMenuTick by mutableIntStateOf(0)

    fun openTrackMenu(track: Int) { activeTrack = track; editMenuTick++ }

    private fun layerOf(id: Int): Layer? = drawTracks.firstNotNullOfOrNull { t -> t.layers.firstOrNull { it.id == id } }
    fun fxModeOf(t: FxTarget): Int = when (t) { is FxTarget.Row -> drawTracks.firstOrNull { it.id == t.id }?.blend; is FxTarget.Lay -> layerOf(t.id)?.blend; is FxTarget.Grp -> groupById(t.id)?.blend } ?: 0
    fun fxOpacityOf(t: FxTarget): Float = when (t) { is FxTarget.Row -> drawTracks.firstOrNull { it.id == t.id }?.opacity; is FxTarget.Lay -> layerOf(t.id)?.opacity; is FxTarget.Grp -> groupById(t.id)?.opacity } ?: 1f
    fun fxClipOf(t: FxTarget): Boolean = when (t) { is FxTarget.Row -> drawTracks.firstOrNull { it.id == t.id }?.clip; is FxTarget.Lay -> layerOf(t.id)?.clip; is FxTarget.Grp -> groupById(t.id)?.clip } ?: false
    fun fxNameOf(t: FxTarget): String = when (t) { is FxTarget.Row -> drawTracks.firstOrNull { it.id == t.id }?.name; is FxTarget.Lay -> layerOf(t.id)?.name; is FxTarget.Grp -> groupById(t.id)?.name } ?: ""

    /** Raw change (no history): a slider drag wraps it in snapshot() + commitEdit(); discrete taps wrap it in edit { }. */
    fun setFxRaw(t: FxTarget, blend: Int? = null, opacity: Float? = null, clip: Boolean? = null) {
        when (t) {
            is FxTarget.Row -> {
                val i = drawTracks.indexOfFirst { it.id == t.id }
                if (i >= 0) drawTracks[i] = drawTracks[i].let { it.copy(blend = blend ?: it.blend, opacity = opacity ?: it.opacity, clip = clip ?: it.clip) }
            }
            is FxTarget.Lay -> {
                val tr = drawTracks.firstOrNull { r -> r.layers.any { it.id == t.id } }?.id ?: return
                editLayers(tr) { ls -> ls.map { if (it.id == t.id) it.copy(blend = blend ?: it.blend, opacity = opacity ?: it.opacity, clip = clip ?: it.clip) else it } }
            }
            is FxTarget.Grp -> {
                val i = groups.indexOfFirst { it.id == t.id }
                if (i >= 0) groups[i] = groups[i].let { it.copy(blend = blend ?: it.blend, opacity = opacity ?: it.opacity, clip = clip ?: it.clip) }
            }
        }
    }

    fun toggleClip(t: FxTarget) = edit { setFxRaw(t, clip = !fxClipOf(t)) }

    /** Changes when any row's transform changes (cheap update, no replay). */
    fun xfKey(): Any = listOf(drawTracks.map { listOf(it.xf, it.keys, it.offset) }, retimeKey())

    /** Changes when any rig (bones, curves, keys, mesh, paint) changes -> resend to native. */
    fun rigKey(): Any = drawTracks.map { it.rig to it.attach } to retimeKey()

    /** Everything the live key re-timing depends on: mode, bar end AND the content span (a trimmed / moved drawing changes the period). */
    private fun retimeKey(): Any = groups.map { listOf(it.id, it.fill, it.endFrame, groupSpans[it.id]) }

    // Packed animation data of the last syncXf(), kept so syncTime() can re-send just the row offsets (see nativeOffsets).
    private var animN = 0
    private var animCounts = IntArray(0)
    private var animStatics = FloatArray(0)
    private var animKeys = FloatArray(0)
    private var animOffsets = IntArray(0)   // the offsets native holds right now

    /** Rows that could not be baked (see [liveRows]): they still use the offset shift of [nativeOffsets]. */
    private var animShift = BooleanArray(0)

    /**
     * The rows as NATIVE must evaluate them at the global playhead (native time - row offset = the row's local frame).
     *  - Stretch / Loop / Loop & Stretch: transform keys AND rig keys are re-timed by [retimeKeys], the same function the
     *    exporter uses, so the canvas equals the export. Stretch used to sample the source at whole frames (stepped, held) and
     *    a rig's keys were not re-timed at all.
     *  - Freeze / Blank / Off: nothing to do, a curve holds its last value by itself (no per-frame re-send any more).
     *  - A channel with one key is constant: it is passed through (a 1-frame drawing can only hold keys on frame 0).
     * [shift] gets true for a row whose unrolled Loop would exceed [BAKE_MAX_KEYS]; it keeps the (once per repeat) offset shift.
     */
    internal fun liveRows(shift: BooleanArray? = null): List<DrawTrack> {
        val rows = drawTracks.toList()
        return rows.mapIndexed { i, r ->
            if (r.keys.isEmpty() && r.rig.keys.isEmpty()) return@mapIndexed r
            val g = if (r.group < 0) null else groupById(r.group)
            val span = if (g == null || g.fill == FILL_NONE) null else groupSpans[g.id]
            if (g == null || span == null) return@mapIndexed r
            if (g.fill != FILL_STRETCH && g.fill != FILL_LOOP && g.fill != FILL_LOOP_STRETCH) return@mapIndexed r
            val end = groupBarEndFrame(g.id)
            val reps = retimeRepeats(g.fill, span.first, span.second, end)
            if (reps.toLong() * (r.keys.size + r.rig.keys.size) > BAKE_MAX_KEYS) { if (shift != null && i < shift.size) shift[i] = true; return@mapIndexed r }
            r.copy(
                keys = retimeKeys(r.keys, r.offset, g.fill, span.first, span.second, end),
                rig = if (r.rig.keys.isEmpty()) r.rig else r.rig.copy(keys = retimeKeys(r.rig.keys, r.offset, g.fill, span.first, span.second, end)),
            )
        }
    }

    /**
     * Send every row's static transform, start offset and keyframes to the native canvas. Native evaluates the
     * curves itself (easing included) at the time set by [syncTime], so playback / scrubbing costs one float.
     */
    fun syncXf() {
        val shift = BooleanArray(drawTracks.size)
        val rows = liveRows(shift)
        val n = layerSlots
        if (rows.isEmpty() || n == 0) return
        val statics = FloatArray(rows.size * 7)
        rows.forEachIndexed { i, r -> r.xf.writeTo(statics, i * 7) }
        val keys = FloatArray(rows.sumOf { it.keys.size } * KEY_STRIDE)
        var o = 0
        for (r in rows) { r.packedKeys.copyInto(keys, o); o += r.packedKeys.size }
        animN = n
        animCounts = IntArray(rows.size) { rows[it].keys.size }
        animStatics = statics
        animKeys = keys
        animShift = shift
        animOffsets = IntArray(0)   // forces the send below
        syncTime()
    }

    /**
     * Row start as NATIVE must see it. Only a row that [liveRows] could not bake (a huge Loop with many keys) is shifted by
     * (playhead - source frame), so native's (time - offset) is the source frame the group shows now. Every other row keeps
     * its real start, so [offs] is the same on every frame and nothing is re-sent while playing.
     */
    private fun nativeOffsets(rows: List<DrawTrack>): IntArray = IntArray(rows.size) { i ->
        val r = rows[i]
        if (i >= animShift.size || !animShift[i]) r.offset
        else rowFrame(r.id, frame).let { m -> if (m < 0) r.offset else r.offset + (frame - m) }
    }

    /** Playhead -> native (it re-evaluates every row's curve on the GL thread). Re-sends the offsets only when a Re-timing shift changed. */
    fun syncTime() {
        val rows = drawTracks.toList()
        if (rows.isNotEmpty() && animCounts.size == rows.size) {
            val offs = nativeOffsets(rows)
            if (!offs.contentEquals(animOffsets)) {
                animOffsets = offs
                NativeCanvas.setAnim(animN, offs, animCounts, animStatics, animKeys)
            }
        }
        NativeCanvas.setTime(frame.toFloat())
    }

    // ---------------------------------------------------------------- layer transform
    /** The row's pose at the playhead. Animated rows ask C++ for it (curve + easing), so UI and canvas always agree. */
    fun trackXf(track: Int): LayerXf {
        val t = drawTracks.firstOrNull { it.id == track } ?: return LayerXf()
        if (t.keys.isEmpty()) return t.xf
        val v = NativeCanvas.evalXf(t.packedKeys, t.keys.size, localTime(track), t.baseArray())
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
        val local = localFrame(track)
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
    /** Row-local frame the keys are read at: the SOURCE frame when the row sits in a re-timed group (see [rowFrame]). */
    fun localFrame(track: Int = activeTrack, f: Int = frame) = rowFrame(track, f).let { if (it < 0) f else it } - trackOffset(track)

    /**
     * Row-local SOURCE time at [f], fractional. Stretch / Loop & Stretch map the playhead to a point BETWEEN two source frames;
     * the gizmo, sliders and rig pose read the keys there, so they agree with the smooth canvas ([liveRows]). Else = [localFrame].
     */
    fun localTime(track: Int = activeTrack, f: Int = frame): Float {
        val t = drawTracks.firstOrNull { it.id == track }
        val g = if (t == null || t.group < 0) null else groupById(t.group)
        val span = if (g == null) null else groupSpans[g.id]
        if (t == null || g == null || span == null || (g.fill != FILL_STRETCH && g.fill != FILL_LOOP_STRETCH)) return localFrame(track, f).toFloat()
        val a = span.first
        val e = maxOf(g.endFrame, span.second)
        val p = (span.second - a).toLong()
        val l = (e - a).toLong()
        if (p <= 0L || l <= 0L || f < a || f >= e) return localFrame(track, f).toFloat()
        val x = (f - a).toLong()
        val m = if (g.fill == FILL_STRETCH) a + x.toDouble() * p / l
        else { val n = maxOf(1L, (l + p / 2) / p); a + ((x * n) % l).toDouble() * p / l }
        return (m - t.offset).toFloat()
    }

    /** Drawing frames in the row (keys beyond this would sit where the row is already gone). */
    fun trackSpan(track: Int): Int = celRel[track]?.let { it[it.size - 1] } ?: 0

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
            val im = s.image
            if (im != null) {
                val r = im.rot * 0.017453292f
                val ex = abs(cos(r)) * im.w / 2f + abs(sin(r)) * im.h / 2f
                val ey = abs(sin(r)) * im.w / 2f + abs(cos(r)) * im.h / 2f
                if (im.cx - ex < x0) x0 = im.cx - ex
                if (im.cy - ey < y0) y0 = im.cy - ey
                if (im.cx + ex > x1) x1 = im.cx + ex
                if (im.cy + ey > y1) y1 = im.cy + ey
                continue
            }
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
    fun shownKey(): Any = drawTracks.map { celAt(frame, it.id)?.shownId() }

    /** Tell native which drawing each row shows now; it redraws only the layers whose drawing changed. */
    fun syncShown() {
        NativeCanvas.setShown(drawTracks.mapNotNull { celAt(frame, it.id)?.shownId() }.toIntArray())
    }

    /**
     * Replay EVERY stroke of every row into the native canvas (native history == [strokes], same order) and tell it
     * which drawings are on screen. Native keeps one lazily-allocated texture per (row, layer) that has something
     * to show and composites them all bottom -> top in a single GL surface.
     */
    fun rebuildNative(blob: Long = 0L) {
        NativeCanvas.resetStrokes()
        val rows = drawTracks.toList()
        val n = layerSlots
        NativeCanvas.setLayers(
            IntArray(rows.size * n) { it },
            // a row with fewer layers than the widest row leaves its spare slots hidden
            BooleanArray(rows.size * n) { rows[it / n].layers.getOrNull(it % n)?.visible ?: false },
        )
        syncFx()
        syncXf()
        syncRig()
        syncShown() // before the strokes, so they are drawn straight into the right layers
        // Project load: [blob] = the natively parsed strokes.bin, identical to [strokes] (no redo yet). The canvas takes it
        // straight from native memory: no FloatArray per stroke, no JNI call per stroke.
        if (blob != 0L && redoStack.isEmpty()) {
            val rowIdx = HashMap<Int, Int>().also { m -> rows.forEachIndexed { i, t -> m[t.id] = i } }
            val celRow = IntArray(cels.size * 2)
            for (i in cels.indices) { celRow[i * 2] = cels[i].id; celRow[i * 2 + 1] = rowIdx[cels[i].track] ?: 0 }
            val layerTab = IntArray(rows.size * n) { Int.MIN_VALUE }
            rows.forEachIndexed { r, t -> t.layers.forEachIndexed { k, l -> if (k < n) layerTab[r * n + k] = l.id } }
            val files = NativeProject.nativeImageFiles(blob)
            if (NativeCanvas.addStrokesFromBlob(blob, celRow, layerTab, n, IntArray(files.size) { ImageStore.handleOf(files[it]) })) return
        }
        val trackOf = HashMap<Int, Int>().also { m -> cels.forEach { m[it.id] = it.track } }
        fun push(st: InkStroke): Boolean {
            val track = trackOf[st.cel] ?: return false
            val im = st.image
            if (im != null) {   // handle -1 (file missing) draws nothing but keeps native history aligned with strokes
                NativeCanvas.addImage(st.cel, nativeLayer(track, st.layer), ImageStore.handleOf(im.file), im.cx, im.cy, im.w, im.h, im.rot)
                return true
            }
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
            groups.clear(); groups.addAll(s.groups)
            collapsedGroups.removeAll { id -> s.groups.none { it.id == id } }
            if (selectedGroup >= 0 && s.groups.none { it.id == selectedGroup }) selectedGroup = -1
            drawTracks.clear(); drawTracks.addAll(s.drawTracks)
            if (drawTracks.none { it.id == activeTrack }) activeTrack = drawTracks.firstOrNull()?.id ?: 0
            if (strokesChanged) { strokes.clear(); strokes.addAll(s.strokes) }
            timeline.selectedClip = if (s.clips.any { it.id == s.selected }) s.selected else -1
        }
        timeline.pushToNative()
        seek(frame) // cels may have shrunk
        if (strokesChanged) rebuildNative()
        refreshLoops()   // audio repeats follow the restored state
    }

    /** The native layer already holds the stroke that was drawn live; this only closes it into history. */
    fun commit(s: InkStroke) {
        strokes.add(s)   // re-timed groups show the source drawing itself: nothing to regenerate
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
    val canDrawHere: Boolean get() = activeTrackObj?.locked == false && currentCel != null && !currentIsGenerated

    /** The playhead is on a re-timed frame (a repeat, a stretched / frozen part): look only, edit the source instead. */
    val currentIsGenerated: Boolean get() = rowFrame(activeTrack, frame) != frame

    fun trackCels(track: Int = activeTrack): List<Cel> = celsByTrack[track] ?: emptyList()
    /** Frame where [track]'s first drawing starts. */
    fun trackOffset(track: Int): Int = drawTracks.firstOrNull { it.id == track }?.offset ?: 0

    /** Frame where [track] ends (offset + all its holds). */
    fun trackLen(track: Int): Int = trackOffset(track) + trackSpan(track)

    /** Raw move of a whole row to start at [offset] (no history; wrap in [edit] / commitEdit). */
    fun setTrackOffset(track: Int, offset: Int) {
        val i = drawTracks.indexOfFirst { it.id == track }
        if (i < 0) return
        val o = offset.coerceAtLeast(0)
        if (drawTracks[i].offset != o) drawTracks[i] = drawTracks[i].copy(offset = o)   // no-op steps must not invalidate every observer
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
        val r = f - trackOffset(track)
        if (r < 0) return -1
        val rel = celRel[track] ?: return -1
        val n = rel.size - 1
        if (n <= 0 || r >= rel[n]) return -1
        // binary search: last cel whose start is <= r (was a linear walk over every generated copy)
        var lo = 0
        var hi = n - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (rel[mid] <= r) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun celStart(i: Int, track: Int = activeTrack): Int {
        val a = trackOffset(track)
        val rel = celRel[track] ?: return a
        return a + rel[i.coerceIn(0, rel.size - 1)]
    }

    /** The drawing row [track] SHOWS at timeline frame [f]: goes through the group's Re-timing ([rowFrame]) first. */
    fun celAt(f: Int, track: Int = activeTrack): Cel? {
        val m = rowFrame(track, f)
        if (m < 0) return null
        return celIndexAt(m, track).let { if (it < 0) null else trackCels(track)[it] }
    }

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
    fun addDrawTrack() = edit { addDrawTrackRaw(-1) } // the + menu always adds a top-level row, never one inside a folder

    /** New drawing row INSIDE group [g] (only from that group's ••• menu): on top of the folder, or right above the active row when it is a member. */
    fun addDrawTrackIn(g: Int) = edit { if (groupById(g) != null) addDrawTrackRaw(g) }

    /** Group a PASTED drawing row joins: the active row's folder (it lands next to it, so the block stays whole). */
    private fun joinGroup(): Int = activeTrackObj?.group?.takeIf { groupById(it) != null } ?: -1

    /**
     * Slot in [drawTracks] (bottom -> top) for a new row of group [g]: above the active row if it is a member, else on top of
     * the folder. A top-level row ([g] < 0) goes just ABOVE the whole folder when the active row sits inside one, so it never splits it.
     */
    private fun joinSlot(g: Int): Int {
        val ai = drawTracks.indexOfFirst { it.id == activeTrack }
        if (g < 0) drawTracks.getOrNull(ai)?.group?.takeIf { groupById(it) != null }?.let { ag -> return drawTracks.indexOfLast { it.group == ag } + 1 }
        if (g >= 0 && drawTracks.getOrNull(ai)?.group != g) {
            val top = drawTracks.indexOfLast { it.group == g }
            if (top >= 0) return top + 1
        }
        return if (ai < 0) drawTracks.size else ai + 1
    }

    private fun addDrawTrackRaw(grp: Int) {
        val id = newTrackId()
        val at = joinSlot(grp)
        // a row that joins a live Loop / Freeze group is as long as the group's source: a timeline-long drawing would stretch the loop period
        val span = if (grp >= 0 && groupById(grp)?.fill != FILL_NONE) groupSourceSpan(grp) else null
        val len = span?.let { (it.second - it.first).coerceAtLeast(1) } ?: frameCount
        drawTracks.add(at, DrawTrack(id, "Drawing ${id + 1}", offset = span?.first ?: 0, group = grp, layers = remapLayers(layers.ifEmpty { listOf(Layer(0, "Body")) }.map { it.copy(visible = true) }).first))
        cels.add(Cel(newCelId(), len, id))
        gatherDrawGroups() // the folder stays ONE block
        activeTrack = id
    }

    /** Copy of the active row (every drawing, hold and stroke) placed directly above it. */
    fun duplicateDrawTrack() = edit {
        val i = drawTracks.indexOfFirst { it.id == activeTrack }
        if (i < 0) return@edit
        val src = drawTracks[i]
        val id = newTrackId()
        val (ls, map) = remapLayers(src.layers)
        drawTracks.add(i + 1, DrawTrack(id, "${src.name} copy", offset = src.offset, xf = src.xf, keys = src.keys, rig = src.rig, attach = src.attach, layers = ls, group = src.group))
        for (c in trackCels(src.id).filter { it.gen == -1 }) {
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
        pruneGroups()
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
        gatherDrawGroups() // a plain move must never split a group's block
    }

    // ------------------------------------------------------------------ groups (folders of tracks)
    // A group is a folder: drawing rows and audio layers join it through their `group` field. It has no transform,
    // so the canvas and the export are untouched. Drawing members of a group always sit next to each other in
    // [drawTracks] (the group is ONE block of the stack); audio layers can be anywhere in the audio list because
    // their order does not change the mix. Every public op that changes membership is ONE undo step.

    val groups = mutableStateListOf<TrackGroup>()
    /** Closed folders. UI only: opening / closing is not an undo step. */
    val collapsedGroups = mutableStateListOf<Int>()
    /** The group whose bar is selected on the timeline (drag it to move every member in time). UI only. */
    var selectedGroup by mutableIntStateOf(-1)
    /** Set by the timeline (long-press on a name tag, group menu); the timeline shows the dialog. */
    var renameTarget by mutableStateOf<RenameTarget?>(null)

    fun groupById(id: Int): TrackGroup? = if (id < 0) null else groups.firstOrNull { it.id == id }
    fun isCollapsed(g: Int) = g in collapsedGroups
    fun toggleCollapsed(g: Int) { if (!collapsedGroups.remove(g)) collapsedGroups.add(g) }
    private fun newGroupId() = (groups.maxOfOrNull { it.id } ?: -1) + 1

    fun groupDrawMembers(g: Int): List<DrawTrack> = drawTracks.filter { it.group == g }
    fun groupAudioMembers(g: Int): List<AudioTrack> = timeline.tracks.filter { it.group == g }

    /** Every member locked (and at least one member). */
    fun isGroupLocked(g: Int): Boolean {
        val d = groupDrawMembers(g)
        val a = groupAudioMembers(g)
        return (d.isNotEmpty() || a.isNotEmpty()) && d.all { it.locked } && a.all { it.locked }
    }

    /** True when any selected track currently sits in a group (enables "Ungroup"). */
    val selectionInGroup: Boolean
        get() = drawTracks.any { it.id in selDraw && groupById(it.group) != null } ||
            timeline.tracks.any { it.id in selAudio && groupById(it.group) != null }

    fun groupMembersSelected(g: Int): Boolean {
        val d = groupDrawMembers(g)
        val a = groupAudioMembers(g)
        return (d.isNotEmpty() || a.isNotEmpty()) && d.all { it.id in selDraw } && a.all { it.id in selAudio }
    }

    /** Select-tracks mode: tapping a group selects all its members, or clears them when they were all selected. */
    fun toggleGroupSel(g: Int) {
        val all = groupMembersSelected(g)
        for (t in groupDrawMembers(g)) { if (all) selDraw.remove(t.id) else if (t.id !in selDraw) selDraw.add(t.id) }
        for (t in groupAudioMembers(g)) { if (all) selAudio.remove(t.id) else if (t.id !in selAudio) selAudio.add(t.id) }
    }

    /** Every selected track (drawing + audio) into ONE new group. The block lands where its topmost drawing row was. */
    fun groupSelectedTracks() {
        if (selCount == 0) return
        edit { makeGroup(selDraw.toSet(), selAudio.toSet()) }
        endTrackSelect()
    }

    /** The active drawing row into a new group of its own (no selection needed). */
    fun groupActiveTrack() = edit {
        if (drawTracks.any { it.id == activeTrack }) makeGroup(setOf(activeTrack), emptySet())
    }

    private fun makeGroup(draw: Set<Int>, audio: Set<Int>) {
        if (draw.isEmpty() && audio.isEmpty()) return
        val tl = timeline
        val gid = newGroupId()
        groups.add(TrackGroup(gid, "Group ${gid + 1}"))
        for (i in drawTracks.indices) if (drawTracks[i].id in draw) drawTracks[i] = drawTracks[i].copy(group = gid)
        for (i in tl.tracks.indices) if (tl.tracks[i].id in audio) tl.tracks[i] = tl.tracks[i].copy(group = gid)
        gatherDrawGroups()
        pruneGroups()
        selectedGroup = gid
    }

    /** Takes the selected tracks out of their groups (select a group's header first to dissolve the whole folder). */
    fun ungroupSelectedTracks() = edit {
        val tl = timeline
        for (i in drawTracks.indices) if (drawTracks[i].id in selDraw && drawTracks[i].group >= 0) drawTracks[i] = drawTracks[i].copy(group = -1)
        for (i in tl.tracks.indices) if (tl.tracks[i].id in selAudio && tl.tracks[i].group >= 0) tl.tracks[i] = tl.tracks[i].copy(group = -1)
        gatherDrawGroups()
        pruneGroups()
    }

    /** Dissolves one folder: its members stay exactly where they are in the stack, just not inside a group any more. */
    fun ungroup(g: Int) = edit {
        val tl = timeline
        for (i in drawTracks.indices) if (drawTracks[i].group == g) drawTracks[i] = drawTracks[i].copy(group = -1)
        for (i in tl.tracks.indices) if (tl.tracks[i].group == g) tl.tracks[i] = tl.tracks[i].copy(group = -1)
        pruneGroups()
        if (selectedGroup == g) selectedGroup = -1
    }

    fun setGroupLocked(g: Int, lock: Boolean) = edit {
        val tl = timeline
        for (i in drawTracks.indices) if (drawTracks[i].group == g) drawTracks[i] = drawTracks[i].copy(locked = lock)
        for (i in tl.tracks.indices) if (tl.tracks[i].group == g) tl.tracks[i] = tl.tracks[i].copy(locked = lock)
    }

    fun nameOf(t: RenameTarget): String = when (t) {
        is RenameTarget.Draw -> drawTracks.firstOrNull { it.id == t.id }?.name
        is RenameTarget.Audio -> timeline.tracks.firstOrNull { it.id == t.id }?.name
        is RenameTarget.Group -> groupById(t.id)?.name
    } ?: ""

    /** Renames a drawing row, audio layer or group (one undo step). An empty name keeps the old one. */
    fun rename(t: RenameTarget, name: String) = edit {
        val n = name.trim().take(32)
        if (n.isEmpty()) return@edit
        when (t) {
            is RenameTarget.Draw -> {
                val i = drawTracks.indexOfFirst { it.id == t.id }
                if (i >= 0) drawTracks[i] = drawTracks[i].copy(name = n)
            }
            is RenameTarget.Audio -> {
                val i = timeline.tracks.indexOfFirst { it.id == t.id }
                if (i >= 0) timeline.tracks[i] = timeline.tracks[i].copy(name = n)
            }
            is RenameTarget.Group -> {
                val i = groups.indexOfFirst { it.id == t.id }
                if (i >= 0) groups[i] = groups[i].copy(name = n)
            }
        }
    }

    /** Makes every group's drawing members ONE block of [drawTracks], placed where its topmost member was. */
    private fun gatherDrawGroups() {
        val topDown = drawTracks.asReversed().toList()
        val out = ArrayList<DrawTrack>(topDown.size)
        val done = HashSet<Int>()
        for (t in topDown) {
            if (groupById(t.group) == null) { out.add(t); continue }
            if (done.add(t.group)) out.addAll(topDown.filter { it.group == t.group })
        }
        if (out.map { it.id } == topDown.map { it.id }) return
        drawTracks.clear()
        drawTracks.addAll(out.asReversed())
    }

    /** Drops groups that lost their last member and frees members that point at a group that is gone. */
    private fun pruneGroups() {
        val tl = timeline
        val live = HashSet<Int>()
        for (t in drawTracks) if (t.group >= 0) live.add(t.group)
        for (t in tl.tracks) if (t.group >= 0) live.add(t.group)
        groups.removeAll { it.id !in live }
        collapsedGroups.removeAll { it !in live }
        for (i in drawTracks.indices) if (drawTracks[i].group >= 0 && groupById(drawTracks[i].group) == null) drawTracks[i] = drawTracks[i].copy(group = -1)
        for (i in tl.tracks.indices) if (tl.tracks[i].group >= 0 && groupById(tl.tracks[i].group) == null) tl.tracks[i] = tl.tracks[i].copy(group = -1)
        if (selectedGroup >= 0 && groupById(selectedGroup) == null) selectedGroup = -1
    }

    /**
     * Drag-reorder of the drawing stack, one undo step. [ctx] = the group whose members are being reordered, or -1
     * for the top-level items (an ungrouped row, or a whole group as one block). [order] = the new TOP -> BOTTOM order
     * of the item keys: a row's track id, or [groupKey] of a group (top level only).
     */
    fun reorderDrawItems(ctx: Int, order: List<Int>) = edit {
        val topDown = drawTracks.asReversed().toList()
        val res: List<DrawTrack>
        if (ctx >= 0) {
            val members = topDown.filter { it.group == ctx }
            if (order.toSet() != members.map { it.id }.toSet() || order.size != members.size) return@edit
            val byId = members.associateBy { it.id }
            var k = 0
            res = topDown.map { t -> if (t.group == ctx) byId.getValue(order[k++]) else t }
        } else {
            val blocks = LinkedHashMap<Int, MutableList<DrawTrack>>()
            for (t in topDown) blocks.getOrPut(if (groupById(t.group) != null) groupKey(t.group) else t.id) { ArrayList() }.add(t)
            if (order.toSet() != blocks.keys || order.size != blocks.size) return@edit
            res = order.flatMap { blocks.getValue(it) }
        }
        if (res.map { it.id } == topDown.map { it.id }) return@edit
        drawTracks.clear()
        drawTracks.addAll(res.asReversed())
    }

    /**
     * Drag-drop: puts the top-level drawing row [id] INTO group [g] (it lands on top of the folder). In a re-timed group
     * the new member is re-timed with the others at once (it is only a mapping).
     */
    fun moveRowIntoGroup(id: Int, g: Int) = edit {
        val i = drawTracks.indexOfFirst { it.id == id }
        if (i < 0 || groupById(g) == null) return@edit
        val t = drawTracks[i]
        if (t.group == g || t.group >= 0) return@edit
        drawTracks.removeAt(i)
        val top = drawTracks.indexOfLast { it.group == g }
        drawTracks.add(if (top >= 0) top + 1 else i.coerceAtMost(drawTracks.size), t.copy(group = g))
        gatherDrawGroups()
        activeTrack = id
    }

    /**
     * Drag-drop: takes drawing row [id] OUT of its folder and puts it just above ([above]) or below the whole folder.
     * It plays by its own time again (a group's Re-timing only applies to the rows inside it).
     */
    fun moveRowOutOfGroup(id: Int, above: Boolean) = edit {
        val i = drawTracks.indexOfFirst { it.id == id }
        if (i < 0) return@edit
        val t = drawTracks[i]
        val g = t.group
        if (g < 0) return@edit
        drawTracks.removeAt(i)
        val last = drawTracks.indexOfLast { it.group == g }
        val first = drawTracks.indexOfFirst { it.group == g }
        val at = when {
            last < 0 -> i.coerceAtMost(drawTracks.size)
            above -> last + 1
            else -> first
        }
        drawTracks.add(at, t.copy(group = -1))
        gatherDrawGroups()
        pruneGroups()
        activeTrack = id
    }

    // ---- moving a whole group in time

    /** Start times of the group's unlocked members: take it when a drag begins. */
    fun beginGroupShift(g: Int): GroupShift {
        val d = drawTracks.filter { it.group == g && !it.locked }.associate { it.id to it.offset }
        val audio = timeline.tracks.filter { it.group == g && !it.locked }.mapTo(HashSet()) { it.id }
        val c = timeline.clips.filter { it.track in audio }.associate { it.id to it.startMs }
        return GroupShift(d, c)
    }

    /**
     * Raw (wrap the drag in snapshot + commitEdit): every unlocked member starts [df] frames later (earlier when
     * negative) than in [s]. Clamped so nothing starts before 0 and the members keep their spacing. Returns the shift used.
     */
    fun shiftGroup(s: GroupShift, df: Int): Int {
        var lo = Int.MIN_VALUE
        for (off in s.draw.values) lo = maxOf(lo, -off)
        for (st in s.clips.values) lo = maxOf(lo, -(st * fps / 1000L).toInt())
        val d = maxOf(df, lo)
        for ((id, off) in s.draw) setTrackOffset(id, off + d)
        val dMs = frameToMs(d)
        timeline.shiftClips(s.clips, dMs)   // one pass over the clip table (was one full scan per clip)
        return d
    }

    /** Time span in seconds of each member of [g]: one entry per drawing row, one per audio layer that has clips. */
    fun groupMemberSpans(g: Int): List<ClosedFloatingPointRange<Float>> {
        val out = ArrayList<ClosedFloatingPointRange<Float>>()
        for (t in drawTracks) if (t.group == g) out.add((t.offset / fps.toFloat())..(trackLen(t.id) / fps.toFloat()))
        for (t in timeline.tracks) if (t.group == g) {
            val cs = timeline.clips.filter { it.track == t.id }
            if (cs.isNotEmpty()) out.add((cs.minOf { it.startMs } / 1000f)..(cs.maxOf { it.startMs + it.lenMs } / 1000f))
        }
        return out
    }

    /** Content of the group in frames: (first member start, last member end); null when it has no content. */
    fun groupFrameSpan(g: Int): Pair<Int, Int>? = groupSpans[g]

    /** Same span under the name the Re-timing code uses: "source" = the real drawings / clips, never audio repeat copies. */
    fun groupSourceSpan(g: Int): Pair<Int, Int>? = groupSpans[g]

    /** Where the group bar ends now: its stretched end, or the end of its content. */
    fun groupBarEndFrame(g: Int): Int = maxOf(groupById(g)?.endFrame ?: -1, groupSpans[g]?.second ?: 0)

    // ------------------------------------------------------------------ group Re-timing
    // Off / Freeze / Stretch / Loop / Loop & Stretch / Blank, like the Group track of Alight Motion. The group keeps ONE
    // copy of its drawings; a time MAPPING ([retimeFrame]) decides what shows at a timeline frame, so nothing is generated
    // and a loop of any length adds no cels, strokes, native redraws or timeline items.
    //  - drawings: celAt() / trackXf() map the playhead through rowFrame() first.
    //  - keyframes: native evaluates every curve at one global time, so syncTime() shifts the start offset of each animated
    //    row by (playhead - source frame) and re-sends only when that shift changes.
    //  - sound: the mixer has no loop flag, so Loop / Loop & Stretch still add one copy of each audio clip per repeat
    //    (AudioClip.gen, rebuilt by refreshLoops, never in the undo history). That is a few clips, not frames.

    /**
     * The timeline frame whose content row [track] shows at timeline frame [f]: [f] itself outside a re-timed group,
     * otherwise the source frame ([retimeFrame]), or [RETIME_BLANK] when nothing is shown. O(1).
     */
    fun rowFrame(track: Int, f: Int): Int {
        val t = drawTracks.firstOrNull { it.id == track } ?: return f
        if (t.group < 0) return f
        val g = groupById(t.group) ?: return f
        if (g.fill == FILL_NONE) return f
        val span = groupSpans[g.id] ?: return f
        val m = retimeFrame(g.fill, span.first, span.second, g.endFrame, f)
        // Freeze holds each row's OWN last drawing (a row shorter than the group would vanish if the group's last frame were used)
        if (g.fill == FILL_FREEZE && m >= 0 && f >= span.second) return minOf(m, trackLen(track) - 1)
        return m
    }

    /** Kept for callers of the old generator: Re-timing is instant, so there is never anything to wait for. */
    val genBusy: Int get() = 0
    suspend fun awaitGeneration() {}

    /** Project load: only the audio repeats of looping groups have to be (re)built; drawings are mapped, not stored. */
    fun regenAllLoops() = refreshLoops()

    /**
     * Raw (a drag wraps it in snapshot + commitEdit): move the end of the group bar to [frame]. Off: at or before the
     * content end = not stretched. A re-timed group can't end before its content does. The picture follows at once (it is
     * a mapping); the audio repeats are rebuilt when the edit is committed.
     */
    fun setGroupEnd(g: Int, frame: Int) {
        val i = groups.indexOfFirst { it.id == g }
        if (i < 0) return
        val grp = groups[i]
        val src = groupSpans[g]?.second ?: 0
        val end = if (grp.fill == FILL_NONE) (if (frame <= src) -1 else frame) else maxOf(frame, src)
        if (grp.endFrame != end) groups[i] = grp.copy(endFrame = end)   // same frame as the last pointer move: no write
    }

    fun clearGroupEnd(g: Int) = edit { setGroupEnd(g, -1) }

    /** While dragging the end of a Loop group: snap to whole repeats when within [tol] frames. */
    fun snapGroupEnd(g: Int, frame: Int, tol: Int): Int {
        val grp = groupById(g) ?: return frame
        val span = groupSpans[g] ?: return frame
        if (grp.fill != FILL_LOOP || frame <= span.second) return frame
        val p = span.second - span.first
        if (p <= 0) return frame
        val b = span.second + Math.round((frame - span.second).toFloat() / p) * p
        return if (abs(frame - b) <= tol) b else frame
    }

    /**
     * Set group [g] to Re-timing [mode] (one of [RETIME_MODES]). The first time a group gets a bar longer than its
     * content: Loop modes get one more repeat, the others one more second. A bar the user already stretched is kept.
     * Off clears the stretch.
     */
    fun setGroupFill(g: Int, mode: Int) = edit {
        val i = groups.indexOfFirst { it.id == g }
        val span = groupSpans[g]
        if (i < 0 || span == null) return@edit
        val cur = groups[i]
        if (mode == FILL_NONE) { groups[i] = cur.copy(fill = FILL_NONE, endFrame = -1); return@edit }
        val extra = if (mode == FILL_LOOP || mode == FILL_LOOP_STRETCH) (span.second - span.first).coerceAtLeast(1) else fps
        val end = if (cur.endFrame > span.second) cur.endFrame else span.second + extra
        groups[i] = cur.copy(fill = mode, endFrame = end)
    }

    /** Re-timing Off. */
    fun clearGroupFill(g: Int) = setGroupFill(g, FILL_NONE)

    /**
     * Brings the audio repeats of every Loop / Loop & Stretch group in line with its source clips and bar end. Compares
     * with what is there and writes nothing when it is the same. (Silence for Freeze / Stretch / Blank: no copies.)
     */
    private fun refreshLoops() {
        val tl = timeline
        val want = ArrayList<AudioClip>()
        var nid = tl.nextClipId()   // one scan of the clip table
        for (grp in groups.toList()) loopAudio(grp, want) { nid++ }
        val have = tl.clips.filter { it.gen }
        if (have.size == want.size && have.indices.all { have[it].copy(id = 0) == want[it].copy(id = 0) }) return
        Snapshot.withMutableSnapshot {
            tl.clips.removeWhere { it.gen }
            tl.clips.addAll(want)
        }
    }

    /** Appends the repeat copies of [grp]'s audio clips (copies of the source clips shifted by whole periods, cut at the bar end). */
    private fun loopAudio(grp: TrackGroup, out: MutableList<AudioClip>, newId: () -> Int) {
        if (grp.fill != FILL_LOOP && grp.fill != FILL_LOOP_STRETCH) return
        val span = groupSpans[grp.id] ?: return
        val audio = groupAudioMembers(grp.id).mapTo(HashSet()) { it.id }
        if (audio.isEmpty()) return
        val src = timeline.clips.filter { it.track in audio && !it.gen }
        if (src.isEmpty()) return
        val a = span.first
        val p = span.second - a
        val end = groupBarEndFrame(grp.id)
        val l = end - a
        if (p <= 0 || l <= p) return
        // Loop: period = the content. Loop & Stretch: the bar split into a whole number of equal repeats (sound itself is not stretched).
        val period = if (grp.fill == FILL_LOOP) p.toDouble() else l.toDouble() / maxOf(1, (l + p / 2) / p)
        val periodMs = period * 1000.0 / fps
        val endMs = frameToMs(end)
        var made = 0
        var k = 1
        while (made < MAX_LOOP_CLIPS) {
            val shift = (k * periodMs).toLong()
            var any = false
            for (c in src) {
                val start = c.startMs + shift
                if (start >= endMs) continue
                any = true
                val len = minOf(c.lenMs, endMs - start)
                if (len < TimelineState.MIN_CLIP_MS) continue
                out.add(c.copy(id = newId(), startMs = start, lenMs = len, gen = true))
                made++
            }
            if (!any) break
            k++
        }
    }

    /** Whole span of the group (first member start .. last member end), seconds; null when it has no members. */
    fun groupSpanSec(g: Int): ClosedFloatingPointRange<Float>? {
        val s = groupMemberSpans(g)
        if (s.isEmpty()) return null
        return s.minOf { it.start }..s.maxOf { it.endInclusive }
    }

    // ------------------------------------------------------------------ drawing (cel / frame) ops
    // Each public op is ONE undo step. Stroke edits are mirrored to the native canvas by commitEdit().

    private fun newCelId() = (cels.maxOfOrNull { it.id } ?: -1) + 1 // unique across ALL rows
    private fun strokesOf(cel: Int): List<InkStroke> = strokesByCel[cel] ?: emptyList()

    fun copyCel() {
        val c = currentCel ?: return
        putClipboard(Clipboard.CelArt(c.len, strokesOf(c.id)))
    }

    fun cutCel() { copyCel(); deleteCel() }

    fun pasteCel() = edit {
        if (currentIsGenerated) return@edit
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
        if (currentIsGenerated) return@edit
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
        if (currentIsGenerated) return@edit
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
        if (currentIsGenerated) return@edit
        val id = currentCel?.id ?: return@edit
        strokes.removeAll { it.cel == id }
    }

    /** Cut the hold at the playhead: the second half becomes its own drawing, starting as a copy of the first. */
    fun splitCel() = edit {
        if (currentIsGenerated) return@edit
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
        if (currentIsGenerated) return@edit
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

    // ---- carrying a drawing into another row / group (raw: wrap in snapshot + commitEdit)

    /** The drawing the finger carries (UI only, -1 = none), the row it hovers and the group bar it hovers (-1 = none). */
    var liftedCel by mutableIntStateOf(-1)
    var dropTrack by mutableIntStateOf(-1)
    var dropGroup by mutableIntStateOf(-1)

    fun celTrack(celId: Int): Int = cels.firstOrNull { it.id == celId }?.track ?: -1

    /** A drawing can be lifted when its row is unlocked and it is real (loop / freeze copies are regenerated, not moved). */
    fun canLiftCel(track: Int, index: Int): Boolean {
        val t = drawTracks.firstOrNull { it.id == track } ?: return false
        return !t.locked && trackCels(track).getOrNull(index)?.gen == -1
    }

    /** Row a drawing dropped on group [g]'s bar goes into: the active row when it belongs to the folder, else its top row. -1 = none. */
    fun groupDropTrack(g: Int): Int {
        val m = drawTracks.filter { it.group == g && !it.locked }
        return (m.firstOrNull { it.id == activeTrack } ?: m.lastOrNull())?.id ?: -1
    }

    /** Slot (0..real count) a drawing dropped with its centre at [centerFrame] takes in [track]; generated copies stay behind it. */
    fun dropIndex(track: Int, centerFrame: Float): Int {
        var start = trackOffset(track).toFloat()
        var n = 0
        for (c in trackCels(track)) {
            if (c.gen != -1) break
            if (centerFrame < start + c.len / 2f) return n
            start += c.len
            n++
        }
        return n
    }

    /**
     * Raw: moves drawing [celId] into row [toTrack] as its [toIndex]-th drawing. Its strokes follow onto the target row's
     * layers (same name, else same position, else the first one). A row that loses its only drawing keeps a blank hold of
     * the same length. Returns the new index in [toTrack], -1 when nothing moved.
     */
    fun moveCelToTrack(celId: Int, toTrack: Int, toIndex: Int): Int {
        val gIdx = cels.indexOfFirst { it.id == celId }
        if (gIdx < 0) return -1
        val c = cels[gIdx]
        val from = c.track
        if (c.gen != -1 || from == toTrack) return -1
        val src = drawTracks.firstOrNull { it.id == from } ?: return -1
        val dst = drawTracks.firstOrNull { it.id == toTrack } ?: return -1
        if (src.locked || dst.locked) return -1
        val map = HashMap<Int, Int>()
        src.layers.forEachIndexed { k, l ->
            map[l.id] = (dst.layers.firstOrNull { it.name == l.name } ?: dst.layers.getOrNull(k) ?: dst.layers.firstOrNull())?.id ?: l.id
        }
        cels.removeAt(gIdx)
        if (trackCels(from).isEmpty()) cels.add(gIdx, Cel(newCelId(), c.len, from))
        for (i in strokes.indices) {
            val s = strokes[i]
            if (s.cel == celId) strokes[i] = s.copy(layer = map[s.layer] ?: s.layer)
        }
        val at = toIndex.coerceIn(0, trackCels(toTrack).count { it.gen == -1 })
        insertCel(toTrack, at, c.copy(track = toTrack))
        return at
    }

    /**
     * One step of a carried drawing (raw, call on every finger move): inside its own row it reorders one slot at a time,
     * over another unlocked row it drops in at the finger. The playhead and the active row follow it.
     * Returns true when something moved.
     */
    fun carryCel(celId: Int, toTrack: Int, centerFrame: Float): Boolean {
        val c = cels.firstOrNull { it.id == celId } ?: return false
        if (c.gen != -1 || toTrack < 0) return false
        if (c.track == toTrack) {
            val from = trackCels(toTrack).indexOfFirst { it.id == celId }
            val real = trackCels(toTrack).count { it.gen == -1 }
            val to = reorderTarget(from, centerFrame, toTrack).coerceAtMost(real - 1)
            if (from < 0 || to == from) return false
            moveCel(from, to, toTrack)
            return true
        }
        val i = moveCelToTrack(celId, toTrack, dropIndex(toTrack, centerFrame))
        if (i < 0) return false
        activeTrack = toTrack
        frame = celStart(i, toTrack)
        return true
    }

    fun changeHold(delta: Int) = edit {
        if (currentIsGenerated) return@edit
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
        val n = c.copy(id = tl.nextClipId(), track = trackId, startMs = start, gen = false)
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
        pruneGroups()
        clearTrackSel()
        seek(frame)
    }

    private fun cloneDrawTrack(src: DrawTrack): Int {
        val i = drawTracks.indexOfFirst { it.id == src.id }
        val id = newTrackId()
        val (ls, map) = remapLayers(src.layers)
        drawTracks.add(i + 1, DrawTrack(id, "${src.name} copy", offset = src.offset, xf = src.xf, keys = src.keys, rig = src.rig, attach = src.attach, layers = ls, group = src.group))
        for (c in trackCels(src.id).filter { it.gen == -1 }) {
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
        val d = drawTracks.filter { it.id in selDraw }.map { t -> TrackArt(t, trackCels(t.id).filter { it.gen == -1 }.map { c -> c.len to strokesOf(c.id) }) }
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
        // pasted rows land above the active one, so they join its group (keeps the group one block)
        val anchorGroup = joinGroup()
        for (art in c.draw) {
            val id = newTrackId()
            val at = joinSlot(anchorGroup)
            val t = art.track
            val (ls, map) = remapLayers(t.layers)
            drawTracks.add(at, DrawTrack(id, "${t.name} copy", offset = t.offset, xf = t.xf, keys = t.keys, rig = t.rig, attach = t.attach, layers = ls, group = anchorGroup))
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
    /** Extra content at the start of the top bar (back button, save status). */
    topLeading: @Composable RowScope.() -> Unit = {},
) {
    val density = LocalDensity.current
    var dockH by remember { mutableStateOf(0.dp) }
    val topH = 44.dp
    
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { state.importImage(ctx, uri) }
    }
    LaunchedEffect(state.imagePickRequested) {
        if (state.imagePickRequested) { state.imagePickRequested = false; imagePicker.launch(arrayOf("image/*")) }
    }
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

        TopBar(state, Modifier.align(Alignment.TopCenter).height(topH), topLeading)

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
private fun TopBar(state: EditorState, modifier: Modifier, leading: @Composable RowScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
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
        snapshotFlow { state.fxKey() }.distinctUntilChanged().collect { state.syncFx() }
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
            override fun canDraw() = state.tool == Tool.Brush && state.brush.ready && state.canDrawHere && !state.transformOpen && state.placing == null
            
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
        if (state.placing != null) ImagePlaceOverlay(state, viewSize)
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
    st.image?.let { drawPlacedImage(it, w); return }
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
    val row = state.cels.firstOrNull { it.id == cel }?.track
    // PERF: this cel's strokes only, not a scan of every stroke in the project
    paintLayers(if (row != null) state.layersOf(row) else emptyList(), state.strokesByCel[cel] ?: emptyList(), w, live)
}

/** [paintCel] on plain data (no snapshot state), so a timeline thumbnail can be inked on a background thread. */
internal fun DrawScope.paintLayers(layers: List<Layer>, ofCel: List<InkStroke>, w: Float, live: InkStroke? = null) {
    val bounds = Rect(0f, 0f, size.width, size.height)
    for (l in layers) {
        if (!l.visible) continue
        val mine = ofCel.filter { it.layer == l.id }
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
internal fun ToolBtn(kind: Ico, label: String, selected: Boolean, size: Dp = 40.dp, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) cs.primaryContainer else Color.Transparent, label = "toolBg")
    val fg = if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { FoxIcon(kind, tint = fg) }
}

@Composable
internal fun ColorSizeBtn(state: EditorState, size: Dp = 40.dp) {
    val cs = MaterialTheme.colorScheme
    val sel = state.panel == Panel.Color
    val bg by animateColorAsState(if (sel) cs.primaryContainer else Color.Transparent, label = "csBg")
    val dot by animateDpAsState((6 + state.brushSize / 64f * 22f).dp, label = "dot")
    Box(
        Modifier
            .size(size)
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

/** The menu a row (timeline header icon) or a layer (3-dot) opens: Edit Track, Blend, Clipping (arrow shows while it is on). */
@Composable
internal fun FxMenuItems(state: EditorState, target: FxTarget, track: Int, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val clip = state.fxClipOf(target)
    val mode = state.fxModeOf(target)
    val op = state.fxOpacityOf(target)
    if (target !is FxTarget.Grp) DropdownMenuItem(
        text = { Text("Edit Track") },
        leadingIcon = { FoxIcon(Ico.Props, iconSize = 20.dp) },
        onClick = { onDismiss(); state.openTrackMenu(track) },
    )
    DropdownMenuItem(
        text = { Text("Blend") },
        leadingIcon = { FoxIcon(Ico.Blend, tint = if (mode != 0 || op < 0.999f) cs.primary else LocalContentColor.current, iconSize = 20.dp) },
        trailingIcon = if (mode != 0 || op < 0.999f) ({ Text(FxBlend.name(mode), style = MaterialTheme.typography.labelSmall, color = cs.primary) }) else null,
        onClick = { onDismiss(); state.fxTarget = target },
    )
    DropdownMenuItem(
        text = { Text("Clipping") },
        leadingIcon = { FoxIcon(Ico.ClipArrow, tint = if (clip) cs.primary else LocalContentColor.current, iconSize = 20.dp) },
        trailingIcon = if (clip) ({ FoxIcon(Ico.ClipArrow, tint = cs.primary, iconSize = 16.dp) }) else null,
        onClick = { onDismiss(); state.toggleClip(target) },
    )
}

/** Blend mode list + opacity slider for a row or a layer. The slider drag is one undo step; a mode tap is one. */
@Composable
internal fun FxDialog(state: EditorState, target: FxTarget) {
    val cs = MaterialTheme.colorScheme
    var before by remember(target) { mutableStateOf<EditSnap?>(null) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { state.fxTarget = null },
        title = { Text("Blend \u00B7 ${state.fxNameOf(target)}") },
        text = {
            Column {
                Text(
                    "Opacity ${(state.fxOpacityOf(target) * 100f).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant,
                )
                Slider(
                    value = state.fxOpacityOf(target),
                    onValueChange = { v ->
                        if (before == null) before = state.snapshot()
                        state.setFxRaw(target, opacity = v)
                    },
                    onValueChangeFinished = { before?.let { state.commitEdit(it) }; before = null },
                    valueRange = 0f..1f,
                )
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    items(FxBlend.names.size) { i ->
                        val on = state.fxModeOf(target) == i
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (on) cs.primaryContainer else Color.Transparent)
                                .clickable { state.edit { state.setFxRaw(target, blend = i) } }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                        ) { Text(FxBlend.names[i], color = if (on) cs.onPrimaryContainer else cs.onSurface) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { state.fxTarget = null }) { Text("Done") } },
        dismissButton = { TextButton(onClick = { state.edit { state.setFxRaw(target, blend = 0, opacity = 1f) } }) { Text("Reset") } },
    )
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
                var menu by remember { mutableStateOf(false) }
                Box {
                    FilledTonalIconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                        FoxIcon(Ico.Plus, iconSize = 18.dp)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Add layer") },
                            leadingIcon = { FoxIcon(Ico.Layers, iconSize = 20.dp) },
                            onClick = { menu = false; state.addLayer() },
                        )
                        DropdownMenuItem(
                            text = { Text("Import image") },
                            leadingIcon = { FoxIcon(Ico.Image, iconSize = 20.dp) },
                            enabled = state.canImportImage,
                            onClick = { menu = false; state.panel = Panel.None; state.imagePickRequested = true },
                        )
                    }
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
                        if (l.clip) FoxIcon(Ico.ClipArrow, Modifier.padding(start = 4.dp), tint = cs.primary, iconSize = 14.dp)   // clipped to the layer below
                        Text(
                            l.name,
                            Modifier.padding(start = 6.dp).weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (sel) cs.onPrimaryContainer else cs.onSurface,
                        )
                        var fxMenu by remember { mutableStateOf(false) }
                        Box {
                            Box(
                                Modifier.size(30.dp).clip(CircleShape).clickable { fxMenu = true },
                                contentAlignment = Alignment.Center,
                            ) { FoxIcon(Ico.More, tint = if (sel) cs.onPrimaryContainer else cs.onSurfaceVariant, iconSize = 18.dp) }
                            DropdownMenu(expanded = fxMenu, onDismissRequest = { fxMenu = false }) {
                                FxMenuItems(state, FxTarget.Lay(l.id), state.drawTracks.firstOrNull { t -> t.layers.any { it.id == l.id } }?.id ?: state.activeTrack) { fxMenu = false }
                            }
                        }
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
    LaunchedEffect(state.editMenuTick) { if (state.editMenuTick > 0) menuOpen = true }   // "Edit Track" in a row / layer menu
    state.fxTarget?.let { FxDialog(state, it) }
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
        
        AnimatedVisibility(state.placing != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) { ImagePlacePanel(state) }
        
        AnimatedVisibility(
            !state.transformOpen && state.placing == null,
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
        Section("GROUPS")
        Item("Group Active Drawing Layer") { state.groupActiveTrack() }
        Item("Group Selected Tracks", state.selCount > 0) { state.groupSelectedTracks() }
        Item("Ungroup Selected Tracks", state.selectionInGroup) { state.ungroupSelectedTracks() }
        Item("Rename Active Drawing Layer") { state.renameTarget = RenameTarget.Draw(state.activeTrack) }
        for (m in RETIME_MODES) {
            Item("Re-timing: ${retimeName(m)}", state.selectedGroup >= 0) { state.setGroupFill(state.selectedGroup, m) }
        }

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
    Brush, Deform, Bone, Warp, Layers, Chevron, Undo, Redo, ToStart, ToEnd, Prev, Next,
    Play, Pause, Props, Plus, Eye, EyeOff, Eraser, Smudge, Blur,
    Lock, Unlock, Volume, VolumeOff, Music, Export, More, Transform, Image, Blend, ClipArrow,
}

/** Everything the exporter needs for blend modes + clipping (see NativeExporter.nativeSetFx). */
class ExportFx(
    val rowMode: IntArray, val rowOpacity: FloatArray, val rowClip: BooleanArray,
    val layerIds: IntArray, val layerMode: IntArray, val layerOpacity: FloatArray, val layerClip: BooleanArray,
    /** Group folders: group id of every row (-1 = none), then per group its id / blend / opacity / clip. */
    val rowGroup: IntArray, val groupIds: IntArray, val groupMode: IntArray, val groupOpacity: FloatArray, val groupClip: BooleanArray,
) {
    fun applyTo(handle: Long) {
        val n = fox.foxiru.foxcat.fox2d.jnicallers.NativeExporter
        n.nativeSetFx(handle, rowMode, rowOpacity, rowClip, layerIds, layerMode, layerOpacity, layerClip)
        n.nativeSetGroupFx(handle, rowGroup, groupIds, groupMode, groupOpacity, groupClip)
    }
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
            Ico.Image -> {
                drawRoundRect(tint, o(4f, 5f), Size(16f * s, 14f * s), CornerRadius(2.5f * s), style = line)
                drawCircle(tint, 1.8f * s, o(9f, 10f))
                drawPath(Path().apply { m(5f, 18f); l(10f, 13f); l(13.5f, 16.5f); l(16f, 14f); l(19f, 17.5f) }, tint, style = thin)
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
            Ico.Warp -> {
                // push-pin (Puppet Warp pins): outlined head + flange, needle toward the lower left
                drawPath(
                    Path().apply {
                        m(15.1f, 3.6f); l(14.3f, 6.0f); l(10.4f, 9.4f); l(7.2f, 10.6f); l(13.7f, 17.2f)
                        l(15.2f, 13.6f); l(17.8f, 9.7f); l(20.3f, 8.7f); close()
                    },
                    tint, style = line,
                )
                drawLine(tint, o(9.6f, 14.4f), o(4f, 20f), 2f * s, StrokeCap.Round)
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
            Ico.Blend -> {
                drawCircle(tint, 6.2f * s, o(9.2f, 12f), style = line)
                drawCircle(tint, 6.2f * s, o(14.8f, 12f), style = line)
            }
            Ico.ClipArrow -> {
                drawPath(Path().apply { m(6f, 4f); l(6f, 16f); l(18f, 16f) }, tint, style = line)
                drawPath(Path().apply { m(14f, 12f); l(18f, 16f); l(14f, 20f) }, tint, style = line)
            }
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
