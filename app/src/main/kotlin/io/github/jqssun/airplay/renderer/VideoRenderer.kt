package io.github.jqssun.airplay.renderer

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import io.github.jqssun.airplay.BuildConfig
import android.util.Log
import android.view.Surface
import io.github.jqssun.airplay.renderer.DecoderSelector.Companion.videoCaps

class VideoRenderer(ctx: Context, private val audioRenderTime: (Long) -> Long = { 0L }) {

    private val lock = Object()
    private val pipeline = VideoPipeline()
    val selector = DecoderSelector(ctx)
    private var avcDecoder: MediaCodecInfo? = null
    private var hevcDecoder: MediaCodecInfo? = null
    private var maxFps = 0
    private var codec: MediaCodec? = null
    private var displaySurface: Surface? = null
    private var currentH265 = false
    private var videoWidth = 0
    private var videoHeight = 0
    private var firstFrameQueued = false
    // Independent H10 experiment: decoder -> SurfaceView, avoiding the latest-image GL blit.
    private var decoderSurface: Surface? = null
    @Volatile private var directOutput = false
    private val renderThread = if (BuildConfig.H10_DIRECT_SURFACE)
        HandlerThread("H10FrameRendered").apply { start() } else null
    private val renderHandler = renderThread?.let { Handler(it.looper) }
    @Volatile private var renderGeneration = 0L
    private var renderedCount = 0L
    private var renderWindowStartNs = 0L
    private var renderWindowFrames = 0L
    private var lastRenderedPtsUs = Long.MIN_VALUE
    private var outputDrainRunning = false
    private var outputDrainThread: Thread? = null
    private var lastDirectTargetNs = 0L
    private var lastDirectPtsUs = Long.MIN_VALUE
    // AirPlayService constructs this renderer before Android attaches its Context.
    // Keep the experimental lead independent of preferences during construction.
    private val directLeadNs = if (BuildConfig.H10_DIRECT_SURFACE) 25_000_000L else 0L
    private var directDecodedFrames = 0L
    private var directLastReportNs = 0L
    private var directLastReportFrames = 0L

    // stats
    @Volatile var fps = 0; private set
    @Volatile var bitrateBps = 0L; private set
    @Volatile var frameCount = 0L; private set
    @Volatile var codecName = ""; private set
    @Volatile var droppedFrames = 0L; private set
    @Volatile var framePacingJitterUs = 0L; private set

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
    private var _lastOutputPtsUs = Long.MIN_VALUE
    private var _firstInputAtNs = 0L
    private var _audioClockFrames = 0L
    private var _lateOutputFrames = 0L
    private var _audioWaitUs = 0L

    fun setResolution(w: Int, h: Int) {
        videoWidth = w
        videoHeight = h
        pipeline.setVideoSize(w, h)
    }

    // doesn't restart codec; decoder renders into pipeline's own persistent surface
    fun setSurface(surface: Surface) = synchronized(lock) {
        displaySurface = surface
        if (BuildConfig.H10_DIRECT_SURFACE) {
            if (codec != null && decoderSurface !== surface && surface.isValid) {
                try {
                    codec!!.setOutputSurface(surface)
                    decoderSurface = surface
                    directOutput = true
                    pipeline.setDisplaySurface(null)
                    Log.i(TAG, "H10_DISPLAY switched decoder directly to SurfaceView")
                } catch (e: Exception) {
                    // Keep the persistent texture fallback alive if the vendor rejects a switch.
                    Log.w(TAG, "H10_DISPLAY surface switch rejected", e)
                    if (!directOutput) pipeline.setDisplaySurface(surface)
                    else {
                        stopCodec()
                        Log.w(TAG, "H10_DISPLAY waiting for next keyframe after surface replacement")
                    }
                }
            }
        } else pipeline.setDisplaySurface(surface)
    }

    fun clearSurface(surface: Surface) = synchronized(lock) {
        if (displaySurface !== surface) return@synchronized
        displaySurface = null
        if (BuildConfig.H10_DIRECT_SURFACE && codec != null && directOutput) {
            pipeline.start()
            val parking = pipeline.inputSurface
            try {
                if (parking != null) {
                    codec!!.setOutputSurface(parking)
                    decoderSurface = parking
                    directOutput = false
                    Log.i(TAG, "H10_DISPLAY decoder parked during surface replacement")
                }
            } catch (e: Exception) {
                Log.w(TAG, "H10_DISPLAY parking failed; waiting for next keyframe", e)
                stopCodec()
            }
        }
        pipeline.setDisplaySurface(null)
    }

