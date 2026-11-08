package com.sonora.player.model

import android.content.ContentUris
import android.net.Uri

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val uri: Uri,
    /** File name including extension, e.g. "voice note.opus". */
    val displayName: String
) {
    /** Upper-case file extension such as MP3, M4A, OPUS. Empty if unknown. */
    val format: String
        get() = displayName.substringAfterLast('.', "").uppercase()

    /** Album-art content URI, used for the media notification and lock screen. */
    val artworkUri: Uri
        get() = ContentUris.withAppendedId(
            Uri.parse("content://media/external/audio/albumart"),
            albumId
        )
}
