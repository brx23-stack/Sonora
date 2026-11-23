package com.sonora.player.lyrics

/** One line of synchronized lyrics. */
data class LyricLine(val timeMs: Long, val text: String)

/**
 * Lyrics for a song. [synced] has timestamps (empty when only plain text is available);
 * [plain] is always the full text.
 */
data class Lyrics(val synced: List<LyricLine>, val plain: String)

object LrcParser {
    // Matches [mm:ss], [mm:ss.x], [mm:ss.xx] and [mm:ss.xxx]. Tags like [ar:Artist] don't match.
    private val timeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /** Parses LRC text. Returns an empty list when the text has no timestamps. */
    fun parse(text: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val tags = timeTag.findAll(line).toList()
            if (tags.isEmpty()) continue
            val lyricText = line.substring(tags.last().range.last + 1).trim()
            for (tag in tags) {
                val minutes = tag.groupValues[1].toLong()
                val seconds = tag.groupValues[2].toLong()
                val fraction = tag.groupValues[3]
                val millis = when (fraction.length) {
                    0 -> 0L
                    1 -> fraction.toLong() * 100
                    2 -> fraction.toLong() * 10
                    else -> fraction.take(3).toLong()
                }
                out.add(LyricLine((minutes * 60 + seconds) * 1000 + millis, lyricText))
            }
        }
        return out.sortedBy { it.timeMs }
    }
}

/** Index of the line that should be highlighted at [positionMs], or -1 before the first line. */
fun currentLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
    // A small lead makes the highlight feel in time despite the position only updating a few times a second.
    val target = positionMs + 250
    var lo = 0
    var hi = lines.size - 1
    var answer = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (lines[mid].timeMs <= target) {
            answer = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return answer
}
