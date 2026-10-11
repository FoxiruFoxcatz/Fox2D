package fox.foxiru.foxcat.fox2d.main_canvas

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ======================================================================================================
// Color Studio: the Color panel of the tool rail (ToolRail.kt -> PanelHost -> ColorPanel(state)).
// Lives in its own file; EditorScreen.kt only keeps ColorSizeBtn (the rail button). The brush colour is still
// EditorState.hue / sat / bri (HSV), so nothing else in the editor changes; every other colour model below is a
// view on those three numbers.
//
//   Pick     SV pad + hue bar
//   Harmony  hue ring with the harmony shape on it (complement, split, analogous, triadic, tetradic, square,
//            mono), tap a swatch to use it, shade + tint ramps
//   Params   choose the colour model (RGB, HSV, HSL, LAB, OKLCH, CMYK), one gradient slider per channel
//   Header   before | now swatch, hex field, copy.   Footer: brush size, colour history (kept on the device).
//
// History: a colour is recorded when a drag ends / a swatch is tapped / a hex is entered. To also record the colours
// you really painted with, call state.recordColorUse() when a stroke ends (optional, see the .patch).
// All UI state sits OUTSIDE EditorState (weak side table), like the Brush Studio state.
// ======================================================================================================

// ------------------------------------------------------------------------------------------------ colour math

