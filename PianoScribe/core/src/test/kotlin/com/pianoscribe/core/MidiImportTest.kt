package com.pianoscribe.core

import com.pianoscribe.core.midi.MidiImporter
import com.pianoscribe.core.midi.MidiWriter
import com.pianoscribe.core.model.Hand
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.TimeGrid
import com.pianoscribe.core.perform.PlayedNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.abs

class MidiImportTest {
    @Test fun roundTripsAnExportedFile() {
        val grid = TimeGrid(bpm = 90.0, beatsPerBar = 3, beatUnit = 4)
        val notes = listOf(
            Note(1, 72, 0.0, 0.6, 90, 1f, Hand.RIGHT), Note(2, 76, 0.667, 0.3, 70, 1f, Hand.RIGHT),
            Note(3, 48, 0.0, 1.9, 60, 1f, Hand.LEFT), Note(4, 55, 0.667, 1.2, 60, 1f, Hand.LEFT),
        )
        val imported = MidiImporter.read(MidiWriter.write(notes, grid, "Waltz"))
        assertEquals("Waltz", imported.title)
        assertEquals(90.0, imported.grid.bpm, 0.01)
        assertEquals(3, imported.grid.beatsPerBar)
        assertEquals(notes.size, imported.notes.size)
        for (n in notes) {
            val m = imported.notes.single { it.pitch == n.pitch }
            assertEquals(n.start, m.start, 0.003)
            assertEquals(n.duration, m.duration, 0.003)
            assertEquals(n.velocity, m.velocity)
            assertEquals("hand of ${n.pitch}", n.hand, m.hand)
        }
    }

    @Test fun followsTempoChangesAndSkipsDrums() {
        // One track: 120 bpm for a quarter, then 60 bpm; a note on each side; a drum hit on channel 10.
        val t = ByteArrayOutputStream()
        fun ev(delta: Long, vararg b: Int) { t.write(MidiWriter.vlq(delta)); b.forEach { t.write(it) } }
        ev(0, 0xFF, 0x51, 3, 0x07, 0xA1, 0x20) // 500000 us
        ev(0, 0x90, 60, 80); ev(0, 0x99, 36, 100)
        ev(480, 0x80, 60, 0); ev(0, 0x89, 36, 0)
        ev(0, 0xFF, 0x51, 3, 0x0F, 0x42, 0x40) // 1000000 us
        ev(0, 0x90, 64, 80); ev(480, 0x90, 64, 0) // note-on with velocity 0 ends it
        ev(0, 0xFF, 0x2F, 0)
        val body = t.toByteArray()
        val file = ByteArrayOutputStream().apply {
            write("MThd".toByteArray()); write(byteArrayOf(0, 0, 0, 6, 0, 0, 0, 1, 1, 0xE0.toByte()))
            write("MTrk".toByteArray()); write(byteArrayOf((body.size ushr 24).toByte(), (body.size ushr 16).toByte(), (body.size ushr 8).toByte(), body.size.toByte())); write(body)
        }.toByteArray()
        val imported = MidiImporter.read(file)
        assertEquals(listOf(60, 64), imported.notes.map { it.pitch })
        assertEquals(0.5, imported.notes[1].start, 1e-9)
        assertEquals(1.0, imported.notes[1].duration, 1e-9)
        assertEquals(120.0, imported.grid.bpm, 1e-9)
    }

    @Test fun notAMidiFile() {
        val r = runCatching { MidiImporter.read("RIFF....WAVE".toByteArray() + ByteArray(20)) }
        assertTrue(r.isFailure)
    }
}
