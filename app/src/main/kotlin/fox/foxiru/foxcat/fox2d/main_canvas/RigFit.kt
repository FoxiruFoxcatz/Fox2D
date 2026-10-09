package fox.foxiru.foxcat.fox2d.main_canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.Uri
import fox.foxiru.foxcat.fox2d.jnicallers.NativeCanvas

/**
 * Puppet Warp "Fit to image": the drawing of a row is bent so its outline matches the outline of a picked picture.
 * The maths (rays from both shapes' centres -> pins) runs in C++ (fox_fit.h); this file only builds the two small masks
 * and turns the answer into ordinary puppet-warp pins, so everything after it (Pose, keys, export) works as usual.
 */
enum class FitShape(val id: Int, val label: String) {
    /** Outline = where the picture is visible (alpha). */
    Alpha(0, "Alpha"),
    /** Outline = the dark pixels, transparent counts as background. For black shapes on white. */
    Dark(1, "Dark"),
    /** Outline = the light pixels, transparent counts as background. For white shapes on black. */
    Light(2, "Light"),
}

private const val FIT_N = 192

/** Decodes [uri] at most [maxSide] px on its long side. null = unreadable. */
fun decodeFitBitmap(ctx: Context, uri: Uri, maxSide: Int = 1024): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
}.getOrNull()

/** The drawing of [track] (current drawing only when it belongs to the track) as an n x n alpha mask over the layer's paper units. */
fun EditorState.trackMask(track: Int, n: Int = FIT_N): ByteArray? {
    val cur = currentCel
    val ids = HashSet<Int>()
    if (cur != null && cur.track == track) ids.add(cur.id) else for (c in cels) if (c.track == track) ids.add(c.id)
    val bm = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
    val cv = Canvas(bm)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL }
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    val clear = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    var any = false
    for (s in strokes) {
        if (s.cel !in ids) continue
        val im = s.image
        if (im != null) {
            if (s.erase) continue
            cv.save()
            cv.translate(im.cx * n, im.cy * n)
            cv.rotate(im.rot)
            cv.drawRect(RectF(-im.w * n / 2f, -im.h * n / 2f, im.w * n / 2f, im.h * n / 2f), fill)
            cv.restore()
            any = true
            continue
        }
        if (s.pts.isEmpty()) continue
        val w = maxOf(1f, s.size / 512f * n)
        val mode = if (s.erase) clear else null
        fill.xfermode = mode; line.xfermode = mode
        line.strokeWidth = w
        if (s.pts.size == 1) cv.drawCircle(s.pts[0].x * n, s.pts[0].y * n, w / 2f, fill)
        else {
            val path = android.graphics.Path()
            path.moveTo(s.pts[0].x * n, s.pts[0].y * n)
            for (i in 1 until s.pts.size) path.lineTo(s.pts[i].x * n, s.pts[i].y * n)
            cv.drawPath(path, line)
        }
        if (!s.erase) any = true
    }
    if (!any) { bm.recycle(); return null }
    val px = IntArray(n * n)
    bm.getPixels(px, 0, n, 0, 0, n, n)
    bm.recycle()
    return ByteArray(n * n) { (px[it] ushr 24).toByte() }
}

/** [bm] letterboxed into an n x n byte mask for [shape] (see fox_fit.h): alpha, or luma composited over the "background" colour. */
fun fitTargetBytes(bm: Bitmap, shape: FitShape, n: Int = FIT_N): ByteArray {
    val box = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
    val k = minOf(n.toFloat() / bm.width, n.toFloat() / bm.height)
    val w = bm.width * k; val h = bm.height * k
    Canvas(box).drawBitmap(bm, null, RectF((n - w) / 2f, (n - h) / 2f, (n + w) / 2f, (n + h) / 2f), Paint(Paint.FILTER_BITMAP_FLAG))
    val px = IntArray(n * n)
    box.getPixels(px, 0, n, 0, 0, n, n)
    box.recycle()
    val bg = if (shape == FitShape.Light) 0f else 255f   // transparent = the colour that is NOT the shape
    return ByteArray(n * n) {
        val p = px[it]
        val a = (p ushr 24) / 255f
        if (shape == FitShape.Alpha) (p ushr 24).toByte()
        else {
            val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
            // premultiplied-safe enough for a mask: luma blended toward the background by (1 - alpha)
            val luma = 0.299f * r + 0.587f * g + 0.114f * b
            (luma * a + bg * (1f - a)).toInt().coerceIn(0, 255).toByte()
        }
    }
}

/**
 * Replaces the pins of [track] with pins that pull the drawing's outline onto the outline of [target]. One undo step; switches
 * to Pose so the result is visible. [uniform] = keep the picture's aspect ratio instead of stretching it over the drawing's box.
 * false = nothing to fit (empty drawing, or no shape found in the picture).
 */
