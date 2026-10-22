package io.github.jqssun.airplay.ui

import org.junit.Assert.*
import org.junit.Test

class NativeVideoSurfaceTest {
    @Test fun sonyFullscreenKeeps4kBuffers() {
        assertEquals(3840 to 2160, nativeVideoBufferSize(1920, 1080, 1920, 1080, 3840, 2160))
    }
    @Test fun portraitMirrorKeepsItsReceivedDetail() {
        assertEquals(994 to 2160, nativeVideoBufferSize(497, 1080, 1920, 1080, 3840, 2160))
    }
    @Test fun previewUsesOnlyItsVisiblePanelArea() {
        assertEquals(960 to 540, nativeVideoBufferSize(480, 270, 1920, 1080, 3840, 2160))
    }
    @Test fun native1080pDisplayIsNotForcedTo4k() {
        assertEquals(1920 to 1080, nativeVideoBufferSize(1920, 1080, 1920, 1080, 1920, 1080))
    }
    @Test fun zeroLayoutWaitsForMeasuredDimensions() {
        assertNull(nativeVideoBufferSize(0, 0, 1920, 1080, 3840, 2160))
    }
    @Test fun rotatedDisplayUsesOrientedPanelDimensions() {
        assertEquals(2160 to 3840, nativeVideoBufferSize(1080, 1920, 1080, 1920, 2160, 3840))
    }
}
