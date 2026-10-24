package com.pianoscribe.core

import com.pianoscribe.core.audio.Fft
import com.pianoscribe.core.audio.Pcm
import com.pianoscribe.core.audio.TimeStretch
import com.pianoscribe.core.audio.Yin
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.synth.ArrayByteSource
import com.pianoscribe.core.synth.NoteSequencer
import com.pianoscribe.core.synth.PianoSampleGenerator
import com.pianoscribe.core.synth.InstrumentSynth
import com.pianoscribe.core.synth.Sf2Reader
import com.pianoscribe.core.synth.Sf2Writer
import com.pianoscribe.core.theory.Pitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SynthAndDspTest {
    private fun peakHz(x: FloatArray, rate: Int, from: Int = 0): Double {
        val size = 32768
        val mag = Fft.magnitudeSpectrum(x, from, minOf(size, x.size - from), size)
        val k = (1 until mag.size).maxBy { mag[it] }
        return k.toDouble() * rate / size
    }

    @Test fun sf2RoundTripAndPlayback() {
        val sr = 22050
        val tone = ShortArray(sr) { (12000 * sin(2 * PI * 440.0 * it / sr)).toInt().toShort() }
        val bytes = Sf2Writer.write("Test", listOf(Sf2Writer.Sample("A4", tone, sr, 69, 0, 127)))
        val presets = Sf2Reader.listPresets(ArrayByteSource(bytes))
        assertEquals(1, presets.size); assertEquals("Test", presets[0].name)
        val preset = Sf2Reader.load(ArrayByteSource(bytes))
        assertEquals(1, preset.regions.size)
        val r = preset.regions[0]
        assertEquals(69, r.rootKey); assertEquals(sr, r.sampleRate); assertEquals(tone.size, r.sample.size)
        assertEquals(0.35, r.release, 0.01)

        val synth = InstrumentSynth(preset, 44100)
        synth.noteOn(81, 100) // an octave above the sample root
        val out = FloatArray(20000)
        synth.render(out, 0, out.size)
        assertEquals(880.0, peakHz(out, 44100), 3.0)
    }

    @Test fun bundledFontCoversPiano() {
        val bytes = PianoSampleGenerator.buildSoundFont()
        val preset = Sf2Reader.load(ArrayByteSource(bytes))
        for (k in 21..108) assertTrue("key $k", preset.regions.any { it.matches(k, 100) })
        val synth = InstrumentSynth(preset, 44100)
        synth.noteOn(57, 110)
        val out = FloatArray(44100)
        synth.render(out, 0, out.size)
        assertEquals(Pitch.frequency(57), peakHz(out, 44100, 2000), 3.0)
        assertTrue(Pcm.rms(out) > 0.01)
    }

    @Test fun fallbackSynthAndSequencer() {
        val synth = InstrumentSynth(null, 22050)
        val seq = NoteSequencer.forNotes(synth, listOf(Note(1, 69, 0.1, 0.5, 100, 1f)))
        val out = FloatArray(22050)
        seq.render(out, 0, out.size, speed = 1.0)
        assertTrue(Pcm.rms(out, 0, 2000) < 1e-6) // silent before the note
        assertEquals(440.0, peakHz(out, 22050, 2205), 3.0)
        // At half speed the same second of output covers only half a second of song.
        val seq2 = NoteSequencer.forNotes(InstrumentSynth(null, 22050), emptyList())
        seq2.render(FloatArray(22050), 0, 22050, speed = 0.5)
        assertEquals(0.5, seq2.songTime, 1e-6)
    }

    @Test fun timeStretchKeepsPitchAndScalesLength() {
        val sr = 22050
        val x = FloatArray(sr * 2) { (0.5 * sin(2 * PI * 330.0 * it / sr)).toFloat() }
        for (speed in listOf(0.5, 0.75, 1.5, 2.0)) {
            val ts = TimeStretch(sr, speed)
            val y = ts.process(x)
            assertEquals("speed $speed", x.size / speed, y.size.toDouble(), x.size / speed * 0.06)
            assertEquals(330.0, peakHz(y, sr, 0), 3.0)
        }
    }

    @Test fun yinDetectsPitch() {
        val sr = 22050
        val yin = Yin(sr, 2048)
        for (midi in listOf(40, 57, 69, 84)) {
            val f = Pitch.frequency(midi)
            val x = FloatArray(2048) { (0.4 * sin(2 * PI * f * it / sr) + 0.2 * sin(4 * PI * f * it / sr)).toFloat() }
            val r = yin.detect(x)!!
            assertEquals("midi $midi", 0.0, Pitch.cents(r.frequency, midi), 5.0)
        }
        assertEquals(null, yin.detect(FloatArray(2048)))
    }
}
