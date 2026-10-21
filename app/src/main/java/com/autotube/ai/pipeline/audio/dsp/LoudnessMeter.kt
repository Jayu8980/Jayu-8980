package com.autotube.ai.pipeline.audio.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * ITU-R BS.1770-4 / EBU R128 integrated loudness meter.
 *
 * K-weighting (high-shelf pre-filter + RLB high-pass; the coefficients are derived for any sample rate
 * and match the published 48 kHz values exactly), 400 ms blocks with 75 % overlap (100 ms hop),
 * absolute gate at -70 LUFS and relative gate at -10 LU. Streaming: feed interleaved audio with
 * [process] in any chunk size, then read [integratedLufs]. Channel weights are 1.0 for every channel
 * (mono, L, R); surround weighting is not needed here.
 */
class LoudnessMeter(private val sampleRate: Int, private val channels: Int) {

    private val preFilters = Array(channels) { kWeightingStage1(sampleRate) }
    private val rlbFilters = Array(channels) { kWeightingStage2(sampleRate) }

    private val hopFrames = max(1, sampleRate / 10)
    private var hopEnergy = 0.0
    private var hopCount = 0
    /** Mean-square energy of the last 4 hops (one 400 ms block = 4 x 100 ms). */
    private val recentHops = DoubleArray(4)
    private var hopsSeen = 0
    private var blockPowers = DoubleArray(1024)
    private var blockCount = 0

    fun reset() {
        preFilters.forEach { it.reset() }
        rlbFilters.forEach { it.reset() }
        hopEnergy = 0.0
        hopCount = 0
        hopsSeen = 0
        blockCount = 0
    }

    /** Feeds [frames] interleaved frames starting at frame [startFrame]. */
    fun process(samples: FloatArray, startFrame: Int = 0, frames: Int = samples.size / channels - startFrame) {
        var idx = startFrame * channels
        repeat(frames) {
            var sum = 0.0
            for (c in 0 until channels) {
                val y = rlbFilters[c].processDouble(preFilters[c].processDouble(samples[idx + c].toDouble()))
                sum += y * y
            }
            idx += channels
            hopEnergy += sum
            hopCount++
            if (hopCount == hopFrames) finishHop()
        }
    }

    private fun finishHop() {
        recentHops[hopsSeen % 4] = hopEnergy / hopFrames
        hopsSeen++
        hopEnergy = 0.0
        hopCount = 0
        if (hopsSeen >= 4) {
            val power = (recentHops[0] + recentHops[1] + recentHops[2] + recentHops[3]) / 4.0
            if (blockCount == blockPowers.size) blockPowers = blockPowers.copyOf(blockPowers.size * 2)
            blockPowers[blockCount++] = power
        }
    }

    /** Integrated, gated loudness in LUFS, or [SILENCE_LUFS] when nothing passes the gates. */
    fun integratedLufs(): Float {
        if (blockCount == 0) return SILENCE_LUFS
        val absGatePower = lufsToPower(ABSOLUTE_GATE)
        var sum = 0.0
        var n = 0
        for (i in 0 until blockCount) {
            val p = blockPowers[i]
            if (p > absGatePower) {
                sum += p; n++
            }
        }
        if (n == 0) return SILENCE_LUFS
        val relGatePower = lufsToPower(powerToLufs(sum / n) + RELATIVE_GATE)
        val gate = max(absGatePower, relGatePower)
        sum = 0.0
        n = 0
        for (i in 0 until blockCount) {
            val p = blockPowers[i]
            if (p > gate) {
                sum += p; n++
            }
        }
        if (n == 0) return SILENCE_LUFS
        return powerToLufs(sum / n).toFloat()
    }

