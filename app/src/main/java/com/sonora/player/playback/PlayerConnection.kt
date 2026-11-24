package com.sonora.player.playback

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.sonora.player.model.Song

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

    /** Sleep timer deadline in [SystemClock.elapsedRealtime] terms, or 0 when no timer is set. */
    var sleepEndsAt by mutableLongStateOf(0L)
        private set

    /** Equalizer state from the service (null until first loaded). */
    var eqState by mutableStateOf<EqState?>(null)
        private set

    /** Current playback queue, in play order. */
    var queue by mutableStateOf<List<QueueItem>>(emptyList())
        private set
    var currentIndex by mutableIntStateOf(-1)
        private set

    /** Increases each time a file can't be played and is skipped, so the UI can tell the user. */
    var playbackErrorCount by mutableIntStateOf(0)
        private set

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncFrom(player)
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) refreshQueue(player)
        }

        // The service skips unplayable files itself (so it works with the app closed);
        // the UI only counts them so it can tell the user.
        override fun onPlayerError(error: PlaybackException) {
            playbackErrorCount++
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
                refreshQueue(c)
                refreshSleepTimer()
                refreshEqualizer()
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
        currentIndex = p.currentMediaItemIndex
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

    /** Sets the sleep timer (minutes > 0) or cancels it (0). Playback pauses when it runs out. */
    fun setSleepTimer(minutes: Int) {
        val c = controller ?: return
        val args = Bundle().apply { putInt(MusicPlaybackService.EXTRA_MINUTES, minutes) }
        applySleepResult(c.sendCustomCommand(MusicPlaybackService.SET_SLEEP_TIMER, args))
    }

    /** Re-reads the timer from the service, e.g. after the activity was recreated. */
    fun refreshSleepTimer() {
        val c = controller ?: return
        applySleepResult(c.sendCustomCommand(MusicPlaybackService.GET_SLEEP_TIMER, Bundle.EMPTY))
    }

    /** Called by the UI once the deadline has passed (the service has paused playback). */
    fun onSleepTimerFinished() {
        sleepEndsAt = 0L
    }

    private fun applySleepResult(future: ListenableFuture<SessionResult>) {
        future.addListener({
            try {
                val result = future.get()
                if (result.resultCode == SessionResult.RESULT_SUCCESS) {
                    sleepEndsAt = result.extras.getLong(MusicPlaybackService.EXTRA_ENDS_AT, 0L)
                }
            } catch (e: Exception) {
                // Service unavailable; leave the timer state as it was.
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // ---- Queue editing ----

    private fun refreshQueue(p: Player) {
        queue = List(p.mediaItemCount) { i ->
            val item = p.getMediaItemAt(i)
            QueueItem(
                index = i,
                mediaId = item.mediaId,
                title = item.mediaMetadata.title?.toString().orEmpty(),
                artist = item.mediaMetadata.artist?.toString().orEmpty()
            )
        }
    }

    fun jumpTo(index: Int) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount) return
        c.seekTo(index, 0L)
        c.prepare()
        c.play()
    }

    fun moveQueueItem(from: Int, to: Int) {
        val c = controller ?: return
        val last = c.mediaItemCount - 1
        if (from !in 0..last || to !in 0..last || from == to) return
        c.moveMediaItem(from, to)
    }

    fun removeQueueItem(index: Int) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount || index == c.currentMediaItemIndex) return
        c.removeMediaItem(index)
    }

    /** Removes everything after the song that is playing now. */
    fun clearUpcoming() {
        val c = controller ?: return
        val from = c.currentMediaItemIndex + 1
        if (from in 1 until c.mediaItemCount) c.removeMediaItems(from, c.mediaItemCount)
    }

    // ---- Equalizer ----

    fun refreshEqualizer() {
        val c = controller ?: return
        applyEqResult(c.sendCustomCommand(MusicPlaybackService.EQ_GET, Bundle.EMPTY))
    }

    fun setEqActive(active: Boolean) {
        val c = controller ?: return
        eqState = eqState?.copy(enabled = active)
        c.sendCustomCommand(
            MusicPlaybackService.EQ_ACTIVE,
            Bundle().apply { putBoolean(MusicPlaybackService.EXTRA_ON, active) }
        )
    }

    fun setEqBand(band: Int, level: Int) {
        val c = controller ?: return
        val s = eqState ?: return
        if (band !in s.levels.indices) return
        eqState = s.copy(levels = s.levels.toMutableList().also { it[band] = level }, preset = -1)
        c.sendCustomCommand(
            MusicPlaybackService.EQ_BAND,
            Bundle().apply {
                putInt(MusicPlaybackService.EXTRA_BAND, band)
                putInt(MusicPlaybackService.EXTRA_LEVEL, level)
            }
        )
    }

    fun useEqPreset(index: Int) {
        val c = controller ?: return
        applyEqResult(
            c.sendCustomCommand(
                MusicPlaybackService.EQ_PRESET,
                Bundle().apply { putInt(MusicPlaybackService.EXTRA_PRESET, index) }
            )
        )
    }

    fun setBass(strength: Int) {
        val c = controller ?: return
        eqState = eqState?.copy(bass = strength)
        c.sendCustomCommand(
            MusicPlaybackService.EQ_BASS,
            Bundle().apply { putInt(MusicPlaybackService.EXTRA_STRENGTH, strength) }
        )
    }

    fun resetEq() {
        val c = controller ?: return
        applyEqResult(c.sendCustomCommand(MusicPlaybackService.EQ_RESET, Bundle.EMPTY))
    }

    private fun applyEqResult(future: ListenableFuture<SessionResult>) {
        future.addListener({
            try {
                val result = future.get()
                if (result.resultCode == SessionResult.RESULT_SUCCESS) {
                    eqState = result.extras.toEqState()
                }
            } catch (e: Exception) {
                // Service unavailable; keep the last known state.
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun Bundle.toEqState() = EqState(
        available = getBoolean(EqualizerController.K_AVAILABLE, false),
        enabled = getBoolean(EqualizerController.K_ON, false),
        minLevel = getInt(EqualizerController.K_MIN, -1500),
        maxLevel = getInt(EqualizerController.K_MAX, 1500),
        freqsHz = getIntArray(EqualizerController.K_FREQS)?.toList().orEmpty(),
        levels = getIntArray(EqualizerController.K_LEVELS)?.toList().orEmpty(),
        presets = getStringArrayList(EqualizerController.K_PRESETS)?.toList().orEmpty(),
        preset = getInt(EqualizerController.K_PRESET, -1),
        bassSupported = getBoolean(EqualizerController.K_BASS_SUPPORTED, false),
        bass = getInt(EqualizerController.K_BASS, 0)
    )

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
                    // Android 10+: SonoraBitmapLoader fetches the thumbnail straight from the song.
                    .setArtworkUri(if (Build.VERSION.SDK_INT >= 29) uri else artworkUri)
                    .build()
            )
            .build()
}

/** Equalizer settings as reported by the playback service. Levels are in millibels (100 = 1 dB). */
data class EqState(
    val available: Boolean,
    val enabled: Boolean,
    val minLevel: Int,
    val maxLevel: Int,
    val freqsHz: List<Int>,
    val levels: List<Int>,
    val presets: List<String>,
    /** Index into [presets], or -1 for custom. */
    val preset: Int,
    val bassSupported: Boolean,
    /** Bass boost strength, 0..1000. */
    val bass: Int
)

data class QueueItem(val index: Int, val mediaId: String, val title: String, val artist: String)
