package io.github.whyoolw.monopod.ui.menu

import android.content.res.Resources
import io.github.whyoolw.monopod.R
import io.github.whyoolw.monopod.data.Album
import io.github.whyoolw.monopod.data.LibraryRepository
import io.github.whyoolw.monopod.data.LibrarySnapshot
import io.github.whyoolw.monopod.data.SmartPlaylists
import io.github.whyoolw.monopod.data.StatsRepository
import io.github.whyoolw.monopod.data.Track

/**
 * Music menu pages, built from the library snapshot at the moment a page opens,
 * like the iPod: Artists → Albums → Songs, Genres → Artists → ..., Playlists → Songs.
 */
class LibraryMenus(
    private val res: Resources,
    private val library: LibraryRepository,
    private val stats: StatsRepository,
    /** Selecting a song: the list it was picked from and its position. */
    private val onPlay: (queue: List<Track>, index: Int) -> Page?,
) {
    private fun s(id: Int) = res.getString(id)
    private fun artistName(a: String?) = a ?: s(R.string.unknown_artist)
    private fun albumName(a: String?) = a ?: s(R.string.unknown_album)
    private fun genreName(g: String?) = g ?: s(R.string.unknown_genre)

    /** Opens [build] with the current library, or explains why the list is empty. */
    private fun page(title: Int, build: (LibrarySnapshot) -> Page): () -> Page = {
        val lib = library.snapshot
        when {
            !lib.isEmpty -> build(lib)
            library.scanProgress != null -> MessagePage(s(title), s(R.string.library_scanning))
            library.folders.isEmpty() -> MessagePage(s(title), s(R.string.library_no_folders))
            else -> MessagePage(s(title), s(R.string.library_no_music))
        }
    }

    fun music(coverFlow: MenuItem): ListPage = ListPage(
        s(R.string.menu_music),
        listOf(
            coverFlow,
            MenuItem(s(R.string.menu_playlists), onSelect = page(R.string.menu_playlists) { lib ->
                // Smart playlists first, like iTunes, then the .m3u files
                // Rebuilt on every read: the stats may still be loading, and plays/ratings change them
                fun smart(title: Int, pick: () -> List<Track>) = MenuItem(s(title)) {
                    ListPage(s(title)) {
                        val tracks = pick()
                        if (tracks.isEmpty()) listOf(MenuItem(s(R.string.library_no_songs), opensPage = false) { null })
                        else tracks.mapIndexed { i, t -> MenuItem(t.title, opensPage = false) { onPlay(tracks, i) } }
                    }
                }
                ListPage(
                    s(R.string.menu_playlists),
                    listOf(
                        smart(R.string.smart_top25) { SmartPlaylists.topPlayed(lib.songs, stats.stats) },
                        smart(R.string.smart_top_rated) { SmartPlaylists.topRated(lib.songs, stats.stats) },
                        smart(R.string.smart_recently_played) { SmartPlaylists.recentlyPlayed(lib.songs, stats.stats) },
                        smart(R.string.smart_recently_added) { SmartPlaylists.recentlyAdded(lib.songs) },
                    ) + lib.playlists.map { p -> MenuItem(p.name) { songs(p.name, p.tracks) } },
                )
            }),
            MenuItem(s(R.string.menu_artists), onSelect = page(R.string.menu_artists) { lib ->
                ListPage(s(R.string.menu_artists), lib.artists.map { a -> MenuItem(artistName(a)) { artist(lib, a) } })
            }),
            MenuItem(s(R.string.menu_albums), onSelect = page(R.string.menu_albums) { lib ->
                albums(s(R.string.menu_albums), lib.albums)
            }),
            MenuItem(s(R.string.menu_songs), onSelect = page(R.string.menu_songs) { lib ->
                songs(s(R.string.menu_songs), lib.songs)
            }),
            MenuItem(s(R.string.menu_genres), onSelect = page(R.string.menu_genres) { lib ->
                ListPage(s(R.string.menu_genres), lib.genres.map { g -> MenuItem(genreName(g)) { genre(lib, g) } })
            }),
        ),
    )

    private fun genre(lib: LibrarySnapshot, genre: String?): Page = ListPage(
        genreName(genre),
        listOf(MenuItem(s(R.string.all_songs)) { songs(genreName(genre), lib.songsIn(genre)) }) +
            lib.artistsIn(genre).map { a -> MenuItem(artistName(a)) { artist(lib, a) } },
    )

    private fun artist(lib: LibrarySnapshot, artist: String?): Page {
        val albums = lib.albumsBy(artist)
        // One album: skip straight to its songs, like the iPod does
        if (albums.size == 1) return songs(artistName(artist), lib.songsBy(artist))
        return ListPage(
            artistName(artist),
            listOf(MenuItem(s(R.string.all_songs), art = albums.map { it.coverPath }) { songs(artistName(artist), lib.songsBy(artist)) }) +
                albums.map { album -> albumItem(album) { songs(albumName(album.title), album.tracks.filter { it.artist == artist }) } },
            split = true,
        )
    }

    private fun albums(title: String, albums: List<Album>): Page =
        ListPage(title, albums.map { album -> albumItem(album) { songs(albumName(album.title), album.tracks) } }, split = true)

    /** An album row of a split list: its cover shows on the right half. */
    private fun albumItem(album: Album, open: () -> Page) =
        MenuItem(albumName(album.title), art = listOf(album.coverPath), onSelect = open)

    private fun songs(title: String, tracks: List<Track>): Page =
        if (tracks.isEmpty()) MessagePage(title, s(R.string.library_no_songs))
        else ListPage(title, tracks.mapIndexed { i, t -> MenuItem(t.title, opensPage = false) { onPlay(tracks, i) } })
}
