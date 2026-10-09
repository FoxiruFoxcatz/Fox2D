package fox.foxiru.foxcat.fox2d.main_canvas

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.rotate
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import fox.foxiru.foxcat.fox2d.jnicallers.NativeCanvas
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// ======================================================================================================
// Bone / Deform panel: sits above the transport inside the Dock. Modes:
//   Build = lay out bones / curves on the untouched picture, Pose = move them (keyframes), Paint = skin weights.
// Every slider with a diamond is a rig channel: tap = keyframe drawer (easing), long-press = key the playhead.
// ======================================================================================================

private fun pct(v: Float) = "${(v * 100f).roundToInt()}%"
private fun deg(v: Float) = "%.1f°".format(v)

@Composable
internal fun RigPanel(state: EditorState) {
    val cs = MaterialTheme.colorScheme
    val ui = state.rigUi
    val track = state.activeTrack
    val rig = state.trackRig(track)
    val isBone = state.tool == Tool.Bone
    val isWarp = state.tool == Tool.Warp
    val pending = remember { arrayOfNulls<EditSnap>(1) }

    // keep the selection valid when the row, the rig or the history changes
    LaunchedEffect(track, rig.bones.size, rig.curves.size, rig.pins.size, state.tool) { state.normalizeRigSel() }

    // "Lines": the mesh is drawn by the native canvas (GLSL); off again as soon as the panel closes
    val meshRow = state.nativeRowOf(track)
    DisposableEffect(ui.meshLines, meshRow) {
        NativeCanvas.setMeshLines(ui.meshLines && meshRow >= 0, meshRow)
        onDispose { NativeCanvas.setMeshLines(false, -1) }
    }

    fun begin() { if (pending[0] == null) pending[0] = state.snapshot() }
    fun end() { pending[0]?.let { state.commitEdit(it) }; pending[0] = null }
    /** Slider on a static rig parameter: one undo step per drag. */
    fun param(f: (Rig) -> Rig) { begin(); state.setRig(track, f) }

    val mode = if (!isBone && ui.mode == RigMode.Paint) RigMode.Build else ui.mode
    val pose = if (rig.isEmpty) null else state.rigPose(track)

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (isBone) "Bones" else if (isWarp) "Puppet Warp" else "Deform", Modifier.padding(end = 4.dp), style = MaterialTheme.typography.titleMedium)
            FilterChip(mode == RigMode.Build, { ui.mode = RigMode.Build }, { Text("Build") })
            FilterChip(mode == RigMode.Pose, { ui.mode = RigMode.Pose }, { Text("Pose") })
            if (isBone) FilterChip(mode == RigMode.Paint, { ui.mode = RigMode.Paint }, { Text("Weights") })
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { state.undo() }, enabled = state.canUndo, modifier = Modifier.size(36.dp)) { FoxIcon(Ico.Undo, iconSize = 20.dp) }
                IconButton(onClick = { state.redo() }, enabled = state.canRedo, modifier = Modifier.size(36.dp)) { FoxIcon(Ico.Redo, iconSize = 20.dp) }
                IconButton(onClick = { ui.collapsed = !ui.collapsed }, modifier = Modifier.size(36.dp)) {
                    FoxIcon(Ico.Chevron, Modifier.rotate(if (ui.collapsed) 270f else 90f), iconSize = 20.dp)
                }
                TextButton(onClick = { state.kfOpen = null; state.tool = Tool.Brush }) { Text("Done") }
            }
        }

        // Build / Weights hide the transport + timeline (see Dock), so they get a bit more room; Pose keeps the timeline
        // for scrubbing and therefore a shorter panel. Folded: header only, the whole screen is canvas.
        if (!ui.collapsed) Column(
            Modifier.fillMaxWidth().heightIn(max = if (mode == RigMode.Pose) 150.dp else 210.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            when {
                isBone && mode == RigMode.Build -> BoneBuild(state, track, rig, ::param, ::end)
                isBone && mode == RigMode.Pose -> BonePose(state, track, rig, pose)
                isBone -> BonePaint(state, track, rig)
                isWarp && mode == RigMode.Build -> PinBuild(state, track, rig, ::param, ::end)
                isWarp -> PinPose(state, track, rig, pose)
                mode == RigMode.Build -> CurveBuild(state, track, rig, ::param, ::end)
                else -> CurvePose(state, track, rig, pose)
            }
            MeshRow(state, track, rig)
            if (mode != RigMode.Pose) AttachRow(state, track)
        }
    }
}

