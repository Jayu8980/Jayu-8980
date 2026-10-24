package com.pianoscribe.core.synth

import com.pianoscribe.core.perform.SoundModel
import com.pianoscribe.core.theory.Pitch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Built-in instrument sounds, used when no SoundFont preset exists for an instrument:
 * - flute, clarinet, violin, cello: band-limited wavetables (violin/cello with body resonances
 *   baked in), breath or bow noise, a bowed or blown attack, and delayed vibrato
 * - nylon guitar and harp: Karplus-Strong plucked strings with fractional-delay tuning
 * - electric piano: two-operator FM with a decaying bell-like tine
 * - church organ: additive drawbars (8', 4', 2 2/3', 2', ...)
 */
object ModelVoices {
    fun create(model: SoundModel, key: Int, vel: Int, rate: Int, expression: Float): SynthVoice = when (model) {
        SoundModel.FLUTE -> WindVoice(key, vel, rate, expression, Timbre.FLUTE)
        SoundModel.CLARINET -> WindVoice(key, vel, rate, expression, Timbre.CLARINET)
        SoundModel.VIOLIN -> WindVoice(key, vel, rate, expression, Timbre.VIOLIN)
        SoundModel.CELLO -> WindVoice(key, vel, rate, expression, Timbre.CELLO)
        SoundModel.GUITAR -> PluckVoice(key, vel, rate, harp = false)
        SoundModel.HARP -> PluckVoice(key, vel, rate, harp = true)
        SoundModel.ELECTRIC_PIANO -> FmVoice(key, vel, rate)
        SoundModel.ORGAN -> OrganVoice(key, vel, rate)
        SoundModel.PIANO -> error("Piano uses the SoundFont or the additive piano voice")
    }

    /**
     * Sustained-instrument character: harmonic recipe, noise, envelope and vibrato. [level] values
     * are balanced on a melody-over-chords test piece: wind, bowed, electric piano and organ come
     * out within 0.5 dB RMS of each other. Plucked strings sit ~5 dB lower in RMS because most of
     * their energy is in the attack (their peaks are already near full scale).
     */
    class Timbre(
        val harmonics: (Int, Double) -> Double,
        val noise: Double,
        val attack: Double,
        val release: Double,
        val vibratoRate: Double,
        val vibratoCents: Double,
        val vibratoDelay: Double,
        val glideTime: Double,
        val level: Double,
    ) {
        companion object {
            private val FLUTE_H = doubleArrayOf(1.0, 0.45, 0.18, 0.1, 0.05, 0.03, 0.015)
            val FLUTE = Timbre({ n, _ -> FLUTE_H.getOrElse(n - 1) { 0.0 } }, 0.10, 0.07, 0.15, 5.0, 12.0, 0.25, 0.05, 0.41)
            val CLARINET = Timbre({ n, _ -> if (n % 2 == 1) 1.0 / n.toDouble().pow(0.9) else 0.04 / n }, 0.04, 0.05, 0.12, 5.0, 4.0, 0.35, 0.04, 0.38)
            val VIOLIN = Timbre({ n, f -> body(f, VIOLIN_BODY) / n }, 0.03, 0.12, 0.2, 5.5, 18.0, 0.18, 0.06, 0.47)
            val CELLO = Timbre({ n, f -> body(f, CELLO_BODY) / n }, 0.03, 0.15, 0.25, 5.0, 15.0, 0.2, 0.07, 0.39)

            /** Body resonances (centre Hz, gain, bandwidth Hz) on top of a small flat floor. */
            private val VIOLIN_BODY = arrayOf(doubleArrayOf(280.0, 1.0, 90.0), doubleArrayOf(470.0, 0.8, 120.0), doubleArrayOf(1600.0, 0.45, 450.0), doubleArrayOf(3000.0, 0.7, 800.0))
            private val CELLO_BODY = arrayOf(doubleArrayOf(200.0, 1.0, 70.0), doubleArrayOf(300.0, 0.8, 100.0), doubleArrayOf(900.0, 0.4, 300.0), doubleArrayOf(2000.0, 0.45, 700.0))

            private fun body(f: Double, peaks: Array<DoubleArray>): Double {
                var g = 0.18
                for (p in peaks) {
                    val x = (f - p[0]) / p[2]
                    g += p[1] / (1 + x * x)
                }
                return g
            }
        }
    }

    private const val TABLE = 2048

    /** One period of a band-limited waveform: harmonics above 0.45 * rate are left out. */
    private fun wavetable(f0: Double, rate: Int, amp: (Int, Double) -> Double): FloatArray {
        val t = DoubleArray(TABLE)
        var n = 1
        while (f0 * n < rate * 0.45 && n <= 60) {
            val a = amp(n, f0 * n)
            if (a != 0.0) for (i in 0 until TABLE) t[i] += a * sin(2 * PI * n * i / TABLE)
            n++
        }
        val peak = t.maxOf { abs(it) }.coerceAtLeast(1e-9)
        return FloatArray(TABLE) { (t[it] / peak).toFloat() }
    }

