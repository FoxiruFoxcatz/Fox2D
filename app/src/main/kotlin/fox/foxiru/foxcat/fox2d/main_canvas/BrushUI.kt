package fox.foxiru.foxcat.fox2d.main_canvas

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import fox.foxiru.foxcat.fox2d.jnicallers.NativeCanvas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.WeakHashMap
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

// ======================================================================================================
// Brush Studio: panel for the native brush engine (FoxBrushEngine.h / .cpp). Same layout and widgets as the KeyGraph Editor
// (KeyGraphUI.kt): header bar with box menus / [preset list | live preview] / footer sliders, built from FxWidget.kt.
//   - Tip: shape (round, square, diamond, star, chalk, bristle), hardness, roundness, angle (+ follow the stroke), spacing
//   - Dynamics: pressure + speed -> size / opacity, pressure curve, simulated pressure for finger input
//   - Stroke: flow / opacity (build-up or capped like a marker), start + end taper, lazy-rope stabiliser + smoothing
//   - Jitter: scatter, dabs per stamp, size / opacity / angle / hue / saturation / brightness jitter
//   - Texture: grain (fixed to the paper or moving with the dab) and wet edge
// The preview is the engine's own CPU twin (NativeCanvas.brushPreview), so it shows the real spacing, taper and jitter.
// Drag on the preview to scribble a test stroke with the finger.
//
// Wiring (see the .patch files): EditorState.brushStudioParams() below feeds the stroke listener, BrushStudioPanel sits in
// the Dock next to the KeyGraph Editor, BrushStudioToolBtn goes on the rail next to KeyGraphToolBtn.
// Brush settings are tool state, not drawing state: they are not part of undo; every stroke stores its own parameter array.
// ======================================================================================================

// ------------------------------------------------------------------------------------------------ parameter layout

/** Index of every engine parameter in the float array sent to native. MUST match enum P in FoxBrushEngine.h. */
internal object BP {
    const val SIZE = 0
    const val TIP = 1
    const val HARDNESS = 2
    const val ROUNDNESS = 3
    const val ANGLE = 4
    const val ANGLE_FOLLOW = 5
    const val SPACING = 6
    const val FLOW = 7
    const val OPACITY = 8
    const val BUILDUP = 9
    const val SIZE_PRESS = 10
    const val OPAC_PRESS = 11
    const val PRESS_GAMMA = 12
    const val SIZE_SPEED = 13
    const val OPAC_SPEED = 14
    const val MIN_SIZE = 15
    const val TAPER_START = 16
    const val TAPER_END = 17
    const val SCATTER = 18
    const val SIZE_JIT = 19
    const val OPAC_JIT = 20
    const val ANGLE_JIT = 21
    const val HUE_JIT = 22
    const val SAT_JIT = 23
    const val BRI_JIT = 24
    const val COUNT = 25
    const val GRAIN = 26
    const val GRAIN_SCALE = 27
    const val GRAIN_PAPER = 28
    const val WET_EDGE = 29
    const val STABILIZER = 30
    const val SMOOTHING = 31
    const val BRISTLES = 32
    const val SEED = 33
    const val SIM_PRESSURE = 34
    const val N = 35
}

/** Order == enum Tip in FoxBrushEngine.h. */
internal enum class BrushTip(val label: String) {
    Round("Round"), Square("Square"), Diamond("Diamond"), Star("Star"), Chalk("Chalk"), Bristle("Bristle"),
}

