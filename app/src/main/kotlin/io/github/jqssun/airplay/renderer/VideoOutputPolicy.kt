package io.github.jqssun.airplay.renderer

/** Keep reference frames in the decoder; discard only decoded pictures that are already stale. */
internal class VideoOutputPolicy(private val maxBacklogUs: Long = 150_000L) {
    private var latestInputUs: Long? = null

    fun queued(presentationTimeUs: Long) {
        latestInputUs = latestInputUs?.let { maxOf(it, presentationTimeUs) } ?: presentationTimeUs
    }

    fun backlogUs(presentationTimeUs: Long): Long =
        latestInputUs?.let { (it - presentationTimeUs).coerceAtLeast(0L) } ?: 0L

    fun shouldRender(presentationTimeUs: Long): Boolean = backlogUs(presentationTimeUs) <= maxBacklogUs

    fun reset() { latestInputUs = null }
}
