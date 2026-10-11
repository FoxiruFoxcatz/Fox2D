package fox.foxiru.foxcat.fox2d

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.system.exitProcess

// ───────────────────────────── constants ─────────────────────────────

private const val PAGE_SIZE = 512 * 1024
private const val MAX_COPY_SIZE = 100 * 1024L
private const val AUTO_PAGER_THRESHOLD = 2L * 1024 * 1024
private const val MAX_MATCHES = 20_000
private const val LOG_WAIT_TIMEOUT_MS = 5000L
private const val LOG_WAIT_POLL_INTERVAL_MS = 100L
private const val SEARCH_DEBOUNCE_MS = 600L
private const val PREF_CLEAR_LOGS = "pref_clear_tombstones"
private const val PREF_PAGER_MODE = "pref_pager_mode"
private const val TAG = "CrashActivity"

// ───────────────────────────── activity ─────────────────────────────

/**
 * Runs in a separate process (":crashui"). ACTION_RENDERED is a broadcast because
 * static state does not cross process boundaries.
 *
 * Deps: compose-bom >= 2024.02 (Material3 >= 1.2 for surfaceContainer* tokens,
 * foundation >= 1.6 for requestScrollToItem), androidx.activity >= 1.8 (enableEdgeToEdge),
 * lifecycle-viewmodel-compose / lifecycle-viewmodel-ktx, material-icons-core (default).
 */
class CrashActivity : ComponentActivity() {

    companion object {
        const val ACTION_RENDERED = "com.foxiru.hooks.CRASH_ACTIVITY_RENDERED"
        const val LOG_PATH = "path"
    }

    private val vm: CrashViewModel by viewModels()
    private var hasSignaledRendered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // The host app's theme is inherited (often a fullscreen/odd theme). Compose draws its own
        // top bar, so force a platform NoActionBar theme that exists on every API level.
        setTheme(android.R.style.Theme_DeviceDefault_NoActionBar)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        vm.init(intent?.getStringExtra(LOG_PATH))

        setContent {
            CrashTheme {
                CrashScreen(vm = vm, onRestart = ::restartApp)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !hasSignaledRendered) {
            hasSignaledRendered = true
            runCatching { sendBroadcast(Intent(ACTION_RENDERED).setPackage(packageName)) }
        }
    }

    private fun restartApp() {
        if (vm.clearLogsOnExit) vm.deleteTombstones()
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
            startActivity(intent)
            Toast.makeText(this, "Restarting App", Toast.LENGTH_LONG).show()
        }
        finish()
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && vm.clearLogsOnExit) vm.deleteTombstones()
    }
}

// ───────────────────────────── theme ─────────────────────────────

/** Dynamic Material You on API 31+, static Material3 baseline on everything below (or if dynamic fails). */
@Composable
private fun CrashTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = remember(dark) {
        val dynamic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx) }.getOrNull()
        } else null
        dynamic ?: if (dark) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

// ───────────────────────────── view model ─────────────────────────────

data class Match(val line: Int, val start: Int, val end: Int)
data class UiMessage(val text: String, val long: Boolean = false)

class CrashViewModel(private val app: Application) : AndroidViewModel(app) {

    // Same file the old PreferenceManager.getDefaultSharedPreferences() used.
    private val prefs: SharedPreferences =
        app.getSharedPreferences("${app.packageName}_preferences", Context.MODE_PRIVATE)

    var logPath: String? = null
        private set
    private var initialized = false

    // prefs / paging
    var clearLogsOnExit by mutableStateOf(prefs.getBoolean(PREF_CLEAR_LOGS, true)); private set
    var pagerMode by mutableStateOf(prefs.getBoolean(PREF_PAGER_MODE, false)); private set
    var totalSize by mutableLongStateOf(0L); private set
    var currentPage by mutableIntStateOf(1); private set
    val totalPages: Int
        get() = if (pagerMode) max(1, ceil(totalSize.toDouble() / PAGE_SIZE).toInt()) else 1

