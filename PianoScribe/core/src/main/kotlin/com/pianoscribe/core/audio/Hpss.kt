package com.pianoscribe.core.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Harmonic/percussive source separation by median filtering (Fitzgerald 2010): in a spectrogram,
 * sustained tones are horizontal lines and drum hits are vertical lines, so a median along time
 * keeps tones and a median along frequency keeps hits. A soft mask built from the two keeps the
 * harmonic part, which is what the note model should hear in a full song.
 *
 * Works on chunks of frames so memory stays small for long songs.
 */
class Hpss(
    private val fftSize: Int = 2048,
    private val hop: Int = 512,
    private val timeKernel: Int = 17,
    private val freqKernel: Int = 17,
    private val chunkFrames: Int = 256,
) {
    /** Result: the harmonic signal plus the share of spectral energy classed as percussive (0..1). */
    class Result(val harmonic: FloatArray, val percussiveRatio: Double)

    private val bins = fftSize / 2 + 1
    private val window = FloatArray(fftSize) { (0.5 - 0.5 * cos(2 * PI * it / fftSize)).toFloat() }

    init {
        require(fftSize and (fftSize - 1) == 0 && fftSize % hop == 0)
        require(timeKernel % 2 == 1 && freqKernel % 2 == 1)
    }

    fun separate(x: FloatArray, onProgress: (Float) -> Unit = {}): Result {
        val n = x.size
        val out = FloatArray(n)
        if (n == 0) return Result(out, 0.0)
        // Frame f covers samples [f*hop - fftSize/2, f*hop + fftSize/2): centred frames.
        val frames = n / hop + 1
        val half = timeKernel / 2
        var harmonicEnergy = 0.0
        var percussiveEnergy = 0.0
        var c0 = 0
        while (c0 < frames) {
            val c1 = minOf(frames, c0 + chunkFrames)
            // Context frames on both sides so the time median is exact at chunk edges.
            val a = maxOf(0, c0 - half)
            val b = minOf(frames, c1 + half)
            val count = b - a
            val re = Array(count) { FloatArray(bins) }
            val im = Array(count) { FloatArray(bins) }
            val mag = Array(count) { FloatArray(bins) }
            val fr = DoubleArray(fftSize)
            val fi = DoubleArray(fftSize)
            for (k in 0 until count) {
                val start = (a + k) * hop - fftSize / 2
                for (i in 0 until fftSize) {
                    val idx = start + i
                    fr[i] = if (idx in 0 until n) (x[idx] * window[i]).toDouble() else 0.0
                    fi[i] = 0.0
                }
                Fft.fft(fr, fi)
                for (j in 0 until bins) {
                    re[k][j] = fr[j].toFloat(); im[k][j] = fi[j].toFloat()
                    mag[k][j] = sqrt(fr[j] * fr[j] + fi[j] * fi[j]).toFloat()
                }
            }
            // Harmonic estimate: median along time, per bin.
            val harm = Array(count) { FloatArray(bins) }
            val column = FloatArray(count)
            val colMed = FloatArray(count)
            for (j in 0 until bins) {
                for (k in 0 until count) column[k] = mag[k][j]
                slidingMedian(column, count, timeKernel, colMed)
                for (k in 0 until count) harm[k][j] = colMed[k]
            }
            // Percussive estimate: median along frequency, per frame. Then mask and resynthesise.
            val perc = FloatArray(bins)
            for (k in (c0 - a) until (c1 - a)) {
                slidingMedian(mag[k], bins, freqKernel, perc)
                for (j in 0 until bins) {
                    val h2 = harm[k][j] * harm[k][j]
                    val p2 = perc[j] * perc[j]
                    val m = if (h2 + p2 > 1e-20f) h2 / (h2 + p2) else 0.5f
                    val e = mag[k][j] * mag[k][j]
                    harmonicEnergy += e * m
                    percussiveEnergy += e * (1 - m)
                    fr[j] = (re[k][j] * m).toDouble()
                    fi[j] = (im[k][j] * m).toDouble()
                }
                // Hermitian mirror, then inverse FFT via conjugation.
                for (j in 1 until fftSize / 2) {
                    fr[fftSize - j] = fr[j]
                    fi[fftSize - j] = -fi[j]
                }
                for (i in 0 until fftSize) fi[i] = -fi[i]
                Fft.fft(fr, fi)
                val start = (a + k) * hop - fftSize / 2
                // Hann analysis window at 75% overlap sums to 2, so overlap-add and halve.
                for (i in 0 until fftSize) {
                    val idx = start + i
                    if (idx in 0 until n) out[idx] += (fr[i] / fftSize * 0.5).toFloat()
                }
            }
            c0 = c1
            onProgress(c0.toFloat() / frames)
        }
        val total = harmonicEnergy + percussiveEnergy
        return Result(out, if (total > 0) percussiveEnergy / total else 0.0)
    }

    companion object {
        /**
         * Running median of src[0 until n] with an odd window [k]; the window shrinks at the edges.
         * Keeps the window sorted, so each step costs O(k) instead of a full sort.
         */
        fun slidingMedian(src: FloatArray, n: Int, k: Int, dst: FloatArray) {
            if (n == 0) return
            val half = k / 2
            val sorted = FloatArray(k)
            var size = 0
            fun insert(v: Float) {
                var i = size
                while (i > 0 && sorted[i - 1] > v) { sorted[i] = sorted[i - 1]; i-- }
                sorted[i] = v
                size++
            }
            fun remove(v: Float) {
                var i = 0
                while (i < size && sorted[i] != v) i++
                if (i == size) return
                while (i < size - 1) { sorted[i] = sorted[i + 1]; i++ }
                size--
            }
            var lo = 0
            var hi = -1 // window is src[lo..hi]
            for (t in 0 until n) {
                val wantLo = maxOf(0, t - half)
                val wantHi = minOf(n - 1, t + half)
                // Remove before inserting so the sorted buffer never holds more than k values.
                while (lo < wantLo) remove(src[lo++])
                while (hi < wantHi) insert(src[++hi])
                dst[t] = if (size % 2 == 1) sorted[size / 2] else 0.5f * (sorted[size / 2 - 1] + sorted[size / 2])
            }
        }
    }
}
