package io.github.whyoolw.monopod.ui.wheel

import kotlin.math.atan2

enum class WheelZone { Menu, Next, PlayPause, Previous }

/** Center button radius relative to the wheel radius. */
const val CENTER_RATIO = 0.38f

/** Rotation needed for one "click" of the wheel; 24 clicks per full turn. */
const val DEGREES_PER_STEP = 15f

/** Rotation after which a touch on the ring is a scroll and no longer a button press. */
const val MOVE_SLOP_DEGREES = 6f

/** Angle of a point around the origin in screen coordinates: 0° = right, growing clockwise. */
fun angleOf(x: Float, y: Float): Float = Math.toDegrees(atan2(y, x).toDouble()).toFloat()

/** Shortest signed rotation from [from] to [to], in (-180, 180]. Positive = clockwise. */
fun angleDelta(from: Float, to: Float): Float {
    var d = (to - from) % 360f
    if (d > 180f) d -= 360f
    if (d <= -180f) d += 360f
    return d
}

/** Which of the four ring buttons is under [angle]: MENU on top, ⏭ right, ⏯ bottom, ⏮ left. */
fun zoneAt(angle: Float): WheelZone {
    val a = ((angle % 360f) + 360f) % 360f
    return when {
        a >= 225f && a < 315f -> WheelZone.Menu
        a >= 315f || a < 45f -> WheelZone.Next
        a < 135f -> WheelZone.PlayPause
        else -> WheelZone.Previous
    }
}

/** Turns continuous rotation into whole clicks, keeping the remainder for the next move. */
class StepAccumulator(private val degreesPerStep: Float = DEGREES_PER_STEP) {
    private var acc = 0f

    fun add(delta: Float): Int {
        acc += delta
        val steps = (acc / degreesPerStep).toInt()
        acc -= steps * degreesPerStep
        return steps
    }
}
