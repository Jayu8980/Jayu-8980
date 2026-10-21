package com.autotube.ai.pipeline.visuals

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Size and length of a media file as displayed (rotation applied). */
internal data class MediaInfo(val width: Int, val height: Int, val durationMs: Long)

/**
 * Reads dimensions/duration of downloaded media and writes the small preview JPEGs used by the UI
 * and the vision check. All functions block; call them on Dispatchers.IO.
 */
internal object MediaProbe {

    const val PREVIEW_MAX_SIDE = 480
    private const val PREVIEW_QUALITY = 80

    /** Null when the file is not a playable video. */
    fun video(file: File): MediaInfo? {
        if (!file.isFile || file.length() == 0L) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
            if (hasVideo != null && !hasVideo.equals("yes", ignoreCase = true)) return null
            var w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) {
                val t = w
                w = h
                h = t
            }
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (w <= 0 || h <= 0 || duration <= 0L) null else MediaInfo(w, h, duration)
        } catch (e: Exception) {
            null
        } finally {
            releaseQuietly(retriever)
        }
    }

    /** Null when the file is not a decodable image. */
    fun image(file: File): MediaInfo? {
        if (!file.isFile || file.length() == 0L) return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        return if (opts.outWidth > 0 && opts.outHeight > 0) MediaInfo(opts.outWidth, opts.outHeight, 0L) else null
    }

    /** Frame at 30 % of the clip, scaled to [PREVIEW_MAX_SIDE]. */
    fun writeVideoPreview(file: File, durationMs: Long, out: File): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val atUs = (durationMs.coerceAtLeast(0L) * 0.3).toLong() * 1000L
            val frame = retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return false
            writeBitmapPreview(frame, out).also { frame.recycle() }
        } catch (e: Exception) {
            false
        } finally {
            releaseQuietly(retriever)
        }
    }

    /** Subsampled decode, scaled to [PREVIEW_MAX_SIDE]. */
    fun writeImagePreview(file: File, out: File): Boolean = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            false
        } else {
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PREVIEW_MAX_SIDE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
            if (bmp == null) false else writeBitmapPreview(bmp, out).also { bmp.recycle() }
        }
    } catch (e: Exception) {
        false
    } catch (e: OutOfMemoryError) {
        false
    }

    /** Scales [bitmap] (not recycled here) and writes it as a JPEG. */
    fun writeBitmapPreview(bitmap: Bitmap, out: File): Boolean {
        val longest = max(bitmap.width, bitmap.height)
        val scaled = if (longest > PREVIEW_MAX_SIDE) {
            val f = PREVIEW_MAX_SIDE.toFloat() / longest
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * f).roundToInt().coerceAtLeast(1),
                (bitmap.height * f).roundToInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        return try {
            out.parentFile?.mkdirs()
            FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.JPEG, PREVIEW_QUALITY, it) }
        } catch (e: Exception) {
            false
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun releaseQuietly(retriever: MediaMetadataRetriever) {
        try {
            retriever.release()
        } catch (e: Exception) {
            // Nothing useful to do; the retriever is gone either way.
        }
    }
}
