package io.github.whyoolw.monopod.ui.screen

import androidx.compose.ui.draw.drawBehind
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.whyoolw.monopod.ScreenEffect

/** Font of everything drawn on the screen, picked in Settings → Appearance → Font. */
val LocalLcdFont = staticCompositionLocalOf<FontFamily> { FontFamily.Default }

/** Set on the pixel screens (iPod 1G, green LCD): bars are drawn in blocks instead of smooth shapes. */
val LocalPixelScreen = staticCompositionLocalOf { false }

/** Colors of everything drawn on the screen; light like the iPod, or dark. */
class LcdColors(
    val background: Color,
    val text: Color,
    val dimText: Color,
    val selectionTop: Color,
    val selectionBottom: Color,
    val barTop: Color,
    val barBottom: Color,
    val barLine: Color,
    val bezel: Color,
    /** Empty part of progress, volume and slider bars (top to bottom). */
    val trackTop: Color,
    val trackBottom: Color,
    /** Square shown instead of a missing album cover, and its ♪. */
    val placeholder: Color,
    val placeholderText: Color,
    val scrollTrack: Color,
    /** Status bar icons: shuffle, repeat, battery outline. */
    val glyph: Color,
    /** Text on the selection highlight. */
    val onSelection: Color = Color.White,
    /** Highlighted text: the sung lyric line, rating stars, the value being edited. */
    val accent: Color = selectionBottom,
    /** Fill of progress, volume and slider bars (top to bottom). */
    val progressTop: Color = selectionTop,
    val progressBottom: Color = selectionBottom,
    /** Material style: pill-shaped selection, flat bars, roomier shapes. */
    val material: Boolean = false,
) {
    val statusHeight = 22.dp
}

val LightLcd = LcdColors(
    background = Color(0xFFFFFFFF), text = Color(0xFF111114), dimText = Color(0xFF8A8B90),
    selectionTop = Color(0xFF6DB3F2), selectionBottom = Color(0xFF1C6CCF),
    barTop = Color(0xFFFDFDFE), barBottom = Color(0xFFC4C8CE), barLine = Color(0xFF8D9197),
    bezel = Color(0xFF1B1B1E),
    trackTop = Color(0xFFBFC3C9), trackBottom = Color(0xFFF4F5F7),
    placeholder = Color(0xFFD5D8DD), placeholderText = Color(0xFF8A8D93),
    scrollTrack = Color(0xFFF1F2F4), glyph = Color(0xFF3F444B),
)

val DarkLcd = LcdColors(
    background = Color(0xFF141517), text = Color(0xFFECEDEF), dimText = Color(0xFF8E9399),
    selectionTop = Color(0xFF4A9BEA), selectionBottom = Color(0xFF1559B5),
    barTop = Color(0xFF3B3E44), barBottom = Color(0xFF1C1E22), barLine = Color(0xFF000000),
    bezel = Color(0xFF050506),
    trackTop = Color(0xFF26282C), trackBottom = Color(0xFF3A3D42),
    placeholder = Color(0xFF2C2F34), placeholderText = Color(0xFF7A7F86),
    scrollTrack = Color(0xFF22252A), glyph = Color(0xFFC9CDD2),
)

/** The same palette with another highlight color (selection, progress bars, accents). */
fun LcdColors.withAccent(top: Color, bottom: Color) = LcdColors(
    background, text, dimText, top, bottom, barTop, barBottom, barLine, bezel, trackTop, trackBottom,
    placeholder, placeholderText, scrollTrack, glyph, onSelection,
    accent = bottom, progressTop = top, progressBottom = bottom, material = material,
)

/**
 * For the pixel screens: the highlight is the text color with the screen color on it (an
 * inverted row, like the first iPod), whatever accent or Material color was chosen.
 */
fun LcdColors.inverted() = LcdColors(
    background, text, dimText, selectionTop = text, selectionBottom = text, barTop, barBottom, barLine, bezel,
    trackTop, trackBottom, placeholder, placeholderText, scrollTrack, glyph,
    onSelection = background, accent = text, progressTop = text, progressBottom = text, material = material,
)

