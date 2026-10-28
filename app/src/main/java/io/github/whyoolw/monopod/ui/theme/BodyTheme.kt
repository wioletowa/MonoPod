package io.github.whyoolw.monopod.ui.theme

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import io.github.whyoolw.monopod.R

/** Colors of the player "hardware": body, click wheel and center button. */
data class BodyTheme(
    val id: String,
    /** Built-in bodies: a string resource; custom ones (theme files) carry [title] instead. */
    @StringRes val name: Int,
    val bodyTop: Color,
    val bodyBottom: Color,
    val wheelTop: Color,
    val wheelBottom: Color,
    val wheelEdge: Color,
    val label: Color,
    val centerTop: Color,
    val centerBottom: Color,
    val centerEdge: Color,
    /** Light body needs dark system bar icons. */
    val lightBody: Boolean,
    val finish: Finish = Finish.Gloss,
    /** The black glass around the screen; some bodies tint it. */
    val bezel: Color = Color(0xFF1B1B1E),
    /** A ring around the bezel, so the screen looks set into the body; none when null. */
    val screenFrame: Color? = null,
    /** A thin polished edge around the whole body; none when null. */
    val rim: Color? = null,
    val title: String? = null,
    /** Custom bodies: a picture over the whole body instead of the gradient (a file path). */
    val bodyImage: String? = null,
    /** Custom bodies: a picture behind the main menu list (a file path). */
    val menuImage: String? = null,
    /** How much of the screen color lies over [menuImage], 0–1, so the menu stays readable. */
    val menuVeil: Float = 0.35f,
)

/** The name to show: a custom body's own, or the built-in one's string. */
fun BodyTheme.label(res: android.content.res.Resources): String = title ?: res.getString(name)

/** Built-in bodies after the custom ones, which come from theme files. */
val AllBodies: List<BodyTheme> get() = CustomBodies.bodies.map { it.body } + BodyThemes

enum class Finish {
    Gloss,
    /** Fine horizontal brushing lines, like anodized aluminium. */
    Brushed,
    /** No shine at all, like ceramic. */
    Matte,
}

val BodyThemes = listOf(
    BodyTheme(
        id = "white", name = R.string.theme_white,
        bodyTop = Color(0xFFFBFBFC), bodyBottom = Color(0xFFDCDDE1),
        wheelTop = Color(0xFFFFFFFF), wheelBottom = Color(0xFFEDEDF0), wheelEdge = Color(0xFFC9CACF),
        label = Color(0xFFA2A3A9),
        centerTop = Color(0xFFF4F4F6), centerBottom = Color(0xFFDADBDF), centerEdge = Color(0xFFBFC0C5),
        lightBody = true,
    ),
    BodyTheme(
        id = "black", name = R.string.theme_black,
        bodyTop = Color(0xFF34343A), bodyBottom = Color(0xFF0D0D0F),
        wheelTop = Color(0xFF26262B), wheelBottom = Color(0xFF151518), wheelEdge = Color(0xFF050506),
        label = Color(0xFFD1D1D6),
        centerTop = Color(0xFF34343A), centerBottom = Color(0xFF1E1E22), centerEdge = Color(0xFF060607),
        lightBody = false,
    ),
    BodyTheme(
        id = "silver", name = R.string.theme_silver,
        bodyTop = Color(0xFFE3E4E7), bodyBottom = Color(0xFFA8AAB0),
        wheelTop = Color(0xFFF7F7F8), wheelBottom = Color(0xFFE3E3E6), wheelEdge = Color(0xFF9EA0A6),
        label = Color(0xFF8E9096),
        centerTop = Color(0xFFD9DADD), centerBottom = Color(0xFFB4B6BB), centerEdge = Color(0xFF8D8F95),
        lightBody = true, finish = Finish.Brushed,
    ),
    BodyTheme(
        id = "blue", name = R.string.theme_blue,
        bodyTop = Color(0xFF7FB6E8), bodyBottom = Color(0xFF2F6FB0),
        wheelTop = Color(0xFFF7F9FB), wheelBottom = Color(0xFFDDE4EB), wheelEdge = Color(0xFF2A5F96),
        label = Color(0xFF5D7FA3),
        centerTop = Color(0xFF8FC0EC), centerBottom = Color(0xFF4A86C4), centerEdge = Color(0xFF2A5F96),
        lightBody = false, finish = Finish.Brushed,
    ),
    BodyTheme(
        id = "red", name = R.string.theme_red,
        bodyTop = Color(0xFFE0464B), bodyBottom = Color(0xFF8E1116),
        wheelTop = Color(0xFFFBF7F7), wheelBottom = Color(0xFFE9DFDF), wheelEdge = Color(0xFF7A0E12),
        label = Color(0xFFB0585B),
        centerTop = Color(0xFFE85A5F), centerBottom = Color(0xFFB0252A), centerEdge = Color(0xFF7A0E12),
        lightBody = false, finish = Finish.Brushed,
    ),
    BodyTheme(
        id = "pastel", name = R.string.theme_pastel,
        bodyTop = Color(0xFFEDCFDE), bodyBottom = Color(0xFFD9AFC5),
        wheelTop = Color(0xFFFBF4F8), wheelBottom = Color(0xFFF1E3EB), wheelEdge = Color(0xFFD3A6BF),
        label = Color(0xFFA86B8E),
        centerTop = Color(0xFFEDCFDE), centerBottom = Color(0xFFDDB7CB), centerEdge = Color(0xFFC895B2),
        lightBody = true, finish = Finish.Brushed,
        bezel = Color(0xFF26222A), screenFrame = Color(0xFFB88AA4),
    ),
    BodyTheme(
        id = "ceramic", name = R.string.theme_ceramic,
        bodyTop = Color(0xFFFAFAF8), bodyBottom = Color(0xFFF1F1EE),
        // The wheel is the body itself, only outlined, like an engraving
        wheelTop = Color(0xFFF7F7F5), wheelBottom = Color(0xFFF2F2EF), wheelEdge = Color(0xFFD6D7DA),
        label = Color(0xFFA5A8AD),
        centerTop = Color(0xFFF4F4F1), centerBottom = Color(0xFFECECE8), centerEdge = Color(0xFFD6D7DA),
        lightBody = true, finish = Finish.Matte,
        bezel = Color(0xFF1A1A1C), rim = Color(0xFFBFC2C7),
    ),
    BodyTheme(
        id = "nano", name = R.string.theme_nano,
        bodyTop = Color(0xFFB8E8D8), bodyBottom = Color(0xFF8ED3BD),
        wheelTop = Color(0xFFF3FBF8), wheelBottom = Color(0xFFE3F4EE), wheelEdge = Color(0xFF7CC2AC),
        label = Color(0xFF4F8F7C),
        centerTop = Color(0xFFB8E8D8), centerBottom = Color(0xFF9EDCC8), centerEdge = Color(0xFF6FBFA8),
        lightBody = true, finish = Finish.Brushed,
        bezel = Color(0xFF18342C),
    ),
)