// ------------------------------------------------------------------------------------------------ pieces

@Composable
private fun RigSlider(
    state: EditorState,
    prop: PropRef.RigChannel?,
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    text: String,
    neutral: Float?,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                Modifier.weight(1f).clickable(enabled = neutral != null) { if (neutral != null) { onChange(neutral); onDone() } },
                style = MaterialTheme.typography.labelLarge, color = cs.onSurface,
            )
            Text(text, Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.labelLarge, color = cs.primary)
            if (prop != null) KeyIcon(state, prop, enabled = !state.activeLocked)
        }
        Slider(
            value = value.coerceIn(range), onValueChange = onChange, onValueChangeFinished = onDone,
            valueRange = range, modifier = Modifier.height(30.dp),
        )
        if (prop != null) KeyDrawer(state, prop)
    }
}

/** Slider bound to a keyed rig channel; edits go through [EditorState.setRigChan] (auto-key). */
@Composable
private fun ChanSlider(
    state: EditorState, track: Int, chan: Int, label: String, pose: RigPose?, range: ClosedFloatingPointRange<Float>,
    neutral: Float?, fmt: (Float) -> String, begin: () -> Unit, end: () -> Unit,
) {
    val v = pose?.value(chan) ?: state.trackRig(track).baseOf(chan)
    RigSlider(
        state, PropRef.RigChannel(track, chan, label), label, v, range, fmt(v), neutral,
        onChange = { begin(); state.setRigChan(track, chan, it) }, onDone = end,
    )
}

@Composable
private fun chanBegin(state: EditorState): Pair<() -> Unit, () -> Unit> {
    val pending = remember { arrayOfNulls<EditSnap>(1) }
    return Pair(
        { if (pending[0] == null) pending[0] = state.snapshot() },
        { pending[0]?.let { state.commitEdit(it) }; pending[0] = null },
    )
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}

