package fox.foxiru.foxcat.fox2d.main_canvas

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ======================================================================================================
// KeyGraph maths: PURE functions on ONE lane (= List<ChanKey> of a single channel, sorted by frame). No Compose, no
// EditorState, no JNI: the easing curves come in through an [EaseSampler] (the app passes NativeCanvas.easeCurve, the host
// test passes its own), so everything here is unit-testable on the JVM. KeyGraphUI.kt does the gestures and the writes.
// Evaluation mirrors fox_anim.h::evalLane exactly (held outside the first / last key, easing of the segment that STARTS at a key).
// ======================================================================================================

/** [Easing] -> samples of its progress curve over t = 0..1 (both ends included, >= 2 values). Never asked for Hold / Linear. */
internal typealias EaseSampler = (Easing) -> FloatArray

internal enum class GKSmooth(val label: String) {
    /** No overshoot: a key that is a local min / max gets a flat tangent (PCHIP / Fritsch-Butland). */
    Monotone("Auto"),
    /** Catmull-Rom style: tangent follows the neighbours, may overshoot like a real spline. */
    Flow("Flow"),
    /** Flat tangents everywhere = every segment eases in and out. */
    Flat("Flat"),
}

internal enum class GKBlend(val label: String) {
    /** Clip keys replace what is in the range (with a fade it cross-fades from / to the existing curve). */
    Replace("Replace"),
    /** Existing curve moves toward the clip by `mix`. */
    Mix("Mix"),
    /** Clip motion (relative to its first key) is ADDED on top of the existing curve. */
    Add("Add"),
}

/** One lane of a clipboard: keys with frames RELATIVE to the start of the copied block. */
internal class GKClipLane(val rig: Boolean, val chan: Int, val keys: List<ChanKey>)

internal class GKClip(val lanes: List<GKClipLane>, val len: Int)

internal object GK {

    // ------------------------------------------------------------------------------------------ easing / eval

    fun ease(e: Easing, t: Float, s: EaseSampler): Float {
        if (e.isHold) return if (t >= 1f) 1f else 0f
        if (e.isLinear) return t
        val a = s(e)
        val n = a.size - 1
        val x = t.coerceIn(0f, 1f) * n
        val i = x.toInt().coerceAtMost(n - 1)
        return a[i] + (a[i + 1] - a[i]) * (x - i)
    }

    /** d ease / d t (numeric, central). */
    fun deriv(e: Easing, t: Float, s: EaseSampler): Float {
        if (e.isLinear) return 1f
        if (e.isHold) return 0f
        val h = 1f / 128f
        val t0 = max(0f, t - h)
        val t1 = min(1f, t + h)
        return (ease(e, t1, s) - ease(e, t0, s)) / (t1 - t0)
    }

    /** Value of a lane at row-local frame [f]; [st] = the static value used when the lane has no keys. */
    fun eval(k: List<ChanKey>, f: Float, st: Float, s: EaseSampler): Float {
        val n = k.size
        if (n == 0) return st
        if (n == 1 || f <= k[0].frame) return k[0].value
        if (f >= k[n - 1].frame) return k[n - 1].value
        var lo = 0
        var hi = n - 1
        while (hi - lo > 1) { val m = (lo + hi) ushr 1; if (k[m].frame <= f) lo = m else hi = m }
        return seg(k[lo], k[hi], f, s)
    }

    fun seg(a: ChanKey, b: ChanKey, f: Float, s: EaseSampler): Float {
        val span = (b.frame - a.frame).toFloat()
        val t = if (span > 1e-6f) (f - a.frame) / span else 1f
        return a.value + (b.value - a.value) * ease(a.ease, t, s)
    }

    // ------------------------------------------------------------------------------------------ tangents -> bezier

