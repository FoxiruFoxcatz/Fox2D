package fox.foxiru.foxcat.fox2d.timeline

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fox.foxiru.foxcat.fox2d.jnicallers.AudioHandlerNative
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One audio file placed on the timeline. All times are milliseconds (fps-independent). */
data class AudioClip(
    val id: Int,
    val track: Int,
    val handle: Int,          // native decoded-PCM handle (AudioHandlerNative)
    val name: String,
    val srcDurMs: Long,       // length of the whole source file
    val startMs: Long,        // where the clip starts on the timeline
    val inMs: Long,           // trim: offset into the source
    val lenMs: Long,          // trim: visible length
    val gain: Float = 1f,
    /** Volume keys (SOURCE ms, sorted). Empty = constant [gain]. With keys the smooth envelope drives the volume. */
    val gainKeys: List<AudioKey> = emptyList(),
    /** File name of the project's own copy of the audio (project/audio/<src>). Empty = not saved with a project. */
    val src: String = "",
    /** true = a copy made by a group's live Loop (rebuilt automatically; edit the original clip instead). */
    val gen: Boolean = false,
)

/** Volume key. [ms] is SOURCE time (0 = start of the file) so trimming / splitting / moving a clip keeps keys on the sound. */
data class AudioKey(val ms: Long, val gain: Float)

data class AudioTrack(
    val id: Int,
    val name: String,
    val muted: Boolean = false,
    val locked: Boolean = false,
    /** Id of the TrackGroup (EditorScreen.kt) this layer sits in, -1 = top level. */
    val group: Int = -1,
)

/**
 * Everything about the timeline that must survive rotation: zoom, scroll, audio tracks/clips, selection.
 * Lives inside EditorState (which lives in the ViewModel).
 */
@Stable
class TimelineState {
    /** Zoom: pixels per second of timeline. */
    var pxPerSec by mutableFloatStateOf(96f)

    /** Horizontal scroll in px: x on screen = seconds * pxPerSec - scrollPx. */
    var scrollPx by mutableFloatStateOf(-24f)

    var viewW by mutableFloatStateOf(0f)
    var contentSec by mutableFloatStateOf(10f)

    /** Length of the longest layer (drawings or audio) in seconds. The ruler marks stop here; set by TimelineEditor. */
    var lengthSec by mutableFloatStateOf(0f)

    /** Zoom-out floor: never above [MIN_PPS], and low enough that a very long timeline can still be fitted on screen. */
    val minPps: Float
        get() = if (viewW > 0f && contentSec > 0f) min(MIN_PPS, viewW / contentSec).coerceAtLeast(0.5f) else MIN_PPS

    val tracks = mutableStateListOf(AudioTrack(0, "Audio 1"))
    val clips = mutableStateListOf<AudioClip>()

    /** Selected audio clip id, -1 = none (then the current cel is the selected clip). */
    var selectedClip by mutableIntStateOf(-1)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    val minScroll: Float get() = -viewW * 0.25f
    val maxScroll: Float get() = max(minScroll, contentSec * pxPerSec - viewW * 0.75f)

    fun panBy(dx: Float) {
        scrollPx = (scrollPx + dx).coerceIn(minScroll, maxScroll)
    }

    /** Pinch zoom that keeps the time under [anchorX] where it is. */
    fun zoomAt(anchorX: Float, factor: Float) {
        val tAnchor = (anchorX + scrollPx) / pxPerSec
        pxPerSec = (pxPerSec * factor).coerceIn(minPps, MAX_PPS)
        scrollPx = (tAnchor * pxPerSec - anchorX).coerceIn(minScroll, maxScroll)
    }

    /**
     * Every native PCM handle this timeline has imported and not yet released. Handles outlive their clips
     * because undo can bring a deleted clip back; [collectGarbage] frees the ones nothing references any more.
     */
    private val owned = mutableSetOf<Int>()

    fun adopt(handle: Int) { owned.add(handle) }

    /** Release native PCM that no clip and no history entry ([keep]) can reach any more. */
    fun collectGarbage(keep: Set<Int>) {
        val live = clips.mapTo(HashSet()) { it.handle } + keep
        val dead = owned.filter { it !in live }
        if (dead.isEmpty()) return
        pushToNative() // mixer slots must stop pointing at the PCM before it is freed
        dead.forEach { AudioHandlerNative.release(it); owned.remove(it) }
    }