    // content
    var lines by mutableStateOf<List<String>>(emptyList()); private set
    var loading by mutableStateOf(true); private set
    var errorText by mutableStateOf<String?>(null); private set
    var pageToken by mutableIntStateOf(0); private set

    // search
    var searchVisible by mutableStateOf(true); private set   // original layout shows the bar from the start
    var focusSearchRequested by mutableStateOf(false)
    var query by mutableStateOf(""); private set
    var useRegex by mutableStateOf(false); private set
    var matchCase by mutableStateOf(false); private set
    var wholeWord by mutableStateOf(false); private set
    var matches by mutableStateOf<List<Match>>(emptyList()); private set
    var matchesByLine by mutableStateOf<Map<Int, List<Match>>>(emptyMap()); private set
    var matchesCapped by mutableStateOf(false); private set
    var matchIndex by mutableIntStateOf(-1); private set
    var scrollTick by mutableIntStateOf(0); private set
    val activeMatch: Match? get() = matches.getOrNull(matchIndex)

    private var searchForward = true
    private var scanInFlight = false
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var debounceJob: Job? = null

    private val _events = Channel<UiMessage>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    fun toast(text: String, long: Boolean = false) { _events.trySend(UiMessage(text, long)) }

    // ── loading ──

    fun init(path: String?) {
        if (initialized) return
        initialized = true
        logPath = path
        if (path == null) {
            loading = false
            errorText = "No crash log path was provided"
            return
        }
        viewModelScope.launch {
            val file = File(path)
            val ready = withContext(Dispatchers.IO) { waitForFile(file) }
            if (!ready) {
                loading = false
                errorText = "Crash log not found"
                toast("Crash log not found (timed out waiting for it to be written)", long = true)
                return@launch
            }
            totalSize = file.length()
            if (!prefs.contains(PREF_PAGER_MODE) && totalSize > AUTO_PAGER_THRESHOLD) pagerMode = true
            loadPage(1)
        }
    }

    /** xcrash's callback can fire before the native dumper finished writing: wait until the size is stable. */
    private suspend fun waitForFile(file: File): Boolean {
        var waited = 0L
        var lastSize = -1L
        while (waited < LOG_WAIT_TIMEOUT_MS) {
            if (file.exists()) {
                val size = file.length()
                if (size > 0 && size == lastSize) return true
                lastSize = size
            }
            delay(LOG_WAIT_POLL_INTERVAL_MS)
            waited += LOG_WAIT_POLL_INTERVAL_MS
        }
        return file.exists()
    }

