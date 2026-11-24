package com.sonora.player.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Signature green. [SpotifyGreenBright] is used for highlights and buttons on dark backgrounds. */
internal val SpotifyGreen = Color(0xFF1DB954)
internal val SpotifyGreenBright = Color(0xFF1ED760)

/** Light, dark, or follow the phone's setting. */
internal enum class ThemeMode(val label: String) {
    System("System default"),
    Light("Light"),
    Dark("Dark")
}

/** The user's appearance choice, remembered between launches. */
internal object ThemeSettings {
    private const val PREFS = "sonora_theme"
    private const val KEY_MODE = "mode"

    var mode by mutableStateOf(ThemeMode.System)
        private set

    fun init(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MODE, 0)
        mode = ThemeMode.entries.getOrElse(saved) { ThemeMode.System }
    }

    fun set(context: Context, newMode: ThemeMode) {
        mode = newMode
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_MODE, newMode.ordinal).apply()
    }
}

/**
 * Semantic colours for Sonora's own screens.
 *
 * Dark: #121212 screens, #181818 cards, #282828 elevated bars, white text, #B3B3B3 secondary text.
 * Light: the same roles on white. Green is used for icons and text ([accent]) and for filled
 * buttons ([accentFill]); in light mode [accent] is a deeper green so it stays readable on white.
 * [inactive] is for disabled items and idle tracks only: #535353 is too dim for text on dark.
 */
@Immutable
internal class SonoraColors(
    val isDark: Boolean,
    val background: Color,
    val headerTint: Color,
    val card: Color,
    val elevated: Color,
    val text: Color,
    val textSecondary: Color,
    val inactive: Color,
    val accent: Color,
    val accentFill: Color,
    val onAccent: Color,
    val error: Color
)

internal val DarkSonoraColors = SonoraColors(
    isDark = true,
    background = Color(0xFF121212),
    headerTint = Color(0xFF1C2E23),
    card = Color(0xFF181818),
    elevated = Color(0xFF282828),
    text = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFB3B3B3),
    inactive = Color(0xFF535353),
    accent = SpotifyGreenBright,
    accentFill = SpotifyGreenBright,
    onAccent = Color(0xFF000000),
    error = Color(0xFFFF8A80)
)

internal val LightSonoraColors = SonoraColors(
    isDark = false,
    background = Color(0xFFFFFFFF),
    headerTint = Color(0xFFDDF4E5),
    card = Color(0xFFF3F3F3),
    elevated = Color(0xFFEAEAEA),
    text = Color(0xFF121212),
    textSecondary = Color(0xFF535353),
    inactive = Color(0xFFB3B3B3),
    accent = Color(0xFF0F7A34),
    accentFill = SpotifyGreen,
    onAccent = Color(0xFF000000),
    error = Color(0xFFB3261E)
)

internal val LocalSonoraColors = staticCompositionLocalOf { DarkSonoraColors }

internal object Sonora {
    val colors: SonoraColors
        @Composable get() = LocalSonoraColors.current
}

internal val SonoraDarkScheme = darkColorScheme(
    primary = SpotifyGreenBright,
    onPrimary = Color.Black,
    secondary = SpotifyGreen,
    onSecondary = Color.Black,
    secondaryContainer = SpotifyGreenBright,
    onSecondaryContainer = Color.Black,
    background = Color(0xFF121212),
    onBackground = Color.White,
    surface = Color(0xFF181818),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF282828),
    onSurfaceVariant = Color(0xFFB3B3B3),
    surfaceContainer = Color(0xFF181818),
    surfaceContainerHigh = Color(0xFF282828),
    surfaceContainerHighest = Color(0xFF282828),
    outline = Color(0xFF727272),
    outlineVariant = Color(0xFF535353)
)

private val SonoraLightScheme = lightColorScheme(
    primary = Color(0xFF0F7A34),
    onPrimary = Color.White,
    secondary = SpotifyGreen,
    onSecondary = Color.Black,
    secondaryContainer = SpotifyGreen,
    onSecondaryContainer = Color.Black,
    background = Color.White,
    onBackground = Color(0xFF121212),
    surface = Color.White,
    onSurface = Color(0xFF121212),
    surfaceVariant = Color(0xFFF3F3F3),
    onSurfaceVariant = Color(0xFF535353),
    surfaceContainer = Color(0xFFF3F3F3),
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color(0xFFEAEAEA),
    outline = Color(0xFF8A8A8A),
    outlineVariant = Color(0xFFD0D0D0)
)

@Composable
internal fun resolveDarkTheme(): Boolean = when (ThemeSettings.mode) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

/** Status and navigation bar icons: light on dark screens, dark on light screens. */
internal fun ComponentActivity.applySystemBars(dark: Boolean) {
    val transparent = android.graphics.Color.TRANSPARENT
    val style = if (dark) {
        SystemBarStyle.dark(transparent)
    } else {
        SystemBarStyle.light(transparent, transparent)
    }
    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
}

@Composable
fun MusicPlayerTheme(content: @Composable () -> Unit) {
    val dark = resolveDarkTheme()
    val activity = LocalContext.current as? ComponentActivity
    SideEffect { activity?.applySystemBars(dark) }
    CompositionLocalProvider(LocalSonoraColors provides if (dark) DarkSonoraColors else LightSonoraColors) {
        MaterialTheme(
            colorScheme = if (dark) SonoraDarkScheme else SonoraLightScheme,
            content = content
        )
    }
}

/**
 * The full-screen player is always dark (it takes its colours from the album art), so while it is
 * shown the system bar icons stay light, and they return to the app theme when it closes.
 */
@Composable
internal fun ForceDarkSystemBars() {
    val activity = LocalContext.current as? ComponentActivity
    val appDark = resolveDarkTheme()
    DisposableEffect(Unit) {
        activity?.applySystemBars(true)
        onDispose { activity?.applySystemBars(appDark) }
    }
}
