package fox.foxiru.foxcat.fox2d.main_canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import fox.foxiru.foxcat.fox2d.jnicallers.NativeCanvas
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// ======================================================================================================
// Canvas overlay of the Bone / Deform tools. Everything it draws and every touch is in LAYER paper units
// (the space the rig lives in); the row transform (LayerXf) and the view (PaperMap) sit on top.
//
//   Bone  / Build / Draw  : drag = new bone (starts on a tail = child, on a body = child, else root)
//   Bone  / Build / Edit  : drag a head / tail / body to reshape the rest skeleton
//   Bone  / Pose          : drag head = move, tail / body = rotate (IK bones: the tail drags the IK target)
//   Bone  / Paint         : brush the weight of the selected bone (heat map)
//   Deform/ Build         : tap = add control point, drag point = move it
//   Deform/ Pose          : drag a control point = keyed offset
//   empty space / 2 fingers : pan, zoom, twist the view
// Every gesture is ONE undo step.
// ======================================================================================================

private sealed interface RHit {
    data class Head(val b: Int) : RHit
    data class Tail(val b: Int) : RHit
    data class Body(val b: Int) : RHit
    data class Ik(val b: Int) : RHit
    data class Ctl(val k: Int, val i: Int) : RHit
    object None : RHit
}

/** layer point <-> screen: row transform, then the attachment [att] (a b c d tx ty, paper units; null = none), then the view. */
private class RCtx(private val m: PaperMap, private val xf: LayerXf, private val att: FloatArray? = null) {
    fun screen(p: Offset): Offset {
        val q = xf.apply(p)
        val a = att ?: return m.toScreen(q)
        return m.toScreen(Offset(a[0] * q.x + a[2] * q.y + a[4], a[1] * q.x + a[3] * q.y + a[5]))
    }

    fun layer(s: Offset): Offset {
        val q = m.toPaper(s)
        val a = att
        if (a == null) return xf.invert(q)
        val det = a[0] * a[3] - a[1] * a[2]
        if (kotlin.math.abs(det) < 1e-8f) return xf.invert(q)
        val x = q.x - a[4]; val y = q.y - a[5]
        return xf.invert(Offset((a[3] * x - a[2] * y) / det, (-a[1] * x + a[0] * y) / det))
    }
}

private fun segDist(p: Offset, a: Offset, b: Offset): Float {
    val v = b - a
    val l2 = v.x * v.x + v.y * v.y
    val t = if (l2 < 1e-6f) 0f else (((p.x - a.x) * v.x + (p.y - a.y) * v.y) / l2).coerceIn(0f, 1f)
    return (p - (a + v * t)).getDistance()
}

private class OtherRig(val id: Int, val pose: RigPose, val xf: LayerXf, val att: FloatArray?)

/** Nearest bone (segment) of another track that has bones, within [r] screen px: (track id, bone) or null. */
private fun pickOther(state: EditorState, m: PaperMap, rest: Boolean, p: Offset, r: Float, skip: Int): Pair<Int, Int>? {
    var best: Pair<Int, Int>? = null
    var bd = r
    for (t in state.drawTracks) {
        if (t.id == skip || t.rig.bones.isEmpty()) continue
        val pose = state.rigPose(t.id, rest) ?: continue
        val c = RCtx(m, state.trackXf(t.id), state.attachAff(t.id))
        for (b in 0 until pose.boneCount) {
            val d = segDist(p, c.screen(Offset(pose.hx(b), pose.hy(b))), c.screen(Offset(pose.tx(b), pose.ty(b))))
            if (d < bd) { bd = d; best = t.id to b }
        }
    }
    return best
}

