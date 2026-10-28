package io.github.whyoolw.monopod.data

import io.github.whyoolw.monopod.Prefs
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

data class LibraryFolder(val uri: Uri, val name: String)

data class ScanProgress(val done: Int, val total: Int)

/**
 * Owns the music library: which folders it is made of, the scan, and the in-memory
 * snapshot the menus read. State is Compose state so the UI follows it directly.
 */
class LibraryRepository(private val context: Context, private val scope: CoroutineScope) {
    private val dao = LibraryDatabase.open(context).dao()
    private val scanner = LibraryScanner(context, dao)
    private var scanJob: Job? = null

    var snapshot by mutableStateOf(LibrarySnapshot.EMPTY)
        private set

    /** Non-null while a scan runs. */
    var scanProgress by mutableStateOf<ScanProgress?>(null)
        private set

    /** The folders the user picked; persisted as SAF URI grants, nothing else to store. */
    var folders by mutableStateOf(readFolders())
        private set

    init {
        scope.launch(Dispatchers.IO) {
            reload()
            rescan()
        }
    }

    fun addFolder(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        folders = readFolders()
        rescan()
    }

    fun removeFolder(folder: LibraryFolder) {
        context.contentResolver.releasePersistableUriPermission(folder.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        folders = readFolders()
        rescan()
    }

    @Synchronized
    fun rescan() {
        val previous = scanJob
        scanJob = scope.launch(Dispatchers.IO) {
            previous?.cancelAndJoin()
            scanProgress = ScanProgress(0, 0)
            try {
                scanner.scan(folders.map { it.uri }) { done, total -> scanProgress = ScanProgress(done, total) }
                reload()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("LibraryRepository", "scan failed", e)
            } finally {
                scanProgress = null
            }
        }
    }

    private suspend fun reload() {
        val tracks = dao.tracks().map { it.toTrack() }
        val byUri = tracks.associateBy { it.uri }
        val entries = dao.playlistEntries().groupBy { it.playlistId }
        val playlists = dao.playlists().map { p ->
            Playlist(p.name, entries[p.id].orEmpty().mapNotNull { byUri[it.trackUri] })
        }
        val lyrics = dao.lyricsFiles().associate { it.trackUri to it.lrcUri }
        val merge = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE).getBoolean(Prefs.MERGE_ALBUMS, false)
        snapshot = LibrarySnapshot(tracks, playlists, lyrics, { key ->
            coverFile(context, key).takeIf { it.exists() }?.path
        }, mergeAlbums = merge)
    }

    /** Rebuilds the menus after a library setting changed (album merging); no rescan. */
    fun rebuild() {
        scope.launch(Dispatchers.IO) { reload() }
    }

    private fun readFolders(): List<LibraryFolder> =
        context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
            .map { LibraryFolder(it.uri, folderName(it.uri)) }
            .sortedBy { it.name.lowercase() }

    /** "primary:Music/Rock" → "Rock"; the volume root shows as its volume name. */
    private fun folderName(uri: Uri): String {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val path = docId.substringAfter(':', "")
        return path.substringAfterLast('/').ifEmpty { docId.substringBefore(':') }
    }
}

private fun TrackEntity.toTrack() = Track(
    id = id, uri = uri, title = title, artist = artist, albumArtist = albumArtist, album = album,
    genre = genre, trackNo = trackNo, discNo = discNo, year = year, durationMs = durationMs, albumKey = albumKey,
    addedAt = lastModified,
)
