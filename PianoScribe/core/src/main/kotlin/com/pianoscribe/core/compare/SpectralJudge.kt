package com.pianoscribe.core.compare

import com.pianoscribe.core.audio.Fft
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.perform.Instrument
import com.pianoscribe.core.perform.PlayedNote
import com.pianoscribe.core.synth.InstrumentSynth
import com.pianoscribe.core.synth.NoteSequencer
import com.pianoscribe.core.synth.Sf2Preset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.roundToInt

/**
 * Judges notes by listening to the audio itself instead of asking the note model again: it
 * resynthesises a short stretch with and without a note and checks which version is closer to the
 * recording in a semitone-band log spectrogram. This catches notes the model imagined (the model
 * would "hear" them in its own output) and notes it missed.
 *
 * Timbre differs between the recording and the piano resynthesis, so only differences matter:
 * a note is kept when it makes the resynthesis clearly closer to the recording around it.
 */
class SpectralJudge(
    private val input: FloatArray,
    private val rate: Int,
    private val preset: Sf2Preset?,
    private val instrument: Instrument = Instrument.PIANO,
) {
    private val fft = 2048
    private val hop = 512
    private val window = DoubleArray(fft) { 0.5 - 0.5 * cos(2 * PI * it / fft) }
    private val loBand = 21
    private val hiBand = minOf(127, Math.floor(69 + 12 * log2(rate * 0.45 / 440.0)).toInt())
    private val bands = hiBand - loBand + 1
    /** FFT bin -> band index (-1 = outside). */
    private val binBand = IntArray(fft / 2 + 1) { b ->
        if (b == 0) -1 else {
            val m = (69 + 12 * log2(b.toDouble() * rate / fft / 440.0)).roundToInt()
            if (m in loBand..hiBand) m - loBand else -1
        }
    }
    private val inputSpec: Array<DoubleArray> = spectrogram(input, 0, input.size)
    private val floor: Double = run {
        var s = 0.0; var n = 0
        for (f in inputSpec) for (v in f) { s += v; n++ }
        (s / n.coerceAtLeast(1)) * 1e-2 + 1e-12
    }
    /** Render level relative to the input, fitted on the whole piece by [calibrate]. */
    var gain = 1.0
        private set

    val frames: Int get() = inputSpec.size

    /** Fits the overall level of the resynthesis to the recording. */
    fun calibrate(notes: List<Note>) {
        val r = spectrogram(render(notes, 0, input.size), 0, input.size)
        var si = 0.0; var sr = 0.0
        for (f in inputSpec.indices) for (b in 0 until bands) { si += inputSpec[f][b]; sr += r[f][b] }
        gain = if (sr > 0) si / sr else 1.0
    }

    /**
     * Share of the recording's sound the MIDI explains, 0..1: how much closer the resynthesis is
     * to the recording than silence is. [error] of the notes vs [error] of no notes.
     */
    fun soundMatch(notes: List<Note>): Double {
        val silent = silenceError
        return if (silent <= 0) 0.0 else ((silent - error(notes)) / silent).coerceIn(0.0, 1.0)
    }

    /** Per-second sound match, NaN where the recording is near silent. */
    fun soundMatchTimeline(notes: List<Note>): DoubleArray {
        val r = spectrogram(render(notes, 0, input.size), 0, input.size)
        val fps = rate.toDouble() / hop
        val seconds = (inputSpec.size / fps).toInt() + 1
        return DoubleArray(seconds) { s ->
            val f0 = (s * fps).toInt()
            val f1 = minOf(inputSpec.size, ((s + 1) * fps).toInt())
            var energy = 0.0
            for (f in f0 until f1) for (b in 0 until bands) energy += inputSpec[f][b]
            if (f1 <= f0 || energy / ((f1 - f0) * bands) < floor * 2) Double.NaN else {
                val e = distance(r, f0, f1, 0)
                val z = distanceToSilence(f0, f1)
                if (z <= 0) Double.NaN else ((z - e) / z).coerceIn(0.0, 1.0)
            }
        }
    }

    private val silenceError: Double by lazy { distanceToSilence(0, inputSpec.size) }

    private fun distanceToSilence(f0: Int, f1: Int): Double {
        var d = 0.0; var n = 0
        val zero = log10(floor)
        for (f in f0 until f1) for (k in 0 until bands) { d += abs(log10(inputSpec[f][k] + floor) - zero); n++ }
        return if (n == 0) 0.0 else d / n
    }

    /** Mean absolute log-spectral difference over the whole piece (lower = closer). */
    fun error(notes: List<Note>): Double {
        val r = spectrogram(render(notes, 0, input.size), 0, input.size)
        return distance(r, 0, inputSpec.size, 0)
    }

    /**
     * How much [note] helps: error without it minus error with it, over the frames the note
     * affects (its span plus 150 ms). Positive = the recording sounds more like the version with
     * the note. [others] are all the other notes.
     */
    fun contribution(note: Note, others: List<Note>): Double {
        val f0 = maxOf(0, ((note.start - 0.05) * rate / hop).toInt())
        val f1 = minOf(inputSpec.size, ((note.end + 0.15) * rate / hop).toInt() + 1)
        if (f1 <= f0) return 0.0
        // Render with 0.5 s of pre-roll (notes already sounding restart there; their attack is the
        // same in both versions and has faded by the scored frames). Start on a frame boundary so
        // rendered frames line up with the recording's, and only analyse the scored frames.
        val s0 = maxOf(0, f0 - (rate / 2 + hop - 1) / hop) * hop
        val s1 = minOf(input.size, f1 * hop + fft)
        val near = others.filter { it.end > s0.toDouble() / rate && it.start < s1.toDouble() / rate }
        val offset = s0 / hop
        val without = spectrogram(render(near, s0, s1), s0, s1, f0 - offset)
        val with = spectrogram(render(near + note, s0, s1), s0, s1, f0 - offset)
        return distance(without, f0, f1, f0) - distance(with, f0, f1, f0)
    }

    companion object {
        /** Instruments tried as resynthesis timbre. Monophonic ones only for single-line music. */
        val POLY_CANDIDATES = listOf(Instrument.PIANO, Instrument.ELECTRIC_PIANO, Instrument.GUITAR, Instrument.HARP, Instrument.ORGAN)
        val MONO_CANDIDATES = listOf(Instrument.FLUTE, Instrument.CLARINET, Instrument.VIOLIN, Instrument.CELLO)

        /**
         * Picks the resynthesis instrument whose sound is closest to the recording (over the first
         * [seconds] of it), so notes are judged with a timbre like the recording's. Returns the
         * judge for that instrument, already calibrated.
         */
        fun bestMatch(input: FloatArray, rate: Int, notes: List<Note>, presetFor: (Instrument) -> Sf2Preset?, seconds: Double = 30.0): SpectralJudge {
            val n = minOf(input.size, (seconds * rate).toInt())
            val excerpt = input.copyOf(n)
            val t = n.toDouble() / rate
            val inExcerpt = notes.filter { it.start < t }
            val mono = maxSimultaneous(notes) <= 1
            val candidates = POLY_CANDIDATES + if (mono) MONO_CANDIDATES else emptyList()
            val best = candidates.minBy { inst ->
                SpectralJudge(excerpt, rate, presetFor(inst), inst).run { calibrate(inExcerpt); error(inExcerpt) }
            }
            return SpectralJudge(input, rate, presetFor(best), best).apply { calibrate(notes) }
        }

        private fun maxSimultaneous(notes: List<Note>): Int {
            val events = notes.flatMap { listOf(it.start to 1, it.end - 0.03 to -1) }.sortedWith(compareBy({ it.first }, { it.second }))
            var cur = 0; var max = 0
            for ((_, d) in events) { cur += d; max = maxOf(max, cur) }
            return max
        }
    }

    /** The resynthesis instrument this judge uses. */
    val timbre: Instrument get() = instrument

    private fun distance(r: Array<DoubleArray>, f0: Int, f1: Int, offset: Int): Double {
        var d = 0.0; var n = 0
        for (f in f0 until f1) {
            val rf = f - offset
            if (rf !in r.indices || f !in inputSpec.indices) continue
            val a = inputSpec[f]; val b = r[rf]
            for (k in 0 until bands) {
                d += abs(log10(a[k] + floor) - log10(gain * b[k] + floor))
                n++
            }
        }
        return if (n == 0) 0.0 else d / n
    }

    private fun render(notes: List<Note>, from: Int, to: Int): FloatArray {
        val out = FloatArray(to - from)
        val synth = InstrumentSynth(preset, rate, instrument).apply { expression = 0f }
        val t0 = from.toDouble() / rate
        val seq = NoteSequencer(synth, notes.filter { it.end > t0 }.map { PlayedNote.of(it).let { p -> if (p.start < t0) p.copy(start = t0, duration = p.end - t0) else p } })
        seq.seek(t0)
        seq.render(out, 0, out.size, 1.0)
        return out
    }

    /**
     * Semitone-band power spectrogram of [x], which holds samples [from, to) of the piece. Element i
     * is frame [firstFrame] + i, centred at sample from + frame * hop (frames before [firstFrame] are
     * skipped, not computed).
     */
    private fun spectrogram(x: FloatArray, from: Int, to: Int, firstFrame: Int = 0): Array<DoubleArray> {
        val n = (to - from) / hop - firstFrame
        val re = DoubleArray(fft)
        val im = DoubleArray(fft)
        return Array(maxOf(0, n)) { i ->
            val centre = (firstFrame + i) * hop
            val out = DoubleArray(bands)
            for (i in 0 until fft) {
                val idx = centre + i - fft / 2
                re[i] = if (idx in x.indices) x[idx] * window[i] else 0.0
                im[i] = 0.0
            }
            Fft.fft(re, im)
            for (b in 1..fft / 2) {
                val band = binBand[b]
                if (band >= 0) out[band] += re[b] * re[b] + im[b] * im[b]
            }
            out
        }
    }
}
