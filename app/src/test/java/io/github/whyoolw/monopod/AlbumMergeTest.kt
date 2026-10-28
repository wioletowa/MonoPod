package io.github.whyoolw.monopod

import io.github.whyoolw.monopod.data.LibrarySnapshot
import io.github.whyoolw.monopod.data.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumMergeTest {
    private fun t(title: String, artist: String, album: String, key: String) = Track(
        id = 0, uri = "$key/$title", title = title, artist = artist, albumArtist = null, album = album,
        genre = null, trackNo = null, discNo = null, year = null, durationMs = 0, albumKey = key,
    )

    @Test
    fun splitCreditsBecomeOneAlbum() {
        val tracks = listOf(
            t("+1up", "5opka, Grechko", "5opka", "a1"),
            t("1000 жизней", "5opka/ Grechko", "5opka", "a2"),
            t("Головоломка", "5opka", "5opka", "a3"),
            t("CLAY", "5opka feat. Someone", "5opka", "a4"),
        )
        val lib = LibrarySnapshot(tracks, emptyList(), emptyMap(), { null }, mergeAlbums = true)
        assertEquals(1, lib.albums.size)
        assertEquals(4, lib.albums[0].tracks.size)
        assertEquals("5opka", lib.albums[0].artist)
    }

    @Test
    fun sameTitleOfOtherArtistsStaysApart() {
        val tracks = listOf(
            t("A", "Kino", "Greatest Hits", "k"),
            t("B", "Queen", "Greatest Hits", "q"),
            t("C", "Kino", "Звезда", "z"),
        )
        val lib = LibrarySnapshot(tracks, emptyList(), emptyMap(), { null }, mergeAlbums = true)
        assertEquals(3, lib.albums.size)
    }

    @Test
    fun offKeepsScannedAlbums() {
        val tracks = listOf(t("x", "5opka, Grechko", "5opka", "a1"), t("y", "5opka", "5opka", "a2"))
        assertEquals(2, LibrarySnapshot(tracks, emptyList(), emptyMap(), { null }).albums.size)
    }
}
