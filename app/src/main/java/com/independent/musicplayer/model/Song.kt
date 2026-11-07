package com.independent.musicplayer.model

import android.content.ContentUris
import android.net.Uri

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val uri: Uri
) {
    /** Album-art content URI, used for the media notification and lock screen. */
    val artworkUri: Uri
        get() = ContentUris.withAppendedId(
            Uri.parse("content://media/external/audio/albumart"),
            albumId
        )
}
