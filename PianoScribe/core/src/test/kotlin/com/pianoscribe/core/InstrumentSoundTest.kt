package com.pianoscribe.core

import com.pianoscribe.core.audio.Pcm
import com.pianoscribe.core.audio.Yin
import com.pianoscribe.core.perform.Instrument
import com.pianoscribe.core.perform.PlayedNote
import com.pianoscribe.core.synth.InstrumentSynth
import com.pianoscribe.core.synth.NoteSequencer
import com.pianoscribe.core.synth.OfflineRenderer
import com.pianoscribe.core.theory.Pitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.abs

class InstrumentSoundTest {
    private val rate = 44100

    private fun play(instrument: Instrument, key: Int, seconds: Double, expression: Float = 0f): FloatArray {
        val synth = InstrumentSynth(null, rate, instrument).apply { this.expression = expression }
        synth.noteOn(key, 100)
        val out = FloatArray((seconds * rate).toInt())
        synth.render(out, 0, out.size)
        return out
    }

    /** Median YIN pitch over the sustained part, in cents from [key]. */
    private fun centsOff(x: FloatArray, key: Int): Double {
        val yin = Yin(rate, 4096, minFreq = 30.0)
        val readings = ArrayList<Double>()
        var p = (0.25 * rate).toInt()
        while (p + 4096 < x.size) {
            yin.detect(x, p)?.let { readings += Pitch.cents(it.frequency, key) }
            p += 2048
        }
        assertTrue("pitch detected", readings.size >= 3)
        return readings.sorted()[readings.size / 2]
    }

    @Test fun everyBuiltInInstrumentIsInTuneAndAudible() {
        for (inst in Instrument.entries) {
            val key = Performer_center(inst)
            val x = play(inst, key, 1.0)
            assertTrue("$inst is not silent", Pcm.rms(x, 0, x.size) > 0.005)
            assertTrue("$inst has no NaN", x.none { it.isNaN() })
            assertTrue("$inst peak ${x.maxOf { abs(it) }}", x.maxOf { abs(it) } < 1.0f)
            val c = centsOff(x, key)
            assertTrue("$inst at MIDI $key is $c cents off", abs(c) < 10)
        }
    }

    @Test fun pluckedStringsStayInTuneAcrossTheirRange() {
        for (inst in listOf(Instrument.GUITAR, Instrument.HARP)) for (key in listOf(inst.low, (inst.low + inst.high) / 2, inst.high - 5)) {
            val c = centsOff(play(inst, key, 1.0), key)
            assertTrue("$inst MIDI $key is $c cents off", abs(c) < 8)
        }
    }

    @Test fun slurGlidesOnOneVoiceWithoutANewAttack() {
        val synth = InstrumentSynth(null, rate, Instrument.FLUTE)
        val seq = NoteSequencer(synth, listOf(PlayedNote(72, 0.0, 0.52, 100), PlayedNote(76, 0.5, 0.5, 100, slur = true)))
        val out = FloatArray((0.8 * rate).toInt())
        seq.render(out, 0, (0.6 * rate).toInt(), 1.0)
        assertEquals(1, synth.activeVoices)
        seq.render(out, (0.6 * rate).toInt(), out.size - (0.6 * rate).toInt(), 1.0)
        // After the glide the voice sounds the new note.
        val yin = Yin(rate, 4096)
        val r = yin.detect(out, (0.65 * rate).toInt())!!
        assertEquals(0.0, Pitch.cents(r.frequency, 76), 15.0)
    }

    @Test fun vibratoFollowsExpression() {
        fun spread(expression: Float): Double {
            val x = play(Instrument.VIOLIN, 69, 2.0, expression)
            val yin = Yin(rate, 2048)
            val cents = ArrayList<Double>()
            var p = (1.0 * rate).toInt()
            while (p + 2048 < x.size) { yin.detect(x, p)?.let { cents += Pitch.cents(it.frequency, 69) }; p += 512 }
            return cents.max() - cents.min()
        }
        assertTrue("vibrato off is steady", spread(0f) < 6)
        assertTrue("vibrato on moves the pitch", spread(1f) > 15)
    }

    @Test fun wavRendererWritesAValidFile() {
        val notes = listOf(PlayedNote(60, 0.0, 0.5, 90), PlayedNote(64, 0.5, 0.5, 90))
        val bytes = ByteArrayOutputStream().also { OfflineRenderer.render(notes, Instrument.GUITAR, null, 22050, it) }.toByteArray()
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        val frames = OfflineRenderer.frameCount(notes, 22050)
        assertEquals(44 + frames * 2, bytes.size)
        val dataLen = (bytes[40].toInt() and 0xFF) or ((bytes[41].toInt() and 0xFF) shl 8) or ((bytes[42].toInt() and 0xFF) shl 16) or ((bytes[43].toInt() and 0xFF) shl 24)
        assertEquals(frames * 2, dataLen)
        assertTrue(bytes.drop(44).any { it != 0.toByte() })
    }

    private fun Performer_center(inst: Instrument) = if (inst == Instrument.PIANO) 69 else (inst.low + inst.high) / 2
}
