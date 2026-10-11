package fox.foxiru.foxcat.fox2d.jnicallers

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * JNI bridge to libfoxcanvas.so (cpp/canvas_gl.cpp).
 *
 * Every setter is cheap and thread-safe: it only enqueues a command that the GL thread executes on the
 * next frame, then asks the GLSurfaceView for that frame. Stroke points are paper-space (0..1), exactly
 * what [fox.foxiru.foxcat.fox2d.main_canvas.CanvasViewport.toUnit] already produces.
 */
object NativeCanvas {

    // ---- GL thread (called by the renderer only) ----
    @JvmStatic external fun nativeSurfaceCreated(paperSize: Int)
    @JvmStatic external fun nativeSurfaceChanged(width: Int, height: Int)
    @JvmStatic external fun nativeDrawFrame()
    @JvmStatic external fun nativeMoreWork(): Boolean
    /** Raster cache: [dir] = where finished drawings spill to disk, [ramMb] = RAM kept for them. */
    @JvmStatic external fun nativeSetCache(dir: String, ramMb: Int)
    @JvmStatic external fun nativePrefetch(cels: IntArray)

    // ---- any thread ----
    @JvmStatic external fun nativeSetView(scale: Float, rotationDeg: Float, offX: Float, offY: Float)
    @JvmStatic external fun nativeSetTheme(bg: Int, checkerA: Int, checkerB: Int, density: Float)
    @JvmStatic external fun nativeBeginStroke(cel: Int, layer: Int, argb: Int, size: Float, erase: Boolean)
    @JvmStatic external fun nativeStrokePoint(x: Float, y: Float)
    // brush engine (FoxBrushEngine.cpp): per-stroke parameter array (layout = BP in BrushUI.kt), per-point pressure + ms
    @JvmStatic external fun nativeBeginStrokeEx(cel: Int, layer: Int, argb: Int, size: Float, erase: Boolean, params: FloatArray)
    @JvmStatic external fun nativeStrokePointEx(x: Float, y: Float, pressure: Float, tMs: Float)
    @JvmStatic external fun nativeAddStrokeEx(cel: Int, layer: Int, argb: Int, size: Float, erase: Boolean, xy: FloatArray, params: FloatArray, aux: FloatArray)
    /** CPU twin of the engine: ARGB pixels (w * h), or an empty array when the size is out of range. [samples] = quadruples (x px, y px, pressure, ms), empty = built-in test stroke. */
    @JvmStatic external fun nativeBrushPreview(params: FloatArray, argb: Int, w: Int, h: Int, unit: Float, samples: FloatArray): IntArray
    @JvmStatic external fun nativeEndStroke()
    @JvmStatic external fun nativeCancelStroke()
    @JvmStatic external fun nativeUndo()
    @JvmStatic external fun nativeRedo()
    @JvmStatic external fun nativeResetStrokes()
    @JvmStatic external fun nativeSetShown(cels: IntArray)
    @JvmStatic external fun nativeSetXf(ids: IntArray, vals: FloatArray)
    // onion skin (fox_onion.h); UI + planning live in OnionSkin.kt
    @JvmStatic external fun nativeSetOnion(row: Int, ghosts: IntArray, prm: FloatArray)

