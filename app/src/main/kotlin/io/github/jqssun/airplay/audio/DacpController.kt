package io.github.jqssun.airplay.audio

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sends DACP commands back to the AirPlay sender.
 * Resolves `iTunes_Ctrl_<dacpId>._dacp._tcp` via NsdManager, with discover + raw mDNS fallbacks
 * (this device's NsdManager resolve is unreliable).
 */
class DacpController(ctx: Context) {

    private val nsdManager = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val exec = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val discovering = AtomicBoolean(false)
    private val rawResolving = AtomicBoolean(false)

    @Volatile var dacpId = ""
    @Volatile var activeRemote = ""
    @Volatile private var host = ""
    @Volatile private var port = 0

    val ready: Boolean get() = host.isNotEmpty() && activeRemote.isNotEmpty()

    fun update(dacpId: String, activeRemote: String) {
        val senderChanged = this.dacpId != dacpId
        this.dacpId = dacpId
        this.activeRemote = activeRemote
        if (senderChanged) {
            host = ""
            port = 0
        }
        ensureResolved()
    }

    fun ensureResolved() {
        if (!_resolved() && dacpId.isNotEmpty()) _resolveAll()
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

    fun reset() {
        dacpId = ""
        activeRemote = ""
        host = ""
        port = 0
    }

    fun release() {
        reset()
        exec.shutdownNow()
    }

    private fun _resolved(): Boolean = host.isNotEmpty() && port > 0 && activeRemote.isNotEmpty()

    private fun _applyResolved(h: String, p: Int, source: String) {
        if (h.isEmpty() || p <= 0) return
        host = h
        port = p
        Log.i(TAG, "DACP resolved via $source: $host:$port")
    }

    private fun _resolveAll() {
        if (dacpId.isEmpty()) return
        _resolveDirect()
        _discover()
        if (rawResolving.compareAndSet(false, true)) {
            exec.execute {
                try {
                    _mdnsResolve()
                } finally {
                    rawResolving.set(false)
                }
            }
        }
    }

    private fun _resolveDirect() {
        val serviceName = "iTunes_Ctrl_$dacpId"
        val info = NsdServiceInfo().apply {
            serviceType = SERVICE_TYPE
            this.serviceName = serviceName
        }
        try {
            nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo, code: Int) {
                    Log.w(TAG, "DACP direct resolve failed: $code")
                }
                override fun onServiceResolved(si: NsdServiceInfo) {
                    _applyResolved(si.host?.hostAddress.orEmpty(), si.port, "nsd-resolve")
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "DACP direct resolve error", e)
        }
    }

    private fun _discover() {
        if (!discovering.compareAndSet(false, true)) return
        val want = "iTunes_Ctrl_$dacpId"
        lateinit var listener: NsdManager.DiscoveryListener
        listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, code: Int) {
                discovering.set(false)
                Log.w(TAG, "DACP discover start failed: $code")
            }
            override fun onStopDiscoveryFailed(serviceType: String, code: Int) {
                discovering.set(false)
            }
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {
                discovering.set(false)
            }
            override fun onServiceFound(service: NsdServiceInfo) {
                val name = service.serviceName ?: return
                if (!name.equals(want, ignoreCase = true) && !name.contains(dacpId, ignoreCase = true)) return
                try {
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(si: NsdServiceInfo, code: Int) {
                            Log.w(TAG, "DACP discover-resolve failed: $code")
                        }
                        override fun onServiceResolved(si: NsdServiceInfo) {
                            _applyResolved(si.host?.hostAddress.orEmpty(), si.port, "nsd-discover")
                            runCatching { nsdManager.stopServiceDiscovery(listener) }
                        }
                    })
                } catch (e: Exception) {
                    Log.w(TAG, "DACP discover resolve error", e)
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) {}
        }
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            mainHandler.postDelayed({
                runCatching { nsdManager.stopServiceDiscovery(listener) }
                discovering.set(false)
            }, DISCOVER_MS)
        } catch (e: Exception) {
            discovering.set(false)
            Log.w(TAG, "DACP discover error", e)
        }
    }

    /** Raw mDNS SRV query — bypasses broken NsdManager on some Android TV devices. */
    private fun _mdnsResolve() {
        if (_resolved() || dacpId.isEmpty()) return
        val qname = "iTunes_Ctrl_$dacpId._dacp._tcp.local"
        try {
            DatagramSocket().use { sock ->
                sock.soTimeout = MDNS_TIMEOUT_MS
                val query = _buildDnsQuery(qname, TYPE_SRV)
                val mdns = InetAddress.getByName("224.0.0.251")
                sock.send(DatagramPacket(query, query.size, mdns, 5353))
                val buf = ByteArray(2048)
                val deadline = System.currentTimeMillis() + MDNS_TIMEOUT_MS
                while (System.currentTimeMillis() < deadline && !_resolved()) {
                    val remaining = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1)
                    sock.soTimeout = remaining
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        sock.receive(packet)
                        val parsed = _parseSrvResponse(packet.data, packet.length) ?: continue
                        var ip = parsed.target
                        if (ip.endsWith(".local", ignoreCase = true) || !ip.any { it.isDigit() }) {
                            ip = _mdnsLookupA(sock, mdns, parsed.target) ?: continue
                        }
                        _applyResolved(ip, parsed.port, "mdns")
                        return
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                }
            }
            if (!_resolved()) Log.w(TAG, "DACP mDNS SRV not found for $qname")
        } catch (e: Exception) {
            Log.w(TAG, "DACP mDNS resolve error", e)
        }
    }

    private fun _mdnsLookupA(sock: DatagramSocket, mdns: InetAddress, name: String): String? {
        val host = if (name.endsWith(".")) name.dropLast(1) else name
        val query = _buildDnsQuery(host, TYPE_A)
        sock.send(DatagramPacket(query, query.size, mdns, 5353))
        val buf = ByteArray(1024)
        val deadline = System.currentTimeMillis() + 800
        while (System.currentTimeMillis() < deadline) {
            sock.soTimeout = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1)
            try {
                val packet = DatagramPacket(buf, buf.size)
                sock.receive(packet)
                _parseAResponse(packet.data, packet.length, host)?.let { return it }
            } catch (_: SocketTimeoutException) {
                break
            }
        }
        return null
    }

    private fun _send(path: String): ListenableFuture<Unit> {
        val result = SettableFuture.create<Unit>()
        if (activeRemote.isEmpty()) {
            Log.w(TAG, "DACP $path skipped: no Active-Remote")
            result.setException(IOException("no active-remote"))
            return result
        }
        if (!_resolved()) {
            Log.w(TAG, "DACP $path: endpoint unresolved, retrying discovery")
            _resolveAll()
        }
        try {
            exec.execute {
                // brief wait for in-flight mDNS/discover
                var waits = 0
                while (!_resolved() && waits < 15) {
                    Thread.sleep(100)
                    waits++
                }
                if (!_resolved()) {
                    Log.w(TAG, "DACP $path failed: still unresolved (id=$dacpId)")
                    result.setException(IOException("dacp endpoint not resolved"))
                    return@execute
                }
                val targetHost = host
                val targetPort = port
                try {
                    val url = "http://$targetHost:$targetPort$path"
                    Log.i(TAG, "DACP $path -> $targetHost:$targetPort")
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("Active-Remote", activeRemote)
                    conn.setRequestProperty("Host", "$targetHost:$targetPort")
                    conn.connectTimeout = 2000
                    conn.readTimeout = 2000
                    val code = conn.responseCode
                    try { conn.inputStream.readBytes() } catch (_: Exception) {}
                    conn.disconnect()
                    if (code in 200..299) {
                        result.set(Unit)
                    } else {
                        Log.w(TAG, "DACP $path -> HTTP $code")
                        _invalidateEndpoint(targetHost, targetPort)
                        result.setException(IOException("HTTP $code"))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "DACP send failed: $path", e)
                    _invalidateEndpoint(targetHost, targetPort)
                    result.setException(e)
                }
            }
        } catch (e: Exception) {
            result.setException(e)
        }
        return result
    }

    private fun _invalidateEndpoint(expectedHost: String, expectedPort: Int) {
        if (host == expectedHost && port == expectedPort) {
            host = ""
            port = 0
        }
        ensureResolved()
    }

    private companion object {
        const val TAG = "DacpController"
        const val SERVICE_TYPE = "_dacp._tcp."
        const val DISCOVER_MS = 8_000L
        const val MDNS_TIMEOUT_MS = 2_000
        const val TYPE_A = 1
        const val TYPE_SRV = 33

        private data class Srv(val port: Int, val target: String)

        private fun _buildDnsQuery(name: String, type: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val dos = DataOutputStream(out)
            dos.writeShort(0) // id
            dos.writeShort(0) // flags (standard query, no recursion for mDNS)
            dos.writeShort(1) // QDCOUNT
            dos.writeShort(0)
            dos.writeShort(0)
            dos.writeShort(0)
            for (label in name.trimEnd('.').split('.')) {
                val bytes = label.toByteArray(Charsets.UTF_8)
                dos.writeByte(bytes.size)
                dos.write(bytes)
            }
            dos.writeByte(0)
            dos.writeShort(type)
            // Request a unicast mDNS response. The socket uses an ephemeral
            // source port and therefore cannot receive multicast replies on 5353.
            dos.writeShort(0x8001) // QU bit + IN
            dos.flush()
            return out.toByteArray()
        }

        private fun _parseSrvResponse(data: ByteArray, len: Int): Srv? {
            if (len < 12) return null
            val inp = DataInputStream(data.inputStream())
            inp.readUnsignedShort() // id
            inp.readUnsignedShort() // flags
            val qd = inp.readUnsignedShort()
            val an = inp.readUnsignedShort()
            inp.readUnsignedShort()
            inp.readUnsignedShort()
            repeat(qd) {
                _skipName(inp, data)
                inp.readUnsignedShort()
                inp.readUnsignedShort()
            }
            repeat(an) {
                _skipName(inp, data)
                val type = inp.readUnsignedShort()
                inp.readUnsignedShort() // class
                inp.readInt() // ttl
                val rdlen = inp.readUnsignedShort()
                if (type == TYPE_SRV && rdlen >= 6) {
                    inp.readUnsignedShort() // priority
                    inp.readUnsignedShort() // weight
                    val port = inp.readUnsignedShort()
                    val target = _readName(inp, data)
                    return Srv(port, target.trimEnd('.'))
                } else {
                    inp.skipBytes(rdlen)
                }
            }
            return null
        }

        private fun _parseAResponse(data: ByteArray, len: Int, wantHost: String): String? {
            if (len < 12) return null
            val inp = DataInputStream(data.inputStream())
            inp.readUnsignedShort()
            inp.readUnsignedShort()
            val qd = inp.readUnsignedShort()
            val an = inp.readUnsignedShort()
            inp.readUnsignedShort()
            inp.readUnsignedShort()
            repeat(qd) {
                _skipName(inp, data)
                inp.readUnsignedShort()
                inp.readUnsignedShort()
            }
            repeat(an) {
                _skipName(inp, data)
                val type = inp.readUnsignedShort()
                inp.readUnsignedShort()
                inp.readInt()
                val rdlen = inp.readUnsignedShort()
                if (type == TYPE_A && rdlen == 4) {
                    val a = inp.readUnsignedByte()
                    val b = inp.readUnsignedByte()
                    val c = inp.readUnsignedByte()
                    val d = inp.readUnsignedByte()
                    return "$a.$b.$c.$d"
                } else {
                    inp.skipBytes(rdlen)
                }
            }
            return null
        }

        private fun _skipName(inp: DataInputStream, data: ByteArray) {
            while (true) {
                val len = inp.readUnsignedByte()
                if (len == 0) return
                if ((len and 0xC0) == 0xC0) {
                    inp.readUnsignedByte()
                    return
                }
                inp.skipBytes(len)
            }
        }

        private fun _readName(inp: DataInputStream, data: ByteArray): String {
            val parts = mutableListOf<String>()
            var jumps = 0
            var pos = -1
            while (jumps < 10) {
                val len = if (pos >= 0) {
                    val l = data[pos].toInt() and 0xff
                    pos++
                    l
                } else {
                    inp.readUnsignedByte()
                }
                if (len == 0) break
                if ((len and 0xC0) == 0xC0) {
                    val b2 = if (pos >= 0) data[pos].toInt() and 0xff else inp.readUnsignedByte()
                    if (pos >= 0) pos++
                    pos = ((len and 0x3F) shl 8) or b2
                    jumps++
                    continue
                }
                val label = if (pos >= 0) {
                    val s = String(data, pos, len, Charsets.UTF_8)
                    pos += len
                    s
                } else {
                    val buf = ByteArray(len)
                    inp.readFully(buf)
                    String(buf, Charsets.UTF_8)
                }
                parts += label
            }
            return parts.joinToString(".")
        }
    }
}
