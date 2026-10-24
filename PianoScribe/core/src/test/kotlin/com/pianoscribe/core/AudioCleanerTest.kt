package com.pianoscribe.core

import com.pianoscribe.core.audio.AudioCleaner
import com.pianoscribe.core.audio.Biquad
import com.pianoscribe.core.audio.CleanOptions
import com.pianoscribe.core.audio.Fft
import com.pianoscribe.core.audio.Stft
import com.pianoscribe.core.theory.Pitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class AudioCleanerTest {
    private val sr = 22050

    /** Piano-like notes: harmonic tones with a struck decay, released after a while. */
    private fun notes(seconds: Double, seed: Int = 1, gap: Double = 0.5): FloatArray {
        val rnd = Random(seed)
        val x = FloatArray((seconds * sr).toInt())
        var t = 0.1
        while (t < seconds - 1) {
            val pitch = 48 + rnd.nextInt(30)
            val f = Pitch.frequency(pitch)
            val hold = 0.2 + rnd.nextDouble() * 0.5
            val s0 = (t * sr).toInt()
            for (i in 0 until (sr * (hold + 0.3)).toInt()) {
                val idx = s0 + i
                if (idx >= x.size) break
                val tt = i.toDouble() / sr
                val env = exp(-tt * 2) * (if (tt > hold) exp(-(tt - hold) * 6.9 / 0.15) else 1.0)
                var s = 0.0
                for (h in 1..6) if (f * h < sr / 2) s += sin(2 * PI * f * h * tt) / h
                x[idx] += (0.2 * env * s).toFloat()
            }
            t += hold + gap * (0.6 + 0.4 * rnd.nextDouble())
        }
        return x
    }

    private fun rms(x: FloatArray) = sqrt(x.sumOf { it.toDouble() * it } / x.size)

    private fun withHum(x: FloatArray, f0: Double, level: Double) = FloatArray(x.size) { i ->
        var s = 0.0
        for (h in 1..6) s += sin(2 * PI * f0 * h * i / sr + h) / h
        x[i] + (level * s).toFloat()
    }

    @Test fun stftResynthesisIsExact() {
        val x = notes(3.0)
        val y = Stft(1024, 256).process(x) { _, _, _ -> }
        var err = 0.0
        for (i in x.indices) err = maxOf(err, abs((x[i] - y[i]).toDouble()))
        assertTrue("max error $err", err < 1e-5)
    }

    @Test fun filtersHaveTheIntendedResponse() {
        val notch = Biquad.notch(50.0, 3.0, sr)
        assertTrue(notch.gainAt(50.0, sr) < 0.01)
        assertEquals(1.0, notch.gainAt(65.0, sr), 0.05)
        val hp = Biquad.butterworthHighPass4(22.0, sr)
        val g = { f: Double -> hp.fold(1.0) { a, b -> a * b.gainAt(f, sr) } }
        assertTrue("A0 kept: ${g(27.5)}", g(27.5) > 0.89)
        assertTrue("rumble cut: ${g(10.0)}", g(10.0) < 0.05)
        assertEquals(10.0.pow(6.0 / 20), Biquad.peaking(500.0, 1.0, 6.0, sr).gainAt(500.0, sr), 0.01)
    }

    @Test fun cleanRecordingNeedsNothing() {
        val r = AudioCleaner.analyze(notes(12.0), sr)
        assertFalse("no hum in clean notes: ${r.humHz} ${r.humHarmonics}", r.hasHum)
        assertFalse("snr ${r.snrDb}", r.hasNoise)
        assertFalse(r.isClipped)
        assertTrue(r.suggested().isEmpty)
    }

    @Test fun findsAndRemovesMainsHum() {
        val clean = notes(12.0)
        for (f0 in listOf(50, 60)) {
            val x = withHum(clean, f0.toDouble(), 0.01)
            val r = AudioCleaner.analyze(x, sr)
            assertEquals(f0, r.humHz)
            assertTrue(r.humHarmonics.containsAll(listOf(1, 2, 3)))
            assertTrue(r.suggested().removeHum)
            val y = AudioCleaner.clean(x, sr, CleanOptions(removeHum = true), r)
            // Hum left = energy at the hum frequencies, compared with the same bins of the clean notes.
            fun humEnergy(z: FloatArray): Double {
                val m = Fft.magnitudeSpectrum(z, sr, 16384, 16384)
                return (1..3).sumOf { h -> val b = (f0 * h * 16384.0 / sr).roundToInt(); (b - 1..b + 1).sumOf { m[it] * m[it] } }
            }
            val removed = 10 * log10(humEnergy(x) / humEnergy(y))
            assertTrue("hum $f0 reduced by $removed dB", removed > 20)
            assertEquals("notes keep their level", rms(clean), rms(y), rms(clean) * 0.1)
        }
    }

    @Test fun measuresNoiseLevel() {
        val clean = notes(12.0)
        val rnd = Random(3)
        val noise = FloatArray(clean.size) { rnd.nextDouble(-1.0, 1.0).toFloat() }
        val g = (rms(clean) / rms(noise) / 10.0.pow(20.0 / 20)).toFloat()
        val r = AudioCleaner.analyze(FloatArray(clean.size) { clean[it] + noise[it] * g }, sr)
        assertTrue("snr ${r.snrDb}", r.snrDb in 14.0..28.0)
        assertTrue(r.hasNoise)
        assertTrue("noise reduction only for listening", r.suggested().noiseReduction == 0f && r.suggestedForListening().noiseReduction > 0f)
    }

    @Test fun noiseReductionLowersTheNoiseAndKeepsTheNotes() {
        val clean = notes(10.0)
        val rnd = Random(4)
        val noise = FloatArray(clean.size) { (rnd.nextDouble(-1.0, 1.0) * 0.02).toFloat() }
        val x = FloatArray(clean.size) { clean[it] + noise[it] }
        val y = AudioCleaner.clean(x, sr, CleanOptions(noiseReduction = 0.8f))
        val errBefore = rms(FloatArray(x.size) { x[it] - clean[it] })
        val errAfter = rms(FloatArray(x.size) { y[it] - clean[it] })
        assertTrue("error ${20 * log10(errAfter / errBefore)} dB", errAfter < errBefore * 0.7)
        assertEquals(rms(clean), rms(y), rms(clean) * 0.25)
    }

    @Test fun estimatesRoomDecayTime() {
        val dry = notes(20.0, seed = 2, gap = 1.0)
        val dryRt = AudioCleaner.analyze(dry, sr).rt60Sec
        assertTrue("dry: $dryRt", dryRt == null || dryRt < AudioCleaner.ECHO_RT60_SEC)
        val rt = 1.0
        val rnd = Random(9)
        val ir = FloatArray((rt * 1.3 * sr).toInt()) { i -> (rnd.nextDouble(-1.0, 1.0) * exp(-6.9 * i / sr / rt) * 0.3).toFloat() }
        ir[0] = 1f
        val wet = convolve(dry, ir)
        val est = AudioCleaner.analyze(wet, sr).rt60Sec
        assertNotNull(est)
        println("RT60 dry $dryRt, wet $est for $rt")
        assertTrue("estimate $est for $rt", est!! in 0.6..1.6)
    }

    @Test fun echoReductionShortensTailsAndKeepsAttacks() {
        // Short clicks in a 0.8 s room: the energy 200-800 ms after each click is the echo.
        val x = FloatArray(sr * 8)
        val starts = (0 until 7).map { ((it + 0.3) * sr).toInt() }
        for (s in starts) for (i in 0 until 200) x[s + i] = (sin(2 * PI * 440 * i / sr) * exp(-i / 40.0)).toFloat()
        val rnd = Random(5)
        val ir = FloatArray((0.8 * 1.3 * sr).toInt()) { i -> (rnd.nextDouble(-1.0, 1.0) * exp(-6.9 * i / sr / 0.8) * 0.05).toFloat() }
        ir[0] = 1f
        val wet = convolve(x, ir)
        val dry = AudioCleaner.clean(wet, sr, CleanOptions(echoReduction = 1f, echoTimeSec = 0.8))
        fun energy(y: FloatArray, from: Double, to: Double) = starts.sumOf { s -> ((s + from * sr).toInt() until (s + to * sr).toInt()).sumOf { y[it].toDouble() * y[it] } }
        val tail = 10 * log10(energy(dry, 0.2, 0.8) / energy(wet, 0.2, 0.8))
        assertTrue("tail $tail dB", tail < -6)
        val attack = 10 * log10(energy(dry, 0.0, 0.02) / energy(wet, 0.0, 0.02))
        assertTrue("attack $attack dB", attack > -1.5)
    }

    @Test fun normaliseBringsQuietAudioUp() {
        val x = notes(4.0).map { it * 0.3f }.toFloatArray()
        val r = AudioCleaner.analyze(x, sr)
        assertTrue(r.isQuiet && r.suggested().normalize)
        val y = AudioCleaner.clean(x, sr, CleanOptions(normalize = true), r)
        assertEquals(10.0.pow(-1.0 / 20), y.maxOf { abs(it) }.toDouble(), 0.01)
    }

    private fun convolve(x: FloatArray, h: FloatArray): FloatArray {
        val size = Fft.nextPow2(x.size + h.size)
        val ar = DoubleArray(size); val ai = DoubleArray(size)
        val br = DoubleArray(size); val bi = DoubleArray(size)
        for (i in x.indices) ar[i] = x[i].toDouble()
        for (i in h.indices) br[i] = h[i].toDouble()
        Fft.fft(ar, ai); Fft.fft(br, bi)
        for (i in 0 until size) {
            val r = ar[i] * br[i] - ai[i] * bi[i]
            val im = ar[i] * bi[i] + ai[i] * br[i]
            ar[i] = r; ai[i] = -im
        }
        Fft.fft(ar, ai)
        return FloatArray(x.size) { (ar[it] / size).toFloat() }
    }
}