    // keyframe animation (maths lives in fox_anim.h)
    @JvmStatic external fun nativeSetAnim(perRow: Int, offsets: IntArray, counts: IntArray, statics: FloatArray, keys: FloatArray)
    @JvmStatic external fun nativeSetTime(frame: Float)
    @JvmStatic external fun nativeEvalXf(keys: FloatArray, n: Int, local: Float, base: FloatArray): FloatArray
    @JvmStatic external fun nativeEaseCurve(id: Int, x1: Float, y1: Float, x2: Float, y2: Float, n: Int): FloatArray
    // KeyGraph editor (maths lives in fox_graph.h)
    @JvmStatic external fun nativeSampleLane(keys: FloatArray, n: Int, f0: Float, f1: Float, out: FloatArray)
    @JvmStatic external fun nativeSmoothKeys(keys: FloatArray, n: Int, chan: Int, mode: Int, strength: Float, flatEnds: Boolean, frameLo: Float, frameHi: Float): FloatArray
    @JvmStatic external fun nativeSplitEase(id: Int, x1: Float, y1: Float, x2: Float, y2: Float, t: Float): FloatArray
    @JvmStatic external fun nativeFitEase(id: Int): FloatArray
    @JvmStatic external fun nativeSetLayers(ids: IntArray, visible: BooleanArray)
    @JvmStatic external fun nativeSetFx(lMode: IntArray, lOpacity: FloatArray, lClip: BooleanArray, rMode: IntArray, rOpacity: FloatArray, rClip: BooleanArray)
    @JvmStatic external fun nativeSetGroupFx(rowGroup: IntArray, gIds: IntArray, gMode: IntArray, gOpacity: FloatArray, gClip: BooleanArray)
    @JvmStatic external fun nativeAddStroke(cel: Int, layer: Int, argb: Int, size: Float, erase: Boolean, xy: FloatArray)
    @JvmStatic external fun nativeAddStrokesFromBlob(blob: Long, celRow: IntArray, layerTab: IntArray, slots: Int, imgHandles: IntArray): Boolean

    // rigging + deformation (maths lives in fox_rig.h)
    @JvmStatic external fun nativeSetRig(blob: FloatArray)
    @JvmStatic external fun nativeSetRigPosed(on: Boolean)
    @JvmStatic external fun nativeEvalRig(row: FloatArray, local: Float, rest: Boolean): FloatArray
    @JvmStatic external fun nativeRigWeights(row: FloatArray, bone: Int): FloatArray
    @JvmStatic external fun nativeSetMeshLines(on: Boolean, row: Int)
    @JvmStatic external fun nativeGenMesh(mask: ByteArray, n: Int, spacing: Float, dilatePx: Int): FloatArray
    @JvmStatic external fun nativeSetTriMesh(row: Int, verts: FloatArray, tris: FloatArray)
    @JvmStatic external fun nativeClearTriMeshes()
    @JvmStatic external fun nativeFitPins(src: ByteArray, tgt: ByteArray, n: Int, mode: Int, uniform: Boolean, maxPins: Int): FloatArray
    @JvmStatic external fun nativeUnwarp(row: Int, x: Float, y: Float): FloatArray
    @JvmStatic external fun nativeSetAttach(a: IntArray)
    @JvmStatic external fun nativeAttach(row: Int): FloatArray
    
    // images (pixels live in fox_images.h, shared with the exporter)
    @JvmStatic external fun nativeImageLoad(w: Int, h: Int, px: java.nio.ByteBuffer): Int
    @JvmStatic external fun nativeImageRelease(handle: Int)
    @JvmStatic external fun nativeAddImage(cel: Int, layer: Int, handle: Int, cx: Float, cy: Float, w: Float, h: Float, rot: Float)

    fun addImage(cel: Int, layer: Int, handle: Int, cx: Float, cy: Float, w: Float, h: Float, rot: Float) {
        nativeAddImage(cel, layer, handle, cx, cy, w, h, rot); requestRender()
    }

    @Volatile
    private var view: GLSurfaceView? = null

    fun attach(v: GLSurfaceView?) { view = v }

    fun requestRender() { view?.requestRender() }

    // ---- friendly API: call these from the UI thread ----
    fun setView(scale: Float, rotationDeg: Float, offX: Float, offY: Float) {
        nativeSetView(scale, rotationDeg, offX, offY); requestRender()
    }

    fun setTheme(bg: Int, checkerA: Int, checkerB: Int, density: Float) {
        nativeSetTheme(bg, checkerA, checkerB, density); requestRender()
    }

    /** [params] = Brush Studio array for this stroke (null = classic capsule pen). */
    fun beginStroke(cel: Int, layer: Int, argb: Int, size: Float, erase: Boolean, params: FloatArray? = null) {
        if (params == null) nativeBeginStroke(cel, layer, argb, size, erase)
        else nativeBeginStrokeEx(cel, layer, argb, size, erase, params)
    }

