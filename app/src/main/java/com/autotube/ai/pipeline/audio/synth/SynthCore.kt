package com.autotube.ai.pipeline.audio.synth

import com.autotube.ai.pipeline.audio.dsp.Biquad
import com.autotube.ai.pipeline.audio.dsp.Curves
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/** Fast deterministic xorshift64* generator (no allocation, no boxing). */
class XorShift(seed: Long) {
    private var state: Long = mixSeed(seed)

    fun nextLong(): Long {
        var x = state
        x = x xor (x ushr 12)
        x = x xor (x shl 25)
        x = x xor (x ushr 27)
        state = x
        return x * 0x2545F4914F6CDD1DL
    }

    /** Uniform in [0, 1). */
    fun nextFloat(): Float = (nextLong() ushr 40).toFloat() / 16777216f

    /** Uniform in [-1, 1). */
    fun bipolar(): Float = nextFloat() * 2f - 1f

    /** Uniform in [0, bound). */
    fun nextInt(bound: Int): Int = if (bound <= 1) 0 else ((nextLong() ushr 33) % bound).toInt()

    fun chance(p: Float): Boolean = nextFloat() < p

    companion object {
        fun mixSeed(seed: Long): Long {
            var z = seed + -0x61c8864680b583ebL
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            z = z xor (z ushr 31)
            return if (z == 0L) 0x9E3779B97F4A7C15uL.toLong() else z
        }
    }
}

/** Musical helpers. */
object Notes {
    fun midiToHz(midi: Float): Float = (440.0 * 2.0.pow((midi - 69f) / 12.0)).toFloat()

    /** The MIDI note with pitch class [pitchClass] closest to [center]. */
    fun nearest(pitchClass: Int, center: Int): Int {
        val pc = ((pitchClass % 12) + 12) % 12
        var best = center
        var bestDist = Int.MAX_VALUE
        for (m in center - 6..center + 6) {
            if (((m % 12) + 12) % 12 == pc) {
                val d = kotlin.math.abs(m - center)
                if (d < bestDist) {
                    bestDist = d; best = m
                }
            }
        }
        return best
    }
}

/**
 * Note renderers that add into an interleaved stereo accumulator. Every renderer clips writes to
 * [accFrames] so notes may safely run past the end.
 */
object Voices {

    private const val TWO_PI = (2.0 * PI).toFloat()

    /** Band-limited step correction for the saw oscillator. */
    private fun polyBlep(t: Float, dt: Float): Float = when {
        t < dt -> {
            val x = t / dt
            x + x - x * x - 1f
        }
        t > 1f - dt -> {
            val x = (t - 1f) / dt
            x * x + x + x + 1f
        }
        else -> 0f
    }

    /** Cheap tanh-like soft saturation. */
    fun softClip(x: Float): Float = when {
        x > 3f -> 1f
        x < -3f -> -1f
        else -> x * (27f + x * x) / (27f + 9f * x * x)
    }

    private fun addStereo(acc: FloatArray, frame: Int, l: Float, r: Float) {
        val i = frame * 2
        acc[i] += l
        acc[i + 1] += r
    }

    /**
     * Warm pad: detuned polyBLEP saws (lower detunes left, higher right) through a resonant-free
     * low-pass, with attack / sustain / cosine release and optional delayed vibrato.
     */
    fun pad(
        acc: FloatArray, accFrames: Int, start: Int,
        holdFrames: Int, attackFrames: Int, releaseFrames: Int,
        freq: Float, amp: Float, detuneCents: Float, sawsPerSide: Int,
        cutoffHz: Float, vibratoDepth: Float, sampleRate: Int, rng: XorShift,
    ) {
        val n = sawsPerSide.coerceIn(1, 4)
        val total = holdFrames + releaseFrames
        val end = minOf(start + total, accFrames)
        if (end <= start) return
        val phases = FloatArray(n * 2) { rng.nextFloat() }
        val incs = FloatArray(n * 2) { k ->
            val side = if (k < n) -1f else 1f
            val v = k % n
            val cents = side * detuneCents * (v + 0.5f) / n
            freq * 2.0.pow(cents / 1200.0).toFloat() / sampleRate
        }
        val lpL = Biquad.lowPass(sampleRate, cutoffHz.toDouble(), 0.6)
        val lpR = Biquad.lowPass(sampleRate, cutoffHz.toDouble(), 0.6)
        val norm = amp / n
        val vibRate = TWO_PI * 5.2f / sampleRate
        val vibOnset = (0.6f * sampleRate).coerceAtLeast(1f)
        val att = attackFrames.coerceAtLeast(1)
        val rel = releaseFrames.coerceAtLeast(1)
        for (f in start until end) {
            val t = f - start
            val env = when {
                t < att -> Curves.easeInOut(t.toFloat() / att)
                t < holdFrames -> 1f
                else -> Curves.equalPowerOut((t - holdFrames).toFloat() / rel)
            }
            val vib = if (vibratoDepth > 0f) 1f + vibratoDepth * sin(vibRate * t) * minOf(1f, t / vibOnset) else 1f
            var l = 0f
            var r = 0f
            for (k in 0 until n * 2) {
                val dt = incs[k] * vib
                var p = phases[k] + dt
                if (p >= 1f) p -= 1f
                phases[k] = p
                val s = 2f * p - 1f - polyBlep(p, dt)
                if (k < n) l += s else r += s
            }
            val a = env * norm
            addStereo(acc, f, lpL.process(l) * a, lpR.process(r) * a)
        }
    }

