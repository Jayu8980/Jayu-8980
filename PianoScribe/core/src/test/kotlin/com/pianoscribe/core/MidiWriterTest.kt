package com.pianoscribe.core

import com.pianoscribe.core.midi.MidiReader
import com.pianoscribe.core.midi.MidiWriter
import com.pianoscribe.core.model.Hand
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.TimeGrid
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MidiWriterTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun vlqEncoding() {
        assertArrayEquals(bytes(0x00), MidiWriter.vlq(0))
        assertArrayEquals(bytes(0x40), MidiWriter.vlq(0x40))
        assertArrayEquals(bytes(0x7F), MidiWriter.vlq(0x7F))
        assertArrayEquals(bytes(0x81, 0x00), MidiWriter.vlq(0x80))
        assertArrayEquals(bytes(0xC0, 0x00), MidiWriter.vlq(0x2000))
        assertArrayEquals(bytes(0xFF, 0x7F), MidiWriter.vlq(0x3FFF))
        assertArrayEquals(bytes(0x81, 0x80, 0x00), MidiWriter.vlq(0x4000))
        assertArrayEquals(bytes(0xFF, 0xFF, 0x7F), MidiWriter.vlq(0x1FFFFF))
        assertArrayEquals(bytes(0x81, 0x80, 0x80, 0x00), MidiWriter.vlq(0x200000))
        assertArrayEquals(bytes(0xFF, 0xFF, 0xFF, 0x7F), MidiWriter.vlq(0x0FFFFFFF))
    }

    @Test fun headerAndConductorTrackBytes() {
        val grid = TimeGrid(bpm = 120.0, beatsPerBar = 3, beatUnit = 4)
        val b = MidiWriter.write(emptyList(), grid, "T")
        // MThd, length 6, format 1, 3 tracks, 480 PPQ
        assertArrayEquals(bytes('M'.code, 'T'.code, 'h'.code, 'd'.code, 0, 0, 0, 6, 0, 1, 0, 3, 0x01, 0xE0), b.copyOfRange(0, 14))
        // Conductor track
        val expectedTrack0 = bytes(
            'M'.code, 'T'.code, 'r'.code, 'k'.code, 0, 0, 0, 24,
            0x00, 0xFF, 0x03, 0x01, 'T'.code, // name
            0x00, 0xFF, 0x58, 0x04, 0x03, 0x02, 0x18, 0x08, // 3/4
            0x00, 0xFF, 0x51, 0x03, 0x07, 0xA1, 0x20, // 500000 us per quarter
            0x00, 0xFF, 0x2F, 0x00,
        )
        assertArrayEquals(expectedTrack0, b.copyOfRange(14, 14 + expectedTrack0.size))
    }

    @Test fun noteTrackBytes() {
        val grid = TimeGrid(bpm = 120.0)
        val notes = listOf(Note(1, 60, 0.0, 0.5, 100, 1f, Hand.RIGHT), Note(2, 64, 0.5, 0.5, 90, 1f, Hand.RIGHT))
        val b = MidiWriter.write(notes, grid, "T")
        val rhStart = 14 + 8 + 24
        val expected = bytes(
            'M'.code, 'T'.code, 'r'.code, 'k'.code, 0, 0, 0, 0x27,
            0x00, 0xFF, 0x03, 0x0A, *"Right Hand".map { it.code }.toIntArray(),
            0x00, 0xC0, 0x00,
            0x00, 0x90, 60, 100,
            0x83, 0x60, 0x80, 60, 0x40, // 480 ticks later, note off
            0x00, 0x90, 64, 90,
            0x83, 0x60, 0x80, 64, 0x40,
            0x00, 0xFF, 0x2F, 0x00,
        )
        assertArrayEquals(expected, b.copyOfRange(rhStart, rhStart + expected.size))
    }

    @Test fun roundTripSeparatesHandsAndKeepsTiming() {
        val grid = TimeGrid(bpm = 90.0)
        val notes = listOf(
            Note(1, 72, 0.0, 0.6667, 80, 1f, Hand.RIGHT),
            Note(2, 48, 0.0, 1.3333, 70, 1f, Hand.LEFT),
            Note(3, 72, 0.6667, 0.6667, 80, 1f, Hand.RIGHT), // repeated note: off before on
        )
        val f = MidiReader.read(MidiWriter.write(notes, grid))
        assertEquals(1, f.format); assertEquals(3, f.tracks); assertEquals(480, f.division)
        val rh = f.events.filter { it.track == 1 && it.status and 0xF0 in listOf(0x80, 0x90) }
        val lh = f.events.filter { it.track == 2 && it.status and 0xF0 in listOf(0x80, 0x90) }
        assertEquals(4, rh.size); assertEquals(2, lh.size)
        assertEquals(0x91, lh[0].status) // LH on channel 2
        assertEquals(480L, rh[1].tick); assertEquals(0x80, rh[1].status)
        assertEquals(480L, rh[2].tick); assertEquals(0x90, rh[2].status)
        assertEquals(960L, lh[1].tick)
        val tempo = f.events.first { it.status == 0xFF51 }.data
        assertEquals(666667, ((tempo[0].toInt() and 0xFF) shl 16) or ((tempo[1].toInt() and 0xFF) shl 8) or (tempo[2].toInt() and 0xFF))
        val programs = f.events.filter { it.status and 0xF0 == 0xC0 }
        assertEquals(2, programs.size)
        programs.forEach { assertEquals(0, it.data[0].toInt()) }
    }
}
