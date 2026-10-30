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
import java.nio.FloatBuffer
import kotlin.coroutines.coroutineContext

/**
 * Runs Transkun 2.0, the high-accuracy piano model (ONNX Runtime), over a whole recording. It
 * ships in the APK as assets/models/transkun/ when the build included it; [isAvailable] says
 * whether it did. The network's large per-segment output (about 64 MB) stays in ONNX Runtime's
 * native memory and is read one 10-key chunk (7 MB) at a time, so the app's heap only ever holds
 * one chunk and its interval scores.
 */
object TranskunRunner {
    private const val DIR = "models/transkun"
    private val MODELS = listOf("transkun_core.onnx", "transkun_score.onnx", "transkun_heads.onnx")
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

    private class Net(private val env: OrtEnvironment, context: Context, private val fe: Transkun.Frontend) : Transkun.Net, AutoCloseable {
        private val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4)) }
        private val core = env.createSession(modelFile(context, MODELS[0]).path, opts)
        private val score = env.createSession(modelFile(context, MODELS[1]).path, opts)
        private val heads = env.createSession(modelFile(context, MODELS[2]).path, opts)
        private val t = fe.nFrame.toLong(); private val d = fe.d.toLong()
        /** The last segment's outputs, in native memory until the next segment. */
        private var result: OrtSession.Result? = null
        override val chunks: Int = core.outputNames.size

        override fun core(features: FloatArray) {
            result?.close(); result = null
            OnnxTensor.createTensor(env, FloatBuffer.wrap(features), longArrayOf(1, t, fe.nMel.toLong(), fe.nWin.toLong())).use { tensor ->
                result = core.run(mapOf("features" to tensor))
            }
        }

        override fun ctx(index: Int): FloatArray =
            (result!!.get(index) as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }

        override fun score(ctx: FloatArray, n: Int): Transkun.Scores =
            OnnxTensor.createTensor(env, FloatBuffer.wrap(ctx), longArrayOf(n.toLong(), t, d)).use { tensor ->
                score.run(mapOf("ctx" to tensor)).use { r -> Transkun.Scores((r.get(0) as OnnxTensor).floatBuffer, (r.get(1) as OnnxTensor).floatBuffer) }
            }

        override fun heads(attr: FloatArray, n: Int): Pair<FloatArray, FloatArray> =
            OnnxTensor.createTensor(env, FloatBuffer.wrap(attr), longArrayOf(n.toLong(), 3 * d)).use { tensor ->
                heads.run(mapOf("attr" to tensor)).use { r ->
                    fun arr(i: Int) = (r.get(i) as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
                    arr(0) to arr(1)
                }
            }

        override fun close() {
            result?.close(); core.close(); score.close(); heads.close()
        }
    }

    /** [audio22k] is mono 22050 Hz; [onProgress] gets (segments done, total). Notes on piano keys only. */
    suspend fun run(context: Context, audio22k: FloatArray, ids: NoteIdGenerator, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<Note> {
        val fe = Transkun.Frontend.parse(context.assets.open("$DIR/frontend.bin").use { it.readBytes() })
        val audio = StreamingResampler.resample(audio22k, BasicPitch.SAMPLE_RATE, fe.fs)
        val ctx = coroutineContext
        Net(OrtEnvironment.getEnvironment(), context, fe).use { net ->
            return Transkun.transcribe(fe, net, audio, ids) { done, total ->
                ctx.ensureActive()
                onProgress(done, total)
            }
        }
    }
}
