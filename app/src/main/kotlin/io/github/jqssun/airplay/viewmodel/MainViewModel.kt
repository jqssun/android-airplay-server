package io.github.jqssun.airplay.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import android.view.Surface
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import io.github.jqssun.airplay.Prefs
import io.github.jqssun.airplay.audio.TrackInfo
import io.github.jqssun.airplay.service.AirPlayService
import io.github.jqssun.airplay.service.AirPlayService.ServerState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

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
    val framePacingJitterUs: Long = 0,
    val audioCodec: String = "",
    val audioVolume: Int = 100,
    val audio: AudioDebug? = null,
    val connections: Int = 0,
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
    private val audioManager = app.getSystemService(AudioManager::class.java)
    private var service: AirPlayService? = null
    val dacpPlayer: Player? get() = service?.dacpPlayer

    fun audioVolumeUp() {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
    }
    fun audioVolumeDown() {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
    }
    fun audioScanBegin(forward: Boolean) {
        service?.dacpController?.let { if (forward) it.beginFastForward() else it.beginRewind() }
    }
    fun audioScanEnd() { service?.dacpController?.playResume() }
    fun audioMuteToggle() { service?.dacpController?.muteToggle() }

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

    private val _serverName = MutableStateFlow(Prefs.serverName(app))
    val serverName: StateFlow<String> = _serverName.asStateFlow()

    // settings
    private val _serverPort = MutableStateFlow(prefs.getInt(Prefs.SERVER_PORT, Prefs.DEF_SERVER_PORT))
    val serverPort: StateFlow<Int> = _serverPort.asStateFlow()

    private val _autoStart = MutableStateFlow(prefs.getBoolean(Prefs.AUTO_START, Prefs.DEF_AUTO_START))
    val autoStart: StateFlow<Boolean> = _autoStart.asStateFlow()

    private val _bootAutoStart = MutableStateFlow(prefs.getBoolean(Prefs.BOOT_AUTO_START, Prefs.DEF_BOOT_AUTO_START))
    val bootAutoStart: StateFlow<Boolean> = _bootAutoStart.asStateFlow()

    private val _runInBackground = MutableStateFlow(prefs.getBoolean(Prefs.RUN_IN_BACKGROUND, Prefs.DEF_RUN_IN_BACKGROUND))
    val runInBackground: StateFlow<Boolean> = _runInBackground.asStateFlow()

    private val _h265Enabled = MutableStateFlow(prefs.getBoolean(Prefs.H265_ENABLED, Prefs.DEF_H265_ENABLED))
    val h265Enabled: StateFlow<Boolean> = _h265Enabled.asStateFlow()

    private val _enforceSdr = MutableStateFlow(prefs.getBoolean(Prefs.ENFORCE_SDR, Prefs.DEF_ENFORCE_SDR))
    val enforceSdr: StateFlow<Boolean> = _enforceSdr.asStateFlow()

    private val _keyAllowFrameDrop = MutableStateFlow(prefs.getBoolean(Prefs.KEY_ALLOW_FRAME_DROP, Prefs.DEF_KEY_ALLOW_FRAME_DROP))
    val keyAllowFrameDrop: StateFlow<Boolean> = _keyAllowFrameDrop.asStateFlow()

    private val _realtimeDecoderPriority = MutableStateFlow(prefs.getBoolean(Prefs.KEY_PRIORITY, Prefs.DEF_KEY_PRIORITY))
    val realtimeDecoderPriority: StateFlow<Boolean> = _realtimeDecoderPriority.asStateFlow()

    private val _lowLatency = MutableStateFlow(prefs.getBoolean(Prefs.LOW_LATENCY, Prefs.DEF_LOW_LATENCY))
    val lowLatency: StateFlow<Boolean> = _lowLatency.asStateFlow()

    private val _operatingRate = MutableStateFlow(prefs.getString(Prefs.OPERATING_RATE, Prefs.DEF_OPERATING_RATE)!!)
    val operatingRate: StateFlow<String> = _operatingRate.asStateFlow()

    private val _scheduledOutputBufferRelease = MutableStateFlow(prefs.getBoolean(Prefs.SCHEDULED_OUTPUT_BUFFER_RELEASE, Prefs.DEF_SCHEDULED_OUTPUT_BUFFER_RELEASE))
    val scheduledOutputBufferRelease: StateFlow<Boolean> = _scheduledOutputBufferRelease.asStateFlow()

    private val _benchmarkLog = MutableStateFlow(prefs.getBoolean(Prefs.BENCHMARK_LOG, Prefs.DEF_BENCHMARK_LOG))
    val benchmarkLog: StateFlow<Boolean> = _benchmarkLog.asStateFlow()

    private val _alacEnabled = MutableStateFlow(prefs.getBoolean(Prefs.ALAC_ENABLED, Prefs.DEF_ALAC_ENABLED))
    val alacEnabled: StateFlow<Boolean> = _alacEnabled.asStateFlow()

    private val _forceSwAlac = MutableStateFlow(prefs.getBoolean(Prefs.FORCE_SW_ALAC, Prefs.DEF_FORCE_SW_ALAC))
    val forceSwAlac: StateFlow<Boolean> = _forceSwAlac.asStateFlow()

    private val _aacEnabled = MutableStateFlow(prefs.getBoolean(Prefs.AAC_ENABLED, Prefs.DEF_AAC_ENABLED))
    val aacEnabled: StateFlow<Boolean> = _aacEnabled.asStateFlow()

    private val _resolution = MutableStateFlow(prefs.getString(Prefs.RESOLUTION, Prefs.DEF_RESOLUTION)!!)
    val resolution: StateFlow<String> = _resolution.asStateFlow()

    private val _maxFps = MutableStateFlow(prefs.getInt(Prefs.MAX_FPS, Prefs.DEF_MAX_FPS))
    val maxFps: StateFlow<Int> = _maxFps.asStateFlow()

    private val _overscanned = MutableStateFlow(prefs.getBoolean(Prefs.OVERSCANNED, Prefs.DEF_OVERSCANNED))
    val overscanned: StateFlow<Boolean> = _overscanned.asStateFlow()

    private val _requirePin = MutableStateFlow(prefs.getBoolean(Prefs.REQUIRE_PIN, Prefs.DEF_REQUIRE_PIN))
    val requirePin: StateFlow<Boolean> = _requirePin.asStateFlow()

    private val _allowNewConn = MutableStateFlow(prefs.getBoolean(Prefs.ALLOW_NEW_CONN, Prefs.DEF_ALLOW_NEW_CONN))
    val allowNewConn: StateFlow<Boolean> = _allowNewConn.asStateFlow()

    private val _audioLatencyMs = MutableStateFlow(prefs.getInt(Prefs.AUDIO_LATENCY_MS, Prefs.DEF_AUDIO_LATENCY_MS))
    val audioLatencyMs: StateFlow<Int> = _audioLatencyMs.asStateFlow()

    private val _idlePreview = MutableStateFlow(prefs.getBoolean(Prefs.IDLE_PREVIEW, Prefs.DEF_IDLE_PREVIEW))
    val idlePreview: StateFlow<Boolean> = _idlePreview.asStateFlow()

    private val _autoFullscreen = MutableStateFlow(prefs.getBoolean(Prefs.AUTO_FULLSCREEN, Prefs.DEF_AUTO_FULLSCREEN))
    val autoFullscreen: StateFlow<Boolean> = _autoFullscreen.asStateFlow()

    private val _keepScreenOn = MutableStateFlow(prefs.getBoolean(Prefs.KEEP_SCREEN_ON, Prefs.DEF_KEEP_SCREEN_ON))
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    private val _advertiseVideo = MutableStateFlow(prefs.getBoolean(Prefs.ADVERTISE_VIDEO, Prefs.DEF_ADVERTISE_VIDEO))
    val advertiseVideo: StateFlow<Boolean> = _advertiseVideo.asStateFlow()

    private val _advertiseAudio = MutableStateFlow(prefs.getBoolean(Prefs.ADVERTISE_AUDIO, Prefs.DEF_ADVERTISE_AUDIO))
    val advertiseAudio: StateFlow<Boolean> = _advertiseAudio.asStateFlow()

    private val _launchOnConnect = MutableStateFlow(prefs.getBoolean(Prefs.LAUNCH_ON_CONNECT, Prefs.DEF_LAUNCH_ON_CONNECT))
    val launchOnConnect: StateFlow<Boolean> = _launchOnConnect.asStateFlow()

    /**
     * "Return to previous app after casting": when a session summoned the activity
     * (see MainActivity.summonedBySession) and then ends, send the task to the back
     * so whatever was on screen before (HDMI input, another app) comes back. When
     * off, the app stays in front on its main page (the old behaviour).
     */
    private val _returnToPreviousApp = MutableStateFlow(prefs.getBoolean(Prefs.RETURN_TO_PREVIOUS_APP, Prefs.DEF_RETURN_TO_PREVIOUS_APP))
    val returnToPreviousApp: StateFlow<Boolean> = _returnToPreviousApp.asStateFlow()

    // debug
    private val _debugEnabled = MutableStateFlow(prefs.getBoolean(Prefs.DEBUG_ENABLED, Prefs.DEF_DEBUG_ENABLED))
    val debugEnabled: StateFlow<Boolean> = _debugEnabled.asStateFlow()

    private val _developerOptions = MutableStateFlow(prefs.getBoolean(Prefs.DEVELOPER_OPTIONS, Prefs.DEF_DEVELOPER_OPTIONS))
    val developerOptions: StateFlow<Boolean> = _developerOptions.asStateFlow()

    private val _audioAutoBuffer = MutableStateFlow(prefs.getBoolean(Prefs.AUDIO_AUTO_BUFFER, Prefs.DEF_AUDIO_AUTO_BUFFER))
    val audioAutoBuffer: StateFlow<Boolean> = _audioAutoBuffer.asStateFlow()

    private val _audioCushionMs = MutableStateFlow(prefs.getInt(Prefs.AUDIO_CUSHION_MS, Prefs.DEF_AUDIO_CUSHION_MS))
    val audioCushionMs: StateFlow<Int> = _audioCushionMs.asStateFlow()

    private val _audioAdaptiveStep = MutableStateFlow(prefs.getInt(Prefs.AUDIO_ADAPTIVE_STEP, Prefs.DEF_AUDIO_ADAPTIVE_STEP))
    val audioAdaptiveStep: StateFlow<Int> = _audioAdaptiveStep.asStateFlow()

    private val _oboeBufferFrames = MutableStateFlow(prefs.getInt(Prefs.OBOE_BUFFER_FRAMES, Prefs.DEF_OBOE_BUFFER_FRAMES))
    val oboeBufferFrames: StateFlow<Int> = _oboeBufferFrames.asStateFlow()

    private val _debugInfo = MutableStateFlow(DebugInfo())
    val debugInfo: StateFlow<DebugInfo> = _debugInfo.asStateFlow()

    // audio mode
    private val _audioOnly = MutableStateFlow(false)
    val audioOnly: StateFlow<Boolean> = _audioOnly.asStateFlow()

    // skip the "switched to audio mode" dialog prompt when on
    private val _autoAudioMode = MutableStateFlow(prefs.getBoolean(Prefs.AUTO_AUDIO_MODE, Prefs.DEF_AUTO_AUDIO_MODE))
    val autoAudioMode: StateFlow<Boolean> = _autoAudioMode.asStateFlow()

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

    // settings setters
    private var _restartJob: Job? = null

    private fun _applyByServerRestart() {
        _restartJob?.cancel()
        _restartJob = viewModelScope.launch {
            delay(SERVER_RESTART_DEBOUNCE_MS)
            val svc = service ?: return@launch
            if (svc.serverState.value == ServerState.RUNNING) {
                svc.stopServer()
                svc.startServer(_serverName.value)
            }
        }
    }

    fun setServerPort(port: Int) { _serverPort.value = port; prefs.edit().putInt(Prefs.SERVER_PORT, port).apply(); _applyByServerRestart() }
    fun setServerName(name: String) { _serverName.value = name; prefs.edit().putString(Prefs.SERVER_NAME, name).apply(); _applyByServerRestart() }
    fun setAutoStart(v: Boolean) { _autoStart.value = v; prefs.edit().putBoolean(Prefs.AUTO_START, v).apply() }
    fun setBootAutoStart(v: Boolean) { _bootAutoStart.value = v; prefs.edit().putBoolean(Prefs.BOOT_AUTO_START, v).apply() }
    fun setRunInBackground(v: Boolean) { _runInBackground.value = v; prefs.edit().putBoolean(Prefs.RUN_IN_BACKGROUND, v).apply() }
    fun setH265Enabled(v: Boolean) { _h265Enabled.value = v; prefs.edit().putBoolean(Prefs.H265_ENABLED, v).apply(); _applyByServerRestart() }
    fun setEnforceSdr(v: Boolean) { _enforceSdr.value = v; prefs.edit().putBoolean(Prefs.ENFORCE_SDR, v).apply(); _applyByServerRestart() }
    fun setKeyAllowFrameDrop(v: Boolean) {
        _keyAllowFrameDrop.value = v
        prefs.edit().putBoolean(Prefs.KEY_ALLOW_FRAME_DROP, v).apply()
        _applyByServerRestart()
    }
    fun setRealtimeDecoderPriority(v: Boolean) {
        _realtimeDecoderPriority.value = v
        prefs.edit().putBoolean(Prefs.KEY_PRIORITY, v).apply()
        _applyByServerRestart()
    }
    fun setLowLatency(v: Boolean) {
        _lowLatency.value = v
        prefs.edit().putBoolean(Prefs.LOW_LATENCY, v).apply()
    }
    fun setOperatingRate(v: String) { _operatingRate.value = v; prefs.edit().putString(Prefs.OPERATING_RATE, v).apply(); _applyByServerRestart() }
    fun setScheduledOutputBufferRelease(v: Boolean) {
        _scheduledOutputBufferRelease.value = v
        prefs.edit().putBoolean(Prefs.SCHEDULED_OUTPUT_BUFFER_RELEASE, v).apply()
        _applyByServerRestart()
    }
    fun setBenchmarkLog(v: Boolean) {
        _benchmarkLog.value = v
        prefs.edit().putBoolean(Prefs.BENCHMARK_LOG, v).apply()
        _applyByServerRestart()
    }
    fun setForceSwAlac(v: Boolean) { _forceSwAlac.value = v; prefs.edit().putBoolean(Prefs.FORCE_SW_ALAC, v).apply() }
    fun setAlacEnabled(v: Boolean) { _alacEnabled.value = v; prefs.edit().putBoolean(Prefs.ALAC_ENABLED, v).apply(); _applyByServerRestart() }
    fun setAacEnabled(v: Boolean) { _aacEnabled.value = v; prefs.edit().putBoolean(Prefs.AAC_ENABLED, v).apply(); _applyByServerRestart() }
    fun setResolution(v: String) { _resolution.value = v; prefs.edit().putString(Prefs.RESOLUTION, v).apply(); _applyByServerRestart() }
    fun setMaxFps(v: Int) { _maxFps.value = v; prefs.edit().putInt(Prefs.MAX_FPS, v).apply(); _applyByServerRestart() }
    fun setOverscanned(v: Boolean) { _overscanned.value = v; prefs.edit().putBoolean(Prefs.OVERSCANNED, v).apply(); _applyByServerRestart() }
    fun setRequirePin(v: Boolean) { _requirePin.value = v; prefs.edit().putBoolean(Prefs.REQUIRE_PIN, v).apply(); _applyByServerRestart() }
    fun setAllowNewConn(v: Boolean) { _allowNewConn.value = v; prefs.edit().putBoolean(Prefs.ALLOW_NEW_CONN, v).apply(); _applyByServerRestart() }
    fun setAudioLatencyMs(v: Int) { _audioLatencyMs.value = v; prefs.edit().putInt(Prefs.AUDIO_LATENCY_MS, v).apply(); _applyByServerRestart() }
    fun setIdlePreview(v: Boolean) { _idlePreview.value = v; prefs.edit().putBoolean(Prefs.IDLE_PREVIEW, v).apply() }
    fun setAutoFullscreen(v: Boolean) { _autoFullscreen.value = v; prefs.edit().putBoolean(Prefs.AUTO_FULLSCREEN, v).apply() }
    fun setKeepScreenOn(v: Boolean) { _keepScreenOn.value = v; prefs.edit().putBoolean(Prefs.KEEP_SCREEN_ON, v).apply() }
    fun setAdvertiseVideo(v: Boolean) { _advertiseVideo.value = v; prefs.edit().putBoolean(Prefs.ADVERTISE_VIDEO, v).apply(); _applyByServerRestart() }
    fun setAdvertiseAudio(v: Boolean) { _advertiseAudio.value = v; prefs.edit().putBoolean(Prefs.ADVERTISE_AUDIO, v).apply(); _applyByServerRestart() }
    fun setLaunchOnConnect(v: Boolean) { _launchOnConnect.value = v; prefs.edit().putBoolean(Prefs.LAUNCH_ON_CONNECT, v).apply() }
    fun setReturnToPreviousApp(v: Boolean) { _returnToPreviousApp.value = v; prefs.edit().putBoolean(Prefs.RETURN_TO_PREVIOUS_APP, v).apply() }
    fun setDebugEnabled(v: Boolean) { _debugEnabled.value = v; prefs.edit().putBoolean(Prefs.DEBUG_ENABLED, v).apply() }
    fun setAutoAudioMode(v: Boolean) { _autoAudioMode.value = v; prefs.edit().putBoolean(Prefs.AUTO_AUDIO_MODE, v).apply() }
    fun setDeveloperOptions(v: Boolean) {
        _developerOptions.value = v
        prefs.edit().putBoolean(Prefs.DEVELOPER_OPTIONS, v).apply()
    }
    fun setAudioAutoBuffer(v: Boolean) {
        _audioAutoBuffer.value = v
        prefs.edit().putBoolean(Prefs.AUDIO_AUTO_BUFFER, v).apply()
    }
    fun setAudioCushionMs(v: Int) {
        val value = v.coerceIn(1, 1000)
        _audioCushionMs.value = value
        prefs.edit().putInt(Prefs.AUDIO_CUSHION_MS, value).apply()
    }
    fun setAudioAdaptiveStep(v: Int) {
        val value = v.coerceIn(0, Prefs.ADAPTIVE_PERCENTILES.size - 1)
        _audioAdaptiveStep.value = value
        prefs.edit().putInt(Prefs.AUDIO_ADAPTIVE_STEP, value).apply()
    }
    fun setOboeBufferFrames(v: Int) {
        val value = v.coerceIn(0, 8192)
        _oboeBufferFrames.value = value
        prefs.edit().putInt(Prefs.OBOE_BUFFER_FRAMES, value).apply()
    }

    // service binding
    fun bindService(svc: AirPlayService) {
        service = svc
        // Mirror the AirPlay Video active flag event-driven rather than only via the
        // 200ms lifecycle-gated UI poll: dismissing the video screen when the sender
        // stops the session must not depend on the poll loop being scheduled at that
        // moment, or the last decoded frame stays up (frozen) on the SurfaceView.
        _serviceVideoJob?.cancel()
        _serviceVideoJob = viewModelScope.launch {
            svc.videoPlaybackActive.collect { active ->
                val prev = _videoPlaybackActive.value
                if (active && !prev) {
                    // fresh session: reset overlay state
                    _videoPlaying.value = true
                    _videoOverlayTick.value = 0L
                    _videoScrubPositionMs.value = null
                }
                _videoPlaybackActive.value = active
            }
        }
        updateFromService()
    }

    private var _serviceVideoJob: Job? = null

    fun unbindService() {
        _serviceVideoJob?.cancel()
        _serviceVideoJob = null
        service = null
    }

    fun startServer() {
        service?.startServer(_serverName.value)
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
            if (_debugEnabled.value) {
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
        const val SERVER_RESTART_DEBOUNCE_MS = 500L
    }
}
