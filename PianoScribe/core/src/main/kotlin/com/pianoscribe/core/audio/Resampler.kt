package com.pianoscribe.core.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Streaming band-limited resampler (Kaiser-windowed sinc, table driven) for arbitrary rate ratios.
 *
 * Feed input in chunks of any size with [process]; call [flush] once at the end. The total output
 * length is round(inputLength * outRate / inRate).
 */
class StreamingResampler(
    val inRate: Int,
    val outRate: Int,
    private val zeroCrossings: Int = 16,
) {
    private val step = inRate.toDouble() / outRate
    /** Cutoff relative to the input Nyquist frequency. */
    private val cutoff = min(1.0, outRate.toDouble() / inRate) * 0.94
    private val halfWidth = zeroCrossings / cutoff
    private val table: FloatArray
    private val tableRes = 256

    private var buf = FloatArray(4096)
    private var bufLen = 0
    /** Absolute input index of buf[0]. */
    private var bufStart: Long
    private var outCount = 0L
    private var inCount = 0L

    init {
        require(inRate > 0 && outRate > 0)
        val n = (halfWidth * tableRes).toInt() + 2
        table = FloatArray(n)
        val beta = 8.0
        val i0b = besselI0(beta)
        for (i in 0 until n) {
            val u = i.toDouble() / tableRes
            val x = u * cutoff
            val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
            val r = u / halfWidth
            val w = if (r >= 1.0) 0.0 else besselI0(beta * sqrt(1 - r * r)) / i0b
            table[i] = (cutoff * sinc * w).toFloat()
        }
        // Pretend the signal is preceded by zeros so the first outputs are well defined.
        val pre = ceil(halfWidth).toInt() + 1
        bufStart = -pre.toLong()
        bufLen = pre
    }

    private fun kernel(d: Double): Float {
        val p = abs(d) * tableRes
        val i = p.toInt()
        if (i + 1 >= table.size) return 0f
        val f = (p - i).toFloat()
        return table[i] + (table[i + 1] - table[i]) * f
    }

    private fun append(input: FloatArray, off: Int, len: Int) {
        if (bufLen + len > buf.size) {
            var cap = buf.size
            while (cap < bufLen + len) cap *= 2
            buf = buf.copyOf(cap)
        }
        System.arraycopy(input, off, buf, bufLen, len)
        bufLen += len
    }

    /** Resamples a chunk; returns the output samples that are ready. */
    fun process(input: FloatArray, off: Int = 0, len: Int = input.size - off): FloatArray {
        append(input, off, len)
        inCount += len
        return drain(false)
    }

    /** Emits the remaining output samples. */
    fun flush(): FloatArray {
        val pad = ceil(halfWidth).toInt() + 2
        append(FloatArray(pad), 0, pad)
        return drain(true)
    }

    private fun drain(final: Boolean): FloatArray {
        val totalOut = if (final) Math.round(inCount.toDouble() * outRate / inRate) else Long.MAX_VALUE
        val bufEnd = bufStart + bufLen
        var result = FloatArray(maxOf(16, ((bufLen / step) + 4).toInt()))
        var n = 0
        while (outCount < totalOut) {
            val x = outCount * step
            val hi = floor(x + halfWidth).toLong()
            if (hi >= bufEnd) break
            val lo = ceil(x - halfWidth).toLong()
            var acc = 0.0
            var k = lo
            while (k <= hi) {
                val idx = (k - bufStart).toInt()
                if (idx >= 0) acc += buf[idx] * kernel(x - k)
                k++
            }
            if (n == result.size) result = result.copyOf(result.size * 2)
            result[n++] = acc.toFloat()
            outCount++
        }
        // Drop input that no future output needs.
        val keepFrom = floor(outCount * step - halfWidth).toLong() - 1
        val drop = (keepFrom - bufStart).toInt().coerceIn(0, bufLen)
        if (drop > 0) {
            System.arraycopy(buf, drop, buf, 0, bufLen - drop)
            bufLen -= drop
            bufStart += drop
        }
        return result.copyOf(n)
    }

    companion object {
        /** One-shot convenience. */
        fun resample(input: FloatArray, inRate: Int, outRate: Int): FloatArray {
            if (inRate == outRate) return input.copyOf()
            val r = StreamingResampler(inRate, outRate)
            val a = r.process(input)
            val b = r.flush()
            return a + b
        }

        private fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val q = x * x / 4
            var k = 1
            while (k < 60) {
                term *= q / (k.toDouble() * k)
                sum += term
                if (term < 1e-12 * sum) break
                k++
            }
            return sum
        }
    }
}

/** Channel helpers. */
object Pcm {
    /** Averages interleaved channels into mono. */
    fun downmix(interleaved: FloatArray, channels: Int, frames: Int = interleaved.size / channels): FloatArray {
        if (channels == 1) return interleaved.copyOf(frames)
        val out = FloatArray(frames)
        val inv = 1f / channels
        for (f in 0 until frames) {
            var s = 0f
            val base = f * channels
            for (c in 0 until channels) s += interleaved[base + c]
            out[f] = s * inv
        }
        return out
    }

    /** Converts 16-bit little-endian PCM to floats in -1..1. */
    fun pcm16ToFloat(bytes: ByteArray, len: Int = bytes.size): FloatArray {
        val n = len / 2
        val out = FloatArray(n)
        for (i in 0 until n) {
            val lo = bytes[2 * i].toInt() and 0xFF
            val hi = bytes[2 * i + 1].toInt()
            out[i] = ((hi shl 8) or lo) / 32768f
        }
        return out
    }

    fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        if (to <= from) return 0.0
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return sqrt(s / (to - from))
    }

    fun toDb(rms: Double): Double = if (rms <= 1e-10) -200.0 else 20 * kotlin.math.log10(rms)
}