val LocalLcd = staticCompositionLocalOf { LightLcd }

/** The current screen palette, readable from any composable as `Lcd.text` and so on. */
val Lcd: LcdColors
    @Composable @ReadOnlyComposable get() = LocalLcd.current

/**
 * The little display: dark bezel, glossy status bar with the page title, and content below.
 * It has no touch handling on purpose, everything is driven by the click wheel.
 */
@Composable
fun LcdScreen(
    title: String,
    /** Status bar icon: ▶ playing, ❚❚ paused, nothing when the queue is empty. */
    playing: Boolean?,
    effect: ScreenEffect,
    effectIntensity: Int,
    font: FontFamily,
    modifier: Modifier = Modifier,
    dark: Boolean = false,
    /** Overrides the iPod palette, e.g. with Material colors. */
    colors: LcdColors? = null,
    crtSpeed: Float = 1f,
    /** Glass around the screen and an optional ring around that, from the body theme. */
    bezel: Color = Color(0xFF1B1B1E),
    frame: Color? = null,
    /** Status bar icons, shown only while the mode is on. */
    shuffle: Boolean = false,
    /** A media3 Player.REPEAT_MODE_* value. */
    repeatMode: Int = 0,
    /** False once the backlight timer ran out: the screen dims until the wheel is touched. */
    lit: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val pixel = effect == ScreenEffect.Mono || effect == ScreenEffect.GameBoy
    val palette = (colors ?: if (dark) DarkLcd else LightLcd).let { if (pixel) it.inverted() else it }
    // Material: a softly rounded card; iPod: black glass with a thin edge
    val outer = if (palette.material) 18.dp else 10.dp
    val inner = if (palette.material) 14.dp else 5.dp
    val dark by animateFloatAsState(if (lit) 0f else 0.85f, tween(if (lit) 120 else 700), label = "backlight")
    CompositionLocalProvider(LocalLcdFont provides font, LocalLcd provides palette, LocalPixelScreen provides pixel) {
        Box(
            modifier
                // The colored rim goes around the screen, so the screen keeps the same size on every body
                .then(
                    if (frame != null) Modifier.drawBehind {
                        val rim = 3.dp.toPx()
                        drawRoundRect(
                            frame, topLeft = Offset(-rim, -rim), size = Size(size.width + rim * 2, size.height + rim * 2),
                            cornerRadius = CornerRadius(outer.toPx() + rim),
                        )
                    } else Modifier,
                )
                .clip(RoundedCornerShape(outer))
                .background(if (palette.material) palette.background else bezel)
                .padding(if (palette.material) 4.dp else 5.dp)
                .clip(RoundedCornerShape(inner))
                // Over the effect, so the dark stays dark in every screen mode
                .drawWithContent {
                    drawContent()
                    if (dark > 0f) drawRect(Color.Black.copy(alpha = dark))
                }
                .screenEffect(effect, effectIntensity, crtSpeed, darkContent = palette.background.luminance() < 0.5f)
                .background(Lcd.background),
        ) {
            Column(Modifier.fillMaxSize()) {
                StatusBar(title, playing, shuffle, repeatMode, rememberBattery())
                Box(Modifier.fillMaxWidth().weight(1f), content = content)
            }
            // Soft glass reflection on the top-left (an LCD has glass; a Material card does not)
            if (!palette.material) Canvas(Modifier.fillMaxSize()) {
                drawRect(
                    Brush.linearGradient(
                        0f to Color.White.copy(alpha = 0.10f),
                        0.45f to Color.Transparent,
                        start = Offset.Zero,
                        end = Offset(size.width * 0.7f, size.height),
                    ),
                )
            }
        }
    }
}

