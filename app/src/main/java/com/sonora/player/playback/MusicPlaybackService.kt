package com.sonora.player.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.sonora.player.MainActivity

class MusicPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

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

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true) // pause when headphones unplug
            // Keep the CPU awake while playing local files with the screen off.
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(openAppIntent)
            .setCallback(SessionCallback())
            .build()
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
            when (customCommand.customAction) {
                ACTION_SET_SLEEP_TIMER -> setSleepTimer(args.getInt(EXTRA_MINUTES, 0))
                ACTION_GET_SLEEP_TIMER -> Unit
                else -> return Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
                )
            }
            val extras = Bundle().apply { putLong(EXTRA_ENDS_AT, sleepEndsAt) }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, extras))
        }
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
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_SET_SLEEP_TIMER = "com.sonora.player.SET_SLEEP_TIMER"
        const val ACTION_GET_SLEEP_TIMER = "com.sonora.player.GET_SLEEP_TIMER"
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_ENDS_AT = "ends_at_elapsed"

        val SET_SLEEP_TIMER = SessionCommand(ACTION_SET_SLEEP_TIMER, Bundle.EMPTY)
        val GET_SLEEP_TIMER = SessionCommand(ACTION_GET_SLEEP_TIMER, Bundle.EMPTY)
    }
}
