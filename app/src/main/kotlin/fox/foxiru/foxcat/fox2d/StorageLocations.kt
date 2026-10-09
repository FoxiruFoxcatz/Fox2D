package fox.foxiru.foxcat.fox2d.project

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.io.File

/**
 * Where projects live.
 *
 *  - default  : <internal storage>/Fox2D/projects   (needs "All files access" on Android 11+)
 *  - private  : <app files>/projects                (always works, hidden from file managers; the old location)
 *  - custom   : any folder under internal storage the user adds in Settings
 *
 * [effective] is what the repository really uses: the chosen location when it is usable, otherwise the private
 * one, so a revoked permission never makes the gallery crash or lose projects, it just looks at the private folder.
 */
class StorageLocations private constructor(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("fox2d_storage", Context.MODE_PRIVATE)

    /** /storage/emulated/0 */
    val internalRoot: File = Environment.getExternalStorageDirectory()
    val defaultDir = File(internalRoot, "Fox2D/projects")
    val privateDir = File(ctx.filesDir, "projects")

    /** Every location shown in Settings (built-ins first). Observable. */
    val saved = mutableStateListOf<File>()

    /** The location the user picked (may be unusable right now, see [effective]). Observable. */
    var active by mutableStateOf(defaultDir)
        private set

    /** One-time move of the old private projects into the new default location. */
    var migrated: Boolean
        get() = prefs.getBoolean(KEY_MIGRATED, false)
        set(v) { prefs.edit().putBoolean(KEY_MIGRATED, v).apply() }

    init {
        val arr = runCatching { JSONArray(prefs.getString(KEY_LIST, "[]")) }.getOrDefault(JSONArray())
        val custom = List(arr.length()) { File(arr.getString(it)) }
        saved += (listOf(defaultDir, privateDir) + custom).distinctBy { it.path }
        active = prefs.getString(KEY_ACTIVE, null)?.let(::File) ?: defaultDir
    }

    // ------------------------------------------------------------------ permission

    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Opens the system screen where the user allows access (Android 11+: "All files access"). */
    fun accessIntent(): Intent {
        val pkg = Uri.parse("package:${ctx.packageName}")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg)
        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
    }

    // ------------------------------------------------------------------ resolving

    private fun isAppOwned(dir: File): Boolean {
        val p = dir.path
        return p.startsWith(ctx.filesDir.path) || ctx.getExternalFilesDir(null)?.path?.let { p.startsWith(it) } == true
    }

    /** True when we can create and write into [dir] right now. */
    fun canUse(dir: File): Boolean {
        if (!isAppOwned(dir) && !hasAllFilesAccess()) return false
        return (dir.isDirectory || dir.mkdirs()) && dir.canWrite()
    }

    /** The folder the repository reads and writes. Never null: falls back to the private folder. */
    fun effective(): File = if (canUse(active)) active else privateDir.also { it.mkdirs() }

    // ------------------------------------------------------------------ editing the list

    fun select(dir: File) {
        active = dir
        prefs.edit().putString(KEY_ACTIVE, dir.path).apply()
    }

    /** [relative] is a path under internal storage, e.g. "Documents/Fox2D". Returns null when it is not a valid folder name. */
    fun add(relative: String): File? {
        val parts = relative.trim().split('/', '\\').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.any { it == "." || it == ".." }) return null
        val dir = File(internalRoot, parts.joinToString("/"))
        if (saved.none { it.path == dir.path }) {
            saved += dir
            persistList()
        }
        return dir
    }

    fun isRemovable(dir: File) = dir.path != defaultDir.path && dir.path != privateDir.path

    /** Forgets the location (files stay on disk). */
    fun remove(dir: File) {
        if (!isRemovable(dir)) return
        saved.removeAll { it.path == dir.path }
        if (active.path == dir.path) select(defaultDir)
        persistList()
    }

    fun label(dir: File): String = when {
        dir.path == defaultDir.path -> "Internal storage / Fox2D / projects"
        dir.path == privateDir.path -> "App storage (private)"
        dir.path.startsWith(internalRoot.path) ->
            "Internal storage / " + dir.path.removePrefix(internalRoot.path).trim('/').split('/').joinToString(" / ")
        else -> dir.name
    }

    private fun persistList() {
        val custom = saved.filter { isRemovable(it) }.map { it.path }
        prefs.edit().putString(KEY_LIST, JSONArray(custom).toString()).apply()
    }

    companion object {
        private const val KEY_LIST = "locations"
        private const val KEY_ACTIVE = "active"
        private const val KEY_MIGRATED = "migrated_legacy"

        @Volatile private var instance: StorageLocations? = null

        fun get(ctx: Context): StorageLocations = instance ?: synchronized(this) {
            instance ?: StorageLocations(ctx.applicationContext).also { instance = it }
        }
    }
}
