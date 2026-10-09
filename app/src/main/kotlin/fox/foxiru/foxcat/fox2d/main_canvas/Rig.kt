package fox.foxiru.foxcat.fox2d.main_canvas

import androidx.compose.runtime.Immutable
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ======================================================================================================
// Rig data: bones (skeleton) + deform curves (bend the picture along a spline) of ONE drawing row.
// This file is DATA + pure edit functions only. Every pose / skinned vertex / bent vertex is computed in C++
// (fox_rig.h, shared by the live canvas and the movie exporter). The layout constants below MIRROR fox_rig.h.
//
// All positions are in LAYER paper units (0..1, y down), i.e. the space strokes are stored in. The row's own
// transform (LayerXf: move / rotate / resize) is applied on top of the rig.
// ======================================================================================================

private const val DEG = 0.017453292f

object RigLimits {
    const val BONE_CHANS = 8
    const val MAX_BONES = 64
    const val MAX_CURVES = 8
    const val MAX_CTL = 16
    const val CURVE_CHANS = 1 + 3 * MAX_CTL
    const val CURVE_BASE = MAX_BONES * BONE_CHANS
    const val MAX_PINS = 24
    const val PIN_CHANS = 2
    const val PIN_BASE = CURVE_BASE + MAX_CURVES * CURVE_CHANS
    const val PIN_STRIDE = 6
    const val PIN_HEAD = 2
    /** Pin rotation channels (degrees, clockwise): one per pin, after the offset channels so old pin keys keep their ids. */
    const val PIN_ROT_BASE = PIN_BASE + MAX_PINS * PIN_CHANS
    const val TOTAL_CHANS = PIN_ROT_BASE + MAX_PINS
    const val BONE_STRIDE = 20
    const val CTL_STRIDE = 6
    const val CURVE_HEAD = 8
    const val ROW_HEAD = 10
    val GRIDS = listOf(24, 32, 48, 64, 96)
}

/** Animatable channels of a bone. [id] = index inside the bone's 8 channels. [neutral] = the un-posed value. */
enum class BoneChan(val id: Int, val label: String, val neutral: Float) {
    MoveX(0, "Move X", 0f), MoveY(1, "Move Y", 0f), Rotate(2, "Rotate", 0f),
    Length(3, "Length", 1f), Thick(4, "Thickness", 1f),
    IkX(5, "IK target X", 0f), IkY(6, "IK target Y", 0f), IkMix(7, "IK mix", 1f),
}

/** Channel numbering shared with C++ (key [1] of every flat key). */
object RigChan {
    fun bone(b: Int, c: BoneChan) = b * RigLimits.BONE_CHANS + c.id
    fun curveMix(k: Int) = RigLimits.CURVE_BASE + k * RigLimits.CURVE_CHANS
    /** [c]: 0 = offset X, 1 = offset Y, 2 = thickness. */
    fun ctl(k: Int, i: Int, c: Int) = RigLimits.CURVE_BASE + k * RigLimits.CURVE_CHANS + 1 + i * 3 + c

    /** Puppet-warp pin [i]; [c]: 0 = offset X, 1 = offset Y. */
    fun pin(i: Int, c: Int) = RigLimits.PIN_BASE + i * RigLimits.PIN_CHANS + c
    /** Rotation of puppet-warp pin [i], degrees clockwise. */
    fun pinRot(i: Int) = RigLimits.PIN_ROT_BASE + i

    fun isBone(ch: Int) = ch < RigLimits.CURVE_BASE
    fun isPin(ch: Int) = ch >= RigLimits.PIN_BASE
    fun isPinRot(ch: Int) = ch >= RigLimits.PIN_ROT_BASE
    fun pinOf(ch: Int) = if (isPinRot(ch)) ch - RigLimits.PIN_ROT_BASE else (ch - RigLimits.PIN_BASE) / RigLimits.PIN_CHANS
    /** 0 = offset X, 1 = offset Y, 2 = rotation. */
    fun pinSlot(ch: Int) = if (isPinRot(ch)) 2 else (ch - RigLimits.PIN_BASE) % RigLimits.PIN_CHANS
    fun boneOf(ch: Int) = ch / RigLimits.BONE_CHANS
    fun boneChanOf(ch: Int) = ch % RigLimits.BONE_CHANS
    fun curveOf(ch: Int) = (ch - RigLimits.CURVE_BASE) / RigLimits.CURVE_CHANS
    /** 0 = curve mix, otherwise 1 + ctl * 3 + c. */
    fun curveSlot(ch: Int) = (ch - RigLimits.CURVE_BASE) % RigLimits.CURVE_CHANS
}

