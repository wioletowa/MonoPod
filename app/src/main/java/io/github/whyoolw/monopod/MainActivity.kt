package io.github.whyoolw.monopod

import android.os.Bundle
import io.github.whyoolw.monopod.ui.wheel.MaterialWheel
import io.github.whyoolw.monopod.ui.screen.LcdColors
import io.github.whyoolw.monopod.ui.menu.Page
import io.github.whyoolw.monopod.ui.material.rememberMaterialScheme
import io.github.whyoolw.monopod.ui.material.animatedLcd
import io.github.whyoolw.monopod.ui.material.animatedBody
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.whyoolw.monopod.ui.stickers.findStickerSpot
import io.github.whyoolw.monopod.ui.stickers.StickersLayer
import io.github.whyoolw.monopod.ui.stickers.StickerStore
import io.github.whyoolw.monopod.ui.stickers.StickerEditor
import io.github.whyoolw.monopod.ui.stickers.Keepout
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.activity.result.PickVisualMediaRequest
import android.os.SystemClock
import io.github.whyoolw.monopod.ui.coverflow.CoverFlowView
import io.github.whyoolw.monopod.ui.coverflow.CoverFlowPage
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import io.github.whyoolw.monopod.playback.Playback
import io.github.whyoolw.monopod.ui.nowplaying.NowPlayingPage
import io.github.whyoolw.monopod.ui.nowplaying.SpotifyNowPlayingPage
import io.github.whyoolw.monopod.ui.nowplaying.LocalSource
import io.github.whyoolw.monopod.ui.nowplaying.NowSource
import io.github.whyoolw.monopod.ui.nowplaying.SpotifySource
import io.github.whyoolw.monopod.ui.nowplaying.SpotifyNowPlayingView
import io.github.whyoolw.monopod.spotify.Spotify
import android.content.Intent
import androidx.compose.runtime.CompositionLocalProvider
import android.widget.Toast
import io.github.whyoolw.monopod.ui.theme.CustomBodies
import io.github.whyoolw.monopod.ui.theme.CustomBody
import io.github.whyoolw.monopod.ui.theme.label
import io.github.whyoolw.monopod.ui.theme.rememberThemePicture
import io.github.whyoolw.monopod.ui.theme.LocalMenuPicture
import io.github.whyoolw.monopod.ui.theme.MenuPicture
import io.github.whyoolw.monopod.ui.menu.coverPath
import io.github.whyoolw.monopod.ui.material.rememberCoverSeed
import io.github.whyoolw.monopod.ui.material.accentFromSeed
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import io.github.whyoolw.monopod.ui.presets.PresetStore
import io.github.whyoolw.monopod.ui.presets.PresetsPage
import io.github.whyoolw.monopod.ui.presets.PresetsView
import io.github.whyoolw.monopod.ui.quick.QuickSettings
import io.github.whyoolw.monopod.ui.quick.QuickSettingsPanel
import io.github.whyoolw.monopod.ui.nowplaying.NowPlayingView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import io.github.whyoolw.monopod.ui.screen.family
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.whyoolw.monopod.data.LibraryRepository
import io.github.whyoolw.monopod.data.StatsRepository
import io.github.whyoolw.monopod.ui.menu.MenuController
import io.github.whyoolw.monopod.ui.menu.PageView
import io.github.whyoolw.monopod.ui.menu.rootPage
import io.github.whyoolw.monopod.ui.screen.LcdScreen
import io.github.whyoolw.monopod.ui.screen.DarkLcd
import io.github.whyoolw.monopod.ui.screen.LightLcd
import io.github.whyoolw.monopod.ui.screen.withAccent
import io.github.whyoolw.monopod.ui.theme.BodyTheme
import io.github.whyoolw.monopod.ui.theme.Finish
import io.github.whyoolw.monopod.ui.wheel.ClickWheel
import io.github.whyoolw.monopod.ui.wheel.WheelEvent
import io.github.whyoolw.monopod.ui.wheel.WheelZone
import io.github.whyoolw.monopod.ui.wheel.rememberWheelFeedback

