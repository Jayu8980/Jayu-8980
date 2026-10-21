package com.autotube.ai.pipeline.visuals

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.autotube.ai.core.model.Emotion
import com.autotube.ai.core.model.Scene
import com.autotube.ai.core.model.ShotType
import com.autotube.ai.core.model.VideoFormat
import com.autotube.ai.core.model.VisualAsset
import com.autotube.ai.core.model.VisualSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Fully offline visuals drawn with android.graphics: emotion-coloured gradients, soft bokeh, light
 * streaks, geometric shapes and film noise in depth layers (good for Ken Burns / parallax), plus a
 * typography variant with a big stylised key word for B-roll and pattern interrupts.
 * Deterministic per (scene, slot); ids are "local:s<scene>_<slot>".
 */
@Singleton
class LocalArtGenerator @Inject constructor() {

    /**
     * Draws one frame-sized JPEG into [outDir]. [typography] draws [scene]'s key text on top.
     * Overwrites an existing file with the same id.
     */
    suspend fun generate(
        scene: Scene,
        slot: Int,
        format: VideoFormat,
        outDir: File,
        typography: Boolean,
        shotType: ShotType = ShotType.MEDIUM,
    ): VisualAsset = withContext(Dispatchers.Default) {
        val w = format.width
        val h = format.height
        val id = idFor(scene.sceneNumber, slot)
        val seed = scene.sceneNumber * 7_919 + slot * 104_729 + (if (typography) 31 else 0) + scene.emotion.ordinal * 13
        val rnd = Random(seed)
        val palette = paletteFor(scene.emotion)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val text = if (typography) typographyText(scene) else ""
        try {
            val canvas = Canvas(bitmap)
            drawBackground(canvas, w, h, palette, rnd)
            drawFarGlow(canvas, w, h, palette, rnd)
            drawStreaks(canvas, w, h, palette, rnd)
            drawGeometry(canvas, w, h, palette, rnd)
            drawBokeh(canvas, w, h, palette, rnd, intensity = scene.intensity)
            if (typography && text.isNotBlank()) drawTypography(canvas, w, h, palette, text, format)
            drawVignette(canvas, w, h)
            addNoise(bitmap, seed)

            outDir.mkdirs()
            val safe = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val file = File(outDir, "$safe.jpg")
            withContext(Dispatchers.IO) {
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            }
            val preview = File(outDir, "${safe}_preview.jpg")
            val hasPreview = withContext(Dispatchers.IO) { MediaProbe.writeBitmapPreview(bitmap, preview) }
            VisualAsset(
                id = id,
                sceneNumber = scene.sceneNumber,
                source = VisualSource.LOCAL_GENERATED,
                localPath = file.absolutePath,
                isVideo = false,
                durationMs = 0,
                width = w,
                height = h,
                shotType = if (typography) ShotType.CLOSE_UP else shotType,
                matchScore = -1,
                query = if (typography) "$TYPOGRAPHY_PREFIX$text" else "art: ${scene.emotion.wireName}",
                attribution = "",
                previewPath = if (hasPreview) preview.absolutePath else null,
            )
        } finally {
            bitmap.recycle()
        }
    }

    // ------------------------------------------------------------------ layers

