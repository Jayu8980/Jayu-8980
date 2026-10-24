package com.pianoscribe.app.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.pianoscribe.core.audio.StreamingResampler
import com.pianoscribe.core.transcribe.BasicPitch
import com.pianoscribe.core.transcribe.PianoModel
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.FloatBuffer
import kotlin.coroutines.coroutineContext

/**
 * Runs the high-resolution piano transcription model (ONNX Runtime) over a whole recording in
 * 10 s segments. The model ships in the APK as assets/models/piano_transcription.onnx when the
 * build included it; [isAvailable] says whether it did.
 */
object PianoModelRunner {
    const val ASSET = "models/piano_transcription.onnx"

    fun isAvailable(context: Context): Boolean = runCatching { context.assets.open(ASSET).close(); true }.getOrDefault(false)

    /** ONNX Runtime maps the model from a file: copy it out of the APK once per app version. */
    private fun modelFile(context: Context): File {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        val f = File(context.noBackupFilesDir, "piano_transcription_$version.onnx")
        if (!f.exists()) {
            context.noBackupFilesDir.listFiles()?.filter { it.name.startsWith("piano_transcription_") }?.forEach { it.delete() }
            val tmp = File(f.path + ".tmp")
            context.assets.open(ASSET).use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
            tmp.renameTo(f)
        }
        return f
    }

    /** [audio22k] is mono 22050 Hz; [onProgress] gets (segments done, total). */
    suspend fun run(context: Context, audio22k: FloatArray, onProgress: (Int, Int) -> Unit = { _, _ -> }): PianoModel.Output {
        val audio = StreamingResampler.resample(audio22k, BasicPitch.SAMPLE_RATE, PianoModel.SAMPLE_RATE)
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
            // The file is optimised offline (CI) without a fusion that breaks on this model; don't redo it.
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
        }
        env.createSession(modelFile(context).path, opts).use { session ->
            val total = PianoModel.segmentCount(audio.size)
            val seg = FloatArray(PianoModel.SEGMENT_SAMPLES)
            val stitcher = PianoModel.Stitcher(total)
            for (i in 0 until total) {
                coroutineContext.ensureActive()
                PianoModel.fillSegment(audio, i, seg)
                OnnxTensor.createTensor(env, FloatBuffer.wrap(seg), longArrayOf(1, seg.size.toLong())).use { t ->
                    session.run(mapOf("audio" to t)).use { r ->
                        fun get(name: String): Array<FloatArray> {
                            val fb = (r.get(name).get() as OnnxTensor).floatBuffer
                            val frames = fb.remaining() / PianoModel.KEYS
                            return Array(frames) { FloatArray(PianoModel.KEYS).also { row -> fb.get(row) } }
                        }
                        stitcher.add(PianoModel.Segment(get("onset"), get("offset"), get("frame"), get("velocity")))
                    }
                }
                onProgress(i + 1, total)
            }
            return stitcher.result()
        }
    }
}
