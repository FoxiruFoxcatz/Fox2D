// Kotlin side of the brush-export fix. Adapt the names marked ADAPT to your InkStroke / NativeExporter.
//
// Wire format (JNI signatures of nativeCreate / nativeAddStrokes are UNCHANGED):
//   meta  (5 ints per stroke): cel, layer, argb, mode, ptsCount
//        mode: 0 plain, 1 plain erase, 2 image, 3 BRUSH, 4 BRUSH ERASE      <- 3 and 4 are new
//   size  (1 float per stroke): ignored for modes 3 / 4 (keep sending something)
//   pts   (ptsCount * 2 floats per stroke), for modes 3 / 4:
//        [ nParams, params[0..nParams-1], zero pad up to a multiple of 4, then (x, y, pressure, ms) * nSamples ]
//        ptsCount = totalFloats / 2   (totalFloats is always a multiple of 4, so this is exact)

package fox.foxiru.foxcat.fox2d.jnicallers

object BrushExportPacking {

    /** Floats this brush stroke occupies in the shared `pts` array. */
    fun brushFloatCount(paramCount: Int, nSamples: Int): Int =
        ((1 + paramCount + 3) and 3.inv()) + nSamples * 4

    /**
     * Appends one brush stroke to the export arrays.
     * [params]  = the stroke's own parameter array, INCLUDING its stored P_SEED (BP.SEED), exactly what the canvas drew with.
     * [samples] = the stroke's stored samples, flat x, y, pressure, ms (paper 0..1, ms since stroke start).
     */
    fun appendBrushStroke(
        meta: MutableList<Int>,
        sizes: MutableList<Float>,
        pts: MutableList<Float>,
        cel: Int, layer: Int, argb: Int, erase: Boolean,
        params: FloatArray, samples: FloatArray,
    ) {
        val nSamples = samples.size / 4
        if (nSamples == 0) return
        val total = brushFloatCount(params.size, nSamples)

        meta += cel; meta += layer; meta += argb
        meta += if (erase) 4 else 3
        meta += total / 2
        sizes += params.getOrElse(0) { 10f }          // P_SIZE, informational only

        pts += params.size.toFloat()
        for (v in params) pts += v
        val pad = ((1 + params.size + 3) and 3.inv()) - (1 + params.size)
        repeat(pad) { pts += 0f }
        for (i in 0 until nSamples * 4) pts += samples[i]
    }
}

// Where you build the stroke list today (ADAPT), something like:
//
//   for (st in strokesOfCel) {
//       if (st.isImage)      { /* mode 2, unchanged */ }
//       else if (st.brush != null) {            // custom brush (non-default tip / scatter / grain / ...)
//           BrushExportPacking.appendBrushStroke(
//               meta, sizes, pts, st.cel, st.layer, st.argb, st.erase,
//               st.brush.params,                // FloatArray, same one the canvas uses (with seed)
//               st.samples)                     // FloatArray x,y,p,t
//       } else               { /* plain stroke, unchanged */ }
//   }
//
// Keep the ORIGINAL draw order: do not collect brush strokes in a separate pass, they must be interleaved with plain
// strokes of the same layer in the order they were drawn (undo/erase depend on it).
