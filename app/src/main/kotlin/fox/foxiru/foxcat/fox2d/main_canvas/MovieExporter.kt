package fox.foxiru.foxcat.fox2d.main_canvas

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import fox.foxiru.foxcat.fox2d.jnicallers.NativeExporter
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

enum class ExportQuality(val label: String, val crf: Float, val bitsPerPixel: Float) {
    Draft("Draft", 29f, 0.04f),
    Good("Good", 24f, 0.08f),
    High("High", 20f, 0.14f),
    Max("Max", 16f, 0.24f),
}

enum class ExportCodec(val label: String, val id: String, val encoders: Set<String>) {
    H264("H.264", "h264", setOf("libx264", "h264_mediacodec", "libopenh264", "mpeg4")),
    HEVC("HEVC", "hevc", setOf("libx265", "hevc_mediacodec")),
}

data class ExportOptions(
    val side: Int = 1080,
    val fps: Int = 12,
    val quality: ExportQuality = ExportQuality.High,
    val codec: ExportCodec = ExportCodec.H264,
    val preset: String = "medium",
    val crf: Float? = null,
    val videoKbps: Int? = null,
    val audio: Boolean = true,
    val audioKbps: Int = 192,
    val background: Color = Color.White,
    val fileName: String? = null,
    val extra: Map<String, String> = emptyMap(),
)

sealed interface ExportResult {
    data class Done(val uri: Uri, val encoder: String, val warning: String) : ExportResult
    data class Failed(val message: String) : ExportResult
}

object MovieExporter {
    const val SUBDIR = "Fox2D"

    fun availableEncoders(): Set<String> =
        NativeExporter.nativeEncoders().split(',').filter { it.isNotBlank() }.toSet()

    fun supports(codec: ExportCodec, available: Set<String> = availableEncoders()): Boolean =
        "mux:mp4" in available && codec.encoders.any { it in available }

