package io.github.jqssun.airplay.renderer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

// AirPlay Video: plays the local HLS proxy URL that UxPlay's /play handler hands us,
// distinct from screen mirroring's raw H.264/H.265 MediaCodec pipeline (VideoRenderer).
// All ExoPlayer calls happen on the main thread; UxPlay's httpd thread never touches
// this class directly, it only reads a cached snapshot pushed via onPlaybackInfo.
class AirPlayVideoPlayer(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var pendingSurface: Surface? = null

    // playWhenReady is separate from rate: rate drops to 0 while buffering after a
    // seek even though playback is still logically "playing" -- the UI overlay wants
    // the logical state, the sender/native side wants the effective rate
    var onPlaybackInfo: ((position: Float, duration: Float, rate: Float, readyToPlay: Boolean, playWhenReady: Boolean) -> Unit)? = null

    // invoked (on the main thread) when playback ends on its own: natural
    // end-of-stream, or a fatal player error. The error case matters for remote
    // stops: the HLS source lives on the sender, so a sender that stops casting
    // without a POST /stop just kills the source mid-stream and ExoPlayer halts on
    // a fatal error, leaving the last decoded frame on the surface forever unless
    // the owner tears the session down (mirrors uxplay's gstreamer EOS/error ->
    // video_reset handling)
    var onPlaybackEnded: (() -> Unit)? = null

    private val _reportTick = object : Runnable {
        override fun run() {
            _reportPlaybackInfo()
            mainHandler.postDelayed(this, REPORT_INTERVAL_MS)
        }
    }

    private val _listener = object : Player.Listener {
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Log.w(TAG, "playback error", error)
            onPlaybackEnded?.invoke()
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) onPlaybackEnded?.invoke()
        }
    }

    fun play(location: String, startPositionSeconds: Float) = mainHandler.post {
        // don't report the "stopped" sentinel (duration=-1) while recycling any
        // old player here: the sender polls GET /playback-info right after POST
        // /play, and reading duration=-1 at that point makes the native side
        // treat the brand-new session as already finished (video_reset
        // HLS_SHUTDOWN) and tear it down ~100ms in. Only report on real stops.
        stopInternal(reportStopped = false)
        val p = ExoPlayer.Builder(context).build().also {
            it.addListener(_listener)
            pendingSurface?.let { s -> it.setVideoSurface(s) }
        }
        player = p
        p.setMediaItem(MediaItem.fromUri(location), (startPositionSeconds * 1000).toLong())
        p.playWhenReady = true
        p.prepare()
        mainHandler.postDelayed(_reportTick, REPORT_INTERVAL_MS)
    }

    fun scrub(positionSeconds: Float) = mainHandler.post {
        player?.seekTo((positionSeconds * 1000).toLong())
    }

    fun setRate(rate: Float) = mainHandler.post {
        val p = player ?: return@post
        if (rate <= 0f) {
            p.playWhenReady = false
        } else {
            p.playbackParameters = PlaybackParameters(rate.coerceAtLeast(0.1f))
            p.playWhenReady = true
        }
    }

    // local-only control (tap-to-pause on the receiver's screen): the source device
    // isn't told about this, it's purely how the tablet plays back what it already
    // received -- matches how a TV's own remote can pause without the source knowing.
    // The sender still self-syncs: its next GET /playback-info poll sees rate=0.
    fun togglePlayPause() = mainHandler.post {
        val p = player ?: return@post
        p.playWhenReady = !p.playWhenReady
    }

    // local-only control (TV remote play/pause keys), same contract as togglePlayPause
    fun setPlaying(playing: Boolean) = mainHandler.post {
        player?.playWhenReady = playing
    }

    // local-only relative seek (TV remote left/right/rewind/fast-forward),
    // clamped to [0, duration] once the duration is known
    fun seekBy(deltaMs: Long) = mainHandler.post {
        val p = player ?: return@post
        var target = (p.currentPosition + deltaMs).coerceAtLeast(0)
        val duration = p.duration
        if (duration != androidx.media3.common.C.TIME_UNSET) {
            target = target.coerceAtMost(duration)
        }
        p.seekTo(target)
    }

    fun setSurface(surface: Surface) = mainHandler.post {
        pendingSurface = surface
        player?.setVideoSurface(surface)
    }

    // no-ops if a newer surface already replaced this one (surface lifecycle race)
    fun clearSurface(surface: Surface) = mainHandler.post {
        if (pendingSurface !== surface) return@post
        pendingSurface = null
        player?.setVideoSurface(null)
    }

    fun stop() = mainHandler.post { stopInternal(reportStopped = true) }

    private fun stopInternal(reportStopped: Boolean) {
        mainHandler.removeCallbacks(_reportTick)
        player?.let {
            it.removeListener(_listener)
            it.release()
        }
        player = null
        // duration=-1 is the "video finished" sentinel: the native GET
        // /playback-info handler answers the sender's next poll with a session
        // shutdown
        if (reportStopped) {
            onPlaybackInfo?.invoke(0f, -1f, 0f, false, false)
        }
    }

    private fun _reportPlaybackInfo() {
        val p = player ?: return
        val durationMs = p.duration
        if (durationMs == androidx.media3.common.C.TIME_UNSET) return
        val position = p.currentPosition / 1000f
        val duration = durationMs / 1000f
        val rate = if (p.playWhenReady && p.playbackState == Player.STATE_READY) p.playbackParameters.speed else 0f
        val ready = p.playbackState == Player.STATE_READY
        onPlaybackInfo?.invoke(position, duration, rate, ready, p.playWhenReady)
    }

    companion object {
        private const val TAG = "AirPlayVideoPlayer"
        private const val REPORT_INTERVAL_MS = 250L
    }
}
