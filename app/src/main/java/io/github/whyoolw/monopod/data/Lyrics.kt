package io.github.whyoolw.monopod.data

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** One line of lyrics; [timeMs] is set for synced (LRC) lyrics. */
data class LyricLine(val timeMs: Long?, val text: String)

class Lyrics(val lines: List<LyricLine>) {
    val synced: Boolean = lines.isNotEmpty() && lines.all { it.timeMs != null }

    /** Index of the line being sung at [positionMs], -1 before the first one. */
    fun lineAt(positionMs: Long): Int {
        if (!synced) return -1
        var found = -1
        for ((i, line) in lines.withIndex()) {
            if (line.timeMs!! <= positionMs) found = i else break
        }
        return found
    }
}

private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
private val OFFSET_TAG = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
/** [ar:...], [ti:...], [length:...] and other LRC header tags. */
private val INFO_TAG = Regex("""^\[[a-zA-Z#]+:.*]$""")

/**
 * Lyrics from an .lrc file or a lyrics tag. Lines with [mm:ss.xx] stamps make synced lyrics
 * (a line may carry several stamps); anything else is plain text, one line per line.
 */
fun parseLyrics(text: String): Lyrics? {
    val raw = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').lines()
    // LRC: offset > 0 means the lyrics should show up earlier
    val offset = raw.firstNotNullOfOrNull { OFFSET_TAG.find(it)?.groupValues?.get(1)?.toLongOrNull() } ?: 0L
    val timed = mutableListOf<LyricLine>()
    for (line in raw) {
        val stamps = TIME_TAG.findAll(line).toList()
        if (stamps.isEmpty()) continue
        val words = line.substring(stamps.last().range.last + 1).trim()
        for (m in stamps) {
            val (min, sec, frac) = m.destructured
            val fracMs = when (frac.length) {
                0 -> 0L
                1 -> frac.toLong() * 100
                2 -> frac.toLong() * 10
                else -> frac.toLong()
            }
            val t = min.toLong() * 60_000 + sec.toLong() * 1000 + fracMs - offset
            timed += LyricLine(t.coerceAtLeast(0), words)
        }
    }
    if (timed.isNotEmpty()) return Lyrics(timed.sortedBy { it.timeMs })

    val plain = raw.map { it.trim() }.filterNot { INFO_TAG.matches(it) }
        .dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }
    return if (plain.isEmpty()) null else Lyrics(plain.map { LyricLine(null, it) })
}

/** UTF-8 when the bytes are valid UTF-8, otherwise Windows-1251 — what Russian .lrc files usually are. */
fun decodeLyricsFile(bytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
} catch (_: CharacterCodingException) {
    String(bytes, Charset.forName("windows-1251"))
}

/** Text of an ID3 USLT frame body: encoding byte, 3-letter language, description, lyrics. */
fun parseUslt(data: ByteArray): String? {
    if (data.size < 5) return null
    val charset = when (data[0].toInt()) {
        0 -> Charsets.ISO_8859_1
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        else -> Charsets.UTF_8
    }
    val wide = data[0].toInt() == 1 || data[0].toInt() == 2
    // Skip the description, which ends with a 1-byte (or 2-byte for UTF-16) zero
    var i = 4
    if (wide) {
        while (i + 1 < data.size && !(data[i].toInt() == 0 && data[i + 1].toInt() == 0)) i += 2
        i += 2
    } else {
        while (i < data.size && data[i].toInt() != 0) i++
        i += 1
    }
    if (i >= data.size) return null
    return String(data, i, data.size - i, charset).trimEnd('\u0000')
}

/**
 * Synced lyrics from an ID3 SYLT frame: encoding byte, language, time format (2 = ms,
 * 1 = MPEG frames, which we cannot turn into time), content type, a description, then
 * text + 4-byte time pairs. Null when it holds nothing usable.
 */
fun parseSylt(data: ByteArray): Lyrics? {
    if (data.size < 7 || data[4].toInt() != 2) return null
    val enc = data[0].toInt()
    val wide = enc == 1 || enc == 2
    val charset = when (enc) {
        0 -> Charsets.ISO_8859_1
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        else -> Charsets.UTF_8
    }
    /** End of the zero-terminated string starting at [from]: the index of its terminator. */
    fun end(from: Int): Int {
        var i = from
        if (wide) { while (i + 1 < data.size && !(data[i].toInt() == 0 && data[i + 1].toInt() == 0)) i += 2 }
        else while (i < data.size && data[i].toInt() != 0) i++
        return i
    }
    val zero = if (wide) 2 else 1
    var i = end(6) + zero
    val entries = mutableListOf<LyricLine>()
    var bom: ByteArray? = null
    while (i < data.size) {
        val e = end(i)
        if (e + zero + 4 > data.size) break
        var bytes = data.copyOfRange(i, e)
        // UTF-16 with a BOM: later strings may leave it out and rely on the first one
        if (enc == 1) {
            if (bytes.size >= 2 && (bytes[0] == 0xFF.toByte() || bytes[0] == 0xFE.toByte())) bom = bytes.copyOfRange(0, 2)
            else bom?.let { bytes = it + bytes }
        }
        val p = e + zero
        val time = ((data[p].toLong() and 0xFF) shl 24) or ((data[p + 1].toLong() and 0xFF) shl 16) or
            ((data[p + 2].toLong() and 0xFF) shl 8) or (data[p + 3].toLong() and 0xFF)
        entries += LyricLine(time, String(bytes, charset))
        i = p + 4
    }
    // As the ID3 spec has it, an entry is often a syllable, and one starting with a newline
    // begins a line: "Strang" "ers" " in" "\nEx" "chang"... Without any newline, every entry is a line
    val newLine = { s: String -> s.startsWith("\n") || s.startsWith("\r") }
    val lines = if (entries.drop(1).none { newLine(it.text) }) {
        entries.map { LyricLine(it.timeMs, it.text.trim()) }
    } else {
        val joined = mutableListOf<LyricLine>()
        val text = StringBuilder()
        var start: Long? = 0L
        for ((n, entry) in entries.withIndex()) {
            if (n == 0 || newLine(entry.text)) {
                if (n > 0) joined += LyricLine(start, text.toString().trim())
                text.clear()
                start = entry.timeMs
            }
            text.append(entry.text.trimStart('\n', '\r'))
        }
        if (entries.isNotEmpty()) joined += LyricLine(start, text.toString().trim())
        joined
    }
    return if (lines.any { it.text.isNotEmpty() }) Lyrics(lines.sortedBy { it.timeMs }) else null
}
