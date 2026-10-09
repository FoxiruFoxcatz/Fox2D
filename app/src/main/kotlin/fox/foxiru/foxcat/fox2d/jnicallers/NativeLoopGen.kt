package fox.foxiru.foxcat.fox2d.jnicallers

/**
 * JNI bridge to fox_loopgen.cpp: the live Loop / Freeze generator of Track Groups, running on its own C++ thread behind a
 * moodycamel lock-free queue. Nothing here blocks: [submit] copies the request and returns, [poll] returns a finished
 * answer or null.
 *
 * Request / answer layouts are documented at the top of fox_loopgen.cpp (EditorState packs and unpacks them).
 */
object NativeLoopGen {
    init {
        AudioNative.loadError   // touching AudioNative loads the shared library (same lib as the canvas / audio)
    }

    /** Queue a generation for [group]. A newer [ticket] for the same group makes every older one obsolete. */
    @JvmStatic external fun nativeSubmit(group: Int, ticket: Int, req: IntArray)

    /** [group, ticket, status, ...answer] of one finished request, or null when nothing is ready. */
    @JvmStatic external fun nativePoll(): IntArray?

    /** The group no longer wants copies; queued / running work for it is dropped. */
    @JvmStatic external fun nativeCancel(group: Int)
}
