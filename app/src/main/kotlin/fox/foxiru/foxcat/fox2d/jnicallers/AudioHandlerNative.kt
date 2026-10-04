package fox.foxiru.foxcat.fox2d.jnicallers

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Result of a successful import. */
class ImportedAudio(val handle: Int, val name: String, val durationMs: Long)

/**
 * JNI bridge to fox_audio.cpp (same shared library as the canvas).
 *
 *  - [nativeLoad]   decodes a whole file with FFmpeg to 48 kHz stereo PCM and builds waveform peaks.
 *                   Blocking: call it from Dispatchers.IO ([import] does).
 *  - [waveform]     min/max peaks for any source range at any zoom (fast, call from the draw pass).
 *  - [setClips]     the timeline's clip table -> lock-free slots read by the Oboe callback.
 *  - [play] / [pause] / [positionMs]  transport; the audio clock is the master clock while playing.
 */
object AudioHandlerNative {

    @JvmStatic external fun nativeLoad(path: String): Int
    @JvmStatic external fun nativeDurationMs(handle: Int): Long
    @JvmStatic external fun nativeRelease(handle: Int)
    @JvmStatic external fun nativeWaveform(handle: Int, startMs: Double, endMs: Double, buckets: Int, out: FloatArray)
    @JvmStatic external fun nativeSetClips(
        handles: IntArray, startMs: LongArray, inMs: LongArray, lenMs: LongArray, gains: FloatArray,
        keyCounts: IntArray, keys: FloatArray,
    )
    @JvmStatic external fun nativeEnvelope(keys: FloatArray, n: Int, fromMs: Double, toMs: Double, out: FloatArray)
    @JvmStatic external fun nativePlay(positionMs: Long)
    @JvmStatic external fun nativePause()
    @JvmStatic external fun nativePositionMs(): Long

    fun release(handle: Int) = nativeRelease(handle)

    /** Fills [out] with (min, max) pairs in -1..1, two floats per bucket. */
    fun waveform(handle: Int, startMs: Double, endMs: Double, buckets: Int, out: FloatArray) =
        nativeWaveform(handle, startMs, endMs, buckets, out)

    /**
     * [gains] = master volume per clip (0 = muted). [keyCounts] / [keys] = per-clip volume keys, (sourceMs, gain) pairs
     * back to back. A clip with keys follows its smooth envelope (the same curve [envelope] returns); without keys it
     * plays at [gains].
     */
    fun setClips(
        handles: IntArray, startMs: LongArray, inMs: LongArray, lenMs: LongArray, gains: FloatArray,
        keyCounts: IntArray, keys: FloatArray,
    ) = nativeSetClips(handles, startMs, inMs, lenMs, gains, keyCounts, keys)

    /**
     * Fills [out] with the volume curve over SOURCE time [fromMs]..[toMs] (evenly spaced; out.size == 1 = the value at
     * [fromMs]). [keys] = (sourceMs, gain) pairs. Smooth spline, computed in C++ exactly like the mixer does.
     */
    fun envelope(keys: FloatArray, n: Int, fromMs: Double, toMs: Double, out: FloatArray) =
        nativeEnvelope(keys, n, fromMs, toMs, out)

    fun play(positionMs: Long) = nativePlay(positionMs)
    fun pause() = nativePause()
    fun positionMs(): Long = nativePositionMs()

    /** Copies the picked document into the cache (FFmpeg wants a path), decodes it, deletes the copy. */
    suspend fun import(context: Context, uri: Uri): ImportedAudio? = withContext(Dispatchers.IO) {
        val name = displayName(context, uri)
        val tmp = File(context.cacheDir, "import_${System.nanoTime()}.audio")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: return@withContext null
            val handle = nativeLoad(tmp.absolutePath)
            if (handle < 0) null else ImportedAudio(handle, name, nativeDurationMs(handle))
        } finally {
            tmp.delete()
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0).substringBeforeLast('.')
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Audio"
    }
}
