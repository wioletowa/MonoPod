package io.github.whyoolw.monopod.ui.wheel

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import io.github.whyoolw.monopod.ui.theme.BodyTheme
import kotlin.math.abs

sealed interface WheelEvent {
    /** Rotation by whole clicks; positive = clockwise. */
    data class Scroll(val steps: Int) : WheelEvent
    data class Press(val zone: WheelZone) : WheelEvent
    data class LongPress(val zone: WheelZone) : WheelEvent
    /** The finger left a ring button after a [LongPress], e.g. stop fast-forwarding. */
    data class Release(val zone: WheelZone) : WheelEvent
    data object Select : WheelEvent
    data object SelectLong : WheelEvent
}

private sealed interface Pressed {
    data class Ring(val zone: WheelZone) : Pressed
    data object Center : Pressed
}

/**
 * The click wheel. Sliding a finger around the ring scrolls in clicks, tapping the ring presses
 * the button under the finger (MENU, ⏭, ⏯, ⏮), the middle is the select button.
 */
@Composable
fun ClickWheel(
    theme: BodyTheme,
    feedback: WheelFeedback,
    onEvent: (WheelEvent) -> Unit,
    modifier: Modifier = Modifier,
    /** Rotation per click; smaller is more sensitive. */
    degreesPerStep: Float = DEGREES_PER_STEP,
    /** Material style: icons instead of printed labels and a shape-shifting center button. */
    material: MaterialWheel? = null,
) {
    val emit by rememberUpdatedState(onEvent)
    val stepDegrees by rememberUpdatedState(degreesPerStep)
    var pressed by remember { mutableStateOf<Pressed?>(null) }
    val measurer = rememberTextMeasurer()

    Box(
        modifier
            .aspectRatio(1f)
            .semantics { contentDescription = "Click wheel" }
            .pointerInput(Unit) {
                val longPressMs = viewConfiguration.longPressTimeoutMillis
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val r = size.width / 2f
                    val start = down.position - c
                    if (start.getDistance() > r) return@awaitEachGesture
                    down.consume()

                    if (start.getDistance() < r * CENTER_RATIO) {
                        pressed = Pressed.Center
                        val up = withTimeoutOrNull(longPressMs) { waitForUpOrCancellation() }
                        if (up != null) {
                            feedback.press(); emit(WheelEvent.Select)
                        } else {
                            feedback.press(); emit(WheelEvent.SelectLong)
                            waitForUpOrCancellation()
                        }
                        pressed = null
                        return@awaitEachGesture
                    }

                    var last = angleOf(start.x, start.y)
                    val zone = zoneAt(last)
                    pressed = Pressed.Ring(zone)
                    val steps = StepAccumulator(stepDegrees)
                    var travelled = 0f
                    var scrolling = false
                    var longFired = false
                    val downAt = SystemClock.uptimeMillis()

                    while (true) {
                        val waitLong = !scrolling && !longFired
                        val event = if (waitLong) {
                            val left = (downAt + longPressMs - SystemClock.uptimeMillis()).coerceAtLeast(0)
                            withTimeoutOrNull(left) { awaitPointerEvent() }
                        } else {
                            awaitPointerEvent()
                        }
                        if (event == null) {
                            longFired = true
                            feedback.press(); emit(WheelEvent.LongPress(zone))
                            continue
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (!scrolling && !longFired) {
                                feedback.press(); emit(WheelEvent.Press(zone))
                            }
                            break
                        }
                        change.consume()
                        // A fast spin delivers several touch points per frame; walk through all of
                        // them, or a quick half turn would read as a jump of unknown direction
                        var d = 0f
                        for (pos in change.historical.map { it.position } + change.position) {
                            val p = pos - c
                            // Near the middle the angle jumps around, ignore it like the real wheel does
                            if (p.getDistance() < r * 0.2f) continue
                            val a = angleOf(p.x, p.y)
                            d += angleDelta(last, a)
                            last = a
                        }
                        if (d == 0f) continue
                        travelled += abs(d)
                        if (!scrolling && !longFired && travelled > MOVE_SLOP_DEGREES) {
                            scrolling = true
                            pressed = null
                        }
                        if (scrolling) {
                            val n = steps.add(d)
                            if (n != 0) {
                                // One tick per event: a burst of clicks would just buzz
                                feedback.tick(); emit(WheelEvent.Scroll(n))
                            }
                        }
                    }
                    if (longFired) emit(WheelEvent.Release(zone))
                    pressed = null
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val c = center
            val cr = r * CENTER_RATIO

            if (material != null) {
                // Material: a flat tonal disc; icons and the center button are composed on top
                drawCircle(theme.wheelTop, r, c)
                (pressed as? Pressed.Ring)?.let { drawZoneHighlight(it.zone, c, r, cr, material.fab.copy(alpha = 0.14f)) }
                return@Canvas
            }

            // Ring
            // Borders are strokes so a see-through wheel is not darkened by a disc underneath
            drawCircle(Brush.verticalGradient(listOf(theme.wheelTop, theme.wheelBottom)), r - 1.5f, c)
            drawCircle(theme.wheelEdge, r - 0.75f, c, style = Stroke(1.5f))
            // Inner bevel so the ring reads as a slightly concave surface
            drawCircle(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.06f), Color.White.copy(alpha = 0.08f))),
                r - 1.5f, c, style = Stroke(r * 0.04f),
            )

            (pressed as? Pressed.Ring)?.let { drawZoneHighlight(it.zone, c, r, cr) }

            val labelColor = theme.label
            val g = r * 0.085f
            val label = measurer.measure(
                "MENU",
                TextStyle(color = labelColor, fontWeight = FontWeight.Bold, fontSize = (r * 0.075f).toSp()),
            )
            drawText(label, topLeft = Offset(c.x - label.size.width / 2f, c.y - r * 0.80f - label.size.height / 2f))
            drawSkip(Offset(c.x + r * 0.80f, c.y), g, labelColor, forward = true)
            drawSkip(Offset(c.x - r * 0.80f, c.y), g, labelColor, forward = false)
            drawPlayPause(Offset(c.x, c.y + r * 0.80f), g, labelColor)

            // Center button
            drawCircle(Color.Black.copy(alpha = 0.10f), cr + r * 0.025f, c + Offset(0f, r * 0.012f))
            val centerBrush = if (pressed == Pressed.Center) {
                Brush.verticalGradient(listOf(theme.centerBottom, theme.centerTop), c.y - cr, c.y + cr)
            } else {
                Brush.verticalGradient(listOf(theme.centerTop, theme.centerBottom), c.y - cr, c.y + cr)
            }
            drawCircle(centerBrush, cr - 1.5f, c)
            drawCircle(theme.centerEdge, cr - 0.75f, c, style = Stroke(1.5f))
        }
        if (material != null) MaterialWheelOverlay(material, pressed == Pressed.Center)
    }
}

