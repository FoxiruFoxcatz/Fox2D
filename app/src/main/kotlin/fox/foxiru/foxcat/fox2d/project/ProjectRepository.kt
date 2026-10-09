package fox.foxiru.foxcat.fox2d.project

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.MimeTypeMap
import fox.foxiru.foxcat.fox2d.main_canvas.InkStroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * <location>/<uuid>/            (location = Fox2D/projects on internal storage by default, see [StorageLocations])
 *   meta.json      gallery entry
 *   project.json   structure + prefs
 *   strokes.bin    ink (gzip binary)
 *   thumb.png      gallery thumbnail
 *   audio/         copies of imported audio (the original Uri may disappear)
 * <location>/order.json           gallery order (list of ids, drag-to-reorder)
 *
 * Every file is written to <name>.tmp, fsync'd, then atomically moved over the old one, and project.json is
 * written last: a crash mid-save leaves the previous complete version, never a half-written one.
 */
class ProjectRepository private constructor(private val locations: StorageLocations) {
    private val lock = Mutex()
    private val thumbKeys = HashMap<String, Any>()

    /** Re-evaluated on every call so switching location in Settings takes effect immediately. */
    private val root: File get() = locations.effective()

    private fun dir(id: String): File {
        require(id.isNotEmpty() && id.all { it.isLetterOrDigit() || it == '-' }) { "Bad project id" }
        return File(root, id)
    }

    fun thumbFile(id: String) = File(dir(id), "thumb.png")
    fun audioDir(id: String) = File(dir(id), "audio").also { it.mkdirs() }

    private fun readMetaSync(id: String): ProjectMeta? =
        runCatching { ProjectCodec.decodeMeta(File(dir(id), "meta.json").readText()) }.getOrNull()
    fun imageDir(id: String) = File(dir(id), "images").also { it.mkdirs() }
    
    suspend fun readMeta(id: String): ProjectMeta? = withContext(Dispatchers.IO) { readMetaSync(id) }

    // ------------------------------------------------------------------ order

    private fun readOrder(location: File): List<String> = runCatching {
        val a = JSONArray(File(location, ORDER_FILE).readText())
        List(a.length()) { a.getString(it) }
    }.getOrDefault(emptyList())

    private fun writeOrder(location: File, ids: List<String>) {
        location.mkdirs()
        writeAtomic(File(location, ORDER_FILE)) { it.write(JSONArray(ids).toString().toByteArray()) }
    }

    /** Persists the gallery order after a drag. */
    suspend fun saveOrder(ids: List<String>) = withContext(Dispatchers.IO) {
        lock.withLock { runCatching { writeOrder(root, ids) } }
    }

    // ------------------------------------------------------------------ list / create / load

    suspend fun list(): List<ProjectMeta> = withContext(Dispatchers.IO) {
        lock.withLock { migrateLegacy() }
        val location = root
        val rank = readOrder(location).withIndex().associate { it.value to it.index }
        (location.listFiles() ?: emptyArray()).filter { it.isDirectory }
            .mapNotNull { readMetaSync(it.name) }
            // ordered ids first; projects the order file does not know (new, or copied in by hand) go on top, newest first
            .sortedWith(compareBy<ProjectMeta> { rank[it.id] ?: -1 }.thenByDescending { it.modifiedAt })
    }

    suspend fun create(name: String, fps: Int): ProjectMeta {
        val now = System.currentTimeMillis()
        val meta = ProjectMeta(UUID.randomUUID().toString(), name.trim().ifEmpty { "Untitled" }, now, now, fps, 12)
        save(blankProject(meta))
        withContext(Dispatchers.IO) {
            lock.withLock { runCatching { writeOrder(root, listOf(meta.id) + readOrder(root).filter { it != meta.id }) } }
        }
        return meta
    }

    suspend fun load(id: String): ProjectData = withContext(Dispatchers.IO) {
        val d = dir(id)
        
        
        
        val meta = readMetaSync(id) ?: error("Project not found")
        val strokesFile = File(d, "strokes.bin")
        val strokes: List<InkStroke> =
            if (strokesFile.exists()) strokesFile.inputStream().use { ProjectCodec.readStrokes(it) } else emptyList()
        val data = ProjectCodec.decode(File(d, "project.json").readText(), meta, strokes)
        
        val usedImg = strokes.mapNotNull { it.image?.file }.toSet()
        imageDir(id).listFiles()?.forEach { if (it.name !in usedImg) it.delete() }
        // history is gone after a load, so audio no clip references can never come back: drop it
        val used = data.clips.map { it.src }.toSet()
        audioDir(id).listFiles()?.forEach { if (it.name !in used) it.delete() }
        data
    }

