package io.github.jqssun.airplay

import android.content.SharedPreferences
import android.media.MediaFormat
import androidx.core.content.edit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

sealed class Pref<T : Any>(val key: String, val def: T, val restart: Boolean) {
    abstract fun parse(s: String): T?
    open fun valid(v: T) = true
    protected abstract fun SharedPreferences.load(): T
    protected abstract fun SharedPreferences.Editor.store(v: T)

    fun read(p: SharedPreferences): T = p.load().takeIf(::valid) ?: def
    fun write(p: SharedPreferences, v: T): Boolean = valid(v).also { if (it) p.edit { store(v) } }
}

class BoolPref(key: String, def: Boolean, restart: Boolean) : Pref<Boolean>(key, def, restart) {
    override fun parse(s: String) = s.toBooleanStrictOrNull()
    override fun SharedPreferences.load() = getBoolean(key, def)
    override fun SharedPreferences.Editor.store(v: Boolean) { putBoolean(key, v) }
}

class IntPref(key: String, def: Int, val range: IntRange, restart: Boolean) : Pref<Int>(key, def, restart) {
    override fun parse(s: String) = s.toIntOrNull()
    override fun valid(v: Int) = v in range
    override fun SharedPreferences.load() = getInt(key, def)
    override fun SharedPreferences.Editor.store(v: Int) { putInt(key, v) }
}

class StrPref(key: String, def: String, restart: Boolean, private val check: (String) -> Boolean) : Pref<String>(key, def, restart) {
    override fun parse(s: String) = s
    override fun valid(v: String) = check(v)
    override fun SharedPreferences.load() = getString(key, def) ?: def
    override fun SharedPreferences.Editor.store(v: String) { putString(key, v) }
}

operator fun <T : Any> SharedPreferences.get(p: Pref<T>): T = p.read(this)

fun SharedPreferences.changes(): Flow<String> = callbackFlow {
    val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key != null) trySend(key) }
    registerOnSharedPreferenceChangeListener(listener)
    awaitClose { unregisterOnSharedPreferenceChangeListener(listener) }
}

fun <T> SharedPreferences.observe(read: SharedPreferences.() -> T): Flow<T> =
    changes().map { read() }.onStart { emit(read()) }.distinctUntilChanged()

object Prefs {
    const val NAME = "settings"
    const val AUTO = "auto"; const val ON = "on"; const val OFF = "off"
    const val PORTRAIT = "portrait"; const val LANDSCAPE = "landscape"
    const val FALLBACK_MAC_ADDRESS = "fallback_mac_address"
    val ADAPTIVE_PERCENTILES = intArrayOf(80, 85, 90, 95, 99)
    private val SIZE = Regex("[1-9]\\d{0,4}x[1-9]\\d{0,4}")

    private val _all = mutableListOf<Pref<*>>()
    val all: Map<String, Pref<*>> by lazy { _all.associateBy { it.key } }

    private fun bool(key: String, def: Boolean, restart: Boolean = false) =
        BoolPref(key, def, restart).also(_all::add)
    private fun int(key: String, def: Int, range: IntRange, restart: Boolean = false) =
        IntPref(key, def, range, restart).also(_all::add)
    private fun str(key: String, def: String, restart: Boolean = false, check: (String) -> Boolean = { true }) =
        StrPref(key, def, restart, check).also(_all::add)
    private fun tri(key: String, restart: Boolean = false) = str(key, AUTO, restart) { it in setOf(AUTO, ON, OFF) }

    fun forced(v: String) = when (v) { ON -> true; OFF -> false; else -> null }

    val SERVER_NAME = str("server_name", "Android AirPlay", restart = true)
    val SERVER_PORT = int("server_port", 7000, 1..65535, restart = true)
    val AUTO_START = bool("auto_start", true)
    val BOOT_AUTO_START = bool("boot_auto_start", true)
    val RUN_IN_BACKGROUND = bool("run_in_background", true)
    val H265 = tri("h265", restart = true)
    // Opt-in: preserve decoder buffers for TVs with a lower-resolution Android UI.
    val DIRECT_SURFACE = bool("direct_surface", false, restart = true)
    val ENFORCE_SDR = bool("enforce_sdr", true, restart = true)
    val KEY_ALLOW_FRAME_DROP = bool(MediaFormat.KEY_ALLOW_FRAME_DROP, true, restart = true)
    val KEY_PRIORITY = bool(MediaFormat.KEY_PRIORITY, true, restart = true)
    val LOW_LATENCY = bool("low_latency", false)
    val OPERATING_RATE = tri("operating_rate", restart = true)
    val SCHEDULED_OUTPUT_BUFFER_RELEASE = bool("scheduled_output_buffer_release", false, restart = true)
    val AUDIO_AUTO_BUFFER = bool("audio_auto_buffer", true)
    // fixed cushion ms, used only when AUDIO_AUTO_BUFFER is off
    val AUDIO_CUSHION_MS = int("audio_cushion_ms", 40, 1..1000)
    // slider step 0..4 mapping to arrival-delay percentile the cushion targets;
    // lower = less latency, higher = more stable
    val AUDIO_ADAPTIVE_STEP = int("audio_adaptive_step", 3, ADAPTIVE_PERCENTILES.indices)
    val OBOE_BUFFER_FRAMES = int("oboe_buffer_frames", 0, 0..8192)
    val ALAC_ENABLED = bool("alac_enabled", false, restart = true)
    val FORCE_SW_ALAC = bool("force_sw_alac", true)
    val FORCE_SW_AAC = bool("force_sw_aac", false)
    val AAC_ENABLED = bool("aac_enabled", true, restart = true)
    // on: sender volume drives receiver volume; off: volume = receiver volume x sender volume
    val SYNC_CLIENT_VOLUME = bool("sync_client_volume", true)
    val RESOLUTION = str("resolution", AUTO, restart = true) { it in setOf(AUTO, PORTRAIT, LANDSCAPE) || SIZE.matches(it) }
    val MAX_FPS = int("max_fps", 60, 1..Int.MAX_VALUE, restart = true)
    val OVERSCANNED = bool("overscanned", false, restart = true)
    val REQUIRE_PIN = bool("require_pin", false, restart = true)
    val ALLOW_NEW_CONN = bool("allow_new_conn", true, restart = true)
    val AUDIO_LATENCY_MS = int("audio_latency_ms", -1, -1..1000, restart = true)
    val DEBUG_ENABLED = bool("debug_enabled", false)
    val DEVELOPER_OPTIONS = bool("developer_options", false)
    val BENCHMARK_LOG = bool("benchmark_log", false, restart = true)
    val IDLE_PREVIEW = bool("idle_preview", false)
    val AUTO_FULLSCREEN = bool("auto_fullscreen", true)
    val KEEP_SCREEN_ON = bool("keep_screen_on", true)
    val ADVERTISE_VIDEO = bool("advertise_video", true, restart = true)
    val ADVERTISE_MIRRORING = bool("advertise_mirroring", true, restart = true)
    val ADVERTISE_AUDIO = bool("advertise_audio", true, restart = true)
    val LAUNCH_ON_CONNECT = bool("launch_on_connect", true)
    val USB_ONLY = bool("usb_only", false, restart = true)
}
