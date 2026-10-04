package fox.foxiru.foxcat.fox2d.main_canvas

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import fox.foxiru.foxcat.fox2d.jnicallers.NativeCanvas

// ======================================================================================================
// Editor-side operations on rigs. Everything here is an extension of EditorState so EditorScreen.kt only needs
// a handful of hooks. "Raw" functions change state without history: gestures / sliders wrap a whole drag in
// snapshot() + commitEdit() (one undo step), discrete buttons wrap them in edit { }.
// ======================================================================================================

/** Build = draw bones / curves in REST pose. Pose = move them (keyframes). Paint = skin-weight brush. */
enum class RigMode { Build, Pose, Paint }

/** Bone tool, Build mode: Draw = a drag creates a bone, Edit = drags move heads / tails / whole bones. */
enum class BoneTool { Draw, Edit }

/** Pose gestures on the canvas. Auto = head moves, tip / body rotates. The others act on the SELECTED bone wherever you drag. */
enum class PoseTool { Auto, Move, Rotate, Scale }

/** UI-only rig state (selection, mode, brush). Not part of undo history. */
@Stable
class RigUiState {
    var mode by mutableStateOf(RigMode.Build)
    var boneTool by mutableStateOf(BoneTool.Draw)
    var poseTool by mutableStateOf(PoseTool.Auto)
    /** Draw the bones of every other drawing track too (dimmed), so a rig can be built against the whole character. */
    var showAll by mutableStateOf(true)
    /** Panel folded to its header bar, so the canvas gets the screen. */
    var collapsed by mutableStateOf(false)
    var bone by mutableIntStateOf(-1)
    var curve by mutableIntStateOf(-1)
    var ctl by mutableIntStateOf(-1)
    /** Pose edits create / update a key at the playhead even when the channel has no keys yet. */
    var autoKey by mutableStateOf(true)
    var showWeights by mutableStateOf(true)
    var brushRadius by mutableFloatStateOf(0.08f)
    var brushStrength by mutableFloatStateOf(0.5f)
    var erase by mutableStateOf(false)
}

val EditorState.rigOpen: Boolean get() = tool == Tool.Bone || tool == Tool.Deform

/** The canvas shows the REST picture while bones / curves are being built or weights painted. */
val EditorState.rigPosedView: Boolean get() = !rigOpen || rigUi.mode == RigMode.Pose

private fun EditorState.rowOf(track: Int) = drawTracks.indexOfFirst { it.id == track }

fun EditorState.trackRig(track: Int = activeTrack): Rig = drawTracks.firstOrNull { it.id == track }?.rig ?: Rig()

/** Raw: replace the rig of [track]. */
fun EditorState.setRig(track: Int, f: (Rig) -> Rig) {
    val i = rowOf(track)
    if (i >= 0) drawTracks[i] = drawTracks[i].copy(rig = f(drawTracks[i].rig))
}

/** Rig geometry of [track] at the playhead (bones, curves, every channel value). Null without a rig. */
fun EditorState.rigPose(track: Int = activeTrack, rest: Boolean = false): RigPose? {
    val t = drawTracks.firstOrNull { it.id == track } ?: return null
    if (t.rig.isEmpty) return null
    return parseRigPose(NativeCanvas.evalRig(t.rig.packed, localFrame(track).toFloat(), rest))
}

/** Every row's rig -> native (the canvas deforms with it; the exporter gets the same blob). */
fun EditorState.syncRig() {
    NativeCanvas.setRig(packRigs(drawTracks.toList()))
    NativeCanvas.setAttach(packAttach(drawTracks.toList()))
    NativeCanvas.setRigPosed(rigPosedView)
}

fun EditorState.selectRigTool(t: Tool) {
    playing = false
    transformOpen = false
    panel = Panel.None
    timeline.selectedClip = -1
    tool = t
    if (t == Tool.Deform && rigUi.mode == RigMode.Paint) rigUi.mode = RigMode.Build   // weights belong to the bone tool
    normalizeRigSel()
}

