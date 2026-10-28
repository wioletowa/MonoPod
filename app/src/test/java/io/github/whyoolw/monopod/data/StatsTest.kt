package io.github.whyoolw.monopod.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsTest {
    private fun track(n: Int, added: Long = 0L) =
        Track(n.toLong(), "u$n", "T$n", null, null, null, null, null, null, null, 200_000L, "k", addedAt = added)

    private val songs = (1..6).map { track(it, added = it * 10L) }

    @Test
    fun countsAfterHalfOrFourMinutes() {
        assertFalse(countsAsPlayed(59_000, 120_000))
        assertTrue(countsAsPlayed(60_000, 120_000))
        // A 20-minute track counts after 4 minutes, not 10
        assertTrue(countsAsPlayed(240_000, 1_200_000))
        assertFalse(countsAsPlayed(1_000, 0))
    }

    @Test
    fun smartPlaylists() {
        val stats = mapOf(
            "u1" to StatEntity("u1", playCount = 2, lastPlayed = 100, rating = 5),
            "u2" to StatEntity("u2", playCount = 7, lastPlayed = 50, rating = 3),
            "u3" to StatEntity("u3", playCount = 2, lastPlayed = 300, rating = 4),
            "u4" to StatEntity("u4", rating = 4),
        )
        assertEquals(listOf("u2", "u3", "u1"), SmartPlaylists.topPlayed(songs, stats).map { it.uri })
        assertEquals(listOf("u1", "u3", "u4"), SmartPlaylists.topRated(songs, stats).map { it.uri })
        assertEquals(listOf("u3", "u1", "u2"), SmartPlaylists.recentlyPlayed(songs, stats).map { it.uri })
        assertEquals(listOf("u6", "u5"), SmartPlaylists.recentlyAdded(songs, limit = 2).map { it.uri })
    }
}
