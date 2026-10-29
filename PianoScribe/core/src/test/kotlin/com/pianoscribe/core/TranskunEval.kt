package com.pianoscribe.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.pianoscribe.core.audio.StreamingResampler
import com.pianoscribe.core.transcribe.BasicPitch
import com.pianoscribe.core.transcribe.PianoEnsemble
import com.pianoscribe.core.transcribe.Transkun
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.FloatBuffer

/**
 * Transkun on the evaluation set (CI, after the export; skipped otherwise): the Kotlin port
 * against the official Python pipeline on the same input, and accuracy as the app would run it
 * (22.05 kHz audio resampled to 44.1 kHz), alone and combined with Basic Pitch.
 */
class TranskunEval {
    private val env = OrtEnvironment.getEnvironment()

    private class OrtNet(env: OrtEnvironment, dir: File, private val fe: Transkun.Frontend) : Transkun.Net {
        private val env = env
        private val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(Runtime.getRuntime().availableProcessors()) }
        private val core = env.createSession(File(dir, "transkun_core.onnx").path, opts)
        private val score = env.createSession(File(dir, "transkun_score.onnx").path, opts)
        private val heads = env.createSession(File(dir, "transkun_heads.onnx").path, opts)
        private var result: OrtSession.Result? = null
        override var chunks = 0

        override fun core(features: FloatArray) {
            result?.close()
            OnnxTensor.createTensor(env, FloatBuffer.wrap(features), longArrayOf(1, fe.nFrame.toLong(), fe.nMel.toLong(), fe.nWin.toLong())).use { t ->
                result = core.run(mapOf("features" to t)).also { chunks = it.size() }
            }
        }

        override fun ctx(index: Int): FloatArray =
            (result!!.get(index) as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }

        override fun score(ctx: FloatArray, n: Int): Transkun.Scores =
            OnnxTensor.createTensor(env, FloatBuffer.wrap(ctx), longArrayOf(n.toLong(), fe.nFrame.toLong(), fe.d.toLong())).use { t ->
                score.run(mapOf("ctx" to t)).use { r -> Transkun.Scores((r.get(0) as OnnxTensor).floatBuffer, (r.get(1) as OnnxTensor).floatBuffer) }
            }

        override fun heads(attr: FloatArray, n: Int): Pair<FloatArray, FloatArray> =
            OnnxTensor.createTensor(env, FloatBuffer.wrap(attr), longArrayOf(n.toLong(), 3L * fe.d)).use { t ->
                heads.run(mapOf("attr" to t)).use { r ->
                    fun arr(i: Int) = (r.get(i) as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
                    arr(0) to arr(1)
                }
            }
    }

    @Test fun evaluate() {
        val dirPath = System.getenv("TRANSKUN_DIR"); val data = System.getenv("EVAL_DATA"); val bpPath = System.getenv("BASIC_PITCH_ONNX")
        assumeTrue("set TRANSKUN_DIR, EVAL_DATA and BASIC_PITCH_ONNX to run", dirPath != null && data != null && bpPath != null)
        val dir = File(dirPath!!)
        val fe = Transkun.Frontend.parse(File(dir, "frontend.bin").readBytes())
        val net = OrtNet(env, dir, fe)
        val bp = env.createSession(bpPath, OrtSession.SessionOptions())
        val helper = PianoModelEval()
        val sums = LinkedHashMap<String, DoubleArray>()
        fun add(k: String, v: Triple<Double, Double, Double>) = sums.getOrPut(k) { DoubleArray(4) }.let { it[0] += v.first; it[1] += v.second; it[2] += v.third; it[3]++ }
        var parityWorst = 1.0
        for (f in File(data!!, "eval22k").listFiles()!!.filter { it.name.endsWith(".f32") }.sortedBy { it.name }) {
            val name = f.name.removeSuffix(".f32")
            val piece = name.substringBefore("-"); val cond = name.substringAfter("-")
            val truth = File(System.getenv("EVAL_TRUTH") ?: data, "$piece.csv").readLines().drop(1).filter { it.isNotBlank() }
                .map { it.split(",").let { c -> PianoModelEval.Truth(c[0].toInt(), c[1].toDouble(), c[2].toDouble()) } }
            // 1. Port check on the official pipeline's exact input.
            val exact = File(dir, "eval44k/$name.f32")
            val ref = File(dir, "reference_tk/$name.json")
            if (exact.exists() && ref.exists()) {
                val refNotes = Regex("""\{"pitch": (\d+), "start": ([\d.e-]+), "end": ([\d.e-]+)""").findAll(ref.readText())
                    .map { PianoModelEval.Truth(it.groupValues[1].toInt(), it.groupValues[2].toDouble(), it.groupValues[3].toDouble()) }.toList()
                val t0 = System.nanoTime()
                val mine = Transkun.transcribe(fe, net, helper.f32(exact))
                val ms = (System.nanoTime() - t0) / 1e6
                val agree = helper.f1(mine, refNotes).third
                parityWorst = minOf(parityWorst, agree)
                println("TKPARITY $name: Kotlin port vs Python F1 ${"%.3f".format(agree)} (${mine.size} vs ${refNotes.size} notes, ${"%.0f".format(ms)} ms)")
            }
            // 2. As the app runs it.
            val a22 = helper.f32(f)
            val a44 = StreamingResampler.resample(a22, BasicPitch.SAMPLE_RATE, Transkun.SAMPLE_RATE)
            val tk = Transkun.transcribe(fe, net, a44).filter { it.start < a22.size.toDouble() / BasicPitch.SAMPLE_RATE }
            val bpNotes = helper.basicPitch(bp, a22)
            add("$cond | Basic Pitch", helper.f1(bpNotes, truth))
            add("$cond | Transkun", helper.f1(tk, truth))
            add("$cond | Transkun (with offsets)", helper.f1(tk, truth, offsets = true))
            add("$cond | Transkun + Basic Pitch combined", helper.f1(PianoEnsemble.combine(tk, bpNotes), truth))
            println("TKEVAL $name: agreement with Basic Pitch ${"%.2f".format(PianoEnsemble.agreement(tk, bpNotes))}")
        }
        println("TKRESULTS (onset within 50 ms; P / R / F1, mean over pieces)")
        for ((k, v) in sums) println("TKRESULT %-50s P %.3f  R %.3f  F1 %.3f".format(k, v[0] / v[3], v[1] / v[3], v[2] / v[3]))
        println("TKPARITY worst %.3f".format(parityWorst))
        assert(parityWorst >= 0.95) { "the Kotlin port differs from the official pipeline" }
    }
}
