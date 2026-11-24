package com.sonora.player.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.sonora.player.lyrics.FetchResult
import com.sonora.player.lyrics.LyricLine
import com.sonora.player.lyrics.Lyrics
import com.sonora.player.lyrics.LyricsRepository
import com.sonora.player.lyrics.currentLineIndex
import com.sonora.player.model.Song
import com.sonora.player.playback.PlayerConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private val NightBase = Color(0xFF121212)

/**
 * Full-screen "Now playing": cover and controls on top, lyrics in a card further down the page.
 * This screen is always dark, in light mode too: it takes its colours from the album art.
 */
@Composable
fun NowPlayingScreen(connection: PlayerConnection, song: Song, onClose: () -> Unit) {
    ForceDarkSystemBars()
    CompositionLocalProvider(LocalSonoraColors provides DarkSonoraColors) {
        MaterialTheme(colorScheme = SonoraDarkScheme) {
            NowPlayingContent(connection, song, onClose)
        }
    }
}

@Composable
private fun NowPlayingContent(connection: PlayerConnection, song: Song, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { LyricsRepository(context) }

    BackHandler(onBack = onClose)

    val pageScroll = rememberScrollState()
    val lyricsCardHeight = (LocalConfiguration.current.screenHeightDp * 0.6f).dp

    // Artwork is loaded once and shared by the background and the colour theme.
    val bitmap by produceState<ImageBitmap?>(initialValue = null, song.id) {
        value = loadArtwork(context, song, 256)
    }
    val theme = rememberArtworkTheme(song, bitmap)
    var showQueue by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(0f) }

    var lyrics by remember(song.id) { mutableStateOf<Lyrics?>(null) }
    var busy by remember(song.id) { mutableStateOf(false) }
    var message by remember(song.id) { mutableStateOf<String?>(null) }

    // Saved lyrics are read from the phone's storage, so they work offline.
    LaunchedEffect(song.id) {
        lyrics = withContext(Dispatchers.IO) { repo.load(song.id) }
    }

    // Update the position more often than the main screen does so lyrics stay in time.
    LaunchedEffect(connection.isPlaying) {
        while (connection.isPlaying) {
            connection.refreshPosition()
            delay(250)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        val text = context.contentResolver.openInputStream(uri)
                            ?.bufferedReader()?.use { it.readText() }.orEmpty()
                        repo.save(song.id, text)
                    }.getOrNull()
                }
                if (saved != null) {
                    lyrics = saved
                    message = null
                } else {
                    Toast.makeText(context, "That file doesn't contain lyrics", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun findOnline() {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            when (val result = withContext(Dispatchers.IO) { repo.fetchOnline(song) }) {
                is FetchResult.Found -> lyrics = result.lyrics
                FetchResult.NotFound -> message = "No lyrics found for this song."
                FetchResult.NetworkError ->
                    message = "Couldn't reach the lyrics service. Check your internet connection."
            }
            busy = false
        }
    }

    val duration = connection.durationMs.coerceAtLeast(1L).toFloat()
    val shownPosition = (if (dragging) dragPosition else connection.positionMs.toFloat()).coerceIn(0f, duration)

    Box(
        Modifier
            .fillMaxSize()
            .background(NightBase)
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        ImmersiveBackground(bitmap, theme)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Text(
                    "Now playing",
                    color = theme.mutedText,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.size(48.dp))
            }

            // Everything below the header scrolls: cover and controls first, lyrics further down.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(pageScroll)
                    .padding(horizontal = 24.dp)
            ) {
                BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val side = minOf(maxWidth, 420.dp)
                    // The cover gently grows while music plays and shrinks when paused.
                    val artScale by animateFloatAsState(
                        targetValue = if (connection.isPlaying) 1f else 0.9f,
                        animationSpec = tween(500),
                        label = "artScale"
                    )
                    Box(
                        Modifier
                            .scale(artScale)
                            .shadow(24.dp, RoundedCornerShape(28.dp))
                    ) {
                        Crossfade(targetState = song, animationSpec = tween(600), label = "art") { s ->
                            Artwork(song = s, size = side, corner = 28.dp)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Crossfade(targetState = song, animationSpec = tween(400), label = "title") { s ->
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            s.title,
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            s.artist,
                            color = theme.mutedText,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Slider(
                    value = shownPosition,
                    onValueChange = {
                        dragging = true
                        dragPosition = it
                    },
                    onValueChangeFinished = {
                        connection.seekTo(dragPosition.toLong())
                        dragging = false
                    },
                    valueRange = 0f..duration,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                    )
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(shownPosition.toLong()), color = theme.mutedText, style = MaterialTheme.typography.labelSmall)
                    Text(formatTime(connection.durationMs), color = theme.mutedText, style = MaterialTheme.typography.labelSmall)
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { connection.toggleShuffle() }) {
                        Icon(
                            Icons.Default.Shuffle,
                            contentDescription = if (connection.shuffleEnabled) "Shuffle on" else "Shuffle off",
                            tint = if (connection.shuffleEnabled) SpotifyGreenBright else theme.mutedText
                        )
                    }
                    IconButton(onClick = { connection.previous() }) {
                        Icon(
                            Icons.Default.SkipPrevious,
                            contentDescription = "Previous",
                            tint = Color.White,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                    IconButton(
                        onClick = { connection.togglePlayPause() },
                        modifier = Modifier.size(68.dp).clip(CircleShape).background(Color.White)
                    ) {
                        Icon(
                            if (connection.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (connection.isPlaying) "Pause" else "Play",
                            tint = theme.backgroundBottom,
                            modifier = Modifier.size(38.dp)
                        )
                    }
                    IconButton(onClick = { connection.next() }) {
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = "Next",
                            tint = Color.White,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                    IconButton(onClick = { connection.cycleRepeat() }) {
                        val mode = connection.repeatMode
                        Icon(
                            if (mode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            contentDescription = when (mode) {
                                Player.REPEAT_MODE_ONE -> "Repeat one"
                                Player.REPEAT_MODE_ALL -> "Repeat all"
                                else -> "Repeat off"
                            },
                            tint = if (mode == Player.REPEAT_MODE_OFF) theme.mutedText else SpotifyGreenBright
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Jumps down to the lyrics card.
                    FilterChip(
                        selected = false,
                        onClick = { scope.launch { pageScroll.animateScrollTo(pageScroll.maxValue) } },
                        label = { Text("Lyrics") }
                    )
                    IconButton(onClick = { showQueue = true }) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Queue", tint = Color.White)
                    }
                    IconButton(onClick = { showSleep = true }) {
                        Icon(
                            Icons.Default.Timer,
                            contentDescription = "Sleep timer",
                            tint = if (connection.sleepEndsAt > 0L) SpotifyGreenBright else theme.mutedText
                        )
                    }
                }

                // Visualizer card (bars / wave / circle), switchable and remembered.
                VisualizerCard(
                    isPlaying = connection.isPlaying,
                    theme = theme,
                    modifier = Modifier.padding(top = 16.dp)
                )

                // Lyrics card: scroll the page down to reach it.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 32.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.White.copy(alpha = 0.10f))
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Lyrics",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        if (lyrics != null) {
                            TextButton(onClick = {
                                repo.delete(song.id)
                                lyrics = null
                            }) { Text("Remove lyrics") }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(lyricsCardHeight)) {
                        LyricsPane(
                            lyrics = lyrics,
                            busy = busy,
                            message = message,
                            positionMs = connection.positionMs,
                            onFind = { findOnline() },
                            onImport = { importLauncher.launch(arrayOf("*/*")) },
                            onSeek = { connection.seekTo(it) }
                        )
                    }
                }
            }
        }
    }

    if (showQueue) {
        QueueSheet(connection = connection, onDismiss = { showQueue = false })
    }
    if (showSleep) {
        SleepTimerDialog(
            active = connection.sleepEndsAt > 0L,
            onPick = { minutes ->
                connection.setSleepTimer(minutes)
                showSleep = false
            },
            onDismiss = { showSleep = false }
        )
    }
}

@Composable
private fun LyricsPane(
    lyrics: Lyrics?,
    busy: Boolean,
    message: String?,
    positionMs: Long,
    onFind: () -> Unit,
    onImport: () -> Unit,
    onSeek: (Long) -> Unit
) {
    when {
        lyrics != null && lyrics.synced.isNotEmpty() ->
            SyncedLyrics(lines = lyrics.synced, positionMs = positionMs, onSeek = onSeek)

        lyrics != null -> Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp)
        ) {
            Text(
                lyrics.plain,
                color = Color.White,
                fontSize = 20.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        else -> Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("No lyrics saved for this song", color = Color.White, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Find them online once and they're kept on your phone for offline use.",
                color = Muted,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(16.dp))
            if (busy) {
                CircularProgressIndicator(color = Accent)
            } else {
                Button(
                    onClick = onFind,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = NightBase)
                ) { Text("Find lyrics online") }
                TextButton(onClick = onImport) { Text("Import .lrc file") }
            }
            if (message != null) {
                Text(
                    message,
                    color = Muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun SyncedLyrics(lines: List<LyricLine>, positionMs: Long, onSeek: (Long) -> Unit) {
    val activeIndex = currentLineIndex(lines, positionMs)
    val listState = rememberLazyListState()

    // Keep the current line about a third of the way down the pane.
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) {
            listState.animateScrollToItem(
                activeIndex,
                -(listState.layoutInfo.viewportSize.height / 3)
            )
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(vertical = 120.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        itemsIndexed(lines) { index, line ->
            val active = index == activeIndex
            val alpha by animateFloatAsState(
                targetValue = when {
                    active -> 1f
                    abs(index - activeIndex) == 1 -> 0.55f
                    else -> 0.35f
                },
                label = "lineAlpha"
            )
            val size by animateFloatAsState(if (active) 26f else 22f, label = "lineSize")
            Text(
                text = line.text.ifBlank { "♪" },
                color = Color.White.copy(alpha = alpha),
                fontSize = size.sp,
                lineHeight = (size * 1.3f).sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSeek(line.timeMs) }
                    .padding(vertical = 10.dp)
            )
        }
    }
}

/**
 * Blurred cover art plus two slowly drifting glows, coloured by the artwork theme.
 * The glows are brightness-capped by the theme so text on top stays readable.
 */
@Composable
private fun ImmersiveBackground(bitmap: ImageBitmap?, theme: ArtworkTheme) {
    val transition = rememberInfiniteTransition(label = "background")
    val p1 = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(15000, easing = LinearEasing), RepeatMode.Reverse),
        label = "p1"
    )
    val p2 = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(19000, easing = LinearEasing), RepeatMode.Reverse),
        label = "p2"
    )

    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(listOf(theme.backgroundTop, theme.backgroundBottom)))
        }
        Crossfade(targetState = bitmap, animationSpec = tween(900), label = "backgroundArt") { image ->
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().blur(40.dp).alpha(0.25f)
                )
            } else {
                Box(Modifier.fillMaxSize())
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.maxDimension * 0.75f
            drawRect(
                Brush.radialGradient(
                    colors = listOf(theme.glowA.copy(alpha = 0.5f), Color.Transparent),
                    center = Offset(size.width * (0.1f + 0.8f * p1.value), size.height * (0.1f + 0.35f * p2.value)),
                    radius = radius
                )
            )
            drawRect(
                Brush.radialGradient(
                    colors = listOf(theme.glowB.copy(alpha = 0.42f), Color.Transparent),
                    center = Offset(size.width * (0.9f - 0.8f * p2.value), size.height * (0.9f - 0.4f * p1.value)),
                    radius = radius
                )
            )
            // Darken the lower part so the controls and lyrics stay readable.
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, theme.backgroundBottom.copy(alpha = 0.8f))))
        }
    }
}
