package com.pianoscribe.core.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Second-order IIR section (RBJ audio EQ cookbook), transposed direct form II. */
class Biquad private constructor(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double,
) {
    private var z1 = 0.0
    private var z2 = 0.0

    fun reset() { z1 = 0.0; z2 = 0.0 }

    fun process(x: FloatArray, off: Int = 0, len: Int = x.size - off) {
        for (i in off until off + len) {
            val v = x[i].toDouble()
            val y = b0 * v + z1
            z1 = b1 * v - a1 * y + z2
            z2 = b2 * v - a2 * y
            x[i] = y.toFloat()
        }
    }

    /** Magnitude response at [freq] Hz. */
    fun gainAt(freq: Double, sampleRate: Int): Double {
        val w = 2 * PI * freq / sampleRate
        val cr = cos(w); val ci = -sin(w)
        val c2r = cos(2 * w); val c2i = -sin(2 * w)
        val nr = b0 + b1 * cr + b2 * c2r; val ni = b1 * ci + b2 * c2i
        val dr = 1 + a1 * cr + a2 * c2r; val di = a1 * ci + a2 * c2i
        return sqrt((nr * nr + ni * ni) / (dr * dr + di * di))
    }

    companion object {
        private fun make(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
            Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

        fun highPass(freq: Double, q: Double, sampleRate: Int): Biquad {
            val w = 2 * PI * freq / sampleRate
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            return make((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }

        fun lowPass(freq: Double, q: Double, sampleRate: Int): Biquad {
            val w = 2 * PI * freq / sampleRate
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            return make((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }

        /** Band-reject filter; [bandwidthHz] is the -3 dB width. */
        fun notch(freq: Double, bandwidthHz: Double, sampleRate: Int): Biquad {
            val w = 2 * PI * freq / sampleRate
            val alpha = sin(w) / (2 * (freq / bandwidthHz))
            val c = cos(w)
            return make(1.0, -2 * c, 1.0, 1 + alpha, -2 * c, 1 - alpha)
        }

        fun peaking(freq: Double, q: Double, gainDb: Double, sampleRate: Int): Biquad {
            val a = 10.0.pow(gainDb / 40)
            val w = 2 * PI * freq / sampleRate
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            return make(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
        }

        fun lowShelf(freq: Double, gainDb: Double, sampleRate: Int): Biquad {
            val a = 10.0.pow(gainDb / 40)
            val w = 2 * PI * freq / sampleRate
            val c = cos(w)
            val alpha = sin(w) / 2 * sqrt(2.0) // shelf slope 1
            val s = 2 * sqrt(a) * alpha
            return make(
                a * ((a + 1) - (a - 1) * c + s), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - s),
                (a + 1) + (a - 1) * c + s, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - s,
            )
        }

        fun highShelf(freq: Double, gainDb: Double, sampleRate: Int): Biquad {
            val a = 10.0.pow(gainDb / 40)
            val w = 2 * PI * freq / sampleRate
            val c = cos(w)
            val alpha = sin(w) / 2 * sqrt(2.0)
            val s = 2 * sqrt(a) * alpha
            return make(
                a * ((a + 1) + (a - 1) * c + s), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - s),
                (a + 1) - (a - 1) * c + s, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - s,
            )
        }

        /** 4th-order Butterworth high-pass as two sections. */
        fun butterworthHighPass4(freq: Double, sampleRate: Int) =
            listOf(highPass(freq, 0.5411961, sampleRate), highPass(freq, 1.3065630, sampleRate))
    }
}