/** Keeps the bone / curve / point selection inside the active row's rig (row switch, undo, delete). */
fun EditorState.normalizeRigSel() {
    val r = trackRig()
    if (rigUi.bone !in r.bones.indices) rigUi.bone = if (r.bones.isEmpty()) -1 else 0
    if (rigUi.curve !in r.curves.indices) { rigUi.curve = if (r.curves.isEmpty()) -1 else 0; rigUi.ctl = -1 }
    else if (rigUi.ctl !in r.curves[rigUi.curve].pts.indices) rigUi.ctl = -1
}

/** Layer-space point under a shown (already row-transform-inverted) touch: undoes the rig deformation. */
fun EditorState.unwarp(track: Int, q: Offset): Offset {
    val row = rowOf(track)
    if (row < 0 || drawTracks[row].rig.isEmpty) return q
    val r = NativeCanvas.unwarp(row, q.x, q.y)
    return Offset(r[0], r[1])
}

/** Mesh = box of the drawing (+ margin), clipped to the paper. Content outside the mesh is not drawn while the rig is active. */
fun EditorState.fitMeshToDrawing(track: Int = activeTrack, grid: Int? = null) = edit {
    val b = trackBounds(track)
    val m = 0.06f
    val rect = if (b == null) MeshRect() else MeshRect(
        (b.left - m).coerceAtLeast(0f), (b.top - m).coerceAtLeast(0f), (b.right + m).coerceAtMost(1f), (b.bottom + m).coerceAtMost(1f),
    )
    setRig(track) { it.withMesh(grid ?: it.grid, rect) }
}

fun EditorState.setMeshGrid(track: Int, grid: Int) = edit { setRig(track) { it.withMesh(grid, it.rect) } }

// ------------------------------------------------------------------------------------------------ channel values / keys

/** Current value of a channel at the playhead (keys applied), as native evaluates it. */
fun EditorState.rigValue(track: Int, chan: Int): Float = rigPose(track)?.value(chan) ?: trackRig(track).baseOf(chan)

fun EditorState.rigLane(track: Int, chan: Int): List<ChanKey> = trackRig(track).keys.filter { it.chan == chan }

private fun EditorState.clampedLocal(track: Int) = localFrame(track).coerceIn(0, (trackSpan(track) - 1).coerceAtLeast(0))

/** Raw: create / update the key of [chan] at the playhead. */
fun EditorState.rigPutKey(track: Int, chan: Int, value: Float) {
    val fr = clampedLocal(track)
    setRig(track) { it.withKey(chan, fr, value, defaultEase) }
    selectedKey = fr
}

/**
 * Raw: a pose edit (slider / drag). A channel that has keys, or any channel while [RigUiState.autoKey] is on, is keyed at
 * the playhead; otherwise the static value changes. Wrap a whole drag in snapshot() + commitEdit().
 */
fun EditorState.setRigChan(track: Int, chan: Int, value: Float) {
    val hasLane = trackRig(track).keys.any { it.chan == chan }
    if (hasLane || rigUi.autoKey) {
        playing = false
        rigPutKey(track, chan, value)
    } else {
        setRig(track) { it.withBase(chan, value) }
    }
}

fun EditorState.rigKeyState(p: PropRef.RigChannel): Int {
    val lane = rigLane(p.track, p.chan)
    return if (lane.isEmpty()) 0 else if (lane.any { it.frame == localFrame(p.track) }) 2 else 1
}

fun EditorState.rigAddKey(p: PropRef.RigChannel) = edit { rigPutKey(p.track, p.chan, rigValue(p.track, p.chan)) }

fun EditorState.rigRemoveKey(p: PropRef.RigChannel) = edit {
    val local = localFrame(p.track)
    val k = rigLane(p.track, p.chan).firstOrNull { it.frame == local } ?: return@edit
    setRig(p.track) { r ->
        val rest = r.keys.filter { it !== k }
        // the last key of a channel going away leaves its value as the static one
        val r2 = r.copy(keys = rest)
        if (rest.none { it.chan == p.chan }) r2.withBase(p.chan, k.value) else r2
    }
    selectedKey = -1
}

