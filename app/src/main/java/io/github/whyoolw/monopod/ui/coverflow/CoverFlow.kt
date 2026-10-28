package io.github.whyoolw.monopod.ui.coverflow

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.TransformOrigin
import io.github.whyoolw.monopod.data.StatsRepository
import io.github.whyoolw.monopod.playback.Volume
import io.github.whyoolw.monopod.ui.nowplaying.NowPlayingPage
import io.github.whyoolw.monopod.ui.nowplaying.NowSource
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.draw.clip
import androidx.compose.animation.scaleOut
import androidx.compose.animation.AnimatedVisibility
import io.github.whyoolw.monopod.playback.Playback
import androidx.compose.animation.togetherWith
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.whyoolw.monopod.R
import io.github.whyoolw.monopod.data.Album
import io.github.whyoolw.monopod.data.Track
import io.github.whyoolw.monopod.ui.menu.CoverCache
import io.github.whyoolw.monopod.ui.menu.Page
import io.github.whyoolw.monopod.ui.screen.Lcd
import io.github.whyoolw.monopod.ui.screen.LocalLcdFont
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Cover Flow: the wheel slides through album covers; the center button flips the cover over
 * to its song list, where the wheel picks a song and the center button plays it. The song then
 * plays right here, on an iPod nano style screen; MENU goes back to the covers.
 */
class CoverFlowPage(
    override val title: String,
    /** Read on every use, so a list that is still loading (Spotify) fills in live. */
    private val source: () -> List<Album>,
    /** Shown when there are no covers (loading, failed); null means the "add a folder" hint. */
    private val empty: () -> String? = { null },
    /** A cover was flipped: its songs may still have to be loaded. */
    private val onOpen: (Album) -> Unit = {},
    /** Songs that play somewhere else (Spotify) open this page instead of the nano screen. */
    private val playElsewhere: ((List<Track>, Int) -> Page)? = null,
    private val onPlay: (List<Track>, Int) -> Unit = { _, _ -> },
) : Page {
    constructor(title: String, albums: List<Album>, onPlay: (List<Track>, Int) -> Unit) :
        this(title, { albums }, onPlay = onPlay)

    val albums: List<Album> get() = source()
    val emptyText: String? get() = empty()
    /** The playing screen's wheel modes (volume, scrub, rating, lyrics), as on Now Playing. */
    val modes = NowPlayingPage(title)
    /** Songs play in Spotify, not our player: the playing screen shows Spotify's song. */
    var remote = false

    /** Showing the playing song instead of the cover wall. */
    var playing by mutableStateOf(false)
        private set

    var index by mutableIntStateOf(0)
        private set
    private var openIndex by mutableStateOf<Int?>(null)
    /** The album whose songs are on the back of the cover; kept while it turns back, so it never blanks. */
    var shownOpen by mutableStateOf<Album?>(null)
        private set
    /** The album flipped over to its song list, or null; looked up live, its songs may arrive later. */
    val open: Album? get() = openIndex?.let { albums.getOrNull(it) }?.also { shownOpen = it }
    var track by mutableIntStateOf(0)
        private set
    var trackFirst by mutableIntStateOf(0)
        private set
    /** Rows the back of the cover fits; set by the view. */
    var trackRows by mutableIntStateOf(6)

    fun scroll(steps: Int) {
        val album = open
        if (album == null) {
            index = (index + steps).coerceIn(0, (albums.size - 1).coerceAtLeast(0))
        } else {
            track = (track + steps).coerceIn(0, album.tracks.lastIndex)
            if (track < trackFirst) trackFirst = track
            if (track >= trackFirst + trackRows) trackFirst = track - trackRows + 1
        }
    }

    /** Flips the cover, or plays the picked song; returns a page to open when it plays elsewhere. */
    fun select(): Page? {
        val album = open
        if (album == null) {
            albums.getOrNull(index)?.let { openIndex = index; shownOpen = it; track = 0; trackFirst = 0; onOpen(it) }
            return null
        }
        if (album.tracks.isEmpty()) return null
        playElsewhere?.let { return it(album.tracks, track) }
        onPlay(album.tracks, track)
        // The list stays open behind the playing screen: MENU turns the cover back to it
        playing = true
        return null
    }

    /**
     * MENU: from the playing screen back to the covers (on the playing song's album), or flip the
     * cover back; false when there is nothing left to undo and the page should close.
     */
    fun back(currentUri: String?): Boolean {
        if (playing) {
            playing = false
            // Back to the song list of the playing album, on the playing song
            val i = albums.indexOfFirst { a -> a.tracks.any { it.uri == currentUri } }
            if (i >= 0) {
                index = i
                openIndex = i
                shownOpen = albums[i]
                track = albums[i].tracks.indexOfFirst { it.uri == currentUri }.coerceAtLeast(0)
                trackFirst = (track - trackRows + 1).coerceIn(0, track)
            } else {
                openIndex = null
            }
            return true
        }
        if (openIndex == null) return false
        openIndex = null
        return true
    }
}

