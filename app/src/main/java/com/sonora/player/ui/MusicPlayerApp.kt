package com.sonora.player.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.LruCache
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.Player
import com.sonora.player.convert.ConversionJob
import com.sonora.player.convert.ConversionManager
import com.sonora.player.convert.ConversionStatus
import com.sonora.player.data.UserLibraryStore
import com.sonora.player.library.FileImporter
import com.sonora.player.library.LocalMusicRepository
import com.sonora.player.model.Song
import com.sonora.player.playback.PlayerConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal val Accent = Color(0xFF9AB8FF)
internal val Muted = Color.White.copy(alpha = 0.55f)

private enum class Section(val label: String) {
    ALL("All"),
    FAVOURITES("Favourites"),
    RECENT("Recent"),
    PLAYLISTS("Playlists"),
    CONVERT("Convert")
}

private data class NewPlaylistRequest(val songToAdd: Song?)

@Composable
fun MusicPlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            secondary = Color(0xFFB7A4FF),
            background = Color(0xFF090B12),
            surface = Color(0xFF171B28)
        ),
        content = content
    )
}

@Composable
fun MusicPlayerApp(connection: PlayerConnection) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { UserLibraryStore(context) }
    val repository = remember { LocalMusicRepository(context) }

    val audioPermission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val requiredPermissions = remember {
        buildList {
            add(audioPermission)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var deviceSongs by remember { mutableStateOf(emptyList<Song>()) }
    var importedSongs by remember { mutableStateOf(store.loadImported()) }
    // Files picked into playlists are part of the library too.
    val songs = remember(deviceSongs, importedSongs) {
        (deviceSongs + importedSongs).sortedBy { it.title.lowercase() }
    }
    var loading by remember { mutableStateOf(false) }
    var permissionGranted by remember { mutableStateOf(hasPermission(context, audioPermission)) }
    var query by rememberSaveable { mutableStateOf("") }
    var section by rememberSaveable(
        stateSaver = Saver<Section, String>(
            save = { it.name },
            restore = { Section.valueOf(it) }
        )
    ) { mutableStateOf(Section.ALL) }
    var openPlaylist by rememberSaveable { mutableStateOf<String?>(null) }
    var favourites by remember { mutableStateOf(store.loadFavourites()) }
    var playlists by remember { mutableStateOf(store.loadPlaylists()) }
    var history by remember { mutableStateOf(store.loadHistory()) }
    var playlistPickerFor by remember { mutableStateOf<Song?>(null) }
    var newPlaylistRequest by remember { mutableStateOf<NewPlaylistRequest?>(null) }
    var playlistToDelete by remember { mutableStateOf<String?>(null) }
    var bitrate by rememberSaveable { mutableIntStateOf(192) }
    var showEqualizer by rememberSaveable { mutableStateOf(false) }
    var showNowPlaying by rememberSaveable { mutableStateOf(false) }
    var selectedForConvert by remember { mutableStateOf(emptySet<Long>()) }

    fun reload() {
        scope.launch {
            loading = true
            deviceSongs = withContext(Dispatchers.IO) {
                runCatching { repository.loadSongs() }.getOrDefault(emptyList())
            }
            loading = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionGranted = result[audioPermission] == true || hasPermission(context, audioPermission)
        if (permissionGranted) reload()
    }

    // Pick any audio files (Opus voice notes, M4A, OGG, FLAC...) to convert to MP3.
    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            ConversionManager.enqueue(
                context,
                uris.map { it to displayNameOf(context, it) },
                bitrate
            )
        }
    }

    // Pick audio files from anywhere on the device and add them to the playlist that is open.
    val importToPlaylist = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val target = openPlaylist
        if (target != null && uris.isNotEmpty()) {
            scope.launch {
                val imported = withContext(Dispatchers.IO) {
                    uris.mapNotNull { runCatching { FileImporter.read(context, it) }.getOrNull() }
                }
                if (imported.isNotEmpty()) {
                    store.addImported(imported)
                    importedSongs = store.loadImported()
                    var updated = playlists
                    imported.forEach { updated = store.addToPlaylist(target, it.id) }
                    playlists = updated
                }
                val skipped = uris.size - imported.size
                val message = when {
                    imported.isEmpty() -> "Couldn't read those files as audio"
                    skipped > 0 -> "Added ${imported.size} files, $skipped couldn't be read"
                    else -> "Added ${imported.size} ${if (imported.size == 1) "file" else "files"} to $target"
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Show newly converted files in the library as soon as they are saved.
    val completedConversions = ConversionManager.completedCount
    LaunchedEffect(completedConversions) {
        if (completedConversions > 0 && permissionGranted) {
            delay(800)
            reload()
        }
    }

    // Rescan whenever the app comes back to the foreground so new files show up.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permissionGranted = hasPermission(context, audioPermission)
        if (permissionGranted) reload()
    }

    // Ask for anything missing (audio access, plus notifications on Android 13+).
    LaunchedEffect(Unit) {
        val missing = requiredPermissions.filter { !hasPermission(context, it) }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    // Move the seek bar while music is playing.
    LaunchedEffect(connection.isPlaying) {
        while (connection.isPlaying) {
            connection.refreshPosition()
            delay(500)
        }
    }

    val songsById = remember(songs) { songs.associateBy { it.id } }
    val currentId = connection.currentMediaId?.toLongOrNull()
    val currentSong = currentId?.let { songsById[it] }

    // Tell the user when a file couldn't be played and was skipped.
    val playbackErrors = connection.playbackErrorCount
    LaunchedEffect(playbackErrors) {
        if (playbackErrors > 0) {
            Toast.makeText(context, "Couldn't play that file, skipping it", Toast.LENGTH_SHORT).show()
        }
    }

    // Record play history whenever the track changes.
    LaunchedEffect(currentId) {
        if (currentId != null) history = store.addToHistory(currentId)
    }

    BackHandler(enabled = openPlaylist != null) { openPlaylist = null }

    val visibleSongs = remember(songs, section, query, favourites, history, openPlaylist, playlists) {
        val base = when (section) {
            Section.ALL -> songs
            Section.FAVOURITES -> songs.filter { it.id in favourites }
            Section.RECENT -> history.mapNotNull { songsById[it] }
            Section.PLAYLISTS -> openPlaylist
                ?.let { name -> playlists[name].orEmpty().mapNotNull { songsById[it] } }
                ?: emptyList()
            Section.CONVERT -> songs.filter { it.format != "MP3" }
        }
        if (query.isBlank()) base else base.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.artist.contains(query, ignoreCase = true) ||
                it.album.contains(query, ignoreCase = true)
        }
    }

    fun playFrom(list: List<Song>, song: Song) {
        connection.play(list, list.indexOfFirst { it.id == song.id }.coerceAtLeast(0))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF151B2C), Color(0xFF090B12), Color(0xFF101323))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("SONORA", color = Accent, style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Library",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                Row {
                    IconButton(onClick = { showEqualizer = true }) {
                        Icon(Icons.Default.Tune, contentDescription = "Equalizer", tint = Color.White)
                    }
                    IconButton(onClick = {
                        if (permissionGranted) reload()
                        else permissionLauncher.launch(requiredPermissions.toTypedArray())
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh library", tint = Color.White)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Search songs, artists, albums") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                shape = RoundedCornerShape(18.dp)
            )

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Section.entries.forEach { s ->
                    FilterChip(
                        selected = section == s,
                        onClick = {
                            section = s
                            openPlaylist = null
                        },
                        label = { Text(s.label) }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    section == Section.CONVERT -> ConvertScreen(
                        songs = visibleSongs,
                        selected = selectedForConvert,
                        bitrate = bitrate,
                        jobs = ConversionManager.jobs,
                        onBitrate = { bitrate = it },
                        onToggle = { id ->
                            selectedForConvert =
                                if (id in selectedForConvert) selectedForConvert - id else selectedForConvert + id
                        },
                        onSelectAll = {
                            val visibleIds = visibleSongs.map { it.id }.toSet()
                            selectedForConvert =
                                if (visibleIds.isNotEmpty() && selectedForConvert.containsAll(visibleIds)) {
                                    selectedForConvert - visibleIds
                                } else {
                                    selectedForConvert + visibleIds
                                }
                        },
                        onPickFiles = { pickFiles.launch(arrayOf("audio/*", "application/ogg")) },
                        onConvertSelected = {
                            val chosen = visibleSongs.filter { it.id in selectedForConvert }
                            ConversionManager.enqueue(
                                context,
                                chosen.map { it.uri to it.displayName },
                                bitrate
                            )
                            selectedForConvert = emptySet()
                        },
                        onClearFinished = { ConversionManager.clearFinished() }
                    )

                    !permissionGranted -> InfoCard(
                        title = "Find music on this device",
                        message = "Allow audio access to scan and play your local music files.",
                        actionLabel = "Allow audio access",
                        onAction = { permissionLauncher.launch(requiredPermissions.toTypedArray()) }
                    )

                    section == Section.PLAYLISTS && openPlaylist == null -> PlaylistList(
                        playlists = playlists,
                        onOpen = { openPlaylist = it },
                        onDelete = { playlistToDelete = it },
                        onCreate = { newPlaylistRequest = NewPlaylistRequest(null) }
                    )

                    else -> Column {
                        val playlistName = openPlaylist
                        if (section == Section.PLAYLISTS && playlistName != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { openPlaylist = null }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back to playlists",
                                        tint = Color.White
                                    )
                                }
                                Text(
                                    playlistName,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { importToPlaylist.launch(arrayOf("audio/*")) }) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = "Add files from storage",
                                        tint = Accent
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        visibleSongs.firstOrNull()?.let { playFrom(visibleSongs, it) }
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = "Play playlist",
                                        tint = Accent
                                    )
                                }
                            }
                        } else {
                            Text(
                                if (loading) "Scanning…" else "${visibleSongs.size} songs",
                                color = Color.LightGray,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }

                        if (visibleSongs.isEmpty()) {
                            InfoCard(
                                title = emptyTitle(section, query, songs.isEmpty()),
                                message = emptyMessage(section, query, songs.isEmpty())
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(visibleSongs, key = { it.id }) { song ->
                                    SongRow(
                                        song = song,
                                        isCurrent = song.id == currentId,
                                        isPlaying = song.id == currentId && connection.isPlaying,
                                        isFavourite = song.id in favourites,
                                        onClick = { playFrom(visibleSongs, song) },
                                        onToggleFavourite = {
                                            favourites = store.setFavourite(song.id, song.id !in favourites)
                                            importedSongs = store.pruneImported()
                                        },
                                        onPlayNext = { connection.playNext(song) },
                                        onAddToQueue = { connection.addToQueue(song) },
                                        onAddToPlaylist = { playlistPickerFor = song },
                                        onRemoveFromPlaylist = if (section == Section.PLAYLISTS && playlistName != null) {
                                            {
                                                playlists = store.removeFromPlaylist(playlistName, song.id)
                                                importedSongs = store.pruneImported()
                                            }
                                        } else null
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (showEqualizer) {
                EqualizerSheet(connection = connection, onDismiss = { showEqualizer = false })
            }

            Spacer(Modifier.height(8.dp))
            NowPlayingBar(
                connection = connection,
                song = currentSong,
                onOpen = { showNowPlaying = true }
            )
            Spacer(Modifier.height(10.dp))
        }

        AnimatedVisibility(
            visible = showNowPlaying && currentSong != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            currentSong?.let { playing ->
                NowPlayingScreen(
                    connection = connection,
                    song = playing,
                    onClose = { showNowPlaying = false }
                )
            }
        }
    }

    // ---- Dialogs ----

    playlistPickerFor?.let { song ->
        AlertDialog(
            onDismissRequest = { playlistPickerFor = null },
            title = { Text("Add to playlist") },
            text = {
                Column {
                    playlists.keys.forEach { name ->
                        TextButton(
                            onClick = {
                                playlists = store.addToPlaylist(name, song.id)
                                playlistPickerFor = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(name, modifier = Modifier.fillMaxWidth()) }
                    }
                    TextButton(
                        onClick = {
                            newPlaylistRequest = NewPlaylistRequest(song)
                            playlistPickerFor = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("New playlist…", modifier = Modifier.fillMaxWidth()) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { playlistPickerFor = null }) { Text("Cancel") } }
        )
    }

    newPlaylistRequest?.let { request ->
        NewPlaylistDialog(
            onDismiss = { newPlaylistRequest = null },
            onCreate = { name ->
                var updated = store.createPlaylist(name)
                request.songToAdd?.let { updated = store.addToPlaylist(name.trim(), it.id) }
                playlists = updated
                newPlaylistRequest = null
            }
        )
    }

    playlistToDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            title = { Text("Delete playlist?") },
            text = { Text("\"$name\" will be removed. Your songs are not deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    playlists = store.deletePlaylist(name)
                    importedSongs = store.pruneImported()
                    if (openPlaylist == name) openPlaylist = null
                    playlistToDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { playlistToDelete = null }) { Text("Cancel") } }
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Pieces
// ---------------------------------------------------------------------------------------------

@Composable
private fun InfoCard(
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Surface(
        color = Color.White.copy(alpha = 0.07f),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp)) {
            Icon(Icons.Default.MusicNote, contentDescription = null, tint = Accent)
            Spacer(Modifier.height(8.dp))
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(message, color = Color.LightGray)
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(14.dp))
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

private fun emptyTitle(section: Section, query: String, libraryEmpty: Boolean): String = when {
    query.isNotBlank() -> "No matches"
    section == Section.FAVOURITES -> "No favourites yet"
    section == Section.RECENT -> "Nothing played yet"
    section == Section.PLAYLISTS -> "This playlist is empty"
    libraryEmpty -> "No music found yet"
    else -> "Nothing here"
}

private fun emptyMessage(section: Section, query: String, libraryEmpty: Boolean): String = when {
    query.isNotBlank() -> "Try a different search."
    section == Section.FAVOURITES -> "Tap the heart on a song to add it here."
    section == Section.RECENT -> "Songs you play will show up here."
    section == Section.PLAYLISTS -> "Use the ⋮ menu on a song and choose Add to playlist."
    libraryEmpty -> "Add audio files to your device, then refresh."
    else -> ""
}

@Composable
private fun PlaylistList(
    playlists: Map<String, List<Long>>,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onCreate: () -> Unit
) {
    Column {
        Button(onClick = onCreate) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("New playlist")
        }
        Spacer(Modifier.height(12.dp))
        if (playlists.isEmpty()) {
            InfoCard("No playlists yet", "Create one, then add songs from the ⋮ menu.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(playlists.keys.toList(), key = { it }) { name ->
                    val count = playlists[name].orEmpty().size
                    Surface(
                        color = Color.White.copy(alpha = 0.065f),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(name) }
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.MusicNote, contentDescription = null, tint = Accent)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (count == 1) "1 song" else "$count songs",
                                    color = Color.LightGray,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            IconButton(onClick = { onDelete(name) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete playlist $name", tint = Muted)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SongRow(
    song: Song,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isFavourite: Boolean,
    onClick: () -> Unit,
    onToggleFavourite: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)?
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        color = if (isCurrent) Accent.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.065f),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 0.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Artwork(song = song, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    song.title,
                    color = if (isCurrent) Accent else Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    (if (isPlaying) "Playing • " else "") +
                        (if (song.format.isNotEmpty()) "${song.format} • " else "") +
                        song.artist,
                    color = Color.LightGray,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onToggleFavourite) {
                Icon(
                    if (isFavourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (isFavourite) "Remove from favourites" else "Add to favourites",
                    tint = if (isFavourite) Accent else Muted
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = Muted)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Play next") }, onClick = { menuOpen = false; onPlayNext() })
                    DropdownMenuItem(text = { Text("Add to queue") }, onClick = { menuOpen = false; onAddToQueue() })
                    DropdownMenuItem(text = { Text("Add to playlist") }, onClick = { menuOpen = false; onAddToPlaylist() })
                    if (onRemoveFromPlaylist != null) {
                        DropdownMenuItem(
                            text = { Text("Remove from this playlist") },
                            onClick = { menuOpen = false; onRemoveFromPlaylist() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConvertScreen(
    songs: List<Song>,
    selected: Set<Long>,
    bitrate: Int,
    jobs: List<ConversionJob>,
    onBitrate: (Int) -> Unit,
    onToggle: (Long) -> Unit,
    onSelectAll: () -> Unit,
    onPickFiles: () -> Unit,
    onConvertSelected: () -> Unit,
    onClearFinished: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "header") {
            Column {
                Text("Convert to MP3", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(
                    "Works with Opus, M4A, AAC, OGG, FLAC, WAV and more. Keep the app open while converting.",
                    color = Color.LightGray,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(128, 192, 256, 320).forEach { kbps ->
                        FilterChip(
                            selected = kbps == bitrate,
                            onClick = { onBitrate(kbps) },
                            label = { Text("$kbps kbps") }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onPickFiles) { Text("Choose files…") }
                    Button(onClick = onConvertSelected, enabled = selected.isNotEmpty()) {
                        Text("Convert ${selected.size} selected")
                    }
                }
            }
        }

        if (jobs.isNotEmpty()) {
            item(key = "jobs-header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Conversions", color = Color.White, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = onClearFinished) { Text("Clear finished") }
                }
            }
            items(jobs, key = { "job-${it.id}" }) { job -> JobRow(job) }
        }

        item(key = "library-header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("From your library (${songs.size})", color = Color.White, fontWeight = FontWeight.SemiBold)
                if (songs.isNotEmpty()) {
                    TextButton(onClick = onSelectAll) {
                        Text(if (songs.all { it.id in selected }) "Clear" else "Select all")
                    }
                }
            }
        }

        if (songs.isEmpty()) {
            item(key = "library-empty") {
                InfoCard(
                    "Nothing to convert here",
                    "Every song in your library is already MP3, or no audio was found. You can still use Choose files."
                )
            }
        }

        items(songs, key = { "song-${it.id}" }) { song ->
            Surface(
                color = Color.White.copy(alpha = 0.065f),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().clickable { onToggle(song.id) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = song.id in selected, onCheckedChange = null)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(song.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${song.format} • ${song.artist}",
                            color = Color.LightGray,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun JobRow(job: ConversionJob) {
    Surface(
        color = Color.White.copy(alpha = 0.10f),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(job.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val statusText = when (job.status) {
                ConversionStatus.QUEUED -> "Waiting…"
                ConversionStatus.RUNNING -> "Converting… ${(job.progress * 100).toInt()}%"
                ConversionStatus.DONE -> "Saved to ${job.message}"
                ConversionStatus.FAILED -> "Failed: ${job.message}"
            }
            Text(
                statusText,
                color = if (job.status == ConversionStatus.FAILED) Color(0xFFFF8A80) else Color.LightGray,
                style = MaterialTheme.typography.bodySmall
            )
            if (job.status == ConversionStatus.RUNNING) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { job.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = Accent
                )
            }
        }
    }
}

@Composable
private fun NowPlayingBar(connection: PlayerConnection, song: Song?, onOpen: () -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(0f) }
    var showSleepDialog by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }

    // Count down the sleep timer; the service pauses playback itself when it expires.
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val sleepEndsAt = connection.sleepEndsAt
    LaunchedEffect(sleepEndsAt) {
        while (sleepEndsAt > 0L) {
            now = SystemClock.elapsedRealtime()
            if (now >= sleepEndsAt) {
                connection.onSleepTimerFinished()
                break
            }
            delay(1000)
        }
    }
    val sleepRemainingMs = if (sleepEndsAt > 0L) (sleepEndsAt - now).coerceAtLeast(0L) else 0L

    if (showQueue) {
        QueueSheet(connection = connection, onDismiss = { showQueue = false })
    }

    if (showSleepDialog) {
        SleepTimerDialog(
            active = sleepEndsAt > 0L,
            onPick = { minutes ->
                connection.setSleepTimer(minutes)
                showSleepDialog = false
            },
            onDismiss = { showSleepDialog = false }
        )
    }
    val duration = connection.durationMs.coerceAtLeast(1L).toFloat()
    val shownPosition = (if (dragging) dragPosition else connection.positionMs.toFloat()).coerceIn(0f, duration)

    Surface(
        color = Color.White.copy(alpha = 0.10f),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.clickable(enabled = song != null) { onOpen() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Artwork(song = song, size = 52.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        song?.title ?: "Nothing playing",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        song?.artist ?: "Pick a song to start",
                        color = Color.LightGray,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = { showQueue = true }) {
                    Icon(
                        Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = "Queue",
                        tint = Color.White
                    )
                }
                IconButton(onClick = { showSleepDialog = true }) {
                    Icon(
                        Icons.Default.Timer,
                        contentDescription = if (sleepEndsAt > 0L) "Sleep timer on" else "Sleep timer",
                        tint = if (sleepEndsAt > 0L) Accent else Muted
                    )
                }
            }

            if (sleepEndsAt > 0L) {
                Text(
                    "Sleep timer: pausing in ${formatTime(sleepRemainingMs)}",
                    color = Accent,
                    style = MaterialTheme.typography.labelSmall
                )
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
                enabled = song != null,
                colors = SliderDefaults.colors(
                    thumbColor = Accent,
                    activeTrackColor = Accent,
                    inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                )
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(shownPosition.toLong()), color = Muted, style = MaterialTheme.typography.labelSmall)
                Text(formatTime(connection.durationMs), color = Muted, style = MaterialTheme.typography.labelSmall)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { connection.toggleShuffle() }) {
                    Icon(
                        Icons.Default.Shuffle,
                        contentDescription = if (connection.shuffleEnabled) "Shuffle on" else "Shuffle off",
                        tint = if (connection.shuffleEnabled) Accent else Muted
                    )
                }
                IconButton(onClick = { connection.previous() }) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = Color.White)
                }
                IconButton(
                    onClick = { connection.togglePlayPause() },
                    modifier = Modifier.size(56.dp).clip(CircleShape).background(Accent)
                ) {
                    Icon(
                        if (connection.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (connection.isPlaying) "Pause" else "Play",
                        tint = Color(0xFF090B12),
                        modifier = Modifier.size(32.dp)
                    )
                }
                IconButton(onClick = { connection.next() }) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color.White)
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
                        tint = if (mode == Player.REPEAT_MODE_OFF) Muted else Accent
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QueueSheet(connection: PlayerConnection, onDismiss: () -> Unit) {
    val queue = connection.queue
    val current = connection.currentIndex

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF12151F)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Queue (${queue.size})",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                TextButton(
                    onClick = { connection.clearUpcoming() },
                    enabled = current >= 0 && current < queue.lastIndex
                ) { Text("Clear upcoming") }
            }

            if (queue.isEmpty()) {
                Text(
                    "Nothing in the queue yet. Play a song, or use Add to queue on a song.",
                    color = Muted,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    items(queue, key = { it.index }) { item ->
                        val isCurrent = item.index == current
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { connection.jumpTo(item.index) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${item.index + 1}",
                                color = if (isCurrent) Accent else Muted,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.width(32.dp)
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.title.ifBlank { "Unknown title" },
                                    color = if (isCurrent) Accent else Color.White,
                                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    item.artist,
                                    color = Muted,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = { connection.moveQueueItem(item.index, item.index - 1) },
                                enabled = item.index > 0
                            ) {
                                Icon(
                                    Icons.Default.KeyboardArrowUp,
                                    contentDescription = "Move up",
                                    tint = if (item.index > 0) Color.White else Muted
                                )
                            }
                            IconButton(
                                onClick = { connection.moveQueueItem(item.index, item.index + 1) },
                                enabled = item.index < queue.lastIndex
                            ) {
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Move down",
                                    tint = if (item.index < queue.lastIndex) Color.White else Muted
                                )
                            }
                            if (isCurrent) {
                                Spacer(Modifier.size(48.dp))
                            } else {
                                IconButton(onClick = { connection.removeQueueItem(item.index) }) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove from queue",
                                        tint = Muted
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EqualizerSheet(connection: PlayerConnection, onDismiss: () -> Unit) {
    LaunchedEffect(connection.isConnected) {
        if (connection.isConnected) connection.refreshEqualizer()
    }
    val state = connection.eqState
    val sliderColors = SliderDefaults.colors(
        thumbColor = Accent,
        activeTrackColor = Accent,
        inactiveTrackColor = Color.White.copy(alpha = 0.2f)
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF12151F)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Equalizer",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Switch(
                    checked = state?.enabled == true,
                    onCheckedChange = { connection.setEqActive(it) },
                    enabled = state?.available == true
                )
            }

            if (state == null) {
                Text("Connecting…", color = Muted, modifier = Modifier.padding(vertical = 16.dp))
            } else if (!state.available) {
                Text(
                    "The equalizer isn't available on this device.",
                    color = Muted,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    state.presets.forEachIndexed { index, name ->
                        FilterChip(
                            selected = state.preset == index,
                            onClick = { connection.useEqPreset(index) },
                            label = { Text(name) },
                            enabled = state.enabled
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                state.levels.forEachIndexed { band, level ->
                    val hz = state.freqsHz.getOrElse(band) { 0 }
                    val db = "%+.1f".format(level / 100f)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            formatHz(hz),
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(64.dp)
                        )
                        Slider(
                            value = level.toFloat(),
                            onValueChange = { connection.setEqBand(band, it.roundToInt()) },
                            valueRange = state.minLevel.toFloat()..state.maxLevel.toFloat(),
                            enabled = state.enabled,
                            colors = sliderColors,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "$db dB",
                            color = Muted,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.width(60.dp)
                        )
                    }
                }

                if (state.bassSupported) {
                    Spacer(Modifier.height(8.dp))
                    Text("Bass boost", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Slider(
                        value = state.bass.toFloat(),
                        onValueChange = { connection.setBass(it.roundToInt()) },
                        valueRange = 0f..1000f,
                        enabled = state.enabled,
                        colors = sliderColors
                    )
                }

                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { connection.resetEq() }, enabled = state.enabled) {
                    Text("Reset to flat")
                }
            }
        }
    }
}

private fun formatHz(hz: Int): String =
    if (hz >= 1000) "%.1f kHz".format(hz / 1000f) else "$hz Hz"

@Composable
internal fun SleepTimerDialog(
    active: Boolean,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep timer") },
        text = {
            Column {
                listOf(15, 30, 45, 60, 90).forEach { minutes ->
                    TextButton(onClick = { onPick(minutes) }, modifier = Modifier.fillMaxWidth()) {
                        Text("$minutes minutes", modifier = Modifier.fillMaxWidth())
                    }
                }
                if (active) {
                    TextButton(onClick = { onPick(0) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Turn off timer", modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun NewPlaylistDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("Playlist name") }
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ---------------------------------------------------------------------------------------------
// Artwork
// ---------------------------------------------------------------------------------------------

private val artworkCache = LruCache<String, ImageBitmap>(120)
private val songsWithoutArtwork: MutableSet<String> =
    java.util.Collections.synchronizedSet(HashSet<String>())

@Composable
internal fun Artwork(song: Song?, size: Dp, corner: Dp = 12.dp) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, song?.id, px) {
        value = if (song == null) null else loadArtwork(context, song, px)
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(Color.White.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(Icons.Default.MusicNote, contentDescription = null, tint = Accent)
        }
    }
}

internal suspend fun loadArtwork(context: Context, song: Song, px: Int): ImageBitmap? {
    // Embedded-artwork thumbnails are only available through this API on Android 10+.
    if (Build.VERSION.SDK_INT < 29) return null
    // Keyed per file: embedded art differs between tracks, and "Unknown album" files share an id.
    val key = "${song.id}-$px"
    artworkCache.get(key)?.let { return it }
    if (key in songsWithoutArtwork) return null
    return withContext(Dispatchers.IO) {
        try {
            val bitmap: Bitmap = context.contentResolver.loadThumbnail(song.uri, Size(px, px), null)
            val image = bitmap.asImageBitmap()
            artworkCache.put(key, image)
            image
        } catch (e: Exception) {
            songsWithoutArtwork.add(key)
            null
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------------------------

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun displayNameOf(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment ?: "audio"

internal fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
