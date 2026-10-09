package fox.foxiru.foxcat.fox2d.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import fox.foxiru.foxcat.fox2d.main_canvas.EditorScreen
import fox.foxiru.foxcat.fox2d.main_canvas.UI_ALPHA
import fox.foxiru.foxcat.fox2d.main_canvas.rememberEditorState
import fox.foxiru.foxcat.fox2d.project.ProjectMeta
import fox.foxiru.foxcat.fox2d.project.ProjectRepository
import fox.foxiru.foxcat.fox2d.project.applyProject
import fox.foxiru.foxcat.fox2d.project.saveKey
import fox.foxiru.foxcat.fox2d.project.toProjectData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

private enum class SaveStatus { Saved, Dirty, Saving, Error }

/**
 * Hosts [EditorScreen] for one project. Opening loads project.json + strokes.bin into the shared EditorState
 * (skipped when that state already IS this project, e.g. after a rotation, so unsaved edits are never overwritten).
 * Saves: 1.5 s after the last edit, when the app stops, on Back, and when the status dot is tapped.
 */
@OptIn(FlowPreview::class)
@Composable
fun ProjectEditor(projectId: String, onClose: () -> Unit) {
    val appCtx = LocalContext.current.applicationContext
    val repo = remember { ProjectRepository.get(appCtx) }
    val state = rememberEditorState()
    val scope = rememberCoroutineScope()

    var meta by remember(projectId) { mutableStateOf<ProjectMeta?>(null) }
    var ready by remember(projectId) { mutableStateOf(false) }
    var failure by remember(projectId) { mutableStateOf<String?>(null) }
    var notice by remember(projectId) { mutableStateOf<String?>(null) }
    var status by remember(projectId) { mutableStateOf(SaveStatus.Saved) }

    suspend fun saveNow() {
        val m = meta ?: return
        val data = state.toProjectData(m) // main thread: reads snapshot state
        status = SaveStatus.Saving
        status = try {
            repo.save(data)
            meta = data.meta
            SaveStatus.Saved
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SaveStatus.Error
        }
    }

    fun close() {
        scope.launch {
            if (ready) saveNow()
            state.playing = false
            onClose()
        }
    }

    // ---- open
    LaunchedEffect(projectId) {
        try {
            val m = repo.readMeta(projectId) ?: error("Project not found")
            if (state.projectId != projectId) {
                val dropped = state.applyProject(appCtx, repo, repo.load(projectId))
                if (dropped > 0) notice = "$dropped audio clip${if (dropped == 1) "" else "s"} couldn't be restored"
            }
            meta = m
            ready = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e.message ?: "Couldn't open this project"
        }
    }

    // ---- autosave
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        snapshotFlow { state.saveKey() }
            .distinctUntilChanged { a, b -> a.first.sameAs(b.first) && a.second == b.second }
            .drop(1) // the state we just loaded
            .onEach { status = SaveStatus.Dirty }
            .debounce(1500)
            .collect { saveNow() }
    }
    LaunchedEffect(notice) { if (notice != null) { delay(4000); notice = null } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (ready) scope.launch { saveNow() } }
    BackHandler { close() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            when {
                failure != null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(failure!!, style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClose) { Text("Back to gallery") }
                }
                !ready -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> EditorScreen(
                    Modifier.fillMaxSize(),
                    state = state,
                    topLeading = {
                        IconButton(::close, Modifier.size(32.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to gallery")
                        }
                        Spacer(Modifier.width(4.dp))
                        SaveChip(status) { scope.launch { saveNow() } }
                        Spacer(Modifier.width(6.dp))
                    },
                )
            }
            notice?.let {
                Surface(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 140.dp),
                    shape = CircleShape, color = MaterialTheme.colorScheme.inverseSurface,
                ) {
                    Text(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.inverseOnSurface)
                }
            }
        }
    }
}

/** A dot (label only while not saved). Tap = save now. */
@Composable
private fun SaveChip(status: SaveStatus, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val (color, label) = when (status) {
        SaveStatus.Saved -> cs.primary to null
        SaveStatus.Dirty -> cs.tertiary to "Unsaved"
        SaveStatus.Saving -> cs.tertiary to "Saving…"
        SaveStatus.Error -> cs.error to "Save failed"
    }
    Surface(onClick, shape = CircleShape, color = cs.surfaceContainer.copy(alpha = UI_ALPHA)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            if (label != null) {
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