    fun brushPreview(params: FloatArray, argb: Int, w: Int, h: Int, unit: Float, samples: FloatArray = FloatArray(0)): IntArray =
        nativeBrushPreview(params, argb, w, h, unit, samples)

    /** Replay an engine stroke (project load): [params] as it was drawn, [aux] = (pressure, ms) per point of [xy]. */
    fun addStrokeEx(cel: Int, layer: Int, argb: Int, size: Float, erase: Boolean, xy: FloatArray, params: FloatArray, aux: FloatArray) {
        nativeAddStrokeEx(cel, layer, argb, size, erase, xy, params, aux); requestRender()
    }

    /** Drawings the playhead is about to reach: native pulls them from disk into RAM on its own thread. */
    fun prefetch(cels: IntArray) { nativePrefetch(cels) }

    fun strokePoint(x: Float, y: Float) { nativeStrokePoint(x, y); requestRender() }
    /** Pressure + time are only read by engine strokes; the classic pen ignores them. */
    fun strokePoint(x: Float, y: Float, pressure: Float, tMs: Float) { nativeStrokePointEx(x, y, pressure, tMs); requestRender() }
    fun endStroke() { nativeEndStroke(); requestRender() }
    fun cancelStroke() { nativeCancelStroke(); requestRender() }
    fun undo() { nativeUndo(); requestRender() }
    fun redo() { nativeRedo(); requestRender() }
    fun resetStrokes() { nativeResetStrokes(); requestRender() }
    /** Per native layer: 7 floats [tx, ty, sx, sy, rotDeg, pivotX, pivotY] in paper units (paper = 0..1). */
    fun setXf(ids: IntArray, vals: FloatArray) { nativeSetXf(ids, vals); requestRender() }

    /**
     * Row animation tables. Row r drives native layers [r * perRow, (r + 1) * perRow). [offsets] = timeline frame each row
     * starts at, [counts] = keys per row, [statics] = 7 floats per row (pose while it has no keys + the pivot),
     * [keys] = every row's keys back to back, 11 floats each (see Keyframe.writeTo), sorted by row-local frame.
     */
    fun setAnim(perRow: Int, offsets: IntArray, counts: IntArray, statics: FloatArray, keys: FloatArray) {
        nativeSetAnim(perRow, offsets, counts, statics, keys); requestRender()
    }

    /** Playhead (timeline frames, fractional ok). Native re-evaluates every row's curve at it. */
    fun setTime(frame: Float) { nativeSetTime(frame); requestRender() }

    /** One row's pose at row-local [local]: 7 floats tx, ty, sx, sy, rotDeg, pivotX, pivotY. Stateless; any thread. */
    fun evalXf(keys: FloatArray, n: Int, local: Float, base: FloatArray): FloatArray = nativeEvalXf(keys, n, local, base)

    /** [n] samples of an easing curve for t = 0..1 (curve preview). */
    fun easeCurve(id: Int, x1: Float, y1: Float, x2: Float, y2: Float, n: Int = 64): FloatArray = nativeEaseCurve(id, x1, y1, x2, y2, n)

    /** Fills [out] with samples of ONE lane (keys of a single channel, [KEY_STRIDE] floats each) over row-local frames [f0]..[f1]: the exact curve the canvas plays. */
    fun sampleLane(keys: FloatArray, n: Int, f0: Float, f1: Float, out: FloatArray) = nativeSampleLane(keys, n, f0, f1, out)

    /**
     * Auto-smooth (monotone / flow tangents -> bezier easings, fox_graph.h). Returns the key array with new easing ids + handles
     * ([KEY_STRIDE] floats per key, same order); frames and values are untouched. [chan] = -1 for every lane.
     */
    fun smoothKeys(keys: FloatArray, n: Int, chan: Int, mode: Int, strength: Float, flatEnds: Boolean, frameLo: Float = -1e9f, frameHi: Float = 1e9f): FloatArray =
        nativeSmoothKeys(keys, n, chan, mode, strength, flatEnds, frameLo, frameHi)

