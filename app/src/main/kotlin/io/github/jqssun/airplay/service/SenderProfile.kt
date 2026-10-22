package io.github.jqssun.airplay.service

import io.github.jqssun.airplay.Prefs

/**
 * What the receiver tells the sender it is.
 *
 * The sender does not only mirror what it is told to mirror: it also reads
 * the identity of the receiver - the model name and the feature bits - and
 * picks the codec and the bitrate budget from it. A receiver that announces
 * itself as an old apple tv gets h264 and a small budget, and the picture
 * breaks into blocks as soon as the screen changes.
 *
 * So the identity is a setting of its own, and the log says what was
 * announced.
 */
data class SenderProfile(
    /** the name in the pref */
    val name: String,
    /** the model name in the dns-sd records and in the info responses */
    val model: String,
    /**
     * the feature bits of the info response: a bit pattern, not a count, so
     * it is read and written with bit operations only. the value with the
     * top bit set is negative as an int, which is fine here.
     */
    val infoFeatures: Int,
    /** the feature bits of the display entry of the info response */
    val displayFeatures: Int,
    /** the size the sender is told about, null keeps the size of the profile */
    val size: Pair<Int, Int>? = null,
    /** the frame rate the sender is told about, null keeps the rate of the profile */
    val fps: Int? = null,
    /** the hevc capability bit is set even when the size says 1080p */
    val forceH265: Boolean = false
) {
    /**
     * The feature bits as they go on the wire: the bits of the profile, with
     * the hevc bit added when hevc is on.
     *
     * The bits with the top bit set are negative as an int, so they are
     * taken as unsigned before they are printed - otherwise the hex number
     * of the log comes out negative and cannot be compared with the value
     * on the wire.
     */
    fun infoFeaturesHex(hevc: Boolean): String {
        val bits = (infoFeatures.toLong() and 0xFFFFFFFFL) or (if (hevc) 1L shl 42 else 0L)
        return "0x" + bits.toString(16)
    }
    companion object {
        /**
         * The feature bits of a receiver that can do everything: the same set
         * the dns-sd record advertises. The info response used to carry the
         * much smaller 0x27F, and a sender that reads it sees a receiver that
         * cannot take hevc and has to be treated with a small bitrate.
         */
        const val FEATURES_FULL = 0x5A7FFEE6.toInt()

        /** the feature bits of an airplay 1 receiver */
        const val FEATURES_MINIMAL = 0x27F

        /** the display feature bits of a plain receiver */
        const val DISPLAY_FEATURES_PLAIN = 14

        /** the display feature bits of a receiver that can take hevc */
        const val DISPLAY_FEATURES_HEVC = 30

        /** an apple tv 3 of 2012: 1080p, h264, no hevc */
        const val MODEL_LEGACY = "AppleTV3,2"

        /** an apple tv 4k of 2015: hevc and a bigger bitrate budget */
        const val MODEL_AIRPLAY2 = "AppleTV6,2"

        private val ALL = listOf(
            // what the receiver used to say: nothing changes for the user
            // who does not touch the setting
            SenderProfile(
                name = Prefs.SENDER_SAFE,
                model = MODEL_LEGACY,
                infoFeatures = FEATURES_MINIMAL,
                displayFeatures = DISPLAY_FEATURES_PLAIN
            ),
            // the same receiver, but it says what the dns-sd record already
            // said: a modern airplay 2 receiver
            SenderProfile(
                name = Prefs.SENDER_AIRPLAY2,
                model = MODEL_AIRPLAY2,
                infoFeatures = FEATURES_FULL,
                displayFeatures = DISPLAY_FEATURES_HEVC
            ),
            // the quality profile: hevc needs a picture above 1080p to be
            // offered at all, the tv downscales it again
            SenderProfile(
                name = Prefs.SENDER_AIRPLAY2_4K,
                model = MODEL_AIRPLAY2,
                infoFeatures = FEATURES_FULL,
                displayFeatures = DISPLAY_FEATURES_HEVC,
                size = 3840 to 2160,
                fps = 30,
                forceH265 = true
            )
        )

        fun all(): List<SenderProfile> = ALL

        fun forName(name: String): SenderProfile = ALL.firstOrNull { it.name == name } ?: ALL.first()
    }
}
