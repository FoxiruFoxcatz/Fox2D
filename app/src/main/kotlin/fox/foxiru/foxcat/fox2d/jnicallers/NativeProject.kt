package fox.foxiru.foxcat.fox2d.jnicallers

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import fox.foxiru.foxcat.fox2d.main_canvas.ImagePlace
import fox.foxiru.foxcat.fox2d.main_canvas.InkStroke
import java.nio.ByteOrder

/**
 * JNI bridge to fox_project.cpp (same shared library as the canvas): strokes.bin is inflated and parsed in C++ into flat
 * arrays. [loadStrokes] returns a handle; [toStrokes] builds the Kotlin model from it in one pass, and the canvas can be
 * fed from the same handle ([NativeCanvas.addStrokesFromBlob]) so the points are never converted back to arrays.
 * Always [release] the handle.
 */
object NativeProject {
    init { AudioNative.loadError }   // touching AudioNative loads the shared library

    @JvmStatic external fun nativeLoadStrokes(path: String): Long
    @JvmStatic external fun nativeError(): String
    @JvmStatic external fun nativeCount(h: Long): Int
    @JvmStatic external fun nativeMeta(h: Long): IntArray               // 6 ints per stroke: cel, layer, argb, flags, a, b
    @JvmStatic external fun nativeSizes(h: Long): FloatArray
    @JvmStatic external fun nativePoints(h: Long): java.nio.ByteBuffer? // direct, host byte order, valid until release
    @JvmStatic external fun nativeImageFiles(h: Long): Array<String>
    @JvmStatic external fun nativeImageData(h: Long): FloatArray        // 5 floats per image stroke
    @JvmStatic external fun nativeRelease(h: Long)

    private const val FLAG_ERASE = 1
    private const val FLAG_IMAGE = 2

    /** Blocking (Dispatchers.IO). 0 = native loader missing / file corrupt: the caller falls back to the Kotlin reader. */
    fun loadStrokes(path: String): Long =
        try { nativeLoadStrokes(path) } catch (_: UnsatisfiedLinkError) { 0L }

    fun release(h: Long) { if (h != 0L) nativeRelease(h) }

    /** Same list the Kotlin reader would return (ProjectCodec.readStrokes). */
    fun toStrokes(h: Long): List<InkStroke> {
        val n = nativeCount(h)
        val meta = nativeMeta(h)
        val sizes = nativeSizes(h)
        val fb = nativePoints(h)?.order(ByteOrder.nativeOrder())?.asFloatBuffer()
        val files = nativeImageFiles(h)
        val img = nativeImageData(h)
        val out = ArrayList<InkStroke>(n)
        var tmp = FloatArray(0)
        for (i in 0 until n) {
            val o = i * 6
            val cel = meta[o]; val layer = meta[o + 1]; val color = Color(meta[o + 2]); val flags = meta[o + 3]
            val a = meta[o + 4]; val b = meta[o + 5]
            val erase = flags and FLAG_ERASE != 0
            if (flags and FLAG_IMAGE != 0) {
                out.add(InkStroke(cel, layer, color, sizes[i], emptyList(), erase,
                    ImagePlace(files[a], img[b], img[b + 1], img[b + 2], img[b + 3], img[b + 4])))
            } else {
                if (tmp.size < b * 2) tmp = FloatArray(b * 2)
                if (b > 0) { val d = fb!!.duplicate(); d.position(a); d.get(tmp, 0, b * 2) }   // bulk copy, then box once
                val pts = ArrayList<Offset>(b)
                for (k in 0 until b) pts.add(Offset(tmp[k * 2], tmp[k * 2 + 1]))
                out.add(InkStroke(cel, color = color, layer = layer, size = sizes[i], pts = pts, erase = erase))
            }
        }
        return out
    }
}
