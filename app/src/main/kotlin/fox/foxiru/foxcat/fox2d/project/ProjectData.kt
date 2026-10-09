package fox.foxiru.foxcat.fox2d.project

import fox.foxiru.foxcat.fox2d.main_canvas.BrushKind
import fox.foxiru.foxcat.fox2d.main_canvas.Cel
import fox.foxiru.foxcat.fox2d.main_canvas.DrawTrack
import fox.foxiru.foxcat.fox2d.main_canvas.Easing
import fox.foxiru.foxcat.fox2d.main_canvas.InkStroke
import fox.foxiru.foxcat.fox2d.main_canvas.Layer
import fox.foxiru.foxcat.fox2d.main_canvas.Tool
import fox.foxiru.foxcat.fox2d.main_canvas.ToolbarDock
import fox.foxiru.foxcat.fox2d.main_canvas.TrackGroup
import fox.foxiru.foxcat.fox2d.timeline.AudioClip
import fox.foxiru.foxcat.fox2d.timeline.AudioTrack

/** What the gallery lists. Lives in its own small meta.json so listing never parses a whole project. */
data class ProjectMeta(
    val id: String,
    val name: String,
    val createdAt: Long,
    val modifiedAt: Long,
    val fps: Int = 12,
    val frames: Int = 12,
)

/**
 * Every editor setting that is not drawing data. Enums are stored by name so a renamed / removed enum
 * constant falls back to its default instead of failing the load.
 */
data class EditorPrefs(
    val fps: Int = 12,
    val hue: Float = 14f, val sat: Float = 0.75f, val bri: Float = 1f,
    val brushSize: Float = 10f,
    val brush: String = BrushKind.Pen.name,
    val tool: String = Tool.Brush.name,
    val railOpen: Boolean = true,
    val railDock: String = ToolbarDock.Right.name, val railAlong: Float = 0.5f,
    val tbDock: String = ToolbarDock.Bottom.name, val tbAlong: Float = 0.5f, val tbCollapsed: Boolean = false,
    val transformBoxOnly: Boolean = false,
    val keyRulerShown: Boolean = true,
    val defaultEase: Easing = Easing.Smooth,
    val posX: Float = 0f, val posY: Float = 0f, val rotation: Float = 0f, val scale: Float = 1f, val opacity: Float = 1f,
    val activeTrack: Int = 0, val activeLayer: Int = 0, val frame: Int = 0,
    val pxPerSec: Float = 96f, val scrollPx: Float = -24f,
    val rigMode: String = "Build", val boneTool: String = "Draw", val poseTool: String = "Auto",
    val rigShowAll: Boolean = true, val rigAutoKey: Boolean = true,
) {
    /** Same prefs minus the values that change constantly (playhead, timeline scroll): used to detect "dirty". */
    fun stable() = copy(frame = 0, scrollPx = 0f)
}

/** Immutable copy of everything a project file holds. Built on the main thread, encoded on IO. */
class ProjectData(
    val meta: ProjectMeta,
    val prefs: EditorPrefs,
    val cels: List<Cel>,
    val drawTracks: List<DrawTrack>,
    val strokes: List<InkStroke>,
    val audioTracks: List<AudioTrack>,
    val clips: List<AudioClip>,
    /** Group tracks (folders). Members point at them through DrawTrack.group / AudioTrack.group. */
    val groups: List<TrackGroup> = emptyList(),
) {
    /** Load only: handle of the natively parsed strokes.bin (NativeProject), 0 = none. [applyProject] feeds the canvas from it and releases it. */
    var strokeBlob: Long = 0L
}

/** A fresh project = exactly the editor's initial state: one row (Body / Head / Tail), three 4-frame drawings. */
fun blankProject(meta: ProjectMeta) = ProjectData(
    meta = meta,
    prefs = EditorPrefs(fps = meta.fps),
    cels = listOf(Cel(0, 4), Cel(1, 4), Cel(2, 4)),
    drawTracks = listOf(
        DrawTrack(0, "Drawing 1", layers = listOf(Layer(0, "Body"), Layer(1, "Head"), Layer(2, "Tail"))),
    ),
    strokes = emptyList(),
    audioTracks = listOf(AudioTrack(0, "Audio 1")),
    clips = emptyList(),
)
