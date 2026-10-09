package fox.foxiru.foxcat.fox2d.main_canvas

/**
 * What the native exporter is given. A group's Re-timing is only a time mapping inside the editor (nothing is stored per
 * repeat), but the exporter takes plain "row start + run of drawings + keys" lists, so for the export ONLY the re-timed
 * rows are unrolled here into primitive lists. Nothing is added to the project or to any Compose state, and the lists
 * are dropped when the export is done.
 */
internal class ExportTimeline(val cels: List<Cel>, val rows: List<DrawTrack>)

/**
 * Unrolls every drawing row that sits in a re-timed group (see [retimeFrame]) over the whole group bar:
 *  - drawings: one entry per run of equal frames, built by asking [EditorState.rowFrame] for every frame. A repeat of a drawing
 *    reuses the SOURCE cel id (its strokes are already in the export), a gap is a fresh empty cel.
 *  - transform keys and rig keys: copied per repeat (Loop), scaled (Stretch), or both (Loop & Stretch), so native evaluates
 *    the same curve the editor shows. Freeze / Blank / Off need nothing: a curve already holds its last value.
 * Rows outside re-timed groups are passed through untouched.
 */
internal fun buildExportTimeline(state: EditorState): ExportTimeline {
    val cels = state.cels.toList()
    val rows = state.drawTracks.toList()
    fun groupOf(r: DrawTrack): TrackGroup? =
        state.groupById(r.group)?.takeIf { it.fill != FILL_NONE && state.groupSourceSpan(it.id) != null }
    if (rows.none { groupOf(it) != null }) return ExportTimeline(cels, rows)

    var nextId = (cels.maxOfOrNull { it.id } ?: -1) + 1
    val unrolled = HashMap<Int, List<Cel>>()
    val newRows = ArrayList<DrawTrack>(rows.size)

    for (r in rows) {
        val g = groupOf(r)
        if (g == null) { newRows.add(r); continue }
        val (a, b) = state.groupSourceSpan(g.id)!!
        val end = state.groupBarEndFrame(g.id)
        val own = state.trackCels(r.id)

        // drawings
        val out = ArrayList<Cel>()
        var runIdx = -2          // -2 = no run yet, -1 = nothing shown, else index into [own]
        var runLen = 0
        fun flush() {
            if (runLen <= 0) return
            out.add(if (runIdx >= 0) own[runIdx].copy(len = runLen) else Cel(nextId++, runLen, r.id))
            runLen = 0
        }
        for (f in r.offset until end) {
            val m = state.rowFrame(r.id, f)
            val idx = if (m < 0) -1 else state.celIndexAt(m, r.id)
            if (idx == runIdx && runLen > 0) runLen++ else { flush(); runIdx = idx; runLen = 1 }
        }
        flush()
        unrolled[r.id] = out

        // keys (the row's own and its rig's: same frame space)
        newRows.add(
            r.copy(
                keys = retimeKeys(r.keys, r.offset, g.fill, a, b, end),
                rig = if (r.rig.keys.isEmpty()) r.rig else r.rig.copy(keys = retimeKeys(r.rig.keys, r.offset, g.fill, a, b, end)),
            )
        )
    }

    // keep the original order of everything else; an unrolled row's run goes where its first drawing was
    val outCels = ArrayList<Cel>(cels.size)
    val placed = HashSet<Int>()
    for (c in cels) {
        val run = unrolled[c.track]
        if (run == null) outCels.add(c)
        else if (placed.add(c.track)) outCels.addAll(run)
    }
    return ExportTimeline(outCels, newRows)
}

/** Repeats a Loop / Loop & Stretch bar holds (1 for every other mode). Shared by the exporter and the live canvas. */
internal fun retimeRepeats(mode: Int, a: Int, b: Int, end: Int): Int {
    val p = b - a
    val l = maxOf(end, b) - a
    if (p <= 0 || l <= 0) return 1
    return when (mode) {
        FILL_LOOP -> (l + p - 1) / p
        FILL_LOOP_STRETCH -> maxOf(1, (l + p / 2) / p)
        else -> 1
    }
}

/**
 * Keys of a row that starts at [offset] (key frames are row-local), inside a group whose content is [a, b) and whose bar ends at [end].
 * Loop is exact; Stretch / Loop & Stretch move each key to the nearest whole frame (at most half a frame off).
 * Used by the exporter AND by the live canvas (EditorState.liveRows), so what you see is what is exported.
 * A channel with a single key is a constant: it is passed through untouched (no scaling, no per-repeat copies).
 */
internal fun retimeKeys(keys: List<ChanKey>, offset: Int, mode: Int, a: Int, b: Int, end: Int): List<ChanKey> {
    if (keys.isEmpty()) return keys
    if (mode != FILL_STRETCH && mode != FILL_LOOP && mode != FILL_LOOP_STRETCH) return keys   // Off / Freeze / Blank: a curve holds its first / last value by itself
    val p = b - a
    val e = maxOf(end, b)
    val l = e - a
    if (p <= 0 || l <= 0) return keys
    val byChan = keys.groupBy { it.chan }
    if (byChan.values.none { it.size > 1 }) return keys   // nothing moves (e.g. a 1-frame drawing): nothing to re-time

    val out = ArrayList<ChanKey>(keys.size)
    for (ks in byChan.values) if (ks.size == 1) out.add(ks[0])
    if (mode == FILL_STRETCH) {
        for (ks in byChan.values) if (ks.size > 1) for (k in ks) {
            out.add(k.copy(frame = Math.round(a + (offset + k.frame - a).toDouble() * l / p).toInt() - offset))
        }
        out.sortWith(ChanKeyOrder)
        return out
    }
    val n = retimeRepeats(mode, a, b, end)
    // global frame where source frame [sk] sits in repeat [r]
    fun pos(r: Int, sk: Int): Int =
        if (mode == FILL_LOOP) sk + r * p else Math.round(a + (r + (sk - a).toDouble() / p) * l / n).toInt()

    val seen = HashSet<Long>()
    fun add(k: ChanKey, local: Int) {
        if (local < 0) return
        if (seen.add((k.chan.toLong() shl 32) or local.toLong())) out.add(k.copy(frame = local))
    }
    for (r in 0 until n) {
        val start = pos(r, a)
        if (start >= e) break
        for (ks in byChan.values) {
            if (ks.size < 2) continue
            if (r > 0) {
                // the wrap is a jump: the first key's value takes over exactly on the repeat's first frame, the last key's value holds until the frame before
                add(ks.first(), start - offset)
                add(ks.last(), start - offset - 1)
            }
            for (k in ks) add(k, pos(r, offset + k.frame) - offset)
        }
    }
    out.sortWith(ChanKeyOrder)
    return out
}