private fun hitTest(state: EditorState, pose: RigPose, rig: Rig, ctx: RCtx, p: Offset, r: Float): RHit {
    val ui = state.rigUi
    if (state.tool == Tool.Deform) {
        var best: RHit = RHit.None
        var bd = r
        // the selected curve wins ties: it is tested first and only strictly closer points replace it
        val order = rig.curves.indices.sortedBy { if (it == ui.curve) 0 else 1 }
        for (k in order) {
            val c = pose.curves.getOrNull(k) ?: continue
            for (i in 0 until c.count) {
                val d = (ctx.screen(Offset(c.x(i), c.y(i))) - p).getDistance()
                if (d < bd) { bd = d; best = RHit.Ctl(k, i) }
            }
        }
        return best
    }
    if (ui.mode == RigMode.Paint) return RHit.None
    var best: RHit = RHit.None
    var bs = r
    for (b in pose.boneCount - 1 downTo 0) {
        val h = ctx.screen(Offset(pose.hx(b), pose.hy(b)))
        val t = ctx.screen(Offset(pose.tx(b), pose.ty(b)))
        val bone = rig.bones[b]
        if (ui.mode == RigMode.Pose && bone.ikChain > 0) {
            val ik = ctx.screen(Offset(pose.value(RigChan.bone(b, BoneChan.IkX)), pose.value(RigChan.bone(b, BoneChan.IkY))))
            val d = (ik - p).getDistance()
            if (d < bs) { bs = d; best = RHit.Ik(b) }
        }
        val dh = (h - p).getDistance()
        val dt = (t - p).getDistance()
        val db = segDist(p, h, t) + r * 0.35f   // joints win over the middle of a bone
        val sel = if (b == ui.bone) -r * 0.1f else 0f   // the selected bone wins near-ties
        if (dh + sel < bs) { bs = dh + sel; best = RHit.Head(b) }
        if (dt + sel < bs) { bs = dt + sel; best = RHit.Tail(b) }
        if (db + sel < bs) { bs = db + sel; best = RHit.Body(b) }
    }
    return best
}

