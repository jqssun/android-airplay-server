package io.github.jqssun.airplay.renderer

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import io.github.jqssun.airplay.renderer.DecoderSelector.Companion.videoCaps

class VideoRenderer(ctx: Context) {

    private val lock = Object()
    private val pipeline = VideoPipeline()
    val selector = DecoderSelector(ctx)
    private var avcDecoder: MediaCodecInfo? = null
    private var hevcDecoder: MediaCodecInfo? = null
    private var maxFps = 0
    private var codec: MediaCodec? = null
    private var codecSurface: Surface? = null
    private var displaySurface: Surface? = null
    private var currentH265 = false
    private var videoWidth = 0
    private var videoHeight = 0
    private var streamRevision = 0L
    private var decoderStartsAllowed = true
    private var sessionFramesAllowed = true

    // stats
    @Volatile var fps = 0; private set
    @Volatile var bitrateBps = 0L; private set
    @Volatile var frameCount = 0L; private set
    @Volatile var codecName = ""; private set
    @Volatile var droppedFrames = 0L; private set
    @Volatile var framePacingJitterUs = 0L; private set

    var enforceSdr = true
    var directSurface = false
    private var directSurfaceFailed = false
    private fun usesDirectSurface() = directSurface && !directSurfaceFailed
    var keyAllowFrameDrop = true
    var scheduledOutputBufferRelease = true
    var benchmarkLog = false
    var benchmarkLogCallback: ((String) -> Unit)? = null
    private var _framesThisSec = 0
    private var _bytesThisSec = 0L
    private var _lastStatReset = 0L
    private val _frameIntervalsNs = LongArray(120)
    private var _frameIntervalIdx = 0
    private var _frameIntervalCount = 0
    private var _lastOutputFrameNs = 0L
    // anchors that map decoder PTS (us) to System.nanoTime() for scheduled rendering
    private var _ptsBaseUs = Long.MIN_VALUE
    private var _wallBaseNs = 0L

    fun setResolution(w: Int, h: Int) = synchronized(lock) {
        if (w == videoWidth && h == videoHeight) return@synchronized
        videoWidth = w
        videoHeight = h
        streamRevision++
        lock.notifyAll()
        pipeline.setVideoSize(w, h)
        stopCodec()
    }

    // Keep the codec and its reference frames across fullscreen/surface changes.
    fun setSurface(surface: Surface) = synchronized(lock) {
        displaySurface = surface
        if (usesDirectSurface() && !pipeline.detachDisplaySurface()) {
            Log.w(TAG, "EGL detach timed out; retaining the GL rendering path")
            directSurfaceFailed = true
            stopCodec()
        }
        if (usesDirectSurface() && codec != null && codecSurface !== surface) {
            moveCodecToSurface(surface)
        }
        // Never connect EGL and MediaCodec producers to the same SurfaceView.
        pipeline.setDisplaySurface(if (usesDirectSurface()) null else surface)
        lock.notifyAll()
    }

    fun clearSurface(surface: Surface) = synchronized(lock) {
        if (displaySurface !== surface) return@synchronized
        if (usesDirectSurface() && codecSurface === surface && codec != null) {
            // The persistent SurfaceTexture keeps decoding while Android replaces
            // the view. Waiting for a new keyframe would leave many senders black.
            pipeline.start()
            pipeline.setVideoSize(videoWidth, videoHeight)
            pipeline.inputSurface?.let { moveCodecToSurface(it) }
        }
        displaySurface = null
        pipeline.setDisplaySurface(null)
    }

    private fun moveCodecToSurface(surface: Surface) {
        if (!surface.isValid) return
        try {
            codec?.setOutputSurface(surface)
            codecSurface = surface
            Log.i(TAG, "Decoder output surface changed (direct=$directSurface)")
        } catch (e: Exception) {
            Log.w(TAG, "Cannot switch decoder output surface", e)
            if (codecSurface === pipeline.inputSurface) {
                // A failed move from the dummy surface can safely retain the
                // original rendering path without losing reference frames.
                directSurfaceFailed = true
                Log.w(TAG, "Using GL output until the receiver restarts")
            } else {
                // A destroyed vendor Surface cannot be reused. The next stream
                // keyframe can create a decoder against the replacement view.
                stopCodec()
            }
        }
    }

