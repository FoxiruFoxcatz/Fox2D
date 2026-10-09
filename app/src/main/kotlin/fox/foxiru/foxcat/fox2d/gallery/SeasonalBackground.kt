package fox.foxiru.foxcat.fox2d.gallery

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// ================================================================================================ seasons

enum class Season(val label: String) {
    Ambient("Default glow"),
    Halloween("Halloween"),
    Christmas("Christmas"),
    NewYear("New Year"),
    Valentines("Valentine's Day"),
    StPatricks("St. Patrick's Day"),
    Easter("Easter");

    companion object {
        /**
         * New Year   Dec 31 - Jan 3        Christmas   Dec 1 - Dec 30
         * Valentine  Feb 1 - Feb 15        St. Patrick Mar 10 - Mar 17
         * Easter     9 days before - 1 day after Easter Sunday (computed per year)
         * Halloween  Oct 1 - Nov 1         anything else: the quiet default glow
         */
        fun forDate(cal: Calendar = Calendar.getInstance()): Season {
            val y = cal.get(Calendar.YEAR)
            val m = cal.get(Calendar.MONTH) + 1
            val d = cal.get(Calendar.DAY_OF_MONTH)
            return when {
                (m == 12 && d == 31) || (m == 1 && d <= 3) -> NewYear
                m == 12 -> Christmas
                m == 2 && d <= 15 -> Valentines
                m == 3 && d in 10..17 -> StPatricks
                isEasterSeason(y, m, d) -> Easter
                m == 10 || (m == 11 && d == 1) -> Halloween
                else -> Ambient
            }
        }

        /** The year shown big behind the New Year animation: Dec 31 already counts down to the next one. */
        fun newYearNumber(cal: Calendar = Calendar.getInstance()): Int =
            cal.get(Calendar.YEAR) + if (cal.get(Calendar.MONTH) == Calendar.DECEMBER) 1 else 0

        private fun isEasterSeason(y: Int, m: Int, d: Int): Boolean {
            // Anonymous Gregorian algorithm
            val a = y % 19; val b = y / 100; val c = y % 100
            val dd = b / 4; val e = b % 4; val f = (b + 8) / 25; val g = (b - f + 1) / 3
            val h = (19 * a + b - dd - g + 15) % 30
            val i = c / 4; val k = c % 4
            val l = (32 + 2 * e + 2 * i - h - k) % 7
            val mm = (a + 11 * h + 22 * l) / 451
            val month = (h + l - 7 * mm + 114) / 31
            val day = (h + l - 7 * mm + 114) % 31 + 1
            val easter = Calendar.getInstance().apply { clear(); set(y, month - 1, day) }
            val today = Calendar.getInstance().apply { clear(); set(y, m - 1, d) }
            val diff = ((today.timeInMillis - easter.timeInMillis) / 86_400_000.0).roundToInt()
            return diff in -9..1
        }
    }
}

/** "auto" follows the date, "off" shows nothing, or a [Season] name to force one (handy for previewing). */
class BackgroundPrefs private constructor(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("fox2d_ui", Context.MODE_PRIVATE)

    var mode by mutableStateOf(prefs.getString(KEY, AUTO) ?: AUTO)
        private set

    fun set(value: String) {
        mode = value
        prefs.edit().putString(KEY, value).apply()
    }

    /** Reads [mode] (observable). null = animation switched off. */
    fun resolve(): Season? = when (mode) {
        OFF -> null
        AUTO -> Season.forDate()
        else -> runCatching { Season.valueOf(mode) }.getOrDefault(Season.forDate())
    }

    companion object {
        const val AUTO = "auto"
        const val OFF = "off"
        private const val KEY = "gallery_background"

        @Volatile private var instance: BackgroundPrefs? = null

        fun get(ctx: Context): BackgroundPrefs = instance ?: synchronized(this) {
            instance ?: BackgroundPrefs(ctx.applicationContext).also { instance = it }
        }
    }
}

// ================================================================================================ host

/**
 * [content] sits between two animated layers: a back layer (big centred motif + particles) and a lighter front
 * layer (a few particles drifting over the content, never blocking touches). Switching season cross-fades.
 * One shared clock drives both layers; it pauses when the app is not visible and stays still when the system
 * animation scale is 0.
 */
@Composable
fun SeasonalBackdrop(
    season: Season?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val clock = rememberClock()
    Box(modifier) {
        Crossfade(season, Modifier.fillMaxSize(), tween(700), label = "season-back") { s ->
            if (s != null) SeasonCanvas(s, clock, front = false)
        }
        content()
        Crossfade(season, Modifier.fillMaxSize(), tween(700), label = "season-front") { s ->
            if (s != null) SeasonCanvas(s, clock, front = true)
        }
    }
}

@Composable
private fun rememberClock(): MutableFloatState {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val clock = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(lifecycle) {
        val scale = runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE) }.getOrDefault(1f)
        if (scale == 0f) { clock.floatValue = 2.5f; return@LaunchedEffect }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var last = androidx.compose.runtime.withFrameNanos { it }
            while (true) {
                val now = androidx.compose.runtime.withFrameNanos { it }
                clock.floatValue += (now - last) / 1_000_000_000f
                last = now
            }
        }
    }
    return clock
}

private class Tools(
    val heart: Path, val egg: Path, val scratch: Path,
    val dark: Boolean, val primary: Color, val secondary: Color, val tertiary: Color,
) {
    val ink = if (dark) Color(0xFFB39DFF) else Color(0xFF2E1A47)   // bats, spider, cobwebs
    val snow = if (dark) Color.White else Color(0xFF5B9BD5)

    // scenery palette (Halloween graveyard, reusable): picked per theme so silhouettes read on both
    val stone = if (dark) Color(0xFF9C92C4) else Color(0xFF2E1A47)    // headstones, dead trees
    val ground = if (dark) Color(0xFF05020C) else Color(0xFF2E1A47)   // hill, mounds, carved lettering
    val fog = if (dark) Color(0xFFCFC6FF) else Color(0xFF7A5FA8)
    val iron = if (dark) Color(0xFFC9A24B) else Color(0xFF4A3520)     // chandelier metalwork
    val ghost = if (dark) Color.White else Color(0xFF7E6BB5)
    val wax = if (dark) Color(0xFFFFF1D6) else Color(0xFFE0B66B)
    val bone = if (dark) Color(0xFFE9E2D0) else Color(0xFFB8AC8C)
    val boughs = Array(5) { Path() }                                  // one Path per branch level of the dead trees
}

@Composable
private fun SeasonCanvas(season: Season, clock: MutableFloatState, front: Boolean) {
    val cs = MaterialTheme.colorScheme
    val dark = cs.surface.luminance() < 0.5f
    val tl = remember(dark, cs.primary, cs.secondary, cs.tertiary) {
        Tools(heartPath(), eggPath(), Path(), dark, cs.primary, cs.secondary, cs.tertiary)
    }
    val measurer = rememberTextMeasurer()
    val year = remember(season, measurer) {
        if (season == Season.NewYear) {
            measurer.measure(Season.newYearNumber().toString(), TextStyle(fontSize = 120.sp, fontWeight = FontWeight.Black))
        } else null
    }
    Canvas(Modifier.fillMaxSize()) {
        val t = clock.floatValue
        when (season) {
            Season.Ambient -> ambient(t, front, tl)
            Season.Halloween -> halloween(t, front, tl)
            Season.Christmas -> christmas(t, front, tl)
            Season.NewYear -> newYear(t, front, year)
            Season.Valentines -> valentines(t, front, tl)
            Season.StPatricks -> stPatricks(t, front, tl)
            Season.Easter -> easter(t, front, tl)
        }
    }
}

// ================================================================================================ helpers

private const val TAU = 6.2831855f
private const val PI_F = 3.1415927f

private fun frac(x: Float) = x - floor(x)

