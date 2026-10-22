package io.github.jqssun.airplay.renderer

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoViewportTest {
    @Test fun ultrawideMirrorKeepsEverySourcePixelInsideFourKPanel() {
        assertEquals(VideoViewport(0, 196, 3840, 1768), fitVideoViewport(3840, 1768, 3840, 2160))
    }

    @Test fun portraitMirrorUsesPillarboxingWithoutStretch() {
        assertEquals(VideoViewport(1423, 0, 994, 2160), fitVideoViewport(994, 2160, 3840, 2160))
    }

    @Test fun standardLandscapeFillsPanelAndSmallPreview() {
        assertEquals(VideoViewport(0, 0, 3840, 2160), fitVideoViewport(1920, 1080, 3840, 2160))
        assertEquals(VideoViewport(0, 0, 640, 360), fitVideoViewport(3840, 2160, 640, 360))
    }

    @Test fun missingSourceSizeUsesWholeSurface() {
        assertEquals(VideoViewport(0, 0, 1920, 1080), fitVideoViewport(0, 0, 1920, 1080))
    }

    @Test fun landscapeFillRemovesTheExtraTopAndBottomBarsWithoutStretching() {
        val frame = videoFraming(3840, 1768, 3840, 2160, true)
        assertEquals(VideoViewport(0, 0, 3840, 2160), frame.viewport)
        assertEquals(0.09074074f, frame.crop.left, 0.00001f)
        assertEquals(0.90925926f, frame.crop.right, 0.00001f)
        assertEquals(0f, frame.crop.bottom)
        assertEquals(1f, frame.crop.top)
    }

    @Test fun fillDoesNotCropPortraitOrChangeNormalSixteenByNineVideo() {
        assertEquals(videoFraming(994, 2160, 3840, 2160, false), videoFraming(994, 2160, 3840, 2160, true))
        assertEquals(VideoCrop(), videoFraming(3840, 2160, 3840, 2160, true).crop)
    }

    @Test fun switchingBackFromWideAndPortraitRestoresTheEntirePanel() {
        for (fill in listOf(false, true)) {
            repeat(4) {
                videoFraming(3840, 1768, 3840, 2160, fill)
                videoFraming(994, 2160, 3840, 2160, fill)
                assertEquals(
                    VideoFraming(VideoViewport(0, 0, 3840, 2160)),
                    videoFraming(3840, 2160, 3840, 2160, fill)
                )
            }
        }
    }
}
