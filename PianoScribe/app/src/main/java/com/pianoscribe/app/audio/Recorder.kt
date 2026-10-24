package com.pianoscribe.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.pianoscribe.core.audio.StreamingResampler
import com.pianoscribe.core.transcribe.BasicPitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/** Microphone recorder writing mono 22050 Hz PCM, with a live level meter. */
class Recorder {
    companion object {
        const val MAX_SECONDS = 600
        private const val RATE = 44100
    }

    private val _levels = MutableStateFlow(FloatArray(0))
    /** Rolling peak levels (newest last), for the live waveform. */
    val levels: StateFlow<FloatArray> = _levels
    private val _elapsed = MutableStateFlow(0.0)
    val elapsed: StateFlow<Double> = _elapsed

    @Volatile var stopRequested = false

    /** Records until [stopRequested] or the 10 minute limit. Returns the number of samples written. */
    @SuppressLint("MissingPermission")
    suspend fun record(out: File): Int = withContext(Dispatchers.IO) {
        stopRequested = false
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, RATE / 5 * 2)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("The microphone is not available.")
        }
        val resampler = StreamingResampler(RATE, BasicPitch.SAMPLE_RATE)
        val writer = PcmFile.Writer(out)
        val shorts = ShortArray(RATE / 20)
        val floats = FloatArray(shorts.size)
        val history = ArrayDeque<Float>()
        var total = 0L
        try {
            rec.startRecording()
            while (isActive && !stopRequested && total < MAX_SECONDS.toLong() * RATE) {
                val n = rec.read(shorts, 0, shorts.size)
                if (n <= 0) continue
                var peak = 0f
                for (i in 0 until n) {
                    val v = shorts[i] / 32768f
                    floats[i] = v
                    if (abs(v) > peak) peak = abs(v)
                }
                writer.write(resampler.process(floats, 0, n))
                total += n
                history.addLast(peak)
                while (history.size > 200) history.removeFirst()
                _levels.value = history.toFloatArray()
                _elapsed.value = total.toDouble() / RATE
            }
            writer.write(resampler.flush())
        } finally {
            runCatching { rec.stop() }
            rec.release()
            writer.close()
        }
        writer.samples.toInt()
    }
}
