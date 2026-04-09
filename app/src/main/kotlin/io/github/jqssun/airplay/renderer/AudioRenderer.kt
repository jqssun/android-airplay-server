package io.github.jqssun.airplay.renderer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import io.github.jqssun.airplay.bridge.NativeBridge
import java.nio.ByteBuffer

class AudioRenderer {

    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var currentCt = -1
    private var failedCt = -1
    private var swAlacHandle = 0L  // software ALAC decoder handle
    @Volatile var swAlacEnabled = true
    @Volatile var volume = 1.0f; private set
    @Volatile var codecLabel = ""; private set

    fun feedAudio(data: ByteArray, ct: Int, ntpTimeNs: Long) {
        if (ct != currentCt || (codec == null && swAlacHandle == 0L)) {
            if (ct == failedCt) {
                if (ct == CT_ALAC && swAlacEnabled && swAlacHandle == 0L) {
                    _startSoftwareAlac()
                }
                if (swAlacHandle == 0L) return
            } else {
                stop()
                start(ct)
            }
        }

        // Software ALAC path
        if (swAlacHandle != 0L && ct == CT_ALAC) {
            val pcm = NativeBridge.nativeAlacDecode(swAlacHandle, data) ?: return
            track?.write(pcm, 0, pcm.size)
            return
        }

        // MediaCodec path
        val c = codec ?: return
        try {
            val idx = c.dequeueInputBuffer(5000)
            if (idx >= 0) {
                val buf = c.getInputBuffer(idx) ?: return
                buf.clear()
                buf.put(data)
                c.queueInputBuffer(idx, 0, data.size, ntpTimeNs / 1000, 0)
            }
            drainOutput()
        } catch (_: IllegalStateException) {
            codec = null
            failedCt = ct
            if (ct == CT_ALAC && swAlacEnabled) {
                _startSoftwareAlac()
                if (swAlacHandle != 0L) {
                    val pcm = NativeBridge.nativeAlacDecode(swAlacHandle, data) ?: return
                    track?.write(pcm, 0, pcm.size)
                }
            }
        }
    }

    private fun _startSoftwareAlac() {
        Log.i(TAG, "Starting software ALAC decoder")
        swAlacHandle = NativeBridge.nativeAlacInit(352, 2, 16, 40, 10, 14)
        if (swAlacHandle == 0L) {
            Log.e(TAG, "Failed to init software ALAC decoder")
            return
        }
        currentCt = CT_ALAC
        codecLabel = "ALAC (SW)"
        _ensureAudioTrack()
    }

    private fun start(ct: Int) {
        currentCt = ct
        codecLabel = when (ct) { CT_ALAC -> "ALAC"; CT_AAC_LC -> "AAC-LC"; CT_AAC_ELD -> "AAC-ELD"; else -> "?" }
        val format = when (ct) {
            CT_AAC_ELD -> {
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, 39)
                    setInteger(MediaFormat.KEY_IS_ADTS, 0)
                    setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50, 0x00)))
                }
            }
            CT_AAC_LC -> {
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, 2)
                    setInteger(MediaFormat.KEY_IS_ADTS, 0)
                    setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0x12, 0x10)))
                }
            }
            CT_ALAC -> {
                MediaFormat.createAudioFormat("audio/alac", 44100, 2).apply {
                    setInteger(MediaFormat.KEY_SAMPLE_RATE, 44100)
                    setInteger(MediaFormat.KEY_CHANNEL_COUNT, 2)
                    val csd = byteArrayOf(
                        0x00, 0x00, 0x00, 0x24,
                        0x61, 0x6C, 0x61, 0x63,
                        0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x01, 0x60,
                        0x00, 0x10, 0x28, 0x0A, 0x0E, 0x02,
                        0x00, 0xFF.toByte(),
                        0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0xAC.toByte(), 0x44,
                    )
                    setByteBuffer("csd-0", ByteBuffer.wrap(csd))
                }
            }
            else -> {
                Log.w(TAG, "Unknown audio codec type: $ct")
                return
            }
        }

        try {
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).also {
                it.configure(format, null, null, 0)
                it.start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init audio codec for ct=$ct", e)
            codec = null
            failedCt = ct
            if (ct == CT_ALAC && swAlacEnabled) {
                _startSoftwareAlac()
                return
            }
            return
        }

        _ensureAudioTrack()
        Log.i(TAG, "Audio started: ct=$ct")
    }

    private fun _ensureAudioTrack() {
        if (track != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val fmt = AudioFormat.Builder()
            .setSampleRate(44100)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val bufSize = AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack(attrs, fmt, bufSize * 4, AudioTrack.MODE_STREAM, 0).also {
            it.setVolume(volume)
            it.play()
        }
    }

    private fun drainOutput() {
        val c = codec ?: return
        val t = track ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = c.dequeueOutputBuffer(info, 0)
            if (idx >= 0) {
                val buf = c.getOutputBuffer(idx) ?: break
                val pcm = ByteArray(info.size)
                buf.get(pcm)
                t.write(pcm, 0, pcm.size)
                c.releaseOutputBuffer(idx, false)
            } else {
                break
            }
        }
    }

    fun setVolume(vol: Float) {
        volume = if (vol <= -144f) 0f
                 else if (vol >= 0f) 1f
                 else (vol + 144f) / 144f
        track?.setVolume(volume)
    }

    fun stop() {
        track?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
        track = null
        codec?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
        codec = null
        if (swAlacHandle != 0L) {
            NativeBridge.nativeAlacDestroy(swAlacHandle)
            swAlacHandle = 0L
        }
        currentCt = -1
        failedCt = -1
        codecLabel = ""
    }

    fun release() = stop()

    companion object {
        private const val TAG = "AudioRenderer"
        const val CT_ALAC = 2
        const val CT_AAC_LC = 4
        const val CT_AAC_ELD = 8
    }
}
