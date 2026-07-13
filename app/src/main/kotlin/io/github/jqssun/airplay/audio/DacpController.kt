package io.github.jqssun.airplay.audio

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Sends DACP (Digital Audio Control Protocol) commands back to the AirPlay sender.
 * Resolves the sender's control port via mDNS, then sends HTTP GET requests.
 */
class DacpController(ctx: Context) {

    private val nsdManager = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val exec = Executors.newSingleThreadExecutor()

    @Volatile var dacpId = ""
    @Volatile var activeRemote = ""
    @Volatile private var host = ""
    @Volatile private var port = 0

    fun update(dacpId: String, activeRemote: String) {
        this.dacpId = dacpId
        this.activeRemote = activeRemote
        _resolve()
    }

    fun play() = _send("/ctrl-int/1/play")
    fun pause() = _send("/ctrl-int/1/pause")
    fun nextItem() = _send("/ctrl-int/1/nextitem")
    fun prevItem() = _send("/ctrl-int/1/previtem")
    fun volumeUp() = _send("/ctrl-int/1/volumeup")
    fun volumeDown() = _send("/ctrl-int/1/volumedown")
    fun muteToggle() = _send("/ctrl-int/1/mutetoggle")
    fun beginFastForward() = _send("/ctrl-int/1/beginff")
    fun beginRewind() = _send("/ctrl-int/1/beginrew")
    fun playResume() = _send("/ctrl-int/1/playresume")

    fun release() {
        exec.shutdownNow()
    }

    private fun _resolve() {
        if (dacpId.isEmpty()) return
        val serviceName = "iTunes_Ctrl_$dacpId"
        val info = NsdServiceInfo().apply {
            serviceType = "_dacp._tcp"
            this.serviceName = serviceName
        }
        try {
            nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo, code: Int) {
                    Log.w(TAG, "DACP resolve failed: $code")
                }
                override fun onServiceResolved(si: NsdServiceInfo) {
                    host = si.host.hostAddress ?: return
                    port = si.port
                    Log.i(TAG, "DACP resolved: $host:$port")
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "DACP resolve error", e)
        }
    }

    private fun _send(path: String): ListenableFuture<Unit> {
        val result = SettableFuture.create<Unit>()
        if (host.isEmpty() || activeRemote.isEmpty()) {
            result.setException(IOException("dacp endpoint not resolved"))
            return result
        }
        try {
            exec.execute {
                try {
                    val url = "http://$host:$port$path"
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("Active-Remote", activeRemote)
                    conn.setRequestProperty("Host", "$host:$port")
                    conn.connectTimeout = 2000
                    conn.readTimeout = 2000
                    val code = conn.responseCode
                    try { conn.inputStream.readBytes() } catch (_: Exception) {}
                    conn.disconnect()
                    if (code in 200..299) {
                        result.set(Unit)
                    } else {
                        Log.w(TAG, "DACP $path -> HTTP $code")
                        result.setException(IOException("HTTP $code"))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "DACP send failed: $path", e)
                    result.setException(e)
                }
            }
        } catch (e: Exception) {
            result.setException(e)
        }
        return result
    }

    companion object {
        private const val TAG = "DacpController"
    }
}
