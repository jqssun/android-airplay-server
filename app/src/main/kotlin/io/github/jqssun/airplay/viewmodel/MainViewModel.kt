package io.github.jqssun.airplay.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.view.Surface
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
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
    val audioCodec: String = "",
    val audioVolume: Int = 100,
    val connections: Int = 0,
) {
    val bitrateStr: String get() {
        val kbps = videoBitrate / 1000
        return if (kbps >= 1000) "${"%.1f".format(kbps / 1000.0)} Mbps" else "$kbps Kbps"
    }
}

@HiltViewModel
class MainViewModel @Inject constructor(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
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

    private val _serverName = MutableStateFlow(prefs.getString("server_name", "Android AirPlay")!!)
    val serverName: StateFlow<String> = _serverName.asStateFlow()

    // Settings
    private val _autoStart = MutableStateFlow(prefs.getBoolean("auto_start", true))
    val autoStart: StateFlow<Boolean> = _autoStart.asStateFlow()

    private val _h265Enabled = MutableStateFlow(prefs.getBoolean("h265_enabled", true))
    val h265Enabled: StateFlow<Boolean> = _h265Enabled.asStateFlow()

    private val _alacEnabled = MutableStateFlow(prefs.getBoolean("alac_enabled", false))
    val alacEnabled: StateFlow<Boolean> = _alacEnabled.asStateFlow()

    private val _swAlacEnabled = MutableStateFlow(prefs.getBoolean("sw_alac_enabled", true))
    val swAlacEnabled: StateFlow<Boolean> = _swAlacEnabled.asStateFlow()

    private val _aacEnabled = MutableStateFlow(prefs.getBoolean("aac_enabled", true))
    val aacEnabled: StateFlow<Boolean> = _aacEnabled.asStateFlow()

    private val _resolution = MutableStateFlow(prefs.getString("resolution", "auto")!!)
    val resolution: StateFlow<String> = _resolution.asStateFlow()

    private val _maxFps = MutableStateFlow(prefs.getInt("max_fps", 60))
    val maxFps: StateFlow<Int> = _maxFps.asStateFlow()

    private val _overscanned = MutableStateFlow(prefs.getBoolean("overscanned", false))
    val overscanned: StateFlow<Boolean> = _overscanned.asStateFlow()

    private val _requirePin = MutableStateFlow(prefs.getBoolean("require_pin", false))
    val requirePin: StateFlow<Boolean> = _requirePin.asStateFlow()

    private val _allowNewConn = MutableStateFlow(prefs.getBoolean("allow_new_conn", false))
    val allowNewConn: StateFlow<Boolean> = _allowNewConn.asStateFlow()

    private val _audioLatencyMs = MutableStateFlow(prefs.getInt("audio_latency_ms", -1))
    val audioLatencyMs: StateFlow<Int> = _audioLatencyMs.asStateFlow()

    // Debug
    private val _debugEnabled = MutableStateFlow(prefs.getBoolean("debug_enabled", false))
    val debugEnabled: StateFlow<Boolean> = _debugEnabled.asStateFlow()

    private val _debugInfo = MutableStateFlow(DebugInfo())
    val debugInfo: StateFlow<DebugInfo> = _debugInfo.asStateFlow()

    // Audio mode
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

    // Logs
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()
    private val _logLock = Any()
    private val _logList = mutableListOf<String>()
    private val _dateFmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    fun addLog(msg: String) {
        val line = "${_dateFmt.get()!!.format(Date())} $msg"
        val snapshot: List<String>
        synchronized(_logLock) {
            _logList.add(line)
            snapshot = _logList.toList()
        }
        _logs.value = snapshot
    }

    fun clearLogs() {
        synchronized(_logLock) { _logList.clear() }
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

    // Settings setters
    fun setServerName(name: String) {
        _serverName.value = name
        prefs.edit().putString("server_name", name).apply()
    }

    fun setAutoStart(v: Boolean) {
        _autoStart.value = v
        prefs.edit().putBoolean("auto_start", v).apply()
    }

    fun setH265Enabled(v: Boolean) {
        _h265Enabled.value = v
        prefs.edit().putBoolean("h265_enabled", v).apply()
    }

    fun setSwAlacEnabled(v: Boolean) {
        _swAlacEnabled.value = v
        prefs.edit().putBoolean("sw_alac_enabled", v).apply()
    }

    fun setAlacEnabled(v: Boolean) {
        _alacEnabled.value = v
        prefs.edit().putBoolean("alac_enabled", v).apply()
    }

    fun setAacEnabled(v: Boolean) {
        _aacEnabled.value = v
        prefs.edit().putBoolean("aac_enabled", v).apply()
    }

    fun setResolution(v: String) {
        _resolution.value = v
        prefs.edit().putString("resolution", v).apply()
    }

    fun setMaxFps(v: Int) {
        _maxFps.value = v
        prefs.edit().putInt("max_fps", v).apply()
    }

    fun setOverscanned(v: Boolean) {
        _overscanned.value = v
        prefs.edit().putBoolean("overscanned", v).apply()
    }

    fun setRequirePin(v: Boolean) {
        _requirePin.value = v
        prefs.edit().putBoolean("require_pin", v).apply()
    }

    fun setAllowNewConn(v: Boolean) {
        _allowNewConn.value = v
        prefs.edit().putBoolean("allow_new_conn", v).apply()
    }

    fun setAudioLatencyMs(v: Int) {
        _audioLatencyMs.value = v
        prefs.edit().putInt("audio_latency_ms", v).apply()
    }

    fun setDebugEnabled(v: Boolean) {
        _debugEnabled.value = v
        prefs.edit().putBoolean("debug_enabled", v).apply()
    }

    // Service binding
    fun bindService(svc: AirPlayService) {
        service = svc
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

    fun onSurfaceDestroyed() {
        service?.setVideoSurface(null)
    }

    // DACP controls
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
