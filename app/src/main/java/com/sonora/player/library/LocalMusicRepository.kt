package com.sonora.player.library

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.sonora.player.model.Song

class LocalMusicRepository(private val context: Context) {

    /** Blocking query. Call from a background dispatcher. */
    fun loadSongs(): List<Song> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DISPLAY_NAME
        )
        // Include every playable audio file (MP3, M4A, OPUS, OGG, FLAC, WAV, AAC...),
        // including voice notes and recordings, but skip system ringtones and alerts.
        val selection =
            "${MediaStore.Audio.Media.DURATION} >= 1000 AND " +
                "${MediaStore.Audio.Media.IS_RINGTONE} = 0 AND " +
                "${MediaStore.Audio.Media.IS_NOTIFICATION} = 0 AND " +
                "${MediaStore.Audio.Media.IS_ALARM} = 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        val result = mutableListOf<Song>()

        context.contentResolver.query(
            collection, projection, selection, null, sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val fileNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn).cleanOr("Unknown title")
                result += Song(
                    id = id,
                    title = title,
                    artist = cursor.getString(artistColumn).cleanOr("Unknown artist"),
                    album = cursor.getString(albumColumn).cleanOr("Unknown album"),
                    albumId = cursor.getLong(albumIdColumn),
                    durationMs = cursor.getLong(durationColumn),
                    uri = ContentUris.withAppendedId(collection, id),
                    displayName = cursor.getString(fileNameColumn).cleanOr(title)
                )
            }
        }
        return result
    }

    // MediaStore reports missing tags as the literal string "<unknown>".
    private fun String?.cleanOr(fallback: String): String =
        if (this.isNullOrBlank() || this == MediaStore.UNKNOWN_STRING) fallback else this
}