/** The cover's final slight turn on the playing screen, like the iPod nano's. */
private const val PLAYING_TILT = 24f

@Composable
fun CoverFlowView(
    page: CoverFlowPage,
    source: NowSource,
    volume: Volume,
    stats: StatsRepository,
    /** Song URI → its .lrc file, from the library. */
    lyricsFile: (String) -> String?,
    modifier: Modifier = Modifier,
) {
    if (Lcd.material) {
        AnimatedContent(
            targetState = page.playing,
            modifier = modifier,
            transitionSpec = { (fadeIn(tween(300)) + scaleIn(tween(300), initialScale = 0.92f)) togetherWith fadeOut(tween(200)) },
            label = "coverflow",
        ) { playing ->
            if (playing) NanoPlaying(page, source, volume, stats, lyricsFile) else MaterialCarousel(page)
        }
        return
    }
    // iPod: one scene. Picking a song turns the cover from its song list to the front and flies
    // it to the left of the playing screen; MENU turns it back to the list in the middle.
    val fly by animateFloatAsState(
        if (page.playing) 1f else 0f, tween(850, easing = FastOutSlowInEasing), label = "fly",
    )
    var origin by remember { mutableStateOf(Offset.Zero) }
    var target by remember { mutableStateOf<Rect?>(null) }
    Box(
        modifier.fillMaxSize()
            .background(lerp(Color.Black, Lcd.background, fly))
            .onGloballyPositioned { origin = it.positionInRoot() },
    ) {
        // The cover that flew over stays the playing cover (same turn, same reflection): the
        // playing screen only lends it its spot
        if (fly > 0f) NanoPlaying(
            page, source, volume, stats, lyricsFile,
            contentAlpha = ((fly - 0.55f) / 0.45f).coerceIn(0f, 1f),
            coverShown = false,
            tilt = PLAYING_TILT,
            onCoverPlaced = { target = it },
        )
        // Landed, it shows the playing song's own cover: a playlist's songs come from many albums
        CoverWall(page, fly, target?.translate(-origin), playingCover = if (fly >= 1f) source.cover else null)
    }
}

/**
 * The wall of covers. [fly] runs 0 → 1 while a picked song takes over: the others fade, the
 * center cover spins and moves onto [target] (the playing screen's cover spot, in this box).
 */