fun EditorState.rigClearKeys(p: PropRef.RigChannel) = edit {
    if (rigLane(p.track, p.chan).isEmpty()) return@edit
    val v = rigValue(p.track, p.chan)
    setRig(p.track) { r -> r.copy(keys = r.keys.filter { it.chan != p.chan }).withBase(p.chan, v) }
    selectedKey = -1
}

fun EditorState.rigJumpKey(p: PropRef.RigChannel, dir: Int) {
    val off = trackOffset(p.track)
    val cur = frame - off
    val ks = rigLane(p.track, p.chan)
    val k = if (dir > 0) ks.firstOrNull { it.frame > cur } else ks.lastOrNull { it.frame < cur }
    if (k != null) { seek(off + k.frame); selectedKey = k.frame }
}

fun EditorState.rigSegmentKey(p: PropRef.RigChannel): ChanKey? {
    val lane = rigLane(p.track, p.chan)
    val local = localFrame(p.track)
    return lane.firstOrNull { it.frame == selectedKey && p.track == activeTrack } ?: lane.lastOrNull { it.frame <= local } ?: lane.firstOrNull()
}

fun EditorState.rigKeyOf(p: PropRef.RigChannel, local: Int): ChanKey? = rigLane(p.track, p.chan).firstOrNull { it.frame == local }

/** Raw: easing of the segment leaving the key at [local]. */
fun EditorState.rigSetEase(p: PropRef.RigChannel, local: Int, e: Easing) =
    setRig(p.track) { r -> r.copy(keys = r.keys.map { if (it.chan == p.chan && it.frame == local) it.copy(ease = e) else it }) }

fun EditorState.rigSetEaseAll(p: PropRef.RigChannel, e: Easing) = edit {
    setRig(p.track) { r -> r.copy(keys = r.keys.map { if (it.chan == p.chan) it.copy(ease = e) else it }) }
    defaultEase = e
}

/** Raw: move the rig keys on [from] (only channel [only], or every channel when null). Returns the frame they ended on. */
fun EditorState.rigMoveKey(track: Int, from: Int, to: Int, only: Int?): Int {
    val rig = trackRig(track)
    fun hit(k: ChanKey) = k.frame == from && (only == null || k.chan == only)
    val moving = rig.keys.filter(::hit)
    if (moving.isEmpty()) return from
    val dst = to.coerceIn(0, (trackSpan(track) - 1).coerceAtLeast(0))
    if (dst == from) return from
    if (rig.keys.any { k -> k.frame == dst && moving.any { it.chan == k.chan } }) return from
    setRig(track) { r -> r.copy(keys = (r.keys.filterNot(::hit) + moving.map { it.copy(frame = dst) }).sortedWith(ChanKeyOrder)) }
    if (selectedKey == from) selectedKey = dst
    return dst
}

/** Key-ruler hook: move keys of the open property, or of every property when none is open. */
fun EditorState.moveKeyFor(track: Int, from: Int, to: Int, open: PropRef?): Int = when {
    open is PropRef.Draw && open.track == track -> moveKey(track, from, to, open.chan)
    open is PropRef.RigChannel && open.track == track -> rigMoveKey(track, from, to, open.chan)
    else -> moveAllKeys(track, from, to)
}

/** Raw: move EVERY key on [from] (transform + rig) together; refuses when either kind would land on a key of the same property. */
fun EditorState.moveAllKeys(track: Int, from: Int, to: Int): Int {
    val t = drawTracks.firstOrNull { it.id == track } ?: return from
    val dst = to.coerceIn(0, (trackSpan(track) - 1).coerceAtLeast(0))
    if (dst == from) return from
    fun clash(keys: List<ChanKey>): Boolean {
        val moving = keys.filter { it.frame == from }
        return keys.any { k -> k.frame == dst && moving.any { it.chan == k.chan } }
    }
    if (clash(t.keys) || clash(t.rig.keys)) return from
    val a = moveKey(track, from, dst)
    val b = rigMoveKey(track, from, dst, null)
    return if (a != from || b != from) dst else from
}

/** Keys of the open keyframe drawer if it belongs to [track]; null when no drawer of this row is open. */
fun EditorState.openLaneKeys(track: Int): List<ChanKey>? = when (val o = kfOpen) {
    is PropRef.Draw -> if (o.track == track) laneKeys(track, o.chan) else null
    is PropRef.RigChannel -> if (o.track == track) rigLane(track, o.chan) else null
    else -> null
}

