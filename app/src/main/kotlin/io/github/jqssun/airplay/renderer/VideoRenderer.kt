package io.github.jqssun.airplay.renderer

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Process
import android.util.Log
import android.view.Surface
import io.github.jqssun.airplay.renderer.DecoderSelector.Companion.videoCaps
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue

class VideoRenderer(ctx: Context) {

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

    /* The frames are decoded on their own thread. The thread that receives
       them must not wait for the decoder: a 4k frame takes 3-8 ms to decode and
       during that time the socket is not drained, so the tcp window closes and
       the sender stalls (a freeze of the whole picture). */
    private val _pending = ArrayBlockingQueue<PendingFrame>(PENDING_CAPACITY)
    private val _decodeLock = Object()
    private var _decodeThread: Thread? = null
    @Volatile private var _decodeRunning = false
    /* tells the app that the frame buffer can be filled again */
    var frameRelease: ((Int) -> Unit)? = null
    /* frames that were dropped because the decoder could not keep up */
    @Volatile var droppedByQueue = 0L; private set

    private class PendingFrame(
        val index: Int,
        val data: ByteBuffer?,
        val bytes: ByteArray?,
        val size: Int,
        val ntpTimeNs: Long,
        val isH265: Boolean,
    )

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

    fun setResolution(w: Int, h: Int) = synchronized(lock) {
        if (w == videoWidth && h == videoHeight) return@synchronized
        videoWidth = w
        videoHeight = h
        pipeline.setVideoSize(w, h)
        stopCodec()
    }

    // keep the picture's aspect ratio instead of stretching it to the panel
    fun setFitAspect(fit: Boolean) {
        pipeline.fitAspect = fit
    }

    // doesn't restart codec; decoder renders into pipeline's own persistent surface
    fun setSurface(surface: Surface) = synchronized(lock) {
        displaySurface = surface
        pipeline.setDisplaySurface(surface)
    }