    fun selectDecoders(w: Int, h: Int, fps: Int, h265: Boolean?): Boolean = synchronized(lock) {
        avcDecoder = selector.avc()
        hevcDecoder = if (h265 != false) selector.hevc(avcDecoder, w, h, fps, force = h265 == true) else null
        maxFps = fps
        Log.i(TAG, "decoders: avc=${avcDecoder?.name} hevc=${hevcDecoder?.name}")
        hevcDecoder != null
    }

    // unknown limits default to 1080p
    fun maxResolution(): Pair<Int, Int> =
        listOfNotNull(avcDecoder?.let { it to DecoderSelector.AVC }, hevcDecoder?.let { it to DecoderSelector.HEVC })
            .map { (info, mime) ->
                runCatching { info.videoCaps(mime).let { it.supportedWidths.upper to it.supportedHeights.upper } }
                    .getOrDefault(1920 to 1080)
            }
            .reduceOrNull { (w1, h1), (w2, h2) -> minOf(w1, w2) to minOf(h1, h2) } ?: (1920 to 1080)

    // codec per mirror session; pipeline persists across sessions
    fun prepareForServerStart() = synchronized(lock) {
        decoderStartsAllowed = true
    }

    fun prepareForServerStop() = synchronized(lock) {
        // Native shutdown joins the frame thread, so cancel its wait first and
        // reject any subsequent frames until the server explicitly starts again.
        decoderStartsAllowed = false
        streamRevision++
        lock.notifyAll()
    }

    fun startSession() = synchronized(lock) {
        sessionFramesAllowed = true
        streamRevision++
        lock.notifyAll()
        _resetStats()
    }

    fun prepareForSessionStop() = synchronized(lock) {
        // Called synchronously before the native mirror thread is joined.
        sessionFramesAllowed = false
        streamRevision++
        lock.notifyAll()
    }

    fun stopSession() = synchronized(lock) {
        prepareForSessionStop()
        stopCodec()
    }

    private fun _resetStats() {
        fps = 0; bitrateBps = 0; frameCount = 0; codecName = ""
        droppedFrames = 0; framePacingJitterUs = 0
        _framesThisSec = 0; _bytesThisSec = 0
    }

    private fun _updateStats(size: Int) {
        val now = System.currentTimeMillis()
        if (now - _lastStatReset >= 1000) {
            fps = _framesThisSec
            bitrateBps = _bytesThisSec * 8
            framePacingJitterUs = _computeFramePacingJitterUs()
            _framesThisSec = 0
            _bytesThisSec = 0
            _lastStatReset = now
            if (benchmarkLog) _emitBenchmarkLine()
        }
        _framesThisSec++
        _bytesThisSec += size
        frameCount++
    }

    private fun _emitBenchmarkLine() {
        val msg = "fps=$fps bitrate=${bitrateBps / 1000}kbps " +
            "jitter=${framePacingJitterUs}us frames=$frameCount " +
            "dropped=$droppedFrames codec=$codecName " +
            "res=${videoWidth}x${videoHeight}"
        Log.i(BENCH_TAG, msg)
        benchmarkLogCallback?.invoke(msg)
    }

    fun feedFrame(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        synchronized(lock) {
            if (!decoderStartsAllowed || !sessionFramesAllowed) return
            _updateStats(data.size)
            if (videoWidth == 0 || videoHeight == 0) return

            if (codec == null || isH265 != currentH265) {
                // a stale reference frame decodes to corruption, so wait for a keyframe to (re)start
                if (!_isKeyframe(data, isH265)) {
                    if (codec != null) stopCodec()
                    return
                }
                stopCodec()
            }

            try {
                if (codec == null && !startCodec(isH265)) return@synchronized
                if (_feedToCodec(data, ntpTimeNs)) drainOutput()
            } catch (e: Exception) {
                Log.w(TAG, "Codec error, resetting", e)
                stopCodec()
            }
        }
    }

