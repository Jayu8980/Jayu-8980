package com.pianoscribe.app.ml

import android.content.Context
import com.pianoscribe.core.transcribe.BasicPitch
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Spotify Basic Pitch (ICASSP 2022) running on TensorFlow Lite. One call processes one
 * 2-second window (43844 samples at 22050 Hz) and returns note, onset and contour posteriorgrams.
 */
class BasicPitchModel(context: Context, threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)) : AutoCloseable {
    class Output(val note: FloatArray, val onset: FloatArray, val contour: FloatArray)

    private val interpreter: Interpreter
    private val input = ByteBuffer.allocateDirect(BasicPitch.AUDIO_N_SAMPLES * 4).order(ByteOrder.nativeOrder())
    private val noteIdx: Int
    private val onsetIdx: Int
    private val contourIdx: Int
    private val buffers: Map<Int, ByteBuffer>

    init {
        val model = loadAsset(context, MODEL_ASSET)
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(threads))
        interpreter.resizeInput(0, intArrayOf(1, BasicPitch.AUDIO_N_SAMPLES, 1))
        interpreter.allocateTensors()
        val outs = (0 until interpreter.outputTensorCount).map { i ->
            val t = interpreter.getOutputTensor(i)
            Triple(i, t.name(), t.shape())
        }
        // Outputs are named after the sorted Keras output keys: contour (264 bins), note, onset.
        contourIdx = outs.first { it.third.last() == BasicPitch.CONTOUR_BINS }.first
        val pitched = outs.filter { it.third.last() == BasicPitch.N_PITCHES }.sortedBy { it.second }
        require(pitched.size == 2) { "Unexpected model outputs: ${outs.map { it.second + it.third.contentToString() }}" }
        noteIdx = pitched[0].first
        onsetIdx = pitched[1].first
        buffers = outs.associate { (i, _, shape) ->
            i to ByteBuffer.allocateDirect(shape.fold(1) { a, b -> a * b } * 4).order(ByteOrder.nativeOrder())
        }
    }

    fun run(window: FloatArray): Output {
        input.rewind()
        input.asFloatBuffer().put(window, 0, BasicPitch.AUDIO_N_SAMPLES)
        buffers.values.forEach { it.rewind() }
        interpreter.runForMultipleInputsOutputs(arrayOf<Any>(input), buffers.mapValues { it.value as Any })
        fun read(i: Int): FloatArray {
            val b = buffers.getValue(i)
            b.rewind()
            val fb = b.asFloatBuffer()
            return FloatArray(fb.remaining()).also { fb.get(it) }
        }
        return Output(read(noteIdx), read(onsetIdx), read(contourIdx))
    }

    override fun close() = interpreter.close()

    companion object {
        const val MODEL_ASSET = "models/basic_pitch_nmp.tflite"

        private fun loadAsset(context: Context, path: String): MappedByteBuffer {
            context.assets.openFd(path).use { fd ->
                FileInputStream(fd.fileDescriptor).use { fis ->
                    return fis.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
        }
    }
}
