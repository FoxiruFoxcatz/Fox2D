package fox.foxiru.foxcat.fox2d.main_canvas

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.CancellationException

private val resolutions = listOf(480, 720, 1080, 1440)
private val frameRates = listOf(12, 24, 30, 60)
private val presets = listOf("ultrafast", "veryfast", "fast", "medium", "slow")

private fun parseExtra(s: String): Map<String, String> =
    s.split(';').mapNotNull {
        val i = it.indexOf('=')
        if (i > 0) it.substring(0, i).trim() to it.substring(i + 1).trim() else null
    }.toMap()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(state: EditorState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val encoders = remember { MovieExporter.availableEncoders() }
    val hasAudioClips = state.timeline.clips.isNotEmpty()

    var side by remember { mutableIntStateOf(1080) }
    var fps by remember { mutableIntStateOf(if (state.fps in frameRates) state.fps else 24) }
    var quality by remember { mutableStateOf(ExportQuality.High) }
    var codec by remember { mutableStateOf(ExportCodec.H264) }
    var preset by remember { mutableStateOf("medium") }
    var audio by remember { mutableStateOf(true) }
    var extra by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }
    var job by remember { mutableStateOf<Job?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var doneUri by remember { mutableStateOf<Uri?>(null) }

    val busy = job != null
    val busyNow by rememberUpdatedState(busy)
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !busyNow })
    val canExport = MovieExporter.supports(codec, encoders)
    val softwareEncoder = "libx264" in encoders || "libx265" in encoders

    ModalBottomSheet(
        onDismissRequest = { if (!busyNow) state.exportOpen = false },
        sheetState = sheet,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Export MP4", style = MaterialTheme.typography.titleMedium)

            ChipRow("Resolution", resolutions, side, { "$it px" }, { !busy }) { side = it }
            ChipRow("Frame rate", frameRates, fps, { "$it fps" }, { !busy }) { fps = it }
            ChipRow("Quality", ExportQuality.entries, quality, { it.label }, { !busy }) { quality = it }
            ChipRow(
                "Codec", ExportCodec.entries, codec, { it.label },
                { !busy && MovieExporter.supports(it, encoders) },
            ) { codec = it }
            if (softwareEncoder) ChipRow("Encoder preset", presets, preset, { it }, { !busy }) { preset = it }

            if (hasAudioClips) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Include audio", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(audio, { audio = it }, enabled = !busy)
                }
            }

            OutlinedTextField(
                value = extra,
                onValueChange = { extra = it },
                enabled = !busy,
                label = { Text("FFmpeg options (key=value;key=value)") },
                placeholder = { Text("tune=animation;crf=18") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (!canExport) {
                Text(
                    "This FFmpeg build has no ${codec.label} encoder or no MP4 muxer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (busy) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(
                    "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(2.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (busy) {
                    OutlinedButton({ job?.cancel() }, Modifier.weight(1f)) { Text("Cancel") }
                } else {
                    doneUri?.let { uri ->
                        TextButton({
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(uri, "video/mp4")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }) { Text("Open") }
                    }
                    Button(
                        onClick = {
                            val opts = ExportOptions(
                                side = side,
                                fps = fps,
                                quality = quality,
                                codec = codec,
                                preset = preset,
                                audio = audio && hasAudioClips,
                                extra = parseExtra(extra),
                            )
                            job = scope.launch {
                                message = null
                                doneUri = null
                                progress = 0f
                                try {
                                    when (val r = MovieExporter.export(context, state, opts) { progress = it }) {
                                        is ExportResult.Done -> {
                                            doneUri = r.uri
                                            message = "Saved to Movies/${MovieExporter.SUBDIR} (${r.encoder})" +
                                                if (r.warning.isNotEmpty()) "\n${r.warning}" else ""
                                        }
                                        is ExportResult.Failed -> message = r.message
                                    }
                                } catch (e: CancellationException) {
                                    message = "Cancelled"
                                    throw e
                                } finally {
                                    job = null
                                }
                            }
                        },
                        enabled = canExport,
                        modifier = Modifier.weight(1f),
                    ) { Text("Export") }
                }
            }
        }
    }
}

@Composable
private fun <T> ChipRow(
    label: String,
    items: List<T>,
    selected: T,
    text: (T) -> String,
    enabled: (T) -> Boolean,
    onPick: (T) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items.forEach {
                FilterChip(
                    selected = it == selected,
                    onClick = { onPick(it) },
                    label = { Text(text(it)) },
                    enabled = enabled(it),
                )
            }
        }
    }
}
