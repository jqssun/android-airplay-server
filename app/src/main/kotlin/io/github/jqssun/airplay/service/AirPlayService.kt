package io.github.jqssun.airplay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import android.util.Log
import android.view.Surface
import io.github.jqssun.airplay.MainActivity
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.bridge.NativeBridge
import io.github.jqssun.airplay.bridge.RaopCallbackHandler
import io.github.jqssun.airplay.discovery.NsdServiceManager
import io.github.jqssun.airplay.renderer.AudioRenderer
import io.github.jqssun.airplay.renderer.VideoRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.NetworkInterface

class AirPlayService : Service(), RaopCallbackHandler {

    private var nativeHandle = 0L
    private var nsdManager: NsdServiceManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    val videoRenderer = VideoRenderer()
    val audioRenderer = AudioRenderer()

    private val _serverState = MutableStateFlow(ServerState.STOPPED)
    val serverState = _serverState.asStateFlow()

    private val _connectionCount = MutableStateFlow(0)
    val connectionCount = _connectionCount.asStateFlow()

    private val _videoAspect = MutableStateFlow(16f / 9f)
    val videoAspect = _videoAspect.asStateFlow()

    private val _videoResolution = MutableStateFlow("")
    val videoResolution = _videoResolution.asStateFlow()

    var logCallback: ((String) -> Unit)? = null
    var pinCallback: ((String?) -> Unit)? = null

    private fun log(msg: String) {
        Log.i(TAG, msg)
        logCallback?.invoke(msg)
    }

    inner class LocalBinder : Binder() {
        val service: AirPlayService get() = this@AirPlayService
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    fun startServer(name: String) {
        if (_serverState.value == ServerState.RUNNING) return

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "airplay:server").apply { acquire() }

        nsdManager = NsdServiceManager(this).apply { acquireMulticastLock() }

        val hwAddr = getHwAddr()
        val keyFile = filesDir.resolve("airplay.pem").absolutePath
        val nohold = prefs.getBoolean("allow_new_conn", false)
        val requirePin = prefs.getBoolean("require_pin", false)

        nativeHandle = NativeBridge.nativeInit(this, hwAddr, name, keyFile, nohold, requirePin)
        if (nativeHandle == 0L) {
            log("Native init failed")
            _serverState.value = ServerState.ERROR
            return
        }

        // Apply settings from preferences
        val maxFps = prefs.getInt("max_fps", 60)
        val overscanned = prefs.getBoolean("overscanned", false)
        val audioLatencyMs = prefs.getInt("audio_latency_ms", -1)
        val h265 = prefs.getBoolean("h265_enabled", true)
        val alac = prefs.getBoolean("alac_enabled", true)
        val aac = prefs.getBoolean("aac_enabled", true)

        NativeBridge.nativeSetH265Enabled(nativeHandle, h265)
        NativeBridge.nativeSetCodecs(nativeHandle, alac, aac)
        NativeBridge.nativeSetPlist(nativeHandle, "maxFPS", maxFps)
        NativeBridge.nativeSetPlist(nativeHandle, "overscanned", if (overscanned) 1 else 0)
        if (audioLatencyMs >= 0) NativeBridge.nativeSetPlist(nativeHandle, "audio_delay_micros", audioLatencyMs * 1000)

        // Set display params
        val dm = resources.displayMetrics
        val res = prefs.getString("resolution", "auto")!!
        val (w, h) = if (res != "auto" && res.contains("x")) {
            val parts = res.split("x")
            parts[0].toInt() to parts[1].toInt()
        } else {
            dm.widthPixels to dm.heightPixels
        }
        videoRenderer.setResolution(w, h)
        _videoResolution.value = "${w}x${h}"
        _videoAspect.value = w.toFloat() / h
        NativeBridge.nativeSetDisplaySize(nativeHandle, w, h, maxFps)

        val port = NativeBridge.nativeStart(nativeHandle)
        if (port < 0) {
            log("Native start failed")
            _serverState.value = ServerState.ERROR
            return
        }

        // Register mDNS services
        val raopTxt = NativeBridge.nativeGetRaopTxtRecords(nativeHandle) ?: emptyMap()
        val airplayTxt = NativeBridge.nativeGetAirplayTxtRecords(nativeHandle) ?: emptyMap()
        val raopName = NativeBridge.nativeGetRaopServiceName(nativeHandle) ?: "AirPlay"
        val serverName = NativeBridge.nativeGetServerName(nativeHandle) ?: name

        nsdManager?.registerRaop(raopName, port, raopTxt)
        nsdManager?.registerAirplay(serverName, port, airplayTxt)

        _serverState.value = ServerState.RUNNING
        ContextCompat.startForegroundService(this, Intent(this, AirPlayService::class.java))
        startForeground(NOTIFICATION_ID, buildNotification())
        log("Server started on port $port")
    }

    fun stopServer() {
        if (nativeHandle != 0L) {
            NativeBridge.nativeStop(nativeHandle)
            NativeBridge.nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
        nsdManager?.release()
        nsdManager = null
        wakeLock?.release()
        wakeLock = null
        videoRenderer.release()
        audioRenderer.release()
        _serverState.value = ServerState.STOPPED
        _connectionCount.value = 0
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        log("Server stopped")
    }

    fun setVideoSurface(surface: Surface?) {
        videoRenderer.setSurface(surface)
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    // RaopCallbackHandler (called from native threads)

    override fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        videoRenderer.feedFrame(data, ntpTimeNs, isH265)
    }

    override fun onAudioData(data: ByteArray, ct: Int, ntpTimeNs: Long, seqNum: Int) {
        audioRenderer.feedAudio(data, ct, ntpTimeNs)
    }

    override fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean) {
        clearPin()
        log("Audio format: ct=$ct spf=$spf screen=$usingScreen")
    }

    override fun onVideoSize(srcW: Float, srcH: Float, w: Float, h: Float) {
        clearPin()
        if (w > 0 && h > 0) {
            _videoAspect.value = w / h
            _videoResolution.value = "${w.toInt()}x${h.toInt()}"
            videoRenderer.setResolution(w.toInt(), h.toInt())
        }
        log("Video size: ${srcW}x${srcH} -> ${w}x${h}")
    }

    override fun onVolumeChange(volume: Float) {
        audioRenderer.setVolume(volume)
    }

    override fun onConnectionInit() {
        _connectionCount.value++
        log("Client connected (${_connectionCount.value})")
    }

    override fun onConnectionDestroy() {
        _connectionCount.value = (_connectionCount.value - 1).coerceAtLeast(0)
        log("Client disconnected (${_connectionCount.value})")
    }

    override fun onConnectionReset(reason: Int) {
        log("Connection reset: $reason")
    }

    override fun onDisplayPin(pin: String) {
        pinCallback?.invoke(pin)
    }

    private fun clearPin() {
        pinCallback?.invoke(null)
    }

    // Helpers

    private fun getHwAddr(): ByteArray {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (iface in interfaces) {
                if (iface.name.startsWith("wlan") || iface.name.startsWith("eth")) {
                    val mac = iface.hardwareAddress
                    if (mac != null && mac.size == 6) return mac
                }
            }
        } catch (_: Exception) {}
        // Fallback: random-ish address
        return byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte())
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    enum class ServerState { STOPPED, RUNNING, ERROR }

    companion object {
        private const val TAG = "AirPlayService"
        private const val CHANNEL_ID = "airplay_service"
        private const val NOTIFICATION_ID = 1
    }
}
