package com.sonora.player.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.sonora.player.MainActivity
import com.sonora.player.R
import com.sonora.player.visualizer.SonoraRenderersFactory

class MusicPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var equalizer: EqualizerController? = null
    private var bitmapLoader: SonoraBitmapLoader? = null

    // Sleep timer lives here (not in the UI) so it still fires if the app is swiped away.
    private val handler = Handler(Looper.getMainLooper())
    private var sleepEndsAt = 0L // SystemClock.elapsedRealtime() deadline, 0 = off
    private val sleepRunnable = Runnable {
        mediaSession?.player?.pause()
        sleepEndsAt = 0L
    }

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val player = ExoPlayer.Builder(this, SonoraRenderersFactory(this))
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true) // pause when headphones unplug
            // Keep the CPU awake while playing local files with the screen off.
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        equalizer = EqualizerController(this, player)

        // A file that can't be played is skipped here, in the service, so it also works when the
        // app window is closed or the screen is off.
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
            }
        })

        // Same single Media3 notification as before, now with Sonora's own status-bar icon and
        // a normal-priority channel (some phones hide low-priority media notifications).
        ensurePlaybackChannel()
        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(CHANNEL_ID)
            .setChannelName(R.string.playback_channel_name)
            .build()
        notificationProvider.setSmallIcon(R.drawable.ic_stat_sonora)
        setMediaNotificationProvider(notificationProvider)

        // Tapping the notification (or the lock-screen player) opens the full Now Playing screen.
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_NOW_PLAYING, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val artworkLoader = SonoraBitmapLoader(this)
        bitmapLoader = artworkLoader

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(openAppIntent)
            .setBitmapLoader(artworkLoader)
            .setCallback(SessionCallback())
            .build()
    }

    // Silent, visible on the lock screen, and not low priority. Media3 uses this channel if it exists.
    private fun ensurePlaybackChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        try {
            // Earlier versions used Media3's default low-priority channel; drop it so only one is listed.
            manager.deleteNotificationChannel(OLD_CHANNEL_ID)
        } catch (e: SecurityException) {
            // Still attached to a running foreground notification; harmless, it just stays unused.
        }
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.playback_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            channel.setSound(null, null)
            channel.enableVibration(false)
            channel.setShowBadge(false)
            channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            manager.createNotificationChannel(channel)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    private fun setSleepTimer(minutes: Int) {
        handler.removeCallbacks(sleepRunnable)
        if (minutes <= 0) {
            sleepEndsAt = 0L
            return
        }
        val delayMs = minutes * 60_000L
        sleepEndsAt = SystemClock.elapsedRealtime() + delayMs
        handler.postDelayed(sleepRunnable, delayMs)
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SET_SLEEP_TIMER)
                .add(GET_SLEEP_TIMER)
                .add(EQ_GET)
                .add(EQ_ACTIVE)
                .add(EQ_BAND)
                .add(EQ_PRESET)
                .add(EQ_BASS)
                .add(EQ_RESET)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            val eq = equalizer
            val extras: Bundle = when (customCommand.customAction) {
                ACTION_SET_SLEEP_TIMER -> {
                    setSleepTimer(args.getInt(EXTRA_MINUTES, 0))
                    sleepExtras()
                }
                ACTION_GET_SLEEP_TIMER -> sleepExtras()
                ACTION_EQ_GET -> eqExtras()
                ACTION_EQ_ACTIVE -> {
                    eq?.setActive(args.getBoolean(EXTRA_ON, false))
                    eqExtras()
                }
                ACTION_EQ_BAND -> {
                    eq?.setBand(args.getInt(EXTRA_BAND, 0), args.getInt(EXTRA_LEVEL, 0))
                    eqExtras()
                }
                ACTION_EQ_PRESET -> {
                    eq?.usePreset(args.getInt(EXTRA_PRESET, 0))
                    eqExtras()
                }
                ACTION_EQ_BASS -> {
                    eq?.setBass(args.getInt(EXTRA_STRENGTH, 0))
                    eqExtras()
                }
                ACTION_EQ_RESET -> {
                    eq?.reset()
                    eqExtras()
                }
                else -> return Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
                )
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, extras))
        }
    }

    private fun sleepExtras(): Bundle = Bundle().apply { putLong(EXTRA_ENDS_AT, sleepEndsAt) }

    private fun eqExtras(): Bundle = equalizer?.state() ?: Bundle().apply {
        putBoolean(EqualizerController.K_AVAILABLE, false)
    }

    // Swiping the app away should stop the service unless music is actually playing.
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(sleepRunnable)
        equalizer?.release()
        equalizer = null
        bitmapLoader?.release()
        bitmapLoader = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "sonora_playback"
        private const val OLD_CHANNEL_ID = "default_channel_id"

        const val ACTION_SET_SLEEP_TIMER = "com.sonora.player.SET_SLEEP_TIMER"
        const val ACTION_GET_SLEEP_TIMER = "com.sonora.player.GET_SLEEP_TIMER"
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_ENDS_AT = "ends_at_elapsed"

        val SET_SLEEP_TIMER = SessionCommand(ACTION_SET_SLEEP_TIMER, Bundle.EMPTY)
        val GET_SLEEP_TIMER = SessionCommand(ACTION_GET_SLEEP_TIMER, Bundle.EMPTY)

        // Equalizer commands (state comes back in the result extras, see EqualizerController.state()).
        const val ACTION_EQ_GET = "com.sonora.player.EQ_GET"
        const val ACTION_EQ_ACTIVE = "com.sonora.player.EQ_ACTIVE"
        const val ACTION_EQ_BAND = "com.sonora.player.EQ_BAND"
        const val ACTION_EQ_PRESET = "com.sonora.player.EQ_PRESET"
        const val ACTION_EQ_BASS = "com.sonora.player.EQ_BASS"
        const val ACTION_EQ_RESET = "com.sonora.player.EQ_RESET"
        const val EXTRA_ON = "on"
        const val EXTRA_BAND = "band"
        const val EXTRA_LEVEL = "level"
        const val EXTRA_PRESET = "preset"
        const val EXTRA_STRENGTH = "strength"

        val EQ_GET = SessionCommand(ACTION_EQ_GET, Bundle.EMPTY)
        val EQ_ACTIVE = SessionCommand(ACTION_EQ_ACTIVE, Bundle.EMPTY)
        val EQ_BAND = SessionCommand(ACTION_EQ_BAND, Bundle.EMPTY)
        val EQ_PRESET = SessionCommand(ACTION_EQ_PRESET, Bundle.EMPTY)
        val EQ_BASS = SessionCommand(ACTION_EQ_BASS, Bundle.EMPTY)
        val EQ_RESET = SessionCommand(ACTION_EQ_RESET, Bundle.EMPTY)
    }
}