/** Everything a brush is except its size (that stays [EditorState.brushSize], so the other panels keep working) and its seed. */
@Immutable
internal data class BrushParams(
    val tip: Int = 0,
    val hardness: Float = 0.85f,
    val roundness: Float = 1f,
    val angle: Float = 0f,
    val angleFollow: Float = 0f,
    val spacing: Float = 0.12f,
    val flow: Float = 1f,
    val opacity: Float = 1f,
    val buildup: Boolean = true,
    val sizePress: Float = 0.6f,
    val opacPress: Float = 0f,
    val pressGamma: Float = 1f,
    val sizeSpeed: Float = 0f,
    val opacSpeed: Float = 0f,
    val minSize: Float = 0.15f,
    val taperStart: Float = 0f,
    val taperEnd: Float = 0f,
    val scatter: Float = 0f,
    val sizeJit: Float = 0f,
    val opacJit: Float = 0f,
    val angleJit: Float = 0f,
    val hueJit: Float = 0f,
    val satJit: Float = 0f,
    val briJit: Float = 0f,
    val count: Int = 1,
    val grain: Float = 0f,
    val grainScale: Float = 6f,
    val grainPaper: Boolean = true,
    val wetEdge: Float = 0f,
    val stabilizer: Float = 0f,
    val smoothing: Float = 0f,
    val bristles: Int = 12,
    val simPressure: Boolean = false,
) {
    /** [seed] = per-stroke random seed (the stroke stores the array, so a replay draws the same jitter). */
    fun toArray(size: Float, seed: Int): FloatArray {
        val a = FloatArray(BP.N)
        a[BP.SIZE] = size
        a[BP.TIP] = tip.toFloat()
        a[BP.HARDNESS] = hardness
        a[BP.ROUNDNESS] = roundness
        a[BP.ANGLE] = angle
        a[BP.ANGLE_FOLLOW] = angleFollow
        a[BP.SPACING] = spacing
        a[BP.FLOW] = flow
        a[BP.OPACITY] = opacity
        a[BP.BUILDUP] = if (buildup) 1f else 0f
        a[BP.SIZE_PRESS] = sizePress
        a[BP.OPAC_PRESS] = opacPress
        a[BP.PRESS_GAMMA] = pressGamma
        a[BP.SIZE_SPEED] = sizeSpeed
        a[BP.OPAC_SPEED] = opacSpeed
        a[BP.MIN_SIZE] = minSize
        a[BP.TAPER_START] = taperStart
        a[BP.TAPER_END] = taperEnd
        a[BP.SCATTER] = scatter
        a[BP.SIZE_JIT] = sizeJit
        a[BP.OPAC_JIT] = opacJit
        a[BP.ANGLE_JIT] = angleJit
        a[BP.HUE_JIT] = hueJit
        a[BP.SAT_JIT] = satJit
        a[BP.BRI_JIT] = briJit
        a[BP.COUNT] = count.toFloat()
        a[BP.GRAIN] = grain
        a[BP.GRAIN_SCALE] = grainScale
        a[BP.GRAIN_PAPER] = if (grainPaper) 1f else 0f
        a[BP.WET_EDGE] = wetEdge
        a[BP.STABILIZER] = stabilizer
        a[BP.SMOOTHING] = smoothing
        a[BP.BRISTLES] = bristles.toFloat()
        a[BP.SEED] = seed.toFloat()
        a[BP.SIM_PRESSURE] = if (simPressure) 1f else 0f
        return a
    }

    /** One line of text a user can copy / paste to share a brush. Round trip: [decode]. */
    fun encode(size: Float): String = PREFIX + toArray(size, 1).joinToString(",") { "%.4g".format(java.util.Locale.ROOT, it) }

    companion object {
        private const val PREFIX = "foxbrush1:"

        fun fromArray(a: FloatArray): BrushParams {
            fun f(i: Int, d: Float) = if (i < a.size && a[i].isFinite()) a[i] else d
            val d = BrushParams()
            return BrushParams(
                tip = f(BP.TIP, 0f).roundToInt().coerceIn(0, BrushTip.values().size - 1),
                hardness = f(BP.HARDNESS, d.hardness).coerceIn(0f, 1f),
                roundness = f(BP.ROUNDNESS, d.roundness).coerceIn(0.05f, 1f),
                angle = f(BP.ANGLE, d.angle).coerceIn(-360f, 360f),
                angleFollow = f(BP.ANGLE_FOLLOW, d.angleFollow).coerceIn(0f, 1f),
                spacing = f(BP.SPACING, d.spacing).coerceIn(0.02f, 2f),
                flow = f(BP.FLOW, d.flow).coerceIn(0.01f, 1f),
                opacity = f(BP.OPACITY, d.opacity).coerceIn(0.01f, 1f),
                buildup = f(BP.BUILDUP, 1f) > 0.5f,
                sizePress = f(BP.SIZE_PRESS, d.sizePress).coerceIn(0f, 1f),
                opacPress = f(BP.OPAC_PRESS, d.opacPress).coerceIn(0f, 1f),
                pressGamma = f(BP.PRESS_GAMMA, d.pressGamma).coerceIn(0.3f, 3f),
                sizeSpeed = f(BP.SIZE_SPEED, d.sizeSpeed).coerceIn(-1f, 1f),
                opacSpeed = f(BP.OPAC_SPEED, d.opacSpeed).coerceIn(-1f, 1f),
                minSize = f(BP.MIN_SIZE, d.minSize).coerceIn(0f, 1f),
                taperStart = f(BP.TAPER_START, d.taperStart).coerceIn(0f, 128f),
                taperEnd = f(BP.TAPER_END, d.taperEnd).coerceIn(0f, 128f),
                scatter = f(BP.SCATTER, d.scatter).coerceIn(0f, 3f),
                sizeJit = f(BP.SIZE_JIT, d.sizeJit).coerceIn(0f, 1f),
                opacJit = f(BP.OPAC_JIT, d.opacJit).coerceIn(0f, 1f),
                angleJit = f(BP.ANGLE_JIT, d.angleJit).coerceIn(0f, 180f),
                hueJit = f(BP.HUE_JIT, d.hueJit).coerceIn(0f, 1f),
                satJit = f(BP.SAT_JIT, d.satJit).coerceIn(0f, 1f),
                briJit = f(BP.BRI_JIT, d.briJit).coerceIn(0f, 1f),
                count = f(BP.COUNT, 1f).roundToInt().coerceIn(1, 8),
                grain = f(BP.GRAIN, d.grain).coerceIn(0f, 1f),
                grainScale = f(BP.GRAIN_SCALE, d.grainScale).coerceIn(1f, 64f),
                grainPaper = f(BP.GRAIN_PAPER, 1f) > 0.5f,
                wetEdge = f(BP.WET_EDGE, d.wetEdge).coerceIn(0f, 1f),
                stabilizer = f(BP.STABILIZER, d.stabilizer).coerceIn(0f, 64f),
                smoothing = f(BP.SMOOTHING, d.smoothing).coerceIn(0f, 1f),
                bristles = f(BP.BRISTLES, 12f).roundToInt().coerceIn(2, 32),
                simPressure = f(BP.SIM_PRESSURE, 0f) > 0.5f,
            )
        }

        /** (parameters, size) or null when [s] is not a brush string. */
        fun decode(s: String): Pair<BrushParams, Float>? {
            val t = s.trim()
            if (!t.startsWith(PREFIX)) return null
            val v = t.removePrefix(PREFIX).split(',').map { it.trim().toFloatOrNull() ?: return null }
            if (v.size < BP.N) return null
            val a = v.toFloatArray()
            return fromArray(a) to a[BP.SIZE].coerceIn(1f, 64f)
        }
    }
}

