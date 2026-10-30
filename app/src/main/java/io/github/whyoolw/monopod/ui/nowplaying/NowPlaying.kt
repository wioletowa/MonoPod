package io.github.whyoolw.monopod.ui.nowplaying

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import io.github.whyoolw.monopod.ui.material.WavyProgress
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import io.github.whyoolw.monopod.playback.LyricsLoader
import io.github.whyoolw.monopod.data.Lyrics
import io.github.whyoolw.monopod.data.StatsRepository
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.filter
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whyoolw.monopod.R
import io.github.whyoolw.monopod.playback.Playback
import io.github.whyoolw.monopod.playback.Volume
import io.github.whyoolw.monopod.ui.menu.CoverCache
import io.github.whyoolw.monopod.ui.menu.coverPath
import io.github.whyoolw.monopod.ui.menu.Page
import io.github.whyoolw.monopod.ui.screen.Lcd
import io.github.whyoolw.monopod.ui.screen.LocalLcdFont
import io.github.whyoolw.monopod.ui.screen.LocalPixelScreen
import kotlinx.coroutines.delay

/** How long the volume bar stays up after the last click. */
internal const val VOLUME_SHOWN_MS = 1500L

/** Scrub and shuffle modes fall back to volume after this much idle time, like the iPod. */
internal const val MODE_TIMEOUT_MS = 4000L

/**
 * The Now Playing screen. The wheel turns the volume; the center button cycles the wheel
 * through scrubbing the song, its star rating and its lyrics (shuffle lives in the quick settings).
 */
class NowPlayingPage(override val title: String) : Page {
    enum class Mode { Volume, Scrub, Rating, Lyrics }

    var mode by mutableStateOf(Mode.Volume)
        private set
    var volumeShownUntil by mutableLongStateOf(0L)
        private set
    private var lastTouch = 0L

    private var _lyrics by mutableStateOf<Lyrics?>(null)
    /** Set by the view once it knows whether the current song has lyrics; another song's start at the top. */
    var lyrics: Lyrics?
        get() = _lyrics
        internal set(v) {
            if (v == _lyrics) return
            _lyrics = v
            lyricsScroll = 0
        }
    /** How far plain (unsynced) lyrics are scrolled, in wheel clicks. */
    var lyricsScroll by mutableIntStateOf(0)
        private set
    /** The most clicks the lyrics on screen can scroll; the view measures it. */
    var lyricsMaxScroll by mutableIntStateOf(0)
        internal set

    private fun scrollLyrics(steps: Int) {
        lyricsScroll = (lyricsScroll + steps).coerceIn(0, lyricsMaxScroll.coerceAtLeast(0))
    }

    /** The same wheel for any source: volume, scrubbing, the rating (own songs only), lyrics. */
    fun scroll(steps: Int, source: NowSource, volume: Volume, stats: StatsRepository?) {
        val now = SystemClock.uptimeMillis()
        lastTouch = now
        val l = lyrics
        when {
            mode == Mode.Scrub -> source.seekBy(steps * maxOf(1000L, source.durationMs / 120))
            mode == Mode.Rating -> source.uri?.let { stats?.setRating(it, stats.rating(it) + steps) }
            mode == Mode.Lyrics && l != null && !l.synced -> scrollLyrics(steps)
            else -> {
                volume.step(steps)
                volumeShownUntil = now + VOLUME_SHOWN_MS
            }
        }
    }

    /** Center button where songs may lack a rating (Spotify): that mode is skipped. */
    fun select(canRate: Boolean) {
        select()
        if (mode == Mode.Rating && !canRate) select()
    }

    fun scroll(steps: Int, playback: Playback, stats: StatsRepository) {
        val now = SystemClock.uptimeMillis()
        lastTouch = now
        when (mode) {
            Mode.Volume -> {
                playback.volume.step(steps)
                volumeShownUntil = now + VOLUME_SHOWN_MS
            }
            // ~120 clicks across a song, never less than a second per click
            Mode.Scrub -> playback.seekBy(steps * maxOf(1000L, playback.durationMs / 120))
            Mode.Rating -> playback.mediaUri?.let { stats.setRating(it, stats.rating(it) + steps) }
            Mode.Lyrics -> {
                val l = lyrics
                if (l == null || l.synced) {
                    // Synced lyrics scroll themselves; the wheel stays on volume
                    playback.volume.step(steps)
                    volumeShownUntil = now + VOLUME_SHOWN_MS
                } else scrollLyrics(steps)
            }
        }
    }

    fun select() {
        lastTouch = SystemClock.uptimeMillis()
        volumeShownUntil = 0L
        var next = Mode.entries[(mode.ordinal + 1) % Mode.entries.size]
        // Songs without lyrics skip the lyrics page
        if (next == Mode.Lyrics && lyrics == null) next = Mode.Volume
        if (next == Mode.Lyrics) lyricsScroll = 0
        mode = next
    }

