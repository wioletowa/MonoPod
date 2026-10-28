package io.github.whyoolw.monopod.playback

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.whyoolw.monopod.MainActivity
import io.github.whyoolw.monopod.Monopod
import io.github.whyoolw.monopod.yandex.YandexMusic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plays music in the background. Media3 gives us the notification, lock screen controls,
 * headset and Bluetooth buttons, and audio focus handling through the media session.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var sound: SoundController? = null
    private var counter: PlayCounter? = null
    private lateinit var store: QueueStore
    private val scope = MainScope()
    private val yandex get() = (application as Monopod).yandex

    override fun onCreate() {
        super.onCreate()
        store = QueueStore(this)
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(musicSourceFactory(this))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            // Pause when headphones are unplugged
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.addListener(StateSaver(player))
        sound = SoundController(this, player)
        counter = PlayCounter(player, (application as Monopod).stats)

        store.restore()?.let { saved ->
            player.setMediaItems(saved.items, saved.index, saved.positionMs)
            player.shuffleModeEnabled = saved.shuffle
            player.repeatMode = saved.repeatMode
            player.prepare()
        }

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(Callback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        // Keep playing when swiped away from recents, like a music player should; stop when idle
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        sound?.release()
        sound = null
        counter?.release()
        counter = null
        session?.run {
            store.savePosition(player)
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private inner class StateSaver(private val player: ExoPlayer) : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                shuffleFromCurrent()
                store.saveQueue(player)
            }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            shuffleFromCurrent()
            store.savePosition(player)
        }

        /**
         * A random order normally puts the chosen song somewhere in the middle, and everything
         * shuffled before it would never play. Start the order at the current song instead.
         */
        private fun shuffleFromCurrent() {
            val count = player.mediaItemCount
            val current = player.currentMediaItemIndex
            if (!player.shuffleModeEnabled || count < 2) return
            // Also stops the loop: setting the order fires another playlist change
            if (player.currentTimeline.getFirstWindowIndex(true) == current) return
            val rest = (0 until count).filter { it != current }.shuffled()
            player.setShuffleOrder(DefaultShuffleOrder((listOf(current) + rest).toIntArray(), System.nanoTime()))
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            store.savePosition(player)
            topUpWave()
        }

        /** My Wave never ends: two songs before the end, the next batch joins the queue. */
        private var waveLoading = false

        private fun topUpWave() {
            val last = player.mediaItemCount - 1
            if (waveLoading || last < 0 || player.currentMediaItemIndex < last - 1) return
            val lastId = player.getMediaItemAt(last).mediaId
            if (!YandexMusic.isWave(lastId)) return
            waveLoading = true
            scope.launch {
                val more = withContext(Dispatchers.IO) {
                    runCatching { yandex.waveTracks(YandexMusic.trackId(lastId)) }.getOrDefault(emptyList())
                }
                player.addMediaItems(more.map { it.toWaveItem() })
                waveLoading = false
            }
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) = store.savePosition(player)
        override fun onRepeatModeChanged(repeatMode: Int) = store.savePosition(player)

        override fun onPlayerError(error: PlaybackException) {
            // A broken or unsupported file should not stop the whole queue
            Log.w(TAG, "cannot play ${player.currentMediaItem?.mediaId}", error)
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }
    }

    private inner class Callback : MediaSession.Callback {
        /** Items from controllers arrive without their URI; it is the media id. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
            mediaItems.map { it.buildUpon().setUri(it.mediaId).build() }.toMutableList(),
        )

        /** "Resume" from the system media controls after the app was closed. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaItemsWithStartPosition> {
            val saved = store.restore()
                ?: return Futures.immediateFailedFuture(UnsupportedOperationException("nothing to resume"))
            return Futures.immediateFuture(MediaItemsWithStartPosition(saved.items, saved.index, saved.positionMs))
        }
    }

    private companion object {
        const val TAG = "PlaybackService"
    }
}
