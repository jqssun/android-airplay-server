package io.github.jqssun.airplay.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.Surface
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import io.github.jqssun.airplay.Pref
import io.github.jqssun.airplay.Prefs
import io.github.jqssun.airplay.audio.TrackInfo
import io.github.jqssun.airplay.get
import io.github.jqssun.airplay.net.UdpErrors
import io.github.jqssun.airplay.net.WifiLink
import io.github.jqssun.airplay.observe
import io.github.jqssun.airplay.service.AirPlayService
import io.github.jqssun.airplay.service.AirPlayService.ServerState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

enum class LocalNetworkAccess { GRANTED, ASK, SETTINGS }

data class AudioDebug(
    val backlogMs: Int,
    val tunedCushionMs: Int,
    val trims: Int,
    val drops: Int,
    val silences: Int,
    val underruns: Int,
    val xrun: Int,
    val decodeMeanUs: Int,
    val decodeMaxUs: Int,
    val decodeHeld: Int,
    val decodeErrors: Int,
)

data class DebugInfo(
    val videoCodec: String = "",
    val videoRes: String = "",
    val videoFps: Int = 0,
    val videoBitrate: Long = 0,
    val videoFrames: Long = 0,
    val droppedFrames: Long = 0,
    /* frames the decoder thread threw away because it could not keep up */
    val queueDrops: Long = 0,
    val framePacingJitterUs: Long = 0,
    val audioCodec: String = "",
    val serverVolume: Int = 100,
    val clientVolume: Int = 100,
    val audio: AudioDebug? = null,
    val connections: Int = 0,
    val link: WifiLink? = null,
    val udpErrors: UdpErrors = UdpErrors(0, 0),
) {
    val bitrateStr: String get() {
        val kbps = videoBitrate / 1000
        return if (kbps >= 1000) "${"%.1f".format(kbps / 1000.0)} Mbps" else "$kbps Kbps"
    }
    val jitterStr: String get() {
        return if (framePacingJitterUs >= 1000) {
            "${"%.1f".format(framePacingJitterUs / 1000.0)} ms"
        } else {
            "$framePacingJitterUs us"
        }
    }
}

