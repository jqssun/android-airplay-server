package io.github.jqssun.airplay.renderer

import kotlin.math.roundToInt

internal data class VideoViewport(val x: Int, val y: Int, val width: Int, val height: Int)

internal data class VideoCrop(val left: Float = 0f, val bottom: Float = 0f, val right: Float = 1f, val top: Float = 1f)
internal data class VideoFraming(val viewport: VideoViewport, val crop: VideoCrop = VideoCrop())

/** Fill is deliberately limited to landscape; portrait phone controls must remain visible. */
internal fun videoFraming(
    videoWidth: Int, videoHeight: Int, surfaceWidth: Int, surfaceHeight: Int, fillLandscape: Boolean,
): VideoFraming {
    val fit = fitVideoViewport(videoWidth, videoHeight, surfaceWidth, surfaceHeight)
    if (!fillLandscape || videoWidth <= videoHeight || videoHeight <= 0) return VideoFraming(fit)
    val sourceAspect = videoWidth.toDouble() / videoHeight
    val targetAspect = surfaceWidth.toDouble() / surfaceHeight
    val crop = if (sourceAspect > targetAspect) {
        val inset = ((1.0 - targetAspect / sourceAspect) / 2.0).toFloat()
        VideoCrop(left = inset, right = 1f - inset)
    } else {
        val inset = ((1.0 - sourceAspect / targetAspect) / 2.0).toFloat()
        VideoCrop(bottom = inset, top = 1f - inset)
    }
    return VideoFraming(VideoViewport(0, 0, surfaceWidth, surfaceHeight), crop)
}

/** Fit the source inside a native panel surface without stretching or cropping. */
internal fun fitVideoViewport(videoWidth: Int, videoHeight: Int, surfaceWidth: Int, surfaceHeight: Int): VideoViewport {
    require(surfaceWidth > 0 && surfaceHeight > 0)
    if (videoWidth <= 0 || videoHeight <= 0) return VideoViewport(0, 0, surfaceWidth, surfaceHeight)
    val scale = minOf(surfaceWidth.toDouble() / videoWidth, surfaceHeight.toDouble() / videoHeight)
    val width = (videoWidth * scale).roundToInt().coerceIn(1, surfaceWidth)
    val height = (videoHeight * scale).roundToInt().coerceIn(1, surfaceHeight)
    return VideoViewport((surfaceWidth - width) / 2, (surfaceHeight - height) / 2, width, height)
}
