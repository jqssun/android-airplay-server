package io.github.jqssun.airplay.audio

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

// exposes sender's audio session as media3 player
@androidx.annotation.OptIn(UnstableApi::class)
class DacpPlayer(
    looper: Looper,
    private val dacp: () -> DacpController?,
    private val snapshot: () -> Snapshot,
    private val positionMs: () -> Long,
    private val setPlaying: (Boolean) -> Unit,
) : SimpleBasePlayer(looper) {

    data class Snapshot(
        val track: TrackInfo,
        val artworkData: ByteArray?,
        val durationMs: Long,
        val playing: Boolean,
        val active: Boolean,
    )

    // must be called on the constructor looper
    fun refresh() = invalidateState()

    override fun getState(): State {
        val s = snapshot()
        if (!s.active) {
            return State.Builder().setAvailableCommands(Player.Commands.EMPTY).build()
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(s.track.title)
            .setArtist(s.track.artist)
            .setAlbumTitle(s.track.album)
            .apply {
                s.artworkData?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
            }
            .build()
        return State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder()
                    .addAll(
                        Player.COMMAND_PLAY_PAUSE,
                        Player.COMMAND_SEEK_TO_NEXT,
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_PREVIOUS,
                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_GET_TIMELINE,
                        Player.COMMAND_GET_METADATA,
                    )
                    .build()
            )
            .setPlaylist(
                listOf(
                    MediaItemData.Builder(UID_PREVIOUS).build(),
                    MediaItemData.Builder(UID_CURRENT)
                        .setMediaMetadata(metadata)
                        .setDurationUs(if (s.durationMs > 0) s.durationMs * 1000 else C.TIME_UNSET)
                        .setIsSeekable(false)
                        .build(),
                    MediaItemData.Builder(UID_NEXT).build(),
                )
            )
            .setCurrentMediaItemIndex(1)
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(s.playing, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setContentPositionMs(PositionSupplier { positionMs() })
            // seekToPrevious must always mean "previous item", never "restart current"
            .setMaxSeekToPreviousPositionMs(Long.MAX_VALUE)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        val dacp = dacp() ?: return Futures.immediateVoidFuture()
        return Futures.transform(
            if (playWhenReady) dacp.play() else dacp.pause(),
            { setPlaying(playWhenReady) },
            MoreExecutors.directExecutor()
        )
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val dacp = dacp() ?: return Futures.immediateVoidFuture()
        return when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ->
                dacp.nextItem()
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ->
                dacp.prevItem()
            else -> Futures.immediateVoidFuture()
        }
    }

    private companion object {
        const val UID_PREVIOUS = "previous"
        const val UID_CURRENT = "current"
        const val UID_NEXT = "next"
    }
}
