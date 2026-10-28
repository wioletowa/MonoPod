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
}
