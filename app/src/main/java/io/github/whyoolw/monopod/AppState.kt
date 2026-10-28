package io.github.whyoolw.monopod

import org.json.JSONObject
import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import io.github.whyoolw.monopod.playback.EqPreset
import io.github.whyoolw.monopod.playback.UserEq
import io.github.whyoolw.monopod.ui.theme.BodyTheme
import io.github.whyoolw.monopod.ui.wheel.HapticMode
import io.github.whyoolw.monopod.ui.theme.BodyThemes
import io.github.whyoolw.monopod.ui.theme.AllBodies
import io.github.whyoolw.monopod.ui.theme.CustomBodies

enum class ScreenLayout(@StringRes val label: Int) {
    /** Screen on top, big wheel below, like the real thing. */
    Classic(R.string.layout_classic),
    /** Square screen, smaller wheel. */
    BigScreen(R.string.layout_big),
    /** The screen fills the phone, the wheel floats over its bottom. */
    FullScreen(R.string.layout_full),
}

enum class ScreenEffect(@StringRes val label: Int) {
    Off(R.string.effect_off),
    /** Color LCD with fine scanlines, like the iPod Classic. */
    Lcd(R.string.effect_lcd),
    /** Old tube monitor: curved glass, thick lines, a rolling band, flicker. */
    Crt(R.string.effect_crt),
    /** Grey-blue monochrome LCD with a visible pixel grid, like the first iPods. */
    Mono(R.string.effect_mono),
}

enum class ScreenFont(@StringRes val label: Int) {
    Classic(R.string.font_classic),
    Pixel(R.string.font_pixel),
    Mono(R.string.font_mono),
}

enum class UiStyle(@StringRes val label: Int) {
    /** The iPod: LCD screen in a bezel, labeled click wheel, body themes. */
    IPod(R.string.style_ipod),
    /** Material 3 colors and shapes around the same screen and wheel. */
    Material(R.string.style_material),
}

/** The iPod style's highlight color, picked like the accent color in macOS. */
enum class IpodAccent(@StringRes val label: Int, val top: Long, val bottom: Long) {
    /** The iPod's own blue gradient. */
    /** Follows the playing album's cover; [top]/[bottom] are the fallback without one. */
    Cover(R.string.colors_cover, 0xFF6DB3F2, 0xFF1C6CCF),
    Multicolor(R.string.accent_multicolor, 0xFF6DB3F2, 0xFF1C6CCF),
    Blue(R.string.accent_blue, 0xFF4BA3FF, 0xFF0A63E0),
    Purple(R.string.accent_purple, 0xFFB57BD9, 0xFF7D3BAA),
    Pink(R.string.accent_pink, 0xFFF48FC0, 0xFFD83C88),
    Red(R.string.accent_red, 0xFFF37C78, 0xFFC9302C),
    Orange(R.string.accent_orange, 0xFFFFB25E, 0xFFE07612),
    Yellow(R.string.accent_yellow, 0xFFFFDC5E, 0xFFE0A800),
    Green(R.string.accent_green, 0xFF8EDB6A, 0xFF3E9E22),
    Graphite(R.string.accent_graphite, 0xFFB4B7BC, 0xFF6E7278),
}

enum class MaterialColors(@StringRes val label: Int) {
    /** Colors follow the playing album's cover. */
    Cover(R.string.colors_cover),
    /** The phone's wallpaper colors (Material You). */
    System(R.string.colors_system),
}

enum class DarkScreen(@StringRes val label: Int) {
    Off(R.string.off),
    On(R.string.on),
    /** Follow the phone's dark theme. */
    System(R.string.dark_system),
}

enum class SoundCheck(@StringRes val label: Int) {
    Off(R.string.off),
    /** Every song at the same loudness, from its ReplayGain track gain. */
    Track(R.string.sound_check_track),
    /** Keeps the loudness differences inside an album (album gain). */
    Album(R.string.sound_check_album),
}

/** Settings shared with [io.github.whyoolw.monopod.playback.PlaybackService], which listens to them. */
object Prefs {
    const val FILE = "settings"
    const val THEME = "theme"
    /** Developer Mode: one album for songs whose artist lines differ ("5opka" / "5opka, Grechko"). */
    const val MERGE_ALBUMS = "merge_albums"
    const val CROSSFADE_SECONDS = "crossfade_seconds"
    const val SOUND_CHECK = "sound_check"
    const val EQ_PRESET = "eq_preset"
    const val EQ_CUSTOM = "eq_custom"
    /** Saved presets, JSON (see EqPreset.parseUser). */
    const val EQ_USER = "eq_user"
    /** Built-in presets the user deleted, comma separated. */
    const val EQ_HIDDEN = "eq_hidden"
    /** Wall clock time to pause at; [SLEEP_END_OF_SONG] pauses when the song ends; absent = off. */
    const val SLEEP_UNTIL = "sleep_until"
    const val SLEEP_END_OF_SONG = -1L
}