/**
 * One bone. Rest pose = absolute [hx],[hy] head, [angle] (degrees, clockwise on screen) and [len]. [parent] is an
 * index into the rig's bone list and is always LOWER than the bone's own index. [base] = the 8 channel values used
 * while a channel has no keys. Influence on the picture: [radius] (paper units), [falloff] (curve of the fade),
 * [strength]. [ikChain] > 0 makes the bone the tip of an IK chain of that many bones aiming at (IkX, IkY).
 */
@Immutable
data class Bone(
    val name: String,
    val parent: Int,
    val hx: Float, val hy: Float,
    val angle: Float,
    val len: Float,
    val radius: Float,
    val falloff: Float = 2.5f,
    val strength: Float = 1f,
    val base: List<Float> = listOf(0f, 0f, 0f, 1f, 1f, 0f, 0f, 1f),
    val ikChain: Int = 0,
    val ikBend: Float = 1f,
) {
    val tailX: Float get() = hx + cos(angle * DEG) * len
    val tailY: Float get() = hy + sin(angle * DEG) * len
    fun withBase(c: BoneChan, v: Float) = copy(base = base.toMutableList().also { it[c.id] = v })
}

/** Control point of a deform curve. [rx],[ry] rest position, [bone] it follows (-1 = free), [dx],[dy],[thick] static pose values. */
@Immutable
data class CtlPoint(val rx: Float, val ry: Float, val bone: Int = -1, val dx: Float = 0f, val dy: Float = 0f, val thick: Float = 1f)

/** Puppet-warp pin. [rx],[ry] rest position (layer paper units), [dx],[dy] static offset used while the channel has no keys. */
@Immutable
data class Pin(
    val rx: Float, val ry: Float, val dx: Float = 0f, val dy: Float = 0f,
    /** Static rotation (degrees clockwise) used while the rotation channel has no keys. */
    val rot: Float = 0f,
    /** Influence multiplier in the warp weights: 0.2 soft .. 4 dominant. */
    val stiff: Float = 1f,
)

/** Catmull-Rom spline through [pts]. Picture within [reach] of the rest curve bends with it; [softness] = fade width. [mix] = deform amount. */
@Immutable
data class DeformCurve(
    val name: String,
    val pts: List<CtlPoint>,
    val reach: Float = 0.12f,
    val softness: Float = 0.6f,
    val mix: Float = 1f,
)

/** Hand-painted skin weight bias, [verts] x [bones] floats in -1..1 added to the automatic weights. [version] makes in-place stroke edits comparable. */
class WeightPaint(val verts: Int, val bones: Int, val data: FloatArray, val version: Int = 0) {
    override fun equals(other: Any?) =
        other is WeightPaint && other.verts == verts && other.bones == bones && other.version == version &&
            (other.data === data || other.data.contentEquals(data))

    override fun hashCode() = (verts * 31 + bones) * 31 + version

    /** [from][newBone] = index of that bone in this paint, -1 = new bone (no paint). */
    fun remapBones(newBones: Int, from: IntArray): WeightPaint {
        val out = FloatArray(verts * newBones)
        for (v in 0 until verts) for (b in 0 until newBones) {
            val o = from[b]
            if (o in 0 until bones) out[v * newBones + b] = data[v * bones + o]
        }
        return WeightPaint(verts, newBones, out, version + 1)
    }
}

@Immutable
data class MeshRect(val l: Float = 0f, val t: Float = 0f, val r: Float = 1f, val b: Float = 1f)

