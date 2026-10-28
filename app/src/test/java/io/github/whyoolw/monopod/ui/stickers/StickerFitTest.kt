package io.github.whyoolw.monopod.ui.stickers

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerFitTest {
    private val window = Rect(0f, 0f, 1000f, 2000f)
    private val keepout = Keepout(screen = Rect(60f, 300f, 940f, 960f), wheel = Rect(140f, 1100f, 860f, 1820f))

    @Test
    fun freeCornersFit() {
        assertTrue(stickerFits(Offset(100f, 150f), 150f, 150f, 0f, window, keepout))
        // Bottom-left corner, beside the wheel
        assertTrue(stickerFits(Offset(90f, 1900f), 120f, 120f, 0f, window, keepout))
    }

    @Test
    fun screenWheelAndEdgesAreOffLimits() {
        assertFalse(stickerFits(Offset(500f, 600f), 100f, 100f, 0f, window, keepout))
        assertFalse(stickerFits(Offset(500f, 1460f), 100f, 100f, 0f, window, keepout))
        assertFalse(stickerFits(Offset(20f, 150f), 100f, 100f, 0f, window, keepout))
    }

    @Test
    fun turningMakesTheBoxBigger() {
        // Fits upright just above the screen, but turned 45° its corners reach into it
        assertTrue(stickerFits(Offset(500f, 230f), 120f, 120f, 0f, window, keepout))
        assertFalse(stickerFits(Offset(500f, 230f), 120f, 120f, 45f, window, keepout))
    }
}
