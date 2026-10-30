package io.github.whyoolw.monopod.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser

/** The heart on a 24-unit grid, as in Material's "favorite". */
private val HEART = PathParser().parsePathString(
    "M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09" +
        "C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z",
).toPath()

/** The same heart in 7×6 pixels, for the pixel screens: filled, and its outline. */
private val PIXEL_FULL = listOf(".##.##.", "#######", "#######", ".#####.", "..###..", "...#...")
private val PIXEL_OUTLINE = listOf(".##.##.", "#..#..#", "#.....#", ".#...#.", "..#.#..", "...#...")

/**
 * The like mark: a filled heart when [liked], an outline when not. Soft and round with a thick
 * line, or in whole pixels where the screen is pixel (the pixel font, iPod 1G, green LCD).
 */
@Composable
fun Heart(liked: Boolean, color: Color, modifier: Modifier = Modifier) {
    val pixel = LocalPixelScreen.current || LocalLcdFont.current.isPixel
    Canvas(modifier) {
        if (pixel) {
            val rows = if (liked) PIXEL_FULL else PIXEL_OUTLINE
            // Whole device pixels per cell, so the edges stay sharp
            val cell = maxOf(1f, kotlin.math.floor(minOf(size.width / 7f, size.height / 6f)))
            val left = kotlin.math.floor((size.width - cell * 7) / 2)
            val top = kotlin.math.floor((size.height - cell * 6) / 2)
            rows.forEachIndexed { y, row ->
                row.forEachIndexed { x, c ->
                    if (c == '#') drawRect(color, Offset(left + x * cell, top + y * cell), Size(cell, cell))
                }
            }
        } else {
            // Drawn a little smaller than the grid, so the thick line stays inside it
            val s = minOf(size.width, size.height) / 24f
            scale(s * 0.82f, pivot = Offset(size.width / 2, size.height / 2)) {
                translate(size.width / 2 - 12f, size.height / 2 - 12f) {
                    if (liked) {
                        drawPath(HEART, color, style = Fill)
                        drawPath(HEART, color, style = Stroke(2.6f, join = StrokeJoin.Round))
                    } else {
                        drawPath(HEART, color, style = Stroke(3.2f, join = StrokeJoin.Round))
                    }
                }
            }
        }
    }
}
