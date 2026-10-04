package fox.foxiru.foxcat.fox2d.jnicallers

object NativeExporter {
    @JvmStatic external fun nativeEncoders(): String

    @JvmStatic external fun nativeCreate(
        path: String,
        side: Int,
        fps: Int,
        srcFps: Int,
        bgArgb: Int,
        codec: String,
        crf: Float,
        videoKbps: Int,
        preset: String,
        audioKbps: Int,
        extra: String,
        celIds: IntArray,
        celLens: IntArray,
        celRows: IntArray,   // index of the timeline row (bottom -> top) each cel belongs to
        rowStarts: IntArray, // frame (at the project fps) where each row starts
        rowXf: FloatArray,   // 7 floats per row: tx, ty, sx, sy, rotDeg, pivotX, pivotY (paper units)
        rowKeyCounts: IntArray, // keyframes per row
        rowKeys: FloatArray,    // every row's keys back to back, 11 floats each (Keyframe.writeTo), row-local source frames
        rigBlob: FloatArray,    // packRigs(rows): [nRows, (len, row blob)...], see fox_rig.h parseSet
        rowAttach: IntArray,    // 2 ints per row: parent row, bone (-1 = none); see packAttach
        layerIds: IntArray,
        layerVis: BooleanArray,
        strokeMeta: IntArray,
        strokeSize: FloatArray,
        strokePts: FloatArray,
    ): Long

    @JvmStatic external fun nativeRun(handle: Long): Int
    @JvmStatic external fun nativeProgress(handle: Long): Float
    @JvmStatic external fun nativeCancel(handle: Long)
    @JvmStatic external fun nativeError(handle: Long): String
    @JvmStatic external fun nativeEncoderUsed(handle: Long): String
    @JvmStatic external fun nativeWarning(handle: Long): String
    @JvmStatic external fun nativeDestroy(handle: Long)
}
