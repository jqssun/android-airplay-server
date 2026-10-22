package io.github.jqssun.airplay.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.io.File

/**
 * What the wifi link looks like right now.
 *
 * The link decides how much the stream can carry: on 2.4 GHz a 4K stream at 60 fps
 * does not fit at all, and a weak 5 GHz signal makes the sender retransmit, which
 * freezes the picture instead of dropping a frame.
 */
data class WifiLink(
    val band: String,
    val rssiDbm: Int,
    val linkSpeedMbps: Int,
    val frequencyMHz: Int,
    /** true while the system throttles scans, which costs the stream airtime */
    val scanThrottled: Boolean? = null,
) {
    val quality: String
        get() = when {
            rssiDbm >= -50 -> "excellent"
            rssiDbm >= -60 -> "good"
            rssiDbm >= -70 -> "weak"
            else -> "very weak"
        }
}

/** The number of packets the kernel dropped because a receive buffer was full. */
data class UdpErrors(val rcvbuf: Long, val sndbuf: Long)

/** The band a channel belongs to. */
fun bandOfFrequency(mhz: Int): String = when {
    mhz <= 0 -> "unknown"
    mhz < 2500 -> "2.4 GHz"
    mhz < 5900 -> "5 GHz"
    else -> "6 GHz"
}

/**
 * Reads the state of the wifi link.
 *
 * On api 31 and later the information comes from the network the system is actually
 * using, before that from the wifi connection info, which does not know the link
 * speed on older releases.
 */
fun readWifiLink(context: Context): WifiLink? {
    val app = context.applicationContext
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            val info = caps.transportInfo as? WifiInfo
            if (info != null) {
                return WifiLink(
                    band = bandOfFrequency(info.frequency),
                    rssiDbm = info.rssi,
                    linkSpeedMbps = info.linkSpeed,
                    frequencyMHz = info.frequency,
                    scanThrottled = readScanThrottleEnabled(app),
                )
            }
        }
    }
    val wm = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
    @Suppress("DEPRECATION")
    val info = wm.connectionInfo ?: return null
    @Suppress("DEPRECATION")
    val frequency = info.frequency
    @Suppress("DEPRECATION")
    val rssi = info.rssi
    val speed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) info.linkSpeed else 0
    if (frequency <= 0 && rssi == 0) return null
    return WifiLink(
        band = bandOfFrequency(frequency),
        rssiDbm = rssi,
        linkSpeedMbps = speed,
        frequencyMHz = frequency,
        scanThrottled = readScanThrottleEnabled(app),
    )
}

/**
 * True while the system throttles background scans (api 30+). A scan hops to
 * another channel and every hop costs the stream airtime. Read only: switching
 * it off is done in the developer options of the device.
 */
fun readScanThrottleEnabled(context: Context): Boolean? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    return try {
        // the context may not be attached yet, so the lookup belongs in the try
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        wm.isScanThrottleEnabled
    } catch (e: Exception) {
        Log.w("WifiLink", "scan throttle state unavailable", e)
        null
    }
}

/**
 * The number of packets the kernel dropped because the receive or the send buffer
 * of a socket was full. On the video path (tcp) this is not where the loss shows
 * up, there a retransmission stalls the whole stream instead.
 *
 * The counters live in /proc/net/snmp, which selinux denies to an app on many
 * devices. Every denied read writes a line into the kernel log, so a reader that
 * asks again and again fills the logcat buffer with denials and pushes the
 * diagnostics of the session out of it. The read is therefore cached, and a
 * device that denies it is asked again only once a minute.
 */
fun readUdpErrors(): UdpErrors = _udpErrorCache.get()

private val _udpErrorCache = UdpErrorCache(read = {
    val lines = File("/proc/net/snmp").readLines()
    val header = lines.firstOrNull { it.startsWith("Udp:") }
    val values = lines.firstOrNull { it.startsWith("Udp:") && it != header }
        ?: throw java.io.IOException("no udp counters")
    val names = header!!.trim().split(Regex("\\s+")).drop(1)
    val nums = values.trim().split(Regex("\\s+")).drop(1)
    fun value(name: String): Long {
        val i = names.indexOf(name)
        return if (i in nums.indices) nums[i].toLongOrNull() ?: 0 else 0
    }
    UdpErrors(value("RcvbufErrors"), value("SndbufErrors"))
})

/**
 * Reads the counters at most once a second, and asks a device that denies the
 * read only once a minute.
 */
class UdpErrorCache(
    private val read: () -> UdpErrors,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private var value = UdpErrors(0, 0)
    private var readAtMs = 0L
    private var retryAtMs = 0L
    private var hasRead = false
    private var failed = false

    @Synchronized
    fun get(): UdpErrors {
        val t = now()
        // the device denied the read: wait before asking again
        if (t < retryAtMs) return value
        if (hasRead && t - readAtMs < CACHE_MS) return value
        hasRead = true
        readAtMs = t
        return try {
            value = read()
            failed = false
            value
        } catch (e: Exception) {
            if (!failed) {
                // once is enough: the counters are simply not readable here
                Log.w("WifiLink", "udp counters unavailable: ${e.message}")
            }
            failed = true
            retryAtMs = t + RETRY_MS
            value
        }
    }

    companion object {
        const val CACHE_MS = 1_000L
        const val RETRY_MS = 60_000L
    }
}