package io.github.jqssun.airplay.ui.gestures

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.jqssun.airplay.R

@Composable
fun VideoPlayerGestures(
    enabled: Boolean,
    locked: Boolean,
    onTap: () -> Unit,
    tapGestureState: TapGestureState,
    seekGestureState: SeekGestureState,
    volumeAndBrightnessGestureState: VolumeAndBrightnessGestureState,
    zoomState: ZoomState,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(enabled, locked) {
                detectTapGestures(
                    onTap = {
                        // taps between double-tap seeks keep accumulating, not toggling controls
                        if (tapGestureState.seekMillis != 0L) return@detectTapGestures
                        onTap()
                    },
                    onDoubleTap = {
                        if (enabled && !locked) tapGestureState.handleDoubleTap(offset = it, size = size)
                    },
                )
            }
            .pointerInput(enabled, locked) {
                if (!enabled || locked) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = seekGestureState::onDragStart,
                    onDragEnd = seekGestureState::onSeekEnd,
                    onHorizontalDrag = seekGestureState::onDrag,
                )
            }
            .pointerInput(enabled, locked) {
                if (!enabled || locked) return@pointerInput
                detectVerticalDragGestures(
                    onDragStart = { volumeAndBrightnessGestureState.onDragStart(it, size) },
                    onDragEnd = volumeAndBrightnessGestureState::onDragEnd,
                    onVerticalDrag = volumeAndBrightnessGestureState::onDrag,
                )
            }
            .pointerInput(enabled, locked) {
                if (!enabled || locked) return@pointerInput
                detectZoomGestures(
                    onZoom = zoomState::onZoom,
                    onZoomEnd = zoomState::onZoomEnd,
                )
            },
    )
}

@Composable
fun DoubleTapIndicator(tapGestureState: TapGestureState, modifier: Modifier = Modifier) {
    if (tapGestureState.seekMillis == 0L) return
    val forward = tapGestureState.seekMillis > 0
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = if (forward) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction = 0.35f)
                .clip(if (forward) RightSideOvalShape else LeftSideOvalShape)
                .background(Color.White.copy(0.2f))
                .indication(tapGestureState.interactionSource, ripple()),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SeekTriangles(forward = forward)
                Text(
                    text = stringResource(R.string.gesture_seek_seconds, tapGestureState.seekMillis / 1000),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun SeekTriangles(forward: Boolean, modifier: Modifier = Modifier) {
    val alphas = remember { List(3) { Animatable(0f) } }
    LaunchedEffect(Unit) {
        val spec = tween<Float>(150)
        while (true) {
            alphas.forEach { it.animateTo(1f, spec) }
            alphas.forEach { it.animateTo(0f, spec) }
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.rotate(if (forward) 0f else 180f),
    ) {
        alphas.forEach { alpha ->
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(20.dp).graphicsLayer { this.alpha = alpha.value },
                tint = Color.White,
            )
        }
    }
}

private val RightSideOvalShape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path().apply {
            moveTo(size.width, size.height)
            lineTo(size.width, 0f)
            lineTo(size.width * 0.1f, 0f)
            cubicTo(size.width * 0.1f, 0f, -size.width * 0.1f, size.height / 2, size.width * 0.1f, size.height)
            close()
        }
        return Outline.Generic(path)
    }
}

private val LeftSideOvalShape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width * 0.9f, 0f)
            cubicTo(size.width * 0.9f, 0f, size.width * 1.1f, size.height / 2, size.width * 0.9f, size.height)
            lineTo(0f, size.height)
            close()
        }
        return Outline.Generic(path)
    }
}

@Composable
fun VerticalProgressIndicator(
    value: Int,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .heightIn(max = 250.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.background)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(modifier = Modifier.size(BAR_WIDTH), contentAlignment = Alignment.Center) {
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .width(BAR_WIDTH)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                modifier = Modifier
                    .width(BAR_WIDTH)
                    .fillMaxHeight(value.coerceIn(0, 100) / 100f)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Box(modifier = Modifier.size(BAR_WIDTH), contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground)
        }
    }
}

@Composable
fun GestureInfoText(info: String, modifier: Modifier = Modifier) {
    Text(
        text = info,
        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
        color = Color.White,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth(),
    )
}

private val BAR_WIDTH = 32.dp
