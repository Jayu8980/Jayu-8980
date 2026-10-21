package com.autotube.ai.pipeline.render.effects

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.autotube.ai.core.model.CaptionChunk
import com.autotube.ai.core.model.CaptionStyle
import com.autotube.ai.core.model.CaptionTrack
import kotlin.math.min

/**
 * Word-by-word captions like top creators: heavy bold font, white text with a dark outline and soft
 * shadow, the active word popping in slightly larger, key words in a brand colour and optional emoji.
 *
 * Styles: [CaptionStyle.BOLD_POP] (uppercase, pop + colour), [CaptionStyle.KARAOKE] (spoken words
 * solid, upcoming dimmed, active word on a coloured box), [CaptionStyle.MINIMAL] (smaller, calm).
 * Text stays inside the format's safe zone (see [TextFit.spec]).
 */
class CaptionOverlay(
    width: Int,
    height: Int,
    vertical: Boolean,
    track: CaptionTrack,
    private val showEmoji: Boolean,
    timelineOffsetUs: Long = 0L,
) : TimelineBitmapOverlay(width, height, timelineOffsetUs) {

    private val style = track.style
    private val chunks: List<CaptionChunk> = track.chunks.filter { it.words.isNotEmpty() }.sortedBy { it.startMs }
    private val spec = TextFit.spec(width, height, vertical, TextFitKind.CAPTION, style)
    private val paint = TextFit.newPaint()
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val layoutCache = HashMap<Int, FitResult>()
    private val tokenCache = HashMap<Int, List<FitToken>>()

    override fun stateKey(timelineMs: Long): Any {
        val idx = visibleChunk(timelineMs)
        if (idx < 0) return EMPTY_KEY
        val chunk = chunks[idx]
        val active = activeWord(chunk, timelineMs)
        val appear = bucket(timelineMs - chunk.startMs, CHUNK_POP_MS, 4)
        val pop = if (active >= 0 && style != CaptionStyle.MINIMAL) {
            bucket(timelineMs - chunk.words[active].startMs, WORD_POP_MS, 5)
        } else 0
        return Key(idx, active, appear * 10 + pop)
    }

    override fun draw(target: Bitmap, timelineMs: Long) {
        val idx = visibleChunk(timelineMs)
        if (idx < 0) return
        val chunk = chunks[idx]
        val tokens = tokenCache.getOrPut(idx) {
            chunk.words.map { w ->
                FitToken(TextFit.displayText(w.text, style), if (showEmoji) w.emoji?.takeIf { it.isNotBlank() } else null)
            }
        }
        val layout = layoutCache.getOrPut(idx) { TextFit.fit(tokens, spec, paint) }
        val size = layout.textSize
        paint.textSize = size
        val canvas = Canvas(target)
        val active = activeWord(chunk, timelineMs)
        val highlight = if (idx % 2 == 0) YELLOW else GREEN

        // Chunk entrance: quick scale/alpha pop.
        val appearT = ((timelineMs - chunk.startMs).toFloat() / CHUNK_POP_MS).coerceIn(0f, 1f)
        val chunkScale = if (style == CaptionStyle.MINIMAL) 1f else EffectMath.lerp(0.86f, 1f, EffectMath.easeOutBack(appearT))
        val chunkAlpha = EffectMath.easeOutCubic(appearT.coerceAtLeast(0.25f))

        val lineHeight = size * 1.2f
        val blockHeight = lineHeight * layout.lines.size
        var top = spec.anchorY - blockHeight / 2f
        top = top.coerceIn(spec.safeTop, (spec.safeBottom - blockHeight).coerceAtLeast(spec.safeTop))
        val space = paint.measureText(" ")

        canvas.save()
        canvas.scale(chunkScale, chunkScale, spec.centerX, top + blockHeight / 2f)
        layout.lines.forEachIndexed { lineNo, line ->
            val widths = line.map { TextFit.tokenWidth(paint, tokens[it]) }
            val lineWidth = widths.sum() + space * (line.size - 1).coerceAtLeast(0)
            var x = spec.centerX - lineWidth / 2f
            val baseline = top + lineHeight * lineNo + size * 0.95f
            line.forEachIndexed { pos, tokenIndex ->
                val word = chunk.words[tokenIndex]
                val token = tokens[tokenIndex]
                val w = widths[pos]
                val isActive = tokenIndex == active
                val spoken = active >= 0 && tokenIndex < active
                drawToken(canvas, token, word.isKeyword, isActive, spoken, active < 0, x, baseline, w, size, highlight, timelineMs - word.startMs, chunkAlpha)
                x += w + space
            }
        }
        canvas.restore()
    }

    private fun drawToken(
        canvas: Canvas,
        token: FitToken,
        isKeyword: Boolean,
        isActive: Boolean,
        spoken: Boolean,
        nothingActiveYet: Boolean,
        x: Float,
        baseline: Float,
        width: Float,
        size: Float,
        highlight: Int,
        sinceWordStartMs: Long,
        alpha: Float,
    ) {
        val fillColor: Int
        var scale = 1f
        var fillAlpha = 1f
        when (style) {
            CaptionStyle.BOLD_POP, CaptionStyle.OFF -> {
                fillColor = if (isActive || isKeyword) highlight else Color.WHITE
                if (isActive) {
                    val t = (sinceWordStartMs.toFloat() / WORD_POP_MS).coerceIn(0f, 1f)
                    scale = EffectMath.lerp(1f, TextFit.ACTIVE_WORD_SCALE, EffectMath.easeOutBack(t))
                }
            }
            CaptionStyle.KARAOKE -> {
                fillColor = if (isActive) Color.BLACK else if (isKeyword) highlight else Color.WHITE
                if (!isActive && !spoken && !nothingActiveYet) fillAlpha = 0.6f
                if (nothingActiveYet) fillAlpha = 0.6f
                if (isActive) {
                    val t = (sinceWordStartMs.toFloat() / WORD_POP_MS).coerceIn(0f, 1f)
                    scale = EffectMath.lerp(1f, 1.06f, EffectMath.easeOutBack(t))
                    val pad = size * 0.14f
                    val rect = RectF(x - pad, baseline - size * 0.92f, x + width + pad, baseline + size * 0.26f)
                    boxPaint.color = highlight
                    boxPaint.alpha = (255 * alpha).toInt()
                    canvas.save()
                    canvas.scale(scale, scale, rect.centerX(), rect.centerY())
                    canvas.drawRoundRect(rect, size * 0.22f, size * 0.22f, boxPaint)
                    canvas.restore()
                }
            }
            CaptionStyle.MINIMAL -> {
                fillColor = if (isKeyword) YELLOW else Color.WHITE
                if (!isActive && !spoken) fillAlpha = 0.85f
            }
        }

        canvas.save()
        if (scale != 1f) canvas.scale(scale, scale, x + width / 2f, baseline - size * 0.35f)
        val outline = style != CaptionStyle.MINIMAL && !(style == CaptionStyle.KARAOKE && isActive)
        if (outline) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = size * 0.16f
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = Color.argb((235 * alpha).toInt(), 0, 0, 0)
            paint.clearShadowLayer()
            canvas.drawText(token.text, x, baseline, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = fillColor
        paint.alpha = (255 * alpha * fillAlpha).toInt().coerceIn(0, 255)
        paint.setShadowLayer(size * 0.12f, 0f, size * 0.05f, Color.argb((150 * alpha).toInt(), 0, 0, 0))
        canvas.drawText(token.text, x, baseline, paint)
        val emoji = token.emoji
        if (emoji != null) {
            paint.clearShadowLayer()
            paint.color = Color.WHITE
            paint.alpha = (255 * alpha).toInt()
            canvas.drawText(emoji, x + paint.measureText(token.text) + paint.measureText(" "), baseline, paint)
        }
        paint.clearShadowLayer()
        canvas.restore()
    }

    /** Index of the chunk shown at [t], or -1. A chunk lingers briefly into a short gap. */
    private fun visibleChunk(t: Long): Int {
        var lo = 0
        var hi = chunks.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (chunks[mid].startMs <= t) {
                found = mid
                lo = mid + 1
            } else hi = mid - 1
        }
        if (found < 0) return -1
        val chunk = chunks[found]
        val nextStart = chunks.getOrNull(found + 1)?.startMs ?: Long.MAX_VALUE
        val linger = min(LINGER_MS, (nextStart - chunk.endMs).coerceAtLeast(0))
        return if (t < chunk.endMs + linger && t < nextStart) found else -1
    }

    private fun activeWord(chunk: CaptionChunk, t: Long): Int {
        var active = -1
        chunk.words.forEachIndexed { i, w -> if (w.startMs <= t) active = i }
        return active
    }

    private fun bucket(elapsedMs: Long, spanMs: Long, buckets: Int): Int {
        if (elapsedMs >= spanMs) return buckets
        if (elapsedMs <= 0) return 0
        return (elapsedMs * buckets / spanMs).toInt()
    }

    private data class Key(val chunk: Int, val active: Int, val anim: Int)

    private companion object {
        val EMPTY_KEY = Key(-1, -1, -1)
        const val CHUNK_POP_MS = 110L
        const val WORD_POP_MS = 150L
        const val LINGER_MS = 250L
        val YELLOW = Color.rgb(0xFF, 0xD4, 0x00)
        val GREEN = Color.rgb(0x3C, 0xFF, 0x7A)
    }
}
