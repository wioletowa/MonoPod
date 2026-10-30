package io.github.whyoolw.monopod.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTest {
    @Test
    fun syncedLrc() {
        val l = parseLyrics(
            "﻿[ar:Кино]\n[ti:Группа крови]\n[00:05.50]Тёплое место\n[00:01.2]Intro\n[01:02.345][00:10]Chorus\n",
        )!!
        assertTrue(l.synced)
        assertEquals(listOf(1200L, 5500L, 10000L, 62345L), l.lines.map { it.timeMs })
        assertEquals("Intro", l.lines[0].text)
        assertEquals("Chorus", l.lines[3].text)
        assertEquals(-1, l.lineAt(500))
        assertEquals(1, l.lineAt(6000))
        assertEquals(3, l.lineAt(100_000))
    }

    @Test
    fun offsetShiftsLinesEarlier() {
        val l = parseLyrics("[offset:+500]\n[00:02.00]a")!!
        assertEquals(1500L, l.lines[0].timeMs)
    }

    @Test
    fun plainText() {
        val l = parseLyrics("\n\nLine one\r\n\r\nLine two\n\n")!!
        assertFalse(l.synced)
        assertEquals(listOf("Line one", "", "Line two"), l.lines.map { it.text })
        assertNull(parseLyrics("  \n "))
    }

    @Test
    fun cp1251Fallback() {
        val cp = "Группа крови".toByteArray(charset("windows-1251"))
        assertEquals("Группа крови", decodeLyricsFile(cp))
        assertEquals("Группа крови", decodeLyricsFile("Группа крови".toByteArray()))
    }

    @Test
    fun usltFrames() {
        val utf8 = byteArrayOf(3) + "rus".toByteArray() + "desc".toByteArray() + byteArrayOf(0) + "Текст".toByteArray()
        assertEquals("Текст", parseUslt(utf8))
        val utf16 = byteArrayOf(1) + "eng".toByteArray() + byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0) +
            "Hi".toByteArray(Charsets.UTF_16)
        assertEquals("Hi", parseUslt(utf16))
    }

    @Test
    fun readsSyltFrames() {
        fun be(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        // UTF-8, "eng", milliseconds, lyrics, empty description, then text + time pairs
        val data = byteArrayOf(3) + "eng".toByteArray() + byteArrayOf(2, 1, 0) +
            "Second".toByteArray() + byteArrayOf(0) + be(5000) +
            "First".toByteArray() + byteArrayOf(0) + be(1200)
        val l = parseSylt(data)!!
        assertTrue(l.synced)
        assertEquals(listOf("First", "Second"), l.lines.map { it.text })
        assertEquals(1200L, l.lines[0].timeMs)
        // Syllables, as in the ID3 spec: a leading newline starts the next line
        val syllables = byteArrayOf(3) + "eng".toByteArray() + byteArrayOf(2, 1, 0) +
            listOf("Strang" to 100, "ers" to 300, " in" to 500, " the night" to 700, "\nEx" to 2000, "chang" to 2300, "ing" to 2600)
                .fold(ByteArray(0)) { acc, (t, ms) -> acc + t.toByteArray() + byteArrayOf(0) + be(ms) }
        val s = parseSylt(syllables)!!
        assertEquals(listOf("Strangers in the night", "Exchanging"), s.lines.map { it.text })
        assertEquals(listOf(100L, 2000L), s.lines.map { it.timeMs })
        // MPEG frame timestamps cannot be turned into time
        assertNull(parseSylt(byteArrayOf(3) + "eng".toByteArray() + byteArrayOf(1, 1, 0) + "x".toByteArray() + byteArrayOf(0) + be(10)))
    }
}
