package io.github.whyoolw.monopod.playback

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.whyoolw.monopod.Monopod
import io.github.whyoolw.monopod.data.LibrarySnapshot
import io.github.whyoolw.monopod.data.Track
import java.io.File

/** The UI side of playback: a media controller connected to [PlaybackService], mirrored into Compose state. */
class Playback(private val context: Context) : Player.Listener {
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    var isPlaying by mutableStateOf(false)
        private set
    var hasQueue by mutableStateOf(false)
        private set
    var metadata by mutableStateOf(MediaMetadata.EMPTY)
        private set
    /** URI of the current song (its media id). */
    var mediaUri by mutableStateOf<String?>(null)
        private set
    /** Position of the current song in play order (shuffle aware), 1-based, and the queue length. */
    var queuePosition by mutableIntStateOf(0)
        private set
    var queueSize by mutableIntStateOf(0)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    private var localShuffle by mutableStateOf(false)
    private var localRepeat by mutableIntStateOf(Player.REPEAT_MODE_OFF)
    /** While Spotify plays, shuffle and repeat are the Spotify app's, so every switch drives what you hear. */
    private val spotify get() = (context.applicationContext as Monopod).spotify
    val shuffle: Boolean get() = if (spotify.active) spotify.shuffle else localShuffle
    val repeatMode: Int get() = if (spotify.active) spotify.repeatMode else localRepeat

    val positionMs: Long get() = controller?.currentPosition ?: 0L

    val volume = Volume(context)

    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context, token).buildAsync()
        future = f
        f.addListener({
            // release() may have cancelled this connection before it finished (activity closed early)
            if (future !== f || f.isCancelled) return@addListener
            controller = runCatching { f.get() }.getOrNull()?.also {
                it.addListener(this)
                refresh(it)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun release() {
        controller?.removeListener(this)
        future?.let(MediaController::releaseFuture)
        future = null
        controller = null
    }

    /** Replaces the queue with [tracks] and plays from [index]; null [shuffle] keeps the current mode. */
    fun play(tracks: List<Track>, index: Int, library: LibrarySnapshot, shuffle: Boolean? = null) {
        val c = controller ?: return
        if (shuffle != null) c.shuffleModeEnabled = shuffle
        c.setMediaItems(tracks.map { it.toMediaItem(library) }, index, 0L)
        c.prepare()
        c.play()
    }

    /** Replaces the queue with ready media items (online songs) and plays from [index]. */
    fun playItems(items: List<MediaItem>, index: Int) {
        val c = controller ?: return
        if (items.isEmpty()) return
        c.setMediaItems(items, index, 0L)
        c.prepare()
        c.play()
    }

    /** Empties the queue if it holds songs of a service that was switched off. */
    fun clearIf(predicate: (String) -> Boolean) {
        val c = controller ?: return
        if ((0 until c.mediaItemCount).any { predicate(c.getMediaItemAt(it).mediaId) }) {
            c.stop()
            c.clearMediaItems()
        }
    }

    fun playPause() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        if (c.playbackState == Player.STATE_ENDED) c.seekTo(c.currentMediaItemIndex, 0L)
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        if (c.isPlaying) c.pause() else c.play()
    }

    fun pause() = controller?.pause()

    fun next() = controller?.seekToNextMediaItem()

    /** Back to the start of the song, or to the previous one in its first 3 seconds, like the iPod. */
    fun previous() = controller?.seekToPrevious()

    fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        val duration = c.duration.takeIf { it > 0 } ?: return
        c.seekTo((c.currentPosition + deltaMs).coerceIn(0L, duration - 500))
    }

    fun toggleShuffle() {
        if (spotify.active) return spotify.toggleShuffle()
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    fun cycleRepeat() {
        if (spotify.active) return spotify.cycleRepeat()
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
    }

    override fun onEvents(player: Player, events: Player.Events) = refresh(player)

    private fun refresh(p: Player) {
        isPlaying = p.isPlaying
        hasQueue = p.mediaItemCount > 0
        metadata = p.mediaMetadata
        mediaUri = p.currentMediaItem?.mediaId
        durationMs = p.duration.takeIf { it > 0 } ?: 0L
        localShuffle = p.shuffleModeEnabled
        localRepeat = p.repeatMode
        queueSize = p.mediaItemCount
        queuePosition = playOrderPosition(p)
    }

    private fun playOrderPosition(p: Player): Int {
        val timeline = p.currentTimeline
        if (timeline.isEmpty) return 0
        var i = timeline.getFirstWindowIndex(p.shuffleModeEnabled)
        var n = 1
        while (i != p.currentMediaItemIndex && i != C.INDEX_UNSET) {
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
            n++
        }
        return n
    }
}

/** Device music volume, which is what the click wheel turns on the Now Playing screen. */
class Volume(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    val max: Int = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    var level by mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        private set

    fun step(steps: Int) {
        val v = (audio.getStreamVolume(AudioManager.STREAM_MUSIC) + steps).coerceIn(0, max)
        // No flags: the iPod draws its own volume bar, the system one would cover the screen
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
        level = v
    }

    /** Volume keys may have changed it behind our back. */
    fun refresh() {
        level = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    }
}

fun Track.toMediaItem(library: LibrarySnapshot): MediaItem = mediaItem(
    uri = uri,
    title = title,
    artist = artist,
    album = album,
    artworkUri = library.coverPath(albumKey)?.let { Uri.fromFile(File(it)) },
)