/** sRGB <-> CIE Lab (D65), OKLab / OKLCH, HSL, helpers. All colours here are sRGB 0..1. */
internal object CuMath {
    fun lin(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    fun gam(c: Float): Float {
        val x = c.coerceIn(0f, 1f)
        return if (x <= 0.0031308f) 12.92f * x else 1.055f * x.pow(1f / 2.4f) - 0.055f
    }

    private fun cbrt(x: Float): Float = max(x, 0f).pow(1f / 3f)
    private fun linColor(r: Float, g: Float, b: Float) = Color(gam(r), gam(g), gam(b))
    private fun inGamut(l: FloatArray) = l.all { it > -0.0003f && it < 1.0003f }

    fun toHsv(c: Color): FloatArray {
        val a = FloatArray(3)
        android.graphics.Color.colorToHSV(c.toArgb(), a)
        return a
    }

    fun hslToHsv(s: Float, l: Float): Pair<Float, Float> {
        val v = l + s * min(l, 1f - l)
        val sv = if (v <= 0f) 0f else 2f * (1f - l / v)
        return sv.coerceIn(0f, 1f) to v.coerceIn(0f, 1f)
    }

    fun hsvToHsl(s: Float, v: Float): Pair<Float, Float> {
        val l = v * (1f - s / 2f)
        val sl = if (l <= 0f || l >= 1f) 0f else (v - l) / min(l, 1f - l)
        return sl.coerceIn(0f, 1f) to l.coerceIn(0f, 1f)
    }

    /** [L 0..100, a, b]. */
    fun toLab(c: Color): FloatArray {
        val r = lin(c.red); val g = lin(c.green); val b = lin(c.blue)
        val x = (0.4124564f * r + 0.3575761f * g + 0.1804375f * b) / 0.95047f
        val y = 0.2126729f * r + 0.7151522f * g + 0.0721750f * b
        val z = (0.0193339f * r + 0.1191920f * g + 0.9503041f * b) / 1.08883f
        fun f(t: Float) = if (t > 0.008856f) cbrt(t) else 7.787f * t + 16f / 116f
        val fx = f(x); val fy = f(y); val fz = f(z)
        return floatArrayOf(116f * fy - 16f, 500f * (fx - fy), 200f * (fy - fz))
    }

    fun fromLab(L: Float, a: Float, b: Float): Color {
        val fy = (L + 16f) / 116f
        val fx = fy + a / 500f
        val fz = fy - b / 200f
        fun fi(t: Float): Float { val t3 = t * t * t; return if (t3 > 0.008856f) t3 else (t - 16f / 116f) / 7.787f }
        val x = fi(fx) * 0.95047f; val y = fi(fy); val z = fi(fz) * 1.08883f
        return linColor(
            3.2404542f * x - 1.5371385f * y - 0.4985314f * z,
            -0.9692660f * x + 1.8760108f * y + 0.0415560f * z,
            0.0556434f * x - 0.2040259f * y + 1.0572252f * z,
        )
    }

    /** [L 0..1, C, h degrees]. */
    fun toOklch(c: Color): FloatArray {
        val r = lin(c.red); val g = lin(c.green); val b = lin(c.blue)
        val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
        val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
        val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
        val L = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s
        val a = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s
        val bb = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
        var h = atan2(bb, a) * 180f / PI.toFloat()
        if (h < 0f) h += 360f
        return floatArrayOf(L, sqrt(a * a + bb * bb), h)
    }

    private fun oklchLin(L: Float, C: Float, h: Float): FloatArray {
        val rad = h * PI.toFloat() / 180f
        val a = C * cos(rad); val b = C * sin(rad)
        val l = (L + 0.3963377774f * a + 0.2158037573f * b).let { it * it * it }
        val m = (L - 0.1055613458f * a - 0.0638541728f * b).let { it * it * it }
        val s = (L - 0.0894841775f * a - 1.2914855480f * b).let { it * it * it }
        return floatArrayOf(
            4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s,
            -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s,
            -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s,
        )
    }

    /** Out of gamut -> chroma is pulled in (binary search), so L / h keep their meaning instead of clipping channels. */
    fun fromOklch(L: Float, C: Float, h: Float): Color {
        val l0 = L.coerceIn(0f, 1f)
        var lin = oklchLin(l0, C, h)
        if (!inGamut(lin)) {
            var lo = 0f; var hi = C
            repeat(14) { val mid = (lo + hi) / 2f; if (inGamut(oklchLin(l0, mid, h))) lo = mid else hi = mid }
            lin = oklchLin(l0, lo, h)
        }
        return linColor(lin[0], lin[1], lin[2])
    }
}

// ------------------------------------------------------------------------------------------------ colour models

private class CuChan(val name: String, val min: Float, val max: Float, val fmt: String)

private enum class CuModel(val label: String, val ch: List<CuChan>) {
    RGB("RGB", listOf(CuChan("R", 0f, 255f, "%.0f"), CuChan("G", 0f, 255f, "%.0f"), CuChan("B", 0f, 255f, "%.0f"))),
    HSV("HSV", listOf(CuChan("H", 0f, 360f, "%.0f°"), CuChan("S", 0f, 100f, "%.0f"), CuChan("V", 0f, 100f, "%.0f"))),
    HSL("HSL", listOf(CuChan("H", 0f, 360f, "%.0f°"), CuChan("S", 0f, 100f, "%.0f"), CuChan("L", 0f, 100f, "%.0f"))),
    LAB("LAB", listOf(CuChan("L", 0f, 100f, "%.0f"), CuChan("a", -100f, 100f, "%+.0f"), CuChan("b", -100f, 100f, "%+.0f"))),
    LCH("OKLCH", listOf(CuChan("L", 0f, 100f, "%.0f"), CuChan("C", 0f, 0.37f, "%.3f"), CuChan("H", 0f, 360f, "%.0f°"))),
    CMYK("CMYK", listOf(CuChan("C", 0f, 100f, "%.0f"), CuChan("M", 0f, 100f, "%.0f"), CuChan("Y", 0f, 100f, "%.0f"), CuChan("K", 0f, 100f, "%.0f"))),
}

/** Channel values of the brush colour in this model. [lchHue] stands in for the hue of a grey (chroma 0) in OKLCH. */
private fun CuModel.read(state: EditorState, lchHue: Float): FloatArray {
    val c = state.color
    return when (this) {
        CuModel.RGB -> floatArrayOf(c.red * 255f, c.green * 255f, c.blue * 255f)
        CuModel.HSV -> floatArrayOf(state.hue, state.sat * 100f, state.bri * 100f)
        CuModel.HSL -> { val (s, l) = CuMath.hsvToHsl(state.sat, state.bri); floatArrayOf(state.hue, s * 100f, l * 100f) }
        CuModel.LAB -> CuMath.toLab(c)
        CuModel.LCH -> { val o = CuMath.toOklch(c); floatArrayOf(o[0] * 100f, o[1], if (o[1] < 0.004f) lchHue else o[2]) }
        CuModel.CMYK -> {
            val k = 1f - max(c.red, max(c.green, c.blue))
            val d = (1f - k).coerceAtLeast(1e-6f)
            floatArrayOf((1f - c.red - k) / d * 100f, (1f - c.green - k) / d * 100f, (1f - c.blue - k) / d * 100f, k * 100f)
        }
    }
}

/** Pure: channel values -> colour (used for the slider gradients too). */
private fun CuModel.toColor(v: FloatArray): Color = when (this) {
    CuModel.RGB -> Color((v[0] / 255f).coerceIn(0f, 1f), (v[1] / 255f).coerceIn(0f, 1f), (v[2] / 255f).coerceIn(0f, 1f))
    CuModel.HSV -> Color.hsv(v[0].coerceIn(0f, 360f), (v[1] / 100f).coerceIn(0f, 1f), (v[2] / 100f).coerceIn(0f, 1f))
    CuModel.HSL -> { val (s, vv) = CuMath.hslToHsv((v[1] / 100f).coerceIn(0f, 1f), (v[2] / 100f).coerceIn(0f, 1f)); Color.hsv(v[0].coerceIn(0f, 360f), s, vv) }
    CuModel.LAB -> CuMath.fromLab(v[0], v[1], v[2])
    CuModel.LCH -> CuMath.fromOklch(v[0] / 100f, v[1], v[2])
    CuModel.CMYK -> {
        val k = (v[3] / 100f).coerceIn(0f, 1f)
        Color(
            ((1f - v[0] / 100f) * (1f - k)).coerceIn(0f, 1f),
            ((1f - v[1] / 100f) * (1f - k)).coerceIn(0f, 1f),
            ((1f - v[2] / 100f) * (1f - k)).coerceIn(0f, 1f),
        )
    }
}

/** HSV / HSL write straight into hue / sat / bri (hue survives a grey); the rest go through a colour and keep the old hue on greys. */
private fun CuModel.write(state: EditorState, v: FloatArray) {
    when (this) {
        CuModel.HSV -> { state.hue = v[0].coerceIn(0f, 360f); state.sat = (v[1] / 100f).coerceIn(0f, 1f); state.bri = (v[2] / 100f).coerceIn(0f, 1f) }
        CuModel.HSL -> {
            val (s, vv) = CuMath.hslToHsv((v[1] / 100f).coerceIn(0f, 1f), (v[2] / 100f).coerceIn(0f, 1f))
            state.hue = v[0].coerceIn(0f, 360f); state.sat = s; state.bri = vv
        }
        else -> state.setColorKeepHue(toColor(v))
    }
}

/** Like EditorState.setColor, but a grey / black keeps the current hue (so dragging back out of grey does not jump to red). */
private fun EditorState.setColorKeepHue(c: Color) {
    val h = CuMath.toHsv(c)
    if (h[1] > 0.004f && h[2] > 0.004f) hue = h[0]
    sat = h[1]
    bri = h[2]
}

// ------------------------------------------------------------------------------------------------ harmony

private data class CuHsv(val h: Float, val s: Float, val v: Float) {
    val color: Color get() = Color.hsv(h.coerceIn(0f, 360f), s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))
}

