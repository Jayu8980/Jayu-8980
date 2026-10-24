package com.pianoscribe.core.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** In-place iterative radix-2 complex FFT. */
object Fft {
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n == im.size && n > 0 && (n and (n - 1)) == 0) { "FFT size must be a power of two" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /**
     * Hann-windowed magnitude spectrum of [x] starting at [from], zero padded to [size].
     * Returns size/2+1 magnitudes; bin k is k*sampleRate/size Hz.
     */
    fun magnitudeSpectrum(x: FloatArray, from: Int, length: Int, size: Int): DoubleArray {
        val re = DoubleArray(size)
        val im = DoubleArray(size)
        val n = minOf(length, size)
        for (i in 0 until n) {
            val idx = from + i
            val s = if (idx in x.indices) x[idx].toDouble() else 0.0
            val w = 0.5 - 0.5 * cos(2 * PI * i / (n - 1).coerceAtLeast(1))
            re[i] = s * w
        }
        fft(re, im)
        return DoubleArray(size / 2 + 1) { hypot(re[it], im[it]) }
    }

    fun nextPow2(n: Int): Int {
        var p = 1
        while (p < n) p = p shl 1
        return p
    }
}
