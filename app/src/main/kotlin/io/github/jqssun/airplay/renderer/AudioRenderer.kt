package io.github.jqssun.airplay.renderer

import io.github.jqssun.airplay.bridge.NativeBridge
import io.github.jqssun.airplay.viewmodel.AudioDebug
import kotlin.math.pow

// wrapper around native audio engine
class AudioRenderer {

    @Volatile var config = AudioConfig(); private set

    @Volatile var volume = 1.0f; private set
    @Volatile var codecLabel = ""; private set

    private var serverHandle = 0L

    @Synchronized
    fun attachEngine(server: Long) {
        serverHandle = server
        pushConfig()
        NativeBridge.nativeServerAudioSetVolume(serverHandle, volume)
    }

    @Synchronized
    fun detachEngine() {
        serverHandle = 0L
        codecLabel = ""
    }

    // open playout (first onAudioFormat, or resume after pause); idempotent natively
    @Synchronized
    fun start() {
        if (serverHandle == 0L) return
        NativeBridge.nativeServerAudioStart(serverHandle)
    }

    // releases oboe stream + audio device while idle; engine stays alive, freed on server destroy
    @Synchronized
    fun stop() {
        if (serverHandle == 0L) return
        NativeBridge.nativeServerAudioStop(serverHandle)
        codecLabel = ""
    }

    @Synchronized
    fun updateConfig(newConfig: AudioConfig) {
        config = newConfig
        pushConfig()
    }

    private fun pushConfig() {
        if (serverHandle == 0L) return
        NativeBridge.nativeServerAudioConfigure(
            serverHandle, config.cushionMs, config.percentilePct, config.oboeBufferFrames,
            config.forceSwAlac, config.realtimePriority, config.lowLatency, config.benchmarkLog)
    }

    private val debugBuf = java.nio.ByteBuffer.allocateDirect(DEBUG_BUF_BYTES)
        .order(java.nio.ByteOrder.LITTLE_ENDIAN)

    // field order/width must mirror native packed AudioDebugData exactly
    @Synchronized
    fun audioDebug(): AudioDebug? {
        if (serverHandle == 0L) return null
        if (!NativeBridge.nativeServerAudioDebug(serverHandle, debugBuf)) return null
        val buf = debugBuf
        buf.rewind()
        val backlogMs = buf.short.toInt() and 0xFFFF
        val tunedCushionMs = buf.short.toInt() and 0xFFFF
        val trims = buf.int
        val drops = buf.int
        val silences = buf.int
        val underruns = buf.int
        val meanUs = buf.int
        val maxUs = buf.int
        val held = buf.short.toInt() and 0xFFFF
        val xrun = buf.int
        val decodeErrors = buf.int
        return AudioDebug(backlogMs, tunedCushionMs, trims, drops, silences, underruns, xrun,
                          meanUs, maxUs, held, decodeErrors)
    }

    @Synchronized
    fun setFormat(ct: Int, spf: Int) {
        codecLabel = when (ct) {
            CT_ALAC -> "ALAC"; CT_AAC_LC -> "AAC-LC"; CT_AAC_ELD -> "AAC-ELD"; else -> "?"
        }
        if (serverHandle != 0L) NativeBridge.nativeServerAudioFormat(serverHandle, ct, spf)
    }

    @Synchronized
    fun setVolume(vol: Float) {
        // dB: max = 0, min = -30, mute = -144
        volume = when {
            vol <= -144f -> 0f
            vol >= 0f -> 1f
            else -> 10f.pow(vol / 20f)
        }
        if (serverHandle == 0L) return
        NativeBridge.nativeServerAudioSetVolume(serverHandle, volume)
    }

    companion object {
        // must be >= native sizeof(AudioDebugData) (38 B)
        private const val DEBUG_BUF_BYTES = 64
        const val CT_ALAC = 2
        const val CT_AAC_LC = 4
        const val CT_AAC_ELD = 8
    }
}