    /** Tangent (value per frame) at every key. */
    fun slopes(k: List<ChanKey>, mode: GKSmooth): FloatArray {
        val n = k.size
        val m = FloatArray(n)
        if (n < 2 || mode == GKSmooth.Flat) return m
        val h = FloatArray(n - 1) { (k[it + 1].frame - k[it].frame).toFloat() }
        val d = FloatArray(n - 1) { (k[it + 1].value - k[it].value) / h[it] }
        if (n == 2) { m[0] = d[0]; m[1] = d[0]; return m }
        for (i in 1 until n - 1) {
            m[i] = if (mode == GKSmooth.Flow) (k[i + 1].value - k[i - 1].value) / (h[i - 1] + h[i])
            else if (d[i - 1] * d[i] <= 0f) 0f
            else { val w1 = 2f * h[i] + h[i - 1]; val w2 = h[i] + 2f * h[i - 1]; (w1 + w2) / (w1 / d[i - 1] + w2 / d[i]) }
        }
        m[0] = endSlope(h[0], h[1], d[0], d[1], mode)
        m[n - 1] = endSlope(h[n - 2], h[n - 3], d[n - 2], d[n - 3], mode)
        return m
    }

    private fun sgn(x: Float) = if (x > 0f) 1 else if (x < 0f) -1 else 0

    private fun endSlope(h0: Float, h1: Float, d0: Float, d1: Float, mode: GKSmooth): Float {
        if (mode == GKSmooth.Flow) return d0
        var m = ((2f * h0 + h1) * d0 - h0 * d1) / (h0 + h1)
        if (sgn(m) != sgn(d0)) m = 0f
        else if (sgn(d0) != sgn(d1) && abs(m) > 3f * abs(d0)) m = 3f * d0
        return m
    }

    /**
     * Bezier easing of the segment [a] -> [b] whose tangents are [ma] (leaving a) / [mb] (arriving at b), value per frame.
     * x handles sit at 1/3 and 2/3, which makes x(s) = s, so the curve is exactly the cubic Hermite spline.
     */
    fun toBezier(a: ChanKey, b: ChanKey, ma: Float, mb: Float): Easing {
        val dx = (b.frame - a.frame).toFloat()
        val dv = b.value - a.value
        if (abs(dv) < 1e-6f || dx < 1f) return Easing.Linear
        val y1 = (ma * dx / (3f * dv)).coerceIn(-1f, 2f)
        val y2 = (1f - mb * dx / (3f * dv)).coerceIn(-1f, 2f)
        return Easing(Easing.BEZIER, 1f / 3f, y1, 2f / 3f, y2)
    }

    /**
     * Re-tangent a lane: every segment that starts or ends at a key in [sel] (all segments when [sel] is empty) becomes a
     * bezier through [slopes] x [tension] (0 = flat = pure ease in-out, 1 = full tangents). Hold segments stay and
     * flatten the tangent on their side. Returns the new lane.
     */
    fun smooth(k: List<ChanKey>, mode: GKSmooth, tension: Float, sel: Set<Int>): List<ChanKey> {
        val n = k.size
        if (n < 2) return k
        val m = slopes(k, mode)
        val out = k.toMutableList()
        for (i in 0 until n - 1) {
            val a = k[i]
            if (a.ease.isHold) continue
            if (sel.isNotEmpty() && a.frame !in sel && k[i + 1].frame !in sel) continue
            val ma = if (i > 0 && k[i - 1].ease.isHold) 0f else m[i]
            val mb = if (k[i + 1].ease.isHold) 0f else m[i + 1]
            out[i] = a.copy(ease = toBezier(a, k[i + 1], ma * tension, mb * tension))
        }
        return out
    }

    /** Bezier with the same START / END tangents as [e] (Hold stays Hold, Bezier stays). Lets the handles take over an eased segment. */
    fun asBezier(e: Easing, s: EaseSampler): Easing {
        if (e.isHold || e.isBezier) return e
        val d0 = deriv(e, 0f, s)
        val d1 = deriv(e, 1f, s)
        return Easing(Easing.BEZIER, 1f / 3f, (d0 / 3f).coerceIn(-1f, 2f), 2f / 3f, (1f - d1 / 3f).coerceIn(-1f, 2f))
    }

