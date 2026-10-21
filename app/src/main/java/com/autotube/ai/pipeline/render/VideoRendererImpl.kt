package com.autotube.ai.pipeline.render

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.autotube.ai.core.common.PipelineException
import com.autotube.ai.core.model.AppSettings
import com.autotube.ai.core.model.AudioMixResult
import com.autotube.ai.core.model.CaptionStyle
import com.autotube.ai.core.model.CaptionTrack
import com.autotube.ai.core.model.EditDecisionList
import com.autotube.ai.core.model.PipelineStep
import com.autotube.ai.core.model.RenderResult
import com.autotube.ai.core.model.TransitionType
import com.autotube.ai.core.model.VideoFormat
import com.autotube.ai.core.model.VideoRequest
import com.autotube.ai.core.model.VisualAsset
import com.autotube.ai.domain.ProjectFiles
import com.autotube.ai.domain.StepLogger
import com.autotube.ai.domain.VideoRenderer
import com.autotube.ai.pipeline.audio.GainLimiterAudioProcessor
import com.autotube.ai.pipeline.render.effects.CameraMotionEffect
import com.autotube.ai.pipeline.render.effects.CaptionOverlay
import com.autotube.ai.pipeline.render.effects.ColorGradeEffect
import com.autotube.ai.pipeline.render.effects.FilmGrainOverlay
import com.autotube.ai.pipeline.render.effects.LookOverlayAssets
import com.autotube.ai.pipeline.render.effects.TextPopOverlay
import com.autotube.ai.pipeline.render.effects.TransitionEffect
import com.autotube.ai.pipeline.render.effects.TransitionPlan
import com.autotube.ai.pipeline.render.effects.VignetteOverlay
import com.google.common.collect.ImmutableList
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

/**
 * Renders the EDL on the phone with Media3 Transformer: one video sequence (one item per shot with
 * crop, camera motion, transitions and grade), one audio sequence (the final mix through a safety
 * limiter) and composition-level overlays (captions, text pops, vignette, grain). H.264/AAC through
 * the hardware encoders.
 *
 * Fallbacks, in order: per-item overlays instead of composition effects; 720p when the encoder rejects
 * 1080p; video-only export + separate AAC export muxed with MediaMuxer.
 */
