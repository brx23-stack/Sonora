package com.sonora.player.lyrics

import android.content.Context
import com.sonora.player.model.Song
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

sealed interface FetchResult {
    class Found(val lyrics: Lyrics) : FetchResult
    object NotFound : FetchResult
    object NetworkError : FetchResult
}

/**
 * Stores lyrics on the phone (one small file per song, keyed by song id) so they work offline,
 * and can look them up once from the free LRCLIB service (lrclib.net).
 * Everything here blocks: call it from a background dispatcher.
 */
class LyricsRepository(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "lyrics").apply { mkdirs() }

    private fun lrcFile(songId: Long) = File(dir, "$songId.lrc")
    private fun txtFile(songId: Long) = File(dir, "$songId.txt")

    fun load(songId: Long): Lyrics? = try {
        val lrc = lrcFile(songId)
        val txt = txtFile(songId)
        when {
            lrc.exists() -> LrcParser.parse(lrc.readText()).takeIf { it.isNotEmpty() }?.toLyrics()
            txt.exists() -> txt.readText().takeIf { it.isNotBlank() }?.let { Lyrics(emptyList(), it) }
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    /** Saves LRC or plain text for a song and returns what was stored (null if there was nothing usable). */
    fun save(songId: Long, raw: String): Lyrics? {
        delete(songId)
        return try {
            val synced = LrcParser.parse(raw)
            when {
                synced.isNotEmpty() -> {
                    lrcFile(songId).writeText(raw)
                    synced.toLyrics()
                }
                raw.isNotBlank() -> {
                    txtFile(songId).writeText(raw.trim())
                    Lyrics(emptyList(), raw.trim())
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun delete(songId: Long) {
        lrcFile(songId).delete()
        txtFile(songId).delete()
    }

    /** Looks the song up online and stores the best match. */
    fun fetchOnline(song: Song): FetchResult {
        return try {
            val query = cleanQuery(song)
            if (query.isBlank()) return FetchResult.NotFound
            val url = "https://lrclib.net/api/search?q=" + URLEncoder.encode(query, "UTF-8")
            val results = JSONArray(httpGet(url))
            val best = pickBest(results, song) ?: return FetchResult.NotFound

            val synced = best.str("syncedLyrics")
            val plain = best.str("plainLyrics")
            val saved = save(song.id, synced.ifBlank { plain })
            if (saved != null) FetchResult.Found(saved) else FetchResult.NotFound
        } catch (e: Exception) {
            FetchResult.NetworkError
        }
    }

    private fun pickBest(results: JSONArray, song: Song): JSONObject? {
        val songSeconds = song.durationMs / 1000.0
        var best: JSONObject? = null
        var bestScore = Double.MAX_VALUE
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            if (item.optBoolean("instrumental", false)) continue
            val hasSynced = item.str("syncedLyrics").isNotBlank()
            if (!hasSynced && item.str("plainLyrics").isBlank()) continue

            val diff = if (songSeconds > 0 && item.has("duration") && !item.isNull("duration")) {
                abs(item.optDouble("duration") - songSeconds)
            } else {
                0.0
            }
            if (songSeconds > 0 && diff > 8.0) continue // probably a different recording

            val artist = item.str("artistName").lowercase()
            val artistMatch = artist.isNotBlank() &&
                (song.title.lowercase().contains(artist) || song.artist.lowercase().contains(artist))
            val score = diff - (if (artistMatch) 3.0 else 0.0) - (if (hasSynced) 1.0 else 0.0)
            if (score < bestScore) {
                bestScore = score
                best = item
            }
        }
        return best
    }

    /** Strips things like "(Lyrics)", "[videoId]" and file extensions from a title. */
    private fun cleanQuery(song: Song): String =
        song.title
            .replace(Regex("""\[[^\]]*]"""), " ")
            .replace(Regex("""\([^)]*\)"""), " ")
            .replace(Regex("""\.(mp3|m4a|opus|ogg|flac|wav|aac)$""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "Sonora Android music player")
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // org.json turns JSON null into the text "null", so check for it explicitly.
    private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key, "")

    private fun List<LyricLine>.toLyrics() = Lyrics(this, joinToString("\n") { it.text })
}
