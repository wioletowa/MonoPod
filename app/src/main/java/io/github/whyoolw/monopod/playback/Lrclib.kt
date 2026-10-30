package io.github.whyoolw.monopod.playback

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/**
 * Lyrics from LRCLIB (lrclib.net), an open database of synced lyrics: for Spotify songs, and
 * for songs of our own that carry none. Free, no key; it asks for a User-Agent naming the app.
 */
object Lrclib {
    private const val API = "https://lrclib.net/api"
    private const val AGENT = "Monopod (https://github.com/wioletowa/MonoPod)"

    /** LRC text when synced lyrics exist, plain text otherwise; null when LRCLIB has none. Blocking. */
    fun find(artist: String, title: String, album: String?, durationMs: Long): String? {
        if (artist.isBlank() || title.isBlank()) return null
        val seconds = durationMs / 1000
        // Exact match first (LRCLIB allows a couple of seconds off), then a search
        if (seconds > 0) {
            val q = "artist_name=${enc(artist)}&track_name=${enc(title)}&duration=$seconds" +
                (album?.takeIf { it.isNotBlank() }?.let { "&album_name=${enc(it)}" } ?: "")
            get("$API/get?$q")?.let { lyricsOf(JSONObject(it)) }?.let { return it }
        }
        val found = get("$API/search?track_name=${enc(title)}&artist_name=${enc(artist)}")?.let(::JSONArray) ?: return null
        val hits = (0 until found.length()).map { found.getJSONObject(it) }
            .filter { seconds <= 0 || abs(it.optDouble("duration", 0.0) - seconds) <= 3 }
        // Synced lyrics beat plain ones
        return hits.firstNotNullOfOrNull { it.optString("syncedLyrics").takeIf { s -> s.isNotBlank() && s != "null" } }
            ?: hits.firstNotNullOfOrNull(::lyricsOf)
    }

    private fun lyricsOf(o: JSONObject): String? =
        listOf("syncedLyrics", "plainLyrics").firstNotNullOfOrNull { k ->
            o.optString(k).takeIf { it.isNotBlank() && it != "null" }
        }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** The body, or null on 404 (not found); other failures throw, so they are not cached as "none". */
    private fun get(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", AGENT)
        c.connectTimeout = 8000
        c.readTimeout = 8000
        try {
            if (c.responseCode == 404) return null
            if (c.responseCode !in 200..299) throw java.io.IOException("LRCLIB: HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }
}
