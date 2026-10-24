package com.pianoscribe.core.synth

import com.pianoscribe.core.theory.Pitch
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Physically-inspired additive piano tone generator used to build the bundled SoundFont
 * (assets/soundfonts/pianoscribe_piano.sf2): inharmonic partials, hammer-position comb filtering,
 * two/three detuned strings per note, two-stage decay and a hammer noise burst.
 */
object PianoSampleGenerator {
    const val SAMPLE_RATE = 24000

    fun tone(midi: Int, sampleRate: Int = SAMPLE_RATE, seconds: Double = durationFor(midi)): ShortArray {
        val f0 = Pitch.frequency(midi)
        val n = (seconds * sampleRate).toInt()
        val out = DoubleArray(n)
        val b = 7e-5 * 2.0.pow((midi - 48) / 18.0)
        val strings = if (midi < 32) 1 else if (midi < 45) 2 else 3
        val detune = doubleArrayOf(0.0, 1.2, -0.9)
        val tau2 = (9.0 * (65.0 / f0).pow(0.55)).coerceIn(0.8, 14.0)
        val tau1 = tau2 / 5.0
        val nyq = sampleRate / 2.0 * 0.92
        val rnd = Random(midi)
        var partial = 1
        while (true) {
            val fn = f0 * partial * sqrt(1 + b * partial * partial)
            if (fn >= nyq || partial > 40) break
            val strike = abs(sin(PI * partial / 7.6))
            val rolloff = 1.0 / (1 + (fn / 3500.0).pow(2.0))
            val a = strike * rolloff / partial.toDouble().pow(0.9)
            val decayScale = 1.0 + 0.18 * (partial - 1) + fn / 3000.0
            val t1 = tau1 / decayScale
            val t2 = tau2 / decayScale
            for (s in 0 until strings) {
                val f = fn * 2.0.pow(detune[s] / 1200.0)
                val w = 2 * PI * f / sampleRate
                val ph = rnd.nextDouble() * 2 * PI
                val e1 = exp(-1.0 / (t1 * sampleRate))
                val e2 = exp(-1.0 / (t2 * sampleRate))
                var env1 = 0.65
                var env2 = 0.35
                // Oscillator via rotation recurrence (cheap and accurate).
                var c = Math.cos(ph); var si = Math.sin(ph)
                val cw = Math.cos(w); val sw = Math.sin(w)
                val amp = a / strings
                for (i in 0 until n) {
                    out[i] += amp * (env1 + env2) * si
                    val nc = c * cw - si * sw
                    si = si * cw + c * sw
                    c = nc
                    env1 *= e1; env2 *= e2
                }
            }
            partial++
        }
        // Hammer thump: short low-passed noise burst.
        var lp = 0.0
        val burst = (0.025 * sampleRate).toInt()
        val alpha = (2 * PI * minOf(4000.0, f0 * 6) / sampleRate).coerceAtMost(1.0)
        for (i in 0 until minOf(n, burst)) {
            lp += alpha * ((rnd.nextDouble() * 2 - 1) - lp)
            out[i] += lp * 0.08 * exp(-i / (0.006 * sampleRate))
        }
        // Attack ramp and end fade.
        val attack = (0.002 * sampleRate).toInt()
        for (i in 0 until minOf(attack, n)) out[i] *= i.toDouble() / attack
        val fade = (0.15 * sampleRate).toInt()
        for (i in 0 until minOf(fade, n)) out[n - 1 - i] *= i.toDouble() / fade
        // Normalise with gentle loudness compensation (treble a bit quieter).
        var peak = 1e-9
        for (v in out) peak = maxOf(peak, abs(v))
        val target = 0.85 * (if (midi > 84) 0.8 else 1.0)
        return ShortArray(n) { (out[it] / peak * target * 32767).roundToInt().coerceIn(-32768, 32767).toShort() }
    }

    fun durationFor(midi: Int): Double = 3.6 * 2.0.pow(-(midi - 21) / 60.0)

    /** Builds the bundled SoundFont: one sample every 4 semitones covering A0..C8. */
    fun buildSoundFont(): ByteArray {
        val samples = ArrayList<Sf2Writer.Sample>()
        var root = 23
        while (root - 2 <= 108) {
            val lo = maxOf(0, if (samples.isEmpty()) 0 else root - 2)
            val hi = if (root + 1 >= 108) 127 else root + 1
            samples += Sf2Writer.Sample("Piano ${Pitch.name(root)}", tone(root), SAMPLE_RATE, root, lo, hi)
            root += 4
        }
        return Sf2Writer.write("PianoScribe Grand", samples)
    }
}

/** Command-line entry: writes the generated SoundFont to the path given as the first argument. */
fun main(args: Array<String>) {
    val path = args.firstOrNull() ?: "pianoscribe_piano.sf2"
    val bytes = PianoSampleGenerator.buildSoundFont()
    File(path).apply { parentFile?.mkdirs() }.writeBytes(bytes)
    println("Wrote ${bytes.size} bytes to $path")
}