    fun clearSurface(surface: Surface) = synchronized(lock) {
        if (displaySurface !== surface) return@synchronized
        displaySurface = null
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
    // the sender picks the codec from the advertised features: with h265
    // advertised (dns-sd feature bit 42) macos prefers hevc for resolutions
    // beyond h264 limits, so the hevc caps bound the advertised size.
    // taking the smaller of both caps would advertise 1080p on tv sets whose
    // h264 decoder is limited while the hevc one handles 4k, and the extended
    // desktop would then be upscaled by the sender
    fun maxResolution(): Pair<Int, Int> {
        val decoder = hevcDecoder?.let { it to DecoderSelector.HEVC }
            ?: avcDecoder?.let { it to DecoderSelector.AVC }
            ?: return 1920 to 1080
        return runCatching {
            decoder.first.videoCaps(decoder.second).let { it.supportedWidths.upper to it.supportedHeights.upper }
        }.getOrDefault(1920 to 1080)
    }

    // codec per mirror session; pipeline persists across sessions
    fun startSession() = synchronized(lock) { _resetStats() }

    fun stopSession() {
        // the queued frames belong to a session that is gone
        _clearPending()
        synchronized(lock) { stopCodec() }
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
            "dropped=$droppedFrames queuedropped=$droppedByQueue codec=$codecName " +
            "res=${videoWidth}x${videoHeight}"
        Log.i(BENCH_TAG, msg)
        benchmarkLogCallback?.invoke(msg)
    }

    fun feedFrame(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        feedFrameBuffer(ByteBuffer.wrap(data), data.size, ntpTimeNs, isH265)
    }

    /* Hands a frame over to the decoder thread. The caller (the thread that
       receives the frames) returns immediately, it does not wait for the
       decoder. */
    fun enqueueFrame(index: Int, data: ByteBuffer, size: Int, ntpTimeNs: Long, isH265: Boolean) {
        _enqueue(PendingFrame(index, data, null, size, ntpTimeNs, isH265))
    }

    fun enqueueFrame(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        _enqueue(PendingFrame(-1, null, data, data.size, ntpTimeNs, isH265))
    }

    private fun _enqueue(frame: PendingFrame) {
        _startDecoder()
        if (_pending.offer(frame)) return
        /* the decoder cannot keep up: the oldest frame goes, so the delay
           stays small instead of growing without end */
        val dropped = _pending.poll()
        if (dropped != null) {
            droppedByQueue++
            _releaseFrame(dropped)
        }
        if (!_pending.offer(frame)) {
            droppedByQueue++
            _releaseFrame(frame)
        }
    }

    private fun _releaseFrame(frame: PendingFrame) {
        if (frame.index < 0) return
        frameRelease?.invoke(frame.index)
    }

    private fun _startDecoder() {
        synchronized(_decodeLock) {
            if (_decodeThread != null) return
            _decodeRunning = true
            _decodeThread = Thread({
                // the decoder is on the critical path of the picture
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
                while (_decodeRunning) {
                    val frame = try {
                        _pending.take()
                    } catch (e: InterruptedException) {
                        break
                    }
                    try {
                        if (frame.data != null) {
                            feedFrameBuffer(frame.data, frame.size, frame.ntpTimeNs, frame.isH265)
                        } else if (frame.bytes != null) {
                            feedFrame(frame.bytes, frame.ntpTimeNs, frame.isH265)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "decode failed", e)
                    } finally {
                        _releaseFrame(frame)
                    }
                }
            }, "VideoDecode").also { it.start() }
        }
    }

    /* stops the decoder thread and gives the queued frames back */
    private fun _stopDecoder() {
        val thread = synchronized(_decodeLock) {
            _decodeRunning = false
            _decodeThread.also { _decodeThread = null }
        }
        thread?.interrupt()
        thread?.join(500)
        while (true) {
            val frame = _pending.poll() ?: break
            _releaseFrame(frame)
        }
    }

    // the frame data is read-only for us; the buffer is owned by the caller
    fun feedFrameBuffer(data: ByteBuffer, size: Int, ntpTimeNs: Long, isH265: Boolean) {
        synchronized(lock) {
            _updateStats(size)
            if (videoWidth == 0 || videoHeight == 0) return

            if (codec == null || isH265 != currentH265) {
                // a stale reference frame decodes to corruption, so wait for a keyframe to (re)start
                if (!_isKeyframe(data, size, isH265)) {
                    if (codec != null) stopCodec()
                    return
                }
                stopCodec()
            }

            try {
                if (codec == null) startCodec(isH265)
                _feedToCodec(data, size, ntpTimeNs)
                drainOutput()
            } catch (e: Exception) {
                Log.w(TAG, "Codec error, resetting", e)
                stopCodec()
            }
        }
    }

    private fun _feedToCodec(data: ByteBuffer, size: Int, ntpTimeNs: Long) {
        val c = codec ?: return
        // dropping a frame desyncs decoder until the next keyframe, but source would only send one on (re)connect
        val retries = if (firstFrameQueued) FEED_RETRIES else FIRST_FEED_RETRIES
        repeat(retries) {
            val idx = c.dequeueInputBuffer(FEED_WAIT_US)
            if (idx >= 0) {
                val buf = c.getInputBuffer(idx) ?: return
                buf.clear()
                val pos = data.position()
                val lim = data.limit()
                data.position(0)
                data.limit(size)
                buf.put(data)
                data.position(pos)
                data.limit(lim)
                c.queueInputBuffer(idx, 0, size, ntpTimeNs / 1000, 0)
                firstFrameQueued = true
                return
            }
            drainOutput()
        }
        droppedFrames++
        Log.w(TAG, "Decoder input queue full; dropping frame. drops=$droppedFrames")
    }

    private fun _isKeyframe(data: ByteBuffer, size: Int, isH265: Boolean): Boolean {
        if (size < 5) return false
        var i = 0
        while (i <= size - 5) {
            if (data.get(i) == 0.toByte() && data.get(i + 1) == 0.toByte() &&
                data.get(i + 2) == 0.toByte() && data.get(i + 3) == 1.toByte()) {
                val key = if (isH265) {
                    val type = (data.get(i + 4).toInt() shr 1) and 0x3F
                    type == 19 || type == 20 || type == 21 || type == 32 || type == 33
                } else {
                    val type = data.get(i + 4).toInt() and 0x1F
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
        val s = pipeline.inputSurface ?: return
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

    // a new session starts with its own geometry and its own keyframes
    fun resetSession() {
        // the queued frames belong to the session that is gone
        _clearPending()
        synchronized(lock) {
            stopCodec()
        }
    }

    fun release() {
        _stopDecoder()
        synchronized(lock) {
            stopCodec()
            pipeline.release()
            _resetStats()
        }
    }

    /* drops the queued frames and gives their buffers back */
    private fun _clearPending() {
        while (true) {
            val frame = _pending.poll() ?: break
            _releaseFrame(frame)
        }
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
        // frames that wait for the decoder; the app has as many frame buffers
        private const val PENDING_CAPACITY = 4
    }
}