    /** Soft bass: sine with a touch of second harmonic and saturation. */
    fun bass(
        acc: FloatArray, accFrames: Int, start: Int, lengthFrames: Int,
        freq: Float, amp: Float, staccato: Boolean, sampleRate: Int,
    ) {
        val release = (0.04f * sampleRate).toInt()
        val total = lengthFrames + release
        val end = minOf(start + total, accFrames)
        if (end <= start) return
        val inc = TWO_PI * freq / sampleRate
        val attack = (0.006f * sampleRate).coerceAtLeast(1f)
        val decayRate = if (staccato) 14f else 2.2f
        var phase = 0f
        for (f in start until end) {
            val t = f - start
            val sec = t.toFloat() / sampleRate
            var env = minOf(1f, t / attack) * (0.65f + 0.35f * exp(-sec * decayRate))
            if (staccato) env *= exp(-sec * 6f)
            if (t >= lengthFrames) env *= 1f - (t - lengthFrames).toFloat() / release
            phase += inc
            if (phase > TWO_PI) phase -= TWO_PI
            val s = softClip((sin(phase) + 0.22f * sin(2f * phase)) * 1.3f)
            val v = s * env * amp
            addStereo(acc, f, v, v)
        }
    }

    /**
     * Karplus-Strong plucked string with a fractional delay, so chords stay in tune.
     * [brightness] 0..1 shapes the excitation. [scratch] must hold at least the note length.
     */
    fun pluck(
        acc: FloatArray, accFrames: Int, start: Int,
        freq: Float, amp: Float, pan: Float, decaySeconds: Float, brightness: Float,
        sampleRate: Int, rng: XorShift, scratch: FloatArray,
    ) {
        val len = minOf((decaySeconds * sampleRate).toInt(), scratch.size, accFrames - start)
        if (len <= 8 || start < 0) return
        val period = (sampleRate / freq - 0.5f).coerceAtLeast(2f)
        val excLen = period.toInt() + 1
        val g = 10.0.pow(-3.0 / (freq * decaySeconds.coerceAtLeast(0.05f))).toFloat().coerceIn(0.5f, 0.99995f)
        val lpCoef = 1f - brightness.coerceIn(0.05f, 1f)
        var lp = 0f
        val y = scratch
        for (n in 0 until len) {
            var exc = 0f
            if (n < excLen) {
                val white = rng.bipolar()
                lp = white + (lp - white) * lpCoef
                exc = lp
            }
            val d = n - period
            val yd = interp(y, d, n)
            val yd1 = interp(y, d - 1f, n)
            y[n] = exc + g * 0.5f * (yd + yd1)
        }
        val fade = minOf(len, (0.01f * sampleRate).toInt())
        val gl = Curves.panLeft(pan) * amp
        val gr = Curves.panRight(pan) * amp
        // Remove the DC the averaging filter can leave behind.
        var prevIn = 0f
        var prevOut = 0f
        for (n in 0 until len) {
            var v = y[n]
            val hp = v - prevIn + 0.995f * prevOut
            prevIn = v
            prevOut = hp
            v = hp
            if (n >= len - fade) v *= (len - n).toFloat() / fade
            addStereo(acc, start + n, v * gl, v * gr)
        }
    }

    private fun interp(y: FloatArray, pos: Float, limit: Int): Float {
        if (pos < 0f) return 0f
        val i0 = floor(pos).toInt()
        val fr = pos - i0
        val a = if (i0 in 0 until limit) y[i0] else 0f
        val b = if (i0 + 1 in 0 until limit) y[i0 + 1] else 0f
        return a + (b - a) * fr
    }

