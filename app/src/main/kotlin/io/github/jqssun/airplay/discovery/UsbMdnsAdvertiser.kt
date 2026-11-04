package io.github.jqssun.airplay.discovery

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * mDNS responder for usb tether links (usb0/ncm0/rndis0) on Android 16 and below
 */
class UsbMdnsAdvertiser(
    private val services: List<MdnsService>,
    private val onLink: (iface: String?, addr: Inet4Address?) -> Unit,
) {
    private class Link(val name: String, val addr: Inet4Address, val mask: Int, val socket: MulticastSocket) {
        fun covers(a: Inet4Address) = ((_ip(a) xor _ip(addr)) and mask) == 0
    }

    private class Record(val name: List<String>, val type: Int, val unique: Boolean, val ttl: Int, val rdata: ByteArray) {
        val key get() = Triple(name.joinToString(".").lowercase(), type, rdata.asList())
    }

    private enum class State { CHECKING, ACTIVE, YIELDED }

    private val stopped = CountDownLatch(1)
    private val running get() = stopped.count > 0
    private var thread: Thread? = null
    private val main = Handler(Looper.getMainLooper())
    private val host = listOf("airplay-usb-%06x".format(Random.nextInt(0x1000000)), "local")

    private var link: Link? = null
    private var state = State.CHECKING
    private var checkUntil = 0L
    private var nextQuery = 0L
    private var foreignAt = 0L
    private var announced = 0
    private var nextAnnounce = 0L

    fun start() {
        thread = Thread(::_loop, "usb-mdns").apply { isDaemon = true; start() }
    }

    fun stop() {
        stopped.countDown()
        thread?.join(STOP_TIMEOUT_MS)
        thread = null
    }

    private fun _loop() {
        val buf = ByteArray(9000)
        var nextScan = 0L
        try {
            while (running) {
                val now = SystemClock.elapsedRealtime()
                if (now >= nextScan) {
                    nextScan = now + SCAN_MS
                    _scan(now)
                }
                val l = link
                if (l == null) {
                    stopped.await(SCAN_MS, TimeUnit.MILLISECONDS)
                    continue
                }
                _tick(l, now)
                val packet = DatagramPacket(buf, buf.size)
                try {
                    l.socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (running) _onPacket(l, packet)
            }
        } catch (e: Exception) {
            Log.w(TAG, "USB mDNS loop failed", e)
        } finally {
            link?.let {
                _close(it)
                _report(null)
            }
        }
    }

    private fun _scan(now: Long) {
        val found = try { _findUsb() } catch (_: SocketException) { return }
        val prev = link
        if (found?.first?.name == prev?.name && found?.second?.address == prev?.addr) return
        prev?.let(::_close)
        val l = found?.let { (ni, ia) -> _open(ni, ia) }
        link = l
        if (l != null) _check(now)
        if (l?.name != prev?.name || l?.addr != prev?.addr) _report(l)
    }

    private fun _report(l: Link?) = main.post { if (running) onLink(l?.name, l?.addr) }

    private fun _findUsb() = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { USB_IFACE.matches(it.name) && it.isUp }
        .firstNotNullOfOrNull { ni -> ni.interfaceAddresses.firstOrNull { it.address is Inet4Address }?.let { ni to it } }

    private fun _open(ni: NetworkInterface, ia: InterfaceAddress): Link? {
        val socket = MulticastSocket(null)
        return try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(MDNS_PORT))
            socket.joinGroup(GROUP, ni)
            socket.networkInterface = ni
            socket.timeToLive = 255
            socket.soTimeout = RECEIVE_TIMEOUT_MS
            val prefix = ia.networkPrefixLength.toInt()
            Log.i(TAG, "USB link ${ni.name} ${ia.address.hostAddress}/$prefix")
            Link(ni.name, ia.address as Inet4Address, if (prefix <= 0) 0 else -1 shl (32 - prefix), socket)
        } catch (e: Exception) {
            socket.close()
            Log.w(TAG, "Cannot open mDNS socket on ${ni.name}", e)
            null
        }
    }

    private fun _close(l: Link) {
        if (state == State.ACTIVE) _send(l, _records(l), goodbye = true)
        l.socket.close()
        Log.i(TAG, "Closed ${l.name}")
    }

    private fun _check(now: Long) {
        state = State.CHECKING
        checkUntil = now + CHECK_MS
        nextQuery = now
    }

    private fun _tick(l: Link, now: Long) {
        when (state) {
            State.CHECKING -> when {
                now >= checkUntil -> {
                    Log.i(TAG, "Advertising on ${l.name}")
                    state = State.ACTIVE
                    announced = 0
                    nextAnnounce = now
                }
                now >= nextQuery -> {
                    _query(l)
                    nextQuery = now + CHECK_QUERY_MS
                }
            }
            // rfc 6762 8.3 interval doubling
            State.ACTIVE -> if (announced < ANNOUNCE_COUNT && now >= nextAnnounce) {
                _send(l, _records(l))
                nextAnnounce = now + (1000L shl announced)
                announced++
            }
            State.YIELDED -> when {
                now - foreignAt > RECHECK_MS + CHECK_MS -> _check(now)
                now >= nextQuery -> {
                    _query(l)
                    nextQuery = now + RECHECK_MS
                }
            }
        }
    }

    private fun _onPacket(l: Link, packet: DatagramPacket) {
        val src = packet.address as? Inet4Address ?: return
        if (packet.length < 12 || !l.covers(src)) return
        val data = packet.data.copyOf(packet.length)
        if ((_rd16(data, 2) and 0x8000) == 0) {
            // a probe for our names means another responder is about to claim them
            if (_probes(data)) _foreignSeen(l)
            else if (state == State.ACTIVE) _answer(l, InetSocketAddress(src, packet.port), data)
            return
        }
        // rfc 6762 6 responses not sent from 5353 ignored
        if (packet.port != MDNS_PORT) return
        when (_foreign(data)) {
            true -> _foreignSeen(l)
            false -> if (state == State.YIELDED) _check(SystemClock.elapsedRealtime())
            null -> {}
        }
    }

    private fun _foreignSeen(l: Link) {
        val now = SystemClock.elapsedRealtime()
        foreignAt = now
        if (state == State.YIELDED) return
        if (state == State.ACTIVE) {
            Log.i(TAG, "Another responder showed up on ${l.name}, yielding")
            // withdraw host-bound records only
            _send(l, services.map(::_srv) + _a(l), goodbye = true)
        } else {
            Log.i(TAG, "Another responder covers ${l.name}, staying quiet")
        }
        state = State.YIELDED
        nextQuery = now + RECHECK_MS
    }

    // rfc 6762 8.1
    private fun _probes(d: ByteArray): Boolean {
        if (_rd16(d, 8) == 0) return false
        var pos = 12
        repeat(_rd16(d, 4)) {
            val (name, next) = _readName(d, pos) ?: return false
            if (services.any { _eq(name, _instance(it)) }) return true
            pos = next + 4
        }
        return false
    }

    // true: live, false: goodbye, null: none
    private fun _foreign(d: ByteArray): Boolean? {
        var result: Boolean? = null
        var pos = 12
        repeat(_rd16(d, 4)) {
            pos = (_readName(d, pos) ?: return result).second + 4
        }
        repeat(_rd16(d, 6) + _rd16(d, 8) + _rd16(d, 10)) {
            val (name, next) = _readName(d, pos) ?: return result
            if (next + 10 > d.size) return result
            val type = _rd16(d, next)
            val ttl = (_rd16(d, next + 4) shl 16) or _rd16(d, next + 6)
            val rdata = next + 10
            val end = rdata + _rd16(d, next + 8)
            if (end > d.size) return result
            if (type == TYPE_SRV && end > rdata + 6 && services.any { _eq(name, _instance(it)) }) {
                val target = _readName(d, rdata + 6)?.first
                if (target != null && !_eq(target, host)) {
                    if (ttl != 0) return true
                    result = false
                }
            }
            pos = end
        }
        return result
    }

    // records

    private fun _type(s: MdnsService) = s.type.split('.') + "local"
    private fun _instance(s: MdnsService) = listOf(s.name) + _type(s)

    private fun _ptr(s: MdnsService) = Record(_type(s), TYPE_PTR, false, TTL_OTHER, _name(_instance(s)))
    private fun _enum(s: MdnsService) = Record(SERVICES, TYPE_PTR, false, TTL_OTHER, _name(_type(s)))
    private fun _srv(s: MdnsService) = Record(_instance(s), TYPE_SRV, true, TTL_HOST, _bytes {
        _u16(0); _u16(0); _u16(s.port); write(_name(host))
    })
    private fun _txt(s: MdnsService) = Record(_instance(s), TYPE_TXT, true, TTL_OTHER, _bytes {
        if (s.txt.isEmpty()) write(0)
        s.txt.forEach { (k, v) -> _label("$k=$v", 255) }
    })
    private fun _a(l: Link) = Record(host, TYPE_A, true, TTL_HOST, l.addr.address)

    private fun _records(l: Link) = services.flatMap { listOf(_ptr(it), _srv(it), _txt(it), _enum(it)) } + _a(l)

    // packets

    private fun _query(l: Link) = _transmit(l, _bytes {
        _u16(0); _u16(0); _u16(services.size); _u16(0); _u16(0); _u16(0)
        services.forEach { write(_name(_instance(it))); _u16(TYPE_SRV); _u16(CLASS_IN) }
    }, GROUP)

    private fun _answer(l: Link, from: InetSocketAddress, d: ByteArray) {
        val answers = LinkedHashMap<Any, Record>()
        val extras = LinkedHashMap<Any, Record>()
        fun MutableMap<Any, Record>.add(vararg rs: Record) = rs.forEach { getOrPut(it.key) { it } }
        var pos = 12
        repeat(_rd16(d, 4)) {
            val (name, next) = _readName(d, pos) ?: return
            if (next + 4 > d.size) return
            val type = _rd16(d, next)
            pos = next + 4
            fun wants(t: Int) = type == t || type == TYPE_ANY
            if (_eq(name, SERVICES) && wants(TYPE_PTR)) services.forEach { answers.add(_enum(it)) }
            for (s in services) {
                if (_eq(name, _type(s)) && wants(TYPE_PTR)) {
                    answers.add(_ptr(s))
                    extras.add(_srv(s), _txt(s), _a(l))
                }
                if (_eq(name, _instance(s))) {
                    if (wants(TYPE_SRV)) { answers.add(_srv(s)); extras.add(_a(l)) }
                    if (wants(TYPE_TXT)) answers.add(_txt(s))
                }
            }
            if (_eq(name, host) && wants(TYPE_A)) answers.add(_a(l))
        }
        if (answers.isEmpty()) return
        answers.keys.forEach(extras::remove)
        // rfc 6762 6.7
        if (from.port == MDNS_PORT) _send(l, answers.values.toList(), extras.values.toList())
        else _send(l, answers.values.toList(), extras.values.toList(), legacy = d.copyOf(pos), to = from)
    }

    private fun _send(
        l: Link,
        answers: List<Record>,
        extras: List<Record> = emptyList(),
        goodbye: Boolean = false,
        legacy: ByteArray? = null,
        to: SocketAddress = GROUP,
    ) = _transmit(l, _bytes {
        if (legacy != null) {
            // name pointers in echoed questions stay valid since header length is the same
            write(legacy, 0, 2); _u16(RESPONSE_FLAGS); write(legacy, 4, 2)
        } else {
            _u16(0); _u16(RESPONSE_FLAGS); _u16(0)
        }
        _u16(answers.size); _u16(0); _u16(extras.size)
        legacy?.let { write(it, 12, it.size - 12) }
        for (r in answers + extras) {
            write(_name(r.name))
            _u16(r.type)
            // rfc 6762 10.2
            _u16(if (r.unique && !goodbye && legacy == null) CLASS_IN or CACHE_FLUSH else CLASS_IN)
            _u32(if (goodbye) 0 else if (legacy != null) minOf(r.ttl, LEGACY_TTL) else r.ttl)
            _u16(r.rdata.size)
            write(r.rdata)
        }
    }, to)

    private fun _transmit(l: Link, bytes: ByteArray, to: SocketAddress) {
        try {
            l.socket.send(DatagramPacket(bytes, bytes.size, to))
        } catch (e: IOException) {
            Log.w(TAG, "Send on ${l.name} failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "UsbMdnsAdvertiser"
        private val USB_IFACE = Regex("(usb|rndis|ncm)\\d+")
        private const val MDNS_PORT = 5353
        private val GROUP = InetSocketAddress(InetAddress.getByName("224.0.0.251"), MDNS_PORT)
        private const val SCAN_MS = 2000L
        private const val RECEIVE_TIMEOUT_MS = 250
        private const val STOP_TIMEOUT_MS = 1000L
        private const val CHECK_MS = 2500L
        private const val CHECK_QUERY_MS = 1000L
        private const val RECHECK_MS = 15_000L
        private const val ANNOUNCE_COUNT = 3
        private const val TTL_HOST = 120
        private const val TTL_OTHER = 4500
        private const val LEGACY_TTL = 10
        private const val TYPE_A = 1
        private const val TYPE_PTR = 12
        private const val TYPE_TXT = 16
        private const val TYPE_SRV = 33
        private const val TYPE_ANY = 255
        private const val CLASS_IN = 1
        private const val CACHE_FLUSH = 0x8000
        private const val RESPONSE_FLAGS = 0x8400 // qr + aa
        private val SERVICES = listOf("_services", "_dns-sd", "_udp", "local")
    }
}

private fun _ip(a: Inet4Address) = a.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }

private fun _eq(a: List<String>, b: List<String>) =
    a.size == b.size && a.indices.all { a[it].equals(b[it], ignoreCase = true) }

private fun _rd16(d: ByteArray, i: Int) = ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)

private fun _bytes(block: ByteArrayOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().apply(block).toByteArray()

private fun ByteArrayOutputStream._u16(v: Int) { write((v shr 8) and 0xFF); write(v and 0xFF) }

private fun ByteArrayOutputStream._u32(v: Int) { _u16(v ushr 16); _u16(v and 0xFFFF) }

private fun ByteArrayOutputStream._label(s: String, max: Int) {
    val b = s.toByteArray()
    var n = minOf(b.size, max)
    if (n < b.size) while (n > 0 && (b[n].toInt() and 0xC0) == 0x80) n--
    write(n)
    write(b, 0, n)
}

private fun _name(labels: List<String>) = _bytes {
    labels.forEach { _label(it, 63) }
    write(0)
}

private fun _readName(d: ByteArray, start: Int): Pair<List<String>, Int>? {
    val labels = mutableListOf<String>()
    var pos = start
    var end = -1
    var jumps = 0
    while (true) {
        if (pos >= d.size) return null
        val len = d[pos].toInt() and 0xFF
        when {
            len == 0 -> return labels to (if (end < 0) pos + 1 else end)
            (len and 0xC0) == 0xC0 -> {
                if (pos + 1 >= d.size || ++jumps > 16) return null
                if (end < 0) end = pos + 2
                pos = ((len and 0x3F) shl 8) or (d[pos + 1].toInt() and 0xFF)
            }
            else -> {
                if (pos + 1 + len > d.size) return null
                labels += String(d, pos + 1, len)
                pos += 1 + len
            }
        }
    }
}