    /** Sustained wind or bowed voice with glide for slurs. */
    private class WindVoice(key: Int, vel: Int, private val rate: Int, private val expression: Float, private val tb: Timbre) : SynthVoice(key) {
        private var freq = Pitch.frequency(key)
        private var target = freq
        private val glideCoef = exp(-1.0 / (tb.glideTime * rate))
        private var table = wavetable(freq, rate, tb.harmonics)
        private var phase = 0.0
        private var t = 0.0
        private val dt = 1.0 / rate
        private var env = 0.0
        private var relEnv = 1.0
        private val relFactor = exp(-dt * 6.9 / tb.release)
        private val amp = tb.level * (0.35 + 0.65 * vel / 127.0)
        private val rnd = Random(key * 31 + vel)
        // Noise through a resonant band-pass around the note (breath / bow hair).
        private var bp1 = 0.0
        private var bp2 = 0.0
        private var vibStart = 0.0

        override val canGlide: Boolean get() = !releasing

        override fun glideTo(newKey: Int) {
            super.glideTo(newKey)
            target = Pitch.frequency(newKey)
            // Large jumps need a table with fewer partials to stay alias-free.
            if (target > freq * 1.5) table = wavetable(target, rate, tb.harmonics)
            vibStart = t
        }

        override fun render(out: FloatArray, off: Int, len: Int, gain: Float): Boolean {
            for (i in 0 until len) {
                freq = target + (freq - target) * glideCoef
                // Delayed vibrato that fades in, scaled by expression.
                val since = t - vibStart - tb.vibratoDelay
                val depth = if (since <= 0) 0.0 else minOf(1.0, since / 0.35) * tb.vibratoCents * expression
                val vib = sin(2 * PI * tb.vibratoRate * t)
                val f = freq * 2.0.pow(depth * vib / 1200.0)
                phase += f / rate
                if (phase >= 1) phase -= floor(phase)
                val p = phase * TABLE
                val i0 = p.toInt()
                val frac = (p - i0).toFloat()
                val a = table[i0 and (TABLE - 1)]
                val b = table[(i0 + 1) and (TABLE - 1)]
                var s = (a + (b - a) * frac).toDouble()
                // Breath/bow noise: two-pole resonator at the note frequency.
                val w = 2 * PI * minOf(f, rate * 0.4) / rate
                val r = 0.995
                val noiseIn = rnd.nextDouble() * 2 - 1
                val y = noiseIn * (1 - r) * 4 + 2 * r * kotlin.math.cos(w) * bp1 - r * r * bp2
                bp2 = bp1; bp1 = y
                val chiff = if (t < 0.05) (rnd.nextDouble() * 2 - 1) * (1 - t / 0.05) * 0.3 else 0.0
                s += tb.noise * (y + chiff)
                // Attack curve (slightly overshooting bow or breath), then sustain with gentle tremolo.
                env = if (t < tb.attack) (t / tb.attack).pow(1.5) else 1.0
                if (releasing) {
                    relEnv *= relFactor
                    if (relEnv < 1e-4) return false
                }
                val trem = 1 + 0.04 * expression * vib
                out[off + i] += (s * env * relEnv * amp * trem * gain).toFloat()
                t += dt
            }
            age = t
            return true
        }
    }

    /**
     * Plucked string: Karplus-Strong with a one-zero loss filter and an all-pass for exact tuning.
     * The loop gain makes up for the filter's loss at the fundamental, so high strings ring for
     * their real decay time instead of dying in a few milliseconds.
     */
    private class PluckVoice(key: Int, vel: Int, rate: Int, harp: Boolean) : SynthVoice(key) {
        private val f0 = Pitch.frequency(key)
        private val buf: FloatArray
        private var idx = 0
        private val n: Int
        private val apC: Float
        private var apX1 = 0f
        private var apY1 = 0f
        private var last = 0f
        /** Loss-filter weight: y = (1 - s) x + s x[-1]; lighter (brighter, less lossy) for high strings. */
        private val s: Float
        private val loopGain: Float
        private var relGain = 1f
        private val amp = (0.25 + 0.75 * vel / 127.0).toFloat() * 1.26f
        private var t = 0.0
        private val dt = 1.0 / rate
        private var silent = 0

