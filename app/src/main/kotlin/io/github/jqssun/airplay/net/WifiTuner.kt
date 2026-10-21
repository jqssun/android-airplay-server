package io.github.jqssun.airplay.net

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

/**
 * Keeps the wifi radio awake while the server runs.
 *
 * An idle stick or phone lets the radio sleep between beacons (dtim skipping).
 * A frame that arrives while the radio sleeps has to wait for the next beacon,
 * which is jitter the stream cannot get back, and the retries that follow eat
 * throughput. A wifi lock is the lever an app is allowed to pull here.
 *
 * The api that switches power save or scan throttling off directly
 * (WifiManager.setWifiPowerSaveMode, setScanThrottlingEnabled, setTxPowerOverride)
 * is a system api, not a public one, so this app cannot call it. It reports what
 * it finds instead ([scanThrottled]) and the docs carry the adb commands for the
 * rest.
 *
 * The lock is not free - the platform documents shorter battery life and possibly
 * lower throughput - so it is a setting rather than something forced on the user.
 */
class WifiTuner(private val context: Context) {

    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var lock: WifiManager.WifiLock? = null

    /**
     * Takes the lock for as long as the server runs. Safe to call twice.
     * Returns what it did, for the service log.
     */
    fun acquire(enabled: Boolean): String {
        release()
        if (!enabled) return "wifi lock off"
        val wifi = wifi ?: return "wifi unavailable"
        val lowLatency = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val mode = if (lowLatency) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else HIGH_PERF
        return try {
            lock = wifi.createWifiLock(mode, "airplay:stream").apply { acquire() }
            "wifi lock held (${if (lowLatency) "low latency" else "high perf"})"
        } catch (e: Exception) {
            Log.w(TAG, "wifi lock refused", e)
            lock = null
            "wifi lock refused (${e.javaClass.simpleName})"
        }
    }

    /** Gives the radio back to the system. */
    fun release() {
        val held = lock ?: return
        lock = null
        try {
            if (held.isHeld) held.release()
        } catch (e: Exception) {
            Log.w(TAG, "wifi lock release failed", e)
        }
    }

    /**
     * True while the system throttles background scans. A scan hops to another
     * channel, and every hop costs the stream airtime it cannot get back, so this
     * is worth showing. Read only on purpose: switching it off is the user's job,
     * in the developer options of the device.
     */
    fun scanThrottled(): Boolean? = readScanThrottleEnabled(context)

    companion object {
        private const val TAG = "WifiTuner"
        // deprecated in favour of the low latency lock, but on api < 29 it is
        // the only way to keep the radio awake
        @Suppress("DEPRECATION")
        private val HIGH_PERF = WifiManager.WIFI_MODE_FULL_HIGH_PERF
    }
}