    fun loadPage(page: Int, searchFromEnd: Boolean = false) {
        val path = logPath ?: return
        currentPage = page.coerceIn(1, totalPages)
        val pager = pagerMode
        val p = currentPage
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            loading = true
            val result: List<String>? = withContext(Dispatchers.IO) {
                try {
                    readLines(path, p, pager)
                } catch (e: OutOfMemoryError) {
                    null
                } catch (e: Exception) {
                    Log.w(TAG, "readLines failed", e)
                    emptyList()
                }
            }
            if (result == null) {
                loading = false
                if (!pager) {
                    toast("Log too large for full view, switching to Pager Mode", long = true)
                    changePagerMode(true)
                } else {
                    toast("Out of memory while reading this page", long = true)
                }
                return@launch
            }
            lines = result
            loading = false
            pageToken++
            clearMatches()
            if (searchVisible && query.isNotEmpty()) runSearch(searchFromEnd)
        }
    }

    fun changePagerMode(enabled: Boolean) {
        pagerMode = enabled
        prefs.edit().putBoolean(PREF_PAGER_MODE, enabled).apply()
        loadPage(1)
    }

    fun changeClearLogsOnExit(enabled: Boolean) {
        clearLogsOnExit = enabled
        prefs.edit().putBoolean(PREF_CLEAR_LOGS, enabled).apply()
        toast("Auto-clear is now: " + if (enabled) "ON" else "OFF")
    }

    // ── search ──

    fun onQueryChange(text: String) {
        query = text
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            searchForward = true
            runSearch(false)
        }
    }

    fun searchNow() {
        debounceJob?.cancel()
        searchForward = true
        runSearch(false)
    }

    fun setRegex(v: Boolean) { useRegex = v; searchNow() }
    fun setCase(v: Boolean) { matchCase = v; searchNow() }
    fun setWord(v: Boolean) { wholeWord = v; searchNow() }

    fun toggleSearchBar() {
        if (searchVisible) {
            searchVisible = false
            debounceJob?.cancel()
            searchJob?.cancel()
            clearMatches()
        } else {
            searchVisible = true
            focusSearchRequested = true
            if (query.isNotEmpty()) runSearch(false)
        }
    }

    private fun clearMatches() {
        matches = emptyList()
        matchesByLine = emptyMap()
        matchesCapped = false
        matchIndex = -1
    }

    private fun buildRegex(term: String): Regex? = try {
        var p = if (useRegex) term else Regex.escape(term)
        if (wholeWord) p = "\\b(?:$p)\\b"
        Regex(p, if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE))
    } catch (e: Exception) {
        null
    }

    private fun runSearch(startAtEnd: Boolean) {
        searchJob?.cancel()
        val term = query
        val re = if (term.isEmpty()) null else buildRegex(term)
        if (re == null) { clearMatches(); return }
        val snapshot = lines
        searchJob = viewModelScope.launch {
            val (found, capped) = withContext(Dispatchers.Default) {
                val out = ArrayList<Match>()
                var cap = false
                outer@ for ((i, line) in snapshot.withIndex()) {
                    ensureActive()
                    for (m in re.findAll(line)) {
                        if (m.range.isEmpty()) continue
                        out.add(Match(i, m.range.first, m.range.last + 1))
                        if (out.size >= MAX_MATCHES) { cap = true; break@outer }
                    }
                }
                out to cap
            }
            matches = found
            matchesByLine = found.groupBy { it.line }
            matchesCapped = capped
            matchIndex = if (found.isEmpty()) -1 else if (startAtEnd) found.lastIndex else 0
            if (found.isEmpty()) onNoResultsOnPage() else scrollTick++
        }
    }

    fun nextMatch() {
        searchForward = true
        if (matches.isEmpty()) {
            if (query.isEmpty()) return
            if (pagerMode) onNoResultsOnPage() else runSearch(false)
            return
        }
        if (matchIndex + 1 >= matches.size) {
            if (pagerMode && totalPages > 1) {
                loadPage(if (currentPage + 1 > totalPages) 1 else currentPage + 1, searchFromEnd = false)
                return
            }
            matchIndex = 0
        } else {
            matchIndex++
        }
        scrollTick++
    }

    fun prevMatch() {
        searchForward = false
        if (matches.isEmpty()) {
            if (query.isEmpty()) return
            if (pagerMode) onNoResultsOnPage() else runSearch(false)
            return
        }
        if (matchIndex - 1 < 0) {
            if (pagerMode && totalPages > 1) {
                loadPage(if (currentPage - 1 < 1) totalPages else currentPage - 1, searchFromEnd = true)
                return
            }
            matchIndex = matches.lastIndex
        } else {
            matchIndex--
        }
        scrollTick++
    }

    private fun onNoResultsOnPage() {
        if (scanInFlight || !pagerMode) return
        val hasMore = if (searchForward) currentPage < totalPages else currentPage > 1
        if (hasMore) scanOtherPages()
    }

    /** Background scan of the other pages for the current query. */
    private fun scanOtherPages() {
        val path = logPath ?: return
        val re = buildRegex(query) ?: return
        val forward = searchForward
        val from = currentPage
        val last = totalPages
        scanInFlight = true
        toast("Scanning pages in background...")
        viewModelScope.launch {
            try {
                val found = withContext(Dispatchers.IO) {
                    try {
                        RandomAccessFile(path, "r").use { raf ->
                            val pages: IntProgression = if (forward) (from + 1)..last else (from - 1) downTo 1
                            for (p in pages) {
                                ensureActive()
                                val text = readPageText(raf, p)
                                if (text.split('\n').any { re.containsMatchIn(it) }) return@use p
                            }
                            -1
                        }
                    } catch (e: OutOfMemoryError) {
                        -1
                    } catch (e: IOException) {
                        -1
                    }
                }
                if (found != -1) loadPage(found, searchFromEnd = !forward)
                else toast("No more matches found.")
            } finally {
                scanInFlight = false
            }
        }
    }

    // ── file operations ──

    fun copyLog() {
        val path = logPath ?: return
        viewModelScope.launch {
            val file = File(path)
            if (!file.exists()) return@launch
            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return@launch
            try {
                if (file.length() > MAX_COPY_SIZE) {
                    cm.setPrimaryClip(ClipData.newPlainText("Crash Path", "Log too large (>100KB).\nPath: $path"))
                    toast("Log > 100KB. Copied FILE PATH only.", long = true)
                } else {
                    val content = withContext(Dispatchers.IO) { runCatching { file.readText() }.getOrNull() }
                    if (content != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("Crash Log", content))
                        toast("Log copied to clipboard")
                    } else toast("Failed to read log")
                }
            } catch (e: Exception) {
                toast("Copy failed: ${e.message}", long = true)
            }
        }
    }

    fun copyLine(text: String) {
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        runCatching {
            cm.setPrimaryClip(ClipData.newPlainText("Crash Log Line", text))
            toast("Line copied")
        }
    }

    /** API 29+: MediaStore (no permission needed). API < 29: legacy public Documents dir (needs WRITE_EXTERNAL_STORAGE). */
    fun saveLog() {
        val path = logPath ?: return
        viewModelScope.launch {
            val src = File(path)
            if (!src.exists()) { toast("Original log file missing!"); return@launch }
            try {
                withContext(Dispatchers.IO) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, src.name)
                            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/Imgui-Mod-Menu")
                        }
                        val resolver = app.contentResolver
                        val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values)
                            ?: throw IOException("MediaStore insert failed")
                        (resolver.openOutputStream(uri) ?: throw IOException("Cannot open output stream")).use { out ->
                            src.inputStream().use { it.copyTo(out) }
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Imgui-Mod-Menu")
                        if (!dir.exists() && !dir.mkdirs()) throw IOException("Cannot create $dir")
                        File(dir, src.name).outputStream().use { out -> src.inputStream().use { it.copyTo(out) } }
                    }
                }
                toast("Saved: ${src.name}", long = true)
            } catch (e: Exception) {
                toast("Save Failed: ${e.message}", long = true)
            }
        }
    }

    fun deleteTombstones() {
        try {
            val internalDir = File(app.filesDir, "tombstones")
            if (internalDir.isDirectory) internalDir.listFiles()?.forEach { it.delete() }
            val parent = logPath?.let { File(it).parentFile }
            if (parent != null && parent.isDirectory) {
                parent.listFiles { _, name -> name.contains("tombstone") || name.contains("log") }
                    ?.forEach { it.delete() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "deleteTombstones failed", e)
        }
    }
}