/** Everything the rig of one drawing row consists of. Keys: sorted by (channel, row-local frame), channel numbers from [RigChan]. */
@Immutable
data class Rig(
    val bones: List<Bone> = emptyList(),
    val curves: List<DeformCurve> = emptyList(),
    val grid: Int = 48,
    val rect: MeshRect = MeshRect(),
    val keys: List<ChanKey> = emptyList(),
    val paint: WeightPaint? = null,
    val pins: List<Pin> = emptyList(),
    /** Puppet-warp reach: 1 = wide, 2 = medium, 3 = tight (MLS weight exponent). */
    val warpFalloff: Int = 2,
) {
    val isEmpty: Boolean get() = bones.isEmpty() && curves.isEmpty() && pins.isEmpty()
    val vertCount: Int get() = (grid + 1) * (grid + 1)

    /** Flat row blob for native (layout: fox_rig.h). Built once per immutable copy. */
    val packed: FloatArray by lazy(LazyThreadSafetyMode.NONE) { pack() }

    private fun pack(): FloatArray {
        if (isEmpty) return FloatArray(0)
        val p = paint?.takeIf { it.verts == vertCount && it.bones == bones.size }
        val sorted = keys.sortedWith(ChanKeyOrder)
        val ctlCount = curves.sumOf { it.pts.size }
        val a = FloatArray(
            RigLimits.ROW_HEAD + bones.size * RigLimits.BONE_STRIDE + curves.size * RigLimits.CURVE_HEAD +
                ctlCount * RigLimits.CTL_STRIDE +
                (if (pins.isEmpty()) 0 else RigLimits.PIN_HEAD + pins.size * RigLimits.PIN_STRIDE) + sorted.size * KEY_STRIDE + (p?.data?.size ?: 0)
        )
        a[0] = bones.size.toFloat(); a[1] = curves.size.toFloat(); a[2] = grid.toFloat()
        a[3] = rect.l; a[4] = rect.t; a[5] = rect.r; a[6] = rect.b
        a[7] = sorted.size.toFloat(); a[8] = (p?.data?.size ?: 0).toFloat()
        a[9] = pins.size.toFloat()
        var o = RigLimits.ROW_HEAD
        for (b in bones) {
            a[o] = b.parent.toFloat(); a[o + 1] = b.hx; a[o + 2] = b.hy; a[o + 3] = b.angle; a[o + 4] = b.len
            a[o + 5] = b.radius; a[o + 6] = b.falloff; a[o + 7] = b.strength
            for (c in 0 until 8) a[o + 8 + c] = b.base[c]
            a[o + 16] = b.ikChain.toFloat(); a[o + 17] = b.ikBend
            o += RigLimits.BONE_STRIDE
        }
        for (c in curves) {
            a[o] = c.pts.size.toFloat(); a[o + 1] = c.reach; a[o + 2] = c.softness; a[o + 3] = c.mix
            o += RigLimits.CURVE_HEAD
            for (t in c.pts) {
                a[o] = t.rx; a[o + 1] = t.ry; a[o + 2] = t.bone.toFloat(); a[o + 3] = t.dx; a[o + 4] = t.dy; a[o + 5] = t.thick
                o += RigLimits.CTL_STRIDE
            }
        }
        if (pins.isNotEmpty()) {
            a[o] = warpFalloff.coerceIn(1, 3).toFloat(); a[o + 1] = 0f
            o += RigLimits.PIN_HEAD
            for (t in pins) { a[o] = t.rx; a[o + 1] = t.ry; a[o + 2] = t.dx; a[o + 3] = t.dy; a[o + 4] = t.rot; a[o + 5] = t.stiff; o += RigLimits.PIN_STRIDE }
        }
        for (k in sorted) { k.writeTo(a, o); o += KEY_STRIDE }
        if (p != null) { p.data.copyInto(a, o) }
        return a
    }

    // ------------------------------------------------------------------ keys (raw, pure)

    fun lane(chan: Int): List<ChanKey> = keys.filter { it.chan == chan }

    /** Create / replace the key of [chan] at [frame]. An existing key keeps its easing. */
    fun withKey(chan: Int, frame: Int, value: Float, ease: Easing): Rig {
        val out = keys.toMutableList()
        val at = out.indexOfFirst { it.chan == chan && it.frame == frame }
        if (at >= 0) out[at] = out[at].copy(value = value)
        else { out.add(ChanKey(chan, frame, value, ease)); out.sortWith(ChanKeyOrder) }
        return copy(keys = out)
    }

    /** Static value of a channel (used while it has no keys). */
    fun withBase(chan: Int, value: Float): Rig {
        if (RigChan.isBone(chan)) {
            val b = RigChan.boneOf(chan)
            if (b !in bones.indices) return this
            val c = BoneChan.values()[RigChan.boneChanOf(chan)]
            return copy(bones = bones.toMutableList().also { it[b] = it[b].withBase(c, value) })
        }
        if (RigChan.isPin(chan)) {
            val i = RigChan.pinOf(chan)
            val p = pins.getOrNull(i) ?: return this
            return copy(pins = pins.toMutableList().also { it[i] = when (RigChan.pinSlot(chan)) { 0 -> p.copy(dx = value); 1 -> p.copy(dy = value); else -> p.copy(rot = value) } })
        }
        val k = RigChan.curveOf(chan)
        if (k !in curves.indices) return this
        val slot = RigChan.curveSlot(chan)
        val cv = curves[k]
        val nc = if (slot == 0) cv.copy(mix = value) else {
            val i = (slot - 1) / 3
            if (i !in cv.pts.indices) return this
            val p = cv.pts[i]
            cv.copy(pts = cv.pts.toMutableList().also {
                it[i] = when ((slot - 1) % 3) { 0 -> p.copy(dx = value); 1 -> p.copy(dy = value); else -> p.copy(thick = value) }
            })
        }
        return copy(curves = curves.toMutableList().also { it[k] = nc })
    }

    /** Static value of a channel as stored (not the animated one). */
    fun baseOf(chan: Int): Float {
        if (RigChan.isBone(chan)) return bones.getOrNull(RigChan.boneOf(chan))?.base?.get(RigChan.boneChanOf(chan)) ?: 0f
        if (RigChan.isPin(chan)) {
            val p = pins.getOrNull(RigChan.pinOf(chan)) ?: return 0f
            return when (RigChan.pinSlot(chan)) { 0 -> p.dx; 1 -> p.dy; else -> p.rot }
        }
        val cv = curves.getOrNull(RigChan.curveOf(chan)) ?: return 0f
        val slot = RigChan.curveSlot(chan)
        if (slot == 0) return cv.mix
        val p = cv.pts.getOrNull((slot - 1) / 3) ?: return 0f
        return when ((slot - 1) % 3) { 0 -> p.dx; 1 -> p.dy; else -> p.thick }
    }

    // ------------------------------------------------------------------ bones

    private fun freeName(prefix: String, used: List<String>): String {
        var n = used.size + 1
        while ("$prefix $n" in used) n++
        return "$prefix $n"
    }

    /** New bone from head to tail. [parent] must be an existing bone (or -1). */
    fun addBone(parent: Int, hx: Float, hy: Float, tx: Float, ty: Float): Rig {
        if (bones.size >= RigLimits.MAX_BONES) return this
        val len = max(0.01f, hypot(tx - hx, ty - hy))
        val ang = atan2(ty - hy, tx - hx) / DEG
        val b = Bone(
            freeName("Bone", bones.map { it.name }), if (parent in bones.indices) parent else -1, hx, hy, ang, len,
            radius = max(0.04f, len * 0.6f), base = listOf(0f, 0f, 0f, 1f, 1f, tx, ty, 1f),
        )
        val n = bones.size + 1
        return copy(bones = bones + b, paint = paint?.remapBones(n, IntArray(n) { if (it < n - 1) it else -1 }))
    }

    fun withBone(i: Int, f: (Bone) -> Bone): Rig =
        if (i !in bones.indices) this else copy(bones = bones.toMutableList().also { it[i] = f(it[i]) })

    /** Removes bone [i] and everything below it. Keys, curve bindings and painted weights follow the renumbering. */
    fun removeBone(i: Int): Rig {
        if (i !in bones.indices) return this
        val dead = BooleanArray(bones.size)
        dead[i] = true
        for (j in i + 1 until bones.size) if (bones[j].parent >= 0 && dead[bones[j].parent]) dead[j] = true
        val map = IntArray(bones.size) { -1 }
        var n = 0
        for (j in bones.indices) if (!dead[j]) map[j] = n++
        val from = IntArray(n)
        for (j in bones.indices) if (map[j] >= 0) from[map[j]] = j
        val nb = bones.indices.filter { !dead[it] }.map { j -> bones[j].let { b -> b.copy(parent = if (b.parent >= 0) map[b.parent] else -1) } }
        val nk = keys.mapNotNull { k ->
            if (!RigChan.isBone(k.chan)) k else {
                val nbI = map[RigChan.boneOf(k.chan)]
                if (nbI < 0) null else k.copy(chan = nbI * RigLimits.BONE_CHANS + RigChan.boneChanOf(k.chan))
            }
        }.sortedWith(ChanKeyOrder)
        val nc = curves.map { c -> c.copy(pts = c.pts.map { p -> if (p.bone >= 0) p.copy(bone = map[p.bone]) else p }) }
        return copy(bones = nb, curves = nc, keys = nk, paint = paint?.remapBones(n, from))
    }

    fun connected(child: Int): Boolean {
        val b = bones.getOrNull(child) ?: return false
        val p = bones.getOrNull(b.parent) ?: return false
        return hypot(b.hx - p.tailX, b.hy - p.tailY) < 1e-3f
    }

    /** Joint drag: bone [i]'s tail goes to (x, y). Children attached to the old tail follow with their own tail fixed. */
    fun moveTail(i: Int, x: Float, y: Float): Rig {
        val b = bones.getOrNull(i) ?: return this
        val ox = b.tailX; val oy = b.tailY
        val out = bones.toMutableList()
        val len = max(0.01f, hypot(x - b.hx, y - b.hy))
        out[i] = b.copy(angle = atan2(y - b.hy, x - b.hx) / DEG, len = len)
        val nx = out[i].tailX; val ny = out[i].tailY
        for (j in bones.indices) {
            val c = bones[j]
            if (c.parent == i && hypot(c.hx - ox, c.hy - oy) < 1e-3f) {
                val ctx = c.tailX; val cty = c.tailY
                out[j] = c.copy(hx = nx, hy = ny, angle = atan2(cty - ny, ctx - nx) / DEG, len = max(0.01f, hypot(ctx - nx, cty - ny)))
            }
        }
        return copy(bones = out)
    }

    /** Whole bone + everything below it moves by (dx, dy). */
    fun translateSubtree(i: Int, dx: Float, dy: Float): Rig {
        if (i !in bones.indices) return this
        val inTree = BooleanArray(bones.size)
        inTree[i] = true
        for (j in i + 1 until bones.size) if (bones[j].parent >= 0 && inTree[bones[j].parent]) inTree[j] = true
        return copy(
            bones = bones.mapIndexed { j, b ->
                if (!inTree[j]) b else b.copy(hx = b.hx + dx, hy = b.hy + dy, base = b.base.toMutableList().also { it[5] += dx; it[6] += dy })
            },
            curves = curves.map { c -> c.copy(pts = c.pts.map { p -> if (p.bone >= 0 && inTree[p.bone]) p.copy(rx = p.rx + dx, ry = p.ry + dy) else p }) },
        )
    }

    /** Head drag: attached to the parent -> moves the parent's tail (joint); a root moves the whole skeleton below it; otherwise only the head, tail fixed. */
    fun moveHead(i: Int, x: Float, y: Float): Rig {
        val b = bones.getOrNull(i) ?: return this
        if (connected(i)) return moveTail(b.parent, x, y)
        if (b.parent < 0) return translateSubtree(i, x - b.hx, y - b.hy)
        val tx = b.tailX; val ty = b.tailY
        return withBone(i) { it.copy(hx = x, hy = y, angle = atan2(ty - y, tx - x) / DEG, len = max(0.01f, hypot(tx - x, ty - y))) }
    }

    /** Re-parent [i] to [p] (must be lower than [i]; -1 = root). */
    fun withParent(i: Int, p: Int): Rig = if (i !in bones.indices || p >= i || p < -1) this else withBone(i) { it.copy(parent = p) }

    // ------------------------------------------------------------------ curves

    fun addCurve(pts: List<CtlPoint>): Rig =
        if (curves.size >= RigLimits.MAX_CURVES) this
        else copy(curves = curves + DeformCurve(freeName("Curve", curves.map { it.name }), pts.take(RigLimits.MAX_CTL)))

    fun withCurve(k: Int, f: (DeformCurve) -> DeformCurve): Rig =
        if (k !in curves.indices) this else copy(curves = curves.toMutableList().also { it[k] = f(it[k]) })

    fun removeCurve(k: Int): Rig {
        if (k !in curves.indices) return this
        val nk = keys.mapNotNull { key ->
            if (RigChan.isBone(key.chan) || RigChan.isPin(key.chan)) key else {
                val c = RigChan.curveOf(key.chan)
                when {
                    c == k -> null
                    c > k -> key.copy(chan = key.chan - RigLimits.CURVE_CHANS)
                    else -> key
                }
            }
        }
        return copy(curves = curves.filterIndexed { i, _ -> i != k }, keys = nk)
    }

    /** Insert a control point so it becomes point number [at] of curve [k]. Later points' keys move up one slot. */
    fun insertCtl(k: Int, at: Int, p: CtlPoint): Rig {
        val c = curves.getOrNull(k) ?: return this
        if (c.pts.size >= RigLimits.MAX_CTL) return this
        val i = at.coerceIn(0, c.pts.size)
        val nk = keys.map { key ->
            if (RigChan.isBone(key.chan) || RigChan.isPin(key.chan) || RigChan.curveOf(key.chan) != k) key else {
                val slot = RigChan.curveSlot(key.chan)
                if (slot == 0) key else {
                    val idx = (slot - 1) / 3
                    if (idx >= i) key.copy(chan = key.chan + 3) else key
                }
            }
        }.sortedWith(ChanKeyOrder)
        return copy(curves = curves.toMutableList().also { it[k] = c.copy(pts = c.pts.toMutableList().also { l -> l.add(i, p) }) }, keys = nk)
    }

    fun removeCtl(k: Int, i: Int): Rig {
        val c = curves.getOrNull(k) ?: return this
        if (i !in c.pts.indices) return this
        val nk = keys.mapNotNull { key ->
            if (RigChan.isBone(key.chan) || RigChan.isPin(key.chan) || RigChan.curveOf(key.chan) != k) key else {
                val slot = RigChan.curveSlot(key.chan)
                if (slot == 0) key else {
                    val idx = (slot - 1) / 3
                    when {
                        idx == i -> null
                        idx > i -> key.copy(chan = key.chan - 3)
                        else -> key
                    }
                }
            }
        }.sortedWith(ChanKeyOrder)
        return copy(curves = curves.toMutableList().also { it[k] = c.copy(pts = c.pts.filterIndexed { j, _ -> j != i }) }, keys = nk)
    }

    fun withCtl(k: Int, i: Int, f: (CtlPoint) -> CtlPoint): Rig =
        withCurve(k) { c -> if (i !in c.pts.indices) c else c.copy(pts = c.pts.toMutableList().also { it[i] = f(it[i]) }) }

    // ------------------------------------------------------------------ puppet warp pins

    fun addPin(x: Float, y: Float): Rig =
        if (pins.size >= RigLimits.MAX_PINS) this else copy(pins = pins + Pin(x, y))

    /** Moves the REST position of pin [i] (Build mode). */
    fun movePin(i: Int, x: Float, y: Float): Rig =
        if (i !in pins.indices) this else copy(pins = pins.toMutableList().also { it[i] = it[i].copy(rx = x, ry = y) })

    /** Removes pin [i]; its keys go, later pins' keys shift down. */
    fun removePin(i: Int): Rig {
        if (i !in pins.indices) return this
        val nk = keys.mapNotNull { key ->
            if (!RigChan.isPin(key.chan)) key else {
                val p = RigChan.pinOf(key.chan)
                when {
                    p == i -> null
                    p > i -> key.copy(chan = key.chan - if (RigChan.isPinRot(key.chan)) 1 else RigLimits.PIN_CHANS)
                    else -> key
                }
            }
        }
        return copy(pins = pins.filterIndexed { j, _ -> j != i }, keys = nk)
    }

    /** Drops every pin and its keys. */
    fun clearPins(): Rig = copy(pins = emptyList(), keys = keys.filter { !RigChan.isPin(it.chan) })

    /** Influence of pin [i] in the warp (0.2 soft .. 4 dominant). */
    fun withPinStiff(i: Int, v: Float): Rig =
        if (i !in pins.indices) this else copy(pins = pins.toMutableList().also { it[i] = it[i].copy(stiff = v.coerceIn(0.2f, 4f)) })

    fun withWarpFalloff(f: Int): Rig = copy(warpFalloff = f.coerceIn(1, 3))

    // ------------------------------------------------------------------ mesh + weights

    /** New mesh. Painted weights belong to the old vertices, so they are dropped. */
    fun withMesh(grid: Int, rect: MeshRect): Rig =
        if (grid == this.grid && rect == this.rect) this else copy(grid = grid.coerceIn(2, 128), rect = rect, paint = null)

    /** A copy of the paint data ready for in-place brush edits (zeros when there is none yet). */
    fun paintWork(): FloatArray {
        val p = paint?.takeIf { it.verts == vertCount && it.bones == bones.size }
        return p?.data?.copyOf() ?: FloatArray(vertCount * bones.size)
    }

    /**
     * Brush dab at layer position (cx, cy) on [work] for [bone]. Returns true when something changed.
     * [amount] > 0 adds weight, < 0 removes it; the dab fades out toward the brush edge.
     */
    fun dab(work: FloatArray, bone: Int, cx: Float, cy: Float, radius: Float, amount: Float): Boolean {
        val nB = bones.size
        if (bone !in 0 until nB || work.size != vertCount * nB || radius <= 0f) return false
        val w = grid + 1
        val sx = (rect.r - rect.l) / grid
        val sy = (rect.b - rect.t) / grid
        val i0 = ((cx - radius - rect.l) / sx).toInt().coerceIn(0, grid)
        val i1 = (((cx + radius - rect.l) / sx).toInt() + 1).coerceIn(0, grid)
        val j0 = ((cy - radius - rect.t) / sy).toInt().coerceIn(0, grid)
        val j1 = (((cy + radius - rect.t) / sy).toInt() + 1).coerceIn(0, grid)
        var changed = false
        for (j in j0..j1) for (i in i0..i1) {
            val d = hypot(rect.l + i * sx - cx, rect.t + j * sy - cy)
            if (d >= radius) continue
            val f = 1f - d / radius
            val at = (j * w + i) * nB + bone
            val nv = (work[at] + amount * f * f).coerceIn(-1f, 1f)
            if (nv != work[at]) { work[at] = nv; changed = true }
        }
        return changed
    }
}

