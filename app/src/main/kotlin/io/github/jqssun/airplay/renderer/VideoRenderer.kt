package io.github.jqssun.airplay.renderer

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import android.view.Surface

class VideoRenderer {

    private val lock = Object()
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var currentH265 = false
    @Volatile private var running = false
    private var videoWidth = 0
    private var videoHeight = 0

    // Cache last keyframe so decoder can bootstrap after late surface attach
    private var cachedKeyframe: ByteArray? = null
    private var cachedKeyframePts: Long = 0
    private var cachedKeyframeH265 = false

    // Stats
    @Volatile var fps = 0; private set
    @Volatile var bitrateBps = 0L; private set
    @Volatile var frameCount = 0L; private set
    @Volatile var codecName = ""; private set
    private var _framesThisSec = 0
    private var _bytesThisSec = 0L
    private var _lastStatReset = 0L

    fun setResolution(w: Int, h: Int) {
        videoWidth = w
        videoHeight = h
    }

    fun setSurface(surface: Surface?) = synchronized(lock) {
        val changed = this.surface !== surface
        this.surface = surface
        if (changed && codec != null) stopCodec()
    }

    private fun _updateStats(size: Int) {
        val now = System.currentTimeMillis()
        if (now - _lastStatReset >= 1000) {
            fps = _framesThisSec
            bitrateBps = _bytesThisSec * 8
            _framesThisSec = 0
            _bytesThisSec = 0
            _lastStatReset = now
        }
        _framesThisSec++
        _bytesThisSec += size
        frameCount++
    }

    fun feedFrame(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        _updateStats(data.size)

        // Always cache keyframes, even without a surface
        if (_isKeyframe(data, isH265)) {
            cachedKeyframe = data.copyOf()
            cachedKeyframePts = ntpTimeNs
            cachedKeyframeH265 = isH265
        }

        synchronized(lock) {
            if (surface == null) return

            // Codec switch needed?
            if (codec == null || isH265 != currentH265) {
                stopCodec()
                startCodec(isH265)
                // Feed cached keyframe to bootstrap decoder
                cachedKeyframe?.let { kf ->
                    if (cachedKeyframeH265 == isH265) {
                        _feedToCodec(kf, cachedKeyframePts)
                    }
                }
            }

            _feedToCodec(data, ntpTimeNs)
            drainOutput()
        }
    }

    private fun _feedToCodec(data: ByteArray, ntpTimeNs: Long) {
        val c = codec ?: return
        val idx = c.dequeueInputBuffer(5000)
        if (idx >= 0) {
            val buf = c.getInputBuffer(idx) ?: return
            buf.clear()
            buf.put(data)
            c.queueInputBuffer(idx, 0, data.size, ntpTimeNs / 1000, 0)
        }
    }

    private fun _isKeyframe(data: ByteArray, isH265: Boolean): Boolean {
        if (data.size < 5) return false
        var i = 0
        while (i <= data.size - 5) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                return if (isH265) {
                    val type = (data[i + 4].toInt() shr 1) and 0x3F
                    type == 19 || type == 20 || type == 32 || type == 33
                } else {
                    val type = data[i + 4].toInt() and 0x1F
                    type == 5 || type == 7
                }
            }
            i++
        }
        return false
    }

    private fun startCodec(h265: Boolean) {
        val s = surface ?: return
        currentH265 = h265
        val mime = if (h265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

        val format = MediaFormat.createVideoFormat(mime, videoWidth, videoHeight)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)

        codec = MediaCodec.createDecoderByType(mime).also {
            it.configure(format, s, null, 0)
            it.start()
        }
        codecName = if (h265) "H.265" else "H.264"
        running = true
        Log.i(TAG, "Video codec started: $mime")
    }

    private fun stopCodec() {
        running = false
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
            if (idx >= 0) {
                c.releaseOutputBuffer(idx, true) // render to surface
            } else {
                break
            }
        }
    }

    fun release() = synchronized(lock) {
        stopCodec()
        cachedKeyframe = null
        fps = 0; bitrateBps = 0; frameCount = 0; codecName = ""
        _framesThisSec = 0; _bytesThisSec = 0
    }

    companion object {
        private const val TAG = "VideoRenderer"

        fun supportsH265(): Boolean {
            val list = MediaCodecList(MediaCodecList.ALL_CODECS)
            return list.codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any {
                    it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
                }
            }
        }
    }
}
