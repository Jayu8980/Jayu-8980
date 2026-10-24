package com.pianoscribe.core

import com.pianoscribe.core.audio.AudioEdit
import com.pianoscribe.core.audio.Fft
import com.pianoscribe.core.audio.Separator
import com.pianoscribe.core.audio.Yin
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.theory.Pitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class SeparateAndEditTest {
    private val sr = 22050

    private fun tone(midi: Int, seconds: Double, amp: Double = 0.3) = FloatArray((seconds * sr).toInt()) { i ->
        val f = Pitch.frequency(midi)
        var s = 0.0
        for (h in 1..5) if (f * h < sr / 2) s += sin(2 * PI * f * h * i / sr) / h
        (amp * s).toFloat()
    }

    private fun energy(x: FloatArray) = x.sumOf { it.toDouble() * it }
    private fun db(a: Double, b: Double) = 10 * log10(a / b)

    /** Energy within half a semitone of the first harmonics of [midi]. */
    private fun bandEnergy(x: FloatArray, midi: Int): Double {
        val m = Fft.magnitudeSpectrum(x, sr, 16384, 16384)
        val f = Pitch.frequency(midi)
        return (1..3).sumOf { h ->
            val c = f * h * 16384 / sr
            ((c * 0.97).toInt()..(c * 1.03).toInt()).sumOf { m[it] * m[it] }
        }
    }

    @Test fun centreShareTellsStereoFromMono() {
        val rnd = Random(1)
        val a = FloatArray(sr * 3) { rnd.nextDouble(-1.0, 1.0).toFloat() }
        val b = FloatArray(sr * 3) { rnd.nextDouble(-1.0, 1.0).toFloat() }
        assertTrue(Separator.centreShare(a, a) > 0.99)
        assertTrue(Separator.centreShare(a, b) < 0.3)
    }

    @Test fun removesAndKeepsTheCentre() {
        // Voice (A4) in the centre; instrument (E3) only on the left and in the right at a different level/phase.
        val voice = tone(69, 3.0)
        val inst = tone(52, 3.0)
        val instR = FloatArray(inst.size) { if (it >= 7) -0.4f * inst[it - 7] else 0f }
        val l = FloatArray(voice.size) { voice[it] + inst[it] }
        val r = FloatArray(voice.size) { voice[it] + instR[it] }
        val mid = FloatArray(l.size) { (l[it] + r[it]) / 2 }
        val removed = Separator.centre(l, r, keepCentre = false)
        val kept = Separator.centre(l, r, keepCentre = true)
        assertTrue("voice removed ${db(bandEnergy(removed, 69), bandEnergy(mid, 69))} dB", db(bandEnergy(removed, 69), bandEnergy(mid, 69)) < -15)
        assertTrue("instrument stays ${db(bandEnergy(removed, 52), bandEnergy(mid, 52))} dB", db(bandEnergy(removed, 52), bandEnergy(mid, 52)) > -3)
        assertTrue("voice kept ${db(bandEnergy(kept, 69), bandEnergy(mid, 69))} dB", db(bandEnergy(kept, 69), bandEnergy(mid, 69)) > -2)
        assertTrue("instrument dropped ${db(bandEnergy(kept, 52), bandEnergy(mid, 52))} dB", db(bandEnergy(kept, 52), bandEnergy(mid, 52)) < -10)
    }

    @Test fun isolatesOnePartByItsNotes() {
        // A3 and D#4: no shared harmonics among the first three, so each can be removed cleanly.
        val a = tone(57, 3.0)
        val e = tone(63, 3.0)
        val mix = FloatArray(a.size) { a[it] + e[it] }
        val na = Note(1, 57, 0.0, 3.0, 90, 1f)
        val ne = Note(2, 63, 0.0, 3.0, 90, 1f)
        fun change(y: FloatArray, midi: Int) = db(bandEnergy(y, midi), bandEnergy(mix, midi))
        val onlyA = Separator.isolate(mix, sr, listOf(na), listOf(ne), keep = true)
        assertTrue("A kept ${change(onlyA, 57)}", change(onlyA, 57) > -3)
        assertTrue("D# dropped ${change(onlyA, 63)}", change(onlyA, 63) < -12)
        val withoutA = Separator.isolate(mix, sr, listOf(na), listOf(ne), keep = false)
        assertTrue("A removed ${change(withoutA, 57)}", change(withoutA, 57) < -12)
        assertTrue("D# kept ${change(withoutA, 63)}", change(withoutA, 63) > -3)
    }

    @Test fun splitsMelodyBassAndAccompaniment() {
        val notes = listOf(
            Note(1, 48, 0.0, 1.0, 80, 1f), Note(2, 55, 0.0, 1.0, 80, 1f), Note(3, 64, 0.0, 1.0, 80, 1f), Note(4, 72, 0.0, 0.5, 80, 1f),
            Note(5, 74, 0.5, 0.5, 80, 1f),
        )
        val p = Separator.parts(notes)
        assertEquals(listOf(72, 74), p.melody.map { it.pitch })
        assertEquals(listOf(48), p.bass.map { it.pitch })
        assertEquals(listOf(55, 64), p.accompaniment.map { it.pitch }.sorted())
    }

    @Test fun cutJoinsWithoutAClick() {
        val x = tone(60, 2.0)
        val y = AudioEdit.cut(x, sr / 2, sr, sr)
        assertEquals(x.size - sr / 2, y.size)
        val maxStep = (1 until y.size).maxOf { abs(y[it] - y[it - 1]) }
        val toneStep = (1 until x.size).maxOf { abs(x[it] - x[it - 1]) }
        assertTrue("step $maxStep vs $toneStep", maxStep < toneStep * 1.5)
    }

    @Test fun cropSilenceFadesAndGain() {
        val x = tone(60, 2.0)
        assertEquals(sr / 2, AudioEdit.crop(x, sr, sr + sr / 2, sr).size)
        val s = AudioEdit.silence(x, sr / 2, sr, sr)
        assertEquals(0.0, energy(s.copyOfRange(sr / 2 + 300, sr - 300)), 1e-9)
        val fi = AudioEdit.withFadeIn(x, 0, sr)
        assertTrue(abs(fi[10]) < 0.01f && energy(fi.copyOfRange(sr, 2 * sr)) == energy(x.copyOfRange(sr, 2 * sr)))
        val g = AudioEdit.gain(x, 0, x.size, -6.0, sr)
        assertEquals(-6.0, db(energy(g), energy(x)), 0.05)
        val n = AudioEdit.normalize(x, 0, x.size, sr)
        assertEquals(10.0.let { Math.pow(it, -1.0 / 20) }, n.maxOf { abs(it) }.toDouble(), 0.01)
    }

    @Test fun tempoChangeKeepsPitch() {
        val x = tone(69, 3.0)
        val y = AudioEdit.changeTempo(x, 1.5, sr)
        assertEquals(x.size / 1.5, y.size.toDouble(), sr * 0.02)
        val f = Yin(sr, 2048).detect(y, y.size / 2)?.frequency ?: 0.0
        assertEquals(440.0, f, 4.0)
    }

    @Test fun pitchShiftKeepsLength() {
        val x = tone(69, 3.0)
        val y = AudioEdit.pitchShift(x, 3.0, sr)
        assertEquals(x.size, y.size)
        val f = Yin(sr, 2048).detect(y, y.size / 2)?.frequency ?: 0.0
        assertEquals(Pitch.frequency(72), f, 6.0)
    }

    @Test fun wavIsValid() {
        val x = tone(60, 0.5)
        val out = ByteArrayOutputStream()
        AudioEdit.writeWav(out, x, sr)
        val b = out.toByteArray()
        assertEquals("RIFF", String(b, 0, 4))
        assertEquals(44 + x.size * 2, b.size)
        val s = (b[44 + 2 * 100].toInt() and 0xFF) or (b[45 + 2 * 100].toInt() shl 8)
        assertEquals((x[100] * 32767).roundToInt().toShort().toInt(), s.toShort().toInt())
        assertTrue(sqrt(energy(x) / x.size) > 0)
    }
}
