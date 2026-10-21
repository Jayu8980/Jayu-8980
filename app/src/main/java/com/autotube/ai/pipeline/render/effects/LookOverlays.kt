package com.autotube.ai.pipeline.render.effects

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.media3.common.OverlaySettings
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.StaticOverlaySettings
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Shared bitmaps for the look overlays. They are generated at half resolution and drawn scaled 2x
 * (smooth gradients and grain do not need full resolution), and shared by every overlay instance of one
 * render, so the per-item fallback does not multiply memory. Call [release] after the export.
 */
class LookOverlayAssets(private val width: Int, private val height: Int) {

    private val assetW = (width / 2).coerceAtLeast(2)
    private val assetH = (height / 2).coerceAtLeast(2)

    private val vignetteLazy = lazy { createVignette() }
    private val grainLazy = lazy { createGrain(GRAIN_FRAMES) }
    val vignette: Bitmap by vignetteLazy
    val grainFrames: List<Bitmap> by grainLazy

    /** Generates the bitmaps up front (call off the GL thread). */
    fun prepare(vignette: Boolean, grain: Boolean) {
        if (vignette) vignetteLazy.value
        if (grain) grainLazy.value
    }

    /** Overlay settings that stretch a half-size bitmap over the full frame. */
    val fullFrameSettings: OverlaySettings by lazy {
        StaticOverlaySettings.Builder()
            .setScale(width.toFloat() / assetW, height.toFloat() / assetH)
            .build()
    }

    private fun createVignette(): Bitmap {
        val bmp = Bitmap.createBitmap(assetW, assetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = assetW / 2f
        val cy = assetH / 2f
        val radius = hypot(cx, cy)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(40, 0, 0, 0), Color.argb(90, 0, 0, 0)),
                floatArrayOf(0f, 0.55f, 0.8f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, assetW.toFloat(), assetH.toFloat(), paint)
        return bmp
    }

    private fun createGrain(count: Int): List<Bitmap> {
        val random = Random(GRAIN_SEED)
        val pixels = IntArray(assetW * assetH)
        return List(count) {
            for (i in pixels.indices) {
                val v = 96 + random.nextInt(64)
                val a = 8 + random.nextInt(12)
                pixels[i] = Color.argb(a, v, v, v)
            }
            Bitmap.createBitmap(assetW, assetH, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, assetW, 0, 0, assetW, assetH)
            }
        }
    }

    fun release() {
        if (vignetteLazy.isInitialized()) vignetteLazy.value.let { if (!it.isRecycled) it.recycle() }
        if (grainLazy.isInitialized()) grainLazy.value.forEach { if (!it.isRecycled) it.recycle() }
    }

    companion object {
        const val GRAIN_FRAMES = 5
        private const val GRAIN_SEED = 0x5EED
    }
}

/** Static radial vignette (edges ~35 % darker). */
class VignetteOverlay(private val assets: LookOverlayAssets) : BitmapOverlay() {
    override fun getBitmap(presentationTimeUs: Long): Bitmap = assets.vignette
    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = assets.fullFrameSettings
}

/** Low-alpha film grain: pre-generated noise frames cycled at ~24 fps. */
class FilmGrainOverlay(private val assets: LookOverlayAssets) : BitmapOverlay() {
    private val clock = LatchedClock()

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val frames = assets.grainFrames
        val bucket = (clock.elapsedUs(presentationTimeUs) / FRAME_US).toInt()
        return frames[bucket.mod(frames.size)]
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = assets.fullFrameSettings

    private companion object {
        const val FRAME_US = 41_667L
    }
}
