package com.sonora.player.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.sonora.player.model.Song
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Small on-device store for favourites, playlists and play history.
 * Songs are referenced by their MediaStore id. Data never leaves the device.
 */
class UserLibraryStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("user_library", Context.MODE_PRIVATE)

    // ---- Favourites ----

    fun loadFavourites(): Set<Long> = readIds(KEY_FAVOURITES).toSet()

    fun setFavourite(id: Long, favourite: Boolean): Set<Long> {
        val updated = readIds(KEY_FAVOURITES).filter { it != id }.toMutableList()
        if (favourite) updated.add(id)
        writeIds(KEY_FAVOURITES, updated)
        return updated.toSet()
    }

    // ---- History (most recent first) ----

    fun loadHistory(): List<Long> = readIds(KEY_HISTORY)

    fun addToHistory(id: Long): List<Long> {
        val updated = (listOf(id) + readIds(KEY_HISTORY).filter { it != id }).take(MAX_HISTORY)
        writeIds(KEY_HISTORY, updated)
        return updated
    }

    // ---- Playlists ----

    fun loadPlaylists(): Map<String, List<Long>> {
        val raw = prefs.getString(KEY_PLAYLISTS, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val result = LinkedHashMap<String, List<Long>>()
            val names = obj.keys()
            while (names.hasNext()) {
                val name = names.next()
                val arr = obj.getJSONArray(name)
                result[name] = List(arr.length()) { arr.getLong(it) }
            }
            result
        } catch (e: JSONException) {
            emptyMap()
        }
    }

    fun createPlaylist(name: String): Map<String, List<Long>> {
        val playlists = loadPlaylists().toMutableMap()
        val clean = name.trim()
        if (clean.isNotEmpty() && !playlists.containsKey(clean)) playlists[clean] = emptyList()
        savePlaylists(playlists)
        return playlists
    }

    fun deletePlaylist(name: String): Map<String, List<Long>> {
        val playlists = loadPlaylists().toMutableMap()
        playlists.remove(name)
        savePlaylists(playlists)
        return playlists
    }

    fun addToPlaylist(name: String, songId: Long): Map<String, List<Long>> {
        val playlists = loadPlaylists().toMutableMap()
        val current = playlists[name] ?: return playlists
        if (songId !in current) playlists[name] = current + songId
        savePlaylists(playlists)
        return playlists
    }

    fun removeFromPlaylist(name: String, songId: Long): Map<String, List<Long>> {
        val playlists = loadPlaylists().toMutableMap()
        val current = playlists[name] ?: return playlists
        playlists[name] = current.filter { it != songId }
        savePlaylists(playlists)
        return playlists
    }

    // ---- Imported files (picked from storage, not part of the MediaStore library) ----

    fun loadImported(): List<Song> {
        val raw = prefs.getString(KEY_IMPORTED, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Song(
                    id = o.getLong("id"),
                    title = o.getString("title"),
                    artist = o.getString("artist"),
                    album = o.getString("album"),
                    albumId = 0L,
                    durationMs = o.getLong("durationMs"),
                    uri = Uri.parse(o.getString("uri")),
                    displayName = o.getString("name")
                )
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    /** Adds songs, ignoring ones that are already imported. */
    fun addImported(songs: List<Song>) {
        val existing = loadImported()
        val known = existing.map { it.id }.toSet()
        saveImported(existing + songs.filter { it.id !in known })
    }

    /**
     * Forgets imported files that are no longer in any playlist or favourites,
     * and releases the access we kept to them. Returns the remaining imported songs.
     */
    fun pruneImported(): List<Song> {
        val used = loadPlaylists().values.flatten().toSet() + loadFavourites()
        val (keep, drop) = loadImported().partition { it.id in used }
        if (drop.isNotEmpty()) {
            saveImported(keep)
            drop.forEach {
                try {
                    appContext.contentResolver.releasePersistableUriPermission(
                        it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    // Already released or never granted.
                }
            }
        }
        return keep
    }

    private fun saveImported(songs: List<Song>) {
        val arr = JSONArray()
        songs.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("title", it.title)
                    .put("artist", it.artist)
                    .put("album", it.album)
                    .put("durationMs", it.durationMs)
                    .put("uri", it.uri.toString())
                    .put("name", it.displayName)
            )
        }
        prefs.edit().putString(KEY_IMPORTED, arr.toString()).apply()
    }

    // ---- Helpers ----

    private fun savePlaylists(playlists: Map<String, List<Long>>) {
        val obj = JSONObject()
        for ((name, ids) in playlists) {
            val arr = JSONArray()
            ids.forEach { arr.put(it) }
            obj.put(name, arr)
        }
        prefs.edit().putString(KEY_PLAYLISTS, obj.toString()).apply()
    }

    private fun readIds(key: String): List<Long> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { arr.getLong(it) }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    private fun writeIds(key: String, ids: List<Long>) {
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    private companion object {
        const val KEY_FAVOURITES = "favourites"
        const val KEY_HISTORY = "history"
        const val KEY_PLAYLISTS = "playlists"
        const val KEY_IMPORTED = "imported_songs"
        const val MAX_HISTORY = 50
    }
}
