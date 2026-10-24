package com.pianoscribe.core.audio

import com.pianoscribe.core.theory.Pitch
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log2

/**
 * Measures how far a detected note is from equal temperament (A4 = 440 Hz), in cents, by finding
 * the spectral peak near the expected fundamental (or a low harmonic for bass notes) and refining
 * it with parabolic interpolation on the log magnitude.
 */
object PitchAccuracy {
    /** Returns cents in -50..50, or null when the note has too little energy to judge. */
    fun measureCents(
        audio: FloatArray,
        sampleRate: Int,
        midi: Int,
        startSec: Double,
        endSec: Double,
    ): Float? {
        val from = ((startSec + 0.03) * sampleRate).toInt().coerceAtLeast(0)
        val to = minOf(audio.size, (minOf(endSec, startSec + 0.5) * sampleRate).toInt())
        val len = to - from
        if (len < sampleRate * 0.04) return null
        val size = maxOf(16384, Fft.nextPow2(len) * 2).coerceAtMost(65536)
        val mag = Fft.magnitudeSpectrum(audio, from, len, size)
        val binHz = sampleRate.toDouble() / size
        val f0 = Pitch.frequency(midi)
        // Bass notes have weak fundamentals on many recordings; use the 2nd harmonic there.
        val harmonic = if (f0 < 80) 2 else 1
        val target = f0 * harmonic
        if (target >= sampleRate / 2.0 - binHz * 4) return null
        val lo = (target * Math.pow(2.0, -60 / 1200.0) / binHz).toInt().coerceAtLeast(1)
        val hi = (target * Math.pow(2.0, 60 / 1200.0) / binHz).toInt() + 1
        if (hi >= mag.size - 1) return null
        var peak = lo
        for (k in lo..hi) if (mag[k] > mag[peak]) peak = k
        if (peak == lo || peak == hi) return null
        val a = ln(mag[peak - 1] + 1e-12)
        val b = ln(mag[peak] + 1e-12)
        val c = ln(mag[peak + 1] + 1e-12)
        val denom = a - 2 * b + c
        val delta = if (denom != 0.0) 0.5 * (a - c) / denom else 0.0
        val freq = (peak + delta) * binHz / harmonic
        val cents = 1200 * log2(freq / f0)
        if (abs(cents) > 55) return null
        return cents.toFloat().coerceIn(-50f, 50f)
    }

    /**
     * Fallback estimate from the Basic Pitch contour output (3 bins per semitone; the bin centred
     * on MIDI pitch p is 3*(p-21)+1). [contourRows] holds the averaged contour row for the note.
     */
    fun centsFromContour(contour: FloatArray, midi: Int): Float {
        val center = 3 * (midi - 21) + 1
        var wsum = 0.0
        var acc = 0.0
        for (d in -1..1) {
            val b = center + d
            if (b !in contour.indices) continue
            wsum += contour[b]
            acc += contour[b] * d
        }
        if (wsum <= 1e-6) return 0f
        return ((acc / wsum) * 100.0 / 3.0 * 1.5).toFloat().coerceIn(-50f, 50f)
    }
}
