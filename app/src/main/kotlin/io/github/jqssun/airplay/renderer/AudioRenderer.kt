package io.github.jqssun.airplay.renderer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

class AudioRenderer {

    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var currentCt = -1
    @Volatile var volume = 1.0f; private set
    @Volatile var codecLabel = ""; private set

    fun feedAudio(data: ByteArray, ct: Int, ntpTimeNs: Long) {
        if (ct != currentCt || codec == null) {
            stop()
            start(ct)
        }

        val c = codec ?: return
        val idx = c.dequeueInputBuffer(5000)
        if (idx >= 0) {
            val buf = c.getInputBuffer(idx) ?: return
            buf.clear()
            buf.put(data)
            c.queueInputBuffer(idx, 0, data.size, ntpTimeNs / 1000, 0)
        }

        drainOutput()
    }

    private fun start(ct: Int) {
        currentCt = ct
        codecLabel = when (ct) { CT_ALAC -> "ALAC"; CT_AAC_LC -> "AAC-LC"; CT_AAC_ELD -> "AAC-ELD"; else -> "?" }
        val format = when (ct) {
            CT_AAC_ELD -> {
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, 39) // AAC-ELD
                    setInteger(MediaFormat.KEY_IS_ADTS, 0)
                    // AAC-ELD codec specific data
                    setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50, 0x00)))
                }
            }
            CT_AAC_LC -> {
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, 2) // AAC-LC
                    setInteger(MediaFormat.KEY_IS_ADTS, 0)
                    setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0x12, 0x10)))
                }
            }
            CT_ALAC -> {
                MediaFormat.createAudioFormat("audio/alac", 44100, 2).apply {
                    setInteger(MediaFormat.KEY_SAMPLE_RATE, 44100)
                    setInteger(MediaFormat.KEY_CHANNEL_COUNT, 2)
                    // ALAC magic cookie -- 36 bytes matching UxPlay's audio_renderer
                    val cookie = byteArrayOf(
                        0x00, 0x00, 0x00, 0x24, // size
                        0x61, 0x6C, 0x61, 0x63, // 'alac'
                        0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x01, 0x00,
                        0x00, 0x10, 0x28, 0x0A,
                        0x0E, 0x02, 0x00, 0xFF.toByte(),
                        0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0xAC.toByte(), 0x44,
                        0x00, 0x00, 0x00, 0x00
                    )
                    setByteBuffer("csd-0", ByteBuffer.wrap(cookie))
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
            return
        }

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

        Log.i(TAG, "Audio started: ct=$ct")
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
        // AirPlay volume: -144 (mute) to 0 (max), convert to 0..1
        volume = if (vol <= -144f) 0f
                 else if (vol >= 0f) 1f
                 else (vol + 144f) / 144f
        track?.setVolume(volume)
    }

    fun stop() {
        track?.let {
            try { it.stop(); it.release() } catch (_: Exception) {}
        }
        track = null
        codec?.let {
            try { it.stop(); it.release() } catch (_: Exception) {}
        }
        codec = null
        currentCt = -1
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
