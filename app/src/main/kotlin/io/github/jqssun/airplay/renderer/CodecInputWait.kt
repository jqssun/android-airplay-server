package io.github.jqssun.airplay.renderer

/** Called with monitor held; callbacks share the renderer's codec ownership lock. */
internal fun awaitCodecInput(
    monitor: Object,
    isCurrent: () -> Boolean,
    dequeue: () -> Int,
    drainOutput: () -> Unit
): Int? {
    // Keep the compressed reference frame until the codec accepts it or its
    // session becomes obsolete. Release ownership between polls so Stop and
    // Surface lifecycle callbacks can acquire the monitor and cancel/change it.
    while (isCurrent()) {
        val index = dequeue()
        if (index >= 0) return index
        drainOutput()
        monitor.wait(1)
    }
    return null
}
