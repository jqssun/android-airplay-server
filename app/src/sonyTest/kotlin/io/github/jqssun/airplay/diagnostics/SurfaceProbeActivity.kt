package io.github.jqssun.airplay.diagnostics

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import io.github.jqssun.airplay.ui.NativeVideoSurface

/** Isolated test page: exercises real Surface sizing without starting an AirPlay server. */
class SurfaceProbeActivity : Activity() {
    lateinit var videoView: SurfaceView
        private set
    @Volatile var changedSize: Pair<Int, Int>? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = 5894
        videoView = NativeVideoSurface(this).apply {
            nativeResolutionEnabled = intent.getBooleanExtra("native_resolution", true)
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) = Unit
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    changedSize = width to height
                }
                override fun surfaceDestroyed(holder: SurfaceHolder) { changedSize = null }
            })
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(videoView, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        })
    }

    fun resizeVideo(width: Int, height: Int) {
        videoView.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)
    }
}
