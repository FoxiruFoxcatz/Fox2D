package fox.foxiru.foxcat.fox2d.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import fox.foxiru.foxcat.fox2d.FoxiruGroup
import fox.foxiru.foxcat.fox2d.FoxiruItem
import fox.foxiru.foxcat.fox2d.FoxiruTopBarAction
import fox.foxiru.foxcat.fox2d.project.ProjectRepository
import fox.foxiru.foxcat.fox2d.project.StorageLocations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private class MoveOffer(val from: File, val to: File, val count: Int)

/** Fill behind groups and items. Transparent shows the Foxiru background fully; try surface.copy(alpha = 0.55f) if text is hard to read. */
private val ItemFill = Color.Transparent

/**
 * Where projects are saved. Pick a location, add your own folder under internal storage, or allow access so
 * Fox2D/projects can live on internal storage. Switching offers to move the projects you already have.
 */
@Composable
fun StorageSettingsScreen(
    locations: StorageLocations,
    repo: ProjectRepository,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var access by remember { mutableStateOf(locations.hasAllFilesAccess()) }
    var tick by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    var offer by remember { mutableStateOf<MoveOffer?>(null) }
    var moving by remember { mutableStateOf(false) }

    // back from the system "All files access" screen
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val now = locations.hasAllFilesAccess()
        if (now != access) { access = now; onChanged() }
        tick++
    }

    val current = remember(access, tick, locations.active) { locations.effective() }

    fun select(dir: File) {
        if (dir.path == current.path) return
        scope.launch {
            val leftBehind = withContext(Dispatchers.IO) { repo.countProjects(current) }
            locations.select(dir)
            onChanged()
            tick++
            if (leftBehind > 0) offer = MoveOffer(current, dir, leftBehind)
        }
    }
    
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        FoxiruOCBackground(Modifier.fillMaxSize())

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                Text("Settings", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.headlineSmall)
            }
            // Group/item fills come from the Material surface roles. Make them clear so Foxiru shows through.
            val cs = MaterialTheme.colorScheme
            MaterialTheme(
                colorScheme = cs.copy(
                    surfaceVariant = ItemFill,
                    surfaceBright = ItemFill,
                    surfaceDim = ItemFill,
                    surfaceContainerLowest = ItemFill,
                    surfaceContainerLow = ItemFill,
                    surfaceContainer = ItemFill,
                    surfaceContainerHigh = ItemFill,
                    surfaceContainerHighest = ItemFill,
                ),
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .widthIn(max = 640.dp)
                        .align(Alignment.CenterHorizontally)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    FoxiruGroup(title = "Storage", icon = Icons.Filled.Storage, segmented = false) {
                        item {
                            FoxiruItem(
                                title = "Internal storage access",
                                subtitle = if (access) "Allowed. Projects can be saved in Fox2D/projects."
                                else "Needed to save projects in Fox2D/projects on internal storage.",
                                icon = Icons.Filled.FolderOpen,
                                trailing = {
                                    if (access) Icon(Icons.Filled.CheckCircle, contentDescription = "Allowed", tint = MaterialTheme.colorScheme.primary)
                                    else Button({ ctx.startActivity(locations.accessIntent()) }) { Text("Allow") }
                                },
                            )
                        }
                        item { FoxiruItem(title = "Saving to", subtitle = current.path) }
                    }
    
                    FoxiruGroup(
                        title = "Save location",
                        icon = Icons.Filled.Folder,
                        topBar = {
                            FoxiruTopBarAction("Add location", Icons.Filled.CreateNewFolder, onClick = { adding = true })
                            FoxiruTopBarAction("Refresh", Icons.Filled.Refresh, onClick = { tick++ })
                        },
                        segmented = false,
                    ) {
                        locations.saved.toList().forEach { dir ->
                            item {
                                LocationItem(
                                    locations = locations, repo = repo, dir = dir,
                                    selected = dir.path == current.path, access = access, tick = tick,
                                    onSelect = { select(dir) },
                                    onRemove = { locations.remove(dir); onChanged(); tick++ },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    if (adding) {
        AddLocationDialog(
            onDismiss = { adding = false },
            onAdd = { text ->
                val dir = locations.add(text)
                if (dir != null) { adding = false; tick++ }
                dir != null
            },
        )
    }

    offer?.let { o ->
        AlertDialog(
            onDismissRequest = { if (!moving) offer = null },
            title = { Text("Move your projects?") },
            text = {
                Text(
                    "${o.count} project${if (o.count == 1) "" else "s"} " +
                        "${if (o.count == 1) "is" else "are"} still in ${locations.label(o.from)}. " +
                        "Move ${if (o.count == 1) "it" else "them"} to ${locations.label(o.to)}?",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !moving,
                    onClick = {
                        moving = true
                        scope.launch {
                            repo.moveAll(o.from, o.to)
                            moving = false
                            offer = null
                            onChanged()
                            tick++
                        }
                    },
                ) { Text(if (moving) "Moving…" else "Move") }
            },
            dismissButton = { TextButton(onClick = { offer = null }, enabled = !moving) { Text("Leave them") } },
        )
    }
}

@Composable
private fun LocationItem(
    locations: StorageLocations,
    repo: ProjectRepository,
    dir: File,
    selected: Boolean,
    access: Boolean,
    tick: Int,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
) {
    val usable = remember(access, tick, dir) { locations.canUse(dir) }
    val count by produceState<Int?>(null, dir, tick, usable) {
        value = if (usable) withContext(Dispatchers.IO) { repo.countProjects(dir) } else null
    }
    FoxiruItem(
        title = locations.label(dir),
        subtitle = dir.path,
        value = when {
            !usable -> "Allow internal storage access first"
            count == null -> null
            else -> "$count project${if (count == 1) "" else "s"}"
        },
        enabled = usable,
        leading = { en -> RadioButton(selected = selected, onClick = null, enabled = en) },
        onClick = onSelect,
        expandedContent = { _ ->
            if (locations.isRemovable(dir)) {
                Text(
                    "Removing a location only forgets it. The files stay on your device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRemove) { Text("Remove from list") }
            } else {
                Text(
                    if (dir.path == locations.privateDir.path) "Hidden from file managers. Always available, needs no permission."
                    else "Visible in your file manager. Needs internal storage access.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun AddLocationDialog(onDismiss: () -> Unit, onAdd: (String) -> Boolean) {
    var text by remember { mutableStateOf("Documents/Fox2D") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add location") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; error = false },
                singleLine = true,
                label = { Text("Folder on internal storage") },
                isError = error,
                supportingText = { Text(if (error) "Enter a folder name, for example Documents/Fox2D" else "Created if it does not exist") },
            )
        },
        confirmButton = { TextButton(onClick = { error = !onAdd(text) }) { Text("Add") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