/** A row that follows bone [bone] of row (track id) [track]. */
@Immutable
data class BoneAttach(val track: Int, val bone: Int)

/** old bone index -> new index after [Rig.removeBone] of [i] (-1 = the bone went away with it). */
fun Rig.boneRemap(i: Int): IntArray {
    val dead = BooleanArray(bones.size)
    if (i in bones.indices) dead[i] = true
    for (j in i + 1 until bones.size) if (j > 0 && bones[j].parent >= 0 && dead[bones[j].parent]) dead[j] = true
    val map = IntArray(bones.size) { -1 }
    var n = 0
    for (j in bones.indices) if (!dead[j]) map[j] = n++
    return map
}

/**
 * [parentRow, bone] per row for native / exporter. Anything invalid (parent gone, itself, no such bone, a cycle) becomes
 * -1, so a stale attachment never crashes and never binds to the wrong thing.
 */
fun packAttach(rows: List<DrawTrack>): IntArray {
    val out = IntArray(rows.size * 2) { -1 }
    for ((i, r) in rows.withIndex()) {
        val a = r.attach ?: continue
        val p = rows.indexOfFirst { it.id == a.track }
        if (p < 0 || p == i || a.bone !in rows[p].rig.bones.indices) continue
        // walk up: must not come back to this row
        var cur = p
        var ok = true
        var steps = 0
        while (cur >= 0 && steps++ < 64) {
            if (cur == i) { ok = false; break }
            val na = rows[cur].attach ?: break
            cur = rows.indexOfFirst { it.id == na.track }
        }
        if (ok && steps < 64) { out[i * 2] = p; out[i * 2 + 1] = a.bone }
    }
    return out
}

