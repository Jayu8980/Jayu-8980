package com.pianoscribe.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.pianoscribe.core.audio.StreamingResampler
import com.pianoscribe.core.model.Hand
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.TimeGrid
import com.pianoscribe.core.perform.Instrument
import com.pianoscribe.core.perform.Performer
import com.pianoscribe.core.synth.InstrumentSynth
import com.pianoscribe.core.synth.NoteSequencer
import com.pianoscribe.core.transcribe.BasicPitch
import com.pianoscribe.core.transcribe.Crepe
import com.pianoscribe.core.transcribe.MonoNotes
import com.pianoscribe.core.transcribe.SourceInstrument
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * CREPE for one voice or melody instrument (CI, after the export; skipped otherwise): the Kotlin
 * port against torchcrepe's own pitch track, and note accuracy on synthesised melodies (flute,
 * violin, clarinet, cello, played with vibrato and human timing, clean and with noise) against
 * Basic Pitch written for the same instrument, as the app does without it.
 */
class CrepeEval {
    private val env = OrtEnvironment.getEnvironment()
    private val helper = PianoModelEval()

    private fun track(s: OrtSession, audio16k: FloatArray, minMidi: Double, maxMidi: Double): Crepe.Track {
        val frames = Crepe.frameCount(audio16k.size)
        val probs = FloatArray(frames * Crepe.BINS)
        val batch = 512
        val buf = FloatArray(batch * Crepe.WINDOW)
        var f = 0
        while (f < frames) {
            val cnt = minOf(batch, frames - f)
            Crepe.fillFrames(audio16k, f, cnt, buf)
            OnnxTensor.createTensor(env, FloatBuffer.wrap(buf, 0, cnt * Crepe.WINDOW), longArrayOf(cnt.toLong(), Crepe.WINDOW.toLong())).use { t ->
                s.run(mapOf("frames" to t)).use { r -> (r.get(0) as OnnxTensor).floatBuffer.get(probs, f * Crepe.BINS, cnt * Crepe.BINS) }
            }
            f += cnt
        }
        return Crepe.decode(probs, frames, minMidi, maxMidi)
    }

    private fun melody(instrument: Instrument, seed: Int): List<Note> {
        val rnd = Random(seed)
        val lo = instrument.low + 4; val hi = minOf(instrument.high - 4, lo + 19)
        var p = (lo + hi) / 2
        var t = 0.5
        val beat = 60.0 / 96
        return List(28) { i ->
            if (rnd.nextFloat() > 0.2f) p = (p + listOf(-4, -2, -1, 1, 2, 3, 5)[rnd.nextInt(7)]).coerceIn(lo, hi) // else: the same note again
            val d = listOf(0.5, 1.0, 1.0, 1.5, 2.0)[rnd.nextInt(5)] * beat
            val n = Note(i + 1L, p, t, d * 0.92, 80, 1f, Hand.RIGHT)
            t += d + if (rnd.nextFloat() < 0.15f) beat else 0.0
            n
        }
    }

