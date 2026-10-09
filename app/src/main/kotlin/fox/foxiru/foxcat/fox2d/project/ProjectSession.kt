package fox.foxiru.foxcat.fox2d.project

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.net.Uri
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.toArgb

import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import fox.foxiru.foxcat.fox2d.main_canvas.ImageStore
import fox.foxiru.foxcat.fox2d.main_canvas.ImageIo

import fox.foxiru.foxcat.fox2d.jnicallers.AudioHandlerNative
import fox.foxiru.foxcat.fox2d.main_canvas.BoneTool
import fox.foxiru.foxcat.fox2d.main_canvas.BrushKind
import fox.foxiru.foxcat.fox2d.main_canvas.EditorState
import fox.foxiru.foxcat.fox2d.main_canvas.LayerXf
import fox.foxiru.foxcat.fox2d.main_canvas.PoseTool
import fox.foxiru.foxcat.fox2d.main_canvas.RigMode
import fox.foxiru.foxcat.fox2d.main_canvas.Tool
import fox.foxiru.foxcat.fox2d.main_canvas.ToolbarDock
import java.io.File

// ============================================================================================ thumbnail

/** Frame-0-ish preview for the gallery: first drawing of every row, layer by layer, static transform only. Paper is 512 px square. */
internal object ThumbRenderer {
    const val SIDE = 320

    fun render(d: ProjectData, imgDir: File): Bitmap {
        val out = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(android.graphics.Color.WHITE)
        val k = SIDE / 512f
        val firstCel = HashMap<Int, Int>().also { m -> d.cels.forEach { m.putIfAbsent(it.track, it.id) } }
        val byKey = d.strokes.groupBy { it.cel to it.layer }
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        val clear = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        val images = HashMap<String, Bitmap?>()
        val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        
        for (t in d.drawTracks) {
            val cel = firstCel[t.id] ?: continue
            val xf = if (t.keys.isEmpty()) t.xf else LayerXf() // animated rows: keys are evaluated natively, skip them here
            for (l in t.layers) {
                if (!l.visible) continue
                val strokes = byKey[cel to l.id] ?: continue
                val bmp = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
                val lc = Canvas(bmp)
                for (s in strokes) {
                    val im = s.image
                    if (im != null) {
                        val bm = images.getOrPut(im.file) { BitmapFactory.decodeFile(File(imgDir, im.file).path) } ?: continue
                        val c = xf.apply(Offset(im.cx, im.cy))
                        val pw = im.w * abs(xf.sx) * SIDE; val ph = im.h * abs(xf.sy) * SIDE
                        val m = android.graphics.Matrix().apply {
                            postScale(pw / bm.width, ph / bm.height); postTranslate(-pw / 2f, -ph / 2f)
                            postRotate(im.rot + xf.rot); postTranslate(c.x * SIDE, c.y * SIDE)
                        }
                        lc.drawBitmap(bm, m, bmpPaint)
                        continue
                    }
                    if (s.pts.isEmpty()) continue
                    ink.color = s.color.toArgb()
                    ink.xfermode = if (s.erase) clear else null
                    val pts = s.pts.map { xf.apply(it) }
                    if (pts.size == 1) {
                        ink.style = Paint.Style.FILL
                        lc.drawCircle(pts[0].x * SIDE, pts[0].y * SIDE, s.size * k / 2f, ink)
                    } else {
                        ink.style = Paint.Style.STROKE
                        ink.strokeWidth = s.size * k
                        val p = Path().apply {
                            moveTo(pts[0].x * SIDE, pts[0].y * SIDE)
                            for (i in 1 until pts.size) lineTo(pts[i].x * SIDE, pts[i].y * SIDE)
                        }
                        lc.drawPath(p, ink)
                    }
                }
                canvas.drawBitmap(bmp, 0f, 0f, null)
                bmp.recycle()
            }
        }
        return out
    }
}

// ============================================================================================ state <-> data

private inline fun <reified E : Enum<E>> parse(name: String, default: E): E =
    runCatching { enumValueOf<E>(name) }.getOrDefault(default)

fun EditorState.capturePrefs() = EditorPrefs(
    fps = fps, hue = hue, sat = sat, bri = bri, brushSize = brushSize,
    brush = brush.name, tool = tool.name, railOpen = railOpen,
    railDock = railDock.name, railAlong = railAlong,
    tbDock = tbDock.name, tbAlong = tbAlong, tbCollapsed = tbCollapsed,
    transformBoxOnly = transformBoxOnly, keyRulerShown = keyRulerShown, defaultEase = defaultEase,
    posX = posX, posY = posY, rotation = rotation, scale = scale, opacity = opacity,
    activeTrack = activeTrack, activeLayer = activeLayer, frame = frame,
    pxPerSec = timeline.pxPerSec, scrollPx = timeline.scrollPx,
    rigMode = rigUi.mode.name, boneTool = rigUi.boneTool.name, poseTool = rigUi.poseTool.name,
    rigShowAll = rigUi.showAll, rigAutoKey = rigUi.autoKey,
)

