package com.play.tube.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/**
 * Lyrics from the free community database lrclib.net (no key needed).
 * Only returns lyrics when a reasonably confident match is found — otherwise
 * the player simply says "no lyrics found".
 */
object LyricsRepository {

    data class Line(val timeMs: Long, val text: String)

    /** [synced] true = timestamps are real (karaoke style), false = plain text. */
    data class Lyrics(val lines: List<Line>, val synced: Boolean)

    private val cache = HashMap<String, Lyrics?>()

    suspend fun fetch(url: String, title: String, uploader: String, durationMs: Long): Lyrics? {
        synchronized(cache) { if (cache.containsKey(url)) return cache[url] }
        val result = withContext(Dispatchers.IO) {
            runCatching { lookup(title, uploader, durationMs) }.getOrNull()
        }
        synchronized(cache) { cache[url] = result }
        return result
    }

    private fun clean(s: String): String = s
        .replace(Regex("\\(.*?\\)|\\[.*?]|\\{.*?}"), " ")
        .replace(Regex("(?i)official|lyric video|lyrics|video song|music video|full video|audio|hd|4k"), " ")
        .replace(Regex("(?i)\\s-\\s*topic\\b"), " ")
        .replace(Regex("(?i)vevo$"), " ")
        .replace(Regex("(?i)\\b(feat|ft|featuring)\\.?\\s.*$"), " ")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '-', '|', '~')

    private fun lookup(rawTitle: String, rawArtist: String, durationMs: Long): Lyrics? {
        var title = clean(rawTitle)
        var artist = clean(rawArtist)
        // "Artist - Song" titles
        val parts = title.split(" - ", " – ", " | ")
        if (parts.size >= 2) { artist = clean(parts[0]); title = clean(parts[1]) }
        if (title.isBlank()) return null

        // Title before a "|" / "," credit tail often finds songs the full title misses.
        val shortTitle = title.split(",", "|", "/").first().trim()
        val queries = listOfNotNull(
            "track_name=${enc(title)}&artist_name=${enc(artist)}",
            "q=${enc("$title $artist")}",
            "q=${enc(title)}",
            if (shortTitle.isNotBlank() && shortTitle != title) "q=${enc("$shortTitle $artist")}" else null,
            if (shortTitle.isNotBlank() && shortTitle != title) "q=${enc(shortTitle)}" else null
        )
        for (q in queries) {
            val arr = runCatching { JSONArray(get("https://lrclib.net/api/search?$q")) }.getOrNull() ?: continue
            var best: Pair<Int, org.json.JSONObject>? = null
            for (i in 0 until minOf(arr.length(), 12)) {
                val o = arr.optJSONObject(i) ?: continue
                val hasText = o.optString("syncedLyrics").isNotBlank() || o.optString("plainLyrics").isNotBlank()
                if (!hasText) continue
                var score = 0
                val dur = o.optDouble("duration", 0.0) * 1000
                if (durationMs > 0 && dur > 0) {
                    val diff = abs(dur - durationMs)
                    score += when { diff < 4000 -> 6; diff < 10000 -> 3; diff > 30000 -> -8; else -> 0 }
                }
                if (o.optString("trackName").contains(title, ignoreCase = true) ||
                    title.contains(o.optString("trackName"), ignoreCase = true)) score += 4
                if (artist.isNotBlank() && o.optString("artistName").let {
                        it.contains(artist, true) || artist.contains(it, true)
                    }) score += 3
                if (o.optString("syncedLyrics").isNotBlank()) score += 2
                if (best == null || score > best.first) best = score to o
            }
            val pick = best ?: continue
            if (pick.first < 4) continue
            parse(pick.second)?.let { return it }
        }
        return fallbackPlain(title, artist)
    }

    /**
     * Second source (lyrics.ovh, keyless): plain unsynced text, used only when
     * lrclib has nothing. Covers some Hindi / Bangla songs (often written in
     * Latin letters) that the main database lacks.
     */
    private fun fallbackPlain(title: String, artist: String): Lyrics? {
        if (artist.isBlank()) return null
        val body = runCatching {
            get("https://api.lyrics.ovh/v1/${enc(artist).replace("+", "%20")}/${enc(title).replace("+", "%20")}")
        }.getOrNull() ?: return null
        val text = runCatching { org.json.JSONObject(body).optString("lyrics") }.getOrDefault("")
        if (text.isBlank()) return null
        return Lyrics(text.replace("\r", "").lines().map { Line(-1L, it.trim()) }, synced = false)
    }

    private fun parse(o: org.json.JSONObject): Lyrics? {
        val synced = o.optString("syncedLyrics")
        if (synced.isNotBlank()) {
            val out = ArrayList<Line>()
            val stamp = Regex("\\[(\\d+):(\\d+)(?:[.:](\\d+))?]")
            synced.lineSequence().forEach { raw ->
                val stamps = stamp.findAll(raw).toList()
                if (stamps.isEmpty()) return@forEach
                val text = raw.substring(stamps.last().range.last + 1).trim()
                stamps.forEach { m ->
                    val min = m.groupValues[1].toLong()
                    val sec = m.groupValues[2].toLong()
                    val frac = m.groupValues[3].let { f ->
                        if (f.isEmpty()) 0L else (f.padEnd(3, '0').take(3)).toLong()
                    }
                    out.add(Line(min * 60_000 + sec * 1000 + frac, text))
                }
            }
            out.sortBy { it.timeMs }
            if (out.size >= 3) return Lyrics(out, synced = true)
        }
        val plain = o.optString("plainLyrics")
        if (plain.isNotBlank()) {
            return Lyrics(plain.lines().map { Line(-1L, it.trim()) }, synced = false)
        }
        return null
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "PlayTube (personal Android app)")
        try {
            if (c.responseCode !in 200..299) error("http ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
