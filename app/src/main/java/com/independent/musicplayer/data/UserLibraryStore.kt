package com.independent.musicplayer.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Small on-device store for favourites, playlists and play history.
 * Songs are referenced by their MediaStore id. Data never leaves the device.
 */
class UserLibraryStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("user_library", Context.MODE_PRIVATE)

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
        const val MAX_HISTORY = 50
    }
}
