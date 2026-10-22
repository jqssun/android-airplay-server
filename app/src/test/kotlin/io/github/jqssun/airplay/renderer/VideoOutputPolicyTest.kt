package io.github.jqssun.airplay.renderer

import org.junit.Assert.*
import org.junit.Test

class VideoOutputPolicyTest {
    @Test fun normalDecodeReorderingIsRendered() {
        val policy = VideoOutputPolicy()
        policy.queued(1_000_000)
        assertTrue(policy.shouldRender(966_667))
    }
    @Test fun secondsOfOldPicturesAreClassifiedAsStale() {
        val policy = VideoOutputPolicy()
        policy.queued(4_000_000)
        assertFalse(policy.shouldRender(1_000_000))
        assertEquals(3_000_000L, policy.backlogUs(1_000_000))
        assertTrue(policy.shouldRender(3_966_667))
    }
    @Test fun outOfOrderInputDoesNotMoveTheLatestTimestampBackwards() {
        val policy = VideoOutputPolicy()
        policy.queued(4_000_000)
        policy.queued(1_000_000)
        assertFalse(policy.shouldRender(1_000_000))
    }
    @Test fun sessionResetDoesNotDropPicturesFromANewClock() {
        val policy = VideoOutputPolicy()
        policy.queued(4_000_000)
        policy.reset()
        policy.queued(100_000)
        assertTrue(policy.shouldRender(100_000))
    }
}
