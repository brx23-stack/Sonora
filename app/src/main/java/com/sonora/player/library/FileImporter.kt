package com.sonora.player.library

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.sonora.player.model.Song

/** Turns an audio file picked with the system file picker into a [Song]. */
object FileImporter {

    /** Blocking. Call from a background dispatcher. Returns null if the file can't be read as audio. */
    fun read(context: Context, uri: Uri): Song? {
        // Keep access after a restart so playlists with this file keep working.
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // Some providers don't offer persistable access; playback may stop working after a restart.
        }

        val name = displayName(context, uri)
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var duration = 0L

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            return null
        } finally {
            try { retriever.release() } catch (e: Exception) { }
        }

        val fallbackTitle = name.substringBeforeLast('.', name).ifBlank { "Unknown title" }
        return Song(
            id = idFor(uri),
            title = title.cleanOr(fallbackTitle),
            artist = artist.cleanOr("Unknown artist"),
            album = album.cleanOr("Unknown album"),
            albumId = 0L,
            durationMs = duration,
            uri = uri,
            displayName = name
        )
    }

    /** Negative so it can never clash with a MediaStore id; stable for the same file. */
    private fun idFor(uri: Uri): Long = -((uri.toString().hashCode().toLong() and 0x7FFFFFFFL) + 1L)

    private fun displayName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val name = c.getString(0)
                        if (!name.isNullOrBlank()) return name
                    }
                }
        } catch (e: Exception) {
            // fall through
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "audio"
    }

    private fun String?.cleanOr(fallback: String): String =
        if (this.isNullOrBlank()) fallback else this
}
