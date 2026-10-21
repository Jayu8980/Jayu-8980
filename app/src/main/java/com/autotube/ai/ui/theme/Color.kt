package com.autotube.ai.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Brand palette for the always-dark AutoTube AI look. */
object AutoTubeColors {
    val Background = Color(0xFF0E0F13)
    val Surface = Color(0xFF17181D)
    val SurfaceLow = Color(0xFF131418)
    val SurfaceHigh = Color(0xFF1E2026)
    val SurfaceHighest = Color(0xFF262830)
    val SurfaceVariant = Color(0xFF23252D)
    val Outline = Color(0xFF3B3E48)
    val OutlineVariant = Color(0xFF2A2C34)

    val Red = Color(0xFFFF3D57)
    val RedDeep = Color(0xFFB3172E)
    val Orange = Color(0xFFFF7A45)
    val Amber = Color(0xFFFFD54F)
    val Teal = Color(0xFF26C6B0)
    val Green = Color(0xFF4ADE80)
    val Blue = Color(0xFF5B9DFF)
    val Purple = Color(0xFFB388FF)
    val Grey = Color(0xFF8C8F9C)

    val TextPrimary = Color(0xFFF2F3F7)
    val TextSecondary = Color(0xFFA6A9B6)
    val Error = Color(0xFFFF6B6B)

    /** The big "Create Video" / "Approve & Upload" call-to-action gradient. */
    val CtaGradient: Brush = Brush.horizontalGradient(listOf(Red, Orange))

    /** Disabled state of [CtaGradient]. */
    val CtaGradientDisabled: Brush = Brush.horizontalGradient(listOf(SurfaceHighest, SurfaceHigh))
}
