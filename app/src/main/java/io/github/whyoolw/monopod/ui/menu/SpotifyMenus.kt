package io.github.whyoolw.monopod.ui.menu

import android.content.res.Resources
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.spotify.protocol.types.ListItem
import io.github.whyoolw.monopod.R
import io.github.whyoolw.monopod.data.Album
import io.github.whyoolw.monopod.data.Track
import io.github.whyoolw.monopod.spotify.Spotify
import io.github.whyoolw.monopod.spotify.SpotifyApi
import io.github.whyoolw.monopod.spotify.SpotifyCovers
import io.github.whyoolw.monopod.spotify.SpotifyTrack
import io.github.whyoolw.monopod.ui.coverflow.CoverFlowPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Spotify branch of the menu, listening only (no search): your library or the Spotify home
 * page, each as a Cover Flow; flipping a cover shows its songs, picking one plays it in the
 * Spotify app. [nowPlaying] is the Spotify Now Playing page.
 */
class SpotifyMenus(
    private val res: Resources,
    private val spotify: Spotify,
    private val nowPlaying: () -> Page,
    /** Called before Spotify starts, so the local player stops. */
    private val beforePlay: () -> Unit,
) {
    private val scope: CoroutineScope = MainScope()
    private fun s(id: Int) = res.getString(id)

    /**
     * One wall of covers; its songs load when a cover is flipped. A new shelf for every visit,
     * so a page still sliding away never sees its list emptied under it.
     */
    private inner class Shelf(val title: String, private val loadCovers: suspend Shelf.() -> Unit) {
        val albums = mutableStateListOf<Album>()
        var status by mutableStateOf<String?>(s(R.string.spotify_loading))
        /** Cover key → how to list its songs, and the Spotify context it plays in. */
        val loaders = HashMap<String, () -> List<SpotifyTrack>>()
        val contexts = HashMap<String, String>()
        private val loading = HashSet<String>()

        fun page() = CoverFlowPage(
            title, { albums.toList() }, { status },
            onOpen = ::loadTracks,
            onPlay = { tracks, index -> play(tracks, index) },
        ).also { it.remote = true; load() }

        private fun load() {
            scope.launch {
                runCatching { loadCovers() }.onFailure { if (albums.isEmpty()) status = failure(it) }
                if (albums.isEmpty() && status == s(R.string.spotify_loading)) status = s(R.string.spotify_empty)
            }
        }

        /** Songs on the back of a cover; lists Spotify will not show get one row that plays them whole. */
        private fun loadTracks(album: Album) {
            if (album.tracks.isNotEmpty() || !loading.add(album.key)) return
            val load = loaders[album.key] ?: { emptyList() }
            scope.launch {
                val songs = withContext(Dispatchers.IO) { runCatching(load).getOrDefault(emptyList()) }
                    .map { it.toTrack(album.key) }
                    .ifEmpty { listOf(playWhole(album.key)) }
                loading.remove(album.key)
                val i = albums.indexOfFirst { it.key == album.key }
                if (i >= 0) albums[i] = albums[i].copy(tracks = songs)
            }
        }

        private fun play(tracks: List<Track>, index: Int) {
            beforePlay()
            val picked = tracks[index]
            val context = contexts[picked.albumKey]
            when {
                picked.uri == picked.albumKey -> spotify.playUri(picked.uri)
                // If Spotify will not start the list there, the song plays with the rest queued after it
                context != null -> spotify.playContext(context, index, picked.uri) { spotify.playTracks(tracks.map { it.uri }, index) }
                else -> spotify.playTracks(tracks.map { it.uri }, index)
            }
        }

        /** Remembers how a cover lists and plays its songs, from the kind of Spotify link it is. */
        fun register(uri: String, listSongs: (() -> List<SpotifyTrack>)? = null) {
            val id = uri.substringAfterLast(':')
            loaders[uri] = listSongs ?: when {
                uri.startsWith("spotify:playlist:") -> { { spotify.api.playlistTracks(id) } }
                uri.startsWith("spotify:album:") -> { { spotify.api.albumTracks(id) } }
                else -> { { emptyList() } }
            }
            if (uri.startsWith("spotify:playlist:") || uri.startsWith("spotify:album:")) contexts[uri] = uri
        }

        fun setCover(key: String, path: String) {
            val i = albums.indexOfFirst { it.key == key }
            if (i >= 0) albums[i] = albums[i].copy(coverPath = path)
        }
    }

    fun root(): Page = ListPage(s(R.string.spotify)) {
        if (!spotify.hasClientId) {
            // Each user signs in through their own Spotify app (Client ID)
            listOf(MenuItem(s(R.string.spotify_setup), opensPage = false) { spotify.setup(); null })
        } else if (!spotify.signedIn) {
            listOf(
                MenuItem(s(R.string.spotify_login), opensPage = false) { spotify.login(); null },
                MenuItem(s(R.string.spotify_setup), opensPage = false) { spotify.setup(); null },
            )
        } else listOfNotNull(
            // A permission was added since this sign-in (playing from a chosen song)
            MenuItem(s(R.string.spotify_relogin), opensPage = false) { spotify.login(); null }.takeIf { spotify.auth.needsNewLogin },
            MenuItem(s(R.string.spotify_playlists)) { library().page() },
            MenuItem(s(R.string.spotify_recs)) { home().page() },
            MenuItem(s(R.string.spotify_logout), opensPage = false, backAfter = true) { spotify.signOut(); null },
        )
    }

    /** Everything in the Spotify library: liked songs, playlists (Spotify's own too), albums, artists. */
    private fun library() = Shelf(s(R.string.spotify_playlists)) {
        val liked = withContext(Dispatchers.IO) { SpotifyCovers.liked(spotify.coverDir) }
        register(LIKED) { spotify.api.likedTracks() }
        // Liked Songs play as Spotify's own collection, so next and previous walk through it
        withContext(Dispatchers.IO) { runCatching { spotify.api.userId() }.getOrNull() }
            ?.let { contexts[LIKED] = "spotify:user:$it:collection" }
        albums += Album(LIKED, s(R.string.spotify_liked), s(R.string.spotify), null, emptyList(), liked)
        val items = withContext(Dispatchers.IO) {
            val lists = spotify.api.myPlaylists()
            val saved = runCatching { spotify.api.savedAlbums() }.getOrDefault(emptyList())
            // Needs the newer sign-in (user-follow-read); an older login just shows no artists
            val artists = runCatching { spotify.api.followedArtists(s(R.string.spotify_artist)) }.getOrDefault(emptyList())
            lists + saved + artists
        }
        items.forEach { register(it.uri) }
        albums += items.map { Album(it.uri, it.name, it.owner, null, emptyList(), cachedCover(it.id)) }
        // Covers come in one by one after the wall is up
        items.forEach { p ->
            val url = p.imageUrl ?: return@forEach
            val file = coverFile(p.id)
            if (file.exists()) return@forEach
            if (withContext(Dispatchers.IO) { runCatching { spotify.api.download(url, file) }.isSuccess }) setCover(p.uri, file.path)
        }
    }

    /** The Spotify home page as the Spotify app shows it: what its sections hold, with their covers. */
    private fun home() = Shelf(s(R.string.spotify_recs)) {
        val found = LinkedHashMap<String, ListItem>()
        for (section in spotify.homeSections()) {
            if (!section.hasChildren) {
                if (section.playable && section.uri.isNotEmpty()) found.putIfAbsent(section.uri, section)
                continue
            }
            for (item in runCatching { spotify.children(section) }.getOrDefault(emptyList())) {
                if (item.playable && item.uri.isNotEmpty()) found.putIfAbsent(item.uri, item)
            }
        }
        val items = found.values.toList()
        items.forEach { register(it.uri) }
        albums += items.map { Album(it.uri, it.title, it.subtitle, null, emptyList(), cachedCover(it.uri.substringAfterLast(':'))) }
        items.forEach { item ->
            val image = item.imageUri ?: return@forEach
            val file = coverFile(item.uri.substringAfterLast(':'))
            if (!file.exists() && spotify.saveImage(image, file)) setCover(item.uri, file.path)
        }
    }

    private fun coverFile(id: String) = File(spotify.coverDir, "pl_$id.jpg")
    private fun cachedCover(id: String) = coverFile(id).takeIf { it.exists() }?.path

    private fun failure(e: Throwable): String {
        if (e.message == Spotify.ERROR_NOT_INSTALLED) return s(R.string.spotify_not_installed)
        val why = (e as? SpotifyApi.Failure)?.let { "HTTP ${it.code}" } ?: e.javaClass.simpleName
        return res.getString(R.string.spotify_failed, why)
    }

    /** The one row of a list Spotify will not show (its own mixes, artists): plays it whole. */
    private fun playWhole(key: String) = Track(
        id = 0L, uri = key, title = "▶ " + s(R.string.spotify_play_all), artist = null, albumArtist = null, album = null,
        genre = null, trackNo = null, discNo = null, year = null, durationMs = 0L, albumKey = key,
    )

    private fun SpotifyTrack.toTrack(albumKey: String) = Track(
        id = 0L, uri = uri, title = name, artist = artist, albumArtist = null, album = album, genre = null,
        trackNo = null, discNo = null, year = null, durationMs = durationMs, albumKey = albumKey,
    )

    companion object {
        private const val LIKED = "spotify:home:liked"
    }
}
