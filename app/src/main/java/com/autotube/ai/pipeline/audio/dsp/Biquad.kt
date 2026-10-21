package com.autotube.ai.pipeline.audio.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Second-order IIR section (transposed direct form II, double precision state) with the classic
 * RBJ "Audio EQ Cookbook" designs. One instance processes one channel.
 */
class Biquad {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var z1 = 0.0
    private var z2 = 0.0

    /** Sets normalized coefficients directly (a0 already divided out). */
    fun setCoefficients(b0: Double, b1: Double, b2: Double, a1: Double, a2: Double): Biquad {
        this.b0 = b0; this.b1 = b1; this.b2 = b2; this.a1 = a1; this.a2 = a2
        return this
    }

    fun reset() {
        z1 = 0.0; z2 = 0.0
    }

    fun process(x: Float): Float {
        val xd = x.toDouble()
        val y = b0 * xd + z1
        z1 = b1 * xd - a1 * y + z2
        z2 = b2 * xd - a2 * y
        return y.toFloat()
    }

    fun processDouble(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    /** Filters one channel of an interleaved buffer in place. */
    fun processInterleaved(samples: FloatArray, channels: Int, channel: Int, startFrame: Int = 0, frames: Int = samples.size / channels - startFrame) {
        var i = startFrame * channels + channel
        repeat(frames) {
            samples[i] = process(samples[i])
            i += channels
        }
    }

    fun lowPass(sampleRate: Int, freq: Double, q: Double = 0.7071): Biquad {
        val w = omega(sampleRate, freq)
        val alpha = sin(w) / (2.0 * q)
        val c = cos(w)
        return normalize((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
    }

    fun highPass(sampleRate: Int, freq: Double, q: Double = 0.7071): Biquad {
        val w = omega(sampleRate, freq)
        val alpha = sin(w) / (2.0 * q)
        val c = cos(w)
        return normalize((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
    }

    /** Band-pass with 0 dB peak gain. */
    fun bandPass(sampleRate: Int, freq: Double, q: Double = 1.0): Biquad {
        val w = omega(sampleRate, freq)
        val alpha = sin(w) / (2.0 * q)
        val c = cos(w)
        return normalize(alpha, 0.0, -alpha, 1 + alpha, -2 * c, 1 - alpha)
    }

    fun peaking(sampleRate: Int, freq: Double, q: Double, gainDb: Double): Biquad {
        val a = 10.0.pow(gainDb / 40.0)
        val w = omega(sampleRate, freq)
        val alpha = sin(w) / (2.0 * q)
        val c = cos(w)
        return normalize(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
    }

    fun lowShelf(sampleRate: Int, freq: Double, gainDb: Double, slope: Double = 1.0): Biquad {
        val a = 10.0.pow(gainDb / 40.0)
        val w = omega(sampleRate, freq)
        val c = cos(w)
        val alpha = sin(w) / 2.0 * sqrt((a + 1 / a) * (1 / slope - 1) + 2)
        val sa = 2 * sqrt(a) * alpha
        return normalize(
            a * ((a + 1) - (a - 1) * c + sa),
            2 * a * ((a - 1) - (a + 1) * c),
            a * ((a + 1) - (a - 1) * c - sa),
            (a + 1) + (a - 1) * c + sa,
            -2 * ((a - 1) + (a + 1) * c),
            (a + 1) + (a - 1) * c - sa,
        )
    }

    fun highShelf(sampleRate: Int, freq: Double, gainDb: Double, slope: Double = 1.0): Biquad {
        val a = 10.0.pow(gainDb / 40.0)
        val w = omega(sampleRate, freq)
        val c = cos(w)
        val alpha = sin(w) / 2.0 * sqrt((a + 1 / a) * (1 / slope - 1) + 2)
        val sa = 2 * sqrt(a) * alpha
        return normalize(
            a * ((a + 1) + (a - 1) * c + sa),
            -2 * a * ((a - 1) + (a + 1) * c),
            a * ((a + 1) + (a - 1) * c - sa),
            (a + 1) - (a - 1) * c + sa,
            2 * ((a - 1) - (a + 1) * c),
            (a + 1) - (a - 1) * c - sa,
        )
    }

    private fun omega(sampleRate: Int, freq: Double): Double {
        val nyquistSafe = freq.coerceIn(1.0, sampleRate * 0.49)
        return 2.0 * PI * nyquistSafe / sampleRate
    }

    private fun normalize(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double): Biquad =
        setCoefficients(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

    companion object {
        fun lowPass(sampleRate: Int, freq: Double, q: Double = 0.7071) = Biquad().lowPass(sampleRate, freq, q)
        fun highPass(sampleRate: Int, freq: Double, q: Double = 0.7071) = Biquad().highPass(sampleRate, freq, q)
        fun bandPass(sampleRate: Int, freq: Double, q: Double = 1.0) = Biquad().bandPass(sampleRate, freq, q)
    }
}

/** Cheap one-pole low-pass (6 dB/oct), handy for smoothing and tone shaping. */
class OnePole(private var coef: Float = 0f) {
    private var state = 0f

    fun setCutoff(sampleRate: Int, freq: Double): OnePole {
        coef = kotlin.math.exp(-2.0 * PI * freq / sampleRate).toFloat()
        return this
    }

    fun setCoefficient(c: Float): OnePole {
        coef = c
        return this
    }

    fun reset(value: Float = 0f) {
        state = value
    }

    fun process(x: Float): Float {
        state = x + (state - x) * coef
        return state
    }
}
