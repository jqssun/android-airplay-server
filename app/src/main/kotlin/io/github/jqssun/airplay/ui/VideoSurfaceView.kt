package io.github.jqssun.airplay.ui

import android.view.Surface
import android.view.SurfaceHolder
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
    modifier: Modifier = Modifier,
    onSurfaceSizeChanged: ((Surface, Int, Int) -> Unit)? = null,
    useNativeResolution: Boolean = true
) {
    val callbacks = remember {
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                onSurfaceAvailable(holder.surface)
            }
            override fun surfaceChanged(holder: SurfaceHolder, fmt: Int, w: Int, h: Int) {
                if (onSurfaceSizeChanged != null) onSurfaceSizeChanged(holder.surface, w, h)
                else onSurfaceAvailable(holder.surface)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                onSurfaceDestroyed(holder.surface)
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            NativeVideoSurface(ctx).also {
                it.nativeResolutionEnabled = useNativeResolution
                it.holder.addCallback(callbacks)
            }
        },
        update = {
            it.nativeResolutionEnabled = useNativeResolution
            it.updateBufferSize()
        },
        modifier = if (applyAspectRatio) {
            modifier
                .aspectRatio(aspectRatio, matchHeightConstraintsFirst = aspectRatio < 1f)
                .fillMaxSize()
        } else {
            modifier
        }
    )
}
