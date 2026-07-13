package io.github.jqssun.airplay.ui.gestures

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.provider.Settings
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.DisposableEffectResult
import androidx.compose.runtime.DisposableEffectScope
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.unit.IntSize
import io.github.jqssun.airplay.viewmodel.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// zones: left 35% = back, right 35% = forward, middle = play/pause
@Stable
class TapGestureState(
    private val viewModel: MainViewModel,
    private val scope: CoroutineScope,
) {
    // accumulated double-tap seek shown by the indicator; sign is the direction
    var seekMillis by mutableLongStateOf(0L)
        private set
    val interactionSource = MutableInteractionSource()
    private var resetJob: Job? = null

    fun handleDoubleTap(offset: Offset, size: IntSize) {
        if (viewModel.videoDurationMs.value <= 0) return
        val zone = offset.x / size.width
        when {
            zone < 0.35f -> _step(-SEEK_INCREMENT_MS, offset)
            zone > 0.65f -> _step(SEEK_INCREMENT_MS, offset)
            else -> viewModel.toggleVideoPlayPause(showOverlay = false)
        }
    }

    private fun _step(deltaMs: Long, offset: Offset) {
        // direction flip restarts the accumulator
        if (seekMillis != 0L && (seekMillis > 0) != (deltaMs > 0)) seekMillis = 0
        seekMillis += deltaMs
        viewModel.seekVideoBy(deltaMs)
        interactionSource.tryEmit(PressInteraction.Press(offset))
        resetJob?.cancel()
        resetJob = scope.launch {
            delay(INDICATOR_TIMEOUT_MS)
            seekMillis = 0
        }
    }

    companion object {
        const val SEEK_INCREMENT_MS = 10_000L
        private const val INDICATOR_TIMEOUT_MS = 750L
    }
}

// shared by the horizontal drag gesture and the seekbar; 50ms per dragged px
@Stable
class SeekGestureState(private val viewModel: MainViewModel) {
    var startPositionMs: Long? by mutableStateOf(null)
        private set
    var targetPositionMs: Long? by mutableStateOf(null)
        private set
    private var startX = 0f

    val seekAmountMs: Long?
        get() {
            val start = startPositionMs ?: return null
            return (targetPositionMs ?: return null) - start
        }

    fun onDragStart(offset: Offset) {
        if (viewModel.videoDurationMs.value <= 0) return
        startX = offset.x
        _begin()
    }

    fun onDrag(change: PointerInputChange, dragAmount: Float) {
        val start = startPositionMs ?: return
        val duration = viewModel.videoDurationMs.value
        if (duration <= 0) return
        val position = viewModel.videoPositionMs.value
        if (position <= 0 && dragAmount < 0) return
        if (position >= duration && dragAmount > 0) return
        _seekTo(start + ((change.position.x - startX) * SEEK_MS_PER_PX).toLong())
    }

    // seekbar entry point
    fun onSeek(positionMs: Long) {
        if (viewModel.videoDurationMs.value <= 0) return
        if (startPositionMs == null) _begin()
        _seekTo(positionMs)
    }

    fun onSeekEnd() {
        if (startPositionMs == null) return
        startPositionMs = null
        targetPositionMs = null
        viewModel.endVideoScrub()
    }

    private fun _begin() {
        startPositionMs = viewModel.videoScrubPositionMs.value ?: viewModel.videoPositionMs.value
        viewModel.startVideoScrub()
    }

    private fun _seekTo(positionMs: Long) {
        val target = positionMs.coerceIn(0L, viewModel.videoDurationMs.value)
        targetPositionMs = target
        viewModel.scrubVideoTo(target)
    }

    companion object {
        private const val SEEK_MS_PER_PX = 50f
    }
}

@Stable
class VolumeState(private val context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    var percentage by mutableIntStateOf(0)
        private set

    fun sync() {
        percentage = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / maxVolume
    }

    fun update(newPercentage: Int) {
        percentage = newPercentage.coerceIn(0, 100)
        // surface the system panel when a headset is connected
        val flags = if (_isHeadsetOn()) AudioManager.FLAG_SHOW_UI else 0
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, percentage * maxVolume / 100, flags)
    }

    // keeps the indicator in sync with volume-key presses during a gesture
    fun handleLifecycle(scope: DisposableEffectScope): DisposableEffectResult = with(scope) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == VOLUME_CHANGED_ACTION) sync()
            }
        }
        context.registerReceiver(receiver, IntentFilter(VOLUME_CHANGED_ACTION))
        onDispose { context.unregisterReceiver(receiver) }
    }

    private fun _isHeadsetOn(): Boolean =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
            device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }

    companion object {
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
    }
}

