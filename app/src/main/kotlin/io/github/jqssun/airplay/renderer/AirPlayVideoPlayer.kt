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
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import java.util.Locale

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
    private val mediaHttp by lazy {
        OkHttpClient.Builder()
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            .followSslRedirects(false)
            .build()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun mediaSourceFactory(location: String): DefaultMediaSourceFactory {
        // One HTTP/2-capable stack for HTTPS media on every CDN host.
        // Other sources retain the pre-existing platform path (including the local AirPlay relay).
        if (!android.net.Uri.parse(location).scheme.equals("https", ignoreCase = true)) {
            return DefaultMediaSourceFactory(context)
        }
        val http = OkHttpDataSource.Factory(mediaHttp)
            .setUserAgent("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1")
        return DefaultMediaSourceFactory(context)
            .setDataSourceFactory(DefaultDataSource.Factory(context, http))
    }

    private fun tokenTimeState(uri: android.net.Uri?): String {
        if (uri == null || !uri.isHierarchical) return "unavailable"
        val expires = listOf("validto", "expires", "exp")
            .firstNotNullOfOrNull { uri.getQueryParameter(it)?.toLongOrNull() }
        return expires?.let { if (it >= System.currentTimeMillis() / 1000) "current" else "expired" }
            ?: "unavailable"
    }


    var onPlaybackInfo: ((PlaybackSnapshot) -> Unit)? = null
    var onVideoSize: ((width: Int, height: Int, aspect: Float) -> Unit)? = null
    var onTitle: ((String?) -> Unit)? = null
    var onEnded: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val _reportTick = object : Runnable {
        override fun run() {
            _reportPlaybackInfo()
            mainHandler.postDelayed(this, REPORT_INTERVAL_MS)
        }
    }

    private val _listener = object : Player.Listener {
        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            // Only error codes, HTTP status and exception types: never log signed
            // media URLs, cookies, headers, response bodies or exception messages.
            val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
            val httpError = causes.filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>()
                .firstOrNull()
            val status = httpError?.responseCode
            val failed = httpError?.dataSpec?.uri
            val last = failed?.lastPathSegment?.lowercase(Locale.ROOT).orEmpty()
            val stage = when {
                last == "master.m3u8" -> "master_playlist"
                last.endsWith(".m3u8") -> "media_playlist"
                last.endsWith(".ts") || last.endsWith(".m4s") || last.endsWith(".mp4") -> "media_segment"
                else -> "other"
            }
            val host = failed?.host?.takeIf { it.matches(Regex("[A-Za-z0-9.-]{1,253}")) } ?: "unknown"
            val summary = "AirPlay Video error: ${error.errorCodeName} (${error.errorCode})" +
                (status?.let { "; HTTP $it" } ?: "") +
                "; stage=" + stage + "; host=" + host + "; token=" + tokenTimeState(failed) +
                "; causes=" + causes.map { it.javaClass.simpleName }.distinct().joinToString(" > ")
            Log.w(TAG, summary)
            onError?.invoke(summary)
            onEnded?.invoke()
        }
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) onEnded?.invoke()
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
            onVideoSize?.invoke(width, videoSize.height, width.toFloat() / videoSize.height)
        }
    }

    fun play(location: String, startPositionSeconds: Float) = mainHandler.post {
        // recycling must not report the stopped sentinel: senders poll right after /play
        _stopInternal(reportStopped = false)
        val p = ExoPlayer.Builder(context).setMediaSourceFactory(mediaSourceFactory(location)).build().also {
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