// ------------------------------------------------------------------------------------------------ presets

@Immutable
internal data class BrushPreset(val name: String, val size: Float, val p: BrushParams, val user: Boolean = false)

private val BuiltinPresets = listOf(
    BrushPreset("Soft Round", 16f, BrushParams(hardness = 0.35f, spacing = 0.08f, sizePress = 0.6f)),
    BrushPreset("Hard Round", 10f, BrushParams(hardness = 1f, spacing = 0.1f, sizePress = 0.5f)),
    BrushPreset(
        "Ink Pen", 8f,
        BrushParams(hardness = 1f, spacing = 0.06f, sizePress = 1f, minSize = 0.1f, pressGamma = 0.8f, taperStart = 10f, taperEnd = 14f, stabilizer = 6f, smoothing = 0.3f, simPressure = true),
    ),
    BrushPreset(
        "Pencil", 6f,
        BrushParams(hardness = 0.6f, spacing = 0.08f, flow = 0.6f, opacity = 0.9f, sizePress = 0.3f, opacPress = 0.6f, grain = 0.7f, grainScale = 3f),
    ),
    BrushPreset(
        "Marker", 22f,
        BrushParams(tip = 1, hardness = 1f, roundness = 0.6f, angle = -30f, spacing = 0.1f, opacity = 0.7f, buildup = false, sizePress = 0f),
    ),
    BrushPreset(
        "Calligraphy", 20f,
        BrushParams(tip = 1, hardness = 1f, roundness = 0.22f, angle = 45f, spacing = 0.04f, sizePress = 0.2f, stabilizer = 4f, smoothing = 0.2f),
    ),
    BrushPreset(
        "Chalk", 26f,
        BrushParams(tip = 4, hardness = 0.6f, spacing = 0.12f, sizePress = 0.4f, opacJit = 0.2f, grain = 0.5f, grainScale = 4f),
    ),
    BrushPreset(
        "Charcoal", 30f,
        BrushParams(tip = 4, hardness = 0.5f, spacing = 0.08f, flow = 0.5f, sizePress = 0.3f, opacPress = 0.8f, scatter = 0.08f, angleJit = 30f, grain = 0.9f, grainScale = 2.5f),
    ),
    BrushPreset(
        "Watercolor", 36f,
        BrushParams(hardness = 0.5f, spacing = 0.08f, opacity = 0.55f, buildup = false, sizePress = 0.3f, grain = 0.25f, grainScale = 14f, wetEdge = 0.85f, stabilizer = 8f, smoothing = 0.3f),
    ),
    BrushPreset("Airbrush", 40f, BrushParams(hardness = 0f, spacing = 0.05f, flow = 0.08f, sizePress = 0f, opacPress = 0.5f)),
    BrushPreset(
        "Flat Bristle", 34f,
        BrushParams(tip = 5, hardness = 0.9f, roundness = 0.3f, angle = 90f, angleFollow = 1f, spacing = 0.06f, sizePress = 0.3f, opacJit = 0.15f, grain = 0.2f, bristles = 16),
    ),
    BrushPreset(
        "Star Stamp", 28f,
        BrushParams(tip = 3, hardness = 1f, spacing = 1.1f, sizePress = 0f, scatter = 0.3f, sizeJit = 0.3f, angleJit = 180f, hueJit = 0.5f),
    ),
    BrushPreset(
        "Splatter", 12f,
        BrushParams(hardness = 0.9f, spacing = 0.5f, sizePress = 0.2f, scatter = 1.6f, sizeJit = 0.8f, opacJit = 0.4f, count = 4),
    ),
)

