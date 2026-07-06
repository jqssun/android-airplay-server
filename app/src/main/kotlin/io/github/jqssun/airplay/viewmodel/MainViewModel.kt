package io.github.jqssun.airplay.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.view.Surface
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import io.github.jqssun.airplay.Prefs
import io.github.jqssun.airplay.audio.TrackInfo
import io.github.jqssun.airplay.service.AirPlayService
import io.github.jqssun.airplay.service.AirPlayService.ServerState
import dagger.hilt.android.lifecycle.HiltViewModel
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

    private val _serverName = MutableStateFlow(prefs.getString(Prefs.SERVER_NAME, Prefs.DEF_SERVER_NAME)!!)
    val serverName: StateFlow<String> = _serverName.asStateFlow()

    // settings
    private val _serverPort = MutableStateFlow(prefs.getInt(Prefs.SERVER_PORT, Prefs.DEF_SERVER_PORT))
    val serverPort: StateFlow<Int> = _serverPort.asStateFlow()

    private val _autoStart = MutableStateFlow(prefs.getBoolean(Prefs.AUTO_START, Prefs.DEF_AUTO_START))
    val autoStart: StateFlow<Boolean> = _autoStart.asStateFlow()

    private val _bootAutoStart = MutableStateFlow(prefs.getBoolean(Prefs.BOOT_AUTO_START, Prefs.DEF_BOOT_AUTO_START))
    val bootAutoStart: StateFlow<Boolean> = _bootAutoStart.asStateFlow()

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
        ctx.startActivity(Intent.createChooser(intent, "Export logs").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
    fun setServerPort(port: Int) { _serverPort.value = port; prefs.edit().putInt(Prefs.SERVER_PORT, port).apply() }
    fun setServerName(name: String) { _serverName.value = name; prefs.edit().putString(Prefs.SERVER_NAME, name).apply() }
    fun setAutoStart(v: Boolean) { _autoStart.value = v; prefs.edit().putBoolean(Prefs.AUTO_START, v).apply() }
    fun setBootAutoStart(v: Boolean) { _bootAutoStart.value = v; prefs.edit().putBoolean(Prefs.BOOT_AUTO_START, v).apply() }
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
        updateFromService()
    }

    fun unbindService() {
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
