package io.github.jqssun.airplay.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.view.Surface
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.jqssun.airplay.Prefs
import io.github.jqssun.airplay.audio.TrackInfo
import io.github.jqssun.airplay.service.AirPlayService
import io.github.jqssun.airplay.service.AirPlayService.ServerState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

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
    private var service: AirPlayService? = null

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

    private val _operatingRateHint = MutableStateFlow(prefs.getBoolean(Prefs.KEY_OPERATING_RATE, Prefs.DEF_KEY_OPERATING_RATE))
    val operatingRateHint: StateFlow<Boolean> = _operatingRateHint.asStateFlow()

    private val _scheduledOutputBufferRelease = MutableStateFlow(prefs.getBoolean(Prefs.SCHEDULED_OUTPUT_BUFFER_RELEASE, Prefs.DEF_SCHEDULED_OUTPUT_BUFFER_RELEASE))
    val scheduledOutputBufferRelease: StateFlow<Boolean> = _scheduledOutputBufferRelease.asStateFlow()

    private val _benchmarkLog = MutableStateFlow(prefs.getBoolean(Prefs.BENCHMARK_LOG, Prefs.DEF_BENCHMARK_LOG))
    val benchmarkLog: StateFlow<Boolean> = _benchmarkLog.asStateFlow()

    private val _alacEnabled = MutableStateFlow(prefs.getBoolean(Prefs.ALAC_ENABLED, Prefs.DEF_ALAC_ENABLED))
    val alacEnabled: StateFlow<Boolean> = _alacEnabled.asStateFlow()

    private val _swAlacEnabled = MutableStateFlow(prefs.getBoolean(Prefs.SW_ALAC_ENABLED, Prefs.DEF_SW_ALAC_ENABLED))
    val swAlacEnabled: StateFlow<Boolean> = _swAlacEnabled.asStateFlow()

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

    private val _autoAudioMode = MutableStateFlow(prefs.getBoolean(Prefs.AUTO_AUDIO_MODE, Prefs.DEF_AUTO_AUDIO_MODE))
    val autoAudioMode: StateFlow<Boolean> = _autoAudioMode.asStateFlow()

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

    private val _audioBufferMultiplier = MutableStateFlow(prefs.getInt(Prefs.AUDIO_BUFFER_MULTIPLIER, Prefs.DEF_AUDIO_BUFFER_MULTIPLIER))
    val audioBufferMultiplier: StateFlow<Int> = _audioBufferMultiplier.asStateFlow()

    private val _debugInfo = MutableStateFlow(DebugInfo())
    val debugInfo: StateFlow<DebugInfo> = _debugInfo.asStateFlow()

    // audio mode
    private val _audioOnly = MutableStateFlow(false)
    val audioOnly: StateFlow<Boolean> = _audioOnly.asStateFlow()

    // AirPlay Video (HLS) playback mode, distinct from screen mirroring / audio-only
    private val _videoPlaybackActive = MutableStateFlow(false)
    val videoPlaybackActive: StateFlow<Boolean> = _videoPlaybackActive.asStateFlow()

    // overlay visibility: any transport action (tap-to-pause, remote key) increments
    // this tick; the MainScreen LaunchedEffect shows the overlay and auto-hides it
    private val _videoOverlayTick = MutableStateFlow(0L)
    val videoOverlayTick: StateFlow<Long> = _videoOverlayTick.asStateFlow()

    // optimistic play state for the overlay icon; updated immediately on local actions
    // and re-synced from the ExoPlayer snapshot every 200ms (updateFromService)
    private val _videoPlaying = MutableStateFlow(true)
    val videoPlaying: StateFlow<Boolean> = _videoPlaying.asStateFlow()

    // live ExoPlayer state (from AirPlayService.videoPlaybackInfo, refreshed each
    // 250ms report tick); the overlay reads these for the seek bar and position text
    private val _videoPositionMs = MutableStateFlow(0L)
    val videoPositionMs: StateFlow<Long> = _videoPositionMs.asStateFlow()
    private val _videoDurationMs = MutableStateFlow(0L)
    val videoDurationMs: StateFlow<Long> = _videoDurationMs.asStateFlow()

    // while the user is scrubbing (holding DPAD left/right) this holds the accumulated
    // seek target; null when not scrubbing. The overlay shows this instead of the live
    // position so the bar tracks the scrub progress, then converges after the seek.
    private val _videoScrubPositionMs = MutableStateFlow<Long?>(null)
    val videoScrubPositionMs: StateFlow<Long?> = _videoScrubPositionMs.asStateFlow()

    // held-key scrub accumulation state
    private var _scrubAccumulatedMs = 0L
    private var _scrubDirection = 0
    private var _scrubLastStepTime = 0L
    private var _scrubTierIndex = 0
    private var _scrubStepsInTier = 0
    private var _scrubCommitJob: kotlinx.coroutines.Job? = null

    // event-driven mirror of the service's videoPlaybackActive (see bindService)
    private var _serviceVideoJob: kotlinx.coroutines.Job? = null

    // true only once real mirroring video size is known for the current session
    private val _mirroringActive = MutableStateFlow(false)
    val mirroringActive: StateFlow<Boolean> = _mirroringActive.asStateFlow()

    private val _trackInfo = MutableStateFlow(TrackInfo())
    val trackInfo: StateFlow<TrackInfo> = _trackInfo.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _playing = MutableStateFlow(true)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

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

    companion object {
        // All scrub-feel knobs in one place.
        // per-step jump sizes; the first entry is the single-tap jump, holding climbs
        // the tiers in order
        val SCRUB_STEP_TIERS_MS = longArrayOf(5000, 5000, 10000, 15000, 20000, 30000, 45000, 60000)
        // accepted steps spent on each tier before accelerating
        const val SCRUB_RAMP_DWELL_STEPS = 2
        // held-key step cadence (lower = faster scrubbing)
        const val SCRUB_REPEAT_THROTTLE_MS = 150L
        // quiet time after the last step before the single seek is issued
        const val SCRUB_COMMIT_DEBOUNCE_MS = 300L
    }

    // settings setters
    fun setServerPort(port: Int) { _serverPort.value = port; prefs.edit().putInt(Prefs.SERVER_PORT, port).apply() }
    fun setServerName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            // clearing the field reverts to the default (the device name)
            prefs.edit().remove(Prefs.SERVER_NAME).apply()
        } else {
            prefs.edit().putString(Prefs.SERVER_NAME, trimmed).apply()
        }
        val effective = Prefs.serverName(getApplication())
        if (_serverName.value == effective) return
        _serverName.value = effective
        // the advertised mDNS/raop name is fixed at native init: restart to apply
        service?.let {
            if (it.serverState.value == ServerState.RUNNING) {
                it.stopServer()
                it.startServer(effective)
            }
        }
    }
    fun setAutoStart(v: Boolean) { _autoStart.value = v; prefs.edit().putBoolean(Prefs.AUTO_START, v).apply() }
    fun setBootAutoStart(v: Boolean) { _bootAutoStart.value = v; prefs.edit().putBoolean(Prefs.BOOT_AUTO_START, v).apply() }
    fun setRunInBackground(v: Boolean) { _runInBackground.value = v; prefs.edit().putBoolean(Prefs.RUN_IN_BACKGROUND, v).apply() }
    fun setH265Enabled(v: Boolean) { _h265Enabled.value = v; prefs.edit().putBoolean(Prefs.H265_ENABLED, v).apply() }
    fun setEnforceSdr(v: Boolean) { _enforceSdr.value = v; prefs.edit().putBoolean(Prefs.ENFORCE_SDR, v).apply() }
    fun setKeyAllowFrameDrop(v: Boolean) {
        _keyAllowFrameDrop.value = v
        prefs.edit().putBoolean(Prefs.KEY_ALLOW_FRAME_DROP, v).apply()
    }
    fun setRealtimeDecoderPriority(v: Boolean) {
        _realtimeDecoderPriority.value = v
        prefs.edit().putBoolean(Prefs.KEY_PRIORITY, v).apply()
    }
    fun setOperatingRateHint(v: Boolean) {
        _operatingRateHint.value = v
        prefs.edit().putBoolean(Prefs.KEY_OPERATING_RATE, v).apply()
    }
    fun setScheduledOutputBufferRelease(v: Boolean) {
        _scheduledOutputBufferRelease.value = v
        prefs.edit().putBoolean(Prefs.SCHEDULED_OUTPUT_BUFFER_RELEASE, v).apply()
    }
    fun setBenchmarkLog(v: Boolean) {
        _benchmarkLog.value = v
        prefs.edit().putBoolean(Prefs.BENCHMARK_LOG, v).apply()
    }
    fun setSwAlacEnabled(v: Boolean) { _swAlacEnabled.value = v; prefs.edit().putBoolean(Prefs.SW_ALAC_ENABLED, v).apply() }
    fun setAlacEnabled(v: Boolean) { _alacEnabled.value = v; prefs.edit().putBoolean(Prefs.ALAC_ENABLED, v).apply() }
    fun setAacEnabled(v: Boolean) { _aacEnabled.value = v; prefs.edit().putBoolean(Prefs.AAC_ENABLED, v).apply() }
    fun setResolution(v: String) { _resolution.value = v; prefs.edit().putString(Prefs.RESOLUTION, v).apply() }
    fun setMaxFps(v: Int) { _maxFps.value = v; prefs.edit().putInt(Prefs.MAX_FPS, v).apply() }
    fun setOverscanned(v: Boolean) { _overscanned.value = v; prefs.edit().putBoolean(Prefs.OVERSCANNED, v).apply() }
    fun setRequirePin(v: Boolean) { _requirePin.value = v; prefs.edit().putBoolean(Prefs.REQUIRE_PIN, v).apply() }
    fun setAllowNewConn(v: Boolean) { _allowNewConn.value = v; prefs.edit().putBoolean(Prefs.ALLOW_NEW_CONN, v).apply() }
    fun setAudioLatencyMs(v: Int) { _audioLatencyMs.value = v; prefs.edit().putInt(Prefs.AUDIO_LATENCY_MS, v).apply() }
    fun setIdlePreview(v: Boolean) { _idlePreview.value = v; prefs.edit().putBoolean(Prefs.IDLE_PREVIEW, v).apply() }
    fun setAutoFullscreen(v: Boolean) { _autoFullscreen.value = v; prefs.edit().putBoolean(Prefs.AUTO_FULLSCREEN, v).apply() }
    fun setKeepScreenOn(v: Boolean) { _keepScreenOn.value = v; prefs.edit().putBoolean(Prefs.KEEP_SCREEN_ON, v).apply() }
    fun setAutoAudioMode(v: Boolean) { _autoAudioMode.value = v; prefs.edit().putBoolean(Prefs.AUTO_AUDIO_MODE, v).apply() }
    fun setLaunchOnConnect(v: Boolean) { _launchOnConnect.value = v; prefs.edit().putBoolean(Prefs.LAUNCH_ON_CONNECT, v).apply() }
    fun setReturnToPreviousApp(v: Boolean) { _returnToPreviousApp.value = v; prefs.edit().putBoolean(Prefs.RETURN_TO_PREVIOUS_APP, v).apply() }
    fun setDebugEnabled(v: Boolean) { _debugEnabled.value = v; prefs.edit().putBoolean(Prefs.DEBUG_ENABLED, v).apply() }
    fun setDeveloperOptions(v: Boolean) {
        _developerOptions.value = v
        prefs.edit().putBoolean(Prefs.DEVELOPER_OPTIONS, v).apply()
    }
    fun setAudioBufferMultiplier(v: Int) {
        val value = v.coerceIn(4, 8)
        _audioBufferMultiplier.value = value
        prefs.edit().putInt(Prefs.AUDIO_BUFFER_MULTIPLIER, value).apply()
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

    fun toggleVideoPlayPause() {
        _videoPlaying.value = !_videoPlaying.value
        service?.toggleVideoPlayback()
        _videoOverlayTick.value = ++_videoOverlayTickValue
    }

    fun setVideoPlaying(playing: Boolean) {
        _videoPlaying.value = playing
        service?.setVideoPlaying(playing)
        _videoOverlayTick.value = ++_videoOverlayTickValue
    }

    fun seekVideoBy(deltaMs: Long) {
        service?.seekVideoBy(deltaMs)
        _videoOverlayTick.value = ++_videoOverlayTickValue
    }

    fun stopVideoPlayback() {
        _videoScrubPositionMs.value = null
        service?.stopVideoPlayback()
    }

    // tap: single step in direction (-1 = left, 1 = right); hold: ramp through tiers
    // the accumulated target is committed as a single debounced seek
    fun scrubVideoBy(direction: Int, repeatCount: Int) {
        val now = System.currentTimeMillis()
        if (repeatCount == 0) {
            // first press: reset accumulator, start fresh
            val currentPos = _videoPositionMs.value
            _scrubAccumulatedMs = currentPos
            _scrubDirection = direction
            _scrubTierIndex = 0
            _scrubStepsInTier = 0
            _scrubLastStepTime = now
            _scrubAccumulatedMs += SCRUB_STEP_TIERS_MS[0] * direction
        } else {
            // held repeat: throttle and ramp
            if (now - _scrubLastStepTime < SCRUB_REPEAT_THROTTLE_MS) return
            // only accumulate if still going the same direction
            if (direction != _scrubDirection) {
                _scrubDirection = direction
                _scrubTierIndex = 0
                _scrubStepsInTier = 0
            }
            _scrubStepsInTier++
            if (_scrubStepsInTier >= SCRUB_RAMP_DWELL_STEPS &&
                _scrubTierIndex < SCRUB_STEP_TIERS_MS.lastIndex) {
                _scrubTierIndex++
                _scrubStepsInTier = 0
            }
            _scrubAccumulatedMs += SCRUB_STEP_TIERS_MS[_scrubTierIndex] * direction
            _scrubLastStepTime = now
        }
        // clamp to [0, duration]
        val dur = _videoDurationMs.value
        if (dur > 0L) _scrubAccumulatedMs = _scrubAccumulatedMs.coerceIn(0L, dur)
        else _scrubAccumulatedMs = _scrubAccumulatedMs.coerceAtLeast(0L)
        _videoScrubPositionMs.value = _scrubAccumulatedMs
        _videoOverlayTick.value = ++_videoOverlayTickValue

        // debounce: cancel previous commit, schedule new one
        _scrubCommitJob?.cancel()
        _scrubCommitJob = kotlinx.coroutines.MainScope().launch {
            delay(SCRUB_COMMIT_DEBOUNCE_MS)
            service?.seekVideoTo(_scrubAccumulatedMs)
        }
    }

    // just show the transport overlay without any action (DPAD_UP/DOWN)
    fun showVideoOverlay() {
        _videoOverlayTick.value = ++_videoOverlayTickValue
    }

    private var _videoOverlayTickValue = 0L

    // dacp controls
    fun dacpPlayPause() { service?.togglePlayPause() }
    fun dacpNext() { service?.dacpController?.nextItem() }
    fun dacpPrev() { service?.dacpController?.prevItem() }

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
            if (videoActive && !_videoPlaybackActive.value) {
                // fresh AirPlay Video session: playback starts playing, no stale overlay
                _videoPlaying.value = true
                _videoOverlayTick.value = 0L
                _videoScrubPositionMs.value = null
            }
            _videoPlaybackActive.value = videoActive
            // sync video state from the snapshot for the seek bar overlay
            if (videoActive) {
                val info = it.videoPlaybackInfo.value
                _videoPlaying.value = info.playing
                _videoPositionMs.value = info.positionMs
                _videoDurationMs.value = info.durationMs
            }
            _mirroringActive.value = it.mirroringActive.value
            _trackInfo.value = it.trackInfo.value
            _positionMs.value = it.currentPositionMs()
            _durationMs.value = it.durationMs.value
            _playing.value = it.playing.value
            if (_debugEnabled.value) {
                _debugInfo.value = it.collectDebugInfo()
            }
        }
    }
}
