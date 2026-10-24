package com.pianoscribe.core.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Short-time Fourier transform with exact resynthesis: square-root Hann windows on analysis and
 * synthesis at 75% overlap, so an unmodified spectrum gives back the input sample for sample.
 *
 * Frame k starts at k * hop - (fftSize - hop), so every sample is covered by the full overlap.
 */
class Stft(val fftSize: Int = 1024, val hop: Int = fftSize / 4) {
    val bins = fftSize / 2 + 1
    private val window = DoubleArray(fftSize) { sqrt(0.5 - 0.5 * cos(2 * PI * it / fftSize)) }
    /** Periodic Hann windows at 75% overlap sum to 2; at 50% they sum to 1. */
    private val norm = fftSize / hop / 2.0

    init {
        require(fftSize and (fftSize - 1) == 0 && fftSize % hop == 0 && fftSize / hop >= 2)
    }

    fun frameCount(n: Int) = if (n == 0) 0 else (n + fftSize - hop + hop - 1) / hop
    fun frameStart(k: Int) = k * hop - (fftSize - hop)
    /** Time in seconds of the centre of frame [k]. */
    fun frameTime(k: Int, sampleRate: Int) = (frameStart(k) + fftSize / 2).toDouble() / sampleRate

    /** Spectrum of frame [k] of [x] into [re]/[im] (length fftSize). */
    fun analyse(x: FloatArray, k: Int, re: DoubleArray, im: DoubleArray) {
        val start = frameStart(k)
        for (i in 0 until fftSize) {
            val idx = start + i
            re[i] = if (idx in x.indices) x[idx] * window[i] else 0.0
            im[i] = 0.0
        }
        Fft.fft(re, im)
    }

    /** Calls [onFrame] with each frame's power spectrum (bins values). */
    fun analyze(x: FloatArray, onFrame: (frame: Int, power: DoubleArray) -> Unit) {
        val re = DoubleArray(fftSize)
        val im = DoubleArray(fftSize)
        val power = DoubleArray(bins)
        for (k in 0 until frameCount(x.size)) {
            analyse(x, k, re, im)
            for (j in 0 until bins) power[j] = re[j] * re[j] + im[j] * im[j]
            onFrame(k, power)
        }
    }

    /** Calls [onFrame] with each frame's complex spectrum (read only). */
    fun spectra(x: FloatArray, onFrame: (frame: Int, re: DoubleArray, im: DoubleArray) -> Unit) {
        val re = DoubleArray(fftSize)
        val im = DoubleArray(fftSize)
        for (k in 0 until frameCount(x.size)) {
            analyse(x, k, re, im)
            onFrame(k, re, im)
        }
    }

    /**
     * Transforms [x]: [onFrame] may change bins 0 until [bins] of each frame's spectrum in place;
     * the mirrored half is rebuilt from them.
     */
    fun process(
        x: FloatArray,
        onProgress: (Float) -> Unit = {},
        onFrame: (frame: Int, re: DoubleArray, im: DoubleArray) -> Unit,
    ): FloatArray {
        val out = FloatArray(x.size)
        val re = DoubleArray(fftSize)
        val im = DoubleArray(fftSize)
        val frames = frameCount(x.size)
        for (k in 0 until frames) {
            analyse(x, k, re, im)
            onFrame(k, re, im)
            im[0] = 0.0; im[fftSize / 2] = 0.0
            for (j in 1 until fftSize / 2) {
                re[fftSize - j] = re[j]
                im[fftSize - j] = -im[j]
            }
            // Inverse FFT by conjugation.
            for (i in 0 until fftSize) im[i] = -im[i]
            Fft.fft(re, im)
            val start = frameStart(k)
            for (i in 0 until fftSize) {
                val idx = start + i
                if (idx in out.indices) out[idx] += (re[i] / fftSize * window[i] / norm).toFloat()
            }
            if (k and 255 == 0) onProgress(k.toFloat() / frames)
        }
        onProgress(1f)
        return out
    }
}