/** All rigs of the timeline rows as ONE native blob: [nRows, (len, floats) per row]. Row order = DrawTrack order. */
fun packRigs(rows: List<DrawTrack>): FloatArray {
    val parts = rows.map { it.rig.packed }
    val a = FloatArray(1 + parts.sumOf { it.size + 1 })
    a[0] = rows.size.toFloat()
    var o = 1
    for (p in parts) { a[o++] = p.size.toFloat(); p.copyInto(a, o); o += p.size }
    return a
}

// ------------------------------------------------------------------------------------------------ pose read-back

/** A deform curve as the editor draws it: control points and a dense polyline, layer paper units. */
class CurvePose(val pts: FloatArray, val samples: FloatArray) {
    val count: Int get() = pts.size / 2
    fun x(i: Int) = pts[i * 2]
    fun y(i: Int) = pts[i * 2 + 1]
}

/** Geometry of a rig at one moment (from native). Bones: head / tail. [chv] = every channel value (keys applied). */
class RigPose(val bones: FloatArray, val curves: List<CurvePose>, val chv: FloatArray, val pins: FloatArray = FloatArray(0)) {
    val pinCount: Int get() = pins.size / 2
    /** Current (posed) pin position. */
    fun pinX(i: Int) = pins[i * 2]
    fun pinY(i: Int) = pins[i * 2 + 1]
    val boneCount: Int get() = bones.size / 4
    fun hx(b: Int) = bones[b * 4]
    fun hy(b: Int) = bones[b * 4 + 1]
    fun tx(b: Int) = bones[b * 4 + 2]
    fun ty(b: Int) = bones[b * 4 + 3]
    /** Current direction of the bone in degrees. */
    fun angle(b: Int) = atan2(ty(b) - hy(b), tx(b) - hx(b)) / DEG
    fun value(chan: Int) = if (chan in chv.indices) chv[chan] else 0f
}

