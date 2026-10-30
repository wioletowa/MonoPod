package io.github.whyoolw.monopod.ui.coverflow

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.whyoolw.monopod.R
import io.github.whyoolw.monopod.data.StatsRepository
import io.github.whyoolw.monopod.playback.LyricsLoader
import io.github.whyoolw.monopod.playback.Volume
import io.github.whyoolw.monopod.ui.menu.CoverCache
import io.github.whyoolw.monopod.ui.nowplaying.LyricsView
import io.github.whyoolw.monopod.ui.nowplaying.NowPlayingPage
import io.github.whyoolw.monopod.ui.nowplaying.NowSource
import io.github.whyoolw.monopod.ui.nowplaying.ProgressBar
import io.github.whyoolw.monopod.ui.nowplaying.RatingBar
import io.github.whyoolw.monopod.ui.nowplaying.VolumeBar
import io.github.whyoolw.monopod.ui.screen.Lcd
import io.github.whyoolw.monopod.ui.screen.LocalLcdFont
import kotlinx.coroutines.delay

/** One song on screen; the queue position decides which way it slides when the song changes. */
private data class Shown(val uri: String?, val position: Int, val title: String?, val artist: String?, val album: String?, val cover: String?)

/**
 * The playing song inside Cover Flow, laid out like the iPod nano 3G: cover on the left, title,
 * artist and album on the right, progress along the bottom. The center button cycles the wheel
 * through volume, scrubbing, the rating and the lyrics, like Now Playing.
 */
