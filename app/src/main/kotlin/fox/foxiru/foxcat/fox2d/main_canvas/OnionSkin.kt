package fox.foxiru.foxcat.fox2d.main_canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import fox.foxiru.foxcat.fox2d.jnicallers.NativeCanvas
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

/*
 * Onion skin: ghosts of the drawings before / after the playhead on the ACTIVE drawing layer (row).
 *
 *   OnionState   settings (lives in EditorState as `val onion = OnionState()`)
 *   OnionSync    playhead / settings -> which drawings are ghosts -> NativeCanvas.setOnion (C++ bakes + draws them, fox_onion.h)
 *   OnionButton  top-right button next to "Properties"
 *   OnionSheet   settings sheet
 *
 * Everything onion lives in this file so EditorScreen.kt only needs four one-line hooks.
 */

@Stable
class OnionState {
    var enabled by mutableStateOf(false)
    var open by mutableStateOf(false)          // settings sheet
    var past by mutableIntStateOf(2)           // drawings before the current one (0..MAX)
    var future by mutableIntStateOf(1)         // drawings after the current one (0..MAX)
    var opacity by mutableFloatStateOf(0.5f)   // nearest ghost
    var falloff by mutableFloatStateOf(0.6f)   // every further ghost keeps this much of the previous opacity
    var tint by mutableStateOf(true)           // flat colour per side instead of the drawing's own colours
    var pastColor by mutableIntStateOf(0xFFFF4040.toInt())
    var futureColor by mutableIntStateOf(0xFF33D95A.toInt())

    companion object { const val MAX = 5 }
}

// ------------------------------------------------------------------------------------------------ plan -> native

private data class OnionPlan(val row: Int, val ghosts: List<Int>, val prm: List<Float>) {
    companion object { val OFF = OnionPlan(-1, emptyList(), emptyList()) }
}

private fun chan(argb: Int, shift: Int) = ((argb shr shift) and 255) / 255f

/**
 * Which drawings of the active row are ghosts. "n drawings away" counts DRAWINGS, not frames: a drawing held for 4 frames is
 * one ghost. [ghosts] = 4 ints each: drawing id, timeline frame it was found at, side (0 past, 1 future), distance (1 = nearest).
 * Reads snapshot state, so it re-runs by itself when the playhead, the active row or the settings change.
 */
private fun EditorState.onionPlan(): OnionPlan {
    val o = onion
    if (!o.enabled || playing || (o.past == 0 && o.future == 0)) return OnionPlan.OFF
    val track = activeTrack
    val row = drawTracks.indexOfFirst { it.id == track }
    if (row < 0) return OnionPlan.OFF

    val here = celAt(frame, track)?.shownId()
    val seen = HashSet<Int>().also { s -> here?.let { s.add(it) } }
    val out = ArrayList<Int>()

    fun walk(side: Int, count: Int) {
        var d = 0
        var f = frame
        var last = here
        var guard = 0
        while (d < count && guard++ < 480) {
            f += if (side == 0) -1 else 1
            if (f < 0 || f >= frameCount) break
            val id = celAt(f, track)?.shownId() ?: continue   // gap in the row: keep looking
            if (id == last) continue                          // same drawing held on
            last = id
            if (!seen.add(id)) continue                       // already a ghost (looping rows)
            d++
            out.add(id); out.add(f); out.add(side); out.add(d)
        }
    }
    walk(0, o.past)
    walk(1, o.future)
    if (out.isEmpty()) return OnionPlan.OFF

    val prm = listOf(
        o.opacity, o.falloff, if (o.tint) 1f else 0f,
        chan(o.pastColor, 16), chan(o.pastColor, 8), chan(o.pastColor, 0),
        chan(o.futureColor, 16), chan(o.futureColor, 8), chan(o.futureColor, 0),
    )
    return OnionPlan(row, out, prm)
}

/** Keeps native in step with the playhead + settings. Put once next to the other sync effects. */
@Composable
fun OnionSync(state: EditorState) {
    LaunchedEffect(state) {
        snapshotFlow { state.onionPlan() }.distinctUntilChanged().collect {
            NativeCanvas.setOnion(it.row, it.ghosts.toIntArray(), it.prm.toFloatArray())
        }
    }
}

