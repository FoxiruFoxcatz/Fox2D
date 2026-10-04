package fox.foxiru.foxcat.fox2d.main_canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import fox.foxiru.foxcat.fox2d.timeline.AudioClip
import kotlin.math.roundToInt

/** Which edge of the canvas area the floating toolbar sits on. */
enum class ToolbarDock { Top, Bottom, Left, Right }

private val ToolbarDock.vertical: Boolean get() = this == ToolbarDock.Left || this == ToolbarDock.Right

/**
 * The timeline toolbar, floating over the canvas area. Drag the grip to move it; on release it snaps to the
 * nearest edge (top / bottom = a row, left / right = a column) and remembers how far along that edge you dropped it.
 * "Select" switches the buttons to track actions: tick tracks in the timeline headers, then delete / copy / cut /
 * duplicate / lock / mute them together (one undo step each).
 */
@Composable
fun TrackToolbar(state: EditorState) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val dock = state.tbDock
    val vertical = dock.vertical
    val collapsed = state.tbCollapsed

    var drag by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf(false) }
    var area by remember { mutableStateOf(IntSize.Zero) }     // the free canvas area the toolbar floats in
    var areaRoot by remember { mutableStateOf(Offset.Zero) }
    var gripRoot by remember { mutableStateOf(Offset.Zero) }
    var gripSize by remember { mutableStateOf(IntSize.Zero) }
    var gripStart by remember { mutableStateOf(Offset.Zero) } // grip centre (area coords) when the drag began

    val bias = state.tbAlong * 2f - 1f
    val align = when (dock) {
        ToolbarDock.Top -> BiasAlignment(bias, -1f)
        ToolbarDock.Bottom -> BiasAlignment(bias, 1f)
        ToolbarDock.Left -> BiasAlignment(-1f, bias)
        ToolbarDock.Right -> BiasAlignment(1f, bias)
    }

    fun finishDrag() {
        dragging = false
        val c = gripStart + drag
        val w = area.width.toFloat().coerceAtLeast(1f)
        val h = area.height.toFloat().coerceAtLeast(1f)
        // nearest edge, measured as a fraction of the area so a wide screen doesn't favour top / bottom
        val dL = c.x / w; val dR = (w - c.x) / w; val dT = c.y / h; val dB = (h - c.y) / h
        val m = minOf(dL, dR, dT, dB)
        val d = when (m) { dL -> ToolbarDock.Left; dR -> ToolbarDock.Right; dT -> ToolbarDock.Top; else -> ToolbarDock.Bottom }
        state.tbDock = d
        state.tbAlong = (if (d.vertical) c.y / h else c.x / w).coerceIn(0f, 1f)
        drag = Offset.Zero
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { area = it }
            .onGloballyPositioned { areaRoot = it.positionInRoot() },
    ) {
        Surface(
            modifier = Modifier
                .align(align)
                .padding(
                    start = 4.dp + state.railInset(ToolbarDock.Left),      // keep clear of the tool rail, wherever it is docked
                    end = 4.dp + state.railInset(ToolbarDock.Right),
                    top = (if (dock == ToolbarDock.Top || dock == ToolbarDock.Left) 34.dp else 4.dp) + state.railInset(ToolbarDock.Top),  // and of the view chip
                    bottom = 4.dp + state.railInset(ToolbarDock.Bottom),
                )
                .graphicsLayer {
                    translationX = drag.x
                    translationY = drag.y
                    val s = if (dragging) 1.04f else 1f
                    scaleX = s; scaleY = s
                    alpha = if (dragging) 0.92f else 1f
                },
            shape = RoundedCornerShape(18.dp),
            color = cs.surfaceContainer.copy(alpha = UI_ALPHA),
            tonalElevation = 3.dp,
            shadowElevation = if (dragging) 14.dp else 6.dp,
        ) {
            val grip: @Composable () -> Unit = {
                Grip(
                    vertical,
                    Modifier
                        .onGloballyPositioned { gripRoot = it.positionInRoot(); gripSize = it.size }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = {
                                    dragging = true
                                    drag = Offset.Zero
                                    gripStart = gripRoot - areaRoot + Offset(gripSize.width / 2f, gripSize.height / 2f)
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDragEnd = { finishDrag() },
                                onDragCancel = { finishDrag() },
                                onDrag = { change, amount -> change.consume(); drag += amount },
                            )
                        },
                )
            }
            val fold: @Composable () -> Unit = {
                Box(
                    Modifier.size(28.dp).clip(CircleShape).clickable { state.tbCollapsed = !collapsed },
                    contentAlignment = Alignment.Center,
                ) {
                    if (collapsed) FoxIcon(Ico.More, iconSize = 18.dp, tint = cs.onSurfaceVariant)
                    else Text("\u2013", color = cs.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
                }
            }
            if (vertical) {
                Column(Modifier.width(if (collapsed) 44.dp else 104.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    grip()
                    fold()
                    if (!collapsed) Lay(true, true, Modifier.weight(1f, fill = false).padding(bottom = 4.dp)) { ToolbarItems(state, true) }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    grip()
                    fold()
                    if (!collapsed) Lay(false, true, Modifier.weight(1f, fill = false).padding(end = 6.dp)) { ToolbarItems(state, false) }
                }
            }
        }
    }
}

