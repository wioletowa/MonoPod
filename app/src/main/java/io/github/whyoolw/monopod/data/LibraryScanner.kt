package io.github.whyoolw.monopod.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

private const val TAG = "LibraryScanner"

private val AUDIO_EXT = setOf(
    "mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "aiff", "aif", "wma", "mka", "ape", "wv",
)
private val PLAYLIST_EXT = setOf("m3u", "m3u8")
private const val LYRICS_EXT = "lrc"
private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp")

/** Folder images that count as album art, best first. */
private val COVER_NAMES = listOf("cover", "folder", "front", "album", "albumart")

private const val COVER_SIZE = 512

private class AudioFile(val uri: String, val root: String, val relPath: String, val mtime: Long, val size: Long)
private class PlaylistFile(val uri: Uri, val root: String, val dir: String, val name: String)

private class FolderContents {
    val audio = mutableListOf<AudioFile>()
    val playlists = mutableListOf<PlaylistFile>()
    /** "root\u0000path without extension" → .lrc file URI. */
    val lyrics = mutableMapOf<String, String>()
    /** Directory path inside a root ("root\u0000dir") → best cover image in it. */
    val dirImages = mutableMapOf<String, Pair<Int, Uri>>()
}

/** Album covers live in app storage, one JPEG per album key. */
fun coverFile(context: Context, albumKey: String): File {
    val hash = MessageDigest.getInstance("SHA-1").digest(albumKey.toByteArray())
        .joinToString("") { "%02x".format(it) }
    return File(File(context.filesDir, "covers"), "$hash.jpg")
}

/**
 * Walks the library folders through the Storage Access Framework, reads tags of new and
 * changed files, saves album covers and re-reads playlists. Unchanged files are skipped by
 * modification time and size, so rescans are cheap.
 */
class LibraryScanner(private val context: Context, private val dao: LibraryDao) {
    private val resolver = context.contentResolver

    suspend fun scan(roots: List<Uri>, onProgress: (done: Int, total: Int) -> Unit) {
        val found = FolderContents()
        for (root in roots) {
            coroutineContext.ensureActive()
            walk(root, found)
        }

        val known = dao.fileStamps().associateBy { it.uri }
        val present = found.audio.associateBy { it.uri }
        // SQLite caps bound parameters, so remove in chunks
        known.keys.filter { it !in present }.chunked(500).forEach { dao.deleteTracks(it) }
        val changed = found.audio.filter { f ->
            val k = known[f.uri]
            k == null || k.lastModified != f.mtime || k.size != f.size
        }
        Log.i(TAG, "scan: ${found.audio.size} files, ${changed.size} new or changed")

        File(context.filesDir, "covers").mkdirs()
        val done = AtomicInteger(0)
        onProgress(0, changed.size)

        // Tag reading is I/O bound; a few in parallel keeps it quick without hammering storage
        for (batch in changed.chunked(64)) {
            coroutineContext.ensureActive()
            val tracks = coroutineScope {
                batch.map { f ->
                    async(Dispatchers.IO) {
                        // Tracks keep looking for a picture until one of the album has been saved;
                        // the first track read is not necessarily the one with the art
                        readTrack(f) { albumKey -> !coverFile(context, albumKey).exists() }.also { onProgress(done.incrementAndGet(), changed.size) }
                    }
                }.awaitAll()
            }
            dao.insertTracks(tracks)
        }

        saveFolderCovers(found)
        deleteOrphanCovers()
        savePlaylists(found)
        dao.replaceLyricsFiles(
            found.audio.mapNotNull { f ->
                found.lyrics["${f.root}\u0000${f.relPath.substringBeforeLast('.')}"]?.let { LyricsFileEntity(f.uri, it) }
            },
        )
    }

    /** Covers of albums that left the library (folder removed, files deleted). */
    private suspend fun deleteOrphanCovers() {
        val keep = dao.tracks().map { coverFile(context, it.albumKey).name }.toSet()
        File(context.filesDir, "covers").listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
    }

