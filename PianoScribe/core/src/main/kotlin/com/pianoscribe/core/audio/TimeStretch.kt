package com.pianoscribe.core.audio

import kotlin.math.PI
import kotlin.math.cos

/**
 * Streaming WSOLA time-stretcher: changes playback speed without changing pitch, so practice at
 * 0.25x..2x keeps the original audio in tune.
 */
class TimeStretch(sampleRate: Int, speed: Double) {
    private val frame = (sampleRate * 0.040).toInt().let { if (it % 2 == 1) it + 1 else it }
    private val hopOut = frame / 2
    private val seek = (sampleRate * 0.012).toInt()
    private val window = FloatArray(frame) { (0.5 - 0.5 * cos(2 * PI * it / frame)).toFloat() }

    var speed: Double = speed
        set(v) { field = v.coerceIn(0.1, 4.0) }

    private var input = FloatArray(frame * 8)
    private var inLen = 0
    /** Ideal (fractional) read position inside [input] for the next frame. */
    private var readPos = 0.0
    /** Where the previous frame actually started, used for similarity search. */
    private var prevStart = -1
    private val overlap = FloatArray(hopOut)

    init { this.speed = speed }

    fun reset() {
        inLen = 0; readPos = 0.0; prevStart = -1; overlap.fill(0f)
    }

    /** Pushes input and returns stretched output available so far. */
    fun process(x: FloatArray, off: Int = 0, len: Int = x.size - off): FloatArray {
        if (inLen + len > input.size) {
            var cap = input.size
            while (cap < inLen + len) cap *= 2
            input = input.copyOf(cap)
        }
        System.arraycopy(x, off, input, inLen, len)
        inLen += len
        if (speed == 1.0 && prevStart < 0) {
            // Pass-through fast path.
            val out = input.copyOf(inLen)
            inLen = 0
            return out
        }
        var out = FloatArray(1024)
        var n = 0
        while (true) {
            val ideal = readPos.toInt()
            if (ideal + frame + seek >= inLen) break
            var start = ideal
            if (prevStart >= 0) {
                // Natural continuation of the previous frame.
                val target = prevStart + hopOut
                var best = Double.NEGATIVE_INFINITY
                val lo = maxOf(0, ideal - seek)
                val hi = ideal + seek
                var s = lo
                while (s <= hi) {
                    var c = 0.0
                    var i = 0
                    while (i < hopOut) {
                        c += input[s + i].toDouble() * input[target + i]
                        i += 2
                    }
                    if (c > best) { best = c; start = s }
                    s++
                }
            }
            if (n + hopOut > out.size) out = out.copyOf(out.size * 2)
            for (i in 0 until hopOut) {
                out[n + i] = overlap[i] + input[start + i] * window[i]
                overlap[i] = input[start + hopOut + i] * window[hopOut + i]
            }
            n += hopOut
            prevStart = start
            readPos += hopOut * speed
            // Discard consumed input.
            val drop = minOf(prevStart, maxOf(0, readPos.toInt() - seek - 1))
            if (drop > frame * 4) {
                System.arraycopy(input, drop, input, 0, inLen - drop)
                inLen -= drop
                readPos -= drop
                prevStart -= drop
            }
        }
        return out.copyOf(n)
    }
}
