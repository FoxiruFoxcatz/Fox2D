package fox.foxiru.foxcat.fox2d.main_canvas

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private const val DEG2RAD = 0.017453292f

/**
 * View transform of the paper square inside the (full-screen) editor viewport.
 *
 *     screen = viewportCenter + offset + R(rotation) * scale * (local - paperCenter)
 *
 * The paper is centred in the viewport, so the graphicsLayer default pivot (layer centre) and this
 * formula agree. graphicsLayer applies scale -> rotate -> translate, which is the same order.
 * Apply it with:
 *
 *     graphicsLayer {
 *         scaleX = vp.scale; scaleY = vp.scale
 *         rotationZ = vp.rotation
 *         translationX = vp.offsetX; translationY = vp.offsetY
 *     }
 *
 * `rotation` is in degrees, clockwise-positive (same sign as graphicsLayer.rotationZ).
 */
@Stable
class CanvasViewport {
    var scale by mutableFloatStateOf(1f)
        private set
    var offsetX by mutableFloatStateOf(0f)
        private set
    var offsetY by mutableFloatStateOf(0f)
        private set

    /** Displayed rotation (after snapping), degrees, wrapped to [-180, 180]. */
    var rotation by mutableFloatStateOf(0f)
        private set

    /** True while [rotation] sits on a snap angle. Flips false -> true when the canvas "clicks" in. */
    var snapped by mutableStateOf(true)
        private set

    /**
     * True until the user moves the view. While true the paper is re-fitted whenever the viewport
     * or the free area (between top bar and dock) changes size.
     */
    var autoFit by mutableStateOf(true)
        private set

    /** Fired synchronously after every change of scale / rotation / offset (feeds the native GL canvas). */
    var onChanged: (() -> Unit)? = null

    /** Unsnapped accumulator; [rotation] is derived from it so snapping has a sticky zone. */
    private var rawRotation = 0f
    private var view = IntSize.Zero
    private var topInset = 0f
    private var bottomInset = 0f

    /** Report viewport size + the pixels covered by the top bar / dock. */
    fun onLayout(view: IntSize, topInset: Float, bottomInset: Float) {
        if (view.width <= 0 || view.height <= 0) return      // not measured yet (e.g. right after rotation)
        val resized = this.view != IntSize.Zero && this.view != view
        this.view = view
        this.topInset = topInset
        this.bottomInset = bottomInset
        if (autoFit || resized) fit()
    }

    /** Paper fitted + centred in the area not covered by the UI, rotation cleared. */
    fun fit() {
        rawRotation = 0f
        rotation = 0f
        snapped = true
        autoFit = true
        offsetX = 0f
        if (view.width <= 0 || view.height <= 0) {
            scale = 1f
            offsetY = 0f
            onChanged?.invoke()
            return
        }
        val side = min(view.width, view.height).toFloat()
        val freeH = (view.height - topInset - bottomInset).coerceAtLeast(1f)
        val free = min(view.width.toFloat(), freeH)
        scale = (free / side * FIT_FILL).coerceIn(MIN_SCALE, MAX_SCALE)
        // centre of the free strip relative to the view centre
        offsetY = (topInset - bottomInset) / 2f
        onChanged?.invoke()
    }

    fun reset() = fit()

    /** Call when a multi-touch gesture ends so the next one starts from the displayed angle. */
    fun commitRotation() {
        rawRotation = rotation
    }

    /** Viewport-space point -> paper space normalised to 0..1 (same space InkStroke.pts uses). */
    fun toUnit(p: Offset, view: IntSize): Offset {
        val side = min(view.width, view.height).toFloat()
        val vx = p.x - view.width / 2f - offsetX
        val vy = p.y - view.height / 2f - offsetY
        val a = -rotation * DEG2RAD
        val c = cos(a)
        val s = sin(a)
        val lx = (vx * c - vy * s) / scale + side / 2f
        val ly = (vx * s + vy * c) / scale + side / 2f
        return Offset(lx / side, ly / side)
    }

