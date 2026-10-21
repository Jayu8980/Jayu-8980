package com.autotube.ai.pipeline.render.effects

import android.graphics.Matrix
import androidx.media3.common.Effect
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.RgbMatrix
import com.autotube.ai.core.model.TransitionType
import kotlin.math.abs
import kotlin.math.min

/**
 * Transition timing for one media item. Transitions are built from per-item fades and moves (no
 * overlapping clips): the incoming half plays at the start of a shot, the outgoing half at the end of
 * the previous shot.
 *
 * All times are relative to the start of this media item.
 */
data class TransitionPlan(
    val incoming: TransitionType,
    val incomingMs: Long,
    val outgoing: TransitionType,
    val outgoingMs: Long,
    /** Fade the very end of the video to black. */
    val fadeOutAtEnd: Boolean,
    val itemDurationMs: Long,
    /** Direction of a whip: +1 content moves left-to-right, -1 right-to-left. */
    val whipDirection: Float = 1f,
)

/** Builds the effects for a [TransitionPlan]. */
object TransitionEffect {

    const val END_FADE_MS = 500L
    private const val MAX_WHIP_MS = 260L
    private const val MAX_ZOOM_MS = 380L

    /**
     * Returns the geometric part (zoom / whip) to place right after the camera motion. Null when the
     * plan has no geometric transition.
     */
    fun motion(plan: TransitionPlan): MatrixTransformation? {
        val hasIn = plan.incoming == TransitionType.ZOOM || plan.incoming == TransitionType.WHIP
        val hasOut = plan.outgoing == TransitionType.ZOOM || plan.outgoing == TransitionType.WHIP
        return if (hasIn || hasOut) TransitionMotion(plan) else null
    }

    /** Returns the brightness part (fade / dip to black / end fade), applied after colour grading. */
    fun fade(plan: TransitionPlan): RgbMatrix? {
        val hasIn = plan.incoming.isFade()
        val hasOut = plan.outgoing.isFade()
        return if (hasIn || hasOut || plan.fadeOutAtEnd) TransitionFade(plan) else null
    }

    fun effects(plan: TransitionPlan): List<Effect> = listOfNotNull(motion(plan), fade(plan))

    internal fun TransitionType.isFade(): Boolean = this == TransitionType.FADE || this == TransitionType.DIP_TO_BLACK

    /** Clamps a transition so it never takes more than 40 % of the item. */
    internal fun clampMs(ms: Long, itemMs: Long): Long = min(ms.coerceAtLeast(0), (itemMs * 0.4f).toLong())

    private class TransitionMotion(private val plan: TransitionPlan) : MatrixTransformation {
        private val clock = LatchedClock()

        override fun getMatrix(presentationTimeUs: Long): Matrix {
            val t = clock.elapsedMs(presentationTimeUs)
            var scale = 1f
            var tx = 0f
            val inMs = clampMs(plan.incomingMs, plan.itemDurationMs).toFloat()
            when (plan.incoming) {
                TransitionType.ZOOM -> {
                    val d = min(inMs, MAX_ZOOM_MS.toFloat())
                    if (d > 0f && t < d) scale *= EffectMath.lerp(1.25f, 1f, EffectMath.easeOutCubic(t / d))
                }
                TransitionType.WHIP -> {
                    val d = min(inMs, MAX_WHIP_MS.toFloat())
                    if (d > 0f && t < d) {
                        val k = 1f - EffectMath.easeOutCubic(t / d)
                        tx += -plan.whipDirection * 0.35f * k
                        scale *= 1f + 0.08f * k
                    }
                }
                TransitionType.CUT, TransitionType.FADE, TransitionType.DIP_TO_BLACK -> Unit
            }
            val outMs = clampMs(plan.outgoingMs, plan.itemDurationMs).toFloat()
            val untilEnd = plan.itemDurationMs - t
            when (plan.outgoing) {
                TransitionType.ZOOM -> {
                    val d = min(outMs, MAX_ZOOM_MS.toFloat())
                    if (d > 0f && untilEnd < d) scale *= EffectMath.lerp(1f, 1.15f, EffectMath.easeInCubic(1f - untilEnd / d))
                }
                TransitionType.WHIP -> {
                    val d = min(outMs, MAX_WHIP_MS.toFloat())
                    if (d > 0f && untilEnd < d) {
                        val k = EffectMath.easeInCubic(1f - untilEnd / d)
                        tx += plan.whipDirection * 0.35f * k
                        scale *= 1f + 0.08f * k
                    }
                }
                TransitionType.CUT, TransitionType.FADE, TransitionType.DIP_TO_BLACK -> Unit
            }
            // Scale up by the translation so no black edge is revealed during the whip.
            scale *= 1f + abs(tx)
            return Matrix().apply {
                setScale(scale, scale)
                postTranslate(tx, 0f)
            }
        }
    }

    private class TransitionFade(private val plan: TransitionPlan) : RgbMatrix {
        private val clock = LatchedClock()

        override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
            val t = clock.elapsedMs(presentationTimeUs)
            var f = 1f
            val inMs = clampMs(plan.incomingMs, plan.itemDurationMs).toFloat()
            if (plan.incoming.isFade() && inMs > 0f && t < inMs) {
                val floor = floorOf(plan.incoming)
                f = minOf(f, EffectMath.lerp(floor, 1f, EffectMath.easeOutCubic(t / inMs)))
            }
            val untilEnd = plan.itemDurationMs - t
            val outMs = clampMs(plan.outgoingMs, plan.itemDurationMs).toFloat()
            if (plan.outgoing.isFade() && outMs > 0f && untilEnd < outMs) {
                val floor = floorOf(plan.outgoing)
                f = minOf(f, EffectMath.lerp(floor, 1f, EffectMath.easeInCubic(untilEnd.coerceAtLeast(0f) / outMs)))
            }
            if (plan.fadeOutAtEnd) {
                val d = min(END_FADE_MS, (plan.itemDurationMs * 0.4f).toLong()).toFloat()
                if (d > 0f && untilEnd < d) f = minOf(f, EffectMath.easeInOutCubic(untilEnd.coerceAtLeast(0f) / d))
            }
            val k = f.coerceIn(0f, 1f)
            return floatArrayOf(
                k, 0f, 0f, 0f,
                0f, k, 0f, 0f,
                0f, 0f, k, 0f,
                0f, 0f, 0f, 1f,
            )
        }

        /** A plain fade dips softly; a dip-to-black goes fully black at the cut. */
        private fun floorOf(type: TransitionType): Float = if (type == TransitionType.DIP_TO_BLACK) 0f else 0.3f
    }
}