@Composable
private fun StatusBar(title: String, playing: Boolean?, shuffle: Boolean, repeatMode: Int, battery: BatteryState) {
    val lcd = Lcd
    Box(
        Modifier
            .fillMaxWidth()
            .height(lcd.statusHeight)
            .background(Brush.verticalGradient(listOf(lcd.barTop, lcd.barBottom))),
    ) {
        if (lcd.material) return@Box MaterialStatus(title, playing, shuffle, repeatMode, battery)
        if (playing != null) PlayGlyph(playing, Modifier.align(Alignment.CenterStart).padding(start = 8.dp).size(9.dp))
        BasicText(
            title,
            // Wide enough on both sides that the title stays centered next to the right-hand icons
            Modifier.align(Alignment.Center).padding(horizontal = 62.dp),
            style = TextStyle(fontFamily = LocalLcdFont.current, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = lcd.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            Modifier.align(Alignment.CenterEnd).padding(end = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (shuffle) ShuffleGlyph(Modifier.padding(end = 4.dp).width(13.dp).height(10.dp))
            if (repeatMode != 0) RepeatGlyph(one = repeatMode == 1, Modifier.padding(end = 5.dp).width(13.dp).height(10.dp))
            Battery(battery, Modifier.width(22.dp).height(10.dp))
        }
        Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp)) {
            drawRect(lcd.barLine)
        }
    }
}

