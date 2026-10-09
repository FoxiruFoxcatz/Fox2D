package fox.foxiru.foxcat.fox2d.gallery

import android.app.Application
import android.graphics.BitmapFactory
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import fox.foxiru.foxcat.fox2d.FoxiruItem
import fox.foxiru.foxcat.fox2d.FoxiruReorderList
import fox.foxiru.foxcat.fox2d.main_canvas.FoxIcon
import fox.foxiru.foxcat.fox2d.main_canvas.Ico
import fox.foxiru.foxcat.fox2d.project.ProjectMeta
import fox.foxiru.foxcat.fox2d.project.ProjectRepository
import fox.foxiru.foxcat.fox2d.project.StorageLocations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/** Material "emphasized decelerate": fast start, long soft landing. Used for every entrance. */
private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val RowShape = RoundedCornerShape(28.dp)

// ================================================================================================ view model

class GalleryViewModel(app: Application) : AndroidViewModel(app) {
    val locations = StorageLocations.get(app)
    val repo = ProjectRepository.get(app)
    var projects by mutableStateOf<List<ProjectMeta>>(emptyList())
        private set
    var loaded by mutableStateOf(false)
        private set

    fun refresh() = viewModelScope.launch { projects = repo.list(); loaded = true }

    fun create(name: String, fps: Int, onCreated: (String) -> Unit) = viewModelScope.launch {
        onCreated(repo.create(name, fps).id)
    }

    /** Live while dragging: only the in-memory list changes. */
    fun move(from: Int, to: Int) {
        if (from == to || from !in projects.indices || to !in projects.indices) return
        projects = projects.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** On release: write the order to disk. */
    fun commitOrder() = viewModelScope.launch { repo.saveOrder(projects.map { it.id }) }

    fun rename(id: String, name: String) = viewModelScope.launch { repo.rename(id, name); refresh() }
    fun duplicate(id: String) = viewModelScope.launch { repo.duplicate(id); refresh() }
    fun delete(id: String) = viewModelScope.launch { repo.delete(id); refresh() }
}

// ================================================================================================ screen

@Composable
fun GalleryScreen(
    onOpen: (String) -> Unit,
    onDevTools: () -> Unit,
    modifier: Modifier = Modifier,
    vm: GalleryViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { vm.refresh() } // runs again every time the gallery comes back from the editor
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState() // hoisted: scroll position survives the trip to Settings
    BackHandler(settingsOpen) { settingsOpen = false }

    // shared-axis slide: the incoming screen travels a quarter of the width, the outgoing one drifts the other way
    AnimatedContent(
        targetState = settingsOpen,
        modifier = modifier.fillMaxSize(),
        transitionSpec = {
            val slide = tween<IntOffset>(460, easing = Emphasized)
            if (targetState) {
                (slideInHorizontally(slide) { it / 4 } + fadeIn(tween(340, 90))) togetherWith
                    (slideOutHorizontally(slide) { -it / 6 } + fadeOut(tween(200)) + scaleOut(tween(460, easing = Emphasized), 0.96f))
            } else {
                (slideInHorizontally(slide) { -it / 6 } + fadeIn(tween(340, 90)) + scaleIn(tween(460, easing = Emphasized), 0.96f)) togetherWith
                    (slideOutHorizontally(slide) { it / 4 } + fadeOut(tween(200)))
            }.using(SizeTransform(clip = false))
        },
        label = "gallery-settings",
    ) { showSettings ->
        if (showSettings) {
            StorageSettingsScreen(
                locations = vm.locations,
                repo = vm.repo,
                onBack = { settingsOpen = false },
                onChanged = { vm.refresh() },
            )
        } else {
            GalleryContent(vm, listState, onOpen, onDevTools, onSettings = { settingsOpen = true })
        }
    }
}

@Composable
private fun GalleryContent(
    vm: GalleryViewModel,
    listState: LazyListState,
    onOpen: (String) -> Unit,
    onDevTools: () -> Unit,
    onSettings: () -> Unit,
) {
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ProjectMeta?>(null) }
    var deleting by remember { mutableStateOf<ProjectMeta?>(null) }

    // rows that exist when the list first fills in cascade; rows added later use the list's own insert animation
    var intro by remember { mutableStateOf(true) }
    LaunchedEffect(vm.loaded) { if (vm.loaded) { delay(1000); intro = false } }

    // null = animation switched off in BackgroundPrefs; resolve() reads observable state, so a change recomposes here
    val season = BackgroundPrefs.get(LocalContext.current).resolve()

