package com.pianoscribe.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.pianoscribe.core.audio.StreamingResampler
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.NoteIdGenerator
import com.pianoscribe.core.transcribe.ActivationAssembler
import com.pianoscribe.core.transcribe.BasicPitch
import com.pianoscribe.core.transcribe.NotePostProcessor
import com.pianoscribe.core.transcribe.PianoEnsemble
import com.pianoscribe.core.transcribe.PianoModel
import com.pianoscribe.core.transcribe.TranscriptionSettings
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs

/**
 * Accuracy of Basic Pitch, the high-resolution piano model and their combination on the
 * evaluation set (CI runs it after converting the models; skipped otherwise). Also checks that
 * the Kotlin post-processing reproduces the reference Python pipeline.
 */
class PianoModelEval {
    private val env = OrtEnvironment.getEnvironment()

    private fun f32(f: File): FloatArray {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(b.remaining()).also { b.get(it) }
    }

    private fun basicPitch(s: OrtSession, audio: FloatArray): List<Note> {
        val asm = ActivationAssembler(audio.size)
        val w = FloatArray(BasicPitch.AUDIO_N_SAMPLES)
        for (i in 0 until BasicPitch.windowCount(audio.size)) {
            BasicPitch.fillWindow(i, audio.size, w) { d, off, st, c -> System.arraycopy(audio, st, d, off, c); c }
            OnnxTensor.createTensor(env, FloatBuffer.wrap(w), longArrayOf(1, w.size.toLong(), 1)).use { t ->
                s.run(mapOf(s.inputNames.first() to t)).use { r ->
                    val m = r.associate { it.key to (it.value as OnnxTensor).floatBuffer.let { fb -> FloatArray(fb.remaining()).also { a -> fb.get(a) } } }
                    asm.add(i, m.getValue("StatefulPartitionedCall:1"), m.getValue("StatefulPartitionedCall:2"), m.getValue("StatefulPartitionedCall:0"))
                }
            }
        }
        return NotePostProcessor.process(asm.activations, TranscriptionSettings(), NoteIdGenerator())
    }

    private fun piano(s: OrtSession, audio22k: FloatArray): PianoModel.Output {
        val audio = StreamingResampler.resample(audio22k, BasicPitch.SAMPLE_RATE, PianoModel.SAMPLE_RATE)
        val seg = FloatArray(PianoModel.SEGMENT_SAMPLES)
        val segments = (0 until PianoModel.segmentCount(audio.size)).map { i ->
            PianoModel.fillSegment(audio, i, seg)
            OnnxTensor.createTensor(env, FloatBuffer.wrap(seg), longArrayOf(1, seg.size.toLong())).use { t ->
                s.run(mapOf("audio" to t)).use { r ->
                    fun get(name: String): Array<FloatArray> {
                        val fb = (r.get(name).get() as OnnxTensor).floatBuffer
                        val frames = fb.remaining() / PianoModel.KEYS
                        return Array(frames) { FloatArray(PianoModel.KEYS).also { row -> fb.get(row) } }
                    }
                    PianoModel.Segment(get("onset"), get("offset"), get("frame"), get("velocity"))
                }
            }
        }
        return PianoModel.stitch(segments)
    }

    data class Truth(val pitch: Int, val start: Double, val end: Double)

    /** Onset F1 (50 ms, mir_eval style one-to-one matching); [offsets] also needs the end within 20% / 50 ms. */
    private fun f1(est: List<Note>, truth: List<Truth>, offsets: Boolean = false): Triple<Double, Double, Double> {
        val used = BooleanArray(truth.size); var tp = 0
        for (n in est.sortedBy { it.start }) {
            val i = truth.indices.firstOrNull { !used[it] && truth[it].pitch == n.pitch && abs(truth[it].start - n.start) <= 0.05 &&
                (!offsets || abs(truth[it].end - n.end) <= maxOf(0.05, 0.2 * (truth[it].end - truth[it].start))) }
            if (i != null) { used[i] = true; tp++ }
        }
        val p = tp.toDouble() / est.size.coerceAtLeast(1); val r = tp.toDouble() / truth.size.coerceAtLeast(1)
        return Triple(p, r, if (p + r == 0.0) 0.0 else 2 * p * r / (p + r))
    }

