package com.pianoscribe.core.audio

/**
 * YIN monophonic pitch detector (de Cheveigné & Kawahara 2002), used for practice mode with a
 * microphone.
 */
class Yin(
    private val sampleRate: Int,
    private val bufferSize: Int,
    private val threshold: Double = 0.15,
    minFreq: Double = 26.0,
    maxFreq: Double = 4200.0,
) {
    data class Result(val frequency: Double, val probability: Double)

    private val maxTau = minOf(bufferSize / 2, (sampleRate / minFreq).toInt())
    private val minTau = maxOf(2, (sampleRate / maxFreq).toInt())
    private val diff = DoubleArray(maxTau + 1)

    /** Returns null when no clear pitch (silence or noise). Reads bufferSize samples from [x]. */
    fun detect(x: FloatArray, offset: Int = 0): Result? {
        val w = bufferSize - maxTau
        if (w <= 0) return null
        var energy = 0.0
        for (i in 0 until bufferSize) energy += x[offset + i].toDouble() * x[offset + i]
        if (energy / bufferSize < 1e-6) return null
        for (tau in 0..maxTau) {
            var s = 0.0
            for (i in 0 until w) {
                val d = x[offset + i] - x[offset + i + tau].toDouble()
                s += d * d
            }
            diff[tau] = s
        }
        // Cumulative mean normalised difference.
        diff[0] = 1.0
        var running = 0.0
        for (tau in 1..maxTau) {
            running += diff[tau]
            diff[tau] = if (running == 0.0) 1.0 else diff[tau] * tau / running
        }
        var tau = minTau
        var found = -1
        while (tau <= maxTau) {
            if (diff[tau] < threshold) {
                while (tau + 1 <= maxTau && diff[tau + 1] < diff[tau]) tau++
                found = tau
                break
            }
            tau++
        }
        if (found < 0) return null
        val betterTau = if (found in 1 until maxTau) {
            val s0 = diff[found - 1]
            val s1 = diff[found]
            val s2 = diff[found + 1]
            val denom = 2 * (2 * s1 - s2 - s0)
            if (denom != 0.0) found + (s2 - s0) / denom else found.toDouble()
        } else found.toDouble()
        return Result(sampleRate / betterTau, (1 - diff[found]).coerceIn(0.0, 1.0))
    }
}