    // the backdrop paints edge to edge (surface colour underneath); the insets padding stays on the content
    SeasonalBackdrop(season, Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        ConstraintLayout(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            val (title, count, actions, list, empty, fab) = createRefs()

            AnimatedTitle(
                "Fox2D",
                Modifier.constrainAs(title) {
                    top.linkTo(parent.top, 12.dp)
                    start.linkTo(parent.start)
                    end.linkTo(parent.end)
                },
            )

            val countLabel = when {
                !vm.loaded -> ""
                vm.projects.isEmpty() -> "No projects yet"
                vm.projects.size == 1 -> "1 project"
                else -> "${vm.projects.size} projects · hold and drag to reorder"
            }
            AnimatedContent(
                targetState = countLabel,
                modifier = Modifier.constrainAs(count) {
                    top.linkTo(title.bottom)
                    start.linkTo(parent.start)
                    end.linkTo(parent.end)
                },
                transitionSpec = { fadeIn(tween(260, 60)) togetherWith fadeOut(tween(140)) },
                label = "count",
            ) { label ->
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(
                Modifier.constrainAs(actions) {
                    top.linkTo(parent.top, 8.dp)
                    end.linkTo(parent.end, 8.dp)
                },
            ) {
                IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                IconButton(onClick = onDevTools) { FoxIcon(Ico.More) }
            }

            Box(
                Modifier.constrainAs(list) {
                    top.linkTo(count.bottom, 12.dp)
                    bottom.linkTo(parent.bottom)
                    start.linkTo(parent.start)
                    end.linkTo(parent.end)
                    width = Dimension.fillToConstraints
                    height = Dimension.fillToConstraints
                },
                contentAlignment = Alignment.TopCenter,
            ) {
                FoxiruReorderList(
                    items = vm.projects,
                    key = { it.id },
                    onMove = { from, to -> vm.move(from, to) },
                    onDragFinished = { vm.commitOrder() },
                    modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 104.dp),
                    spacing = 12.dp,
                    liftShape = RowShape,
                ) { p, index ->
                    ProjectRow(
                        meta = p, repo = vm.repo, index = index, intro = intro,
                        onOpen = { onOpen(p.id) },
                        onRename = { renaming = p },
                        onDuplicate = { vm.duplicate(p.id) },
                        onDelete = { deleting = p },
                    )
                }
            }

            AnimatedVisibility(
                visible = vm.loaded && vm.projects.isEmpty(),
                modifier = Modifier.constrainAs(empty) {
                    top.linkTo(list.top)
                    bottom.linkTo(list.bottom)
                    start.linkTo(parent.start)
                    end.linkTo(parent.end)
                },
                enter = fadeIn(tween(420, 120)) + scaleIn(tween(420, 120, Emphasized), 0.92f),
                exit = fadeOut(tween(150)),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Nothing here yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Tap Create project to start drawing",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            ExtendedFloatingActionButton(
                onClick = { creating = true },
                expanded = fabExpanded,
                icon = { FoxIcon(Ico.Plus) },
                text = { Text("Create project") },
                modifier = Modifier.constrainAs(fab) {
                    end.linkTo(parent.end, 20.dp)
                    bottom.linkTo(parent.bottom, 20.dp)
                },
            )
        }
    }

    if (creating) {
        ProjectDialog(
            title = "Create project", confirm = "Create",
            initialName = "Project ${vm.projects.size + 1}", showFps = true,
            onDismiss = { creating = false },
            onConfirm = { name, fps -> creating = false; vm.create(name, fps, onOpen) },
        )
    }
    renaming?.let { p ->
        ProjectDialog(
            title = "Rename project", confirm = "Rename", initialName = p.name, showFps = false,
            onDismiss = { renaming = null },
            onConfirm = { name, _ -> renaming = null; vm.rename(p.id, name) },
        )
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${p.name}\"?") },
            text = { Text("The drawings and audio of this project will be removed from this device.") },
            confirmButton = { TextButton({ deleting = null; vm.delete(p.id) }) { Text("Delete") } },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } },
        )
    }
}

// ================================================================================================ row (FoxiruItem)

@Composable
private fun ProjectRow(
    meta: ProjectMeta,
    repo: ProjectRepository,
    index: Int,
    intro: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }

    // entrance: rise + fade + settle, staggered by row (only while the list is first filling in)
    val enter = remember { Animatable(if (intro) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (enter.value < 1f) {
            delay(index.coerceAtMost(8) * 55L)
            enter.animateTo(1f, tween(560, easing = Emphasized))
        }
    }

    FoxiruItem(
        title = meta.name,
        subtitle = "${meta.frames} frames · ${meta.fps} fps",
        value = DateUtils.getRelativeTimeSpanString(meta.modifiedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
        shape = RowShape,
        modifier = Modifier.graphicsLayer {
            val e = enter.value
            alpha = e
            translationY = (1f - e) * 40.dp.toPx()
            val s = 0.94f + 0.06f * e
            scaleX = s
            scaleY = s
        },
        leading = { Thumb(repo, meta, Modifier.size(76.dp).clip(RoundedCornerShape(18.dp))) },
        onClick = onOpen,
        trailing = { enabled ->
            Box {
                IconButton({ menu = true }, enabled = enabled) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Rename") }, { menu = false; onRename() }, leadingIcon = { Icon(Icons.Filled.Edit, null) })
                    DropdownMenuItem({ Text("Duplicate") }, { menu = false; onDuplicate() }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) })
                    DropdownMenuItem({ Text("Delete") }, { menu = false; onDelete() }, leadingIcon = { Icon(Icons.Filled.Delete, null) })
                }
            }
        },
    )
}