/** User settings, kept in Compose state and saved to SharedPreferences on every change. */
class AppState(context: Context) {
    /** For things the menus do with the system (saving the theme template). */
    val context: Context = context.applicationContext

    /** Where the app keeps its own files (the last crash report). */
    val filesDir: java.io.File = context.filesDir

    private val prefs = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)

    private inline fun <reified T : Enum<T>> load(key: String, default: T): T =
        prefs.getString(key, null)?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default

    /** Everything on the Appearance page, for presets. */
    fun exportLook(): JSONObject = JSONObject()
        .put("style", uiStyle.name).put("materialColors", materialColors.name).put("accent", ipodAccent.name)
        .put("theme", theme.id).put("layout", layout.name).put("effect", effect.name).put("intensity", intensity)
        .put("dark", darkScreen.name).put("font", font.name).put("hideStatusBar", hideStatusBar)

    /**
     * The settings part of a theme file, applied once when its body is picked (values are
     * already checked by the parser). "colors" means the accent in the iPod style.
     */
    fun applyThemeSettings(settings: Map<String, String>) {
        settings["style"]?.let { uiStyle = if (it == "material") UiStyle.Material else UiStyle.IPod }
        settings["colors"]?.let { c ->
            if (uiStyle == UiStyle.Material) {
                if (c == "cover") materialColors = MaterialColors.Cover
                if (c == "system") materialColors = MaterialColors.System
            } else {
                IpodAccent.entries.firstOrNull { it.name.equals(c, ignoreCase = true) }?.let { ipodAccent = it }
            }
        }
        settings["layout"]?.let {
            layout = when (it) { "big" -> ScreenLayout.BigScreen; "full" -> ScreenLayout.FullScreen; else -> ScreenLayout.Classic }
        }
        settings["effect"]?.let { e -> ScreenEffect.entries.firstOrNull { it.name.equals(e, ignoreCase = true) }?.let { effect = it } }
        settings["effect-strength"]?.removeSuffix("%")?.toIntOrNull()?.let { intensity = (it + 5) / 10 }
        settings["dark-screen"]?.let { d -> DarkScreen.entries.firstOrNull { it.name.equals(d, ignoreCase = true) }?.let { darkScreen = it } }
        settings["font"]?.let { f -> ScreenFont.entries.firstOrNull { it.name.equals(f, ignoreCase = true) }?.let { font = it } }
        settings["hide-status-bar"]?.let { hideStatusBar = it == "true" }
    }

    fun importLook(o: JSONObject) {
        fun <T : Enum<T>> pick(values: Array<T>, key: String, current: T) = values.firstOrNull { it.name == o.optString(key) } ?: current
        uiStyle = pick(UiStyle.entries.toTypedArray(), "style", uiStyle)
        materialColors = pick(MaterialColors.entries.toTypedArray(), "materialColors", materialColors)
        ipodAccent = pick(IpodAccent.entries.toTypedArray(), "accent", ipodAccent)
        theme = AllBodies.firstOrNull { it.id == o.optString("theme") } ?: theme
        layout = pick(ScreenLayout.entries.toTypedArray(), "layout", layout)
        effect = pick(ScreenEffect.entries.toTypedArray(), "effect", effect)
        intensity = o.optInt("intensity", intensity)
        darkScreen = pick(DarkScreen.entries.toTypedArray(), "dark", darkScreen)
        font = pick(ScreenFont.entries.toTypedArray(), "font", font)
        hideStatusBar = o.optBoolean("hideStatusBar", hideStatusBar)
    }

    private var _theme by mutableStateOf(
        AllBodies.firstOrNull { it.id == prefs.getString(KEY_THEME, null) } ?: BodyThemes.first(),
    )
    var theme: BodyTheme
        get() = _theme
        set(v) { _theme = v; prefs.edit { putString(KEY_THEME, v.id) } }

    private var _clickSound by mutableStateOf(prefs.getBoolean(KEY_SOUND, true))
    var clickSound: Boolean
        get() = _clickSound
        set(v) { _clickSound = v; prefs.edit { putBoolean(KEY_SOUND, v) } }

    private var _clickHaptics by mutableStateOf(prefs.getBoolean(KEY_HAPTICS, true))
    var clickHaptics: Boolean
        get() = _clickHaptics
        set(v) { _clickHaptics = v; prefs.edit { putBoolean(KEY_HAPTICS, v) } }

    private var _layout by mutableStateOf(load(KEY_LAYOUT, ScreenLayout.Classic))
    var layout: ScreenLayout
        get() = _layout
        set(v) { _layout = v; prefs.edit { putString(KEY_LAYOUT, v.name) } }

    private var _effect by mutableStateOf(load(KEY_EFFECT, ScreenEffect.Lcd))
    var effect: ScreenEffect
        get() = _effect
        set(v) { _effect = v; prefs.edit { putString(KEY_EFFECT, v.name) } }

    /** Effect strength in tenths: 0..10 normally, up to 100 (1000%) from developer settings. */
    private var _intensity by mutableIntStateOf(prefs.getInt(KEY_INTENSITY, 5))
    var intensity: Int
        get() = _intensity
        set(v) { _intensity = v.coerceIn(0, 100); prefs.edit { putInt(KEY_INTENSITY, _intensity) } }

    private var _font by mutableStateOf(load(KEY_FONT, ScreenFont.Classic))
    var font: ScreenFont
        get() = _font
        set(v) { _font = v; prefs.edit { putString(KEY_FONT, v.name) } }

    private var _mergeAlbums by mutableStateOf(prefs.getBoolean(Prefs.MERGE_ALBUMS, false))
    var mergeAlbums: Boolean
        get() = _mergeAlbums
        set(v) { _mergeAlbums = v; prefs.edit { putBoolean(Prefs.MERGE_ALBUMS, v) } }

    /** Online services shown in the main menu (Developer Mode → Online Music). */
    private var _spotifyEnabled by mutableStateOf(prefs.getBoolean(KEY_SPOTIFY, true))
    var spotifyEnabled: Boolean
        get() = _spotifyEnabled
        set(v) { _spotifyEnabled = v; prefs.edit { putBoolean(KEY_SPOTIFY, v) } }

    private var _yandexEnabled by mutableStateOf(prefs.getBoolean(KEY_YANDEX, true))
    var yandexEnabled: Boolean
        get() = _yandexEnabled
        set(v) { _yandexEnabled = v; prefs.edit { putBoolean(KEY_YANDEX, v) } }

    private var _uiStyle by mutableStateOf(load(KEY_UI_STYLE, UiStyle.IPod))
    var uiStyle: UiStyle
        get() = _uiStyle
        set(v) { _uiStyle = v; prefs.edit { putString(KEY_UI_STYLE, v.name) } }

    private var _materialColors by mutableStateOf(load(KEY_MATERIAL_COLORS, MaterialColors.Cover))
    var materialColors: MaterialColors
        get() = _materialColors
        set(v) { _materialColors = v; prefs.edit { putString(KEY_MATERIAL_COLORS, v.name) } }

    private var _ipodAccent by mutableStateOf(load(KEY_IPOD_ACCENT, IpodAccent.Multicolor))
    var ipodAccent: IpodAccent
        get() = _ipodAccent
        set(v) { _ipodAccent = v; prefs.edit { putString(KEY_IPOD_ACCENT, v.name) } }

    private var _darkScreen by mutableStateOf(load(KEY_DARK_SCREEN, DarkScreen.Off))
    var darkScreen: DarkScreen
        get() = _darkScreen
        set(v) { _darkScreen = v; prefs.edit { putString(KEY_DARK_SCREEN, v.name) } }

    /** Hide the phone's own status bar (clock, notifications); a swipe from the top brings it back. */
    private var _hideStatusBar by mutableStateOf(prefs.getBoolean(KEY_HIDE_STATUS_BAR, false))
    var hideStatusBar: Boolean
        get() = _hideStatusBar
        set(v) { _hideStatusBar = v; prefs.edit { putBoolean(KEY_HIDE_STATUS_BAR, v) } }

    /** Wheel vibration strength, 1..10. */
    private var _hapticStrength by mutableIntStateOf(prefs.getInt(KEY_HAPTIC_STRENGTH, 6))
    var hapticStrength: Int
        get() = _hapticStrength
        set(v) { _hapticStrength = v.coerceIn(1, 10); prefs.edit { putInt(KEY_HAPTIC_STRENGTH, _hapticStrength) } }

    // Developer settings

    private var _hapticMode by mutableStateOf(load(KEY_HAPTIC_MODE, HapticMode.Auto))
    var hapticMode: HapticMode
        get() = _hapticMode
        set(v) { _hapticMode = v; prefs.edit { putString(KEY_HAPTIC_MODE, v.name) } }

    /** Wheel click pulse length, ms. */
    private var _pulseMs by mutableIntStateOf(prefs.getInt(KEY_PULSE_MS, DEFAULT_PULSE_MS))
    var pulseMs: Int
        get() = _pulseMs
        set(v) { _pulseMs = v.coerceIn(2, 60); prefs.edit { putInt(KEY_PULSE_MS, _pulseMs) } }

    /** Vibrate on the media channel instead of touch feedback, which some phones damp or mute. */
    private var _hapticMedia by mutableStateOf(prefs.getBoolean(KEY_HAPTIC_MEDIA, false))
    var hapticMedia: Boolean
        get() = _hapticMedia
        set(v) { _hapticMedia = v; prefs.edit { putBoolean(KEY_HAPTIC_MEDIA, v) } }

    /** Wheel rotation per click, degrees: smaller is more sensitive. */
    private var _wheelDegrees by mutableIntStateOf(prefs.getInt(KEY_WHEEL_DEGREES, DEFAULT_WHEEL_DEGREES))
    var wheelDegrees: Int
        get() = _wheelDegrees
        set(v) { _wheelDegrees = v.coerceIn(6, 40); prefs.edit { putInt(KEY_WHEEL_DEGREES, _wheelDegrees) } }

    /** List speed-up for fast turns: index into [ACCEL_LEVELS]. */
    private var _accelLevel by mutableIntStateOf(prefs.getInt(KEY_ACCEL, DEFAULT_ACCEL))
    var accelLevel: Int
        get() = _accelLevel
        set(v) { _accelLevel = v.coerceIn(0, ACCEL_LEVELS.lastIndex); prefs.edit { putInt(KEY_ACCEL, _accelLevel) } }

    /** Click sound volume in tenths. */
    private var _clickVolume by mutableIntStateOf(prefs.getInt(KEY_CLICK_VOLUME, DEFAULT_CLICK_VOLUME))
    var clickVolume: Int
        get() = _clickVolume
        set(v) { _clickVolume = v.coerceIn(0, 10); prefs.edit { putInt(KEY_CLICK_VOLUME, _clickVolume) } }

    /** CRT rolling band and flicker speed, tenths of normal. */
    private var _crtSpeed by mutableIntStateOf(prefs.getInt(KEY_CRT_SPEED, DEFAULT_CRT_SPEED))
    var crtSpeed: Int
        get() = _crtSpeed
        set(v) { _crtSpeed = v.coerceIn(0, 50); prefs.edit { putInt(KEY_CRT_SPEED, _crtSpeed) } }

    fun resetDeveloperSettings() {
        hapticMode = HapticMode.Auto
        pulseMs = DEFAULT_PULSE_MS
        hapticMedia = false
        wheelDegrees = DEFAULT_WHEEL_DEGREES
        accelLevel = DEFAULT_ACCEL
        clickVolume = DEFAULT_CLICK_VOLUME
        crtSpeed = DEFAULT_CRT_SPEED
        if (intensity > 10) intensity = 10
    }

    private var _crossfade by mutableIntStateOf(prefs.getInt(Prefs.CROSSFADE_SECONDS, 0))
    var crossfadeSeconds: Int
        get() = _crossfade
        set(v) { _crossfade = v; prefs.edit { putInt(Prefs.CROSSFADE_SECONDS, v) } }

    private var _soundCheck by mutableStateOf(load(Prefs.SOUND_CHECK, SoundCheck.Off))
    var soundCheck: SoundCheck
        get() = _soundCheck
        set(v) { _soundCheck = v; prefs.edit { putString(Prefs.SOUND_CHECK, v.name) } }

    private var _eqPreset by mutableStateOf(prefs.getString(Prefs.EQ_PRESET, null) ?: EqPreset.Off.name)
    var eqPreset: String
        get() = _eqPreset
        set(v) { _eqPreset = v; prefs.edit { putString(Prefs.EQ_PRESET, v) } }

    /** Custom EQ, dB per band at [EqPreset.FREQUENCIES]. */
    private var _eqCustom by mutableStateOf(EqPreset.parseCustom(prefs.getString(Prefs.EQ_CUSTOM, null)))
    val eqCustom: List<Int> get() = _eqCustom

    fun setEqBand(band: Int, db: Int) {
        _eqCustom = _eqCustom.toMutableList().also { it[band] = db.coerceIn(-12, 12) }
        prefs.edit {
            putString(Prefs.EQ_CUSTOM, _eqCustom.joinToString(","))
            putString(Prefs.EQ_PRESET, EqPreset.CUSTOM)
        }
        _eqPreset = EqPreset.CUSTOM
    }

    private var _eqUser by mutableStateOf(EqPreset.parseUser(prefs.getString(Prefs.EQ_USER, null)))
    val eqUser: List<UserEq> get() = _eqUser

    private var _eqHidden by mutableStateOf(
        prefs.getString(Prefs.EQ_HIDDEN, null)?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet(),
    )
    val eqHidden: Set<String> get() = _eqHidden

    /** What the EQ list offers: Off, the built-ins not deleted, then saved presets (by key). */
    val eqChoices: List<String>
        get() = EqPreset.entries.filter { it == EqPreset.Off || it.name !in _eqHidden }.map { it.name } +
            _eqUser.map { it.key }

    /** Stores the custom bands as a new preset named [name] and switches to it. */
    fun saveCustomEq(name: String) {
        val preset = UserEq((_eqUser.maxOfOrNull { it.id } ?: 0) + 1, name, _eqCustom)
        _eqUser = _eqUser + preset
        prefs.edit { putString(Prefs.EQ_USER, EqPreset.encodeUser(_eqUser)) }
        eqPreset = preset.key
    }

    /** Removes a saved preset, or hides a built-in one. Off can't go. */
    fun deleteEq(key: String) {
        if (key == EqPreset.Off.name) return
        if (key.startsWith(EqPreset.USER_PREFIX)) {
            _eqUser = _eqUser.filter { it.key != key }
            prefs.edit { putString(Prefs.EQ_USER, EqPreset.encodeUser(_eqUser)) }
        } else {
            _eqHidden = _eqHidden + key
            prefs.edit { putString(Prefs.EQ_HIDDEN, _eqHidden.joinToString(",")) }
        }
        if (eqPreset == key) eqPreset = EqPreset.Off.name
    }

    fun restoreBuiltInEqs() {
        _eqHidden = emptySet()
        prefs.edit { remove(Prefs.EQ_HIDDEN) }
    }

    private var _sleepUntil by mutableLongStateOf(prefs.getLong(Prefs.SLEEP_UNTIL, 0L))
    /** 0 = off, [Prefs.SLEEP_END_OF_SONG], or the wall clock time playback stops. */
    var sleepUntil: Long
        get() = _sleepUntil
        set(v) {
            _sleepUntil = v
            prefs.edit { if (v == 0L) remove(Prefs.SLEEP_UNTIL) else putLong(Prefs.SLEEP_UNTIL, v) }
        }

    // The service clears the sleep timer once it fired; follow it
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        if (key == Prefs.SLEEP_UNTIL) _sleepUntil = p.getLong(Prefs.SLEEP_UNTIL, 0L)
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        /** Speed-up strength per level: off, gentle, normal, strong, wild. */
        val ACCEL_LEVELS = listOf(0f, 0.5f, 1f, 2f, 4f)
        const val DEFAULT_PULSE_MS = 12
        const val DEFAULT_WHEEL_DEGREES = 15
        const val DEFAULT_ACCEL = 2
        const val DEFAULT_CLICK_VOLUME = 6
        const val DEFAULT_CRT_SPEED = 10

        private const val KEY_UI_STYLE = "ui_style"
        private const val KEY_SPOTIFY = "online_spotify"
        private const val KEY_YANDEX = "online_yandex"
        private const val KEY_IPOD_ACCENT = "ipod_accent"
        private const val KEY_MATERIAL_COLORS = "material_colors"
        private const val KEY_HAPTIC_MODE = "haptic_mode"
        private const val KEY_PULSE_MS = "haptic_pulse_ms"
        private const val KEY_HAPTIC_MEDIA = "haptic_media"
        private const val KEY_WHEEL_DEGREES = "wheel_degrees"
        private const val KEY_ACCEL = "accel_level"
        private const val KEY_CLICK_VOLUME = "click_volume"
        private const val KEY_CRT_SPEED = "crt_speed"
        private const val KEY_THEME = Prefs.THEME
        private const val KEY_SOUND = "click_sound"
        private const val KEY_HAPTICS = "click_haptics"
        private const val KEY_LAYOUT = "layout"
        private const val KEY_EFFECT = "effect"
        private const val KEY_INTENSITY = "effect_intensity"
        private const val KEY_FONT = "font"
        private const val KEY_HIDE_STATUS_BAR = "hide_status_bar"
        private const val KEY_DARK_SCREEN = "dark_screen"
        private const val KEY_HAPTIC_STRENGTH = "haptic_strength"
    }
}