// ───────────────────────────── file helpers ─────────────────────────────

private fun readPageText(raf: RandomAccessFile, page: Int): String {
    raf.seek((page - 1).toLong() * PAGE_SIZE)
    val buf = ByteArray(PAGE_SIZE)
    var total = 0
    while (total < PAGE_SIZE) {
        val n = raf.read(buf, total, PAGE_SIZE - total)
        if (n <= 0) break
        total += n
    }
    return if (total <= 0) "" else String(buf, 0, total, Charsets.UTF_8)
}

private fun readLines(path: String, page: Int, pager: Boolean): List<String> {
    val file = File(path)
    if (!file.exists()) return emptyList()
    if (!pager) return file.bufferedReader(Charsets.UTF_8).use { it.readLines() }
    val text = RandomAccessFile(file, "r").use { readPageText(it, page) }
    val parts = text.split('\n')
    return if (parts.isNotEmpty() && parts.last().isEmpty()) parts.dropLast(1) else parts
}

private fun formatSize(v: Long): String {
    if (v < 1024) return "$v B"
    val z = (63 - java.lang.Long.numberOfLeadingZeros(v)) / 10
    return String.format(Locale.US, "%.1f %cB", v.toDouble() / (1L shl (z * 10)), " KMGTPE"[z])
}

