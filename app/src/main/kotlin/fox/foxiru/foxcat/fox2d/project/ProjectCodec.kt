package fox.foxiru.foxcat.fox2d.project

import android.util.Base64
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import fox.foxiru.foxcat.fox2d.main_canvas.Bone
import fox.foxiru.foxcat.fox2d.main_canvas.BoneAttach
import fox.foxiru.foxcat.fox2d.main_canvas.Cel
import fox.foxiru.foxcat.fox2d.main_canvas.ChanKey
import fox.foxiru.foxcat.fox2d.main_canvas.CtlPoint
import fox.foxiru.foxcat.fox2d.main_canvas.DeformCurve
import fox.foxiru.foxcat.fox2d.main_canvas.DrawTrack
import fox.foxiru.foxcat.fox2d.main_canvas.Easing
import fox.foxiru.foxcat.fox2d.main_canvas.ImagePlace
import fox.foxiru.foxcat.fox2d.main_canvas.InkStroke
import fox.foxiru.foxcat.fox2d.main_canvas.Layer
import fox.foxiru.foxcat.fox2d.main_canvas.LayerXf
import fox.foxiru.foxcat.fox2d.main_canvas.MeshRect
import fox.foxiru.foxcat.fox2d.main_canvas.Pin
import fox.foxiru.foxcat.fox2d.main_canvas.RigLimits
import fox.foxiru.foxcat.fox2d.main_canvas.Rig
import fox.foxiru.foxcat.fox2d.main_canvas.TrackGroup
import fox.foxiru.foxcat.fox2d.main_canvas.WeightPaint
import fox.foxiru.foxcat.fox2d.timeline.AudioClip
import fox.foxiru.foxcat.fox2d.timeline.AudioKey
import fox.foxiru.foxcat.fox2d.timeline.AudioTrack
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * project.json  = structure (rows, cels, rig, keys, audio, prefs)   - small, human-readable
 * strokes.bin   = every ink stroke, gzip'd binary                   - the bulk of a project
 * meta.json     = gallery entry
 * Floats go through Double (exact round trip); non-finite floats are stored as 0 (JSON cannot hold NaN).
 */
internal object ProjectCodec {
    const val VERSION = 1
    private const val STROKE_MAGIC = 0x46325331 // "F2S1"

    // ------------------------------------------------------------------ json helpers
    private fun fin(v: Float) = (if (v.isFinite()) v else 0f).toDouble()
    private fun floats(vararg v: Float) = JSONArray().also { a -> v.forEach { a.put(fin(it)) } }
    private fun JSONArray.f(i: Int) = getDouble(i).toFloat()
    private fun JSONObject.f(k: String, d: Float) = optDouble(k, d.toDouble()).toFloat()
    private fun JSONObject.pf(k: String, v: Float): JSONObject = put(k, fin(v))
    private inline fun <T> JSONArray?.mapObj(f: (JSONObject) -> T): List<T> =
        if (this == null) emptyList() else List(length()) { f(getJSONObject(it)) }

    private fun blob(a: FloatArray): String {
        val b = ByteBuffer.allocate(a.size * 4)
        b.asFloatBuffer().put(a)
        return Base64.encodeToString(b.array(), Base64.NO_WRAP)
    }

    private fun unblob(s: String): FloatArray {
        val bytes = Base64.decode(s, Base64.NO_WRAP)
        return FloatArray(bytes.size / 4).also { ByteBuffer.wrap(bytes).asFloatBuffer().get(it) }
    }

    // ------------------------------------------------------------------ meta
    fun encodeMeta(m: ProjectMeta): String = JSONObject()
        .put("id", m.id).put("name", m.name).put("created", m.createdAt).put("modified", m.modifiedAt)
        .put("fps", m.fps).put("frames", m.frames).toString()

