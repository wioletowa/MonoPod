package io.github.whyoolw.monopod.yandex

import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A song from Yandex Music, enough to list it and to play it through our own player. */
data class YandexTrack(val id: String, val title: String, val artist: String, val album: String, val durationMs: Long, val cover: String?) {
    val uri: String get() = "yandex://track/$id"
}

/** One of the user's own playlists, as a place to add a song to. */
data class YandexPlaylistRef(val kind: String, val title: String, val revision: Int, val trackCount: Int)

/** A cover on the wall: a playlist, album or artist, or a list of songs we build ("Мне нравится"). */
data class YandexCollection(val key: String, val title: String, val subtitle: String, val cover: String?, val load: () -> List<YandexTrack>)

/**
 * Yandex Music through its unofficial API, the one the official apps use (documented by the
 * yandex-music-api project; Cassette works the same way). Not approved by Yandex, and it may
 * change under us. Sign-in happens on Yandex's own page ([YandexLoginActivity]); calls are blocking.
 */
class YandexMusic(private val context: Context) {
    private val prefs = context.getSharedPreferences("yandex", Context.MODE_PRIVATE)
    class Failure(val code: Int) : Exception("Yandex Music: HTTP $code")

    var signedIn by mutableStateOf(prefs.getString(KEY_TOKEN, null) != null)
        private set

    /** Pictures (covers) of online music; wiped with the sign-out of the last service that uses them. */
    val cacheDir: File get() = File(context.cacheDir, "yandex")