/** Deterministic pseudo random 0..1: every particle derives its look from its index, so nothing is stored. */
private fun rnd(i: Int, salt: Int): Float {
    var x = i * 374761393 + salt * 668265263
    x = (x xor (x ushr 13)) * 1274126177.toInt()
    x = x xor (x ushr 16)
    return (x and 0xFFFFFF) / 16777216f
}

/** Unit heart, centred on the origin: tip at (0, 0.9), top at y -0.9, about 1.9 wide. */
private fun heartPath() = Path().apply {
    moveTo(0f, 0.9f)
    cubicTo(-1.2f, 0f, -1f, -0.9f, -0.5f, -0.9f)
    cubicTo(-0.2f, -0.9f, 0f, -0.7f, 0f, -0.45f)
    cubicTo(0f, -0.7f, 0.2f, -0.9f, 0.5f, -0.9f)
    cubicTo(1f, -0.9f, 1.2f, 0f, 0f, 0.9f)
    close()
}

/** Unit egg, centred on the origin: about 0.92 wide, 1.3 tall, wider at the bottom. */
private fun eggPath() = Path().apply {
    moveTo(0f, -0.65f)
    cubicTo(0.42f, -0.8f, 0.78f, 0.7f, 0f, 0.65f)
    cubicTo(-0.78f, 0.7f, -0.42f, -0.8f, 0f, -0.65f)
    close()
}

private fun DrawScope.drawHeartAt(heart: Path, x: Float, y: Float, s: Float, rotDeg: Float, color: Color) {
    withTransform({ translate(x, y); rotate(rotDeg, Offset.Zero); scale(s, s, Offset.Zero) }) { drawPath(heart, color) }
}

private fun DrawScope.drawEgg(
    egg: Path, x: Float, y: Float, s: Float, rot: Float,
    base: Color, accent: Color, alpha: Float, detailed: Boolean,
) {
    withTransform({ translate(x, y); rotate(rot, Offset.Zero); scale(s, s, Offset.Zero) }) {
        drawPath(egg, base.copy(alpha = alpha))
        clipPath(egg) {
            drawRect(accent.copy(alpha = alpha * 1.2f), Offset(-0.7f, -0.14f), Size(1.4f, 0.16f))
            if (detailed) {
                for (k in 0 until 8) {
                    val x0 = -0.5f + k * 0.125f
                    val up = k % 2 == 0
                    drawLine(
                        accent.copy(alpha = alpha * 1.4f),
                        Offset(x0, if (up) 0.14f else 0.28f), Offset(x0 + 0.125f, if (up) 0.28f else 0.14f),
                        strokeWidth = 0.05f, cap = StrokeCap.Round,
                    )
                }
                val dots = floatArrayOf(-0.2f, -0.4f, 0.15f, -0.46f, -0.02f, 0.46f, -0.26f, 0.38f, 0.26f, 0.42f)
                for (k in 0 until dots.size / 2) drawCircle(accent.copy(alpha = alpha * 1.4f), 0.045f, Offset(dots[k * 2], dots[k * 2 + 1]))
            }
        }
    }
}

/** Fade in at the first and last 1/8 of a 0..1 journey. */
private fun edge(y: Float) = min(1f, min(y, 1f - y) * 8f)

// ================================================================================================ default

private fun DrawScope.ambient(t: Float, front: Boolean, tl: Tools) {
    if (front) return
    val w = size.width; val h = size.height; val cx = w / 2f; val cy = h / 2f; val u = min(w, h)
    val colors = arrayOf(tl.primary, tl.tertiary, tl.secondary, tl.primary)
    for (i in 0 until 4) {
        val o = Offset(cx + cos(t * 0.12f + i * 1.7f) * w * 0.32f, cy + sin(t * 0.1f + i * 2.3f) * h * 0.28f)
        val r = u * (0.38f + 0.08f * i)
        drawCircle(Brush.radialGradient(listOf(colors[i].copy(alpha = 0.14f), Color.Transparent), center = o, radius = r), r, o)
    }
    drawCircle(tl.primary.copy(alpha = 0.10f), u * (0.28f + 0.03f * sin(t * 0.8f)), Offset(cx, cy), style = Stroke(2f * density))
}

// ================================================================================================ shared scenery
// Small building blocks (glow, flame, candle, fog, ghost, web ...). Halloween uses them first; other seasons can too.

private val FlameOuter = Color(0xFFFF9A2E)
private val FlameInner = Color(0xFFFFE9A0)
private val FlameGlow = Color(0xFFFFA43A)
private val WispGreen = Color(0xFF69F0AE)
private val Night = Color(0xFF1B1030)

private fun sq(x: Float) = x * x

/** Round glow that fades to nothing at [r]. One gradient shader: use for the big ones. */
private fun DrawScope.glow(c: Offset, r: Float, color: Color, a: Float) {
    if (a <= 0.004f) return
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = a), Color.Transparent), center = c, radius = r), r, c)
}

/** Cheap glow from four stacked discs (no shader): use for flames, wisps, eyes. */
private fun DrawScope.softGlow(c: Offset, r: Float, color: Color, a: Float) {
    if (a <= 0.004f) return
    drawCircle(color.copy(alpha = a * 0.22f), r, c)
    drawCircle(color.copy(alpha = a * 0.30f), r * 0.68f, c)
    drawCircle(color.copy(alpha = a * 0.40f), r * 0.42f, c)
    drawCircle(color.copy(alpha = a * 0.55f), r * 0.22f, c)
}

private fun DrawScope.teardrop(p: Path, x: Float, y: Float, s: Float, h: Float, sway: Float, color: Color) {
    p.reset()
    p.moveTo(x, y + s * 0.35f)
    p.cubicTo(x - s * 0.8f, y + s * 0.15f, x - s * 0.45f + sway * 0.4f, y - h * 0.55f, x + sway, y - h)
    p.cubicTo(x + s * 0.45f + sway * 0.4f, y - h * 0.55f, x + s * 0.8f, y + s * 0.15f, x, y + s * 0.35f)
    p.close()
    drawPath(p, color)
}

/** Candle flame sitting at (x, y); about 2.4 * [s] tall. [ph] keeps neighbouring flames out of step. */
private fun DrawScope.drawFlame(x: Float, y: Float, s: Float, t: Float, ph: Float, a: Float, p: Path) {
    val fl = 0.85f + 0.15f * sin(t * 13f + ph * 5f) * sin(t * 7.3f + ph * 3f) + 0.05f * sin(t * 23f + ph)
    val sway = sin(t * 4.3f + ph * 7f) * 0.25f * s
    softGlow(Offset(x, y - s * 1.1f), s * 8f, FlameGlow, 0.45f * fl * a)
    teardrop(p, x, y, s, s * 2.4f * fl, sway, FlameOuter.copy(alpha = 0.9f * a))
    teardrop(p, x, y + s * 0.1f, s * 0.55f, s * 1.3f * fl, sway * 0.6f, FlameInner.copy(alpha = 0.95f * a))
}

/** Wax candle standing on (x, baseY): body, one drip, wick and a lit flame. */
private fun DrawScope.drawCandle(x: Float, baseY: Float, w: Float, h: Float, t: Float, ph: Float, a: Float, tl: Tools) {
    val top = baseY - h
    drawRoundRect(tl.wax.copy(alpha = 0.85f * a), Offset(x - w / 2f, top), Size(w, h), CornerRadius(w * 0.25f))
    drawRoundRect(tl.wax.copy(alpha = 0.7f * a), Offset(x - w * 0.5f, top), Size(w * 0.28f, h * (0.25f + 0.2f * frac(ph))), CornerRadius(w * 0.14f))
    drawLine(Night.copy(alpha = 0.7f * a), Offset(x, top), Offset(x, top - w * 0.4f), maxOf(w * 0.12f, 1f))
    drawFlame(x, top - w * 0.15f, w * 0.7f, t, ph, a, tl.scratch)
}

