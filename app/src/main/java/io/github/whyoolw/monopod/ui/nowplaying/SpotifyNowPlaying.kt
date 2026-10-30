package io.github.whyoolw.monopod.ui.nowplaying

import android.os.SystemClock
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.whyoolw.monopod.R
import io.github.whyoolw.monopod.playback.LyricsLoader
import io.github.whyoolw.monopod.playback.SongInfo
import io.github.whyoolw.monopod.playback.Volume
import io.github.whyoolw.monopod.spotify.Spotify
import io.github.whyoolw.monopod.ui.menu.Page
import io.github.whyoolw.monopod.ui.screen.Lcd
import io.github.whyoolw.monopod.ui.screen.LocalLcdFont
import kotlinx.coroutines.delay

/**
 * Now Playing for Spotify: the wheel turns the volume; the center button cycles through
 * scrubbing and the lyrics (from LRCLIB), like our own Now Playing without the rating.
 */
class SpotifyNowPlayingPage(override val title: String) : Page {
    /** The same modes as our own Now Playing; the rating is skipped (Spotify songs have none). */
    val modes = NowPlayingPage(title)
    val scrubbing: Boolean get() = modes.mode == NowPlayingPage.Mode.Scrub

    fun scroll(steps: Int, spotify: Spotify, volume: Volume) = modes.scroll(steps, SpotifySource(spotify), volume, null)

    fun select() = modes.select(canRate = false)
}

@Composable
fun SpotifyNowPlayingView(page: SpotifyNowPlayingPage, spotify: Spotify, volume: Volume, modifier: Modifier = Modifier) {
    val modes = page.modes
    val context = LocalContext.current
    val uri = spotify.trackUri
    val lyrics by produceState(LyricsLoader.cached(uri), uri) {
        value = uri?.let {
            LyricsLoader.load(context.applicationContext, it, null, SongInfo(spotify.artist, spotify.title, spotify.album, spotify.durationMs))
        }
    }
    SideEffect {
        modes.lyrics = lyrics
        if (lyrics == null && modes.mode == NowPlayingPage.Mode.Lyrics) modes.select(canRate = false)
    }
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    var position by remember { mutableLongStateOf(spotify.positionMs) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(page) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            volume.refresh()
            while (true) {
                now = SystemClock.uptimeMillis()
                position = spotify.positionMs.coerceAtMost(spotify.durationMs)
                modes.expire(now)
                delay(200)
            }
        }
    }
    val message = when {
        spotify.error == Spotify.ERROR_NOT_INSTALLED -> stringResource(R.string.spotify_not_installed)
        spotify.error != null -> stringResource(R.string.spotify_error, spotify.error!!)
        spotify.trackUri == null && !spotify.connected -> stringResource(R.string.spotify_connecting)
        spotify.trackUri == null -> stringResource(R.string.nothing_playing)
        else -> null
    }
    if (message != null) {
        Box(modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            BasicText(
                message,
                style = TextStyle(fontFamily = LocalLcdFont.current, fontSize = 15.sp, color = Lcd.dimText, textAlign = TextAlign.Center),
            )
        }
        return
    }
    Column(modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp)) {
        val shownLyrics = lyrics
        if (modes.mode == NowPlayingPage.Mode.Lyrics && shownLyrics != null) {
            LyricsView(shownLyrics, position, modes.lyricsScroll, { modes.lyricsMaxScroll = it }, Modifier.fillMaxWidth().weight(1f))
        } else BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            // Laid out like our own Now Playing: the cover on the left, the song at its right
            val tall = maxHeight > maxWidth * 0.75f
            val big = Lcd.material
            val coverSize = if (tall) minOf(maxHeight * if (big) 0.92f else 0.78f, maxWidth * 0.5f)
            else minOf(maxHeight * if (big) 0.98f else 0.78f, maxWidth * if (big) 0.48f else 0.42f)
            Row(
                Modifier.fillMaxWidth().align(if (tall) Alignment.CenterStart else Alignment.TopStart),
                verticalAlignment = Alignment.Top,
            ) {
                Cover(spotify.coverPath, coverSize)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f).height(coverSize), contentAlignment = Alignment.CenterStart) {
                    Column {
                        val small = TextStyle(fontFamily = LocalLcdFont.current, fontSize = 13.sp, color = Lcd.text)
                        BasicText(
                            stringResource(R.string.spotify),
                            style = TextStyle(fontFamily = LocalLcdFont.current, fontSize = 11.sp, color = Lcd.dimText),
                        )
                        Spacer(Modifier.height(4.dp))
                        BasicText(
                            spotify.title,
                            Modifier.basicMarquee(initialDelayMillis = 1500),
                            style = TextStyle(fontFamily = LocalLcdFont.current, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Lcd.text),
                            maxLines = 1,
                        )
                        BasicText(spotify.artist, style = small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        BasicText(spotify.album, style = small.copy(color = Lcd.dimText), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(34.dp), contentAlignment = Alignment.Center) {
            if (modes.mode == NowPlayingPage.Mode.Volume && now < modes.volumeShownUntil) {
                VolumeBar(volume.level.toFloat() / volume.max.coerceAtLeast(1))
            } else {
                ProgressBar(position, spotify.durationMs, scrubbing = scrubbing(modes), playing = spotify.isPlaying)
            }
        }
    }
}

private fun scrubbing(modes: NowPlayingPage) = modes.mode == NowPlayingPage.Mode.Scrub