    @Test fun evaluate() {
        val pianoPath = System.getenv("PIANO_ONNX"); val bpPath = System.getenv("BASIC_PITCH_ONNX"); val data = System.getenv("EVAL_DATA")
        assumeTrue("set PIANO_ONNX, BASIC_PITCH_ONNX and EVAL_DATA to run", pianoPath != null && bpPath != null && data != null)
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(Runtime.getRuntime().availableProcessors()) }
        val bp = env.createSession(bpPath, opts)
        val models = pianoPath!!.split(",").associate { File(it).name to env.createSession(it, opts) }
        val evalDir = File(data!!)
        val sums = LinkedHashMap<String, DoubleArray>()
        fun add(k: String, v: Triple<Double, Double, Double>) = sums.getOrPut(k) { DoubleArray(4) }.let { it[0] += v.first; it[1] += v.second; it[2] += v.third; it[3]++ }
        var parityWorst = 1.0
        for (f in evalDir.resolve("eval22k").listFiles()!!.filter { it.name.endsWith(".f32") }.sortedBy { it.name }) {
            val name = f.name.removeSuffix(".f32")
            val piece = name.substringBefore("-")
            val cond = name.substringAfter("-")
            val truth = File(System.getenv("EVAL_TRUTH") ?: evalDir.path, "$piece.csv").readLines().drop(1).filter { it.isNotBlank() }
                .map { it.split(",").let { c -> Truth(c[0].toInt(), c[1].toDouble(), c[2].toDouble()) } }
            val audio = f32(f)
            val bpNotes = basicPitch(bp, audio)
            add("$cond | Basic Pitch", f1(bpNotes, truth))
            for ((mName, s) in models) {
                val t0 = System.nanoTime()
                val out = piano(s, audio)
                val ms = (System.nanoTime() - t0) / 1e6
                val hr = PianoModel.notes(out).filter { it.start < audio.size.toDouble() / BasicPitch.SAMPLE_RATE }
                add("$cond | $mName", f1(hr, truth))
                add("$cond | $mName (with offsets)", f1(hr, truth, offsets = true))
                val ens = PianoEnsemble.combine(hr, out, bpNotes)
                add("$cond | $mName + Basic Pitch combined", f1(ens, truth))
                println("EVAL $name $mName: ${"%.0f".format(ms)} ms for ${"%.1f".format(audio.size / 22050.0)} s")
                // Port check against the official Python pipeline (fp32 model only).
                val refFile = evalDir.resolve("reference/$name.json")
                if (mName.contains("fp32") && refFile.exists()) {
                    val ref = Regex("""\{"pitch": (\d+), "start": ([\d.e-]+), "end": ([\d.e-]+)""").findAll(refFile.readText())
                        .map { Truth(it.groupValues[1].toInt(), it.groupValues[2].toDouble(), it.groupValues[3].toDouble()) }.toList()
                    val agree = f1(hr, ref).third
                    parityWorst = minOf(parityWorst, agree)
                    println("PARITY $name: Kotlin port vs Python reference F1 ${"%.3f".format(agree)} (${hr.size} vs ${ref.size} notes)")
                }
            }
        }
        println("RESULTS (onset within 50 ms; P / R / F1, mean over pieces)")
        for ((k, v) in sums) println("RESULT %-62s P %.3f  R %.3f  F1 %.3f".format(k, v[0] / v[3], v[1] / v[3], v[2] / v[3]))
        println("PARITY worst %.3f".format(parityWorst))
        if (models.keys.any { it.contains("fp32") }) assert(parityWorst > 0.97) { "Kotlin post-processing differs from the reference" }
    }
}
