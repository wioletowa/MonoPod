package io.github.whyoolw.monopod.playback

import io.github.whyoolw.monopod.Monopod
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
import io.github.whyoolw.monopod.data.parseUslt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Lyrics for a song: an .lrc file next to it wins, otherwise the lyrics tag inside the file. */
object LyricsLoader {
    private const val TAG = "LyricsLoader"

    /** Results by song URI, including "none", so a song is only inspected once. */
    private val cache = LruCache<String, Result<Lyrics?>>(64)

    fun cached(audioUri: String?): Lyrics? = audioUri?.let { cache.get(it)?.getOrNull() }

    suspend fun load(context: Context, audioUri: String, lrcUri: String?): Lyrics? {
        cache.get(audioUri)?.let { return it.getOrNull() }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val yandexId = YandexMusic.trackId(audioUri)
                if (yandexId != null) (context.applicationContext as Monopod).yandex.lyrics(yandexId)?.let(::parseLyrics)
                else lrcUri?.let { fromFile(context, it) } ?: fromTags(context, audioUri)
            }.onFailure { Log.w(TAG, "cannot read lyrics of $audioUri", it) }
        }
        Log.i(TAG, "lyrics for $audioUri: ${result.getOrNull()?.let { "${it.lines.size} lines, synced=${it.synced}" } ?: "none"}")
        cache.put(audioUri, result)
        return result.getOrNull()
    }

    private fun fromFile(context: Context, uri: String): Lyrics? =
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { parseLyrics(decodeLyricsFile(it.readBytes())) }

    private fun fromTags(context: Context, audioUri: String): Lyrics? {
        val groups = MetadataRetriever.Builder(context, MediaItem.fromUri(audioUri)).build().use {
            it.retrieveTrackGroups().get(10, TimeUnit.SECONDS)
        }
        for (g in 0 until groups.length) {
            val group = groups[g]
            for (t in 0 until group.length) {
                group.getFormat(t).metadata?.let { m -> lyricsText(m)?.let { return parseLyrics(it) } }
            }
        }
        return null
    }

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
