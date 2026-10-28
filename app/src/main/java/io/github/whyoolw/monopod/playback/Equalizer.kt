package io.github.whyoolw.monopod.playback

import android.media.audiofx.Equalizer
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln

/**
 * The iPod's EQ list. Gains in dB at [FREQUENCIES]; the device equalizer gets them
 * interpolated onto whatever bands it has.
 */
enum class EqPreset(val title: String, val gains: List<Int>?) {
    Off("Off", null),
    Acoustic("Acoustic", listOf(4, 3, 1, 2, 3)),
    BassBooster("Bass Booster", listOf(6, 3, 0, 0, 0)),
    BassReducer("Bass Reducer", listOf(-6, -3, 0, 0, 0)),
    Classical("Classical", listOf(4, 2, -1, 2, 4)),
    Dance("Dance", listOf(5, 3, 0, 3, 2)),
    Deep("Deep", listOf(4, 2, 1, -2, -4)),
    Electronic("Electronic", listOf(4, 1, -2, 2, 4)),
    HipHop("Hip-Hop", listOf(5, 3, -1, 1, 3)),
    Jazz("Jazz", listOf(3, 1, -2, 1, 3)),
    Latin("Latin", listOf(3, 0, -2, 0, 4)),
    Loudness("Loudness", listOf(6, 2, -2, 1, 5)),
    Lounge("Lounge", listOf(-3, -1, 2, 1, -1)),
    Piano("Piano", listOf(2, 1, 2, 3, 3)),
    Pop("Pop", listOf(-1, 1, 4, 1, -1)),
    RnB("R&B", listOf(6, 3, -2, 2, 3)),
    Rock("Rock", listOf(5, 3, -2, 3, 5)),
    SmallSpeakers("Small Speakers", listOf(5, 4, 1, -1, -3)),
    SpokenWord("Spoken Word", listOf(-3, 0, 4, 3, 1)),
    TrebleBooster("Treble Booster", listOf(0, 0, 0, 3, 6)),
    TrebleReducer("Treble Reducer", listOf(0, 0, 0, -3, -6)),
    VocalBooster("Vocal Booster", listOf(-2, -1, 4, 3, 0));

    companion object {
        /** Band centers of the presets and the custom EQ, Hz. */
        val FREQUENCIES = listOf(60, 230, 910, 3600, 14000)
        const val CUSTOM = "Custom"

        fun parseCustom(value: String?): List<Int> =
            value?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == FREQUENCIES.size }
                ?: List(FREQUENCIES.size) { 0 }

        /** Display name of a preset key; [off] and [custom] are the localized words. */
        fun label(key: String, user: List<UserEq>, off: String, custom: String): String = when {
            key == CUSTOM -> custom
            key == Off.name -> off
            key.startsWith(USER_PREFIX) -> user.firstOrNull { it.key == key }?.name ?: off
            else -> entries.firstOrNull { it.name == key }?.title ?: off
        }

        /** Saved presets are stored as "user:<id>". */
        const val USER_PREFIX = "user:"

        /** dB per band for a stored preset name, null when the EQ is off. */
        fun gainsFor(preset: String?, custom: List<Int>, user: List<UserEq> = emptyList()): List<Int>? = when {
            preset == CUSTOM -> custom
            preset?.startsWith(USER_PREFIX) == true -> user.firstOrNull { it.key == preset }?.gains
            else -> entries.firstOrNull { it.name == preset }?.gains
        }

        /** Presets the user saved, stored as JSON: [{"id":3,"name":"…","gains":[…]}]. */
        fun parseUser(json: String?): List<UserEq> = try {
            val array = JSONArray(json ?: "[]")
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                val g = o.getJSONArray("gains")
                UserEq(o.getInt("id"), o.getString("name"), (0 until g.length()).map { g.getInt(it) })
            }
        } catch (_: Exception) {
            emptyList()
        }

        fun encodeUser(presets: List<UserEq>): String = JSONArray().apply {
            presets.forEach { p ->
                put(JSONObject().put("id", p.id).put("name", p.name).put("gains", JSONArray(p.gains)))
            }
        }.toString()

        /** Gain at [hz], interpolated between preset bands on a log frequency scale. */
        fun gainAt(gains: List<Int>, hz: Float): Float {
            val f = FREQUENCIES
            if (hz <= f.first()) return gains.first().toFloat()
            if (hz >= f.last()) return gains.last().toFloat()
            val i = f.indexOfLast { it <= hz }
            val t = (ln(hz) - ln(f[i].toFloat())) / (ln(f[i + 1].toFloat()) - ln(f[i].toFloat()))
            return gains[i] + (gains[i + 1] - gains[i]) * t
        }
    }
}

/** An EQ curve the user saved from the custom bands. */
data class UserEq(val id: Int, val name: String, val gains: List<Int>) {
    val key: String get() = EqPreset.USER_PREFIX + id
}

/** The platform equalizer on the player's audio session. */
class EqController {
    private var eq: Equalizer? = null
    private var gains: List<Int>? = null

    fun attach(audioSessionId: Int) {
        eq?.release()
        eq = try {
            Equalizer(0, audioSessionId)
        } catch (e: Exception) {
            // Some devices have no equalizer effect; music still plays without it
            Log.w(TAG, "no equalizer for session $audioSessionId", e); null
        }
        apply(gains)
    }

    fun apply(gains: List<Int>?) {
        this.gains = gains
        val e = eq ?: return
        try {
            if (gains == null) {
                e.enabled = false
                return
            }
            val (min, max) = e.bandLevelRange.let { it[0] to it[1] }
            for (band in 0 until e.numberOfBands) {
                val hz = e.getCenterFreq(band.toShort()) / 1000f
                val millibel = (EqPreset.gainAt(gains, hz) * 100).toInt().coerceIn(min.toInt(), max.toInt())
                e.setBandLevel(band.toShort(), millibel.toShort())
            }
            e.enabled = true
            Log.i(TAG, "eq on: " + (0 until e.numberOfBands).joinToString { b ->
                "${e.getCenterFreq(b.toShort()) / 1000}Hz=${e.getBandLevel(b.toShort())}mB"
            })
        } catch (ex: Exception) {
            Log.w(TAG, "cannot set equalizer", ex)
        }
    }

    fun release() {
        eq?.release()
        eq = null
    }

    private companion object {
        const val TAG = "Equalizer"
    }
}
