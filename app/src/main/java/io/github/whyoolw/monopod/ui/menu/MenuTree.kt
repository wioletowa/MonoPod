package io.github.whyoolw.monopod.ui.menu

import android.content.res.Resources
import androidx.media3.common.Player
import io.github.whyoolw.monopod.AppState
import io.github.whyoolw.monopod.Prefs
import io.github.whyoolw.monopod.Monopod
import io.github.whyoolw.monopod.DarkScreen
import io.github.whyoolw.monopod.MaterialColors
import io.github.whyoolw.monopod.IpodAccent
import io.github.whyoolw.monopod.R
import io.github.whyoolw.monopod.UiStyle
import io.github.whyoolw.monopod.ui.wheel.HapticMode
import io.github.whyoolw.monopod.ui.wheel.WheelFeedback
import io.github.whyoolw.monopod.SoundCheck
import io.github.whyoolw.monopod.playback.EqPreset
import io.github.whyoolw.monopod.ScreenEffect
import io.github.whyoolw.monopod.ScreenFont
import io.github.whyoolw.monopod.ScreenLayout
import io.github.whyoolw.monopod.data.LibraryRepository
import io.github.whyoolw.monopod.data.StatsRepository
import io.github.whyoolw.monopod.data.Track
import io.github.whyoolw.monopod.playback.Playback
import io.github.whyoolw.monopod.ui.coverflow.CoverFlowPage
import io.github.whyoolw.monopod.ui.quick.CROSSFADE_OPTIONS
import io.github.whyoolw.monopod.ui.quick.SLEEP_MINUTES
import io.github.whyoolw.monopod.ui.nowplaying.NowPlayingPage
import io.github.whyoolw.monopod.ui.nowplaying.SpotifyNowPlayingPage
import io.github.whyoolw.monopod.spotify.Spotify
import io.github.whyoolw.monopod.yandex.YandexMusic
import io.github.whyoolw.monopod.ui.theme.BodyThemes
import io.github.whyoolw.monopod.ui.theme.CustomBodies
import io.github.whyoolw.monopod.ui.theme.CustomBody
import io.github.whyoolw.monopod.ui.theme.label
import io.github.whyoolw.monopod.ui.presets.PresetStore
import io.github.whyoolw.monopod.ui.presets.PresetsPage

