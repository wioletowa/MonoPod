package io.github.whyoolw.monopod.playback

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import org.json.JSONArray
import org.json.JSONObject

/**
 * Remembers the queue and where playback stopped, so the player comes back where it was
 * after the app or the phone restarts, like the iPod does.
 */
class QueueStore(context: Context) {
    private val prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)

    class Saved(
        val items: List<MediaItem>,
        val index: Int,
        val positionMs: Long,
        val shuffle: Boolean,
        val repeatMode: Int,
    )

    /** The item list is big, so it is only written when it changes. */
    fun saveQueue(player: Player) {
        val array = JSONArray()
        for (i in 0 until player.mediaItemCount) array.put(player.getMediaItemAt(i).toJson())
        prefs.edit { putString(KEY_ITEMS, array.toString()) }
        savePosition(player)
    }

    fun savePosition(player: Player) {
        prefs.edit {
            putInt(KEY_INDEX, player.currentMediaItemIndex)
            putLong(KEY_POSITION, player.currentPosition)
            putBoolean(KEY_SHUFFLE, player.shuffleModeEnabled)
            putInt(KEY_REPEAT, player.repeatMode)
        }
    }

    fun restore(): Saved? {
        val json = prefs.getString(KEY_ITEMS, null) ?: return null
        val array = JSONArray(json)
        val items = (0 until array.length()).map { array.getJSONObject(it).toMediaItem() }
        if (items.isEmpty()) return null
        return Saved(
            items,
            prefs.getInt(KEY_INDEX, 0).coerceIn(0, items.lastIndex),
            prefs.getLong(KEY_POSITION, 0L),
            prefs.getBoolean(KEY_SHUFFLE, false),
            prefs.getInt(KEY_REPEAT, Player.REPEAT_MODE_OFF),
        )
    }

    private fun MediaItem.toJson() = JSONObject().apply {
        put("id", mediaId)
        mediaMetadata.title?.let { put("title", it.toString()) }
        mediaMetadata.artist?.let { put("artist", it.toString()) }
        mediaMetadata.albumTitle?.let { put("album", it.toString()) }
        mediaMetadata.artworkUri?.let { put("art", it.toString()) }
    }

    private fun JSONObject.toMediaItem(): MediaItem = mediaItem(
        uri = getString("id"),
        title = optString("title").ifEmpty { null },
        artist = optString("artist").ifEmpty { null },
        album = optString("album").ifEmpty { null },
        artworkUri = optString("art").ifEmpty { null }?.let(Uri::parse),
    )

    private companion object {
        const val KEY_ITEMS = "items"
        const val KEY_INDEX = "index"
        const val KEY_POSITION = "position"
        const val KEY_SHUFFLE = "shuffle"
        const val KEY_REPEAT = "repeat"
    }
}

/** The media id is the file URI, so the service can always rebuild a playable item from it. */
/** A Yandex Music song for the queue; [wave] marks My Wave songs, after which more are fetched. */
fun io.github.whyoolw.monopod.yandex.YandexTrack.toMediaItem(wave: Boolean = false): MediaItem =
    mediaItem(if (wave) "$uri?wave" else uri, title, artist, album, cover?.let(Uri::parse))

fun io.github.whyoolw.monopod.yandex.YandexTrack.toWaveItem(): MediaItem = toMediaItem(wave = true)

fun mediaItem(uri: String, title: String?, artist: String?, album: String?, artworkUri: Uri?): MediaItem =
    MediaItem.Builder()
        .setMediaId(uri)
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(artworkUri)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build(),
        )
        .build()