    /** Safe to call while the caller is being cancelled (screen closing): the write still finishes. */
    suspend fun save(data: ProjectData) = withContext(Dispatchers.IO + NonCancellable) {
        lock.withLock {
            val id = data.meta.id
            val d = dir(id).also { it.mkdirs() }
            
            writeAtomic(File(d, "strokes.bin")) { ProjectCodec.writeStrokes(it, data.strokes) }
            writeAtomic(File(d, "project.json")) { it.write(ProjectCodec.encode(data).toByteArray()) }
            // thumbnail only when the picture could have changed
            val key = Triple(data.strokes, data.cels, data.drawTracks.map { it.xf to it.layers })
            if (thumbKeys[id] != key || !thumbFile(id).exists()) {
                val bmp = ThumbRenderer.render(data, imageDir(id))
                writeAtomic(thumbFile(id)) { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                thumbKeys[id] = key
            }
            writeAtomic(File(d, "meta.json")) { it.write(ProjectCodec.encodeMeta(data.meta).toByteArray()) }
        }
    }

    suspend fun rename(id: String, name: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val m = readMetaSync(id) ?: return@withLock
            writeAtomic(File(dir(id), "meta.json")) {
                it.write(ProjectCodec.encodeMeta(m.copy(name = name.trim().ifEmpty { m.name })).toByteArray())
            }
        }
    }

    suspend fun duplicate(id: String): ProjectMeta? = withContext(Dispatchers.IO) {
        lock.withLock {
            val m = readMetaSync(id) ?: return@withLock null
            val now = System.currentTimeMillis()
            val copy = m.copy(id = UUID.randomUUID().toString(), name = m.name + " copy", createdAt = now, modifiedAt = now)
            dir(id).copyRecursively(dir(copy.id), overwrite = true)
            writeAtomic(File(dir(copy.id), "meta.json")) { it.write(ProjectCodec.encodeMeta(copy).toByteArray()) }
            // the copy sits right after the original
            runCatching {
                val order = readOrder(root).toMutableList()
                val at = order.indexOf(id)
                if (at >= 0) order.add(at + 1, copy.id) else order.add(0, copy.id)
                writeOrder(root, order)
            }
            copy
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            dir(id).deleteRecursively()
            thumbKeys.remove(id)
            runCatching { writeOrder(root, readOrder(root).filter { it != id }) }
        }
    }

    // ------------------------------------------------------------------ locations

    fun countProjects(location: File): Int =
        location.listFiles()?.count { it.isDirectory && File(it, "meta.json").isFile } ?: 0

    /**
     * Moves every project folder from [from] into [to] (copy, then delete the source, so a failure never loses a
     * project) and carries the gallery order over. Returns how many projects moved.
     */
    suspend fun moveAll(from: File, to: File): Int = withContext(Dispatchers.IO) {
        lock.withLock {
            if (from.path == to.path) return@withLock 0
            to.mkdirs()
            val movedIds = ArrayList<String>()
            for (d in from.listFiles().orEmpty()) {
                if (!d.isDirectory || !File(d, "meta.json").isFile) continue
                val dst = File(to, d.name)
                if (dst.exists()) continue // never overwrite
                val ok = runCatching { d.copyRecursively(dst, overwrite = false) }.getOrDefault(false)
                if (ok) { d.deleteRecursively(); movedIds += d.name } else dst.deleteRecursively()
            }
            val srcOrder = readOrder(from)
            val carried = srcOrder.filter { it in movedIds } + movedIds.filter { it !in srcOrder }
            runCatching { writeOrder(to, carried + readOrder(to).filter { it !in carried }) }
            movedIds.size
        }
    }

    /** Old versions kept projects in <app files>/projects. Move them to the new default location once. */
    private fun migrateLegacy() {
        val legacy = locations.privateDir
        val target = root
        if (locations.migrated || legacy.path == target.path) return
        val pending = legacy.listFiles()?.filter { it.isDirectory && File(it, "meta.json").isFile }.orEmpty()
        for (d in pending) {
            val dst = File(target, d.name)
            if (dst.exists()) continue
            val ok = runCatching { d.copyRecursively(dst, overwrite = false) }.getOrDefault(false)
            if (ok) d.deleteRecursively() else dst.deleteRecursively()
        }
        if (legacy.listFiles()?.none { it.isDirectory && File(it, "meta.json").isFile } != false) locations.migrated = true
    }

    /** Copies a picked audio file into the project so it survives the Uri's permission going away. */
    suspend fun copyAudioIn(id: String, ctx: Context, uri: Uri): File? = withContext(Dispatchers.IO) {
        runCatching {
            val ext = ctx.contentResolver.getType(uri)?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "bin"
            val dst = File(audioDir(id), "${UUID.randomUUID()}.$ext")
            val input = ctx.contentResolver.openInputStream(uri) ?: return@runCatching null
            input.use { i -> FileOutputStream(dst).use { o -> i.copyTo(o) } }
            dst
        }.getOrNull()
    }

    private inline fun writeAtomic(target: File, block: (OutputStream) -> Unit) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { fos ->
            block(fos)
            fos.flush()
            fos.fd.sync()
        }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        private const val ORDER_FILE = "order.json"

        @Volatile private var instance: ProjectRepository? = null

        fun get(ctx: Context): ProjectRepository = instance ?: synchronized(this) {
            instance ?: ProjectRepository(StorageLocations.get(ctx)).also { instance = it }
        }
    }
}