// ───────────────────────────── screen ─────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CrashScreen(vm: CrashViewModel, onRestart: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current

    LaunchedEffect(Unit) {
        vm.events.collect {
            Toast.makeText(ctx, it.text, if (it.long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }
    // Avoid a dark/light flash behind the first frame.
    val window = (ctx as? Activity)?.window
    SideEffect { window?.setBackgroundDrawable(ColorDrawable(cs.surface.toArgb())) }

    val listState = rememberLazyListState()
    val hScroll = rememberScrollState()
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var menuOpen by remember { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var showPicker by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = cs.surface,
        topBar = {
            TopAppBar(
                title = { Text("Crash Log") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = cs.surfaceContainerLow,
                    scrolledContainerColor = cs.surfaceContainerLow,
                    titleContentColor = cs.onSurface,
                    actionIconContentColor = cs.onSurface,
                ),
                actions = {
                    IconButton(onClick = { vm.toggleSearchBar() }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Restart") }, onClick = { menuOpen = false; onRestart() })
                            DropdownMenuItem(text = { Text("Save") }, onClick = { menuOpen = false; vm.saveLog() })
                            DropdownMenuItem(text = { Text("Copy") }, onClick = { menuOpen = false; vm.copyLog() })
                            DropdownMenuItem(
                                text = { Text("Clear Logs on Exit") },
                                trailingIcon = if (vm.clearLogsOnExit) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else null,
                                onClick = { menuOpen = false; vm.changeClearLogsOnExit(!vm.clearLogsOnExit) },
                            )
                            DropdownMenuItem(
                                text = { Text("Pager Mode") },
                                trailingIcon = if (vm.pagerMode) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else null,
                                onClick = { menuOpen = false; vm.changePagerMode(!vm.pagerMode) },
                            )
                            DropdownMenuItem(text = { Text("About") }, onClick = { menuOpen = false; showAbout = true })
                        }
                    }
                },
            )
        },
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .consumeWindowInsets(inner)
                .imePadding()
                .fillMaxSize()
        ) {
            TopStatusBar(vm) {
                if (!vm.pagerMode || vm.totalPages <= 1) vm.toast("Enable Pager Mode to select pages")
                else showPicker = true
            }
            LogViewer(
                vm = vm,
                listState = listState,
                hScroll = hScroll,
                zoom = zoom,
                onZoom = { zoom = it },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            AnimatedVisibility(visible = vm.searchVisible) { SearchBar(vm) }
        }
    }

    if (showAbout) AboutDialog { showAbout = false }
    if (showPicker) PagePickerDialog(vm) { showPicker = false }
}

// ───────────────────────────── status bar ─────────────────────────────

@Composable
private fun TopStatusBar(vm: CrashViewModel, onPageClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val cur = if (vm.matches.isNotEmpty()) vm.matchIndex + 1 else 0
    val matchText = "$cur/${vm.matches.size}" + if (vm.matchesCapped) "+" else ""

    Row(
        Modifier.fillMaxWidth().height(28.dp).background(cs.surfaceContainerLow).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(matchText, fontSize = 12.sp, color = cs.onSurface, modifier = Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.weight(1f))
        Text(formatSize(vm.totalSize), fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        Text(" | ", fontSize = 12.sp, color = cs.onSurfaceVariant)
        Text(
            if (vm.pagerMode) "${vm.currentPage}/${vm.totalPages}" else "Full View",
            fontSize = 12.sp,
            color = cs.onSurfaceVariant,
            modifier = Modifier.clickable(onClick = onPageClick).padding(horizontal = 6.dp),
        )
    }
}

// ───────────────────────────── log viewer ─────────────────────────────

@Composable
private fun rememberLogFontFamily(): FontFamily {
    val ctx = LocalContext.current
    return remember {
        try {
            ctx.assets.open("FiraCode-Regular.ttf").close()
            FontFamily(Font("FiraCode-Regular.ttf", ctx.assets))
        } catch (e: Exception) {
            FontFamily.Monospace
        }
    }
}

@Composable
private fun LogViewer(
    vm: CrashViewModel,
    listState: LazyListState,
    hScroll: ScrollState,
    zoom: Float,
    onZoom: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val fontFamily = rememberLogFontFamily()
    val sizeSp = 13f * zoom
    val textStyle = remember(fontFamily, zoom, cs.onSurface) {
        TextStyle(fontFamily = fontFamily, fontSize = sizeSp.sp, lineHeight = (sizeSp * 1.5f).sp, color = cs.onSurface)
    }
    val measurer = rememberTextMeasurer()
    val charWidthPx = remember(textStyle) { measurer.measure("0", textStyle).size.width.toFloat() }
    val lineHeightPx = with(density) { (sizeSp * 1.5f).sp.toPx() }
    val padPx = with(density) { 10.dp.toPx() }

    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val currentOnZoom by rememberUpdatedState(onZoom)
    val currentZoom by rememberUpdatedState(zoom)

    // reset scroll on page change (guarded so rotation does not jump to the top)
    var handledPage by rememberSaveable { mutableIntStateOf(vm.pageToken) }
    LaunchedEffect(vm.pageToken) {
        if (vm.pageToken != handledPage) {
            handledPage = vm.pageToken
            listState.scrollToItem(0)
            hScroll.scrollTo(0)
        }
    }

    // scroll to the active match
    var handledScroll by rememberSaveable { mutableIntStateOf(vm.scrollTick) }
    LaunchedEffect(vm.scrollTick) {
        if (vm.scrollTick == handledScroll) return@LaunchedEffect
        handledScroll = vm.scrollTick
        val m = vm.activeMatch ?: return@LaunchedEffect
        try {
            listState.animateScrollToItem(m.line.coerceAtLeast(0), -(viewport.height / 2) + (lineHeightPx / 2).toInt())
            val target = (padPx + m.start * charWidthPx - viewport.width / 2f).toInt().coerceAtLeast(0)
            hScroll.animateScrollTo(target.coerceAtMost(hScroll.maxValue))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "scroll to match failed", e)
        }
    }

    // scroller fade: 1.0 while active, 0.3 idle, 0.1 after 1.5s
    var draggingV by remember { mutableStateOf(false) }
    var draggingH by remember { mutableStateOf(false) }
    val active = listState.isScrollInProgress || hScroll.isScrollInProgress || draggingV || draggingH
    var faded by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (active) faded = false else { delay(1500); faded = true }
    }
    val scrollerAlpha by animateFloatAsState(if (active) 1f else if (faded) 0.1f else 0.3f, label = "scrollerAlpha")

    val lines = vm.lines
    val byLine = vm.matchesByLine
    val activeMatch = vm.activeMatch

    Box(
        modifier
            .onSizeChanged { viewport = it }
            // two-finger pinch zoom (only consumes multi-touch, so single-finger scrolling is untouched)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        if (ev.changes.size >= 2 && ev.changes.all { it.pressed }) {
                            val z = ev.calculateZoom()
                            if (z != 1f) {
                                currentOnZoom((currentZoom * z).coerceIn(0.5f, 4f))
                                ev.changes.forEach { it.consume() }
                            }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            }
    ) {
        Box(Modifier.fillMaxSize().horizontalScroll(hScroll)) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(10.dp),
                modifier = Modifier.widthIn(min = with(density) { viewport.width.toDp() }),
            ) {
                items(count = lines.size) { index ->
                    val line = lines[index]
                    LogLine(
                        text = line,
                        matches = byLine[index],
                        active = activeMatch?.takeIf { it.line == index },
                        style = textStyle,
                        onLongClick = { vm.copyLine(line) },
                    )
                }
            }
        }

        when {
            vm.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = cs.primary)
            vm.errorText != null -> Text(
                vm.errorText.orEmpty(), color = cs.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).padding(24.dp), textAlign = TextAlign.Center,
            )
        }

        VerticalFastScroller(listState, scrollerAlpha) { draggingV = it }
        HorizontalFastScroller(hScroll, viewport.width, scrollerAlpha) { draggingH = it }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogLine(text: String, matches: List<Match>?, active: Match?, style: TextStyle, onLongClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val content = remember(text, matches, active, cs.primary, cs.onPrimary, cs.tertiary, cs.onTertiary) {
        if (matches.isNullOrEmpty()) AnnotatedString(text)
        else buildAnnotatedString {
            append(text)
            val hit = SpanStyle(background = cs.primary, color = cs.onPrimary)
            val cur = SpanStyle(background = cs.tertiary, color = cs.onTertiary, fontWeight = FontWeight.Bold)
            for (m in matches) {
                val s = m.start.coerceIn(0, text.length)
                val e = m.end.coerceIn(s, text.length)
                addStyle(if (m == active) cur else hit, s, e)
            }
        }
    }
    Text(
        text = content,
        style = style,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onLongClick),
    )
}