// ------------------------------------------------------------------------------------------------ state

/** UI-only state of the Brush Studio (not in undo history, not saved). */
@Stable
internal class BrushStudioUi {
    var open by mutableStateOf(false)
    /** false = the classic capsule pen. The Studio switches it on when it is opened; it stays on after the panel closes. */
    var engineOn by mutableStateOf(false)
    var p by mutableStateOf(BuiltinPresets[0].p)
    var selected by mutableStateOf<String?>(BuiltinPresets[0].name)
    /** The selected preset has been edited. */
    var dirty by mutableStateOf(false)
    val user = mutableStateListOf<BrushPreset>()
    var list by mutableStateOf(true)
    var big by mutableStateOf(false)
    var bg by mutableIntStateOf(0)          // 0 paper, 1 dark, 2 checker
    /** Scribble pad: quadruples (x px, y px, pressure, ms). null = the built-in test stroke. */
    var pad by mutableStateOf<FloatArray?>(null)
    internal val thumbs = HashMap<String, ImageBitmap>()

    val all: List<BrushPreset> get() = BuiltinPresets + user

    fun edit(f: BrushParams.() -> BrushParams) { p = p.f(); dirty = true }

    fun apply(pr: BrushPreset, state: EditorState) {
        p = pr.p
        state.brushSize = pr.size.coerceIn(1f, 64f)
        selected = pr.name
        dirty = false
        engineOn = true
    }

    fun saveNew(state: EditorState) {
        var n = user.size + 1
        while (all.any { it.name == "Custom $n" }) n++
        val pr = BrushPreset("Custom $n", state.brushSize, p, user = true)
        user.add(pr)
        selected = pr.name
        dirty = false
    }
}

private val bsHolders = WeakHashMap<EditorState, BrushStudioUi>()

/** The Brush Studio state of this editor, kept OUTSIDE EditorState (a weak side table), like the KeyGraph state. */
internal val EditorState.brushStudio: BrushStudioUi
    get() = synchronized(bsHolders) { bsHolders.getOrPut(this) { BrushStudioUi() } }

/**
 * Engine parameters for the stroke that starts now (fresh random seed, current size), or null while the classic pen is active.
 * Called once per stroke by the gesture listener; the stroke keeps the array so replays match.
 */
internal fun EditorState.brushStudioParams(): FloatArray? {
    val ui = brushStudio
    if (!ui.engineOn) return null
    return ui.p.toArray(brushSize, Random.nextInt(1, 1 shl 24))
}

// ------------------------------------------------------------------------------------------------ icon / rail button

/** A wavy stroke with three dabs above it (the spacing). */
@Composable
internal fun BrushStudioIcon(modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant, iconSize: Dp = 24.dp) {
    val accent = MaterialTheme.colorScheme.tertiary
    Canvas(modifier.size(iconSize)) {
        val u = size.minDimension / 24f
        val path = Path().apply {
            moveTo(3f * u, 19f * u)
            cubicTo(8f * u, 9f * u, 12f * u, 24f * u, 21f * u, 12f * u)
        }
        drawPath(path, tint, style = Stroke(width = 2.6f * u, cap = StrokeCap.Round))
        drawCircle(accent, 1.7f * u, Offset(5f * u, 5.5f * u))
        drawCircle(accent, 2.3f * u, Offset(11f * u, 5f * u))
        drawCircle(accent, 3f * u, Offset(18.5f * u, 5.5f * u))
    }
}