fun EditorState.openLabel(track: Int): String? = when (val o = kfOpen) {
    is PropRef.Draw -> if (o.track == track) o.chan.label else null
    is PropRef.RigChannel -> if (o.track == track) o.label else null
    else -> null
}

/** Transform keys + rig keys of the row (frames / easing only; channel numbers of the two kinds overlap). */
fun EditorState.allTrackKeys(track: Int = activeTrack): List<ChanKey> =
    drawTracks.firstOrNull { it.id == track }?.allKeys ?: emptyList()

// ------------------------------------------------------------------------------------------------ pose helpers

private fun BoneChan.isIk() = this == BoneChan.IkX || this == BoneChan.IkY || this == BoneChan.IkMix

/** Back to the rest pose: every pose channel of [bone] returns to neutral (keyed channels get a neutral key at the playhead). One undo step. */
fun EditorState.resetBonePose(track: Int, bone: Int) = edit {
    val rig = trackRig(track)
    val b = rig.bones.getOrNull(bone) ?: return@edit
    for (c in BoneChan.values()) {
        if (c.isIk()) continue
        val ch = RigChan.bone(bone, c)
        val has = rig.keys.any { it.chan == ch }
        if (has || rigUi.autoKey) {
            if (kotlin.math.abs(rigValue(track, ch) - c.neutral) > 1e-6f) rigPutKey(track, ch, c.neutral)
        } else setRig(track) { it.withBase(ch, c.neutral) }
    }
    if (b.ikChain > 0) {
        // the IK target goes back to the tail of the bone in its rest pose
        rigPutOrBase(track, RigChan.bone(bone, BoneChan.IkX), b.tailX)
        rigPutOrBase(track, RigChan.bone(bone, BoneChan.IkY), b.tailY)
    }
}

private fun EditorState.rigPutOrBase(track: Int, chan: Int, v: Float) {
    if (trackRig(track).keys.any { it.chan == chan } || rigUi.autoKey) rigPutKey(track, chan, v) else setRig(track) { it.withBase(chan, v) }
}

/** Key every pose channel of [bone] at the playhead with its current value. One undo step. */
fun EditorState.keyBone(track: Int, bone: Int) = edit {
    val rig = trackRig(track)
    val b = rig.bones.getOrNull(bone) ?: return@edit
    val pose = rigPose(track) ?: return@edit
    for (c in BoneChan.values()) {
        if (c.isIk() && b.ikChain == 0) continue
        val ch = RigChan.bone(bone, c)
        rigPutKey(track, ch, pose.value(ch))
    }
}

/** Whole rig back to the rest pose (drops nothing but values: keys stay, a neutral key is added where a lane exists). */
fun EditorState.resetRigPose(track: Int) = edit {
    val n = trackRig(track).bones.size
    for (b in 0 until n) {
        val rig = trackRig(track)
        for (c in BoneChan.values()) {
            if (c.isIk()) continue
            val ch = RigChan.bone(b, c)
            if (rig.keys.any { it.chan == ch }) { if (kotlin.math.abs(rigValue(track, ch) - c.neutral) > 1e-6f) rigPutKey(track, ch, c.neutral) }
            else setRig(track) { it.withBase(ch, c.neutral) }
        }
    }
}

/** Remove the rig of the row (one undo step). */
fun EditorState.clearRig(track: Int = activeTrack) = edit {
    setRig(track) { Rig() }
    for (i in drawTracks.indices) if (drawTracks[i].attach?.track == track) drawTracks[i] = drawTracks[i].copy(attach = null)
    rigUi.bone = -1; rigUi.curve = -1; rigUi.ctl = -1
    if (kfOpen is PropRef.RigChannel) kfOpen = null
}