class MainActivity : ComponentActivity() {
    private val app by lazy { AppState(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val library = (application as Monopod).library
        (application as Monopod).spotify.handleRedirect(intent?.data)
        setContent { MonopodApp(app, library) }
    }

    // The browser hands the Spotify sign-in back here (singleTask: the running app gets it)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        (application as Monopod).spotify.handleRedirect(intent.data)
    }
}

@Composable
fun MonopodApp(app: AppState, library: LibraryRepository) {
    val res = LocalResources.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(library::addFolder)
    }
    val context = LocalContext.current
    val playback = remember { Playback(context.applicationContext) }
    DisposableEffect(playback) {
        playback.connect()
        onDispose { playback.release() }
    }
    val stats = (context.applicationContext as Monopod).stats
    val spotify = (context.applicationContext as Monopod).spotify
    // Stickers: the editor covers the app; keep-out areas follow where the screen and wheel really are
    val stickers = remember { StickerStore(context.applicationContext) }
    // Look presets: the picture of a slot is the app as it is on screen
    val presets = remember { PresetStore(context.applicationContext, app, stickers) }
    val activityView = LocalView.current
    SideEffect {
        presets.capture = { done ->
            val window = (activityView.context as ComponentActivity).window
            val root = window.decorView
            if (root.width == 0) done(null)
            else {
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                PixelCopy.request(window, bitmap, { result ->
                    done(bitmap.takeIf { result == PixelCopy.SUCCESS })
                }, Handler(Looper.getMainLooper()))
            }
        }
    }
    var stickerEditing by remember { mutableStateOf(false) }
    var screenRect by remember { mutableStateOf(Rect.Zero) }
    var wheelRect by remember { mutableStateOf(Rect.Zero) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    val stickerScope = rememberCoroutineScope()
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) stickerScope.launch {
            stickers.add(uri) { aspect ->
                findStickerSpot(aspect, rootSize.width.toFloat(), rootSize.height.toFloat(), Keepout(screenRect, wheelRect))
            }
        }
    }
    // A theme file for a new custom body, or for updating one (held in themeTarget meanwhile)
    var themeTarget by remember { mutableStateOf<CustomBody?>(null) }
    val themePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val replace = themeTarget
        themeTarget = null
        if (uri != null) {
            runCatching { CustomBodies.add(context, uri, replace) }
                .onSuccess { custom ->
                    app.theme = custom.body
                    app.applyThemeSettings(custom.settings)
                    Toast.makeText(context, res.getString(R.string.theme_added, custom.body.label(res)), Toast.LENGTH_SHORT).show()
                }
                .onFailure { e ->
                    Toast.makeText(context, res.getString(R.string.theme_bad_file, e.message ?: "?"), Toast.LENGTH_LONG).show()
                }
        }
    }
    val feedback = rememberWheelFeedback()
    val menu = remember {
        MenuController(rootPage(res, app, library, stats, playback, feedback, spotify, (context.applicationContext as Monopod).yandex, presets, { stickerEditing = true }, { target ->
            themeTarget = target
            themePicker.launch(arrayOf("text/css", "text/plain", "application/octet-stream", "*/*"))
        }) { picker.launch(null) })
    }
    val scope = rememberCoroutineScope()
    // What plays, for the Cover Flow playing screen: our player or Spotify
    val localSource = remember { LocalSource(playback) { library.snapshot.coverForTrack(it) } }
    val spotifySource = remember { SpotifySource(spotify) }
    val quick = remember { QuickSettings(res, app, playback, spotify, (context.applicationContext as Monopod).yandex) }
    val wheel = remember { WheelRouter(menu, playback, spotify, stats, scope, quick, localSource, spotifySource) }

    val view = LocalView.current
    // Read here, not inside SideEffect: reads there are not tracked, so a changed setting
    // would only apply on some unrelated recomposition
    val clickSound = app.clickSound
    val clickHaptics = app.clickHaptics
    val hapticStrength = app.hapticStrength
    val hideStatusBar = app.hideStatusBar
    val hapticMode = app.hapticMode
    val pulseMs = app.pulseMs
    val hapticMedia = app.hapticMedia
    val clickVolume = app.clickVolume
    val accelBoost = AppState.ACCEL_LEVELS[app.accelLevel]
    val darkScreen = when (app.darkScreen) {
        DarkScreen.On -> true
        DarkScreen.Off -> false
        DarkScreen.System -> isSystemInDarkTheme()
    }
    // Material style: one color scheme (from the album cover or the wallpaper) for everything
    val material = app.uiStyle == UiStyle.Material
    val coverPath = if (spotify.active) spotify.coverPath
    else playback.mediaUri?.let { library.snapshot.coverForTrack(it) } ?: playback.metadata.artworkUri?.coverPath()
    val scheme = if (material) rememberMaterialScheme(app.materialColors, darkScreen, coverPath) else null
    val materialLcd = scheme?.let { animatedLcd(it) }
    // iPod style: the classic palette with the chosen accent color
    val accent = app.ipodAccent
    // "From cover": only the highlight follows the album, the rest stays iPod
    val coverSeed = rememberCoverSeed(if (!material && accent == IpodAccent.Cover) coverPath else null)
    val ipodLcd = remember(darkScreen, accent, coverSeed) {
        val (top, bottom) = coverSeed?.let(::accentFromSeed) ?: (Color(accent.top) to Color(accent.bottom))
        (if (darkScreen) DarkLcd else LightLcd).withAccent(top, bottom)
    }
    val screenColors = materialLcd ?: ipodLcd
    val theme = scheme?.let { animatedBody(it, darkScreen) } ?: app.theme
    // A custom body's picture behind the main menu (iPod style, where bodies apply)
    val menuPicture = if (material) null else app.theme.menuImage?.let { MenuPicture(it, app.theme.menuVeil) }
    val screenFont = if (material) FontFamily.Default else app.font.family()
    SideEffect {
        feedback.soundEnabled = clickSound
        feedback.hapticsEnabled = clickHaptics
        feedback.hapticStrength = hapticStrength / 10f
        feedback.hapticMode = hapticMode
        feedback.pulseMs = pulseMs
        feedback.mediaChannel = hapticMedia
        feedback.soundVolume = clickVolume / 10f
        menu.accelBoost = accelBoost
        val window = (view.context as ComponentActivity).window
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = theme.lightBody
            isAppearanceLightNavigationBars = theme.lightBody
            // A swipe from the top still shows the bar for a moment
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (hideStatusBar) hide(WindowInsetsCompat.Type.statusBars()) else show(WindowInsetsCompat.Type.statusBars())
        }
    }

    // On its side the phone shows Cover Flow full screen, with the wheel to the right
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val coverFlowTitle = res.getString(R.string.menu_cover_flow)
    val landscapeFlow = remember(landscape) {
        if (!landscape) null
        else CoverFlowPage(coverFlowTitle, library.snapshot.albums) { queue, index ->
            spotify.deactivate()
            playback.play(queue, index, library.snapshot)
        }
    }
    SideEffect { wheel.landscapeFlow = landscapeFlow }

    BackHandler(enabled = !landscape && menu.canGoBack) { menu.back() }
    BackHandler(enabled = landscapeFlow?.let { it.open != null || it.playing } == true) { landscapeFlow?.back(playback.mediaUri) }
    // Declared last, so it wins while the panel is up
    BackHandler(enabled = quick.open) { quick.close() }
    BackHandler(enabled = stickerEditing) {
        stickers.save()
        stickerEditing = false
    }

    Box(Modifier.fillMaxSize().onSizeChanged { rootSize = it }) {
        Body(theme)
        StickersLayer(stickers)
        BoxWithConstraints(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.TopCenter,
        ) {
            val current = menu.current
            val screen: @Composable (Modifier) -> Unit = { mod ->
                LcdScreen(
                    title = current.page.title,
                    playing = when {
                        spotify.active -> spotify.isPlaying
                        playback.hasQueue -> playback.isPlaying
                        else -> null
                    },
                    shuffle = playback.shuffle,
                    repeatMode = playback.repeatMode,
                    effect = app.effect,
                    effectIntensity = app.intensity,
                    font = screenFont,
                    dark = darkScreen,
                    colors = screenColors,
                    crtSpeed = app.crtSpeed / 10f,
                    bezel = theme.bezel,
                    frame = theme.screenFrame,
                    modifier = mod.onGloballyPositioned { screenRect = it.boundsInRoot() },
                ) {
                    AnimatedContent(
                        targetState = current,
                        transitionSpec = {
                            val forward = targetState.depth > initialState.depth
                            val dir = if (forward) 1 else -1
                            slideInHorizontally(tween(220)) { it * dir } togetherWith
                                slideOutHorizontally(tween(220)) { -it * dir }
                        },
                        label = "page",
                    ) { state ->
                        val page = state.page
                        when (page) {
                            is PresetsPage -> PresetsView(page, presets)
                            is SpotifyNowPlayingPage -> SpotifyNowPlayingView(page, spotify, playback.volume)
                            is NowPlayingPage -> NowPlayingView(page, playback, stats, { library.snapshot.lyricsFile(it) }, { library.snapshot.coverForTrack(it) })
                            is CoverFlowPage -> CoverFlowView(
                                page, if (page.remote) spotifySource else localSource, playback.volume, stats,
                                { library.snapshot.lyricsFile(it) },
                            )
                            else -> CompositionLocalProvider(LocalMenuPicture provides menuPicture) { PageView(state) }
                        }
                    }
                    QuickSettingsPanel(quick)
                }
            }
            val clickWheel: @Composable (Modifier) -> Unit = { mod ->
                ClickWheel(
                    theme = theme, feedback = feedback, onEvent = wheel::onEvent,
                    degreesPerStep = app.wheelDegrees.toFloat(),
                    material = if (scheme != null && materialLcd != null) {
                        materialWheel(menu.current.page, landscapeFlow, quick, if (spotify.active) spotify.isPlaying else playback.isPlaying, materialLcd, scheme.onPrimary)
                    } else null,
                    modifier = mod.onGloballyPositioned { wheelRect = it.boundsInRoot() },
                )
            }
            if (landscapeFlow != null) {
                val wheelSize = minOf(maxHeight * 0.82f, maxWidth * 0.3f)
                Row(Modifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    LcdScreen(
                        title = coverFlowTitle,
                        playing = if (playback.hasQueue) playback.isPlaying else null,
                        shuffle = playback.shuffle,
                        repeatMode = playback.repeatMode,
                        effect = app.effect,
                        effectIntensity = app.intensity,
                        font = screenFont,
                        dark = darkScreen,
                        colors = screenColors,
                        crtSpeed = app.crtSpeed / 10f,
                        bezel = theme.bezel,
                        frame = theme.screenFrame,
                        modifier = Modifier.weight(1f).fillMaxHeight().onGloballyPositioned { screenRect = it.boundsInRoot() },
                    ) {
                        CoverFlowView(landscapeFlow, localSource, playback.volume, stats, { library.snapshot.lyricsFile(it) })
                        QuickSettingsPanel(quick)
                    }
                    Spacer(Modifier.width(14.dp))
                    clickWheel(Modifier.width(wheelSize))
                }
            } else {
                // Classic keeps the iPod's 4:3 screen. Big screen keeps the wheel just where Classic has it
                // and grows the screen up and to the sides, up to about as tall as it is wide.
                val classicHeight = maxWidth * 0.88f * 0.75f
                val wheelSize = minOf(maxWidth * 0.80f, maxHeight - classicHeight - 56.dp)
                val free = maxHeight - classicHeight - wheelSize
                val wheelTop = free * (0.6f / 2.6f) + classicHeight + free * (1f / 2.6f)
                val big = app.layout == ScreenLayout.BigScreen
                val screenWidth = maxWidth * if (big) 0.96f else 0.88f
                val screenHeight = if (big) minOf(maxWidth * 0.97f, wheelTop - 30.dp - 12.dp) else classicHeight
                val screenTop = if (big) wheelTop - 30.dp - screenHeight else free * (0.6f / 2.6f)
                Box(Modifier.fillMaxSize()) {
                    screen(Modifier.align(Alignment.TopCenter).offset(y = screenTop).width(screenWidth).height(screenHeight))
                    clickWheel(Modifier.align(Alignment.TopCenter).offset(y = wheelTop).width(wheelSize))
                }
            }
        }
        if (stickerEditing) {
            StickerEditor(
                stickers,
                Keepout(screenRect, wheelRect),
                onAdd = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onDone = { stickerEditing = false },
            )
        }
    }
}