@Composable
private fun Hint(t: String) {
    Text(t, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun BoneChips(state: EditorState, rig: Rig) {
    val ui = state.rigUi
    ChipRow {
        FilterChip(ui.showAll, { ui.showAll = !ui.showAll }, { Text("All tracks") })
        rig.bones.forEachIndexed { i, b ->
            FilterChip(ui.bone == i, { ui.bone = i }, { Text((if (b.parent >= 0) "↳ " else "") + b.name) })
        }
    }
}

// ------------------------------------------------------------------------------------------------ bones

@Composable
private fun BoneBuild(state: EditorState, track: Int, rig: Rig, param: ((Rig) -> Rig) -> Unit, end: () -> Unit) {
    val ui = state.rigUi
    ChipRow {
        FilterChip(ui.boneTool == BoneTool.Draw, { ui.boneTool = BoneTool.Draw }, { Text("Draw bone") })
        FilterChip(ui.boneTool == BoneTool.Edit, { ui.boneTool = BoneTool.Edit }, { Text("Move joints") })
        TextButton(onClick = { state.clearRig(track) }, enabled = !rig.isEmpty) { Text("Clear rig") }
    }
    Hint(
        if (ui.boneTool == BoneTool.Draw) "Drag to draw a bone. Start on a bone's tip or body to chain a child to it. Tap a bone to select."
        else "Drag a joint (head / tip) or a bone body. Moving a parent's tip drags the connected children."
    )
    if (rig.bones.isEmpty()) return
    BoneChips(state, rig)
    val i = ui.bone
    val b = rig.bones.getOrNull(i) ?: return

    // parent
    ChipRow {
        Text("Parent", style = MaterialTheme.typography.labelMedium)
        FilterChip(b.parent < 0, { state.edit { state.setRig(track) { it.withParent(i, -1) } } }, { Text("None") })
        for (p in 0 until i) FilterChip(b.parent == p, { state.edit { state.setRig(track) { it.withParent(i, p) } } }, { Text(rig.bones[p].name) })
    }

    RigSlider(state, null, "Influence radius", b.radius, 0.02f..0.6f, pct(b.radius), null,
        { v -> param { it.withBone(i) { x -> x.copy(radius = v) } } }, end)
    RigSlider(state, null, "Falloff", b.falloff, 0.5f..6f, "%.1f".format(b.falloff), null,
        { v -> param { it.withBone(i) { x -> x.copy(falloff = v) } } }, end)
    RigSlider(state, null, "Strength", b.strength, 0.1f..2f, pct(b.strength), null,
        { v -> param { it.withBone(i) { x -> x.copy(strength = v) } } }, end)

    // IK
    ChipRow {
        Text("IK chain", style = MaterialTheme.typography.labelMedium)
        for (n in listOf(0, 2, 3, 4)) FilterChip(b.ikChain == n, { state.setIkChain(track, i, n) }, { Text(if (n == 0) "Off" else "$n bones") })
        if (b.ikChain > 0) {
            Text("Bend", style = MaterialTheme.typography.labelMedium)
            Switch(b.ikBend >= 0f, { on -> state.edit { state.setRig(track) { it.withBone(i) { x -> x.copy(ikBend = if (on) 1f else -1f) } } } })
        }
    }
    ChipRow {
        TextButton(onClick = {
            state.edit {
                val old = state.trackRig(track)
                state.setRig(track) { it.removeBone(i) }
                state.fixAttachAfterRemove(track, old, i)
            }
            ui.bone = (i - 1).coerceAtLeast(0)
        }) { Text("Delete bone") }
        TextButton(onClick = { state.fitMeshToDrawing() }) { Text("Fit mesh to drawing") }
    }
}

@Composable
private fun BonePose(state: EditorState, track: Int, rig: Rig, pose: RigPose?) {
    val ui = state.rigUi
    val (begin, end) = chanBegin(state)
    if (rig.bones.isEmpty()) { Hint("Build bones first."); return }
    BoneChips(state, rig)
    ChipRow {
        Text("Drag", style = MaterialTheme.typography.labelMedium)
        for (t in PoseTool.values()) FilterChip(ui.poseTool == t, { ui.poseTool = t }, { Text(if (t == PoseTool.Auto) "Auto" else t.name) })
    }
    ChipRow {
        Text("Auto-key", style = MaterialTheme.typography.labelMedium)
        Switch(ui.autoKey, { ui.autoKey = it })
        TextButton(onClick = { state.keyBone(track, ui.bone) }) { Text("Key bone") }
        TextButton(onClick = { state.resetBonePose(track, ui.bone) }) { Text("Reset bone") }
        TextButton(onClick = { state.resetRigPose(track) }) { Text("Reset pose") }
    }
    Hint("Drag a joint to move, the tip or body to rotate. IK bones: drag the gold target. Sliders below are keyframeable.")
    val i = ui.bone
    val b = rig.bones.getOrNull(i) ?: return
    fun ch(c: BoneChan) = RigChan.bone(i, c)
    fun lbl(c: BoneChan) = "${b.name} · ${c.label}"
    ChanSlider(state, track, ch(BoneChan.MoveX), lbl(BoneChan.MoveX), pose, -0.5f..0.5f, 0f, ::pct, begin, end)
    ChanSlider(state, track, ch(BoneChan.MoveY), lbl(BoneChan.MoveY), pose, -0.5f..0.5f, 0f, ::pct, begin, end)
    ChanSlider(state, track, ch(BoneChan.Rotate), lbl(BoneChan.Rotate), pose, -360f..360f, 0f, ::deg, begin, end)
    ChanSlider(state, track, ch(BoneChan.Length), lbl(BoneChan.Length), pose, 0.2f..3f, 1f, ::pct, begin, end)
    ChanSlider(state, track, ch(BoneChan.Thick), lbl(BoneChan.Thick), pose, 0.2f..3f, 1f, ::pct, begin, end)
    if (b.ikChain > 0) {
        ChanSlider(state, track, ch(BoneChan.IkX), lbl(BoneChan.IkX), pose, -0.5f..1.5f, null, ::pct, begin, end)
        ChanSlider(state, track, ch(BoneChan.IkY), lbl(BoneChan.IkY), pose, -0.5f..1.5f, null, ::pct, begin, end)
        ChanSlider(state, track, ch(BoneChan.IkMix), lbl(BoneChan.IkMix), pose, 0f..1f, 1f, ::pct, begin, end)
    }
}

@Composable
private fun BonePaint(state: EditorState, track: Int, rig: Rig) {
    val ui = state.rigUi
    val (_, end) = chanBegin(state)
    if (rig.bones.isEmpty()) { Hint("Build bones first."); return }
    BoneChips(state, rig)
    Hint("Paint the picture that the selected bone moves. Skin weights are blended and normalised on the rest of the bones.")
    ChipRow {
        FilterChip(!ui.erase, { ui.erase = false }, { Text("Add") })
        FilterChip(ui.erase, { ui.erase = true }, { Text("Erase") })
        Text("Heat map", style = MaterialTheme.typography.labelMedium)
        Switch(ui.showWeights, { ui.showWeights = it })
        TextButton(onClick = { state.edit { state.setRig(track) { it.copy(paint = null) } } }, enabled = rig.paint != null) { Text("Reset to auto") }
    }
    RigSlider(state, null, "Brush size", ui.brushRadius, 0.01f..0.3f, pct(ui.brushRadius), null, { ui.brushRadius = it }, end)
    RigSlider(state, null, "Strength", ui.brushStrength, 0.05f..1f, pct(ui.brushStrength), null, { ui.brushStrength = it }, end)
}

// ------------------------------------------------------------------------------------------------ puppet warp pins

@Composable
private fun PinChips(state: EditorState, rig: Rig) {
    val ui = state.rigUi
    ChipRow {
        rig.pins.forEachIndexed { i, _ ->
            val keyed = rig.keys.any { it.chan == RigChan.pin(i, 0) || it.chan == RigChan.pin(i, 1) || it.chan == RigChan.pinRot(i) }
            FilterChip(ui.pin == i, { ui.pin = i }, { Text("Pin ${i + 1}" + if (keyed) " ◆" else "") })
        }
    }
}

@Composable
private fun PinBuild(state: EditorState, track: Int, rig: Rig, param: ((Rig) -> Rig) -> Unit, end: () -> Unit) {
    val ui = state.rigUi
    Hint("Tap the picture to drop a pin. Pins hold the drawing in place; in Pose mode, drag one and the rest bends rigidly around the others. Drag a pin to move it.")
    ChipRow {
        Text("Reach", style = MaterialTheme.typography.labelMedium)
        for ((f, n) in listOf(1 to "Wide", 2 to "Medium", 3 to "Tight")) FilterChip(rig.warpFalloff == f, { state.setWarpFalloff(track, f) }, { Text(n) })
    }
    OutlineMeshRow(state, track)
    FitToImageRow(state, track)
    ChipRow {
        Text("Auto pins", style = MaterialTheme.typography.labelMedium)
        for (n in intArrayOf(6, 10, 16, RigLimits.MAX_PINS)) TextButton(onClick = { state.autoPins(track, n) }) { Text("$n") }
    }
    if (rig.pins.isEmpty()) return
    PinChips(state, rig)
    ChipRow {
        TextButton(onClick = { state.removeWarpPin(track, ui.pin) }, enabled = ui.pin in rig.pins.indices) { Text("Delete pin") }
        TextButton(onClick = { state.clearWarpPins(track) }) { Text("Clear pins") }
        TextButton(onClick = { state.fitMeshToDrawing() }) { Text("Fit mesh to drawing") }
    }
    val sel = ui.pin
    if (sel in rig.pins.indices) {
        val st = rig.pins[sel].stiff
        RigSlider(state, null, "Pin ${sel + 1} · Influence", st, 0.2f..4f, "%.1f×".format(st), 1f, { v -> param { it.withPinStiff(sel, v) } }, end)
    }
    Hint("${rig.pins.size}/${RigLimits.MAX_PINS} pins. A pin you never move still works as an anchor. Influence: how far a pin's pull reaches (higher = stiffer area around it).")
}

@Composable
private fun PinPose(state: EditorState, track: Int, rig: Rig, pose: RigPose?) {
    val ui = state.rigUi
    val (begin, end) = chanBegin(state)
    if (rig.pins.isEmpty()) { Hint("Drop pins in Build first."); return }
    PinChips(state, rig)
    ChipRow {
        Text("Auto-key", style = MaterialTheme.typography.labelMedium)
        Switch(ui.autoKey, { ui.autoKey = it })
        TextButton(onClick = { state.resetPins(track, ui.pin) }, enabled = ui.pin in rig.pins.indices) { Text("Reset pin") }
        TextButton(onClick = { state.resetPins(track) }) { Text("Reset all") }
    }
    Hint("Drag a pin to move it; drag the yellow handle of the selected pin to rotate it (keyframeable, past 360° too). Unmoved pins stay put and hold the picture.")
    val i = ui.pin
    if (i !in rig.pins.indices) return
    val n = "Pin ${i + 1}"
    ChanSlider(state, track, RigChan.pin(i, 0), "$n · Offset X", pose, -0.5f..0.5f, 0f, ::pct, begin, end)
    ChanSlider(state, track, RigChan.pin(i, 1), "$n · Offset Y", pose, -0.5f..0.5f, 0f, ::pct, begin, end)
    ChanSlider(state, track, RigChan.pinRot(i), "$n · Rotation", pose, -360f..360f, 0f, ::deg, begin, end)
}

// ------------------------------------------------------------------------------------------------ curves

@Composable
private fun CurveChips(state: EditorState, rig: Rig, add: Boolean, track: Int) {
    val ui = state.rigUi
    ChipRow {
        FilterChip(ui.showAll, { ui.showAll = !ui.showAll }, { Text("All tracks") })
        rig.curves.forEachIndexed { k, c ->
            FilterChip(ui.curve == k, { ui.curve = k; ui.ctl = -1 }, { Text(c.name) })
        }
        if (add) TextButton(
            onClick = { state.edit { state.setRig(track) { it.addCurve(emptyList()) } }; ui.curve = state.trackRig(track).curves.size - 1; ui.ctl = -1 },
            enabled = rig.curves.size < RigLimits.MAX_CURVES,
        ) { Text("+ Curve") }
    }
}

@Composable
private fun CurveBuild(state: EditorState, track: Int, rig: Rig, param: ((Rig) -> Rig) -> Unit, end: () -> Unit) {
    val ui = state.rigUi
    CurveChips(state, rig, true, track)
    Hint("Tap the canvas to add control points along the part you want to bend (filled = follows a bone, hollow = free). Drag a point to move it.")
    val k = ui.curve
    val c = rig.curves.getOrNull(k) ?: return
    ChipRow {
        TextButton(onClick = {
            state.edit { state.setRig(track) { it.removeCtl(k, ui.ctl) } }
            ui.ctl = (ui.ctl - 1).coerceAtLeast(-1)
        }, enabled = ui.ctl in c.pts.indices) { Text("Delete point") }
        val bone = ui.bone
        TextButton(onClick = { state.edit { state.setRig(track) { it.withCtl(k, ui.ctl) { p -> p.copy(bone = bone) } } } },
            enabled = ui.ctl in c.pts.indices && bone in rig.bones.indices) { Text("Bind to selected bone") }
        TextButton(onClick = { state.edit { state.setRig(track) { it.withCtl(k, ui.ctl) { p -> p.copy(bone = -1) } } } },
            enabled = ui.ctl in c.pts.indices && c.pts[ui.ctl].bone >= 0) { Text("Free") }
        TextButton(onClick = {
            state.edit { state.setRig(track) { it.removeCurve(k) } }
            ui.curve = (k - 1).coerceAtLeast(0); ui.ctl = -1
        }) { Text("Delete curve") }
    }
    if (ui.ctl in c.pts.indices) {
        val p = c.pts[ui.ctl]
        Hint("Point ${ui.ctl + 1}/${c.pts.size} · " + (if (p.bone >= 0) "bound to ${rig.bones.getOrNull(p.bone)?.name ?: "?"}" else "free"))
    } else Hint("${c.pts.size} point${if (c.pts.size == 1) "" else "s"} · a curve needs 2 or more to bend anything")
    RigSlider(state, null, "Reach (how far the bend grabs)", c.reach, 0.01f..0.5f, pct(c.reach), null,
        { v -> param { it.withCurve(k) { x -> x.copy(reach = v) } } }, end)
    RigSlider(state, null, "Softness (edge fade)", c.softness, 0f..1f, pct(c.softness), null,
        { v -> param { it.withCurve(k) { x -> x.copy(softness = v) } } }, end)
}

@Composable
private fun CurvePose(state: EditorState, track: Int, rig: Rig, pose: RigPose?) {
    val ui = state.rigUi
    val (begin, end) = chanBegin(state)
    if (rig.curves.isEmpty()) { Hint("Create a curve in Build first."); return }
    CurveChips(state, rig, false, track)
    ChipRow {
        Text("Auto-key", style = MaterialTheme.typography.labelMedium)
        Switch(ui.autoKey, { ui.autoKey = it })
    }
    val k = ui.curve
    val c = rig.curves.getOrNull(k) ?: return
    ChanSlider(state, track, RigChan.curveMix(k), "${c.name} · Deform amount", pose, 0f..1f, 1f, ::pct, begin, end)
    val i = ui.ctl
    if (i in c.pts.indices) {
        Hint("Point ${i + 1}: drag it on the canvas, or use the sliders. Thickness widens / narrows the bent part there.")
        val n = "${c.name} P${i + 1}"
        ChanSlider(state, track, RigChan.ctl(k, i, 0), "$n · Offset X", pose, -0.4f..0.4f, 0f, ::pct, begin, end)
        ChanSlider(state, track, RigChan.ctl(k, i, 1), "$n · Offset Y", pose, -0.4f..0.4f, 0f, ::pct, begin, end)
        ChanSlider(state, track, RigChan.ctl(k, i, 2), "$n · Thickness", pose, 0.1f..3f, 1f, ::pct, begin, end)
    } else Hint("Tap a control point to pose it.")
}

// ------------------------------------------------------------------------------------------------ attach to another row's bone

@Composable
private fun AttachRow(state: EditorState, track: Int) {
    val cur = state.drawTracks.firstOrNull { it.id == track }?.attach
    val cands = state.attachCandidates(track)
    if (cands.isEmpty() && cur == null) return
    val parent = cands.firstOrNull { it.id == cur?.track }
    ChipRow {
        Text("Attach to", style = MaterialTheme.typography.labelMedium)
        FilterChip(cur == null, { state.setAttach(track, null) }, { Text("Nothing") })
        for (c in cands) FilterChip(cur?.track == c.id, { state.setAttach(track, BoneAttach(c.id, 0)) }, { Text(c.name) })
    }
    if (parent != null && cur != null) {
        ChipRow {
            Text("Bone", style = MaterialTheme.typography.labelMedium)
            parent.rig.bones.forEachIndexed { i, b ->
                FilterChip(cur.bone == i, { state.setAttach(track, BoneAttach(parent.id, i)) }, { Text(b.name) })
            }
        }
        Hint("This drawing follows ${parent.name} \u203A ${parent.rig.bones.getOrNull(cur.bone)?.name ?: "?"}: move, turn and stretch included. Shown unattached while building.")
    }
}

// ------------------------------------------------------------------------------------------------ mesh

@Composable
private fun MeshRow(state: EditorState, track: Int, rig: Rig) {
    if (rig.isEmpty) return
    ChipRow {
        Text("Mesh", style = MaterialTheme.typography.labelMedium)
        for (g in RigLimits.GRIDS) FilterChip(rig.grid == g, { state.setMeshGrid(track, g) }, { Text("$g") })
        FilterChip(state.rigUi.meshLines, { state.rigUi.meshLines = !state.rigUi.meshLines }, { Text("Lines") })
    }
}

/** Puppet Warp: mesh that follows the drawing's outline (triangles), drawn as GPU lines. */
@Composable
private fun OutlineMeshRow(state: EditorState, track: Int) {
    val ui = state.rigUi
    var msg by remember { mutableStateOf<String?>(null) }
    val m = ui.tri[track]
    ChipRow {
        Text("Outline mesh", style = MaterialTheme.typography.labelMedium)
        for ((i, n) in listOf("Coarse", "Medium", "Fine").withIndex()) FilterChip(ui.triDensity == i, { ui.triDensity = i }, { Text(n) })
        TextButton(onClick = { msg = if (state.generateOutlineMesh(track)) null else "Draw something first: the mesh is built around your drawing." }) { Text("Generate") }
        if (m != null) TextButton(onClick = { state.clearOutlineMesh(track) }) { Text("Clear") }
    }
    Hint(msg ?: if (m != null) "${m.vertexCount} points, ${m.triangleCount} triangles. Lines on = the mesh is drawn on the canvas (chip Lines)." else "Builds triangles only where your drawing is, instead of a square grid.")
}

/** Puppet Warp: pick a picture, the drawing's outline is pulled onto the picture's outline (pins are created for you). */
@Composable
private fun FitToImageRow(state: EditorState, track: Int) {
    val ui = state.rigUi
    val ctx = LocalContext.current
    var msg by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bm: Bitmap? = decodeFitBitmap(ctx, uri)
        msg = when {
            bm == null -> "Could not read that picture."
            state.fitToImage(track, bm, FitShape.values()[ui.fitShape.coerceIn(0, 2)], ui.fitUniform) -> null
            else -> "Nothing to fit: draw something first, or pick another outline (Alpha / Dark / Light)."
        }
        bm?.recycle()
    }
    ChipRow {
        Text("Fit to image", style = MaterialTheme.typography.labelMedium)
        for (f in FitShape.values()) FilterChip(ui.fitShape == f.ordinal, { ui.fitShape = f.ordinal }, { Text(f.label) })
        FilterChip(ui.fitUniform, { ui.fitUniform = !ui.fitUniform }, { Text("Keep aspect") })
        TextButton(onClick = { pick.launch("image/*") }) { Text("Pick image") }
    }
    Hint(msg ?: "Alpha = the visible part of the picture. Dark / Light = the dark or light pixels (for pictures without transparency).")
}