/** The status bar in Material style: Material icons in the scheme's colors, a flat battery. */
@Composable
private fun BoxScope.MaterialStatus(title: String, playing: Boolean?, shuffle: Boolean, repeatMode: Int, battery: BatteryState) {
    val lcd = Lcd
    val iconMod = Modifier.size(15.dp)
    if (playing != null) Icon(
        if (playing) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null,
        Modifier.align(Alignment.CenterStart).padding(start = 6.dp).size(16.dp), tint = lcd.accent,
    )
    BasicText(
        title,
        Modifier.align(Alignment.Center).padding(horizontal = 62.dp),
        style = TextStyle(fontFamily = LocalLcdFont.current, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = lcd.text),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Row(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (shuffle) Icon(Icons.Rounded.Shuffle, null, iconMod.padding(end = 1.dp), tint = lcd.dimText)
        if (repeatMode != 0) Icon(
            if (repeatMode == 1) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, null,
            iconMod.padding(end = 1.dp), tint = lcd.dimText,
        )
        Canvas(Modifier.padding(start = 3.dp).width(20.dp).height(10.dp)) {
            val nub = size.width * 0.08f
            val body = Size(size.width - nub - 1f, size.height)
            val r = CornerRadius(size.height * 0.3f)
            drawRoundRect(lcd.dimText.copy(alpha = 0.35f), size = body, cornerRadius = r)
            val low = battery.level <= 0.2f && !battery.charging
            drawRoundRect(
                if (low) Color(0xFFD32F2F) else lcd.accent,
                size = Size(body.width * battery.level.coerceIn(0.08f, 1f), body.height), cornerRadius = r,
            )
            drawRoundRect(
                lcd.dimText.copy(alpha = 0.35f), topLeft = Offset(body.width + 1f, size.height * 0.3f),
                size = Size(nub, size.height * 0.4f), cornerRadius = CornerRadius(nub / 2),
            )
        }
        if (battery.charging) Icon(Icons.Rounded.Bolt, null, Modifier.size(12.dp), tint = lcd.dimText)
    }
}

@Composable
private fun PlayGlyph(playing: Boolean, modifier: Modifier) {
    val lcd = Lcd
    Canvas(modifier) {
        val brush = Brush.verticalGradient(listOf(lcd.progressTop, lcd.progressBottom))
        if (playing) {
            val p = Path().apply {
                moveTo(0f, 0f); lineTo(size.width, size.height / 2); lineTo(0f, size.height); close()
            }
            drawPath(p, brush)
        } else {
            val bar = size.width * 0.34f
            drawRect(brush, size = Size(bar, size.height))
            drawRect(brush, topLeft = Offset(size.width - bar, 0f), size = Size(bar, size.height))
        }
    }
}

@Composable
private fun Battery(battery: BatteryState, modifier: Modifier) {
    val lcd = Lcd
    Canvas(modifier) {
        val nub = size.width * 0.1f
        val body = Size(size.width - nub, size.height)
        drawRoundRect(lcd.glyph, size = body, cornerRadius = CornerRadius(2f), style = Stroke(1.5f))
        drawRect(lcd.glyph, topLeft = Offset(body.width, size.height * 0.3f), size = Size(nub, size.height * 0.4f))
        val inset = 2.5f
        // Green normally, red when it is about to run out, like the iPod
        val fill = if (battery.level <= 0.2f && !battery.charging) {
            listOf(Color(0xFFFF8A80), Color(0xFFD32F2F))
        } else {
            listOf(Color(0xFFB4F07A), Color(0xFF4DAA1C))
        }
        drawRect(
            Brush.verticalGradient(fill),
            topLeft = Offset(inset, inset),
            size = Size((body.width - inset * 2) * battery.level.coerceIn(0.05f, 1f), body.height - inset * 2),
        )
        if (battery.charging) {
            val w = body.width
            val h = size.height
            val bolt = Path().apply {
                moveTo(w * 0.56f, h * 0.08f); lineTo(w * 0.32f, h * 0.56f); lineTo(w * 0.5f, h * 0.56f)
                lineTo(w * 0.42f, h * 0.92f); lineTo(w * 0.7f, h * 0.4f); lineTo(w * 0.52f, h * 0.4f); close()
            }
            drawPath(bolt, Color.White)
            drawPath(bolt, lcd.glyph, style = Stroke(0.8f))
        }
    }
}

/** 🔀: two crossing paths with arrowheads. */
@Composable
private fun ShuffleGlyph(modifier: Modifier) {
    val lcd = Lcd
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(1.4f * density / 2.6f + 0.8f)
        val a = Path().apply { moveTo(0f, h * 0.25f); cubicTo(w * 0.45f, h * 0.25f, w * 0.4f, h * 0.75f, w * 0.85f, h * 0.75f) }
        val b = Path().apply { moveTo(0f, h * 0.75f); cubicTo(w * 0.45f, h * 0.75f, w * 0.4f, h * 0.25f, w * 0.85f, h * 0.25f) }
        drawPath(a, lcd.glyph, style = stroke)
        drawPath(b, lcd.glyph, style = stroke)
        for (y in listOf(h * 0.25f, h * 0.75f)) {
            drawPath(Path().apply { moveTo(w, y); lineTo(w * 0.72f, y - h * 0.2f); lineTo(w * 0.72f, y + h * 0.2f); close() }, lcd.glyph)
        }
    }
}

/** 🔁 (or 🔂 with a "1"): a loop with an arrowhead. */
@Composable
private fun RepeatGlyph(one: Boolean, modifier: Modifier) {
    val lcd = Lcd
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(1.4f * density / 2.6f + 0.8f)
        val r = h * 0.3f
        drawRoundRect(lcd.glyph, Offset(w * 0.05f, h * 0.18f), Size(w * 0.85f, h * 0.64f), CornerRadius(r), style = stroke)
        // Gap and arrowhead on the top edge
        drawRect(lcd.barTop, Offset(w * 0.55f, 0f), Size(w * 0.18f, h * 0.35f))
        drawPath(Path().apply { moveTo(w * 0.78f, h * 0.18f); lineTo(w * 0.55f, 0f); lineTo(w * 0.55f, h * 0.36f); close() }, lcd.glyph)
        if (one) {
            drawLine(lcd.glyph, Offset(w * 0.47f, h * 0.36f), Offset(w * 0.47f, h * 0.66f), stroke.width)
            drawLine(lcd.glyph, Offset(w * 0.47f, h * 0.36f), Offset(w * 0.38f, h * 0.44f), stroke.width)
        }
    }
}
