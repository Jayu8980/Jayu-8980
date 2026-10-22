package com.autotube.ai.studio

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SpeedChangingAudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.autotube.ai.core.model.TransitionType
import com.autotube.ai.pipeline.render.effects.TransitionEffect
import com.autotube.ai.pipeline.render.effects.TransitionPlan
import com.google.common.collect.ImmutableList
import java.io.File

/**
 * Turns a [StudioProject] into a Media3 [Composition], used both for the live preview
 * (CompositionPlayer) and for export (Transformer):
 *
 * - sequence 1: the picture of every clip, audio removed;
 * - sequence 2: the clips' own sound (silence for photos and muted clips), same timing as sequence 1;
 * - one more sequence per audio layer (music, voiceover...), padded with silence to its start.
 *
 * Text is a composition overlay on export; the preview draws it in Compose instead so it can be
 * dragged.
 */
class StudioCompositionBuilder(private val silenceDir: File) {

    data class Options(
        val width: Int,
        val height: Int,
        val forPreview: Boolean,
        /** Speed changes (some devices' preview players reject them). */
        val speed: Boolean = true,
        val audioLayers: Boolean = true,
        val clipAudio: Boolean = true,
        /** Export: draw text on the whole composition (true) or per clip (fallback). */
        val textOnComposition: Boolean = true,
    )

    /** Null when the project has no clips. */
    fun build(project: StudioProject, o: Options): Composition? {
        if (project.clips.isEmpty()) return null
        val clips = project.clips
        val starts = project.clipStarts()
        val totalMs = project.durationMs
        val sequences = ArrayList<EditedMediaItemSequence>()

        val videoItems = clips.mapIndexed { i, clip -> videoItem(project, clip, i, starts[i], o) }
        sequences += EditedMediaItemSequence.Builder(videoItems).build()

        if (o.clipAudio && clips.any { !it.isImage && it.hasAudio && it.volume > 0f }) {
            sequences += EditedMediaItemSequence.Builder(clips.map { clipAudioItem(it, o) }).build()
        }

        if (o.audioLayers) {
            for (layer in project.audio) {
                if (layer.startMs >= totalMs - StudioEdits.MIN_MS) continue
                sequences += EditedMediaItemSequence.Builder(audioLayerItems(layer, totalMs)).build()
            }
        }

        val builder = Composition.Builder(sequences)
        if (!o.forPreview && o.textOnComposition && project.texts.isNotEmpty()) {
            val overlay = StudioTextOverlay(o.width, o.height, project.texts)
            builder.setEffects(Effects(emptyList(), listOf<Effect>(OverlayEffect(ImmutableList.of<TextureOverlay>(overlay)))))
        }
        return builder.build()
    }

    // ------------------------------------------------------------------ video

    private fun videoItem(project: StudioProject, clip: StudioClip, index: Int, startMs: Long, o: Options): EditedMediaItem {
        val uri = Uri.fromFile(File(clip.path))
        val mediaItem = if (clip.isImage) {
            MediaItem.Builder()
                .setUri(uri)
                .setMimeType(imageMimeType(clip.path))
                .setImageDurationMs(clip.trimmedMs)
                .build()
        } else {
            MediaItem.Builder()
                .setUri(uri)
                .setClippingConfiguration(clipping(clip.trimStartMs, clip.trimEndMs))
                .build()
        }
        val builder = EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(true)
            .setEffects(Effects(emptyList(), videoEffects(project, clip, index, startMs, o)))
        if (clip.isImage) {
            builder.setFrameRate(IMAGE_FPS)
            builder.setDurationUs(clip.trimmedMs * 1000L)
        } else {
            builder.setDurationUs(clip.sourceDurationMs * 1000L)
        }
        return builder.build()
    }

    private fun videoEffects(project: StudioProject, clip: StudioClip, index: Int, startMs: Long, o: Options): List<Effect> {
        val effects = ArrayList<Effect>(8)
        if (o.speed && !clip.isImage && clip.speed != 1f) effects += SpeedChangeEffect(clip.speed)
        if (clip.rotation != 0 || clip.flipH || clip.flipV) {
            effects += ScaleAndRotateTransformation.Builder()
                .setScale(if (clip.flipH) -1f else 1f, if (clip.flipV) -1f else 1f)
                // Media3 rotates counter-clockwise; the editor's rotation is clockwise.
                .setRotationDegrees((360 - clip.rotation) % 360f)
                .build()
        }
        val fit = clip.fit ?: project.fit
        val layout = if (fit == FitMode.FILL) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT
        effects += Presentation.createForWidthAndHeight(o.width, o.height, layout)
        if (clip.zoom > 1.001f) {
            effects += ScaleAndRotateTransformation.Builder().setScale(clip.zoom, clip.zoom).build()
        }
        val plan = if (o.forPreview) null else transitionPlan(project, clip, index)
        plan?.let { TransitionEffect.motion(it) }?.let { effects += it }
        StudioColor.effect(clip)?.let { effects += it }
        plan?.let { TransitionEffect.fade(it) }?.let { effects += it }
        if (!o.forPreview && !o.textOnComposition) {
            val texts = project.texts.filter { it.endMs > startMs && it.startMs < startMs + clip.durationMs }
            if (texts.isNotEmpty()) {
                effects += OverlayEffect(ImmutableList.of<TextureOverlay>(StudioTextOverlay(o.width, o.height, texts, startMs * 1000L)))
            }
        }
        return effects
    }