@Composable
private fun Thumb(repo: ProjectRepository, meta: ProjectMeta, modifier: Modifier) {
    val img by produceState<ImageBitmap?>(null, meta.id, meta.modifiedAt) {
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(repo.thumbFile(meta.id).path)?.asImageBitmap() }.getOrNull()
        }
    }
    // the paper is white; the drawing fades in over it once decoded
    val alpha by animateFloatAsState(if (img != null) 1f else 0f, tween(300), label = "thumb")
    Box(modifier.aspectRatio(1f).background(Color.White), contentAlignment = Alignment.Center) {
        img?.let { Image(it, null, Modifier.fillMaxSize().alpha(alpha), contentScale = ContentScale.Crop) }
    }
}

// ================================================================================================ animated title

/** Every effect is a pure function of progress t (0..1) per letter, see [computeFx]. */
private enum class TitleFx(val ms: Int, val stepMs: Int) {
    Hop(950, 70), Flip(900, 60), Spin(950, 55), Pop(750, 55),
    Scatter(1050, 45), Jelly(1050, 55), Drop(1050, 75), Glitch(750, 40),
}

/** Output of one effect for one letter. Reused every frame, so no allocation while animating. */
private class FxOut {
    var tx = 0f; var ty = 0f; var sx = 1f; var sy = 1f; var rz = 0f; var rx = 0f; var a = 1f
    fun reset() { tx = 0f; ty = 0f; sx = 1f; sy = 1f; rz = 0f; rx = 0f; a = 1f }
}

/**
 * @param r1 r2 random numbers in -1..1, new for every letter on every tap
 * @param pos   letter position across the word, -1 (first) .. 1 (last)
 * @param dp    pixels per dp
 * Amplitudes stay under ~26 dp: that is the room the title leaves inside its offscreen layer.
 */
private fun computeFx(o: FxOut, fx: TitleFx, t: Float, r1: Float, r2: Float, pos: Float, dp: Float) {
    val pi = PI.toFloat()
    when (fx) {
        TitleFx.Hop -> {
            val bounce = abs(sin(t * pi * 3f)) * (1f - t) * (1f - t)
            o.ty = -bounce * 26f * dp
            o.sy = 1f + 0.2f * bounce
            o.sx = 1f - 0.12f * bounce
        }
        TitleFx.Flip -> {
            val e = t * t * (3f - 2f * t)
            o.rx = 360f * e
            val s = 1f + 0.15f * sin(t * pi)
            o.sx = s; o.sy = s
        }
        TitleFx.Spin -> {
            val e = 1f - (1f - t) * (1f - t) * (1f - t)
            o.rz = 360f * e * (if (r1 >= 0f) 1f else -1f)
            val s = 1f + 0.25f * sin(t * pi)
            o.sx = s; o.sy = s
        }
        TitleFx.Pop -> {
            val s = 1f + 0.9f * sin(t * pi) * (1f - 0.4f * t)
            o.sx = s; o.sy = s
            o.rz = r1 * 18f * sin(t * pi)
        }
        TitleFx.Scatter -> {
            val out = sin(t * pi)
            val p = out * out * (3f - 2f * out)
            o.tx = (pos * 0.6f + r1 * 0.4f) * 30f * dp * p
            o.ty = r2 * 24f * dp * p
            o.rz = r1 * 200f * p
            o.a = 1f - 0.45f * p
        }
        TitleFx.Jelly -> {
            val d = 1f - t
            val w = sin(t * pi * 7f) * d * d
            o.sx = 1f + 0.35f * w
            o.sy = 1f - 0.3f * w
            o.rz = r1 * 14f * w
        }
        TitleFx.Drop -> {
            val e = 1f - exp(-6f * t) * cos(t * 14f)
            o.ty = -(1f - e) * 26f * dp
            o.a = (t * 5f).coerceIn(0f, 1f)
            val s = 0.5f + 0.5f * e
            o.sx = s; o.sy = s
            o.rz = (1f - e) * -30f * (if (r1 >= 0f) 1f else -1f)
        }
        TitleFx.Glitch -> {
            val d = 1f - t
            val tick = floor(t * 18f)
            val j = sin(tick * 12.9898f + r1 * 78.233f)
            o.tx = j * 9f * dp * d
            o.ty = cos(tick * 7.1f + r2 * 3f) * 5f * dp * d
            o.sx = 1f + j * 0.15f * d
            o.a = if (sin(tick * 5f + r2 * 9f) > 0.75f) 0.25f else 1f
        }
    }
}