    fun selectDecoders(w: Int, h: Int, fps: Int, h265: Boolean): Boolean = synchronized(lock) {
        avcDecoder = selector.avc()
        hevcDecoder = if (h265) selector.hevc(avcDecoder, w, h, fps) else null
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

    fun resetTiming(reason: String) = synchronized(lock) {
        _ptsBaseUs = Long.MIN_VALUE
        _wallBaseNs = 0L
        _lastOutputPtsUs = Long.MIN_VALUE
        Log.i(TAG, "MVP_SYNC video timing reset: $reason")
    }

    fun flush() = synchronized(lock) {
        // A protocol video FLUSH requires a fresh reference/key frame.
        stopCodec()
        Log.i(TAG, "MVP_SYNC video decoder flushed; waiting for keyframe")
    }

    private fun _resetStats() {
        fps = 0; bitrateBps = 0; frameCount = 0; codecName = ""
        droppedFrames = 0; framePacingJitterUs = 0
        _framesThisSec = 0; _bytesThisSec = 0
        _audioClockFrames = 0; _lateOutputFrames = 0; _audioWaitUs = 0
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
            "res=${videoWidth}x${videoHeight} " +
            "audioClockFrames=$_audioClockFrames audioWaitUs=$_audioWaitUs lateOutputFrames=$_lateOutputFrames"
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
                if (!firstFrameQueued) _firstInputAtNs = System.nanoTime()
                c.queueInputBuffer(idx, 0, data.size, ntpTimeNs / 1000, 0)
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
        val direct = if (BuildConfig.H10_DIRECT_SURFACE) displaySurface?.takeIf { it.isValid } else null
        val s = direct ?: run {
            pipeline.start()
            pipeline.setVideoSize(videoWidth, videoHeight)
            pipeline.setDisplaySurface(displaySurface)
            pipeline.inputSurface ?: return
        }
        directOutput = direct != null
        decoderSurface = s
        currentH265 = h265
        val mime = if (h265) DecoderSelector.HEVC else DecoderSelector.AVC
        val info = (if (h265) hevcDecoder else avcDecoder) ?: error("no decoder selected for $mime")

        firstFrameQueued = false
        try {
            _startWithLadder(info, mime, s, h265)
        } catch (e: Exception) {
            // strict hw decoders reject configs beyond their real limits
            val sw = selector.software(mime, videoWidth, videoHeight) ?: throw e
            Log.w(TAG, "Hardware decoder failed, trying software fallback", e)
            _startWithLadder(sw, mime, s, h265)
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
        codecName = (if (h265) "H.265" else "H.264") + " (${c.name})"
        if (BuildConfig.H10_DIRECT_SURFACE) {
            if (outputDrainThread == null) {
                outputDrainRunning = true
                outputDrainThread = Thread({
                    while (true) {
                        synchronized(lock) {
                            if (!outputDrainRunning) return@Thread
                            try { drainOutput() } catch (e: Exception) {
                                Log.w(TAG, "H10_DISPLAY output drain failed", e)
                                stopCodec()
                            }
                        }
                        Thread.sleep(2)
                    }
                }, "H10OutputDrain").apply { start() }
            }
            val generation = ++renderGeneration
            renderedCount = 0L
            renderWindowStartNs = 0L
            renderWindowFrames = 0L
            lastRenderedPtsUs = Long.MIN_VALUE
            c.setOnFrameRenderedListener({ _, ptsUs, renderNs ->
                // Use codec's render timestamps, not delayed/batched callback delivery time.
                if (generation == renderGeneration && ptsUs != Long.MAX_VALUE && ptsUs != lastRenderedPtsUs) {
                    lastRenderedPtsUs = ptsUs
                    renderedCount++
                    if (renderWindowStartNs == 0L) renderWindowStartNs = renderNs
                    else renderWindowFrames++
                    val elapsed = renderNs - renderWindowStartNs
                    if (elapsed >= 2_000_000_000L) {
                        val rate = renderWindowFrames * 1e9 / elapsed
                        Log.i(TAG, "H10_DISPLAY rendered=$renderedCount fps=$rate direct=$directOutput ptsUs=$ptsUs renderNs=$renderNs")
                        renderWindowStartNs = renderNs
                        renderWindowFrames = 0L
                    }
                }
            }, renderHandler)
            Log.i(TAG, "H10_DISPLAY output=${if (directOutput) "direct SurfaceView" else "GL fallback"} leadMs=${directLeadNs / 1_000_000}")
        }
    }

    private fun stopCodec() {
        renderGeneration++
        lastDirectTargetNs = 0L
        lastDirectPtsUs = Long.MIN_VALUE
        directDecodedFrames = 0L
        directLastReportNs = 0L
        directLastReportFrames = 0L
        _frameIntervalIdx = 0
        _frameIntervalCount = 0
        _lastOutputFrameNs = 0L
        _ptsBaseUs = Long.MIN_VALUE
        _wallBaseNs = 0L
        _lastOutputPtsUs = Long.MIN_VALUE
        _firstInputAtNs = 0L
        codec?.let {
            try {
                it.stop()
                it.release()
            } catch (_: Exception) {}
        }
        codec = null
        decoderSurface = null
        directOutput = false
    }

    private fun drainOutput() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = c.dequeueOutputBuffer(info, 0)
            if (idx < 0) break
            _recordOutputFrameTime()
            if (scheduledOutputBufferRelease) {
                // Audio and video share UxPlay's local NTP PTS (Unix clock), which
                // must be mapped into System.nanoTime(), never used directly.
                val ptsUs = info.presentationTimeUs
                val now = System.nanoTime()
                val firstOutput = _ptsBaseUs == Long.MIN_VALUE
                val jump = _lastOutputPtsUs != Long.MIN_VALUE &&
                    (ptsUs < _lastOutputPtsUs - 750_000L || ptsUs > _lastOutputPtsUs + 750_000L)
                if (firstOutput || jump) {
                    _ptsBaseUs = ptsUs
                    _wallBaseNs = now
                    if (_firstInputAtNs > 0L) {
                        Log.i(TAG, "MVP_SYNC video firstOutput startupMs=${(now - _firstInputAtNs) / 1_000_000}")
                        _firstInputAtNs = 0L
                    }
                }
                _lastOutputPtsUs = ptsUs
                val audioTime = audioRenderTime(ptsUs * 1000L)
                val target = if (audioTime > 0L) {
                    _audioClockFrames++
                    _audioWaitUs = (audioTime - now) / 1000L
                    // OpenSL ES + HDMI has only an estimated audio clock. Count
                    // late output but display it immediately: discarding all frames
                    // on a stable hardware offset would erase short flashes/video.
                    if (now - audioTime > 80_000_000L) {
                        _lateOutputFrames++
                    }
                    // Keep the silent-video fallback anchored to the last audio
                    // estimate, rather than jumping back to the original first frame.
                    _ptsBaseUs = ptsUs
                    _wallBaseNs = audioTime
                    audioTime
                } else {
                    (_wallBaseNs + (ptsUs - _ptsBaseUs) * 1000L).coerceAtMost(now + 250_000_000L)
                }
                var renderTarget = target.coerceAtLeast(now)
                if (BuildConfig.H10_DIRECT_SURFACE && directOutput && target <= now) {
                    // Preserve source cadence when audio's estimated timestamp is already
                    // behind decode completion. A small, bounded lead lets burst arrivals
                    // span separate display refreshes instead of overwriting each other.
                    val deltaUs = ptsUs - lastDirectPtsUs
                    renderTarget = if (lastDirectTargetNs > 0 && deltaUs in 1..100_000)
                        (lastDirectTargetNs + deltaUs * 1000).coerceIn(now + 8_000_000L, now + 50_000_000L)
                    else now + directLeadNs
                }
                lastDirectTargetNs = renderTarget
                lastDirectPtsUs = ptsUs
                c.releaseOutputBuffer(idx, renderTarget)
                if (BuildConfig.H10_DIRECT_SURFACE) {
                    directDecodedFrames++
                    if (directLastReportNs == 0L) directLastReportNs = now
                    val elapsed = now - directLastReportNs
                    if (elapsed >= 2_000_000_000L) {
                        val rate = (directDecodedFrames - directLastReportFrames) * 1e9 / elapsed
                        Log.i(TAG, "H10_OUTPUT released=$directDecodedFrames fps=$rate holdUs=${(renderTarget-now)/1000} direct=$directOutput")
                        directLastReportNs = now
                        directLastReportFrames = directDecodedFrames
                    }
                }
            } else {
                c.releaseOutputBuffer(idx, System.nanoTime())
            }
        }
    }

    fun release() = synchronized(lock) {
        stopCodec()
        pipeline.release()
        outputDrainRunning = false
        renderThread?.quitSafely()
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