private fun wrapHue(h: Float) = ((h % 360f) + 360f) % 360f

private enum class CuHarmony(val label: String, val offs: FloatArray) {
    Complement("Complement", floatArrayOf(0f, 180f)),
    Split("Split", floatArrayOf(0f, 150f, 210f)),
    Analogous("Analogous", floatArrayOf(-30f, 0f, 30f)),
    Triadic("Triadic", floatArrayOf(0f, 120f, 240f)),
    Tetradic("Tetradic", floatArrayOf(0f, 60f, 180f, 240f)),
    Square("Square", floatArrayOf(0f, 90f, 180f, 270f)),
    Mono("Mono", floatArrayOf(0f)),
}

private fun CuHarmony.swatches(h: Float, s: Float, v: Float): List<CuHsv> =
    if (this == CuHarmony.Mono) listOf(
        CuHsv(h, s, v * 0.35f), CuHsv(h, s, v * 0.65f), CuHsv(h, s, v), CuHsv(h, s * 0.6f, v), CuHsv(h, s * 0.3f, v),
    ) else offs.map { CuHsv(wrapHue(h + it), s, v) }

// ------------------------------------------------------------------------------------------------ state

private const val CU_PREFS = "fox_color_ui"
private const val CU_HISTORY_MAX = 24
private val CuBody = 160.dp   // every tab has the same height, so the panel never jumps when you switch