// ───────────────────────────── fast scrollers ─────────────────────────────

@Composable
private fun GripLines(width: Int) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(3) { Box(Modifier.size(width.dp, 2.dp).background(cs.onPrimary)) }
    }
}

@Composable
private fun BoxScope.VerticalFastScroller(state: LazyListState, alpha: Float, onDragging: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val onDraggingState by rememberUpdatedState(onDragging)

    BoxWithConstraints(
        Modifier.align(Alignment.CenterEnd).padding(end = 2.dp).fillMaxHeight(0.75f).width(30.dp).alpha(alpha)
    ) {
        val trackPx = constraints.maxHeight.toFloat()
        val minThumbPx = with(density) { 30.dp.toPx() }
        val total by remember { derivedStateOf { state.layoutInfo.totalItemsCount } }
        val visible by remember { derivedStateOf { state.layoutInfo.visibleItemsInfo.size } }
        val fraction by remember {
            derivedStateOf {
                val span = total - visible
                if (span <= 0) 0f else (state.firstVisibleItemIndex.toFloat() / span).coerceIn(0f, 1f)
            }
        }
        val thumbPx = if (total <= 0) minThumbPx
        else max(minThumbPx, trackPx * (visible.toFloat() / total)).coerceAtMost(trackPx)

        Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(cs.onPrimary.copy(alpha = 0.5f))
                .pointerInput(state, trackPx) {
                    fun scrub(y: Float) {
                        val info = state.layoutInfo
                        val span = info.totalItemsCount - info.visibleItemsInfo.size
                        if (span <= 0 || trackPx <= 0f) return
                        state.requestScrollToItem(((y / trackPx).coerceIn(0f, 1f) * span).roundToInt())
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        onDraggingState(true)
                        scrub(down.position.y)
                        do {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull()
                            if (c != null && c.pressed) { scrub(c.position.y); c.consume() }
                        } while (ev.changes.any { it.pressed })
                        onDraggingState(false)
                    }
                }
        )
        Box(
            Modifier
                .offset { IntOffset(0, (fraction * (trackPx - thumbPx)).roundToInt()) }
                .size(30.dp, with(density) { thumbPx.toDp() })
                .clip(RoundedCornerShape(8.dp))
                .background(cs.primary),
            contentAlignment = Alignment.Center,
        ) { GripLines(14) }
    }
}

