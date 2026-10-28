package io.github.whyoolw.monopod.playback

import androidx.media3.common.C
import androidx.media3.common.Metadata
import androidx.media3.common.Tracks
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import io.github.whyoolw.monopod.SoundCheck
import kotlin.math.pow

/** ReplayGain values of a song in dB, from whichever tag format the file uses. */
data class ReplayGain(val track: Float?, val album: Float?) {
    /** Volume factor for [mode]. Players can only turn down, so boosts are capped at 1. */
    fun volume(mode: SoundCheck): Float {
        val db = when (mode) {
            SoundCheck.Off -> null
            SoundCheck.Track -> track ?: album
            SoundCheck.Album -> album ?: track
        } ?: return 1f
        return 10f.pow(db / 20f).coerceAtMost(1f)
    }

    companion object {
        val NONE = ReplayGain(null, null)

        /** Reads the gain tags of the selected audio track, as the player parsed them. */
        fun from(tracks: Tracks): ReplayGain {
            var result = NONE
            for (group in tracks.groups) {
                if (group.type != C.TRACK_TYPE_AUDIO) continue
                for (i in 0 until group.length) {
                    group.getTrackFormat(i).metadata?.let { result = merge(result, from(it)) }
                }
            }
            return result
        }

        fun from(metadata: Metadata): ReplayGain {
            var track: Float? = null
            var album: Float? = null
            for (i in 0 until metadata.length()) {
                val (key, value) = when (val e = metadata.get(i)) {
                    is TextInformationFrame -> (e.description ?: continue) to e.values.firstOrNull()
                    is VorbisComment -> e.key to e.value
                    // MP4 freeform atoms (----:com.apple.iTunes:replaygain_track_gain)
                    is InternalFrame -> e.description to e.text
                    else -> continue
                }
                val v = value ?: continue
                when (key.uppercase()) {
                    "REPLAYGAIN_TRACK_GAIN" -> track = parseDb(v)
                    "REPLAYGAIN_ALBUM_GAIN" -> album = parseDb(v)
                    // Opus: Q7.8 fixed point relative to -23 LUFS; +5 dB lines it up with ReplayGain's 89 dB
                    "R128_TRACK_GAIN" -> if (track == null) track = v.trim().toIntOrNull()?.let { it / 256f + 5f }
                    "R128_ALBUM_GAIN" -> if (album == null) album = v.trim().toIntOrNull()?.let { it / 256f + 5f }
                }
            }
            return ReplayGain(track, album)
        }

        /** "-6.54 dB" → -6.54 */
        fun parseDb(value: String): Float? = Regex("""[-+]?\d+(\.\d+)?""").find(value)?.value?.toFloatOrNull()

        private fun merge(a: ReplayGain, b: ReplayGain) = ReplayGain(a.track ?: b.track, a.album ?: b.album)
    }
}
