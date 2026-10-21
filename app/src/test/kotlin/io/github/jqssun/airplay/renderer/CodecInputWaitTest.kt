package io.github.jqssun.airplay.renderer

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class CodecInputWaitTest {
    @Test fun retainsInputAcrossTransientUnavailability() {
        val monitor = Object()
        var attempts = 0
        val index = synchronized(monitor) {
            awaitCodecInput(monitor, { true }, { if (++attempts <= 80) -1 else 7 }, {})
        }
        assertEquals("The retained frame must reach the eventual input slot", 7, index)
    }

    @Test fun acceptsSlotZeroWithoutWaiting() {
        val monitor = Object()
        val index = synchronized(monitor) {
            awaitCodecInput(monitor, { true }, { 0 }, { error("Ready input must not wait") })
        }
        assertEquals(0, index)
    }

    @Test fun drainsOutputToMakeInputAvailable() {
        val monitor = Object()
        var drained = 0
        val index = synchronized(monitor) {
            awaitCodecInput(monitor, { true }, { if (drained >= 3) 2 else -1 }, { drained++ })
        }
        assertEquals(2, index)
    }

    @Test fun stopCanAcquireMonitorAndCancelUnavailableInput() {
        val monitor = Object()
        val active = AtomicBoolean(true)
        val entered = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val waiter = workers.submit<Int?> {
                synchronized(monitor) {
                    awaitCodecInput(monitor, active::get, {
                        entered.countDown()
                        Thread.sleep(1)
                        -1
                    }, {})
                }
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val stop = workers.submit {
                synchronized(monitor) {
                    active.set(false)
                    monitor.notifyAll()
                }
            }
            stop.get(1, TimeUnit.SECONDS)
            assertNull(waiter.get(1, TimeUnit.SECONDS))
        } finally {
            active.set(false)
            workers.shutdownNow()
            workers.awaitTermination(1, TimeUnit.SECONDS)
        }
    }
}
