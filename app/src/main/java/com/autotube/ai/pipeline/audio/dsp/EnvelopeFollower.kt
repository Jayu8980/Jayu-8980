package com.autotube.ai.pipeline.audio.dsp

import kotlin.math.abs
import kotlin.math.exp

/**
 * Peak envelope follower with attack, hold and release, used to drive sidechain ducking.
 * [rateHz] is the rate at which [process] is called (audio sample rate or a control rate).
 */
class EnvelopeFollower(
    rateHz: Float,
    attackMs: Float = 40f,
    releaseMs: Float = 350f,
    holdMs: Float = 150f,
) {
    private val attackCoef = coef(rateHz, attackMs)
    private val releaseCoef = coef(rateHz, releaseMs)
    private val holdSteps = (holdMs / 1000f * rateHz).toInt().coerceAtLeast(0)
    private var envelope = 0f
    private var holdCounter = 0

    val value: Float get() = envelope

    fun reset() {
        envelope = 0f
        holdCounter = 0
    }

    /** Feeds a level (absolute sample or RMS) and returns the smoothed envelope. */
    fun process(input: Float): Float {
        val x = abs(input)
        if (x >= envelope) {
            envelope = x + (envelope - x) * attackCoef
            holdCounter = holdSteps
        } else if (holdCounter > 0) {
            holdCounter--
        } else {
            envelope = x + (envelope - x) * releaseCoef
        }
        return envelope
    }

    private fun coef(rate: Float, ms: Float): Float =
        if (ms <= 0f) 0f else exp(-1.0 / (ms / 1000.0 * rate)).toFloat()
}
