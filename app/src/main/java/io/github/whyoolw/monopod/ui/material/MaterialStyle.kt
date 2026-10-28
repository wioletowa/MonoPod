package io.github.whyoolw.monopod.ui.material

import androidx.compose.ui.graphics.toArgb
import io.github.whyoolw.monopod.ui.menu.CoverCache
import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import com.materialkolor.dynamicColorScheme
import io.github.whyoolw.monopod.MaterialColors
import io.github.whyoolw.monopod.R
import io.github.whyoolw.monopod.ui.screen.LcdColors
import io.github.whyoolw.monopod.ui.theme.BodyTheme
import io.github.whyoolw.monopod.ui.theme.Finish
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Material color scheme: built from the playing album's cover, or the phone's wallpaper
 * colors. A song without a cover falls back to the wallpaper.
 */
@Composable
fun rememberMaterialScheme(source: MaterialColors, dark: Boolean, coverPath: String?): ColorScheme {
    val context = LocalContext.current
    val seed by produceState<Color?>(null, source, coverPath) {
        value = if (source == MaterialColors.Cover && coverPath != null) seedFromCover(coverPath) else null
    }
    val s = seed
    return if (s != null) {
        dynamicColorScheme(seedColor = s, isDark = dark, isAmoled = false)
    } else if (dark) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }
}

/** The cover's main color, or null without a cover. */
@Composable
fun rememberCoverSeed(coverPath: String?): Color? {
    val seed by produceState<Color?>(null, coverPath) { value = coverPath?.let { seedFromCover(it) } }
    return seed
}

/**
 * An iPod highlight (light top, deep bottom of the gradient) from a cover color, kept dark
 * enough for the white text on it.
 */
fun accentFromSeed(seed: Color): Pair<Color, Color> {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(seed.toArgb(), hsv)
    val bottom = Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], hsv[1].coerceIn(0.45f, 0.9f), hsv[2].coerceIn(0.45f, 0.72f))))
    val top = Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], (hsv[1] * 0.6f).coerceIn(0.25f, 0.6f), 0.95f)))
    return top to bottom
}

/** The cover's most characteristic color: vibrant if it has one, otherwise the dominant one. */
private suspend fun seedFromCover(path: String): Color? = withContext(Dispatchers.IO) {
    val file = CoverCache.localFile(path) ?: return@withContext null
    val bitmap = BitmapFactory.decodeFile(file, BitmapFactory.Options().apply { inSampleSize = 8 }) ?: return@withContext null
    val palette = Palette.from(bitmap).maximumColorCount(16).generate()
    (palette.vibrantSwatch ?: palette.dominantSwatch)?.rgb?.let { Color(it) }
}

/** Screen colors from the scheme; animated, so a new album's colors fade in. */
@Composable
fun animatedLcd(scheme: ColorScheme): LcdColors {
    @Composable
    fun anim(c: Color): Color = animateColorAsState(c, tween(600), label = "lcd").value
    val background = anim(scheme.surfaceContainer)
    val selection = anim(scheme.secondaryContainer)
    val surfaceVariant = anim(scheme.surfaceVariant)
    val primary = anim(scheme.primary)
    return LcdColors(
        background = background,
        text = anim(scheme.onSurface),
        dimText = anim(scheme.onSurfaceVariant),
        selectionTop = selection,
        selectionBottom = selection,
        barTop = background,
        barBottom = background,
        barLine = background,
        bezel = background,
        trackTop = surfaceVariant,
        trackBottom = surfaceVariant,
        placeholder = surfaceVariant,
        placeholderText = anim(scheme.onSurfaceVariant),
        scrollTrack = background,
        glyph = anim(scheme.onSurfaceVariant),
        onSelection = anim(scheme.onSecondaryContainer),
        accent = primary,
        progressTop = primary,
        progressBottom = primary,
        material = true,
    )
}

/** The body and wheel in the scheme's surfaces, fading along with the screen. */
@Composable
fun animatedBody(scheme: ColorScheme, dark: Boolean): BodyTheme {
    @Composable
    fun anim(c: Color): Color = animateColorAsState(c, tween(600), label = "body").value
    val primary = anim(scheme.primary)
    val wheel = anim(scheme.surfaceContainerHigh)
    return BodyTheme(
        id = "material", name = R.string.style_material,
        bodyTop = anim(scheme.surface), bodyBottom = anim(scheme.surfaceContainerLow),
        wheelTop = wheel, wheelBottom = wheel, wheelEdge = wheel,
        label = anim(scheme.onSurfaceVariant),
        centerTop = primary, centerBottom = primary, centerEdge = primary,
        lightBody = !dark, finish = Finish.Matte,
        bezel = anim(scheme.surfaceContainer),
    )
}