/** IK on / off for a bone: [chain] = bones solved (0 = off). The target starts at the bone's tail. */
fun EditorState.setIkChain(track: Int, bone: Int, chain: Int) = edit {
    val rig = trackRig(track)
    val b = rig.bones.getOrNull(bone) ?: return@edit
    val c = chain.coerceIn(0, 8)
    setRig(track) { r ->
        r.withBone(bone) { it.copy(ikChain = c) }.let { r2 ->
            if (b.ikChain == 0 && c > 0) r2.withBase(RigChan.bone(bone, BoneChan.IkX), b.tailX).withBase(RigChan.bone(bone, BoneChan.IkY), b.tailY) else r2
        }
    }
}

// ------------------------------------------------------------------------------------------------ generic key drawer hooks
// The key drawer / easing editor work on any drawing-row property: a transform slider (PropRef.Draw) or a rig channel.

internal fun EditorState.propTrack(p: PropRef): Int = when (p) {
    is PropRef.Draw -> p.track
    is PropRef.RigChannel -> p.track
    is PropRef.AudioGain -> -1
}

internal fun EditorState.propLabel(p: PropRef): String = when (p) {
    is PropRef.Draw -> p.chan.label
    is PropRef.RigChannel -> p.label
    is PropRef.AudioGain -> "Volume"
}

internal fun EditorState.propLane(p: PropRef): List<ChanKey> = when (p) {
    is PropRef.Draw -> laneKeys(p.track, p.chan)
    is PropRef.RigChannel -> rigLane(p.track, p.chan)
    is PropRef.AudioGain -> emptyList()
}

internal fun EditorState.propSegmentKey(p: PropRef): ChanKey? = when (p) {
    is PropRef.Draw -> segmentKey(p)
    is PropRef.RigChannel -> rigSegmentKey(p)
    is PropRef.AudioGain -> null
}

internal fun EditorState.propKeyOf(p: PropRef, local: Int): ChanKey? = when (p) {
    is PropRef.Draw -> keyOf(p, local)
    is PropRef.RigChannel -> rigKeyOf(p, local)
    is PropRef.AudioGain -> null
}

/** Raw: easing of the segment leaving the key at [local]. */
internal fun EditorState.propSetEase(p: PropRef, local: Int, e: Easing) {
    when (p) {
        is PropRef.Draw -> setEase(p.track, p.chan, local, e)
        is PropRef.RigChannel -> rigSetEase(p, local, e)
        is PropRef.AudioGain -> Unit
    }
}

internal fun EditorState.propSetEaseAll(p: PropRef, e: Easing) {
    when (p) {
        is PropRef.Draw -> setEaseAll(p.track, p.chan, e)
        is PropRef.RigChannel -> rigSetEaseAll(p, e)
        is PropRef.AudioGain -> Unit
    }
}

// ------------------------------------------------------------------------------------------------ attach to a bone of another row

/** Attach [track] to [a] (null = detach). One undo step. The row's picture does not jump: at rest a bone's skin is the identity. */
fun EditorState.setAttach(track: Int, a: BoneAttach?) = edit {
    val i = rowOf(track)
    if (i >= 0) drawTracks[i] = drawTracks[i].copy(attach = a)
}

/** Rows that [track] may attach to: they have bones and would not form a loop. */
fun EditorState.attachCandidates(track: Int): List<DrawTrack> = drawTracks.filter { c ->
    if (c.id == track || c.rig.bones.isEmpty()) return@filter false
    var next: Int? = c.attach?.track
    var steps = 0
    while (next != null && steps++ < 64) {
        if (next == track) return@filter false
        val id: Int = next
        next = drawTracks.firstOrNull { it.id == id }?.attach?.track
    }
    steps < 64
}

/** Bone removal in [track]: rows attached to a removed bone are detached, the others follow the renumbering. Raw. */
fun EditorState.fixAttachAfterRemove(track: Int, oldRig: Rig, removed: Int) {
    val map = oldRig.boneRemap(removed)
    for (i in drawTracks.indices) {
        val a = drawTracks[i].attach ?: continue
        if (a.track != track) continue
        val nb = map.getOrElse(a.bone) { -1 }
        drawTracks[i] = drawTracks[i].copy(attach = if (nb >= 0) BoneAttach(track, nb) else null)
    }
}

