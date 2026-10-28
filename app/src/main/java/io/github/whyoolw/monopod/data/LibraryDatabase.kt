package io.github.whyoolw.monopod.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction

/** One audio file found in a library folder, with its tags. */
@Entity(tableName = "tracks", indices = [Index(value = ["uri"], unique = true), Index("root")])
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    /** Tree URI of the library folder the file was found in. */
    val root: String,
    /** Path inside that folder, "Album/01 Song.mp3"; used to resolve playlist entries. */
    val relPath: String,
    val lastModified: Long,
    val size: Long,
    val title: String,
    val artist: String?,
    val albumArtist: String?,
    val album: String?,
    val genre: String?,
    val trackNo: Int?,
    val discNo: Int?,
    val year: Int?,
    val durationMs: Long,
    val albumKey: String,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val name: String,
)

@Entity(
    tableName = "playlist_entries",
    primaryKeys = ["playlistId", "position"],
    foreignKeys = [
        ForeignKey(PlaylistEntity::class, ["id"], ["playlistId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class PlaylistEntryEntity(
    val playlistId: Long,
    val position: Int,
    val trackUri: String,
)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM tracks")
    suspend fun tracks(): List<TrackEntity>

    @Query("SELECT uri, lastModified, size FROM tracks")
    suspend fun fileStamps(): List<FileStamp>

    /** REPLACE drops the old row with the same uri, so a changed file is simply re-inserted. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<TrackEntity>)

    @Query("DELETE FROM tracks WHERE uri IN (:uris)")
    suspend fun deleteTracks(uris: List<String>)

    @Query("SELECT * FROM playlists")
    suspend fun playlists(): List<PlaylistEntity>

    @Query("SELECT * FROM playlist_entries ORDER BY playlistId, position")
    suspend fun playlistEntries(): List<PlaylistEntryEntity>

    @Query("DELETE FROM playlists")
    suspend fun deletePlaylists()

    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Insert
    suspend fun insertEntries(entries: List<PlaylistEntryEntity>)

    @Query("SELECT * FROM lyrics_files")
    suspend fun lyricsFiles(): List<LyricsFileEntity>

    @Query("DELETE FROM lyrics_files")
    suspend fun deleteLyricsFiles()

    @Insert
    suspend fun insertLyricsFiles(files: List<LyricsFileEntity>)

    @Transaction
    suspend fun replaceLyricsFiles(files: List<LyricsFileEntity>) {
        deleteLyricsFiles()
        insertLyricsFiles(files)
    }

    @Transaction
    suspend fun replacePlaylists(playlists: List<Pair<PlaylistEntity, List<String>>>) {
        deletePlaylists()
        for ((playlist, uris) in playlists) {
            val id = insertPlaylist(playlist)
            insertEntries(uris.mapIndexed { i, uri -> PlaylistEntryEntity(id, i, uri) })
        }
    }
}

/** An .lrc file next to a song with the same name. Rebuilt on every scan. */
@Entity(tableName = "lyrics_files")
data class LyricsFileEntity(
    @PrimaryKey val trackUri: String,
    val lrcUri: String,
)

data class FileStamp(val uri: String, val lastModified: Long, val size: Long)

@Database(
    entities = [TrackEntity::class, PlaylistEntity::class, PlaylistEntryEntity::class, LyricsFileEntity::class],
    version = 2,
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao

    companion object {
        fun open(context: Context): LibraryDatabase =
            Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db")
                // The library is only a cache of what is on disk: a rescan rebuilds it
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