/** Rail button: same look as ToolBtn / KeyGraphToolBtn, opens / closes the Brush Studio (and turns the engine on). */
@Composable
internal fun BrushStudioToolBtn(state: EditorState, size: Dp = 36.dp) {
    val cs = MaterialTheme.colorScheme
    val ui = state.brushStudio
    val bg by animateColorAsState(if (ui.open) cs.primaryContainer else Color.Transparent, label = "bsBg")
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable {
                ui.open = !ui.open
                if (ui.open) {
                    ui.engineOn = true
                    state.tool = Tool.Brush; state.panel = Panel.None    // opened from Deform / Bone / Layers: hand the canvas back to the pen
                    state.keyGraph.open = false; state.transformOpen = false
                }
            }
            .semantics { contentDescription = "Brush Studio" },
        contentAlignment = Alignment.Center,
    ) { BrushStudioIcon(tint = if (ui.open) cs.onPrimaryContainer else cs.onSurfaceVariant) }
}

// ------------------------------------------------------------------------------------------------ small helpers

private fun pct(v: Float) = "${(v * 100).roundToInt()}%"

@Composable
private fun BsNum(label: String, value: Float, range: ClosedFloatingPointRange<Float>, fmt: String = "%.2f", onChange: (Float) -> Unit) =
    FxSlider(label, value, range, fmt.format(java.util.Locale.ROOT, value), onChange, {})

@Composable
private fun BsPct(label: String, value: Float, range: ClosedFloatingPointRange<Float> = 0f..1f, onChange: (Float) -> Unit) =
    FxSlider(label, value, range, pct(value), onChange, {})

/** Small square button floating on top of the preview (same look as KeyGraph's GKFloatBtn). */
@Composable
private fun BsFloatBtn(text: String, selected: Boolean = false, onClick: () -> Unit) {
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

/** Preview pixels per brush unit: a fixed look, shrunk when the brush would not fit the picture. */
private fun previewUnit(size: Float, heightPx: Int, density: Float): Float = min(density * 0.9f, 0.8f * heightPx / size.coerceAtLeast(1f))

/**
 * Renders [params] with the engine's CPU twin off the UI thread. Waits 35 ms so a slider drag only renders where it rests;
 * a newer key cancels the older render. null until the first picture is ready (or when libfoxcanvas lacks the brush engine).
 */
@Composable
private fun rememberBrushPicture(params: FloatArray, argb: Int, w: Int, h: Int, unit: Float, samples: FloatArray?): ImageBitmap? {
    var img by remember { mutableStateOf<ImageBitmap?>(null) }
    val pk = params.contentHashCode()
    val sk = samples?.contentHashCode() ?: 0
    LaunchedEffect(pk, argb, w, h, unit, sk) {
        if (w < 8 || h < 8) return@LaunchedEffect
        delay(35)
        val px = try {
            withContext(Dispatchers.Default) { NativeCanvas.brushPreview(params, argb, w, h, unit, samples ?: FloatArray(0)) }
        } catch (e: UnsatisfiedLinkError) {
            return@LaunchedEffect   // native library without the brush engine
        }
        if (px.size == w * h) img = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    return img
}

// ------------------------------------------------------------------------------------------------ panel

@Composable
internal fun BrushStudioPanel(state: EditorState) {
    val cs = MaterialTheme.colorScheme
    val ui = state.brushStudio
    Surface(
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
        color = Color.Transparent,          // the dock Surface around it already carries UI_ALPHA
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            BsHeader(state, ui)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp).height(if (ui.big) 200.dp else 124.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (ui.list) BsPresetList(state, ui, Modifier.width(140.dp).fillMaxHeight())
                BsPreview(state, ui, Modifier.weight(1f).fillMaxHeight())
            }
            BsFooter(state, ui)
        }
    }
}

/** Menu bar: engine switch, one box menu per job, undo / redo and close on the right. */
@Composable
private fun BsHeader(state: EditorState, ui: BrushStudioUi) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(34.dp).background(cs.surfaceContainerHigh.copy(alpha = UI_ALPHA)).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        BrushStudioIcon(Modifier.padding(end = 4.dp), iconSize = 20.dp, tint = cs.primary)
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            FxTab(if (ui.engineOn) "Engine on" else "Engine off", selected = ui.engineOn) { ui.engineOn = !ui.engineOn }
            BsBrushMenu(state, ui)
            BsTipMenu(ui)
            BsDynamicsMenu(ui)
            BsStrokeMenu(ui)
            BsJitterMenu(ui)
            BsTextureMenu(ui)
        }
        FxTBtn("↶", enabled = state.canUndo) { state.undo() }
        FxTBtn("↷", enabled = state.canRedo) { state.redo() }
        FxTBtn("✕") { ui.open = false }
    }
}

// ------------------------------------------------------------------------------------------------ menus