    fun login() {
        context.startActivity(Intent(context, YandexLoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun saveToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).remove(KEY_UID).apply()
        signedIn = true
    }

    /** Forgets the account and everything downloaded for it. */
    fun signOut() {
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        android.webkit.WebStorage.getInstance().deleteAllData()
        prefs.edit().clear().apply()
        signedIn = false
        cacheDir.deleteRecursively()
        streams.clear()
    }

    private val token: String get() = prefs.getString(KEY_TOKEN, null) ?: throw Failure(401)

    private val uid: Long
        get() {
            prefs.getLong(KEY_UID, 0L).takeIf { it != 0L }?.let { return it }
            val id = get("/account/status").getJSONObject("account").getLong("uid")
            prefs.edit().putLong(KEY_UID, id).apply()
            return id
        }

    // --- Library -------------------------------------------------------------------------

    fun likedTracks(): List<YandexTrack> {
        val ids = get("/users/$uid/likes/tracks").getJSONObject("library").getJSONArray("tracks")
        val keys = (0 until ids.length()).map { i ->
            val o = ids.getJSONObject(i)
            o.getString("id") + (o.optString("albumId").takeIf { it.isNotEmpty() }?.let { ":$it" } ?: "")
        }
        return tracksByIds(keys.take(1000))
    }

    fun library(likedLabel: String, artistLabel: String): List<YandexCollection> {
        val out = ArrayList<YandexCollection>()
        out += YandexCollection("yandex:liked", likedLabel, "", null) { likedTracks() }
        val own = arrayOf(getAny("/users/$uid/playlists/list") as JSONArray)
        val liked = runCatching { get2Array("/users/$uid/likes/playlists") }.getOrDefault(JSONArray())
        val playlists = own.flatMap { a -> (0 until a.length()).map { a.getJSONObject(it) } } +
            (0 until liked.length()).mapNotNull { liked.getJSONObject(it).optJSONObject("playlist") }
        playlists.distinctBy { it.opt("uid").toString() + ":" + it.opt("kind") }.forEach { out += playlist(it) }
        runCatching { get2Array("/users/$uid/likes/albums?rich=true") }.getOrNull()?.let { a ->
            for (i in 0 until a.length()) {
                val album = a.getJSONObject(i).optJSONObject("album") ?: continue
                val id = album.getString("id")
                out += YandexCollection("yandex:album:$id", album.optString("title"), artists(album), cover(album.optString("coverUri"))) { albumTracks(id) }
            }
        }
        runCatching { get2Array("/users/$uid/likes/artists?with-timestamps=false") }.getOrNull()?.let { a ->
            for (i in 0 until a.length()) {
                val artist = a.getJSONObject(i).let { it.optJSONObject("artist") ?: it }
                val id = artist.getString("id")
                out += YandexCollection(
                    "yandex:artist:$id", artist.optString("name"), artistLabel,
                    cover(artist.optJSONObject("cover")?.optString("uri")),
                ) { artistTracks(id) }
            }
        }
        return out
    }

    /** The personal mixes from the main page: Playlist of the Day, Déjà vu, Premiere and the like. */
    fun recommendations(): List<YandexCollection> {
        val found = ArrayList<JSONObject>()
        runCatching {
            val blocks = get("/landing3?blocks=personalplaylists").getJSONArray("blocks")
            for (b in 0 until blocks.length()) {
                val entities = blocks.getJSONObject(b).optJSONArray("entities") ?: continue
                for (e in 0 until entities.length()) {
                    entities.getJSONObject(e).optJSONObject("data")?.optJSONObject("data")?.let(found::add)
                }
            }
        }
        if (found.isEmpty()) {
            val generated = get("/feed").optJSONArray("generatedPlaylists") ?: JSONArray()
            for (i in 0 until generated.length()) generated.getJSONObject(i).optJSONObject("data")?.let(found::add)
        }
        return found.map(::playlist)
    }

    /** The next songs of My Wave; [after] is the last one played, so the wave goes on from there. */
    fun waveTracks(after: String?): List<YandexTrack> {
        val q = after?.let { "&queue=$it" } ?: ""
        val seq = get("/rotor/station/user:onyourwave/tracks?settings2=true$q").getJSONArray("sequence")
        return (0 until seq.length()).mapNotNull { seq.getJSONObject(it).optJSONObject("track")?.let(::track) }
    }

    private fun playlist(p: JSONObject): YandexCollection {
        val owner = p.optJSONObject("owner")?.optString("uid")?.takeIf { it.isNotEmpty() } ?: p.opt("uid").toString()
        val kind = p.opt("kind").toString()
        val coverObj = p.optJSONObject("cover")
        val coverUri = coverObj?.optString("uri")?.takeIf { it.isNotEmpty() }
            ?: coverObj?.optJSONArray("itemsUri")?.optString(0)
            ?: p.optString("ogImage")
        return YandexCollection(
            "yandex:playlist:$owner:$kind", p.optString("title"),
            p.optJSONObject("owner")?.optString("name").orEmpty(), cover(coverUri),
        ) { playlistTracks(owner, kind) }
    }

    private fun playlistTracks(owner: String, kind: String): List<YandexTrack> {
        val items = get("/users/$owner/playlists/$kind?rich-tracks=true").optJSONArray("tracks") ?: JSONArray()
        val rich = (0 until items.length()).mapNotNull { items.getJSONObject(it).optJSONObject("track")?.let(::track) }
        if (rich.isNotEmpty()) return rich
        // Some answers only carry the ids
        return tracksByIds((0 until items.length()).map { items.getJSONObject(it).let { o -> o.getString("id") + (o.optString("albumId").takeIf { a -> a.isNotEmpty() }?.let { a -> ":$a" } ?: "") } })
    }

    private fun albumTracks(id: String): List<YandexTrack> {
        val volumes = get("/albums/$id/with-tracks").optJSONArray("volumes") ?: return emptyList()
        return (0 until volumes.length()).flatMap { v ->
            val disc = volumes.getJSONArray(v)
            (0 until disc.length()).mapNotNull { track(disc.getJSONObject(it)) }
        }
    }

    private fun artistTracks(id: String): List<YandexTrack> {
        val tracks = get("/artists/$id/tracks?page=0&page-size=100").optJSONArray("tracks") ?: return emptyList()
        return (0 until tracks.length()).mapNotNull { track(tracks.getJSONObject(it)) }
    }

    private fun tracksByIds(ids: List<String>): List<YandexTrack> = ids.chunked(200).flatMap { chunk ->
        val a = postArray("/tracks", "track-ids=" + URLEncoder.encode(chunk.joinToString(","), "UTF-8") + "&with-positions=false")
        (0 until a.length()).mapNotNull { track(a.getJSONObject(it)) }
    }

    private fun track(o: JSONObject): YandexTrack? {
        if (!o.optBoolean("available", true)) return null
        val album = o.optJSONArray("albums")?.optJSONObject(0)
        return YandexTrack(
            id = o.opt("id").toString(),
            title = o.optString("title") + (o.optString("version").takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""),
            artist = artists(o),
            album = album?.optString("title").orEmpty(),
            durationMs = o.optLong("durationMs"),
            cover = cover(o.optString("coverUri").ifEmpty { album?.optString("coverUri").orEmpty() }),
        )
    }

    private fun artists(o: JSONObject): String =
        o.optJSONArray("artists")?.let { a -> (0 until a.length()).joinToString(", ") { a.getJSONObject(it).optString("name") } }.orEmpty()

    /** "avatars.yandex.net/get-music-content/…/%%" → a 400 px picture URL. */
    private fun cover(uri: String?): String? =
        uri?.takeIf { it.isNotEmpty() }?.let { "https://" + it.removePrefix("https://").replace("%%", "400x400") }

    // --- Likes and own playlists --------------------------------------------------------------

    /** Ids of the liked songs, read once and kept up to date by [setLiked]. */
    private var likedIds: MutableSet<String>? = null

    @Synchronized
    fun isLiked(trackId: String): Boolean {
        val ids = likedIds ?: run {
            val a = get("/users/$uid/likes/tracks").getJSONObject("library").getJSONArray("tracks")
            (0 until a.length()).mapTo(HashSet()) { a.getJSONObject(it).getString("id") }.also { likedIds = it }
        }
        return trackId in ids
    }

    @Synchronized
    fun setLiked(trackId: String, on: Boolean) {
        val action = if (on) "add-multiple" else "remove"
        request("$API/users/$uid/likes/tracks/$action", "track-ids=$trackId")
        likedIds?.let { if (on) it += trackId else it -= trackId }
    }

    fun ownPlaylists(): List<YandexPlaylistRef> {
        val a = getAny("/users/$uid/playlists/list") as JSONArray
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            YandexPlaylistRef(it.opt("kind").toString(), it.optString("title"), it.optInt("revision", 1), it.optInt("trackCount"))
        }
    }

