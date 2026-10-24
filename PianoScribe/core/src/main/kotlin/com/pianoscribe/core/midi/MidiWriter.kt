package com.pianoscribe.core.midi

import com.pianoscribe.core.model.Hand
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.TimeGrid
import java.io.ByteArrayOutputStream
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Standard MIDI File writer: Type 1 with a conductor track (tempo, time signature) and one track
 * per hand (Right Hand on channel 1, Left Hand on channel 2). Both use General MIDI [program]
 * (0 = Acoustic Grand Piano).
 */
object MidiWriter {
    const val PPQ = 480

    fun write(notes: List<Note>, grid: TimeGrid, title: String = "PianoScribe", program: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("MThd".toByteArray(Charsets.US_ASCII))
        out.write(int32(6))
        out.write(int16(1)) // format 1
        out.write(int16(3)) // tracks
        out.write(int16(PPQ))
        writeTrack(out, conductorTrack(grid, title))
        writeTrack(out, noteTrack(notes.filter { it.hand == Hand.RIGHT }, grid, "Right Hand", 0, program))
        writeTrack(out, noteTrack(notes.filter { it.hand == Hand.LEFT }, grid, "Left Hand", 1, program))
        return out.toByteArray()
    }

    /** Seconds -> ticks. Tick 0 is the first downbeat (grid offset) so bars line up in a DAW. */
    fun ticks(sec: Double, grid: TimeGrid): Long = (grid.timeToBeats(sec) * PPQ).roundToLong().coerceAtLeast(0)

    private fun conductorTrack(grid: TimeGrid, title: String): ByteArray {
        val t = ByteArrayOutputStream()
        // Track name
        t.write(vlq(0)); meta(t, 0x03, title.toByteArray(Charsets.UTF_8))
        // Time signature: nn dd(2^dd) cc(clocks per click) bb(32nds per quarter)
        val dd = log2(grid.beatUnit.toDouble()).roundToInt()
        t.write(vlq(0)); meta(t, 0x58, byteArrayOf(grid.beatsPerBar.toByte(), dd.toByte(), 24, 8))
        // Tempo in microseconds per quarter note.
        val usPerQuarter = (60_000_000.0 / grid.bpm * (grid.beatUnit / 4.0)).roundToInt().coerceIn(1, 0xFFFFFF)
        t.write(vlq(0)); meta(t, 0x51, byteArrayOf((usPerQuarter shr 16).toByte(), (usPerQuarter shr 8).toByte(), usPerQuarter.toByte()))
        t.write(vlq(0)); meta(t, 0x2F, ByteArray(0))
        return t.toByteArray()
    }

    private class Ev(val tick: Long, val on: Boolean, val pitch: Int, val vel: Int)

    private fun noteTrack(notes: List<Note>, grid: TimeGrid, name: String, channel: Int, program: Int): ByteArray {
        val t = ByteArrayOutputStream()
        t.write(vlq(0)); meta(t, 0x03, name.toByteArray(Charsets.UTF_8))
        t.write(vlq(0)); t.write(0xC0 or channel); t.write(program.coerceIn(0, 127))
        val evs = ArrayList<Ev>(notes.size * 2)
        for (n in notes) {
            val on = ticks(n.start, grid)
            val off = maxOf(on + 1, ticks(n.end, grid))
            val p = n.pitch.coerceIn(0, 127)
            evs += Ev(on, true, p, n.velocity.coerceIn(1, 127))
            evs += Ev(off, false, p, 0)
        }
        // Note-offs before note-ons at the same tick so repeated notes retrigger cleanly.
        evs.sortWith(compareBy<Ev>({ it.tick }, { if (it.on) 1 else 0 }, { it.pitch }))
        var last = 0L
        for (e in evs) {
            t.write(vlq(e.tick - last))
            last = e.tick
            if (e.on) { t.write(0x90 or channel); t.write(e.pitch); t.write(e.vel) }
            else { t.write(0x80 or channel); t.write(e.pitch); t.write(0x40) }
        }
        t.write(vlq(0)); meta(t, 0x2F, ByteArray(0))
        return t.toByteArray()
    }

    private fun meta(t: ByteArrayOutputStream, type: Int, data: ByteArray) {
        t.write(0xFF); t.write(type); t.write(vlq(data.size.toLong())); t.write(data)
    }

    private fun writeTrack(out: ByteArrayOutputStream, body: ByteArray) {
        out.write("MTrk".toByteArray(Charsets.US_ASCII))
        out.write(int32(body.size))
        out.write(body)
    }

    /** MIDI variable-length quantity. */
    fun vlq(value: Long): ByteArray {
        require(value in 0..0x0FFFFFFF) { "VLQ out of range: $value" }
        var v = value
        val bytes = ArrayList<Byte>()
        bytes += (v and 0x7F).toByte()
        v = v shr 7
        while (v > 0) {
            bytes += ((v and 0x7F) or 0x80).toByte()
            v = v shr 7
        }
        return bytes.reversed().toByteArray()
    }

    private fun int32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun int16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
}

/** Minimal SMF reader, used by tests and to re-import exported files. */
object MidiReader {
    data class Event(val track: Int, val tick: Long, val status: Int, val data: ByteArray)
    data class File(val format: Int, val tracks: Int, val division: Int, val events: List<Event>)

    fun read(bytes: ByteArray): File {
        var p = 0
        fun u8() = bytes[p++].toInt() and 0xFF
        fun u16() = (u8() shl 8) or u8()
        fun u32() = (u16() shl 16) or u16()
        fun tag(): String { val s = String(bytes, p, 4, Charsets.US_ASCII); p += 4; return s }
        fun vlq(): Long { var v = 0L; while (true) { val b = u8(); v = (v shl 7) or (b and 0x7F).toLong(); if (b and 0x80 == 0) return v } }
        require(tag() == "MThd") { "Not a MIDI file" }
        require(u32() == 6)
        val format = u16(); val ntracks = u16(); val division = u16()
        val events = ArrayList<Event>()
        for (tr in 0 until ntracks) {
            require(tag() == "MTrk")
            val len = u32()
            val end = p + len
            var tick = 0L
            var running = 0
            while (p < end) {
                tick += vlq()
                var status = bytes[p].toInt() and 0xFF
                if (status < 0x80) status = running else p++
                when {
                    status == 0xFF -> {
                        val type = u8(); val l = vlq().toInt()
                        events += Event(tr, tick, 0xFF00 or type, bytes.copyOfRange(p, p + l)); p += l
                    }
                    status == 0xF0 || status == 0xF7 -> { val l = vlq().toInt(); p += l }
                    else -> {
                        running = status
                        val n = if (status and 0xF0 == 0xC0 || status and 0xF0 == 0xD0) 1 else 2
                        events += Event(tr, tick, status, bytes.copyOfRange(p, p + n)); p += n
                    }
                }
            }
            p = end
        }
        return File(format, ntracks, division, events)
    }
}