    private fun walk(root: Uri, into: FolderContents) {
        val rootKey = root.toString()
        val stack = ArrayDeque<Pair<String, String>>()
        val rootDoc = try {
            DocumentsContract.getTreeDocumentId(root)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "not a tree uri: $root", e); return
        }
        stack.add(rootDoc to "")
        while (stack.isNotEmpty()) {
            val (docId, dir) = stack.removeLast()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(root, docId)
            val cursor = try {
                resolver.query(children, PROJECTION, null, null, null)
            } catch (e: Exception) {
                // Permission revoked or the folder is gone: skip it, its tracks get cleaned up
                Log.w(TAG, "cannot list $children", e); null
            } ?: continue
            cursor.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    if (name.startsWith(".")) continue
                    val rel = if (dir.isEmpty()) name else "$dir/$name"
                    if (mime == Document.MIME_TYPE_DIR) {
                        stack.add(id to rel); continue
                    }
                    val ext = name.substringAfterLast('.', "").lowercase()
                    val base = name.substringBeforeLast('.').lowercase()
                    val uri = DocumentsContract.buildDocumentUriUsingTree(root, id)
                    when {
                        ext == LYRICS_EXT -> into.lyrics["$rootKey\u0000${rel.substringBeforeLast('.')}"] = uri.toString()
                        ext in PLAYLIST_EXT -> into.playlists += PlaylistFile(uri, rootKey, dir, name.substringBeforeLast('.'))
                        ext in AUDIO_EXT -> into.audio += AudioFile(uri.toString(), rootKey, rel, c.getLong(3), c.getLong(4))
                        ext in IMAGE_EXT -> {
                            val rank = COVER_NAMES.indexOfFirst { base == it || base.startsWith("${it}_") }
                            if (rank >= 0) {
                                val key = "$rootKey\u0000$dir"
                                val best = into.dirImages[key]
                                if (best == null || rank < best.first) into.dirImages[key] = rank to uri
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun readTrack(file: AudioFile, wantsCover: suspend (String) -> Boolean): TrackEntity =
        MediaMetadataRetriever().use { r ->
            val fileName = file.relPath.substringAfterLast('/').substringBeforeLast('.')
            val ok = try {
                r.setDataSource(context, Uri.parse(file.uri)); true
            } catch (e: Exception) {
                Log.w(TAG, "cannot read tags of ${file.relPath}", e); false
            }
            fun key(k: Int) = if (ok) r.extractMetadata(k).tag() else null

            val artist = key(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val albumArtist = key(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            val album = key(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val track = TrackEntity(
                uri = file.uri,
                root = file.root,
                relPath = file.relPath,
                lastModified = file.mtime,
                size = file.size,
                title = key(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: fileName,
                artist = artist,
                albumArtist = albumArtist,
                album = album,
                genre = normalizeGenre(key(MediaMetadataRetriever.METADATA_KEY_GENRE)),
                trackNo = parseNumber(key(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)),
                discNo = parseNumber(key(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)),
                year = parseYear(key(MediaMetadataRetriever.METADATA_KEY_YEAR))
                    ?: parseYear(key(MediaMetadataRetriever.METADATA_KEY_DATE)),
                durationMs = key(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                albumKey = albumKey(albumArtist, artist, album),
            )
            if (ok && wantsCover(track.albumKey)) {
                r.embeddedPicture?.let { saveCover(it, track.albumKey) }
            }
            track
        }

    /** Albums still without art get the folder image (cover.jpg, folder.jpg, ...) next to their files. */
    private suspend fun saveFolderCovers(found: FolderContents) {
        if (found.dirImages.isEmpty()) return
        val done = mutableSetOf<String>()
        for (t in dao.tracks()) {
            if (t.albumKey in done) continue
            done += t.albumKey
            if (coverFile(context, t.albumKey).exists()) continue
            val image = found.dirImages["${t.root}\u0000${t.relPath.substringBeforeLast('/', "")}"] ?: continue
            try {
                resolver.openInputStream(image.second)?.use { saveCover(it.readBytes(), t.albumKey) }
            } catch (e: Exception) {
                Log.w(TAG, "cannot read ${image.second}", e)
            }
        }
    }

    private fun saveCover(bytes: ByteArray, albumKey: String) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= COVER_SIZE && bounds.outHeight / (sample * 2) >= COVER_SIZE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return
        val scale = COVER_SIZE.toFloat() / maxOf(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
        } else decoded
        val target = coverFile(context, albumKey)
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        tmp.renameTo(target)
    }

    private suspend fun savePlaylists(found: FolderContents) {
        val byRoot = found.audio.groupBy { it.root }.mapValues { (_, files) -> files.associate { it.relPath to it.uri } }
        val playlists = found.playlists.mapNotNull { p ->
            val text = try {
                resolver.openInputStream(p.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (e: Exception) {
                Log.w(TAG, "cannot read playlist ${p.uri}", e); null
            } ?: return@mapNotNull null
            val files = byRoot[p.root].orEmpty()
            val uris = parseM3u(text).mapNotNull { resolvePlaylistEntry(it, p.dir, files) }
            PlaylistEntity(uri = p.uri.toString(), name = p.name) to uris
        }
        dao.replacePlaylists(playlists)
    }

    private companion object {
        val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_SIZE,
        )
    }
}