@Composable
private fun CoverWall(
    page: CoverFlowPage,
    fly: Float = 0f,
    target: Rect? = null,
    /** The playing song's cover, shown by the landed cover instead of the album's. */
    playingCover: String? = null,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        if (page.albums.isEmpty()) {
            BasicText(
                page.emptyText ?: stringResource(R.string.library_no_folders),
                Modifier.align(Alignment.Center).padding(16.dp),
                style = TextStyle(fontFamily = LocalLcdFont.current, fontSize = 14.sp, color = Color(0xFFB0B4BA), textAlign = TextAlign.Center),
            )
            return
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val captionHeight = 40.dp
            val cover = minOf((maxHeight - captionHeight) * 0.68f, maxWidth * 0.44f)
            val coverPx = with(LocalDensity.current) { cover.toPx() }
            val position by animateFloatAsState(page.index.toFloat(), spring(dampingRatio = 0.85f, stiffness = 220f), label = "cf")
            val flip by animateFloatAsState(if (page.open != null) 180f else 0f, tween(450), label = "flip")

            val wallWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
            val first = (floor(position).toInt() - 6).coerceAtLeast(0)
            val last = (ceil(position).toInt() + 6).coerceAtMost(page.albums.lastIndex)
            // Covers sit a bit above the middle, leaving room for their reflections and the caption
            val centerY = (maxHeight - captionHeight) / 2 - cover * 0.12f
            val centerYPx = with(LocalDensity.current) { centerY.toPx() }
            for (i in first..last) {
                key(page.albums[i].key) {
                    val d = i - position
                    val near = d.coerceIn(-1f, 1f)
                    val isCenter = i == page.index
                    // The flight: from the song list (180°) one half turn to the front, then a slight tilt
                    val spin = if (isCenter) flip + fly * (180f + PLAYING_TILT) else 0f
                    val facing = ((spin % 360f) + 360f) % 360f
                    val showBack = isCenter && facing > 90f && facing < 270f
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .offset(y = centerY - cover / 2)
                            .zIndex(-abs(d) + if (isCenter) 1f else 0f)
                            .graphicsLayer {
                                // Next to the center the covers swing round and stack up behind each other
                                val baseX = near * coverPx * 0.62f + (d - near) * coverPx * 0.24f
                                val grow = 1f + 0.1f * (flip / 180f)
                                val baseScale = (1f - 0.08f * abs(near)) * grow
                                if (isCenter && target != null) {
                                    translationX = baseX + fly * (target.center.x - wallWidthPx / 2 - baseX)
                                    translationY = fly * (target.center.y - centerYPx)
                                    val s = baseScale + fly * (target.width / coverPx - baseScale)
                                    scaleX = s
                                    scaleY = s
                                } else {
                                    translationX = baseX
                                    scaleX = baseScale
                                    scaleY = baseScale
                                }
                                rotationY = -near * 65f + spin
                                alpha = if (isCenter) 1f else 1f - fly
                                cameraDistance = 10f * density * coverPx / 100f
                                // The face's middle; the reflection hangs below it (face + 0.3 of it)
                                transformOrigin = TransformOrigin(0.5f, 0.5f / 1.3f)
                            },
                    ) {
                        if (showBack) {
                            // The back: turned once more so the list reads the right way; same height as a face
                            Column(Modifier.graphicsLayer { rotationY = 180f }) {
                                TrackList(page, cover)
                                Spacer(Modifier.height(cover * 0.3f))
                            }
                        } else {
                            CoverFace(
                                page.albums[i], cover, floor = lerp(Color.Black, Lcd.background, fly),
                                path = (if (isCenter) playingCover else null) ?: page.albums[i].coverPath,
                            )
                        }
                    }
                }
            }

            val album = page.albums[page.index.coerceIn(0, page.albums.lastIndex)]
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(captionHeight).padding(horizontal = 8.dp)
                    .graphicsLayer { alpha = 1f - fly },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BasicText(
                    album.title ?: stringResource(R.string.unknown_album),
                    style = TextStyle(fontFamily = LocalLcdFont.current, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                BasicText(
                    album.artist ?: stringResource(R.string.unknown_artist),
                    style = TextStyle(fontFamily = LocalLcdFont.current, fontSize = 12.sp, color = Color(0xFF9DA2A8), textAlign = TextAlign.Center),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CoverFace(album: Album, size: Dp, floor: Color = Color.Black, path: String? = album.coverPath) {
    // The old picture stays until the new one (the next song's cover) is loaded, then fades over it
    var image by remember { mutableStateOf(CoverCache.peek(path, full = true)) }
    LaunchedEffect(path) {
        val next = CoverCache.load(path, full = true)
        if (next != null || path == null) image = next
    }
    val face: @Composable () -> Unit = {
        Crossfade(image, animationSpec = tween(400), label = "cover") { img ->
        if (img != null) {
            Image(img, null, Modifier.size(size), contentScale = ContentScale.Crop)
        } else {
            Box(
                Modifier.size(size).background(Brush.verticalGradient(listOf(Color(0xFF4A4E55), Color(0xFF26282C)))),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    album.title ?: "♪",
                    Modifier.padding(8.dp),
                    style = TextStyle(fontFamily = LocalLcdFont.current, fontSize = 13.sp, color = Color(0xFFD9DCE0), textAlign = TextAlign.Center),
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        }
    }
    Column {
        face()
        // Reflection on the black "floor"
        Box(Modifier.width(size).height(size * 0.3f).clipToBounds()) {
            Box(Modifier.graphicsLayer { scaleY = -1f; alpha = 0.4f }) { face() }
            // Fades into the floor, which turns from black to the playing screen's color
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Brush.verticalGradient(listOf(floor.copy(alpha = 0.35f), floor)))
            }
        }
    }
}

@Composable
private fun TrackList(page: CoverFlowPage, size: Dp) {
    val album = page.open ?: page.shownOpen ?: return
    val rowHeight = 17.dp
    val header = 30.dp
    val rows = ((size - header) / rowHeight).toInt().coerceAtLeast(3)
    SideEffect { page.trackRows = rows }
    // On a Material card the list needs its own tone to stand off the screen behind it
    Column(Modifier.size(size).background(if (Lcd.material) Lcd.trackTop else Lcd.background)) {
        Column(
            Modifier.fillMaxWidth().height(header)
                .background(Brush.verticalGradient(listOf(Lcd.barTop, Lcd.barBottom)))
                .padding(horizontal = 6.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            BasicText(
                album.title ?: stringResource(R.string.unknown_album),
                style = TextStyle(fontFamily = LocalLcdFont.current, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Lcd.text),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            BasicText(
                album.artist ?: stringResource(R.string.unknown_artist),
                style = TextStyle(fontFamily = LocalLcdFont.current, fontSize = 9.sp, color = Lcd.dimText),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (album.tracks.isEmpty()) BasicText(
            stringResource(R.string.spotify_loading),
            Modifier.padding(6.dp),
            style = TextStyle(fontFamily = LocalLcdFont.current, fontSize = 11.sp, color = Lcd.dimText),
        )
        val end = minOf(page.trackFirst + rows, album.tracks.size)
        for (i in page.trackFirst until end) {
            val selected = i == page.track
            Row(
                Modifier.fillMaxWidth().height(rowHeight)
                    .then(if (selected) Modifier.background(Brush.verticalGradient(listOf(Lcd.selectionTop, Lcd.selectionBottom))) else Modifier)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val color = if (selected) Lcd.onSelection else Lcd.text
                BasicText(
                    "${album.tracks[i].trackNo ?: i + 1}",
                    Modifier.width(18.dp),
                    style = TextStyle(fontFamily = LocalLcdFont.current, fontSize = 10.sp, color = if (selected) Lcd.onSelection else Lcd.dimText),
                )
                BasicText(
                    album.tracks[i].title,
                    style = TextStyle(fontFamily = LocalLcdFont.current, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Cover Flow in Material: a carousel. The cover in front is big; its neighbours are squeezed
 * into narrow pills, and as the wheel turns the next pill widens into the big cover while the
 * current one narrows (the picture is cropped, not squashed). The center button brings up the
 * album's songs on a card.
 */
@Composable
private fun MaterialCarousel(page: CoverFlowPage) {
    val lcd = Lcd
    Box(Modifier.fillMaxSize().background(lcd.background)) {
        if (page.albums.isEmpty()) {
            BasicText(
                page.emptyText ?: stringResource(R.string.library_no_folders),
                Modifier.align(Alignment.Center).padding(16.dp),
                style = TextStyle(fontFamily = LocalLcdFont.current, fontSize = 14.sp, color = lcd.dimText, textAlign = TextAlign.Center),
            )
            return
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val captionHeight = 44.dp
            val density = LocalDensity.current
            val heroH = (maxHeight - captionHeight) * 0.9f
            val hero = minOf(heroH, maxWidth * 0.6f)
            val heroPx = with(density) { hero.toPx() }
            val pillPx = heroPx * 0.2f
            val gapPx = with(density) { 6.dp.toPx() }
            val centerX = with(density) { maxWidth.toPx() } / 2
            val position by animateFloatAsState(page.index.toFloat(), spring(dampingRatio = 0.9f, stiffness = 260f), label = "carousel")

            // Width of item i: the front one is big, the next ones in line shrink smoothly into pills
            fun widthOf(i: Int): Float {
                val d = abs(i - position)
                return if (d < 1f) heroPx + (pillPx - heroPx) * d else pillPx * (1f - (d - 1f) * 0.18f).coerceAtLeast(0.5f)
            }
            val fl = floor(position).toInt().coerceIn(0, page.albums.lastIndex)
            val frac = position - fl
            // Left edges, laid out from the item at the front
            val first = (fl - 6).coerceAtLeast(0)
            val last = (fl + 7).coerceAtMost(page.albums.lastIndex)
            val left = HashMap<Int, Float>()
            left[fl] = 0f
            for (i in fl + 1..last) left[i] = left.getValue(i - 1) + widthOf(i - 1) + gapPx
            for (i in fl - 1 downTo first) left[i] = left.getValue(i + 1) - gapPx - widthOf(i)
            val next = if (fl + 1 <= page.albums.lastIndex) widthOf(fl + 1) else 0f
            val anchor = widthOf(fl) / 2 + frac * (widthOf(fl) / 2 + gapPx + next / 2)
            val shift = centerX - anchor
            val top = with(density) { ((maxHeight - captionHeight - heroH) / 2).toPx() }
            val heroHPx = with(density) { heroH.toPx() }

            for (i in first..last) {
                key(page.albums[i].key) {
                    val w = widthOf(i)
                    val d = abs(i - position).coerceAtMost(1f)
                    val h = heroHPx * (1f - 0.12f * d)
                    with(density) {
                        CarouselImage(
                            page.albums[i],
                            Modifier
                                .offset { IntOffset((shift + left.getValue(i)).roundToInt(), (top + (heroHPx - h) / 2).roundToInt()) }
                                .size(w.toDp(), h.toDp())
                                .clip(RoundedCornerShape(20.dp)),
                        )
                    }
                }
            }

            val album = page.albums[page.index.coerceIn(0, page.albums.lastIndex)]
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(captionHeight).padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BasicText(
                    album.title ?: stringResource(R.string.unknown_album),
                    style = TextStyle(fontFamily = LocalLcdFont.current, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = lcd.text, textAlign = TextAlign.Center),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                BasicText(
                    album.artist ?: stringResource(R.string.unknown_artist),
                    style = TextStyle(fontFamily = LocalLcdFont.current, fontSize = 12.sp, color = lcd.dimText, textAlign = TextAlign.Center),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }

            // The album's songs on a card over the front cover
            val card = minOf(heroH, maxWidth * 0.8f)
            AnimatedVisibility(
                page.open != null,
                Modifier.align(Alignment.TopCenter).offset(y = with(density) { top.toDp() }),
                enter = fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.9f),
                exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.9f),
            ) {
                Box(Modifier.clip(RoundedCornerShape(20.dp))) { TrackList(page, card) }
            }
        }
    }
}

@Composable
private fun CarouselImage(album: Album, modifier: Modifier) {
    val image by produceState(CoverCache.peek(album.coverPath, full = true), album.coverPath) {
        value = CoverCache.load(album.coverPath, full = true)
    }
    Box(modifier.background(Lcd.placeholder), contentAlignment = Alignment.Center) {
        val img = image
        if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else BasicText("♪", style = TextStyle(fontSize = 22.sp, color = Lcd.placeholderText))
    }
}
