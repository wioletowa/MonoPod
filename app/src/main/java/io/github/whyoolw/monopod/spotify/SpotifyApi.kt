package io.github.whyoolw.monopod.spotify

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Anything in the library that has a cover and plays as a whole: a playlist, album or artist. */
data class SpotifyPlaylist(val uri: String, val id: String, val name: String, val owner: String, val imageUrl: String?)

data class SpotifyTrack(val uri: String, val name: String, val artist: String, val album: String, val durationMs: Long)

/** The few Web API reads the iPod menus need: playlists and the home lists. Blocking calls. */
class SpotifyApi(private val auth: SpotifyAuth) {
    class Failure(val code: Int) : Exception("Spotify Web API: HTTP $code")

    fun myPlaylists(): List<SpotifyPlaylist> = paged("https://api.spotify.com/v1/me/playlists?limit=50") { o ->
        SpotifyPlaylist(
            uri = o.getString("uri"),
            id = o.getString("id"),
            name = o.optString("name"),
            owner = o.optJSONObject("owner")?.optString("display_name").orEmpty(),
            imageUrl = o.optJSONArray("images")?.optJSONObject(0)?.optString("url"),
        )
    }

    /** The signed-in user's Spotify id (for the Liked Songs collection link). */
    fun userId(): String = get("https://api.spotify.com/v1/me").getString("id")

    fun savedAlbums(): List<SpotifyPlaylist> = paged("https://api.spotify.com/v1/me/albums?limit=50") { o ->
        val a = o.getJSONObject("album")
        SpotifyPlaylist(
            uri = a.getString("uri"), id = a.getString("id"), name = a.optString("name"),
            owner = a.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty(),
            imageUrl = a.optJSONArray("images")?.optJSONObject(0)?.optString("url"),
        )
    }

    /** Followed artists; their list is under "artists" and pages by cursor. */
    fun followedArtists(artistLabel: String): List<SpotifyPlaylist> {
        val out = ArrayList<SpotifyPlaylist>()
        var url: String? = "https://api.spotify.com/v1/me/following?type=artist&limit=50"
        while (url != null && out.size < 500) {
            val page = get(url).getJSONObject("artists")
            out += items(page) { a ->
                SpotifyPlaylist(
                    uri = a.getString("uri"), id = a.getString("id"), name = a.optString("name"), owner = artistLabel,
                    imageUrl = a.optJSONArray("images")?.optJSONObject(0)?.optString("url"),
                )
            }
            url = page.optString("next").takeIf { it.isNotEmpty() && it != "null" }
        }
        return out
    }

    fun albumTracks(id: String): List<SpotifyTrack> {
        val album = get("https://api.spotify.com/v1/albums/$id")
        val name = album.optString("name")
        return paged("https://api.spotify.com/v1/albums/$id/tracks?limit=50") { o ->
            SpotifyTrack(
                uri = o.getString("uri"), name = o.optString("name"),
                artist = o.optJSONArray("artists")?.let { a -> (0 until a.length()).joinToString(", ") { a.getJSONObject(it).optString("name") } }.orEmpty(),
                album = name, durationMs = o.optLong("duration_ms"),
            )
        }
    }

    fun playlistTracks(id: String): List<SpotifyTrack> {
        // Spotify renamed .../tracks to .../items (and "track" to "item"); try the new one first
        val items = runCatching {
            paged("https://api.spotify.com/v1/playlists/$id/items?limit=100") { it.entry() }
        }.getOrElse {
            paged("https://api.spotify.com/v1/playlists/$id/tracks?limit=100") { it.entry() }
        }
        return items.filterNotNull()
    }

    fun likedTracks(): List<SpotifyTrack> =
        paged("https://api.spotify.com/v1/me/tracks?limit=50", maxItems = 500) { it.entry() }.filterNotNull()

