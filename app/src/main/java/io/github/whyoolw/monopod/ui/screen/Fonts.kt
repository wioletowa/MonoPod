package io.github.whyoolw.monopod.ui.screen

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import io.github.whyoolw.monopod.R
import io.github.whyoolw.monopod.ScreenFont

/** Tiny5 (OFL) is a variable pixel font with full Cyrillic; one file, several weights. */
private val Tiny5 = FontFamily(
    listOf(FontWeight.Normal, FontWeight.SemiBold, FontWeight.Bold).map { w ->
        Font(R.font.tiny5, w, variationSettings = FontVariation.Settings(FontVariation.weight(w.weight)))
    },
)

fun ScreenFont.family(): FontFamily = when (this) {
    ScreenFont.Classic -> FontFamily.Default
    ScreenFont.Pixel -> Tiny5
    ScreenFont.Mono -> FontFamily.Monospace
}

/** Whether this is the pixel font, so icons next to it can be drawn in pixels too. */
val FontFamily.isPixel: Boolean get() = this == Tiny5