/** What the Material wheel shows: the four ring icons, the center button's icon and shape, colors. */
data class MaterialWheel(
    val top: ImageVector,
    val right: ImageVector,
    val bottom: ImageVector,
    val left: ImageVector,
    val center: ImageVector,
    /** Round center (playing) or a rounded square (menus). */
    val round: Boolean,
    val icon: Color,
    val fab: Color,
    val onFab: Color,
)

@Composable
private fun BoxScope.MaterialWheelOverlay(w: MaterialWheel, centerPressed: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val r = maxWidth / 2
        val iconSize = r * 0.24f
        val ring = r * 0.78f
        @Composable
        fun ringIcon(v: ImageVector, dx: Float, dy: Float) = Icon(
            v, null,
            Modifier.align(Alignment.Center).offset(ring * dx, ring * dy).size(iconSize),
            tint = w.icon,
        )
        ringIcon(w.top, 0f, -1f)
        ringIcon(w.right, 1f, 0f)
        ringIcon(w.bottom, 0f, 1f)
        ringIcon(w.left, -1f, 0f)
        // The center button: a plain accent circle, no icon on it, that dips when pressed
        val scale by animateFloatAsState(if (centerPressed) 0.9f else 1f, tween(120), label = "press")
        Box(
            Modifier.align(Alignment.Center).size(r * 2 * CENTER_RATIO * 0.94f)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .background(w.fab, CircleShape),
        )
    }
}

private fun DrawScope.drawZoneHighlight(zone: WheelZone, c: Offset, r: Float, cr: Float, color: Color = Color.Black.copy(alpha = 0.08f)) {
    val startAngle = when (zone) {
        WheelZone.Next -> -45f
        WheelZone.PlayPause -> 45f
        WheelZone.Previous -> 135f
        WheelZone.Menu -> 225f
    }
    val thickness = r - cr
    val mid = (r + cr) / 2f
    drawArc(
        color,
        startAngle, 90f, useCenter = false,
        topLeft = Offset(c.x - mid, c.y - mid), size = Size(mid * 2, mid * 2),
        style = Stroke(thickness),
    )
}

private fun DrawScope.triangle(tip: Offset, w: Float, h: Float, right: Boolean): Path = Path().apply {
    val dx = if (right) -w else w
    moveTo(tip.x, tip.y)
    lineTo(tip.x + dx, tip.y - h / 2)
    lineTo(tip.x + dx, tip.y + h / 2)
    close()
}

private fun DrawScope.drawSkip(at: Offset, g: Float, color: Color, forward: Boolean) {
    val w = g * 0.75f
    val h = g
    val bar = g * 0.16f
    if (forward) {
        drawPath(triangle(Offset(at.x - w * 0.15f, at.y), w, h, right = true), color)
        drawPath(triangle(Offset(at.x + w * 0.85f - w * 0.15f, at.y), w, h, right = true), color)
        drawRect(color, Offset(at.x + w * 0.70f, at.y - h / 2), Size(bar, h))
    } else {
        drawPath(triangle(Offset(at.x + w * 0.15f, at.y), w, h, right = false), color)
        drawPath(triangle(Offset(at.x - w * 0.85f + w * 0.15f, at.y), w, h, right = false), color)
        drawRect(color, Offset(at.x - w * 0.70f - bar, at.y - h / 2), Size(bar, h))
    }
}

private fun DrawScope.drawPlayPause(at: Offset, g: Float, color: Color) {
    val h = g * 0.9f
    val w = h * 0.8f
    drawPath(triangle(Offset(at.x - g * 0.05f, at.y), w, h, right = true), color)
    val bar = g * 0.17f
    drawRect(color, Offset(at.x + g * 0.15f, at.y - h / 2), Size(bar, h))
    drawRect(color, Offset(at.x + g * 0.15f + bar * 1.9f, at.y - h / 2), Size(bar, h))
}