@Composable
private fun BsBrushMenu(state: EditorState, ui: BrushStudioUi) {
    val clip = LocalClipboardManager.current
    FxMenu("Brush") { close ->
        val cur = ui.all.firstOrNull { it.name == ui.selected }
        FxSection("Engine")
        FxCheck("Use the brush engine (Pen + Eraser)", ui.engineOn) { ui.engineOn = it }
        FxHint("Off = the classic round pen. Every stroke keeps its own settings.")
        FxSection("Preset")
        FxGrid(listOf(0, 1, 2, 3), 2) { i, m ->
            when (i) {
                0 -> FxChip("Save as new", m) { ui.saveNew(state); close() }
                1 -> FxChip("Reset edits", m, enabled = cur != null && ui.dirty) { cur?.let { ui.apply(it, state) }; close() }
                2 -> FxChip("Copy settings", m) { clip.setText(AnnotatedString(ui.p.encode(state.brushSize))); close() }
                else -> FxChip("Paste settings", m) {
                    BrushParams.decode(clip.getText()?.text.orEmpty())?.let { (bp, sz) -> ui.p = bp; state.brushSize = sz; ui.selected = null; ui.dirty = true; ui.engineOn = true }
                    close()
                }
            }
        }
        FxAction("Delete preset", enabled = cur?.user == true) {
            cur?.let { ui.user.remove(it) }
            ui.selected = null
            close()
        }
        FxSection("Preview")
        FxGrid(listOf("Paper", "Dark", "Checker"), 3) { n, m ->
            val i = listOf("Paper", "Dark", "Checker").indexOf(n)
            FxChip(n, m, selected = ui.bg == i) { ui.bg = i }
        }
        FxCheck("Preset list", ui.list) { ui.list = it }
        FxCheck("Taller preview", ui.big) { ui.big = it }
        FxHint("Drag on the preview to scribble a test stroke.")
    }
}

@Composable
private fun BsTipMenu(ui: BrushStudioUi) {
    FxMenu("Tip") {
        val p = ui.p
        FxSection("Shape")
        FxGrid(BrushTip.values().toList(), 3) { t, m -> FxChip(t.label, m, selected = p.tip == t.ordinal) { ui.edit { copy(tip = t.ordinal) } } }
        BsPct("Hardness", p.hardness) { ui.edit { copy(hardness = it) } }
        BsPct("Round", p.roundness, 0.05f..1f) { ui.edit { copy(roundness = it) } }
        BsNum("Angle", p.angle, -180f..180f, "%.0f°") { ui.edit { copy(angle = it) } }
        BsPct("Follow", p.angleFollow) { ui.edit { copy(angleFollow = it) } }
        if (p.tip == BrushTip.Bristle.ordinal) BsNum("Bristles", p.bristles.toFloat(), 2f..32f, "%.0f") { ui.edit { copy(bristles = it.roundToInt()) } }
        FxSection("Spacing")
        BsPct("Spacing", p.spacing, 0.02f..1.5f) { ui.edit { copy(spacing = it) } }
        FxHint("Spacing is a share of the dab width. Follow turns the tip along the stroke.")
    }
}

@Composable
private fun BsDynamicsMenu(ui: BrushStudioUi) {
    FxMenu("Dynamics") {
        val p = ui.p
        FxSection("Pressure")
        BsPct("→ Size", p.sizePress) { ui.edit { copy(sizePress = it) } }
        BsPct("→ Opac", p.opacPress) { ui.edit { copy(opacPress = it) } }
        BsNum("Curve", p.pressGamma, 0.3f..3f) { ui.edit { copy(pressGamma = it) } }
        BsPct("Min size", p.minSize) { ui.edit { copy(minSize = it) } }
        FxCheck("Simulate pressure (finger)", p.simPressure) { ui.edit { copy(simPressure = it) } }
        FxSection("Speed")
        BsNum("→ Size", p.sizeSpeed, -1f..1f, "%+.2f") { ui.edit { copy(sizeSpeed = it) } }
        BsNum("→ Opac", p.opacSpeed, -1f..1f, "%+.2f") { ui.edit { copy(opacSpeed = it) } }
        FxHint("Simulated pressure = slow is heavy, fast is light. Negative speed = faster is thinner.")
    }
}

