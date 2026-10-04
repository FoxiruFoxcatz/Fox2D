package fox.foxiru.foxcat.fox2d.main_canvas

import androidx.compose.runtime.Immutable

// ======================================================================================================
// Keyframe data. This file is DATA ONLY: every curve is evaluated in C++ (fox_anim.h for drawing rows, shared by
// the live canvas and the movie exporter; fox_audio.cpp for audio volume). The ids below mirror fox::anim::Ease.
// Keys are PER PROPERTY: each slider / switch owns its own lane, so keying "Rotation" never touches "Position X".
// ======================================================================================================

/** Easing families; each owns 3 consecutive ids in C++: In, Out, InOut. [base] = id of its "In". */
enum class EaseFamily(val label: String, val base: Int) {
    Sine("Sine", 2), Quad("Quad", 5), Cubic("Cubic", 8), Quart("Quart", 11), Expo("Expo", 14),
    Back("Back", 17), Elastic("Elastic", 20), Bounce("Bounce", 23),
}

enum class EaseDir(val label: String) { In("In"), Out("Out"), InOut("In-Out") }

/**
 * Easing of the segment that STARTS at a key (key -> next key). [x1..y2] are the cubic-bezier control points
 * and only matter when [id] == [BEZIER]. Back / Elastic overshoot on purpose.
 */
@Immutable
data class Easing(
    val id: Int,
    val x1: Float = 0.25f, val y1: Float = 0.1f, val x2: Float = 0.25f, val y2: Float = 1f,
) {
    val isHold: Boolean get() = id == HOLD
    val isLinear: Boolean get() = id == LINEAR
    val isBezier: Boolean get() = id == BEZIER
    val family: EaseFamily? get() = if (id in 2 until BEZIER) EaseFamily.values()[(id - 2) / 3] else null
    val dir: EaseDir? get() = if (id in 2 until BEZIER) EaseDir.values()[(id - 2) % 3] else null

    val label: String
        get() = when {
            isHold -> "Hold"
            isLinear -> "Linear"
            isBezier -> "Bezier"
            else -> "${family?.label} ${dir?.label}"
        }

    companion object {
        const val HOLD = 0
        const val LINEAR = 1
        const val BEZIER = 26

        val Hold = Easing(HOLD)
        val Linear = Easing(LINEAR)
        val Smooth = of(EaseFamily.Cubic, EaseDir.InOut)

        fun of(f: EaseFamily, d: EaseDir) = Easing(f.base + d.ordinal)
    }
}

/** Animatable properties of a drawing row. [id] = channel index in fox_anim.h. [neutral] = the un-transformed value. */
enum class Chan(val id: Int, val label: String, val neutral: Float) {
    PosX(0, "Position X", 0f), PosY(1, "Position Y", 0f),
    Width(2, "W  (width)", 1f), Height(3, "H  (height)", 1f),
    Rotation(4, "Rotation", 0f);

    companion object { fun of(id: Int) = values().firstOrNull { it.id == id } }
}

/** What a keyframe icon is attached to. Only one drawer is open at a time (EditorState.kfOpen). */
sealed interface PropRef {
    /** One transform slider of a drawing row. */
    data class Draw(val track: Int, val chan: Chan) : PropRef
    /** Volume of one audio clip. */
    data class AudioGain(val clip: Int) : PropRef
    /** One animatable channel of a row's rig (bone pose, IK target, curve mix, control-point offset / thickness). [chan] = fox_rig.h id. */
    data class RigChannel(val track: Int, val chan: Int, val label: String) : PropRef
}

/** Floats per key in the flat array handed to C++ (see fox_anim.h). */
const val KEY_STRIDE = 8

/**
 * One key of ONE property of a drawing row. [frame] is ROW-LOCAL (0 = the row's first drawing), so dragging the row
 * on the timeline carries its keys along. [value] is in the channel's own unit (paper units / scale factor / degrees).
 */
@Immutable
data class ChanKey(val chan: Int, val frame: Int, val value: Float, val ease: Easing = Easing.Smooth) {
    /** [frame, channel, value, easingId, bx1, by1, bx2, by2] */
    fun writeTo(a: FloatArray, at: Int) {
        a[at] = frame.toFloat(); a[at + 1] = chan.toFloat(); a[at + 2] = value
        a[at + 3] = ease.id.toFloat()
        a[at + 4] = ease.x1; a[at + 5] = ease.y1; a[at + 6] = ease.x2; a[at + 7] = ease.y2
    }
}

/** Keys MUST be sorted by (channel, frame) -> the flat layout the native side reads. */
fun List<ChanKey>.pack(): FloatArray {
    val a = FloatArray(size * KEY_STRIDE)
    for (i in indices) this[i].writeTo(a, i * KEY_STRIDE)
    return a
}

val ChanKeyOrder: Comparator<ChanKey> = compareBy<ChanKey>({ it.chan }, { it.frame })
