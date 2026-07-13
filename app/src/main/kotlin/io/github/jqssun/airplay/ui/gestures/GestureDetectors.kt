package io.github.jqssun.airplay.ui.gestures

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.abs

suspend fun PointerInputScope.detectHorizontalDragGestures(
    onDragStart: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onHorizontalDrag: (change: PointerInputChange, dragAmount: Float) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var overSlop = 0f
        val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
            change.consume()
            overSlop = over
        }
        if (drag != null && currentEvent.changes.count { it.pressed } == 1) {
            onDragStart(drag.position)
            onHorizontalDrag(drag, overSlop)
            horizontalDrag(drag.id) {
                onHorizontalDrag(it, it.positionChange().x)
                it.consume()
            }
            onDragEnd()
        }
    }
}

suspend fun PointerInputScope.detectVerticalDragGestures(
    onDragStart: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onVerticalDrag: (change: PointerInputChange, dragAmount: Float) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var overSlop = 0f
        val drag = awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
            change.consume()
            overSlop = over
        }
        if (drag != null && currentEvent.changes.count { it.pressed } == 1) {
            onDragStart(drag.position)
            onVerticalDrag(drag, overSlop)
            verticalDrag(drag.id) {
                onVerticalDrag(it, it.positionChange().y)
                it.consume()
            }
            onDragEnd()
        }
    }
}

// two-pointer pinch; bails out while a single-pointer drag owns the gesture
suspend fun PointerInputScope.detectZoomGestures(
    onZoom: (zoomChange: Float) -> Unit,
    onZoomEnd: () -> Unit,
) {
    awaitEachGesture {
        var zoom = 1f
        var pan = Offset.Zero
        var pastTouchSlop = false
        var started = false
        val touchSlop = viewConfiguration.touchSlop
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            val canceled = event.changes.any { it.isConsumed } ||
                event.changes.count { it.pressed } != 2
            if (!canceled) {
                started = true
                val zoomChange = event.calculateZoom()
                if (!pastTouchSlop) {
                    zoom *= zoomChange
                    pan += event.calculatePan()
                    val centroidSize = event.calculateCentroidSize(useCurrent = false)
                    if (abs(1 - zoom) * centroidSize > touchSlop || pan.getDistance() > touchSlop) {
                        pastTouchSlop = true
                    }
                }
                if (pastTouchSlop) {
                    if (zoomChange != 1f) onZoom(zoomChange)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            }
        } while (!canceled && event.changes.any { it.pressed })
        if (started) onZoomEnd()
    }
}
