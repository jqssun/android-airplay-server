package io.github.jqssun.airplay.ui

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

// shared surface for mirroring (VideoRenderer) and airplay video (AirPlayVideoPlayer)
@Composable
fun VideoSurfaceView(
    onSurfaceAvailable: (Surface) -> Unit,
    onSurfaceDestroyed: (Surface) -> Unit,
    aspectRatio: Float = 16f / 9f,
    // false = caller sizes the surface (content-scale modes)
    applyAspectRatio: Boolean = true,
    modifier: Modifier = Modifier
) {
    val callbacks = remember {
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                onSurfaceAvailable(holder.surface)
            }
            override fun surfaceChanged(holder: SurfaceHolder, fmt: Int, w: Int, h: Int) {
                onSurfaceAvailable(holder.surface)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                onSurfaceDestroyed(holder.surface)
            }
        }
    }

    // For portrait videos (aspect < 1.0), use a Box to center and pillarbox
    if (applyAspectRatio && aspectRatio < 1f) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).also {
                        it.holder.addCallback(callbacks)
                    }
                },
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(aspectRatio, matchHeightConstraintsFirst = true)
            )
        }
    } else {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).also {
                    it.holder.addCallback(callbacks)
                }
            },
            modifier = if (applyAspectRatio) {
                modifier
                    .aspectRatio(aspectRatio, matchHeightConstraintsFirst = false)
                    .fillMaxSize()
            } else {
                modifier
            }
        )
    }
}