private val CuDefaults = listOf(
    0xFF212121, 0xFFFFFFFF, 0xFFEF5350, 0xFFFF7043, 0xFFFFCA28, 0xFF66BB6A, 0xFF29B6F6, 0xFFAB47BC,
).map { Color(it) }

private fun cuHex(c: Color) = "%06X".format(Locale.ROOT, c.toArgb() and 0xFFFFFF)

private fun parseHex(s: String): Color? {
    val t = s.trim().removePrefix("#")
    val v = when (t.length) { 3 -> t.map { "$it$it" }.joinToString(""); 6 -> t; else -> return null }
    val n = v.toLongOrNull(16) ?: return null
    return Color((0xFF000000L or n).toInt())
}

/** UI-only state of the colour panel (not in undo history). History + last tab / model are kept in SharedPreferences. */
@Stable
private class CuUi {
    var tab by mutableIntStateOf(0)
    var model by mutableStateOf(CuModel.HSV)
    var harmony by mutableStateOf(CuHarmony.Complement)
    var lchHue by mutableFloatStateOf(0f)
    val history = mutableStateListOf<Color>().also { it.addAll(CuDefaults) }
    private var prefs: SharedPreferences? = null

    fun load(p: SharedPreferences) {
        if (prefs != null) return
        prefs = p
        p.getString("history", null)?.let { s ->
            history.clear()
            history.addAll(s.split(',').mapNotNull { parseHex(it) }.take(CU_HISTORY_MAX))
        }
        tab = p.getInt("tab", 0).coerceIn(0, 2)
        model = CuModel.values().getOrElse(p.getInt("model", 1)) { CuModel.HSV }
        harmony = CuHarmony.values().getOrElse(p.getInt("harmony", 0)) { CuHarmony.Complement }
    }

    fun save() {
        val e = prefs?.edit() ?: return
        e.putString("history", history.joinToString(",") { cuHex(it) })
        e.putInt("tab", tab)
        e.putInt("model", model.ordinal)
        e.putInt("harmony", harmony.ordinal)
        e.apply()
    }

    /** Newest first, no duplicates. */
    fun commit(c: Color) {
        val k = c.toArgb() or (0xFF shl 24)
        history.removeAll { it.toArgb() == k }
        history.add(0, Color(k))
        while (history.size > CU_HISTORY_MAX) history.removeAt(history.lastIndex)
        save()
    }

    fun clear() { history.clear(); save() }
}

private val cuHolders = WeakHashMap<EditorState, CuUi>()
private val EditorState.colorUi: CuUi get() = synchronized(cuHolders) { cuHolders.getOrPut(this) { CuUi() } }