/** A bank of soft fog blobs drifting sideways ([speed] in screen widths per second, negative = leftwards). */
private fun DrawScope.fogBank(t: Float, y: Float, r: Float, n: Int, speed: Float, color: Color, a: Float, seed: Int) {
    val w = size.width
    for (i in 0 until n) {
        val rr = r * (0.75f + 0.5f * rnd(i + seed, 81))
        val x = frac(i.toFloat() / n + rnd(i + seed, 80) * 0.3f + t * speed) * (w + 2f * rr) - rr
        val c = Offset(x, y + sin(t * 0.3f + i * 1.7f) * rr * 0.08f)
        withTransform({ scale(1f, 0.38f, c) }) {
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = a), Color.Transparent), center = c, radius = rr), rr, c)
        }
    }
}

/** Friendly ghost: domed head, rippling hem, hollow eyes and an "oooh" mouth. [s] is half its width. */
private fun DrawScope.drawGhost(x: Float, y: Float, s: Float, t: Float, ph: Float, a: Float, tl: Tools) {
    if (a <= 0.01f) return
    val p = tl.scratch
    softGlow(Offset(x, y), s * 2.2f, tl.ghost, 0.22f * a)
    p.reset()
    p.moveTo(x - s, y + s * 1.1f)
    p.lineTo(x - s, y - s * 0.15f)
    p.cubicTo(x - s, y - s * 1.35f, x + s, y - s * 1.35f, x + s, y - s * 0.15f)
    p.lineTo(x + s, y + s * 1.1f)
    for (k in 0 until 4) { // hem, right to left
        val ex = x + s - (k + 1) * s * 0.5f
        val bulge = (if (k % 2 == 0) s * 0.32f else -s * 0.32f) + s * 0.3f * sin(t * 3.2f + ph + k * 1.7f)
        p.quadraticTo(ex + s * 0.25f, y + s * 1.1f + bulge, ex, y + s * 1.1f)
    }
    p.close()
    drawPath(p, tl.ghost.copy(alpha = 0.26f * a))
    val hole = Night.copy(alpha = 0.55f * a)
    drawOval(hole, Offset(x - s * 0.5f, y - s * 0.35f), Size(s * 0.26f, s * 0.38f))
    drawOval(hole, Offset(x + s * 0.24f, y - s * 0.35f), Size(s * 0.26f, s * 0.38f))
    drawOval(hole, Offset(x - s * 0.14f, y + s * 0.12f), Size(s * 0.28f, s * (0.3f + 0.1f * sin(t * 2f + ph))))
}

/** Spider hanging on a thread from the top edge, legs twitching. (x, y) is the centre of its body. */
private fun DrawScope.drawSpider(x: Float, y: Float, r: Float, t: Float, tl: Tools) {
    val dp = density
    val c = tl.ink.copy(alpha = 0.8f)
    drawLine(tl.ink.copy(alpha = 0.4f), Offset(x, 0f), Offset(x, y - r), 1f * dp)
    val p = tl.scratch
    p.reset()
    for (side in 0..1) {
        val sg = if (side == 0) -1f else 1f
        for (i in 0 until 4) {
            val th = -0.9f + i * 0.55f + sin(t * 7f + i * 1.3f + side * 2f) * 0.12f
            val kx = x + sg * cos(th) * r * 1.9f
            val ky = y + sin(th) * r * 1.9f - r * 0.4f
            p.moveTo(x, y)
            p.lineTo(kx, ky)
            p.lineTo(kx + sg * r * 0.9f, ky + r * (1.3f + 0.25f * i))
        }
    }
    drawPath(p, c, style = Stroke(1.4f * dp, cap = StrokeCap.Round))
    drawCircle(c, r, Offset(x, y + r * 0.35f))
    drawCircle(c, r * 0.6f, Offset(x, y - r * 0.75f))
    drawCircle(Color(0xFFFF4040).copy(alpha = 0.9f), r * 0.14f, Offset(x - r * 0.22f, y - r * 0.85f))
    drawCircle(Color(0xFFFF4040).copy(alpha = 0.9f), r * 0.14f, Offset(x + r * 0.22f, y - r * 0.85f))
}

/** Quarter cobweb in a corner: [dir] 1 = corner is the left edge, -1 = the right edge. */
private fun DrawScope.drawWeb(cx: Float, cy: Float, r: Float, dir: Float, color: Color, sw: Float, p: Path) {
    val spokes = 5
    val quarter = PI_F / 2f
    p.reset()
    for (k in 0 until spokes) {
        val th = k / (spokes - 1f) * quarter
        p.moveTo(cx, cy)
        p.lineTo(cx + dir * cos(th) * r, cy + sin(th) * r)
    }
    for (j in 1..4) {
        val rj = r * j / 4f
        for (k in 0 until spokes) {
            val th = k / (spokes - 1f) * quarter
            val px = cx + dir * cos(th) * rj
            val py = cy + sin(th) * rj
            if (k == 0) {
                p.moveTo(px, py)
            } else { // each strand sags a little towards the corner
                val tm = (k - 0.5f) / (spokes - 1f) * quarter
                p.quadraticTo(cx + dir * cos(tm) * rj * 0.86f, cy + sin(tm) * rj * 0.86f, px, py)
            }
        }
    }
    drawPath(p, color, style = Stroke(sw))
}

// ================================================================================================ halloween

private fun hwGroundY(h: Float, x01: Float) =
    h * 0.9f - h * 0.012f * sin(x01 * TAU * 1.3f + 0.8f) - h * 0.008f * sin(x01 * TAU * 3.1f)

