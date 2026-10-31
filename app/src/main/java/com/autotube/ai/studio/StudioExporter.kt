package com.autotube.ai.studio

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.autotube.ai.domain.MediaStoreSaver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Result of a Studio export: the MP4 in app storage and its gallery copy (null if saving failed). */
data class StudioExportResult(val file: File, val galleryUri: Uri?)

/**
 * Renders a [StudioProject] to an H.264/AAC MP4 with Media3 Transformer and saves a copy to the
 * gallery. Tries progressively safer settings when a device's encoder or GL pipeline refuses one:
 * full quality with text on the composition, then text per clip, then 720p.
 */
@Singleton
class StudioExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: StudioRepository,
    private val mediaStoreSaver: MediaStoreSaver,
) {

    suspend fun export(project: StudioProject, shortSide: Int, onProgress: (Float) -> Unit): StudioExportResult {
        require(project.clips.isNotEmpty()) { "Add at least one clip before exporting." }
        val outDir = repository.exportDir(project.id)
        outDir.listFiles()?.forEach { it.delete() }
        val out = File(outDir, "${safeName(project.name)}_${System.currentTimeMillis()}.mp4")
        val builder = StudioCompositionBuilder(repository.cacheDir(project.id))

        val attempts = listOf(
            shortSide to true,
            shortSide to false,
            minOf(shortSide, 720) to false,
        ).distinct()
        var lastError: Throwable? = null
        for ((side, textOnComposition) in attempts) {
            currentCoroutineContext().ensureActive()
            val (w, h) = project.ratio.sizeFor(side)
            try {
                out.delete()
                val composition = withContext(Dispatchers.IO) {
                    builder.build(project, StudioCompositionBuilder.Options(w, h, forPreview = false, textOnComposition = textOnComposition))
                } ?: error("Nothing to export")
                run(composition, out, bitrateFor(side), onProgress)
                if (!out.isFile || out.length() == 0L) error("The exported file is empty")
                lastError = null
                break
            } catch (e: CancellationException) {
                out.delete()
                throw e
            } catch (e: Throwable) {
                lastError = e
            }
        }
        lastError?.let { throw IllegalStateException(describe(it), it) }

        val galleryUri = runCatching { mediaStoreSaver.saveVideo(out, project.name.ifBlank { "Studio video" }) }.getOrNull()
        return StudioExportResult(out, galleryUri)
    }

    private fun bitrateFor(shortSide: Int): Int = when {
        shortSide >= 2160 -> 40_000_000
        shortSide >= 1440 -> 24_000_000
        shortSide >= 1080 -> 14_000_000
        else -> 7_000_000
    }

    private suspend fun run(composition: Composition, out: File, bitrate: Int, onProgress: (Float) -> Unit): ExportResult =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val handler = Handler(Looper.getMainLooper())
                val holder = ProgressHolder()
                var transformer: Transformer? = null
                val poll = object : Runnable {
                    override fun run() {
                        val t = transformer ?: return
                        if (!cont.isActive) return
                        try {
                            if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                                onProgress((holder.progress / 100f).coerceIn(0f, 1f))
                            }
                        } catch (e: IllegalStateException) {
                            return
                        }
                        handler.postDelayed(this, POLL_MS)
                    }
                }
                val listener = object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        handler.removeCallbacks(poll)
                        onProgress(1f)
                        if (cont.isActive) cont.resume(exportResult)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        handler.removeCallbacks(poll)
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                }
                cont.invokeOnCancellation {
                    handler.post {
                        handler.removeCallbacks(poll)
                        runCatching { transformer?.cancel() }
                    }
                }
                try {
                    val encoderFactory = DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                        .setEnableFallback(true)
                        .build()
                    val tr = Transformer.Builder(context)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setEncoderFactory(encoderFactory)
                        .addListener(listener)
                        .build()
                    transformer = tr
                    tr.start(composition, out.absolutePath)
                    handler.postDelayed(poll, POLL_MS)
                } catch (e: Exception) {
                    handler.removeCallbacks(poll)
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
        }

    private fun describe(e: Throwable): String = when (e) {
        is ExportException -> "Export failed (${e.errorCodeName}). Try a lower resolution or shorter clips."
        else -> e.message ?: "Export failed."
    }

    private fun safeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').take(40).ifBlank { "studio" }

    private companion object {
        const val POLL_MS = 250L
    }
}
