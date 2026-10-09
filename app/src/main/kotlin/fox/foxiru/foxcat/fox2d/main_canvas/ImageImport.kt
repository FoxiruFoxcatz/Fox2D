package fox.foxiru.foxcat.fox2d.main_canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import fox.foxiru.foxcat.fox2d.jnicallers.NativeCanvas
import fox.foxiru.foxcat.fox2d.project.ProjectRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** An imported picture inside a layer. Layer paper units (0..1, y down): [cx],[cy] centre, [w],[h] size, [rot] degrees clockwise. */
@Immutable
data class ImagePlace(val file: String, val cx: Float, val cy: Float, val w: Float, val h: Float, val rot: Float)

/** file name (inside <project>/images) -> native pixel handle + a Compose bitmap for the timeline thumbnails. */
object ImageStore {
    private val handles = ConcurrentHashMap<String, Int>()
    private val bitmaps = ConcurrentHashMap<String, ImageBitmap>()

    fun handleOf(file: String): Int = handles[file] ?: -1
    fun bitmap(file: String): ImageBitmap? = bitmaps[file]

    fun register(file: String, src: Bitmap): Boolean {
        if (handles.containsKey(file)) return true
        val bmp = if (src.config == Bitmap.Config.ARGB_8888) src else (src.copy(Bitmap.Config.ARGB_8888, false) ?: return false)
        val buf = ByteBuffer.allocateDirect(bmp.byteCount)
        bmp.copyPixelsToBuffer(buf)          // premultiplied R,G,B,A bytes = what the GL side expects
        buf.rewind()
        val h = NativeCanvas.nativeImageLoad(bmp.width, bmp.height, buf)
        if (h < 0) return false
        handles[file] = h
        bitmaps[file] = bmp.asImageBitmap()
        return true
    }

    fun release(file: String) {
        bitmaps.remove(file)
        handles.remove(file)?.let { NativeCanvas.nativeImageRelease(it) }
    }

    fun releaseAll() = handles.keys.toList().forEach(::release)
}

internal object ImageIo {
    const val MAX_SIDE = 2048

    class Loaded(val name: String, val bitmap: Bitmap)

    fun decodeFile(f: File): Bitmap? = runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull()

