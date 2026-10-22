package io.github.jqssun.airplay.service

import io.github.jqssun.airplay.Prefs
import kotlin.math.roundToInt

/**
 * What the stream profile asks the sender for: the size of the picture, the
 * frame rate and the codec.
 *
 * The rules live here and not in the service because they are the part of the
 * receiver that has to be right for the picture to be the best one the link
 * can carry: a size the panel cannot show is wasted airtime, and a codec that
 * is only good above 1080p makes the sender push 4k onto a 1080p panel.
 */
object StreamProfile {

    /** The size the sender is told about, "auto" for the panel's own size. */
    fun resolution(profile: String, resolutionPref: String): String = when (profile) {
        Prefs.PROFILE_4K30, Prefs.PROFILE_4K60 -> "3840x2160"
        Prefs.PROFILE_1080P60 -> "1920x1080"
        Prefs.PROFILE_CUSTOM -> resolutionPref
        else -> Prefs.AUTO
    }

    /**
     * The frame rate the sender is told about.
     *
     * The profile gives up the frame rate before it gives up the size, because
     * 4k30 looks better than 1080p60. But when the panel forced a smaller
     * size the frame rate can go back up: the picture is smaller now, so it
     * costs less to send it at the panel's own rate.
     */
    fun fps(profile: String, maxFps: Int, w: Int, h: Int): Int = when (profile) {
        Prefs.PROFILE_4K30 -> if (w >= 3840 && h >= 2160) 30 else maxFps
        else -> maxFps
    }

    /**
     * Whether the receiver tells the sender that it can decode hevc.
     *
     * The answer is more than a codec choice: the hevc capability is the dns-sd
     * feature bit 42, and that bit is what tells the sender that it may send
     * 4k at all. On a 1080p panel it would make the sender push a 4k mirror
     * that the panel cannot show - four times the pixels, four times the
     * decode work and no gain in the picture.
     *
     * So hevc follows the size the sender is told about, and the setting wins
     * over everything: the user knows what the tv can do.
     */
    fun hevc(h265Pref: String, w: Int, h: Int): Boolean {
        val forced = Prefs.forced(h265Pref)
        if (forced != null) return forced
        return w > 1920 || h > 1080
    }

    /**
     * Scale down to the cap without changing the aspect ratio.
     *
     * The sender builds the extended desktop from the advertised geometry, so
     * the cap has to keep the picture's shape: a stretched desktop is worse
     * than a smaller one.
     */
    fun fit(w: Int, h: Int, maxW: Int, maxH: Int): Pair<Int, Int> {
        if (w <= maxW && h <= maxH) return w to h
        val scale = minOf(maxW.toDouble() / w, maxH.toDouble() / h)
        return (w * scale).roundToInt().coerceAtLeast(1) to
            (h * scale).roundToInt().coerceAtLeast(1)
    }
}
