package io.github.jqssun.airplay.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
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
    )
}

/**
 * The number of packets the kernel dropped because the receive or the send buffer
 * of a socket was full. On the video path (tcp) this is not where the loss shows
 * up, there a retransmission stalls the whole stream instead.
 */
fun readUdpErrors(): UdpErrors {
    return try {
        val lines = File("/proc/net/snmp").readLines()
        val header = lines.firstOrNull { it.startsWith("Udp:") } ?: return UdpErrors(0, 0)
        val values = lines.firstOrNull { it.startsWith("Udp:") && it != header } ?: return UdpErrors(0, 0)
        val names = header.trim().split(Regex("\\s+")).drop(1)
        val nums = values.trim().split(Regex("\\s+")).drop(1)
        fun value(name: String): Long {
            val i = names.indexOf(name)
            return if (i in nums.indices) nums[i].toLongOrNull() ?: 0 else 0
        }
        UdpErrors(value("RcvbufErrors"), value("SndbufErrors"))
    } catch (_: Exception) {
        UdpErrors(0, 0)
    }
}
