package fox.foxiru.foxcat.fox2d

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import fox.foxiru.foxcat.fox2d.jnicallers.AudioNative

// Mirrors `enum State` in fox_main.cpp
private const val ST_IDLE = 0
private const val ST_PLAYING = 1
private const val ST_PAUSED = 2
private const val ST_ENDED = 3
private const val ST_ERROR = 4
private const val ST_TONE = 5

private const val MAX_LOG = 20_000
private const val MAX_LIBRARY = 50

private val AUDIO_PERMISSION =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

data class AudioEntry(val uri: Uri, val title: String, val artist: String?, val durationMs: Long)

data class PlaybackStatus(
    val state: Int = ST_IDLE,
    val posMs: Long = 0,
    val durMs: Long = 0,
    val underruns: Long = 0,
    val bufferedMs: Long = 0,
) {
    val active get() = state == ST_PLAYING || state == ST_PAUSED || state == ST_TONE
    val stateName
        get() = when (state) {
            ST_PLAYING -> "playing"
            ST_PAUSED -> "paused"
            ST_ENDED -> "ended"
            ST_ERROR -> "error"
            ST_TONE -> "tone"
            else -> "idle"
        }
}

/** BPM detection settings, edited from the "BPM detection" group. */
data class BpmSettings(
    val minBpm: Int = 60,
    val maxBpm: Int = 200,
    val analysisSec: Int = 90,
    val skipSec: Int = 0,
    val fftSize: Int = 4096,
    val hopDiv: Int = 2, // hop = fftSize / hopDiv
) {
    val hop get() = fftSize / hopDiv
}

/** Raw values within +-ROUND_TOL of a whole number are rounded to it (134.04 -> 134.00). */
private const val ROUND_TOL = 0.07

private fun realBpm(raw: Double): Double {
    val whole = Math.rint(raw)
    // 1e-9: raw comes in as 2-decimal text, so 134.07 - 134 is 0.0700000000000074 in binary
    return if (kotlin.math.abs(raw - whole) <= ROUND_TOL + 1e-9) whole else Math.round(raw * 100.0) / 100.0
}

private val BPM_LINE = Regex("""^BPM\s+([0-9]+(?:\.[0-9]+)?)""", RegexOption.MULTILINE)

private val FFT_SIZES = listOf(1024, 2048, 4096)
private val HOP_DIVS = listOf(2, 4, 8)

/**
 * App-lifetime holder so results and playback state survive tab switches and
 * rotation (the native player is a process-wide singleton too).
 */
object AudioTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var endedHandled = false
    private var loadReported = false

    var uri by mutableStateOf<Uri?>(null)
    var fileName by mutableStateOf<String?>(null)
    var log by mutableStateOf("")
    var busy by mutableStateOf(false)
    var status by mutableStateOf(PlaybackStatus())
    var bpm by mutableStateOf(BpmSettings())
    var lastBpm by mutableStateOf<Double?>(null) // estimate from the last detection

    var hasPermission by mutableStateOf(false)
    var denials by mutableStateOf(0)
    var library by mutableStateOf<List<AudioEntry>>(emptyList())

    fun checkPermission(ctx: Context) {
        hasPermission = ContextCompat.checkSelfPermission(ctx, AUDIO_PERMISSION) == PackageManager.PERMISSION_GRANTED
    }

    fun onPermissionResult(granted: Boolean) {
        hasPermission = granted
        if (!granted) denials++
    }

    fun openAppSettings(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Newest [MAX_LIBRARY] audio files from MediaStore. Needs the audio permission. */
    fun loadLibrary(ctx: Context) {
        scope.launch {
            val out = mutableListOf<AudioEntry>()
            try {
                val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                val proj = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.DURATION,
                )
                ctx.applicationContext.contentResolver
                    .query(collection, proj, null, null, "${MediaStore.Audio.Media.DATE_ADDED} DESC")
                    ?.use { c ->
                        while (c.moveToNext() && out.size < MAX_LIBRARY) {
                            val artist = c.getString(2)?.takeIf { it != "<unknown>" }
                            out += AudioEntry(
                                uri = ContentUris.withAppendedId(collection, c.getLong(0)),
                                title = c.getString(1) ?: "?",
                                artist = artist,
                                durationMs = c.getLong(3),
                            )
                        }
                    }
                library = out
                append("library", "${out.size} audio files (newest first, max $MAX_LIBRARY)")
            } catch (t: Throwable) {
                append("library FAILED", t.toString())
            }
        }
    }

    private fun append(title: String, body: String) = synchronized(this) {
        log = (log + "-- $title --\n" + body.trimEnd() + "\n\n").takeLast(MAX_LOG)
    }

    fun clearLog() {
        log = ""
    }

    fun onPicked(context: Context, picked: Uri) {
        uri = picked
        fileName = context.applicationContext.contentResolver
            .query(picked, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: picked.lastPathSegment
    }

    private fun task(title: String, block: () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            val out = try {
                block()
            } catch (t: Throwable) {
                "EXCEPTION: $t"
            }
            append(title, out)
            busy = false
        }
    }

    private fun withFd(context: Context, block: (Int) -> String): String {
        val u = uri ?: return "No file selected"
        val pfd = context.applicationContext.contentResolver.openFileDescriptor(u, "r")
            ?: return "openFileDescriptor returned null"
        return pfd.use { block(it.fd) } // native dup()s, so closing ours right after is fine
    }

    fun versions() = task("ffmpeg / oboe versions") { AudioNative.versions() }
    fun oboeProbe() = task("oboe output probe") { AudioNative.oboeProbe() }
    fun probe(ctx: Context) = task("ffmpeg probe: $fileName") { withFd(ctx) { AudioNative.probe(it) } }
    fun decode(ctx: Context) = task("ffmpeg decode test: $fileName") { withFd(ctx) { AudioNative.decodeTest(it) } }
    fun play(ctx: Context) = task("play: $fileName") { withFd(ctx) { AudioNative.play(it) } }

    fun bpmSettings(block: BpmSettings.() -> BpmSettings) {
        var n = bpm.block()
        // keep the range valid whatever the user just tapped
        if (n.maxBpm < n.minBpm + 10) n = n.copy(maxBpm = n.minBpm + 10)
        bpm = n
    }

    fun detectBpm(ctx: Context) = task("bpm: $fileName") {
        val c = bpm
        val out = withFd(ctx) {
            AudioNative.detectBpm(
                it, c.minBpm.toDouble(), c.maxBpm.toDouble(), c.analysisSec.toDouble(),
                c.skipSec.toDouble(), c.fftSize, c.hop,
            )
        }
        lastBpm = BPM_LINE.find(out)?.groupValues?.get(1)?.toDoubleOrNull()
        out
    }

    fun toggleTone() {
        if (status.state == ST_TONE) stop() else task("oboe tone") { AudioNative.toneStart() }
    }

    fun togglePause() = AudioNative.setPaused(status.state == ST_PLAYING)

    fun stop() {
        scope.launch {
            val msg = AudioNative.stop()
            if (msg.isNotBlank()) append("stopped", msg)
        }
    }

    /** Called by the UI poll loop. */
    fun refresh() {
        // AudioNative.loadError?.let {
            // if (!loadReported) {
                // loadReported = true
                // append("NATIVE LOAD FAILED", it.message ?: it.toString())
            // }
            // return
        // }
        val a = AudioNative.status()
        val s = PlaybackStatus(a[0].toInt(), a[1], a[2], a[3], a[4])
        status = s
        if (s.state == ST_ENDED || s.state == ST_ERROR) {
            if (!endedHandled) {
                endedHandled = true
                scope.launch {
                    val msg = AudioNative.stop() // joins decoder thread, closes stream, returns message
                    append(if (s.state == ST_ENDED) "playback finished" else "playback error", msg)
                }
            }
        } else {
            endedHandled = false
        }
    }
}

private fun mmss(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/** Right-slot "- value +" stepper. */
@Composable
private fun Stepper(text: String, enabled: Boolean, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        TextButton(onClick = onMinus, enabled = enabled) { Text("-") }
        Text(text)
        TextButton(onClick = onPlus, enabled = enabled) { Text("+") }
    }
}

private fun <T> List<T>.step(cur: T, d: Int): T = this[(indexOf(cur) + d).coerceIn(0, lastIndex)]

