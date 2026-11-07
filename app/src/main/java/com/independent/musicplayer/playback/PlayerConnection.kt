package com.independent.musicplayer.playback

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.independent.musicplayer.model.Song

/**
 * Wraps a Media3 [MediaController] and exposes the playback state as Compose state,
 * so the UI always reflects what the background service is really doing.
 * All methods must be called on the main thread.
 */
class PlayerConnection(private val context: Context) {
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    var isConnected by mutableStateOf(false)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var currentMediaId by mutableStateOf<String?>(null)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var shuffleEnabled by mutableStateOf(false)
        private set
    var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)
        private set

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncFrom(player)
        }
    }

    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, MusicPlaybackService::class.java))
        val pending = MediaController.Builder(context, token).buildAsync()
        future = pending
        pending.addListener({
            try {
                val c = pending.get()
                controller = c
                c.addListener(listener)
                isConnected = true
                syncFrom(c)
            } catch (e: Exception) {
                isConnected = false
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun release() {
        controller?.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        controller = null
        future = null
        isConnected = false
    }

    private fun syncFrom(p: Player) {
        isPlaying = p.isPlaying
        currentMediaId = p.currentMediaItem?.mediaId
        shuffleEnabled = p.shuffleModeEnabled
        repeatMode = p.repeatMode
        durationMs = p.duration.coerceAtLeast(0L)
        positionMs = p.currentPosition
    }

    /** Called periodically by the UI while playing to move the seek bar. */
    fun refreshPosition() {
        val c = controller ?: return
        positionMs = c.currentPosition
        durationMs = c.duration.coerceAtLeast(0L)
    }

    fun play(songs: List<Song>, startIndex: Int) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        c.setMediaItems(songs.map { it.toMediaItem() }, startIndex.coerceIn(0, songs.lastIndex), 0L)
        c.prepare()
        c.play()
    }

    fun playNext(song: Song) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) {
            play(listOf(song), 0)
        } else {
            c.addMediaItem(c.currentMediaItemIndex + 1, song.toMediaItem())
        }
    }

    fun addToQueue(song: Song) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) play(listOf(song), 0) else c.addMediaItem(song.toMediaItem())
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition(0)
            c.play()
        }
    }

    fun next() { controller?.seekToNext() }

    fun previous() { controller?.seekToPrevious() }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms)
        positionMs = ms
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    private fun Song.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setArtworkUri(artworkUri)
                    .build()
            )
            .build()
}