    private fun drawBackground(canvas: Canvas, w: Int, h: Int, p: Palette, rnd: Random) {
        val angle = rnd.nextDouble(0.0, 2 * PI)
        val cx = w / 2f
        val cy = h / 2f
        val r = max(w, h) * 0.75f
        val dx = (cos(angle) * r).toFloat()
        val dy = (sin(angle) * r).toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            cx - dx, cy - dy, cx + dx, cy + dy,
            intArrayOf(p.dark, p.mid, p.light),
            floatArrayOf(0f, 0.55f + rnd.nextFloat() * 0.15f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    }

    private fun drawFarGlow(canvas: Canvas, w: Int, h: Int, p: Palette, rnd: Random) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(2 + rnd.nextInt(2)) {
            val cx = rnd.nextFloat() * w
            val cy = rnd.nextFloat() * h
            val radius = max(w, h) * (0.35f + rnd.nextFloat() * 0.35f)
            val color = if (it % 2 == 0) p.accent else p.light
            paint.shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(withAlpha(color, 70), withAlpha(color, 18), withAlpha(color, 0)),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, radius, paint)
        }
    }

    private fun drawStreaks(canvas: Canvas, w: Int, h: Int, p: Palette, rnd: Random) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        val baseAngle = -15f - rnd.nextFloat() * 25f
        val diag = (max(w, h) * 1.6f)
        repeat(3 + rnd.nextInt(4)) {
            val thickness = min(w, h) * (0.01f + rnd.nextFloat() * 0.05f)
            val offset = (rnd.nextFloat() - 0.5f) * max(w, h)
            canvas.save()
            canvas.translate(w / 2f, h / 2f)
            canvas.rotate(baseAngle + (rnd.nextFloat() - 0.5f) * 8f)
            val rect = RectF(-diag / 2f, offset - thickness / 2f, diag / 2f, offset + thickness / 2f)
            paint.shader = LinearGradient(
                rect.left, 0f, rect.right, 0f,
                intArrayOf(withAlpha(p.light, 0), withAlpha(p.light, 28 + rnd.nextInt(30)), withAlpha(p.light, 0)),
                floatArrayOf(0f, 0.3f + rnd.nextFloat() * 0.4f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(rect, paint)
            canvas.restore()
        }
    }

    private fun drawGeometry(canvas: Canvas, w: Int, h: Int, p: Palette, rnd: Random) {
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = min(w, h) * 0.004f
            color = withAlpha(p.light, 34)
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = withAlpha(p.accent, 14)
        }
        repeat(2 + rnd.nextInt(3)) {
            val cx = rnd.nextFloat() * w
            val cy = rnd.nextFloat() * h
            val radius = min(w, h) * (0.08f + rnd.nextFloat() * 0.22f)
            val sides = 3 + rnd.nextInt(4)
            val rot = rnd.nextDouble(0.0, 2 * PI)
            val path = Path()
            for (i in 0 until sides) {
                val a = rot + 2 * PI * i / sides
                val x = (cx + cos(a) * radius).toFloat()
                val y = (cy + sin(a) * radius).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            canvas.drawPath(path, fill)
            canvas.drawPath(path, stroke)
        }
    }

    private fun drawBokeh(canvas: Canvas, w: Int, h: Int, p: Palette, rnd: Random, intensity: Int) {
        val scale = min(w, h) / 1080f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        val count = 22 + intensity.coerceIn(1, 10) * 3 + rnd.nextInt(12)
        repeat(count) { i ->
            // Far layer: large and very soft; near layer: small and crisper.
            val near = i % 3 == 0
            val radius = (if (near) 6f + rnd.nextFloat() * 22f else 24f + rnd.nextFloat() * 60f) * scale
            val cx = rnd.nextFloat() * w
            val cy = rnd.nextFloat() * h
            val color = when (i % 3) {
                0 -> p.light
                1 -> p.accent
                else -> p.mid
            }
            val alpha = if (near) 70 + rnd.nextInt(60) else 25 + rnd.nextInt(40)
            paint.shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(withAlpha(color, alpha), withAlpha(color, alpha / 2), withAlpha(color, 0)),
                floatArrayOf(0f, 0.7f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, radius, paint)
            if (near) {
                ring.strokeWidth = max(1f, radius * 0.08f)
                ring.color = withAlpha(color, alpha / 2)
                canvas.drawCircle(cx, cy, radius * 0.92f, ring)
            }
        }
    }

    private fun drawTypography(canvas: Canvas, w: Int, h: Int, p: Palette, rawText: String, format: VideoFormat) {
        val shade = Paint().apply { color = Color.argb(80, 0, 0, 0) }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), shade)

        val text = if (isLatin(rawText)) rawText.uppercase() else rawText
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val maxWidth = w * 0.84f
        val maxHeight = h * (if (format == VideoFormat.SHORTS) 0.45f else 0.6f)
        var size = w * (if (format == VideoFormat.SHORTS) 0.16f else 0.11f)
        var lines: List<String> = emptyList()
        while (true) {
            paint.textSize = size
            lines = wrap(text, paint, maxWidth)
            val height = lines.size * size * LINE_SPACING
            val widest = lines.maxOfOrNull { paint.measureText(it) } ?: 0f
            if ((lines.size <= 3 && height <= maxHeight && widest <= maxWidth) || size <= w * 0.035f) break
            size *= 0.9f
        }
        paint.setShadowLayer(size * 0.12f, 0f, size * 0.05f, Color.argb(170, 0, 0, 0))
        val blockHeight = lines.size * size * LINE_SPACING
        var baseline = h / 2f - blockHeight / 2f + size * 0.85f
        for (line in lines) {
            canvas.drawText(line, w / 2f, baseline, paint)
            baseline += size * LINE_SPACING
        }
        val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.accent }
        val barWidth = min(maxWidth, (lines.maxOfOrNull { paint.measureText(it) } ?: maxWidth) * 0.5f)
        val barTop = h / 2f + blockHeight / 2f + size * 0.15f
        canvas.drawRoundRect(
            RectF(w / 2f - barWidth / 2f, barTop, w / 2f + barWidth / 2f, barTop + size * 0.09f),
            size * 0.05f, size * 0.05f, bar,
        )
    }

    private fun drawVignette(canvas: Canvas, w: Int, h: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val radius = max(w, h) * 0.75f
        paint.shader = RadialGradient(
            w / 2f, h / 2f, radius,
            intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(0, 0, 0, 0), Color.argb(150, 0, 0, 0)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    }

    /** Subtle monochrome film noise (+-6 levels) so gradients do not band after H.264. */
    private fun addNoise(bitmap: Bitmap, seed: Int) {
        val w = bitmap.width
        val h = bitmap.height
        val row = IntArray(w)
        var state = seed.toLong() * 6364136223846793005L + 1442695040888963407L
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                state = state * 6364136223846793005L + 1442695040888963407L
                val n = ((state ushr 33) % 13).toInt() - 6
                val c = row[x]
                val r = (((c shr 16) and 0xFF) + n).coerceIn(0, 255)
                val g = (((c shr 8) and 0xFF) + n).coerceIn(0, 255)
                val b = ((c and 0xFF) + n).coerceIn(0, 255)
                row[x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            bitmap.setPixels(row, 0, w, 0, y, w, 1)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val lines = ArrayList<String>()
        var current = ""
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth || current.isEmpty()) {
                current = candidate
            } else {
                lines += current
                current = word
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }

    private fun typographyText(scene: Scene): String {
        val raw = when {
            scene.onScreenText.isNotBlank() -> scene.onScreenText
            scene.keyWords.isNotEmpty() -> scene.keyWords.first()
            else -> scene.narrationText.split(Regex("\\s+")).filter { it.isNotBlank() }.take(3).joinToString(" ")
        }
        return raw.trim().trim('"', '\'', '“', '”').take(MAX_TEXT_CHARS)
    }

    private fun isLatin(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return true
        return letters.count { it.code < 0x250 } * 2 >= letters.length
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private class Palette(val dark: Int, val mid: Int, val light: Int, val accent: Int)

    private fun paletteFor(emotion: Emotion): Palette = when (emotion) {
        Emotion.SUSPENSE -> Palette(rgb(0x0B1026), rgb(0x1F2A44), rgb(0x4B3F72), rgb(0xE0B04C))
        Emotion.HAPPY -> Palette(rgb(0xFF6F61), rgb(0xFFB347), rgb(0xFFE29A), rgb(0xFFFFFF))
        Emotion.SAD -> Palette(rgb(0x141E27), rgb(0x34495E), rgb(0x7F8C9D), rgb(0xAED6F1))
        Emotion.EXCITING -> Palette(rgb(0x3A0CA3), rgb(0xF72585), rgb(0xFFB199), rgb(0xFFE66D))
        Emotion.CALM -> Palette(rgb(0x0B486B), rgb(0x3B8686), rgb(0xA8E6CF), rgb(0xF8F8F8))
        Emotion.DRAMATIC -> Palette(rgb(0x0F0C29), rgb(0x302B63), rgb(0x8E0E00), rgb(0xF3A712))
        Emotion.INSPIRING -> Palette(rgb(0x2E1437), rgb(0xFF4E50), rgb(0xF9D423), rgb(0xFFF3C4))
        Emotion.FUNNY -> Palette(rgb(0x21D4FD), rgb(0xF7971E), rgb(0xFFD200), rgb(0xFFFFFF))
    }

    private fun rgb(hex: Int): Int = hex or (0xFF shl 24)

    companion object {
        const val TYPOGRAPHY_PREFIX = "typography: "
        private const val JPEG_QUALITY = 88
        private const val LINE_SPACING = 1.12f
        private const val MAX_TEXT_CHARS = 48

        fun idFor(sceneNumber: Int, slot: Int): String = "local:s${sceneNumber}_$slot"

        /** True for the big-text variant (used by the editor as B-roll / pattern interrupt). */
        fun isTypography(asset: VisualAsset): Boolean =
            asset.source == VisualSource.LOCAL_GENERATED && asset.query.startsWith(TYPOGRAPHY_PREFIX)
    }
}