private fun DrawScope.halloween(t: Float, front: Boolean, tl: Tools) {
    val w = size.width; val h = size.height; val cx = w / 2f; val cy = h / 2f; val u = min(w, h)
    val dp = density
    val fade = if (front) 0.7f else 1f
    val seed = if (front) 1000 else 0

    if (!front) {
        // night sky, with a pale moon right behind the title
        drawRect(
            Brush.verticalGradient(
                listOf(Color(0xFF3B1A78).copy(alpha = if (tl.dark) 0.30f else 0.10f), Color.Transparent),
                startY = 0f, endY = h * 0.5f,
            ),
        )
        hwMoon(Offset(cx, h * 0.06f + sin(t * 0.2f) * u * 0.004f), u * 0.08f, t)
        hwLightning(t, tl)

        // centrepiece: warm halo and the flickering jack-o'-lantern
        val pulse = 0.5f + 0.5f * sin(t * 0.9f)
        val halo = Offset(cx, cy - u * 0.10f)
        glow(halo, u * 0.62f, Color(0xFFFFB347), 0.22f + 0.07f * pulse)
        drawPumpkin(cx, cy + u * 0.03f + sin(t * 1.3f) * u * 0.008f, u * 0.24f, t, tl.scratch)

        hwGraveyard(t, tl)

        // chandeliers hanging from the top edge, swaying on their chains
        hwChandelier(w * 0.2f, 0f, h * 0.035f, u * 0.13f, t, 0.4f, 1f, tl)
        hwChandelier(w * 0.8f, 0f, h * 0.075f, u * 0.10f, t, 2.3f, 0.9f, tl)
        drawWeb(0f, 0f, u * 0.2f, 1f, tl.ink.copy(alpha = 0.22f), 1f * dp, tl.scratch)
        drawWeb(w, 0f, u * 0.17f, -1f, tl.ink.copy(alpha = 0.22f), 1f * dp, tl.scratch)

        // will-o'-the-wisps low over the graves, candles hovering in the air, a ghost wandering about
        for (i in 0 until 5) hwWisp(i, t, h * 0.9f, h * 0.1f, 0, 1f, tl)
        for (i in 0 until 5) {
            val s = i + 200
            val x = w * (0.1f + 0.8f * rnd(s, 120)) + sin(t * 0.45f + rnd(s, 121) * TAU) * u * 0.02f
            val y = h * (0.2f + 0.45f * rnd(s, 122)) + sin(t * 0.62f + rnd(s, 123) * TAU) * u * 0.02f
            drawCandle(x, y, u * 0.014f, u * 0.055f, t, s.toFloat(), 0.9f, tl)
        }
        drawGhost(
            w * (0.5f + 0.35f * sin(t * 0.12f + 0.7f)), h * (0.42f + 0.06f * sin(t * 0.31f)), u * 0.07f, t, 0f,
            0.35f + 0.65f * (0.5f + 0.5f * sin(t * 0.35f)), tl,
        )
    } else {
        // low mist, a small ghost drifting over the content, a spider dropping from the ceiling
        fogBank(t, h * 0.95f, u * 0.35f, 3, 0.012f, tl.fog, if (tl.dark) 0.08f else 0.10f, 50)
        drawGhost(
            w * (0.5f + 0.55f * sin(t * 0.11f + 1f)), h * (0.45f + 0.1f * sin(t * 0.17f)), u * 0.04f, t, 2f,
            0.55f * (0.35f + 0.65f * (0.5f + 0.5f * sin(t * 0.27f + 2f))), tl,
        )
        drawSpider(w * 0.1f, h * (0.03f + 0.11f * (0.5f - 0.5f * cos(t * 0.33f))), u * 0.016f, t, tl)
        for (i in 0 until 3) hwWisp(i, t, h * 0.8f, h * 0.55f, 500, 0.8f, tl)
        for (i in 0 until 3) { // candles drifting up very slowly
            val s = i + 300
            val y = 1f - frac(rnd(s, 2) + t * (0.012f + rnd(s, 1) * 0.01f))
            val x = rnd(s, 3) + sin(t * 0.5f + rnd(s, 4) * TAU) * 0.03f
            drawCandle(x * w, y * h, u * 0.012f, u * 0.045f, t, s.toFloat(), 0.8f * edge(y), tl)
        }
    }

    // embers rising
    for (i in 0 until if (front) 8 else 24) {
        val s = i + seed
        val y = 1f - frac(rnd(s, 2) + t * (0.025f + rnd(s, 1) * 0.045f))
        val x = rnd(s, 3) + sin(t * 0.8f + rnd(s, 4) * TAU) * 0.025f
        drawCircle(
            Color(0xFFFF8A1F).copy(alpha = 0.55f * sin(y * PI_F) * fade),
            (1.8f + rnd(s, 5) * 3f) * dp, Offset(x * w, y * h),
        )
    }

    // bats crossing, wings flapping
    for (i in 0 until if (front) 2 else 5) {
        val s = i + seed
        val ph = frac(t / (16f + rnd(s, 6) * 12f) + rnd(s, 7))
        val span = w + 160f * dp
        val x = if (rnd(s, 8) > 0.5f) ph * span - 80f * dp else w + 80f * dp - ph * span
        val y = h * (0.08f + 0.62f * rnd(s, 9)) + sin(t * 1.1f + rnd(s, 10) * TAU) * h * 0.03f
        val flap = 0.35f + 0.65f * (0.5f + 0.5f * sin(t * (9f + rnd(s, 11) * 4f) + rnd(s, 12) * TAU))
        drawBat(x, y, (11f + 9f * rnd(s, 13)) * dp, flap, tl.ink.copy(alpha = 0.5f * fade), tl.scratch)
    }
}

private fun DrawScope.hwMoon(c: Offset, r: Float, t: Float) {
    glow(c, r * 3.4f, Color(0xFFFFE9A8), 0.20f + 0.03f * sin(t * 0.5f))
    drawCircle(Color(0xFFFFE08A).copy(alpha = 0.32f), r, c)
    val crater = Color(0xFFC9A24B).copy(alpha = 0.26f)
    drawCircle(crater, r * 0.22f, Offset(c.x - r * 0.35f, c.y - r * 0.25f))
    drawCircle(crater, r * 0.14f, Offset(c.x + r * 0.25f, c.y + r * 0.3f))
    drawCircle(crater, r * 0.09f, Offset(c.x + r * 0.4f, c.y - r * 0.35f))
    drawCircle(crater, r * 0.10f, Offset(c.x - r * 0.15f, c.y + r * 0.5f))
}

/** A strike every ~9 s (some cycles stay dark): double flash over the whole screen plus a jagged bolt. */
private fun DrawScope.hwLightning(t: Float, tl: Tools) {
    val w = size.width; val h = size.height
    val period = 9f
    val idx = floor(t / period).toInt()
    if (rnd(idx, 71) < 0.35f) return
    val lt = t - idx * period
    val st = 3f + 5f * rnd(idx, 70)
    val f = min(1f, exp(-sq((lt - st) * 12f)) + 0.7f * exp(-sq((lt - st - 0.22f) * 9f)))
    if (f < 0.02f) return
    val c = if (tl.dark) Color(0xFFDCD0FF) else Color(0xFF5B3FA0)
    drawRect(c.copy(alpha = (if (tl.dark) 0.10f else 0.06f) * f))
    val p = tl.scratch
    p.reset()
    var x = w * (0.15f + 0.7f * rnd(idx, 72))
    p.moveTo(x, 0f)
    val segments = 9
    for (k in 1..segments) {
        x += (rnd(idx * 13 + k, 73) - 0.5f) * w * 0.14f
        p.lineTo(x, h * 0.5f * k / segments)
    }
    drawPath(p, c.copy(alpha = 0.25f * f), style = Stroke(7f * density, cap = StrokeCap.Round))
    drawPath(p, (if (tl.dark) Color.White else c).copy(alpha = 0.8f * f), style = Stroke(2.2f * density, cap = StrokeCap.Round))
}

private fun DrawScope.hwGraveyard(t: Float, tl: Tools) {
    val w = size.width; val h = size.height; val u = min(w, h); val dp = density
    val p = tl.scratch

    // horizon glow, then the hill everything stands on
    drawRect(
        Brush.verticalGradient(
            listOf(Color.Transparent, Color(0xFFFF7A18).copy(alpha = if (tl.dark) 0.16f else 0.12f)),
            startY = h * 0.6f, endY = h * 0.9f,
        ),
    )
    p.reset()
    p.moveTo(0f, h)
    for (i in 0..24) {
        val x01 = i / 24f
        p.lineTo(x01 * w, hwGroundY(h, x01))
    }
    p.lineTo(w, h)
    p.close()
    drawPath(p, tl.ground.copy(alpha = if (tl.dark) 0.75f else 0.16f))

    // two dead trees at the edges
    hwTree(w * 0.07f, hwGroundY(h, 0.07f) + 4f * dp, u * 0.2f, 0.06f, t, tl, 11)
    hwTree(w * 0.95f, hwGroundY(h, 0.95f) + 4f * dp, u * 0.14f, -0.1f, t, tl, 5)

    // far row: small, faint stones behind a thin mist
    fogBank(t, h * 0.88f, u * 0.3f, 4, 0.010f, tl.fog, if (tl.dark) 0.09f else 0.12f, 20)
    for (i in 0 until 7) {
        val x01 = (i + 0.5f) / 7f + (rnd(i, 90) - 0.5f) * 0.05f
        val gw = u * (0.045f + 0.015f * rnd(i, 91))
        grave((i * 5 + 2) % 4, x01 * w, hwGroundY(h, x01) - 1f * dp, gw, gw * (1.4f + 0.5f * rnd(i, 92)), (rnd(i, 93) - 0.5f) * 14f, 0.30f, tl)
    }

    // a ghost rising out of the middle grave every 18 s
    val rise = frac(t / 18f)
    drawGhost(
        w * 0.5f + sin(rise * TAU * 1.5f) * u * 0.05f, hwGroundY(h, 0.5f) + u * 0.02f - rise * h * 0.4f,
        u * 0.045f * (0.6f + 0.6f * rise), t, 1.3f, sin(rise * PI_F), tl,
    )

    // near row: big stones, with candles burning beside every other one
    for (i in 0 until 5) {
        val x01 = 0.1f + 0.2f * i + (rnd(i, 94) - 0.5f) * 0.05f
        val gw = u * (0.07f + 0.025f * rnd(i, 95))
        val base = hwGroundY(h, x01) + u * 0.04f
        grave((i * 3 + 1) % 4, x01 * w, base, gw, gw * (1.3f + 0.45f * rnd(i, 96)), (rnd(i, 97) - 0.5f) * 12f, 0.5f, tl)
        if (i % 2 == 1) drawCandle(x01 * w + gw * 0.8f, base, u * 0.013f, u * 0.04f, t, i * 2.1f, 1f, tl)
    }

    // a small jack-o'-lantern and a skull with a candle on top, between the stones
    val pr = u * 0.05f
    drawPumpkin(w * 0.4f, hwGroundY(h, 0.4f) + u * 0.05f - pr * 0.8f, pr, t, p)
    hwSkull(w * 0.6f, hwGroundY(h, 0.6f) + u * 0.05f, u * 0.026f, t, tl)

    // things watching from the dark
    hwEyes(w * 0.07f, hwGroundY(h, 0.07f) - u * 0.1f, u * 0.011f, t, 0.5f, tl.dark)
    hwEyes(w * 0.95f, hwGroundY(h, 0.95f) - u * 0.07f, u * 0.009f, t, 2.9f, tl.dark)
    hwEyes(w * 0.8f, hwGroundY(h, 0.8f) + u * 0.03f, u * 0.009f, t, 4.4f, tl.dark)

    // mist in front of the stones
    fogBank(t, h * 0.95f, u * 0.28f, 3, -0.015f, tl.fog, if (tl.dark) 0.08f else 0.10f, 30)
}