    suspend fun export(
        context: Context,
        state: EditorState,
        options: ExportOptions = ExportOptions(),
        onProgress: (Float) -> Unit = {},
    ): ExportResult = coroutineScope {
        val app = context.applicationContext
        val strokes = state.strokes.toList()
        // rows of re-timed groups (Loop / Freeze / Stretch ...) are unrolled for the exporter only; everything else passes through
        val timeline = buildExportTimeline(state)
        val cels = timeline.cels
        val layers = state.drawTracks.flatMap { it.layers }   // ids are unique across rows
        val rows = timeline.rows // bottom -> top, same order the canvas composites
        val groups = state.groups.toList()
        val srcFps = state.fps

        val side = (options.side.coerceIn(64, 4096) / 2) * 2
        val fps = options.fps.coerceIn(1, 120)
        val autoKbps = (side.toLong() * side * fps * options.quality.bitsPerPixel / 1000f).toLong()
            .coerceIn(500L, 200_000L).toInt()
        val kbps = options.videoKbps ?: autoKbps
        val crf = options.crf ?: if (options.videoKbps != null) -1f else options.quality.crf
        val audioKbps = if (options.audio) options.audioKbps.coerceIn(32, 512) else 0
        val extra = options.extra.entries.joinToString(";") { "${it.key}=${it.value}" }

        val tmp = File(app.cacheDir, "fox_export_${System.nanoTime()}.mp4")
        try {
            val meta = IntArray(strokes.size * 5)
            val sizes = FloatArray(strokes.size)
            var ptCount = 0
            for (s in strokes) ptCount += if (s.image != null) 6 else s.pts.size * 2
            val pts = FloatArray(ptCount)
            var o = 0
            for ((i, s) in strokes.withIndex()) {
                meta[i * 5] = s.cel
                meta[i * 5 + 1] = s.layer
                val im = s.image
                if (im != null) {
                    meta[i * 5 + 2] = 0; meta[i * 5 + 3] = 2; meta[i * 5 + 4] = 3   // 3 "points" = 6 floats
                    sizes[i] = 0f
                    pts[o++] = im.cx; pts[o++] = im.cy; pts[o++] = im.w; pts[o++] = im.h; pts[o++] = im.rot
                    pts[o++] = ImageStore.handleOf(im.file).toFloat()
                    continue
                }
                meta[i * 5 + 2] = s.color.toArgb()
                meta[i * 5 + 3] = if (s.erase) 1 else 0
                meta[i * 5 + 4] = s.pts.size
                sizes[i] = s.size
                for (p in s.pts) { pts[o++] = p.x; pts[o++] = p.y }
            }

            val h = NativeExporter.nativeCreate(
                tmp.absolutePath, side, fps, srcFps, options.background.toArgb(),
                options.codec.id, crf, kbps, options.preset, audioKbps, extra,
                IntArray(cels.size) { cels[it].id }, IntArray(cels.size) { cels[it].len },
                IntArray(cels.size) { i -> rows.indexOfFirst { it.id == cels[i].track }.coerceAtLeast(0) },
                IntArray(rows.size) { rows[it].offset },
                FloatArray(rows.size * 7).also { a -> rows.forEachIndexed { i, r -> r.xf.writeTo(a, i * 7) } },
                IntArray(rows.size) { rows[it].keys.size },
                FloatArray(rows.sumOf { it.keys.size } * KEY_STRIDE).also { a ->
                    var o = 0
                    for (r in rows) { r.packedKeys.copyInto(a, o); o += r.packedKeys.size }
                },
                packRigs(rows),
                packAttach(rows),
                IntArray(layers.size) { layers[it].id }, BooleanArray(layers.size) { layers[it].visible },
                meta, sizes, pts,
            )
            if (h == 0L) return@coroutineScope ExportResult.Failed("Invalid export data")

            // Blend modes / opacity / clipping masks: rows bottom -> top, layers by id. Built from the same snapshot as above.
            ExportFx(
                IntArray(rows.size) { rows[it].blend }, FloatArray(rows.size) { rows[it].opacity }, BooleanArray(rows.size) { rows[it].clip },
                IntArray(layers.size) { layers[it].id }, IntArray(layers.size) { layers[it].blend },
                FloatArray(layers.size) { layers[it].opacity }, BooleanArray(layers.size) { layers[it].clip },
                IntArray(rows.size) { i -> rows[i].group.takeIf { g -> groups.any { it.id == g } } ?: -1 },
                IntArray(groups.size) { groups[it].id }, IntArray(groups.size) { groups[it].blend },
                FloatArray(groups.size) { groups[it].opacity }, BooleanArray(groups.size) { groups[it].clip },
            ).applyTo(h)

            try {
                val finished = AtomicBoolean(false)
                val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        if (!finished.get()) NativeExporter.nativeCancel(h)
                    }
                }
                val poll = launch {
                    while (true) {
                        onProgress(NativeExporter.nativeProgress(h))
                        delay(80)
                    }
                }
                val rc = try {
                    withContext(Dispatchers.Default) { NativeExporter.nativeRun(h) }
                } finally {
                    finished.set(true)
                    watcher.cancel()
                    poll.cancel()
                }
                if (rc == 1) throw CancellationException("export cancelled")
                if (rc != 0) {
                    val err = NativeExporter.nativeError(h)
                    return@coroutineScope ExportResult.Failed(err.ifEmpty { "Export failed ($rc)" })
                }
                val encoder = NativeExporter.nativeEncoderUsed(h)
                val warning = NativeExporter.nativeWarning(h)
                onProgress(1f)
                val name = (options.fileName ?: defaultName()).removeSuffix(".mp4") + ".mp4"
                val uri = withContext(Dispatchers.IO) { saveToMovies(app, tmp, name) }
                ExportResult.Done(uri, encoder, warning)
            } finally {
                NativeExporter.nativeDestroy(h)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ExportResult.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            tmp.delete()
        }
    }

    private fun defaultName(): String =
        "Fox2D_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun saveToMovies(context: Context, src: File, name: String): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + SUBDIR)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            try {
                val out = resolver.openOutputStream(uri) ?: throw IllegalStateException("cannot open $uri")
                out.use { o -> src.inputStream().use { it.copyTo(o) } }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return uri
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), SUBDIR)
        dir.mkdirs()
        val dst = File(dir, name)
        src.copyTo(dst, overwrite = true)
        MediaScannerConnection.scanFile(context, arrayOf(dst.absolutePath), arrayOf("video/mp4"), null)
        return Uri.fromFile(dst)
    }
}
