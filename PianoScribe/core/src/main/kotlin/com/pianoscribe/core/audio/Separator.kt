package com.pianoscribe.core.audio

import com.pianoscribe.core.model.Note
import com.pianoscribe.core.theory.Pitch
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/** Parts of a transcription, by role in the texture. */
class NoteParts(val melody: List<Note>, val bass: List<Note>, val accompaniment: List<Note>)

/**
 * Source separation without a trained separation model:
 *  - stereo centre: what is panned dead centre (usually the lead vocal, bass and kick) has the
 *    same level and phase in both channels; a soft mask on that similarity removes or keeps it
 *    (Avendano & Jot 2002);
 *  - note-informed: the transcribed notes say where each part's harmonics are in every frame, so
 *    a mask built from harmonic combs keeps one part (melody, bass, accompaniment) and drops the
 *    rest, including drums and noise.
 */
object Separator {
    private const val FFT = 2048
    private const val HOP = 512

    /**
     * How much of a stereo recording is panned dead centre (0..1, energy weighted). Close to 1
     * means the file is effectively mono and centre separation can't help.
     */
    fun centreShare(left: FloatArray, right: FloatArray): Double {
        require(left.size == right.size)
        val stft = Stft(FFT, HOP)
        val lr = DoubleArray(FFT); val li = DoubleArray(FFT)
        val rr = DoubleArray(FFT); val ri = DoubleArray(FFT)
        var centre = 0.0
        var total = 0.0
        val frames = stft.frameCount(left.size)
        val step = maxOf(1, frames / 600)
        for (k in 0 until frames step step) {
            stft.analyse(left, k, lr, li)
            stft.analyse(right, k, rr, ri)
            for (j in 1 until stft.bins) {
                val e = lr[j] * lr[j] + li[j] * li[j] + rr[j] * rr[j] + ri[j] * ri[j]
                centre += e * similarity(lr[j], li[j], rr[j], ri[j])
                total += e
            }
        }
        return if (total > 0) centre / total else 1.0
    }

    /** 1 when the bin is identical in both channels, 0 when it is in one channel only or out of phase. */
    private fun similarity(ar: Double, ai: Double, br: Double, bi: Double): Double {
        val ea = ar * ar + ai * ai
        val eb = br * br + bi * bi
        if (ea + eb < 1e-24) return 0.0
        // Re(A conj B): in phase counts, out of phase doesn't.
        val cross = ar * br + ai * bi
        return (2 * cross / (ea + eb)).coerceIn(0.0, 1.0)
    }

    /**
     * Mono mix with the centre removed ([keepCentre] = false: karaoke, leaves instruments panned
     * to the sides) or kept alone ([keepCentre] = true). [amount] 0..1.
     */
    fun centre(left: FloatArray, right: FloatArray, keepCentre: Boolean, amount: Float = 1f, onProgress: (Float) -> Unit = {}): FloatArray {
        require(left.size == right.size)
        val mid = FloatArray(left.size) { (left[it] + right[it]) * 0.5f }
        val stft = Stft(FFT, HOP)
        val lr = DoubleArray(FFT); val li = DoubleArray(FFT)
        val rr = DoubleArray(FFT); val ri = DoubleArray(FFT)
        val a = amount.coerceIn(0f, 1f).toDouble()
        return stft.process(mid, onProgress) { k, re, im ->
            stft.analyse(left, k, lr, li)
            stft.analyse(right, k, rr, ri)
            for (j in 0 until stft.bins) {
                // A steep curve: only bins that are nearly identical count as centre.
                val c = similarity(lr[j], li[j], rr[j], ri[j]).pow(8)
                val g = if (keepCentre) 1 - a * (1 - c) else 1 - a * c
                re[j] *= g; im[j] *= g
            }
        }
    }

    /** Splits notes into the top line, the bass line and everything in between. */
    fun parts(notes: List<Note>): NoteParts {
        val melody = ArrayList<Note>()
        val bass = ArrayList<Note>()
        val rest = ArrayList<Note>()
        val sorted = notes.sortedBy { it.start }
        for (n in sorted) {
            val at = n.start + minOf(0.03, n.duration / 2)
            val sounding = sorted.filter { it.start <= at + 0.03 && it.end > at }
            val top = sounding.maxOf { it.pitch }
            val low = sounding.minOf { it.pitch }
            when {
                n.pitch == top && (sounding.size > 1 || n.pitch >= Note.MIDDLE_C) -> melody += n
                n.pitch == low -> bass += n
                else -> rest += n
            }
        }
        return NoteParts(melody, bass, rest)
    }

    /**
     * Keeps the harmonics of [target] notes ([keep] = true) or removes them ([keep] = false).
     * [others] are the remaining notes; where their harmonics overlap the target's, energy is
     * shared in proportion. Notes are assumed to ring for [release] seconds after they end.
     */
    fun isolate(
        x: FloatArray,
        sampleRate: Int,
        target: List<Note>,
        others: List<Note>,
        keep: Boolean,
        release: Double = 0.15,
        onProgress: (Float) -> Unit = {},
    ): FloatArray {
        val stft = Stft(FFT, HOP)
        val bins = stft.bins
        val wt = DoubleArray(bins)
        val wo = DoubleArray(bins)
        val all = (target.map { it to true } + others.map { it to false }).sortedBy { it.first.start }
        var first = 0
        // Bins far from any harmonic: drop when keeping a part, keep when removing one.
        val background = 0.02
        return stft.process(x, onProgress) { k, re, im ->
            val t = stft.frameTime(k, sampleRate)
            wt.fill(0.0); wo.fill(0.0)
            while (first < all.size && all[first].first.end + release + 1.0 < t) first++
            for (i in first until all.size) {
                val (n, isTarget) = all[i]
                if (n.start > t + 0.03) break
                if (n.end + release < t) continue
                val level = (n.velocity / 127.0).pow(2) * if (t > n.end) exp(-(t - n.end) / release * 3) else 1.0
                addComb(if (isTarget) wt else wo, n, level, sampleRate)
            }
            for (j in 0 until bins) {
                val share = wt[j] / (wt[j] + wo[j] + background)
                val g = if (keep) share else 1 - share
                re[j] *= g; im[j] *= g
            }
        }
    }

    /** Adds a harmonic comb for [n]: partials with 1/h amplitude and slight piano stretch. */
    private fun addComb(w: DoubleArray, n: Note, level: Double, sampleRate: Int) {
        val f0 = Pitch.frequency(n.pitch, n.cents.toDouble())
        val binHz = sampleRate.toDouble() / FFT
        val nyquist = sampleRate / 2.0 * 0.95
        val b = 0.0004 * sqrt(f0 / 261.6)
        var h = 1
        while (h <= 24) {
            val f = f0 * h * sqrt(1 + b * h * h)
            if (f > nyquist) break
            val c = f / binHz
            // Half a semitone either side, at least 1.2 bins.
            val sigma = maxOf(1.2, c * 0.029)
            val amp = level / h
            val lo = maxOf(0, (c - 3 * sigma).toInt())
            val hi = minOf(w.size - 1, (c + 3 * sigma).toInt() + 1)
            for (j in lo..hi) {
                val d = (j - c) / sigma
                w[j] += amp * exp(-0.5 * d * d)
            }
            h++
        }
    }
}