/** Row or column of the same children; [scroll] makes it scroll along its axis. */
@Composable
private fun Lay(vertical: Boolean, scroll: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (vertical) {
        Column(
            modifier.then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
    } else {
        Row(
            modifier.then(if (scroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
    }
}

@Composable
private fun Grip(vertical: Boolean, modifier: Modifier) {
    val dot = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    Canvas(modifier.size(if (vertical) 100.dp else 26.dp, if (vertical) 24.dp else 44.dp)) {
        val r = 2.2.dp.toPx()
        val gap = 7.dp.toPx()
        val cols = if (vertical) 3 else 2
        val rows = if (vertical) 2 else 3
        val x0 = size.width / 2f - (cols - 1) * gap / 2f
        val y0 = size.height / 2f - (rows - 1) * gap / 2f
        for (i in 0 until cols) for (j in 0 until rows) drawCircle(dot, r, Offset(x0 + i * gap, y0 + j * gap))
    }
}

@Composable
private fun Sep(vertical: Boolean) {
    val c = MaterialTheme.colorScheme.outlineVariant
    if (vertical) Box(Modifier.padding(vertical = 3.dp).width(64.dp).height(1.dp).background(c))
    else Box(Modifier.padding(horizontal = 3.dp).height(24.dp).width(1.dp).background(c))
}

@Composable
private fun TBtn(label: String, vertical: Boolean, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 6.dp),
        modifier = Modifier.height(34.dp).then(if (vertical) Modifier.fillMaxWidth() else Modifier),
        colors = if (danger) ButtonDefaults.textButtonColors(contentColor = cs.error) else ButtonDefaults.textButtonColors(),
    ) { Text(label, maxLines = 1) }
}

@Composable
private fun ToolbarItems(state: EditorState, v: Boolean) {
    if (state.trackSelectMode) SelectItems(state, v) else NormalItems(state, v)
}

// ------------------------------------------------------------------------------------------ select tracks

@Composable
private fun SelectItems(state: EditorState, v: Boolean) {
    val n = state.selCount
    val any = n > 0
    val tl = state.timeline
    val allLocked = any &&
        state.drawTracks.filter { it.id in state.selDraw }.all { it.locked } &&
        tl.tracks.filter { it.id in state.selAudio }.all { it.locked }
    val allMuted = state.selAudio.isNotEmpty() && tl.tracks.filter { it.id in state.selAudio }.all { it.muted }

    Text(
        if (any) "$n selected" else "Tick tracks",
        Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
    )
    TBtn("All", v) { state.selectAllTracks() }
    TBtn("None", v, any) { state.clearTrackSel() }
    Sep(v)
    TBtn("Copy", v, any) { state.copySelectedTracks() }
    TBtn("Cut", v, any) { state.cutSelectedTracks() }
    TBtn("Paste", v, state.clipboard is Clipboard.Tracks) { state.pasteTracks() }
    TBtn("Duplicate", v, any) { state.duplicateSelectedTracks() }
    TBtn("Delete", v, any, danger = true) { state.deleteSelectedTracks() }
    Sep(v)
    TBtn(if (allLocked) "Unlock" else "Lock", v, any) { state.setSelectedLocked(!allLocked) }
    if (state.selAudio.isNotEmpty()) TBtn(if (allMuted) "Unmute" else "Mute", v) { state.setSelectedMuted(!allMuted) }
    Sep(v)
    TBtn("Done", v) { state.endTrackSelect() }
}

// ------------------------------------------------------------------------------------------ everyday editing

@Composable
private fun NormalItems(state: EditorState, v: Boolean) {
    val tl = state.timeline
    val sel = tl.clip(tl.selectedClip)
    val audio = sel != null
    val trackLocked = sel != null && tl.tracks.firstOrNull { it.id == sel.track }?.locked == true
    val canEdit = if (audio) !trackLocked else !state.activeLocked
    val playMs = state.frame * 1000L / state.fps
    var gainBefore by remember { mutableStateOf<EditSnap?>(null) }

    TBtn("+ Drawing", v, !state.activeLocked) { state.edit { state.addCel() } }
    TBtn(if (tl.loading) "Loading\u2026" else "+ Audio", v, !tl.loading) { state.audioPickRequested = true }
    Sep(v)

    // the selection decides the target: a yellow audio clip, otherwise the drawing under the playhead
    TBtn("Copy", v) { state.copySelection() }
    TBtn("Cut", v, canEdit) { state.cutSelection() }
    TBtn("Paste", v, state.clipboard != null) { state.paste(playMs) }
    TBtn("Duplicate", v, canEdit) { state.duplicateSelection() }
    TBtn("Split", v, canEdit) { state.splitSelection(playMs) }
    TBtn("Delete", v, canEdit) { state.deleteSelection() }
    Sep(v)

    if (sel != null) {
        TBtn("Trim start", v, canEdit) { state.edit { tl.trimStartTo(playMs) } }
        TBtn("Trim end", v, canEdit) { state.edit { tl.trimEndTo(playMs) } }
        TBtn("\u25C0 Frame", v, canEdit) { state.edit { tl.nudgeSelected(-1000L / state.fps) } }
        TBtn("Frame \u25B6", v, canEdit) { state.edit { tl.nudgeSelected(1000L / state.fps) } }
        // volume: with keys the slider shows the envelope at the playhead and a drag keys that frame
        val shownGain = tl.gainAt(sel, playMs)
        Lay(v, false) {
            Slider(
                value = shownGain.coerceIn(0f, 2f),
                onValueChange = { g ->
                    if (gainBefore == null) gainBefore = state.snapshot() // whole slider drag = one undo step
                    state.playing = false
                    tl.setGain(sel.id, playMs, g)
                },
                onValueChangeFinished = {
                    gainBefore?.let { state.commitEdit(it) }
                    gainBefore = null
                },
                valueRange = 0f..2f,
                modifier = if (v) Modifier.width(92.dp).height(28.dp) else Modifier.width(110.dp).height(28.dp),
            )
            Lay(false, false) {
                Text("${(shownGain * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                // tap = volume keyframe drawer (smooth curve), long-press = key / un-key the playhead
                KeyIcon(state, PropRef.AudioGain(sel.id), enabled = canEdit)
            }
        }
        Sep(v)
        TBtn("Delete track", v, tl.tracks.size > 1 && !trackLocked, danger = true) { state.edit { tl.removeTrack(sel.track) } }
    } else {
        TBtn("Clear", v, canEdit) { state.clearCel() }
        TBtn("\u25C0 Move", v, canEdit) { state.moveCelBy(-1) }
        TBtn("Move \u25B6", v, canEdit) { state.moveCelBy(1) }
        TBtn("\u25C0 Layer", v, canEdit && state.trackOffset(state.activeTrack) > 0) { state.shiftLayer(-1) }
        TBtn("Layer \u25B6", v, canEdit) { state.shiftLayer(1) }
        TBtn("Hold \u2212", v, canEdit && (state.currentCel?.len ?: 0) > 1) { state.changeHold(-1) }
        TBtn("Hold +", v, canEdit) { state.changeHold(1) }
        Sep(v)
        TBtn("+ Track", v) { state.addDrawTrack() }
        TBtn("Dup track", v) { state.duplicateDrawTrack() }
        TBtn("Delete track", v, state.drawTracks.size > 1 && !state.activeLocked, danger = true) { state.deleteDrawTrack() }
    }
    Sep(v)
    TBtn("Select\u2026", v) { state.trackSelectMode = true; state.tbCollapsed = false }
    tl.error?.let {
        Text(it, Modifier.padding(horizontal = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
    }
}
