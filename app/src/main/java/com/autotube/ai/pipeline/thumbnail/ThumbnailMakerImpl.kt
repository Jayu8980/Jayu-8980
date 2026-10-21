package com.autotube.ai.pipeline.thumbnail

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import com.autotube.ai.core.common.PipelineException
import com.autotube.ai.core.model.PipelineStep
import com.autotube.ai.core.model.SceneRole
import com.autotube.ai.core.model.Script
import com.autotube.ai.core.model.SeoPackage
import com.autotube.ai.core.model.VideoRequest
import com.autotube.ai.core.model.VisualAsset
import com.autotube.ai.domain.ProjectFiles
import com.autotube.ai.domain.StepLogger
import com.autotube.ai.domain.ThumbnailMaker
import com.autotube.ai.pipeline.render.effects.TextFit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Canvas thumbnail: 1280x720 (long-form) or 1080x1920 (Shorts). Best-matching scene image (or a frame
 * from the best clip, or a gradient), centre-cropped and punched up, a dark gradient for legibility,
 * 2-4 big bold words with a thick outline and one highlighted word, plus a small accent shape.
 * Saved as JPEG under 2 MB to renderDir/thumbnail.jpg.
 */
@Singleton
class ThumbnailMakerImpl @Inject constructor(
    private val projectFiles: ProjectFiles,
) : ThumbnailMaker {

    override suspend fun make(
        projectId: Long,
        request: VideoRequest,
        script: Script,
        seo: SeoPackage,
        visuals: List<VisualAsset>,
        log: StepLogger,
    ): File {
        val vertical = request.format.isVertical
        val width = if (vertical) 1080 else 1280
        val height = if (vertical) 1920 else 720
        val out = File(projectFiles.renderDir(projectId).apply { mkdirs() }, "thumbnail.jpg")

        val background = withContext(Dispatchers.IO) { loadBackground(script, visuals, width, height, log) }
        val words = thumbnailWords(seo, script)
        val bytes = withContext(Dispatchers.Default) {
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bmp)
                drawBackground(canvas, background, width, height, seed = script.title.hashCode())
                drawLegibilityGradient(canvas, width, height, vertical)
                val textBounds = drawText(canvas, words, width, height, vertical)
                drawAccent(canvas, textBounds, width, height, vertical)
                encodeUnderLimit(bmp)
            } finally {
                bmp.recycle()
                background?.recycle()
            }
        }
        withContext(Dispatchers.IO) { out.writeBytes(bytes) }
        log.i("Thumbnail ${width}x$height saved (${bytes.size / 1024} KB): \"${words.joinToString(" ")}\"")
        return out
    }

    // ------------------------------------------------------------------ background

    private fun loadBackground(script: Script, visuals: List<VisualAsset>, w: Int, h: Int, log: StepLogger): Bitmap? {
        val hookScenes = script.scenes.filter { it.role == SceneRole.HOOK || it.role == SceneRole.INTRO }.map { it.sceneNumber }.toSet()
        fun rank(a: VisualAsset): Int = a.matchScore * 10 + (if (a.sceneNumber in hookScenes) 5 else 0) +
            (if (a.width >= w || a.height >= h) 2 else 0)

        val images = visuals.filter { !it.isVideo && File(it.localPath).isFile }.sortedByDescending { rank(it) }
        for (img in images.take(3)) {
            try {
                decodeImage(img.localPath, w, h)?.let { return it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w("Thumbnail: could not read ${img.id}", e)
            }
        }
        val videos = visuals.filter { it.isVideo && File(it.localPath).isFile }.sortedByDescending { rank(it) }
        for (v in videos.take(3)) {
            grabFrame(v, log)?.let { return it }
        }
        log.w("Thumbnail: no usable visual, drawing a gradient background")
        return null
    }

    private fun decodeImage(path: String, w: Int, h: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val rotation = runCatching {
            when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (rotation == 0f) return decoded
        val m = Matrix().apply { postRotate(rotation) }
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }

    private fun grabFrame(asset: VisualAsset, log: StepLogger): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(asset.localPath)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val at = if (dur > 0) (dur * 0.3).toLong() else 1_000L
            r.getFrameAtTime(at * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Exception) {
            log.w("Thumbnail: could not grab a frame from ${asset.id}", e)
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun drawBackground(canvas: Canvas, src: Bitmap?, w: Int, h: Int, seed: Int) {
        if (src == null) {
            drawGradientArt(canvas, w, h, seed)
            return
        }
        // Centre-crop.
        val scale = max(w.toFloat() / src.width, h.toFloat() / src.height)
        val dw = src.width * scale
        val dh = src.height * scale
        val dst = RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
        val boost = ColorMatrix().apply { setSaturation(1.3f) }
        val contrast = 1.15f
        val offset = 128f * (1f - contrast)
        boost.postConcat(
            ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, offset,
                    0f, contrast, 0f, 0f, offset,
                    0f, 0f, contrast, 0f, offset,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(boost) }
        canvas.drawBitmap(src, null, dst, paint)
    }

    private fun drawGradientArt(canvas: Canvas, w: Int, h: Int, seed: Int) {
        val palettes = listOf(
            intArrayOf(Color.rgb(0x14, 0x1E, 0x61), Color.rgb(0x78, 0x1C, 0x8F)),
            intArrayOf(Color.rgb(0x0F, 0x3D, 0x3E), Color.rgb(0x1D, 0x8A, 0x99)),
            intArrayOf(Color.rgb(0x3A, 0x0C, 0x0C), Color.rgb(0xC0, 0x39, 0x2B)),
            intArrayOf(Color.rgb(0x1B, 0x1B, 0x2F), Color.rgb(0xE4, 0x3F, 0x5A)),
        )
        val pal = palettes[Math.floorMod(seed, palettes.size)]
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        paint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), pal[0], pal[1], Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        val rnd = java.util.Random(seed.toLong())
        repeat(6) {
            val cx = rnd.nextFloat() * w
            val cy = rnd.nextFloat() * h
            val r = (0.15f + rnd.nextFloat() * 0.35f) * max(w, h)
            paint.shader = RadialGradient(cx, cy, r, Color.argb(70, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r, paint)
        }
    }

    private fun drawLegibilityGradient(canvas: Canvas, w: Int, h: Int, vertical: Boolean) {
        val paint = Paint(Paint.DITHER_FLAG)
        if (vertical) {
            canvas.drawColor(Color.argb(50, 0, 0, 0))
            paint.shader = LinearGradient(0f, h * 0.15f, 0f, h * 0.65f,
                intArrayOf(Color.TRANSPARENT, Color.argb(150, 0, 0, 0), Color.TRANSPARENT), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        } else {
            paint.shader = LinearGradient(0f, h * 0.35f, 0f, h.toFloat(), Color.TRANSPARENT, Color.argb(200, 0, 0, 0), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            paint.shader = LinearGradient(0f, 0f, w * 0.7f, 0f, Color.argb(130, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        }
    }

    // ------------------------------------------------------------------ text

    private fun thumbnailWords(seo: SeoPackage, script: Script): List<String> {
        val source = seo.thumbnailText.ifBlank { seo.title.ifBlank { script.title } }
        val cleaned = source.replace(Regex("[#\"“”]"), " ").trim()
        var words = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size > 4) words = words.take(4)
        if (words.isEmpty()) words = listOf("WATCH", "THIS")
        val latin = cleaned.any { it in 'a'..'z' || it in 'A'..'Z' }
        return if (latin) words.map { it.uppercase(Locale.ROOT) } else words
    }

    /** Index of the word to highlight: one with digits, else the longest. */
    private fun highlightIndex(words: List<String>): Int {
        val digit = words.indexOfFirst { w -> w.any { it.isDigit() } }
        if (digit >= 0) return digit
        return words.indices.maxByOrNull { words[it].length } ?: 0
    }

    private data class TextLayout(val size: Float, val lines: List<List<Int>>)

    private fun layoutText(paint: Paint, words: List<String>, maxW: Float, maxH: Float, startSize: Float, minSize: Float): TextLayout {
        var size = startSize
        while (true) {
            paint.textSize = size
            val space = paint.measureText(" ")
            val lines = mutableListOf<MutableList<Int>>()
            var cur = mutableListOf<Int>()
            var curW = 0f
            var fits = true
            for (i in words.indices) {
                val ww = paint.measureText(words[i]) + size * 0.2f
                if (ww > maxW) fits = false
                val need = if (cur.isEmpty()) ww else curW + space + ww
                if (need <= maxW || cur.isEmpty()) {
                    cur.add(i); curW = need
                } else {
                    lines.add(cur); cur = mutableListOf(i); curW = ww
                }
            }
            if (cur.isNotEmpty()) lines.add(cur)
            val totalH = lines.size * size * 1.05f
            if ((fits && lines.size <= 3 && totalH <= maxH) || size <= minSize) return TextLayout(size, lines)
            size = max(minSize, size * 0.92f)
        }
    }

    /** Draws the words and returns their bounding box. */
    private fun drawText(canvas: Canvas, words: List<String>, w: Int, h: Int, vertical: Boolean): RectF {
        val paint = TextFit.newPaint()
        val maxW = if (vertical) w * 0.86f else w * 0.62f
        val maxH = if (vertical) h * 0.34f else h * 0.62f
        val layout = layoutText(paint, words, maxW, maxH, startSize = if (vertical) w * 0.17f else h * 0.22f, minSize = if (vertical) w * 0.07f else h * 0.09f)
        val size = layout.size
        paint.textSize = size
        val space = paint.measureText(" ")
        val lineH = size * 1.05f
        val blockH = lineH * layout.lines.size
        val top = if (vertical) h * 0.40f - blockH / 2f else h * 0.92f - blockH
        val left = if (vertical) 0f else w * 0.05f
        val hi = highlightIndex(words)
        val bounds = RectF(Float.MAX_VALUE, top, 0f, top + blockH)

        layout.lines.forEachIndexed { li, line ->
            val lineW = line.sumOf { paint.measureText(words[it]).toDouble() }.toFloat() + space * (line.size - 1)
            var x = if (vertical) (w - lineW) / 2f else left
            val baseline = top + lineH * li + size * 0.9f
            bounds.left = min(bounds.left, x)
            bounds.right = max(bounds.right, x + lineW)
            for (i in line) {
                val word = words[i]
                // Drop shadow.
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(170, 0, 0, 0)
                canvas.drawText(word, x + size * 0.05f, baseline + size * 0.06f, paint)
                // Thick outline.
                paint.style = Paint.Style.STROKE
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeWidth = size * 0.14f
                paint.color = Color.BLACK
                canvas.drawText(word, x, baseline, paint)
                // Fill.
                paint.style = Paint.Style.FILL
                paint.color = if (i == hi) YELLOW else Color.WHITE
                canvas.drawText(word, x, baseline, paint)
                x += paint.measureText(word) + space
            }
        }
        if (bounds.left == Float.MAX_VALUE) bounds.left = left
        return bounds
    }

    private fun drawAccent(canvas: Canvas, text: RectF, w: Int, h: Int, vertical: Boolean) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = YELLOW }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = max(w, h) * 0.006f
            strokeJoin = Paint.Join.ROUND
        }
        if (vertical) {
            // Underline bar below the text.
            val barW = min(text.width() * 0.5f, w * 0.5f)
            val y = text.bottom + h * 0.02f
            val rect = RectF(w / 2f - barW / 2f, y, w / 2f + barW / 2f, y + h * 0.012f)
            canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, paint)
            canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, outline)
        } else {
            // Arrow pointing at the subject on the right half.
            val s = h * 0.16f
            val cx = min(w * 0.9f, max(text.right + s * 0.9f, w * 0.72f))
            val cy = h * 0.42f
            val path = Path().apply {
                moveTo(cx - s, cy - s * 0.18f)
                lineTo(cx, cy - s * 0.18f)
                lineTo(cx, cy - s * 0.5f)
                lineTo(cx + s * 0.6f, cy)
                lineTo(cx, cy + s * 0.5f)
                lineTo(cx, cy + s * 0.18f)
                lineTo(cx - s, cy + s * 0.18f)
                close()
            }
            val m = Matrix().apply { postRotate(-20f, cx, cy) }
            path.transform(m)
            canvas.drawPath(path, paint)
            canvas.drawPath(path, outline)
        }
    }

    // ------------------------------------------------------------------ encode

    private fun encodeUnderLimit(bmp: Bitmap): ByteArray {
        var quality = 92
        while (true) {
            val bos = ByteArrayOutputStream()
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)) {
                throw PipelineException("Thumbnail failed", "The thumbnail image could not be saved.", retryable = true, step = PipelineStep.SEO)
            }
            val bytes = bos.toByteArray()
            if (bytes.size < MAX_BYTES || quality <= 60) return bytes
            quality -= 8
        }
    }

    private companion object {
        val YELLOW = Color.rgb(0xFF, 0xD4, 0x00)
        const val MAX_BYTES = 2_000_000
    }
}
