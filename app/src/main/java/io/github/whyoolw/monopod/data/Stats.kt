package io.github.whyoolw.monopod.data

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** What the user did with a song: how often and when it played, and its stars. */
@Entity(tableName = "stats")
data class StatEntity(
    @PrimaryKey val trackUri: String,
    val playCount: Int = 0,
    val lastPlayed: Long = 0L,
    /** 0..5 stars. */
    val rating: Int = 0,
)

@Dao
interface StatsDao {
    @Query("SELECT * FROM stats")
    suspend fun all(): List<StatEntity>

    @Upsert
    suspend fun put(stat: StatEntity)
}

/**
 * Kept in its own database, apart from the library index: the index is a cache that is
 * thrown away and rebuilt on schema changes, these numbers are the user's own.
 */
@Database(entities = [StatEntity::class], version = 1)
abstract class StatsDatabase : RoomDatabase() {
    abstract fun dao(): StatsDao
}

class StatsRepository(context: Context, private val scope: CoroutineScope) {
    private val dao = Room.databaseBuilder(context, StatsDatabase::class.java, "stats.db").build().dao()

    /** Song URI → stats; Compose state, so screens follow changes. */
    var stats by mutableStateOf<Map<String, StatEntity>>(emptyMap())
        private set

    init {
        scope.launch(Dispatchers.IO) {
            val loaded = dao.all().associateBy { it.trackUri }
            Log.i("Stats", "loaded ${loaded.size} songs with stats")
            // Keep anything recorded while loading
            stats = loaded + stats
        }
    }

    fun rating(uri: String): Int = stats[uri]?.rating ?: 0

    fun countPlay(uri: String) = update(uri) { it.copy(playCount = it.playCount + 1, lastPlayed = System.currentTimeMillis()) }

    fun setRating(uri: String, stars: Int) = update(uri) { it.copy(rating = stars.coerceIn(0, 5)) }

    private fun update(uri: String, change: (StatEntity) -> StatEntity) {
        val updated = change(stats[uri] ?: StatEntity(uri))
        stats = stats + (uri to updated)
        scope.launch(Dispatchers.IO) { dao.put(updated) }
    }
}

/** iTunes-style smart playlists, built from the library and the stats. */
object SmartPlaylists {
    fun topPlayed(songs: List<Track>, stats: Map<String, StatEntity>, limit: Int = 25): List<Track> =
        songs.filter { (stats[it.uri]?.playCount ?: 0) > 0 }
            .sortedWith(compareByDescending<Track> { stats[it.uri]!!.playCount }.thenByDescending { stats[it.uri]!!.lastPlayed })
            .take(limit)

    /** Four stars and up, best first. */
    fun topRated(songs: List<Track>, stats: Map<String, StatEntity>): List<Track> =
        songs.filter { (stats[it.uri]?.rating ?: 0) >= 4 }
            .sortedWith(compareByDescending<Track> { stats[it.uri]!!.rating }.thenByDescending { stats[it.uri]!!.playCount })

    fun recentlyPlayed(songs: List<Track>, stats: Map<String, StatEntity>, limit: Int = 25): List<Track> =
        songs.filter { (stats[it.uri]?.lastPlayed ?: 0L) > 0L }
            .sortedByDescending { stats[it.uri]!!.lastPlayed }
            .take(limit)

    /** Newest files first, by when they landed in the folder. */
    fun recentlyAdded(songs: List<Track>, limit: Int = 50): List<Track> =
        songs.sortedByDescending { it.addedAt }.take(limit)
}

/** A play counts once half the song has actually been listened to, or four minutes of a long one. */
fun countsAsPlayed(listenedMs: Long, durationMs: Long): Boolean =
    durationMs > 0 && listenedMs >= minOf(durationMs / 2, 240_000L)