/** Gnarled tree: five levels of branches (one Path per level, so joints do not double up in alpha), swaying slightly. */
private fun DrawScope.hwTree(x: Float, base: Float, len: Float, lean: Float, t: Float, tl: Tools, seed: Int) {
    val b = tl.boughs
    for (path in b) path.reset()
    hwBough(b, x, base, lean, len, 0, t, seed)
    var sw = len * 0.09f
    for (d in b.indices) {
        drawPath(b[d], tl.stone.copy(alpha = 0.45f), style = Stroke(maxOf(sw, 1.1f * density), cap = StrokeCap.Round))
        sw *= 0.58f
    }
}

private fun hwBough(b: Array<Path>, x: Float, y: Float, ang: Float, len: Float, d: Int, t: Float, seed: Int) {
    val a = ang + sin(t * 0.55f + d * 1.3f + seed) * 0.025f * d
    val ex = x + sin(a) * len
    val ey = y - cos(a) * len
    b[d].moveTo(x, y)
    b[d].lineTo(ex, ey)
    if (d == b.size - 1) return
    val n = if (d == 0) 3 else 2
    for (k in 0 until n) {
        val spread = (k - (n - 1) / 2f) * (0.6f + 0.3f * rnd(seed + d * 7 + k, 40)) + (rnd(seed * 3 + d * 11 + k, 41) - 0.5f) * 0.35f
        hwBough(b, ex, ey, a + spread, len * (0.68f + 0.12f * rnd(seed + d + k * 5, 42)), d + 1, t, seed + k * 17 + 1)
    }
}

/** Headstone silhouettes: 0 rounded slab, 1 cross, 2 obelisk, 3 broken slab. One path each, so alpha stays even. */
private fun DrawScope.grave(kind: Int, cx: Float, base: Float, gw: Float, gh: Float, tilt: Float, a: Float, tl: Tools) {
    val p = tl.scratch
    val x0 = cx - gw / 2f
    val x1 = cx + gw / 2f
    val top = base - gh
    withTransform({ rotate(tilt, Offset(cx, base)) }) {
        drawOval(tl.ground.copy(alpha = a * 0.9f), Offset(cx - gw * 0.85f, base - gw * 0.1f), Size(gw * 1.7f, gw * 0.3f)) // earth mound
        p.reset()
        when (kind) {
            0 -> {
                p.moveTo(x0, base)
                p.lineTo(x0, top + gw * 0.5f)
                p.cubicTo(x0, top + gw * 0.22f, cx - gw * 0.22f, top, cx, top)
                p.cubicTo(cx + gw * 0.22f, top, x1, top + gw * 0.22f, x1, top + gw * 0.5f)
                p.lineTo(x1, base)
                p.close()
            }
            1 -> {
                val th = gw * 0.26f
                val arm = gw * 0.48f
                val aTop = top + gh * 0.18f
                val aBot = aTop + th
                p.moveTo(cx - th / 2f, base)
                p.lineTo(cx - th / 2f, aBot)
                p.lineTo(cx - arm, aBot)
                p.lineTo(cx - arm, aTop)
                p.lineTo(cx - th / 2f, aTop)
                p.lineTo(cx - th / 2f, top)
                p.lineTo(cx + th / 2f, top)
                p.lineTo(cx + th / 2f, aTop)
                p.lineTo(cx + arm, aTop)
                p.lineTo(cx + arm, aBot)
                p.lineTo(cx + th / 2f, aBot)
                p.lineTo(cx + th / 2f, base)
                p.close()
            }
            2 -> {
                p.moveTo(cx - gw * 0.7f, base)
                p.lineTo(cx - gw * 0.7f, base - gh * 0.12f)
                p.lineTo(cx - gw * 0.4f, base - gh * 0.12f)
                p.lineTo(cx - gw * 0.3f, top + gh * 0.14f)
                p.lineTo(cx, top)
                p.lineTo(cx + gw * 0.3f, top + gh * 0.14f)
                p.lineTo(cx + gw * 0.4f, base - gh * 0.12f)
                p.lineTo(cx + gw * 0.7f, base - gh * 0.12f)
                p.lineTo(cx + gw * 0.7f, base)
                p.close()
            }
            else -> {
                p.moveTo(x0, base)
                p.lineTo(x0, top + gw * 0.2f)
                p.lineTo(cx - gw * 0.2f, top + gw * 0.02f)
                p.lineTo(cx, top + gw * 0.15f)
                p.lineTo(cx + gw * 0.2f, top)
                p.lineTo(x1, top + gw * 0.18f)
                p.lineTo(x1, base)
                p.close()
            }
        }
        drawPath(p, tl.stone.copy(alpha = a))
        if (kind == 0 || kind == 3) { // carved cross and two lines of "text"
            val cut = tl.ground.copy(alpha = a * 0.8f)
            val sw = maxOf(gw * 0.045f, 1f)
            val c0 = top + gh * 0.2f
            drawLine(cut, Offset(cx, c0), Offset(cx, c0 + gh * 0.2f), sw, StrokeCap.Round)
            drawLine(cut, Offset(cx - gw * 0.12f, c0 + gh * 0.07f), Offset(cx + gw * 0.12f, c0 + gh * 0.07f), sw, StrokeCap.Round)
            drawLine(cut, Offset(cx - gw * 0.22f, top + gh * 0.58f), Offset(cx + gw * 0.22f, top + gh * 0.58f), sw, StrokeCap.Round)
            drawLine(cut, Offset(cx - gw * 0.14f, top + gh * 0.7f), Offset(cx + gw * 0.14f, top + gh * 0.7f), sw, StrokeCap.Round)
        }
        if (kind == 3) { // crack
            val cut = tl.ground.copy(alpha = a * 0.8f)
            val sw = maxOf(gw * 0.035f, 1f)
            drawLine(cut, Offset(cx + gw * 0.3f, top + gh * 0.3f), Offset(cx + gw * 0.15f, top + gh * 0.45f), sw)
            drawLine(cut, Offset(cx + gw * 0.15f, top + gh * 0.45f), Offset(cx + gw * 0.25f, top + gh * 0.6f), sw)
            drawLine(cut, Offset(cx + gw * 0.25f, top + gh * 0.6f), Offset(cx + gw * 0.1f, top + gh * 0.8f), sw)
        }
    }
}

