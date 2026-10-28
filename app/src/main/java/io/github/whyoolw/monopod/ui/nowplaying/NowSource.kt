package io.github.whyoolw.monopod.ui.nowplaying

import io.github.whyoolw.monopod.playback.Playback
import io.github.whyoolw.monopod.spotify.Spotify
import io.github.whyoolw.monopod.ui.menu.coverPath

/** What is playing, from our own player or from Spotify, for screens that show either. */
interface NowSource {
    val uri: String?
    val title: String?
    val artist: String?
    val album: String?
    val cover: String?
    val positionMs: Long
    val durationMs: Long
    val isPlaying: Boolean
    /** Place in the queue (1-based) and its length; 0 when the source has no queue to show. */
    val queuePosition: Int
    val queueSize: Int
    /** Ratings and lyrics belong to songs of our own player. */
    val local: Boolean
    fun seekBy(deltaMs: Long)
}

/** Our player; [coverFor] finds a library song's cover, web covers come from the metadata. */
class LocalSource(private val playback: Playback, private val coverFor: (String) -> String?) : NowSource {
    override val uri get() = playback.mediaUri
    override val title get() = playback.metadata.title?.toString()
    override val artist get() = playback.metadata.artist?.toString()
    override val album get() = playback.metadata.albumTitle?.toString()
    override val cover get() = uri?.let(coverFor) ?: playback.metadata.artworkUri?.coverPath()
    override val positionMs get() = playback.positionMs
    override val durationMs get() = playback.durationMs
    override val isPlaying get() = playback.isPlaying
    override val queuePosition get() = playback.queuePosition
    override val queueSize get() = playback.queueSize
    override val local get() = true
    override fun seekBy(deltaMs: Long) = playback.seekBy(deltaMs)
}

class SpotifySource(private val spotify: Spotify) : NowSource {
    override val uri get() = spotify.trackUri
    override val title get() = spotify.title
    override val artist get() = spotify.artist
    override val album get() = spotify.album
    override val cover get() = spotify.coverPath
    override val positionMs get() = spotify.positionMs.coerceAtMost(spotify.durationMs)
    override val durationMs get() = spotify.durationMs
    override val isPlaying get() = spotify.isPlaying
    override val queuePosition get() = 0
    override val queueSize get() = 0
    override val local get() = false
    override fun seekBy(deltaMs: Long) = spotify.seekBy(deltaMs)
}
