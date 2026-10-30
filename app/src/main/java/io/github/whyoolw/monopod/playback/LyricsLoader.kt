package io.github.whyoolw.monopod.playback

import io.github.whyoolw.monopod.Monopod
import io.github.whyoolw.monopod.Prefs
import io.github.whyoolw.monopod.yandex.YandexMusic
import android.content.Context
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import androidx.media3.inspector.MetadataRetriever
import io.github.whyoolw.monopod.data.Lyrics
import io.github.whyoolw.monopod.data.decodeLyricsFile
import io.github.whyoolw.monopod.data.parseLyrics
import io.github.whyoolw.monopod.data.parseSylt
import io.github.whyoolw.monopod.data.parseUslt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** What LRCLIB needs to find a song's lyrics. */
data class SongInfo(val artist: String?, val title: String?, val album: String?, val durationMs: Long)

/**
 * Lyrics for a song: an .lrc file next to it wins, otherwise the lyrics tag inside the file;
 * Yandex has its own. Without any, and for Spotify, LRCLIB (when switched on).
 */
object LyricsLoader {
    private const val TAG = "LyricsLoader"

    /** Results by song URI, including "none", so a song is only inspected once. */
    private val cache = LruCache<String, Result<Lyrics?>>(64)

    /**
     * What the song itself carries (file, tags, Yandex), kept apart from the final answer: a file
     * whose tags cannot be read is not probed again, and the LRCLIB switch does not lose it.
     */
    private val own = LruCache<String, Result<Lyrics?>>(64)

    fun cached(audioUri: String?): Lyrics? = audioUri?.let { cache.get(it)?.getOrNull() }

    /** Forgets the answers, for when LRCLIB was switched on or off. */
    fun forgetOnline() = cache.evictAll()

    suspend fun load(context: Context, audioUri: String, lrcUri: String?, info: SongInfo? = null): Lyrics? {
        cache.get(audioUri)?.let { return it.getOrNull() }
        val result = withContext(Dispatchers.IO) {
            val yandexId = YandexMusic.trackId(audioUri)
            val mine = own.get(audioUri) ?: runCatching {
                when {
                    audioUri.startsWith("spotify:") -> null
                    yandexId != null -> (context.applicationContext as Monopod).yandex.lyrics(yandexId)?.let(::parseLyrics)
                    else -> lrcUri?.let { fromFile(context, it) } ?: fromTags(context, audioUri)
                }
            }.onFailure { Log.w(TAG, "cannot read lyrics of $audioUri", it) }
                // Yandex failing is the network, which may be back next time; a local file is what it is
                .also { if (it.isSuccess || yandexId == null) own.put(audioUri, it) }
            // Whatever went wrong with the song's own lyrics, LRCLIB may still have them
            mine.getOrNull()?.let { return@withContext Result.success(it) }
            val online = info?.takeIf { online(context) }
                ?: return@withContext if (mine.isSuccess || yandexId == null) Result.success(null) else mine
            runCatching {
                Lrclib.find(online.artist.orEmpty(), online.title.orEmpty(), online.album, online.durationMs)?.let(::parseLyrics)
            }.onFailure { Log.w(TAG, "cannot find lyrics of $audioUri on LRCLIB", it) }
        }
        Log.i(TAG, "lyrics for $audioUri: ${result.getOrNull()?.let { "${it.lines.size} lines, synced=${it.synced}" } ?: "none"}")
        // A failure (no network) may work next time; "none" is final
        if (result.isSuccess) cache.put(audioUri, result)
        return result.getOrNull()
    }

    private fun online(context: Context) =
        context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE).getBoolean(Prefs.ONLINE_LYRICS, true)

    private fun fromFile(context: Context, uri: String): Lyrics? =
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { parseLyrics(decodeLyricsFile(it.readBytes())) }

    private fun fromTags(context: Context, audioUri: String): Lyrics? {
        val groups = MetadataRetriever.Builder(context, MediaItem.fromUri(audioUri)).build().use {
            it.retrieveTrackGroups().get(10, TimeUnit.SECONDS)
        }
        val all = (0 until groups.length).flatMap { g -> (0 until groups[g].length).mapNotNull { groups[g].getFormat(it).metadata } }
        // Synced lyrics first, wherever they are: a SYLT frame, or LRC text in a lyrics tag
        all.firstNotNullOfOrNull(::syncedFrame)?.let { return it }
        val texts = all.mapNotNull(::lyricsText).mapNotNull(::parseLyrics)
        return texts.firstOrNull { it.synced } ?: texts.firstOrNull()
    }

    private fun syncedFrame(metadata: Metadata): Lyrics? = (0 until metadata.length())
        .map { metadata.get(it) }
        .firstNotNullOfOrNull { e -> (e as? BinaryFrame)?.takeIf { it.id == "SYLT" }?.let { parseSylt(it.data) } }

    private fun lyricsText(metadata: Metadata): String? {
        for (i in 0 until metadata.length()) {
            when (val e = metadata.get(i)) {
                // MP3: ID3 USLT, which Media3 leaves as a raw frame
                is BinaryFrame -> if (e.id == "USLT") parseUslt(e.data)?.let { return it }
                // MP4 ©lyr arrives as a USLT text frame; some taggers write TXXX:LYRICS
                is TextInformationFrame -> {
                    val isLyrics = e.id == "USLT" ||
                        (e.id == "TXXX" && e.description?.uppercase()?.let { it == "LYRICS" || it == "UNSYNCEDLYRICS" } == true)
                    if (isLyrics) e.values.firstOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
                }
                // FLAC / Ogg / Opus
                is VorbisComment -> if (e.key.uppercase() in setOf("LYRICS", "UNSYNCEDLYRICS")) return e.value
            }
        }
        return null
    }
}
