package io.github.whyoolw.monopod.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.github.whyoolw.monopod.data.StatsRepository
import io.github.whyoolw.monopod.data.countsAsPlayed

/**
 * Counts plays for "Top 25" and "Recently Played": a song counts once it has actually played
 * for half its length (or four minutes), so skipping or scrubbing through does not.
 */
class PlayCounter(private val player: Player, private val stats: StatsRepository) : Player.Listener {
    private val handler = Handler(Looper.getMainLooper())
    private var uri: String? = player.currentMediaItem?.mediaId
    private var listenedMs = 0L
    private var counted = false

    private val tick = object : Runnable {
        override fun run() {
            if (player.isPlaying && player.currentMediaItem?.mediaId == uri) {
                listenedMs += TICK_MS
                val u = uri
                if (!counted && u != null && countsAsPlayed(listenedMs, player.duration)) {
                    counted = true
                    stats.countPlay(u)
                }
                handler.postDelayed(this, TICK_MS)
            } else {
                ticking = false
            }
        }
    }
    private var ticking = false

    init {
        player.addListener(this)
        if (player.isPlaying) start()
    }

    private fun start() {
        if (ticking) return
        ticking = true
        handler.postDelayed(tick, TICK_MS)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) start()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // Repeat-one replays the same item: that is a new listen too
        uri = mediaItem?.mediaId
        listenedMs = 0L
        counted = false
    }

    fun release() {
        handler.removeCallbacks(tick)
        player.removeListener(this)
    }

    private companion object {
        const val TICK_MS = 1000L
    }
}
