package io.github.jqssun.airplay.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Point
import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.SurfaceView
import android.view.WindowManager
import kotlin.math.roundToInt

/** Size the video buffers in panel pixels, independently of the TV's scaled UI. */
internal class NativeVideoSurface(context: Context) : SurfaceView(context) {
    private var bufferSize: Pair<Int, Int>? = null
    var nativeResolutionEnabled = true
        set(value) {
            if (field == value) return
            field = value
            bufferSize = null
            updateBufferSize()
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateBufferSize()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateBufferSize()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateBufferSize()
    }

    @Suppress("DEPRECATION")
    fun updateBufferSize() {
        val display = display ?: return
        // Display.getSize() may shrink to this Activity's PiP bounds. Use the
        // full logical display as the scale reference, never the small window.
        val uiSize = if (Build.VERSION.SDK_INT >= 30) {
            val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else Point().also(display::getRealSize)
        val mode = display.mode
        val rotated = display.rotation == Surface.ROTATION_90 || display.rotation == Surface.ROTATION_270
        val panelWidth = if (rotated) mode.physicalHeight else mode.physicalWidth
        val panelHeight = if (rotated) mode.physicalWidth else mode.physicalHeight
        val size = if (nativeResolutionEnabled) {
            nativeVideoBufferSize(width, height, uiSize.x, uiSize.y, panelWidth, panelHeight)
        } else if (width > 0 && height > 0) width to height else null
        if (size == null) return
        if (size == bufferSize) return
        bufferSize = size
        holder.setFixedSize(size.first, size.second)
        Log.i("NativeVideoSurface", "UI ${width}x$height -> buffer ${size.first}x${size.second}")
    }
}

internal fun nativeVideoBufferSize(
    viewWidth: Int, viewHeight: Int,
    uiWidth: Int, uiHeight: Int,
    panelWidth: Int, panelHeight: Int,
): Pair<Int, Int>? {
    if (listOf(viewWidth, viewHeight, uiWidth, uiHeight, panelWidth, panelHeight).any { it <= 0 }) return null
    return (viewWidth.toDouble() * panelWidth / uiWidth).roundToInt().coerceIn(1, panelWidth) to
        (viewHeight.toDouble() * panelHeight / uiHeight).roundToInt().coerceIn(1, panelHeight)
}