    fun nextClipId() = (clips.maxOfOrNull { it.id } ?: -1) + 1
    fun nextTrackId() = (tracks.maxOfOrNull { it.id } ?: -1) + 1

    fun clip(id: Int) = clips.firstOrNull { it.id == id }

    fun update(id: Int, f: (AudioClip) -> AudioClip) {
        val i = clips.indexOfFirst { it.id == id }
        if (i >= 0) clips[i] = f(clips[i])
    }

    /**
     * Group drag: every clip in [base] (clip id -> start ms when the drag began) starts [dMs] later, in ONE pass over the
     * table. Clips that do not move are not rewritten, so they do not invalidate the mixer sync / the timeline.
     */
    fun shiftClips(base: Map<Int, Long>, dMs: Long) {
        for (i in clips.indices) {
            val c = clips[i]
            val st = base[c.id] ?: continue
            val ns = max(0L, st + dMs)
            if (ns != c.startMs) clips[i] = c.copy(startMs = ns)
        }
    }

    // ---------------------------------------------------------------- volume keyframes (data here, curve in C++)

    /** Timeline time -> the clip's SOURCE time (what keys are stored in). */
    fun srcMsAt(c: AudioClip, timelineMs: Long): Long = c.inMs + (timelineMs - c.startMs)

    fun gainKeyAt(c: AudioClip, timelineMs: Long, tolMs: Long): AudioKey? {
        val s = srcMsAt(c, timelineMs)
        return c.gainKeys.firstOrNull { abs(it.ms - s) <= tolMs }
    }

    /** Pairs for native: [ms0, gain0, ms1, gain1, ...]. */
    fun packGainKeys(keys: List<AudioKey>): FloatArray {
        val a = FloatArray(keys.size * 2)
        keys.forEachIndexed { i, k -> a[i * 2] = k.ms.toFloat(); a[i * 2 + 1] = k.gain }
        return a
    }

    /** Volume at a timeline time: the envelope when the clip has keys (asked from C++), else its constant gain. */
    fun gainAt(c: AudioClip, timelineMs: Long): Float {
        if (c.gainKeys.isEmpty()) return c.gain
        val out = FloatArray(1)
        val s = srcMsAt(c, timelineMs).toDouble()
        AudioHandlerNative.envelope(packGainKeys(c.gainKeys), c.gainKeys.size, s, s, out)
        return out[0]
    }

    /** Raw: create / replace the key at [srcMs]. Refuses a new key past [MAX_GAIN_KEYS]. */
    fun putGainKey(id: Int, srcMs: Long, gain: Float) {
        val c = clip(id) ?: return
        val g = gain.coerceIn(0f, MAX_GAIN)
        val rest = c.gainKeys.filter { it.ms != srcMs }
        if (rest.size >= MAX_GAIN_KEYS) return
        update(id) { it.copy(gainKeys = (rest + AudioKey(srcMs, g)).sortedBy { k -> k.ms }) }
    }

    fun removeGainKeyNear(id: Int, timelineMs: Long, tolMs: Long) {
        val c = clip(id) ?: return
        val k = gainKeyAt(c, timelineMs, tolMs) ?: return
        val rest = c.gainKeys.filter { it !== k }
        // the last key going away leaves its volume as the clip's constant gain
        update(id) { it.copy(gainKeys = rest, gain = if (rest.isEmpty()) k.gain else it.gain) }
    }

    /** Raw (graph drag; wrap in snapshot + commitEdit): move key [fromMs] to [toMs] / [gain]. Returns the key's new source ms. */
    fun moveGainKey(id: Int, fromMs: Long, toMs: Long, gain: Float): Long {
        val c = clip(id) ?: return fromMs
        val k = c.gainKeys.firstOrNull { it.ms == fromMs } ?: return fromMs
        val others = c.gainKeys.filter { it !== k }
        var to = toMs.coerceAtLeast(0L)
        if (others.any { it.ms == to }) to = fromMs   // never stack two keys on the same instant
        update(id) { it.copy(gainKeys = (others + AudioKey(to, gain.coerceIn(0f, MAX_GAIN))).sortedBy { x -> x.ms }) }
        return to
    }