    fun expire(now: Long) {
        // Lyrics stay up while you read them
        if (mode != Mode.Volume && mode != Mode.Lyrics && now - lastTouch > MODE_TIMEOUT_MS) mode = Mode.Volume
    }
}

@Composable
fun NowPlayingView(
    page: NowPlayingPage,
    playback: Playback,
    stats: StatsRepository,
    /** Song URI → its .lrc file, from the library. */
    lyricsFile: (String) -> String?,
    /** Song URI → its album cover, from the library. */
    coverFor: (String) -> String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val uri = playback.mediaUri
    val lyrics by produceState(LyricsLoader.cached(uri), uri) {
        value = uri?.let { LyricsLoader.load(context.applicationContext, it, lyricsFile(it), playback.songInfo) }
    }
    SideEffect {
        page.lyrics = lyrics
        if (lyrics == null && page.mode == NowPlayingPage.Mode.Lyrics) page.select()
    }
    // Position is not pushed by the player, poll it while the screen is up
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    var position by remember { mutableLongStateOf(playback.positionMs) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(page) {
        // Only while the app is on screen: no polling with the phone in the pocket
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            playback.volume.refresh()
            while (true) {
                now = SystemClock.uptimeMillis()
                position = playback.positionMs
                page.expire(now)
                delay(200)
            }
        }
    }

    val meta = playback.metadata
    if (!playback.hasQueue) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            BasicText(stringResource(R.string.nothing_playing), style = TextStyle(fontFamily = LocalLcdFont.current, fontSize = 15.sp, color = Lcd.dimText))
        }
        return
    }

    Column(modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp)) {
        val shownLyrics = lyrics
        if (page.mode == NowPlayingPage.Mode.Lyrics && shownLyrics != null) {
            LyricsView(shownLyrics, position, page.lyricsScroll, { page.lyricsMaxScroll = it }, Modifier.fillMaxWidth().weight(1f))
        } else BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            // Laid out like the Cover Flow playing screen: the cover on the left, the song at its
            // right; a taller screen (big screen layout) centers the pair and gives it a bigger cover
            val tall = maxHeight > maxWidth * 0.75f
            // Material shows the cover bigger: no reflection to make room for
            val big = Lcd.material
            val coverSize = if (tall) minOf(maxHeight * if (big) 0.92f else 0.78f, maxWidth * 0.5f)
            else minOf(maxHeight * if (big) 0.98f else 0.78f, maxWidth * if (big) 0.48f else 0.42f)
            Row(
                Modifier.fillMaxWidth().align(if (tall) Alignment.CenterStart else Alignment.TopStart),
                verticalAlignment = Alignment.Top,
            ) {
                Cover(uri?.let(coverFor) ?: meta.artworkUri?.coverPath(), coverSize)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f).height(coverSize), contentAlignment = Alignment.CenterStart) {
                    Column {
                        val small = TextStyle(fontFamily = LocalLcdFont.current, fontSize = 13.sp, color = Lcd.text)
                        BasicText(
                            stringResource(R.string.queue_position, playback.queuePosition, playback.queueSize),
                            style = TextStyle(fontFamily = LocalLcdFont.current, fontSize = 11.sp, color = Lcd.dimText),
                        )
                        Spacer(Modifier.height(4.dp))
                        BasicText(
                            meta.title?.toString().orEmpty(),
                            Modifier.basicMarquee(initialDelayMillis = 1500),
                            style = TextStyle(fontFamily = LocalLcdFont.current, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Lcd.text),
                            maxLines = 1,
                        )
                        BasicText(
                            meta.artist?.toString() ?: stringResource(R.string.unknown_artist),
                            style = small, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        BasicText(
                            meta.albumTitle?.toString() ?: stringResource(R.string.unknown_album),
                            style = small.copy(color = Lcd.dimText), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(34.dp), contentAlignment = Alignment.Center) {
            when {
                page.mode == NowPlayingPage.Mode.Rating -> RatingBar(uri?.let { stats.rating(it) } ?: 0)
                page.mode == NowPlayingPage.Mode.Volume && now < page.volumeShownUntil ->
                    VolumeBar(playback.volume.level.toFloat() / playback.volume.max.coerceAtLeast(1))
                else -> ProgressBar(position, playback.durationMs, scrubbing = page.mode == NowPlayingPage.Mode.Scrub, playing = playback.isPlaying)
            }
        }
    }
}

@Composable
internal fun Cover(path: String?, size: Dp) {
    val lcd = Lcd
    val image by produceState(CoverCache.peek(path, full = true), path) { value = CoverCache.load(path, full = true) }
    if (lcd.material) {
        // Material: a big rounded cover, no glass reflection
        Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.07f)).background(lcd.placeholder), contentAlignment = Alignment.Center) {
            image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                ?: BasicText("♪", style = TextStyle(fontFamily = LocalLcdFont.current, fontSize = 34.sp, color = lcd.placeholderText))
        }
        return
    }
    Column {
        Box(Modifier.size(size).background(lcd.placeholder), contentAlignment = Alignment.Center) {
            image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                ?: BasicText("♪", style = TextStyle(fontFamily = LocalLcdFont.current, fontSize = 34.sp, color = lcd.placeholderText))
        }
        // The glossy reflection under the cover, iPod Classic style
        Box(Modifier.width(size).height(size * 0.22f).clipToBounds()) {
            Box(
                Modifier.size(size).graphicsLayer { scaleY = -1f; alpha = 0.35f }
                    .background(lcd.placeholder),
            ) {
                image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Brush.verticalGradient(listOf(lcd.background.copy(alpha = 0.3f), lcd.background)))
            }
        }
    }
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
internal fun ProgressBar(positionMs: Long, durationMs: Long, scrubbing: Boolean, playing: Boolean) {
    val lcd = Lcd
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth()) {
        if (LocalPixelScreen.current) {
            PixelBar(fraction, scrubbing)
        } else if (lcd.material) {
            WavyProgress(fraction, playing, Modifier.fillMaxWidth().height(14.dp), thumb = scrubbing)
        } else Canvas(Modifier.fillMaxWidth().height(9.dp)) {
            val r = CornerRadius(2f)
            drawRoundRect(Brush.verticalGradient(listOf(lcd.trackTop, lcd.trackBottom)), cornerRadius = r)
            drawRoundRect(
                Brush.verticalGradient(listOf(lcd.progressTop, lcd.progressBottom)),
                size = Size(size.width * fraction, size.height), cornerRadius = r,
            )
            drawRect(Color.White.copy(alpha = 0.35f), size = Size(size.width * fraction, size.height * 0.4f))
            drawRoundRect(lcd.barLine, cornerRadius = r, style = Stroke(1f))
            if (scrubbing) {
                // Scrubbing: a round knob, bigger than the bar, marks the spot
                val c = Offset(size.width * fraction, size.height / 2)
                val knob = size.height * 0.95f
                drawCircle(Color.Black.copy(alpha = 0.25f), knob + 1.5f, c.copy(y = c.y + 1f))
                drawCircle(Brush.verticalGradient(listOf(Color.White, lcd.trackTop), startY = c.y - knob, endY = c.y + knob), knob, c)
                drawCircle(lcd.barLine, knob, c, style = Stroke(1f))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            val style = TextStyle(fontFamily = LocalLcdFont.current, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = lcd.text)
            BasicText(fmt(positionMs), Modifier.weight(1f), style = style)
            BasicText("-" + fmt(durationMs - positionMs), style = style)
        }
    }
}