/** Sends wheel input to the menu or, on the Now Playing screen, to that screen; the ring buttons drive playback anywhere. */
private class WheelRouter(
    private val menu: MenuController,
    private val playback: Playback,
    private val spotify: Spotify,
    private val stats: StatsRepository,
    private val scope: CoroutineScope,
    private val quick: QuickSettings,
    private val localSource: NowSource,
    private val spotifySource: NowSource,
) {
    private var seekJob: Job? = null

    /** Set while the phone is on its side and Cover Flow takes over the whole screen. */
    var landscapeFlow: CoverFlowPage? = null

    fun onEvent(event: WheelEvent) {
        // Holding ⏯ opens (or closes) the quick settings on any screen
        if (event == WheelEvent.LongPress(WheelZone.PlayPause)) return quick.toggle()
        if (quick.open) {
            when {
                event is WheelEvent.Scroll -> return quick.scroll(event.steps)
                event == WheelEvent.Select -> return quick.select()
                event == WheelEvent.Press(WheelZone.Menu) -> return quick.menu()
                event == WheelEvent.LongPress(WheelZone.Menu) -> return quick.close()
            }
        }
        val landscape = landscapeFlow
        val flow = landscape ?: menu.current.page as? CoverFlowPage
        if (flow != null) {
            when {
                // On the playing screen the wheel and the center button work as on Now Playing
                event is WheelEvent.Scroll && flow.playing -> {
                    flow.modes.scroll(event.steps, if (flow.remote) spotifySource else localSource, playback.volume, stats)
                    return
                }
                event is WheelEvent.Scroll -> return flow.scroll(event.steps)
                event == WheelEvent.Select && flow.playing -> return flow.modes.select(canRate = !flow.remote)
                event == WheelEvent.Select -> {
                    flow.select()?.let(menu::push)
                    return
                }
                event == WheelEvent.Press(WheelZone.Menu) -> {
                    if (!flow.back(if (flow.remote) spotify.trackUri else playback.mediaUri) && landscape == null) menu.back()
                    return
                }
                event == WheelEvent.LongPress(WheelZone.Menu) && landscape != null -> return
            }
        }
        // Look presets: the grid and its popups take the wheel
        (menu.current.page as? PresetsPage)?.let { p ->
            when {
                event is WheelEvent.Scroll -> return p.scroll(event.steps)
                event == WheelEvent.Select -> return p.select()
                event == WheelEvent.SelectLong -> return p.selectLong()
                event == WheelEvent.Press(WheelZone.Menu) -> { if (!p.back()) menu.back(); return }
            }
        }
        // Spotify plays: its own Now Playing page, and the buttons drive the Spotify app
        val spotifyPage = menu.current.page as? SpotifyNowPlayingPage
        if (spotifyPage != null && event is WheelEvent.Scroll) return spotifyPage.scroll(event.steps, spotify, playback.volume)
        if (spotifyPage != null && event == WheelEvent.Select) return spotifyPage.select()
        if (spotify.active && event is WheelEvent.Press) when (event.zone) {
            WheelZone.PlayPause -> return spotify.playPause()
            WheelZone.Next -> return spotify.next()
            WheelZone.Previous -> return spotify.previous()
            WheelZone.Menu -> Unit
        }
        val nowPlaying = menu.current.page as? NowPlayingPage
        when (event) {
            is WheelEvent.Scroll ->
                if (nowPlaying != null) nowPlaying.scroll(event.steps, playback, stats) else menu.scroll(event.steps)
            WheelEvent.Select -> if (nowPlaying != null) nowPlaying.select() else menu.select()
            is WheelEvent.Press -> when (event.zone) {
                WheelZone.Menu -> menu.back()
                WheelZone.PlayPause -> playback.playPause()
                WheelZone.Next -> playback.next()
                WheelZone.Previous -> playback.previous()
            }
            is WheelEvent.LongPress -> when (event.zone) {
                WheelZone.Menu -> menu.home()
                WheelZone.Next -> startSeeking(forward = true)
                WheelZone.Previous -> startSeeking(forward = false)
                WheelZone.PlayPause -> Unit
            }
            is WheelEvent.Release -> {
                seekJob?.cancel()
                seekJob = null
            }
            WheelEvent.SelectLong -> if (nowPlaying == null) menu.selectLong()
        }
    }

    /** Holding ⏭/⏮ scans through the song, speeding up after two seconds like the iPod. */
    private fun startSeeking(forward: Boolean) {
        seekJob?.cancel()
        seekJob = scope.launch {
            var held = 0L
            while (true) {
                val step = if (held < 2000) 2000L else 6000L
                if (spotify.active) spotify.seekBy(if (forward) step else -step) else playback.seekBy(if (forward) step else -step)
                delay(200)
                held += 200
            }
        }
    }
}

