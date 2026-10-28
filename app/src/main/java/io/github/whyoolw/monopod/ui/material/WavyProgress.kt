package io.github.whyoolw.monopod.ui.material

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.whyoolw.monopod.ui.screen.Lcd
import kotlin.math.PI
import kotlin.math.sin

/**
 * The Android 16 style progress line: the played part is a wave that travels while the song
 * plays and smooths out when paused; the rest is a straight track with a stop dot at the end.
 * [thumb] shows a round knob, used while scrubbing.
 */
@Composable
fun WavyProgress(fraction: Float, playing: Boolean, modifier: Modifier = Modifier, thumb: Boolean = false) {
    val lcd = Lcd
    val phase by rememberInfiniteTransition(label = "wave").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "phase",
    )
    // The wave flattens when paused rather than freezing mid-swing
    val amplitude by animateFloatAsState(if (playing) 1f else 0f, tween(400), label = "amp")
    Canvas(modifier) {
        val stroke = size.height * 0.28f
        val mid = size.height / 2
        val x = size.width * fraction.coerceIn(0f, 1f)
        val wave = size.height * 0.26f * amplitude
        val length = size.height * 2.2f
        val gap = stroke * 1.4f

        if (x > stroke) {
            val path = Path().apply {
                moveTo(stroke / 2, mid)
                var px = stroke / 2
                while (px < x - gap / 2) {
                    val y = mid + wave * sin(2 * PI * (px / length - phase)).toFloat()
                    lineTo(px, y)
                    px += 2f
                }
            }
            drawPath(path, lcd.accent, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        val trackStart = x + gap
        if (trackStart < size.width - stroke) {
            drawLine(lcd.trackTop, Offset(trackStart, mid), Offset(size.width - stroke / 2, mid), stroke, StrokeCap.Round)
        }
        drawCircle(lcd.accent, stroke * 0.55f, Offset(size.width - stroke / 2, mid))
        if (thumb) {
            // Scrubbing: a big round knob on the line marks the spot
            drawCircle(lcd.accent, size.height * 0.55f, Offset(x, mid))
            drawCircle(lcd.background, size.height * 0.22f, Offset(x, mid))
        }
    }
}