/**
 * Letters drop in one after another (spring overshoot), then keep floating in a wave while a gradient
 * sweeps across the whole word. The gradient is one SrcIn pass over the row, so it is continuous across letters.
 *
 * TAP a letter: a random effect (never the same twice in a row) plays on every letter, rippling outward from the
 * letter you tapped. Each letter gets its own random numbers, so the same effect looks different every time.
 */
@Composable
fun AnimatedTitle(text: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val colors = listOf(cs.primary, cs.tertiary, cs.secondary, cs.primary) // first == last -> seamless repeat
    val inf = rememberInfiniteTransition(label = "title")
    val sweep by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "sweep")
    val wave by inf.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "wave")
    val letters = remember(text) { text.map { Animatable(0f) } }
    LaunchedEffect(text) {
        letters.forEachIndexed { i, a ->
            launch {
                delay(i * 90L)
                a.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessLow))
            }
        }
    }

    // tap effects
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val fxProgress = remember(text) { text.map { Animatable(1f) } } // 1 = idle
    val outs = remember(text) { text.map { FxOut() } }
    val rnd = remember(text) { FloatArray(text.length * 2) }
    val running = remember { arrayOfNulls<Job>(1) }
    var fx by remember { mutableStateOf(TitleFx.Hop) }

    fun play(from: Int) {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        val next = TitleFx.values().filter { it != fx }.random()
        fx = next
        for (k in rnd.indices) rnd[k] = Random.nextFloat() * 2f - 1f
        running[0]?.cancel()
        running[0] = scope.launch {
            text.indices.forEach { idx ->
                launch {
                    fxProgress[idx].snapTo(0f)
                    delay(abs(idx - from) * next.stepMs.toLong())
                    fxProgress[idx].animateTo(1f, tween(next.ms, easing = LinearEasing))
                }
            }
        }
    }

    Row(
        modifier
            // report less height than we draw: the padding below is only room for the effects, not layout space
            .layout { m, c ->
                val p = m.measure(c)
                val cut = 20.dp.roundToPx()
                layout(p.width, p.height - cut * 2) { p.place(0, -cut) }
            }
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val w = size.width.coerceAtLeast(1f)
                val x = sweep * w
                drawRect(
                    Brush.linearGradient(colors, start = Offset(x, 0f), end = Offset(x + w, size.height), tileMode = TileMode.Repeated),
                    blendMode = BlendMode.SrcIn,
                )
            }
            .padding(vertical = 34.dp, horizontal = 20.dp), // inside the layer: this is the room the effects draw in
        verticalAlignment = Alignment.CenterVertically,
    ) {
        text.forEachIndexed { i, ch ->
            if (ch == ' ') {
                Spacer(Modifier.width(16.dp))
            } else {
                val pos = if (text.length > 1) i / (text.length - 1f) * 2f - 1f else 0f
                Text(
                    ch.toString(),
                    Modifier
                        .pointerInput(text) { detectTapGestures { play(i) } } // before graphicsLayer: the hit box stays put while the letter moves
                        .graphicsLayer {
                            val p = letters[i].value
                            val o = outs[i]
                            o.reset()
                            val ft = fxProgress[i].value
                            if (ft < 1f) computeFx(o, fx, ft, rnd[i * 2], rnd[i * 2 + 1], pos, density)

                            val pc = p.coerceIn(0f, 1f)
                            alpha = pc * o.a
                            val s = 0.3f + 0.7f * p
                            scaleX = s * o.sx
                            scaleY = s * o.sy
                            rotationZ = (1f - p) * -25f + o.rz
                            rotationX = o.rx
                            cameraDistance = 14f * density
                            translationX = o.tx
                            translationY = (1f - p) * -90f + sin(wave + i * 0.7f) * 7f * pc + o.ty
                        },
                    color = Color.Black, // only the alpha matters: the gradient replaces the colour
                    fontSize = 58.sp,
                    fontWeight = FontWeight.Black,
                )
            }
        }
    }
}

// ================================================================================================ dialog

@Composable
private fun ProjectDialog(
    title: String, confirm: String, initialName: String, showFps: Boolean,
    onDismiss: () -> Unit, onConfirm: (name: String, fps: Int) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var fps by remember { mutableIntStateOf(12) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") })
                if (showFps) {
                    Spacer(Modifier.padding(top = 12.dp))
                    Text("Frame rate", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.padding(top = 6.dp)) {
                        listOf(12, 24, 30).forEach { f ->
                            FilterChip(fps == f, { fps = f }, { Text("$f fps") }, Modifier.padding(end = 8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ onConfirm(name, fps) }, enabled = name.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