/** Optional hook: call when a stroke ends so the colour you actually painted with lands in the history. */
internal fun EditorState.recordColorUse() = colorUi.commit(color)

// ------------------------------------------------------------------------------------------------ small widgets

/** One-finger drag / tap surface: [onDrag] gets the pointer position + the size of the box, [onDone] fires on release. */
@Composable
private fun Modifier.cuDrag(onDrag: (Offset, IntSize) -> Unit, onDone: () -> Unit): Modifier {
    val dragS by rememberUpdatedState(onDrag)
    val doneS by rememberUpdatedState(onDone)
    return this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            dragS(down.position, size)
            do {
                val ev = awaitPointerEvent()
                for (c in ev.changes) {
                    if (c.pressed) dragS(c.position, size)
                    c.consume()
                }
            } while (ev.changes.any { it.pressed })
            doneS()
        }
    }
}

@Composable
private fun CuPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) cs.primary else cs.surfaceContainerHighest, label = "cuPill")
    Box(
        Modifier.height(22.dp).clip(RoundedCornerShape(11.dp)).background(bg).clickable(onClick = onClick).padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) cs.onPrimary else cs.onSurface, maxLines = 1)
    }
}

/** Gradient-track slider: the track shows what the channel does to the colour, the thumb takes the colour under it. */
@Composable
private fun CuSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, text: String, stops: List<Color>,
    onChange: (Float) -> Unit, onDone: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val pad = with(LocalDensity.current) { 9.dp.toPx() }
    val span = (range.endInclusive - range.start).coerceAtLeast(1e-6f)
    val frac = ((value - range.start) / span).coerceIn(0f, 1f)
    Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(28.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1)
        Canvas(
            Modifier.weight(1f).fillMaxHeight().cuDrag(
                { p, sz ->
                    val f = ((p.x - pad) / (sz.width - 2f * pad).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    onChange(range.start + f * span)
                },
                onDone,
            ),
        ) {
            val th = 8.dp.toPx()
            drawRoundRect(
                brush = Brush.horizontalGradient(stops, startX = pad, endX = size.width - pad),
                topLeft = Offset(0f, (size.height - th) / 2f), size = Size(size.width, th), cornerRadius = CornerRadius(th / 2f),
            )
            val c = Offset(pad + frac * (size.width - 2f * pad), size.height / 2f)
            drawCircle(Color.Black.copy(alpha = 0.35f), 8.5.dp.toPx(), c, style = Stroke(1.dp.toPx()))
            drawCircle(Color.White, 7.5.dp.toPx(), c)
            drawCircle(stops[(frac * (stops.size - 1)).roundToInt().coerceIn(0, stops.size - 1)], 4.5.dp.toPx(), c)
        }
        Text(text, Modifier.width(40.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurface, textAlign = TextAlign.End, maxLines = 1)
    }
}

// ------------------------------------------------------------------------------------------------ panel

@Composable
internal fun ColorPanel(state: EditorState) {
    val cs = MaterialTheme.colorScheme
    val ui = state.colorUi
    val ctx = LocalContext.current
    LaunchedEffect(ui) { ui.load(ctx.applicationContext.getSharedPreferences(CU_PREFS, Context.MODE_PRIVATE)) }
    val before = remember { state.color }   // the colour when the panel opened

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = cs.surfaceContainerHigh.copy(alpha = UI_ALPHA),
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, Brush.linearGradient(listOf(state.color.copy(alpha = 0.6f), Color.Transparent))),
    ) {
        Column(
            Modifier.width(248.dp).verticalScroll(rememberScrollState()).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            CuHeader(state, ui, before)
            CuTabs(ui)
            Box(Modifier.fillMaxWidth().height(CuBody)) {
                Crossfade(ui.tab, label = "cuTab") { t ->
                    when (t) {
                        0 -> CuPickTab(state, ui)
                        1 -> CuHarmonyTab(state, ui)
                        else -> CuParamsTab(state, ui)
                    }
                }
            }
            CuSlider(
                "Size", state.brushSize, 1f..64f, "${state.brushSize.roundToInt()}", listOf(cs.surfaceContainerHighest, state.color),
                { state.brushSize = it }, {},
            )
            CuHistory(state, ui)
        }
    }
}