@Composable
private fun Body(theme: BodyTheme) {
    // A custom body may bring its own picture, laid over the gradient (which shows while it loads)
    val picture = rememberThemePicture(theme.bodyImage)
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(theme.bodyTop, theme.bodyBottom)))
        picture?.let { img ->
            // Fill the body, cropping what does not fit (like "cover" in CSS)
            val scale = maxOf(size.width / img.width, size.height / img.height)
            val w = img.width * scale
            val h = img.height * scale
            drawImage(
                img,
                dstOffset = androidx.compose.ui.unit.IntOffset(((size.width - w) / 2).toInt(), ((size.height - h) / 2).toInt()),
                dstSize = androidx.compose.ui.unit.IntSize(w.toInt(), h.toInt()),
            )
        }
        // Gentle top highlight, like light falling on a glossy case; matte bodies have none
        if (theme.finish != Finish.Matte) drawRect(
            Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.18f), Color.Transparent),
                center = Offset(size.width * 0.3f, 0f),
                radius = size.width,
            ),
        )
        when (theme.finish) {
            Finish.Brushed -> {
                var y = 0f
                var i = 0
                while (y < size.height) {
                    val a = if (i % 3 == 0) 0.035f else 0.018f
                    drawRect(
                        if (i % 2 == 0) Color.White.copy(alpha = a) else Color.Black.copy(alpha = a),
                        topLeft = Offset(0f, y),
                        size = Size(size.width, 1.5f),
                    )
                    y += 2f
                    i++
                }
            }
            Finish.Gloss, Finish.Matte -> Unit
        }
        theme.rim?.let { rim ->
            val w = 3.dp.toPx()
            drawRect(rim, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w), style = Stroke(w))
            drawRect(Color.White.copy(alpha = 0.6f), topLeft = Offset(w * 1.5f, w * 1.5f), size = Size(size.width - w * 3, size.height - w * 3), style = Stroke(1.dp.toPx()))
        }
    }
}

