package io.github.whyoolw.monopod.ui.menu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccelerationTest {
    @Test
    fun speedGrowsSmoothlyWithWheelSpeed() {
        // Calm turns are exact
        assertEquals(1f, scrollSpeed(3f), 0.001f)
        assertEquals(1f, scrollSpeed(6f), 0.001f)
        // Then every bit faster scrolls a bit faster, no jumps
        assertEquals(2.2f, scrollSpeed(10f), 0.001f)
        assertEquals(4f, scrollSpeed(16f), 0.001f)
        assertTrue(scrollSpeed(16.5f) > scrollSpeed(16f))
        assertEquals(16f, scrollSpeed(200f), 0.001f)
        // Boost 0 turns it off, 2 doubles the growth
        assertEquals(1f, scrollSpeed(30f, boost = 0f), 0.001f)
        assertEquals(5.8f, scrollSpeed(14f, boost = 2f), 0.001f)
    }

    @Test
    fun indexLetters() {
        assertEquals("B", indexLetter("beatles"))
        assertEquals("K", indexLetter("The Killers"))
        assertEquals("Ж", indexLetter("жёлтый"))
        assertEquals("#", indexLetter("99 Problems"))
        assertEquals("A", indexLetter("  (a) song"))
        assertEquals("#", indexLetter("…"))
    }
}