    fun decodeMeta(s: String): ProjectMeta = JSONObject(s).let {
        ProjectMeta(it.getString("id"), it.getString("name"), it.getLong("created"), it.getLong("modified"), it.optInt("fps", 12), it.optInt("frames", 12))
    }

    // ------------------------------------------------------------------ keys / easing
    private fun easingJ(e: Easing) = floats(e.id.toFloat(), e.x1, e.y1, e.x2, e.y2)
    private fun easingOf(a: JSONArray) = Easing(a.getInt(0), a.f(1), a.f(2), a.f(3), a.f(4))

    private fun keysJ(keys: List<ChanKey>) = JSONArray().also { out ->
        for (k in keys) out.put(floats(k.chan.toFloat(), k.frame.toFloat(), k.value, k.ease.id.toFloat(), k.ease.x1, k.ease.y1, k.ease.x2, k.ease.y2))
    }

    private fun keysOf(a: JSONArray?): List<ChanKey> = if (a == null) emptyList() else List(a.length()) {
        val k = a.getJSONArray(it)
        ChanKey(k.getInt(0), k.getInt(1), k.f(2), Easing(k.getInt(3), k.f(4), k.f(5), k.f(6), k.f(7)))
    }

    // ------------------------------------------------------------------ rig
    private fun rigJ(r: Rig): JSONObject = JSONObject()
        .put("grid", r.grid)
        .put("rect", floats(r.rect.l, r.rect.t, r.rect.r, r.rect.b))
        .put("bones", JSONArray().also { a ->
            for (b in r.bones) a.put(
                JSONObject().put("name", b.name).put("parent", b.parent).put("ik", b.ikChain)
                    .put("v", floats(b.hx, b.hy, b.angle, b.len, b.radius, b.falloff, b.strength, b.ikBend))
                    .put("base", floats(*b.base.toFloatArray()))
            )
        })
        .put("curves", JSONArray().also { a ->
            for (c in r.curves) a.put(
                JSONObject().put("name", c.name).put("v", floats(c.reach, c.softness, c.mix))
                    .put("pts", JSONArray().also { p -> for (t in c.pts) p.put(floats(t.rx, t.ry, t.bone.toFloat(), t.dx, t.dy, t.thick)) })
            )
        })
        .put("keys", keysJ(r.keys))
        .also { o ->
            if (r.pins.isNotEmpty()) {
                o.put("warp", r.warpFalloff)
                o.put("pins", JSONArray().also { a -> for (p in r.pins) a.put(floats(p.rx, p.ry, p.dx, p.dy)) })
            }
            r.paint?.let { o.put("paint", JSONObject().put("verts", it.verts).put("bones", it.bones).put("version", it.version).put("data", blob(it.data))) }
        }

    private fun rigOf(o: JSONObject?): Rig {
        if (o == null) return Rig()
        val rc = o.optJSONArray("rect")
        return Rig(
            bones = o.optJSONArray("bones").mapObj { b ->
                val v = b.getJSONArray("v"); val base = b.getJSONArray("base")
                Bone(
                    name = b.getString("name"), parent = b.getInt("parent"),
                    hx = v.f(0), hy = v.f(1), angle = v.f(2), len = v.f(3), radius = v.f(4), falloff = v.f(5), strength = v.f(6),
                    base = List(base.length()) { base.f(it) }, ikChain = b.optInt("ik", 0), ikBend = v.f(7),
                )
            },
            curves = o.optJSONArray("curves").mapObj { c ->
                val v = c.getJSONArray("v"); val p = c.getJSONArray("pts")
                DeformCurve(
                    name = c.getString("name"), reach = v.f(0), softness = v.f(1), mix = v.f(2),
                    pts = List(p.length()) { i -> p.getJSONArray(i).let { t -> CtlPoint(t.f(0), t.f(1), t.getInt(2), t.f(3), t.f(4), t.f(5)) } },
                )
            },
            grid = o.optInt("grid", 48),
            rect = if (rc != null) MeshRect(rc.f(0), rc.f(1), rc.f(2), rc.f(3)) else MeshRect(),
            keys = keysOf(o.optJSONArray("keys")),
            paint = o.optJSONObject("paint")?.let { WeightPaint(it.getInt("verts"), it.getInt("bones"), unblob(it.getString("data")), it.optInt("version", 0)) },
            pins = o.optJSONArray("pins")?.let { a -> List(minOf(a.length(), RigLimits.MAX_PINS)) { i -> a.getJSONArray(i).let { t -> Pin(t.f(0), t.f(1), t.f(2), t.f(3)) } } } ?: emptyList(),
            warpFalloff = o.optInt("warp", 2).coerceIn(1, 3),
        )
    }