fun EditorState.fitToImage(track: Int, target: Bitmap, shape: FitShape, uniform: Boolean): Boolean {
    val src = trackMask(track) ?: return false
    val out = NativeCanvas.fitPins(src, fitTargetBytes(target, shape), FIT_N, shape.id, uniform, RigLimits.MAX_PINS)
    if (out.size < 5) return false
    val count = out[4].toInt()
    if (count < 3 || out.size < 5 + count * 4) return false
    val pins = ArrayList<Pin>(count)
    for (i in 0 until count) {
        val o = 5 + i * 4
        pins.add(Pin(out[o], out[o + 1], out[o + 2], out[o + 3]))
    }
    // the mesh must cover the drawing AND where it ends up (corners of the box stay fixed)
    val b = trackBounds(track)
    val m = 0.05f
    var l = out[0]; var t = out[1]; var r = out[2]; var bt = out[3]
    if (b != null) { l = minOf(l, b.left); t = minOf(t, b.top); r = maxOf(r, b.right); bt = maxOf(bt, b.bottom) }
    val rect = MeshRect((l - m).coerceAtLeast(0f), (t - m).coerceAtLeast(0f), (r + m).coerceAtMost(1f), (bt + m).coerceAtMost(1f))
    edit {
        setRig(track) { it.clearPins().withMesh(it.grid, rect).copy(pins = pins) }
        rigUi.pin = 0
        rigUi.mode = RigMode.Pose
    }
    return true
}

// ------------------------------------------------------------------------------------------------ outline mesh

/** Triangle mesh in layer paper units: [verts] = x y per vertex, [tris] = i j k per triangle (as floats, the native format). */
class TriMeshData(val verts: FloatArray, val tris: FloatArray) {
    val vertexCount get() = verts.size / 2
    val triangleCount get() = tris.size / 3
}

private val TRI_SPACING = floatArrayOf(0.06f, 0.04f, 0.025f)

/** Re-sends every stored outline mesh with the CURRENT timeline row numbers (rows can be reordered / deleted). */
fun EditorState.syncTriMeshes() {
    NativeCanvas.clearTriMeshes()
    for ((track, m) in rigUi.tri) {
        val row = nativeRowOf(track)
        if (row >= 0) NativeCanvas.setTriMesh(row, m.verts, m.tris)
    }
}

/**
 * Builds a triangle mesh that follows the drawing's outline (dense along the edge, evenly spread inside) and shows it as
 * GPU lines. false = nothing drawn on the track. The old square grid is not drawn for this track any more.
 */
fun EditorState.generateOutlineMesh(track: Int): Boolean {
    val n = 256
    val mask = trackMask(track, n) ?: return false
    val out = NativeCanvas.genMesh(mask, n, TRI_SPACING[rigUi.triDensity.coerceIn(0, 2)], 3)
    if (out.size < 2) return false
    val nv = out[0].toInt(); val nt = out[1].toInt()
    if (nv < 3 || nt < 1 || out.size < 2 + nv * 2 + nt * 3) return false
    rigUi.tri[track] = TriMeshData(out.copyOfRange(2, 2 + nv * 2), out.copyOfRange(2 + nv * 2, 2 + nv * 2 + nt * 3))
    rigUi.meshLines = true
    syncTriMeshes()
    return true
}

fun EditorState.clearOutlineMesh(track: Int) {
    rigUi.tri.remove(track)
    syncTriMeshes()
}

/**
 * Auto-place [count] pins spread evenly over the drawing (farthest-point sampling over the outline mesh vertices, starting at the
 * point nearest the centre). Builds the outline mesh first when there is none. One undo step; switches to Pose. false = no drawing.
 */
fun EditorState.autoPins(track: Int, count: Int): Boolean {
    if (rigUi.tri[track] == null && !generateOutlineMesh(track)) return false
    val m = rigUi.tri[track] ?: return false
    val nv = m.vertexCount
    val k = count.coerceIn(3, RigLimits.MAX_PINS)
    if (nv < k) return false
    var cx = 0f; var cy = 0f
    for (i in 0 until nv) { cx += m.verts[i * 2]; cy += m.verts[i * 2 + 1] }
    cx /= nv; cy /= nv
    var first = 0; var best = Float.MAX_VALUE
    for (i in 0 until nv) {
        val d = (m.verts[i * 2] - cx) * (m.verts[i * 2] - cx) + (m.verts[i * 2 + 1] - cy) * (m.verts[i * 2 + 1] - cy)
        if (d < best) { best = d; first = i }
    }
    val chosen = ArrayList<Int>(k)
    val minD = FloatArray(nv) { Float.MAX_VALUE }
    var next = first
    repeat(k) {
        chosen.add(next)
        val px = m.verts[next * 2]; val py = m.verts[next * 2 + 1]
        var far = -1f
        for (i in 0 until nv) {
            val d = (m.verts[i * 2] - px) * (m.verts[i * 2] - px) + (m.verts[i * 2 + 1] - py) * (m.verts[i * 2 + 1] - py)
            if (d < minD[i]) minD[i] = d
            if (minD[i] > far) { far = minD[i]; next = i }
        }
    }
    val pins = chosen.map { Pin(m.verts[it * 2], m.verts[it * 2 + 1]) }
    val b = trackBounds(track)
    val mg = 0.06f
    val rect = if (b == null) MeshRect() else MeshRect(
        (b.left - mg).coerceAtLeast(0f), (b.top - mg).coerceAtLeast(0f), (b.right + mg).coerceAtMost(1f), (b.bottom + mg).coerceAtMost(1f),
    )
    edit {
        setRig(track) { r ->
            val base = if (r.bones.isEmpty() && r.curves.isEmpty()) r.withMesh(r.grid, rect) else r
            base.clearPins().copy(pins = pins)
        }
        rigUi.pin = 0
        rigUi.mode = RigMode.Pose
    }
    return true
}