@Composable
private fun BoxScope.HorizontalFastScroller(
    state: ScrollState,
    viewportPx: Int,
    alpha: Float,
    onDragging: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val onDraggingState by rememberUpdatedState(onDragging)

    BoxWithConstraints(
        Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp).fillMaxWidth(0.75f).height(30.dp).alpha(alpha)
    ) {
        val trackPx = constraints.maxWidth.toFloat()
        val minThumbPx = with(density) { 30.dp.toPx() }
        val maxValue = state.maxValue
        val contentPx = viewportPx + maxValue
        val thumbPx = if (contentPx <= 0 || viewportPx <= 0) minThumbPx
        else max(minThumbPx, trackPx * (viewportPx.toFloat() / contentPx)).coerceAtMost(trackPx)
        val fraction = if (maxValue > 0) (state.value.toFloat() / maxValue).coerceIn(0f, 1f) else 0f

        Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(cs.onPrimary.copy(alpha = 0.5f))
                .pointerInput(state, trackPx) {
                    fun scrub(x: Float) {
                        if (trackPx <= 0f) return
                        val target = (x / trackPx).coerceIn(0f, 1f) * state.maxValue
                        state.dispatchRawDelta(target - state.value)
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        onDraggingState(true)
                        scrub(down.position.x)
                        do {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull()
                            if (c != null && c.pressed) { scrub(c.position.x); c.consume() }
                        } while (ev.changes.any { it.pressed })
                        onDraggingState(false)
                    }
                }
        )
        Box(
            Modifier
                .offset { IntOffset((fraction * (trackPx - thumbPx)).roundToInt(), 0) }
                .size(with(density) { thumbPx.toDp() }, 30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(cs.primary),
            contentAlignment = Alignment.Center,
        ) { GripLines(12) }
    }
}