@Composable
fun NanoPlaying(
    page: CoverFlowPage,
    source: NowSource,
    volume: Volume,
    stats: StatsRepository,
    /** Song URI → its .lrc file, from the library. */
    lyricsFile: (String) -> String?,
    /** Text and progress fade in while the cover flies over from the wall. */
    contentAlpha: Float = 1f,
    /** False while the flying cover stands in for this one. */
    coverShown: Boolean = true,
    /** The cover's slight turn, degrees around the vertical axis; iPod style also shows its reflection. */
    tilt: Float = 0f,
    /** Where the cover's face sits (in the window), for the flight to land on it. */
    onCoverPlaced: (Rect) -> Unit = {},
) {
    val modes = page.modes
    val context = LocalContext.current
    val uri = source.uri
    val lyrics by produceState(LyricsLoader.cached(uri), uri, source.local) {
        // Spotify songs have no lyrics of their own: LRCLIB finds them by name
        value = uri?.let {
            LyricsLoader.load(
                context.applicationContext, it, if (source.local) lyricsFile(it) else null,
                source.info,
            )
        }
    }
    SideEffect {
        modes.lyrics = lyrics
        if (lyrics == null && modes.mode == NowPlayingPage.Mode.Lyrics) modes.select()
    }
    var position by remember { mutableLongStateOf(source.positionMs) }
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(source) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            volume.refresh()
            while (true) {
                position = source.positionMs
                now = SystemClock.uptimeMillis()
                modes.expire(now)
                delay(200)
            }
        }
    }
    val font = LocalLcdFont.current
    val background = if (tilt == 0f) Lcd.background else Color.Transparent
    Column(Modifier.fillMaxSize().background(background).padding(horizontal = 10.dp, vertical = 8.dp)) {
        val shownLyrics = lyrics
        if (modes.mode == NowPlayingPage.Mode.Lyrics && shownLyrics != null) {
            // The lyrics take the whole screen, the cover makes way for them
            LyricsView(shownLyrics, position, modes.lyricsScroll, { modes.lyricsMaxScroll = it }, Modifier.fillMaxWidth().weight(1f).graphicsLayer { alpha = contentAlpha })
        } else AnimatedContent(
            targetState = Shown(uri, source.queuePosition, source.title, source.artist, source.album, source.cover),
            modifier = Modifier.fillMaxWidth().weight(1f),
            transitionSpec = {
                // Next song comes in from the right, previous from the left; wrapping round counts as next
                val forward = targetState.position > initialState.position ||
                    (initialState.position == source.queueSize && targetState.position == 1)
                val dir = if (forward) 1 else -1
                (slideInHorizontally(tween(320)) { it * dir } + fadeIn(tween(320))) togetherWith
                    (slideOutHorizontally(tween(320)) { -it * dir } + fadeOut(tween(200)))
            },
            contentKey = { it.uri },
            label = "song",
        ) { shown ->
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // Room under the cover for its reflection in the iPod style
                val reflect = tilt != 0f
                val cover = minOf(maxHeight * if (reflect) 0.8f else 0.92f, maxWidth * 0.5f)
                // A taller screen (big screen layout) leaves room above and below: the cover and its
                // reflection sit in the middle of it
                val block = if (reflect) cover * 1.3f else cover
                Row(
                    Modifier.fillMaxWidth().height(block).align(Alignment.CenterStart),
                    verticalAlignment = if (reflect) Alignment.Top else Alignment.CenterVertically,
                ) {
                    Box(Modifier.graphicsLayer { alpha = if (coverShown) 1f else 0f }) {
                        NanoCover(shown.cover, shown.album, cover, tilt, reflect, onCoverPlaced)
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f).height(cover).graphicsLayer { alpha = contentAlpha }, contentAlignment = Alignment.CenterStart) {
                        Column {
                            // From the shown song, so the card sliding out keeps its own text
                            BasicText(
                                shown.title.orEmpty(),
                                Modifier.basicMarquee(initialDelayMillis = 1500),
                                style = TextStyle(fontFamily = font, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Lcd.text),
                                maxLines = 1,
                            )
                            BasicText(
                                shown.artist ?: stringResource(R.string.unknown_artist),
                                style = TextStyle(fontFamily = font, fontSize = 14.sp, color = Lcd.text),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            BasicText(
                                shown.album ?: stringResource(R.string.unknown_album),
                                style = TextStyle(fontFamily = font, fontSize = 14.sp, color = Lcd.text),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            if (shown.position > 0) {
                                Spacer(Modifier.height(10.dp))
                                BasicText(
                                    stringResource(R.string.queue_position, shown.position, source.queueSize),
                                    style = TextStyle(fontFamily = font, fontSize = 13.sp, color = Lcd.dimText),
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(34.dp).graphicsLayer { alpha = contentAlpha }, contentAlignment = Alignment.Center) {
            when {
                modes.mode == NowPlayingPage.Mode.Rating -> RatingBar(uri?.let { stats.rating(it) } ?: 0)
                modes.mode != NowPlayingPage.Mode.Scrub && now < modes.volumeShownUntil ->
                    VolumeBar(volume.level.toFloat() / volume.max.coerceAtLeast(1))
                else -> ProgressBar(position, source.durationMs, scrubbing = modes.mode == NowPlayingPage.Mode.Scrub, playing = source.isPlaying)
            }
        }
    }
}

/** The playing cover: turned slightly, with its glossy reflection in the iPod style. */
@Composable
private fun NanoCover(path: String?, album: String?, size: Dp, tilt: Float, reflect: Boolean, onPlaced: (Rect) -> Unit) {
    val image by produceState(CoverCache.peek(path, full = true), path) { value = CoverCache.load(path, full = true) }
    val face: @Composable () -> Unit = {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(if (Lcd.material) size * 0.12f else 0.dp)).background(Lcd.placeholder),
            contentAlignment = Alignment.Center,
        ) {
            val img = image
            when {
                img != null -> Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                // iPod style: the same dark tile with the album name the Cover Flow wall shows
                reflect -> Box(
                    Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF4A4E55), Color(0xFF26282C)))),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        album ?: "♪",
                        Modifier.padding(8.dp),
                        style = TextStyle(
                            fontFamily = LocalLcdFont.current, fontSize = 13.sp, color = Color(0xFFD9DCE0),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        ),
                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                    )
                }
                else -> BasicText("♪", style = TextStyle(fontSize = 34.sp, color = Lcd.placeholderText))
            }
        }
    }
    val lcd = Lcd
    Column(
        Modifier.graphicsLayer {
            rotationY = tilt
            cameraDistance = 10f * density * size.toPx() / 100f
            // Turn around the face's middle, so the reflection under it does not shift it
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, if (reflect) 1f / (2f * 1.3f) else 0.5f)
        },
    ) {
        Box(Modifier.onGloballyPositioned { onPlaced(it.boundsInRoot()) }) { face() }
        if (reflect) Box(Modifier.width(size).height(size * 0.3f).clipToBounds()) {
            Box(Modifier.graphicsLayer { scaleY = -1f; alpha = 0.4f }) { face() }
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Brush.verticalGradient(listOf(lcd.background.copy(alpha = 0.35f), lcd.background)))
            }
        }
    }
}