    // ------------------------------------------------------------------ tracks
    private fun trackJ(t: DrawTrack) = JSONObject()
        .put("id", t.id).put("name", t.name).put("locked", t.locked).put("offset", t.offset).put("group", t.group)
        .put("blend", t.blend).put("opacity", t.opacity.toDouble()).put("clip", t.clip)
        .put("xf", floats(t.xf.tx, t.xf.ty, t.xf.sx, t.xf.sy, t.xf.rot, t.xf.px, t.xf.py))
        .put("keys", keysJ(t.keys))
        .put("rig", rigJ(t.rig))
        .put("layers", JSONArray().also { a -> t.layers.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("visible", it.visible).put("blend", it.blend).put("opacity", it.opacity.toDouble()).put("clip", it.clip)) } })
        .also { o -> t.attach?.let { o.put("attach", JSONObject().put("track", it.track).put("bone", it.bone)) } }

    private fun trackOf(o: JSONObject): DrawTrack {
        val x = o.getJSONArray("xf")
        return DrawTrack(
            id = o.getInt("id"), name = o.getString("name"), locked = o.optBoolean("locked"), offset = o.optInt("offset"),
            xf = LayerXf(x.f(0), x.f(1), x.f(2), x.f(3), x.f(4), x.f(5), x.f(6)),
            keys = keysOf(o.optJSONArray("keys")),
            rig = rigOf(o.optJSONObject("rig")),
            attach = o.optJSONObject("attach")?.let { BoneAttach(it.getInt("track"), it.getInt("bone")) },
            layers = o.optJSONArray("layers").mapObj { Layer(it.getInt("id"), it.getString("name"), it.optBoolean("visible", true), it.optInt("blend", 0), it.optDouble("opacity", 1.0).toFloat(), it.optBoolean("clip", false)) },
            group = o.optInt("group", -1),
            blend = o.optInt("blend", 0),
            opacity = o.optDouble("opacity", 1.0).toFloat(),
            clip = o.optBoolean("clip", false),
        )
    }

    // ------------------------------------------------------------------ prefs
    private fun prefsJ(p: EditorPrefs) = JSONObject()
        .put("fps", p.fps).pf("hue", p.hue).pf("sat", p.sat).pf("bri", p.bri).pf("brushSize", p.brushSize)
        .put("brush", p.brush).put("tool", p.tool).put("railOpen", p.railOpen)
        .put("railDock", p.railDock).pf("railAlong", p.railAlong)
        .put("tbDock", p.tbDock).pf("tbAlong", p.tbAlong).put("tbCollapsed", p.tbCollapsed)
        .put("transformBoxOnly", p.transformBoxOnly).put("keyRulerShown", p.keyRulerShown)
        .put("defaultEase", easingJ(p.defaultEase))
        .pf("posX", p.posX).pf("posY", p.posY).pf("rotation", p.rotation).pf("scale", p.scale).pf("opacity", p.opacity)
        .put("activeTrack", p.activeTrack).put("activeLayer", p.activeLayer).put("frame", p.frame)
        .pf("pxPerSec", p.pxPerSec).pf("scrollPx", p.scrollPx)
        .put("rigMode", p.rigMode).put("boneTool", p.boneTool).put("poseTool", p.poseTool)
        .put("rigShowAll", p.rigShowAll).put("rigAutoKey", p.rigAutoKey)

    private fun prefsOf(o: JSONObject?): EditorPrefs {
        val d = EditorPrefs()
        if (o == null) return d
        return EditorPrefs(
            fps = o.optInt("fps", d.fps), hue = o.f("hue", d.hue), sat = o.f("sat", d.sat), bri = o.f("bri", d.bri),
            brushSize = o.f("brushSize", d.brushSize),
            brush = o.optString("brush", d.brush), tool = o.optString("tool", d.tool), railOpen = o.optBoolean("railOpen", d.railOpen),
            railDock = o.optString("railDock", d.railDock), railAlong = o.f("railAlong", d.railAlong),
            tbDock = o.optString("tbDock", d.tbDock), tbAlong = o.f("tbAlong", d.tbAlong), tbCollapsed = o.optBoolean("tbCollapsed", d.tbCollapsed),
            transformBoxOnly = o.optBoolean("transformBoxOnly", d.transformBoxOnly), keyRulerShown = o.optBoolean("keyRulerShown", d.keyRulerShown),
            defaultEase = o.optJSONArray("defaultEase")?.let(::easingOf) ?: d.defaultEase,
            posX = o.f("posX", d.posX), posY = o.f("posY", d.posY), rotation = o.f("rotation", d.rotation),
            scale = o.f("scale", d.scale), opacity = o.f("opacity", d.opacity),
            activeTrack = o.optInt("activeTrack", d.activeTrack), activeLayer = o.optInt("activeLayer", d.activeLayer), frame = o.optInt("frame", d.frame),
            pxPerSec = o.f("pxPerSec", d.pxPerSec), scrollPx = o.f("scrollPx", d.scrollPx),
            rigMode = o.optString("rigMode", d.rigMode), boneTool = o.optString("boneTool", d.boneTool), poseTool = o.optString("poseTool", d.poseTool),
            rigShowAll = o.optBoolean("rigShowAll", d.rigShowAll), rigAutoKey = o.optBoolean("rigAutoKey", d.rigAutoKey),
        )
    }

    // ------------------------------------------------------------------ whole project
    fun encode(d: ProjectData): String = JSONObject()
        .put("version", VERSION)
        .put("prefs", prefsJ(d.prefs))
        .put("cels", JSONArray().also { a -> d.cels.forEach { a.put(JSONObject().put("id", it.id).put("len", it.len).put("track", it.track).put("gen", it.gen)) } })
        .put("tracks", JSONArray().also { a -> d.drawTracks.forEach { a.put(trackJ(it)) } })
        .put("audioTracks", JSONArray().also { a ->
            d.audioTracks.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("muted", it.muted).put("locked", it.locked).put("group", it.group)) }
        })
        .put("clips", JSONArray().also { a ->
            for (c in d.clips) a.put(
                JSONObject().put("id", c.id).put("track", c.track).put("name", c.name).put("src", c.src)
                    .put("srcDurMs", c.srcDurMs).put("startMs", c.startMs).put("inMs", c.inMs).put("lenMs", c.lenMs)
                    .pf("gain", c.gain).put("gen", c.gen)
                    .put("gainKeys", JSONArray().also { k -> c.gainKeys.forEach { g -> k.put(JSONArray().put(g.ms).put(fin(g.gain))) } })
            )
        })
        .put("groups", JSONArray().also { a ->
            d.groups.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("endFrame", it.endFrame).put("fill", it.fill)) }
        })
        .toString()

    fun decode(text: String, meta: ProjectMeta, strokes: List<InkStroke>): ProjectData {
        val j = JSONObject(text)
        return ProjectData(
            meta = meta,
            prefs = prefsOf(j.optJSONObject("prefs")),
            cels = j.optJSONArray("cels").mapObj { Cel(it.getInt("id"), it.getInt("len"), it.optInt("track"), it.optInt("gen", -1)) },
            drawTracks = j.optJSONArray("tracks").mapObj(::trackOf),
            strokes = strokes,
            audioTracks = j.optJSONArray("audioTracks").mapObj { AudioTrack(it.getInt("id"), it.getString("name"), it.optBoolean("muted"), it.optBoolean("locked"), it.optInt("group", -1)) },
            clips = j.optJSONArray("clips").mapObj { c ->
                val gk = c.optJSONArray("gainKeys")
                AudioClip(
                    id = c.getInt("id"), track = c.getInt("track"), handle = -1, name = c.getString("name"),
                    srcDurMs = c.getLong("srcDurMs"), startMs = c.getLong("startMs"), inMs = c.getLong("inMs"), lenMs = c.getLong("lenMs"),
                    gain = c.f("gain", 1f),
                    gainKeys = if (gk == null) emptyList() else List(gk.length()) { i -> gk.getJSONArray(i).let { AudioKey(it.getLong(0), it.f(1)) } },
                    src = c.optString("src", ""),
                    gen = c.optBoolean("gen", false),
                )
            },
            groups = j.optJSONArray("groups").mapObj { TrackGroup(it.getInt("id"), it.optString("name", "Group"), it.optInt("endFrame", -1), it.optInt("fill", 0)) },
        )
    }

    // ------------------------------------------------------------------ strokes (gzip binary)
    /** Does NOT close [out]; the caller syncs / closes it. */

    fun writeStrokes(out: OutputStream, strokes: List<InkStroke>) {
        val gz = GZIPOutputStream(out)
        val o = DataOutputStream(BufferedOutputStream(gz, 64 * 1024))
        o.writeInt(STROKE_MAGIC); o.writeInt(2); o.writeInt(strokes.size)
        for (s in strokes) {
            o.writeInt(s.cel); o.writeInt(s.layer); o.writeInt(s.color.toArgb()); o.writeFloat(s.size)
            o.writeBoolean(s.erase)
            val im = s.image
            o.writeBoolean(im != null)
            if (im != null) {
                o.writeUTF(im.file)
                o.writeFloat(im.cx); o.writeFloat(im.cy); o.writeFloat(im.w); o.writeFloat(im.h); o.writeFloat(im.rot)
            } else {
                o.writeInt(s.pts.size)
                for (p in s.pts) { o.writeFloat(p.x); o.writeFloat(p.y) }
            }
        }
        o.flush()
        gz.finish()
    }

    fun readStrokes(input: InputStream): List<InkStroke> {
        val i = DataInputStream(GZIPInputStream(input, 64 * 1024).buffered(64 * 1024))
        require(i.readInt() == STROKE_MAGIC) { "Not a stroke file" }
        val ver = i.readInt()
        val n = i.readInt()
        require(n in 0..50_000_000) { "Corrupt stroke count" }
        val out = ArrayList<InkStroke>(n)
        repeat(n) {
            val cel = i.readInt(); val layer = i.readInt(); val argb = i.readInt(); val size = i.readFloat()
            val erase = i.readBoolean()
            if (ver >= 2 && i.readBoolean()) {
                val file = i.readUTF()
                out.add(InkStroke(cel, layer, Color(argb), size, emptyList(), erase,
                    ImagePlace(file, i.readFloat(), i.readFloat(), i.readFloat(), i.readFloat(), i.readFloat())))
            } else {
                val pc = i.readInt()
                require(pc in 0..10_000_000) { "Corrupt point count" }
                val pts = ArrayList<Offset>(pc)
                repeat(pc) { pts.add(Offset(i.readFloat(), i.readFloat())) }
                out.add(InkStroke(cel, layer, Color(argb), size, pts, erase))
            }
        }
        return out
    }
}
