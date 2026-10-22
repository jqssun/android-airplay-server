package io.github.jqssun.airplay.service

import io.github.jqssun.airplay.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide what the sender is told: the size of the picture, the
 * frame rate and the codec.
 *
 * The hevc rule is the one that costs something when it is wrong: the hevc
 * capability is the dns-sd feature bit 42, and that bit is what lets the
 * sender push a 4k mirror. On a 1080p panel that is four times the pixels for
 * a picture the panel cannot show.
 */
class StreamProfileTest {

    @Test
    fun `the auto profile leaves the size to the panel`() {
        assertEquals(Prefs.AUTO, StreamProfile.resolution(Prefs.PROFILE_AUTO, Prefs.AUTO))
        assertEquals(Prefs.AUTO, StreamProfile.resolution(Prefs.PROFILE_AUTO, "3840x2160"))
    }

    @Test
    fun `the 4k profiles ask for 4k, the custom one asks for the setting`() {
        assertEquals("3840x2160", StreamProfile.resolution(Prefs.PROFILE_4K30, Prefs.AUTO))
        assertEquals("3840x2160", StreamProfile.resolution(Prefs.PROFILE_4K60, Prefs.AUTO))
        assertEquals("1920x1080", StreamProfile.resolution(Prefs.PROFILE_1080P60, Prefs.AUTO))
        assertEquals("1280x720", StreamProfile.resolution(Prefs.PROFILE_CUSTOM, "1280x720"))
    }

    @Test
    fun `the 4k30 profile asks for 30 frames, the others for the setting`() {
        assertEquals(30, StreamProfile.fps(Prefs.PROFILE_4K30, 60, 3840, 2160))
        assertEquals(60, StreamProfile.fps(Prefs.PROFILE_4K60, 60, 3840, 2160))
        assertEquals(60, StreamProfile.fps(Prefs.PROFILE_AUTO, 60, 1920, 1080))
        assertEquals(24, StreamProfile.fps(Prefs.PROFILE_CUSTOM, 24, 1280, 720))
    }

    @Test
    fun `the frame rate goes back up when the panel made the picture smaller`() {
        // the panel showed 1080p, so the 30 frames of the 4k profile are not
        // needed any more
        assertEquals(60, StreamProfile.fps(Prefs.PROFILE_4K30, 60, 1920, 1080))
    }

    @Test
    fun `hevc is off below 1080p, so the sender does not push 4k onto a small panel`() {
        assertFalse(StreamProfile.hevc(Prefs.AUTO, 1920, 1080))
        assertFalse(StreamProfile.hevc(Prefs.AUTO, 1280, 720))
    }

    @Test
    fun `hevc is on above 1080p, where it halves the bitrate`() {
        assertTrue(StreamProfile.hevc(Prefs.AUTO, 3840, 2160))
        assertTrue(StreamProfile.hevc(Prefs.AUTO, 2560, 1440))
    }

    @Test
    fun `the setting wins over the size`() {
        assertTrue(StreamProfile.hevc(Prefs.ON, 1920, 1080))
        assertFalse(StreamProfile.hevc(Prefs.OFF, 3840, 2160))
    }

    @Test
    fun `an unknown setting falls back to the size`() {
        assertFalse(StreamProfile.hevc("maybe", 1920, 1080))
        assertTrue(StreamProfile.hevc("maybe", 3840, 2160))
    }

    @Test
    fun `the size is capped by the panel without changing its shape`() {
        // 4k on a 1080p panel: the sender gets 1080p, the panel's own size
        assertEquals(1920 to 1080, StreamProfile.fit(3840, 2160, 1920, 1080))
        // a picture that is not 16:9 keeps its aspect ratio
        assertEquals(1920 to 1080, StreamProfile.fit(2560, 1440, 1920, 1080))
        assertEquals(1440 to 1080, StreamProfile.fit(4000, 3000, 1920, 1080))
    }

    @Test
    fun `a size that fits is left alone`() {
        assertEquals(1920 to 1080, StreamProfile.fit(1920, 1080, 1920, 1080))
        assertEquals(1280 to 720, StreamProfile.fit(1280, 720, 4096, 2176))
    }
}
