package io.github.whyoolw.monopod.ui.quick

import androidx.compose.runtime.mutableStateListOf
import io.github.whyoolw.monopod.spotify.Spotify
import io.github.whyoolw.monopod.yandex.YandexMusic
import io.github.whyoolw.monopod.yandex.YandexPlaylistRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.res.Resources
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import io.github.whyoolw.monopod.AppState
import io.github.whyoolw.monopod.Prefs
import io.github.whyoolw.monopod.R
import io.github.whyoolw.monopod.playback.EqPreset
import io.github.whyoolw.monopod.playback.Playback
import io.github.whyoolw.monopod.ui.screen.Lcd
import io.github.whyoolw.monopod.ui.screen.LocalLcdFont
import kotlinx.coroutines.delay

/** The panel closes itself after this long without a touch. */
private const val IDLE_CLOSE_MS = 5_000L

val CROSSFADE_OPTIONS = listOf(0, 2, 4, 6, 8, 12)
val SLEEP_MINUTES = listOf(15, 30, 60, 90, 120)

/**
 * One line of the panel: its name, the value as text, and how a wheel click changes it.
 * A line with an [action] does it straight away on the center button instead (Like, Add to…).
 */
class QuickRow(
    val label: String,
    val value: () -> String,
    val action: (() -> Unit)? = null,
    val change: (steps: Int) -> Unit = {},
)

/** "Add to…": the user's playlists, the wheel moves, the center ticks, MENU adds and closes. */
class PlaylistPicker {
    var lists by mutableStateOf<List<YandexPlaylistRef>?>(null)
    var failed by mutableStateOf(false)
    var cursor by mutableIntStateOf(0)
    val marked = mutableStateListOf<Int>()
}

/**
 * Quick settings, opened by holding ⏯ anywhere: the wheel picks a line, the center button
 * starts changing it (the wheel then changes the value right away), center again is done.
 */