    /**
     * Content under [prev] ends up under [curr], scaled by [zoom] and turned by [turnDeg] about
     * that point. Handles pan + pinch + twist in one step; clamps scale, snaps rotation and keeps
     * part of the paper on screen.
     *
     * With v = prev - c - offset (c = viewport centre) the new offset is
     *     offset' = (curr - c) - R(dRot) * z * v
     * where dRot is the change of the *displayed* (snapped) rotation, so the point under the
     * fingers stays glued to them even while the angle is locked on a snap stop.
     */
    fun transformBy(prev: Offset, curr: Offset, zoom: Float, turnDeg: Float, view: IntSize) {
        autoFit = false

        val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        val z = newScale / scale

        var raw = rawRotation + turnDeg
        raw -= 360f * floor((raw + 180f) / 360f)          // wrap to [-180, 180)
        val nearest = (raw / SNAP_STEP).roundToInt() * SNAP_STEP
        val locked = abs(raw - nearest) <= SNAP_TOLERANCE
        val newRot = if (locked) nearest else raw
        val d = (newRot - rotation) * DEG2RAD
        val cd = cos(d)
        val sd = sin(d)

        val cx = view.width / 2f
        val cy = view.height / 2f
        val vx = prev.x - cx - offsetX
        val vy = prev.y - cy - offsetY

        var nx = (curr.x - cx) - z * (vx * cd - vy * sd)
        var ny = (curr.y - cy) - z * (vx * sd + vy * cd)

        // Free pan: only stop the paper from being lost completely (a KEEP_PX sliver stays reachable).
        val side = min(view.width, view.height).toFloat()
        val half = side * newScale / 2f
        val keep = min(KEEP_PX, half)
        val limX = half + view.width / 2f - keep
        val limY = half + view.height / 2f - keep
        nx = nx.coerceIn(-limX, limX)
        ny = ny.coerceIn(-limY, limY)

        scale = newScale
        offsetX = nx
        offsetY = ny
        rawRotation = raw
        rotation = newRot
        snapped = locked
        onChanged?.invoke()
    }

    companion object {
        const val MIN_SCALE = 0.1f
        const val MAX_SCALE = 16f
        private const val KEEP_PX = 48f
        private const val FIT_FILL = 0.96f

        /** Rotation snaps to multiples of this many degrees... */
        const val SNAP_STEP = 45f

        /** ...when the raw angle is within this many degrees of a stop. */
        const val SNAP_TOLERANCE = 5f
    }
}

/** Everything is reported in paper space (0..1) so the caller never sees the view transform. */
interface CanvasGestureListener {
    /** Sampled once per gesture, on the first finger down. */
    fun canDraw(): Boolean
    fun onInkStart(p: Offset)
    fun onInkMove(p: Offset)
    fun onInkEnd()

    /** Extra touch landed mid-stroke: throw the stroke away. */
    fun onInkCancel()
    fun onUndo()
    fun onRedo()
}

private const val TAP_MAX_MS = 300L

/** Twist must exceed this before rotation engages, so a plain pinch never wobbles the canvas. */
private const val ROTATE_SLOP_DEG = 5f

/**
 * View-only navigation fed one pointer event at a time, for callers that own the gesture loop
 * (TransformOverlay in box-only mode).
 *   1 finger  -> pan
 *   2+ fingers -> pan + pinch zoom + twist (same rotate slop / snapping as [canvasGestures])
 * Call [end] when the gesture is over so the next one starts from the displayed angle.
 * Never touches [CanvasViewport.autoFit] unless something actually moved.
 */
internal class ViewNav(private val vp: CanvasViewport) {
    private var rotAccum = 0f
    private var rotating = false

