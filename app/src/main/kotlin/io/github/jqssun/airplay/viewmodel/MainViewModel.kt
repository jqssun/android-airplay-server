package io.github.jqssun.airplay.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.view.Surface
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import io.github.jqssun.airplay.service.AirPlayService
import io.github.jqssun.airplay.service.AirPlayService.ServerState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

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
    private val _h265Enabled = MutableStateFlow(prefs.getBoolean("h265_enabled", true))
    val h265Enabled: StateFlow<Boolean> = _h265Enabled.asStateFlow()

    private val _alacEnabled = MutableStateFlow(prefs.getBoolean("alac_enabled", true))
    val alacEnabled: StateFlow<Boolean> = _alacEnabled.asStateFlow()

    private val _aacEnabled = MutableStateFlow(prefs.getBoolean("aac_enabled", true))
    val aacEnabled: StateFlow<Boolean> = _aacEnabled.asStateFlow()

    // Logs
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()
    private val _logList = Collections.synchronizedList(mutableListOf<String>())
    private val _dateFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun addLog(msg: String) {
        val line = "${_dateFmt.format(Date())} $msg"
        _logList.add(line)
        _logs.value = _logList.toList()
    }

    fun clearLogs() {
        _logList.clear()
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

    fun setH265Enabled(v: Boolean) {
        _h265Enabled.value = v
        prefs.edit().putBoolean("h265_enabled", v).apply()
    }

    fun setAlacEnabled(v: Boolean) {
        _alacEnabled.value = v
        prefs.edit().putBoolean("alac_enabled", v).apply()
    }

    fun setAacEnabled(v: Boolean) {
        _aacEnabled.value = v
        prefs.edit().putBoolean("aac_enabled", v).apply()
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

    fun dismissPin() {
        _pinCode.value = null
    }

    fun updateFromService() {
        service?.let {
            _serverState.value = it.serverState.value
            _connectionCount.value = it.connectionCount.value
            _pinCode.value = it.pinCode.value
            _videoAspect.value = it.videoAspect.value
            _videoResolution.value = it.videoResolution.value
        }
    }
}
