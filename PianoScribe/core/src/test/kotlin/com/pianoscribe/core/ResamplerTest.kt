package com.pianoscribe.core

import com.pianoscribe.core.audio.Fft
import com.pianoscribe.core.audio.Pcm
import com.pianoscribe.core.audio.StreamingResampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class ResamplerTest {
    private fun sine(freq: Double, rate: Int, seconds: Double) = FloatArray((rate * seconds).toInt()) { (0.5 * sin(2 * PI * freq * it / rate)).toFloat() }

    private fun peakHz(x: FloatArray, rate: Int): Double {
        val size = 16384
        val mag = Fft.magnitudeSpectrum(x, x.size / 4, size, size)
        val k = mag.indices.maxBy { mag[it] }
        return k.toDouble() * rate / size
    }

    @Test fun lengthMatchesRatio() {
        for ((inRate, outRate) in listOf(44100 to 22050, 48000 to 22050, 16000 to 22050, 22050 to 22050, 8000 to 22050)) {
            val x = sine(440.0, inRate, 1.37)
            val y = StreamingResampler.resample(x, inRate, outRate)
            assertEquals("$inRate->$outRate", Math.round(x.size.toDouble() * outRate / inRate).toDouble(), y.size.toDouble(), 0.0)
        }
    }

    @Test fun preservesFrequencyAndLevel() {
        val x = sine(1000.0, 48000, 1.0)
        val y = StreamingResampler.resample(x, 48000, 22050)
        assertEquals(1000.0, peakHz(y, 22050), 2.0)
        val rmsIn = Pcm.rms(x, 4800, 43200)
        val rmsOut = Pcm.rms(y, 2205, 19845)
        assertEquals(rmsIn, rmsOut, 0.01)
    }

    @Test fun attenuatesAboveNewNyquist() {
        // 15 kHz is above 11.025 kHz and must be removed, not aliased to 7.05 kHz.
        val x = sine(15000.0, 44100, 1.0)
        val y = StreamingResampler.resample(x, 44100, 22050)
        val r = Pcm.rms(y, 2000, y.size - 2000)
        assertTrue("alias rms $r", Pcm.toDb(r) < -50)
    }

    @Test fun streamingEqualsOneShot() {
        val x = sine(330.0, 44100, 0.8)
        val oneShot = StreamingResampler.resample(x, 44100, 22050)
        val r = StreamingResampler(44100, 22050)
        val parts = ArrayList<Float>()
        var p = 0
        val chunks = intArrayOf(1, 7, 100, 4096, 333)
        var c = 0
        while (p < x.size) {
            val n = minOf(chunks[c++ % chunks.size], x.size - p)
            parts += r.process(x, p, n).toList()
            p += n
        }
        parts += r.flush().toList()
        assertEquals(oneShot.size, parts.size)
        for (i in oneShot.indices) assertEquals(oneShot[i], parts[i], 1e-5f)
    }

    @Test fun downmixAverages() {
        val st = floatArrayOf(1f, 0f, 0.5f, 0.5f, -1f, 1f)
        val m = Pcm.downmix(st, 2)
        assertEquals(3, m.size)
        assertEquals(0.5f, m[0], 1e-6f); assertEquals(0.5f, m[1], 1e-6f); assertEquals(0f, m[2], 1e-6f)
    }
}
