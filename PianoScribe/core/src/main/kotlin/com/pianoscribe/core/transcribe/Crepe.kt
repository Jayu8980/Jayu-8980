package com.pianoscribe.core.transcribe

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.sqrt

/**
 * CREPE pitch tracking (Kim, Salamon, Li & Bello, "CREPE: A Convolutional Representation for
 * Pitch Estimation", ICASSP 2018; torchcrepe port by Max Morrison, MIT licence): a network
 * trained to follow the pitch of one voice or instrument. Here: its input frames and its
 * decoding, as torchcrepe does them; the network runs in ONNX Runtime.
 *
 * Input is 16 kHz audio in 1024-sample frames every 10 ms, centred, each frame normalised to zero
 * mean and unit variance. The network gives, per frame, 360 pitch-bin probabilities (20 cents
 * apart from C1); Viterbi decoding picks a smooth path through them, and the probability on that
 * path is the frame's periodicity (how sure it is a pitch is sounding).
 */
object Crepe {
    const val SAMPLE_RATE = 16000
    const val WINDOW = 1024
    const val HOP = 160 // 10 ms
    const val BINS = 360
    const val FRAME_SEC = HOP.toDouble() / SAMPLE_RATE
    private const val CENTS_BIN0 = 1997.3794084376191
    private const val CENTS_PER_BIN = 20.0

    fun frameCount(samples: Int) = 1 + samples / HOP

    /** Frames [from, from + count) of [audio] (16 kHz), normalised, into [out] (count x 1024). */
    fun fillFrames(audio: FloatArray, from: Int, count: Int, out: FloatArray) {
        for (f in 0 until count) {
            val start = (from + f) * HOP - WINDOW / 2
            var sum = 0.0
            for (i in 0 until WINDOW) { val j = start + i; val v = if (j in audio.indices) audio[j].toDouble() else 0.0; sum += v }
            val mean = sum / WINDOW
            var ss = 0.0
            for (i in 0 until WINDOW) { val j = start + i; val v = (if (j in audio.indices) audio[j].toDouble() else 0.0) - mean; ss += v * v }
            // torch.std is the unbiased estimate.
            val sd = max(sqrt(ss / (WINDOW - 1)), 1e-10)
            val o = f * WINDOW
            for (i in 0 until WINDOW) { val j = start + i; val v = if (j in audio.indices) audio[j].toDouble() else 0.0; out[o + i] = ((v - mean) / sd).toFloat() }
        }
    }

    fun binToCents(bin: Double) = CENTS_BIN0 + CENTS_PER_BIN * bin
    fun centsToMidi(cents: Double) = 69 + (cents - 1200 * log2(440.0 / 10.0)) / 100
    fun midiToBin(midi: Double) = ((1200 * log2(440.0 / 10.0) + (midi - 69) * 100) - CENTS_BIN0) / CENTS_PER_BIN

    /** Pitch (MIDI, fractional) and periodicity per frame. */
    class Track(val midi: FloatArray, val periodicity: FloatArray) { val size get() = midi.size }

    /**
     * Viterbi decoding as torchcrepe: emissions are the softmax over the allowed bins of the
     * network's outputs, transitions favour moves of under 12 bins (240 cents) per frame. The
     * pitch is then refined to the probability-weighted mean of the bins within 4 of the path.
     * [probs] is [frames x 360]; bins outside [minMidi, maxMidi] are not allowed.
     */
    fun decode(probs: FloatArray, frames: Int, minMidi: Double = 24.0, maxMidi: Double = 107.0): Track {
        val lo = midiToBin(minMidi).toInt().coerceIn(0, BINS - 1)
        val hi = Math.ceil(midiToBin(maxMidi)).toInt().coerceIn(lo + 1, BINS)
        val n = hi - lo
        // Transition log-probabilities by distance, per source row (rows near the edges have fewer neighbours).
        val logT = Array(n) { i ->
            var sum = 0.0
            for (j in 0 until BINS) sum += max(12 - Math.abs((lo + i) - j), 0)
            DoubleArray(23) { k -> val d = k - 11; val w = 12 - Math.abs(d); if (w > 0) ln(w / sum) else Double.NEGATIVE_INFINITY }
        }
        val score = DoubleArray(n); val next = DoubleArray(n)
        val back = Array(frames) { IntArray(n) }
        fun emissions(t: Int, into: DoubleArray) {
            // Softmax over the allowed bins of the (already sigmoid) outputs.
            var m = Double.NEGATIVE_INFINITY
            for (i in 0 until n) m = max(m, probs[t * BINS + lo + i].toDouble())
            var z = 0.0
            for (i in 0 until n) z += exp(probs[t * BINS + lo + i] - m)
            val lz = ln(z) + m
            for (i in 0 until n) into[i] = probs[t * BINS + lo + i] - lz
        }
        val em = DoubleArray(n)
        if (frames == 0) return Track(FloatArray(0), FloatArray(0))
        emissions(0, em)
        for (i in 0 until n) score[i] = em[i] - ln(n.toDouble())
        for (t in 1 until frames) {
            emissions(t, em)
            for (j in 0 until n) {
                var best = Double.NEGATIVE_INFINITY; var arg = j
                for (d in -11..11) {
                    val i = j - d
                    if (i < 0 || i >= n) continue
                    val v = score[i] + logT[i][d + 11]
                    if (v > best) { best = v; arg = i }
                }
                next[j] = best + em[j]; back[t][j] = arg
            }
            System.arraycopy(next, 0, score, 0, n)
        }
        val path = IntArray(frames)
        var b = 0
        for (i in 1 until n) if (score[i] > score[b]) b = i
        for (t in frames - 1 downTo 0) { path[t] = b; b = back[t][b] }
        val midi = FloatArray(frames); val per = FloatArray(frames)
        for (t in 0 until frames) {
            val bin = lo + path[t]
            var w = 0.0; var c = 0.0
            for (k in maxOf(0, bin - 4)..minOf(BINS - 1, bin + 4)) { val p = probs[t * BINS + k].toDouble(); w += p; c += p * binToCents(k.toDouble()) }
            midi[t] = centsToMidi(if (w > 0) c / w else binToCents(bin.toDouble())).toFloat()
            per[t] = probs[t * BINS + bin]
        }
        return Track(midi, per)
    }
}
