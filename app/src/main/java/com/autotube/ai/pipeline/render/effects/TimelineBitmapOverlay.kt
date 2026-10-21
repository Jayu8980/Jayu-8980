package com.autotube.ai.pipeline.render.effects

import android.graphics.Bitmap
import android.graphics.Color
import androidx.media3.effect.BitmapOverlay

/**
 * Base for overlays that draw against the absolute video timeline.
 *
 * Composition-level overlays use [timelineOffsetUs] = 0; per-item fallback overlays pass the item's
 * start on the timeline. Either way the first timestamp seen is latched, so offset per-item
 * timestamps never shift the animation.
 *
 * Subclasses draw into one of two reusable full-frame bitmaps; the returned reference only changes
 * when the content changes, which lets Media3 skip re-uploading the texture.
 */
abstract class TimelineBitmapOverlay(
    protected val width: Int,
    protected val height: Int,
    private val timelineOffsetUs: Long,
) : BitmapOverlay() {

    private val clock = LatchedClock()
    private var buffers: Array<Bitmap>? = null
    private var front = 0
    private var lastKey: Any? = null

    /** Cache key for the visible state at [timelineMs]; equal keys mean an identical frame. */
    protected abstract fun stateKey(timelineMs: Long): Any

    /** Draws the state for [timelineMs] into the cleared [target]. */
    protected abstract fun draw(target: Bitmap, timelineMs: Long)

    final override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val timelineMs = (timelineOffsetUs + clock.elapsedUs(presentationTimeUs)) / 1000L
        val bufs = buffers ?: Array(2) { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
            .also { buffers = it }
        val key = stateKey(timelineMs)
        if (key == lastKey) return bufs[front]
        front = 1 - front
        val target = bufs[front]
        target.eraseColor(Color.TRANSPARENT)
        draw(target, timelineMs)
        lastKey = key
        return target
    }

    override fun release() {
        super.release()
        buffers?.forEach { if (!it.isRecycled) it.recycle() }
        buffers = null
        lastKey = null
    }
}