    /** Pen-tool "add anchor": split a segment easing at [t] (0..1) keeping the curve. [ok, left x1 y1 x2 y2, right x1 y1 x2 y2, curve y at t]. */
    fun splitEase(id: Int, x1: Float, y1: Float, x2: Float, y2: Float, t: Float): FloatArray = nativeSplitEase(id, x1, y1, x2, y2, t)

    /** Bezier handles [x1 y1 x2 y2] closest to the preset easing [id] (fox_graph.h), so a preset segment can be edited as a curve. */
    fun fitEase(id: Int): FloatArray = nativeFitEase(id)

    /**
     * Onion skin ghosts of timeline row [row] (-1 = off, frees the GPU memory). [ghosts] = 4 ints each: drawing id, timeline frame
     * it sits at, side (0 past / 1 future), distance (1 = nearest). [prm] = opacity, fade, tint amount, past rgb, future rgb (0..1).
     */
    fun setOnion(row: Int, ghosts: IntArray, prm: FloatArray) { nativeSetOnion(row, ghosts, prm); requestRender() }

    /** Cel ids on screen right now (one per timeline row). Only layers whose drawing changed are redrawn. */
    fun setShown(cels: IntArray) { nativeSetShown(cels); requestRender() }

    fun setLayers(ids: IntArray, visible: BooleanArray) {
        nativeSetLayers(ids, visible); requestRender()
    }

    /**
     * Blend mode / opacity / clipping (fox_blend.h). Layer arrays are indexed by native layer id (same order as [setLayers]),
     * row arrays by timeline row (bottom -> top). Modes: see EditorScreen.kt BlendMode.
     */
    fun setFx(lMode: IntArray, lOpacity: FloatArray, lClip: BooleanArray, rMode: IntArray, rOpacity: FloatArray, rClip: BooleanArray) {
        nativeSetFx(lMode, lOpacity, lClip, rMode, rOpacity, rClip); requestRender()
    }

    /** Blend / opacity / clipping of group folders: [rowGroup] = group id of every timeline row (bottom -> top, -1 = none). */
    fun setGroupFx(rowGroup: IntArray, gIds: IntArray, gMode: IntArray, gOpacity: FloatArray, gClip: BooleanArray) {
        nativeSetGroupFx(rowGroup, gIds, gMode, gOpacity, gClip); requestRender()
    }

    /**
     * Project load: queue every stroke of a parsed strokes.bin ([fox.foxiru.foxcat.fox2d.jnicallers.NativeProject] handle) straight
     * from native memory. [celRow] = pairs (cel id, timeline row), [layerTab] = rows * [slots] layer ids (Int.MIN_VALUE = empty),
     * [imgHandles] = native image handle per blob image file. false = bad handle: the caller replays the Kotlin strokes instead.
     */
    fun addStrokesFromBlob(blob: Long, celRow: IntArray, layerTab: IntArray, slots: Int, imgHandles: IntArray): Boolean =
        nativeAddStrokesFromBlob(blob, celRow, layerTab, slots, imgHandles).also { requestRender() }

    fun addStroke(cel: Int, layer: Int, argb: Int, size: Float, erase: Boolean, xy: FloatArray) {
        nativeAddStroke(cel, layer, argb, size, erase, xy); requestRender()
    }

    /** Whole rig set, one entry per timeline row in the same order as [setAnim] (see packRigs). */
    fun setRig(blob: FloatArray) { nativeSetRig(blob); requestRender() }

    /** false = rigged rows are drawn flat (rest pose) so bones / curves can be built on the untouched picture. */
    fun setRigPosed(on: Boolean) { nativeSetRigPosed(on); requestRender() }

    /** Show the rig mesh as GPU lines (drawn by the canvas shader, no Compose drawing). [row] = timeline row, -1 = every rigged row. */
    fun setMeshLines(on: Boolean, row: Int = -1) { nativeSetMeshLines(on, row); requestRender() }

    /**
     * Outline-following triangle mesh (fox_mesh.h) from a drawing mask (n * n bytes, > 127 = inside). Stateless.
     * Result: [nV, nT, x, y * nV, i, j, k * nT]; empty = no shape.
     */
    fun genMesh(mask: ByteArray, n: Int, spacing: Float, dilatePx: Int): FloatArray = nativeGenMesh(mask, n, spacing, dilatePx)