    /** Easing of the same segment walked BACKWARDS: e'(t) = 1 - e(1 - t). Hold has no mirror, it stays. */
    fun reverseEase(e: Easing): Easing = when {
        e.isHold || e.isLinear -> e
        e.isBezier -> Easing(Easing.BEZIER, 1f - e.x2, 1f - e.y2, 1f - e.x1, 1f - e.y1)
        else -> {
            val d = e.dir
            val f = e.family
            if (d == null || f == null) e else Easing.of(f, when (d) { EaseDir.In -> EaseDir.Out; EaseDir.Out -> EaseDir.In; EaseDir.InOut -> EaseDir.InOut })
        }
    }

    // ------------------------------------------------------------------------------------------ bezier handles

    /** Value-space position of the OUTGOING handle of key [i] (segment i -> i+1); null when that segment is not a bezier. */
    fun outHandle(k: List<ChanKey>, i: Int): Pair<Float, Float>? {
        val a = k.getOrNull(i) ?: return null
        val b = k.getOrNull(i + 1) ?: return null
        if (!a.ease.isBezier) return null
        return Pair(a.frame + a.ease.x1 * (b.frame - a.frame), a.value + a.ease.y1 * (b.value - a.value))
    }

    /** Value-space position of the INCOMING handle of key [i] (segment i-1 -> i). */
    fun inHandle(k: List<ChanKey>, i: Int): Pair<Float, Float>? {
        val a = k.getOrNull(i - 1) ?: return null
        val b = k.getOrNull(i) ?: return null
        if (!a.ease.isBezier) return null
        return Pair(a.frame + a.ease.x2 * (b.frame - a.frame), a.value + a.ease.y2 * (b.value - a.value))
    }

