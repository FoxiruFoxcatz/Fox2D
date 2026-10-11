package fox.foxiru.foxcat.fox2d.main_canvas

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fox.foxiru.foxcat.fox2d.jnicallers.NativeCanvas
import kotlin.math.roundToInt

// ======================================================================================================
// Bone / Deform / Puppet Warp panel, Blender-style: ONE slim bar (title, mode tabs, box menus, undo / redo / fold / Done), then a
// small body = a strip to pick the bone / pin / curve + the sliders that are keyframeable. Everything that is an option or a
// one-shot action lives in the box menus of the bar, so the panel takes ~110 - 165 dp instead of ~260 dp.
//   Build = lay out bones / curves / pins on the untouched picture, Pose = move them (keyframes), Weights = skin brush (Mesh method).
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
    LaunchedEffect(track, rig.bones.size, rig.curves.size, rig.pins.size, rig.method, state.tool) { state.normalizeRigSel() }

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

    val meshBones = isBone && rig.method == RigMethod.Mesh   // weights / influence / stretch exist only for the Mesh method
    val mode = if (!meshBones && ui.mode == RigMode.Paint) RigMode.Build else ui.mode
    val pose = if (rig.isEmpty) null else state.rigPose(track)

    // hoisted: the picture picker and the messages live here so they survive a box menu closing
    val ctx = LocalContext.current
    var fitMsg by remember { mutableStateOf<String?>(null) }
    var meshMsg by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bm: Bitmap? = decodeFitBitmap(ctx, uri)
        fitMsg = when {
            bm == null -> "Could not read that picture."
            state.fitToImage(track, bm, FitShape.values()[ui.fitShape.coerceIn(0, 2)], ui.fitUniform) -> null
            else -> "Nothing to fit: draw something first, or pick another outline (Alpha / Dark / Light)."
        }
        bm?.recycle()
    }

    Column(Modifier.fillMaxWidth()) {
        // ---------------------------------------------------------------- bar
        Row(
            Modifier.fillMaxWidth().height(34.dp).background(cs.surfaceContainerHigh).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                if (isBone) "Bones" else if (isWarp) "Warp" else "Deform",
                Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.titleSmall, color = cs.primary, maxLines = 1,
            )
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                FxTab("Build", mode == RigMode.Build) { ui.mode = RigMode.Build }
                FxTab("Pose", mode == RigMode.Pose) { ui.mode = RigMode.Pose }
                if (meshBones) FxTab("Weights", mode == RigMode.Paint) { ui.mode = RigMode.Paint }
                Spacer(Modifier.width(6.dp))
                when {
                    isBone -> {
                        if (mode == RigMode.Build) BoneMenu(state, track, rig)
                        if (mode == RigMode.Build && rig.method == RigMethod.Mesh) SkinMenu(state, rig, ::param, ::end)
                        if (mode == RigMode.Pose) PoseMenu(state, track, rig)
                        if (mode == RigMode.Paint) BrushMenu(state, track, rig, ::end)
                    }
                    isWarp -> {
                        if (mode == RigMode.Build) WarpMenu(state, track, rig, meshMsg, { meshMsg = it }, fitMsg) { pick.launch("image/*") }
                        PinMenu(state, track, rig, mode, ::param, ::end)
                    }
                    else -> {
                        if (mode == RigMode.Build) CurveMenu(state, track, rig, ::param, ::end) else AutoKeyMenu(state)
                    }
                }
                RigMenu(state, track, rig, mode, isBone)
            }
            FxTBtn("↶", enabled = state.canUndo) { state.undo() }
            FxTBtn("↷", enabled = state.canRedo) { state.redo() }
            FxTBtn(if (ui.collapsed) "▴" else "▾") { ui.collapsed = !ui.collapsed }
            FxTab("Done") { state.kfOpen = null; state.tool = Tool.Brush }
        }

        // ---------------------------------------------------------------- body (folded: the whole screen is canvas)
        if (!ui.collapsed) Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .heightIn(max = if (mode == RigMode.Pose) 132.dp else 84.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            when {
                isBone && mode == RigMode.Build -> BoneBuildBody(state, rig)
                isBone && mode == RigMode.Pose -> BonePoseBody(state, track, rig, pose)
                isBone -> BonePaintBody(state, rig)
                isWarp && mode == RigMode.Build -> PinBuildBody(state, rig)
                isWarp -> PinPoseBody(state, track, rig, pose)
                mode == RigMode.Build -> CurveBuildBody(state, track, rig)
                else -> CurvePoseBody(state, track, rig, pose)
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ body pieces

@Composable
private fun Strip(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}

/** One compact line: label | slider | value | key diamond. Tap the label to reset to [neutral], the value is read-only. */
@Composable
private fun RigRow(
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
        Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                Modifier.width(84.dp).clickable(enabled = neutral != null) { if (neutral != null) { onChange(neutral); onDone() } },
                style = MaterialTheme.typography.labelMedium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Slider(value.coerceIn(range), onChange, onValueChangeFinished = onDone, valueRange = range, modifier = Modifier.weight(1f).height(28.dp))
            Text(text, Modifier.widthIn(min = 44.dp).padding(horizontal = 4.dp), style = MaterialTheme.typography.labelMedium, color = cs.primary, textAlign = TextAlign.End, maxLines = 1)
            if (prop != null) KeyIcon(state, prop, enabled = !state.activeLocked) else Spacer(Modifier.width(32.dp))
        }
        if (prop != null) KeyDrawer(state, prop)
    }
}

