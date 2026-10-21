package com.autotube.ai.pipeline.orchestrator

import com.autotube.ai.core.model.PipelineStep

/** Share of the overall progress bar each step owns. UPLOAD is tracked separately. */
internal object StepWeights {

    private val weights: Map<PipelineStep, Float> = mapOf(
        PipelineStep.RESEARCH to 0.05f,
        PipelineStep.SCRIPT to 0.08f,
        PipelineStep.LENGTH_CHECK to 0.04f,
        PipelineStep.VOICE to 0.15f,
        PipelineStep.CAPTIONS to 0.05f,
        PipelineStep.VISUALS to 0.20f,
        PipelineStep.EDIT_PLAN to 0.05f,
        PipelineStep.MUSIC to 0.06f,
        PipelineStep.RENDER to 0.22f,
        PipelineStep.QUALITY_CHECK to 0.07f,
        PipelineStep.SEO to 0.03f,
        PipelineStep.UPLOAD to 0f,
    )

    private val total: Float = weights.values.sum()

    fun weightOf(step: PipelineStep): Float = weights[step] ?: 0f

    /** Overall 0..1 progress when [step] is [fraction] done and every earlier step is complete. */
    fun overall(step: PipelineStep, fraction: Float): Float {
        if (step == PipelineStep.UPLOAD) return 1f
        val before = PipelineStep.entries
            .filter { it.ordinal < step.ordinal }
            .sumOf { weightOf(it).toDouble() }
            .toFloat()
        val value = (before + weightOf(step) * fraction.coerceIn(0f, 1f)) / total
        return value.coerceIn(0f, 1f)
    }
}