/** Skull with glowing sockets that flicker in time with the candle burning on its head. */
private fun DrawScope.hwSkull(x: Float, base: Float, r: Float, t: Float, tl: Tools) {
    val p = tl.scratch
    val top = base - r * 2.2f
    p.reset()
    p.moveTo(x - r * 0.5f, base)
    p.lineTo(x - r * 0.5f, base - r * 0.7f)
    p.cubicTo(x - r * 1.15f, base - r * 0.9f, x - r * 1.05f, top, x, top)
    p.cubicTo(x + r * 1.05f, top, x + r * 1.15f, base - r * 0.9f, x + r * 0.5f, base - r * 0.7f)
    p.lineTo(x + r * 0.5f, base)
    p.close()
    drawPath(p, tl.bone.copy(alpha = if (tl.dark) 0.6f else 0.85f))
    val flick = 0.7f + 0.3f * sin(t * 9f) * sin(t * 4.1f)
    for (side in 0..1) {
        val ex = x + (side * 2 - 1) * r * 0.42f
        softGlow(Offset(ex, base - r * 1.25f), r * 0.6f, FlameGlow, 0.5f * flick)
        drawCircle(Night.copy(alpha = 0.8f), r * 0.26f, Offset(ex, base - r * 1.25f))
        drawCircle(FlameOuter.copy(alpha = 0.8f * flick), r * 0.15f, Offset(ex, base - r * 1.25f))
    }
    p.reset()
    p.moveTo(x, base - r * 0.95f)
    p.lineTo(x - r * 0.12f, base - r * 0.7f)
    p.lineTo(x + r * 0.12f, base - r * 0.7f)
    p.close()
    drawPath(p, Night.copy(alpha = 0.7f))
    for (k in -1..1) drawLine(Night.copy(alpha = 0.6f), Offset(x + k * r * 0.25f, base - r * 0.4f), Offset(x + k * r * 0.25f, base), maxOf(r * 0.07f, 1f))
    drawCandle(x, top + r * 0.12f, r * 0.42f, r * 1.1f, t, 3.3f, 1f, tl)
}

/** A pair of slanted, glowing eyes that fade in and out of the dark and blink now and then. */
private fun DrawScope.hwEyes(x: Float, y: Float, s: Float, t: Float, ph: Float, dark: Boolean) {
    val vis = ((sin(t * 0.23f + ph) - 0.2f) * 3f).coerceIn(0f, 1f)
    if (vis <= 0f) return
    val blink = if (frac(t / 5f + ph) > 0.93f) 0.12f else 1f
    val c = if (dark) Color(0xFFD4FF3A) else Color(0xFF8CA800)
    for (side in 0..1) {
        val sg = (side * 2 - 1).toFloat()
        val e = Offset(x + sg * s * 1.1f, y)
        softGlow(e, s * 2.6f, c, 0.4f * vis * blink)
        withTransform({ rotate(-sg * 18f, e) }) {
            drawOval(c.copy(alpha = 0.9f * vis), Offset(e.x - s * 0.55f, e.y - s * 0.22f * blink), Size(s * 1.1f, s * 0.44f * blink))
        }
    }
}

/** Will-o'-the-wisp: a green orb wandering around [baseY] - [spanY], dragging a fading trail. */
private fun DrawScope.hwWisp(i: Int, t: Float, baseY: Float, spanY: Float, seed: Int, a: Float, tl: Tools) {
    val w = size.width
    val u = min(w, size.height)
    val c = if (tl.dark) WispGreen else Color(0xFF00A86B)
    val s = i + seed
    for (k in 4 downTo 0) {
        val tt = t - k * 0.16f
        val x = w * (0.08f + 0.84f * rnd(s, 100) + 0.08f * sin(tt * 0.31f + rnd(s, 101) * TAU) + 0.03f * sin(tt * 0.9f + i))
        val y = baseY - spanY * (0.5f + 0.5f * sin(tt * 0.23f + rnd(s, 102) * TAU)) + sin(tt * 1.7f + i) * u * 0.01f
        val f = 1f - k * 0.2f
        if (k == 0) {
            softGlow(Offset(x, y), u * 0.04f, c, 0.55f * a)
            drawCircle(Color.White.copy(alpha = 0.8f * a), u * 0.005f, Offset(x, y))
        } else {
            drawCircle(c.copy(alpha = 0.32f * f * a), u * 0.006f * f, Offset(x, y))
        }
    }
}

/**
 * Iron chandelier on a chain: swings from its anchor, five candles with their own flames, a warm glow and a few
 * twinkling crystals. [r] is half its width.
 */
private fun DrawScope.hwChandelier(cx: Float, topY: Float, chain: Float, r: Float, t: Float, ph: Float, a: Float, tl: Tools) {
    val dp = density
    val swing = sin(t * 0.8f + ph) * 3f + sin(t * 1.9f + ph * 2f) * 0.7f
    val hubY = topY + chain
    val iron = tl.iron.copy(alpha = 0.8f * a)
    val cupW = r * 0.2f
    val cupH = r * 0.05f
    withTransform({ rotate(swing, Offset(cx, topY)) }) {
        glow(Offset(cx, hubY + r * 0.3f), r * 1.7f, FlameGlow, 0.10f * a)

        val linkH = 6f * dp
        for (i in 0 until (chain / linkH).toInt()) { // chain links, alternately face-on and edge-on
            val lw = if (i % 2 == 0) 4.5f * dp else 1.6f * dp
            drawOval(iron, Offset(cx - lw / 2f, topY + i * linkH), Size(lw, linkH * 1.3f), style = Stroke(1.2f * dp))
        }

        val p = tl.scratch
        p.reset()
        p.moveTo(cx, hubY)
        p.lineTo(cx, hubY + r * 0.42f)
        for (i in -2..2) {
            if (i == 0) continue
            val k = i / 2f
            val cupY = hubY + r * 0.42f - r * 0.28f * k * k
            p.moveTo(cx, hubY + r * 0.1f)
            p.cubicTo(cx + k * r * 0.15f, hubY + r * 0.65f, cx + k * r * 0.8f, hubY + r * 0.65f, cx + k * r, cupY + cupH)
        }
        drawPath(p, iron, style = Stroke(2f * dp, cap = StrokeCap.Round))
        drawCircle(iron, 3.5f * dp, Offset(cx, hubY))

        for (i in -2..2) {
            val k = i / 2f
            val cupX = cx + k * r
            val cupY = hubY + r * 0.42f - r * 0.28f * k * k
            drawRoundRect(iron, Offset(cupX - cupW / 2f, cupY), Size(cupW, cupH), CornerRadius(cupH * 0.4f))
            drawCandle(cupX, cupY, r * 0.075f, r * (0.2f + 0.08f * rnd(i + 5, 110 + (ph * 10f).toInt())), t, ph + i * 1.9f, a, tl)
            val tw = 0.6f + 0.4f * sin(t * 3f + ph + i)
            drawLine(iron, Offset(cupX, cupY + cupH), Offset(cupX, cupY + cupH + 7f * dp), 1f * dp)
            drawCircle(tl.snow.copy(alpha = 0.55f * tw * a), 2.4f * dp, Offset(cupX, cupY + cupH + 9f * dp))
        }
    }
}

private fun DrawScope.drawBat(x: Float, y: Float, s: Float, flap: Float, color: Color, p: Path) {
    p.reset()
    for (side in intArrayOf(1, -1)) {
        val k = side.toFloat()
        p.moveTo(0.1f * k, -0.05f)
        p.quadraticTo(0.55f * k, -1f * flap, 1.35f * k, -0.45f * flap)
        p.quadraticTo(1.05f * k, -0.05f * flap + 0.1f, 0.95f * k, 0.2f)
        p.quadraticTo(0.7f * k, 0f, 0.5f * k, 0.25f)
        p.quadraticTo(0.3f * k, 0.05f, 0.1f * k, 0.3f)
        p.close()
    }
    withTransform({ translate(x, y); scale(s, s, Offset.Zero) }) {
        drawPath(p, color)
        drawCircle(color, 0.2f, Offset(0f, 0.05f))
    }
}