    private fun _feedToCodec(data: ByteArray, ntpTimeNs: Long): Boolean {
        val c = codec ?: return false
        val revision = streamRevision
        val started = SystemClock.uptimeMillis()
        var reportedStall = false
        val idx = awaitCodecInput(
            lock,
            { c === codec && revision == streamRevision && decoderStartsAllowed && sessionFramesAllowed },
            { c.dequeueInputBuffer(FEED_WAIT_US) },
            {
                drainOutput()
                if (!reportedStall && SystemClock.uptimeMillis() - started >= 200) {
                    reportedStall = true
                    Log.w(TAG, "Decoder input backpressure; retaining compressed frame")
                }
            }
        ) ?: return false
        val buffer = checkNotNull(c.getInputBuffer(idx)) { "Decoder input buffer unavailable" }
        buffer.clear()
        buffer.put(data)
        c.queueInputBuffer(idx, 0, data.size, ntpTimeNs / 1000, 0)
        if (reportedStall) {
            Log.i(TAG, "Decoder input resumed after ${SystemClock.uptimeMillis() - started}ms; frame retained")
        }
        return true
    }

    private fun _isKeyframe(data: ByteArray, isH265: Boolean): Boolean {
        if (data.size < 5) return false
        var i = 0
        while (i <= data.size - 5) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                val key = if (isH265) {
                    val type = (data[i + 4].toInt() shr 1) and 0x3F
                    type == 19 || type == 20 || type == 21 || type == 32 || type == 33
                } else {
                    val type = data[i + 4].toInt() and 0x1F
                    type == 5 || type == 7
                }
                if (key) return true
            }
            i++
        }
        return false
    }

    private fun startCodec(h265: Boolean): Boolean {
        if (usesDirectSurface() && displaySurface?.isValid != true) {
            // The first keyframe can arrive before Compose creates the view.
            // Configuring against SurfaceTexture first can retain buffer usage
            // for the whole decoder lifetime, even after setOutputSurface().
            // Wait on the native frame thread and retain that first keyframe;
            // wait() releases the monitor so the UI can attach the SurfaceView.
            val revision = streamRevision
            val deadline = SystemClock.elapsedRealtime() + 5000
            Log.i(TAG, "Waiting for SurfaceView before direct decoder startup")
            while (displaySurface?.isValid != true && revision == streamRevision) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) break
                lock.wait(remaining)
            }
            // A stopped/replaced stream must not configure an old keyframe.
            if (revision != streamRevision || !decoderStartsAllowed) return false
            if (displaySurface?.isValid != true) {
                Log.w(TAG, "No SurfaceView after 5s; starting with the GL surface")
            }
        }
        if (usesDirectSurface() && !pipeline.detachDisplaySurface()) {
            Log.w(TAG, "EGL detach timed out; retaining the GL rendering path")
            directSurfaceFailed = true
        }
        pipeline.start()
        pipeline.setVideoSize(videoWidth, videoHeight)
        var s = if (usesDirectSurface()) {
            displaySurface?.takeIf { it.isValid } ?: pipeline.inputSurface
        } else pipeline.inputSurface
        if (s == null) return false
        currentH265 = h265
        val mime = if (h265) DecoderSelector.HEVC else DecoderSelector.AVC
        val info = (if (h265) hevcDecoder else avcDecoder) ?: error("no decoder selected for $mime")

        try {
            _startWithLadder(info, mime, s, h265)
        } catch (e: Exception) {
            if (s === displaySurface && usesDirectSurface()) {
                // Some vendor codecs reject the SurfaceView's usage flags.
                // Retry hardware through the original path before software.
                directSurfaceFailed = true
                s = pipeline.inputSurface ?: throw e
                pipeline.setDisplaySurface(displaySurface)
                Log.w(TAG, "Direct output failed; trying the GL rendering path", e)
                try {
                    _startWithLadder(info, mime, s, h265)
                    return true
                } catch (glError: Exception) {
                    Log.w(TAG, "GL hardware decoder also failed", glError)
                }
            }
            // strict hw decoders reject configs beyond their real limits
            val sw = selector.software(mime, videoWidth, videoHeight) ?: throw e
            Log.w(TAG, "Hardware decoder failed, trying software fallback", e)
            _startWithLadder(sw, mime, s, h265)
        }
        Log.i(TAG, "Video codec started: $mime ${videoWidth}x${videoHeight} ($codecName)")
        return true
    }

    private fun _startWithLadder(info: MediaCodecInfo, mime: String, s: Surface, h265: Boolean) {
        var tryNum = 0
        while (true) {
            val format = _format(mime, info)
            val more = selector.lowLatencyOptions(format, info, mime, tryNum)
            try {
                _startDecoder(MediaCodec.createByCodecName(info.name), format, s, h265)
                return
            } catch (e: Exception) {
                if (!more) throw e
                Log.w(TAG, "configure try $tryNum failed: $format", e)
                tryNum++
            }
        }
    }

    private fun _format(mime: String, info: MediaCodecInfo) = MediaFormat.createVideoFormat(mime, videoWidth, videoHeight).apply {
        setInteger(MediaFormat.KEY_FRAME_RATE, maxFps)
        if (selector.adaptive(info, mime)) {
            setInteger(MediaFormat.KEY_MAX_WIDTH, videoWidth)
            setInteger(MediaFormat.KEY_MAX_HEIGHT, videoHeight)
        }
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(videoWidth * videoHeight * 3 / 4, 1024 * 1024))
        if (enforceSdr) {
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, if (keyAllowFrameDrop) 1 else 0)
        }
    }

    private fun _startDecoder(c: MediaCodec, format: MediaFormat, surface: Surface, h265: Boolean) {
        try {
            c.configure(format, surface, null, 0)
            c.start()
        } catch (e: Exception) {
            try { c.release() } catch (_: Exception) {}
            throw e
        }
        codec = c
        codecSurface = surface
        pipeline.setDisplaySurface(if (usesDirectSurface()) null else displaySurface)
        codecName = (if (h265) "H.265" else "H.264") + " (${c.name})"
        Log.i(TAG, "Decoder output: ${if (surface === displaySurface) "direct SurfaceView" else "GL pipeline"}")
    }

    private fun stopCodec() {
        _frameIntervalIdx = 0
        _frameIntervalCount = 0
        _lastOutputFrameNs = 0L
        _ptsBaseUs = Long.MIN_VALUE
        _wallBaseNs = 0L
        codec?.let {
            try {
                it.stop()
                it.release()
            } catch (_: Exception) {}
        }
        codec = null
        codecSurface = null
    }

    private fun drainOutput() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = c.dequeueOutputBuffer(info, 0)
            if (idx < 0) break
            _recordOutputFrameTime()
            if (scheduledOutputBufferRelease) {
                // schedule frame at VSYNC matching its NTP presentation time
                val ptsUs = info.presentationTimeUs
                if (_ptsBaseUs == Long.MIN_VALUE) {
                    _ptsBaseUs = ptsUs
                    _wallBaseNs = System.nanoTime()
                }
                c.releaseOutputBuffer(idx, _wallBaseNs + (ptsUs - _ptsBaseUs) * 1000L)
            } else {
                c.releaseOutputBuffer(idx, true)
            }
        }
    }

    fun release() = synchronized(lock) {
        streamRevision++
        lock.notifyAll()
        stopCodec()
        pipeline.release()
        directSurfaceFailed = false
        _resetStats()
    }

    private fun _recordOutputFrameTime() {
        val now = System.nanoTime()
        if (_lastOutputFrameNs > 0) {
            _frameIntervalsNs[_frameIntervalIdx % _frameIntervalsNs.size] = now - _lastOutputFrameNs
            _frameIntervalIdx++
            _frameIntervalCount++
        }
        _lastOutputFrameNs = now
    }

    private fun _computeFramePacingJitterUs(): Long {
        val count = _frameIntervalCount.coerceAtMost(_frameIntervalsNs.size)
        if (count < 2) return 0

        var sum = 0.0
        var sumSq = 0.0
        for (i in 0 until count) {
            val interval = _frameIntervalsNs[i].toDouble()
            sum += interval
            sumSq += interval * interval
        }
        val mean = sum / count
        val variance = (sumSq / count) - (mean * mean)
        return (kotlin.math.sqrt(variance.coerceAtLeast(0.0)) / 1000.0).toLong()
    }

    companion object {
        private const val TAG = "VideoRenderer"
        private const val BENCH_TAG = "BENCHMARK"
        private const val FEED_WAIT_US = 20_000L
    }
}