    /** Show a triangle mesh on timeline row [row] (as lines, replaces that row's square grid). */
    fun setTriMesh(row: Int, verts: FloatArray, tris: FloatArray) { nativeSetTriMesh(row, verts, tris); requestRender() }
    fun clearTriMeshes() { nativeClearTriMeshes(); requestRender() }

    /**
     * Puppet-warp pins that fit a drawing silhouette to a picture silhouette (fox_fit.h). Both masks n * n bytes. Stateless.
     * Result: [l, t, r, b of the mapped picture, pin count, then rx, ry, dx, dy per pin]; empty = nothing found.
     */
    fun fitPins(src: ByteArray, tgt: ByteArray, n: Int, mode: Int, uniform: Boolean, maxPins: Int): FloatArray =
        nativeFitPins(src, tgt, n, mode, uniform, maxPins)

    /** Overlay geometry + every channel value of ONE row's rig at row-local [local]. Stateless. Empty = no rig. */
    fun evalRig(row: FloatArray, local: Float, rest: Boolean): FloatArray = nativeEvalRig(row, local, rest)

    /** Final skin weight of [bone] per grid vertex of the row (weight heat map). Stateless. */
    fun rigWeights(row: FloatArray, bone: Int): FloatArray = nativeRigWeights(row, bone)

    /** Per row [parentRow, bone] (-1 = not attached): the row follows that bone of another row. Same row order as [setAnim]. */
    fun setAttach(a: IntArray) { nativeSetAttach(a); requestRender() }

    /** Matrix [a b c d tx ty] (paper units) that the attachment adds to [row] right now; identity when not attached. */
    fun attach(row: Int): FloatArray = nativeAttach(row)

    /** Shown point (paper units) -> rest position inside the rigged row [row]; unchanged when not rigged / not posed. */
    fun unwarp(row: Int, x: Float, y: Float): FloatArray = nativeUnwarp(row, x, y)
}

/** Paper-texture resolution. 2048 = 16 MB per layer; raise to 4096 only if you budget 64 MB per layer. */
const val PAPER_TEXTURE_SIZE = 2048

/** RAM the native raster cache may use for finished drawings (compressed); the rest of the project streams from the cache dir. */
const val CEL_CACHE_RAM_MB = 192

class FoxCanvasView(context: Context, paperSize: Int = PAPER_TEXTURE_SIZE) : GLSurfaceView(context) {
    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        holder.setFormat(PixelFormat.OPAQUE)
        preserveEGLContextOnPause = true
        setRenderer(object : Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                // on the GL thread, so libfoxcanvas is certainly loaded; same dir again after a context loss keeps the spilled drawings
                NativeCanvas.nativeSetCache(context.cacheDir.absolutePath + "/fox_cel_cache", CEL_CACHE_RAM_MB)
                NativeCanvas.nativeSurfaceCreated(paperSize)
            }
            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) = NativeCanvas.nativeSurfaceChanged(width, height)
            override fun onDrawFrame(gl: GL10?) {
                NativeCanvas.nativeDrawFrame()
                if (NativeCanvas.nativeMoreWork()) requestRender()   // live stroke backlog: keep drawing without waiting for the next touch event
            }
        })
        renderMode = RENDERMODE_WHEN_DIRTY   // draw only when something changed
        isClickable = false
        isFocusable = false
    }
}

/**
 * Full-bleed GL canvas. Put it at the bottom of a Box and lay the gesture layer (a transparent Compose
 * Box with Modifier.canvasGestures) ON TOP of it, so pointer input never depends on the AndroidView.
 */
@Composable
fun NativeCanvasSurface(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val glView = remember(context) { FoxCanvasView(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(glView, lifecycle) {
        NativeCanvas.attach(glView)
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> glView.onResume()
                Lifecycle.Event.ON_PAUSE -> glView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        glView.requestRender()
        onDispose {
            lifecycle.removeObserver(observer)
            glView.onPause()
            NativeCanvas.attach(null)
        }
    }

    AndroidView(factory = { glView }, modifier = modifier)
}