/** Jack-o'-lantern: five overlapping lobes (their overlaps read as ribs) and a face that flickers like a candle. */
private fun DrawScope.drawPumpkin(cx: Float, cy: Float, r: Float, t: Float, p: Path) {
    val flicker = (0.5f + 0.5f * sin(t * 7f) * sin(t * 3.1f)).coerceIn(0f, 1f)
    drawCircle(
        Brush.radialGradient(listOf(Color(0xFFFF9A2E).copy(alpha = 0.16f + 0.08f * flicker), Color.Transparent), Offset(cx, cy), r * 1.7f),
        r * 1.7f, Offset(cx, cy),
    )
    val body = Color(0xFFFF7A18).copy(alpha = 0.22f)
    val xs = floatArrayOf(-0.62f, 0.62f, -0.3f, 0.3f, 0f)
    val ws = floatArrayOf(1.1f, 1.1f, 1.2f, 1.2f, 1.1f)
    for (k in 0 until 5) drawOval(body, Offset(cx + xs[k] * r - ws[k] * r / 2f, cy - r * 0.8f), Size(ws[k] * r, r * 1.6f))
    drawRoundRect(Color(0xFF6B8E23).copy(alpha = 0.55f), Offset(cx - r * 0.08f, cy - r * 1.05f), Size(r * 0.16f, r * 0.32f), CornerRadius(r * 0.05f))

    p.reset()
    // eyes
    p.moveTo(cx - 0.5f * r, cy - 0.05f * r); p.lineTo(cx - 0.2f * r, cy - 0.05f * r); p.lineTo(cx - 0.35f * r, cy - 0.4f * r); p.close()
    p.moveTo(cx + 0.5f * r, cy - 0.05f * r); p.lineTo(cx + 0.2f * r, cy - 0.05f * r); p.lineTo(cx + 0.35f * r, cy - 0.4f * r); p.close()
    // nose
    p.moveTo(cx, cy + 0.02f * r); p.lineTo(cx - 0.08f * r, cy + 0.17f * r); p.lineTo(cx + 0.08f * r, cy + 0.17f * r); p.close()
    // toothy grin
    val top = floatArrayOf(-0.62f, 0.28f, -0.4f, 0.4f, -0.2f, 0.34f, 0f, 0.44f, 0.2f, 0.34f, 0.4f, 0.4f, 0.62f, 0.28f)
    p.moveTo(cx + top[0] * r, cy + top[1] * r)
    for (k in 1 until top.size / 2) p.lineTo(cx + top[k * 2] * r, cy + top[k * 2 + 1] * r)
    val bottom = floatArrayOf(0.4f, 0.62f, 0.2f, 0.56f, 0f, 0.66f, -0.2f, 0.56f, -0.4f, 0.62f)
    for (k in 0 until bottom.size / 2) p.lineTo(cx + bottom[k * 2] * r, cy + bottom[k * 2 + 1] * r)
    p.close()
    drawPath(p, Color(0xFFFFD54F).copy(alpha = 0.32f + 0.3f * flicker))
}

// ================================================================================================ christmas

private fun DrawScope.christmas(t: Float, front: Boolean, tl: Tools) {
    val w = size.width; val h = size.height; val cx = w / 2f; val cy = h / 2f; val u = min(w, h)
    val dp = density
    val fade = if (front) 0.7f else 1f
    val seed = if (front) 1000 else 0

    if (!front) {
        drawCircle(
            Brush.radialGradient(listOf(Color(0xFF6FB7FF).copy(alpha = 0.16f), Color.Transparent), Offset(cx, cy), u * 0.6f),
            u * 0.6f, Offset(cx, cy),
        )
        snowflake(cx, cy, u * 0.34f, t * 5f, tl.snow.copy(alpha = 0.22f + 0.05f * sin(t)), 3f * dp)
        // a ring of twinkling lights
        for (i in 0 until 14) {
            val a = i / 14f * TAU + t * 0.1f
            val tw = 0.5f + 0.5f * sin(t * 2.4f + i * 1.3f)
            val c = if (i % 2 == 0) Color(0xFFFF5252) else Color(0xFF69F0AE)
            drawCircle(c.copy(alpha = 0.10f + 0.28f * tw), 3.2f * dp, Offset(cx + cos(a) * u * 0.46f, cy + sin(a) * u * 0.46f))
        }
    }

    for (i in 0 until if (front) 12 else 40) {
        val s = i + seed
        val y = frac(rnd(s, 2) + t * (0.04f + rnd(s, 1) * 0.05f))
        val x = rnd(s, 3) + sin(t * 0.7f + rnd(s, 4) * TAU) * 0.035f
        drawCircle(tl.snow.copy(alpha = 0.6f * edge(y) * fade), (1.5f + rnd(s, 5) * 3.2f) * dp, Offset(x * w, y * h))
    }
}

private fun DrawScope.snowflake(cx: Float, cy: Float, r: Float, rotDeg: Float, color: Color, sw: Float) {
    val c = Offset(cx, cy)
    rotate(rotDeg, c) {
        for (k in 0 until 6) {
            rotate(k * 60f, c) {
                drawLine(color, c, Offset(cx, cy - r), sw, StrokeCap.Round)
                for (b in 0 until 3) {
                    val f = 0.38f + b * 0.23f
                    val by = cy - r * f
                    val bl = r * 0.22f * (1.2f - f * 0.5f)
                    drawLine(color, Offset(cx, by), Offset(cx - bl * 0.8f, by - bl), sw * 0.8f, StrokeCap.Round)
                    drawLine(color, Offset(cx, by), Offset(cx + bl * 0.8f, by - bl), sw * 0.8f, StrokeCap.Round)
                }
            }
        }
    }
}

// ================================================================================================ new year

private val NyColors = arrayOf(Color(0xFFFFC83D), Color(0xFFFF5C93), Color(0xFF4DD0E1), Color(0xFFB388FF))

private fun DrawScope.newYear(t: Float, front: Boolean, year: TextLayoutResult?) {
    val w = size.width; val h = size.height; val cx = w / 2f; val cy = h / 2f; val u = min(w, h)
    val dp = density
    val seed = if (front) 1000 else 0

    if (!front) {
        year?.let {
            drawText(it, NyColors[0], Offset(cx - it.size.width / 2f, cy - it.size.height / 2f), 0.10f + 0.05f * sin(t * 2f))
        }
        burst(cx, cy, u * 0.42f, frac(t / 3.6f), 32, 0)
    }
    for (k in 0 until if (front) 1 else 3) {
        val c = t / (2.4f + k * 0.7f) + k * 0.37f + seed * 0.001f
        val idx = floor(c).toInt()
        val bx = w * (0.15f + 0.7f * rnd(idx * 7 + k + seed, 21))
        val by = h * (0.12f + 0.5f * rnd(idx * 7 + k + seed, 22))
        burst(bx, by, u * (if (front) 0.2f else 0.26f), c - floor(c), 22, idx + k + 3 + seed)
    }

    for (i in 0 until if (front) 10 else 30) {
        val s = i + seed
        val y = frac(rnd(s, 3) + t * (0.05f + rnd(s, 2) * 0.07f))
        val x = rnd(s, 4) + sin(t * 0.9f + rnd(s, 5) * TAU) * 0.04f
        val cw = (5f + 4f * rnd(s, 6)) * dp
        val c = Offset(x * w, y * h)
        rotate(t * (80f + 160f * rnd(s, 7)) + rnd(s, 8) * 360f, c) {
            drawRect(NyColors[s % 4].copy(alpha = 0.55f * edge(y)), Offset(c.x - cw / 2f, c.y - cw / 4f), Size(cw, cw / 2f))
        }
    }
}

