package com.sonora.player

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.sonora.player.playback.PlayerConnection
import com.sonora.player.ui.MusicPlayerApp
import com.sonora.player.ui.MusicPlayerTheme

class MainActivity : ComponentActivity() {
    private lateinit var connection: PlayerConnection

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The UI is always dark, so keep system bar icons light regardless of system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )

        connection = PlayerConnection(this)
        connection.connect()

        setContent {
            MusicPlayerTheme {
                MusicPlayerApp(connection)
            }
        }
    }

    override fun onDestroy() {
        connection.release()
        super.onDestroy()
    }
}
