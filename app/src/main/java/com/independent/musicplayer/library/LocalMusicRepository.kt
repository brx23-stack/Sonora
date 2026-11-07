package com.independent.musicplayer.library

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.independent.musicplayer.model.Song

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
            MediaStore.Audio.Media.DURATION
        )
        val selection =
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 0"
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

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                result += Song(
                    id = id,
                    title = cursor.getString(titleColumn).cleanOr("Unknown title"),
                    artist = cursor.getString(artistColumn).cleanOr("Unknown artist"),
                    album = cursor.getString(albumColumn).cleanOr("Unknown album"),
                    albumId = cursor.getLong(albumIdColumn),
                    durationMs = cursor.getLong(durationColumn),
                    uri = ContentUris.withAppendedId(collection, id)
                )
            }
        }
        return result
    }

    // MediaStore reports missing tags as the literal string "<unknown>".
    private fun String?.cleanOr(fallback: String): String =
        if (this.isNullOrBlank() || this == MediaStore.UNKNOWN_STRING) fallback else this
}
