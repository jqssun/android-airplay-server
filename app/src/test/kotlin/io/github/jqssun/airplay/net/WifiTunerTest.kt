package io.github.jqssun.airplay.net

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A Service is constructed before attachBaseContext runs, so the service is a
 * ContextWrapper whose base is still null at that moment. Anything that
 * dereferences the context in a constructor throws a NullPointerException
 * there - which is not caught by a try/catch further down and takes the whole
 * app down on every service start.
 *
 * The test uses the same state the platform hands to a Service, so it fails
 * with the same stack trace the crash had.
 */
class WifiTunerTest {

    /** A context in the state a Service is in before attachBaseContext. */
    private class UnattachedContext : ContextWrapper(null)

    @Test
    fun constructingTheTunerDoesNotTouchTheContext() {
        // the old code resolved the wifi service here and threw
        val tuner = WifiTuner(UnattachedContext())
        assertNull(tuner.scanThrottled())
    }

    @Test
    fun acquiringTheLockOnAnUnattachedContextDoesNotThrow() {
        // the lock is a nicety: it must never take the server down
        val result = WifiTuner(UnattachedContext()).acquire(enabled = true)
        assert(result.isNotEmpty())
    }

    @Test
    fun releasingWithoutAcquiringIsHarmless() {
        WifiTuner(UnattachedContext()).release()
    }
}