class QuickSettings(
    private val res: Resources,
    private val app: AppState,
    private val playback: Playback,
    private val spotify: Spotify,
    private val yandex: YandexMusic,
) {
    private val scope = MainScope()
    var open by mutableStateOf(false)
        private set
    var row by mutableIntStateOf(0)
        private set
    var editing by mutableStateOf(false)
        private set
    var lastTouch = 0L
        private set

    private fun s(id: Int) = res.getString(id)
    private fun touch() { lastTouch = SystemClock.uptimeMillis() }

    /** Moves along [options] from the current one, clamped at both ends. */
    private fun <T> step(options: List<T>, current: Int, steps: Int): T =
        options[(current.coerceAtLeast(0) + steps).coerceIn(0, options.lastIndex)]

    /** The Yandex Music song playing in our player, if any (Spotify playing wins). */
    private val yandexTrack: String?
        get() = if (spotify.active) null else playback.mediaUri?.let(YandexMusic::trackId)

    /** Whether the playing Yandex song is liked; null while unknown. */
    private var yandexLiked by mutableStateOf<Boolean?>(null)
    private var yandexLikedOf: String? = null

    var picker by mutableStateOf<PlaylistPicker?>(null)
        private set

    private val likeRow = QuickRow(s(R.string.like), {
        val on = if (spotify.active) spotify.liked else yandexLikedFor()
        when (on) { true -> "♥"; false -> "♡"; null -> "…" }
    }, action = {
        if (spotify.active) spotify.toggleLike()
        else yandexTrack?.let { id ->
            val now = yandexLiked ?: return@let
            yandexLiked = !now
            scope.launch {
                // Back to how it was if Yandex says no
                if (withContext(Dispatchers.IO) { runCatching { yandex.setLiked(id, !now) } }.isFailure && yandexLikedOf == id) yandexLiked = now
            }
        }
    })

    private val addRow = QuickRow(s(R.string.add_to), { "›" }, action = { openPicker() })

    /** Reads the like state of a newly playing Yandex song once. */
    private fun yandexLikedFor(): Boolean? {
        val id = yandexTrack ?: return null
        if (yandexLikedOf != id) {
            yandexLikedOf = id
            yandexLiked = null
            scope.launch {
                val liked = withContext(Dispatchers.IO) { runCatching { yandex.isLiked(id) }.getOrNull() }
                if (yandexLikedOf == id) yandexLiked = liked
            }
        }
        return yandexLiked
    }

    private fun openPicker() {
        val p = PlaylistPicker()
        picker = p
        scope.launch {
            val lists = withContext(Dispatchers.IO) { runCatching { yandex.ownPlaylists() }.getOrNull() }
            if (lists == null) p.failed = true else p.lists = lists
        }
    }

    /** MENU in the picker: adds the song to the ticked playlists and closes everything. */
    private fun confirmPicker(p: PlaylistPicker) {
        val id = yandexTrack
        val chosen = p.marked.mapNotNull { p.lists?.getOrNull(it) }
        if (id != null && chosen.isNotEmpty()) scope.launch(Dispatchers.IO) {
            chosen.forEach { runCatching { yandex.addToPlaylist(id, it) } }
        }
        close()
    }

    /** The settings lines, then Like (Spotify or Yandex playing) and Add to… (Yandex). */
    val rows: List<QuickRow>
        get() = base + listOfNotNull(
            likeRow.takeIf { spotify.active || yandexTrack != null },
            addRow.takeIf { yandexTrack != null },
        )

    private val base: List<QuickRow> = listOf(
        QuickRow(s(R.string.settings_shuffle), { s(if (playback.shuffle) R.string.shuffle_songs else R.string.off) }) { steps ->
            // Two states: any click flips it
            if (steps != 0) playback.toggleShuffle()
        },
        QuickRow(s(R.string.settings_repeat), {
            s(when (playback.repeatMode) {
                Player.REPEAT_MODE_ONE -> R.string.repeat_one
                Player.REPEAT_MODE_ALL -> R.string.repeat_all
                else -> R.string.off
            })
        }) { steps -> repeat(((steps % 3) + 3) % 3) { playback.cycleRepeat() } },
        QuickRow(s(R.string.settings_sleep), { sleepText() }) { steps ->
            // Off, 15…120 min, end of song; a running timer counts as the nearest option above it
            val options = listOf(0L) + SLEEP_MINUTES.map { it.toLong() } + listOf(Prefs.SLEEP_END_OF_SONG)
            val current = when (val until = app.sleepUntil) {
                0L -> 0
                Prefs.SLEEP_END_OF_SONG -> options.lastIndex
                else -> {
                    val left = (until - System.currentTimeMillis()) / 60_000
                    SLEEP_MINUTES.indexOfFirst { it >= left }.let { if (it < 0) SLEEP_MINUTES.size else it + 1 }
                }
            }
            app.sleepUntil = when (val picked = step(options, current, steps)) {
                0L, Prefs.SLEEP_END_OF_SONG -> picked
                else -> System.currentTimeMillis() + picked * 60_000
            }
        },
        QuickRow(s(R.string.crossfade), { crossfadeText(app.crossfadeSeconds) }) { steps ->
            app.crossfadeSeconds = step(CROSSFADE_OPTIONS, CROSSFADE_OPTIONS.indexOf(app.crossfadeSeconds), steps)
        },
        QuickRow(s(R.string.settings_eq), { eqText() }) { steps ->
            val options = app.eqChoices + EqPreset.CUSTOM
            app.eqPreset = step(options, options.indexOf(app.eqPreset), steps)
        },
    )

    fun crossfadeText(sec: Int) = if (sec == 0) s(R.string.off) else res.getString(R.string.seconds, sec)

    fun eqText(): String = EqPreset.label(app.eqPreset, app.eqUser, s(R.string.off), s(R.string.eq_custom))

    fun sleepText(): String = when (val until = app.sleepUntil) {
        0L -> s(R.string.off)
        Prefs.SLEEP_END_OF_SONG -> s(R.string.sleep_end_of_song)
        else -> res.getString(R.string.minutes, ((until - System.currentTimeMillis()) / 60_000 + 1).coerceAtLeast(1))
    }

    fun toggle() {
        open = !open
        editing = false
        touch()
    }

    fun close() {
        open = false
        editing = false
        picker = null
    }

    fun scroll(steps: Int) {
        touch()
        val p = picker
        val r = rows
        when {
            p != null -> p.cursor = (p.cursor + steps).coerceIn(0, ((p.lists?.size ?: 1) - 1).coerceAtLeast(0))
            editing -> r.getOrNull(row)?.change?.invoke(steps)
            else -> row = (row + steps).coerceIn(0, r.lastIndex)
        }
    }

    fun select() {
        touch()
        picker?.let { p ->
            if (p.lists != null) { if (p.cursor in p.marked) p.marked.remove(p.cursor) else p.marked.add(p.cursor) }
            return
        }
        val r = rows.getOrNull(row) ?: return
        r.action?.let { it(); return }
        editing = !editing
    }

    /** MENU: in the picker it adds and closes; otherwise it closes the panel. */
    fun menu() {
        picker?.let { confirmPicker(it); return }
        close()
    }
}

