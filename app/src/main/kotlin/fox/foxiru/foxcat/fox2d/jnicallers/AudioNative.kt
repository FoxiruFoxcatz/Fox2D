package fox.foxiru.foxcat.fox2d.jnicallers

import fox.foxiru.foxcat.fox2d.BuildConfig
/** JNI surface for the FFmpeg + Oboe test harness (see fox_main.cpp). */
object AudioNative {
    /** Non-null if the native library failed to load (missing dependency, wrong ABI, ...). */
    val loadError: Throwable? = try {
        System.loadLibrary(BuildConfig.NATIVE_LIB_NAME) // must match LIB_NAME in CMakeLists.txt
        null
    } catch (t: UnsatisfiedLinkError) {
        t
    }

    /** FFmpeg/Oboe versions, decoders and demuxers compiled in. */
    external fun versions(): String

    /** Container + stream + tag dump. Native dup()s [fd]; caller may close its copy afterwards. */
    external fun probe(fd: Int): String

    /** Full decode, no playback: throughput, peak/RMS, bad packets. */
    external fun decodeTest(fd: Int): String

    /**
     * Onset-flux tempogram BPM estimate. Decodes at most [analysisSec] (after skipping [skipSec]),
     * so it is fast; blocks the calling thread. Native dup()s [fd].
     */
    external fun detectBpm(
        fd: Int,
        minBpm: Double,
        maxBpm: Double,
        analysisSec: Double,
        skipSec: Double,
        fftSize: Int,
        hop: Int,
    ): String

    /** Opens Shared and Exclusive output streams and reports what the device grants. */
    external fun oboeProbe(): String

    /** FFmpeg decode thread -> ring -> Oboe callback. Blocks ~250 ms for prebuffer. */
    external fun play(fd: Int): String

    /** 440 Hz sine straight from the Oboe callback (no FFmpeg involved). */
    external fun toneStart(): String

    /** Stops everything. Returns the last decoder/stream message ("" if none). */
    external fun stop(): String

    external fun setPaused(paused: Boolean)

    /** [state, posMs, durMs, underruns, bufferedMs]. Lock-free, safe to poll. */
    external fun status(): LongArray
}