// ------------------------------------------------------------------------------------------------ header

@Composable
private fun CuHeader(state: EditorState, ui: CuUi, before: Color) {
    val cs = MaterialTheme.colorScheme
    val clip = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1000); copied = false } }
    val half = RoundedCornerShape(10.dp)
    Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // before | now
        Row(Modifier.size(54.dp, 28.dp).clip(half).border(1.dp, cs.outlineVariant, half)) {
            Box(
                Modifier.weight(1f).fillMaxHeight().background(before).clickable { state.setColorKeepHue(before) }
                    .semantics { contentDescription = "Previous color" },
            )
            Box(Modifier.weight(1f).fillMaxHeight().background(state.color).semantics { contentDescription = "Current color" })
        }
        CuHex(state, ui, Modifier.weight(1f))
        CuPill(if (copied) "Copied" else "Copy", copied) { clip.setText(AnnotatedString("#" + cuHex(state.color))); copied = true }
    }
}

@Composable
private fun CuHex(state: EditorState, ui: CuUi, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val hex = cuHex(state.color)
    var text by remember { mutableStateOf(hex) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(hex, focused) { if (!focused) text = hex }
    BasicTextField(
        value = if (focused) text else hex,
        onValueChange = { v ->
            val t = v.uppercase(Locale.ROOT).filter { it in "0123456789ABCDEF" }.take(6)
            text = t
            if (t.length == 6) parseHex(t)?.let { state.setColorKeepHue(it) }
        },
        singleLine = true,
        textStyle = TextStyle(color = cs.onSurface, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
        cursorBrush = SolidColor(cs.primary),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            parseHex(text)?.let { state.setColorKeepHue(it); ui.commit(it) }
            focus.clearFocus()
        }),
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(10.dp)).background(cs.surfaceContainerHighest).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("#", fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = cs.onSurfaceVariant)
                inner()
            }
        },
    )
}

@Composable
private fun CuTabs(ui: CuUi) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(13.dp)
    Row(Modifier.fillMaxWidth().height(26.dp).clip(shape).background(cs.surfaceContainerHighest.copy(alpha = 0.7f)).padding(2.dp)) {
        listOf("Pick", "Harmony", "Params").forEachIndexed { i, t ->
            val sel = ui.tab == i
            val bg by animateColorAsState(if (sel) cs.primaryContainer else Color.Transparent, label = "cuTabBg")
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(11.dp)).background(bg).clickable { ui.tab = i; ui.save() },
                contentAlignment = Alignment.Center,
            ) {
                Text(t, style = MaterialTheme.typography.labelMedium, color = if (sel) cs.onPrimaryContainer else cs.onSurfaceVariant)
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ tab: pick

@Composable
private fun CuPickTab(state: EditorState, ui: CuUi) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CuSvPad(state, ui, Modifier.weight(1f).fillMaxHeight())
        CuHueBar(state, ui, Modifier.width(24.dp).fillMaxHeight())
    }
}

@Composable
private fun CuSvPad(state: EditorState, ui: CuUi, modifier: Modifier) {
    Canvas(
        modifier.clip(RoundedCornerShape(12.dp)).cuDrag(
            { p, sz ->
                state.sat = (p.x / sz.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                state.bri = (1f - p.y / sz.height.coerceAtLeast(1)).coerceIn(0f, 1f)
            },
            { ui.commit(state.color) },
        ),
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(state.hue.coerceIn(0f, 360f), 1f, 1f))))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val c = Offset(state.sat * size.width, (1f - state.bri) * size.height)
        drawCircle(Color.Black.copy(alpha = 0.5f), 9.dp.toPx(), c, style = Stroke(3.5.dp.toPx()))
        drawCircle(Color.White, 8.dp.toPx(), c, style = Stroke(2.dp.toPx()))
        drawCircle(state.color, 5.dp.toPx(), c)
    }
}