    /**
     * Drag a handle to value-space ([hf], [hv]). [outSide] = outgoing handle of key [i], else the incoming one.
     * [aligned] copies the TANGENT onto the other handle of the same key (a smooth, kink-free joint) while the other
     * handle keeps its own length.
     */
    fun dragHandle(k: List<ChanKey>, i: Int, outSide: Boolean, hf: Float, hv: Float, aligned: Boolean): List<ChanKey> {
        val out = k.toMutableList()
        val segI = if (outSide) i else i - 1
        val a = k.getOrNull(segI) ?: return k
        val b = k.getOrNull(segI + 1) ?: return k
        if (!a.ease.isBezier) return k
        val dx = (b.frame - a.frame).toFloat()
        val dv = b.value - a.value
        if (dx < 1f) return k
        val ey = { v: Float -> if (abs(dv) < 1e-6f) 0f else ((v - a.value) / dv).coerceIn(-1f, 2f) }
        var e = a.ease
        e = if (outSide) e.copy(x1 = ((hf - a.frame) / dx).coerceIn(HMIN, 1f - HMIN), y1 = ey(hv))
        else e.copy(x2 = ((hf - a.frame) / dx).coerceIn(HMIN, 1f - HMIN), y2 = ey(hv))
        out[segI] = a.copy(ease = e)
        if (aligned) {
            // tangent at key i (value per frame) from the handle just moved
            val key = k[i]
            val hx = hf - key.frame
            if (abs(hx) > 1e-4f) {
                val tan = (hv - key.value) / hx
                if (outSide) {
                    val p = k.getOrNull(i - 1)
                    if (p != null && p.ease.isBezier && !inRetracted(p.ease)) {
                        val pdx = (key.frame - p.frame).toFloat(); val pdv = key.value - p.value
                        val len = (1f - p.ease.x2) * pdx          // keep the length of the other handle
                        if (abs(pdv) > 1e-6f && len > 1e-4f) out[i - 1] = p.copy(ease = p.ease.copy(y2 = (1f - tan * len / pdv).coerceIn(-1f, 2f)))
                    }
                } else {
                    val nx = k.getOrNull(i + 1)
                    if (key.ease.isBezier && !outRetracted(key.ease) && nx != null) {
                        val ndx = (nx.frame - key.frame).toFloat(); val ndv = nx.value - key.value
                        val len = key.ease.x1 * ndx
                        if (abs(ndv) > 1e-6f && len > 1e-4f) out[i] = key.copy(ease = key.ease.copy(y1 = (tan * len / ndv).coerceIn(-1f, 2f)))
                    }
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------------------------------ path points (corner / smooth)

    /** Smallest handle length (fraction of the segment). A RETRACTED handle sits this far along the chord, i.e. it is "not pulled out". */
    const val HMIN = 0.02f

    fun outRetracted(e: Easing): Boolean = !e.isBezier || (e.x1 < 0.04f && abs(e.y1 - e.x1) < 0.04f)
    fun inRetracted(e: Easing): Boolean = !e.isBezier || (e.x2 > 0.96f && abs(e.y2 - e.x2) < 0.04f)

    /** A segment with both handles retracted is just a straight line. */
    private fun norm(e: Easing): Easing = if (e.isBezier && outRetracted(e) && inRetracted(e)) Easing.Linear else e

    /** Key [i] has no handle pulled out on either side (a polygon vertex). Hold segments count as handle-less. */
    fun isCorner(k: List<ChanKey>, i: Int): Boolean {
        val outOk = i >= k.size - 1 || k[i].ease.isHold || outRetracted(k[i].ease)
        val inOk = i <= 0 || k[i - 1].ease.isHold || inRetracted(k[i - 1].ease)
        return outOk && inOk
    }

    /** Retract both handles of key [i] (Illustrator: Alt+click a smooth point). Eased segments become beziers first so the other end keeps its shape. */
    fun setCorner(k: List<ChanKey>, i: Int, s: EaseSampler): List<ChanKey> {
        if (i !in k.indices) return k
        val out = k.toMutableList()
        if (i < k.size - 1 && !k[i].ease.isHold) out[i] = k[i].copy(ease = norm(asBezier(k[i].ease, s).copy(x1 = HMIN, y1 = HMIN)))
        if (i > 0 && !k[i - 1].ease.isHold) out[i - 1] = k[i - 1].copy(ease = norm(asBezier(k[i - 1].ease, s).copy(x2 = 1f - HMIN, y2 = 1f - HMIN)))
        return out
    }

    /** Pull both handles of key [i] out along the [mode] tangent (Alt+click a corner point). The far ends of the two segments keep their handles. */
    fun setSmooth(k: List<ChanKey>, i: Int, mode: GKSmooth, s: EaseSampler): List<ChanKey> {
        if (i !in k.indices || k.size < 2) return k
        val m = slopes(k, mode)[i]
        val out = k.toMutableList()
        if (i < k.size - 1 && !k[i].ease.isHold) {
            val a = k[i]; val b = k[i + 1]
            val dx = (b.frame - a.frame).toFloat(); val dv = b.value - a.value
            if (abs(dv) > 1e-6f && dx >= 1f) out[i] = a.copy(ease = asBezier(a.ease, s).copy(x1 = 1f / 3f, y1 = (m * dx / (3f * dv)).coerceIn(-1f, 2f)))
        }
        if (i > 0 && !k[i - 1].ease.isHold) {
            val p = k[i - 1]; val b = k[i]
            val dx = (b.frame - p.frame).toFloat(); val dv = b.value - p.value
            if (abs(dv) > 1e-6f && dx >= 1f) out[i - 1] = p.copy(ease = asBezier(p.ease, s).copy(x2 = 2f / 3f, y2 = (1f - m * dx / (3f * dv)).coerceIn(-1f, 2f)))
        }
        return out
    }

    /** Alt+click: corner <-> smooth. */
    fun toggleCorner(k: List<ChanKey>, i: Int, mode: GKSmooth, s: EaseSampler): List<ChanKey> =
        if (isCorner(k, i)) setSmooth(k, i, mode, s) else setCorner(k, i, s)

    // ------------------------------------------------------------------------------------------ editing

    fun sorted(k: List<ChanKey>): List<ChanKey> = k.sortedWith(ChanKeyOrder)

    /** Create / replace the key at [frame]; an existing key keeps its easing unless [ease] is given. */
    fun put(k: List<ChanKey>, chan: Int, frame: Int, value: Float, ease: Easing): List<ChanKey> {
        val out = k.toMutableList()
        val at = out.indexOfFirst { it.frame == frame }
        if (at >= 0) out[at] = out[at].copy(value = value)
        else { out.add(ChanKey(chan, frame, value, ease)); out.sortWith(ChanKeyOrder) }
        return out
    }

    /**
     * Insert a key at [frame] WITHOUT changing the curve: the segment is split and both halves get a bezier with the
     * tangents the old curve had there (exact for Linear / Hold / Bezier-with-linear-x, close for the rest).
     */
    fun insert(k: List<ChanKey>, chan: Int, frame: Int, st: Float, s: EaseSampler): List<ChanKey> {
        if (k.any { it.frame == frame }) return k
        if (k.isEmpty() || frame <= k.first().frame || frame >= k.last().frame) {
            val v = eval(k, frame.toFloat(), st, s)
            return put(k, chan, frame, v, k.firstOrNull { it.frame > frame }?.ease ?: k.lastOrNull()?.ease ?: Easing.Smooth)
        }
        val i = k.indexOfLast { it.frame < frame }
        val a = k[i]; val b = k[i + 1]
        val span = (b.frame - a.frame).toFloat()
        val t0 = (frame - a.frame) / span
        val e = a.ease
        val v = a.value + (b.value - a.value) * ease(e, t0, s)
        val mid = ChanKey(chan, frame, v, e)
        val out = k.toMutableList()
        if (e.isLinear || e.isHold) {
            out[i] = a.copy(ease = e)
        } else {
            val dv = b.value - a.value
            val sa = dv * deriv(e, 0f, s) / span
            val sm = dv * deriv(e, t0, s) / span
            val sb = dv * deriv(e, 1f, s) / span
            out[i] = a.copy(ease = toBezier(a, mid, sa, sm))
            out.add(i + 1, mid.copy(ease = toBezier(mid, b, sm, sb)))
            return out
        }
        out.add(i + 1, mid)
        return out
    }

    /** Largest shift toward 0 from [df] keeping every key of [sel] inside 0..[maxF] and off the unselected keys. */
    fun fitShift(k: List<ChanKey>, sel: Set<Int>, df: Int, maxF: Int): Int {
        val frames = k.filter { it.frame in sel }.map { it.frame }
        if (frames.isEmpty()) return 0
        val others = k.filter { it.frame !in sel }.map { it.frame }.toHashSet()
        var d = df
        fun free(x: Int) = frames.all { val f = it + x; f in 0..maxF && f !in others }
        while (d != 0 && !free(d)) d -= if (d > 0) 1 else -1
        return d
    }

    /** Move the keys of [sel] by [df] frames (use [fitShift] first) and [dv] in value. */
    fun move(k: List<ChanKey>, sel: Set<Int>, df: Int, dv: Float): List<ChanKey> =
        k.map { if (it.frame in sel) it.copy(frame = it.frame + df, value = it.value + dv) else it }.sortedWith(ChanKeyOrder)

    fun setValue(k: List<ChanKey>, sel: Set<Int>, f: (ChanKey) -> Float): List<ChanKey> =
        k.map { if (it.frame in sel) it.copy(value = f(it)) else it }

    fun delete(k: List<ChanKey>, sel: Set<Int>): List<ChanKey> = k.filter { it.frame !in sel }

    fun setEase(k: List<ChanKey>, sel: Set<Int>, e: Easing): List<ChanKey> =
        k.map { if (sel.isEmpty() || it.frame in sel) it.copy(ease = e) else it }

    /** Scale the time of [sel] about [pivot]. Keys that would collide are dropped (the earlier one wins). */
    fun scaleTime(k: List<ChanKey>, sel: Set<Int>, factor: Float, pivot: Int, maxF: Int): List<ChanKey> {
        val seen = HashSet<Int>()
        val moved = k.map { if (it.frame in sel) it.copy(frame = (pivot + (it.frame - pivot) * factor).roundToInt().coerceIn(0, maxF)) else it }
        // unselected keys keep priority over a selected one landing on them
        val fixed = moved.filter { it.frame !in sel || true }
        val out = ArrayList<ChanKey>()
        val unsel = k.filter { it.frame !in sel }.map { it.frame }.toHashSet()
        for (c in fixed.sortedWith(ChanKeyOrder)) {
            val isSel = c.frame in unsel
            if (seen.add(c.frame) || isSel) out.add(c)
        }
        return out.distinctBy { it.frame }.sortedWith(ChanKeyOrder)
    }

    /** Selected values mirrored about the middle of their own range. */
    fun flip(k: List<ChanKey>, sel: Set<Int>): List<ChanKey> {
        val v = k.filter { it.frame in sel }.map { it.value }
        if (v.isEmpty()) return k
        val mid = (v.min() + v.max()) / 2f
        return setValue(k, sel) { 2f * mid - it.value }
    }

    /** Scale the selected values about their own mean. */
    fun scaleValue(k: List<ChanKey>, sel: Set<Int>, factor: Float): List<ChanKey> {
        val v = k.filter { it.frame in sel }.map { it.value }
        if (v.isEmpty()) return k
        val mean = v.average().toFloat()
        return setValue(k, sel) { mean + (it.value - mean) * factor }
    }

    /**
     * Relax the VALUES of [sel] (all interior keys when empty) toward the average of their neighbours, [amt] 0..1 per pass.
     * Both ends of the lane stay. Takes the jitter out of keys that were drawn / baked by hand.
     */
    fun relax(k: List<ChanKey>, sel: Set<Int>, amt: Float, passes: Int = 1): List<ChanKey> {
        var cur = k
        repeat(passes) {
            val src = cur
            cur = src.mapIndexed { i, c ->
                if (i == 0 || i == src.size - 1 || (sel.isNotEmpty() && c.frame !in sel)) c
                else {
                    val a = src[i - 1]; val b = src[i + 1]
                    val w = (c.frame - a.frame).toFloat() / (b.frame - a.frame)
                    val avg = a.value + (b.value - a.value) * w     // where the neighbours say this key should be
                    c.copy(value = c.value + (avg - c.value) * amt)
                }
            }
        }
        return cur
    }

    /** Replace the lane between [f0] and [f1] by one Linear key every [step] frames (+ the two ends) sampled from the curve. */
    fun bake(k: List<ChanKey>, chan: Int, f0: Int, f1: Int, step: Int, st: Float, s: EaseSampler): List<ChanKey> {
        if (f1 <= f0 || step < 1) return k
        val keep = k.filter { it.frame < f0 || it.frame > f1 }.toMutableList()
        var f = f0
        while (true) {
            keep.add(ChanKey(chan, f, eval(k, f.toFloat(), st, s), Easing.Linear))
            if (f >= f1) break
            f = min(f + step, f1)
        }
        // the key before the range keeps its easing, it now just arrives at a baked key
        keep.sortWith(ChanKeyOrder)
        return keep
    }

    /**
     * Drop keys whose removal changes the curve by less than [eps] at any whole frame (vs the ORIGINAL curve). Only inside
     * [f0]..[f1]; both ends of the lane and of the range stay. Cheapest key first.
     */
    fun reduce(k: List<ChanKey>, eps: Float, f0: Int, f1: Int, s: EaseSampler): List<ChanKey> {
        val ref = k
        var cur = k
        while (cur.size > 2) {
            var best = -1
            var bestErr = Float.MAX_VALUE
            for (i in 1 until cur.size - 1) {
                val c = cur[i]
                if (c.frame <= f0 || c.frame >= f1) continue
                val a = cur[i - 1]; val b = cur[i + 1]
                var err = 0f
                for (f in a.frame..b.frame) err = max(err, abs(eval(ref, f.toFloat(), 0f, s) - seg(a, b, f.toFloat(), s)))
                if (err < bestErr) { bestErr = err; best = i }
            }
            if (best < 0 || bestErr > eps) break
            cur = cur.filterIndexed { i, _ -> i != best }
        }
        return cur
    }

    // ------------------------------------------------------------------------------------------ clipboard / blend

    /** Keys of [sel] with frames relative to the first selected one; null when nothing is selected. */
    fun copyLane(k: List<ChanKey>, sel: Set<Int>): List<ChanKey>? {
        val c = k.filter { it.frame in sel }
        if (c.isEmpty()) return null
        val f0 = c.first().frame
        return c.map { it.copy(frame = it.frame - f0) }
    }

    /** Same block played backwards: frames mirrored, every segment's easing mirrored with it. */
    fun reverse(block: List<ChanKey>): List<ChanKey> {
        if (block.size < 2) return block
        val len = block.last().frame
        val n = block.size
        return List(n) { j ->
            // new key j = old key n-1-j; the segment leaving it (j -> j+1) is old segment (n-2-j) walked backwards
            val o = block[n - 1 - j]
            val e = if (j < n - 1) reverseEase(block[n - 2 - j].ease) else o.ease
            o.copy(frame = len - o.frame, ease = e)
        }
    }

    /** Frames inserted so no gap in [frames] is wider than [gap]. */
    private fun fill(frames: java.util.TreeSet<Int>, gap: Int) {
        var prev = frames.first()
        for (f in frames.toList().drop(1)) {
            var p = prev
            while (f - p > gap) { p += gap; frames.add(p) }
            prev = f
        }
    }

    /**
     * Paste [clip] (frames relative) into [dst] at frame [at].
     *  - [GKBlend.Replace] with [fade] = 0: the range is overwritten by the clip's keys.
     *  - Replace with [fade] > 0, Mix, Add: the range is BAKED (a key at every clip / lane key + at least every 3 frames) from the
     *    blend of both curves, weight = [mix] x ramp (ramps up over [fade] frames at the start, down at the end), then smoothed.
     * Frames past [maxF] are cut. [st] = static value of an empty lane.
     */
    fun paste(
        dst: List<ChanKey>, chan: Int, clip: List<ChanKey>, at: Int, mode: GKBlend, mix: Float, fade: Int,
        maxF: Int, st: Float, s: EaseSampler,
    ): List<ChanKey> {
        if (clip.isEmpty() || at > maxF) return dst
        val len = clip.last().frame
        val f0 = at
        val f1 = min(at + len, maxF)
        val plain = mode == GKBlend.Replace && fade <= 0
        if (plain || dst.isEmpty() && mode == GKBlend.Replace) {
            val keep = dst.filter { it.frame < f0 || it.frame > f1 }
            val add = clip.map { it.copy(chan = chan, frame = it.frame + at) }.filter { it.frame <= maxF }
            return (keep + add).sortedWith(ChanKeyOrder)
        }
        val frames = java.util.TreeSet<Int>()
        frames.add(f0); frames.add(f1)
        for (c in clip) if (c.frame + at <= maxF) frames.add(c.frame + at)
        for (c in dst) if (c.frame in f0..f1) frames.add(c.frame)
        fill(frames, 3)
        val cv0 = clip.first().value
        val w0 = if (mode == GKBlend.Replace) 1f else mix.coerceIn(0f, 1f)
        val baked = frames.map { f ->
            val base = eval(dst, f.toFloat(), st, s)
            val cv = eval(clip, (f - at).toFloat(), cv0, s)
            val ramp = if (fade <= 0) 1f else min(1f, min((f - f0).toFloat() / fade, (f1 - f).toFloat() / fade)).coerceIn(0f, 1f)
            val w = w0 * ramp
            val v = if (mode == GKBlend.Add) base + (cv - cv0) * w else base + (cv - base) * w
            ChanKey(chan, f, v, Easing.Linear)
        }
        val keep = dst.filter { it.frame < f0 || it.frame > f1 }
        val merged = (keep + baked).sortedWith(ChanKeyOrder)
        return smooth(merged, GKSmooth.Flow, 1f, frames)
    }

    /** Selected block repeated [times] more times right after itself (Replace-paste, so a loop's overlapping key is shared). */
    fun repeat(k: List<ChanKey>, chan: Int, sel: Set<Int>, times: Int, pingPong: Boolean, maxF: Int, st: Float, s: EaseSampler): List<ChanKey> {
        val block = copyLane(k, sel) ?: return k
        val f0 = k.first { it.frame in sel }.frame
        val len = block.last().frame
        if (len <= 0) return k
        var cur = k
        var b = block
        var at = f0
        repeat(times) {
            at += len
            if (pingPong) b = reverse(b)
            cur = paste(cur, chan, b, at, GKBlend.Replace, 1f, 0, maxF, st, s)
        }
        return cur
    }
}
