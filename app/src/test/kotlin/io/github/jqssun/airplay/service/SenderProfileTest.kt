package io.github.jqssun.airplay.service

import io.github.jqssun.airplay.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the receiver tells the sender it is.
 *
 * The sender reads the identity of the receiver - the model name and the
 * feature bits - and picks the codec and the bitrate budget from it. A
 * receiver that announces itself as an old apple tv gets h264 and a small
 * budget, and the picture breaks into blocks as soon as the screen changes.
 */
class SenderProfileTest {

    @Test
    fun `the safe profile is what the receiver used to say`() {
        val safe = SenderProfile.forName(Prefs.SENDER_SAFE)
        assertEquals("AppleTV3,2", safe.model)
        assertEquals(0x27F, safe.infoFeatures)
        assertEquals(14, safe.displayFeatures)
        assertNull(safe.size)
        assertNull(safe.fps)
        assertFalse(safe.forceH265)
    }

    @Test
    fun `the airplay2 profile says what the dns-sd record already said`() {
        val ap2 = SenderProfile.forName(Prefs.SENDER_AIRPLAY2)
        assertEquals("AppleTV6,2", ap2.model)
        assertEquals(0x5A7FFEE6.toInt(), ap2.infoFeatures)
        assertEquals(30, ap2.displayFeatures)
        // the size stays the one of the stream profile
        assertNull(ap2.size)
        assertNull(ap2.fps)
    }

    @Test
    fun `the quality profile asks for 4k at 30 frames with hevc`() {
        val q = SenderProfile.forName(Prefs.SENDER_AIRPLAY2_4K)
        assertEquals(3840 to 2160, q.size)
        assertEquals(30, q.fps)
        assertTrue(q.forceH265)
        // hevc needs a picture above 1080p to be offered at all, so the
        // feature bit 42 is the one that has to be set
        assertEquals(0x5A7FFEE6.toInt(), q.infoFeatures)
    }

    @Test
    fun `an unknown name falls back to the safe profile`() {
        assertEquals(
            SenderProfile.forName(Prefs.SENDER_SAFE).model,
            SenderProfile.forName("something else").model
        )
    }

    @Test
    fun `the feature bits fit into the plist value the bridge sends`() {
        // the bridge sends the bits as a signed int
        for (p in SenderProfile.all()) {
            assertTrue(p.infoFeatures > 0)
        }
    }

    @Test
    fun `the feature bits are printed as they go on the wire`() {
        // the bits with the top bit set are negative as an int: without the
        // unsigned mask the hex number of the log comes out negative
        val ap2 = SenderProfile.forName(Prefs.SENDER_AIRPLAY2)
        assertEquals("0x5a7ffee6", ap2.infoFeaturesHex(hevc = false))
        // bit 42 is the hevc bit
        assertEquals("0x4005a7ffee6", ap2.infoFeaturesHex(hevc = true))
        val safe = SenderProfile.forName(Prefs.SENDER_SAFE)
        assertEquals("0x27f", safe.infoFeaturesHex(hevc = false))
        assertEquals("0x4000000027f", safe.infoFeaturesHex(hevc = true))
    }
}
