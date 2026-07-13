package io.github.jqssun.airplay.bridge

interface RaopCallbackHandler {
    fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean)
    fun onAudioData(data: ByteArray, ct: Int, ntpTimeNs: Long, seqNum: Int)
    fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean)
    fun onVideoSize(srcW: Float, srcH: Float, w: Float, h: Float)
    fun onVolumeChange(volume: Float)
    fun onConnectionInit()
    fun onConnectionDestroy()
    fun onConnectionReset(reason: Int)
    fun onDisplayPin(pin: String)
    fun onMetadata(data: ByteArray)
    fun onCoverArt(data: ByteArray)
    fun onProgress(start: Long, curr: Long, end: Long)
    fun onDacpId(dacpId: String, activeRemote: String)
    fun onAudioOnly(audioOnly: Boolean)

    // AirPlay Video: a separate HTTP/HLS protocol used by apps like YouTube's or
    // Safari's native AirPlay button, distinct from Screen Mirroring and RAOP audio.
    fun onVideoPlay(location: String, startPositionSeconds: Float)
    fun onVideoScrub(positionSeconds: Float)
    fun onVideoRate(rate: Float)
    fun onVideoStop()
    // fired per sender GET /playback-info poll; polling starts before /play
    fun onVideoSessionPoll()
}
