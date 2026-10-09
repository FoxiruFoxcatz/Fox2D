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

    /**
     * More strokes for the job (same layout as the nativeCreate arrays). Send them in small batches after nativeCreate and
     * before nativeRun, so the Java heap never holds one array with every point of the project.
     */
    @JvmStatic external fun nativeAddStrokes(handle: Long, strokeMeta: IntArray, strokeSize: FloatArray, strokePts: FloatArray): Boolean

    /** Blend / opacity / clipping of the rows (bottom -> top) and layers (by layer id). Call after nativeCreate, before nativeRun. */
    @JvmStatic external fun nativeSetFx(
        handle: Long,
        rowMode: IntArray, rowOpacity: FloatArray, rowClip: BooleanArray,
        layerIds: IntArray, layerMode: IntArray, layerOpacity: FloatArray, layerClip: BooleanArray,
    )

    /** Blend / opacity / clipping of group folders. rowGroup = group id of every row (bottom -> top, -1 = none). */
    @JvmStatic external fun nativeSetGroupFx(
        handle: Long,
        rowGroup: IntArray, groupIds: IntArray, groupMode: IntArray, groupOpacity: FloatArray, groupClip: BooleanArray,
    )

    @JvmStatic external fun nativeRun(handle: Long): Int
    @JvmStatic external fun nativeProgress(handle: Long): Float
    @JvmStatic external fun nativeCancel(handle: Long)
    @JvmStatic external fun nativeError(handle: Long): String
    @JvmStatic external fun nativeEncoderUsed(handle: Long): String
    @JvmStatic external fun nativeWarning(handle: Long): String
    @JvmStatic external fun nativeDestroy(handle: Long)
}