@Composable
private fun BsStrokeMenu(ui: BrushStudioUi) {
    FxMenu("Stroke") {
        val p = ui.p
        FxSection("Strength")
        BsPct("Flow", p.flow, 0.01f..1f) { ui.edit { copy(flow = it) } }
        BsPct("Opacity", p.opacity, 0.01f..1f) { ui.edit { copy(opacity = it) } }
        FxCheck("Build-up (airbrush)", p.buildup) { ui.edit { copy(buildup = it) } }
        FxHint("Off: opacity caps the whole stroke, overlaps never get darker (marker).")
        FxSection("Taper")
        BsNum("Start", p.taperStart, 0f..64f, "%.0f") { ui.edit { copy(taperStart = it) } }
        BsNum("End", p.taperEnd, 0f..64f, "%.0f") { ui.edit { copy(taperEnd = it) } }
        FxHint("The end taper is drawn after the finger lifts.")
        FxSection("Stabilizer")
        BsNum("Rope", p.stabilizer, 0f..64f, "%.0f") { ui.edit { copy(stabilizer = it) } }
        BsPct("Smooth", p.smoothing) { ui.edit { copy(smoothing = it) } }
    }
}

@Composable
private fun BsJitterMenu(ui: BrushStudioUi) {
    FxMenu("Jitter") {
        val p = ui.p
        FxSection("Position")
        BsNum("Scatter", p.scatter, 0f..3f) { ui.edit { copy(scatter = it) } }
        BsNum("Count", p.count.toFloat(), 1f..8f, "%.0f") { ui.edit { copy(count = it.roundToInt()) } }
        FxSection("Per dab")
        BsPct("Size", p.sizeJit) { ui.edit { copy(sizeJit = it) } }
        BsPct("Opacity", p.opacJit) { ui.edit { copy(opacJit = it) } }
        BsNum("Angle", p.angleJit, 0f..180f, "%.0f°") { ui.edit { copy(angleJit = it) } }
        FxSection("Colour")
        BsPct("Hue", p.hueJit) { ui.edit { copy(hueJit = it) } }
        BsPct("Saturation", p.satJit) { ui.edit { copy(satJit = it) } }
        BsPct("Bright", p.briJit) { ui.edit { copy(briJit = it) } }
    }
}

@Composable
private fun BsTextureMenu(ui: BrushStudioUi) {
    FxMenu("Texture") {
        val p = ui.p
        FxSection("Grain")
        BsPct("Grain", p.grain) { ui.edit { copy(grain = it) } }
        BsNum("Size", p.grainScale, 1f..64f, "%.0f") { ui.edit { copy(grainScale = it) } }
        FxCheck("Grain fixed to the paper", p.grainPaper) { ui.edit { copy(grainPaper = it) } }
        FxSection("Edge")
        BsPct("Wet edge", p.wetEdge) { ui.edit { copy(wetEdge = it) } }
        FxHint("Wet edge pools the colour at the rim; best with a capped (non build-up) stroke.")
    }
}

// ------------------------------------------------------------------------------------------------ preset list

/** Left column like the KeyGraph channel list: bar + name of the selected preset, a tiny stroke of each, "+ Save" below. */
@Composable
private fun BsPresetList(state: EditorState, ui: BrushStudioUi, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val ink = cs.onSurface.toArgb()
    Column(modifier.clip(RoundedCornerShape(8.dp)).background(cs.surfaceContainerLowest)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            for (pr in ui.all) {
                val sel = ui.selected == pr.name
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .background(if (sel) cs.primary.copy(alpha = 0.20f) else Color.Transparent)
                        .clickable { ui.apply(pr, state) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(if (sel) cs.primary else Color.Transparent))
                    Text(
                        pr.name + if (sel && ui.dirty) " •" else "", style = MaterialTheme.typography.labelMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, color = cs.onSurface,
                        modifier = Modifier.weight(1f).padding(start = 6.dp, end = 2.dp),
                    )
                    BsThumb(ui, pr, ink, Modifier.width(40.dp).height(20.dp))
                    Box(Modifier.width(4.dp))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(28.dp).background(cs.surfaceContainerHigh).clickable { ui.saveNew(state) }, contentAlignment = Alignment.Center) {
            Text("+ Save", style = MaterialTheme.typography.labelMedium, color = cs.primary)
        }
    }
}

/** One preset's stroke, rendered once by the engine and cached per (preset, colour, size). */
@Composable
private fun BsThumb(ui: BrushStudioUi, pr: BrushPreset, argb: Int, modifier: Modifier) {
    val density = LocalDensity.current.density
    val w = (40 * density).roundToInt()
    val h = (20 * density).roundToInt()
    val key = "${pr.name}|${pr.p.hashCode()}|${pr.size}|$argb|$w"
    var img by remember(key) { mutableStateOf(ui.thumbs[key]) }
    LaunchedEffect(key) {
        if (img != null) return@LaunchedEffect
        val arr = pr.p.toArray(pr.size, 7)
        val px = try {
            withContext(Dispatchers.Default) { NativeCanvas.brushPreview(arr, argb, w, h, previewUnit(pr.size, h, density * 0.55f)) }
        } catch (e: UnsatisfiedLinkError) {
            return@LaunchedEffect
        }
        if (px.size == w * h) {
            val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
            ui.thumbs[key] = bmp
            img = bmp
        }
    }
    Canvas(modifier) { img?.let { drawImage(it) } }
}

