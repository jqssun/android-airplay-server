package io.github.jqssun.airplay.bridge

object NativeBridge {
    init {
        System.loadLibrary("airplay_native")
    }

    external fun nativeInit(
        callback: RaopCallbackHandler,
        hwAddr: ByteArray,
        name: String,
        keyFile: String,
        nohold: Boolean,
        requirePin: Boolean
    ): Long

    external fun nativeStart(handle: Long, port: Int): Int
    external fun nativeStop(handle: Long)
    external fun nativeDestroy(handle: Long)

    external fun nativeSetDisplaySize(handle: Long, w: Int, h: Int, fps: Int)
    external fun nativeSetPlist(handle: Long, key: String, value: Int)
    external fun nativeSetH265Enabled(handle: Long, enabled: Boolean)
    external fun nativeSetCodecs(handle: Long, alac: Boolean, aac: Boolean)
    external fun nativeSetHlsEnabled(handle: Long, enabled: Boolean)

    // AirPlay Video (HLS) playback info snapshot, polled by native httpd thread
    external fun nativeUpdatePlaybackInfo(
        handle: Long, position: Float, duration: Float, rate: Float, readyToPlay: Boolean
    )

    /**
     * Milliseconds since the sender last made a request touching the AirPlay Video
     * session (POST /play, /rate, /scrub, /stop, GET /playback-info polls, playlist
     * actions), or -1 if none has arrived since native init. Senders poll GET
     * /playback-info continuously while a video session is mounted -- including
     * while paused -- so a long silence here while playback isn't progressing means
     * the sender abandoned the session (see AirPlayService's sender-liveness
     * watchdog).
     */
    external fun nativeMsSinceVideoRequest(handle: Long): Long

    /**
     * Running count of video-session requests from the sender since native init
     * (dominated by GET /playback-info polls). Used for throttled poll-activity
     * logging so real-device traces show whether a sender is still polling.
     */
    external fun nativeVideoRequestCount(handle: Long): Long

    external fun nativeGetRaopTxtRecords(handle: Long): Map<String, String>?
    external fun nativeGetAirplayTxtRecords(handle: Long): Map<String, String>?
    external fun nativeGetRaopServiceName(handle: Long): String?
    external fun nativeGetServerName(handle: Long): String?

    // software alac decoder
    external fun nativeAlacInit(frameLength: Int, numChannels: Int, bitDepth: Int,
                                pb: Int, mb: Int, kb: Int): Long
    external fun nativeAlacDecode(handle: Long, input: ByteArray): ByteArray?
    external fun nativeAlacDestroy(handle: Long)
}
