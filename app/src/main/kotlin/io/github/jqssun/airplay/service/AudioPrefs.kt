package io.github.jqssun.airplay.service

import android.content.SharedPreferences
import io.github.jqssun.airplay.Prefs
import io.github.jqssun.airplay.get
import io.github.jqssun.airplay.observe
import io.github.jqssun.airplay.renderer.AudioConfig
import kotlinx.coroutines.flow.Flow

fun readAudioConfig(p: SharedPreferences) = AudioConfig(
    cushionMs = if (p[Prefs.AUDIO_AUTO_BUFFER]) 0 else p[Prefs.AUDIO_CUSHION_MS],
    percentilePct = Prefs.ADAPTIVE_PERCENTILES[p[Prefs.AUDIO_ADAPTIVE_STEP]],
    oboeBufferFrames = p[Prefs.OBOE_BUFFER_FRAMES],
    forceSwAlac = p[Prefs.FORCE_SW_ALAC],
    forceSwAac = p[Prefs.FORCE_SW_AAC],
    realtimePriority = p[Prefs.KEY_PRIORITY],
    lowLatency = p[Prefs.LOW_LATENCY],
    benchmarkLog = p[Prefs.BENCHMARK_LOG],
)

fun SharedPreferences.audioConfigFlow(): Flow<AudioConfig> = observe(::readAudioConfig)
