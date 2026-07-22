package io.github.jqssun.airplay.service

import android.content.SharedPreferences
import io.github.jqssun.airplay.Prefs
import io.github.jqssun.airplay.renderer.AudioConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

fun readAudioConfig(p: SharedPreferences) = AudioConfig(
    cushionMs = if (p.getBoolean(Prefs.AUDIO_AUTO_BUFFER, Prefs.DEF_AUDIO_AUTO_BUFFER)) 0
                else p.getInt(Prefs.AUDIO_CUSHION_MS, Prefs.DEF_AUDIO_CUSHION_MS).coerceIn(1, 1000),
    percentilePct = Prefs.ADAPTIVE_PERCENTILES[
        p.getInt(Prefs.AUDIO_ADAPTIVE_STEP, Prefs.DEF_AUDIO_ADAPTIVE_STEP)
            .coerceIn(0, Prefs.ADAPTIVE_PERCENTILES.size - 1)],
    oboeBufferFrames = p.getInt(Prefs.OBOE_BUFFER_FRAMES, Prefs.DEF_OBOE_BUFFER_FRAMES).coerceIn(0, 8192),
    forceSwAlac = p.getBoolean(Prefs.FORCE_SW_ALAC, Prefs.DEF_FORCE_SW_ALAC),
    realtimePriority = p.getBoolean(Prefs.KEY_PRIORITY, Prefs.DEF_KEY_PRIORITY),
    lowLatency = p.getBoolean(Prefs.LOW_LATENCY, Prefs.DEF_LOW_LATENCY),
    benchmarkLog = p.getBoolean(Prefs.BENCHMARK_LOG, Prefs.DEF_BENCHMARK_LOG),
)

fun SharedPreferences.audioConfigFlow(): Flow<AudioConfig> = callbackFlow {
    val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        trySend(readAudioConfig(this@audioConfigFlow))
    }
    trySend(readAudioConfig(this@audioConfigFlow))
    registerOnSharedPreferenceChangeListener(listener)
    awaitClose { unregisterOnSharedPreferenceChangeListener(listener) }
}.distinctUntilChanged()
