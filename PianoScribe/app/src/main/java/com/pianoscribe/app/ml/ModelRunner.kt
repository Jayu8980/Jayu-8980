package com.pianoscribe.app.ml

import android.content.Context
import com.pianoscribe.core.transcribe.ActivationAssembler
import com.pianoscribe.core.transcribe.Activations
import com.pianoscribe.core.transcribe.BasicPitch
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Runs Basic Pitch over a whole signal in overlapping windows and stitches the activations. */
object ModelRunner {
    /**
     * @param read fills dst[off, off + count) with signal samples [src, src + count); returns samples read
     * @param onProgress called after each window with 0..1
     */
    suspend fun run(
        context: Context,
        length: Int,
        read: (dst: FloatArray, off: Int, src: Int, count: Int) -> Int,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Activations {
        val assembler = ActivationAssembler(length)
        val windows = BasicPitch.windowCount(length)
        BasicPitchModel(context).use { model ->
            val window = FloatArray(BasicPitch.AUDIO_N_SAMPLES)
            for (w in 0 until windows) {
                coroutineContext.ensureActive()
                BasicPitch.fillWindow(w, length, window, read)
                val out = model.run(window)
                assembler.add(w, out.note, out.onset, out.contour)
                onProgress(w + 1, windows)
            }
        }
        return assembler.activations
    }

    suspend fun run(context: Context, audio: FloatArray, onProgress: (Int, Int) -> Unit = { _, _ -> }): Activations =
        run(context, audio.size, { dst, off, src, count -> System.arraycopy(audio, src, dst, off, count); count }, onProgress)
}