/** The matrix the attachment adds to [track] right now: a b c d tx ty (paper units); null = identity. */
fun EditorState.attachAff(track: Int): FloatArray? {
    val row = rowOf(track)
    if (row < 0 || drawTracks[row].attach == null) return null
    val m = NativeCanvas.attach(row)
    return if (m.size == 6) m else null
}

/** Shown paper point -> where it was before the attachment moved the row (touch input on an attached row). */
fun EditorState.unattach(track: Int, q: Offset): Offset {
    val m = attachAff(track) ?: return q
    val det = m[0] * m[3] - m[1] * m[2]
    if (kotlin.math.abs(det) < 1e-8f) return q
    val x = q.x - m[4]; val y = q.y - m[5]
    return Offset((m[3] * x - m[2] * y) / det, (-m[1] * x + m[0] * y) / det)
}

/** Paper point -> shown point after the attachment. */
fun EditorState.reattach(track: Int, p: Offset): Offset {
    val m = attachAff(track) ?: return p
    return Offset(m[0] * p.x + m[2] * p.y + m[4], m[1] * p.x + m[3] * p.y + m[5])
}

// ------------------------------------------------------------------------------------------------ pose gestures (raw)
// All of these take LAYER-space points (row transform already inverted) and key through [setRigChan].

private const val RIG_DEG = 0.017453292f

private fun rigWrap(d: Float): Float {
    var r = d % 360f
    if (r > 180f) r -= 360f
    if (r < -180f) r += 360f
    return r
}

/** Rotate [bone] so it points at [target]; the value stays on the turn nearest to the current one (no 360 jumps). */
fun EditorState.poseBoneToward(track: Int, bone: Int, target: Offset) {
    val rig = trackRig(track)
    val b = rig.bones.getOrNull(bone) ?: return
    val pose = rigPose(track) ?: return
    val theta = kotlin.math.atan2(target.y - pose.hy(bone), target.x - pose.hx(bone)) / RIG_DEG
    val pa = if (b.parent >= 0) pose.angle(b.parent) else 0f
    val pr = if (b.parent >= 0) rig.bones[b.parent].angle else 0f
    val want = theta - pa - (b.angle - pr)
    val ch = RigChan.bone(bone, BoneChan.Rotate)
    val cur = pose.value(ch)
    setRigChan(track, ch, cur + rigWrap(want - cur))
}

/** Slide [bone] by [d] (layer units, world direction). The offset lives in the parent's frame, so it is rotated back. */
fun EditorState.poseBoneMove(track: Int, bone: Int, d: Offset) {
    val rig = trackRig(track)
    val b = rig.bones.getOrNull(bone) ?: return
    val pose = rigPose(track) ?: return
    val pa = (if (b.parent >= 0) pose.angle(b.parent) else 0f) * RIG_DEG
    val c = kotlin.math.cos(pa); val s = kotlin.math.sin(pa)
    val lx = c * d.x + s * d.y
    val ly = -s * d.x + c * d.y
    val cx = RigChan.bone(bone, BoneChan.MoveX); val cy = RigChan.bone(bone, BoneChan.MoveY)
    setRigChan(track, cx, pose.value(cx) + lx)
    setRigChan(track, cy, pose.value(cy) + ly)
}

/** Rotate [bone] by the angle the finger swept around its (posed) head from [prev] to [cur]. */
fun EditorState.poseBoneRotateDrag(track: Int, bone: Int, prev: Offset, cur: Offset) {
    val pose = rigPose(track) ?: return
    if (bone !in 0 until pose.boneCount) return
    val h = Offset(pose.hx(bone), pose.hy(bone))
    if ((cur - h).getDistance() < 0.01f || (prev - h).getDistance() < 0.01f) return
    val a0 = kotlin.math.atan2(prev.y - h.y, prev.x - h.x)
    val a1 = kotlin.math.atan2(cur.y - h.y, cur.x - h.x)
    val ch = RigChan.bone(bone, BoneChan.Rotate)
    setRigChan(track, ch, pose.value(ch) + rigWrap((a1 - a0) / RIG_DEG))
}

