package io.github.jqssun.airplay

import android.content.Context
import android.media.MediaFormat
import android.provider.Settings

/** Centralized preference keys and defaults. */
object Prefs {
    const val NAME = "settings"

    const val SERVER_NAME = "server_name"; const val DEF_SERVER_NAME = "Android AirPlay"

    /**
     * Default advertised name when the user hasn't set one: the device name from
     * Android Settings > About ("device_name" in Settings.Global -- the constant is
     * API 25+, the row itself exists on API 24), falling back to [DEF_SERVER_NAME].
     */
    fun defaultServerName(context: Context): String =
        runCatching { Settings.Global.getString(context.contentResolver, "device_name") }
            .getOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: DEF_SERVER_NAME

    /** The configured server name, or [defaultServerName] when unset/blank. */
    fun serverName(context: Context): String {
        val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return prefs.getString(SERVER_NAME, null)?.takeIf { it.isNotBlank() }
            ?: defaultServerName(context)
    }

    const val SERVER_PORT = "server_port"; const val DEF_SERVER_PORT = 7000
    const val AUTO_START = "auto_start"; const val DEF_AUTO_START = true
    const val BOOT_AUTO_START = "boot_auto_start"; const val DEF_BOOT_AUTO_START = true
    const val RUN_IN_BACKGROUND = "run_in_background"; const val DEF_RUN_IN_BACKGROUND = true
    const val H265_ENABLED = "h265_enabled"; const val DEF_H265_ENABLED = true
    const val ENFORCE_SDR = "enforce_sdr"; const val DEF_ENFORCE_SDR = true
    val KEY_ALLOW_FRAME_DROP: String = MediaFormat.KEY_ALLOW_FRAME_DROP; const val DEF_KEY_ALLOW_FRAME_DROP = true
    val KEY_PRIORITY: String = MediaFormat.KEY_PRIORITY; const val DEF_KEY_PRIORITY = true
    val KEY_OPERATING_RATE: String = MediaFormat.KEY_OPERATING_RATE; const val DEF_KEY_OPERATING_RATE = true
    const val SCHEDULED_OUTPUT_BUFFER_RELEASE = "scheduled_output_buffer_release"; const val DEF_SCHEDULED_OUTPUT_BUFFER_RELEASE = false
    const val AUDIO_BUFFER_MULTIPLIER = "audio_buffer_multiplier"; const val DEF_AUDIO_BUFFER_MULTIPLIER = 4
    const val ALAC_ENABLED = "alac_enabled"; const val DEF_ALAC_ENABLED = false
    const val SW_ALAC_ENABLED = "sw_alac_enabled"; const val DEF_SW_ALAC_ENABLED = true
    const val AAC_ENABLED = "aac_enabled"; const val DEF_AAC_ENABLED = true
    const val RESOLUTION = "resolution"; const val DEF_RESOLUTION = "auto"
    const val MAX_FPS = "max_fps"; const val DEF_MAX_FPS = 60
    const val OVERSCANNED = "overscanned"; const val DEF_OVERSCANNED = false
    const val REQUIRE_PIN = "require_pin"; const val DEF_REQUIRE_PIN = false
    const val ALLOW_NEW_CONN = "allow_new_conn"; const val DEF_ALLOW_NEW_CONN = true
    const val AUDIO_LATENCY_MS = "audio_latency_ms"; const val DEF_AUDIO_LATENCY_MS = -1
    const val DEBUG_ENABLED = "debug_enabled"; const val DEF_DEBUG_ENABLED = false
    const val DEVELOPER_OPTIONS = "developer_options"; const val DEF_DEVELOPER_OPTIONS = false
    const val BENCHMARK_LOG = "benchmark_log"; const val DEF_BENCHMARK_LOG = false
    const val IDLE_PREVIEW = "idle_preview"; const val DEF_IDLE_PREVIEW = false
    const val AUTO_FULLSCREEN = "auto_fullscreen"; const val DEF_AUTO_FULLSCREEN = true
    const val KEEP_SCREEN_ON = "keep_screen_on"; const val DEF_KEEP_SCREEN_ON = true
    const val ADVERTISE_VIDEO = "advertise_video"; const val DEF_ADVERTISE_VIDEO = true
    const val ADVERTISE_AUDIO = "advertise_audio"; const val DEF_ADVERTISE_AUDIO = true
    const val AUTO_AUDIO_MODE = "auto_audio_mode"; const val DEF_AUTO_AUDIO_MODE = true
    const val LAUNCH_ON_CONNECT = "launch_on_connect"; const val DEF_LAUNCH_ON_CONNECT = true
    const val RETURN_TO_PREVIOUS_APP = "return_to_previous_app"; const val DEF_RETURN_TO_PREVIOUS_APP = true

    // internal bookkeeping (not user-facing settings): lets the watchdog tell a
    // deliberate stopServer() apart from the process simply having been killed
    const val SERVER_SHOULD_RUN = "server_should_run"
}