private fun EditorState.applyPrefs(p: EditorPrefs) {
    fps = p.fps; hue = p.hue; sat = p.sat; bri = p.bri; brushSize = p.brushSize
    brush = parse(p.brush, BrushKind.Pen); tool = parse(p.tool, Tool.Brush); railOpen = p.railOpen
    railDock = parse(p.railDock, ToolbarDock.Right); railAlong = p.railAlong
    tbDock = parse(p.tbDock, ToolbarDock.Bottom); tbAlong = p.tbAlong; tbCollapsed = p.tbCollapsed
    transformBoxOnly = p.transformBoxOnly; keyRulerShown = p.keyRulerShown; defaultEase = p.defaultEase
    posX = p.posX; posY = p.posY; rotation = p.rotation; scale = p.scale; opacity = p.opacity
    timeline.pxPerSec = p.pxPerSec; timeline.scrollPx = p.scrollPx
    rigUi.mode = parse(p.rigMode, RigMode.Build); rigUi.boneTool = parse(p.boneTool, BoneTool.Draw)
    rigUi.poseTool = parse(p.poseTool, PoseTool.Auto)
    rigUi.showAll = p.rigShowAll; rigUi.autoKey = p.rigAutoKey
}

/** Call on the main thread (reads snapshot state). The result is immutable and safe to encode on any thread. */
fun EditorState.toProjectData(meta: ProjectMeta) = ProjectData(
    meta = meta.copy(modifiedAt = System.currentTimeMillis(), fps = fps, frames = frameCount),
    prefs = capturePrefs(),
    cels = cels.toList(),
    drawTracks = drawTracks.toList(),
    strokes = strokes.toList(),
    audioTracks = timeline.tracks.toList(),
    clips = timeline.clips.toList(),
    groups = groups.toList(),
)

/**
 * Replace the whole editor with [data] (open / create). Audio is re-decoded from the project's own copies; a clip
 * whose file is missing or undecodable is dropped. Undo / redo history starts empty.
 * @return how many audio clips could not be restored.
 */
suspend fun EditorState.applyProject(ctx: Context, repo: ProjectRepository, data: ProjectData): Int {
    playing = false
    AudioHandlerNative.pause()
    clipboard = null
    timeline.releaseAll()

    val id = data.meta.id
    val handles = HashMap<String, Int>()
    for (src in data.clips.map { it.src }.filter { it.isNotEmpty() }.distinct()) {
        val f = File(repo.audioDir(id), src)
        if (!f.exists()) continue
        val a = AudioHandlerNative.import(ctx, Uri.fromFile(f)) ?: continue
        timeline.adopt(a.handle)
        handles[src] = a.handle
    }
    val clips = data.clips.mapNotNull { c -> handles[c.src]?.let { c.copy(handle = it) } }
    ImageStore.releaseAll()
    placing = null
    val imgDir = repo.imageDir(id)
    withContext(Dispatchers.Default) {
        for (f in data.strokes.mapNotNull { it.image?.file }.distinct()) {
            ImageIo.decodeFile(File(imgDir, f))?.let { ImageStore.register(f, it) }
        }
    }
    Snapshot.withMutableSnapshot {
        timeline.tracks.clear(); timeline.tracks.addAll(data.audioTracks)
        timeline.clips.clear(); timeline.clips.addAll(clips)
        timeline.selectedClip = -1
        cels.clear(); cels.addAll(data.cels)
        drawTracks.clear(); drawTracks.addAll(data.drawTracks)
        groups.clear(); groups.addAll(data.groups)
        collapsedGroups.clear(); selectedGroup = -1
        strokes.clear(); strokes.addAll(data.strokes)
        redoStack.clear(); undoOps.clear(); redoOps.clear()
        selectedKey = -1; kfOpen = null
        applyPrefs(data.prefs)
        activeTrack = if (drawTracks.any { it.id == data.prefs.activeTrack }) data.prefs.activeTrack else drawTracks.firstOrNull()?.id ?: 0
        activeLayer = data.prefs.activeLayer // falls back to the row's first layer if it no longer exists
        projectId = id
    }
    timeline.pushToNative()
    seek(data.prefs.frame)
    rebuildNative()
    return data.clips.size - clips.size
}

/** Used by the autosave watcher: changes when saved content or a persisted setting changes (not playhead / scroll). */
fun EditorState.saveKey() = snapshot() to capturePrefs().stable()