    /**
     * Slider edit (raw; wrap a drag in snapshot + commitEdit). No keys: sets the constant gain. With keys: AUTO-KEY at
     * the playhead (only while the playhead is inside the clip).
     */
    fun setGain(id: Int, timelineMs: Long, g: Float) {
        val c = clip(id) ?: return
        if (c.gainKeys.isEmpty()) { update(id) { it.copy(gain = g) }; return }
        if (timelineMs in c.startMs..(c.startMs + c.lenMs)) putGainKey(id, srcMsAt(c, timelineMs), g)
    }

    fun addTrack() {
        val id = nextTrackId()
        tracks.add(AudioTrack(id, "Audio ${id + 1}"))
    }

    fun endMs(): Long = clips.maxOfOrNull { it.startMs + it.lenMs } ?: 0L

    /** Split the selected clip at [playheadMs]. */
    fun split(playheadMs: Long) {
        val c = clip(selectedClip) ?: return
        val cut = playheadMs - c.startMs
        if (cut < MIN_CLIP_MS || c.lenMs - cut < MIN_CLIP_MS) return
        update(c.id) { it.copy(lenMs = cut) }
        val right = c.copy(id = nextClipId(), gen = false, startMs = playheadMs, inMs = c.inMs + cut, lenMs = c.lenMs - cut)
        clips.add(right)
        selectedClip = right.id
    }

    /** Drop everything before the playhead from the selected clip (keeps its end where it was). */
    fun trimStartTo(playheadMs: Long) {
        val c = clip(selectedClip) ?: return
        val cut = playheadMs - c.startMs
        if (cut <= 0 || c.lenMs - cut < MIN_CLIP_MS) return
        update(c.id) { it.copy(startMs = playheadMs, inMs = it.inMs + cut, lenMs = it.lenMs - cut) }
    }

    /** Drop everything after the playhead from the selected clip. */
    fun trimEndTo(playheadMs: Long) {
        val c = clip(selectedClip) ?: return
        val cut = playheadMs - c.startMs
        if (cut < MIN_CLIP_MS || cut >= c.lenMs) return
        update(c.id) { it.copy(lenMs = cut) }
    }

    fun nudgeSelected(deltaMs: Long) {
        val c = clip(selectedClip) ?: return
        update(c.id) { it.copy(startMs = max(0L, it.startMs + deltaMs)) }
    }

    /** Remove an audio layer with every clip on it (the last layer stays). PCM is freed by the history GC. */
    fun removeTrack(id: Int) {
        if (tracks.size <= 1) return
        tracks.removeAll { it.id == id }
        clips.removeAll { it.track == id }
        selectedClip = -1
    }

    fun duplicateSelected() {
        val c = clip(selectedClip) ?: return
        val copy = c.copy(id = nextClipId(), startMs = c.startMs + c.lenMs, gen = false)
        clips.add(copy)
        selectedClip = copy.id
    }

    fun deleteSelected() {
        val c = clip(selectedClip) ?: return
        clips.removeAll { it.id == c.id }
        selectedClip = -1
        // PCM is NOT released here: undo may restore the clip. EditorState.pushOp -> collectGarbage frees it
        // once the history entry that could resurrect it is gone.
    }

    fun releaseAll() {
        val handles = owned.toList()
        clips.clear()
        pushToNative()
        handles.forEach { AudioHandlerNative.release(it) }
        owned.clear()
    }

    /** Hand the current clip table to the native mixer (muted tracks = gain 0). */
    fun pushToNative() {
        val list = clips.toList()
        val muted = tracks.filter { it.muted }.map { it.id }.toSet()
        // a clip with volume keys is driven by its envelope, so its master gain is 1 (0 when the track is muted)
        val keysOf = { c: AudioClip -> if (c.track in muted) emptyList() else c.gainKeys }
        val packed = list.flatMap { keysOf(it) }
        AudioHandlerNative.setClips(
            IntArray(list.size) { list[it].handle },
            LongArray(list.size) { list[it].startMs },
            LongArray(list.size) { list[it].inMs },
            LongArray(list.size) { list[it].lenMs },
            FloatArray(list.size) { if (list[it].track in muted) 0f else if (list[it].gainKeys.isNotEmpty()) 1f else list[it].gain },
            IntArray(list.size) { keysOf(list[it]).size },
            packGainKeys(packed),
        )
    }

    companion object {
        const val MIN_PPS = 12f
        const val MAX_PPS = 1600f
        const val MIN_CLIP_MS = 100L
        const val MAX_GAIN = 2f
        const val MAX_GAIN_KEYS = 32   // = kMaxKeys in fox_audio.cpp
    }
}
