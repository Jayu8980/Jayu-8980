package com.pianoscribe.app.practice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.pianoscribe.core.audio.Yin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PitchReading(val frequency: Double, val probability: Double, val level: Float)

/** Streams monophonic pitch readings from the microphone using YIN (about 20 per second). */
object MicPitchListener {
    private const val RATE = 22050
    private const val SIZE = 2048
    private const val HOP = 1024

    @SuppressLint("MissingPermission")
    fun readings(): Flow<PitchReading?> = callbackFlow {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SIZE * 4))
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            close(IllegalStateException("Microphone unavailable"))
            return@callbackFlow
        }
        val yin = Yin(RATE, SIZE)
        val job = launch(Dispatchers.IO) {
            val frame = FloatArray(SIZE)
            val shorts = ShortArray(HOP)
            try {
                rec.startRecording()
                while (isActive) {
                    val n = rec.read(shorts, 0, HOP)
                    if (n <= 0) continue
                    System.arraycopy(frame, n, frame, 0, SIZE - n)
                    var peak = 0f
                    for (i in 0 until n) {
                        val v = shorts[i] / 32768f
                        frame[SIZE - n + i] = v
                        peak = maxOf(peak, kotlin.math.abs(v))
                    }
                    val r = if (peak > 0.01f) yin.detect(frame) else null
                    trySend(r?.let { PitchReading(it.frequency, it.probability, peak) })
                }
            } finally {
                // Released on the reading thread so a read is never in flight during release().
                runCatching { rec.stop() }
                rec.release()
            }
        }
        awaitClose { job.cancel() }
    }.flowOn(Dispatchers.IO)
}