@Composable
internal fun VolumeBar(fraction: Float) {
    val lcd = Lcd
    Column(Modifier.fillMaxWidth()) {
        // Same place and size as the progress bar it stands in for
        if (LocalPixelScreen.current) {
            PixelBar(fraction, thumb = false)
        } else if (lcd.material) {
            WavyProgress(fraction, playing = false, Modifier.fillMaxWidth().height(14.dp))
        } else Canvas(Modifier.fillMaxWidth().height(9.dp)) {
            val r = CornerRadius(2f)
            drawRoundRect(Brush.verticalGradient(listOf(lcd.trackTop, lcd.trackBottom)), cornerRadius = r)
            drawRoundRect(
                Brush.verticalGradient(listOf(lcd.progressTop, lcd.progressBottom)),
                size = Size(size.width * fraction, size.height), cornerRadius = r,
            )
            drawRect(Color.White.copy(alpha = 0.35f), size = Size(size.width * fraction, size.height * 0.4f))
            drawRoundRect(lcd.barLine, cornerRadius = r, style = Stroke(1f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Speaker(Modifier.size(13.dp), waves = 0)
            Spacer(Modifier.weight(1f))
            Speaker(Modifier.size(13.dp), waves = 2)
        }
    }
}

/** A bar of square blocks for the pixel screens: filled up to [fraction], a tall block marks the spot while scrubbing. */
@Composable
private fun PixelBar(fraction: Float, thumb: Boolean) {
    val lcd = Lcd
    Canvas(Modifier.fillMaxWidth().height(12.dp)) {
        val block = 3.dp.toPx()
        val gap = 1.dp.toPx()
        val count = ((size.width + gap) / (block + gap)).toInt().coerceAtLeast(1)
        val step = (size.width + gap) / count
        val filled = (fraction * count).toInt()
        val top = (size.height - block * 2) / 2
        for (i in 0 until count) {
            val x = i * step
            val here = thumb && i == filled.coerceAtMost(count - 1)
            if (here) drawRect(lcd.text, Offset(x, 0f), Size(step - gap, size.height))
            else if (i < filled) drawRect(lcd.text, Offset(x, top), Size(step - gap, block * 2))
            else drawRect(lcd.dimText.copy(alpha = 0.45f), Offset(x, top + block * 0.5f), Size(step - gap, block))
        }
    }
}

@Composable
private fun Speaker(modifier: Modifier, waves: Int) {
    val lcd = Lcd
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val body = Path().apply {
            moveTo(0f, h * 0.35f); lineTo(w * 0.25f, h * 0.35f); lineTo(w * 0.5f, h * 0.1f)
            lineTo(w * 0.5f, h * 0.9f); lineTo(w * 0.25f, h * 0.65f); lineTo(0f, h * 0.65f); close()
        }
        drawPath(body, lcd.text)
        for (i in 1..waves) {
            val rr = w * (0.18f + 0.17f * i)
            drawArc(
                lcd.text, -45f, 90f, useCenter = false,
                topLeft = Offset(w * 0.5f - rr, h / 2 - rr), size = Size(rr * 2, rr * 2),
                style = Stroke(1.5f),
            )
        }
    }
}


/** Pixels one wheel click scrolls plain lyrics: about a line. */
private val LYRICS_STEP = 22.dp

/**
 * Lyrics over the whole song area. Synced lyrics keep the sung line in the middle of the
 * screen; plain lyrics scroll with the wheel, a line per click, however long the lines wrap.
 */
@Composable
internal fun LyricsView(lyrics: Lyrics, positionMs: Long, scroll: Int, onMaxScroll: (Int) -> Unit, modifier: Modifier) {
    // A new song's lyrics start from the top
    val state = remember(lyrics) { ScrollState(0) }
    val current = lyrics.lineAt(positionMs)
    // Where each line sits in the column, for centering the sung one
    val tops = remember(lyrics) { IntArray(lyrics.lines.size) }
    val heights = remember(lyrics) { IntArray(lyrics.lines.size) }
    val density = LocalDensity.current
    val step = with(density) { LYRICS_STEP.roundToPx() }
    var viewport by remember { mutableIntStateOf(0) }
    if (lyrics.synced) {
        LaunchedEffect(state, current, viewport) {
            val i = current.coerceAtLeast(0)
            if (i < tops.size && viewport > 0) state.animateScrollTo(tops[i] + heights[i] / 2 - viewport / 2)
        }
    } else {
        LaunchedEffect(state, scroll) { state.animateScrollTo(scroll * step) }
        // The wheel stops at the last line, once the lyrics are measured (until then the most is unknown)
        val report by rememberUpdatedState(onMaxScroll)
        LaunchedEffect(state, step) {
            snapshotFlow { state.maxValue }.filter { it != Int.MAX_VALUE }.collect { report((it + step - 1) / step) }
        }
    }
    Column(
        modifier.padding(bottom = 6.dp).clipToBounds()
            .onSizeChanged { viewport = it.height }
            .verticalScroll(state, enabled = false),
    ) {
        // Synced: room above the first line and below the last, so either can sit in the middle
        if (lyrics.synced) Spacer(Modifier.height(with(density) { (viewport / 2).toDp() }))
        lyrics.lines.forEachIndexed { i, line ->
            val now = lyrics.synced && i == current
            BasicText(
                line.text.ifEmpty { "♪" },
                Modifier.fillMaxWidth().padding(vertical = 2.dp).onGloballyPositioned {
                    tops[i] = it.positionInParent().y.toInt()
                    heights[i] = it.size.height
                },
                style = TextStyle(
                    fontFamily = LocalLcdFont.current,
                    fontSize = if (now) 15.sp else 14.sp,
                    fontWeight = if (now) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        now -> Lcd.accent
                        lyrics.synced -> Lcd.dimText
                        else -> Lcd.text
                    },
                    textAlign = TextAlign.Center,
                ),
            )
        }
        if (lyrics.synced) Spacer(Modifier.height(with(density) { (viewport / 2).toDp() }))
    }
}

/** Five places: ★ for each star, a dot for the rest, like the iPod. */
@Composable
internal fun RatingBar(stars: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        for (i in 1..5) {
            BasicText(
                if (i <= stars) "★" else "•",
                Modifier.width(30.dp),
                style = TextStyle(
                    fontSize = if (i <= stars) 22.sp else 18.sp,
                    color = if (i <= stars) Lcd.accent else Lcd.dimText,
                    textAlign = TextAlign.Center,
                ),
            )
        }
    }
}