private fun DrawScope.burst(cx: Float, cy: Float, radius: Float, p: Float, sparks: Int, seed: Int) {
    if (p > 0.97f) return
    val e = 1f - (1f - p) * (1f - p) * (1f - p)
    val a = 1f - p
    val sag = p * p * radius * 0.35f
    for (i in 0 until sparks) {
        val ang = i.toFloat() / sparks * TAU + rnd(seed, i) * 0.2f
        val r = radius * (0.75f + 0.25f * rnd(seed + 5, i)) * e
        val dx = cos(ang); val dy = sin(ang)
        val c = NyColors[(i + seed) % 4].copy(alpha = 0.7f * a)
        val head = Offset(cx + dx * r, cy + dy * r + sag)
        drawLine(c, Offset(cx + dx * r * 0.82f, cy + dy * r * 0.82f + sag * 0.9f), head, 2f * density, StrokeCap.Round)
        drawCircle(c, 2.2f * density * (1f - 0.5f * p), head)
    }
}

// ================================================================================================ valentine's

private val ValColors = arrayOf(Color(0xFFFF4D6D), Color(0xFFFF8FA3), Color(0xFFE0245E), Color(0xFFFFB3C1))

private fun DrawScope.valentines(t: Float, front: Boolean, tl: Tools) {
    val w = size.width; val h = size.height; val cx = w / 2f; val cy = h / 2f; val u = min(w, h)
    val dp = density
    val fade = if (front) 0.7f else 1f
    val seed = if (front) 1000 else 0

    if (!front) {
        // lub-dub
        val ph = frac(t / 1.3f)
        val beat = exp(-((ph - 0.12f) * 16f) * ((ph - 0.12f) * 16f)) + 0.7f * exp(-((ph - 0.32f) * 14f) * ((ph - 0.32f) * 14f))
        drawCircle(
            Brush.radialGradient(listOf(ValColors[0].copy(alpha = 0.14f + 0.06f * beat), Color.Transparent), Offset(cx, cy), u * 0.6f),
            u * 0.6f, Offset(cx, cy),
        )
        drawHeartAt(tl.heart, cx, cy, u * 0.25f * (1.3f + 0.1f * beat), 0f, ValColors[1].copy(alpha = 0.06f + 0.05f * beat))
        drawHeartAt(tl.heart, cx, cy, u * 0.25f * (1f + 0.09f * beat), 0f, ValColors[0].copy(alpha = 0.24f))
    }

    for (i in 0 until if (front) 7 else 20) {
        val s = i + seed
        val y = 1f - frac(rnd(s, 2) + t * (0.03f + rnd(s, 1) * 0.04f))
        val x = rnd(s, 3) + sin(t * 0.9f + rnd(s, 4) * TAU) * 0.04f
        drawHeartAt(
            tl.heart, x * w, y * h, (7f + 14f * rnd(s, 5)) * dp, sin(t + rnd(s, 6) * TAU) * 14f,
            ValColors[s % 4].copy(alpha = 0.4f * edge(y) * fade),
        )
    }
}

// ================================================================================================ st. patrick's

private val Green = Color(0xFF2DBE60)

private fun DrawScope.shamrock(heart: Path, x: Float, y: Float, s: Float, rot: Float, leaves: Int, color: Color) {
    val step = 360f / leaves
    withTransform({ translate(x, y); rotate(rot, Offset.Zero) }) {
        for (k in 0 until leaves) {
            withTransform({ rotate(k * step, Offset.Zero) }) { drawHeartAt(heart, 0f, -0.9f * s, s, 0f, color) } // tip on the pivot
        }
        drawLine(color, Offset.Zero, Offset(0.25f * s, 2.4f * s), 0.22f * s, StrokeCap.Round)
    }
}

private fun DrawScope.stPatricks(t: Float, front: Boolean, tl: Tools) {
    val w = size.width; val h = size.height; val cx = w / 2f; val cy = h / 2f; val u = min(w, h)
    val dp = density
    val fade = if (front) 0.7f else 1f
    val seed = if (front) 1000 else 0

    if (!front) {
        drawCircle(
            Brush.radialGradient(listOf(Green.copy(alpha = 0.16f), Color.Transparent), Offset(cx, cy), u * 0.6f),
            u * 0.6f, Offset(cx, cy),
        )
        shamrock(tl.heart, cx, cy - u * 0.05f, u * 0.12f, sin(t * 0.7f) * 8f, 4, Green.copy(alpha = 0.26f))
    }

    for (i in 0 until if (front) 6 else 16) {
        val s = i + seed
        val y = frac(rnd(s, 2) + t * (0.04f + rnd(s, 1) * 0.05f))
        val x = rnd(s, 3) + sin(t * 0.8f + rnd(s, 4) * TAU) * 0.05f
        shamrock(
            tl.heart, x * w, y * h, (3.5f + 4f * rnd(s, 5)) * dp, t * (20f + 40f * rnd(s, 6)) * (if (rnd(s, 7) > 0.5f) 1f else -1f),
            3, Green.copy(alpha = 0.4f * edge(y) * fade),
        )
    }
    if (!front) {
        for (i in 0 until 8) { // gold coins
            val y = frac(rnd(i, 12) + t * (0.05f + rnd(i, 11) * 0.04f))
            val x = rnd(i, 13) + sin(t * 0.6f + rnd(i, 14) * TAU) * 0.03f
            val r = (5f + 4f * rnd(i, 15)) * dp
            val squash = abs(cos(t * 1.5f + rnd(i, 16) * TAU)) * 0.8f + 0.2f // spinning coin
            val a = 0.5f * edge(y)
            val o = Offset(x * w, y * h)
            drawOval(Color(0xFFFFC107).copy(alpha = a), Offset(o.x - r * squash, o.y - r), Size(2f * r * squash, 2f * r))
            drawOval(Color(0xFFFFF59D).copy(alpha = a), Offset(o.x - r * 0.6f * squash, o.y - r * 0.6f), Size(1.2f * r * squash, 1.2f * r), style = Stroke(1.2f * dp))
        }
    }
}

// ================================================================================================ easter

private val EggBase = arrayOf(Color(0xFFFF8FB1), Color(0xFF7CC4F5), Color(0xFFFFD34E), Color(0xFF7FE0A0), Color(0xFFB794F4))

private fun DrawScope.easter(t: Float, front: Boolean, tl: Tools) {
    val w = size.width; val h = size.height; val cx = w / 2f; val cy = h / 2f; val u = min(w, h)
    val dp = density
    val fade = if (front) 0.7f else 1f
    val seed = if (front) 1000 else 0

    if (!front) {
        drawCircle(
            Brush.radialGradient(listOf(EggBase[4].copy(alpha = 0.16f), Color.Transparent), Offset(cx, cy), u * 0.6f),
            u * 0.6f, Offset(cx, cy),
        )
        // the big egg rocks side to side, a little hop on every swing
        val rock = sin(t * 1.6f)
        drawEgg(
            tl.egg, cx, cy - abs(rock) * u * 0.02f, u * 0.34f, rock * 7f,
            EggBase[0], Color.White, 0.30f, detailed = true,
        )
        // petals
        for (i in 0 until 10) {
            val y = frac(rnd(i, 32) + t * (0.03f + rnd(i, 31) * 0.03f))
            val x = rnd(i, 33) + sin(t * 0.7f + rnd(i, 34) * TAU) * 0.05f
            val pr = (4f + 3f * rnd(i, 35)) * dp
            rotate(t * 40f * (rnd(i, 36) - 0.5f) * 4f, Offset(x * w, y * h)) {
                drawOval(Color(0xFFFFB6C9).copy(alpha = 0.45f * edge(y)), Offset(x * w - pr, y * h - pr * 0.6f), Size(pr * 2f, pr * 1.2f))
            }
        }
    }

    for (i in 0 until if (front) 4 else 10) {
        val s = i + seed
        val y = 1f - frac(rnd(s, 2) + t * (0.025f + rnd(s, 1) * 0.03f))
        val x = rnd(s, 3) + sin(t * 0.8f + rnd(s, 4) * TAU) * 0.04f
        drawEgg(
            tl.egg, x * w, y * h, (12f + 12f * rnd(s, 5)) * dp, sin(t * 1.2f + rnd(s, 6) * TAU) * 15f,
            EggBase[s % 5], Color.White, 0.42f * edge(y) * fade, detailed = false,
        )
    }
}
