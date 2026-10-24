package com.pianoscribe.core

import com.pianoscribe.core.audio.Fft
import com.pianoscribe.core.audio.Hpss
import com.pianoscribe.core.audio.Pcm
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.synth.PianoSampleGenerator
import com.pianoscribe.core.transcribe.SongCleanup
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

class SongModeTest {
    private fun n(id: Long, p: Int, s: Double, d: Double, v: Int = 90) = Note(id, p, s, d, v, 0.8f)

    @Test fun slidingMedianMatchesNaive() {
        val rnd = Random(7)
        val x = FloatArray(200) { rnd.nextFloat() }
        val out = FloatArray(200)
        Hpss.slidingMedian(x, x.size, 9, out)
        for (t in x.indices) {
            val w = x.slice(maxOf(0, t - 4)..minOf(x.size - 1, t + 4)).sorted()
            val med = if (w.size % 2 == 1) w[w.size / 2] else (w[w.size / 2 - 1] + w[w.size / 2]) / 2
            assertEquals("t=$t", med, out[t], 1e-6f)
        }
    }

    @Test fun hpssKeepsTonesAndRemovesClicks() {
        val sr = 22050
        val len = sr * 2
        val tone = FloatArray(len) { (0.3 * sin(2 * PI * 440.0 * it / sr)).toFloat() }
        val clicks = FloatArray(len)
        val rnd = Random(3)
        // Drum-like noise bursts every 250 ms.
        var t = 1000
        while (t < len - 600) { for (i in 0 until 300) clicks[t + i] = ((rnd.nextFloat() * 2 - 1) * 0.8f * (1 - i / 300f)); t += sr / 4 }
        val mix = FloatArray(len) { tone[it] + clicks[it] }
        val r = Hpss().separate(mix)
        assertEquals(len, r.harmonic.size)
        // Tone kept: 440 Hz is still the strongest component and keeps most of its level.
        val size = 16384
        val mag = Fft.magnitudeSpectrum(r.harmonic, sr / 2, size, size)
        val peak = (1 until mag.size).maxBy { mag[it] } * sr.toDouble() / size
        assertEquals(440.0, peak, 3.0)
        val toneIn = Pcm.rms(tone, sr / 4, len - sr / 4)
        val residual = FloatArray(len) { r.harmonic[it] - tone[it] }
        val noiseBefore = Pcm.rms(clicks, sr / 4, len - sr / 4)
        val noiseAfter = Pcm.rms(residual, sr / 4, len - sr / 4)
        assertTrue("clicks reduced: before $noiseBefore after $noiseAfter", Pcm.toDb(noiseAfter) < Pcm.toDb(noiseBefore) - 6)
        assertTrue("tone level kept", Pcm.rms(r.harmonic, sr / 4, len - sr / 4) > toneIn * 0.7)
        // A pure tone is classed as harmonic, and adding clicks raises the percussive share.
        val pure = Hpss().separate(tone)
        assertTrue("pure tone ratio ${pure.percussiveRatio}", pure.percussiveRatio < 0.05)
        assertTrue(r.percussiveRatio > pure.percussiveRatio + 0.03)
    }

    private val sr = 22050

    private fun pianoPiece(seconds: Double, seed: Int): FloatArray {
        val x = FloatArray((seconds * sr).toInt())
        val rnd = Random(seed)
        var t = 0.0
        while (t < seconds - 1) {
            for (p in listOf(48, 52, 55).map { it + rnd.nextInt(-2, 3) } + (72 + rnd.nextInt(0, 7))) {
                val tone = PianoSampleGenerator.tone(p, sr, 1.5)
                val i0 = (t * sr).toInt()
                for (i in tone.indices) if (i0 + i < x.size) x[i0 + i] += tone[i] / 32768f * 0.25f
            }
            t += 0.5
        }
        return x
    }

    /** Kick on beats 1 and 3, noise snare on 2 and 4, high-passed hi-hat on every eighth. */
    private fun drumKit(seconds: Double): FloatArray {
        val x = FloatArray((seconds * sr).toInt())
        val rnd = Random(1)
        val beat = 0.5
        var b = 0
        while (b * beat < seconds - 0.3) {
            val i0 = (b * beat * sr).toInt()
            if (b % 2 == 0) {
                for (i in 0 until 4000) { val tt = i.toDouble() / sr; x[i0 + i] += (0.6 * sin(2 * PI * (50 + 80 * exp(-tt * 30)) * tt) * exp(-tt * 12)).toFloat() }
            } else {
                for (i in 0 until 3300) { val tt = i.toDouble() / sr; x[i0 + i] += ((rnd.nextDouble() * 2 - 1) * 0.4 * exp(-tt * 25)).toFloat() }
            }
            for (h in 0 until 2) {
                val j0 = i0 + (h * beat / 2 * sr).toInt()
                var prev = 0.0
                for (i in 0 until 1100) { val v = rnd.nextDouble() * 2 - 1; if (j0 + i < x.size) x[j0 + i] += ((v - prev) * 0.15 * exp(-i / 300.0)).toFloat(); prev = v }
            }
            b++
        }
        return x
    }