/** Decodes the array of NativeCanvas.evalRig (layout: fox_rig.h evalOverlay). Null when it is empty / malformed. */
fun parseRigPose(a: FloatArray): RigPose? {
    if (a.size < 2) return null
    val nB = a[0].toInt()
    val nC = a[1].toInt()
    var o = 2
    if (o + nB * 4 > a.size) return null
    val bones = a.copyOfRange(o, o + nB * 4)
    o += nB * 4
    val curves = ArrayList<CurvePose>(nC)
    for (k in 0 until nC) {
        if (o >= a.size) return null
        val n = a[o++].toInt()
        if (o + n * 2 + 1 > a.size) return null
        val pts = a.copyOfRange(o, o + n * 2)
        o += n * 2
        val ns = a[o++].toInt()
        if (o + ns * 2 > a.size) return null
        val samples = a.copyOfRange(o, o + ns * 2)
        o += ns * 2
        curves.add(CurvePose(pts, samples))
    }
    if (o >= a.size) return null
    val nP = a[o++].toInt()
    if (nP < 0 || o + nP * 2 > a.size) return null
    val pins = a.copyOfRange(o, o + nP * 2)
    o += nP * 2
    if (o + RigLimits.TOTAL_CHANS > a.size) return null
    return RigPose(bones, curves, a.copyOfRange(o, o + RigLimits.TOTAL_CHANS), pins)
}

fun minMax(v: Float, lo: Float, hi: Float) = min(hi, max(lo, v))
