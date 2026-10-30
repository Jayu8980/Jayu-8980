package com.pianoscribe.core.midi

import com.pianoscribe.core.model.Hand
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.NoteIdGenerator
import com.pianoscribe.core.model.TimeGrid

/**
 * Opens a Standard MIDI File as notes for a project (MIDI to sheet music): exact times from the
 * file's tempo map, the time signature and title, drums (channel 10) left out. A piano file with
 * two note tracks keeps them as right and left hand (the higher one is the right hand); otherwise
 * the hands are split at middle C.
 */
object MidiImporter {
    class Imported(val notes: List<Note>, val grid: TimeGrid, val title: String?, val durationSec: Double)

    fun read(bytes: ByteArray): Imported {
        val f = MidiReader.read(bytes)
        require(f.division in 1..0x7FFF) { "This MIDI file uses SMPTE time, which isn't supported" }

        // Tempo map: (tick, microseconds per quarter), integrated to seconds.
        val tempos = f.events.filter { it.status == 0xFF51 && it.data.size >= 3 }
            .map { it.tick to (((it.data[0].toInt() and 0xFF) shl 16) or ((it.data[1].toInt() and 0xFF) shl 8) or (it.data[2].toInt() and 0xFF)) }
            .sortedBy { it.first }
            .let { if (it.isEmpty() || it.first().first > 0) listOf(0L to 500_000) + it else it }
        val segStartSec = DoubleArray(tempos.size)
        for (i in 1 until tempos.size) {
            segStartSec[i] = segStartSec[i - 1] + (tempos[i].first - tempos[i - 1].first) * tempos[i - 1].second / 1e6 / f.division
        }
        fun seconds(tick: Long): Double {
            var i = tempos.size - 1
            while (i > 0 && tempos[i].first > tick) i--
            return segStartSec[i] + (tick - tempos[i].first) * tempos[i].second / 1e6 / f.division
        }

        val timeSig = f.events.firstOrNull { it.status == 0xFF58 && it.data.size >= 2 }
        val beatsPerBar = timeSig?.let { it.data[0].toInt() and 0xFF }?.takeIf { it in 1..32 } ?: 4
        val beatUnit = timeSig?.let { 1 shl (it.data[1].toInt() and 0xFF) }?.takeIf { it in 1..32 } ?: 4
        val usPerQuarter = tempos.first().second
        val grid = TimeGrid(bpm = 60_000_000.0 / usPerQuarter * beatUnit / 4.0, beatsPerBar = beatsPerBar, beatUnit = beatUnit, offset = 0.0)
        val title = f.events.firstOrNull { it.status == 0xFF03 && it.track == 0 }?.data?.toString(Charsets.UTF_8)?.trim()?.takeIf { it.isNotEmpty() }

        // Notes: note-on until the matching note-off (or note-on with velocity 0), per track, channel and key.
        class Raw(val track: Int, val pitch: Int, val on: Long, val off: Long, val velocity: Int)
        val open = HashMap<Triple<Int, Int, Int>, ArrayDeque<Pair<Long, Int>>>()
        val raw = ArrayList<Raw>()
        for (e in f.events.sortedWith(compareBy({ it.tick }, { if (it.status and 0xF0 == 0x90 && (it.data.getOrNull(1)?.toInt() ?: 0) > 0) 1 else 0 }))) {
            val type = e.status and 0xF0
            val ch = e.status and 0x0F
            if (e.status > 0xFF || (type != 0x80 && type != 0x90) || ch == 9 || e.data.size < 2) continue
            val pitch = e.data[0].toInt() and 0x7F
            val vel = e.data[1].toInt() and 0x7F
            val k = Triple(e.track, ch, pitch)
            if (type == 0x90 && vel > 0) open.getOrPut(k) { ArrayDeque() }.addLast(e.tick to vel)
            else open[k]?.removeFirstOrNull()?.let { (on, v) -> raw += Raw(e.track, pitch, on, e.tick, v) }
        }
        require(raw.isNotEmpty()) { "This MIDI file has no notes" }

        val tracks = raw.groupBy { it.track }
        val handOf: (Raw) -> Hand = if (tracks.size == 2) {
            val right = tracks.maxBy { (_, rs) -> rs.map { it.pitch }.average() }.key
            { r -> if (r.track == right) Hand.RIGHT else Hand.LEFT }
        } else { r -> if (r.pitch >= Note.MIDDLE_C) Hand.RIGHT else Hand.LEFT }

        val ids = NoteIdGenerator()
        // Keys outside the 88 of a piano are left out rather than moved.
        val notes = raw.filter { it.pitch in Note.MIN_PIANO..Note.MAX_PIANO }.map { r ->
            val start = seconds(r.on)
            Note(ids.next(), r.pitch, start, maxOf(0.02, seconds(r.off) - start), r.velocity.coerceIn(1, 127), 1f, handOf(r), verified = true)
        }.sortedWith(compareBy({ it.start }, { it.pitch }))
        require(notes.isNotEmpty()) { "This MIDI file has no notes in the piano's range" }
        return Imported(notes, grid, title, notes.maxOf { it.end })
    }
}