    /** Low drone: two detuned saws through a dark low-pass plus a sine sub, slow breathing. */
    fun drone(
        acc: FloatArray, accFrames: Int, start: Int, holdFrames: Int, fadeFrames: Int,
        freq: Float, amp: Float, sampleRate: Int, rng: XorShift,
    ) {
        val total = holdFrames + fadeFrames
        val end = minOf(start + total, accFrames)
        if (end <= start) return
        var p1 = rng.nextFloat()
        var p2 = rng.nextFloat()
        var ps = 0f
        val i1 = freq * 0.997f / sampleRate
        val i2 = freq * 1.003f / sampleRate
        val iSub = TWO_PI * freq * 0.5f / sampleRate
        val lpL = Biquad.lowPass(sampleRate, 380.0, 0.7)
        val lpR = Biquad.lowPass(sampleRate, 420.0, 0.7)
        val lfo = TWO_PI * 0.11f / sampleRate
        val fade = fadeFrames.coerceAtLeast(1)
        for (f in start until end) {
            val t = f - start
            val env = when {
                t < fade -> Curves.equalPowerIn(t.toFloat() / fade)
                t < holdFrames -> 1f
                else -> Curves.equalPowerOut((t - holdFrames).toFloat() / fade)
            }
            p1 += i1; if (p1 >= 1f) p1 -= 1f
            p2 += i2; if (p2 >= 1f) p2 -= 1f
            ps += iSub; if (ps > TWO_PI) ps -= TWO_PI
            val breathe = 0.8f + 0.2f * sin(lfo * f)
            val sub = sin(ps) * 0.6f
            val l = lpL.process(2f * p1 - 1f) + sub
            val r = lpR.process(2f * p2 - 1f) + sub
            val a = env * amp * breathe
            addStereo(acc, f, l * a, r * a)
        }
    }

    fun kick(acc: FloatArray, accFrames: Int, start: Int, amp: Float, sampleRate: Int, rng: XorShift) {
        val len = (0.42f * sampleRate).toInt()
        val end = minOf(start + len, accFrames)
        var phase = 0f
        val clickLen = (0.003f * sampleRate).toInt()
        for (f in start until end) {
            val sec = (f - start).toFloat() / sampleRate
            val freq = 46f + 95f * exp(-sec * 32f)
            phase += TWO_PI * freq / sampleRate
            var v = sin(phase) * exp(-sec * 8.5f)
            if (f - start < clickLen) v += rng.bipolar() * 0.25f * (1f - (f - start).toFloat() / clickLen)
            v = softClip(v * 1.4f) * amp
            addStereo(acc, f, v, v)
        }
    }

    fun snare(acc: FloatArray, accFrames: Int, start: Int, amp: Float, sampleRate: Int, rng: XorShift, toneHz: Float = 190f) {
        val len = (0.26f * sampleRate).toInt()
        val end = minOf(start + len, accFrames)
        val bp = Biquad.bandPass(sampleRate, 2100.0, 0.6)
        val hp = Biquad.highPass(sampleRate, 400.0)
        var phase = 0f
        for (f in start until end) {
            val sec = (f - start).toFloat() / sampleRate
            phase += TWO_PI * toneHz / sampleRate
            val tone = sin(phase) * exp(-sec * 32f) * 0.45f
            val noise = hp.process(bp.process(rng.bipolar())) * exp(-sec * 17f) * 1.8f
            val v = (tone + noise) * amp
            addStereo(acc, f, v * 0.95f, v)
        }
    }

    fun hat(acc: FloatArray, accFrames: Int, start: Int, amp: Float, open: Boolean, pan: Float, sampleRate: Int, rng: XorShift) {
        val len = ((if (open) 0.28f else 0.06f) * sampleRate).toInt()
        val end = minOf(start + len, accFrames)
        val hp = Biquad.highPass(sampleRate, 7200.0, 0.8)
        val decay = if (open) 13f else 75f
        val gl = Curves.panLeft(pan) * amp
        val gr = Curves.panRight(pan) * amp
        for (f in start until end) {
            val sec = (f - start).toFloat() / sampleRate
            val v = hp.process(rng.bipolar()) * exp(-sec * decay)
            addStereo(acc, f, v * gl, v * gr)
        }
    }

    /** Big low drum hit (taiko-like) for dramatic material. */
    fun taiko(acc: FloatArray, accFrames: Int, start: Int, amp: Float, sampleRate: Int, rng: XorShift) {
        val len = (1.0f * sampleRate).toInt()
        val end = minOf(start + len, accFrames)
        val lp = Biquad.lowPass(sampleRate, 450.0, 0.7)
        var phase = 0f
        for (f in start until end) {
            val sec = (f - start).toFloat() / sampleRate
            val freq = 54f + 42f * exp(-sec * 12f)
            phase += TWO_PI * freq / sampleRate
            val body = sin(phase) * exp(-sec * 4.2f)
            val skin = lp.process(rng.bipolar()) * exp(-sec * 22f) * 0.9f
            val v = softClip((body + skin) * 1.2f) * amp
            addStereo(acc, f, v, v * 0.97f)
        }
    }
}