    /** Decodes [uri] (downscaled to MAX_SIDE, EXIF-rotated) and stores it as <dir>/<uuid>.png|jpg. */
    fun importUri(ctx: Context, dir: File, uri: Uri): Loaded? = runCatching {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }!!
        val deg = exifDegrees(ctx, uri)
        val big = max(bmp.width, bmp.height)
        val k = if (big > MAX_SIDE) MAX_SIDE / big.toFloat() else 1f
        if (deg != 0 || k < 1f) {
            val m = android.graphics.Matrix().apply { postRotate(deg.toFloat()); postScale(k, k) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        dir.mkdirs()
        val alpha = bmp.hasAlpha()
        val name = UUID.randomUUID().toString() + if (alpha) ".png" else ".jpg"
        File(dir, name).outputStream().use {
            bmp.compress(if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, it)
        }
        Loaded(name, bmp)
    }.getOrNull()

    private fun exifDegrees(ctx: Context, uri: Uri): Int {
        if (Build.VERSION.SDK_INT < 24) return 0
        return runCatching {
            ctx.contentResolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        }.getOrDefault(0)
    }
}

/** The picture while it is being placed (not in history yet). */
@Stable
class ImagePlacing(
    val file: File, val name: String, val bmp: ImageBitmap,
    val track: Int, val cel: Int, val layer: Int,
    cx: Float, cy: Float, w: Float,
) {
    var cx by mutableFloatStateOf(cx)
    var cy by mutableFloatStateOf(cy)
    var w by mutableFloatStateOf(w)
    var rot by mutableFloatStateOf(0f)
    val aspect: Float = bmp.height / bmp.width.toFloat()
    val h: Float get() = w * aspect
}

/** Picker result -> decode -> store in the project -> start placing on the active row / layer / drawing. */
suspend fun EditorState.importImage(ctx: Context, uri: Uri) {
    val id = projectId ?: return
    val track = activeTrackObj ?: return
    val cel = currentCel ?: return
    if (track.locked) return
    val layer = activeLayer
    val dir = ProjectRepository.get(ctx).imageDir(id)
    val loaded = withContext(Dispatchers.IO) {
        ImageIo.importUri(ctx, dir, uri)?.takeIf { ImageStore.register(it.name, it.bitmap) }
    }
    val bmp = loaded?.let { ImageStore.bitmap(it.name) }
    if (loaded == null || bmp == null) { timeline.error = "Couldn't read that image"; return }

    val aspect = bmp.height / bmp.width.toFloat()
    val w0 = if (aspect <= 1f) 0.6f else 0.6f / aspect              // fits 60% of the paper
    val c = trackXf(track.id).invert(Offset(0.5f, 0.5f))            // starts under the middle of the screen
    playing = false
    tool = Tool.Brush
    transformOpen = false
    panel = Panel.None
    timeline.selectedClip = -1
    placing = ImagePlacing(File(dir, loaded.name), loaded.name, bmp, track.id, cel.id, layer, c.x, c.y, w0)
}

/** Timeline thumbnails / Compose fallback. [w] = paper side in px. */
internal fun DrawScope.drawPlacedImage(im: ImagePlace, w: Float) {
    val bmp = ImageStore.bitmap(im.file) ?: return
    withTransform({ translate(im.cx * w, im.cy * w); rotate(im.rot, Offset.Zero) }) {
        drawImage(
            bmp, srcOffset = IntOffset.Zero, srcSize = IntSize(bmp.width, bmp.height),
            dstOffset = IntOffset((-im.w * w / 2f).roundToInt(), (-im.h * w / 2f).roundToInt()),
            dstSize = IntSize((im.w * w).roundToInt().coerceAtLeast(1), (im.h * w).roundToInt().coerceAtLeast(1)),
        )
    }
}

// ------------------------------------------------------------------------------------------ placement overlay

private const val D2R = 0.017453292f
private fun pWrap(d: Float): Float { var r = d % 360f; if (r > 180f) r -= 360f; if (r < -180f) r += 360f; return r }
private fun pSnap(d: Float): Float { val s = (d / 15f).roundToInt() * 15f; return if (abs(d - s) < 2f) s else d }

private class PGeo(val c: List<Offset>, val rotHandle: Offset, val top: Offset)

/** layer point -> screen: row transform, then the view. */
private fun placeGeo(m: PaperMap, xf: LayerXf, p: ImagePlacing, gap: Float): PGeo {
    val r = p.rot * D2R
    val cs = cos(r); val sn = sin(r)
    fun at(dx: Float, dy: Float) = m.toScreen(xf.apply(Offset(p.cx + cs * dx - sn * dy, p.cy + sn * dx + cs * dy)))
    val hw = p.w / 2f; val hh = p.h / 2f
    val c = listOf(at(-hw, -hh), at(hw, -hh), at(hw, hh), at(-hw, hh))
    val top = (c[0] + c[1]) / 2f
    val d = top - (c[0] + c[2]) / 2f
    val l = d.getDistance()
    val up = if (l < 1e-3f) Offset(0f, -1f) else d / l
    return PGeo(c, top + up * gap, top)
}

private fun insideQuad(c: List<Offset>, p: Offset): Boolean {
    var pos = false; var neg = false
    for (i in 0..3) {
        val a = c[i]; val b = c[(i + 1) % 4]
        val cr = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
        if (cr > 0f) pos = true else if (cr < 0f) neg = true
    }
    return !(pos && neg)
}

private enum class PGrab { Move, Scale, Rotate, Nav }

@Composable
internal fun ImagePlaceOverlay(state: EditorState, viewSize: IntSize) {
    val p = state.placing ?: return
    val vp = state.viewport
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val hitPx = with(density) { 28.dp.toPx() }
    val gapPx = with(density) { 36.dp.toPx() }
    val accent = cs.primary
    fun map() = PaperMap(viewSize.width.toFloat(), viewSize.height.toFloat(), vp.scale, vp.rotation, vp.offsetX, vp.offsetY)

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(p, viewSize) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val xf = state.trackXf(p.track)
                    fun toLayer(m: PaperMap, s: Offset) = xf.invert(m.toPaper(s))
                    val g0 = placeGeo(map(), xf, p, gapPx)
                    val grab = when {
                        (down.position - g0.rotHandle).getDistance() < hitPx -> PGrab.Rotate
                        g0.c.any { (down.position - it).getDistance() < hitPx } -> PGrab.Scale
                        insideQuad(g0.c, down.position) -> PGrab.Move
                        else -> PGrab.Nav                       // touch off the image: pan / zoom the view
                    }
                    val nav = ViewNav(vp)
                    val navigating = grab == PGrab.Nav
                    var rawRot = p.rot
                    if (!navigating) down.consume()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (navigating) {
                            nav.step(ev, viewSize)
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                            continue
                        }
                        val m = map()
                        if (pressed.size >= 2) {                                 // pinch = size, twist = rotate, drag = move
                            val zoom = ev.calculateZoom(); val twist = ev.calculateRotation()
                            val pan = ev.calculatePan(); val cen = ev.calculateCentroid()
                            val a = toLayer(m, cen - pan); val b = toLayer(m, cen)
                            p.cx += b.x - a.x; p.cy += b.y - a.y
                            p.w = (p.w * zoom).coerceIn(0.02f, 8f)
                            rawRot = pWrap(rawRot + twist); p.rot = pSnap(rawRot)
                        } else {
                            val c = pressed[0]
                            if (c.position != c.previousPosition) {
                                val a = toLayer(m, c.previousPosition); val b = toLayer(m, c.position)
                                val ctr = Offset(p.cx, p.cy)
                                when (grab) {
                                    PGrab.Move -> { p.cx += b.x - a.x; p.cy += b.y - a.y }
                                    PGrab.Scale -> {
                                        val d0 = (a - ctr).getDistance(); val d1 = (b - ctr).getDistance()
                                        if (d0 > 1e-4f) p.w = (p.w * d1 / d0).coerceIn(0.02f, 8f)
                                    }
                                    PGrab.Rotate -> {
                                        val a0 = atan2(a.y - ctr.y, a.x - ctr.x); val a1 = atan2(b.y - ctr.y, b.x - ctr.x)
                                        rawRot = pWrap(rawRot + pWrap(((a1 - a0) * 180.0 / PI).toFloat()))
                                        p.rot = pSnap(rawRot)
                                    }
                                    PGrab.Nav -> Unit
                                }
                            }
                        }
                        ev.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                    nav.end()
                }
            },
    ) {
        val g = placeGeo(map(), state.trackXf(p.track), p, gapPx)
        // picture: bitmap pixel space -> screen is affine (row transform * view), so one matrix draws it
        val ax = (g.c[1] - g.c[0]) / p.bmp.width.toFloat()
        val ay = (g.c[3] - g.c[0]) / p.bmp.height.toFloat()
        val mtx = Matrix(floatArrayOf(ax.x, ax.y, 0f, 0f, ay.x, ay.y, 0f, 0f, 0f, 0f, 1f, 0f, g.c[0].x, g.c[0].y, 0f, 1f))
        withTransform({ transform(mtx) }) { drawImage(p.bmp) }

        val box = Path().apply { moveTo(g.c[0].x, g.c[0].y); for (i in 1..3) lineTo(g.c[i].x, g.c[i].y); close() }
        drawPath(box, accent, style = Stroke(2.dp.toPx()))
        drawLine(accent, g.top, g.rotHandle, 2.dp.toPx(), StrokeCap.Round)
        drawCircle(cs.surface, 11.dp.toPx(), g.rotHandle)
        drawCircle(accent, 11.dp.toPx(), g.rotHandle, style = Stroke(2.dp.toPx()))
        drawCircle(accent, 3.5f.dp.toPx(), g.rotHandle)
        for (c in g.c) {
            drawCircle(cs.surface, 9.dp.toPx(), c)
            drawCircle(accent, 9.dp.toPx(), c, style = Stroke(2.dp.toPx()))
        }
    }
}

