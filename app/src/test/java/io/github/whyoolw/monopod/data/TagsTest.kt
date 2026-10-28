package io.github.whyoolw.monopod.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TagsTest {
    @Test
    fun numbersAndYears() {
        assertEquals(3, parseNumber("3/12"))
        assertEquals(7, parseNumber(" 07 "))
        assertNull(parseNumber("A1"))
        assertEquals(2012, parseYear("2012-05-01"))
        assertEquals(1999, parseYear("19990101T000000.000Z"))
        assertNull(parseYear("0"))
    }

    @Test
    fun id3Genres() {
        assertEquals("Rock", normalizeGenre("(17)"))
        assertEquals("Rock", normalizeGenre("17"))
        assertEquals("Indie", normalizeGenre("(17)Indie"))
        assertEquals("Synthwave", normalizeGenre(" Synthwave "))
        assertNull(normalizeGenre("  "))
    }

    @Test
    fun albumKeyIgnoresCaseAndPrefersAlbumArtist() {
        assertEquals(albumKey("Various", "A", "Hits"), albumKey("various", "B", "HITS "))
        assertEquals(albumKey(null, "Air", "Moon Safari"), albumKey("Air", "Air", "moon safari"))
    }

    @Test
    fun m3uSkipsCommentsAndBom() {
        val text = "\uFEFF#EXTM3U\n#EXTINF:123,Song\n01.mp3\n\n  sub/02.flac  \n"
        assertEquals(listOf("01.mp3", "sub/02.flac"), parseM3u(text))
    }

    @Test
    fun playlistEntriesResolve() {
        val files = mapOf(
            "Air/Moon Safari/01 La femme d'argent.mp3" to "u1",
            "Кино/Группа крови/01 Группа крови.flac" to "u2",
        )
        // relative to the playlist's own directory
        assertEquals("u1", resolvePlaylistEntry("Moon Safari/01 La femme d'argent.mp3", "Air", files))
        assertEquals("u2", resolvePlaylistEntry("../Кино/Группа крови/01 Группа крови.flac", "Playlists", files))
        // Windows path from another computer
        assertEquals("u1", resolvePlaylistEntry("C:\\Users\\me\\Music\\Air\\Moon Safari\\01 La femme d'argent.mp3", "", files))
        // absolute Android path and file:// URL
        assertEquals("u2", resolvePlaylistEntry("/storage/emulated/0/Music/Кино/Группа крови/01 Группа крови.flac", "", files))
        assertEquals("u1", resolvePlaylistEntry("file:///sdcard/Music/Air/Moon%20Safari/01%20La%20femme%20d'argent.mp3", "", files))
        assertNull(resolvePlaylistEntry("http://radio.example/stream", "", files))
        assertNull(resolvePlaylistEntry("missing.mp3", "", files))
    }
}
