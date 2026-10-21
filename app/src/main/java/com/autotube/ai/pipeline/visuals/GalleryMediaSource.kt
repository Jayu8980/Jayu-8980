package com.autotube.ai.pipeline.visuals

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.autotube.ai.domain.StepLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** One photo or video from the user's gallery. */
data class GalleryItem(
    val mediaId: Long,
    val uri: Uri,
    val isVideo: Boolean,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
) {
    val assetId: String get() = (if (isVideo) "gallery_video:" else "gallery_photo:") + mediaId
}

/**
 * Reads recent videos/images from MediaStore. Used only as varied filler when the user enabled
 * "use my gallery". Needs READ_MEDIA_* (or READ_EXTERNAL_STORAGE); without it the list is empty.
 */
@Singleton
class GalleryMediaSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    suspend fun list(log: StepLogger, maxPerKind: Int = 150): List<GalleryItem> = withContext(Dispatchers.IO) {
        val videos = query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, isVideo = true, maxPerKind, log)
        val images = query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, isVideo = false, maxPerKind, log)
        videos.filter { it.durationMs in MIN_VIDEO_MS..MAX_VIDEO_MS && it.sizeBytes in 1..MAX_VIDEO_BYTES } +
            images.filter { it.width >= MIN_IMAGE_SIDE || it.height >= MIN_IMAGE_SIDE || it.width == 0 }
    }

    /** Copies the item into app storage. Returns null when it cannot be read. */
    suspend fun copyTo(item: GalleryItem, outFile: File): File? = withContext(Dispatchers.IO) {
        try {
            outFile.parentFile?.mkdirs()
            val input = context.contentResolver.openInputStream(item.uri) ?: return@withContext null
            input.use { src -> FileOutputStream(outFile).use { dst -> src.copyTo(dst, 64 * 1024) } }
            if (outFile.length() > 0L) outFile else null
        } catch (e: SecurityException) {
            outFile.delete()
            null
        } catch (e: java.io.IOException) {
            outFile.delete()
            null
        }
    }

    private fun query(base: Uri, isVideo: Boolean, max: Int, log: StepLogger): List<GalleryItem> {
        val projection = if (isVideo) {
            arrayOf(COL_ID, COL_WIDTH, COL_HEIGHT, COL_SIZE, COL_DURATION)
        } else {
            arrayOf(COL_ID, COL_WIDTH, COL_HEIGHT, COL_SIZE)
        }
        val out = ArrayList<GalleryItem>()
        try {
            context.contentResolver.query(base, projection, null, null, "$COL_DATE_ADDED DESC")?.use { c ->
                val idCol = c.getColumnIndex(COL_ID)
                val wCol = c.getColumnIndex(COL_WIDTH)
                val hCol = c.getColumnIndex(COL_HEIGHT)
                val sizeCol = c.getColumnIndex(COL_SIZE)
                val durCol = if (isVideo) c.getColumnIndex(COL_DURATION) else -1
                while (c.moveToNext() && out.size < max) {
                    if (idCol < 0) break
                    val id = c.getLong(idCol)
                    out += GalleryItem(
                        mediaId = id,
                        uri = ContentUris.withAppendedId(base, id),
                        isVideo = isVideo,
                        durationMs = if (durCol >= 0 && !c.isNull(durCol)) c.getLong(durCol) else 0L,
                        width = if (wCol >= 0 && !c.isNull(wCol)) c.getInt(wCol) else 0,
                        height = if (hCol >= 0 && !c.isNull(hCol)) c.getInt(hCol) else 0,
                        sizeBytes = if (sizeCol >= 0 && !c.isNull(sizeCol)) c.getLong(sizeCol) else 0L,
                    )
                }
            }
        } catch (e: SecurityException) {
            log.w("Gallery access is not allowed (grant photo/video permission to use your gallery).")
        } catch (e: IllegalArgumentException) {
            log.w("Gallery query failed: ${e.message}")
        }
        return out
    }

    private companion object {
        // MediaStore column names (stable strings shared by MediaColumns / VideoColumns since API 1-16).
        const val COL_ID = "_id"
        const val COL_WIDTH = "width"
        const val COL_HEIGHT = "height"
        const val COL_SIZE = "_size"
        const val COL_DURATION = "duration"
        const val COL_DATE_ADDED = "date_added"
        const val MIN_VIDEO_MS = 2_000L
        const val MAX_VIDEO_MS = 10 * 60_000L
        const val MAX_VIDEO_BYTES = 400L * 1024 * 1024
        const val MIN_IMAGE_SIDE = 720
    }
}
