package io.github.whyoolw.monopod.ui.menu

import android.content.res.Resources
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.whyoolw.monopod.R
import io.github.whyoolw.monopod.data.Album
import io.github.whyoolw.monopod.data.Track
import io.github.whyoolw.monopod.playback.Playback
import io.github.whyoolw.monopod.playback.toMediaItem
import io.github.whyoolw.monopod.playback.toWaveItem
import io.github.whyoolw.monopod.spotify.SpotifyCovers
import io.github.whyoolw.monopod.ui.coverflow.CoverFlowPage
import io.github.whyoolw.monopod.yandex.YandexCollection
import io.github.whyoolw.monopod.yandex.YandexMusic
import io.github.whyoolw.monopod.yandex.YandexTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Yandex Music branch: My Wave, your library and the personal mixes, as Cover Flows.
 * Songs play in our own player, so the EQ, crossfade and lyrics work for them too.
 */
class YandexMenus(
    private val res: Resources,
    private val yandex: YandexMusic,
    private val playback: Playback,
    private val nowPlaying: () -> Page,
    /** Called before a song starts, so Spotify stops. */
    private val beforePlay: () -> Unit,
) {
    private val scope = MainScope()
    private fun s(id: Int) = res.getString(id)

    private inner class Shelf(val title: String, private val list: () -> List<YandexCollection>) {
        val albums = mutableStateListOf<Album>()
        var status by mutableStateOf<String?>(s(R.string.spotify_loading))
        private val collections = HashMap<String, YandexCollection>()
        /** Songs per cover, kept with their pictures for the queue. */
        private val songs = HashMap<String, List<YandexTrack>>()
        private val loading = HashSet<String>()

        fun page(): Page {
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching(list) }
                result.onSuccess { found ->
                    found.forEach { collections[it.key] = it }
                    albums += found.map { Album(it.key, it.title, it.subtitle, null, emptyList(), it.cover ?: likedCover(it.key)) }
                    if (found.isEmpty()) status = s(R.string.spotify_empty)
                }.onFailure { status = failure(it) }
            }
            return CoverFlowPage(
                title, { albums.toList() }, { status },
                onOpen = ::load,
                onPlay = { tracks, index -> play(tracks, index) },
            )
        }

        private fun load(album: Album) {
            if (album.tracks.isNotEmpty() || !loading.add(album.key)) return
            val c = collections[album.key] ?: return
            scope.launch {
                val found = withContext(Dispatchers.IO) { runCatching(c.load).getOrDefault(emptyList()) }
                loading.remove(album.key)
                songs[album.key] = found
                val i = albums.indexOfFirst { it.key == album.key }
                if (i >= 0) albums[i] = albums[i].copy(tracks = found.map { it.toTrack(album.key) })
            }
        }

        private fun play(tracks: List<Track>, index: Int) {
            val list = songs[tracks.firstOrNull()?.albumKey] ?: return
            beforePlay()
            playback.playItems(list.map { it.toMediaItem() }, index)
        }
    }

    // A new shelf per visit, so a page sliding away never sees its list emptied under it
    private fun library() = Shelf(s(R.string.spotify_playlists)) { yandex.library(s(R.string.yandex_liked), s(R.string.spotify_artist)) }
    private fun home() = Shelf(s(R.string.spotify_recs)) { yandex.recommendations() }

    fun root(): Page = ListPage(s(R.string.yandex_music)) {
        if (!yandex.signedIn) {
            listOf(MenuItem(s(R.string.spotify_login), opensPage = false) { yandex.login(); null })
        } else listOf(
            MenuItem(s(R.string.yandex_wave), opensPage = false) { startWave(); nowPlaying() },
            MenuItem(s(R.string.spotify_playlists)) { library().page() },
            MenuItem(s(R.string.spotify_recs)) { home().page() },
            MenuItem(s(R.string.spotify_logout), opensPage = false, backAfter = true) {
                playback.clearIf { it.startsWith("yandex://") }
                yandex.signOut(); null
            },
        )
    }

    /** My Wave: the first songs now, the player asks for more as it nears the end. */
    private fun startWave() {
        beforePlay()
        scope.launch {
            val first = withContext(Dispatchers.IO) { runCatching { yandex.waveTracks(null) }.getOrDefault(emptyList()) }
            playback.playItems(first.map { it.toWaveItem() }, 0)
        }
    }

    /** "Мне нравится" has no picture of its own: a heart in Yandex colors. */
    private fun likedCover(key: String): String? =
        if (key == "yandex:liked") SpotifyCovers.liked(File(yandex.cacheDir, "covers"), 0xFFFF5C33.toInt(), 0xFFFFCC00.toInt()) else null

    private fun failure(e: Throwable): String {
        val why = (e as? YandexMusic.Failure)?.let { "HTTP ${it.code}" } ?: e.javaClass.simpleName
        return res.getString(R.string.spotify_failed, why)
    }

    private fun YandexTrack.toTrack(albumKey: String) = Track(
        id = 0L, uri = uri, title = title, artist = artist, albumArtist = null, album = album, genre = null,
        trackNo = null, discNo = null, year = null, durationMs = durationMs, albumKey = albumKey,
    )
}
