package com.pianoscribe.core.synth

import com.pianoscribe.core.perform.Instrument
import com.pianoscribe.core.perform.PlayedNote
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/** Renders a performance to a 16-bit mono WAV stream, chunk by chunk so long songs need little memory. */
object OfflineRenderer {
    const val TAIL_SEC = 2.5

    /** Frames that [render] writes for these notes. */
    fun frameCount(notes: List<PlayedNote>, rate: Int): Int =
        (((notes.maxOfOrNull { it.end } ?: 0.0) + TAIL_SEC) * rate).roundToInt()

    fun render(
        notes: List<PlayedNote>,
        instrument: Instrument,
        preset: Sf2Preset?,
        rate: Int,
        out: OutputStream,
        expression: Float = 0.6f,
        onProgress: (Float) -> Unit = {},
    ) {
        val synth = InstrumentSynth(preset, rate, instrument).apply { this.expression = expression }
        val seq = NoteSequencer(synth, notes).apply { seek(0.0) }
        val total = frameCount(notes, rate)
        writeHeader(out, total, rate)
        val block = FloatArray(4096)
        val bytes = ByteArray(block.size * 2)
        var done = 0
        var lastReport = 0
        while (done < total) {
            val n = minOf(block.size, total - done)
            block.fill(0f, 0, n)
            seq.render(block, 0, n, 1.0)
            for (i in 0 until n) {
                // Gentle limiter: transparent below 0.8, smooth above, never clips.
                val x = block[i]
                val y = if (abs(x) <= 0.8f) x else Math.copySign(0.8f + 0.2f * kotlin.math.tanh((abs(x) - 0.8f) / 0.2f), x)
                val v = (y * 32767).roundToInt().coerceIn(-32768, 32767)
                bytes[2 * i] = v.toByte(); bytes[2 * i + 1] = (v shr 8).toByte()
            }
            out.write(bytes, 0, n * 2)
            done += n
            if (done - lastReport > rate) { lastReport = done; onProgress(done.toFloat() / total) }
        }
        out.flush()
        onProgress(1f)
    }

    /**
     * Renders exactly [frames] mono float samples (no tail), handing them out in chunks: used to turn
     * the MIDI back into audio of the same length as the recording for comparison.
     */
    fun renderMono(notes: List<PlayedNote>, instrument: Instrument, preset: Sf2Preset?, rate: Int, frames: Int, sink: (FloatArray, Int) -> Unit) {
        val synth = InstrumentSynth(preset, rate, instrument).apply { expression = 0f }
        val seq = NoteSequencer(synth, notes).apply { seek(0.0) }
        val block = FloatArray(4096)
        var done = 0
        while (done < frames) {
            val n = minOf(block.size, frames - done)
            block.fill(0f, 0, n)
            seq.render(block, 0, n, 1.0)
            sink(block, n)
            done += n
        }
    }

    /** Canonical 44-byte PCM WAV header. */
    fun writeHeader(out: OutputStream, frames: Int, rate: Int) {
        val dataBytes = frames * 2
        fun i32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        fun i16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        out.write("RIFF".toByteArray(Charsets.US_ASCII)); out.write(i32(36 + dataBytes))
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII)); out.write(i32(16)); out.write(i16(1)); out.write(i16(1))
        out.write(i32(rate)); out.write(i32(rate * 2)); out.write(i16(2)); out.write(i16(16))
        out.write("data".toByteArray(Charsets.US_ASCII)); out.write(i32(dataBytes))
    }
}