@Stable
class BrightnessState(private val window: Window) {
    var percentage by mutableIntStateOf(_current())
        private set

    // window override when set, else the system brightness
    private fun _current(): Int {
        val b = window.attributes.screenBrightness
        val brightness = if (b in 0f..1f) b else {
            Settings.System.getFloat(window.context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 127.5f) / 255f
        }
        return (brightness * 100).toInt()
    }

    fun update(newPercentage: Int) {
        percentage = newPercentage.coerceIn(0, 100)
        window.attributes = window.attributes.apply { screenBrightness = percentage / 100f }
    }

    // single-activity app: the override must not outlive the video screen
    fun clearOverride() {
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }
}

enum class VerticalGesture { VOLUME, BRIGHTNESS }

// left half = brightness, right half = volume
@Stable
class VolumeAndBrightnessGestureState(
    private val volumeState: VolumeState,
    private val brightnessState: BrightnessState?,
    private val scope: CoroutineScope,
) {
    var activeGesture: VerticalGesture? by mutableStateOf(null)
        private set
    private var startY = 0f
    private var startVolume = 0
    private var startBrightness = 0
    private var hideJob: Job? = null

    fun onDragStart(offset: Offset, size: IntSize) {
        hideJob?.cancel()
        activeGesture = if (offset.x < size.width / 2) {
            VerticalGesture.BRIGHTNESS.takeIf { brightnessState != null }
        } else {
            VerticalGesture.VOLUME
        }
        startY = offset.y
        volumeState.sync()
        startVolume = volumeState.percentage
        startBrightness = brightnessState?.percentage ?: 0
    }

    fun onDrag(change: PointerInputChange, dragAmount: Float) {
        val delta = ((startY - change.position.y) * SENSITIVITY).toInt()
        when (activeGesture ?: return) {
            VerticalGesture.VOLUME -> volumeState.update(startVolume + delta)
            VerticalGesture.BRIGHTNESS -> brightnessState?.update(startBrightness + delta)
        }
    }

    fun onDragEnd() {
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(INDICATOR_TIMEOUT_MS)
            activeGesture = null
        }
    }

    companion object {
        // sensitivity 0.5, applied as sensitivity/10 percent per px
        private const val SENSITIVITY = 0.05f
        private const val INDICATOR_TIMEOUT_MS = 1_000L
    }
}

enum class VideoContentScale { BEST_FIT, STRETCH, CROP, HUNDRED_PERCENT }

@Stable
class ZoomState(
    private val viewModel: MainViewModel,
    private val scope: CoroutineScope,
) {
    var zoom by mutableFloatStateOf(1f)
        private set
    var isZooming by mutableStateOf(false)
        private set
    var contentScale by mutableStateOf(VideoContentScale.BEST_FIT)
        private set
    var showContentScaleIndicator by mutableStateOf(false)
        private set
    private var indicatorJob: Job? = null

    fun onZoom(zoomChange: Float) {
        // zoom is disabled until the timeline is established
        if (viewModel.videoDurationMs.value <= 0) return
        isZooming = true
        zoom = (zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    fun onZoomEnd() {
        isZooming = false
    }

    fun switchToNextContentScale() {
        contentScale = VideoContentScale.entries[(contentScale.ordinal + 1) % VideoContentScale.entries.size]
        zoom = 1f
        indicatorJob?.cancel()
        showContentScaleIndicator = true
        indicatorJob = scope.launch {
            delay(CONTENT_SCALE_INDICATOR_MS)
            showContentScaleIndicator = false
        }
    }

    fun reset() {
        zoom = 1f
        isZooming = false
        contentScale = VideoContentScale.BEST_FIT
    }

    companion object {
        private const val MIN_ZOOM = 0.25f
        private const val MAX_ZOOM = 4f
        private const val CONTENT_SCALE_INDICATOR_MS = 1_000L
    }
}