    @Test fun evaluate() {
        val dirPath = System.getenv("CREPE_DIR"); val bpPath = System.getenv("BASIC_PITCH_ONNX")
        assumeTrue("set CREPE_DIR and BASIC_PITCH_ONNX to run", dirPath != null && bpPath != null)
        val dir = File(dirPath!!)
        val bp = env.createSession(bpPath, OrtSession.SessionOptions())
        val sizes = listOf("tiny", "full").associateWith { env.createSession(File(dir, "crepe_$it.onnx").path, OrtSession.SessionOptions()) }

        // 1. Port check: the same pitch as torchcrepe on its test signal.
        val test = helper.f32(File(dir, "reference/test16k.f32"))
        for ((size, s) in sizes) {
            val ref = helper.f32(File(dir, "reference/${size}_pitch.f32"))
            val refPer = helper.f32(File(dir, "reference/${size}_periodicity.f32"))
            val mine = track(s, test, 31.0, 95.0)
            val errs = ArrayList<Double>(); var voicedAgree = 0
            for (i in 0 until minOf(ref.size, mine.size)) {
                val rv = refPer[i] >= 0.5f; val mv = mine.periodicity[i] >= 0.5f
                if (rv == mv) voicedAgree++
                if (rv && mv) errs += abs((69 + 12 * log2(ref[i] / 440.0)) - mine.midi[i]) * 100
            }
            errs.sort()
            println("CREPEPARITY $size: ${mine.size} vs ${ref.size} frames, median |cents| ${"%.1f".format(errs.getOrElse(errs.size / 2) { -1.0 })}, " +
                "95th ${"%.1f".format(errs.getOrElse((errs.size * 0.95).toInt()) { -1.0 })}, voicing agrees ${"%.3f".format(voicedAgree.toDouble() / minOf(ref.size, mine.size))}")
        }

        // 2. Accuracy on synthesised melodies.
        val sums = LinkedHashMap<String, DoubleArray>()
        fun add(k: String, v: Triple<Double, Double, Double>) = sums.getOrPut(k) { DoubleArray(4) }.let { it[0] += v.first; it[1] += v.second; it[2] += v.third; it[3]++ }
        val pairs = listOf(Instrument.FLUTE to SourceInstrument.FLUTE, Instrument.VIOLIN to SourceInstrument.VIOLIN,
            Instrument.CLARINET to SourceInstrument.CLARINET, Instrument.CELLO to SourceInstrument.CELLO)
        for ((inst, src) in pairs) for (seed in 1..3) {
            val grid = TimeGrid(96.0)
            val played = Performer.perform(melody(inst, seed * 7 + inst.ordinal), grid, inst, Performer.Options(feel = 0.6f, seed = seed.toLong()))
            val truth = played.map { PianoModelEval.Truth(it.pitch, it.start, it.end) }
            val rate = BasicPitch.SAMPLE_RATE
            val synth = InstrumentSynth(null, rate, inst).apply { expression = 0.7f }
            val seq = NoteSequencer(synth, played).apply { seek(0.0) }
            val clean = FloatArray(((played.maxOf { it.end } + 1.0) * rate).toInt())
            var o = 0
            while (o < clean.size) { val n = minOf(4096, clean.size - o); seq.render(clean, o, n, 1.0); o += n }
            val peak = clean.maxOf { abs(it) }.coerceAtLeast(1e-6f)
            for (i in clean.indices) clean[i] = clean[i] / peak * 0.7f
            val rms = sqrt(clean.sumOf { it.toDouble() * it } / clean.size)
            val noiseRnd = Random(seed)
            val noisy = FloatArray(clean.size) { clean[it] + (noiseRnd.nextFloat() * 2 - 1) * (rms * 0.1 * sqrt(3.0)).toFloat() } // 20 dB SNR
            for ((cond, a22) in listOf("clean" to clean, "noisy" to noisy)) {
                val key = "${inst.label.lowercase()} $cond"
                add("$cond | Basic Pitch", helper.f1(src.shape(helper.basicPitch(bp, a22)), truth))
                val a16 = StreamingResampler.resample(a22, rate, Crepe.SAMPLE_RATE)
                val db = MonoNotes.rmsDb(a16, Crepe.frameCount(a16.size))
                for ((size, s) in sizes) {
                    val tr = track(s, a16, src.low - 1.0, src.high + 1.0)
                    val notes = src.shape(MonoNotes.notes(tr, db))
                    val r = helper.f1(notes, truth)
                    add("$cond | CREPE $size", r)
                    add("$cond | CREPE $size (with offsets)", helper.f1(notes, truth, offsets = true))
                    println("CREPEEVAL $key seed $seed $size: F1 ${"%.3f".format(r.third)} (${notes.size} vs ${truth.size} notes)")
                }
            }
        }
        println("CREPERESULTS (onset within 50 ms; P / R / F1, mean over melodies)")
        for ((k, v) in sums) println("CREPERESULT %-40s P %.3f  R %.3f  F1 %.3f".format(k, v[0] / v[3], v[1] / v[3], v[2] / v[3]))
    }
}
