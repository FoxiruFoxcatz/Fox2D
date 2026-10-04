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

    // ---- any thread ----
    @JvmStatic external fun nativeSetView(scale: Float, rotationDeg: Float, offX: Float, offY: Float)
    @JvmStatic external fun nativeSetTheme(bg: Int, checkerA: Int, checkerB: Int, density: Float)
    @JvmStatic external fun nativeBeginStroke(cel: Int, layer: Int, argb: Int, size: Float, erase: Boolean)
    @JvmStatic external fun nativeStrokePoint(x: Float, y: Float)
    @JvmStatic external fun nativeEndStroke()
    @JvmStatic external fun nativeCancelStroke()
    @JvmStatic external fun nativeUndo()
    @JvmStatic external fun nativeRedo()
    @JvmStatic external fun nativeResetStrokes()
    @JvmStatic external fun nativeSetShown(cels: IntArray)
    @JvmStatic external fun nativeSetXf(ids: IntArray, vals: FloatArray)

    // keyframe animation (maths lives in fox_anim.h)
    @JvmStatic external fun nativeSetAnim(perRow: Int, offsets: IntArray, counts: IntArray, statics: FloatArray, keys: FloatArray)
    @JvmStatic external fun nativeSetTime(frame: Float)
    @JvmStatic external fun nativeEvalXf(keys: FloatArray, n: Int, local: Float, base: FloatArray): FloatArray
    @JvmStatic external fun nativeEaseCurve(id: Int, x1: Float, y1: Float, x2: Float, y2: Float, n: Int): FloatArray
    @JvmStatic external fun nativeSetLayers(ids: IntArray, visible: BooleanArray)
    @JvmStatic external fun nativeAddStroke(cel: Int, layer: Int, argb: Int, size: Float, erase: Boolean, xy: FloatArray)

    // rigging + deformation (maths lives in fox_rig.h)
    @JvmStatic external fun nativeSetRig(blob: FloatArray)
    @JvmStatic external fun nativeSetRigPosed(on: Boolean)
    @JvmStatic external fun nativeEvalRig(row: FloatArray, local: Float, rest: Boolean): FloatArray
    @JvmStatic external fun nativeRigWeights(row: FloatArray, bone: Int): FloatArray
    @JvmStatic external fun nativeUnwarp(row: Int, x: Float, y: Float): FloatArray
    @JvmStatic external fun nativeSetAttach(a: IntArray)
    @JvmStatic external fun nativeAttach(row: Int): FloatArray

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

    fun beginStroke(cel: Int, layer: Int, argb: Int, size: Float, erase: Boolean) {
        nativeBeginStroke(cel, layer, argb, size, erase)
    }

    fun strokePoint(x: Float, y: Float) { nativeStrokePoint(x, y); requestRender() }
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

    /** Cel ids on screen right now (one per timeline row). Only layers whose drawing changed are redrawn. */
    fun setShown(cels: IntArray) { nativeSetShown(cels); requestRender() }

    fun setLayers(ids: IntArray, visible: BooleanArray) {
        nativeSetLayers(ids, visible); requestRender()
    }

    fun addStroke(cel: Int, layer: Int, argb: Int, size: Float, erase: Boolean, xy: FloatArray) {
        nativeAddStroke(cel, layer, argb, size, erase, xy); requestRender()
    }

    /** Whole rig set, one entry per timeline row in the same order as [setAnim] (see packRigs). */
    fun setRig(blob: FloatArray) { nativeSetRig(blob); requestRender() }

    /** false = rigged rows are drawn flat (rest pose) so bones / curves can be built on the untouched picture. */
    fun setRigPosed(on: Boolean) { nativeSetRigPosed(on); requestRender() }

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

class FoxCanvasView(context: Context, paperSize: Int = PAPER_TEXTURE_SIZE) : GLSurfaceView(context) {
    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        holder.setFormat(PixelFormat.OPAQUE)
        preserveEGLContextOnPause = true
        setRenderer(object : Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) = NativeCanvas.nativeSurfaceCreated(paperSize)
            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) = NativeCanvas.nativeSurfaceChanged(width, height)
            override fun onDrawFrame(gl: GL10?) = NativeCanvas.nativeDrawFrame()
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