    /** Adds the song at the end of [playlist]; needs the song's album, which the queue does not keep. */
    fun addToPlaylist(trackId: String, playlist: YandexPlaylistRef) {
        val info = postArray("/tracks", "track-ids=$trackId&with-positions=false").getJSONObject(0)
        val albumId = info.optJSONArray("albums")?.optJSONObject(0)?.opt("id")?.toString()
        val track = JSONObject().put("id", trackId).apply { albumId?.let { put("albumId", it) } }
        val diff = JSONArray().put(JSONObject().put("op", "insert").put("at", playlist.trackCount).put("tracks", JSONArray().put(track)))
        request(
            "$API/users/$uid/playlists/${playlist.kind}/change-relative",
            "diff=" + URLEncoder.encode(diff.toString(), "UTF-8") + "&revision=${playlist.revision}",
        )
    }

    // --- Playback --------------------------------------------------------------------------

    /** Stream links are good for a while; remember them so seeking does not ask again. */
    private val streams = HashMap<String, Pair<String, Long>>()

    /** A direct link to the song's MP3, best quality offered. */
    @Synchronized
    fun streamUrl(trackId: String): String {
        streams[trackId]?.let { (url, at) -> if (System.currentTimeMillis() - at < 10 * 60_000) return url }
        val infos = getAny("/tracks/$trackId/download-info") as JSONArray
        val best = (0 until infos.length()).map { infos.getJSONObject(it) }
            .filter { it.optString("codec") == "mp3" && !it.optBoolean("preview") }
            .maxByOrNull { it.optInt("bitrateInKbps") }
            ?: (0 until infos.length()).map { infos.getJSONObject(it) }.maxByOrNull { it.optInt("bitrateInKbps") }
            ?: throw Failure(404)
        val xml = request(best.getString("downloadInfoUrl"), null)
        fun tag(name: String) = Regex("<$name>(.*?)</$name>").find(xml)?.groupValues?.get(1) ?: throw Failure(500)
        val host = tag("host")
        val path = tag("path")
        val ts = tag("ts")
        val s = tag("s")
        val sign = md5("XGRlBW9FXlekgbPrRHuSiA" + path.drop(1) + s)
        val url = "https://$host/get-mp3/$sign/$ts$path"
        streams[trackId] = url to System.currentTimeMillis()
        return url
    }

    /** The song's lyrics, as LRC when Yandex has them synced, plain text otherwise; null when none. */
    fun lyrics(trackId: String): String? {
        for (format in listOf("LRC", "TEXT")) {
            val ts = (System.currentTimeMillis() / 1000).toString()
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec("p93jhgh689SBReK6ghtw62".toByteArray(), "HmacSHA256")) }
            val sign = Base64.encodeToString(mac.doFinal("$trackId$ts".toByteArray()), Base64.NO_WRAP)
            val result = runCatching {
                get("/tracks/$trackId/lyrics?format=$format&timeStamp=$ts&sign=" + URLEncoder.encode(sign, "UTF-8"))
            }.getOrNull() ?: continue
            val url = result.optString("downloadUrl").takeIf { it.isNotEmpty() } ?: continue
            return runCatching { request(url, null, auth = false) }.getOrNull()
        }
        return null
    }

    // --- HTTP ------------------------------------------------------------------------------

    private fun get(path: String): JSONObject = getAny(path) as JSONObject
    private fun get2Array(path: String): JSONArray = getAny(path) as JSONArray

    private fun getAny(path: String): Any = JSONObject(request(API + path, null)).get("result")

    private fun postArray(path: String, form: String): JSONArray = JSONObject(request(API + path, form)).getJSONArray("result")

    private fun request(url: String, form: String?, auth: Boolean = true): String {
        val c = URL(url).openConnection() as HttpURLConnection
        if (auth) c.setRequestProperty("Authorization", "OAuth $token")
        c.setRequestProperty("X-Yandex-Music-Client", "YandexMusicAndroid/24023621")
        c.setRequestProperty("Accept-Language", "ru")
        if (form != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(form.toByteArray()) }
        }
        try {
            if (c.responseCode !in 200..299) {
                if (c.responseCode == 401 || c.responseCode == 403) signedIn = prefs.getString(KEY_TOKEN, null) != null
                throw Failure(c.responseCode)
            }
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val API = "https://api.music.yandex.net"
        /** The official Android app's id: the API only answers it (the same one Cassette uses). */
        const val CLIENT_ID = "23cabbbdc6cd418abb4b39c32c41195d"
        private const val KEY_TOKEN = "token"
        private const val KEY_UID = "uid"

        fun trackId(uri: String): String? =
            uri.takeIf { it.startsWith("yandex://") }?.substringAfterLast('/')?.substringBefore('?')

        fun isWave(uri: String?) = uri?.startsWith("yandex://track/") == true && uri.endsWith("?wave")
    }
}
