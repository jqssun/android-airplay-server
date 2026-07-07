package io.github.jqssun.airplay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import io.github.jqssun.airplay.MainActivity
import io.github.jqssun.airplay.Prefs
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.WatchdogReceiver
import io.github.jqssun.airplay.audio.DacpController
import io.github.jqssun.airplay.audio.DmapParser
import io.github.jqssun.airplay.audio.TrackInfo
import io.github.jqssun.airplay.bridge.NativeBridge
import io.github.jqssun.airplay.bridge.RaopCallbackHandler
import io.github.jqssun.airplay.discovery.NsdServiceManager
import io.github.jqssun.airplay.renderer.AirPlayVideoPlayer
import io.github.jqssun.airplay.renderer.AudioRenderer
import io.github.jqssun.airplay.renderer.VideoRenderer
import io.github.jqssun.airplay.viewmodel.DebugInfo
import java.net.NetworkInterface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

// snapshot of the local AirPlay Video (HLS) ExoPlayer state, refreshed by the same
// 250ms tick that feeds the native /playback-info handler; the UI transport overlay
// reads this instead of poking the player from off the main thread
data class VideoPlaybackInfo(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = true,
)

class AirPlayService : Service(), RaopCallbackHandler {

    private var nativeHandle = 0L
    private var nsdManager: NsdServiceManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false
    private val mainHandler = Handler(Looper.getMainLooper())

    val videoRenderer = VideoRenderer()
    val audioRenderer = AudioRenderer()
    val airPlayVideoPlayer by lazy { AirPlayVideoPlayer(this) }

    private val _serverState = MutableStateFlow(ServerState.STOPPED)
    val serverState = _serverState.asStateFlow()

    private val _connectionCount = MutableStateFlow(0)
    val connectionCount = _connectionCount.asStateFlow()

    private val _videoAspect = MutableStateFlow(16f / 9f)
    val videoAspect = _videoAspect.asStateFlow()

    private val _videoResolution = MutableStateFlow("")
    val videoResolution = _videoResolution.asStateFlow()

    private val _audioOnly = MutableStateFlow(false)
    val audioOnly = _audioOnly.asStateFlow()

    private val _videoPlaybackActive = MutableStateFlow(false)
    val videoPlaybackActive = _videoPlaybackActive.asStateFlow()

    private val _videoPlaybackInfo = MutableStateFlow(VideoPlaybackInfo())
    val videoPlaybackInfo = _videoPlaybackInfo.asStateFlow()

    // true only once onVideoSize (the mirroring-only video_report_size callback) has
    // actually reported a real size for the current connection cycle -- unlike
    // connectionCount, this can't be confused with an AirPlay Video or audio-only
    // session that also briefly holds connections open before its own kind of
    // playback (or none at all) becomes clear.
    private val _mirroringActive = MutableStateFlow(false)
    val mirroringActive = _mirroringActive.asStateFlow()

    private val _trackInfo = MutableStateFlow(TrackInfo())
    val trackInfo = _trackInfo.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs = _durationMs.asStateFlow()

    private val _playing = MutableStateFlow(true)
    val playing = _playing.asStateFlow()

    @Volatile private var _progressBaseMs = 0L
    @Volatile private var _progressBaseTime = 0L

    fun currentPositionMs(): Long {
        if (_progressBaseTime == 0L || !_playing.value) return _positionMs.value
        val elapsed = SystemClock.elapsedRealtime() - _progressBaseTime
        return (_progressBaseMs + elapsed).coerceIn(0, _durationMs.value)
    }

    var dacpController: DacpController? = null
        private set
    private var mediaSession: MediaSessionCompat? = null
    private var mediaReceiver: BroadcastReceiver? = null

    var logCallback: ((String) -> Unit)? = null
    var modeCallback: ((Boolean) -> Unit)? = null

