package com.sonora.player

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import com.sonora.player.playback.PlayerConnection
import com.sonora.player.ui.MusicPlayerApp
import com.sonora.player.ui.MusicPlayerTheme
import com.sonora.player.ui.ThemeSettings

class MainActivity : ComponentActivity() {
    private lateinit var connection: PlayerConnection

    // Counts notification taps; the UI opens Now Playing whenever this goes up.
    private val openNowPlaying = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light, dark or system theme is chosen in the app; the theme also sets the system bar style.
        ThemeSettings.init(this)
        enableEdgeToEdge()

        connection = PlayerConnection(this)
        connection.connect()

        // Only on a fresh start, so rotating the phone doesn't re-open the player.
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            MusicPlayerTheme {
                MusicPlayerApp(connection, openNowPlayingRequest = openNowPlaying.intValue)
            }
        }
    }

    // The activity is single-top: tapping the notification while Sonora is open lands here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_NOW_PLAYING, false) == true) {
            openNowPlaying.intValue++
        }
    }

    override fun onDestroy() {
        connection.release()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_OPEN_NOW_PLAYING = "com.sonora.player.OPEN_NOW_PLAYING"
    }
}
