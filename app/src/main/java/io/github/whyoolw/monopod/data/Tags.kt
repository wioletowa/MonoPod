package io.github.whyoolw.monopod.data

import java.net.URLDecoder

/** Trimmed tag value, null when missing or blank. */
fun String?.tag(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/** "3/12" → 3, "07" → 7. */
fun parseNumber(value: String?): Int? = value?.trim()?.substringBefore('/')?.trim()?.toIntOrNull()

/** First 4-digit year in "2012", "2012-05-01", "20120501T000000". */
fun parseYear(value: String?): Int? =
    value?.let { Regex("""(1[89]\d\d|20\d\d)""").find(it)?.value?.toInt() }

/** ID3v1 numeric genres, as written by old taggers: "(17)", "17", "(17)Rock". */
fun normalizeGenre(value: String?): String? {
    val v = value.tag() ?: return null
    val m = Regex("""^\((\d+)\)(.*)$""").find(v) ?: Regex("""^(\d+)()$""").find(v)
    if (m != null) {
        m.groupValues[2].tag()?.let { return it }
        return ID3_GENRES.getOrNull(m.groupValues[1].toInt())
    }
    return v
}

/** Groups tracks into albums: same album title from the same album artist (or artist). */
fun albumKey(albumArtist: String?, artist: String?, album: String?): String =
    (albumArtist ?: artist ?: "").lowercase().trim() + "\u0000" + (album ?: "").lowercase().trim()

/** Lines of an .m3u/.m3u8 file that point at media, comments and blanks dropped. */
fun parseM3u(text: String): List<String> =
    text.removePrefix("\uFEFF").lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .toList()

/**
 * Finds the library file a playlist entry points at.
 * [playlistDir] is the playlist's directory inside its library folder, [files] maps
 * paths inside that folder to URIs. Relative entries resolve against the playlist's
 * directory; absolute ones (from another device, "C:\Music\...") match by the longest
 * tail of the path that exists in the folder.
 */
fun resolvePlaylistEntry(entry: String, playlistDir: String, files: Map<String, String>): String? {
    var e = entry.replace('\\', '/')
    // URLDecoder turns "+" into a space, but in file URLs it is a literal plus
    if (e.startsWith("file://")) e = URLDecoder.decode(e.removePrefix("file://").replace("+", "%2B"), "UTF-8")
    else if ("://" in e) return null

    val absolute = e.startsWith("/") || Regex("^[A-Za-z]:/").containsMatchIn(e)
    if (!absolute) {
        normalizePath(if (playlistDir.isEmpty()) e else "$playlistDir/$e")?.let { p -> files[p]?.let { return it } }
    }
    val parts = e.split('/').filter { it.isNotEmpty() && it != "." }
    for (i in parts.indices) {
        files[parts.drop(i).joinToString("/")]?.let { return it }
    }
    return null
}

/** Resolves "." and ".." segments; null when the path climbs above the folder root. */
fun normalizePath(path: String): String? {
    val out = ArrayDeque<String>()
    for (seg in path.split('/')) {
        when (seg) {
            "", "." -> Unit
            ".." -> if (out.isEmpty()) return null else out.removeLast()
            else -> out.addLast(seg)
        }
    }
    return out.joinToString("/")
}

private val ID3_GENRES = listOf(
    "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz", "Metal",
    "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno", "Industrial",
    "Alternative", "Ska", "Death Metal", "Pranks", "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop",
    "Vocal", "Jazz+Funk", "Fusion", "Trance", "Classical", "Instrumental", "Acid", "House", "Game",
    "Sound Clip", "Gospel", "Noise", "Alternative Rock", "Bass", "Soul", "Punk", "Space", "Meditative",
    "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic", "Darkwave", "Techno-Industrial",
    "Electronic", "Pop-Folk", "Eurodance", "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta",
    "Top 40", "Christian Rap", "Pop/Funk", "Jungle", "Native American", "Cabaret", "New Wave",
    "Psychedelic", "Rave", "Showtunes", "Trailer", "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz", "Polka",
    "Retro", "Musical", "Rock & Roll", "Hard Rock",
)