    @Volatile private var _lastPin: String? = null
    var pinCallback: ((String?) -> Unit)? = null
        set(value) {
            field = value
            // ui replay only: binding the activity must not mint a new native pin
            value?.invoke(_lastPin)
        }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        logCallback?.invoke(msg)
    }

    inner class LocalBinder : Binder() {
        val service: AirPlayService
            get() = this@AirPlayService
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        dacpController = DacpController(this)
        mediaSession = MediaSessionCompat(this, "AirPlay").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                // during an AirPlay Video (HLS) session transport keys drive the local
                // ExoPlayer; otherwise they keep controlling the audio sender via DACP
                override fun onPlay() {
                    if (_videoPlaybackActive.value) {
                        airPlayVideoPlayer.setPlaying(true)
                        return
                    }
                    _setPlaying(true)
                    dacpController?.play()
                }
                override fun onPause() {
                    if (_videoPlaybackActive.value) {
                        airPlayVideoPlayer.setPlaying(false)
                        return
                    }
                    _setPlaying(false)
                    dacpController?.pause()
                }
                override fun onStop() {
                    if (_videoPlaybackActive.value) stopVideoPlayback()
                }
                override fun onFastForward() {
                    if (_videoPlaybackActive.value) airPlayVideoPlayer.seekBy(VIDEO_SEEK_FORWARD_MS)
                }
                override fun onRewind() {
                    if (_videoPlaybackActive.value) airPlayVideoPlayer.seekBy(-VIDEO_SEEK_BACK_MS)
                }
                override fun onSeekTo(pos: Long) {
                    if (_videoPlaybackActive.value) airPlayVideoPlayer.scrub(pos / 1000f)
                }
                override fun onSkipToNext() {
                    if (!_videoPlaybackActive.value) dacpController?.nextItem()
                }
                override fun onSkipToPrevious() {
                    if (!_videoPlaybackActive.value) dacpController?.prevItem()
                }
            })
        }
        mediaReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_PLAY_PAUSE -> togglePlayPause()
                    ACTION_NEXT -> dacpController?.nextItem()
                    ACTION_PREV -> dacpController?.prevItem()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_PLAY_PAUSE)
            addAction(ACTION_NEXT)
            addAction(ACTION_PREV)
        }
        ContextCompat.registerReceiver(this, mediaReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        // natural end-of-stream, or the sender stopping without a POST /stop (its HLS
        // source just dies and the player errors out): end the session either way so
        // the UI isn't left frozen on the last decoded frame
        airPlayVideoPlayer.onPlaybackEnded = { endVideoPlayback(" (playback ended)") }

        airPlayVideoPlayer.onPlaybackInfo = { position, duration, rate, ready, playWhenReady ->
            if (nativeHandle != 0L) {
                NativeBridge.nativeUpdatePlaybackInfo(nativeHandle, position, duration, rate, ready)
            }
            // keep the media session mirroring the real ExoPlayer state so system
            // media-button routing (TV remotes, bluetooth) stays on the video session
            if (_videoPlaybackActive.value) {
                _updateVideoPlaybackState(position, rate)
                _videoPlaybackInfo.value = VideoPlaybackInfo(
                    positionMs = (position * 1000).toLong(),
                    durationMs = if (duration > 0f) (duration * 1000).toLong() else 0L,
                    playing = playWhenReady,
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        // a null intent means the system recreated us after the process was killed
        // (START_STICKY) -- only resume if we were actually running before, not
        // after a deliberate stopServer()
        val isStickyRestart = intent == null && prefs.getBoolean(Prefs.SERVER_SHOULD_RUN, false)
        if (intent?.action == ACTION_START_SERVER || isStickyRestart) {
            promoteToForeground()
            startServer(Prefs.serverName(this), ensureServiceStarted = false)
            if (_serverState.value != ServerState.RUNNING) stopSelf(startId)
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // some OEM battery managers kill the whole process shortly after the task
        // card is swiped away, even for an active foreground service -- pull the
        // watchdog check in close instead of waiting out the full interval
        if (_serverState.value == ServerState.RUNNING) {
            WatchdogReceiver.schedule(applicationContext, delayMs = 15_000L)
        }
    }

    fun startServer(name: String) {
        startServer(name, ensureServiceStarted = true)
    }

    private fun startServer(name: String, ensureServiceStarted: Boolean) {
        if (_serverState.value == ServerState.RUNNING) return
        val effectiveName = name.ifBlank { Prefs.defaultServerName(this) }

        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "airplay:server").apply { acquire() }

        nsdManager = NsdServiceManager(this).apply { acquireMulticastLock() }

        val hwAddr = getHwAddr()
        val keyFile = filesDir.resolve("airplay.pem").absolutePath
        val nohold = prefs.getBoolean(Prefs.ALLOW_NEW_CONN, Prefs.DEF_ALLOW_NEW_CONN)
        val requirePin = prefs.getBoolean(Prefs.REQUIRE_PIN, Prefs.DEF_REQUIRE_PIN)

        nativeHandle = NativeBridge.nativeInit(this, hwAddr, effectiveName, keyFile, nohold, requirePin)
        if (nativeHandle == 0L) {
            log("Native init failed")
            _failStart()
            return
        }

        // apply settings from preferences
        val maxFps = prefs.getInt(Prefs.MAX_FPS, Prefs.DEF_MAX_FPS)
        val overscanned = prefs.getBoolean(Prefs.OVERSCANNED, Prefs.DEF_OVERSCANNED)
        val audioLatencyMs = prefs.getInt(Prefs.AUDIO_LATENCY_MS, Prefs.DEF_AUDIO_LATENCY_MS)
        val h265 = prefs.getBoolean(Prefs.H265_ENABLED, Prefs.DEF_H265_ENABLED)
        val alac = prefs.getBoolean(Prefs.ALAC_ENABLED, Prefs.DEF_ALAC_ENABLED)
        val aac = prefs.getBoolean(Prefs.AAC_ENABLED, Prefs.DEF_AAC_ENABLED)

        audioRenderer.swAlacEnabled = prefs.getBoolean(Prefs.SW_ALAC_ENABLED, Prefs.DEF_SW_ALAC_ENABLED)
        audioRenderer.audioBufferMultiplier = prefs.getInt(Prefs.AUDIO_BUFFER_MULTIPLIER, Prefs.DEF_AUDIO_BUFFER_MULTIPLIER)
        videoRenderer.enforceSdr = prefs.getBoolean(Prefs.ENFORCE_SDR, Prefs.DEF_ENFORCE_SDR)
        videoRenderer.keyAllowFrameDrop = prefs.getBoolean(Prefs.KEY_ALLOW_FRAME_DROP, Prefs.DEF_KEY_ALLOW_FRAME_DROP)
        val realtimePriority = prefs.getBoolean(Prefs.KEY_PRIORITY, Prefs.DEF_KEY_PRIORITY)
        videoRenderer.realtimeDecoderPriority = realtimePriority
        videoRenderer.operatingRateHint = prefs.getBoolean(Prefs.KEY_OPERATING_RATE, Prefs.DEF_KEY_OPERATING_RATE)
        videoRenderer.benchmarkLog = prefs.getBoolean(Prefs.BENCHMARK_LOG, Prefs.DEF_BENCHMARK_LOG)
        videoRenderer.benchmarkLogCallback = { msg -> logCallback?.invoke(msg) }
        videoRenderer.scheduledOutputBufferRelease = prefs.getBoolean(Prefs.SCHEDULED_OUTPUT_BUFFER_RELEASE, Prefs.DEF_SCHEDULED_OUTPUT_BUFFER_RELEASE)
        audioRenderer.realtimeDecoderPriority = realtimePriority
        NativeBridge.nativeSetH265Enabled(nativeHandle, h265)
        NativeBridge.nativeSetCodecs(nativeHandle, alac, aac)
        NativeBridge.nativeSetHlsEnabled(nativeHandle, true)
        NativeBridge.nativeSetPlist(nativeHandle, "maxFPS", maxFps)
        NativeBridge.nativeSetPlist(nativeHandle, "overscanned", if (overscanned) 1 else 0)
        if (audioLatencyMs >= 0) {
            NativeBridge.nativeSetPlist(nativeHandle, "audio_delay_micros", audioLatencyMs * 1000)
        }

        // set display params
        val dm = resources.displayMetrics
        val res = prefs.getString(Prefs.RESOLUTION, Prefs.DEF_RESOLUTION)!!
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

        val requestedPort = prefs.getInt(Prefs.SERVER_PORT, Prefs.DEF_SERVER_PORT).coerceIn(1, 65535)
        val port = NativeBridge.nativeStart(nativeHandle, requestedPort)
        if (port < 0) {
            log("Failed to start on port $requestedPort")
            _failStart()
            return
        }

        // register mdns services
        val raopTxt = NativeBridge.nativeGetRaopTxtRecords(nativeHandle) ?: emptyMap()
        val airplayTxt = NativeBridge.nativeGetAirplayTxtRecords(nativeHandle) ?: emptyMap()
        val raopName = NativeBridge.nativeGetRaopServiceName(nativeHandle) ?: "AirPlay"
        val resolvedName = NativeBridge.nativeGetServerName(nativeHandle) ?: effectiveName

        nsdManager?.registerRaop(raopName, port, raopTxt)
        nsdManager?.registerAirplay(resolvedName, port, airplayTxt)

        _serverState.value = ServerState.RUNNING
        prefs.edit().putBoolean(Prefs.SERVER_SHOULD_RUN, true).apply()
        WatchdogReceiver.schedule(this)
        if (ensureServiceStarted) {
            ContextCompat.startForegroundService(this, Intent(this, AirPlayService::class.java))
        }
        promoteToForeground()
        log("Server started on port $port")
    }

    private fun _failStart() {
        if (nativeHandle != 0L) {
            NativeBridge.nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
        nsdManager?.release()
        nsdManager = null
        wakeLock?.release()
        wakeLock = null
        _serverState.value = ServerState.ERROR
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
    }

    fun stopServer() {
        if (nativeHandle != 0L) {
            NativeBridge.nativeStop(nativeHandle)
            NativeBridge.nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
        dacpController?.release()
        nsdManager?.release()
        nsdManager = null
        wakeLock?.release()
        wakeLock = null
        videoRenderer.release()
        audioRenderer.release()
        airPlayVideoPlayer.stop()
        mediaSession?.isActive = false
        _audioOnly.value = false
        _videoPlaybackActive.value = false
        _mirroringActive.value = false
        _trackInfo.value = TrackInfo()
        _positionMs.value = 0
        _durationMs.value = 0
        _serverState.value = ServerState.STOPPED
        _connectionCount.value = 0
        getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).edit().putBoolean(Prefs.SERVER_SHOULD_RUN, false).apply()
        WatchdogReceiver.cancel(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
        log("Server stopped")
    }

    fun setVideoSurface(surface: Surface) {
        videoRenderer.setSurface(surface)
    }

    fun clearVideoSurface(surface: Surface) {
        videoRenderer.clearSurface(surface)
    }

    fun setVideoPlaybackSurface(surface: Surface) {
        airPlayVideoPlayer.setSurface(surface)
    }

    fun clearVideoPlaybackSurface(surface: Surface) {
        airPlayVideoPlayer.clearSurface(surface)
    }

    fun toggleVideoPlayback() {
        airPlayVideoPlayer.togglePlayPause()
    }

    fun setVideoPlaying(playing: Boolean) {
        airPlayVideoPlayer.setPlaying(playing)
    }

    fun seekVideoBy(deltaMs: Long) {
        airPlayVideoPlayer.seekBy(deltaMs)
    }

    // absolute local seek: the transport overlay accumulates held-key scrubbing in the
    // viewmodel and commits the final target here once, instead of dozens of seekBy calls
    fun seekVideoTo(positionMs: Long) {
        airPlayVideoPlayer.scrub(positionMs / 1000f)
    }

    // local stop (TV remote stop/back): same teardown as the sender's POST /stop;
    // the sender learns about it from ready=false on its next /playback-info poll
    fun stopVideoPlayback() {
        endVideoPlayback(" (local)")
    }

    // -- sender-liveness watchdog for AirPlay Video --
    //
    // Backstop for sender apps that abandon a video without a usable stop signal
    // (the deterministic primary signal is the native play-connection-close rule,
    // RESET_TYPE_HLS_CONN_CLOSED in raop.c). The native layer timestamps every
    // video-session request (NativeBridge.nativeMsSinceVideoRequest); while a video
    // session is active but NOT progressing, this watchdog treats a long request
    // silence as sender-abandoned and ends the session. It never fires while the
    // position is advancing, so a steady-state playing session can't be killed by
    // it. Note it does NOT cover senders that keep polling /playback-info after
    // abandoning (iFit does, from a connection that outlives the session): that
    // case is exactly what the connection-close rule is for. The watchdog also
    // logs sender poll activity so real-device traces show which case occurred.

    // last position sampled by the watchdog, to detect "not progressing"
    private var _livenessLastPositionMs = -1L
    // poll-activity accounting for the throttled request logs
    private var _livenessLastRequestCount = -1L
    private var _livenessLogCountdown = 0
    private var _senderPollingStopped = false

    private val _senderLivenessTick = object : Runnable {
        override fun run() {
            if (!_videoPlaybackActive.value) return
            _checkSenderLiveness()
            if (_videoPlaybackActive.value) {
                mainHandler.postDelayed(this, SENDER_LIVENESS_CHECK_MS)
            }
        }
    }

    private fun _checkSenderLiveness() {
        val handle = nativeHandle
        if (handle == 0L) return
        val silentMs = NativeBridge.nativeMsSinceVideoRequest(handle)
        // -1 (no request seen) can't normally happen while a session is active,
        // since the session's own POST /play bumps the timestamp
        if (silentMs < 0) return
        val requestCount = NativeBridge.nativeVideoRequestCount(handle)

        // throttled poll-activity trace (logcat debug): one line per 5s with the
        // number of sender requests (mostly /playback-info polls) seen since the
        // previous line -- the ground truth for whether a sender is still polling
        if (_livenessLastRequestCount < 0) {
            _livenessLastRequestCount = requestCount
            _livenessLogCountdown = LIVENESS_LOG_EVERY_TICKS
        } else if (--_livenessLogCountdown <= 0) {
            Log.d(TAG, "AirPlay Video sender requests: " +
                "+${requestCount - _livenessLastRequestCount} in last " +
                "${LIVENESS_LOG_EVERY_TICKS}s (last ${silentMs}ms ago)")
            _livenessLastRequestCount = requestCount
            _livenessLogCountdown = LIVENESS_LOG_EVERY_TICKS
        }

        // polling stopped/resumed transitions (app log)
        val pollingSilent = silentMs >= SENDER_POLL_STOPPED_MS
        if (pollingSilent && !_senderPollingStopped) {
            _senderPollingStopped = true
            log("AirPlay Video sender polling stopped (last request ${silentMs / 1000}s ago)")
        } else if (!pollingSilent && _senderPollingStopped) {
            _senderPollingStopped = false
            log("AirPlay Video sender polling resumed")
        }

        // positionMs comes from the 250ms ExoPlayer snapshot; between watchdog ticks
        // (1s apart) it always moves while playback progresses. It stays frozen when
        // paused, stalled, errored, or still stuck loading (no snapshot at all).
        val positionMs = _videoPlaybackInfo.value.positionMs
        val progressing = positionMs != _livenessLastPositionMs
        _livenessLastPositionMs = positionMs
        if (progressing) return
        if (silentMs < SENDER_LIVENESS_CHECK_MS) return // requests still arriving
        // true-silence countdown, visible in traces (at most ~10 lines per incident)
        log("AirPlay Video sender silent ${silentMs / 1000}s/" +
            "${SENDER_ABANDON_TIMEOUT_MS / 1000}s, position frozen")
        if (silentMs < SENDER_ABANDON_TIMEOUT_MS) return
        endVideoPlayback(" (sender silent for ${silentMs / 1000}s)")
    }

    /**
     * Single teardown for the end of an AirPlay Video session, whatever ended it:
     * the sender's POST /stop or TEARDOWN ([onVideoStop]), the sender dropping its
     * connections ([onConnectionDestroy]), a local TV-remote BACK/STOP
     * ([stopVideoPlayback]) or the local player finishing/erroring out
     * ([AirPlayVideoPlayer.onPlaybackEnded]). Clearing [videoPlaybackActive] here is
     * what dismisses the video screen in the UI (MainViewModel mirrors this flow
     * event-driven) and what lets a session-summoned MainActivity return to the
     * previously foregrounded app.
     */
    private fun endVideoPlayback(logSuffix: String = "") {
        if (!_videoPlaybackActive.value) return
        _videoPlaybackActive.value = false
        mainHandler.removeCallbacks(_senderLivenessTick)
        airPlayVideoPlayer.stop()
        if (!_audioOnly.value) mediaSession?.isActive = false
        log("AirPlay Video stopped$logSuffix")
    }

    override fun onDestroy() {
        stopServer()
        mediaReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        mediaReceiver = null
        dacpController?.release()
        dacpController = null
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    // RaopCallbackHandler (called from native threads)

    override fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        videoRenderer.feedFrame(data, ntpTimeNs, isH265)
    }

    override fun onAudioData(data: ByteArray, ct: Int, ntpTimeNs: Long, seqNum: Int) {
        audioRenderer.feedAudio(data, ct, ntpTimeNs)
    }

    override fun onVideoPlay(location: String, startPositionSeconds: Float) {
        _videoPlaybackInfo.value = VideoPlaybackInfo(positionMs = (startPositionSeconds * 1000).toLong())
        _videoPlaybackActive.value = true
        airPlayVideoPlayer.play(location, startPositionSeconds)
        // claim media-button routing so remote play/pause/stop keys reach the video
        // player even when they arrive as media-session events rather than KeyEvents
        mediaSession?.isActive = true
        log("AirPlay Video play: $location @ ${startPositionSeconds}s")
        // (re)arm the sender-liveness watchdog for this session
        mainHandler.removeCallbacks(_senderLivenessTick)
        _livenessLastPositionMs = -1L
        _livenessLastRequestCount = -1L
        _senderPollingStopped = false
        mainHandler.postDelayed(_senderLivenessTick, SENDER_LIVENESS_CHECK_MS)
        // (re-)summon the UI for EVERY play, not only the first client connection:
        // switching videos in the sender app stops one item and plays the next on
        // the same connection -- possibly after a summoned activity has already
        // stepped back to the previous app -- and without this the new video would
        // play its audio into an invisible activity with no surface. Harmless when
        // the activity is already frontmost (singleTop redelivery; the summon flag
        // is only set when the start actually brings the activity forward).
        if (shouldLaunchOnConnect()) launchMainActivity()
    }

    override fun onVideoScrub(positionSeconds: Float) {
        log("AirPlay Video scrub: ${positionSeconds}s")
        airPlayVideoPlayer.scrub(positionSeconds)
    }

    override fun onVideoRate(rate: Float) {
        log("AirPlay Video rate: $rate")
        airPlayVideoPlayer.setRate(rate)
    }

    override fun onVideoStop() {
        endVideoPlayback()
    }

    override fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean) {
        clearPin()
        if (!usingScreen && !_audioOnly.value) {
            // pure music streaming (not screen mirroring audio)
            onAudioOnly(true)
        }
        log("Audio format: ct=$ct spf=$spf screen=$usingScreen")
    }

    override fun onVideoSize(srcW: Float, srcH: Float, w: Float, h: Float) {
        clearPin()
        if (w > 0 && h > 0) {
            _videoAspect.value = w / h
            _videoResolution.value = "${w.toInt()}x${h.toInt()}"
            videoRenderer.setResolution(w.toInt(), h.toInt())
            _mirroringActive.value = true
        }
        log("Video size: ${srcW}x${srcH} -> ${w}x${h}")
    }

    override fun onVolumeChange(volume: Float) {
        audioRenderer.setVolume(volume)
    }

    override fun onConnectionInit() {
        val firstConnection = _connectionCount.value == 0
        _connectionCount.value++
        log("Client connected (${_connectionCount.value})")
        if (!firstConnection) return
        // conn_init is only a tcp pre-auth signal. pin-required sessions must wait for
        // onDisplayPin, otherwise the server ui can move before the client pin is current
        if (requiresPin()) return
        if (!shouldLaunchOnConnect()) return
        launchMainActivity()
    }

    override fun onConnectionDestroy() {
        _connectionCount.value = (_connectionCount.value - 1).coerceAtLeast(0)
        if (_connectionCount.value == 0) {
            _audioOnly.value = false
            _mirroringActive.value = false
            _trackInfo.value = TrackInfo()
            _positionMs.value = 0
            _durationMs.value = 0
            mediaSession?.isActive = false
            audioRenderer.markSessionEnded()
            // the client may drop the connection without ever sending POST /stop
            // (closing the app, leaving the video, losing the network), so don't
            // rely solely on onVideoStop to end AirPlay Video playback
            endVideoPlayback(" (disconnected)")
        }
        log("Client disconnected (${_connectionCount.value})")
    }

    override fun onConnectionReset(reason: Int) {
        audioRenderer.markSessionEnded()
        log("Connection reset: $reason")
    }

    override fun onDisplayPin(pin: String) {
        // a new pin is the sync point with the client prompt: show every new value immediately
        if (_lastPin == pin) return
        _lastPin = pin
        pinCallback?.invoke(pin)
        _updateMediaNotification()
    }

    override fun onMetadata(data: ByteArray) {
        val map = DmapParser.parse(data)
        val info = TrackInfo.fromDmap(map, _trackInfo.value.coverArt)
        _trackInfo.value = info
        _durationMs.value = info.durationMs
        _updateMediaMetadata()
        log("Track: ${info.artist} - ${info.title}")
    }

    override fun onCoverArt(data: ByteArray) {
        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return
        _trackInfo.value = _trackInfo.value.copy(coverArt = bmp)
        _updateMediaMetadata()
    }

    override fun onProgress(start: Long, curr: Long, end: Long) {
        val rate = 44100.0
        val posMs = ((curr - start) / rate * 1000).toLong().coerceAtLeast(0)
        val durMs = ((end - start) / rate * 1000).toLong().coerceAtLeast(0)
        _positionMs.value = posMs
        _durationMs.value = durMs
        _progressBaseMs = posMs
        _progressBaseTime = SystemClock.elapsedRealtime()
        _playing.value = true
        _updatePlaybackState()
    }

    override fun onDacpId(dacpId: String, activeRemote: String) {
        dacpController?.update(dacpId, activeRemote)
        log("DACP: $dacpId")
    }

    override fun onAudioOnly(audioOnly: Boolean) {
        val prev = _audioOnly.value
        _audioOnly.value = audioOnly
        if (audioOnly && !prev) {
            mediaSession?.isActive = true
            modeCallback?.invoke(true)
            log("Audio mode")
        } else if (!audioOnly && prev) {
            mediaSession?.isActive = false
            _trackInfo.value = TrackInfo()
            _positionMs.value = 0
            _durationMs.value = 0
            modeCallback?.invoke(false)
            log("Mirror mode")
        }
    }

    private fun _updateMediaMetadata() {
        val info = _trackInfo.value
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, info.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, info.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, info.album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, _durationMs.value)
        info.coverArt?.let { builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
        mediaSession?.setMetadata(builder.build())
        _updateMediaNotification()
    }

    fun togglePlayPause() {
        val nowPlaying = !_playing.value
        _setPlaying(nowPlaying)
        dacpController?.playPause()
    }

    private fun _setPlaying(playing: Boolean) {
        _playing.value = playing
        if (playing) {
            // resume extrapolation from current position
            _progressBaseMs = _positionMs.value
            _progressBaseTime = SystemClock.elapsedRealtime()
        } else {
            // freeze position
            _positionMs.value = currentPositionMs()
            _progressBaseTime = 0
        }
        _updatePlaybackState()
    }

    private fun _updatePlaybackState() {
        val isPlaying = _playing.value
        val pbState = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val speed = if (isPlaying) 1f else 0f
        val state = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            )
            .setState(pbState, _positionMs.value, speed, SystemClock.elapsedRealtime())
            .build()
        mediaSession?.setPlaybackState(state)
        _updateMediaNotification()
    }

    // AirPlay Video variant of _updatePlaybackState: state comes straight from the
    // 250ms ExoPlayer snapshot instead of the audio progress extrapolation, and the
    // (audio-only) media notification is left alone
    private fun _updateVideoPlaybackState(positionSeconds: Float, rate: Float) {
        val playing = rate > 0f
        val state = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_FAST_FORWARD or
                PlaybackStateCompat.ACTION_REWIND or
                PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(
                if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                (positionSeconds * 1000).toLong(),
                if (playing) rate else 0f,
                SystemClock.elapsedRealtime()
            )
            .build()
        mediaSession?.setPlaybackState(state)
    }

    private fun clearPin() {
        _lastPin = null
        pinCallback?.invoke(null)
        _updateMediaNotification()
    }

    fun collectDebugInfo() = DebugInfo(
        videoCodec = videoRenderer.codecName,
        videoRes = _videoResolution.value,
        videoFps = videoRenderer.fps,
        videoBitrate = videoRenderer.bitrateBps,
        videoFrames = videoRenderer.frameCount,
        droppedFrames = videoRenderer.droppedFrames,
        framePacingJitterUs = videoRenderer.framePacingJitterUs,
        audioCodec = audioRenderer.codecLabel,
        audioVolume = (audioRenderer.volume * 100).toInt(),
        connections = _connectionCount.value,
    )

    // helpers

    private fun getHwAddr(): ByteArray {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (iface in interfaces) {
                if (iface.name.startsWith("wlan") || iface.name.startsWith("eth")) {
                    val mac = iface.hardwareAddress
                    if (mac != null && mac.size == 6) return mac
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get hardware address", e)
        }
        // fallback: random-ish address
        return byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return _buildMediaNotification()
    }

    private fun promoteToForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
        foregroundStarted = true
    }

    private fun requiresPin(): Boolean {
        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(Prefs.REQUIRE_PIN, Prefs.DEF_REQUIRE_PIN)
    }

    private fun shouldLaunchOnConnect(): Boolean {
        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(Prefs.LAUNCH_ON_CONNECT, Prefs.DEF_LAUNCH_ON_CONNECT)
    }

    private fun _buildMediaNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val info = _trackInfo.value
        val isAudio = _audioOnly.value && info.title.isNotEmpty()

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOngoing(true)

        if (isAudio) {
            builder.setContentTitle(info.title).setContentText(info.artist).setSubText(info.album)
            info.coverArt?.let { builder.setLargeIcon(it) }
            mediaSession?.sessionToken?.let { token ->
                builder.setStyle(
                    MediaNotificationCompat.MediaStyle()
                        .setMediaSession(token)
                        .setShowActionsInCompactView(0, 1, 2)
                )
                // transport action buttons
                builder.addAction(android.R.drawable.ic_media_previous, "Prev", _mediaAction(ACTION_PREV))
                builder.addAction(android.R.drawable.ic_media_pause, "Pause", _mediaAction(ACTION_PLAY_PAUSE))
                builder.addAction(android.R.drawable.ic_media_next, "Next", _mediaAction(ACTION_NEXT))
            }
        } else {
            if (_lastPin != null) {
                // passive handoff only: do not launch/reorder the activity during pin auth
                builder.setContentTitle(getString(R.string.notification_pin_title))
                    .setContentText(getString(R.string.notification_pin_text, _lastPin))
            } else {
                builder.setContentTitle(getString(R.string.notification_title))
                    .setContentText(getString(R.string.notification_text))
            }
        }
        return builder.build()
    }

    // "Open this application on connect": summon the UI for an incoming session. The
    // extra marks the start as session-driven so MainActivity can step back out of the
    // way (return to the previously foregrounded app/input) once the session ends --
    // unlike launcher/notification starts, which carry no extra.
    private fun launchMainActivity() {
        Handler(Looper.getMainLooper()).post {
            val launchIntent = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(MainActivity.EXTRA_SESSION_SUMMON, true)
            try {
                startActivity(launchIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to launch activity", e)
            }
        }
    }

    private fun _mediaAction(action: String): PendingIntent {
        val intent = Intent(action).setPackage(packageName)
        return PendingIntent.getBroadcast(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun _updateMediaNotification() {
        if (_serverState.value != ServerState.RUNNING) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, _buildMediaNotification())
    }

    enum class ServerState {
        STOPPED,
        RUNNING,
        ERROR
    }

    companion object {
        private const val TAG = "AirPlayService"
        private const val CHANNEL_ID = "airplay_service"
        private const val NOTIFICATION_ID = 1
        const val ACTION_PLAY_PAUSE = "io.github.jqssun.airplay.PLAY_PAUSE"
        const val ACTION_NEXT = "io.github.jqssun.airplay.NEXT"
        const val ACTION_PREV = "io.github.jqssun.airplay.PREV"
        const val ACTION_START_SERVER = "io.github.jqssun.airplay.START_SERVER"
        // TV-remote relative seek steps for AirPlay Video
        const val VIDEO_SEEK_BACK_MS = 10_000L
        const val VIDEO_SEEK_FORWARD_MS = 15_000L

        // -- sender-liveness watchdog for AirPlay Video --
        //
        // Backstop for sender apps that abandon a video without a usable stop signal
        // (the deterministic primary signal is the native play-connection-close rule,
        // RESET_TYPE_HLS_CONN_CLOSED in raop.c). The native layer timestamps every
        // video-session request (NativeBridge.nativeMsSinceVideoRequest); while a video
        // session is active but NOT progressing, this watchdog treats a long request
        // silence as sender-abandoned and ends the session. It never fires while the
        // position is advancing, so a steady-state playing session can't be killed by
        // it. Note it does NOT cover senders that keep polling /playback-info after
        // abandoning (iFit does, from a connection that outlives the session): that
        // case is exactly what the connection-close rule is for. The watchdog also
        // logs sender poll activity so real-device traces show which case occurred.
        const val SENDER_ABANDON_TIMEOUT_MS = 10_000L
        private const val SENDER_LIVENESS_CHECK_MS = 1_000L
        private const val LIVENESS_LOG_EVERY_TICKS = 5
        private const val SENDER_POLL_STOPPED_MS = 3_000L
    }
}