@Composable
private fun CuHueBar(state: EditorState, ui: CuUi, modifier: Modifier) {
    Canvas(
        modifier.clip(RoundedCornerShape(12.dp)).cuDrag(
            { p, sz -> state.hue = (p.y / sz.height.coerceAtLeast(1) * 360f).coerceIn(0f, 360f) },
            { ui.commit(state.color) },
        ),
    ) {
        drawRect(Brush.verticalGradient((0..6).map { Color.hsv(it * 60f, 1f, 1f) }))
        val y = state.hue / 360f * size.height
        val h = 9.dp.toPx(); val inset = 2.dp.toPx()
        val tl = Offset(inset, (y - h / 2f).coerceIn(0f, size.height - h))
        drawRoundRect(Color.Black.copy(alpha = 0.45f), tl, Size(size.width - 2f * inset, h), CornerRadius(h / 2f), style = Stroke(3.5.dp.toPx()))
        drawRoundRect(Color.White, tl, Size(size.width - 2f * inset, h), CornerRadius(h / 2f), style = Stroke(2.dp.toPx()))
        drawRoundRect(Color.hsv(state.hue.coerceIn(0f, 360f), 1f, 1f), tl, Size(size.width - 2f * inset, h), CornerRadius(h / 2f))
    }
}

// ------------------------------------------------------------------------------------------------ tab: harmony

@Composable
private fun CuHarmonyTab(state: EditorState, ui: CuUi) {
    val cs = MaterialTheme.colorScheme
    val hm = ui.harmony
    val sw = hm.swatches(state.hue, state.sat, state.bri)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CuHarmony.values().forEach { h -> CuPill(h.label, h == hm) { ui.harmony = h; ui.save() } }
        }
        Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CuRing(state, ui, hm, Modifier.size(96.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                sw.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { c ->
                            Box(
                                Modifier.weight(1f).height(28.dp).clip(RoundedCornerShape(10.dp)).background(c.color)
                                    .border(1.dp, cs.outlineVariant, RoundedCornerShape(10.dp))
                                    .clickable { state.hue = c.h; state.sat = c.s; state.bri = c.v; ui.commit(c.color) },
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        // shades (value) and tints (saturation) of the current colour
        CuRamp(List(8) { CuHsv(state.hue, state.sat, 0.12f + 0.88f * it / 7f) }, ((state.bri - 0.12f) / 0.88f * 7f).roundToInt()) { state.bri = it.v; ui.commit(state.color) }
        CuRamp(List(8) { CuHsv(state.hue, it / 7f, state.bri) }, (state.sat * 7f).roundToInt()) { state.sat = it.s; ui.commit(state.color) }
    }
}

@Composable
private fun CuRamp(items: List<CuHsv>, active: Int, onPick: (CuHsv) -> Unit) {
    Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))) {
        items.forEachIndexed { i, c ->
            Box(Modifier.weight(1f).fillMaxHeight().background(c.color).clickable { onPick(c) }, contentAlignment = Alignment.Center) {
                if (i == active.coerceIn(0, items.lastIndex)) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(if (c.color.luminance() > 0.5f) Color.Black else Color.White))
                }
            }
        }
    }
}

