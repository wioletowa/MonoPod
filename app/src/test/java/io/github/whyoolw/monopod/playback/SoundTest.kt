package io.github.whyoolw.monopod.playback

import io.github.whyoolw.monopod.SoundCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SoundTest {
    @Test
    fun parsesGainStrings() {
        assertEquals(-6.54f, ReplayGain.parseDb("-6.54 dB")!!, 0.001f)
        assertEquals(2f, ReplayGain.parseDb("+2.00 dB")!!, 0.001f)
        assertNull(ReplayGain.parseDb("none"))
    }

    @Test
    fun volumeFollowsModeAndNeverBoosts() {
        val g = ReplayGain(track = -6f, album = -3f)
        assertEquals(1f, g.volume(SoundCheck.Off), 0.001f)
        assertEquals(0.501f, g.volume(SoundCheck.Track), 0.001f)
        assertEquals(0.708f, g.volume(SoundCheck.Album), 0.001f)
        // Missing album gain falls back to the track gain, and positive gains are capped
        assertEquals(0.501f, ReplayGain(-6f, null).volume(SoundCheck.Album), 0.001f)
        assertEquals(1f, ReplayGain(4f, null).volume(SoundCheck.Track), 0.001f)
        assertEquals(1f, ReplayGain.NONE.volume(SoundCheck.Track), 0.001f)
    }

    @Test
    fun eqInterpolatesOnLogScale() {
        val gains = listOf(6, 0, 0, 0, -6)
        assertEquals(6f, EqPreset.gainAt(gains, 30f), 0.001f)
        assertEquals(6f, EqPreset.gainAt(gains, 60f), 0.001f)
        assertEquals(-6f, EqPreset.gainAt(gains, 20000f), 0.001f)
        // sqrt(60*230) ≈ 117 Hz is halfway on a log scale
        assertEquals(3f, EqPreset.gainAt(gains, 117.47f), 0.01f)
        assertEquals(listOf(1, 2, 3, 4, 5), EqPreset.parseCustom("1,2,3,4,5"))
        assertEquals(listOf(0, 0, 0, 0, 0), EqPreset.parseCustom("bad"))
        assertNull(EqPreset.gainsFor("Off", listOf()))
        assertEquals(listOf(1, 1, 1, 1, 1), EqPreset.gainsFor(EqPreset.CUSTOM, listOf(1, 1, 1, 1, 1)))
    }

    @Test
    fun sleepTimerFadesOverLastHalfMinute() {
        assertEquals(1f, sleepFade(10 * 60_000L), 0.001f)
        assertEquals(1f, sleepFade(30_000L), 0.001f)
        assertEquals(0.5f, sleepFade(15_000L), 0.001f)
        assertEquals(0f, sleepFade(0L), 0.001f)
    }

    @Test
    fun userPresetsRoundTrip() {
        val saved = listOf(UserEq(1, "Мой пресет 1", listOf(6, 0, -2, 0, 3)), UserEq(4, "Bass", listOf(8, 4, 0, 0, 0)))
        val back = EqPreset.parseUser(EqPreset.encodeUser(saved))
        assertEquals(saved, back)
        assertEquals(listOf(8, 4, 0, 0, 0), EqPreset.gainsFor("user:4", listOf(), back))
        assertNull(EqPreset.gainsFor("user:9", listOf(), back))
        assertEquals("Мой пресет 1", EqPreset.label("user:1", back, "Выкл", "Свой"))
        assertEquals("Rock", EqPreset.label("Rock", back, "Выкл", "Свой"))
        assertEquals("Выкл", EqPreset.label("Off", back, "Выкл", "Свой"))
        assertEquals(emptyList<UserEq>(), EqPreset.parseUser("not json"))
    }
}