@Composable
internal fun RigOverlay(state: EditorState, viewSize: IntSize) {
    val vp = state.viewport
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val slop = LocalViewConfiguration.current.touchSlop
    val ui = state.rigUi
    val track = state.activeTrack
    val xf = state.trackXf(track)
    val rig = state.trackRig(track)
    val mode = ui.mode
    val rest = mode != RigMode.Pose
    val pose = state.rigPose(track, rest)
    val att = state.attachAff(track)
    // every other track that has bones, in the same (rest / posed) state as the active one
    val others = if (!ui.showAll) emptyList() else state.drawTracks.filter { it.id != track && it.rig.bones.isNotEmpty() }.mapNotNull { t ->
        state.rigPose(t.id, rest)?.let { OtherRig(t.id, it, state.trackXf(t.id), state.attachAff(t.id)) }
    }
    val dpx = density.density   // read here: inside the Canvas lambda `density` is the DrawScope's Float
    val hitPx = 24f * dpx
    fun map() = PaperMap(viewSize.width.toFloat(), viewSize.height.toFloat(), vp.scale, vp.rotation, vp.offsetX, vp.offsetY)

    // transient on-canvas feedback (not history): bone being drawn, brush position
    var draft by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    var brushAt by remember { mutableStateOf<Offset?>(null) }
    var snapAt by remember { mutableStateOf<Offset?>(null) }   // layer point of the joint a dragged bone is sticking to

    val weights = remember(rig, ui.bone, mode, ui.showWeights) {
        if (mode == RigMode.Paint && ui.showWeights && ui.bone in rig.bones.indices) NativeCanvas.rigWeights(rig.packed, ui.bone) else FloatArray(0)
    }

    val boneCol = cs.tertiary
    val selCol = cs.primary
    val curveCol = cs.secondary

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(state, viewSize) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val tr = state.activeTrack
                    val tool = state.tool
                    val md = state.rigUi.mode.let { if (tool == Tool.Deform && it == RigMode.Paint) RigMode.Build else it }
                    val locked = state.activeLocked
                    val rig0 = state.trackRig(tr)
                    val xf0 = state.trackXf(tr)
                    val pose0 = state.rigPose(tr, rest = md != RigMode.Pose)
                    val before = state.snapshot()
                    val att0 = state.attachAff(tr)
                    fun ctx() = RCtx(map(), xf0, att0)
                    val hit = if (pose0 != null && !locked) hitTest(state, pose0, rig0, ctx(), down.position, hitPx) else RHit.None
                    val startL = ctx().layer(down.position)

                    // what a one-finger drag does here; None = the drag navigates the view
                    val edits = !locked && when (tool) {
                        Tool.Bone -> when (md) {
                            RigMode.Build -> state.rigUi.boneTool == BoneTool.Draw || hit != RHit.None
                            RigMode.Pose -> hit != RHit.None || (state.rigUi.poseTool != PoseTool.Auto && state.rigUi.bone in rig0.bones.indices)
                            RigMode.Paint -> state.rigUi.bone in rig0.bones.indices
                        }
                        Tool.Deform -> hit is RHit.Ctl
                        else -> false
                    }

                    // Bone / Build / Draw: where the new bone starts and who its parent is
                    var drawParent = -1
                    var drawHead = startL
                    if (tool == Tool.Bone && md == RigMode.Build && state.rigUi.boneTool == BoneTool.Draw && pose0 != null) {
                        when (hit) {
                            is RHit.Tail -> { drawParent = hit.b; drawHead = Offset(rig0.bones[hit.b].tailX, rig0.bones[hit.b].tailY) }
                            is RHit.Head -> { drawParent = rig0.bones[hit.b].parent; drawHead = Offset(rig0.bones[hit.b].hx, rig0.bones[hit.b].hy) }
                            is RHit.Body -> drawParent = hit.b
                            else -> Unit
                        }
                    }

                    val snapL = hitPx * 0.9f / (min(viewSize.width, viewSize.height) * vp.scale * max(0.05f, kotlin.math.abs(xf0.sx)))
                    if (drawParent >= 0) snapAt = drawHead
                    val nav = ViewNav(vp)
                    var navigating = !edits
                    var multi = false
                    var moved = false
                    var changed = false
                    var work: FloatArray? = null      // weight paint, edited in place for the whole stroke
                    var lastL = startL
                    if (!navigating) down.consume()
                    if (md == RigMode.Paint && edits) brushAt = down.position

                    fun paintAt(p: Offset) {
                        val bone = state.rigUi.bone
                        val cur = state.trackRig(tr)
                        val w = work ?: cur.paintWork().also { work = it }
                        val amt = (if (state.rigUi.erase) -1f else 1f) * state.rigUi.brushStrength * 0.35f
                        if (cur.dab(w, bone, p.x, p.y, state.rigUi.brushRadius, amt)) {
                            val old = cur.paint
                            state.setRig(tr) { it.copy(paint = WeightPaint(it.vertCount, it.bones.size, w, (old?.version ?: 0) + 1)) }
                            changed = true
                        }
                    }
                    if (edits && md == RigMode.Paint) paintAt(startL)

                    while (true) {
                        val ev: PointerEvent = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            navigating = true
                            multi = true
                            draft = null; brushAt = null
                        }
                        val c = pressed[0]
                        if (!moved && (c.position - down.position).getDistance() > slop) moved = true
                        if (navigating) {
                            // a one-finger tap must not nudge the view: navigate only after the slop (or with 2 fingers)
                            if (moved || multi) {
                                nav.step(ev, viewSize)
                                ev.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                            continue
                        }
                        val cx = ctx()
                        val q = cx.layer(c.position)
                        val d = q - lastL
                        if (moved) {
                            when {
                                tool == Tool.Bone && md == RigMode.Build && state.rigUi.boneTool == BoneTool.Draw -> {
                                    draft = drawHead to q
                                }
                                tool == Tool.Bone && md == RigMode.Build -> {
                                    when (hit) {
                                        is RHit.Head -> {
                                            // a free head sticks to the tip of an earlier bone (and becomes its child); always rebuilt from the
                                            // rig at gesture start, so dragging away un-sticks it again
                                            val sn = if (rig0.connected(hit.b)) null else rig0.snapHead(hit.b, q, snapL)
                                            snapAt = sn?.first
                                            state.setRig(tr) { _ ->
                                                if (sn != null) rig0.moveHead(hit.b, sn.first.x, sn.first.y).withParent(hit.b, sn.second)
                                                else rig0.moveHead(hit.b, q.x, q.y)
                                            }
                                            changed = true
                                        }
                                        is RHit.Tail -> {
                                            // a tip sticks to the head of a later bone, which then becomes its child
                                            val sn = rig0.snapTail(hit.b, q, snapL)
                                            snapAt = sn?.first
                                            state.setRig(tr) { _ ->
                                                if (sn != null) rig0.moveTail(hit.b, sn.first.x, sn.first.y).withParent(sn.second, hit.b)
                                                else rig0.moveTail(hit.b, q.x, q.y)
                                            }
                                            changed = true
                                        }
                                        is RHit.Body -> { state.setRig(tr) { it.translateSubtree(hit.b, d.x, d.y) }; changed = true }
                                        else -> Unit
                                    }
                                }
                                tool == Tool.Bone && md == RigMode.Pose && state.rigUi.poseTool != PoseTool.Auto -> {
                                    // Move / Rotate / Scale act on the selected bone wherever the finger is
                                    val tb = state.rigUi.bone
                                    if (tb in rig0.bones.indices) {
                                        when (state.rigUi.poseTool) {
                                            PoseTool.Move -> state.poseBoneMove(tr, tb, d)
                                            PoseTool.Rotate -> state.poseBoneRotateDrag(tr, tb, lastL, q)
                                            PoseTool.Scale -> state.poseBoneScaleDrag(tr, tb, lastL, q)
                                            PoseTool.Auto -> Unit
                                        }
                                        changed = true
                                    }
                                }
                                tool == Tool.Bone && md == RigMode.Pose -> {
                                    when (hit) {
                                        is RHit.Head -> { state.poseBoneMove(tr, hit.b, d); changed = true }
                                        is RHit.Ik -> { state.poseIk(tr, hit.b, q); changed = true }
                                        is RHit.Tail, is RHit.Body -> {
                                            val b = if (hit is RHit.Tail) hit.b else (hit as RHit.Body).b
                                            if (rig0.bones[b].ikChain > 0) state.poseIk(tr, b, q) else state.poseBoneToward(tr, b, q)
                                            changed = true
                                        }
                                        else -> Unit
                                    }
                                }
                                tool == Tool.Bone && md == RigMode.Paint -> { brushAt = c.position; paintAt(q) }
                                tool == Tool.Deform && md == RigMode.Build && hit is RHit.Ctl -> {
                                    state.setRig(tr) { r -> r.withCtl(hit.k, hit.i) { p -> p.copy(rx = q.x, ry = q.y) } }
                                    changed = true
                                }
                                tool == Tool.Deform && md == RigMode.Pose && hit is RHit.Ctl -> {
                                    state.poseCtlMove(tr, hit.k, hit.i, d); changed = true
                                }
                            }
                            lastL = q
                        } else if (md == RigMode.Paint) {
                            brushAt = c.position
                        }
                        ev.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                    nav.end()
                    brushAt = null
                    snapAt = null

                    // ---- gesture end ----
                    val up = lastL
                    val dr = draft
                    draft = null
                    if (multi) {
                        // 2+ fingers drove the view; nothing to commit here
                    } else if (tool == Tool.Bone && md == RigMode.Build && state.rigUi.boneTool == BoneTool.Draw && moved && dr != null) {
                        val len = hypot(dr.second.x - dr.first.x, dr.second.y - dr.first.y)
                        if (len > 0.02f) {
                            state.setRig(tr) { it.addBone(drawParent, dr.first.x, dr.first.y, dr.second.x, dr.second.y) }
                            state.rigUi.bone = state.trackRig(tr).bones.size - 1
                            changed = true
                        }
                    } else if (!moved) {
                        // a tap selects (or, deforming in Build, adds a control point)
                        val otherPick = if (tool == Tool.Bone && state.rigUi.showAll && hit == RHit.None) pickOther(state, map(), md != RigMode.Pose, down.position, hitPx, tr) else null
                        when {
                            tool == Tool.Bone && hit is RHit.Head -> state.rigUi.bone = hit.b
                            tool == Tool.Bone && hit is RHit.Tail -> state.rigUi.bone = hit.b
                            tool == Tool.Bone && hit is RHit.Body -> state.rigUi.bone = hit.b
                            tool == Tool.Bone && hit is RHit.Ik -> state.rigUi.bone = hit.b
                            tool == Tool.Deform && hit is RHit.Ctl -> { state.rigUi.curve = hit.k; state.rigUi.ctl = hit.i }
                            // a tap on a dimmed bone of ANOTHER track switches to that track and bone
                            otherPick != null -> {
                                state.activeTrack = otherPick.first
                                state.selectedKey = -1
                                state.kfOpen = null
                                state.timeline.selectedClip = -1
                                state.rigUi.bone = otherPick.second
                                state.rigUi.ctl = -1
                                state.normalizeRigSel()
                            }
                            tool == Tool.Deform && md == RigMode.Build && !locked -> {
                                val cur = state.trackRig(tr)
                                val bone = cur.nearestBone(up, 0.08f)
                                val pt = CtlPoint(up.x, up.y, bone)
                                val k = state.rigUi.curve
                                if (k in cur.curves.indices) {
                                    val at = if (state.rigUi.ctl in cur.curves[k].pts.indices) state.rigUi.ctl + 1 else cur.curves[k].pts.size
                                    state.setRig(tr) { it.insertCtl(k, at, pt) }
                                    state.rigUi.ctl = at.coerceAtMost(state.trackRig(tr).curves[k].pts.size - 1)
                                } else {
                                    state.setRig(tr) { it.addCurve(listOf(pt)) }
                                    state.rigUi.curve = state.trackRig(tr).curves.size - 1
                                    state.rigUi.ctl = 0
                                }
                                changed = true
                            }
                        }
                    }
                    if (changed) state.commitEdit(before)   // one undo step per gesture
                }
            }
    ) {
        val m = map()
        val ctx = RCtx(m, xf, att)
        val side = min(size.width, size.height) * vp.scale
        val dp = dpx

        // ---- weight heat map (selected bone) ----
        if (weights.isNotEmpty()) {
            val g = rig.grid
            val w = g + 1
            val r = rig.rect
            val cell = side * max(r.r - r.l, r.b - r.t) / g * 0.62f
            for (j in 0..g) for (i in 0..g) {
                val v = weights.getOrElse(j * w + i) { 0f }
                if (v <= 0.02f) continue
                val p = ctx.screen(Offset(r.l + (r.r - r.l) * i / g, r.t + (r.b - r.t) * j / g))
                drawCircle(heat(v).copy(alpha = 0.18f + 0.5f * v), cell, p)
            }
        }

        // ---- deform curves ----
        if (pose != null) {
            for (k in pose.curves.indices) {
                val c = pose.curves[k]
                val sel = k == ui.curve && state.tool == Tool.Deform
                val col = if (sel) KeyGold else curveCol
                if (c.samples.size >= 4) {
                    val path = Path()
                    for (i in 0 until c.samples.size / 2) {
                        val p = ctx.screen(Offset(c.samples[i * 2], c.samples[i * 2 + 1]))
                        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                    }
                    val reach = rig.curves.getOrNull(k)?.reach ?: 0f
                    if (sel && reach > 0f) {
                        drawPath(path, col.copy(alpha = 0.12f), style = Stroke(reach * 2f * side * xf.sx.coerceAtLeast(0.05f), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    drawPath(path, Color.Black.copy(alpha = 0.45f), style = Stroke(4.5f * dp, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(path, col, style = Stroke(2.5f * dp, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                if (state.tool == Tool.Deform || sel) {
                    for (i in 0 until c.count) {
                        val p = ctx.screen(Offset(c.x(i), c.y(i)))
                        val isSel = sel && i == ui.ctl
                        val bound = rig.curves.getOrNull(k)?.pts?.getOrNull(i)?.bone ?: -1
                        drawCircle(Color.Black.copy(alpha = 0.55f), 9.5f * dp, p)
                        drawCircle(if (isSel) KeyGold else col, 7f * dp, p, style = if (bound >= 0) androidx.compose.ui.graphics.drawscope.Fill else Stroke(2.2f * dp))
                        if (isSel) drawCircle(Color.White, 10.5f * dp, p, style = Stroke(1.6f * dp))
                    }
                }
            }
        }

        // ---- bones of the other drawing tracks (context while editing; tap one to switch to it) ----
        for (o in others) {
            val oc = RCtx(m, o.xf, o.att)
            val oCol = cs.onSurface.copy(alpha = 0.38f)
            for (b in 0 until o.pose.boneCount) {
                val h = oc.screen(Offset(o.pose.hx(b), o.pose.hy(b)))
                val t = oc.screen(Offset(o.pose.tx(b), o.pose.ty(b)))
                drawBone(h, t, oCol, Color.Black.copy(alpha = 0.25f), dp * 0.8f)
                drawCircle(oCol, 3f * dp, h)
            }
        }

        // ---- bones ----
        if (pose != null) {
            val p = pose
            val dim = state.tool != Tool.Bone
            for (b in 0 until p.boneCount) {
                val h = ctx.screen(Offset(p.hx(b), p.hy(b)))
                val t = ctx.screen(Offset(p.tx(b), p.ty(b)))
                val sel = b == ui.bone && state.tool == Tool.Bone
                val col = (if (sel) selCol else boneCol).copy(alpha = if (dim) 0.35f else 0.95f)
                drawBone(h, t, col, Color.Black.copy(alpha = if (dim) 0.2f else 0.55f), dp)
                if (state.tool == Tool.Bone) {
                    drawCircle(Color.Black.copy(alpha = 0.55f), 7f * dp, h)
                    drawCircle(col, 5f * dp, h)
                    if (sel) drawCircle(Color.White, 8.5f * dp, h, style = Stroke(1.4f * dp))
                }
                // IK target
                val bone = rig.bones.getOrNull(b)
                if (bone != null && bone.ikChain > 0 && mode == RigMode.Pose && state.tool == Tool.Bone) {
                    val ik = ctx.screen(Offset(p.value(RigChan.bone(b, BoneChan.IkX)), p.value(RigChan.bone(b, BoneChan.IkY))))
                    drawCircle(KeyGold, 9f * dp, ik, style = Stroke(2f * dp))
                    drawLine(KeyGold, ik - Offset(13f * dp, 0f), ik + Offset(13f * dp, 0f), 1.5f * dp)
                    drawLine(KeyGold, ik - Offset(0f, 13f * dp), ik + Offset(0f, 13f * dp), 1.5f * dp)
                }
            }
        }

        // ---- joint a bone is sticking to ----
        snapAt?.let { s ->
            val p = ctx.screen(s)
            drawCircle(KeyGold, 13f * dp, p, style = Stroke(2.5f * dp))
            drawCircle(KeyGold.copy(alpha = 0.25f), 13f * dp, p)
        }

        // ---- bone being drawn ----
        draft?.let { (a, b) ->
            drawBone(ctx.screen(a), ctx.screen(b), selCol.copy(alpha = 0.75f), Color.Black.copy(alpha = 0.4f), dp)
        }

        // ---- weight brush ----
        brushAt?.let { at ->
            val rr = ui.brushRadius * side
            drawCircle(Color.Black.copy(alpha = 0.5f), rr, at, style = Stroke(3.5f * dp))
            drawCircle(if (ui.erase) Color(0xFFFF6B6B) else Color.White, rr, at, style = Stroke(1.6f * dp))
        }
    }
}

/** Blue (0) -> green -> yellow -> red (1). */
private fun heat(v: Float): Color {
    val t = v.coerceIn(0f, 1f)
    return when {
        t < 0.33f -> lerp3(Color(0xFF2962FF), Color(0xFF00C853), t / 0.33f)
        t < 0.66f -> lerp3(Color(0xFF00C853), Color(0xFFFFD600), (t - 0.33f) / 0.33f)
        else -> lerp3(Color(0xFFFFD600), Color(0xFFFF1744), (t - 0.66f) / 0.34f)
    }
}

private fun lerp3(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f,
)

/** Classic bone shape: a long kite from the head joint, widest ~18% along, to the tail. */
private fun DrawScope.drawBone(h: Offset, t: Offset, fill: Color, outline: Color, dp: Float) {
    val v = t - h
    val l = v.getDistance()
    if (l < 1f) return
    val u = v / l
    val n = Offset(-u.y, u.x)
    val w = min(9f * dp, l * 0.22f)
    val mid = h + u * (l * 0.18f)
    val path = Path().apply {
        moveTo(h.x, h.y)
        lineTo(mid.x + n.x * w, mid.y + n.y * w)
        lineTo(t.x, t.y)
        lineTo(mid.x - n.x * w, mid.y - n.y * w)
        close()
    }
    drawPath(path, fill.copy(alpha = fill.alpha * 0.55f))
    drawPath(path, outline, style = Stroke(3.2f * dp, join = StrokeJoin.Round))
    drawPath(path, fill, style = Stroke(1.6f * dp, join = StrokeJoin.Round))
}
