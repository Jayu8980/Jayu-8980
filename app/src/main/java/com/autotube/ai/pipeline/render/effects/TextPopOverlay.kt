package com.autotube.ai.pipeline.render.effects

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.autotube.ai.core.model.TextPop
import com.autotube.ai.core.model.TextPopKind

/**
 * Animated pop-up text (numbers, names, facts, titles, CTA) on a rounded pill: ease-out-back scale and
 * alpha in over 180 ms, hold, fade out over 150 ms. Drawn in the upper safe area (Shorts ~28 % height,
 * long-form upper-left third) with a colour per [TextPopKind] and optional emoji.
 */
class TextPopOverlay(
    width: Int,
    height: Int,
    vertical: Boolean,
    pops: List<TextPop>,
    timelineOffsetUs: Long = 0L,
) : TimelineBitmapOverlay(width, height, timelineOffsetUs) {

    private val pops: List<TextPop> = pops.filter { it.text.isNotBlank() && it.endMs > it.startMs }.sortedBy { it.startMs }
    private val spec = TextFit.spec(width, height, vertical, TextFitKind.TEXT_POP)
    private val paint = TextFit.newPaint()
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val layouts = HashMap<Int, Pair<List<FitToken>, FitResult>>()

    override fun stateKey(timelineMs: Long): Any {
        val active = activePops(timelineMs)
        if (active.isEmpty()) return EMPTY
        return active.map { i -> i * 100 + phaseBucket(pops[i], timelineMs) }
    }

    override fun draw(target: Bitmap, timelineMs: Long) {
        val active = activePops(timelineMs)
        if (active.isEmpty()) return
        val canvas = Canvas(target)
        // Overlapping pops stack downwards inside the safe band.
        var yOffset = 0f
        for (i in active) {
            yOffset += drawPop(canvas, i, timelineMs, yOffset)
        }
    }

    /** Returns the vertical space used. */
    private fun drawPop(canvas: Canvas, index: Int, t: Long, yOffset: Float): Float {
        val pop = pops[index]
        val (tokens, layout) = layouts.getOrPut(index) {
            val tk = TextFit.tokensOf(pop.text).toMutableList()
            val emoji = pop.emoji?.takeIf { it.isNotBlank() }
            if (emoji != null && tk.isNotEmpty()) tk[tk.lastIndex] = tk.last().copy(emoji = emoji)
            tk to TextFit.fit(tk, spec, paint, headroom = 1f)
        }
        if (tokens.isEmpty()) return 0f
        val size = layout.textSize
        paint.textSize = size
        val space = paint.measureText(" ")
        val lineWidths = layout.lines.map { line -> line.sumOf { TextFit.tokenWidth(paint, tokens[it]).toDouble() }.toFloat() + space * (line.size - 1) }
        val padX = size * 0.55f
        val padY = size * 0.32f
        val lineHeight = size * 1.18f
        val boxW = (lineWidths.maxOrNull() ?: 0f) + padX * 2
        val boxH = lineHeight * layout.lines.size + padY * 2
        val left = if (spec.leftAligned) spec.left else spec.centerX - boxW / 2f
        val top = (spec.anchorY - boxH / 2f + yOffset).coerceIn(spec.safeTop, (spec.safeBottom - boxH).coerceAtLeast(spec.safeTop))
        val rect = RectF(left, top, left + boxW, top + boxH)

        val elapsed = (t - pop.startMs).toFloat()
        val remaining = (pop.endMs - t).toFloat()
        var scale = 1f
        var alpha = 1f
        if (elapsed < IN_MS) {
            val p = elapsed / IN_MS
            scale = EffectMath.lerp(0.6f, 1f, EffectMath.easeOutBack(p))
            alpha = EffectMath.easeOutCubic(p)
        } else if (remaining < OUT_MS) {
            val p = 1f - remaining / OUT_MS
            scale = EffectMath.lerp(1f, 0.94f, EffectMath.easeInCubic(p))
            alpha = 1f - EffectMath.easeInCubic(p)
        }
        alpha = alpha.coerceIn(0f, 1f)

        val (bg, fg) = colorsFor(pop.kind)
        canvas.save()
        canvas.scale(scale, scale, rect.centerX(), rect.centerY())
        // Soft drop shadow under the pill.
        pillPaint.color = bg
        pillPaint.alpha = (Color.alpha(bg) * alpha).toInt()
        pillPaint.setShadowLayer(size * 0.25f, 0f, size * 0.08f, Color.argb((120 * alpha).toInt(), 0, 0, 0))
        val radius = minOf(rect.height() / 2f, size * 0.9f)
        canvas.drawRoundRect(rect, radius, radius, pillPaint)
        pillPaint.clearShadowLayer()

        paint.style = Paint.Style.FILL
        layout.lines.forEachIndexed { lineNo, line ->
            var x = if (spec.leftAligned) rect.left + padX else rect.centerX() - lineWidths[lineNo] / 2f
            val baseline = rect.top + padY + lineHeight * lineNo + size * 0.93f
            for (ti in line) {
                val token = tokens[ti]
                paint.color = fg
                paint.alpha = (255 * alpha).toInt()
                canvas.drawText(token.text, x, baseline, paint)
                val emoji = token.emoji
                if (emoji != null) {
                    paint.color = Color.WHITE
                    paint.alpha = (255 * alpha).toInt()
                    canvas.drawText(emoji, x + paint.measureText(token.text) + space, baseline, paint)
                }
                x += TextFit.tokenWidth(paint, token) + space
            }
        }
        canvas.restore()
        return boxH + size * 0.4f
    }

    private fun activePops(t: Long): List<Int> {
        val out = ArrayList<Int>(2)
        for (i in pops.indices) {
            val p = pops[i]
            if (p.startMs > t) break
            if (t < p.endMs) out.add(i)
        }
        return if (out.size > MAX_SIMULTANEOUS) out.takeLast(MAX_SIMULTANEOUS) else out
    }

    private fun phaseBucket(pop: TextPop, t: Long): Int {
        val elapsed = t - pop.startMs
        val remaining = pop.endMs - t
        return when {
            elapsed < IN_MS -> (elapsed * 6 / IN_MS).toInt().coerceIn(0, 5)
            remaining < OUT_MS -> 20 + ((OUT_MS - remaining) * 5 / OUT_MS).toInt().coerceIn(0, 4)
            else -> 10
        }
    }

    private fun colorsFor(kind: TextPopKind): Pair<Int, Int> = when (kind) {
        TextPopKind.NUMBER -> Color.rgb(0xFF, 0xD4, 0x00) to Color.rgb(0x11, 0x11, 0x11)
        TextPopKind.NAME -> Color.argb(0xF2, 0xFF, 0xFF, 0xFF) to Color.rgb(0x11, 0x11, 0x11)
        TextPopKind.FACT -> Color.argb(0xE0, 0x12, 0x12, 0x16) to Color.WHITE
        TextPopKind.TITLE -> Color.rgb(0x3C, 0xFF, 0x7A) to Color.rgb(0x08, 0x20, 0x10)
        TextPopKind.CTA -> Color.rgb(0xFF, 0x00, 0x33) to Color.WHITE
    }

    private companion object {
        const val IN_MS = 180L
        const val OUT_MS = 150L
        const val MAX_SIMULTANEOUS = 2
        val EMPTY: Any = "empty"
    }
}