// ------------------------------------------------------------------------------------------ placement panel

@Composable
private fun PlaceSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, text: String, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(60.dp), style = MaterialTheme.typography.labelMedium)
        Slider(value.coerceIn(range), onChange, Modifier.weight(1f).height(28.dp), valueRange = range)
        Text(text, Modifier.widthIn(min = 48.dp).padding(horizontal = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

/** Replaces the player + timeline in the Dock while an image is being placed. */
@Composable
internal fun ImagePlacePanel(state: EditorState) {
    val p = state.placing ?: return
    val tight = PaddingValues(horizontal = 8.dp)
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { state.cancelPlacing() }, contentPadding = tight, modifier = Modifier.height(34.dp)) { Text("Cancel") }
            Text("Place image", Modifier.weight(1f).padding(start = 6.dp), style = MaterialTheme.typography.titleSmall)
            FilledTonalButton(onClick = { state.applyPlacing() }, contentPadding = PaddingValues(horizontal = 14.dp), modifier = Modifier.height(34.dp)) { Text("Apply") }
        }
        PlaceSlider("Size", p.w * 100f, 2f..400f, "${(p.w * 100f).roundToInt()}%") { p.w = it / 100f }
        PlaceSlider("Rotate", p.rot, -180f..180f, "%.0f\u00B0".format(p.rot)) { p.rot = it }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { p.w = if (p.aspect <= 1f) 1f else 1f / p.aspect }, contentPadding = tight, modifier = Modifier.height(34.dp)) { Text("Fit paper") }
            TextButton(onClick = {
                val c = state.trackXf(p.track).invert(Offset(0.5f, 0.5f)); p.cx = c.x; p.cy = c.y
            }, contentPadding = tight, modifier = Modifier.height(34.dp)) { Text("Center") }
            TextButton(onClick = { p.rot = 0f }, contentPadding = tight, modifier = Modifier.height(34.dp)) { Text("Reset angle") }
        }
        Text("Drag = move \u00B7 corners = size \u00B7 knob = rotate \u00B7 two fingers = pinch / twist \u00B7 off the image = pan view",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}