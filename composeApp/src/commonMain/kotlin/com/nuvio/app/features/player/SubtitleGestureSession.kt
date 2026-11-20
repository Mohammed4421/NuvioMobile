package com.nuvio.app.features.player

/**
 * One move or pinch of the subtitles. While the fingers move, the new style is only a preview
 * (shown at once, not stored); it is stored once, when the gesture ends, or when it is left
 * unfinished (the player closes, a new gesture starts). Before Build 15 every frame of a drag
 * stored the whole subtitle style (twelve settings) again.
 */
internal class SubtitleGestureSession(
    private val persist: (SubtitleStyleState) -> Unit,
) {
    private var start: SubtitleStyleState? = null

    /** A gesture is in progress. */
    val active: Boolean get() = start != null

    /** The style shown while a gesture is in progress, or null. */
    var preview: SubtitleStyleState? = null
        private set

    fun begin(current: SubtitleStyleState) {
        flush()
        start = current
    }

    /** Sets the preview from the style at the start of the gesture. */
    fun update(current: SubtitleStyleState, transform: (SubtitleStyleState) -> SubtitleStyleState): SubtitleStyleState {
        val from = start ?: current.also { start = it }
        val updated = transform(from)
        preview = updated.takeIf { it != from }
        return updated
    }

    /** Stores the final style (if it changed) and ends the gesture. */
    fun flush() {
        val final = preview
        start = null
        preview = null
        if (final != null) persist(final)
    }
}