// ───────────────────────────── search bar ─────────────────────────────

@Composable
private fun SearchBar(vm: CrashViewModel) {
    val cs = MaterialTheme.colorScheme
    val onBar = cs.onSecondaryContainer
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // only grab focus when the user opened the bar (not on first composition / rotation)
    LaunchedEffect(vm.focusSearchRequested) {
        if (vm.focusSearchRequested) {
            vm.focusSearchRequested = false
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
        }
    }
    DisposableEffect(Unit) { onDispose { keyboard?.hide() } }

    Surface(color = cs.surfaceContainerHighest, shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {

            Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Results: ${vm.matches.size}", color = onBar, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                ToggleChip("Cc", vm.matchCase) { vm.setCase(it) }
                ToggleChip("W", vm.wholeWord) { vm.setWord(it) }
                ToggleChip(".*", vm.useRegex) { vm.setRegex(it) }
            }

            Row(
                Modifier.fillMaxWidth().height(42.dp).padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Find", color = onBar, fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp))
                BasicTextField(
                    value = vm.query,
                    onValueChange = vm::onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(color = onBar, fontSize = 14.sp),
                    cursorBrush = SolidColor(onBar),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.searchNow() }),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .border(1.dp, cs.outlineVariant, RoundedCornerShape(8.dp))
                        .focusRequester(focusRequester),
                    decorationBox = { inner ->
                        Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                            if (vm.query.isEmpty()) Text("Search Logs...", color = onBar.copy(alpha = 0.53f), fontSize = 14.sp)
                            inner()
                        }
                    },
                )
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.prevMatch() }, modifier = Modifier.weight(1f)) { Text("PREV") }
                TextButton(onClick = { vm.nextMatch() }, modifier = Modifier.weight(1f)) { Text("NEXT") }
                TextButton(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) { Text("REP") }
                TextButton(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) { Text("ALL") }
                TextButton(onClick = { vm.toggleSearchBar() }) { Text("✕") }
            }
        }
    }
}

@Composable
private fun ToggleChip(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .padding(start = 6.dp)
            .height(24.dp)
            .clip(shape)
            .then(if (checked) Modifier.background(cs.tertiary) else Modifier.border(1.dp, cs.outlineVariant, shape))
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 12.sp, color = if (checked) cs.onTertiary else cs.onSecondaryContainer)
    }
}

// ───────────────────────────── dialogs ─────────────────────────────

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        title = {
            Text("Crash Log", color = cs.primary, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth())
        },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("v10.0", fontSize = 12.sp, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Text("Made by Foxiru", fontSize = 16.sp, color = cs.onSurface)
            }
        },
    )
}

@Composable
private fun PagePickerDialog(vm: CrashViewModel, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (vm.currentPage - 2).coerceAtLeast(0))
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = {
            Text("Select Page", color = cs.primary, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth())
        },
        text = {
            LazyColumn(state = state, modifier = Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(count = vm.totalPages) { i ->
                    val page = i + 1
                    val current = page == vm.currentPage
                    Text(
                        "Page $page",
                        fontSize = 16.sp,
                        color = if (current) cs.onPrimary else cs.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .then(if (current) Modifier.background(cs.primary) else Modifier)
                            .clickable { vm.loadPage(page); onDismiss() }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        },
    )
}