/** Hue ring with the harmony drawn on it. Drag anywhere to turn the whole shape. */
@Composable
private fun CuRing(state: EditorState, ui: CuUi, hm: CuHarmony, modifier: Modifier) {
    val ink = if (state.color.luminance() > 0.5f) Color.Black else Color.White
    Box(
        modifier.cuDrag(
            { p, sz ->
                var a = atan2(p.y - sz.height / 2f, p.x - sz.width / 2f) * 180f / PI.toFloat()
                if (a < 0f) a += 360f
                state.hue = a
            },
            { ui.commit(state.color) },
        ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val thick = 14.dp.toPx()
            val outer = size.minDimension / 2f - 2.dp.toPx()
            val r = outer - thick / 2f
            val c = center
            // the ring always reads as colour, even while the brush colour is grey / dark
            val s = state.sat.coerceAtLeast(0.45f); val v = state.bri.coerceAtLeast(0.55f)
            drawCircle(Brush.sweepGradient((0..12).map { Color.hsv(it * 30f, s, v) }, c), r, c, style = Stroke(thick))
            drawCircle(state.color, outer - thick - 3.dp.toPx(), c)

            val hues = hm.offs.map { state.hue + it }
            fun at(h: Float): Offset { val a = h * PI.toFloat() / 180f; return Offset(c.x + r * cos(a), c.y + r * sin(a)) }
            if (hm.offs.size > 1) {
                val path = Path()
                hues.sortedBy { wrapHue(it) }.forEachIndexed { i, h -> if (i == 0) path.moveTo(at(h).x, at(h).y) else path.lineTo(at(h).x, at(h).y) }
                if (hm.offs.size > 2) path.close()
                drawPath(path, Color.White.copy(alpha = 0.75f), style = Stroke(1.5.dp.toPx()))
            }
            hues.forEachIndexed { i, h ->
                val base = hm.offs[i] == 0f
                val p = at(h)
                val rad = if (base) 9.dp.toPx() else 6.5.dp.toPx()
                drawCircle(Color.hsv(wrapHue(h), state.sat, state.bri), rad, p)
                drawCircle(Color.Black.copy(alpha = 0.45f), rad, p, style = Stroke(3.5.dp.toPx()))
                drawCircle(Color.White, rad, p, style = Stroke(2.dp.toPx()))
            }
        }
        Text("${state.hue.roundToInt()}°", style = MaterialTheme.typography.labelMedium, color = ink)
    }
}

// ------------------------------------------------------------------------------------------------ tab: params

@Composable
private fun CuParamsTab(state: EditorState, ui: CuUi) {
    val m = ui.model
    val v = m.read(state, ui.lchHue)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CuModel.values().forEach { md -> CuPill(md.label, md == m) { ui.model = md; ui.save() } }
        }
        m.ch.forEachIndexed { i, ch ->
            val stops = (0..10).map { k ->
                val w = v.copyOf()
                w[i] = ch.min + (ch.max - ch.min) * k / 10f
                m.toColor(w)
            }
            CuSlider(
                ch.name, v[i], ch.min..ch.max, ch.fmt.format(Locale.ROOT, v[i]), stops,
                { nv ->
                    val w = v.copyOf()
                    w[i] = nv
                    if (m == CuModel.LCH && i == 2) ui.lchHue = nv
                    m.write(state, w)
                },
                { ui.commit(state.color) },
            )
        }
    }
}

// ------------------------------------------------------------------------------------------------ history

@Composable
private fun CuHistory(state: EditorState, ui: CuUi) {
    val cs = MaterialTheme.colorScheme
    val cur = state.color.toArgb() and 0xFFFFFF
    Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (ui.history.isEmpty()) Text("Colors you use show up here", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            ui.history.forEach { c ->
                val sel = (c.toArgb() and 0xFFFFFF) == cur
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(c)
                        .border(if (sel) 2.dp else 1.dp, if (sel) cs.primary else cs.outlineVariant, CircleShape)
                        .clickable { state.setColorKeepHue(c) }
                        .semantics { contentDescription = "Color history #" + cuHex(c) },
                )
            }
        }
        Box(
            Modifier.size(24.dp).clip(CircleShape).clickable { ui.clear() }.semantics { contentDescription = "Clear color history" },
            contentAlignment = Alignment.Center,
        ) { Text("✕", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant) }
    }
}
