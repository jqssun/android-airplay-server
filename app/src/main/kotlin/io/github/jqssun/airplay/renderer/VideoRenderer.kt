package io.github.jqssun.airplay.renderer

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
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
    private var displaySurface: Surface? = null
    private var displayWidth = 0
    private var displayHeight = 0
    private var codecSurface: Surface? = null
    private var fillLandscape = false
    var preferDirectOutput = false
    @Volatile var directOutputActive = false; private set
    private var currentH265 = false
    private var videoWidth = 0
    private var videoHeight = 0
    private var firstFrameQueued = false
    private val outputPolicy = VideoOutputPolicy()
    private val outputThread = HandlerThread("AirPlayVideoOutput").apply { start() }
    private val outputHandler = Handler(outputThread.looper)
    private val outputDrain = object : Runnable {
        override fun run() = synchronized(lock) {
            if (codec == null) return@synchronized
            try {
                drainOutput()
            } catch (e: Exception) {
                Log.w(TAG, "Output drain failed, resetting", e)
                stopCodec()
            }
            if (codec != null) outputHandler.postDelayed(this, 5L)
        }
    }

    // stats
    @Volatile var fps = 0; private set
    @Volatile var bitrateBps = 0L; private set
    @Volatile var frameCount = 0L; private set
    @Volatile var codecName = ""; private set
    @Volatile var droppedFrames = 0L; private set
    @Volatile var framePacingJitterUs = 0L; private set
    @Volatile var staleOutputFrames = 0L; private set
    @Volatile var outputBacklogMs = 0L; private set
    @Volatile var renderedFrames = 0L; private set

    var enforceSdr = true
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
        pipeline.setVideoSize(w, h)
        stopCodec()
    }

    // doesn't restart codec; decoder renders into pipeline's own persistent surface
    fun setSurface(surface: Surface, width: Int = 0, height: Int = 0) = synchronized(lock) {
        displaySurface = surface
        displayWidth = width
        displayHeight = height
        if (preferDirectOutput && (codec == null || switchCodecSurface(surface))) {
            pipeline.setDisplaySurface(null)
        } else pipeline.setDisplaySurface(surface, width, height)
    }

    fun setFillLandscape(fill: Boolean) = synchronized(lock) {
        fillLandscape = fill
        pipeline.setFillLandscape(fill)
        codec?.let { applyScalingMode(it) }
    }

    fun clearSurface(surface: Surface) = synchronized(lock) {
        if (displaySurface !== surface) return@synchronized
        if (preferDirectOutput && codecSurface === surface) {
            val parkingSurface = pipeline.inputSurface
            if (parkingSurface == null || !switchCodecSurface(parkingSurface)) stopCodec()
        }
        displaySurface = null
        displayWidth = 0
        displayHeight = 0
        pipeline.setDisplaySurface(null)
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
    fun startSession() = synchronized(lock) { _resetStats() }

    fun stopSession() = synchronized(lock) { stopCodec() }

    private fun _resetStats() {
        fps = 0; bitrateBps = 0; frameCount = 0; codecName = ""
        droppedFrames = 0; framePacingJitterUs = 0
        staleOutputFrames = 0; outputBacklogMs = 0
        renderedFrames = 0
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
                if (codec == null) startCodec(isH265)
                _feedToCodec(data, ntpTimeNs)
                drainOutput()
            } catch (e: Exception) {
                Log.w(TAG, "Codec error, resetting", e)
                stopCodec()
            }
        }
    }

    private fun _feedToCodec(data: ByteArray, ntpTimeNs: Long) {
        val c = codec ?: return
        // dropping a frame desyncs decoder until the next keyframe, but source would only send one on (re)connect
        val retries = if (firstFrameQueued) FEED_RETRIES else FIRST_FEED_RETRIES
        repeat(retries) {
            val idx = c.dequeueInputBuffer(FEED_WAIT_US)
            if (idx >= 0) {
                val buf = c.getInputBuffer(idx) ?: return
                buf.clear()
                buf.put(data)
                c.queueInputBuffer(idx, 0, data.size, ntpTimeNs / 1000, 0)
                outputPolicy.queued(ntpTimeNs / 1000)
                firstFrameQueued = true
                return
            }
            drainOutput()
        }
        droppedFrames++
        Log.w(TAG, "Decoder input queue full; dropping frame. drops=$droppedFrames")
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

    private fun startCodec(h265: Boolean) {
        pipeline.start()
        pipeline.setVideoSize(videoWidth, videoHeight)
        val parkingSurface = pipeline.inputSurface ?: return
        val directSurface = displaySurface?.takeIf { preferDirectOutput && it.isValid }
        val s = directSurface ?: parkingSurface
        currentH265 = h265
        val mime = if (h265) DecoderSelector.HEVC else DecoderSelector.AVC
        val info = (if (h265) hevcDecoder else avcDecoder) ?: error("no decoder selected for $mime")

        firstFrameQueued = false
        try {
            try {
                _startWithLadder(info, mime, s, h265)
            } catch (e: Exception) {
                if (directSurface == null) throw e
                Log.w(TAG, "Direct surface rejected, trying the compatible GL path", e)
                pipeline.setDisplaySurface(displaySurface, displayWidth, displayHeight)
                _startWithLadder(info, mime, parkingSurface, h265)
            }
        } catch (e: Exception) {
            // strict hw decoders reject configs beyond their real limits
            val sw = selector.software(mime, videoWidth, videoHeight) ?: throw e
            Log.w(TAG, "Hardware decoder failed, trying software fallback", e)
            pipeline.setDisplaySurface(displaySurface, displayWidth, displayHeight)
            _startWithLadder(sw, mime, parkingSurface, h265)
        }
        Log.i(TAG, "Video codec started: $mime ${videoWidth}x${videoHeight} ($codecName)")
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
        directOutputActive = preferDirectOutput && surface === displaySurface
        applyScalingMode(c)
        Log.i(TAG, "Video output: ${if (directOutputActive) "direct decoder surface" else "GL surface"}")
        codecName = (if (h265) "H.265" else "H.264") + " (${c.name})"
        outputHandler.removeCallbacks(outputDrain)
        outputHandler.post(outputDrain)
    }

    private fun switchCodecSurface(surface: Surface): Boolean {
        val c = codec ?: return true
        if (codecSurface !== surface) {
            try {
                c.setOutputSurface(surface)
                codecSurface = surface
            } catch (e: Exception) {
                Log.w(TAG, "Output surface switch rejected", e)
                return false
            }
        }
        directOutputActive = preferDirectOutput && surface === displaySurface
        applyScalingMode(c)
        Log.i(TAG, "Video output: ${if (directOutputActive) "direct decoder surface" else "GL surface"}")
        return true
    }

    private fun applyScalingMode(c: MediaCodec) {
        val mode = if (directOutputActive && fillLandscape && videoWidth > videoHeight) {
            MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        } else MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT
        try { c.setVideoScalingMode(mode) } catch (e: Exception) { Log.w(TAG, "Video scaling mode rejected", e) }
    }

    private fun stopCodec() {
        outputHandler.removeCallbacks(outputDrain)
        outputPolicy.reset()
        outputBacklogMs = 0
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
        directOutputActive = false
    }

    private fun drainOutput() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        var pendingIndex = -1
        var pendingPtsUs = 0L
        while (true) {
            val idx = c.dequeueOutputBuffer(info, 0)
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                applyScalingMode(c)
                continue
            }
            if (idx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                applyScalingMode(c)
                continue
            }
            if (idx < 0) break
            if (pendingIndex >= 0) {
                if (outputPolicy.shouldRender(pendingPtsUs)) {
                    renderOutput(c, pendingIndex, pendingPtsUs)
                } else {
                    // A newer decoded picture is available, so skip the stale one.
                    // Encoded reference frames are still all fed to the codec.
                    c.releaseOutputBuffer(pendingIndex, false)
                    staleOutputFrames++
                }
            }
            pendingIndex = idx
            pendingPtsUs = info.presentationTimeUs
        }
        // Always show the newest available decoded picture, even on a slow decoder.
        // A hard timestamp cutoff here would otherwise freeze video indefinitely.
        if (pendingIndex >= 0) renderOutput(c, pendingIndex, pendingPtsUs)
    }

    private fun renderOutput(c: MediaCodec, index: Int, ptsUs: Long) {
        outputBacklogMs = outputPolicy.backlogUs(ptsUs) / 1000
        _recordOutputFrameTime()
        if (scheduledOutputBufferRelease) {
            if (_ptsBaseUs == Long.MIN_VALUE) {
                _ptsBaseUs = ptsUs
                _wallBaseNs = System.nanoTime()
            }
            c.releaseOutputBuffer(index, _wallBaseNs + (ptsUs - _ptsBaseUs) * 1000L)
        } else c.releaseOutputBuffer(index, true)
        renderedFrames++
    }

    fun release() = synchronized(lock) {
        stopCodec()
        pipeline.release()
        outputThread.quitSafely()
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
        private const val FEED_RETRIES = 10
        private const val FIRST_FEED_RETRIES = 50
    }
}