@Composable
fun TestingScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) AudioTest.onPicked(context, uri)
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        AudioTest.onPermissionResult(granted)
    }

    LaunchedEffect(Unit) {
        while (true) {
            AudioTest.checkPermission(context) // also catches grant/revoke made in system settings
            AudioTest.refresh()
            delay(200)
        }
    }

    LaunchedEffect(AudioTest.hasPermission) {
        if (AudioTest.hasPermission && AudioTest.library.isEmpty()) AudioTest.loadLibrary(context)
    }

    val hasFile = AudioTest.uri != null
    val idle = !AudioTest.busy
    val st = AudioTest.status

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {

        FoxiruGroup(title = "Audio file") {
            item {
                FoxiruItem(
                    title = "Pick audio file",
                    subtitle = AudioTest.fileName ?: "Nothing selected",
                    onClick = { picker.launch(arrayOf("audio/*", "application/ogg")) },
                )
            }
        }

        FoxiruGroup(title = "Device audio") {
            item {
                if (!AudioTest.hasPermission) {
                    FoxiruItem(
                        title = "Allow audio access",
                        subtitle = when {
                            AudioTest.denials == 0 -> "Needed to browse audio files on this device"
                            AudioTest.denials == 1 -> "Denied. Tap to ask again"
                            else -> "Blocked. Tap to open app settings"
                        },
                        onClick = {
                            if (AudioTest.denials >= 2) AudioTest.openAppSettings(context)
                            else permLauncher.launch(AUDIO_PERMISSION)
                        },
                    )
                } else {
                    FoxiruItem(
                        title = "Refresh library",
                        subtitle = "${AudioTest.library.size} audio files",
                        onClick = { AudioTest.loadLibrary(context) },
                    )
                }
            }
            if (AudioTest.hasPermission) {
                AudioTest.library.forEach { e ->
                    item {
                        FoxiruItem(
                            title = e.title,
                            subtitle = listOfNotNull(e.artist, mmss(e.durationMs)).joinToString("  |  "),
                            onClick = { AudioTest.onPicked(context, e.uri) },
                        )
                    }
                }
            }
        }

        FoxiruGroup(title = "FFmpeg") {
            item {
                FoxiruItem(
                    title = "Versions and codecs",
                    subtitle = "libav* versions, decoders and demuxers built in",
                    enabled = idle,
                    onClick = { AudioTest.versions() },
                )
            }
            item {
                FoxiruItem(
                    title = "Probe file",
                    subtitle = "Container, streams, tags",
                    enabled = hasFile && idle,
                    onClick = { AudioTest.probe(context) },
                )
            }
            item {
                FoxiruItem(
                    title = "Decode test",
                    subtitle = "Full decode without playback: speed, peak, RMS",
                    enabled = hasFile && idle,
                    onClick = { AudioTest.decode(context) },
                )
            }
        }

        FoxiruGroup(title = "Oboe") {
            item {
                FoxiruItem(
                    title = "Probe output stream",
                    subtitle = "Shared vs Exclusive: API, rate, burst, buffer",
                    enabled = idle,
                    onClick = { AudioTest.oboeProbe() },
                )
            }
            item {
                FoxiruItem(
                    title = if (st.state == ST_TONE) "Stop tone" else "Play 440 Hz tone",
                    subtitle = "Oboe callback only, no FFmpeg",
                    enabled = idle && (st.state == ST_TONE || !st.active),
                    onClick = { AudioTest.toggleTone() },
                )
            }
        }

        FoxiruGroup(title = "Playback (FFmpeg to Oboe)") {
            item {
                FoxiruItem(
                    title = "${mmss(st.posMs)} / ${if (st.durMs > 0) mmss(st.durMs) else "--:--"}",
                    subtitle = "${st.stateName}  |  underruns ${st.underruns}  |  buffered ${st.bufferedMs} ms",
                    content = {
                        val p = if (st.durMs > 0) (st.posMs.toFloat() / st.durMs).coerceIn(0f, 1f) else 0f
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    },
                )
            }
            item {
                FoxiruItem(
                    title = "Play",
                    enabled = hasFile && idle && !st.active,
                    onClick = { AudioTest.play(context) },
                )
            }
            item {
                FoxiruItem(
                    title = if (st.state == ST_PAUSED) "Resume" else "Pause",
                    enabled = st.state == ST_PLAYING || st.state == ST_PAUSED,
                    onClick = { AudioTest.togglePause() },
                )
            }
            item {
                FoxiruItem(
                    title = "Stop",
                    enabled = st.active,
                    onClick = { AudioTest.stop() },
                )
            }
        }

        FoxiruGroup(title = "BPM detection") {
            item {
                FoxiruItem(
                    title = "Detect BPM",
                    subtitle = "Onset-flux tempogram on the selected file",
                    enabled = hasFile && idle,
                    onClick = { AudioTest.detectBpm(context) },
                )
            }
            item {
                val raw = AudioTest.lastBpm
                FoxiruItem(
                    title = if (raw != null) "Real BPM: %.2f".format(realBpm(raw)) else "No BPM yet",
                    subtitle = if (raw != null) "Raw BPM: %.2f".format(raw) else "Run Detect BPM on a file",
                )
            }
            item {
                FoxiruItem(
                    title = "Min BPM",
                    subtitle = "Lower bound of the tempo search",
                    enabled = idle,
                    trailing = { en ->
                        Stepper("${AudioTest.bpm.minBpm}", en,
                            { AudioTest.bpmSettings { copy(minBpm = (minBpm - 5).coerceAtLeast(30)) } },
                            { AudioTest.bpmSettings { copy(minBpm = (minBpm + 5).coerceAtMost(280)) } })
                    },
                )
            }
            item {
                FoxiruItem(
                    title = "Max BPM",
                    subtitle = "Upper bound of the tempo search",
                    enabled = idle,
                    trailing = { en ->
                        Stepper("${AudioTest.bpm.maxBpm}", en,
                            { AudioTest.bpmSettings { copy(maxBpm = (maxBpm - 5).coerceAtLeast(40)) } },
                            { AudioTest.bpmSettings { copy(maxBpm = (maxBpm + 5).coerceAtMost(300)) } })
                    },
                )
            }
            item {
                FoxiruItem(
                    title = "Analysis length",
                    subtitle = "Maximum seconds analysed; stops early once the result is stable",
                    enabled = idle,
                    trailing = { en ->
                        Stepper("${AudioTest.bpm.analysisSec} s", en,
                            { AudioTest.bpmSettings { copy(analysisSec = (analysisSec - 15).coerceAtLeast(15)) } },
                            { AudioTest.bpmSettings { copy(analysisSec = (analysisSec + 15).coerceAtMost(300)) } })
                    },
                )
            }
            item {
                FoxiruItem(
                    title = "Skip start",
                    subtitle = "Ignore the intro before analysing",
                    enabled = idle,
                    trailing = { en ->
                        Stepper("${AudioTest.bpm.skipSec} s", en,
                            { AudioTest.bpmSettings { copy(skipSec = (skipSec - 10).coerceAtLeast(0)) } },
                            { AudioTest.bpmSettings { copy(skipSec = (skipSec + 10).coerceAtMost(120)) } })
                    },
                )
            }
            item {
                FoxiruItem(
                    title = "FFT size",
                    subtitle = "Bigger = finer frequency, blurrier onsets",
                    enabled = idle,
                    trailing = { en ->
                        Stepper("${AudioTest.bpm.fftSize}", en,
                            { AudioTest.bpmSettings { copy(fftSize = FFT_SIZES.step(fftSize, -1)) } },
                            { AudioTest.bpmSettings { copy(fftSize = FFT_SIZES.step(fftSize, +1)) } })
                    },
                )
            }
            item {
                FoxiruItem(
                    title = "Hop",
                    subtitle = "FFT size / ${AudioTest.bpm.hopDiv} = ${AudioTest.bpm.hop} samples between frames",
                    enabled = idle,
                    trailing = { en ->
                        Stepper("1/${AudioTest.bpm.hopDiv}", en,
                            { AudioTest.bpmSettings { copy(hopDiv = HOP_DIVS.step(hopDiv, -1)) } },
                            { AudioTest.bpmSettings { copy(hopDiv = HOP_DIVS.step(hopDiv, +1)) } })
                    },
                )
            }
            item {
                FoxiruItem(
                    title = "Reset settings",
                    enabled = idle,
                    onClick = { AudioTest.bpm = BpmSettings() },
                )
            }
        }

        FoxiruGroup(title = "Log") {
            item {
                FoxiruItem(
                    title = if (AudioTest.busy) "Working..." else "Output",
                    trailing = { en -> TextButton(onClick = { AudioTest.clearLog() }, enabled = en) { Text("Clear") } },
                    content = {
                        SelectionContainer {
                            Text(
                                text = AudioTest.log.ifEmpty { "Nothing yet" },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                            )
                        }
                    },
                )
            }
        }
    }
}

