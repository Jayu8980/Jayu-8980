package com.autotube.ai.studio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** Records a mono AAC voiceover to an .m4a file. */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recorder != null

    fun start(target: File) {
        cancel()
        target.parentFile?.mkdirs()
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(128_000)
            r.setOutputFile(target.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            runCatching { r.release() }
            target.delete()
            throw e
        }
        recorder = r
        file = target
        startedAt = System.currentTimeMillis()
    }

    /** Stops and returns the recording, or null when it was too short or failed. */
    fun stop(): File? {
        val r = recorder ?: return null
        val f = file
        recorder = null
        file = null
        val ok = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        if (!ok || f == null || !f.isFile || f.length() == 0L) {
            f?.delete()
            return null
        }
        return f
    }

    fun elapsedMs(): Long = if (recorder != null) System.currentTimeMillis() - startedAt else 0L

    /** Stops and discards any recording in progress. */
    fun cancel() {
        stop()?.delete()
    }
}
