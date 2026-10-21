package com.autotube.ai.pipeline.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import com.autotube.ai.core.common.PipelineException
import com.autotube.ai.core.model.EditDecisionList
import com.autotube.ai.core.model.PipelineStep
import com.autotube.ai.core.model.Shot
import com.autotube.ai.core.model.VisualAsset
import com.autotube.ai.domain.StepLogger
import java.io.File
import java.io.FileOutputStream

/**
 * One media item on the video track. A shot normally maps to one segment; a video clip that is shorter
 * than its shot is followed by a freeze-frame image segment that continues the same camera move.
 */
internal data class RenderSegment(
    val shot: Shot,
    val path: String,
    val isVideo: Boolean,
    /** For videos: where to start reading in the source clip. */
    val clipStartMs: Long,
    val durationMs: Long,
    /** Where this segment starts inside its shot. */
    val offsetInShotMs: Long,
    val firstOfShot: Boolean,
    val lastOfShot: Boolean,
    /** MIME type for image segments (e.g. image/jpeg). */
    val imageMimeType: String?,
) {
    /** Absolute start on the video timeline. */
    val timelineStartMs: Long get() = shot.startMs + offsetInShotMs
}

/** Turns an EDL into concrete, verified [RenderSegment]s (runs on an IO thread). */
internal class RenderPlanner(
    private val tempDir: File,
    private val log: StepLogger,
) {
    private val durationCache = HashMap<String, Long>()

    fun plan(edl: EditDecisionList, visuals: List<VisualAsset>): List<RenderSegment> {
        val shots = normalizeShots(edl.shots)
        if (shots.isEmpty()) {
            throw PipelineException(
                "Nothing to render",
                "The edit plan has no shots. Re-run the editing step.",
                retryable = true,
                step = PipelineStep.RENDER,
            )
        }
        val byId = visuals.associateBy { it.id }
        val segments = ArrayList<RenderSegment>(shots.size + 4)
        for (shot in shots) {
            val asset = resolveAsset(shot, byId[shot.assetId], visuals)
            segments += segmentsFor(shot, asset, visuals)
        }
        return segments
    }

    /** Sorts shots, closes a leading gap and folds sub-frame shots into their neighbour. */
    private fun normalizeShots(input: List<Shot>): List<Shot> {
        val sorted = input.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
        val out = ArrayList<Shot>(sorted.size)
        for (shot in sorted) {
            val prev = out.lastOrNull()
            var s = shot
            if (prev == null && s.startMs > 0) s = s.copy(startMs = 0)
            if (prev != null && s.startMs != prev.endMs) s = s.copy(startMs = prev.endMs)
            if (s.endMs - s.startMs < MIN_SHOT_MS) {
                if (prev != null) {
                    out[out.lastIndex] = prev.copy(endMs = s.endMs)
                    continue
                }
                if (s.endMs <= s.startMs) continue
            }
            out += s
        }
        return out
    }

    private fun resolveAsset(shot: Shot, asset: VisualAsset?, visuals: List<VisualAsset>): VisualAsset {
        if (asset != null && isUsable(asset)) return asset
        log.w("Shot ${shot.index}: visual ${shot.assetId} is missing; using another visual from scene ${shot.sceneNumber}")
        val replacement = visuals.filter { it.sceneNumber == shot.sceneNumber && it.id != shot.assetId && isUsable(it) }
            .sortedByDescending { it.matchScore }
            .firstOrNull()
            ?: visuals.firstOrNull { it.id != shot.assetId && isUsable(it) }
        return replacement ?: throw PipelineException(
            "Visuals missing",
            "The pictures and clips for this video are no longer on the phone. Re-run \"Finding visuals\".",
            retryable = true,
            step = PipelineStep.RENDER,
        )
    }

    private fun isUsable(asset: VisualAsset): Boolean {
        val f = File(asset.localPath)
        if (!f.isFile || f.length() == 0L) return false
        return if (asset.isVideo) videoDurationMs(asset.localPath) > 0 else imageMime(asset.localPath) != null
    }

    private fun segmentsFor(shot: Shot, asset: VisualAsset, visuals: List<VisualAsset>): List<RenderSegment> {
        val dur = shot.durationMs
        if (!asset.isVideo) {
            return listOf(imageSegment(shot, asset.localPath, imageMime(asset.localPath), 0, dur, first = true, last = true))
        }
        val srcDur = videoDurationMs(asset.localPath)
        if (srcDur >= dur + SAFETY_MS) {
            val start = shot.sourceStartMs.coerceAtLeast(0).let { if (it + dur + SAFETY_MS > srcDur) (srcDur - dur - SAFETY_MS).coerceAtLeast(0) else it }
            return listOf(RenderSegment(shot, asset.localPath, true, start, dur, 0, firstOfShot = true, lastOfShot = true, imageMimeType = null))
        }
        // The clip is shorter than the shot: play what exists, then hold its last frame.
        val playable = srcDur - SAFETY_MS
        val freezeAt = (srcDur - FREEZE_BACKOFF_MS).coerceAtLeast(0)
        val freeze = extractFrame(asset.localPath, freezeAt, "freeze_${shot.index}.jpg")
        if (playable >= MIN_VIDEO_PART_MS && freeze != null) {
            log.i("Shot ${shot.index}: clip is ${srcDur} ms for a ${dur} ms shot; holding the last frame")
            return listOf(
                RenderSegment(shot, asset.localPath, true, 0, playable, 0, firstOfShot = true, lastOfShot = false, imageMimeType = null),
                imageSegment(shot, freeze.absolutePath, JPEG, playable, dur - playable, first = false, last = true),
            )
        }
        if (freeze != null) {
            return listOf(imageSegment(shot, freeze.absolutePath, JPEG, 0, dur, first = true, last = true))
        }
        val still = visuals.firstOrNull { !it.isVideo && it.sceneNumber == shot.sceneNumber && imageMime(it.localPath) != null }
            ?: visuals.firstOrNull { !it.isVideo && imageMime(it.localPath) != null }
            ?: throw PipelineException(
                "Video clip unreadable",
                "A video clip for scene ${shot.sceneNumber} could not be read. Re-run \"Finding visuals\".",
                retryable = true,
                step = PipelineStep.RENDER,
            )
        return listOf(imageSegment(shot, still.localPath, imageMime(still.localPath), 0, dur, first = true, last = true))
    }

    private fun imageSegment(shot: Shot, path: String, mime: String?, offset: Long, dur: Long, first: Boolean, last: Boolean) =
        RenderSegment(shot, path, false, 0, dur, offset, first, last, mime ?: JPEG)

    fun videoDurationMs(path: String): Long = durationCache.getOrPut(path) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(path)
            val hasVideo = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            if (!hasVideo) 0L else r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            log.w("Could not read clip $path", e)
            0L
        } finally {
            runCatching { r.release() }
        }
    }

    private fun imageMime(path: String): String? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        return opts.outMimeType ?: JPEG
    }

    private fun extractFrame(path: String, atMs: Long, name: String): File? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            val bmp = r.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: r.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC)
                ?: return null
            tempDir.mkdirs()
            val out = File(tempDir, name)
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            bmp.recycle()
            out
        } catch (e: Exception) {
            log.w("Could not grab a frame from $path", e)
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private companion object {
        const val MIN_SHOT_MS = 50L
        const val SAFETY_MS = 40L
        const val FREEZE_BACKOFF_MS = 120L
        const val MIN_VIDEO_PART_MS = 500L
        const val JPEG = "image/jpeg"
    }
}
