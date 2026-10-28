package io.github.whyoolw.monopod

import androidx.compose.ui.graphics.Color
import io.github.whyoolw.monopod.ui.theme.CustomBodies
import io.github.whyoolw.monopod.ui.theme.Finish
import io.github.whyoolw.monopod.ui.theme.ThemeFileError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ThemeFileTest {
    private val template = File("src/main/assets/monopod-theme-template.css").readText()

    @Test
    fun templateParses() {
        val (body, settings) = CustomBodies.parse(template, "custom:t")
        assertEquals("Midnight Blue", body.title)
        assertEquals(Color(0xFF2B3A55), body.bodyTop)
        assertEquals(Finish.Gloss, body.finish)
        assertNull(body.rim)
        assertFalse(body.lightBody)
        assertEquals("blue", settings["colors"])
        assertEquals("40%", settings["effect-strength"])
        assertEquals(8, settings.size)
    }

    @Test
    fun minimalFileDerivesTheRest() {
        val (body, settings) = CustomBodies.parse("monopod-theme { name: Snow; body-top: #fff; body-bottom: #EEEEEE; }", "custom:s")
        assertTrue(body.lightBody)
        assertEquals(Color(0xFFFFFFFF), body.bodyTop)
        assertTrue(settings.isEmpty())
    }

    @Test
    fun badFilesSayWhy() {
        fun error(text: String) = runCatching { CustomBodies.parse(text, "x") }.exceptionOrNull() as? ThemeFileError
        assertTrue(error("hello")!!.message!!.contains("monopod-theme"))
        assertTrue(error("monopod-theme { name: A; body-top: red; body-bottom: #000; }")!!.message!!.contains("body-top"))
        assertTrue(error("monopod-theme { name: A; body-top: #000; body-bottom: #000; glow: 1; }")!!.message!!.contains("glow"))
        assertTrue(error("monopod-theme { name: A; body-top: #000; body-bottom: #000; effect: neon; }")!!.message!!.contains("effect"))
    }
}