    /** [range]: short_term (about a month), medium_term (half a year), long_term (years). */
    fun topTracks(range: String = "short_term"): List<SpotifyTrack> =
        paged("https://api.spotify.com/v1/me/top/tracks?limit=50&time_range=$range", maxItems = 50) { it.track() }.filterNotNull()

    /** Downloads a picture (a playlist cover) to [to]; blocking. */
    fun download(url: String, to: java.io.File) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            if (c.responseCode !in 200..299) throw Failure(c.responseCode)
            to.parentFile?.mkdirs()
            val tmp = java.io.File(to.path + ".part")
            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(to)
        } finally {
            c.disconnect()
        }
    }

    /** The last 50 plays, each song once. */
    fun recentlyPlayed(): List<SpotifyTrack> =
        items(get("https://api.spotify.com/v1/me/player/recently-played?limit=50")) { it.entry() }
            .filterNotNull().distinctBy { it.uri }

    /** A playlist row's song: under "item" (new API) or "track"; local files and podcasts are skipped. */
    private fun JSONObject.entry(): SpotifyTrack? = (optJSONObject("item") ?: optJSONObject("track"))?.track()

    private fun JSONObject.track(): SpotifyTrack? {
        if (optString("type") != "track" || optBoolean("is_local")) return null
        return SpotifyTrack(
            uri = getString("uri"),
            name = optString("name"),
            artist = optJSONArray("artists")?.let { a -> (0 until a.length()).joinToString(", ") { a.getJSONObject(it).optString("name") } }.orEmpty(),
            album = optJSONObject("album")?.optString("name").orEmpty(),
            durationMs = optLong("duration_ms"),
        )
    }

    private fun <T> paged(first: String, maxItems: Int = 2000, map: (JSONObject) -> T): List<T> {
        val out = ArrayList<T>()
        var url: String? = first
        while (url != null && out.size < maxItems) {
            val page = get(url)
            out += items(page, map)
            url = page.optString("next").takeIf { it.isNotEmpty() && it != "null" }
        }
        return out
    }

    private fun <T> items(page: JSONObject, map: (JSONObject) -> T): List<T> {
        val a = page.optJSONArray("items") ?: JSONArray()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(map) }
    }

    /**
     * Plays [contextUri] on the phone's Spotify from [trackUri] (or song [index]), going on in the list's order.
     * Needs user-modify-playback-state; 404 means Spotify has no active device yet.
     */
    fun startPlayback(contextUri: String, index: Int, trackUri: String? = null) {
        val token = auth.accessToken() ?: throw Failure(401)
        val c = URL("https://api.spotify.com/v1/me/player/play").openConnection() as HttpURLConnection
        c.requestMethod = "PUT"
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Content-Type", "application/json")
        // By the song's link when known: positions count items Monopod hides (local files,
        // podcasts, unavailable songs), so a position would land a few songs off
        val offset = if (trackUri != null) JSONObject().put("uri", trackUri) else JSONObject().put("position", index)
        val body = JSONObject().put("context_uri", contextUri).put("offset", offset)
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode !in 200..299) throw Failure(c.responseCode)
        } finally {
            c.disconnect()
        }
    }

    /** Pauses whatever device Spotify plays on; 403 when it already is paused. */
    fun pausePlayback() {
        val token = auth.accessToken() ?: throw Failure(401)
        val c = URL("https://api.spotify.com/v1/me/player/pause").openConnection() as HttpURLConnection
        c.requestMethod = "PUT"
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setFixedLengthStreamingMode(0)
        c.doOutput = true
        try {
            c.outputStream.close()
            if (c.responseCode !in 200..299 && c.responseCode != 403) throw Failure(c.responseCode)
        } finally {
            c.disconnect()
        }
    }

    private fun get(url: String): JSONObject {
        val token = auth.accessToken() ?: throw Failure(401)
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", "Bearer $token")
        try {
            if (c.responseCode !in 200..299) throw Failure(c.responseCode)
            val text = c.inputStream.bufferedReader().readText()
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            c.disconnect()
        }
    }
}