    companion object {
        const val SILENCE_LUFS = -70f
        private const val ABSOLUTE_GATE = -70.0
        private const val RELATIVE_GATE = -10.0

        fun powerToLufs(power: Double): Double = if (power <= 0.0) -200.0 else -0.691 + 10.0 * log10(power)
        fun lufsToPower(lufs: Double): Double = 10.0.pow((lufs + 0.691) / 10.0)

        /** Measures the integrated loudness of a whole interleaved buffer. */
        fun measure(samples: FloatArray, sampleRate: Int, channels: Int): Float {
            val meter = LoudnessMeter(sampleRate, channels)
            meter.process(samples)
            return meter.integratedLufs()
        }

        /** BS.1770 stage 1: high-shelf (+4 dB above ~1.7 kHz) modelling the head. */
        fun kWeightingStage1(sampleRate: Int): Biquad {
            val f0 = 1681.974450955533
            val g = 3.999843853973347
            val q = 0.7071752369554196
            val k = tan(PI * f0 / sampleRate)
            val vh = 10.0.pow(g / 20.0)
            val vb = vh.pow(0.4996667741545416)
            val a0 = 1.0 + k / q + k * k
            return Biquad().setCoefficients(
                (vh + vb * k / q + k * k) / a0,
                2.0 * (k * k - vh) / a0,
                (vh - vb * k / q + k * k) / a0,
                2.0 * (k * k - 1.0) / a0,
                (1.0 - k / q + k * k) / a0,
            )
        }

        /** BS.1770 stage 2: RLB high-pass at ~38 Hz. */
        fun kWeightingStage2(sampleRate: Int): Biquad {
            val f0 = 38.13547087602444
            val q = 0.5003270373238773
            val k = tan(PI * f0 / sampleRate)
            val a0 = 1.0 + k / q + k * k
            return Biquad().setCoefficients(
                1.0, -2.0, 1.0,
                2.0 * (k * k - 1.0) / a0,
                (1.0 - k / q + k * k) / a0,
            )
        }
    }
}

/**
 * True-peak estimate per BS.1770-4 Annex 2: 4x oversampling with a 48-tap windowed-sinc interpolator.
 * Interpolation is only evaluated around samples that could raise the running maximum, which keeps
 * a 10-minute stereo scan fast.
 */
object TruePeak {
    private const val OVERSAMPLE = 4
    private const val TAPS_PER_PHASE = 12
    private val phases: Array<FloatArray> = buildPhases()

    private fun buildPhases(): Array<FloatArray> {
        val total = OVERSAMPLE * TAPS_PER_PHASE
        val center = (total - 1) / 2.0
        val proto = DoubleArray(total) { n ->
            val x = (n - center) / OVERSAMPLE
            val sinc = if (abs(x) < 1e-9) 1.0 else sin(PI * x) / (PI * x)
            val w = 0.42 - 0.5 * kotlin.math.cos(2 * PI * n / (total - 1)) + 0.08 * kotlin.math.cos(4 * PI * n / (total - 1))
            sinc * w
        }
        return Array(OVERSAMPLE) { p ->
            val taps = FloatArray(TAPS_PER_PHASE) { t -> proto[t * OVERSAMPLE + p].toFloat() }
            // Normalize each phase to unity DC gain.
            val s = taps.sum()
            if (s != 0f) for (i in taps.indices) taps[i] /= s
            taps
        }
    }

    /** Linear true peak (max over channels) of an interleaved buffer. */
    fun measure(samples: FloatArray, channels: Int): Float {
        val frames = samples.size / channels
        var peak = 0f
        for (i in samples.indices) {
            val a = abs(samples[i])
            if (a > peak) peak = a
        }
        if (peak == 0f) return 0f
        var truePeak = peak
        val half = TAPS_PER_PHASE / 2
        for (c in 0 until channels) {
            for (f in half until frames - half) {
                val s = abs(samples[f * channels + c])
                // Inter-sample overs exceed neighbouring samples by at most a few dB.
                if (s * 2.2f < truePeak) continue
                for (p in 1 until OVERSAMPLE) {
                    val taps = phases[p]
                    var acc = 0f
                    var idx = (f - half + 1) * channels + c
                    for (t in TAPS_PER_PHASE - 1 downTo 0) {
                        acc += samples[idx] * taps[t]
                        idx += channels
                    }
                    val a = abs(acc)
                    if (a > truePeak) truePeak = a
                }
            }
        }
        return truePeak
    }

    fun measureDb(samples: FloatArray, channels: Int): Float = Curves.linearToDb(measure(samples, channels))
}
