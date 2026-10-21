package io.github.jqssun.airplay.ui

import android.view.Surface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import kotlin.math.roundToInt

/** Keep the native view attached; UI branches supply only its current viewport. */
@Composable
fun PersistentVideoSurface(
    aspectRatio: Float,
    revealVideo: Boolean,
    onSurfaceAvailable: (Surface) -> Unit,
    onSurfaceDestroyed: (Surface) -> Unit,
    content: @Composable (@Composable () -> Unit) -> Unit
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntRect(0, 0, 1, 1)) }
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        // This call site never moves between Compose branches. Keeping the last
        // viewport while covered by Settings/Logs also keeps the last buffer.
        VideoSurfaceView(
            onSurfaceAvailable, onSurfaceDestroyed,
            applyAspectRatio = false,
            modifier = Modifier
                .offset { IntOffset(viewport.left, viewport.top) }
                .requiredSize(
                    with(density) { viewport.width.toDp() },
                    with(density) { viewport.height.toDp() }
                )
        )
        content {
            Box(
                Modifier
                    .aspectRatio(aspectRatio, matchHeightConstraintsFirst = aspectRatio < 1f)
                    .fillMaxSize()
                    .onGloballyPositioned {
                        val bounds = it.boundsInRoot().translate(-origin)
                        if (bounds.width > 0 && bounds.height > 0) {
                            viewport = IntRect(
                                bounds.left.roundToInt(), bounds.top.roundToInt(),
                                bounds.right.roundToInt(), bounds.bottom.roundToInt()
                            )
                        }
                    }
                    // The SurfaceView is below the app window. Open only its
                    // slot; sibling controls and the rest of the UI stay opaque.
                    .drawBehind {
                        if (revealVideo) drawRect(Color.Transparent, blendMode = BlendMode.Clear)
                    }
            )
        }
    }
}