@Singleton
class VideoRendererImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectFiles: ProjectFiles,
) : VideoRenderer {

    override suspend fun render(
        projectId: Long,
        request: VideoRequest,
        edl: EditDecisionList,
        visuals: List<VisualAsset>,
        captions: CaptionTrack,
        audioMix: AudioMixResult,
        settings: AppSettings,
        outFile: File,
        log: StepLogger,
        onProgress: (Float) -> Unit,
    ): RenderResult {
        val mixFile = File(audioMix.mixPath)
        if (!mixFile.isFile || mixFile.length() == 0L) {
            throw PipelineException(
                "Audio mix missing",
                "The mixed soundtrack is missing. Re-run \"Music & sound design\".",
                retryable = true,
                step = PipelineStep.RENDER,
            )
        }
        val tempDir = projectFiles.tempDir(projectId).apply { mkdirs() }
        outFile.parentFile?.mkdirs()

        val segments = withContext(Dispatchers.IO) { RenderPlanner(tempDir, log).plan(edl, visuals) }
        val videoMs = segments.sumOf { it.durationMs }
        val lastShotIndex = segments.last().shot.index
        log.i("Render plan: ${segments.size} items, ${videoMs} ms video, mix ${audioMix.durationMs} ms, format ${request.format.label}")

        val inputs = RenderInputs(request.format, edl, segments, lastShotIndex, captions, settings, mixFile, videoMs, audioMix.durationMs)
        var config = RenderConfig.full(request.format, compositionOverlays = true)
        var lastError: Throwable? = null

        // 1) Combined export (video + audio), adapting the config on failure.
        for (attempt in 1..MAX_COMBINED_ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            log.i("Export attempt $attempt: ${config.describe()}")
            try {
                deleteQuietly(outFile)
                withLookAssets(config, settings) { assets ->
                    val composition = buildComposition(inputs, config, assets, includeVideo = true, includeAudio = true)
                    export(composition, outFile, config) { p -> onProgress(0.97f * p) }
                }
                return finish(outFile, request, config, videoMs, usedFallbackMuxing = false, log, onProgress)
            } catch (e: CancellationException) {
                deleteQuietly(outFile)
                throw e
            } catch (e: Throwable) {
                lastError = e
                log.w("Export attempt $attempt failed: ${describe(e)}", e)
                config = nextConfig(config, e) ?: break
            }
        }

        // 2) Video-only + audio-only exports, muxed manually.
        log.w("Falling back to separate video/audio exports and manual muxing")
        val videoOnly = File(tempDir, "render_video_only.mp4")
        val audioOnly = File(tempDir, "render_audio.m4a")
        var fallbackConfig = config.copy(compositionOverlays = false)
        try {
            var videoDone = false
            for (attempt in 1..2) {
                currentCoroutineContext().ensureActive()
                try {
                    deleteQuietly(videoOnly)
                    withLookAssets(fallbackConfig, settings) { assets ->
                        val composition = buildComposition(inputs, fallbackConfig, assets, includeVideo = true, includeAudio = false)
                        export(composition, videoOnly, fallbackConfig) { p -> onProgress(0.7f * p) }
                    }
                    videoDone = true
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    lastError = e
                    log.w("Video-only export failed: ${describe(e)}", e)
                    fallbackConfig = if (!fallbackConfig.reduced) fallbackConfig.reduce(request.format) else break
                }
            }
            if (!videoDone) throw lastError ?: IllegalStateException("video-only export failed")

            deleteQuietly(audioOnly)
            val audioComposition = buildComposition(inputs, fallbackConfig, null, includeVideo = false, includeAudio = true)
            export(audioComposition, audioOnly, fallbackConfig) { p -> onProgress(0.7f + 0.2f * p) }

            withContext(Dispatchers.IO) {
                deleteQuietly(outFile)
                TrackMuxer.mux(videoOnly, audioOnly, outFile)
            }
            onProgress(0.97f)
            return finish(outFile, request, fallbackConfig, videoMs, usedFallbackMuxing = true, log, onProgress)
        } catch (e: CancellationException) {
            deleteQuietly(outFile)
            throw e
        } catch (e: PipelineException) {
            throw e
        } catch (e: Throwable) {
            log.e("All render strategies failed", e)
            throw PipelineException(
                "Rendering failed",
                userMessageFor(lastError ?: e),
                retryable = true,
                step = PipelineStep.RENDER,
                cause = e,
            )
        } finally {
            deleteQuietly(videoOnly)
            deleteQuietly(audioOnly)
        }
    }

    // ------------------------------------------------------------------ composition

    private class RenderInputs(
        val format: VideoFormat,
        val edl: EditDecisionList,
        val segments: List<RenderSegment>,
        val lastShotIndex: Int,
        val captions: CaptionTrack,
        val settings: AppSettings,
        val mixFile: File,
        val videoMs: Long,
        val mixMs: Long,
    )

    private fun buildComposition(
        inputs: RenderInputs,
        config: RenderConfig,
        assets: LookOverlayAssets?,
        includeVideo: Boolean,
        includeAudio: Boolean,
    ): Composition {
        val sequences = ArrayList<EditedMediaItemSequence>(2)
        if (includeVideo) {
            val items = inputs.segments.mapIndexed { i, seg -> videoItem(inputs, config, assets, seg, i) }
            sequences += EditedMediaItemSequence.Builder(items).build()
        }
        if (includeAudio) sequences += EditedMediaItemSequence.Builder(listOf(audioItem(inputs))).build()
        val builder = Composition.Builder(sequences)
        if (includeVideo && config.compositionOverlays && assets != null) {
            val overlays = overlaysFor(inputs, config, assets, timelineOffsetUs = 0L)
            if (overlays.isNotEmpty()) {
                builder.setEffects(Effects(emptyList(), listOf<Effect>(OverlayEffect(ImmutableList.copyOf(overlays)))))
            }
        }
        return builder.build()
    }

    private fun videoItem(inputs: RenderInputs, config: RenderConfig, assets: LookOverlayAssets?, seg: RenderSegment, position: Int): EditedMediaItem {
        val uri = Uri.fromFile(File(seg.path))
        val mediaItem = if (seg.isVideo) {
            MediaItem.Builder()
                .setUri(uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(seg.clipStartMs)
                        .setEndPositionMs(seg.clipStartMs + seg.durationMs)
                        .build(),
                )
                .build()
        } else {
            MediaItem.Builder()
                .setUri(uri)
                .setMimeType(seg.imageMimeType ?: MimeTypes.IMAGE_JPEG)
                .setImageDurationMs(seg.durationMs)
                .build()
        }
        val builder = EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(true)
            .setEffects(Effects(emptyList(), videoEffects(inputs, config, assets, seg, position)))
        if (!seg.isVideo) builder.setFrameRate(FRAME_RATE)
        return builder.build()
    }

    private fun videoEffects(inputs: RenderInputs, config: RenderConfig, assets: LookOverlayAssets?, seg: RenderSegment, position: Int): List<Effect> {
        val shot = seg.shot
        val effects = ArrayList<Effect>(7)
        effects += Presentation.createForWidthAndHeight(config.width, config.height, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        effects += CameraMotionEffect(
            shot = shot,
            segmentOffsetMs = seg.offsetInShotMs,
            segmentDurationMs = seg.durationMs,
            aspect = config.width.toFloat() / config.height,
            handheldShake = inputs.settings.handheldShake,
        )
        val next = inputs.segments.getOrNull(position + 1)?.takeIf { seg.lastOfShot }
        val plan = TransitionPlan(
            incoming = if (seg.firstOfShot && position > 0) shot.transitionIn else TransitionType.CUT,
            incomingMs = if (seg.firstOfShot) shot.transitionMs else 0,
            outgoing = next?.shot?.transitionIn ?: TransitionType.CUT,
            outgoingMs = next?.shot?.transitionMs ?: 0,
            fadeOutAtEnd = seg.lastOfShot && position == inputs.segments.lastIndex,
            itemDurationMs = seg.durationMs,
            whipDirection = if (shot.index % 2 == 0) 1f else -1f,
        )
        TransitionEffect.motion(plan)?.let { effects += it }
        if (inputs.settings.colorGrading) effects += ColorGradeEffect(inputs.edl.sceneLooks[shot.sceneNumber])
        TransitionEffect.fade(plan)?.let { effects += it }
        if (!config.compositionOverlays && assets != null) {
            val overlays = overlaysFor(inputs, config, assets, timelineOffsetUs = seg.timelineStartMs * 1000L)
            if (overlays.isNotEmpty()) effects += OverlayEffect(ImmutableList.copyOf(overlays))
        }
        return effects
    }

    private fun overlaysFor(inputs: RenderInputs, config: RenderConfig, assets: LookOverlayAssets, timelineOffsetUs: Long): List<TextureOverlay> {
        val list = ArrayList<TextureOverlay>(4)
        val vertical = inputs.format.isVertical
        // Look overlays first so the text sits on top.
        if (inputs.settings.vignette) list += VignetteOverlay(assets)
        if (inputs.settings.filmGrain) list += FilmGrainOverlay(assets)
        if (inputs.edl.textPops.isNotEmpty()) {
            list += TextPopOverlay(config.width, config.height, vertical, inputs.edl.textPops, timelineOffsetUs)
        }
        val style = if (inputs.settings.captionStyle == CaptionStyle.OFF) CaptionStyle.OFF else inputs.captions.style
        if (style != CaptionStyle.OFF && inputs.captions.chunks.isNotEmpty()) {
            list += CaptionOverlay(config.width, config.height, vertical, inputs.captions, inputs.settings.emojiInCaptions, timelineOffsetUs)
        }
        return list
    }

    private fun audioItem(inputs: RenderInputs): EditedMediaItem {
        val builder = MediaItem.Builder().setUri(Uri.fromFile(inputs.mixFile))
        if (inputs.mixMs > inputs.videoMs + 50) {
            builder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setEndPositionMs(inputs.videoMs).build(),
            )
        }
        return EditedMediaItem.Builder(builder.build())
            .setRemoveVideo(true)
            .setEffects(Effects(listOf<AudioProcessor>(GainLimiterAudioProcessor(0f, -1f)), emptyList()))
            .build()
    }

    private suspend fun <T> withLookAssets(config: RenderConfig, settings: AppSettings, block: suspend (LookOverlayAssets) -> T): T {
        val assets = LookOverlayAssets(config.width, config.height)
        withContext(Dispatchers.Default) { assets.prepare(vignette = settings.vignette, grain = settings.filmGrain) }
        try {
            return block(assets)
        } finally {
            assets.release()
        }
    }

    // ------------------------------------------------------------------ export

    private class ExportFailure(val exception: ExportException) :
        Exception("Export failed: ${exception.errorCodeName} (${exception.errorCode})", exception)

    private suspend fun export(composition: Composition, out: File, config: RenderConfig, onProgress: (Float) -> Unit): ExportResult =
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
                        handler.postDelayed(this, PROGRESS_POLL_MS)
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
                        if (cont.isActive) cont.resumeWithException(ExportFailure(exportException))
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
                        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(config.bitrate).build())
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
                    handler.postDelayed(poll, PROGRESS_POLL_MS)
                } catch (e: Exception) {
                    handler.removeCallbacks(poll)
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
        }

    // ------------------------------------------------------------------ verification

    private suspend fun finish(
        file: File,
        request: VideoRequest,
        config: RenderConfig,
        expectedMs: Long,
        usedFallbackMuxing: Boolean,
        log: StepLogger,
        onProgress: (Float) -> Unit,
    ): RenderResult = withContext(Dispatchers.IO) {
        if (!file.isFile || file.length() == 0L) {
            throw PipelineException("Rendering failed", "The exported video file is empty. Please retry.", retryable = true, step = PipelineStep.RENDER)
        }
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: config.width
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: config.height
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) { val tmp = w; w = h; h = tmp }
            if (duration <= 0L) {
                throw PipelineException("Rendering failed", "The exported video could not be read back. Please retry.", retryable = true, step = PipelineStep.RENDER)
            }
            if (duration < expectedMs * 0.9) log.w("Rendered duration $duration ms is shorter than planned $expectedMs ms")
            if (request.isShorts && duration > 60_000) log.w("Rendered Short is ${duration} ms (over 60 s)")
            log.i("Rendered ${w}x$h, $duration ms, ${file.length() / 1024} KB${if (usedFallbackMuxing) " (fallback muxing)" else ""}")
            onProgress(1f)
            RenderResult(
                outputPath = file.absolutePath,
                durationMs = duration,
                width = w,
                height = h,
                sizeBytes = file.length(),
                usedFallbackMuxing = usedFallbackMuxing,
            )
        } catch (e: PipelineException) {
            throw e
        } catch (e: Exception) {
            throw PipelineException("Rendering failed", "The exported video could not be verified. Please retry.", retryable = true, step = PipelineStep.RENDER, cause = e)
        } finally {
            runCatching { r.release() }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Picks the next strategy after a failed combined export, or null to go to manual muxing. */
    private fun nextConfig(config: RenderConfig, error: Throwable): RenderConfig? {
        val code = (error as? ExportFailure)?.exception?.errorCode
        val encoderProblem = code == ExportException.ERROR_CODE_ENCODER_INIT_FAILED ||
            code == ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED ||
            code == ExportException.ERROR_CODE_ENCODING_FAILED
        return when {
            encoderProblem && !config.reduced -> config.reduce(config.format)
            config.compositionOverlays -> config.copy(compositionOverlays = false)
            !config.reduced -> config.reduce(config.format)
            else -> null
        }
    }

    private fun describe(e: Throwable): String = when (e) {
        is ExportFailure -> "${e.exception.errorCodeName}: ${e.exception.message}"
        else -> "${e.javaClass.simpleName}: ${e.message}"
    }

    private fun userMessageFor(e: Throwable): String {
        val code = (e as? ExportFailure)?.exception?.errorCode
        return when (code) {
            ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
            ExportException.ERROR_CODE_ENCODING_FAILED,
            ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED ->
                "This phone's video encoder refused the export, even at 720p. Close other apps and retry."
            ExportException.ERROR_CODE_DECODER_INIT_FAILED,
            ExportException.ERROR_CODE_DECODING_FAILED,
            ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                "One of the video clips could not be decoded on this phone. Re-run \"Finding visuals\" to pick other clips."
            ExportException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                "A file needed for the video is missing. Re-run the visuals and music steps."
            else -> "The phone could not finish the video export. Free some storage, close other apps and retry."
        }
    }

    private fun deleteQuietly(file: File) {
        runCatching { if (file.exists()) file.delete() }
    }

    internal data class RenderConfig(
        val format: VideoFormat,
        val width: Int,
        val height: Int,
        val bitrate: Int,
        val compositionOverlays: Boolean,
        val reduced: Boolean,
    ) {
        fun reduce(format: VideoFormat): RenderConfig = copy(
            width = if (format.isVertical) 720 else 1280,
            height = if (format.isVertical) 1280 else 720,
            bitrate = if (format.isVertical) 5_000_000 else 6_000_000,
            reduced = true,
        )

        fun describe(): String =
            "${width}x$height @ ${bitrate / 1_000_000} Mbps, overlays ${if (compositionOverlays) "on composition" else "per item"}"

        companion object {
            fun full(format: VideoFormat, compositionOverlays: Boolean) = RenderConfig(
                format = format,
                width = format.width,
                height = format.height,
                bitrate = if (format.isVertical) 10_000_000 else 12_000_000,
                compositionOverlays = compositionOverlays,
                reduced = false,
            )
        }
    }

    private companion object {
        const val FRAME_RATE = 30
        const val PROGRESS_POLL_MS = 500L
        const val MAX_COMBINED_ATTEMPTS = 3
    }
}