        init {
            val w = 2 * PI * f0 / rate
            // Decay time (T60) shorter for high strings; harp rings longer.
            val t60 = (if (harp) 5.0 else 3.2) * sqrt(110.0 / f0).coerceIn(0.25, 1.6)
            val targetGain = 10.0.pow(-3.0 / (t60 * f0))
            fun filterGain(sv: Double) = sqrt((1 - sv) * (1 - sv) + sv * sv + 2 * sv * (1 - sv) * kotlin.math.cos(w))
            // Start from a darker filter for low strings; lighten it until its loss per period
            // leaves room for the target decay (otherwise high notes would die within milliseconds).
            var sv = 0.5 - 0.3 * ((f0 - 200) / 1500).coerceIn(0.0, 1.0)
            while (sv > 0.002 && filterGain(sv) < targetGain * 1.0001) sv *= 0.7
            s = sv.toFloat()
            // Loop delay = n samples + the loss filter's phase delay at f0 + all-pass fraction d.
            val filterDelay = kotlin.math.atan2(s * sin(w), (1 - s) + s * kotlin.math.cos(w)) / w
            val total = rate / f0
            n = floor(total - filterDelay - 0.1).toInt().coerceAtLeast(2)
            val d = total - filterDelay - n
            apC = ((1 - d) / (1 + d)).toFloat()
            buf = FloatArray(n)
            val rnd = Random(key)
            // Excitation: noise, darker for soft notes, then a pluck-position comb.
            val bright = 0.3 + 0.6 * vel / 127.0 + if (harp) 0.1 else 0.0
            var lp = 0.0
            val raw = DoubleArray(n)
            for (i in 0 until n) {
                lp += bright * ((rnd.nextDouble() * 2 - 1) - lp)
                raw[i] = lp
            }
            val pos = ((if (harp) 0.5 else 0.18) * n).toInt().coerceAtLeast(1)
            var mean = 0.0
            for (i in 0 until n) { buf[i] = (raw[i] - raw[(i + pos) % n]).toFloat(); mean += buf[i] }
            mean /= n
            var peak = 1e-6f
            for (i in 0 until n) { buf[i] -= mean.toFloat(); peak = maxOf(peak, abs(buf[i])) }
            for (i in 0 until n) buf[i] /= peak
            // Stable as long as the total gain stays below 1 at DC, where the filter passes everything.
            loopGain = (targetGain / filterGain(sv)).coerceAtMost(0.99995).toFloat()
        }

        override fun render(out: FloatArray, off: Int, len: Int, gain: Float): Boolean {
            for (i in 0 until len) {
                val x = buf[idx]
                val avg = (1 - s) * x + s * last
                last = x
                // First-order all-pass for the fractional part of the delay.
                val y = apC * avg + apX1 - apC * apY1
                apX1 = avg; apY1 = y
                buf[idx] = y * loopGain * relGain
                idx = if (idx + 1 == n) 0 else idx + 1
                if (releasing) relGain = maxOf(0f, relGain * 0.9993f)
                out[off + i] += x * amp * gain
                if (abs(x) < 1e-4f) silent++ else silent = 0
                t += dt
            }
            age = t
            return silent < n * 4 && relGain > 1e-3f
        }
    }

    /** Electric piano: carrier with a decaying FM index, plus a short high "tine" partial. */
    private class FmVoice(key: Int, vel: Int, private val rate: Int) : SynthVoice(key) {
        private val f = Pitch.frequency(key)
        private var t = 0.0
        private val dt = 1.0 / rate
        private val v = vel / 127.0
        private val decay = 2.2 * (440.0 / f).pow(0.3).coerceIn(0.4, 3.0)
        private var rel = 1.0
        private val relFactor = exp(-dt * 6.9 / 0.25)
        private var pc = 0.0
        private var pm = 0.0
        private var pt = 0.0

        override fun render(out: FloatArray, off: Int, len: Int, gain: Float): Boolean {
            for (i in 0 until len) {
                val index = (0.5 + 2.5 * v) * exp(-t / 0.5)
                pm += 2 * PI * f / rate
                pc += 2 * PI * f / rate
                pt += 2 * PI * f * 14 / rate
                var s = sin(pc + index * sin(pm))
                if (f * 14 < rate * 0.45) s += 0.15 * v * sin(pt) * exp(-t / 0.08)
                val env = minOf(1.0, t / 0.003) * exp(-t / decay)
                if (releasing) { rel *= relFactor; if (rel < 1e-4) return false }
                out[off + i] += (s * env * rel * (0.12 + 0.24 * v) * gain).toFloat()
                t += dt
            }
            age = t
            return exp(-t / decay) > 1e-4
        }
    }

    /** Church organ: additive drawbars with a quick attack and release, no decay. */
    private class OrganVoice(key: Int, vel: Int, private val rate: Int) : SynthVoice(key) {
        private val f = Pitch.frequency(key)
        // Footages as multiples of the 8' pitch: 8', 4', 2 2/3', 2', 1 3/5', 1'. No 16': it makes
        // the written note sound an octave low, which is wrong for playing back a transcription.
        private val ratios = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 8.0)
        private val levels = doubleArrayOf(1.0, 0.7, 0.35, 0.45, 0.15, 0.2)
        private val phases = DoubleArray(ratios.size)
        private var t = 0.0
        private val dt = 1.0 / rate
        private var rel = 1.0
        private val relFactor = exp(-dt * 6.9 / 0.06)
        private val amp = 0.12 * (0.7 + 0.3 * vel / 127.0)

        override fun render(out: FloatArray, off: Int, len: Int, gain: Float): Boolean {
            for (i in 0 until len) {
                var s = 0.0
                for (k in ratios.indices) {
                    if (f * ratios[k] >= rate * 0.45) continue
                    phases[k] += 2 * PI * f * ratios[k] / rate
                    s += levels[k] * sin(phases[k])
                }
                val env = minOf(1.0, t / 0.01)
                if (releasing) { rel *= relFactor; if (rel < 1e-4) return false }
                out[off + i] += (s * env * rel * amp * gain).toFloat()
                t += dt
            }
            age = t
            return true
        }
    }
}