// ------------------------------------------------------------------------------------------------ UI

/** Top-right button next to "Properties". First tap switches the onion on, tapping it while on opens the settings sheet (the switch there turns it off). */
@Composable
fun OnionButton(state: EditorState, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val o = state.onion
    FilledTonalButton(
        onClick = { if (o.enabled) o.open = true else { o.enabled = true } },
        modifier = modifier.height(36.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = (if (o.enabled) cs.primaryContainer else cs.secondaryContainer).copy(alpha = UI_ALPHA),
            contentColor = if (o.enabled) cs.onPrimaryContainer else cs.onSecondaryContainer,
        ),
    ) {
        OnionGlyph(tint = if (o.enabled) cs.onPrimaryContainer else cs.onSecondaryContainer)
        Spacer(Modifier.width(6.dp))
        Text("Onion")
    }
}

/** Three stacked frames, the older / newer ones fading out. */
@Composable
private fun OnionGlyph(tint: Color) {
    Canvas(Modifier.size(18.dp)) {
        val s = size.minDimension
        val box = Size(s * 0.62f, s * 0.62f)
        val r = CornerRadius(s * 0.12f)
        val step = s * 0.19f
        for (i in 0..2) {
            val a = if (i == 2) 1f else 0.35f + 0.2f * i
            val at = Offset(i * step, (2 - i) * step)
            if (i == 2) drawRoundRect(tint.copy(alpha = a), at, box, r)
            else drawRoundRect(tint.copy(alpha = a), at, box, r, style = androidx.compose.ui.graphics.drawscope.Stroke(width = s * 0.09f))
        }
    }
}

private val SWATCHES = listOf(
    0xFFFF4040.toInt(), 0xFFFF9F1A.toInt(), 0xFFFFD633.toInt(), 0xFF33D95A.toInt(),
    0xFF2EC4D6.toInt(), 0xFF4D7CFF.toInt(), 0xFFB45CFF.toInt(), 0xFFFF5CA8.toInt(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnionSheet(state: EditorState) {
    val o = state.onion
    ModalBottomSheet(onDismissRequest = { o.open = false }) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Onion Skin", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Neighbouring drawings of the active layer, hidden while playing",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(o.enabled, { o.enabled = it })
            }
            Spacer(Modifier.height(8.dp))
            CountRow("Past", o.past) { o.past = it }
            CountRow("Future", o.future) { o.future = it }
            PercentRow("Opacity", o.opacity, 0.05f..1f) { o.opacity = it }
            PercentRow("Fade", o.falloff, 0.2f..1f) { o.falloff = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Colour tint", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(o.tint, { o.tint = it })
            }
            if (o.tint) {
                Spacer(Modifier.height(8.dp))
                SwatchRow("Past", o.pastColor) { o.pastColor = it }
                Spacer(Modifier.height(6.dp))
                SwatchRow("Future", o.futureColor) { o.futureColor = it }
            }
        }
    }
}

@Composable
private fun CountRow(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(64.dp), style = MaterialTheme.typography.bodyMedium)
        Slider(value.toFloat(), { onChange(it.roundToInt()) }, Modifier.weight(1f), valueRange = 0f..OnionState.MAX.toFloat(), steps = OnionState.MAX - 1)
        Text("$value", Modifier.width(40.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PercentRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(64.dp), style = MaterialTheme.typography.bodyMedium)
        Slider(value, onChange, Modifier.weight(1f), valueRange = range)
        Text("${(value * 100).roundToInt()}%", Modifier.width(40.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwatchRow(label: String, selected: Int, onPick: (Int) -> Unit) {
    val ring = MaterialTheme.colorScheme.onSurface
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(64.dp), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (c in SWATCHES) {
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color(c))
                        .then(if (c == selected) Modifier.border(2.dp, ring, CircleShape) else Modifier)
                        .clickable { onPick(c) },
                )
            }
        }
    }
}
