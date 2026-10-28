package io.github.whyoolw.monopod.ui.wheel

import org.junit.Assert.assertEquals
import org.junit.Test

class WheelMathTest {
    @Test
    fun angleGrowsClockwiseOnScreen() {
        assertEquals(0f, angleOf(1f, 0f), 0.01f)
        assertEquals(90f, angleOf(0f, 1f), 0.01f) // below center
        assertEquals(-90f, angleOf(0f, -1f), 0.01f) // above center
    }

    @Test
    fun deltaWrapsAcrossTheSeam() {
        assertEquals(20f, angleDelta(170f, -170f), 0.01f)
        assertEquals(-20f, angleDelta(-170f, 170f), 0.01f)
        assertEquals(-30f, angleDelta(10f, -20f), 0.01f)
    }

    @Test
    fun zonesMatchButtonLayout() {
        assertEquals(WheelZone.Menu, zoneAt(-90f))
        assertEquals(WheelZone.Next, zoneAt(0f))
        assertEquals(WheelZone.PlayPause, zoneAt(90f))
        assertEquals(WheelZone.Previous, zoneAt(180f))
        assertEquals(WheelZone.Previous, zoneAt(-170f))
    }

    @Test
    fun accumulatorKeepsRemainder() {
        val acc = StepAccumulator(15f)
        assertEquals(0, acc.add(10f))
        assertEquals(1, acc.add(10f)) // 20 → 1 step, 5 left
        assertEquals(2, acc.add(25f)) // 30 → 2 steps
        assertEquals(0, acc.add(-10f))
        assertEquals(-1, acc.add(-10f)) // -20 → -1 step
    }
}
