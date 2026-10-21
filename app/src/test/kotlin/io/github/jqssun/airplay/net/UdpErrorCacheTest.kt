package io.github.jqssun.airplay.net

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * The udp counters live in /proc/net/snmp, which selinux denies to an app on
 * many devices. Every denied read writes a line into the kernel log, so a
 * reader that asks again and again fills the logcat buffer with denials: on a
 * tv that denies it the app wrote five denial lines per second and the
 * diagnostics of the session were pushed out of the log before anyone could
 * read them.
 */
class UdpErrorCacheTest {

    @Test
    fun `the counters are read at most once a second`() {
        var clock = 0L
        var reads = 0
        val cache = UdpErrorCache({ reads++; UdpErrors(1, 2) }, { clock })

        assertEquals(UdpErrors(1, 2), cache.get())
        clock = 500
        assertEquals(UdpErrors(1, 2), cache.get())
        clock = 999
        assertEquals(UdpErrors(1, 2), cache.get())
        assertEquals(1, reads)

        clock = 1_000
        assertEquals(UdpErrors(1, 2), cache.get())
        assertEquals(2, reads)
    }

    @Test
    fun `a device that denies the read is asked once a minute, not five times a second`() {
        var clock = 0L
        var reads = 0
        val cache = UdpErrorCache({
            reads++
            throw IOException("Permission denied")
        }, { clock })

        // the read fails: the app still gets the numbers it had
        assertEquals(UdpErrors(0, 0), cache.get())
        assertEquals(1, reads)

        // every call in the next minute is answered from the cache
        for (ms in 1..60 step 100) {
            clock = ms.toLong()
            assertEquals(UdpErrors(0, 0), cache.get())
        }
        assertEquals(1, reads)

        // after a minute the device is asked again
        clock = 60_001
        cache.get()
        assertEquals(2, reads)
    }

    @Test
    fun `a read that works again is picked up`() {
        var clock = 0L
        var denied = true
        val cache = UdpErrorCache({
            if (denied) throw IOException("Permission denied")
            UdpErrors(7, 8)
        }, { clock })

        assertEquals(UdpErrors(0, 0), cache.get())
        denied = false
        clock = 60_001
        assertEquals(UdpErrors(7, 8), cache.get())
    }
}