private fun modeIcon(mode: NowPlayingPage.Mode) = when (mode) {
    NowPlayingPage.Mode.Volume -> Icons.AutoMirrored.Rounded.VolumeUp
    NowPlayingPage.Mode.Scrub -> Icons.Rounded.FastForward
    NowPlayingPage.Mode.Rating -> Icons.Rounded.Star
    NowPlayingPage.Mode.Lyrics -> Icons.Rounded.FormatQuote
}

/** The Material wheel's icons; the center one says what the button does right now. */
private fun materialWheel(
    page: Page,
    landscapeFlow: CoverFlowPage?,
    quick: QuickSettings,
    playing: Boolean,
    lcd: LcdColors,
    onPrimary: Color,
): MaterialWheel {
    val playPause = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow
    val flow = landscapeFlow ?: page as? CoverFlowPage
    // Rounded square in menus, a circle while a song is in front
    val (center, round) = when {
        quick.open -> (if (quick.editing) Icons.Rounded.Check else Icons.Rounded.Edit) to false
        flow != null && flow.playing -> modeIcon(flow.modes.mode) to true
        flow != null && flow.open != null -> Icons.Rounded.PlayArrow to false
        flow != null -> Icons.AutoMirrored.Rounded.List to false
        page is NowPlayingPage -> modeIcon(page.mode) to true
        page is SpotifyNowPlayingPage -> (if (page.scrubbing) Icons.Rounded.FastForward else Icons.AutoMirrored.Rounded.VolumeUp) to true
        else -> Icons.Rounded.Check to false
    }
    return MaterialWheel(
        top = Icons.Rounded.Menu,
        right = Icons.Rounded.SkipNext,
        bottom = playPause,
        left = Icons.Rounded.SkipPrevious,
        center = center,
        round = round,
        icon = lcd.dimText,
        fab = lcd.accent,
        onFab = onPrimary,
    )
}