/** The whole menu tree. [pickFolder] opens the system folder picker. */
fun rootPage(
    res: Resources,
    app: AppState,
    library: LibraryRepository,
    stats: StatsRepository,
    playback: Playback,
    feedback: WheelFeedback,
    spotify: Spotify,
    yandex: YandexMusic,
    presets: PresetStore,
    /** Opens the sticker editor over the whole app. */
    openStickers: () -> Unit,
    /** Opens the file picker for a theme file; with a body, the file updates that body. */
    pickThemeFile: (replace: CustomBody?) -> Unit,
    pickFolder: () -> Unit,
): Page {
    fun s(id: Int) = res.getString(id)
    fun soon(title: Int, stage: Int) = MessagePage(s(title), res.getString(R.string.coming_in_stage, stage))
    fun item(title: Int, stage: Int) = MenuItem(s(title)) { soon(title, stage) }
    fun onOff(on: Boolean) = s(if (on) R.string.on else R.string.off)

    fun nowPlaying() = NowPlayingPage(s(R.string.menu_now_playing))
    fun spotifyPlaying() = SpotifyNowPlayingPage(s(R.string.menu_now_playing))
    val spotifyMenus = SpotifyMenus(res, spotify, ::spotifyPlaying) { playback.pause() }
    val yandexMenus = YandexMenus(res, yandex, playback, ::nowPlaying) { spotify.deactivate() }

    // Picking a song plays the list it was picked from, starting at that song
    fun play(queue: List<Track>, index: Int): Page {
        spotify.deactivate()
        playback.play(queue, index, library.snapshot)
        return nowPlaying()
    }

    val coverFlow = MenuItem(s(R.string.menu_cover_flow)) {
        CoverFlowPage(s(R.string.menu_cover_flow), library.snapshot.albums) { queue, index ->
            spotify.deactivate()
            playback.play(queue, index, library.snapshot)
        }
    }
    val music = LibraryMenus(res, library, stats, ::play).music(coverFlow)

    // Custom bodies (theme files) first, then the built-in ones, then the template and "+ Add".
    // Picking a custom body also applies the settings in its file, once.
    val body = ListPage(s(R.string.settings_body)) {
        CustomBodies.bodies.toList().map { custom ->
            MenuItem(
                custom.body.label(res), opensPage = false, checked = { app.theme.id == custom.body.id },
                onHold = {
                    ListPage(custom.body.label(res), listOf(
                        MenuItem(s(R.string.theme_update), opensPage = false, backAfter = true) { pickThemeFile(custom); null },
                        MenuItem(s(R.string.theme_delete), opensPage = false, backAfter = true) {
                            if (app.theme.id == custom.body.id) app.theme = BodyThemes.first()
                            CustomBodies.delete(custom); null
                        },
                    ))
                },
            ) {
                app.theme = custom.body
                app.applyThemeSettings(custom.settings); null
            }
        } + BodyThemes.map { theme ->
            MenuItem(theme.label(res), opensPage = false, checked = { app.theme.id == theme.id }) {
                app.theme = theme; null
            }
        } + listOf(
            MenuItem(s(R.string.theme_download), opensPage = true) {
                val name = runCatching { CustomBodies.downloadTemplate(app.context) }.getOrNull()
                MessagePage(
                    s(R.string.theme_download),
                    if (name != null) res.getString(R.string.theme_downloaded, name) else s(R.string.theme_download_failed),
                )
            },
            MenuItem(s(R.string.theme_add), opensPage = false) { pickThemeFile(null); null },
        )
    }
    // A list of options with a check mark on the current one
    fun <T> choice(title: Int, options: List<T>, label: (T) -> Int, get: () -> T, set: (T) -> Unit) = ListPage(
        s(title),
        options.map { o -> MenuItem(s(label(o)), opensPage = false, checked = { get() == o }) { set(o); null } },
    )
    val layout = choice(R.string.settings_layout, ScreenLayout.entries, { it.label }, { app.layout }) { app.layout = it }
    val effect = choice(R.string.settings_screen_effect, ScreenEffect.entries, { it.label }, { app.effect }) { app.effect = it }
    val uiStyle = choice(R.string.style, UiStyle.entries, { it.label }, { app.uiStyle }) { app.uiStyle = it }
    val ipodAccent = choice(R.string.material_colors, IpodAccent.entries, { it.label }, { app.ipodAccent }) { app.ipodAccent = it }
    val materialColors = choice(R.string.material_colors, MaterialColors.entries, { it.label }, { app.materialColors }) { app.materialColors = it }
    val darkScreen = choice(R.string.dark_screen, DarkScreen.entries, { it.label }, { app.darkScreen }) { app.darkScreen = it }
    val font = choice(R.string.settings_font, ScreenFont.entries, { it.label }, { app.font }) { app.font = it }
    val intensity = SliderPage(s(R.string.settings_effect_intensity), 10, { app.intensity }, { app.intensity = it })
    // One "Colors" entry: iPod accents (or the cover's) in the iPod style, cover / wallpaper in Material.
    // Body and font only exist in the iPod style: Material takes both from its theme.
    val appearance = ListPage(s(R.string.settings_appearance)) {
        val material = app.uiStyle == UiStyle.Material
        listOfNotNull(
            MenuItem(s(R.string.presets)) { PresetsPage(s(R.string.presets), presets) },
            MenuItem(s(R.string.style), value = { s(app.uiStyle.label) }) { uiStyle },
            if (material) MenuItem(s(R.string.material_colors), value = { s(app.materialColors.label) }) { materialColors }
            else MenuItem(s(R.string.material_colors), value = { s(app.ipodAccent.label) }) { ipodAccent },
            MenuItem(s(R.string.settings_body), value = { app.theme.label(res) }) { body }.takeIf { !material },
            MenuItem(s(R.string.settings_layout), value = { s(app.layout.label) }) { layout },
            MenuItem(s(R.string.settings_screen_effect), value = { s(app.effect.label) }) { effect },
            MenuItem(s(R.string.settings_effect_intensity), value = { "${app.intensity * 10}%" }) { intensity },
            MenuItem(s(R.string.dark_screen), value = { s(app.darkScreen.label) }) { darkScreen },
            MenuItem(s(R.string.stickers), opensPage = false) { openStickers(); null },
            MenuItem(s(R.string.settings_font), value = { s(app.font.label) }) { font }.takeIf { !material },
            MenuItem(s(R.string.hide_status_bar), opensPage = false, value = { onOff(app.hideStatusBar) }) {
                app.hideStatusBar = !app.hideStatusBar; null
            },
        )
    }
    val clicker = ListPage(
        s(R.string.settings_clicker),
        listOf(
            MenuItem(s(R.string.clicker_sound), opensPage = false, value = { onOff(app.clickSound) }) {
                app.clickSound = !app.clickSound; null
            },
            MenuItem(s(R.string.clicker_haptics), opensPage = false, value = { onOff(app.clickHaptics) }) {
                app.clickHaptics = !app.clickHaptics; null
            },
            MenuItem(s(R.string.haptic_strength), value = { "${app.hapticStrength * 10}%" }) {
                SliderPage(s(R.string.haptic_strength), 10, { app.hapticStrength }, { app.hapticStrength = it })
            },
        ),
    )
    // Rebuilt on every read so added/removed folders and scan progress show up live
    val folders = ListPage(s(R.string.settings_library)) {
        library.folders.map { folder ->
            MenuItem(folder.name) {
                ListPage(
                    folder.name,
                    listOf(
                        MenuItem(s(R.string.folder_remove), opensPage = false, backAfter = true) {
                            library.removeFolder(folder); null
                        },
                    ),
                )
            }
        } + listOf(
            MenuItem(s(R.string.folder_add), opensPage = false) { pickFolder(); null },
            MenuItem(
                s(R.string.folder_rescan), opensPage = false,
                value = {
                    val p = library.scanProgress
                    when {
                        p == null -> res.getQuantityString(R.plurals.song_count, library.snapshot.songs.size, library.snapshot.songs.size)
                        p.total == 0 -> s(R.string.scanning)
                        else -> "${p.done}/${p.total}"
                    }
                },
            ) { library.rescan(); null },
        )
    }
    fun eqLabel(key: String) = EqPreset.label(key, app.eqUser, s(R.string.off), s(R.string.eq_custom))
    fun eqTitle() = eqLabel(app.eqPreset)
    fun signedDb(db: Int) = res.getString(R.string.db_value, if (db > 0) "+$db" else "$db")
    val eqBands = ListPage(s(R.string.eq_custom), EqPreset.FREQUENCIES.mapIndexed { band, hz ->
        val name = if (hz >= 1000) res.getString(R.string.khz, hz / 1000f) else res.getString(R.string.hz, hz)
        // Slider runs 0..24 for -12..+12 dB
        MenuItem(name, value = { signedDb(app.eqCustom[band]) }) {
            SliderPage(name, 24, { app.eqCustom[band] + 12 }, { app.setEqBand(band, it - 12) }) { signedDb(it - 12) }
        }
    } + MenuItem(s(R.string.eq_save), opensPage = false, backAfter = true) {
        // No keyboard on an iPod: saved presets are numbered
        app.saveCustomEq(res.getString(R.string.eq_user_name, (app.eqUser.maxOfOrNull { it.id } ?: 0) + 1)); null
    })
    // Delete presets one by one; built-ins can come back
    val eqManage = ListPage(s(R.string.eq_presets)) {
        app.eqChoices.filter { it != EqPreset.Off.name }.map { key ->
            MenuItem(eqLabel(key)) {
                ListPage(eqLabel(key), listOf(
                    MenuItem(s(R.string.eq_delete), opensPage = false, backAfter = true) { app.deleteEq(key); null },
                ))
            }
        } + listOfNotNull(
            if (app.eqHidden.isEmpty()) null
            else MenuItem(s(R.string.eq_restore), opensPage = false) { app.restoreBuiltInEqs(); null },
        )
    }
    val eq = ListPage(s(R.string.settings_eq)) {
        app.eqChoices.map { key ->
            MenuItem(eqLabel(key), opensPage = false, checked = { app.eqPreset == key }) { app.eqPreset = key; null }
        } + listOf(
            MenuItem(s(R.string.eq_custom), checked = { app.eqPreset == EqPreset.CUSTOM }) {
                app.eqPreset = EqPreset.CUSTOM; eqBands
            },
            MenuItem(s(R.string.eq_presets)) { eqManage },
        )
    }
    val crossfade = ListPage(s(R.string.crossfade), CROSSFADE_OPTIONS.map { sec ->
        MenuItem(
            if (sec == 0) s(R.string.off) else res.getString(R.string.seconds, sec), opensPage = false,
            checked = { app.crossfadeSeconds == sec },
        ) { app.crossfadeSeconds = sec; null }
    })
    val soundCheck = choice(R.string.sound_check, SoundCheck.entries, { it.label }, { app.soundCheck }) { app.soundCheck = it }
    val sound = ListPage(
        s(R.string.settings_sound),
        listOf(
            MenuItem(s(R.string.crossfade), value = {
                if (app.crossfadeSeconds == 0) s(R.string.off) else res.getString(R.string.seconds, app.crossfadeSeconds)
            }) { crossfade },
            MenuItem(s(R.string.sound_check), value = { s(app.soundCheck.label) }) { soundCheck },
        ),
    )
    fun sleepValue(): String = when (val until = app.sleepUntil) {
        0L -> s(R.string.off)
        Prefs.SLEEP_END_OF_SONG -> s(R.string.sleep_end_of_song)
        else -> res.getString(R.string.minutes, ((until - System.currentTimeMillis()) / 60_000 + 1).coerceAtLeast(1))
    }
    val sleep = ListPage(
        s(R.string.settings_sleep),
        listOf(MenuItem(s(R.string.off), opensPage = false, checked = { app.sleepUntil == 0L }) { app.sleepUntil = 0L; null }) +
            SLEEP_MINUTES.map { min ->
                MenuItem(res.getString(R.string.minutes, min), opensPage = false, backAfter = true) {
                    app.sleepUntil = System.currentTimeMillis() + min * 60_000L; null
                }
            } +
            MenuItem(s(R.string.sleep_end_of_song), opensPage = false, backAfter = true,
                checked = { app.sleepUntil == Prefs.SLEEP_END_OF_SONG }) { app.sleepUntil = Prefs.SLEEP_END_OF_SONG; null },
    )
    // Developer mode: the knobs behind the normal settings, with wider ranges
    fun percent(tenths: Int) = "${tenths * 10}%"
    fun yesNo(v: Boolean) = s(if (v) R.string.yes else R.string.no)
    val hapticModeLabels = mapOf(
        HapticMode.Auto to R.string.haptic_auto, HapticMode.Primitive to R.string.haptic_primitive, HapticMode.Pulse to R.string.haptic_pulse,
    )
    val accelLabels = listOf(R.string.accel_off, R.string.accel_gentle, R.string.accel_normal, R.string.accel_strong, R.string.accel_wild)
    val hapticModePage = choice(R.string.dev_haptic_mode, HapticMode.entries, { hapticModeLabels.getValue(it) }, { app.hapticMode }) { app.hapticMode = it }
    val accelPage = choice(R.string.dev_accel, AppState.ACCEL_LEVELS.indices.toList(), { accelLabels[it] }, { app.accelLevel }) { app.accelLevel = it }
    fun slider(title: Int, max: Int, get: () -> Int, set: (Int) -> Unit, format: (Int) -> String) =
        MenuItem(s(title), value = { format(get()) }) { SliderPage(s(title), max, get, set, format) }
    // Online services: unticking one signs out and wipes its cache, as if it had never been on
    val online = ListPage(s(R.string.online_music), listOf(
        MenuItem(s(R.string.spotify), opensPage = false, icon = R.drawable.ic_spotify, checked = { app.spotifyEnabled }) {
            if (app.spotifyEnabled) spotify.forgetEverything()
            app.spotifyEnabled = !app.spotifyEnabled; null
        },
        MenuItem(s(R.string.yandex_music), opensPage = false, icon = R.drawable.ic_yandex_music, checked = { app.yandexEnabled }) {
            if (app.yandexEnabled) {
                playback.clearIf { it.startsWith("yandex://") }
                yandex.signOut()
            }
            app.yandexEnabled = !app.yandexEnabled; null
        },
    ))
    val developer = ListPage(s(R.string.dev_mode)) {
        listOf(
            MenuItem(s(R.string.online_music)) { online },
            MenuItem(s(R.string.dev_last_crash)) {
                val file = java.io.File(app.filesDir, Monopod.CRASH_FILE)
                val text = file.takeIf { it.exists() }?.readText()?.lines()?.take(14)?.joinToString("\n") ?: s(R.string.dev_no_crash)
                MessagePage(s(R.string.dev_last_crash), text)
            },
            MenuItem(s(R.string.dev_merge_albums), opensPage = false, value = { onOff(app.mergeAlbums) }) {
                app.mergeAlbums = !app.mergeAlbums
                library.rebuild(); null
            },
            slider(R.string.settings_effect_intensity, 100, { app.intensity }, { app.intensity = it }, ::percent),
            slider(R.string.dev_crt_speed, 50, { app.crtSpeed }, { app.crtSpeed = it }, ::percent),
            slider(R.string.haptic_strength, 10, { app.hapticStrength }, { app.hapticStrength = it }, ::percent),
            MenuItem(s(R.string.dev_haptic_mode), value = { s(hapticModeLabels.getValue(app.hapticMode)) }) { hapticModePage },
            slider(R.string.dev_pulse, 60, { app.pulseMs }, { app.pulseMs = it }) { res.getString(R.string.ms, it) },
            MenuItem(s(R.string.dev_haptic_channel), opensPage = false, value = {
                s(if (app.hapticMedia) R.string.channel_media else R.string.channel_touch)
            }) { app.hapticMedia = !app.hapticMedia; null },
            // What the phone's motor can do, to see why strength might not change
            MenuItem(s(R.string.dev_motor_amplitude), opensPage = false, value = { yesNo(feedback.supportsAmplitude) }) { null },
            MenuItem(s(R.string.dev_motor_primitives), opensPage = false, value = { yesNo(feedback.supportsPrimitives) }) { null },
            MenuItem(s(R.string.dev_motor_used), opensPage = false, value = { s(hapticModeLabels.getValue(feedback.effectiveMode)) }) { null },
            slider(R.string.dev_wheel_degrees, 40, { app.wheelDegrees }, { app.wheelDegrees = it }) { "$it°" },
            MenuItem(s(R.string.dev_accel), value = { s(accelLabels[app.accelLevel]) }) { accelPage },
            slider(R.string.dev_click_volume, 10, { app.clickVolume }, { app.clickVolume = it }, ::percent),
            MenuItem(s(R.string.dev_reset), opensPage = false) { app.resetDeveloperSettings(); null },
        )
    }
    val settings = ListPage(
        s(R.string.menu_settings),
        listOf(
            MenuItem(s(R.string.settings_library)) { folders },
            MenuItem(s(R.string.settings_appearance)) { appearance },
            MenuItem(s(R.string.settings_clicker)) { clicker },
            MenuItem(
                s(R.string.settings_shuffle), opensPage = false,
                value = { s(if (playback.shuffle) R.string.shuffle_songs else R.string.off) },
            ) { playback.toggleShuffle(); null },
            MenuItem(
                s(R.string.settings_repeat), opensPage = false,
                value = {
                    s(
                        when (playback.repeatMode) {
                            Player.REPEAT_MODE_ONE -> R.string.repeat_one
                            Player.REPEAT_MODE_ALL -> R.string.repeat_all
                            else -> R.string.off
                        },
                    )
                },
            ) { playback.cycleRepeat(); null },
            MenuItem(s(R.string.settings_eq), value = { eqTitle() }) { eq },
            MenuItem(s(R.string.settings_sound)) { sound },
            MenuItem(s(R.string.settings_sleep), value = { sleepValue() }) { sleep },
            MenuItem(s(R.string.settings_about)) { MessagePage(s(R.string.settings_about), s(R.string.about_text)) },
            MenuItem(s(R.string.dev_mode)) { developer },
        ),
    )

    fun main() = listOfNotNull(
        MenuItem(s(R.string.menu_music)) { music },
        MenuItem(s(R.string.menu_shuffle_songs), opensPage = false) {
            val songs = library.snapshot.songs
            if (songs.isEmpty()) {
                MessagePage(s(R.string.menu_shuffle_songs), s(R.string.library_no_folders))
            } else {
                spotify.deactivate()
                playback.play(songs, songs.indices.random(), library.snapshot, shuffle = true)
                nowPlaying()
            }
        },
        MenuItem(s(R.string.spotify), icon = R.drawable.ic_spotify) { spotifyMenus.root() }.takeIf { app.spotifyEnabled },
        MenuItem(s(R.string.yandex_music), icon = R.drawable.ic_yandex_music) { yandexMenus.root() }.takeIf { app.yandexEnabled },
        MenuItem(s(R.string.menu_settings)) { settings },
    )
    // "Now Playing" shows up at the bottom once there is something to play, from either source
    val nowPlayingItem = MenuItem(s(R.string.menu_now_playing)) { if (spotify.active) spotifyPlaying() else nowPlaying() }
    return ListPage(s(R.string.app_name)) { if (playback.hasQueue || spotify.active) main() + nowPlayingItem else main() }
}