// ------------------------------------------------------------------------------------------------ preview

@Composable
private fun BsPreview(state: EditorState, ui: BrushStudioUi, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current.density
    var box by remember { mutableStateOf(IntSize.Zero) }
    val w = min(box.width, 1400)
    val h = box.height
    val arr = remember(ui.p, state.brushSize) { ui.p.toArray(state.brushSize, 1) }   // fixed seed: the preview does not flicker while you edit
    val img = rememberBrushPicture(arr, state.color.toArgb(), w, h, previewUnit(state.brushSize, h, density), ui.pad)

    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .onSizeChanged { box = it }
            .background(if (ui.bg == 1) Color(0xFF1E1F22) else Color(0xFFF2F2F2))
            .pointerInput(ui) {
                // scribble pad: the finger's own samples (position, pressure, time) go through the same engine
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val t0 = down.uptimeMillis
                    val s = ArrayList<Float>(512)
                    fun add(x: Float, y: Float, p: Float, t: Long) {
                        val n = s.size
                        if (n >= 4) { val dx = x - s[n - 4]; val dy = y - s[n - 3]; if (dx * dx + dy * dy < 4f) return }
                        if (n < 4 * 3000) { s.add(x); s.add(y); s.add(p); s.add(t.toFloat()) }
                    }
                    add(down.position.x, down.position.y, down.pressure, 0L)
                    do {
                        val ev = awaitPointerEvent()
                        for (c in ev.changes) {
                            for (hh in c.historical) add(hh.position.x, hh.position.y, c.pressure, (hh.uptimeMillis - t0).coerceAtLeast(0L))
                            add(c.position.x, c.position.y, c.pressure, (c.uptimeMillis - t0).coerceAtLeast(0L))
                            c.consume()
                        }
                        if (s.size >= 8) ui.pad = s.toFloatArray()
                    } while (ev.changes.any { it.pressed })
                }
            },
    ) {
        if (ui.bg == 2) Canvas(Modifier.fillMaxSize()) {
            val tile = 8.dp.toPx()
            val a = Color(0xFFD9D9D9)
            val b = Color(0xFFF4F4F4)
            val cols = (size.width / tile).toInt() + 1
            val rows = (size.height / tile).toInt() + 1
            for (r in 0 until rows) for (c in 0 until cols) drawRect(if ((r + c) % 2 == 0) a else b, Offset(c * tile, r * tile), Size(tile, tile))
        }
        img?.let { Canvas(Modifier.fillMaxSize()) { drawImage(it) } }
            ?: Text("…", Modifier.align(Alignment.Center), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        Column(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (ui.pad != null) BsFloatBtn("⌫") { ui.pad = null }
            BsFloatBtn("◐") { ui.bg = (ui.bg + 1) % 3 }
            BsFloatBtn(if (ui.big) "▾" else "▴") { ui.big = !ui.big }
        }
        val p = ui.p
        Text(
            (if (ui.pad != null) "Pad · " else "") + "${BrushTip.values()[p.tip.coerceIn(0, BrushTip.values().size - 1)].label} · ${state.brushSize.roundToInt()} · ${pct(p.spacing)} spacing" +
                (if (!p.buildup) " · capped" else ""),
            style = MaterialTheme.typography.labelSmall, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(cs.surfaceContainerHigh.copy(alpha = 0.88f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

// ------------------------------------------------------------------------------------------------ footer

/** Size + opacity sliders (side by side when there is room), the two values every stroke is judged by. */
@Composable
private fun BsFooter(state: EditorState, ui: BrushStudioUi) {
    val cs = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxWidth().background(cs.surfaceContainerHigh.copy(alpha = UI_ALPHA)).padding(horizontal = 6.dp)) {
        val size: @Composable (Modifier) -> Unit = { m ->
            Box(m) { FxSlider("Size", state.brushSize, 1f..64f, "${state.brushSize.roundToInt()}", { state.brushSize = it }, {}) }
        }
        val opacity: @Composable (Modifier) -> Unit = { m ->
            Box(m) { FxSlider("Opacity", ui.p.opacity, 0.01f..1f, pct(ui.p.opacity), { ui.edit { copy(opacity = it) } }, {}) }
        }
        if (maxWidth >= 520.dp) {
            Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                size(Modifier.weight(1f))
                opacity(Modifier.weight(1f))
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                size(Modifier.fillMaxWidth())
                opacity(Modifier.fillMaxWidth())
            }
        }
    }
}
