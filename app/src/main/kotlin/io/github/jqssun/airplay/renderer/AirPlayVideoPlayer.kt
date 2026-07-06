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

    var onPlaybackInfo: ((position: Float, duration: Float, rate: Float, readyToPlay: Boolean) -> Unit)? = null

    private val _reportTick = object : Runnable {
        override fun run() {
            _reportPlaybackInfo()
            mainHandler.postDelayed(this, REPORT_INTERVAL_MS)
        }
    }

    private val _listener = object : Player.Listener {
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Log.w(TAG, "playback error", error)
        }
    }

    fun play(location: String, startPositionSeconds: Float) = mainHandler.post {
        stopInternal()
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

    fun stop() = mainHandler.post { stopInternal() }

    private fun stopInternal() {
        mainHandler.removeCallbacks(_reportTick)
        player?.let {
            it.removeListener(_listener)
            it.release()
        }
        player = null
        onPlaybackInfo?.invoke(0f, -1f, 0f, false)
    }

    private fun _reportPlaybackInfo() {
        val p = player ?: return
        val durationMs = p.duration
        if (durationMs == androidx.media3.common.C.TIME_UNSET) return
        val position = p.currentPosition / 1000f
        val duration = durationMs / 1000f
        val rate = if (p.playWhenReady && p.playbackState == Player.STATE_READY) p.playbackParameters.speed else 0f
        val ready = p.playbackState == Player.STATE_READY
        onPlaybackInfo?.invoke(position, duration, rate, ready)
    }

    companion object {
        private const val TAG = "AirPlayVideoPlayer"
        private const val REPORT_INTERVAL_MS = 250L
    }
}