    fun step(ev: PointerEvent, view: IntSize) {
        val pressed = ev.changes.count { it.pressed }
        if (pressed >= 2) {
            val zoom = ev.calculateZoom()
            val turn = ev.calculateRotation()
            if (!rotating) {
                rotAccum += turn
                if (abs(rotAccum) > ROTATE_SLOP_DEG) rotating = true
            }
            val prev = ev.calculateCentroid(useCurrent = false)
            val curr = ev.calculateCentroid()
            if (!prev.isSpecified || !curr.isSpecified) return
            val t = if (rotating) turn else 0f
            if (zoom == 1f && t == 0f && prev == curr) return
            vp.transformBy(prev, curr, zoom, t, view)
        } else if (pressed == 1) {
            val c = ev.changes.first { it.pressed }
            if (c.position == c.previousPosition) return
            vp.transformBy(c.previousPosition, c.position, 1f, 0f, view)
        }
    }

    fun end() = vp.commitRotation()
}

/**
 * One finger              -> ink (if [CanvasGestureListener.canDraw])
 * 2+ fingers at any point -> never ink; a live stroke is cancelled; pinch zoom + pan + twist rotate
 * 2-finger quick tap      -> undo
 * 3-finger quick tap      -> redo
 *
 * Place it on an untransformed, full-screen container; the transformed paper goes inside.
 */
fun Modifier.canvasGestures(viewport: CanvasViewport, listener: CanvasGestureListener): Modifier =
    pointerInput(viewport, listener) {
        val slop = viewConfiguration.touchSlop

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val t0 = down.uptimeMillis
            var tEnd = t0

            val origin = HashMap<PointerId, Offset>()
            origin[down.id] = down.position

            val mayDraw = listener.canDraw()
            var maxPointers = 1
            var multi = false   // 2+ fingers seen -> this gesture can no longer draw
            var moved = false   // some finger left its tap slop -> transform, not a tap
            var inking = false
            var rotAccum = 0f   // twist accumulated before rotation engages
            var rotating = false

            try {
                do {
                    val event = awaitPointerEvent()
                    val changes = event.changes
                    tEnd = changes[0].uptimeMillis

                    for (c in changes) if (c.changedToDownIgnoreConsumed()) origin[c.id] = c.position

                    val pressed = changes.count { it.pressed }
                    if (pressed > maxPointers) maxPointers = pressed

                    if (pressed >= 2 && !multi) {
                        multi = true
                        if (inking) {
                            inking = false
                            listener.onInkCancel()
                        }
                    }

                    if (multi) {
                        if (!moved) {
                            moved = changes.any {
                                it.pressed && (it.position - (origin[it.id] ?: it.position)).getDistance() > slop
                            }
                        }
                        if (moved && pressed >= 2) {
                            val zoom = event.calculateZoom()
                            val turn = event.calculateRotation()
                            if (!rotating) {
                                rotAccum += turn
                                if (abs(rotAccum) > ROTATE_SLOP_DEG) rotating = true
                            }
                            val prev = event.calculateCentroid(useCurrent = false)
                            val curr = event.calculateCentroid()
                            if (prev.isSpecified && curr.isSpecified) {
                                viewport.transformBy(prev, curr, zoom, if (rotating) turn else 0f, size)
                            }
                        }
                        changes.forEach { if (it.positionChanged()) it.consume() }
                    } else if (mayDraw) {
                        val c = changes.firstOrNull { it.id == down.id }
                        if (c != null) {
                            if (!inking && c.pressed && (c.position - down.position).getDistance() > slop) {
                                inking = true
                                listener.onInkStart(viewport.toUnit(down.position, size))
                            }
                            if (inking) {
                                for (h in c.historical) listener.onInkMove(viewport.toUnit(h.position, size))
                                listener.onInkMove(viewport.toUnit(c.position, size))
                                c.consume()
                                if (!c.pressed) {
                                    inking = false
                                    listener.onInkEnd()
                                }
                            }
                        }
                    }
                } while (event.changes.any { it.pressed })

                if (multi && !moved && tEnd - t0 <= TAP_MAX_MS) {
                    when (maxPointers) {
                        2 -> listener.onUndo()
                        3 -> listener.onRedo()
                    }
                }
            } finally {
                if (inking) listener.onInkCancel()
                if (multi) viewport.commitRotation()
            }
        }
    }