@HiltViewModel
class MainViewModel @Inject constructor(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
    private val logFile = File(app.filesDir, "airplay_logs.txt")
    private var service: AirPlayService? = null
    val dacpPlayer: Player? get() = service?.dacpPlayer

    fun audioScanBegin(forward: Boolean) {
        service?.dacpController?.let { if (forward) it.beginFastForward() else it.beginRewind() }
    }
    fun audioScanEnd() { service?.dacpController?.playResume() }

    private val _clientVolume = MutableStateFlow(1f)
    val clientVolume: StateFlow<Float> = _clientVolume.asStateFlow()
    private val _clientVolumeAdjustable = MutableStateFlow(false)
    val clientVolumeAdjustable: StateFlow<Boolean> = _clientVolumeAdjustable.asStateFlow()
    fun setClientVolume(frac: Float) { service?.setClientVolume(frac) }

    private val _serverState = MutableStateFlow(ServerState.STOPPED)
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    private val _connectionCount = MutableStateFlow(0)
    val connectionCount: StateFlow<Int> = _connectionCount.asStateFlow()

    private val _pinCode = MutableStateFlow<String?>(null)
    val pinCode: StateFlow<String?> = _pinCode.asStateFlow()

    private val _videoAspect = MutableStateFlow(16f / 9f)
    val videoAspect: StateFlow<Float> = _videoAspect.asStateFlow()

    private val _videoResolution = MutableStateFlow("")
    val videoResolution: StateFlow<String> = _videoResolution.asStateFlow()
    // geometry advertised to the sender, the extended desktop is built from it
    private val _advertisedResolution = MutableStateFlow("")
    val advertisedResolution: StateFlow<String> = _advertisedResolution.asStateFlow()
    // number of active mirroring sessions (a mirror and an extended display)
    private val _videoSessionCount = MutableStateFlow(0)
    val videoSessionCount: StateFlow<Int> = _videoSessionCount.asStateFlow()

    private val _prefStates = mutableMapOf<Pref<*>, StateFlow<*>>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> pref(p: Pref<T>): StateFlow<T> = _prefStates.getOrPut(p) {
        prefs.observe { this[p] }.stateIn(viewModelScope, SharingStarted.Eagerly, prefs[p])
    } as StateFlow<T>

    fun <T : Any> set(p: Pref<T>, v: T) { p.write(prefs, v) }

    private val _debugInfo = MutableStateFlow(DebugInfo())
    val debugInfo: StateFlow<DebugInfo> = _debugInfo.asStateFlow()

    private val _localNetwork = MutableStateFlow(LocalNetworkAccess.GRANTED)
    val localNetwork: StateFlow<LocalNetworkAccess> = _localNetwork.asStateFlow()
    fun setLocalNetwork(v: LocalNetworkAccess) { _localNetwork.value = v }

    // audio mode
    private val _audioOnly = MutableStateFlow(false)
    val audioOnly: StateFlow<Boolean> = _audioOnly.asStateFlow()

    private val _videoPlaybackActive = MutableStateFlow(false)
    val videoPlaybackActive: StateFlow<Boolean> = _videoPlaybackActive.asStateFlow()

    // video channel polling but /play not received yet
    private val _videoSessionPending = MutableStateFlow(false)
    val videoSessionPending: StateFlow<Boolean> = _videoSessionPending.asStateFlow()

    // airplay video transport overlay state

    // bumping the tick shows the overlay and restarts its auto-hide timer
    private val _videoOverlayTick = MutableStateFlow(0L)
    val videoOverlayTick: StateFlow<Long> = _videoOverlayTick.asStateFlow()

    private val _videoPositionMs = MutableStateFlow(0L)
    val videoPositionMs: StateFlow<Long> = _videoPositionMs.asStateFlow()

    private val _videoDurationMs = MutableStateFlow(0L)
    val videoDurationMs: StateFlow<Long> = _videoDurationMs.asStateFlow()

    private val _videoPlaybackAspect = MutableStateFlow(16f / 9f)
    val videoPlaybackAspect: StateFlow<Float> = _videoPlaybackAspect.asStateFlow()

    private val _videoPlaybackSize = MutableStateFlow<Pair<Int, Int>?>(null)
    val videoPlaybackSize: StateFlow<Pair<Int, Int>?> = _videoPlaybackSize.asStateFlow()

    private val _videoBuffering = MutableStateFlow(false)
    val videoBuffering: StateFlow<Boolean> = _videoBuffering.asStateFlow()

    private val _videoTitle = MutableStateFlow("")
    val videoTitle: StateFlow<String> = _videoTitle.asStateFlow()

    private val _videoLocation = MutableStateFlow<String?>(null)
    val videoLocation: StateFlow<String?> = _videoLocation.asStateFlow()

    // optimistic for instant icon feedback, re-synced from the delayed snapshot
    private val _videoPlaying = MutableStateFlow(true)
    val videoPlaying: StateFlow<Boolean> = _videoPlaying.asStateFlow()
    private var _videoActionAt = 0L

    // null = idle, -1 = unknown, else 0..100
    private val _videoDownloadProgress = MutableStateFlow<Int?>(null)
    val videoDownloadProgress: StateFlow<Int?> = _videoDownloadProgress.asStateFlow()

    // mirror the player's playback parameters; sender /rate commands reset speed to 1
    private val _videoSpeed = MutableStateFlow(1f)
    val videoSpeed: StateFlow<Float> = _videoSpeed.asStateFlow()
    private val _videoSkipSilence = MutableStateFlow(false)
    val videoSkipSilence: StateFlow<Boolean> = _videoSkipSilence.asStateFlow()
    private var _videoParamsActionAt = 0L

    // non-null while a scrub is in flight; committed once after a debounce
    private val _videoScrubPositionMs = MutableStateFlow<Long?>(null)
    val videoScrubPositionMs: StateFlow<Long?> = _videoScrubPositionMs.asStateFlow()
    private var _scrubJob: Job? = null
    private var _lastVideoPlaySeq = 0L

    private val _mirroringActive = MutableStateFlow(false)
    val mirroringActive: StateFlow<Boolean> = _mirroringActive.asStateFlow()

    private val _trackInfo = MutableStateFlow(TrackInfo())
    val trackInfo: StateFlow<TrackInfo> = _trackInfo.asStateFlow()


    // logs
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()
    private val _logLock = Any()
    private val _logList = mutableListOf<String>()
    private val _dateFmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    init {
        loadPersistedLogs()
    }

    fun addLog(msg: String) {
        val line = "${_dateFmt.get()!!.format(Date())} $msg"
        val snapshot: List<String>
        synchronized(_logLock) {
            _logList.add(line)
            while (_logList.size > 9999) _logList.removeAt(0)
            persistLogsLocked()
            snapshot = _logList.toList()
        }
        _logs.value = snapshot
    }

    fun clearLogs() {
        synchronized(_logLock) {
            _logList.clear()
            runCatching { logFile.delete() }
        }
        _logs.value = emptyList()
    }

    fun exportLogs() {
        val ctx = getApplication<Application>()
        val file = File(ctx.cacheDir, "airplay_logs.txt")
        file.writeText(_logList.joinToString("\n"))
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooserTitle = ctx.getString(io.github.jqssun.airplay.R.string.export_logs_chooser_title)
        ctx.startActivity(Intent.createChooser(intent, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun loadPersistedLogs() {
        val snapshot: List<String>
        synchronized(_logLock) {
            val restored = runCatching {
                if (logFile.exists()) logFile.readLines().takeLast(9999) else emptyList()
            }.getOrDefault(emptyList())
            _logList.clear()
            _logList.addAll(restored)
            snapshot = _logList.toList()
        }
        _logs.value = snapshot
    }

    private fun persistLogsLocked() {
        runCatching {
            logFile.writeText(_logList.joinToString("\n"))
        }
    }

    // service binding
    fun bindService(svc: AirPlayService) {
        service = svc
        updateFromService()
    }

    fun unbindService() {
        service = null
    }

    fun startServer() {
        service?.startServer()
    }

    fun stopServer() {
        service?.stopServer()
    }

    fun onSurfaceAvailable(surface: Surface) {
        service?.setVideoSurface(surface)
    }

    fun onSurfaceDestroyed(surface: Surface) {
        service?.clearVideoSurface(surface)
    }

    fun onVideoPlaybackSurfaceAvailable(surface: Surface) {
        service?.setVideoPlaybackSurface(surface)
    }

    fun onVideoPlaybackSurfaceDestroyed(surface: Surface) {
        service?.clearVideoPlaybackSurface(surface)
    }

    fun toggleVideoPlayPause(showOverlay: Boolean = true) = setVideoPlaying(!_videoPlaying.value, showOverlay)

    fun setVideoPlaying(playing: Boolean, showOverlay: Boolean = true) {
        // pending session: there is no local media yet, nothing to toggle
        if (!_videoPlaybackActive.value) return
        _videoPlaying.value = playing
        _videoActionAt = SystemClock.elapsedRealtime()
        service?.setVideoPlaying(playing)
        if (showOverlay) showVideoOverlay()
    }

    // hold the target until the reported position catches up (hls rebuffer)
    private suspend fun _holdScrubUntilSettled(target: Long) {
        val deadline = SystemClock.elapsedRealtime() + SCRUB_SETTLE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(SCRUB_SETTLE_POLL_MS)
            if (abs(_videoPositionMs.value - target) <= SCRUB_SETTLE_EPSILON_MS) break
        }
        _videoScrubPositionMs.value = null
    }

    // drag-seek: live seeks under scrubbing mode, settle-hold on release
    fun startVideoScrub() {
        _scrubJob?.cancel()
        service?.setVideoScrubbing(true)
    }

    fun scrubVideoTo(positionMs: Long) {
        val duration = _videoDurationMs.value
        if (duration <= 0) return
        val target = positionMs.coerceIn(0L, duration)
        _videoScrubPositionMs.value = target
        service?.seekVideoTo(target)
    }

    fun endVideoScrub() {
        service?.setVideoScrubbing(false)
        _scrubJob?.cancel()
        _scrubJob = viewModelScope.launch {
            _holdScrubUntilSettled(_videoScrubPositionMs.value ?: return@launch)
        }
    }

    fun showVideoOverlay() {
        _videoOverlayTick.value++
    }

    fun stopVideoPlayback() {
        service?.stopVideoPlayback()
    }

    fun setVideoSpeed(speed: Float) {
        val value = speed.coerceIn(0.2f, 4f)
        _videoSpeed.value = value
        _videoParamsActionAt = SystemClock.elapsedRealtime()
        service?.setVideoSpeed(value)
    }

    fun setVideoSkipSilence(enabled: Boolean) {
        _videoSkipSilence.value = enabled
        _videoParamsActionAt = SystemClock.elapsedRealtime()
        service?.setVideoSkipSilence(enabled)
    }

    fun seekVideoBy(deltaMs: Long) {
        service?.seekVideoBy(deltaMs)
    }

    fun toggleVideoDownload() {
        val svc = service ?: return
        if (_videoDownloadProgress.value != null) svc.videoDownloader.cancel() else svc.downloadVideo()
        showVideoOverlay()
    }

    fun dismissPin() {
        _pinCode.value = null
    }

    fun showPin(pin: String?) {
        _pinCode.value = pin
    }

    fun updateFromService() {
        service?.let {
            _serverState.value = it.serverState.value
            _connectionCount.value = it.connectionCount.value
            _videoAspect.value = it.videoAspect.value
            _videoResolution.value = it.videoResolution.value
            _advertisedResolution.value = it.advertisedResolution.value
            _videoSessionCount.value = it.videoSessionCount.value
            _audioOnly.value = it.audioOnly.value
            val videoActive = it.videoPlaybackActive.value
            val playSeq = it.videoPlaySeq.value
            if (playSeq != _lastVideoPlaySeq) {
                // fresh /play (incl. back-to-back): drop stale overlay and scrub state
                _lastVideoPlaySeq = playSeq
                _videoPlaying.value = true
                _videoActionAt = 0
                _videoOverlayTick.value = 0
                _videoSpeed.value = 1f
                _videoSkipSilence.value = false
                _scrubJob?.cancel()
                _videoScrubPositionMs.value = null
            }
            _videoPlaybackActive.value = videoActive
            _videoSessionPending.value = it.videoSessionPending()
            _videoDownloadProgress.value = it.videoDownloader.progress.value
            if (videoActive) {
                val info = it.videoPlaybackInfo.value
                _videoPositionMs.value = info.positionMs
                _videoDurationMs.value = info.durationMs
                if (SystemClock.elapsedRealtime() - _videoParamsActionAt > VIDEO_ACTION_SYNC_HOLD_MS) {
                    _videoSpeed.value = info.speed
                    _videoSkipSilence.value = info.skipSilence
                }
                _videoBuffering.value = info.buffering
                _videoPlaybackAspect.value = it.videoPlaybackAspect.value
                _videoPlaybackSize.value = it.videoPlaybackSize.value
                // stream metadata first, dmap second; senders rarely provide either for video
                _videoTitle.value = it.videoTitle.value.ifEmpty { it.trackInfo.value.title }
                _videoLocation.value = it.videoLocation.value
                // stale snapshots must not overwrite a just-made optimistic action
                if (SystemClock.elapsedRealtime() - _videoActionAt > VIDEO_ACTION_SYNC_HOLD_MS) {
                    _videoPlaying.value = info.playing
                }
            } else {
                _videoPositionMs.value = 0
                _videoDurationMs.value = 0
                _videoBuffering.value = false
            }
            _mirroringActive.value = it.mirroringActive.value
            _trackInfo.value = it.trackInfo.value
            _clientVolume.value = it.clientVolume()
            _clientVolumeAdjustable.value = it.dacpController?.ready == true
            if (pref(Prefs.DEBUG_ENABLED).value) {
                _debugInfo.value = it.collectDebugInfo()
            }
        }
    }

    private companion object {
        // post-commit: show the target until within epsilon, give up after the timeout
        const val SCRUB_SETTLE_POLL_MS = 100L
        const val SCRUB_SETTLE_EPSILON_MS = 2_000L
        const val SCRUB_SETTLE_TIMEOUT_MS = 3_000L
        // raise if the play/pause icon flickers back right after a local toggle
        const val VIDEO_ACTION_SYNC_HOLD_MS = 700L
    }
}