/** Scale [bone] with the finger: along the bone = Length, across it = Thickness (ratios of the finger's distance to the head). */
fun EditorState.poseBoneScaleDrag(track: Int, bone: Int, prev: Offset, cur: Offset) {
    val pose = rigPose(track) ?: return
    if (bone !in 0 until pose.boneCount) return
    val h = Offset(pose.hx(bone), pose.hy(bone))
    val t = Offset(pose.tx(bone), pose.ty(bone))
    val axis = t - h
    val l = axis.getDistance()
    if (l < 1e-4f) return
    val u = Offset(axis.x / l, axis.y / l)
    fun along(p: Offset) = (p.x - h.x) * u.x + (p.y - h.y) * u.y
    fun across(p: Offset) = kotlin.math.abs(-(p.x - h.x) * u.y + (p.y - h.y) * u.x)
    val a0 = along(prev); val a1 = along(cur)
    if (kotlin.math.abs(a0) > 0.03f && a0 * a1 > 0f) {
        val ch = RigChan.bone(bone, BoneChan.Length)
        setRigChan(track, ch, (pose.value(ch) * (a1 / a0).coerceIn(0.5f, 2f)).coerceIn(0.2f, 3f))
    }
    val p0 = across(prev); val p1 = across(cur)
    if (p0 > 0.02f) {
        val ch = RigChan.bone(bone, BoneChan.Thick)
        setRigChan(track, ch, (pose.value(ch) * (p1 / p0).coerceIn(0.5f, 2f)).coerceIn(0.2f, 3f))
    }
}

/** IK target of [bone] to [target]. */
fun EditorState.poseIk(track: Int, bone: Int, target: Offset) {
    setRigChan(track, RigChan.bone(bone, BoneChan.IkX), target.x)
    setRigChan(track, RigChan.bone(bone, BoneChan.IkY), target.y)
}

/** Offset a deform control point by [d] (layer units): keys its dx / dy. */
fun EditorState.poseCtlMove(track: Int, curve: Int, ctl: Int, d: Offset) {
    val pose = rigPose(track) ?: return
    val cx = RigChan.ctl(curve, ctl, 0); val cy = RigChan.ctl(curve, ctl, 1)
    setRigChan(track, cx, pose.value(cx) + d.x)
    setRigChan(track, cy, pose.value(cy) + d.y)
}

/** Snap for a dragged HEAD of bone [b]: the nearest tail of an earlier bone within [r] (layer units). Returns (tail point, that bone). */
fun Rig.snapHead(b: Int, p: Offset, r: Float): Pair<Offset, Int>? {
    var best: Pair<Offset, Int>? = null
    var bd = r
    for (j in 0 until b.coerceAtMost(bones.size)) {
        val t = Offset(bones[j].tailX, bones[j].tailY)
        val d = (t - p).getDistance()
        if (d < bd) { bd = d; best = t to j }
    }
    return best
}

/** Snap for a dragged TAIL of bone [b]: the nearest head of a later bone that is not already b's child, within [r]. */
fun Rig.snapTail(b: Int, p: Offset, r: Float): Pair<Offset, Int>? {
    var best: Pair<Offset, Int>? = null
    var bd = r
    for (k in b + 1 until bones.size) {
        if (bones[k].parent == b) continue
        val h = Offset(bones[k].hx, bones[k].hy)
        val d = (h - p).getDistance()
        if (d < bd) { bd = d; best = h to k }
    }
    return best
}

/** Nearest bone (by distance to its segment) to [p] within [maxDist], or -1. Used to bind new control points. */
fun Rig.nearestBone(p: Offset, maxDist: Float): Int {
    var best = -1
    var bd = maxDist
    for (i in bones.indices) {
        val b = bones[i]
        val ax = b.hx; val ay = b.hy; val bx = b.tailX; val by = b.tailY
        val vx = bx - ax; val vy = by - ay
        val l2 = vx * vx + vy * vy
        val t = if (l2 < 1e-9f) 0f else (((p.x - ax) * vx + (p.y - ay) * vy) / l2).coerceIn(0f, 1f)
        val d = kotlin.math.hypot(p.x - (ax + vx * t), p.y - (ay + vy * t))
        if (d < bd) { bd = d; best = i }
    }
    return best
}
