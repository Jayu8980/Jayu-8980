package com.autotube.ai.studio

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.autotube.ai.pipeline.render.effects.EffectMath
import com.autotube.ai.pipeline.render.effects.TimelineBitmapOverlay
import kotlin.math.ceil
import kotlin.math.min

/**
 * Draws [TextLayer]s onto an Android [Canvas]. The editor preview (Compose `nativeCanvas`) and the
 * export overlay share this class, so what you see while editing is what gets rendered.
 */
class StudioTextPainter {

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val layouts = HashMap<String, StaticLayout>()

    /** Draws every layer visible at [timelineMs]. */
    fun drawAll(canvas: Canvas, layers: List<TextLayer>, frameW: Int, frameH: Int, timelineMs: Long) {
        for (layer in layers) {
            if (timelineMs >= layer.startMs && timelineMs < layer.endMs) {
                draw(canvas, layer, frameW, frameH, timelineMs - layer.startMs)
            }
        }
    }

    /** Draws one layer; [localMs] is the time since the layer started. */
    fun draw(canvas: Canvas, layer: TextLayer, frameW: Int, frameH: Int, localMs: Long) {
        if (layer.text.isBlank() || frameW <= 0 || frameH <= 0) return
        val anim = animState(layer, localMs)
        if (anim.alpha <= 0.01f) return
        val visibleText = if (anim.chars < layer.text.length) layer.text.substring(0, anim.chars) else layer.text
        if (visibleText.isEmpty()) return
        val fullLayout = layoutFor(layer, layer.text, frameW, frameH)
        val layout = if (visibleText.length == layer.text.length) fullLayout else layoutFor(layer, visibleText, frameW, frameH)
        val w = fullLayout.width.toFloat()
        val h = fullLayout.height.toFloat()
        val cx = layer.x * frameW
        val cy = layer.y * frameH + anim.dy * frameH
        val alpha = (anim.alpha * 255).toInt().coerceIn(0, 255)

        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(anim.scale, anim.scale)
        canvas.translate(-w / 2f, -h / 2f)

        val textSize = paint.textSize
        if (!layer.sticker && layer.look == TextLook.BOX) {
            val padX = textSize * 0.45f
            val padY = textSize * 0.22f
            boxPaint.color = argb(layer.accent, alpha)
            boxPaint.maskFilter = null
            val lineW = (0 until fullLayout.lineCount).maxOf { fullLayout.getLineWidth(it) }
            val left = (w - lineW) / 2f - padX
            canvas.drawRoundRect(RectF(left, -padY, left + lineW + padX * 2, h + padY), textSize * 0.3f, textSize * 0.3f, boxPaint)
        }

        if (!layer.sticker) {
            when (layer.look) {
                TextLook.OUTLINE -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = textSize * 0.14f
                    paint.strokeJoin = Paint.Join.ROUND
                    paint.color = argb(layer.accent, alpha)
                    layout.draw(canvas)
                }
                TextLook.SHADOW -> {
                    paint.style = Paint.Style.FILL
                    paint.color = argb(layer.accent, (alpha * 0.75f).toInt())
                    canvas.save()
                    canvas.translate(textSize * 0.06f, textSize * 0.08f)
                    layout.draw(canvas)
                    canvas.restore()
                }
                TextLook.NEON -> {
                    paint.style = Paint.Style.FILL
                    paint.color = argb(layer.accent, alpha)
                    paint.maskFilter = BlurMaskFilter(textSize * 0.28f, BlurMaskFilter.Blur.NORMAL)
                    layout.draw(canvas)
                    layout.draw(canvas)
                    paint.maskFilter = null
                }
                TextLook.PLAIN, TextLook.BOX -> Unit
            }
        }
        paint.style = Paint.Style.FILL
        paint.maskFilter = null
        paint.color = argb(layer.color, alpha)
        layout.draw(canvas)
        canvas.restore()
    }

    /** Hit box of a layer in frame pixels (unanimated). */
    fun bounds(layer: TextLayer, frameW: Int, frameH: Int): RectF {
        val layout = layoutFor(layer, layer.text.ifBlank { " " }, frameW, frameH)
        val w = layout.width.toFloat()
        val h = layout.height.toFloat()
        val pad = paint.textSize * 0.4f
        val cx = layer.x * frameW
        val cy = layer.y * frameH
        return RectF(cx - w / 2f - pad, cy - h / 2f - pad, cx + w / 2f + pad, cy + h / 2f + pad)
    }

    private fun layoutFor(layer: TextLayer, text: String, frameW: Int, frameH: Int): StaticLayout {
        val size = layer.size * frameH
        paint.textSize = size
        paint.typeface = Typeface.create(layer.font.family, if (layer.bold && !layer.sticker) Typeface.BOLD else Typeface.NORMAL)
        val key = "${layer.font}|${layer.bold}|${layer.sticker}|$size|$frameW|$text"
        layouts[key]?.let { return it }
        if (layouts.size > 64) layouts.clear()
        val maxW = (frameW * 0.9f).toInt().coerceAtLeast(1)
        // Width of the widest unwrapped line, capped to the frame.
        val natural = text.split('\n').maxOf { ceil(paint.measureText(it)).toInt() }
        val width = min(natural + 2, maxW).coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.05f)
            .build()
        layouts[key] = layout
        return layout
    }

    private class AnimState(val alpha: Float, val scale: Float, val dy: Float, val chars: Int)

    private fun animState(layer: TextLayer, localMs: Long): AnimState {
        val remaining = layer.durationMs - localMs
        val outAlpha = if (layer.anim != TextAnim.NONE && remaining < OUT_MS) (remaining.toFloat() / OUT_MS).coerceIn(0f, 1f) else 1f
        return when (layer.anim) {
            TextAnim.NONE -> AnimState(1f, 1f, 0f, layer.text.length)
            TextAnim.FADE -> {
                val p = (localMs.toFloat() / IN_MS).coerceIn(0f, 1f)
                AnimState(min(EffectMath.easeOutCubic(p), outAlpha), 1f, 0f, layer.text.length)
            }
            TextAnim.POP -> {
                val p = (localMs.toFloat() / IN_MS).coerceIn(0f, 1f)
                AnimState(min(EffectMath.easeOutCubic(p), outAlpha), EffectMath.lerp(0.5f, 1f, EffectMath.easeOutBack(p)), 0f, layer.text.length)
            }
            TextAnim.SLIDE_UP -> {
                val p = (localMs.toFloat() / SLIDE_MS).coerceIn(0f, 1f)
                AnimState(min(EffectMath.easeOutCubic(p), outAlpha), 1f, 0.06f * (1f - EffectMath.easeOutCubic(p)), layer.text.length)
            }
            TextAnim.TYPEWRITER -> {
                val total = min(layer.text.length * CHAR_MS, min(TYPE_MAX_MS, layer.durationMs * 2 / 3))
                val p = if (total <= 0) 1f else (localMs.toFloat() / total).coerceIn(0f, 1f)
                AnimState(outAlpha, 1f, 0f, ceil(layer.text.length * p).toInt().coerceIn(1, layer.text.length))
            }
        }
    }

    /** Animation bucket: equal keys draw an identical frame (lets the export skip redraws). */
    fun stateKey(layer: TextLayer, localMs: Long): Long {
        val inWindow = when (layer.anim) {
            TextAnim.NONE -> 0L
            TextAnim.FADE, TextAnim.POP -> IN_MS
            TextAnim.SLIDE_UP -> SLIDE_MS
            TextAnim.TYPEWRITER -> TYPE_MAX_MS
        }
        val remaining = layer.durationMs - localMs
        return when {
            localMs < inWindow -> localMs / FRAME_MS
            layer.anim != TextAnim.NONE && remaining < OUT_MS -> 100_000L + remaining / FRAME_MS
            else -> -1L
        }
    }

    private fun argb(color: Long, alpha: Int): Int {
        val c = color.toInt()
        val a = ((c ushr 24) and 0xFF) * alpha / 255
        return (a shl 24) or (c and 0x00FFFFFF)
    }

    companion object {
        const val IN_MS = 280L
        const val OUT_MS = 220L
        const val SLIDE_MS = 380L
        const val CHAR_MS = 55L
        const val TYPE_MAX_MS = 1_600L
        private const val FRAME_MS = 16L
    }
}

/** Export overlay drawing the project's text layers against the output timeline. */
class StudioTextOverlay(
    width: Int,
    height: Int,
    layers: List<TextLayer>,
    timelineOffsetUs: Long = 0L,
) : TimelineBitmapOverlay(width, height, timelineOffsetUs) {

    private val layers = layers.filter { it.text.isNotBlank() && it.endMs > it.startMs }.sortedBy { it.startMs }
    private val painter = StudioTextPainter()

    override fun stateKey(timelineMs: Long): Any {
        val active = layers.filter { timelineMs >= it.startMs && timelineMs < it.endMs }
        if (active.isEmpty()) return EMPTY
        return active.map { it.id to painter.stateKey(it, timelineMs - it.startMs) }
    }

    override fun draw(target: Bitmap, timelineMs: Long) {
        painter.drawAll(Canvas(target), layers, width, height, timelineMs)
    }

    private companion object {
        val EMPTY = Any()
    }
}