    @Test fun autoModeSeparatesSoloPianoFromSongsWithDrums() {
        for (seed in 1..3) {
            val piano = pianoPiece(8.0, seed)
            val drums = drumKit(8.0)
            val solo = Hpss().separate(piano).percussiveRatio
            val song = Hpss().separate(FloatArray(piano.size) { piano[it] + drums[it] }).percussiveRatio
            assertTrue("solo piano $solo", solo < SongCleanup.PERCUSSIVE_SONG_THRESHOLD)
            assertTrue("piano + drums $song", song > SongCleanup.PERCUSSIVE_SONG_THRESHOLD)
        }
    }

    @Test fun hpssOfSilenceIsSilence() {
        val r = Hpss().separate(FloatArray(5000))
        assertArrayEquals(FloatArray(5000), r.harmonic, 0f)
    }

    @Test fun mergesFragmentedNotes() {
        val out = SongCleanup.mergeFragments(listOf(n(1, 60, 0.0, 0.3), n(2, 60, 0.33, 0.3), n(3, 60, 1.0, 0.2), n(4, 62, 0.31, 0.2)), 0.06)
        val c4 = out.filter { it.pitch == 60 }.sortedBy { it.start }
        assertEquals(2, c4.size)
        assertEquals(0.63, c4[0].duration, 1e-9)
        assertEquals(1, out.count { it.pitch == 62 })
    }

    @Test fun removesOvertoneGhosts() {
        val notes = listOf(
            n(1, 48, 0.0, 1.0, 110), // C3 real note
            n(2, 60, 0.01, 0.9, 60), // octave ghost
            n(3, 67, 0.02, 0.8, 50), // octave + fifth ghost
            n(4, 64, 0.0, 1.0, 70),  // E4: a real chord tone, not an overtone interval
            n(5, 72, 0.5, 0.4, 100), // later note: not a ghost of anything
        )
        val ids = SongCleanup.removeOvertoneGhosts(notes).map { it.id }.toSet()
        assertEquals(setOf(1L, 4L, 5L), ids)
        // A louder upper note, or one about as loud as the bass, is kept: it is the melody, not an overtone.
        val keep = SongCleanup.removeOvertoneGhosts(listOf(n(1, 48, 0.0, 1.0, 60), n(2, 60, 0.0, 1.0, 110)))
        assertEquals(2, keep.size)
        val melody = SongCleanup.removeOvertoneGhosts(listOf(n(1, 48, 0.0, 2.0, 90), n(2, 72, 0.0, 0.5, 80)))
        assertEquals(2, melody.size)
    }

    @Test fun removesQuietBackgroundNotes() {
        val notes = listOf(n(1, 60, 0.0, 0.5, 100), n(2, 64, 0.1, 0.5, 20), n(3, 67, 5.0, 0.5, 20))
        val ids = SongCleanup.removeQuietNotes(notes, 0.55f, 1.0).map { it.id }.toSet()
        // Note 3 is quiet but alone, so it stays.
        assertEquals(setOf(1L, 3L), ids)
    }

    @Test fun limitsPolyphonyPerHand() {
        val chord = (0 until 7).map { n(it.toLong() + 1, 60 + it * 2, 0.0, 1.0, 50 + it * 10) }
        val kept = SongCleanup.limitPolyphony(chord, 60, 4, 3)
        assertEquals(4, kept.size)
        // The loudest four are kept.
        assertEquals(setOf(4L, 5L, 6L, 7L), kept.map { it.id }.toSet())
        // Non-overlapping notes are never limited.
        val melody = (0 until 10).map { n(it.toLong(), 72, it * 1.0, 0.9) }
        assertEquals(10, SongCleanup.limitPolyphony(melody, 60, 1, 1).size)
    }

    @Test fun tooFewNotesHeuristic() {
        assertTrue(SongCleanup.tooFewNotes(0, 10.0))
        assertTrue(SongCleanup.tooFewNotes(20, 120.0))
        assertTrue(!SongCleanup.tooFewNotes(100, 120.0))
    }
}