/** Slider bound to a keyed rig channel; edits go through [EditorState.setRigChan] (auto-key). [label] is short, [full] is the name used by the KeyGraph. */
@Composable
private fun ChanRow(
    state: EditorState, track: Int, chan: Int, label: String, full: String, pose: RigPose?, range: ClosedFloatingPointRange<Float>,
    neutral: Float?, fmt: (Float) -> String, begin: () -> Unit, end: () -> Unit,
) {
    val v = pose?.value(chan) ?: state.trackRig(track).baseOf(chan)
    RigRow(
        state, PropRef.RigChannel(track, chan, full), label, v, range, fmt(v), neutral,
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
private fun Hint1(t: String) = FxHint(t)

// ------------------------------------------------------------------------------------------------ bones: body

@Composable
private fun BoneStrip(state: EditorState, rig: Rig) {
    val ui = state.rigUi
    Strip {
        rig.bones.forEachIndexed { i, b ->
            FxChip((if (b.parent >= 0) "↳ " else "") + b.name, selected = ui.bone == i, height = 26.dp) { ui.bone = i }
        }
    }
}

@Composable
private fun BoneBuildBody(state: EditorState, rig: Rig) {
    val ui = state.rigUi
    if (rig.bones.isEmpty()) { Hint1("Drag on the canvas to draw the first bone."); return }
    BoneStrip(state, rig)
    Hint1(
        if (ui.boneTool == BoneTool.Draw) "Drag = new bone. Start on a bone's tip or body to chain a child. Tap a bone to select."
        else "Drag a joint (head / tip) or a bone body. A parent's tip drags its connected children."
    )
}

@Composable
private fun BonePoseBody(state: EditorState, track: Int, rig: Rig, pose: RigPose?) {
    val ui = state.rigUi
    val (begin, end) = chanBegin(state)
    if (rig.bones.isEmpty()) { Hint1("Build bones first."); return }
    BoneStrip(state, rig)
    val i = ui.bone
    val b = rig.bones.getOrNull(i) ?: return
    fun ch(c: BoneChan) = RigChan.bone(i, c)
    fun full(c: BoneChan) = "${b.name} · ${c.label}"
    ChanRow(state, track, ch(BoneChan.MoveX), "Position X", full(BoneChan.MoveX), pose, -0.5f..0.5f, 0f, ::pct, begin, end)
    ChanRow(state, track, ch(BoneChan.MoveY), "Position Y", full(BoneChan.MoveY), pose, -0.5f..0.5f, 0f, ::pct, begin, end)
    ChanRow(state, track, ch(BoneChan.Rotate), "Rotation", full(BoneChan.Rotate), pose, -360f..360f, 0f, ::deg, begin, end)
    if (rig.method == RigMethod.Mesh) {
        ChanRow(state, track, ch(BoneChan.Length), "Length", full(BoneChan.Length), pose, 0.2f..3f, 1f, ::pct, begin, end)
        ChanRow(state, track, ch(BoneChan.Thick), "Thickness", full(BoneChan.Thick), pose, 0.2f..3f, 1f, ::pct, begin, end)
    }
    if (b.ikChain > 0) {
        ChanRow(state, track, ch(BoneChan.IkX), "IK target X", full(BoneChan.IkX), pose, -0.5f..1.5f, null, ::pct, begin, end)
        ChanRow(state, track, ch(BoneChan.IkY), "IK target Y", full(BoneChan.IkY), pose, -0.5f..1.5f, null, ::pct, begin, end)
        ChanRow(state, track, ch(BoneChan.IkMix), "IK mix", full(BoneChan.IkMix), pose, 0f..1f, 1f, ::pct, begin, end)
    }
}

@Composable
private fun BonePaintBody(state: EditorState, rig: Rig) {
    if (rig.bones.isEmpty()) { Hint1("Build bones first."); return }
    BoneStrip(state, rig)
    Hint1("Paint the part of the picture the selected bone moves. Brush options are in the Brush menu.")
}

// ------------------------------------------------------------------------------------------------ bones: bar menus

@Composable
private fun BoneMenu(state: EditorState, track: Int, rig: Rig) {
    val ui = state.rigUi
    FxMenu("Bone") { close ->
        FxSection("Tool")
        FxGrid(BoneTool.values().toList(), 2) { t, m ->
            FxChip(if (t == BoneTool.Draw) "Draw bone" else "Move joints", m, selected = ui.boneTool == t) { ui.boneTool = t }
        }
        val i = ui.bone
        val b = rig.bones.getOrNull(i)
        if (b == null) { FxHint("Draw a bone on the canvas first."); return@FxMenu }
        FxSection("Parent of ${b.name}")
        FxGrid(listOf(-1) + (0 until i).toList(), 3) { p, m ->
            FxChip(if (p < 0) "None" else rig.bones[p].name, m, selected = b.parent == p) { state.edit { state.setRig(track) { it.withParent(i, p) } } }
        }
        FxSection("IK chain")
        FxGrid(listOf(0, 2, 3, 4), 4) { n, m -> FxChip(if (n == 0) "Off" else "$n", m, selected = b.ikChain == n) { state.setIkChain(track, i, n) } }
        if (b.ikChain > 0) FxCheck("Bend the other way", b.ikBend < 0f) { on ->
            state.edit { state.setRig(track) { it.withBone(i) { x -> x.copy(ikBend = if (on) -1f else 1f) } } }
        }
        FxAction("Delete ${b.name}") {
            state.edit {
                val old = state.trackRig(track)
                state.setRig(track) { it.removeBone(i) }
                state.fixAttachAfterRemove(track, old, i)
            }
            ui.bone = (i - 1).coerceAtLeast(0)
            close()
        }
    }
}

/** Mesh method only: how far and how strongly the selected bone skins the picture. */
@Composable
private fun SkinMenu(state: EditorState, rig: Rig, param: ((Rig) -> Rig) -> Unit, end: () -> Unit) {
    val i = state.rigUi.bone
    val b = rig.bones.getOrNull(i)
    FxMenu("Skin", enabled = b != null) {
        if (b == null) return@FxMenu
        FxSection("${b.name} · influence")
        FxSlider("Radius", b.radius, 0.02f..0.6f, pct(b.radius), { v -> param { it.withBone(i) { x -> x.copy(radius = v) } } }, end)
        FxSlider("Falloff", b.falloff, 0.5f..6f, "%.1f".format(b.falloff), { v -> param { it.withBone(i) { x -> x.copy(falloff = v) } } }, end)
        FxSlider("Strength", b.strength, 0.1f..2f, pct(b.strength), { v -> param { it.withBone(i) { x -> x.copy(strength = v) } } }, end)
    }
}

@Composable
private fun PoseMenu(state: EditorState, track: Int, rig: Rig) {
    val ui = state.rigUi
    FxMenu("Pose") { close ->
        FxSection("Drag gesture")
        FxGrid(PoseTool.values().filter { it != PoseTool.Scale || rig.method == RigMethod.Mesh }, 4) { t, m ->
            FxChip(if (t == PoseTool.Auto) "Auto" else t.name, m, selected = ui.poseTool == t) { ui.poseTool = t }
        }
        FxHint(
            when (ui.poseTool) {
                PoseTool.Auto -> "Joint = move, tip or body = rotate. IK bones: drag the gold target."
                PoseTool.Move -> "Drag anywhere: moves the selected bone."
                PoseTool.Rotate -> "Drag anywhere: turns the selected bone around its head."
                PoseTool.Scale -> "Drag anywhere: stretches the selected bone."
            }
        )
        FxCheck("Auto-key", ui.autoKey) { ui.autoKey = it }
        FxSection("Keys / reset")
        FxGrid(listOf(0, 1, 2), 3) { i, m ->
            when (i) {
                0 -> FxChip("Key bone", m, enabled = ui.bone in rig.bones.indices) { state.keyBone(track, ui.bone); close() }
                1 -> FxChip("Reset bone", m, enabled = ui.bone in rig.bones.indices) { state.resetBonePose(track, ui.bone); close() }
                else -> FxChip("Reset pose", m) { state.resetRigPose(track); close() }
            }
        }
    }
}

@Composable
private fun BrushMenu(state: EditorState, track: Int, rig: Rig, end: () -> Unit) {
    val ui = state.rigUi
    FxMenu("Brush") { close ->
        FxSection("Mode")
        FxGrid(listOf(false, true), 2) { er, m -> FxChip(if (er) "Erase" else "Add", m, selected = ui.erase == er) { ui.erase = er } }
        FxCheck("Heat map", ui.showWeights) { ui.showWeights = it }
        FxSlider("Size", ui.brushRadius, 0.01f..0.3f, pct(ui.brushRadius), { ui.brushRadius = it }, end)
        FxSlider("Strength", ui.brushStrength, 0.05f..1f, pct(ui.brushStrength), { ui.brushStrength = it }, end)
        FxAction("Reset to auto weights", enabled = rig.paint != null) {
            state.edit { state.setRig(track) { it.copy(paint = null) } }
            close()
        }
    }
}

// ------------------------------------------------------------------------------------------------ puppet warp

@Composable
private fun PinStrip(state: EditorState, rig: Rig) {
    val ui = state.rigUi
    Strip {
        rig.pins.forEachIndexed { i, _ ->
            val keyed = rig.keys.any { it.chan == RigChan.pin(i, 0) || it.chan == RigChan.pin(i, 1) || it.chan == RigChan.pinRot(i) }
            FxChip("Pin ${i + 1}" + if (keyed) " ◆" else "", selected = ui.pin == i, height = 26.dp) { ui.pin = i }
        }
    }
}

@Composable
private fun PinBuildBody(state: EditorState, rig: Rig) {
    if (rig.pins.isEmpty()) { Hint1("Tap the picture to drop a pin, or use Warp > Auto pins."); return }
    PinStrip(state, rig)
    Hint1("${rig.pins.size}/${RigLimits.MAX_PINS} pins · drag a pin to move it · tap empty paper for another. Options: Warp menu.")
}

@Composable
private fun PinPoseBody(state: EditorState, track: Int, rig: Rig, pose: RigPose?) {
    val ui = state.rigUi
    val (begin, end) = chanBegin(state)
    if (rig.pins.isEmpty()) { Hint1("Drop pins in Build first."); return }
    if (rig.cutOut && rig.outline == null) Hint1("Cut-out waits for the outline mesh (e.g. after reopening): Build > Warp > Generate.")
    PinStrip(state, rig)
    val i = ui.pin
    if (i !in rig.pins.indices) return
    ChanRow(state, track, RigChan.pin(i, 0), "Offset X", "Pin ${i + 1} · Offset X", pose, -0.5f..0.5f, 0f, ::pct, begin, end)
    ChanRow(state, track, RigChan.pin(i, 1), "Offset Y", "Pin ${i + 1} · Offset Y", pose, -0.5f..0.5f, 0f, ::pct, begin, end)
    ChanRow(state, track, RigChan.pinRot(i), "Rotation", "Pin ${i + 1} · Rotation", pose, -360f..360f, 0f, ::deg, begin, end)
}

@Composable
private fun WarpMenu(
    state: EditorState, track: Int, rig: Rig,
    meshMsg: String?, setMeshMsg: (String?) -> Unit, fitMsg: String?, pickImage: () -> Unit,
) {
    val ui = state.rigUi
    FxMenu("Warp") { close ->
        FxSection("Reach")
        FxGrid(listOf(1 to "Wide", 2 to "Medium", 3 to "Tight"), 3) { (f, n), m -> FxChip(n, m, selected = rig.warpFalloff == f) { state.setWarpFalloff(track, f) } }
        FxSection("Solver")
        FxGrid(listOf(0, 1, 2), 3) { i, m ->
            when (i) {
                0 -> FxChip("ARAP", m, selected = rig.warpSolver == 1) { state.setWarpSolver(track, 1) }
                1 -> FxChip("MLS", m, selected = rig.warpSolver == 0) { state.setWarpSolver(track, 0) }
                else -> FxChip("Cut-out", m, selected = rig.cutOut) { state.setWarpCutOut(track, !rig.cutOut) }
            }
        }
        if (rig.cutOut) FxHint(
            if (rig.outline == null) "Cut-out needs the outline mesh: tap Generate below."
            else "The picture is cut along the outline mesh and only that is bent."
        )
        FxSection("Outline mesh")
        FxGrid(listOf("Coarse", "Medium", "Fine"), 3) { n, m -> val i = listOf("Coarse", "Medium", "Fine").indexOf(n); FxChip(n, m, selected = ui.triDensity == i) { ui.triDensity = i } }
        val tm = ui.tri[track]
        FxGrid(listOf(0, 1), 2) { i, m ->
            if (i == 0) FxChip("Generate", m) { setMeshMsg(if (state.generateOutlineMesh(track)) null else "Draw something first: the mesh is built around your drawing.") }
            else FxChip("Clear", m, enabled = tm != null) { state.clearOutlineMesh(track); setMeshMsg(null) }
        }
        FxHint(meshMsg ?: if (tm != null) "${tm.vertexCount} points, ${tm.triangleCount} triangles." else "Triangles only where your drawing is, instead of a square grid.")
        FxSection("Fit to image")
        FxGrid(FitShape.values().toList(), 3) { f, m -> FxChip(f.label, m, selected = ui.fitShape == f.ordinal) { ui.fitShape = f.ordinal } }
        FxGrid(listOf(0, 1), 2) { i, m ->
            if (i == 0) FxChip("Keep aspect", m, selected = ui.fitUniform) { ui.fitUniform = !ui.fitUniform }
            else FxChip("Pick image", m) { pickImage() }
        }
        FxHint(fitMsg ?: "Alpha = visible part of the picture. Dark / Light = dark or light pixels (no transparency).")
        FxSection("Auto pins")
        FxGrid(listOf(6, 10, 16, RigLimits.MAX_PINS), 4) { n, m -> FxChip("$n", m) { state.autoPins(track, n); close() } }
    }
}

@Composable
private fun PinMenu(state: EditorState, track: Int, rig: Rig, mode: RigMode, param: ((Rig) -> Rig) -> Unit, end: () -> Unit) {
    val ui = state.rigUi
    FxMenu("Pins", enabled = rig.pins.isNotEmpty()) { close ->
        if (mode == RigMode.Pose) {
            FxCheck("Auto-key", ui.autoKey) { ui.autoKey = it }
            FxGrid(listOf(0, 1), 2) { i, m ->
                if (i == 0) FxChip("Reset pin", m, enabled = ui.pin in rig.pins.indices) { state.resetPins(track, ui.pin); close() }
                else FxChip("Reset all", m) { state.resetPins(track); close() }
            }
        } else {
            val sel = ui.pin
            if (sel in rig.pins.indices) {
                FxSection("Pin ${sel + 1}")
                val st = rig.pins[sel].stiff
                FxSlider("Influence", st, 0.2f..4f, "%.1f×".format(st), { v -> param { it.withPinStiff(sel, v) } }, end)
            }
            FxGrid(listOf(0, 1, 2), 3) { i, m ->
                when (i) {
                    0 -> FxChip("Delete pin", m, enabled = sel in rig.pins.indices) { state.removeWarpPin(track, sel); close() }
                    1 -> FxChip("Clear pins", m) { state.clearWarpPins(track); close() }
                    else -> FxChip("Fit mesh", m) { state.fitMeshToDrawing(); close() }
                }
            }
            FxHint("A pin you never move still works as an anchor. Influence: how far a pin's pull reaches.")
        }
    }
}

// ------------------------------------------------------------------------------------------------ deform curves

@Composable
private fun CurveStrip(state: EditorState, track: Int, rig: Rig, add: Boolean) {
    val ui = state.rigUi
    Strip {
        rig.curves.forEachIndexed { k, c -> FxChip(c.name, selected = ui.curve == k, height = 26.dp) { ui.curve = k; ui.ctl = -1 } }
        if (add) FxChip("+ Curve", enabled = rig.curves.size < RigLimits.MAX_CURVES, height = 26.dp) {
            state.edit { state.setRig(track) { it.addCurve(emptyList()) } }
            ui.curve = state.trackRig(track).curves.size - 1; ui.ctl = -1
        }
    }
}

@Composable
private fun CurveBuildBody(state: EditorState, track: Int, rig: Rig) {
    val ui = state.rigUi
    CurveStrip(state, track, rig, true)
    val c = rig.curves.getOrNull(ui.curve)
    if (c == null) { Hint1("Tap the canvas to add control points along the part you want to bend."); return }
    if (ui.ctl in c.pts.indices) {
        val p = c.pts[ui.ctl]
        Hint1("Point ${ui.ctl + 1}/${c.pts.size} · " + (if (p.bone >= 0) "bound to ${rig.bones.getOrNull(p.bone)?.name ?: "?"}" else "free") + " · drag to move")
    } else Hint1("${c.pts.size} point${if (c.pts.size == 1) "" else "s"} · tap the canvas to add · filled = follows a bone, hollow = free")
}

@Composable
private fun CurvePoseBody(state: EditorState, track: Int, rig: Rig, pose: RigPose?) {
    val ui = state.rigUi
    val (begin, end) = chanBegin(state)
    if (rig.curves.isEmpty()) { Hint1("Create a curve in Build first."); return }
    CurveStrip(state, track, rig, false)
    val k = ui.curve
    val c = rig.curves.getOrNull(k) ?: return
    ChanRow(state, track, RigChan.curveMix(k), "Deform", "${c.name} · Deform amount", pose, 0f..1f, 1f, ::pct, begin, end)
    val i = ui.ctl
    if (i in c.pts.indices) {
        val n = "${c.name} P${i + 1}"
        ChanRow(state, track, RigChan.ctl(k, i, 0), "P${i + 1} Offset X", "$n · Offset X", pose, -0.4f..0.4f, 0f, ::pct, begin, end)
        ChanRow(state, track, RigChan.ctl(k, i, 1), "P${i + 1} Offset Y", "$n · Offset Y", pose, -0.4f..0.4f, 0f, ::pct, begin, end)
        ChanRow(state, track, RigChan.ctl(k, i, 2), "P${i + 1} Thick", "$n · Thickness", pose, 0.1f..3f, 1f, ::pct, begin, end)
    } else Hint1("Tap a control point to pose it.")
}

@Composable
private fun CurveMenu(state: EditorState, track: Int, rig: Rig, param: ((Rig) -> Rig) -> Unit, end: () -> Unit) {
    val ui = state.rigUi
    val k = ui.curve
    val c = rig.curves.getOrNull(k)
    FxMenu("Curve", enabled = c != null) { close ->
        if (c == null) return@FxMenu
        val hasPt = ui.ctl in c.pts.indices
        FxSection("Point")
        FxGrid(listOf(0, 1, 2), 3) { i, m ->
            when (i) {
                0 -> FxChip("Delete", m, enabled = hasPt) {
                    state.edit { state.setRig(track) { it.removeCtl(k, ui.ctl) } }
                    ui.ctl = (ui.ctl - 1).coerceAtLeast(-1)
                    close()
                }
                1 -> FxChip("Bind bone", m, enabled = hasPt && ui.bone in rig.bones.indices) {
                    val bone = ui.bone
                    state.edit { state.setRig(track) { it.withCtl(k, ui.ctl) { p -> p.copy(bone = bone) } } }
                    close()
                }
                else -> FxChip("Free", m, enabled = hasPt && c.pts[ui.ctl].bone >= 0) {
                    state.edit { state.setRig(track) { it.withCtl(k, ui.ctl) { p -> p.copy(bone = -1) } } }
                    close()
                }
            }
        }
        FxSection("${c.name} · shape")
        FxSlider("Reach", c.reach, 0.01f..0.5f, pct(c.reach), { v -> param { it.withCurve(k) { x -> x.copy(reach = v) } } }, end)
        FxSlider("Softness", c.softness, 0f..1f, pct(c.softness), { v -> param { it.withCurve(k) { x -> x.copy(softness = v) } } }, end)
        FxAction("Delete curve") {
            state.edit { state.setRig(track) { it.removeCurve(k) } }
            ui.curve = (k - 1).coerceAtLeast(0); ui.ctl = -1
            close()
        }
    }
}

@Composable
private fun AutoKeyMenu(state: EditorState) {
    FxMenu("Pose") { FxCheck("Auto-key", state.rigUi.autoKey) { state.rigUi.autoKey = it } }
}

// ------------------------------------------------------------------------------------------------ common "Rig" menu

/** Method (bones), what is drawn, mesh grid and attaching this row to a bone of another row. */
@Composable
private fun RigMenu(state: EditorState, track: Int, rig: Rig, mode: RigMode, isBone: Boolean) {
    val ui = state.rigUi
    val isDeform = state.tool == Tool.Deform
    val transform = isBone && rig.method == RigMethod.Transform
    FxMenu("Rig") { close ->
        if (isBone) {
            FxSection("Method")
            FxGrid(RigMethod.values().toList(), 2) { mth, m -> FxChip(mth.label, m, selected = rig.method == mth) { state.setRigMethod(track, mth) } }
            FxHint(if (transform) "Bones move + turn the layers attached to them (position, rotation)." else "Bones bend the picture with skin weights.")
        }
        if (isBone || isDeform) FxCheck("Show rigs of other tracks", ui.showAll) { ui.showAll = it }

        if (!rig.isEmpty && !transform) {
            FxSection("Mesh grid")
            FxGrid(RigLimits.GRIDS, 5) { g, m -> FxChip("$g", m, selected = rig.grid == g) { state.setMeshGrid(track, g) } }
            FxCheck("Show mesh lines", ui.meshLines) { ui.meshLines = it }
        }

        // attach: building, or any time for the Transform method (attaching layers IS how it moves things)
        if (mode != RigMode.Pose || transform) {
            val cur = state.drawTracks.firstOrNull { it.id == track }?.attach
            val cands = state.attachCandidates(track)
            if (cands.isNotEmpty() || cur != null) {
                FxSection("Attach this layer to")
                FxGrid(listOf<DrawTrack?>(null) + cands, 3) { c, m ->
                    FxChip(c?.name ?: "Nothing", m, selected = if (c == null) cur == null else cur?.track == c.id) {
                        state.setAttach(track, if (c == null) null else BoneAttach(c.id, 0))
                    }
                }
                val parent = cands.firstOrNull { it.id == cur?.track }
                if (parent != null && cur != null) {
                    FxSection("Bone of ${parent.name}")
                    FxGrid(parent.rig.bones.indices.toList(), 3) { i, m ->
                        FxChip(parent.rig.bones[i].name, m, selected = cur.bone == i) { state.setAttach(track, BoneAttach(parent.id, i)) }
                    }
                    FxHint("Follows the bone: move, turn" + (if (transform) "" else " and stretch") + " included. Shown unattached while building.")
                }
            }
        }
        if (isBone) FxAction("Clear rig", enabled = !rig.isEmpty) { state.clearRig(track); close() }
    }
}
