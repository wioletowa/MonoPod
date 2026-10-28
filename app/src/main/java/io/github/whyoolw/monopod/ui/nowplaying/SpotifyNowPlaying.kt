package io.github.whyoolw.monopod.ui.nowplaying

import android.os.SystemClock
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.mutableStateOf
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
import io.github.whyoolw.monopod.playback.Volume
import io.github.whyoolw.monopod.spotify.Spotify
import io.github.whyoolw.monopod.ui.menu.Page
import io.github.whyoolw.monopod.ui.screen.Lcd
import io.github.whyoolw.monopod.ui.screen.LocalLcdFont
import kotlinx.coroutines.delay

/** Now Playing for Spotify: the wheel turns the volume, the center button switches to scrubbing. */
class SpotifyNowPlayingPage(override val title: String) : Page {
    var scrubbing by mutableStateOf(false)
        private set
    var volumeShownUntil by mutableLongStateOf(0L)
        private set
    private var lastTouch = 0L

    fun scroll(steps: Int, spotify: Spotify, volume: Volume) {
        val now = SystemClock.uptimeMillis()
        lastTouch = now
        if (scrubbing) {
            spotify.seekBy(steps * maxOf(1000L, spotify.durationMs / 120))
        } else {
            volume.step(steps)
            volumeShownUntil = now + VOLUME_SHOWN_MS
        }
    }

    fun select() {
        lastTouch = SystemClock.uptimeMillis()
        volumeShownUntil = 0L
        scrubbing = !scrubbing
    }

    fun expire(now: Long) {
        if (scrubbing && now - lastTouch > MODE_TIMEOUT_MS) scrubbing = false
    }
}

@Composable
fun SpotifyNowPlayingView(page: SpotifyNowPlayingPage, spotify: Spotify, volume: Volume, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    var position by remember { mutableLongStateOf(spotify.positionMs) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(page) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            volume.refresh()
            while (true) {
                now = SystemClock.uptimeMillis()
                position = spotify.positionMs.coerceAtMost(spotify.durationMs)
                page.expire(now)
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
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val tall = maxHeight > maxWidth * 0.75f
            val big = Lcd.material
            val coverSize = if (tall) {
                minOf(maxWidth * if (big) 0.86f else 0.72f, maxHeight * if (big) 0.66f else 0.58f)
            } else {
                minOf(maxHeight * if (big) 0.98f else 0.78f, maxWidth * if (big) 0.48f else 0.42f)
            }
            val info: @Composable (Alignment.Horizontal, TextAlign) -> Unit = { align, textAlign ->
                Column(horizontalAlignment = align) {
                    val small = TextStyle(fontFamily = LocalLcdFont.current, fontSize = 13.sp, color = Lcd.text, textAlign = textAlign)
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
            if (tall) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Cover(spotify.coverPath, coverSize)
                    info(Alignment.CenterHorizontally, TextAlign.Center)
                }
            } else {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.Top) {
                    Cover(spotify.coverPath, coverSize)
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f).height(coverSize), contentAlignment = Alignment.CenterStart) {
                        info(Alignment.Start, TextAlign.Start)
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(34.dp), contentAlignment = Alignment.Center) {
            if (!page.scrubbing && now < page.volumeShownUntil) {
                VolumeBar(volume.level.toFloat() / volume.max.coerceAtLeast(1))
            } else {
                ProgressBar(position, spotify.durationMs, scrubbing = page.scrubbing, playing = spotify.isPlaying)
            }
        }
    }
}
