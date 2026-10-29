package com.pianoscribe.app.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.pianoscribe.core.audio.StreamingResampler
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.NoteIdGenerator
import com.pianoscribe.core.transcribe.BasicPitch
import com.pianoscribe.core.transcribe.Transkun
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.coroutines.coroutineContext

/**
 * Runs Transkun 2.0, the high-accuracy piano model (ONNX Runtime), over a whole recording. It
 * ships in the APK as assets/models/transkun/ when the build included it; [isAvailable] says
 * whether it did. The network's large per-segment outputs (about 190 MB) stay in native memory:
 * ONNX Runtime writes them straight into direct buffers ("pinned outputs") that the decoder reads.
 */
object TranskunRunner {
    private const val DIR = "models/transkun"
    private val MODELS = listOf("transkun_core.onnx", "transkun_score.onnx", "transkun_heads.onnx")
    /** Symbols scored per call: 10 x 691 x 691 floats, 19 MB. */
    private const val SYMBOLS_PER_SCORE = 10

    fun isAvailable(context: Context): Boolean = runCatching { context.assets.open("$DIR/${MODELS[0]}").close(); true }.getOrDefault(false)

    /** ONNX Runtime maps the models from files: copy them out of the APK once per app version. */
    private fun modelFile(context: Context, name: String): File {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        val prefix = name.removeSuffix(".onnx") + "_"
        val f = File(context.noBackupFilesDir, "$prefix$version.onnx")
        if (!f.exists()) {
            context.noBackupFilesDir.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
            val tmp = File(f.path + ".tmp")
            context.assets.open("$DIR/$name").use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
            tmp.renameTo(f)
        }
        return f
    }

    private fun direct(floats: Int): FloatBuffer = ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private class Net(private val env: OrtEnvironment, context: Context, private val fe: Transkun.Frontend) : Transkun.Net, AutoCloseable {
        private val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4)) }
        private val core = env.createSession(modelFile(context, MODELS[0]).path, opts)
        private val score = env.createSession(modelFile(context, MODELS[1]).path, opts)
        private val heads = env.createSession(modelFile(context, MODELS[2]).path, opts)
        private val t = fe.nFrame.toLong(); private val s = fe.pitches.size.toLong(); private val d = fe.d.toLong()
        private val featBuf = direct(fe.featureSize)
        private val featTensor = OnnxTensor.createTensor(env, featBuf, longArrayOf(1, t, fe.nMel.toLong(), fe.nWin.toLong()))
        private val q = direct((s * t * d).toInt()); private val k = direct((s * t * d).toInt())
        private val diag = direct((s * t).toInt()); private val ctx = direct((s * t * d).toInt())
        private val coreOut = mapOf(
            "q" to OnnxTensor.createTensor(env, q, longArrayOf(s, t, d)),
            "k" to OnnxTensor.createTensor(env, k, longArrayOf(s, t, d)),
            "diag" to OnnxTensor.createTensor(env, diag, longArrayOf(s, t)),
            "ctx" to OnnxTensor.createTensor(env, ctx, longArrayOf(s, t, d)),
        )
        /** Score outputs by symbol count (all calls but possibly the last use the same count). */
        private val scoreOut = HashMap<Int, Pair<FloatBuffer, OnnxTensor>>()

        override fun core(features: FloatArray): Transkun.CoreOut {
            featBuf.clear(); featBuf.put(features); featBuf.rewind()
            core.run(mapOf("features" to featTensor), coreOut).close()
            return Transkun.CoreOut(q, k, diag, ctx)
        }

        override fun score(q: FloatBuffer, k: FloatBuffer, n: Int): FloatBuffer {
            val (buf, out) = scoreOut.getOrPut(n) { direct(n * fe.nFrame * fe.nFrame).let { b -> b to OnnxTensor.createTensor(env, b, longArrayOf(n.toLong(), t, t)) } }
            val shape = longArrayOf(n.toLong(), t, d)
            OnnxTensor.createTensor(env, q, shape).use { tq ->
                OnnxTensor.createTensor(env, k, shape).use { tk ->
                    score.run(mapOf("q" to tq, "k" to tk), mapOf("score" to out)).close()
                }
            }
            return buf
        }

        override fun heads(attr: FloatArray, n: Int): Pair<FloatArray, FloatArray> =
            OnnxTensor.createTensor(env, FloatBuffer.wrap(attr), longArrayOf(n.toLong(), 3 * d)).use { tensor ->
                heads.run(mapOf("attr" to tensor)).use { r ->
                    fun arr(i: Int) = (r.get(i) as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
                    arr(0) to arr(1)
                }
            }

        override fun close() {
            featTensor.close(); coreOut.values.forEach { it.close() }; scoreOut.values.forEach { it.second.close() }
            core.close(); score.close(); heads.close()
        }
    }

    /** [audio22k] is mono 22050 Hz; [onProgress] gets (segments done, total). Notes on piano keys only. */
    suspend fun run(context: Context, audio22k: FloatArray, ids: NoteIdGenerator, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<Note> {
        val fe = Transkun.Frontend.parse(context.assets.open("$DIR/frontend.bin").use { it.readBytes() })
        val audio = StreamingResampler.resample(audio22k, BasicPitch.SAMPLE_RATE, fe.fs)
        val ctx = coroutineContext
        Net(OrtEnvironment.getEnvironment(), context, fe).use { net ->
            return Transkun.transcribe(fe, net, audio, ids, SYMBOLS_PER_SCORE) { done, total ->
                ctx.ensureActive()
                onProgress(done, total)
            }
        }
    }
}