    private fun transitionPlan(project: StudioProject, clip: StudioClip, index: Int): TransitionPlan? {
        val prev = project.clips.getOrNull(index - 1)
        val hasNext = index < project.clips.lastIndex
        val incoming = prev?.transition ?: ClipTransition.NONE
        val outgoing = if (hasNext) clip.transition else ClipTransition.NONE
        if (incoming == ClipTransition.NONE && outgoing == ClipTransition.NONE) return null
        return TransitionPlan(
            incoming = incoming.toCore(),
            incomingMs = prev?.transitionMs ?: 0L,
            outgoing = outgoing.toCore(),
            outgoingMs = clip.transitionMs,
            fadeOutAtEnd = false,
            itemDurationMs = clip.durationMs,
            whipDirection = if (index % 2 == 0) 1f else -1f,
        )
    }

    private fun ClipTransition.toCore(): TransitionType = when (this) {
        ClipTransition.NONE -> TransitionType.CUT
        ClipTransition.FADE -> TransitionType.FADE
        ClipTransition.DIP_BLACK -> TransitionType.DIP_TO_BLACK
        ClipTransition.ZOOM -> TransitionType.ZOOM
        ClipTransition.WHIP -> TransitionType.WHIP
    }

    // ------------------------------------------------------------------ audio

    private fun clipAudioItem(clip: StudioClip, o: Options): EditedMediaItem {
        if (clip.isImage || !clip.hasAudio || clip.volume <= 0f) return silenceItem(clip.durationMs)
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.fromFile(File(clip.path)))
            .setClippingConfiguration(clipping(clip.trimStartMs, clip.trimEndMs))
            .build()
        // Keep the audio in step with an un-sped preview picture.
        if (clip.speed != 1f && !o.speed) return silenceItem(clip.trimmedMs)
        val processors = ArrayList<AudioProcessor>(2)
        if (clip.speed != 1f) processors += SpeedChangingAudioProcessor(ConstantSpeed(clip.speed))
        if (clip.volume != 1f) processors += VolumeFadeAudioProcessor(clip.volume)
        val builder = EditedMediaItem.Builder(mediaItem)
            .setRemoveVideo(true)
            .setDurationUs(clip.sourceDurationMs * 1000L)
        if (processors.isNotEmpty()) builder.setEffects(Effects(processors, emptyList()))
        return builder.build()
    }

    private fun audioLayerItems(layer: AudioLayer, totalMs: Long): List<EditedMediaItem> {
        val items = ArrayList<EditedMediaItem>(2)
        if (layer.startMs > 0) items += silenceItem(layer.startMs)
        val length = minOf(layer.durationMs, totalMs - layer.startMs).coerceAtLeast(StudioEdits.MIN_MS)
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.fromFile(File(layer.path)))
            .setClippingConfiguration(clipping(layer.trimStartMs, layer.trimStartMs + length))
            .build()
        val processor = VolumeFadeAudioProcessor(
            volume = layer.volume,
            fadeInMs = layer.fadeInMs,
            fadeOutMs = if (length < layer.durationMs) 0L else layer.fadeOutMs,
            durationMs = length,
        )
        items += EditedMediaItem.Builder(mediaItem)
            .setRemoveVideo(true)
            .setDurationUs(layer.sourceDurationMs * 1000L)
            .setEffects(Effects(listOf<AudioProcessor>(processor), emptyList()))
            .build()
        return items
    }

    private fun silenceItem(durationMs: Long): EditedMediaItem {
        val file = SilenceWav.ensure(silenceDir, durationMs)
        return EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(file)))
            .setRemoveVideo(true)
            .setDurationUs(durationMs.coerceAtLeast(1L) * 1000L)
            .build()
    }

    // ------------------------------------------------------------------ helpers

    private fun clipping(startMs: Long, endMs: Long) = MediaItem.ClippingConfiguration.Builder()
        .setStartPositionMs(startMs)
        .setEndPositionMs(endMs)
        .build()

    private class ConstantSpeed(private val speed: Float) : SpeedProvider {
        override fun getSpeed(timeUs: Long): Float = speed
        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
    }

    companion object {
        const val IMAGE_FPS = 30

        fun imageMimeType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
            "png" -> MimeTypes.IMAGE_PNG
            "webp" -> MimeTypes.IMAGE_WEBP
            "heic", "heif" -> "image/heif"
            "bmp" -> "image/bmp"
            else -> MimeTypes.IMAGE_JPEG
        }
    }
}
