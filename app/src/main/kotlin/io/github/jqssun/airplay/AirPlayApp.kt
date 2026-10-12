package io.github.jqssun.airplay

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AirPlayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.MIRROR_SYNC_MVP) {
            val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
            if (!prefs.getBoolean("mvp_defaults_v1", false)) {
                prefs.edit()
                    .putString(Prefs.SERVER_NAME, "Android AirPlay MVP")
                    .putBoolean(Prefs.BOOT_AUTO_START, false)
                    .putBoolean(Prefs.LOW_LATENCY, true)
                    .putBoolean(Prefs.SCHEDULED_OUTPUT_BUFFER_RELEASE, true)
                    .putBoolean(Prefs.AUDIO_AUTO_BUFFER, false)
                    .putInt(Prefs.AUDIO_CUSHION_MS, 40)
                    .putBoolean(Prefs.DEBUG_ENABLED, true)
                    .putBoolean(Prefs.DEVELOPER_OPTIONS, true)
                    .putBoolean(Prefs.BENCHMARK_LOG, true)
                    .putBoolean("mvp_defaults_v1", true)
                    .commit()
            }
            if (BuildConfig.H10_HEVC_MVP && !prefs.getBoolean("h10_hevc_defaults_v1", false)) {
                // Only the independent HEVC test package requests a larger mirror.
                // This does not change the HDMI output or the stable MVP preferences.
                prefs.edit()
                    .putString(Prefs.SERVER_NAME, if (BuildConfig.H10_DIRECT_SURFACE)
                        "Android AirPlay H10 Direct 60" else if (BuildConfig.H10_HEVC_MAX_FPS == 60)
                        "Android AirPlay H.265 60 Test" else "Android AirPlay H.265 Test")
                    .putString(Prefs.RESOLUTION, "2560x1440")
                    .putInt(Prefs.MAX_FPS, BuildConfig.H10_HEVC_MAX_FPS)
                    .putBoolean(Prefs.H265_ENABLED, true)
                    .putBoolean("h10_hevc_defaults_v1", true)
                    .commit()
            }
        }
    }
}
