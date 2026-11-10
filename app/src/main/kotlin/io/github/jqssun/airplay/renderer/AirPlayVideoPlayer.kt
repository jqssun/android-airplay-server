package io.github.jqssun.airplay.renderer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer

// rate is 0 while buffering; speed is the configured rate regardless of pause state
// native reads the effective rate, overlay reads playWhenReady + speed + skipSilence
data class PlaybackSnapshot(
    val position: Float,
    val duration: Float,
    val rate: Float,
    val ready: Boolean,
    val playWhenReady: Boolean,
    val speed: Float = 1f,
    val skipSilence: Boolean = false,
    val buffering: Boolean = false,
)

// exoplayer calls stay on the main thread; native only reads the onPlaybackInfo snapshot
class AirPlayVideoPlayer(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var pendingSurface: Surface? = null
    private var clientGain = 1f

    var onPlaybackInfo: ((PlaybackSnapshot) -> Unit)? = null
    var onVideoSize: ((width: Int, height: Int, aspect: Float) -> Unit)? = null
    var onTitle: ((String?) -> Unit)? = null
    var onEnded: (() -> Unit)? = null

    private val _reportTick = object : Runnable {
        override fun run() {
            _reportPlaybackInfo()
            mainHandler.postDelayed(this, REPORT_INTERVAL_MS)
        }
    }

    private val _listener = object : Player.Listener {
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Log.e(TAG, "ExoPlayer ERROR code=${error.errorCode} name=${error.errorCodeName} msg=${error.message}", error)
            onEnded?.invoke()
        }
        override fun onPlaybackStateChanged(state: Int) {
            android.util.Log.i("AirPlayVideoPlayer", "ExoPlayer state=$state " +
                "(${when (state) { 1 -> "IDLE"; 2 -> "BUFFERING"; 3 -> "READY"; 4 -> "ENDED"; else -> "?" }})")
            if (state == Player.STATE_ENDED) {
                // Pause but DO NOT tear down: for YouTube Shorts and similar short clips, the
                // sender often queues the next /play immediately. Sending the duration=-1 "finished"
                // sentinel to iOS triggers a full RTSP TEARDOWN that also kills the mirror
                // session, forcing the user to re-select AirPlay from Control Center.
                player?.playWhenReady = false
                onEnded?.invoke()
            }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            // duration is usually established here (esp. hls): report so the held /play releases
            _reportPlaybackInfo()
        }
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            onTitle?.invoke(mediaMetadata.title?.toString())
        }
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.height == 0) return
            val width = (videoSize.width * videoSize.pixelWidthHeightRatio).toInt()
            val aspect = width.toFloat() / videoSize.height
            android.util.Log.i("AirPlayVideoPlayer",
                "ExoPlayer videoSize=${videoSize.width}x${videoSize.height} " +
                "sar=${videoSize.pixelWidthHeightRatio} " +
                "unappliedRotation=${videoSize.unappliedRotationDegrees} " +
                "corrected=${width}x${videoSize.height} aspect=$aspect")
            onVideoSize?.invoke(width, videoSize.height, aspect)
        }
    }

    fun play(location: String, startPositionSeconds: Float) = mainHandler.post {
        // recycling must not report the stopped sentinel: senders poll right after /play
        _stopInternal(reportStopped = false)
        val p = ExoPlayer.Builder(context).build().also {
            it.addListener(_listener)
            pendingSurface?.let { s -> it.setVideoSurface(s) }
            it.volume = clientGain
        }
        player = p
        p.setMediaItem(MediaItem.fromUri(location), (startPositionSeconds * 1000).toLong())
        p.playWhenReady = true
        p.prepare()
        onPlaybackInfo?.invoke(PlaybackSnapshot(startPositionSeconds, 0f, 0f, false, true))
        mainHandler.postDelayed(_reportTick, REPORT_INTERVAL_MS)
    }

    fun scrub(positionSeconds: Float) = mainHandler.post {
        player?.seekTo((positionSeconds * 1000).toLong())
    }

    // coalesces the seek spam from drag-seeking
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun setScrubbing(enabled: Boolean) = mainHandler.post {
        player?.isScrubbingModeEnabled = enabled
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

    // local-only: the sender self-syncs from its next /playback-info poll
    fun setPlaying(playing: Boolean) = mainHandler.post {
        player?.playWhenReady = playing
    }

    // local speed toggle: unlike setRate it must not resume a paused player
    fun setSpeed(speed: Float) = mainHandler.post {
        player?.playbackParameters = PlaybackParameters(speed.coerceAtLeast(0.1f))
    }

    // linear, unity unless sender volume isn't synced to receiver
    fun setClientGain(gain: Float) = mainHandler.post {
        clientGain = gain
        player?.volume = gain
    }

    fun setSkipSilence(enabled: Boolean) = mainHandler.post {
        player?.skipSilenceEnabled = enabled
    }

    fun seekBy(deltaMs: Long) = mainHandler.post {
        val p = player ?: return@post
        var target = (p.currentPosition + deltaMs).coerceAtLeast(0)
        val duration = p.duration
        if (duration != C.TIME_UNSET) {
            target = target.coerceAtMost(duration)
        }
        p.seekTo(target)
    }

    fun setSurface(surface: Surface) = mainHandler.post {
        pendingSurface = surface
        player?.setVideoSurface(surface)
    }

    // no-op if a newer surface already replaced this one
    fun clearSurface(surface: Surface) = mainHandler.post {
        if (pendingSurface !== surface) return@post
        pendingSurface = null
        player?.setVideoSurface(null)
    }

    fun stop() = mainHandler.post { _stopInternal(reportStopped = true) }

    private fun _stopInternal(reportStopped: Boolean) {
        mainHandler.removeCallbacks(_reportTick)
        player?.let {
            it.removeListener(_listener)
            it.release()
        }
        player = null
        // duration=-1 is the "video finished" sentinel for the playback-info handler
        if (reportStopped) {
            onPlaybackInfo?.invoke(PlaybackSnapshot(0f, -1f, 0f, false, false))
        }
    }

    private fun _reportPlaybackInfo() {
        val p = player ?: return
        val durationMs = p.duration
        val position = p.currentPosition / 1000f
        // 0 = live/unknown (TIME_UNSET); a real value only exists for vod
        val duration = if (durationMs == C.TIME_UNSET) 0f else durationMs / 1000f
        val rate = if (p.playWhenReady && p.playbackState == Player.STATE_READY) p.playbackParameters.speed else 0f
        // readyToPlay = the timeline is established, NOT exoplayer's buffering state: for vod that means the duration is known; for live there is no duration so being playable is enough. the sender holds its timeline (and /play) until this, so it must not go true early
        val ready = if (p.isCurrentMediaItemLive) p.playbackState == Player.STATE_READY
                    else durationMs != C.TIME_UNSET
        onPlaybackInfo?.invoke(
            PlaybackSnapshot(
                position = position,
                duration = duration,
                rate = rate,
                ready = ready,
                playWhenReady = p.playWhenReady,
                speed = p.playbackParameters.speed,
                skipSilence = p.skipSilenceEnabled,
                buffering = p.playbackState == Player.STATE_BUFFERING,
            )
        )
    }

    companion object {
        private const val TAG = "AirPlayVideoPlayer"
        private const val REPORT_INTERVAL_MS = 250L
    }
}
