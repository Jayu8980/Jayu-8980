package com.autotube.ai.pipeline.timeline

import com.autotube.ai.core.model.EditStyle
import com.autotube.ai.core.model.SceneRole
import com.autotube.ai.core.model.Script
import com.autotube.ai.pipeline.script.Seeded
import kotlin.math.roundToLong

/**
 * The silence model between scene voice files. Shared by [TimelineBuilderImpl] (exact layout) and the
 * length manager (estimates), so the length check measures exactly what the timeline will produce.
 *
 * Variation is deterministic (seeded by the script title and scene number), so rebuilding the
 * timeline for the same script always gives the same gaps.
 */
internal object VoiceGapModel {

    const val SHORTS_TAIL_MS = 1_200L
    const val LONG_FORM_TAIL_MS = 1_800L

    fun leadInMs(script: Script, isShorts: Boolean): Long =
        if (isShorts) Seeded.between(250, 320, script.title, "lead") else Seeded.between(280, 400, script.title, "lead")

    fun tailMs(isShorts: Boolean): Long = if (isShorts) SHORTS_TAIL_MS else LONG_FORM_TAIL_MS

    /** Silence after scene at [index] (0-based) before the next scene; 0 for the last scene. */
    fun gapAfterMs(script: Script, index: Int, isShorts: Boolean): Long {
        val scenes = script.scenes
        if (index < 0 || index >= scenes.size - 1) return 0
        val cur = scenes[index]
        val next = scenes[index + 1]
        var gap = Seeded.between(280, 520, script.title, cur.sceneNumber, "gap").toDouble()
        if (next.role == SceneRole.CTA) gap += 110
        if (cur.role == SceneRole.HOOK) gap += 60
        if (next.sectionTitle.isNotBlank() && !next.sectionTitle.equals(cur.sectionTitle, ignoreCase = true)) gap += 70
        if (cur.intensity >= 8) gap += 80
        val fast = isShorts || script.style == EditStyle.FAST_SHORTS
        if (fast) gap *= 0.72
        val bounds = if (fast) 180L..480L else 280L..700L
        return gap.roundToLong().coerceIn(bounds)
    }

    /** Lead-in + all inter-scene gaps + tail for [script]. */
    fun totalSilenceMs(script: Script, isShorts: Boolean): Long {
        if (script.scenes.isEmpty()) return 0
        var total = leadInMs(script, isShorts) + tailMs(isShorts)
        for (i in 0 until script.scenes.size - 1) total += gapAfterMs(script, i, isShorts)
        return total
    }

    /** Typical silence overhead for a script that is not written yet. */
    fun typicalOverheadMs(sceneCount: Int, isShorts: Boolean): Long {
        val n = sceneCount.coerceAtLeast(1)
        return if (isShorts) 285L + (n - 1) * 300L + SHORTS_TAIL_MS else 340L + (n - 1) * 430L + LONG_FORM_TAIL_MS
    }
}