/** The panel sliding up from the bottom of the screen. */
@Composable
fun BoxScope.QuickSettingsPanel(quick: QuickSettings) {
    LaunchedEffect(quick.open) {
        while (quick.open) {
            delay(500)
            if (SystemClock.uptimeMillis() - quick.lastTouch > IDLE_CLOSE_MS) quick.close()
        }
    }
    AnimatedVisibility(
        quick.open,
        Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
    ) {
        val font = LocalLcdFont.current
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.92f)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.25f), Color.Transparent), endY = 12f))
                .padding(top = 3.dp)
                .background(Lcd.background),
        ) {
            BasicText(
                stringResource(if (quick.picker != null) R.string.add_to else R.string.quick_settings),
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Lcd.barTop, Lcd.barBottom))).padding(horizontal = 9.dp, vertical = 3.dp),
                style = TextStyle(fontFamily = font, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Lcd.text),
            )
            val picker = quick.picker
            if (picker != null) {
                PickerView(picker)
                return@Column
            }
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    quick.rows.forEachIndexed { i, r ->
                        val selected = i == quick.row
                        val editing = selected && quick.editing
                        Row(
                            Modifier.fillMaxWidth().weight(1f)
                                .then(
                                    when {
                                        // Editing: a framed row, so it reads as "the wheel changes this now"
                                        editing -> Modifier.background(Lcd.accent).padding(2.dp).background(Lcd.background)
                                        selected && Lcd.material -> Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            .background(Lcd.selectionTop, RoundedCornerShape(50))
                                        selected -> Modifier.background(Brush.verticalGradient(listOf(Lcd.selectionTop, Lcd.selectionBottom)))
                                        else -> Modifier
                                    },
                                )
                                .padding(horizontal = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val color = if (selected && !editing) Lcd.onSelection else Lcd.text
                            BasicText(
                                r.label, Modifier.weight(1f),
                                style = TextStyle(fontFamily = font, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = color),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            BasicText(
                                if (editing) "‹ ${r.value()} ›" else r.value(),
                                style = TextStyle(
                                    fontFamily = font, fontSize = 14.sp,
                                    fontWeight = if (editing) FontWeight.Bold else FontWeight.Normal,
                                    color = when {
                                        editing -> Lcd.accent
                                        selected -> Lcd.onSelection
                                        else -> Lcd.dimText
                                    },
                                ),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The playlists to add the song to: a tick on each chosen one; MENU adds and closes. */
@Composable
private fun PickerView(p: PlaylistPicker) {
    val font = LocalLcdFont.current
    val lists = p.lists
    if (lists == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            BasicText(
                stringResource(if (p.failed) R.string.add_to_failed else R.string.spotify_loading),
                style = TextStyle(fontFamily = font, fontSize = 14.sp, color = Lcd.dimText),
            )
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        // A window of rows around the cursor
        val rows = 6
        val first = (p.cursor - rows + 1).coerceAtLeast(0).coerceAtMost((lists.size - rows).coerceAtLeast(0))
        for (i in first until minOf(first + rows, lists.size)) {
            val selected = i == p.cursor
            Row(
                Modifier.fillMaxWidth().weight(1f)
                    .then(if (selected) Modifier.background(Brush.verticalGradient(listOf(Lcd.selectionTop, Lcd.selectionBottom))) else Modifier)
                    .padding(horizontal = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val color = if (selected) Lcd.onSelection else Lcd.text
                BasicText(
                    if (i in p.marked) "☑" else "☐",
                    Modifier.padding(end = 8.dp),
                    style = TextStyle(fontSize = 16.sp, color = color),
                )
                BasicText(
                    lists[i].title, Modifier.weight(1f),
                    style = TextStyle(fontFamily = font, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = color),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        BasicText(
            stringResource(R.string.add_to_hint),
            Modifier.fillMaxWidth().padding(4.dp),
            style = TextStyle(fontFamily = font, fontSize = 11.sp, color = Lcd.dimText, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
        )
    }
}
